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
                Button(action: onCloseSettings) {
                    HStack(spacing: 5) {
                        Image(systemName: "chevron.left")
                            .font(.system(size: 11.5, weight: .bold))
                        Text("Back")
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

                Text("Keyboardy Settings")
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
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(palette.surface)

            Divider().background(palette.border)

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
                                : "Required so Keyboardy can send ⌘V automatically into your active chat"
                        ) {
                            Button(action: {
                                prefs.isAccessibilityGranted = ImageCommitService.shared.isAccessibilityTrusted(promptIfNeeded: true)
                            }) {
                                Text(prefs.isAccessibilityGranted ? "Recheck" : "Grant Access")
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
                        // Row 1: Menu Bar Icon Style (App Icon, White Icon, Black Icon)
                        VStack(alignment: .leading, spacing: 7) {
                            HStack {
                                Text("Menu Bar Icon Style")
                                    .font(.system(size: 12, weight: .bold))
                                    .foregroundColor(palette.textPrimary)
                                Spacer()
                                Text("Auto-switches with Theme, or pick manually")
                                    .font(.system(size: 10))
                                    .foregroundColor(palette.textSecondary)
                            }

                            HStack(spacing: 8) {
                                badgeOptionButton(.keyboardyAppIcon, label: "App Icon")
                                badgeOptionButton(.keyboardyWhiteIcon, label: "White Icon")
                                badgeOptionButton(.keyboardyBlackIcon, label: "Black Icon")
                            }
                        }

                        rowDivider

                        // Row 2: Rewired App Theme Mode (System Auto / Dark / Light)
                        settingRow(
                            title: "App Theme & Icon Mode",
                            subtitle: "Follow macOS System Settings or choose Dark (White Icon) / Light (Black Icon)"
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

                        // Row 4: Unfiltered / NSFW Mode
                        settingToggleRow(
                            title: "Unfiltered Search (SafeSearch Off)",
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
        .background(palette.background)
        .preferredColorScheme(prefs.isDarkMode ? .dark : .light)
        .onAppear {
            prefs.isAccessibilityGranted = ImageCommitService.shared.isAccessibilityTrusted()
        }
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
        Button(action: { prefs.statusBarBadgeStyle = style }) {
            HStack(spacing: 6) {
                Image(nsImage: StatusBarBadgeRenderer.makeStatusBarImage(style: style))
                    .resizable()
                    .interpolation(.high)
                    .frame(width: 16, height: 16)
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
