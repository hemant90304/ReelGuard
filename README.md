# ReelGuard

A production-ready Android app for Nothing OS / CMF Phone 1 that limits your daily Instagram Reels consumption.

## Features
- 🔢 Configurable daily Reels limit (default: 100)
- 📊 Live floating HUD overlay showing current count
- 🔒 Hard limit enforcement — kicks you to home screen when limit is reached
- 🌙 Automatic reset at midnight via WorkManager
- 🎨 Nothing OS dark monochrome aesthetic

## Permissions Required
1. **Overlay** — to show the floating HUD counter
2. **Accessibility** — to detect Reels scrolling inside Instagram
3. **Notifications** — for foreground service stability

## Build
See [GitHub Actions](.github/workflows/build-apk.yml) — APK is built automatically on every push.
