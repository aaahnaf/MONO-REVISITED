# Mono – APK build

1. Create a GitHub repo and upload everything in this folder (keep `.github/` at the root).
2. Push to `main` (or Actions -> Build APK -> Run workflow).
3. Download **Mono-APK** from the run's Artifacts, unzip, install `app-debug.apk`.

## Testing on an emulator
An emulator has NO music by default. Drag an .mp3 file onto the emulator window
(it goes into Downloads), then open the app and allow the audio permission.
Or: `adb push song.mp3 /sdcard/Music/`
If the list is empty, tap the title / "Scan again". The app also indexes any .mp3
it finds on shared storage that Android hasn't indexed yet.
Errors are shown on screen instead of being hidden.
