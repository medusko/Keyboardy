import AppKit
import Carbon
import Foundation

@MainActor
public final class TriggerManager {
    public static let shared = TriggerManager()

    public var onHotkeyTriggered: (() -> Void)?
    public var onInlinePrefixTriggered: ((String) -> Void)?

    private var hotKeyRef: EventHotKeyRef?
    private var eventHandlerRef: EventHandlerRef?
    private var globalKeyMonitor: Any?
    private var rollingTypedBuffer: String = ""
    private var lastKeyDownTime: TimeInterval = 0

    private init() {
        installCarbonEventHandlerIfNeeded()
        installGlobalKeyMonitor()
    }

    public func applyCurrentHotkeyFromPrefs() {
        let prefs = KeyboardyPrefs.shared
        unregisterCurrentHotKey()

        let keyCode: UInt32
        let modifiers: UInt32

        switch prefs.hotkeyPreset {
        case .disabled:
            return
        case .custom:
            keyCode = prefs.customKeyCode
            modifiers = prefs.customCarbonModifiers
        default:
            guard let presetCode = prefs.hotkeyPreset.carbonKeyCode else { return }
            keyCode = presetCode
            modifiers = prefs.hotkeyPreset.carbonModifiers
        }

        let hotKeyID = EventHotKeyID(signature: OSType(0x4B424459), id: 1) // 'KBDY'
        var ref: EventHotKeyRef?
        let status = RegisterEventHotKey(
            keyCode,
            modifiers,
            hotKeyID,
            GetApplicationEventTarget(),
            0,
            &ref
        )
        if status == noErr {
            self.hotKeyRef = ref
        }
    }

    /// Converts a key-down NSEvent into Carbon modifiers and a human-readable macOS shortcut label (e.g. "⌃⌥G" or "⌥⇧Space").
    public static func parseShortcutEvent(_ event: NSEvent) -> (keyCode: UInt32, carbonModifiers: UInt32, label: String)? {
        let flags = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        var carbonMods: UInt32 = 0
        var symbols = ""

        if flags.contains(.control) {
            carbonMods |= UInt32(controlKey)
            symbols += "⌃"
        }
        if flags.contains(.option) {
            carbonMods |= UInt32(optionKey)
            symbols += "⌥"
        }
        if flags.contains(.shift) {
            carbonMods |= UInt32(shiftKey)
            symbols += "⇧"
        }
        if flags.contains(.command) {
            carbonMods |= UInt32(cmdKey)
            symbols += "⌘"
        }

        // Require at least one modifier key (Cmd, Option, or Control)
        guard carbonMods != 0 else { return nil }

        let code = UInt32(event.keyCode)
        // Ignore pure modifier key presses
        if [54, 55, 56, 57, 58, 59, 60, 61, 62, 63].contains(Int(code)) {
            return nil
        }

        let keyName: String
        switch Int(code) {
        case kVK_Space: keyName = "Space"
        case kVK_Return: keyName = "↩"
        case kVK_Tab: keyName = "⇥"
        case kVK_Delete: keyName = "⌫"
        default:
            let chars = (event.charactersIgnoringModifiers ?? "").uppercased().trimmingCharacters(in: .whitespacesAndNewlines)
            keyName = chars.isEmpty ? "Key\(code)" : chars
        }

        return (code, carbonMods, "\(symbols)\(keyName)")
    }

    private func unregisterCurrentHotKey() {
        if let ref = hotKeyRef {
            UnregisterEventHotKey(ref)
            hotKeyRef = nil
        }
    }

    private func installCarbonEventHandlerIfNeeded() {
        guard eventHandlerRef == nil else { return }
        var eventType = EventTypeSpec(
            eventClass: OSType(kEventClassKeyboard),
            eventKind: UInt32(kEventHotKeyPressed)
        )

        let callback: EventHandlerUPP = { _, _, _ in
            Task { @MainActor in
                if !KeyboardyPrefs.shared.isRecordingCustomShortcut {
                    TriggerManager.shared.onHotkeyTriggered?()
                }
            }
            return noErr
        }

        InstallEventHandler(
            GetApplicationEventTarget(),
            callback,
            1,
            &eventType,
            nil,
            &eventHandlerRef
        )
    }

    private func installGlobalKeyMonitor() {
        globalKeyMonitor = NSEvent.addGlobalMonitorForEvents(matching: .keyDown) { [weak self] event in
            Task { @MainActor in
                self?.handleGlobalKeyDown(event)
            }
        }
    }

    private func handleGlobalKeyDown(_ event: NSEvent) {
        let prefs = KeyboardyPrefs.shared
        guard prefs.inlineTriggerEnabled else {
            rollingTypedBuffer = ""
            return
        }

        let prefix = prefs.inlineTriggerPrefix
        guard !prefix.isEmpty else { return }

        let now = ProcessInfo.processInfo.systemUptime
        if lastKeyDownTime > 0 && (now - lastKeyDownTime) > 5.0 {
            rollingTypedBuffer = ""
        }
        lastKeyDownTime = now

        let flags = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        if !flags.intersection([.command, .control]).isEmpty {
            rollingTypedBuffer = ""
            return
        }

        if event.keyCode == 51 { // Backspace
            if !rollingTypedBuffer.isEmpty {
                rollingTypedBuffer.removeLast()
            }
            return
        }

        // Reset on Return (36), Tab (48), Escape (53), Keypad Enter (76), or Arrow Keys (123..126)
        if [36, 48, 53, 76, 123, 124, 125, 126].contains(Int(event.keyCode)) {
            rollingTypedBuffer = ""
            return
        }

        guard let chars = event.charactersIgnoringModifiers, !chars.isEmpty else { return }
        rollingTypedBuffer.append(chars)
        if rollingTypedBuffer.count > 80 {
            rollingTypedBuffer = String(rollingTypedBuffer.suffix(80))
        }

        if rollingTypedBuffer.hasSuffix(prefix) {
            rollingTypedBuffer = ""
            onInlinePrefixTriggered?("")
        }
    }
}
