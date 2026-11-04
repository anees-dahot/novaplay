# NovaPlay

Ad-free, tracker-free video player for Android, built on libVLC.

## Features
- Plays almost any format (MKV, MP4, AVI, HEVC, AC3/DTS…) with hardware or software decoding
- Folder library with thumbnails, sorting and resume progress bars
- Gestures: brightness (left swipe), volume (right swipe), seek (horizontal swipe), double-tap ±10s, pinch to crop, hold for 2x
- Subtitles: embedded tracks, load .srt/.ass files, delay and size
- Audio track switching, speed 0.25x–4x, aspect ratios (Fit, Crop, Stretch, 16:9, 4:3, 21:9, 100%)
- Picture-in-picture, background play with lock-screen controls
- Next/previous in folder, auto-play next, loop one/all, A–B repeat, chapters
- Seek preview thumbnails, screenshots, night mode, screen lock
- Resumes where you left off
- Updates itself from GitHub Releases

## Install
Download the APK for your phone from [Releases](https://github.com/anees-dahot/novaplay/releases/latest)
(most phones: `arm64-v8a`; not sure: `universal`).

## Build
JDK 17 + Android SDK, then `./gradlew assembleDebug`.
Releases: `./release.sh <version> "notes"` (needs `~/.novaplay/keystore.properties`).
