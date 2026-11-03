# Keyboardy — Zero-Step Google Image & GIF Auxiliary Keyboard for Android

**Keyboardy** is an auxiliary Android `InputMethodService` (Option A) designed to work alongside **Gboard**. It lets you search Google Images and animated GIFs, tap a result, and immediately insert it into **Slack, Google Messages, WhatsApp, Discord, Signal, or Telegram** via Android's `CommitContent` API—without saving images to your gallery or opening a file picker.

---

## Key Features

1. **100% Free Hybrid Search Engine (Zero API Keys Required):**
   - **Headless Google Images WebView (`G`):** Runs an invisible 1×1 `WebView` inside the keyboard service that executes Google Images (`udm=2` / `tbs=itp:animated` for GIFs) and extracts full-resolution image and GIF URLs alongside thumbnails.
   - **Instant Web Image Search (`⚡`):** Fetches 30+ high-res images and Tenor/Giphy GIFs over async HTTP in ~150ms so the grid populates immediately while Google WebView finishes hydrating.
   - **Optional API Key Support:** Includes optional support for **SerpApi** or **Brave Search API** keys in settings if desired.

2. **Two Effortless Search Workflows:**
   - **Workflow 1 — Type in Gboard, Switch to Insert (Zero-Typing Mode):**
     1. Type your search words (e.g., `party parrot`) right inside your Slack/Messages chat box using Gboard (swipe typing, voice typing, etc.).
     2. Tap the **Keyboard Switcher** icon in the bottom-right corner of Android's navigation bar (or long-press Gboard's spacebar) to switch to **Keyboardy**.
     3. Keyboardy automatically reads `party parrot` from the chat box and searches immediately!
     4. Tap any image or GIF → Keyboardy clears the temporary `party parrot` text, inserts the image directly into the chat box, and **automatically switches you right back to Gboard**.
   - **Workflow 2 — Direct In-Keyboard Search:**
     1. Switch to **Keyboardy** anytime.
     2. Type on Keyboardy's collapsible **Mini-QWERTY pad** and filter by `All Images`, `GIFs`, `Memes`, `Reactions`, or `Transparent`.
     3. Tap any image or GIF → it inserts directly into your chat and switches back to Gboard.

3. **Smart Format & Fallback Handling:**
   - Detects real image formats (`image/gif`, `image/png`, `image/webp`, `image/jpeg`) via magic bytes.
   - Preserves animated GIFs byte-for-byte (`image/gif`).
   - Automatically transcodes static WebP images to PNG if the target chat app does not advertise `image/webp` support in `EditorInfo.contentMimeTypes`.
   - Falls back to copying the `content://` image URI to the Android system clipboard if an app's input field only supports plain text.

---

## Building & Installing the APK

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
./gradlew assembleDebug testDebugUnitTest
```

The installable debug APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

Install onto a connected Android phone via ADB:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
