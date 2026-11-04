---
name: mass-update
description: |
  Automated build, release, sync, and device deploy pipeline for Keyboardy (Android & macOS).
  Use whenever the user requests to release, build, sync, mass update, or push the app to GitHub and testing devices.
  Triggers on '/mass-update' or requests to sync code, update changelog, push a GitHub release build, and install to phone via Wireless/USB ADB.
---

# Mass Update Skill (`/mass-update`)

This skill automates the complete lifecycle for releasing and testing updates to the Keyboardy app:
1. **Syncing Code to GitHub:** Staging, committing, and pushing changes to `main`.
2. **Updating the Changelog:** Formatting and logging user-facing enhancements in `CHANGELOG.md`.
3. **Publishing New Builds to GitHub:** Building release artifacts and publishing GitHub releases via `gh` CLI.
4. **Deploying to Test Device:** Connecting via Wireless or USB ADB and installing the APK onto the device.

---

## Quick Execution

You can run the end-to-end mass update pipeline directly using the bundled script:

```bash
./scripts/mass_update.sh --platform android --commit-msg "Your commit message"
```

Options:
- `--platform android` (default): Builds and releases the Android APK.
- `--platform macos`: Builds and packages the macOS `.app`, `.dmg`, and `.zip`.
- `--platform all`: Runs both Android and macOS build and release workflows.
- `--commit-msg "<msg>"`: Custom commit message for the git push.

---

## Detailed Step-by-Step Procedure

If performing individual steps manually or troubleshooting:

### 1. Version & Changelog Update
1. Check the current sub-version:
   - **Android:** `versionName` in `app/build.gradle.kts` (e.g. `1.02`). Increment when changes are introduced.
   - **macOS:** `macos/VERSION` (e.g. `1.0.1-beta.4`).
2. Add a new release section at the top of `CHANGELOG.md`:
   ```markdown
   ## [android-v1.02] - YYYY-MM-DD

   ### Added / Fixed
   - Bullet points describing the update.
   ```

### 2. Build the Application
- **Android:**
  ```bash
  export JAVA_HOME="/opt/homebrew/opt/openjdk@21"
  ./gradlew assembleDebug testDebugUnitTest
  cp app/build/outputs/apk/debug/app-debug.apk Keyboardy-Android-v<VERSION>.apk
  ```
- **macOS:**
  ```bash
  ./macos/build_app.sh
  ```

### 3. Sync Code & Push to GitHub
```bash
git add .
git commit -m "Update <platform> to v<VERSION>: <summary>"
git push origin main
git tag <platform>-v<VERSION>
git push origin <platform>-v<VERSION>
```

### 4. Create GitHub Release
```bash
gh release create <platform>-v<VERSION> <ARTIFACT_FILE> \
  --title "Keyboardy for <Platform> v<VERSION>" \
  --notes "Release notes from CHANGELOG.md"
```

### 5. Push to Android Test Device (Wireless ADB)
1. Check attached devices:
   ```bash
   adb devices -l
   ```
2. If no device is attached, lookup mDNS wireless debugging:
   ```bash
   adb mdns services
   ```
3. Connect to the device:
   ```bash
   adb connect <IP>:<PORT>
   ```
4. Install the newly built APK:
   ```bash
   adb install -r Keyboardy-Android-v<VERSION>.apk
   ```
