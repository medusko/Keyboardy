import AppKit
import Carbon
import Combine
import Foundation

@MainActor
public final class KeyboardyPrefs: ObservableObject {
    public static let shared = KeyboardyPrefs()

    private let defaults: UserDefaults
    private var isUpdatingThemeInternally = false

    @Published public var themeMode: AppThemeMode {
        didSet {
            defaults.set(themeMode.rawValue, forKey: Keys.themeMode)
            guard !isUpdatingThemeInternally else { return }
            applyThemeMode(themeMode, syncIcon: true)
        }
    }

    @Published public var isDarkMode: Bool {
        didSet {
            defaults.set(isDarkMode, forKey: Keys.isDarkMode)
            guard !isUpdatingThemeInternally else { return }
            // If user toggled Dark Mode directly (e.g. via switch or top-bar button), update themeMode & sync icon
            isUpdatingThemeInternally = true
            themeMode = isDarkMode ? .dark : .light
            defaults.set(themeMode.rawValue, forKey: Keys.themeMode)
            statusBarBadgeStyle = isDarkMode ? .keyboardyWhiteIcon : .keyboardyBlackIcon
            isUpdatingThemeInternally = false
        }
    }

    @Published public var statusBarBadgeStyle: StatusBarBadgeStyle {
        didSet {
            defaults.set(statusBarBadgeStyle.rawValue, forKey: Keys.statusBarBadgeStyle)
        }
    }

    @Published public var hotkeyPreset: HotkeyPreset {
        didSet { defaults.set(hotkeyPreset.rawValue, forKey: Keys.hotkeyPreset) }
    }

    @Published public var customKeyCode: UInt32 {
        didSet { defaults.set(Int(customKeyCode), forKey: Keys.customKeyCode) }
    }

    @Published public var customCarbonModifiers: UInt32 {
        didSet { defaults.set(Int(customCarbonModifiers), forKey: Keys.customCarbonModifiers) }
    }

    @Published public var customShortcutLabel: String {
        didSet { defaults.set(customShortcutLabel, forKey: Keys.customShortcutLabel) }
    }

    @Published public var isRecordingCustomShortcut: Bool = false

    @Published public var popupAnchorMode: PopupAnchorMode {
        didSet { defaults.set(popupAnchorMode.rawValue, forKey: Keys.popupAnchorMode) }
    }

    @Published public var insertBehaviorMode: InsertBehaviorMode {
        didSet { defaults.set(insertBehaviorMode.rawValue, forKey: Keys.insertBehaviorMode) }
    }

    @Published public var autoReadSelectedOrChatText: Bool {
        didSet { defaults.set(autoReadSelectedOrChatText, forKey: Keys.autoReadSelectedOrChatText) }
    }

    @Published public var inlineTriggerEnabled: Bool {
        didSet { defaults.set(inlineTriggerEnabled, forKey: Keys.inlineTriggerEnabled) }
    }

    @Published public var inlineTriggerPrefix: String {
        didSet { defaults.set(inlineTriggerPrefix, forKey: Keys.inlineTriggerPrefix) }
    }

    @Published public var restoreClipboardAfterAutoPaste: Bool {
        didSet { defaults.set(restoreClipboardAfterAutoPaste, forKey: Keys.restoreClipboardAfterAutoPaste) }
    }

    @Published public var nsfwModeEnabled: Bool {
        didSet { defaults.set(nsfwModeEnabled, forKey: Keys.nsfwModeEnabled) }
    }

    @Published public var engineMode: SearchEngineMode {
        didSet { defaults.set(engineMode.rawValue, forKey: Keys.engineMode) }
    }

    @Published public var optionalApiKey: String {
        didSet { defaults.set(optionalApiKey.trimmingCharacters(in: .whitespacesAndNewlines), forKey: Keys.optionalApiKey) }
    }

    @Published public private(set) var searchHistory: [String] = []
    @Published public private(set) var searchesToday: Int = 0
    @Published public private(set) var searchesThisMonth: Int = 0
    @Published public private(set) var imagesInsertedToday: Int = 0
    @Published public private(set) var imagesInsertedTotal: Int = 0
    @Published public var isAccessibilityGranted: Bool = false

    public var activeHotkeyShortLabel: String {
        if hotkeyPreset == .custom {
            return customShortcutLabel
        }
        return hotkeyPreset.shortLabel
    }

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults

        let systemDark = Self.detectSystemDarkMode()
        let savedThemeRaw = defaults.string(forKey: Keys.themeMode) ?? AppThemeMode.system.rawValue
        let initialThemeMode = AppThemeMode(rawValue: savedThemeRaw) ?? .system
        self.themeMode = initialThemeMode

        let effectiveDark: Bool
        switch initialThemeMode {
        case .system:
            effectiveDark = systemDark
        case .dark:
            effectiveDark = true
        case .light:
            effectiveDark = false
        }
        self.isDarkMode = effectiveDark

        // Night/Dark Mode -> White Icon (.keyboardyWhiteIcon)
        // Light Mode      -> Black Icon (.keyboardyBlackIcon)
        let defaultIconForMode: StatusBarBadgeStyle = effectiveDark ? .keyboardyWhiteIcon : .keyboardyBlackIcon
        if let badgeRaw = defaults.string(forKey: Keys.statusBarBadgeStyle),
           let savedStyle = StatusBarBadgeStyle(rawValue: badgeRaw),
           savedStyle == .keyboardyAppIcon {
            self.statusBarBadgeStyle = .keyboardyAppIcon
        } else {
            self.statusBarBadgeStyle = defaultIconForMode
            defaults.set(defaultIconForMode.rawValue, forKey: Keys.statusBarBadgeStyle)
        }

        // Default to ⌥⇧Space (Option + Shift + Space) so it never conflicts with Gemini (⌥Space) or Spotlight (⌘Space)
        let hotkeyRaw = defaults.string(forKey: Keys.hotkeyPreset) ?? HotkeyPreset.optShiftSpace.rawValue
        self.hotkeyPreset = HotkeyPreset(rawValue: hotkeyRaw) ?? .optShiftSpace

        if defaults.object(forKey: Keys.customKeyCode) == nil {
            self.customKeyCode = UInt32(kVK_Space)
            self.customCarbonModifiers = UInt32(optionKey | shiftKey)
            self.customShortcutLabel = "⌥⇧Space"
        } else {
            self.customKeyCode = UInt32(defaults.integer(forKey: Keys.customKeyCode))
            self.customCarbonModifiers = UInt32(defaults.integer(forKey: Keys.customCarbonModifiers))
            self.customShortcutLabel = defaults.string(forKey: Keys.customShortcutLabel) ?? "⌥⇧Space"
        }

        let anchorRaw = defaults.string(forKey: Keys.popupAnchorMode) ?? PopupAnchorMode.statusBarPopover.rawValue
        self.popupAnchorMode = PopupAnchorMode(rawValue: anchorRaw) ?? .statusBarPopover

        let insertRaw = defaults.string(forKey: Keys.insertBehaviorMode) ?? InsertBehaviorMode.autoPasteIntoApp.rawValue
        self.insertBehaviorMode = InsertBehaviorMode(rawValue: insertRaw) ?? .autoPasteIntoApp

        if defaults.object(forKey: Keys.autoReadSelectedOrChatText) == nil {
            self.autoReadSelectedOrChatText = true
        } else {
            self.autoReadSelectedOrChatText = defaults.bool(forKey: Keys.autoReadSelectedOrChatText)
        }

        self.inlineTriggerEnabled = defaults.bool(forKey: Keys.inlineTriggerEnabled)
        let savedPrefix = defaults.string(forKey: Keys.inlineTriggerPrefix) ?? "//gif "
        self.inlineTriggerPrefix = savedPrefix.isEmpty ? "//gif " : savedPrefix

        if defaults.object(forKey: Keys.restoreClipboardAfterAutoPaste) == nil {
            self.restoreClipboardAfterAutoPaste = false
        } else {
            self.restoreClipboardAfterAutoPaste = defaults.bool(forKey: Keys.restoreClipboardAfterAutoPaste)
        }

        self.nsfwModeEnabled = defaults.bool(forKey: Keys.nsfwModeEnabled)

        let engineRaw = defaults.string(forKey: Keys.engineMode) ?? SearchEngineMode.hybrid.rawValue
        self.engineMode = SearchEngineMode(rawValue: engineRaw) ?? .hybrid

        self.optionalApiKey = (defaults.string(forKey: Keys.optionalApiKey) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)

        self.searchHistory = Self.loadHistory(from: defaults)
        refreshCounters()
        observeSystemAppearanceChanges()
    }

    public static func detectSystemDarkMode() -> Bool {
        if let style = UserDefaults.standard.string(forKey: "AppleInterfaceStyle") {
            return style.caseInsensitiveCompare("Dark") == .orderedSame
        }
        return false
    }

    public func applyThemeMode(_ mode: AppThemeMode, syncIcon: Bool) {
        isUpdatingThemeInternally = true
        let targetDark: Bool
        switch mode {
        case .system:
            targetDark = Self.detectSystemDarkMode()
        case .dark:
            targetDark = true
        case .light:
            targetDark = false
        }
        isDarkMode = targetDark
        if syncIcon {
            statusBarBadgeStyle = targetDark ? .keyboardyWhiteIcon : .keyboardyBlackIcon
        }
        isUpdatingThemeInternally = false
    }

    private func observeSystemAppearanceChanges() {
        DistributedNotificationCenter.default().addObserver(
            forName: Notification.Name("AppleInterfaceThemeChangedNotification"),
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                guard let self = self else { return }
                if self.themeMode == .system {
                    self.applyThemeMode(.system, syncIcon: true)
                }
            }
        }
    }

    public func saveCustomRecordedShortcut(keyCode: UInt32, carbonModifiers: UInt32, displayLabel: String) {
        self.customKeyCode = keyCode
        self.customCarbonModifiers = carbonModifiers
        self.customShortcutLabel = displayLabel
        self.hotkeyPreset = .custom
        self.isRecordingCustomShortcut = false
    }

    // MARK: - Search History (up to 25 queries)

    public func addSearchHistoryQuery(_ query: String) {
        let cleaned = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard cleaned.count >= 2 else { return }
        var list = searchHistory
        list.removeAll { $0.caseInsensitiveCompare(cleaned) == .orderedSame }
        list.insert(cleaned, at: 0)
        if list.count > 25 {
            list = Array(list.prefix(25))
        }
        searchHistory = list
        defaults.set(list, forKey: Keys.searchHistory)
    }

    public func removeSearchHistoryQuery(_ query: String) {
        var list = searchHistory
        list.removeAll { $0.caseInsensitiveCompare(query) == .orderedSame }
        searchHistory = list
        defaults.set(list, forKey: Keys.searchHistory)
    }

    public func clearSearchHistory() {
        searchHistory = []
        defaults.set([String](), forKey: Keys.searchHistory)
    }

    private static func loadHistory(from defaults: UserDefaults) -> [String] {
        return (defaults.stringArray(forKey: Keys.searchHistory) ?? [])
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }

    // MARK: - Quota & Usage Tracking

    public func recordSearchExecuted() {
        ensureQuotaDatesCurrent()
        let dayCount = defaults.integer(forKey: Keys.searchesToday) + 1
        let monthCount = defaults.integer(forKey: Keys.searchesMonth) + 1
        defaults.set(dayCount, forKey: Keys.searchesToday)
        defaults.set(monthCount, forKey: Keys.searchesMonth)
        refreshCounters()
    }

    public func recordImageInserted() {
        ensureQuotaDatesCurrent()
        let dayInserts = defaults.integer(forKey: Keys.insertsToday) + 1
        let totalInserts = defaults.integer(forKey: Keys.insertsTotal) + 1
        defaults.set(dayInserts, forKey: Keys.insertsToday)
        defaults.set(totalInserts, forKey: Keys.insertsTotal)
        refreshCounters()
    }

    private func refreshCounters() {
        ensureQuotaDatesCurrent()
        searchesToday = defaults.integer(forKey: Keys.searchesToday)
        searchesThisMonth = defaults.integer(forKey: Keys.searchesMonth)
        imagesInsertedToday = defaults.integer(forKey: Keys.insertsToday)
        imagesInsertedTotal = defaults.integer(forKey: Keys.insertsTotal)
    }

    private func ensureQuotaDatesCurrent() {
        let formatterDay = DateFormatter()
        formatterDay.dateFormat = "yyyy-MM-dd"
        let todayStr = formatterDay.string(from: Date())

        let formatterMonth = DateFormatter()
        formatterMonth.dateFormat = "yyyy-MM"
        let monthStr = formatterMonth.string(from: Date())

        let savedDay = defaults.string(forKey: Keys.quotaDayDate) ?? ""
        let savedMonth = defaults.string(forKey: Keys.quotaMonthDate) ?? ""

        if savedDay != todayStr {
            defaults.set(todayStr, forKey: Keys.quotaDayDate)
            defaults.set(0, forKey: Keys.searchesToday)
            defaults.set(0, forKey: Keys.insertsToday)
        }
        if savedMonth != monthStr {
            defaults.set(monthStr, forKey: Keys.quotaMonthDate)
            defaults.set(0, forKey: Keys.searchesMonth)
        }
    }

    private enum Keys {
        static let themeMode = "mac_theme_mode_v3"
        static let isDarkMode = "mac_is_dark_mode_v3"
        static let statusBarBadgeStyle = "mac_status_bar_badge_style_v3"
        static let hotkeyPreset = "mac_hotkey_preset_v2"
        static let customKeyCode = "mac_custom_key_code"
        static let customCarbonModifiers = "mac_custom_carbon_modifiers"
        static let customShortcutLabel = "mac_custom_shortcut_label"
        static let popupAnchorMode = "mac_popup_anchor_mode"
        static let insertBehaviorMode = "mac_insert_behavior_mode"
        static let autoReadSelectedOrChatText = "mac_auto_read_chat_text"
        static let inlineTriggerEnabled = "mac_inline_trigger_enabled"
        static let inlineTriggerPrefix = "mac_inline_trigger_prefix"
        static let restoreClipboardAfterAutoPaste = "mac_restore_clipboard"
        static let nsfwModeEnabled = "mac_nsfw_mode_enabled"
        static let engineMode = "mac_engine_mode"
        static let optionalApiKey = "mac_optional_api_key"
        static let searchHistory = "mac_search_history"

        static let quotaDayDate = "mac_quota_day_date"
        static let quotaMonthDate = "mac_quota_month_date"
        static let searchesToday = "mac_searches_today"
        static let searchesMonth = "mac_searches_month"
        static let insertsToday = "mac_inserts_today"
        static let insertsTotal = "mac_inserts_total"
    }
}
