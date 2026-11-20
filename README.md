# Mono – APK build

1. Create a new GitHub repo and upload everything in this folder (keep `.github/` at the root).
2. Push to `main` (or open **Actions → Build APK → Run workflow**).
3. When the run finishes, download **Mono-APK** from the run's *Artifacts* section, unzip, and install `app-debug.apk`.

On first launch the app asks for audio/file permission and then lists every `.mp3` on the phone.
If permission is denied, tap the title / empty screen to retry (or open settings if blocked).
