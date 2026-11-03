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

    func prepareForPanelOpen(capturedApp: NSRunningApplication?, initialQueryOverride: String? = nil) {
        self.targetApp = capturedApp
        self.isShowingSettings = false
        self.isShowingHistory = false
        self.statusBannerText = nil

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
        if newValue.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            searchTask?.cancel()
            isSearching = false
            results = []
            selectedIndex = 0
            return
        }
        triggerSearch(immediate: false)
    }

    func selectFilter(_ filter: SearchFilter) {
        currentFilter = filter
        isShowingHistory = false
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
        searchTask?.cancel()
        queryOriginatedFromTargetField = false
        searchQuery = ""
        results = []
        selectedIndex = 0
        isSearching = false
    }

    func triggerSearch(immediate: Bool) {
        let trimmed = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            searchTask?.cancel()
            isSearching = false
            return
        }

        searchTask?.cancel()
        searchTask = Task { [weak self] in
            guard let self = self else { return }
            if !immediate {
                try? await Task.sleep(nanoseconds: 340_000_000)
                guard !Task.isCancelled else { return }
            }

            isSearching = true
            isShowingHistory = false

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
            prefs.addSearchHistoryQuery(q)
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

    func confirmCurrentSelection() {
        guard !results.isEmpty, results.indices.contains(selectedIndex) else {
            triggerSearch(immediate: true)
            return
        }
        selectImageResult(results[selectedIndex])
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
