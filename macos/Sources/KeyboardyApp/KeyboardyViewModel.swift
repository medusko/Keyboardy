import AppKit
import Combine
import Foundation
import KeyboardyCore

@MainActor
final class KeyboardyViewModel: ObservableObject {
    @Published var searchQuery: String = ""
    @Published var currentFilter: SearchFilter = .all
    @Published var results: [ImageResult] = []
    @Published var searchGeneration: Int = 0
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
    private var activeSearchToken = UUID()
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
            if searchQuery != overrideText {
                searchQuery = overrideText
                triggerSearch(immediate: true, clearExistingResultsFirst: true)
            } else if results.isEmpty {
                triggerSearch(immediate: true, clearExistingResultsFirst: true)
            }
            return
        }

        if prefs.autoReadSelectedOrChatText,
           let extracted = ImageCommitService.shared.readFocusedOrSelectedText(from: capturedApp) {
            queryOriginatedFromTargetField = extracted.wasFullFieldValue
            if searchQuery != extracted.text {
                searchQuery = extracted.text
                triggerSearch(immediate: true, clearExistingResultsFirst: true)
            } else if results.isEmpty {
                triggerSearch(immediate: true, clearExistingResultsFirst: true)
            }
            return
        }

        queryOriginatedFromTargetField = false
    }

    /// Called directly by the Search TextField's Binding setter whenever the user types, pastes, or deletes text.
    func onUserEditedSearchQuery(_ newValue: String) {
        let oldTrimmed = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        let newTrimmed = newValue.trimmingCharacters(in: .whitespacesAndNewlines)
        searchQuery = newValue
        queryOriginatedFromTargetField = false

        if newTrimmed.isEmpty {
            searchTask?.cancel()
            activeSearchToken = UUID()
            isSearching = false
            results = []
            searchGeneration &+= 1
            selectedIndex = 0
            lastSessionIncrementalHistoryQuery = nil
            return
        }

        // Immediately clear old search results when the query changes so stale images never linger on screen!
        let queryChanged = oldTrimmed.caseInsensitiveCompare(newTrimmed) != .orderedSame
        triggerSearch(immediate: false, clearExistingResultsFirst: queryChanged)
    }

    func selectFilter(_ filter: SearchFilter) {
        currentFilter = filter
        isShowingHistory = false
        lastSessionIncrementalHistoryQuery = nil
        if !searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            triggerSearch(immediate: true, clearExistingResultsFirst: true)
        }
    }

    func selectHistoryQuery(_ queryItem: String) {
        queryOriginatedFromTargetField = false
        lastSessionIncrementalHistoryQuery = nil
        isShowingHistory = false
        searchQuery = queryItem
        triggerSearch(immediate: true, clearExistingResultsFirst: true)
    }

    func cycleEngineMode() {
        prefs.engineMode = prefs.engineMode.next()
        if !searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            triggerSearch(immediate: true, clearExistingResultsFirst: true)
        }
    }

    func clearSearch() {
        let currentTrimmed = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        if currentTrimmed.count >= 2 {
            recordQueryInHistory(currentTrimmed, lockSession: true)
        }
        searchTask?.cancel()
        activeSearchToken = UUID()
        queryOriginatedFromTargetField = false
        lastSessionIncrementalHistoryQuery = nil
        searchQuery = ""
        results = []
        searchGeneration &+= 1
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
            if lowerClean.hasPrefix(lowerPrev) || lowerPrev.hasPrefix(lowerClean) {
                prefs.removeSearchHistoryQuery(prev)
            }
        }

        prefs.addSearchHistoryQuery(cleaned)
        lastSessionIncrementalHistoryQuery = lockSession ? nil : cleaned
    }

    func triggerSearch(immediate: Bool, clearExistingResultsFirst: Bool = true) {
        let trimmed = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            searchTask?.cancel()
            activeSearchToken = UUID()
            isSearching = false
            results = []
            searchGeneration &+= 1
            return
        }

        if immediate {
            recordQueryInHistory(trimmed, lockSession: true)
        }

        if clearExistingResultsFirst {
            results = []
            selectedIndex = 0
            searchGeneration &+= 1
        }

        let activeFilter = currentFilter
        let activeEngine = prefs.engineMode
        let activeNsfw = prefs.nsfwModeEnabled
        let activeApiKey = prefs.optionalApiKey

        searchTask?.cancel()
        let searchToken = UUID()
        self.activeSearchToken = searchToken

        searchTask = Task { [weak self] in
            guard let self = self else { return }
            if !immediate {
                try? await Task.sleep(nanoseconds: 280_000_000)
                guard !Task.isCancelled, self.activeSearchToken == searchToken else { return }
            }

            self.isSearching = true
            self.isShowingHistory = false

            // Save every executed search query to history!
            self.recordQueryInHistory(trimmed, lockSession: immediate)

            let countKey = "\(trimmed.lowercased())|\(activeFilter.rawValue)|\(activeEngine.rawValue)|\(activeNsfw)"
            if countKey != self.lastCountedSearchKey {
                self.lastCountedSearchKey = countKey
                self.prefs.recordSearchExecuted()
            }

            let finalItems = await self.repository.searchImages(
                query: trimmed,
                filter: activeFilter,
                engineMode: activeEngine,
                nsfwMode: activeNsfw,
                optionalApiKey: activeApiKey,
                onProgressiveResults: { [weak self] partial in
                    guard let self = self,
                          !Task.isCancelled,
                          self.activeSearchToken == searchToken else { return }
                    if !partial.isEmpty && self.results != partial {
                        self.results = partial
                        if self.selectedIndex >= partial.count {
                            self.selectedIndex = 0
                        }
                    }
                }
            )

            guard !Task.isCancelled, self.activeSearchToken == searchToken else { return }
            self.isSearching = false
            if self.results != finalItems {
                self.results = finalItems
            }
            if self.selectedIndex >= finalItems.count {
                self.selectedIndex = 0
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
        triggerSearch(immediate: true, clearExistingResultsFirst: true)
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
