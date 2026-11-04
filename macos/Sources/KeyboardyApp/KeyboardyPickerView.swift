import AppKit
import KeyboardyCore
import SwiftUI

struct KeyboardyPickerView: View {
    @ObservedObject var viewModel: KeyboardyViewModel
    @ObservedObject var prefs: KeyboardyPrefs
    @FocusState private var isSearchFieldFocused: Bool

    private var palette: ThemePalette {
        ThemePalette.forMode(isDark: prefs.isDarkMode)
    }

    private var searchQueryBinding: Binding<String> {
        Binding(
            get: { viewModel.searchQuery },
            set: { newValue in
                viewModel.onUserEditedSearchQuery(newValue)
            }
        )
    }

    private let gridColumns = [
        GridItem(.flexible(), spacing: 8),
        GridItem(.flexible(), spacing: 8),
        GridItem(.flexible(), spacing: 8)
    ]

    var body: some View {
        ZStack {
            palette.background.ignoresSafeArea()

            if viewModel.isShowingSettings {
                SettingsView(
                    prefs: prefs,
                    onCloseSettings: {
                        viewModel.isShowingSettings = false
                        isSearchFieldFocused = true
                    }
                )
            } else {
                mainSearchContent
            }
        }
        .frame(width: 500, height: 550)
        .preferredColorScheme(prefs.isDarkMode ? .dark : .light)
        .onAppear {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
                isSearchFieldFocused = true
            }
        }
    }

    private var mainSearchContent: some View {
        VStack(spacing: 0) {
            // 1. TOP SEARCH & ACTION BAR (App Title on the Left + Search Bar + Action Buttons)
            HStack(spacing: 8) {
                // App Title to the left of the Search Bar
                Text("Keyboardy")
                    .font(.system(size: 15, weight: .black, design: .rounded))
                    .foregroundColor(palette.textPrimary)
                    .fixedSize()
                    .padding(.leading, 2)
                    .padding(.trailing, 2)

                // Search Bar Pill
                HStack(spacing: 6) {
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 12.5, weight: .semibold))
                        .foregroundColor(palette.textSecondary)

                    TextField("Search Google Images, GIFs, Memes...", text: searchQueryBinding)
                        .textFieldStyle(.plain)
                        .font(.system(size: 13, weight: .medium))
                        .foregroundColor(palette.textPrimary)
                        .focused($isSearchFieldFocused)
                        .onSubmit {
                            viewModel.submitSearchFromKeyboard()
                        }

                    if viewModel.isSearching {
                        ProgressView()
                            .scaleEffect(0.55)
                            .frame(width: 15, height: 15)
                    }

                    if !viewModel.searchQuery.isEmpty {
                        Button(action: {
                            viewModel.clearSearch()
                            isSearchFieldFocused = true
                        }) {
                            Image(systemName: "xmark.circle.fill")
                                .font(.system(size: 13))
                                .foregroundColor(palette.textSecondary)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(palette.surface)
                .cornerRadius(18)
                .overlay(
                    RoundedRectangle(cornerRadius: 18)
                        .stroke(palette.border, lineWidth: 1)
                )

                // History Button (🕒)
                Button(action: {
                    viewModel.isShowingHistory.toggle()
                }) {
                    Image(systemName: "clock.arrow.circlepath")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(viewModel.isShowingHistory ? .white : palette.textPrimary)
                        .frame(width: 30, height: 30)
                        .background(viewModel.isShowingHistory ? palette.accentBlue : palette.surface)
                        .cornerRadius(8)
                        .overlay(
                            RoundedRectangle(cornerRadius: 8)
                                .stroke(palette.border, lineWidth: 1)
                        )
                }
                .buttonStyle(.plain)
                .help("Recent Searches")

                // Theme Toggle Button (☀️ / 🌙)
                Button(action: {
                    prefs.isDarkMode.toggle()
                }) {
                    Image(systemName: prefs.isDarkMode ? "sun.max.fill" : "moon.fill")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(palette.accentBlue)
                        .frame(width: 30, height: 30)
                        .background(palette.surface)
                        .cornerRadius(8)
                        .overlay(
                            RoundedRectangle(cornerRadius: 8)
                                .stroke(palette.border, lineWidth: 1)
                        )
                }
                .buttonStyle(.plain)
                .help("Toggle Dark / Light Mode")

                // Settings Button (⚙️)
                Button(action: {
                    viewModel.isShowingSettings = true
                }) {
                    Image(systemName: "slider.horizontal.3")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(palette.textPrimary)
                        .frame(width: 30, height: 30)
                        .background(palette.surface)
                        .cornerRadius(8)
                        .overlay(
                            RoundedRectangle(cornerRadius: 8)
                                .stroke(palette.border, lineWidth: 1)
                        )
                }
                .buttonStyle(.plain)
                .help("Settings")
            }
            .padding(.horizontal, 12)
            .padding(.top, 10)
            .padding(.bottom, 8)

            // 2. FILTER CHIPS + ENGINE SWITCHER ROW
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(SearchFilter.allCases) { filter in
                        let isSelected = viewModel.currentFilter == filter
                        Button(action: {
                            viewModel.selectFilter(filter)
                        }) {
                            Text(filter.displayName)
                                .font(.system(size: 11.5, weight: .bold))
                                .foregroundColor(isSelected ? palette.chipActiveText : palette.chipInactiveText)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 5)
                                .background(isSelected ? palette.chipActiveBg : palette.chipInactiveBg)
                                .cornerRadius(12)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 12)
                                        .stroke(isSelected ? Color.clear : palette.border, lineWidth: 1)
                                )
                        }
                        .buttonStyle(.plain)
                    }

                    Button(action: {
                        viewModel.cycleEngineMode()
                    }) {
                        Text("Engine: \(prefs.engineMode.displayName)")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundColor(palette.accentBlue)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 5)
                            .background(palette.chipInactiveBg)
                            .cornerRadius(12)
                            .overlay(
                                RoundedRectangle(cornerRadius: 12)
                                    .stroke(palette.accentBlue, lineWidth: 1)
                            )
                    }
                    .buttonStyle(.plain)
                    .help("Click to cycle Hybrid -> Google WebView -> Instant Web")
                }
                .padding(.horizontal, 12)
            }
            .padding(.bottom, 8)

            Divider().background(palette.border)

            // Accessibility Onboarding Banner (if user has Auto-Paste selected but Accessibility permission is not yet granted in macOS)
            if prefs.insertBehaviorMode == .autoPasteIntoApp && !prefs.isAccessibilityGranted {
                HStack(spacing: 8) {
                    Image(systemName: "hand.tap.fill")
                        .foregroundColor(palette.accentBlue)
                        .font(.system(size: 13))
                    VStack(alignment: .leading, spacing: 1) {
                        Text("Enable Accessibility for 1-Click Auto-Paste")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundColor(palette.textPrimary)
                        Text("macOS requires Accessibility permission so Keyboardy can send ⌘V into \(viewModel.targetAppName)")
                            .font(.system(size: 10))
                            .foregroundColor(palette.textSecondary)
                            .lineLimit(1)
                    }
                    Spacer()
                    Button(action: {
                        let trusted = ImageCommitService.shared.requestAccessibilityPermission()
                        prefs.isAccessibilityGranted = trusted
                    }) {
                        Text("Grant")
                            .font(.system(size: 10.5, weight: .bold))
                            .foregroundColor(.white)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(palette.accentBlue)
                            .cornerRadius(5)
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(palette.accentBlue.opacity(0.12))

                Divider().background(palette.border)
            }

            // Status Banner (if active)
            if let banner = viewModel.statusBannerText {
                HStack(spacing: 6) {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundColor(palette.successGreen)
                        .font(.system(size: 12))
                    Text(banner)
                        .font(.system(size: 11.5, weight: .semibold))
                        .foregroundColor(palette.textPrimary)
                    Spacer()
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(palette.surfaceVariant)
            }

            // 3. MAIN BODY: HISTORY PANEL OR 3-COLUMN IMAGE/GIF GRID
            ZStack {
                if viewModel.isShowingHistory {
                    historyPanelView
                } else if viewModel.results.isEmpty {
                    emptyStateView
                } else {
                    resultsGridView
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            Divider().background(palette.border)

            // 4. FOOTER BAR (Target App + Keyboard Shortcuts)
            HStack(spacing: 8) {
                HStack(spacing: 4) {
                    Circle()
                        .fill(palette.successGreen)
                        .frame(width: 6, height: 6)
                    Text(prefs.insertBehaviorMode == .autoPasteIntoApp
                         ? "Target: \(viewModel.targetAppName)"
                         : "Mode: Copy to Clipboard")
                        .font(.system(size: 10.5, weight: .bold))
                        .foregroundColor(palette.textPrimary)
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 3)
                .background(palette.surface)
                .cornerRadius(6)

                Spacer()

                Text("Click or Drag to Insert  •  ⌘1–9  •  ↵ Search  •  \(prefs.activeHotkeyShortLabel)")
                    .font(.system(size: 10.5, weight: .medium))
                    .foregroundColor(palette.textSecondary)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(palette.surface)
        }
    }

    private var emptyStateView: some View {
        VStack(spacing: 12) {
            Image(systemName: viewModel.isSearching ? "sparkle.magnifyingglass" : "photo.on.rectangle.angled")
                .font(.system(size: 34, weight: .light))
                .foregroundColor(palette.accentBlue)

            Text(viewModel.isSearching
                 ? "Searching \(prefs.engineMode.displayName) for \"\(viewModel.searchQuery)\"..."
                 : "Type above to search Google Images & Animated GIFs")
                .font(.system(size: 13, weight: .semibold))
                .foregroundColor(palette.textPrimary)
                .multilineTextAlignment(.center)

            if !viewModel.isSearching {
                Text("Zero API keys required • Click any result to insert directly into \(viewModel.targetAppName)")
                    .font(.system(size: 11.5))
                    .foregroundColor(palette.textSecondary)
                    .multilineTextAlignment(.center)

                if !prefs.searchHistory.isEmpty {
                    VStack(spacing: 6) {
                        Text("Recent Searches:")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundColor(palette.textSecondary)
                        HStack(spacing: 6) {
                            ForEach(prefs.searchHistory.prefix(4), id: \.self) { recent in
                                Button(action: {
                                    viewModel.selectHistoryQuery(recent)
                                }) {
                                    HStack(spacing: 4) {
                                        Image(systemName: "clock")
                                            .font(.system(size: 10))
                                        Text(recent)
                                            .font(.system(size: 11, weight: .semibold))
                                    }
                                    .foregroundColor(palette.textPrimary)
                                    .padding(.horizontal, 9)
                                    .padding(.vertical, 4)
                                    .background(palette.surface)
                                    .cornerRadius(10)
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 10)
                                            .stroke(palette.border, lineWidth: 1)
                                    )
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                    .padding(.top, 6)
                }
            }
        }
        .padding(24)
    }

    private var resultsGridView: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVGrid(columns: gridColumns, spacing: 8) {
                    ForEach(Array(viewModel.results.enumerated()), id: \.element.fullImageUrl) { index, item in
                        resultTile(item: item, index: index)
                            .id("\(viewModel.searchGeneration)_\(item.fullImageUrl)")
                    }
                }
                .id(viewModel.searchGeneration)
                .padding(10)
            }
            .onChange(of: viewModel.selectedIndex) { newIdx in
                if viewModel.results.indices.contains(newIdx) {
                    let targetId = "\(viewModel.searchGeneration)_\(viewModel.results[newIdx].fullImageUrl)"
                    withAnimation(.easeInOut(duration: 0.12)) {
                        proxy.scrollTo(targetId, anchor: .center)
                    }
                }
            }
        }
    }

    private func resultTile(item: ImageResult, index: Int) -> some View {
        let isSelected = viewModel.selectedIndex == index
        let isLoading = viewModel.loadingItemId == item.id

        return Button(action: {
            viewModel.selectedIndex = index
            viewModel.selectImageResult(item)
        }) {
            ZStack {
                palette.surfaceVariant

                AnimatedRemoteImageView(
                    item: item,
                    onSelect: {
                        viewModel.selectedIndex = index
                        viewModel.selectImageResult(item)
                    }
                )
                .frame(height: 116)
                .padding(4)

                VStack {
                    HStack {
                        if index < 9 {
                            Text("⌘\(index + 1)")
                                .font(.system(size: 9.5, weight: .bold, design: .rounded))
                                .foregroundColor(.white)
                                .padding(.horizontal, 5)
                                .padding(.vertical, 2)
                                .background(Color.black.opacity(0.65))
                                .cornerRadius(4)
                        }
                        Spacer()
                        if item.isAnimatedGif {
                            Text("GIF")
                                .font(.system(size: 9.5, weight: .heavy))
                                .foregroundColor(.white)
                                .padding(.horizontal, 5)
                                .padding(.vertical, 2)
                                .background(palette.accentBlue.opacity(0.92))
                                .cornerRadius(4)
                        }
                    }
                    Spacer()
                    HStack {
                        Spacer()
                        Text(item.sourceLabel)
                            .font(.system(size: 9.5, weight: .bold))
                            .foregroundColor(.white)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 2)
                            .background(Color.black.opacity(0.65))
                            .cornerRadius(4)
                    }
                }
                .padding(5)
                .allowsHitTesting(false)

                if isLoading {
                    Color.black.opacity(0.55)
                        .allowsHitTesting(false)
                    ProgressView()
                        .scaleEffect(0.8)
                        .allowsHitTesting(false)
                }
            }
            .frame(height: 116)
            .cornerRadius(9)
            .overlay(
                RoundedRectangle(cornerRadius: 9)
                    .stroke(isSelected ? palette.accentBlue : palette.border, lineWidth: isSelected ? 2.5 : 1)
                    .allowsHitTesting(false)
            )
        }
        .buttonStyle(.plain)
        .onDrag {
            viewModel.selectedIndex = index
            return viewModel.makeItemProvider(for: item)
        }
        .help("Click to Auto-Paste into \(viewModel.targetAppName), or Drag & Drop anywhere")
    }

    private var historyPanelView: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Recent Searches")
                    .font(.system(size: 13, weight: .heavy))
                    .foregroundColor(palette.accentBlue)
                Spacer()
                if !prefs.searchHistory.isEmpty {
                    Button(action: { prefs.clearSearchHistory() }) {
                        Text("Clear All")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundColor(palette.textSecondary)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(palette.surface)
                            .cornerRadius(6)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 14)
            .padding(.top, 10)

            if prefs.searchHistory.isEmpty {
                Spacer()
                Text("No recent searches yet. Your searches will appear here for 1-click access!")
                    .font(.system(size: 12.5))
                    .foregroundColor(palette.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .center)
                Spacer()
            } else {
                ScrollView {
                    VStack(spacing: 6) {
                        ForEach(prefs.searchHistory, id: \.self) { queryItem in
                            HStack {
                                Button(action: {
                                    viewModel.selectHistoryQuery(queryItem)
                                }) {
                                    HStack(spacing: 8) {
                                        Image(systemName: "clock.arrow.circlepath")
                                            .foregroundColor(palette.accentBlue)
                                            .font(.system(size: 12))
                                        Text(queryItem)
                                            .font(.system(size: 13, weight: .bold))
                                            .foregroundColor(palette.textPrimary)
                                        Spacer()
                                    }
                                    .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)

                                Button(action: {
                                    prefs.removeSearchHistoryQuery(queryItem)
                                }) {
                                    Image(systemName: "xmark")
                                        .font(.system(size: 11, weight: .bold))
                                        .foregroundColor(palette.textSecondary)
                                        .frame(width: 22, height: 22)
                                }
                                .buttonStyle(.plain)
                            }
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                            .background(palette.surface)
                            .cornerRadius(8)
                            .overlay(
                                RoundedRectangle(cornerRadius: 8)
                                    .stroke(palette.border, lineWidth: 1)
                            )
                        }
                    }
                    .padding(.horizontal, 14)
                    .padding(.bottom, 12)
                }
            }
        }
    }
}
