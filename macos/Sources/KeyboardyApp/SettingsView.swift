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
            // Header Bar
            HStack(spacing: 10) {
                Button(action: onCloseSettings) {
                    HStack(spacing: 4) {
                        Image(systemName: "chevron.left")
                            .font(.system(size: 12, weight: .bold))
                        Text("Back to Search")
                            .font(.system(size: 12.5, weight: .bold))
                    }
                    .foregroundColor(palette.textPrimary)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(palette.surfaceVariant)
                    .cornerRadius(8)
                    .overlay(
                        RoundedRectangle(cornerRadius: 8)
                            .stroke(palette.border, lineWidth: 1)
                    )
                }
                .buttonStyle(.plain)

                Spacer()

                Text("Keyboardy macOS Settings")
                    .font(.system(size: 14, weight: .heavy))
                    .foregroundColor(palette.textPrimary)

                Spacer()

                Button(action: { prefs.isDarkMode.toggle() }) {
                    Image(systemName: prefs.isDarkMode ? "sun.max.fill" : "moon.fill")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(palette.accentBlue)
                        .frame(width: 28, height: 28)
                        .background(palette.surfaceVariant)
                        .cornerRadius(8)
                }
                .buttonStyle(.plain)
                .help("Toggle Dark / Light Theme")
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(palette.surface)

            Divider().background(palette.border)

            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    // 1. STATUS BAR & TRIGGER CONFIGURATION CARD
                    settingsCard(title: "1. Status Bar & Trigger Configuration") {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("Menu Bar Icon Style (Updates Live):")
                                .font(.system(size: 11.5, weight: .semibold))
                                .foregroundColor(palette.textSecondary)

                            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 6) {
                                ForEach(StatusBarBadgeStyle.allCases) { style in
                                    let isSelected = prefs.statusBarBadgeStyle == style
                                    Button(action: { prefs.statusBarBadgeStyle = style }) {
                                        Text(style.displayName)
                                            .font(.system(size: 11.5, weight: .bold))
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
                            }

                            Divider().background(palette.border).padding(.vertical, 2)

                            HStack {
                                Text("Global Shortcut:")
                                    .font(.system(size: 12, weight: .semibold))
                                    .foregroundColor(palette.textPrimary)
                                Spacer()
                                Picker("", selection: $prefs.hotkeyPreset) {
                                    ForEach(HotkeyPreset.allCases) { preset in
                                        if preset == .custom {
                                            Text("Custom (\(prefs.customShortcutLabel))").tag(preset)
                                        } else {
                                            Text(preset.displayName).tag(preset)
                                        }
                                    }
                                }
                                .labelsHidden()
                                .frame(width: 225)
                            }

                            HStack(spacing: 8) {
                                Text("Or record your own key combo:")
                                    .font(.system(size: 11))
                                    .foregroundColor(palette.textSecondary)
                                Spacer()
                                Button(action: {
                                    prefs.isRecordingCustomShortcut.toggle()
                                }) {
                                    Text(prefs.isRecordingCustomShortcut
                                         ? "Listening... Press keys now (Esc to cancel)"
                                         : "Record Custom Shortcut (\(prefs.activeHotkeyShortLabel))")
                                        .font(.system(size: 11, weight: .bold))
                                        .foregroundColor(.white)
                                        .padding(.horizontal, 10)
                                        .padding(.vertical, 5)
                                        .background(prefs.isRecordingCustomShortcut ? Color.orange : palette.accentBlue)
                                        .cornerRadius(6)
                                }
                                .buttonStyle(.plain)
                            }

                            HStack {
                                Text("Shortcut Window Mode:")
                                    .font(.system(size: 12, weight: .semibold))
                                    .foregroundColor(palette.textPrimary)
                                Spacer()
                                Picker("", selection: $prefs.popupAnchorMode) {
                                    ForEach(PopupAnchorMode.allCases) { mode in
                                        Text(mode.displayName).tag(mode)
                                    }
                                }
                                .labelsHidden()
                                .frame(width: 225)
                            }

                            Toggle(isOn: $prefs.autoReadSelectedOrChatText) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("Zero-Typing Mode: Auto-Read Active Selection / Chat Input")
                                        .font(.system(size: 12, weight: .semibold))
                                        .foregroundColor(palette.textPrimary)
                                    Text("When triggered via shortcut, automatically searches words you highlighted or typed in chat.")
                                        .font(.system(size: 10.5))
                                        .foregroundColor(palette.textSecondary)
                                }
                            }
                            .toggleStyle(.checkbox)

                            Toggle(isOn: $prefs.inlineTriggerEnabled) {
                                HStack(spacing: 8) {
                                    Text("Inline Text Prefix Trigger:")
                                        .font(.system(size: 12, weight: .semibold))
                                        .foregroundColor(palette.textPrimary)
                                    TextField("//gif ", text: $prefs.inlineTriggerPrefix)
                                        .textFieldStyle(.roundedBorder)
                                        .frame(width: 80)
                                        .font(.system(size: 11.5, design: .monospaced))
                                    Text("(Type anywhere to pop open)")
                                        .font(.system(size: 10.5))
                                        .foregroundColor(palette.textSecondary)
                                }
                            }
                            .toggleStyle(.checkbox)
                        }
                    }

                    // 2. IMAGE INSERTION & ACCESSIBILITY CARD
                    settingsCard(title: "2. Image Insertion & Auto-Paste") {
                        VStack(alignment: .leading, spacing: 10) {
                            HStack {
                                Text("On Image Click / Return:")
                                    .font(.system(size: 12, weight: .semibold))
                                    .foregroundColor(palette.textPrimary)
                                Spacer()
                                Picker("", selection: $prefs.insertBehaviorMode) {
                                    ForEach(InsertBehaviorMode.allCases) { mode in
                                        Text(mode.displayName).tag(mode)
                                    }
                                }
                                .labelsHidden()
                                .frame(width: 245)
                            }

                            Toggle(isOn: $prefs.restoreClipboardAfterAutoPaste) {
                                Text("Restore previous text clipboard 450ms after auto-pasting")
                                    .font(.system(size: 12))
                                    .foregroundColor(palette.textPrimary)
                            }
                            .toggleStyle(.checkbox)

                            // Accessibility Permission Status Banner
                            HStack(spacing: 10) {
                                Circle()
                                    .fill(prefs.isAccessibilityGranted ? palette.successGreen : Color.orange)
                                    .frame(width: 9, height: 9)

                                VStack(alignment: .leading, spacing: 2) {
                                    Text(prefs.isAccessibilityGranted
                                         ? "Accessibility Enabled — Direct Auto-Paste (⌘V) Ready"
                                         : "Accessibility Needed for 1-Step Auto-Paste (Currently Copies to Clipboard)")
                                        .font(.system(size: 11.5, weight: .bold))
                                        .foregroundColor(palette.textPrimary)
                                    Text("Allows Keyboardy to synthesize ⌘V into Slack/Messages/Discord and read selected text.")
                                        .font(.system(size: 10.5))
                                        .foregroundColor(palette.textSecondary)
                                }

                                Spacer()

                                Button(action: {
                                    prefs.isAccessibilityGranted = ImageCommitService.shared.isAccessibilityTrusted(promptIfNeeded: true)
                                }) {
                                    Text(prefs.isAccessibilityGranted ? "Recheck" : "Grant Access")
                                        .font(.system(size: 11, weight: .bold))
                                        .foregroundColor(.white)
                                        .padding(.horizontal, 10)
                                        .padding(.vertical, 5)
                                        .background(palette.accentBlue)
                                        .cornerRadius(6)
                                }
                                .buttonStyle(.plain)
                            }
                            .padding(10)
                            .background(palette.surfaceVariant)
                            .cornerRadius(8)
                        }
                    }

                    // 3. SEARCH ENGINE, SAFESEARCH & USAGE STATS CARD
                    settingsCard(title: "3. Hybrid Search Engine & Usage Quota") {
                        VStack(alignment: .leading, spacing: 10) {
                            Toggle(isOn: $prefs.nsfwModeEnabled) {
                                Text("Unfiltered / NSFW Search Mode (SafeSearch Off)")
                                    .font(.system(size: 12, weight: .semibold))
                                    .foregroundColor(palette.textPrimary)
                            }
                            .toggleStyle(.checkbox)

                            VStack(alignment: .leading, spacing: 4) {
                                Text("Searches Today: \(prefs.searchesToday)   •   This Month: \(prefs.searchesThisMonth)")
                                    .font(.system(size: 11.5, weight: .bold))
                                    .foregroundColor(palette.textPrimary)
                                Text("Images Inserted Today: \(prefs.imagesInsertedToday)   •   All-Time: \(prefs.imagesInsertedTotal) (0 API quota used)")
                                    .font(.system(size: 11))
                                    .foregroundColor(palette.textSecondary)
                                Text("Active Engine: \(prefs.engineMode.displayName) — 100% Free Unlimited")
                                    .font(.system(size: 10.5, weight: .semibold))
                                    .foregroundColor(palette.accentBlue)
                            }
                            .padding(10)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(palette.surfaceVariant)
                            .cornerRadius(8)

                            VStack(alignment: .leading, spacing: 4) {
                                Text("Optional SerpApi or Brave Search API Key (Leave blank for 100% free Hybrid):")
                                    .font(.system(size: 11))
                                    .foregroundColor(palette.textSecondary)
                                TextField("Paste optional SerpApi or Brave API key...", text: $prefs.optionalApiKey)
                                    .textFieldStyle(.roundedBorder)
                                    .font(.system(size: 11.5, design: .monospaced))
                            }
                        }
                    }
                }
                .padding(14)
            }
        }
        .background(palette.background)
        .onAppear {
            prefs.isAccessibilityGranted = ImageCommitService.shared.isAccessibilityTrusted()
        }
    }

    @ViewBuilder
    private func settingsCard<Content: View>(title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title)
                .font(.system(size: 12.5, weight: .heavy))
                .foregroundColor(palette.accentBlue)
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
