#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$SCRIPT_DIR"

VERSION_FILE="$SCRIPT_DIR/VERSION"
if [[ -z "${VERSION:-}" ]] && [[ -f "$VERSION_FILE" ]]; then
    VERSION="$(cat "$VERSION_FILE" | tr -d '[:space:]')"
fi
VERSION="${VERSION:-1.0.1-beta.1}"
BUILD_NUMBER="${BUILD_NUMBER:-7}"

echo "==> Building Keyboardy for macOS v${VERSION} (Build ${BUILD_NUMBER}) [Release]..."
swift build -c release

APP_NAME="Keyboardy"
APP_BUNDLE="$SCRIPT_DIR/build/${APP_NAME}.app"
CONTENTS_DIR="$APP_BUNDLE/Contents"
MACOS_DIR="$CONTENTS_DIR/MacOS"
RESOURCES_DIR="$CONTENTS_DIR/Resources"

rm -rf "$APP_BUNDLE"
mkdir -p "$MACOS_DIR" "$RESOURCES_DIR"

cp "$SCRIPT_DIR/.build/release/Keyboardy" "$MACOS_DIR/$APP_NAME"
chmod +x "$MACOS_DIR/$APP_NAME"

# Generate AppIcon.icns and StatusBarIcon.png from the Android project icon
SOURCE_ICON="$REPO_ROOT/app/src/main/res/drawable-nodpi/ic_app_option_1.png"
if [[ -f "$SOURCE_ICON" ]]; then
    cp "$SOURCE_ICON" "$RESOURCES_DIR/StatusBarIcon.png"
    ICONSET_DIR="$SCRIPT_DIR/build/AppIcon.iconset"
    rm -rf "$ICONSET_DIR"
    mkdir -p "$ICONSET_DIR"
    sips -z 16 16     "$SOURCE_ICON" --out "$ICONSET_DIR/icon_16x16.png" >/dev/null 2>&1 || true
    sips -z 32 32     "$SOURCE_ICON" --out "$ICONSET_DIR/icon_16x16@2x.png" >/dev/null 2>&1 || true
    sips -z 32 32     "$SOURCE_ICON" --out "$ICONSET_DIR/icon_32x32.png" >/dev/null 2>&1 || true
    sips -z 64 64     "$SOURCE_ICON" --out "$ICONSET_DIR/icon_32x32@2x.png" >/dev/null 2>&1 || true
    sips -z 128 128   "$SOURCE_ICON" --out "$ICONSET_DIR/icon_128x128.png" >/dev/null 2>&1 || true
    sips -z 256 256   "$SOURCE_ICON" --out "$ICONSET_DIR/icon_128x128@2x.png" >/dev/null 2>&1 || true
    sips -z 256 256   "$SOURCE_ICON" --out "$ICONSET_DIR/icon_256x256.png" >/dev/null 2>&1 || true
    sips -z 512 512   "$SOURCE_ICON" --out "$ICONSET_DIR/icon_256x256@2x.png" >/dev/null 2>&1 || true
    iconutil -c icns "$ICONSET_DIR" -o "$RESOURCES_DIR/AppIcon.icns" >/dev/null 2>&1 || true
    rm -rf "$ICONSET_DIR"
fi

cat > "$CONTENTS_DIR/Info.plist" << EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleDevelopmentRegion</key>
    <string>en</string>
    <key>CFBundleExecutable</key>
    <string>Keyboardy</string>
    <key>CFBundleIconFile</key>
    <string>AppIcon</string>
    <key>CFBundleIdentifier</key>
    <string>com.medusko.keyboardy.macos</string>
    <key>CFBundleInfoDictionaryVersion</key>
    <string>6.0</string>
    <key>CFBundleName</key>
    <string>Keyboardy</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleShortVersionString</key>
    <string>${VERSION}</string>
    <key>CFBundleVersion</key>
    <string>${BUILD_NUMBER}</string>
    <key>LSMinimumSystemVersion</key>
    <string>13.0</string>
    <key>LSUIElement</key>
    <true/>
    <key>NSHighResolutionCapable</key>
    <true/>
    <key>NSAccessibilityUsageDescription</key>
    <string>Keyboardy requires Accessibility access to automatically insert images and animated GIFs into your active chat applications (Slack, Messages, Discord, WhatsApp) using ⌘V.</string>
    <key>NSAppleEventsUsageDescription</key>
    <string>Keyboardy requires Apple Events access to interact with target chat applications when inserting images and animated GIFs.</string>
</dict>
</plist>
EOF

codesign --force --deep --sign - --requirements '=designated => identifier "com.medusko.keyboardy.macos"' "$APP_BUNDLE" >/dev/null 2>&1 || true
echo "==> Built macOS App Bundle at: $APP_BUNDLE"

if [[ "${1:-}" == "--package" ]]; then
    ZIP_PATH="$SCRIPT_DIR/build/Keyboardy-macOS-v${VERSION}.zip"
    DMG_PATH="$SCRIPT_DIR/build/Keyboardy-macOS-v${VERSION}.dmg"
    DMG_STAGING="$SCRIPT_DIR/build/dmg_staging"

    rm -f "$ZIP_PATH" "$DMG_PATH"
    rm -rf "$DMG_STAGING"

    echo "==> Creating ZIP archive: $ZIP_PATH"
    ditto -c -k --sequesterRsrc --keepParent "$APP_BUNDLE" "$ZIP_PATH"

    echo "==> Creating DMG installer: $DMG_PATH"
    mkdir -p "$DMG_STAGING"
    cp -R "$APP_BUNDLE" "$DMG_STAGING/"
    ln -s /Applications "$DMG_STAGING/Applications"
    hdiutil create -volname "Keyboardy" -srcfolder "$DMG_STAGING" -ov -format UDZO "$DMG_PATH" >/dev/null
    rm -rf "$DMG_STAGING"

    echo "==> Packaged Release Artifacts:"
    echo "    - $ZIP_PATH"
    echo "    - $DMG_PATH"
fi
