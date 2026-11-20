#!/usr/bin/env bash
# Run after `npx cap add android`: installs the native MP3 plugin, streaming handler and permissions.
set -euo pipefail

PKG_DIR="android/app/src/main/java/com/mono/player"
MANIFEST="android/app/src/main/AndroidManifest.xml"

mkdir -p "$PKG_DIR"
cp android-src/MainActivity.java "$PKG_DIR/MainActivity.java"
cp android-src/MusicLibraryPlugin.java "$PKG_DIR/MusicLibraryPlugin.java"

# Storage / media permissions (Android 13+ uses READ_MEDIA_AUDIO, older uses READ_EXTERNAL_STORAGE)
if ! grep -q READ_MEDIA_AUDIO "$MANIFEST"; then
  sed -i 's#<application#<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />\n    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />\n    <application#' "$MANIFEST"
fi

echo "Android project patched."
grep -n "uses-permission" "$MANIFEST"
