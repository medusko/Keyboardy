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

    /// Opens macOS System Settings directly to Privacy & Security -> Accessibility
    public func openAccessibilitySettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility") {
            NSWorkspace.shared.open(url)
        }
    }

    /// Opens System Settings to Privacy & Security -> Accessibility without triggering modal alert dialogs.
    @discardableResult
    public func openAndRefreshAccessibilityPermission() -> Bool {
        openAccessibilitySettings()
        return isAccessibilityTrusted(promptIfNeeded: false)
    }

    /// Requests Accessibility permission from macOS and opens System Settings so the user can easily toggle Keyboardy.
    @discardableResult
    public func requestAccessibilityPermission() -> Bool {
        openAccessibilitySettings()
        return isAccessibilityTrusted(promptIfNeeded: false)
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

        let myPid = ProcessInfo.processInfo.processIdentifier
        let resolvedApp: NSRunningApplication? = targetApp ?? NSWorkspace.shared.runningApplications.first(where: {
            $0.processIdentifier != myPid && $0.activationPolicy == .regular && !$0.isTerminated
        })

        guard insertMode == .autoPasteIntoApp, let app = resolvedApp else {
            return .copiedToClipboard(reason: "Copied to Clipboard — press ⌘V in any app!")
        }

        let appName = app.localizedName ?? "chat app"

        // Hide the Keyboardy popover/panel and return focus to the target chat app
        onPanelShouldHideBeforePaste()
        app.activate(options: [.activateIgnoringOtherApps])

        // Wait until the target application actually becomes frontmost (up to 400ms) + 60ms settle delay
        for _ in 0..<20 {
            if NSWorkspace.shared.frontmostApplication?.processIdentifier == app.processIdentifier {
                break
            }
            try? await Task.sleep(nanoseconds: 20_000_000)
        }
        try? await Task.sleep(nanoseconds: 60_000_000)

        let trusted = isAccessibilityTrusted(promptIfNeeded: false)

        if clearTargetQueryBeforePaste && trusted {
            // Select all & delete the temporary query text in the focused chat input before pasting
            Self.synthesizeSelectAllAndDelete(targetPid: app.processIdentifier)
            try? await Task.sleep(nanoseconds: 40_000_000)
        }

        // Synthesize Cmd+V (Paste) with full modifier + key hold sequence delivered to HID tap and target PID
        Self.synthesizePasteKeystroke(targetPid: app.processIdentifier)

        if restoreClipboardAfterPaste, let previousText = savedTextClipboard {
            // Wait 3.0s before restoring old text clipboard so Slack/Discord/Messages/browsers finish reading the pasted image/GIF
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            let pb = NSPasteboard.general
            pb.clearContents()
            pb.setString(previousText, forType: .string)
        }

        if trusted {
            return .autoPastedIntoApp(appName: appName)
        } else {
            return .copiedToClipboard(
                reason: "Copied! Toggle switch ON next to Keyboardy in System Settings for 1-step auto-paste."
            )
        }
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

        // 1. File URL (standard modern macOS pasteboard object)
        pb.writeObjects([fileURL as NSURL])

        // 2. Legacy filenames array for desktop apps (Slack, Discord desktop)
        pb.setPropertyList([fileURL.path], forType: NSPasteboard.PasteboardType("NSFilenamesPboardType"))

        if ext == "gif" {
            // 3. Raw animated GIF bytes
            pb.setData(data, forType: NSPasteboard.PasteboardType("com.compuserve.gif"))
            pb.setData(data, forType: NSPasteboard.PasteboardType("image/gif"))
        } else {
            // 4. Static PNG / JPEG
            pb.setData(data, forType: .png)
            pb.setData(data, forType: NSPasteboard.PasteboardType("image/png"))
            if let nsImage = NSImage(data: data),
               let tiff = nsImage.tiffRepresentation {
                pb.setData(tiff, forType: .tiff)
            }
        }
    }

    /// Synthesizes a true hardware-like ⌘V keystroke sequence (Cmd down -> V down -> 35ms hold -> V up -> Cmd up)
    /// dispatched exclusively via the system HID event tap (.cghidEventTap) to the active frontmost window.
    /// Note: We do NOT dual-dispatch to postToPid because sending to both HID and PID produces duplicate
    /// keystrokes in Chromium, Electron, Safari, and AppKit applications.
    public nonisolated static func synthesizePasteKeystroke(targetPid: pid_t? = nil) {
        guard let src = CGEventSource(stateID: .combinedSessionState) else { return }

        let cmdKeyCode = CGKeyCode(55) // kVK_Command
        let vKeyCode = CGKeyCode(kVK_ANSI_V) // 9

        // 1. Command down
        if let cmdDown = CGEvent(keyboardEventSource: src, virtualKey: cmdKeyCode, keyDown: true) {
            cmdDown.flags = .maskCommand
            cmdDown.post(tap: .cghidEventTap)
        }

        usleep(15_000) // 15ms delay

        // 2. 'V' down
        if let vDown = CGEvent(keyboardEventSource: src, virtualKey: vKeyCode, keyDown: true) {
            vDown.flags = .maskCommand
            vDown.post(tap: .cghidEventTap)
        }

        usleep(35_000) // 35ms hold so the target app's event loop registers the keypress

        // 3. 'V' up
        if let vUp = CGEvent(keyboardEventSource: src, virtualKey: vKeyCode, keyDown: false) {
            vUp.flags = .maskCommand
            vUp.post(tap: .cghidEventTap)
        }

        usleep(15_000) // 15ms delay

        // 4. Command up
        if let cmdUp = CGEvent(keyboardEventSource: src, virtualKey: cmdKeyCode, keyDown: false) {
            cmdUp.flags = []
            cmdUp.post(tap: .cghidEventTap)
        }
    }

    /// Synthesizes ⌘A (Select All) followed by Delete with reliable hold delays to clear temporary query text.
    /// Dispatched via the system HID tap (.cghidEventTap) to avoid duplicate key events.
    private nonisolated static func synthesizeSelectAllAndDelete(targetPid: pid_t? = nil) {
        guard let src = CGEventSource(stateID: .combinedSessionState) else { return }

        let cmdKeyCode = CGKeyCode(55) // kVK_Command
        let aKeyCode = CGKeyCode(kVK_ANSI_A) // 0
        let deleteKeyCode = CGKeyCode(kVK_Delete) // 51

        // ⌘A
        if let cmdDown = CGEvent(keyboardEventSource: src, virtualKey: cmdKeyCode, keyDown: true) {
            cmdDown.flags = .maskCommand
            cmdDown.post(tap: .cghidEventTap)
        }
        usleep(10_000)
        if let aDown = CGEvent(keyboardEventSource: src, virtualKey: aKeyCode, keyDown: true) {
            aDown.flags = .maskCommand
            aDown.post(tap: .cghidEventTap)
        }
        usleep(25_000)
        if let aUp = CGEvent(keyboardEventSource: src, virtualKey: aKeyCode, keyDown: false) {
            aUp.flags = .maskCommand
            aUp.post(tap: .cghidEventTap)
        }
        usleep(10_000)
        if let cmdUp = CGEvent(keyboardEventSource: src, virtualKey: cmdKeyCode, keyDown: false) {
            cmdUp.flags = []
            cmdUp.post(tap: .cghidEventTap)
        }
        usleep(25_000)

        // Delete
        if let delDown = CGEvent(keyboardEventSource: src, virtualKey: deleteKeyCode, keyDown: true) {
            delDown.flags = []
            delDown.post(tap: .cghidEventTap)
        }
        usleep(25_000)
        if let delUp = CGEvent(keyboardEventSource: src, virtualKey: deleteKeyCode, keyDown: false) {
            delUp.flags = []
            delUp.post(tap: .cghidEventTap)
        }
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
