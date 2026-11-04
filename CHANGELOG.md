# Changelog

All notable changes to the **Keyboardy** project (Android & macOS) will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [macos-v1.0.1-beta.1] - 2026-11-03

### Added
- **Fast Tenor GIF Integration:** Integrated streaming animated GIF extraction from Tenor (`https://tenor.com/search/<slug>-gifs`). Uses lightweight `AAAAM` animated GIF streams (~100 KB) so preview tiles animate in the search grid within ~60–100ms.
- **Native macOS Drag-and-Drop (`NSDraggingSource`):** Users can now either click any image/GIF to auto-paste, or click and drag any tile out of Keyboardy and drop it directly into Messages, Slack, Discord, Chrome, Safari, Notes, or Finder.
- **In-App Beta Version & Improvements Tracker:** Added a dedicated "BETA VERSION & WHAT'S NEW" section in Settings displaying current build details (`v1.0.1-beta.1 Build 2`), recent highlights, and direct links to the changelog and release page.
- **Single-Source Version Tracking:** Added `macos/VERSION` and `KeyboardyVersion` in `KeyboardyCore` to ensure every beta release has an explicit, traceable sub-version number across packaging, bundle plists, and UI tooltips.

### Fixed
- **GIF Playback & Continuous Looping:** Fixed AppKit layer freezing inside SwiftUI hosting views by implementing `override var wantsUpdateLayer: Bool { true }` + `override func updateLayer()` driven by a `RunLoop.main` (`.common` mode) timer. Multi-frame GIFs now loop indefinitely.
- **Search Result Accuracy & 45+ Image Results:** Replaced non-persistent WebViews with persistent `WKWebsiteDataStore` and anti-poisoning query token validation. Prevents Google `/sorry/index` CAPTCHA redirects and Bing anti-bot fallback, restoring 45+ accurate images per search.
- **1-Step Direct Auto-Paste (⌘V):** Fixed issue where clicking an image copied to clipboard but did not auto-insert. Now tracks both app activation and deactivation, yields focus to the active chat app, waits for window focus to settle, and synthesizes ⌘V reliably with AppleScript System Events fallback.
- **Dark/Light Mode Icon Synchronization:** Night/Dark Mode now cleanly selects the White Icon (`.keyboardyWhiteIcon`), and Light Mode selects the Black Icon (`.keyboardyBlackIcon`), with zero border and transparent alpha masking.
- **Text Selection & Native Edit Shortcuts:** Added macOS main menu `Edit` submenu and local key event forwarding for `⌘A` (Select All), `⌘C` (Copy), `⌘V` (Paste), `⌘X` (Cut), and `⌘Z` (Undo) in the search field.
- **Stale Cached Results on New Search:** Bound the search field directly to view model actions, immediately clearing stale results and cancelling in-flight tasks whenever the query changes.

---

## [macos-v1.0.0] - 2026-11-03

### Added
- Initial native macOS Swift/SwiftUI menu bar & floating HUD implementation.
- Dual search engines: Headless Google Images WebView (`G`) and Instant Web (`⚡`).
- Global configurable hotkey (`⌥ ⇧ Space` default, with custom recorder).
- Zero-Typing Mode (Auto-reads highlighted text or active chat field via macOS Accessibility API).
- Search history tracking up to 25 recent searches.
- Usage quota & counter dashboard.
- Packaged release bundle with DMG and ZIP artifacts.

---

## [android-v1.0.0] - 2026-11-03

### Added
- Initial native Android Kotlin IME implementation (`Keyboardy`).
- Dual search engines (Headless Google WebView + Instant Web).
- Workflow 1: In-Place Zero-Typing Search & 1-step insertion.
- Workflow 2: Direct Search with Mini-QWERTY pad.
- Magic-byte image format detection and raw animated GIF byte preservation.
