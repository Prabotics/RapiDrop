#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

BUILD_DMG=true
BUILD_ZIP=true

if [ $# -gt 0 ]; then
  case "$1" in
    --dmg)
      BUILD_DMG=true
      BUILD_ZIP=false
      ;;
    --zip)
      BUILD_DMG=false
      BUILD_ZIP=true
      ;;
    --all)
      BUILD_DMG=true
      BUILD_ZIP=true
      ;;
    --app-only)
      BUILD_DMG=false
      BUILD_ZIP=false
      ;;
  esac
fi

swift build -c release

APP_DIR="$SCRIPT_DIR/RapiDrop.app"
CONTENTS_DIR="$APP_DIR/Contents"
MACOS_DIR="$CONTENTS_DIR/MacOS"
RESOURCES_DIR="$CONTENTS_DIR/Resources"

rm -rf "$APP_DIR"
mkdir -p "$MACOS_DIR" "$RESOURCES_DIR"

cp "$SCRIPT_DIR/.build/release/RapiDrop" "$MACOS_DIR/RapiDrop"
chmod +x "$MACOS_DIR/RapiDrop"

cp "$SCRIPT_DIR/Info.plist" "$CONTENTS_DIR/Info.plist"
if [ -d "$SCRIPT_DIR/Resources" ]; then
  cp -R "$SCRIPT_DIR/Resources/"* "$RESOURCES_DIR/"
fi

codesign --force --deep --sign - "$APP_DIR"
if [ "$BUILD_ZIP" = true ]; then
  ZIP_PATH="$SCRIPT_DIR/RapiDrop-macOS.zip"
  rm -f "$ZIP_PATH"
  ditto -c -k --keepParent "$APP_DIR" "$ZIP_PATH"
fi

if [ "$BUILD_DMG" = true ]; then
  DMG_PATH="$SCRIPT_DIR/RapiDrop-macOS.dmg"
  DMG_STAGING="$SCRIPT_DIR/.dmg-staging"
  rm -rf "$DMG_PATH" "$DMG_STAGING"
  mkdir -p "$DMG_STAGING"
  cp -R "$APP_DIR" "$DMG_STAGING/"
  ln -s /Applications "$DMG_STAGING/Applications"
  hdiutil create -volname "RapiDrop" -srcfolder "$DMG_STAGING" -ov -format UDZO "$DMG_PATH" > /dev/null
  rm -rf "$DMG_STAGING"
fi
