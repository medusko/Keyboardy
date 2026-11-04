# Keyboardy — Zero-Step Google Image & GIF Keyboard (Android & macOS)

**Keyboardy** lets you search Google Images, Memes, Stickers, and animated GIFs with **zero API keys required** (`G` Headless Google Images + `⚡` Instant Web) and immediately insert them directly into **Slack, Apple/Google Messages, WhatsApp, Discord, Signal, or Telegram**—without saving files or opening a file picker.

---

## 📥 Downloads (GitHub Releases)

We publish independent per-platform releases so Android and macOS can evolve at their own pace. All minor improvements and beta updates are tracked in detail in our [**CHANGELOG.md**](CHANGELOG.md):

- 💻 **[Download Keyboardy for macOS Beta (`Keyboardy-macOS-v1.0.1-beta.1.dmg` / `.zip`)](https://github.com/medusko/Keyboardy/releases/tag/macos-v1.0.1-beta.1)** *(Latest macOS Beta — includes Tenor fast GIFs, continuous looping, 1-step auto-paste, and native drag-and-drop)*
- 📱 **[Download Keyboardy for Android (`Keyboardy-Android-v1.0.0.apk`)](https://github.com/medusko/Keyboardy/releases/tag/android-v1.0.0)**
- 📋 **[View All Releases & Full Changelog](CHANGELOG.md)**

---

## Key Features

1. **100% Free Hybrid Search Engine (Zero API Keys Required):**
   - **Headless Google Images WebView (`G`):** Runs an invisible `WebView` / `WKWebView` that executes Google Images (`udm=2` / `tbs=itp:animated` for GIFs) and extracts full-resolution image and GIF URLs alongside thumbnails.
   - **Instant Web Image Search (`⚡`):** Fetches 30+ high-res images and Tenor/Giphy GIFs over async HTTP in ~150ms so the grid populates immediately while Google WebView finishes hydrating.
   - **Optional API Key Support:** Includes optional support for **SerpApi** or **Brave Search API** keys in settings if desired.

2. **Two Effortless Search Workflows (Android & macOS):**
   - **Workflow 1 — Zero-Typing / In-Place Search:**
     - **Android:** Type your search words (e.g., `party parrot`) right inside your chat box using Gboard → switch to **Keyboardy** → tap any image/GIF to replace `party parrot` and automatically switch back to Gboard.
     - **macOS:** Highlight text or type in your chat box → press **`⌥ ⇧ Space`** (or your custom shortcut) → click or press `Return` on any result to replace the text and auto-paste the image directly into your active chat app!
   - **Workflow 2 — Direct Picker Search:**
     - **Android:** Switch to Keyboardy anytime and use its collapsible Mini-QWERTY pad.
     - **macOS:** Click the **Keyboardy** icon in your macOS Menu Bar (or press **`⌥ ⇧ Space`**) and filter by `All`, `GIFs`, `Memes`, `Reactions`, or `Stickers`.

3. **Smart Format & Animated GIF Preservation:**
   - Detects real image formats (`image/gif`, `image/png`, `image/webp`, `image/jpeg`) via magic bytes.
   - Preserves animated GIFs byte-for-byte (`image/gif` on Android `CommitContent`; `public.file-url` + `com.compuserve.gif` on macOS `NSPasteboard` so Slack/Discord/Messages never flatten animations).
   - Automatically transcodes static WebP images to PNG for universal compatibility.

---

## 🗺️ Codebase Architecture & 1-to-1 Platform Mapping

Both platforms live in this repository (`app/` for Android and `macos/` for macOS) and share a 1-to-1 architectural mapping:

| Feature Area | Android (`app/src/main/java/com/medusko/keyboardy/...`) | macOS (`macos/Sources/...`) |
| :--- | :--- | :--- |
| **Search & Scrapers** | [`ImageSearchRepository.kt`](app/src/main/java/com/medusko/keyboardy/search/ImageSearchRepository.kt) & [`SearchParsers.kt`](app/src/main/java/com/medusko/keyboardy/search/SearchParsers.kt) | [`ImageSearchRepository.swift`](macos/Sources/KeyboardyCore/ImageSearchRepository.swift) & [`SearchParsers.swift`](macos/Sources/KeyboardyCore/SearchParsers.swift) |
| **Headless Google JS** | [`HeadlessGoogleWebViewSearcher.kt`](app/src/main/java/com/medusko/keyboardy/search/HeadlessGoogleWebViewSearcher.kt) | [`HeadlessGoogleWebViewSearcher.swift`](macos/Sources/KeyboardyCore/HeadlessGoogleWebViewSearcher.swift) |
| **Filters & Models** | [`ImageResult.kt`](app/src/main/java/com/medusko/keyboardy/search/ImageResult.kt) | [`ImageResult.swift`](macos/Sources/KeyboardyCore/ImageResult.swift) |
| **Image Insertion** | [`ImageCommitHelper.kt`](app/src/main/java/com/medusko/keyboardy/ime/ImageCommitHelper.kt) | [`ImageCommitService.swift`](macos/Sources/KeyboardyCore/ImageCommitService.swift) |
| **Settings & Prefs** | [`KeyboardyPrefs.kt`](app/src/main/java/com/medusko/keyboardy/prefs/KeyboardyPrefs.kt) & [`MainActivity.kt`](app/src/main/java/com/medusko/keyboardy/MainActivity.kt) | [`KeyboardyPrefs.swift`](macos/Sources/KeyboardyCore/KeyboardyPrefs.swift) & [`SettingsView.swift`](macos/Sources/KeyboardyApp/SettingsView.swift) |
| **Picker Grid & Theme** | [`KeyboardyImeService.kt`](app/src/main/java/com/medusko/keyboardy/ime/KeyboardyImeService.kt) & [`ThemePalette.kt`](app/src/main/java/com/medusko/keyboardy/theme/ThemePalette.kt) | [`KeyboardyPickerView.swift`](macos/Sources/KeyboardyApp/KeyboardyPickerView.swift) & [`ThemePalette.swift`](macos/Sources/KeyboardyApp/ThemePalette.swift) |

---

## Building From Source

### 1. Android (`app/`)

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
./gradlew assembleDebug testDebugUnitTest
```
The installable APK is generated at `app/build/outputs/apk/debug/app-debug.apk`:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 2. macOS (`macos/`)

```bash
cd macos
./build_app.sh --package
open build/Keyboardy.app
```
This builds `macos/build/Keyboardy.app` along with release installers `Keyboardy-macOS-v1.0.0.dmg` and `Keyboardy-macOS-v1.0.0.zip`.

---

## Publishing Per-Platform Releases on GitHub

This repository uses independent tag prefixes (`.github/workflows/release-android.yml` and `.github/workflows/release-macos.yml`):

- **Publish a new Android release:**
  ```bash
  git tag android-v1.0.1 && git push origin android-v1.0.1
  ```
- **Publish a new macOS release:**
  ```bash
  git tag macos-v1.0.1 && git push origin macos-v1.0.1
  ```
