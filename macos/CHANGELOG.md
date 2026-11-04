# Changelog

All notable changes to the **Keyboardy** project (Android & macOS) will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [macos-v1.0.1-beta.6] - 2026-11-04

### Fixed
- **Resolved Duplicate Image Insertion on Single Click (Double Paste Fix):**
  - **Root Cause:** In `ImageCommitService.synthesizePasteKeystroke`, keystrokes were being dispatched to **both** the system HID event tap (`.cghidEventTap`) and directly into the target process's Mach event queue via `postToPid(pid)`. Because the target application was already frontmost, macOS delivered the `⌘V` keystroke twice in the exact same millisecond (`10:25:42`), causing chat inputs and web browsers to paste two identical copies of the image.
  - **Fix:** Switched exclusively to system HID event tap dispatch (`.cghidEventTap`), matching the behavior of standard macOS accessibility utilities (Raycast, Alfred, 1Password). Keystrokes are cleanly routed once to the active frontmost window.
  - Also removed dual `postToPid` dispatch from `synthesizeSelectAllAndDelete`.
- **Added Selection In-Flight Guard:**
  - Added `guard loadingItemId == nil else { return }` in `KeyboardyViewModel.selectImageResult` and increased debounce to 400ms to guarantee rapid duplicate clicks cannot spawn overlapping commit tasks.
- **Enhanced Test Studio Diagnostics (`test_site/index.html`):**
  - Added timestamp tracking on `Keydown: ⌘V` and pasteboard events to detect and visually flag rapid duplicate pastes within 150ms.

---

## [macos-v1.0.1-beta.5] - 2026-11-04

### Fixed
- **Completely Eliminated Unprompted System Alert Dialogs:**
  - Removed `promptIfNeeded: true` calls from `applicationDidFinishLaunching` that caused macOS to popup the *"Keyboardy would like to control this Mac..."* dialog unprompted on startup.
  - Replaced modal permission requests with direct, non-intrusive System Settings navigation (`openAndRefreshAccessibilityPermission()`), allowing users to view or toggle settings without modal alerts blocking their screen.
- **Resolved Stale macOS TCC Permission Desynchronization:**
  - Explained and addressed the macOS bug where the GUI switch under *Device Control and Data Access* displays as **ON (blue)**, but the system's underlying TCC database rejects the binary due to cached hashes from previous builds.
  - Reset stale development TCC database entries via `tccutil reset Accessibility com.medusko.keyboardy.macos`.
  - Added in-app guidance advising users to toggle the switch **OFF and back ON** (or remove with `-` and re-add) to immediately flush the macOS TCC cache without requiring a system restart.

---

## [macos-v1.0.1-beta.4] - 2026-11-04

### Fixed
- **Eliminated Repetitive System Permission Popups on Every Click:**
  - **Root Cause:** In earlier builds, if `isAccessibilityTrusted()` returned false, `downloadAndInsertImage` was actively calling `requestAccessibilityPermission()` on *every single image click*, triggering the macOS modal dialog (*"Keyboardy would like to control this Mac..."*) and opening System Settings while aborting the paste action.
  - **Fix:** Removed the blocking permission check and modal prompt from the insertion path. Keyboardy now seamlessly copies to the clipboard, activates the target app, and executes `⌘V` paste without interruption. Permission prompts are restricted to explicit user actions (clicking "Grant" in Settings).
- **Persistent TCC Code Signing Identity:**
  - Code-signed the bundle with an explicit designated requirement (`designated => identifier "com.medusko.keyboardy.macos"`). macOS Privacy & Security (TCC) now anchors permission to the bundle ID rather than ephemeral binary hashes (`cdhash`), keeping permission persistent across rebuilds and updates.
- **Clarified System Settings Toggle Guidance:**
  - Added visual in-app banner guidance reminding users to toggle the switch next to Keyboardy to the **ON (blue)** position in `System Settings > Privacy & Security > Accessibility`.

---

## [macos-v1.0.1-beta.3] - 2026-11-03

### Fixed
- **Image Click Selection Execution (`onSelect`):**
  - **Root Cause:** When clicking on an image tile, `AspectFitImageContainerView` intercepted the `mouseDown` and `mouseUp` events before SwiftUI's outer `Button` could fire. Because `AnimatedRemoteImageView` was constructed without passing `onSelect`, `AspectFitImageContainerView.mouseUp` called `nil`, discarding the user's click completely.
  - **Fix:** Wired `onSelect: { viewModel.selectedIndex = index; viewModel.selectImageResult(item) }` directly into `AnimatedRemoteImageView`, ensuring clicking on any image result immediately triggers download and insertion.
  - Added a 350ms debounce guard on `selectImageResult` to ensure rapid double-clicks never create duplicate insertion tasks.
- **Hardware-Accurate ⌘V Keypress Simulation:**
  - Re-engineered synthetic keystrokes into a complete, authentic hardware event sequence: `Command` key-down (`.maskCommand`), 15ms delay, `V` key-down, 35ms key hold, `V` key-up, 15ms delay, and `Command` key-up.
  - Posted events to both the system HID event tap (`.cghidEventTap`) and directly to the target application's PID (`postToPid`), ensuring Chrome, Safari, Electron apps (Slack, Discord), and native macOS text fields capture the paste command every single time.
- **Universal Multi-Format Pasteboard Payloads:**
  - Expanded `NSPasteboard` payload writing to populate `public.file-url`, legacy `NSFilenamesPboardType`, `image/gif`, `com.compuserve.gif`, `image/png`, and `public.tiff` so web browsers and desktop chat apps can read image files and raw bytes seamlessly.

### Added
- **Interactive Insertion Test Studio (`test_site/index.html`):**
  - Created a local test studio with a focused chat input, drag-and-drop zone, live event inspector logging pasteboard MIME types and keystrokes, and real-time animated GIF rendering.

---

## [macos-v1.0.1-beta.2] - 2026-11-03

### Added
- **Proactive macOS Accessibility Permission Onboarding:**
  - Added initial launch permission detection: if Accessibility is not yet granted, Keyboardy automatically displays the system permission prompt.
  - Added an in-app actionable Accessibility warning banner directly under the search bar when Auto-Paste mode is active without permissions, featuring a 1-click **"Grant"** button that directly opens `System Settings > Privacy & Security > Accessibility`.
  - Added `NSAccessibilityUsageDescription` and `NSAppleEventsUsageDescription` to `Info.plist` explaining why Keyboardy needs Accessibility access (synthesizing `⌘V` to paste into active chat apps).
- **Graceful Clipboard Fallback with Guidance:**
  - When clicking an image without Accessibility permissions, Keyboardy copies the image/GIF to the clipboard and provides actionable feedback instructing the user to grant permission in System Settings for 1-step auto-paste.

### Fixed
- **1-Click Auto-Paste vs. Drag-and-Drop Conflict:**
  - **Root Cause:** Drag-and-drop uses macOS pointer event streams (`NSItemProvider`) which require zero special permissions. Keystroke synthesis (`⌘V` into external apps), however, strictly requires macOS Accessibility trust (`AXIsProcessTrusted() == true`). Without it, macOS silently drops the synthetic keystrokes. Additionally, custom mouse handling in `AspectFitImageContainerView` (`mouseDown`, `mouseDragged`, `mouseUp`) intercepted pointer events inside SwiftUI's grid, preventing clicks from firing reliably.
  - **Fix:** Decoupled image rendering from gesture handling: `AspectFitImageContainerView` now operates purely as a high-performance layer-backed frame animator, while grid tiles are standard SwiftUI `Button` elements with native `.onDrag` handlers.
- **Keystroke Registration Reliability:**
  - Switched `CGEventSource` to `.combinedSessionState` and added a 25ms key-down hold delay before posting key-up, ensuring Slack, Discord, Chrome, and Messages process the `⌘V` keystroke every time.

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
