import Foundation
import Carbon

public struct ImageResult: Identifiable, Equatable, Hashable, Sendable {
    public let id: String
    public let title: String
    public let thumbnailUrl: String
    public let fullImageUrl: String
    public let isAnimatedGif: Bool
    public let sourceLabel: String

    public init(
        id: String,
        title: String,
        thumbnailUrl: String,
        fullImageUrl: String,
        isAnimatedGif: Bool,
        sourceLabel: String
    ) {
        self.id = id
        self.title = title
        self.thumbnailUrl = thumbnailUrl
        self.fullImageUrl = fullImageUrl
        self.isAnimatedGif = isAnimatedGif
        self.sourceLabel = sourceLabel
    }
}

public enum SearchFilter: String, CaseIterable, Identifiable, Sendable {
    case all = "ALL"
    case gif = "GIF"
    case meme = "MEME"
    case reaction = "REACTION"
    case transparent = "TRANSPARENT"

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .all: return "All"
        case .gif: return "GIFs"
        case .meme: return "Memes"
        case .reaction: return "Reactions"
        case .transparent: return "Stickers"
        }
    }

    public var querySuffix: String {
        switch self {
        case .all: return ""
        case .gif: return "gif"
        case .meme: return "meme"
        case .reaction: return "reaction gif"
        case .transparent: return "transparent png sticker"
        }
    }

    public var animatedOnly: Bool {
        switch self {
        case .gif, .reaction: return true
        default: return false
        }
    }

    public var transparentOnly: Bool {
        self == .transparent
    }

    public func buildEffectiveQuery(_ rawQuery: String) -> String {
        let base = rawQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        if base.isEmpty { return "" }
        if querySuffix.isEmpty { return base }
        let lower = base.lowercased()
        if lower.contains(querySuffix.lowercased()) {
            return base
        } else {
            return "\(base) \(querySuffix)"
        }
    }
}

public enum SearchEngineMode: String, CaseIterable, Identifiable, Sendable {
    case hybrid = "HYBRID"
    case googleWebView = "GOOGLE_WEBVIEW"
    case instantHttp = "INSTANT_HTTP"

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .hybrid: return "Hybrid (G + ⚡)"
        case .googleWebView: return "Google WebView (G)"
        case .instantHttp: return "Instant Web (⚡)"
        }
    }

    public func next() -> SearchEngineMode {
        let all = Self.allCases
        guard let idx = all.firstIndex(of: self) else { return .hybrid }
        return all[(idx + 1) % all.count]
    }
}

/// App Theme Mode: follows macOS System settings or forces Dark / Light mode.
public enum AppThemeMode: String, CaseIterable, Identifiable, Sendable {
    case system = "SYSTEM"
    case dark = "DARK"
    case light = "LIGHT"

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .system: return "System (Auto)"
        case .dark: return "Dark"
        case .light: return "Light"
        }
    }
}

/// Configurable Menu Bar / Status Bar icon style: Original Color App Icon, White Icon, or Black Icon.
public enum StatusBarBadgeStyle: String, CaseIterable, Identifiable, Sendable {
    case keyboardyAppIcon = "KEYBOARDY_APP_ICON"
    case keyboardyWhiteIcon = "KEYBOARDY_WHITE_ICON"
    case keyboardyBlackIcon = "KEYBOARDY_BLACK_ICON"

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .keyboardyAppIcon: return "App Icon"
        case .keyboardyWhiteIcon: return "White Icon"
        case .keyboardyBlackIcon: return "Black Icon"
        }
    }
}

/// Configurable Global Hotkey presets (plus Custom Shortcut recorder)
public enum HotkeyPreset: String, CaseIterable, Identifiable, Sendable {
    case optShiftSpace = "OPT_SHIFT_SPACE"
    case cmdOptG = "CMD_OPT_G"
    case ctrlOptionG = "CTRL_OPT_G"
    case cmdShiftG = "CMD_SHIFT_G"
    case cmdShiftK = "CMD_SHIFT_K"
    case optionSpace = "OPT_SPACE"
    case custom = "CUSTOM"
    case disabled = "DISABLED"

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .optShiftSpace: return "⌥ ⇧ Space (Option + Shift + Space)"
        case .cmdOptG: return "⌘ ⌥ G (Cmd + Option + G)"
        case .ctrlOptionG: return "⌃ ⌥ G (Ctrl + Option + G)"
        case .cmdShiftG: return "⌘ ⇧ G (Cmd + Shift + G)"
        case .cmdShiftK: return "⌘ ⇧ K (Cmd + Shift + K)"
        case .optionSpace: return "⌥ Space (Option + Space)"
        case .custom: return "Custom Recorded Shortcut"
        case .disabled: return "Disabled (Status Bar Only)"
        }
    }

    public var shortLabel: String {
        switch self {
        case .optShiftSpace: return "⌥⇧Space"
        case .cmdOptG: return "⌘⌥G"
        case .ctrlOptionG: return "⌃⌥G"
        case .cmdShiftG: return "⌘⇧G"
        case .cmdShiftK: return "⌘⇧K"
        case .optionSpace: return "⌥Space"
        case .custom: return "Custom"
        case .disabled: return "Off"
        }
    }

    public var carbonKeyCode: UInt32? {
        switch self {
        case .optShiftSpace, .optionSpace: return UInt32(kVK_Space)
        case .cmdOptG, .ctrlOptionG, .cmdShiftG: return UInt32(kVK_ANSI_G)
        case .cmdShiftK: return UInt32(kVK_ANSI_K)
        case .custom, .disabled: return nil
        }
    }

    public var carbonModifiers: UInt32 {
        switch self {
        case .optShiftSpace: return UInt32(optionKey | shiftKey)
        case .cmdOptG: return UInt32(cmdKey | optionKey)
        case .ctrlOptionG: return UInt32(controlKey | optionKey)
        case .cmdShiftG, .cmdShiftK: return UInt32(cmdKey | shiftKey)
        case .optionSpace: return UInt32(optionKey)
        case .custom, .disabled: return 0
        }
    }
}

/// Where the window opens when triggered via Global Hotkey or Inline Text Trigger
public enum PopupAnchorMode: String, CaseIterable, Identifiable, Sendable {
    case statusBarPopover = "STATUS_BAR_POPOVER"
    case floatingCenterHud = "FLOATING_CENTER_HUD"
    case nearMouseCursor = "NEAR_MOUSE_CURSOR"

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .statusBarPopover: return "Under Status Bar Icon (Popover)"
        case .floatingCenterHud: return "Spotlight Floating Window (Center)"
        case .nearMouseCursor: return "Near Mouse / Active Window"
        }
    }
}

/// What happens when you click or press Return on an image/GIF
public enum InsertBehaviorMode: String, CaseIterable, Identifiable, Sendable {
    case autoPasteIntoApp = "AUTO_PASTE"
    case copyToClipboardOnly = "COPY_ONLY"

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .autoPasteIntoApp: return "Auto-Insert into Active Chat (⌘V) + Copy"
        case .copyToClipboardOnly: return "Copy to Clipboard Only"
        }
    }
}
