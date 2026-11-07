# Vyro Browser

By **Yoolan**. A fast Android browser built on the system WebView (Chromium) with built-in offline games.

## Get the APK

1. Push this folder to your GitHub repository (branch `main`).
2. Open **Actions**, then the latest **Build APK** run, and wait for the green tick.
3. Scroll to **Artifacts**, download **Vyro-apk**, unzip it and install `app-debug.apk`.

## Features

- Search bar with a choice of DuckDuckGo, Google or Bing
- Light, dark and follow-system themes
- Settings: theme, search engine, ad blocking, desktop site, clear cookies and cache
- Video download button (direct video files on sites that allow it)
- Long-press any link or image to open, copy or download
- Built-in Games page (Snake, Memory) that works offline
- Live home page with daily news and pictures (BBC feeds: Top, Africa, Swahili, Tech, Sport)
- Media screen: asks permission, then lists your music, videos and pictures
- Built-in player for music, videos and pictures
- Vault: hide files and folders behind a PIN or pattern lock
- Custom vector icons and an adaptive app icon

## Notes

- The package name is `com.yoolan.vyro`, so it installs as a new app next to older versions.
- Vault files are stored privately inside the app. They are locked, not encrypted. Uninstalling the app or clearing its data deletes them.
- The news comes from BBC public RSS feeds, which are meant for personal, non-commercial use. For a commercial release, use a licensed news API.
- YouTube and other protected streaming sites cannot be downloaded from. That is against their terms.
- This is a debug build. A Play Store release needs a signed release build and a privacy policy.
