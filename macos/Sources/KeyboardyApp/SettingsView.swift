import AppKit
import KeyboardyCore
import SwiftUI

struct SettingsView: View {
    @ObservedObject var prefs: KeyboardyPrefs
    let onCloseSettings: () -> Void

    private var palette: ThemePalette {
        ThemePalette.forMode(isDark: prefs.isDarkMode)
    }

    var body: some View {
        VStack(spacing: 0) {
            // Top Navigation Bar
            HStack(spacing: 10) {
                Button(action: {
                    if prefs.isShowingAbout {
                        withAnimation(.easeInOut(duration: 0.15)) {
                            prefs.isShowingAbout = false
                        }
                    } else {
                        onCloseSettings()
                    }
                }) {
                    HStack(spacing: 5) {
                        Image(systemName: "chevron.left")
                            .font(.system(size: 11.5, weight: .bold))
                        Text(prefs.isShowingAbout ? "Settings" : "Back")
                            .font(.system(size: 12.5, weight: .bold))
                    }
                    .foregroundColor(palette.textPrimary)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
                    .background(palette.surfaceVariant)
                    .cornerRadius(8)
                    .overlay(
                        RoundedRectangle(cornerRadius: 8)
                            .stroke(palette.border, lineWidth: 1)
                    )
                }
                .buttonStyle(.plain)

                Spacer()

                Text(prefs.isShowingAbout ? "About Keyboardy" : "Keyboardy Settings")
                    .font(.system(size: 14, weight: .black, design: .rounded))
                    .foregroundColor(palette.textPrimary)

                Spacer()

                // Status Pill in Top Bar (Accessibility Ready vs Clipboard Mode)
                HStack(spacing: 5) {
                    Circle()
                        .fill(prefs.isAccessibilityGranted ? palette.successGreen : Color.orange)
                        .frame(width: 7, height: 7)
                    Text(prefs.isAccessibilityGranted ? "AUTO-PASTE READY" : "CLIPBOARD MODE")
                        .font(.system(size: 9.5, weight: .heavy))
                        .foregroundColor(prefs.isAccessibilityGranted ? palette.successGreen : Color.orange)
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background((prefs.isAccessibilityGranted ? palette.successGreen : Color.orange).opacity(0.14))
                .cornerRadius(12)
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .stroke((prefs.isAccessibilityGranted ? palette.successGreen : Color.orange).opacity(0.5), lineWidth: 1)
                )

                // (i) Icon Button to the right of the status pill
                Button(action: {
                    withAnimation(.easeInOut(duration: 0.15)) {
                        prefs.isShowingAbout.toggle()
                    }
                }) {
                    Image(systemName: prefs.isShowingAbout ? "info.circle.fill" : "info.circle")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundColor(palette.accentBlue)
                        .frame(width: 22, height: 22)
                }
                .buttonStyle(.plain)
                .help(prefs.isShowingAbout ? "Back to Settings" : "About Keyboardy & What's New")
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(palette.surface)

            Divider().background(palette.border)

            if prefs.isShowingAbout {
                aboutScrollView
            } else {
                mainSettingsScrollView
            }
        }
        .background(palette.background)
        .preferredColorScheme(prefs.isDarkMode ? .dark : .light)
        .onAppear {
            prefs.isAccessibilityGranted = ImageCommitService.shared.isAccessibilityTrusted()
        }
    }

    // MARK: - Main Settings Content

    private var mainSettingsScrollView: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {

                    // =====================================================
                    // SECTION 1: SHORTCUTS & TRIGGERS
                    // =====================================================
                    sectionGroup(header: "SHORTCUTS & TRIGGERS") {
                        // Row 1: Global Shortcut Selector (High-contrast custom dropdown menu)
                        settingRow(
                            title: "Global Keyboard Shortcut",
                            subtitle: "Open or hide Keyboardy from any app"
                        ) {
                            Menu {
                                ForEach(HotkeyPreset.allCases) { preset in
                                    Button(action: {
                                        prefs.hotkeyPreset = preset
                                    }) {
                                        if preset == .custom {
                                            Text("Custom (\(prefs.customShortcutLabel))")
                                        } else {
                                            Text(preset.displayName)
                                        }
                                    }
                                }
                            } label: {
                                dropdownPillLabel(text: prefs.activeHotkeyShortLabel == "Off" ? "Disabled" : prefs.activeHotkeyShortLabel)
                            }
                            .menuStyle(.borderlessButton)
                            .fixedSize()
                        }

                        rowDivider

                        // Row 2: Custom Key Recorder
                        settingRow(
                            title: "Custom Shortcut Recorder",
                            subtitle: prefs.isRecordingCustomShortcut
                                ? "Press any modifier + key now (Esc to cancel)..."
                                : "Active shortcut: \(prefs.activeHotkeyShortLabel)"
                        ) {
                            Button(action: {
                                prefs.isRecordingCustomShortcut.toggle()
                            }) {
                                Text(prefs.isRecordingCustomShortcut ? "Listening..." : "Record Key Combo")
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundColor(.white)
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 5)
                                    .background(prefs.isRecordingCustomShortcut ? Color.orange : palette.accentBlue)
                                    .cornerRadius(6)
                            }
                            .buttonStyle(.plain)
                        }

                        rowDivider

                        // Row 3: Window Position on Shortcut (High-contrast segmented pills)
                        VStack(alignment: .leading, spacing: 7) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Shortcut Window Position")
                                    .font(.system(size: 12, weight: .bold))
                                    .foregroundColor(palette.textPrimary)
                                Text("Where Keyboardy appears when triggered via keyboard shortcut")
                                    .font(.system(size: 10.5))
                                    .foregroundColor(palette.textSecondary)
                            }

                            HStack(spacing: 6) {
                                segmentedPillButton(
                                    title: "Menu Bar Popover",
                                    isSelected: prefs.popupAnchorMode == .statusBarPopover
                                ) {
                                    prefs.popupAnchorMode = .statusBarPopover
                                }
                                segmentedPillButton(
                                    title: "Center Spotlight",
                                    isSelected: prefs.popupAnchorMode == .floatingCenterHud
                                ) {
                                    prefs.popupAnchorMode = .floatingCenterHud
                                }
                                segmentedPillButton(
                                    title: "Near Mouse",
                                    isSelected: prefs.popupAnchorMode == .nearMouseCursor
                                ) {
                                    prefs.popupAnchorMode = .nearMouseCursor
                                }
                            }
                        }

                        rowDivider

                        // Row 4: Zero-Typing Mode
                        settingToggleRow(
                            title: "Zero-Typing Mode (Auto-Read Chat / Selection)",
                            subtitle: "Automatically searches text highlighted or typed in your active chat box",
                            isOn: $prefs.autoReadSelectedOrChatText
                        )

                        rowDivider

                        // Row 5: Inline Text Prefix Trigger
                        VStack(spacing: 8) {
                            settingToggleRow(
                                title: "Inline Text Prefix Trigger",
                                subtitle: "Pop open Keyboardy automatically when you type a prefix anywhere",
                                isOn: $prefs.inlineTriggerEnabled
                            )

                            if prefs.inlineTriggerEnabled {
                                HStack {
                                    Text("Trigger Prefix String:")
                                        .font(.system(size: 11, weight: .semibold))
                                        .foregroundColor(palette.textSecondary)
                                    Spacer()
                                    TextField("//gif ", text: $prefs.inlineTriggerPrefix)
                                        .textFieldStyle(.plain)
                                        .font(.system(size: 11.5, weight: .bold, design: .monospaced))
                                        .foregroundColor(palette.textPrimary)
                                        .padding(.horizontal, 9)
                                        .padding(.vertical, 5)
                                        .frame(width: 110)
                                        .background(palette.surfaceVariant)
                                        .cornerRadius(6)
                                        .overlay(
                                            RoundedRectangle(cornerRadius: 6)
                                                .stroke(palette.accentBlue, lineWidth: 1)
                                        )
                                }
                                .padding(.top, 2)
                            }
                        }
                    }

                    // =====================================================
                    // SECTION 2: IMAGE INSERTION & PERMISSIONS
                    // =====================================================
                    sectionGroup(header: "IMAGE INSERTION & PERMISSIONS") {
                        // Row 1: Accessibility Permission
                        settingRow(
                            title: prefs.isAccessibilityGranted
                                ? "Accessibility Permission Enabled"
                                : "Enable Accessibility for 1-Step Auto-Paste",
                            subtitle: prefs.isAccessibilityGranted
                                ? "Keyboardy can directly paste (⌘V) into Slack, Messages, and Discord"
                                : "Ensure switch is ON in Settings > Device Control (if ON, toggle OFF & ON to refresh)"
                        ) {
                            Button(action: {
                                prefs.isAccessibilityGranted = ImageCommitService.shared.openAndRefreshAccessibilityPermission()
                            }) {
                                Text(prefs.isAccessibilityGranted ? "Recheck" : "Open Settings")
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundColor(prefs.isAccessibilityGranted ? palette.textPrimary : .white)
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 5)
                                    .background(prefs.isAccessibilityGranted ? palette.surfaceVariant : palette.accentBlue)
                                    .cornerRadius(6)
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 6)
                                            .stroke(palette.border, lineWidth: prefs.isAccessibilityGranted ? 1 : 0)
                                    )
                            }
                            .buttonStyle(.plain)
                        }

                        rowDivider

                        // Row 2: Click / Return Behavior (High-contrast segmented pills)
                        settingRow(
                            title: "When Clicking or Pressing Return",
                            subtitle: "Choose whether to paste immediately or only copy to clipboard"
                        ) {
                            HStack(spacing: 5) {
                                segmentedPillButton(
                                    title: "Auto-Paste (⌘V)",
                                    isSelected: prefs.insertBehaviorMode == .autoPasteIntoApp
                                ) {
                                    prefs.insertBehaviorMode = .autoPasteIntoApp
                                }
                                segmentedPillButton(
                                    title: "Copy Only",
                                    isSelected: prefs.insertBehaviorMode == .copyToClipboardOnly
                                ) {
                                    prefs.insertBehaviorMode = .copyToClipboardOnly
                                }
                            }
                            .frame(width: 210)
                        }

                        rowDivider

                        // Row 3: Restore Clipboard
                        settingToggleRow(
                            title: "Preserve Text Clipboard After Auto-Paste",
                            subtitle: "Restores your previous copied text 450ms after inserting an image",
                            isOn: $prefs.restoreClipboardAfterAutoPaste
                        )
                    }

                    // =====================================================
                    // SECTION 3: APPEARANCE & SEARCH ENGINE
                    // =====================================================
                    sectionGroup(header: "APPEARANCE & SEARCH ENGINE") {
                        // Row 1: Menu Bar Icon Style (App Icon, White Icon for Night, Black Icon for Light)
                        VStack(alignment: .leading, spacing: 7) {
                            HStack {
                                Text("Menu Bar Icon Style")
                                    .font(.system(size: 12, weight: .bold))
                                    .foregroundColor(palette.textPrimary)
                                Spacer()
                                Text("White = Night Mode • Black = Light Mode")
                                    .font(.system(size: 10, weight: .semibold))
                                    .foregroundColor(palette.textSecondary)
                            }

                            HStack(spacing: 8) {
                                badgeOptionButton(.keyboardyAppIcon, label: "App Icon")
                                badgeOptionButton(.keyboardyWhiteIcon, label: "White (Night)")
                                badgeOptionButton(.keyboardyBlackIcon, label: "Black (Light)")
                            }
                        }

                        rowDivider

                        // Row 2: Rewired App Theme Mode (System Auto / Dark / Light)
                        settingRow(
                            title: "App Theme & Icon Mode",
                            subtitle: "System Auto, Night Mode (White Icon), or Light Mode (Black Icon)"
                        ) {
                            HStack(spacing: 5) {
                                ForEach(AppThemeMode.allCases) { mode in
                                    segmentedPillButton(
                                        title: mode.displayName,
                                        isSelected: prefs.themeMode == mode
                                    ) {
                                        prefs.themeMode = mode
                                    }
                                }
                            }
                            .frame(width: 225)
                        }

                        rowDivider

                        // Row 3: Default Search Engine Mode (High-contrast segmented pills)
                        VStack(alignment: .leading, spacing: 7) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Search Engine Mode")
                                    .font(.system(size: 12, weight: .bold))
                                    .foregroundColor(palette.textPrimary)
                                Text("Hybrid combines Instant Web (⚡) with Google Images WebView (G)")
                                    .font(.system(size: 10.5))
                                    .foregroundColor(palette.textSecondary)
                            }

                            HStack(spacing: 6) {
                                segmentedPillButton(
                                    title: "Hybrid (G + ⚡)",
                                    isSelected: prefs.engineMode == .hybrid
                                ) {
                                    prefs.engineMode = .hybrid
                                }
                                segmentedPillButton(
                                    title: "Google WebView (G)",
                                    isSelected: prefs.engineMode == .googleWebView
                                ) {
                                    prefs.engineMode = .googleWebView
                                }
                                segmentedPillButton(
                                    title: "Instant Web (⚡)",
                                    isSelected: prefs.engineMode == .instantHttp
                                ) {
                                    prefs.engineMode = .instantHttp
                                }
                            }
                        }

                        rowDivider

                        // Row 4: Unfiltered Mode
                        settingToggleRow(
                            title: "Unfiltered Mode (SafeSearch Off)",
                            subtitle: "Disable SafeSearch filtering on Google Images and Instant Web",
                            isOn: $prefs.nsfwModeEnabled
                        )
                    }

                    // =====================================================
                    // SECTION 4: USAGE QUOTA & OPTIONAL API KEY
                    // =====================================================
                    sectionGroup(header: "USAGE STATS & OPTIONAL API KEY") {
                        HStack(spacing: 8) {
                            statMetricBox(value: "\(prefs.searchesToday)", label: "Searches Today")
                            statMetricBox(value: "\(prefs.searchesThisMonth)", label: "This Month")
                            statMetricBox(value: "\(prefs.imagesInsertedToday)", label: "Inserted Today")
                            statMetricBox(value: "\(prefs.imagesInsertedTotal)", label: "All-Time")
                        }

                        rowDivider

                        VStack(alignment: .leading, spacing: 6) {
                            Text("Optional SerpApi or Brave Search API Key")
                                .font(.system(size: 12, weight: .bold))
                                .foregroundColor(palette.textPrimary)
                            Text("Leave blank to use the 100% free built-in Hybrid (G + ⚡) engine with unlimited searches.")
                                .font(.system(size: 10.5))
                                .foregroundColor(palette.textSecondary)

                            HStack(spacing: 6) {
                                Image(systemName: "key.fill")
                                    .font(.system(size: 11))
                                    .foregroundColor(palette.textSecondary)

                                TextField("Paste optional API key (or leave empty for free Hybrid)...", text: $prefs.optionalApiKey)
                                    .textFieldStyle(.plain)
                                    .font(.system(size: 11.5, design: .monospaced))
                                    .foregroundColor(palette.textPrimary)

                                if !prefs.optionalApiKey.isEmpty {
                                    Button(action: { prefs.optionalApiKey = "" }) {
                                        Image(systemName: "xmark.circle.fill")
                                            .font(.system(size: 12))
                                            .foregroundColor(palette.textSecondary)
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                            .padding(.horizontal, 10)
                            .padding(.vertical, 7)
                            .background(palette.surfaceVariant)
                            .cornerRadius(8)
                            .overlay(
                                RoundedRectangle(cornerRadius: 8)
                                    .stroke(palette.border, lineWidth: 1)
                            )
                        }
                    }
                }
                .padding(14)
            }
        }

    // MARK: - About Keyboardy & What's New Content

    private var aboutScrollView: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                // 1. HERO IDENTITY CARD
                sectionGroup(header: "APPLICATION") {
                    HStack(spacing: 14) {
                        Image(nsImage: StatusBarBadgeRenderer.loadAppIconImage())
                            .resizable()
                            .interpolation(.high)
                            .aspectRatio(contentMode: .fit)
                            .frame(width: 48, height: 48)
                            .cornerRadius(12)
                            .overlay(
                                RoundedRectangle(cornerRadius: 12)
                                    .stroke(palette.border.opacity(0.8), lineWidth: 1)
                            )
                            .shadow(color: Color.black.opacity(0.15), radius: 3, x: 0, y: 1.5)

                        VStack(alignment: .leading, spacing: 3) {
                            HStack(spacing: 6) {
                                Text("Keyboardy")
                                    .font(.system(size: 16, weight: .black, design: .rounded))
                                    .foregroundColor(palette.textPrimary)

                                Text("macOS")
                                    .font(.system(size: 10, weight: .black, design: .rounded))
                                    .foregroundColor(palette.accentBlue)
                                    .padding(.horizontal, 6)
                                    .padding(.vertical, 2)
                                    .background(palette.accentBlue.opacity(0.12))
                                    .cornerRadius(6)
                            }

                            Text("Instant Auxiliary Google Image & GIF Search Companion")
                                .font(.system(size: 11, weight: .medium))
                                .foregroundColor(palette.textSecondary)
                        }

                        Spacer()

                        VStack(alignment: .trailing, spacing: 3) {
                            Text(KeyboardyVersion.displayString)
                                .font(.system(size: 11, weight: .black, design: .monospaced))
                                .foregroundColor(palette.accentBlue)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 3.5)
                                .background(palette.accentBlue.opacity(0.12))
                                .cornerRadius(7)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 7)
                                        .stroke(palette.accentBlue.opacity(0.35), lineWidth: 1)
                                )

                            Text("Build \(KeyboardyVersion.buildNumber)")
                                .font(.system(size: 9.5, weight: .bold, design: .monospaced))
                                .foregroundColor(palette.textSecondary)
                        }
                    }

                    rowDivider

                    // Quick Diagnostic Status Bar
                    HStack(spacing: 12) {
                        HStack(spacing: 5) {
                            Circle()
                                .fill(prefs.isAccessibilityGranted ? palette.successGreen : Color.orange)
                                .frame(width: 7, height: 7)
                            Text(prefs.isAccessibilityGranted ? "1-Click Auto-Paste Ready" : "Accessibility Needed")
                                .font(.system(size: 10, weight: .bold))
                                .foregroundColor(prefs.isAccessibilityGranted ? palette.successGreen : Color.orange)
                        }

                        Spacer()

                        HStack(spacing: 5) {
                            Image(systemName: "command")
                                .font(.system(size: 10, weight: .bold))
                            Text("Hotkey: \(prefs.activeHotkeyShortLabel)")
                                .font(.system(size: 10, weight: .bold))
                        }
                        .foregroundColor(palette.textSecondary)

                        HStack(spacing: 5) {
                            Image(systemName: "cpu")
                                .font(.system(size: 10, weight: .bold))
                            Text("Universal (Apple Silicon & Intel)")
                                .font(.system(size: 10, weight: .bold))
                        }
                        .foregroundColor(palette.textSecondary)
                    }
                    .padding(.top, 2)
                }

                // 2. ABOUT & WORKFLOW HIGHLIGHTS
                sectionGroup(header: "ZERO-STEP WORKFLOW") {
                    Text("Keyboardy is engineered to eliminate the friction of manual image searching, browser tabs, downloads folders, and file-picker dialogs when sharing pictures and animated GIFs in chat.")
                        .font(.system(size: 11.5))
                        .foregroundColor(palette.textPrimary)
                        .lineSpacing(2.5)
                        .fixedSize(horizontal: false, vertical: true)

                    rowDivider

                    // Feature Pillars with macOS SF Symbols
                    VStack(alignment: .leading, spacing: 9) {
                        aboutFeatureRow(
                            icon: "bolt.fill",
                            iconColor: Color.yellow,
                            title: "Instant 1-Click Auto-Paste (⌘V)",
                            description: "Restores focus to your previous app (Slack, Messages, Discord, WhatsApp, Chrome) and automatically executes a hardware-accurate ⌘V keystroke."
                        )

                        aboutFeatureRow(
                            icon: "hand.draw.fill",
                            iconColor: palette.accentBlue,
                            title: "Native Drag-and-Drop",
                            description: "Drag any picture or animated GIF thumbnail directly into your conversation input, browser window, or Desktop."
                        )

                        aboutFeatureRow(
                            icon: "play.circle.fill",
                            iconColor: Color.green,
                            title: "Infinite Looping Tenor GIFs",
                            description: "Streams high-speed Tenor animations with ~60ms preview loading and dedicated RunLoop frame-stepping for continuous playback."
                        )

                        aboutFeatureRow(
                            icon: "lock.shield.fill",
                            iconColor: Color.blue,
                            title: "Zero Telemetry & 100% Private",
                            description: "Direct Google WebView and Instant search connections only. No analytics, tracking, or keystroke logging."
                        )
                    }
                }

                // 3. RELEASE NOTES & RECENT IMPROVEMENTS
                sectionGroup(header: "RELEASE NOTES & WHAT'S NEW") {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Keyboardy for macOS \(KeyboardyVersion.displayString)")
                                .font(.system(size: 12.5, weight: .bold))
                                .foregroundColor(palette.textPrimary)
                            Text("Release Candidate 1 • \(KeyboardyVersion.releaseDate)")
                                .font(.system(size: 10.5))
                                .foregroundColor(palette.textSecondary)
                        }

                        Spacer()

                        Text("Current Build")
                            .font(.system(size: 10, weight: .heavy))
                            .foregroundColor(palette.successGreen)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 3)
                            .background(palette.successGreen.opacity(0.12))
                            .cornerRadius(6)
                    }

                    rowDivider

                    VStack(alignment: .leading, spacing: 8) {
                        ForEach(KeyboardyVersion.recentImprovements.prefix(6), id: \.title) { item in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(item.title)
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundColor(palette.textPrimary)
                                Text(item.detail)
                                    .font(.system(size: 10))
                                    .foregroundColor(palette.textSecondary)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                            .padding(.vertical, 1)
                        }
                    }
                }

                // 4. QUICK ACTIONS & LINKS
                sectionGroup(header: "LINKS & RESOURCES") {
                    HStack(spacing: 8) {
                        linkButton(
                            title: "Full Changelog",
                            icon: "doc.text.magnifyingglass",
                            url: "https://github.com/medusko/Keyboardy/blob/main/CHANGELOG.md"
                        )

                        linkButton(
                            title: "All Releases",
                            icon: "arrow.down.circle",
                            url: "https://github.com/medusko/Keyboardy/releases"
                        )

                        linkButton(
                            title: "Source Code",
                            icon: "curlybraces",
                            url: "https://github.com/medusko/Keyboardy"
                        )

                        linkButton(
                            title: "Sponsor ☕",
                            icon: "heart.fill",
                            url: "https://github.com/sponsors/Zlatty"
                        )
                    }

                    rowDivider

                    // Contact & Web
                    VStack(alignment: .leading, spacing: 5) {
                        HStack {
                            Text("Website:")
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundColor(.secondary)
                                .frame(width: 60, alignment: .leading)
                            Link("medusko.com", destination: URL(string: "https://medusko.com")!)
                                .font(.system(size: 11, weight: .bold))
                        }
                        HStack {
                            Text("Support:")
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundColor(.secondary)
                                .frame(width: 60, alignment: .leading)
                            Link("support@medusko.com", destination: URL(string: "mailto:support@medusko.com")!)
                                .font(.system(size: 11, weight: .bold))
                        }
                        HStack {
                            Text("Social:")
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundColor(.secondary)
                                .frame(width: 60, alignment: .leading)
                            Link("@meduskodev on Twitter", destination: URL(string: "https://twitter.com/meduskodev")!)
                                .font(.system(size: 11, weight: .bold))
                        }
                    }
                    .padding(.vertical, 2)

                    rowDivider

                    HStack(spacing: 8) {
                        Button(action: {
                            _ = ImageCommitService.shared.openAndRefreshAccessibilityPermission()
                        }) {
                            HStack(spacing: 5) {
                                Image(systemName: "gearshape.2")
                                    .font(.system(size: 11, weight: .bold))
                                Text("macOS Accessibility Settings")
                                    .font(.system(size: 11, weight: .bold))
                            }
                            .foregroundColor(palette.accentBlue)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 6)
                            .background(palette.surfaceVariant)
                            .cornerRadius(7)
                            .overlay(
                                RoundedRectangle(cornerRadius: 7)
                                    .stroke(palette.border, lineWidth: 1)
                            )
                        }
                        .buttonStyle(.plain)

                        Spacer()

                        Button(action: {
                            let testSitePath = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
                                .appendingPathComponent("test_site/index.html")
                            if FileManager.default.fileExists(atPath: testSitePath.path) {
                                NSWorkspace.shared.open(testSitePath)
                            } else if let repoUrl = URL(string: "https://github.com/medusko/Keyboardy") {
                                NSWorkspace.shared.open(repoUrl)
                            }
                        }) {
                            HStack(spacing: 5) {
                                Image(systemName: "testtube.2")
                                    .font(.system(size: 11, weight: .bold))
                                Text("Launch Test Studio")
                                    .font(.system(size: 11, weight: .bold))
                            }
                            .foregroundColor(palette.textSecondary)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 6)
                            .background(palette.surfaceVariant)
                            .cornerRadius(7)
                            .overlay(
                                RoundedRectangle(cornerRadius: 7)
                                    .stroke(palette.border, lineWidth: 1)
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }

                // 5. TRADEMARK & LEGAL DISCLAIMER
                sectionGroup(header: "TRADEMARKS & LEGAL NOTICE") {
                    Text("Google, Android, Gboard, Google Messages, and Google Images are trademarks of Google LLC. Tenor is a trademark of Tenor, Inc. / Google LLC. Slack is a trademark of Slack Technologies, LLC / Salesforce, Inc. WhatsApp is a trademark of Meta Platforms, Inc. Discord is a trademark of Discord Inc. Keyboardy is an independent product developed by Medusko and is not affiliated with, endorsed by, or sponsored by Google LLC, Tenor, Slack, Meta, Discord, or any other referenced company. All trademarks and registered trademarks are the property of their respective owners.")
                        .font(.system(size: 10))
                        .foregroundColor(palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }

                // 6. FOOTER
                HStack {
                    Spacer()
                    Text("Designed & Engineered for macOS by Medusko • Keyboardy \(KeyboardyVersion.detailedDisplayString)")
                        .font(.system(size: 9.5, weight: .medium))
                        .foregroundColor(palette.textSecondary.opacity(0.8))
                    Spacer()
                }
                .padding(.top, 4)
                .padding(.bottom, 6)
            }
            .padding(14)
        }
    }

    private func aboutFeatureRow(icon: String, iconColor: Color, title: String, description: String) -> some View {
        HStack(alignment: .top, spacing: 9) {
            Image(systemName: icon)
                .font(.system(size: 12, weight: .bold))
                .foregroundColor(iconColor)
                .frame(width: 18, height: 18)
                .padding(4)
                .background(iconColor.opacity(0.12))
                .cornerRadius(6)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 11.5, weight: .bold))
                    .foregroundColor(palette.textPrimary)
                Text(description)
                    .font(.system(size: 10.5))
                    .foregroundColor(palette.textSecondary)
                    .lineSpacing(1.5)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func linkButton(title: String, icon: String, url: String) -> some View {
        Button(action: {
            if let targetUrl = URL(string: url) {
                NSWorkspace.shared.open(targetUrl)
            }
        }) {
            HStack(spacing: 5) {
                Image(systemName: icon)
                    .font(.system(size: 10.5, weight: .bold))
                Text(title)
                    .font(.system(size: 11, weight: .bold))
            }
            .foregroundColor(palette.accentBlue)
            .padding(.horizontal, 9)
            .padding(.vertical, 6)
            .frame(maxWidth: .infinity)
            .background(palette.surfaceVariant)
            .cornerRadius(7)
            .overlay(
                RoundedRectangle(cornerRadius: 7)
                    .stroke(palette.border, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }

    // MARK: - Reusable High-Contrast Components (Explicit Palette Colors in Dark & Light Mode)

    private var rowDivider: some View {
        Divider()
            .background(palette.border.opacity(0.8))
            .padding(.vertical, 2)
    }

    @ViewBuilder
    private func sectionGroup<Content: View>(header: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(header)
                .font(.system(size: 10.5, weight: .heavy))
                .tracking(0.8)
                .foregroundColor(palette.accentBlue)
                .padding(.leading, 4)

            VStack(alignment: .leading, spacing: 10) {
                content()
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(palette.surface)
            .cornerRadius(12)
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(palette.border, lineWidth: 1)
            )
        }
    }

    @ViewBuilder
    private func settingRow<Control: View>(
        title: String,
        subtitle: String,
        @ViewBuilder control: () -> Control
    ) -> some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 12, weight: .bold))
                    .foregroundColor(palette.textPrimary)
                Text(subtitle)
                    .font(.system(size: 10.5))
                    .foregroundColor(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 8)
            control()
        }
    }

    /// Custom high-contrast switch toggle so the OFF state track & thumb never blend into Dark Mode surfaces.
    @ViewBuilder
    private func settingToggleRow(
        title: String,
        subtitle: String,
        isOn: Binding<Bool>
    ) -> some View {
        settingRow(title: title, subtitle: subtitle) {
            Button(action: {
                withAnimation(.easeInOut(duration: 0.14)) {
                    isOn.wrappedValue.toggle()
                }
            }) {
                ZStack(alignment: isOn.wrappedValue ? .trailing : .leading) {
                    Capsule()
                        .fill(isOn.wrappedValue ? palette.accentBlue : palette.surfaceVariant)
                        .frame(width: 38, height: 21)
                        .overlay(
                            Capsule()
                                .stroke(isOn.wrappedValue ? palette.accentBluePressed : palette.textSecondary.opacity(0.55), lineWidth: 1.2)
                        )

                    Circle()
                        .fill(Color.white)
                        .shadow(color: Color.black.opacity(0.25), radius: 1, x: 0, y: 1)
                        .frame(width: 15, height: 15)
                        .padding(.horizontal, 3)
                }
            }
            .buttonStyle(.plain)
        }
    }

    @ViewBuilder
    private func dropdownPillLabel(text: String) -> some View {
        HStack(spacing: 8) {
            Text(text)
                .font(.system(size: 11.5, weight: .bold))
                .foregroundColor(palette.textPrimary)
            Image(systemName: "chevron.up.chevron.down")
                .font(.system(size: 9.5, weight: .bold))
                .foregroundColor(palette.accentBlue)
        }
        .padding(.horizontal, 11)
        .padding(.vertical, 6)
        .background(palette.surfaceVariant)
        .cornerRadius(7)
        .overlay(
            RoundedRectangle(cornerRadius: 7)
                .stroke(palette.border, lineWidth: 1.2)
        )
    }

    @ViewBuilder
    private func segmentedPillButton(
        title: String,
        isSelected: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 10.5, weight: .bold))
                .foregroundColor(isSelected ? .white : palette.textPrimary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 6)
                .background(isSelected ? palette.accentBlue : palette.surfaceVariant)
                .cornerRadius(7)
                .overlay(
                    RoundedRectangle(cornerRadius: 7)
                        .stroke(isSelected ? Color.clear : palette.border, lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private func badgeOptionButton(_ style: StatusBarBadgeStyle, label: String) -> some View {
        let isSelected = prefs.statusBarBadgeStyle == style
        let previewBackdrop: Color = {
            switch style {
            case .keyboardyWhiteIcon:
                return Color(red: 0.14, green: 0.16, blue: 0.20)
            case .keyboardyBlackIcon:
                return Color(red: 0.93, green: 0.91, blue: 0.87)
            case .keyboardyAppIcon:
                return Color.clear
            }
        }()
        Button(action: { prefs.statusBarBadgeStyle = style }) {
            HStack(spacing: 5) {
                ZStack {
                    if style == .keyboardyWhiteIcon || style == .keyboardyBlackIcon {
                        RoundedRectangle(cornerRadius: 4)
                            .fill(previewBackdrop)
                            .frame(width: 20, height: 20)
                    }
                    Image(nsImage: StatusBarBadgeRenderer.makeStatusBarImage(style: style))
                        .resizable()
                        .interpolation(.high)
                        .frame(width: 16, height: 16)
                }
                Text(label)
                    .font(.system(size: 11, weight: .bold))
                    .foregroundColor(isSelected ? .white : palette.textPrimary)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 6)
            .background(isSelected ? palette.accentBlue : palette.surfaceVariant)
            .cornerRadius(7)
            .overlay(
                RoundedRectangle(cornerRadius: 7)
                    .stroke(isSelected ? Color.clear : palette.border, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private func statMetricBox(value: String, label: String) -> some View {
        VStack(spacing: 2) {
            Text(value)
                .font(.system(size: 15, weight: .black, design: .rounded))
                .foregroundColor(palette.accentBlue)
            Text(label)
                .font(.system(size: 9.5, weight: .semibold))
                .foregroundColor(palette.textSecondary)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 7)
        .background(palette.surfaceVariant)
        .cornerRadius(8)
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(palette.border, lineWidth: 1)
        )
    }
}
