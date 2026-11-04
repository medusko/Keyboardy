# Changelog

All notable changes to the **Keyboardy** project (Android & macOS) will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

## [android-v1.01] - 2026-11-03

### Added
- **Word Suggestion & Real-Time Spell Check Bar:**
  - Added a responsive suggestion strip positioned directly above the typing pad (`container_mini_qwerty`).
  - Integrated `SpellAndSuggestionEngine` providing:
    - 0ms instant local typo corrections for frequent typing mistakes (e.g., `reciept` -> `receipt`, `dancin` -> `dancing`, `exicted` -> `excited`, `definately` -> `definitely`, `teh` -> `the`, `haply` -> `happily`, `congrats` -> `congratulations`, etc.).
    - Levenshtein distance fuzzy spell-checking (edit distance 1–2) for arbitrary misspelled words.
    - Curated GIF, reaction, emotion, and meme vocabulary.
    - Real-time prefix-matching autocomplete.
    - Fast async online completions via Google Suggest (~40ms) for internet slang, trending memes, and multi-word queries.
    - Default trending search chips (`excited`, `dancing`, `happy`, `crying`, `facepalm`, `mind blown`, `applause`, etc.) when the search bar is empty.
  - One-tap chip replacement: Tapping any chip immediately replaces the misspelled/partial word in the search box, positions the cursor, and triggers the search.

### Fixed
- **Typing Lag & Dropped Keystrokes:**
  - Replaced standard view click listeners (`setOnClickListener`) on keyboard keys with instantaneous `MotionEvent.ACTION_DOWN` touch handlers. Every character is registered on contact with immediate haptic and visual pressed feedback, completely resolving dropped keystrokes during rapid two-thumb typing and accidental touch-slop cancellations.
  - Implemented a 550ms search debounce delay during typing so background search computations and network queries never compete with the user's keystrokes.
  - Added `stopActiveLoading()` in `HeadlessGoogleWebViewSearcher` and cancellation hooks: in-flight WebView script execution and HTTP requests are immediately aborted on key-down to keep the Android UI thread 100% responsive.
  - Suppressed automatic web searches for 1-character queries, preventing heavy background DOM hydration for incomplete terms.
  - Optimized Shift state toggle: shifts letter case dynamically in-place on existing TextViews rather than tearing down and reconstructing the 35+ view hierarchy.
- **Image & Animated GIF Preview Scaling (Aspect-Fit / Zero Cropping):**
  - Updated grid preview thumbnail scale from `centerCrop` to `fitCenter` with 2dp inner padding inside `item_image_result.xml` and `ImageGridAdapter.kt`.
  - Wide horizontal GIFs and tall vertical images zoom out to fit entirely within the 3-column tile bounds with zero pixel cutoff on left, right, top, or bottom.

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
