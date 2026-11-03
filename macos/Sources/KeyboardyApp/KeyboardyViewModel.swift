import AppKit
import Combine
import Foundation
import KeyboardyCore

@MainActor
final class KeyboardyViewModel: ObservableObject {
    @Published var searchQuery: String = ""
    @Published var currentFilter: SearchFilter = .all
    @Published var results: [ImageResult] = []
    @Published var selectedIndex: Int = 0
    @Published var isSearching: Bool = false
    @Published var loadingItemId: String? = nil
    @Published var statusBannerText: String? = nil
    @Published var isShowingHistory: Bool = false
    @Published var isShowingSettings: Bool = false
    @Published var targetAppName: String = "Active App"

    var targetApp: NSRunningApplication? {
        didSet {
            targetAppName = targetApp?.localizedName ?? "Clipboard"
        }
    }

    var onRequestClosePanel: (() -> Void)?

    private let prefs = KeyboardyPrefs.shared
    private let googleSearcher = HeadlessGoogleWebViewSearcher()
    private lazy var repository = ImageSearchRepository(headlessGoogleSearcher: googleSearcher)

    private var searchTask: Task<Void, Never>?
    private var commitTask: Task<Void, Never>?
    private var bannerClearTask: Task<Void, Never>?
    private var lastCountedSearchKey: String = ""
    private var queryOriginatedFromTargetField: Bool = false
    private var lastSessionIncrementalHistoryQuery: String? = nil

    func prepareForPanelOpen(capturedApp: NSRunningApplication?, initialQueryOverride: String? = nil) {
        self.targetApp = capturedApp
        self.isShowingSettings = false
        self.isShowingHistory = false
        self.statusBannerText = nil
        self.lastSessionIncrementalHistoryQuery = nil

        if let overrideText = initialQueryOverride, !overrideText.isEmpty {
            queryOriginatedFromTargetField = false
            searchQuery = overrideText
            triggerSearch(immediate: true)
            return
        }

        if prefs.autoReadSelectedOrChatText,
           let extracted = ImageCommitService.shared.readFocusedOrSelectedText(from: capturedApp) {
            queryOriginatedFromTargetField = extracted.wasFullFieldValue
            searchQuery = extracted.text
            triggerSearch(immediate: true)
            return
        }

        queryOriginatedFromTargetField = false
    }

    func onSearchQueryEdited(_ newValue: String) {
        queryOriginatedFromTargetField = false
        let trimmed = newValue.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            searchTask?.cancel()
            isSearching = false
            results = []
            selectedIndex = 0
            lastSessionIncrementalHistoryQuery = nil
            return
        }
        triggerSearch(immediate: false)
    }

    func selectFilter(_ filter: SearchFilter) {
        currentFilter = filter
        isShowingHistory = false
        lastSessionIncrementalHistoryQuery = nil
        if !searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            triggerSearch(immediate: true)
        }
    }

    func cycleEngineMode() {
        prefs.engineMode = prefs.engineMode.next()
        if !searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            triggerSearch(immediate: true)
        }
    }

    func clearSearch() {
        let currentTrimmed = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        if currentTrimmed.count >= 2 {
            recordQueryInHistory(currentTrimmed, lockSession: true)
        }
        searchTask?.cancel()
        queryOriginatedFromTargetField = false
        lastSessionIncrementalHistoryQuery = nil
        searchQuery = ""
        results = []
        selectedIndex = 0
        isSearching = false
    }

    /// Saves a searched query to history while upgrading any half-typed prefix from the same continuous typing burst.
    private func recordQueryInHistory(_ rawQuery: String, lockSession: Bool) {
        let cleaned = rawQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard cleaned.count >= 2 else { return }

        if let prev = lastSessionIncrementalHistoryQuery,
           prev.caseInsensitiveCompare(cleaned) != .orderedSame {
            let lowerClean = cleaned.lowercased()
            let lowerPrev = prev.lowercased()
            // If the user kept typing more letters onto the same query (e.g. "par" -> "party parrot")
            // or backspaced a typo within the same word, replace the partial entry with the full query.
            if lowerClean.hasPrefix(lowerPrev) || lowerPrev.hasPrefix(lowerClean) {
                prefs.removeSearchHistoryQuery(prev)
            }
        }

        prefs.addSearchHistoryQuery(cleaned)
        lastSessionIncrementalHistoryQuery = lockSession ? nil : cleaned
    }

    func triggerSearch(immediate: Bool) {
        let trimmed = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            searchTask?.cancel()
            isSearching = false
            return
        }

        if immediate {
            recordQueryInHistory(trimmed, lockSession: true)
        }

        searchTask?.cancel()
        searchTask = Task { [weak self] in
            guard let self = self else { return }
            if !immediate {
                try? await Task.sleep(nanoseconds: 380_000_000)
                guard !Task.isCancelled else { return }
            }

            isSearching = true
            isShowingHistory = false

            // Save every executed search query to history!
            recordQueryInHistory(trimmed, lockSession: immediate)

            let countKey = "\(trimmed.lowercased())|\(currentFilter.rawValue)|\(prefs.engineMode.rawValue)|\(prefs.nsfwModeEnabled)"
            if countKey != lastCountedSearchKey {
                lastCountedSearchKey = countKey
                prefs.recordSearchExecuted()
            }

            let finalItems = await repository.searchImages(
                query: trimmed,
                filter: currentFilter,
                engineMode: prefs.engineMode,
                nsfwMode: prefs.nsfwModeEnabled,
                optionalApiKey: prefs.optionalApiKey,
                onProgressiveResults: { [weak self] partial in
                    guard let self = self, !Task.isCancelled else { return }
                    if !partial.isEmpty {
                        self.results = partial
                        if self.selectedIndex >= partial.count {
                            self.selectedIndex = 0
                        }
                    }
                }
            )

            guard !Task.isCancelled else { return }
            isSearching = false
            results = finalItems
            if selectedIndex >= finalItems.count {
                selectedIndex = 0
            }
        }
    }

    func selectImageResult(_ item: ImageResult) {
        let q = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        if !q.isEmpty {
            recordQueryInHistory(q, lockSession: true)
        }

        commitTask?.cancel()
        loadingItemId = item.id

        commitTask = Task { [weak self] in
            guard let self = self else { return }
            let shouldClearTarget = queryOriginatedFromTargetField && prefs.autoReadSelectedOrChatText
            let outcome = await ImageCommitService.shared.downloadAndInsertImage(
                item: item,
                targetApp: targetApp,
                insertMode: prefs.insertBehaviorMode,
                clearTargetQueryBeforePaste: shouldClearTarget,
                restoreClipboardAfterPaste: prefs.restoreClipboardAfterAutoPaste,
                onPanelShouldHideBeforePaste: { [weak self] in
                    self?.onRequestClosePanel?()
                }
            )

            loadingItemId = nil
            queryOriginatedFromTargetField = false

            switch outcome {
            case .autoPastedIntoApp(let appName):
                prefs.recordImageInserted()
                showBanner("Inserted into \(appName)!")

            case .copiedToClipboard(let reason):
                prefs.recordImageInserted()
                showBanner(reason)
                if prefs.insertBehaviorMode == .copyToClipboardOnly {
                    try? await Task.sleep(nanoseconds: 350_000_000)
                    onRequestClosePanel?()
                }

            case .downloadFailed:
                showBanner("Couldn't download that image. Try another result!")
            }
        }
    }

    func moveSelection(delta: Int, columns: Int = 3) {
        guard !results.isEmpty else { return }
        let next = (selectedIndex + delta).clamped(to: 0...(results.count - 1))
        selectedIndex = next
    }

    /// Called when the user presses Return/Enter in the search bar: runs an immediate search and saves to history without closing the window!
    func submitSearchFromKeyboard() {
        let trimmed = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        recordQueryInHistory(trimmed, lockSession: true)
        triggerSearch(immediate: true)
    }

    func showBanner(_ message: String) {
        bannerClearTask?.cancel()
        statusBannerText = message
        bannerClearTask = Task {
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard !Task.isCancelled else { return }
            statusBannerText = nil
        }
    }
}

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}
