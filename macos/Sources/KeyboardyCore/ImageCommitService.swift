import AppKit
import ApplicationServices
import Carbon
import Foundation

public enum MacCommitOutcome: Equatable {
    case autoPastedIntoApp(appName: String)
    case copiedToClipboard(reason: String)
    case downloadFailed
}

@MainActor
public final class ImageCommitService {
    public static let shared = ImageCommitService()

    private init() {}

    /// Checks whether the process has macOS Accessibility trust (required for synthesizing ⌘V and reading focused AX text).
    public func isAccessibilityTrusted(promptIfNeeded: Bool = false) -> Bool {
        if promptIfNeeded {
            let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
            return AXIsProcessTrustedWithOptions(options)
        }
        return AXIsProcessTrusted()
    }

    /// Reads either selected text or short single-line input text from the currently focused UI element in the target app.
    public func readFocusedOrSelectedText(from app: NSRunningApplication?) -> (text: String, wasFullFieldValue: Bool)? {
        guard isAccessibilityTrusted(promptIfNeeded: false),
              let pid = app?.processIdentifier else {
            return nil
        }

        let appElement = AXUIElementCreateApplication(pid)
        var focusedObj: CFTypeRef?
        let err = AXUIElementCopyAttributeValue(appElement, kAXFocusedUIElementAttribute as CFString, &focusedObj)
        guard err == .success, let focusedElement = focusedObj else {
            return nil
        }

        let axElement = focusedElement as! AXUIElement

        // 1. Check if the user highlighted specific text first
        var selectedObj: CFTypeRef?
        if AXUIElementCopyAttributeValue(axElement, kAXSelectedTextAttribute as CFString, &selectedObj) == .success,
           let selectedStr = selectedObj as? String {
            let trimmed = selectedStr.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty && trimmed.count <= 60 && !trimmed.contains("\n") {
                return (trimmed, false)
            }
        }

        // 2. Otherwise check if the focused text box has a short single-line query (like Android Workflow 1)
        var valueObj: CFTypeRef?
        if AXUIElementCopyAttributeValue(axElement, kAXValueAttribute as CFString, &valueObj) == .success,
           let valueStr = valueObj as? String {
            let trimmed = valueStr.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty && trimmed.count <= 60 && !trimmed.contains("\n") {
                return (trimmed, true)
            }
        }

        return nil
    }

    /// Downloads the image/GIF, prepares the temp file + NSPasteboard payload, and either auto-pastes into `targetApp` or copies to clipboard.
    public func downloadAndInsertImage(
        item: ImageResult,
        targetApp: NSRunningApplication?,
        insertMode: InsertBehaviorMode,
        clearTargetQueryBeforePaste: Bool,
        restoreClipboardAfterPaste: Bool,
        onPanelShouldHideBeforePaste: @escaping @MainActor () -> Void
    ) async -> MacCommitOutcome {
        guard let rawData = await Self.downloadImageWithFallback(item: item) else {
            return .downloadFailed
        }

        let prepared = Self.prepareImageDataAndExtension(rawData: rawData)
        guard let fileURL = Self.saveToSharedTempDirectory(data: prepared.data, ext: prepared.ext) else {
            return .downloadFailed
        }

        let savedTextClipboard = NSPasteboard.general.string(forType: .string)

        Self.writeToPasteboard(fileURL: fileURL, data: prepared.data, mimeType: prepared.mimeType, ext: prepared.ext)

        guard insertMode == .autoPasteIntoApp, let app = targetApp else {
            return .copiedToClipboard(reason: "Copied to Clipboard — press ⌘V in any app!")
        }

        let appName = app.localizedName ?? "chat app"

        // Hide the Keyboardy popover/panel and return focus to the target chat app
        onPanelShouldHideBeforePaste()
        app.activate(options: [.activateIgnoringOtherApps])

        // Wait until the target application actually becomes frontmost (up to 450ms) + 80ms settle delay
        for _ in 0..<18 {
            if NSWorkspace.shared.frontmostApplication?.processIdentifier == app.processIdentifier {
                break
            }
            try? await Task.sleep(nanoseconds: 25_000_000)
        }
        try? await Task.sleep(nanoseconds: 85_000_000)

        let trusted = isAccessibilityTrusted(promptIfNeeded: false)
        if !trusted {
            // Prompt macOS to grant Accessibility permission for the current binary
            _ = isAccessibilityTrusted(promptIfNeeded: true)
        }

        if clearTargetQueryBeforePaste && trusted {
            // Select all & delete the temporary query text in the focused chat input before pasting
            Self.synthesizeKeyStroke(keyCode: CGKeyCode(kVK_ANSI_A), flags: .maskCommand)
            try? await Task.sleep(nanoseconds: 40_000_000)
            Self.synthesizeKeyStroke(keyCode: CGKeyCode(kVK_Delete), flags: [])
            try? await Task.sleep(nanoseconds: 40_000_000)
        }

        // Synthesize Cmd+V (Paste) via CGEvent (and AppleScript System Events fallback if needed)
        Self.synthesizeKeyStroke(keyCode: CGKeyCode(kVK_ANSI_V), flags: .maskCommand)
        if !trusted {
            Self.tryAppleScriptPasteFallback()
        }

        if restoreClipboardAfterPaste, let previousText = savedTextClipboard {
            // Wait 3.0s before restoring old text clipboard so Slack/Discord/Messages/browsers finish reading the pasted image/GIF
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            let pb = NSPasteboard.general
            pb.clearContents()
            pb.setString(previousText, forType: .string)
        }

        return .autoPastedIntoApp(appName: appName)
    }

    /// Downloads image bytes for drag-and-drop or local preview caching.
    public nonisolated static func downloadImageWithFallback(item: ImageResult) async -> Data? {
        if let fullData = await downloadBytes(urlStr: item.fullImageUrl), fullData.count > 64 {
            return fullData
        }
        if item.thumbnailUrl != item.fullImageUrl,
           let thumbData = await downloadBytes(urlStr: item.thumbnailUrl), thumbData.count > 64 {
            return thumbData
        }
        return nil
    }

    public nonisolated static func prepareImageDataAndExtension(rawData: Data) -> (data: Data, mimeType: String, ext: String) {
        let (mimeType, ext) = SearchParsers.detectImageMimeTypeAndExtension(rawData)

        // Preserve animated GIFs byte-for-byte
        if mimeType == "image/gif" {
            return (rawData, "image/gif", "gif")
        }

        // Transcode static WebP to PNG for universal macOS pasteboard compatibility
        if mimeType == "image/webp" {
            if let nsImage = NSImage(data: rawData),
               let tiffData = nsImage.tiffRepresentation,
               let bitmap = NSBitmapImageRep(data: tiffData),
               let pngData = bitmap.representation(using: .png, properties: [:]) {
                return (pngData, "image/png", "png")
            }
        }

        return (rawData, mimeType, ext)
    }

    public nonisolated static func saveToSharedTempDirectory(data: Data, ext: String) -> URL? {
        let fm = FileManager.default
        let dir = fm.temporaryDirectory.appendingPathComponent("keyboardy_shared", isDirectory: true)
        do {
            if !fm.fileExists(atPath: dir.path) {
                try fm.createDirectory(at: dir, withIntermediateDirectories: true)
            }
            // Prune cached images older than 10 minutes
            let cutoff = Date().addingTimeInterval(-600)
            if let files = try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.contentModificationDateKey]) {
                for file in files {
                    if let attrs = try? file.resourceValues(forKeys: [.contentModificationDateKey]),
                       let modDate = attrs.contentModificationDate,
                       modDate < cutoff {
                        try? fm.removeItem(at: file)
                    }
                }
            }

            let filename = "keyboardy_\(Int(Date().timeIntervalSince1970 * 1000)).\(ext)"
            let targetURL = dir.appendingPathComponent(filename)
            try data.write(to: targetURL, options: .atomic)
            return targetURL
        } catch {
            return nil
        }
    }

    /// Populates NSPasteboard so both animated GIFs and static images paste cleanly into Slack, Discord, Messages, WhatsApp, and browsers.
    public nonisolated static func writeToPasteboard(fileURL: URL, data: Data, mimeType: String, ext: String) {
        let pb = NSPasteboard.general
        pb.clearContents()

        let gifPasteboardType = NSPasteboard.PasteboardType("com.compuserve.gif")

        if ext == "gif" {
            // Write file URL AND com.compuserve.gif raw bytes (avoiding flattened NSImage bitmap representation)
            pb.writeObjects([fileURL as NSURL])
            pb.setData(data, forType: gifPasteboardType)
        } else {
            // Static PNG / JPEG: write both file URL and native NSImage / PNG pasteboard types
            pb.writeObjects([fileURL as NSURL])
            if ext == "png" {
                pb.setData(data, forType: .png)
            } else if let nsImage = NSImage(data: data),
                      let tiff = nsImage.tiffRepresentation,
                      let rep = NSBitmapImageRep(data: tiff),
                      let pngData = rep.representation(using: .png, properties: [:]) {
                pb.setData(pngData, forType: .png)
            }
        }
    }

    private nonisolated static func synthesizeKeyStroke(keyCode: CGKeyCode, flags: CGEventFlags) {
        let src = CGEventSource(stateID: .hidSystemState)
        if let keyDown = CGEvent(keyboardEventSource: src, virtualKey: keyCode, keyDown: true),
           let keyUp = CGEvent(keyboardEventSource: src, virtualKey: keyCode, keyDown: false) {
            keyDown.flags = flags
            keyUp.flags = flags
            keyDown.post(tap: .cghidEventTap)
            keyUp.post(tap: .cghidEventTap)
        }
    }

    private nonisolated static func tryAppleScriptPasteFallback() {
        let script = NSAppleScript(source: #"tell application "System Events" to keystroke "v" using command down"#)
        var err: NSDictionary?
        script?.executeAndReturnError(&err)
    }

    private nonisolated static func downloadBytes(urlStr: String) async -> Data? {
        guard urlStr.hasPrefix("http"), let url = URL(string: urlStr) else { return nil }
        var req = URLRequest(url: url)
        req.httpMethod = "GET"
        req.timeoutInterval = 5.0
        req.setValue(
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            forHTTPHeaderField: "User-Agent"
        )
        req.setValue("image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8", forHTTPHeaderField: "Accept")

        do {
            let (data, response) = try await URLSession.shared.data(for: req)
            if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
                return nil
            }
            return data
        } catch {
            return nil
        }
    }
}
