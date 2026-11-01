<p align="center">
  <img src="app/src/main/res/drawable/ic_hyperlock_full.webp" alt="HyperLock icon" width="128">
</p>

<h1 align="center"><strong>HyperLock</strong></h1>

<p align="center">
  A lightweight module for Xiaomi HyperOS 4 lockscreen customization.
</p>

<p align="center">
  <a href="README_zh.md">中文</a> |
  <a href="https://github.com/windsnn/HyperLock/releases">Releases</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Target_OS-HyperOS_OS4-blue?style=flat-square" alt="Target OS">
  <img src="https://img.shields.io/badge/Platform-Android_13+-green?style=flat-square&logo=android" alt="Platform">
  <img src="https://img.shields.io/badge/License-Apache--2.0-yellow?style=flat-square" alt="License">
</p>

---

## ✨ Features

- 🎵 **Lockscreen Mini Music Player**: Elegantly placed between the bottom shortcut buttons, automatically syncing with the system media session, with Lyricon protocol floating lyrics support.
- 🖼️ **Bypass Depth Wallpaper Occlusion Limits**: Completely removes the system restriction where depth wallpaper is disabled due to "subject occluding too much of the clock", forcing depth effect even when the clock is heavily covered.
- 🕒 **Unlock "Glass" Clock Material**: Removes material selection restrictions on lockscreen clocks, allowing "Glass" material on all clock styles and preventing OTA presets from resetting it.
- 📐 **Notification & Fingerprint Layout**: Eliminates the notification shelf drop position limit, trimming redundant empty space above the fingerprint area; allows hiding the in-display fingerprint icon (lockscreen only or global).
- 🎨 **Deep Material & Background Customization**: Supports solid color, advanced material, and real-time soft glass effects based on RuntimeShader (Backdrop blur and physical blend color) for shortcut buttons and player; circular frosted-glass background for PIN entry keys.
- 📝 **Streamlined Bottom Text**: Independently toggle visibility for bottom status text (Charging status / Do Not Disturb / Notification count) for a cleaner lockscreen aesthetic.
- 🌐 **Full-Featured Internationalization**: Built-in comprehensive Chinese and English localizations with in-app language switching (Follow System / 简体中文 / English), seamlessly integrated with native HyperOS per-app language preferences.

## 📋 Requirements

- **Target System**: Xiaomi HyperOS 4 (Android 13+)
- **Prerequisites**: Root access with LSPosed framework installed
- **Module Scopes**:
  - **System UI** (`com.android.systemui`): Powers lockscreen player, lyrics, shortcut buttons, notification shelf, and PIN pad
  - **Lockscreen Editor & AOD** (`com.miui.aod`): Unlocks depth wallpaper and "Glass" clock material on the lockscreen

## 🎶 About Lockscreen Lyrics

Lyrics are not fetched directly by this module. Instead, it subscribes to the Lyricon protocol for real-time floating lyrics rendering. Setup instructions:

1. **Install Lyricon Core**: Download and install [Lyricon Core](https://github.com/tomakino/lyricon/releases/tag/core), activate it in LSPosed, and check the System UI (`com.android.systemui`) scope.
2. **Player & Plugin Configuration**:
   - Music players with native Lyricon protocol support broadcast lyrics automatically.
   - For standard music apps without native Lyricon support, install and configure their respective Provider plugins.
3. **Enable in HyperLock**: Turn on "Lockscreen lyrics display" in HyperLock settings.

## 🛠️ Build

Prebuilt APKs are available on the [Releases](https://github.com/windsnn/HyperLock/releases) page.

To build from source:
- Requirements: JDK 17 + Android SDK 37 or higher
- Build command: `./gradlew assembleRelease`
- CI/CD: Automated with GitHub Actions; pushing a `v*` tag automatically builds and publishes a GitHub Release.

## ⚠️ Disclaimer

1. The code in this project was written by AI and is provided "AS IS", without warranties of any kind, express or implied. Modified redistributions by third parties are unrelated to this project.
2. Flashing, acquiring root access, and utilizing LSPosed modules entail inherent risks. You are solely responsible for evaluating and bearing any consequences arising from use.
3. This is an independent open-source third-party project and is not affiliated with, authorized by, or endorsed by Xiaomi Inc. Xiaomi, HyperOS, and related trademarks belong to their respective owners.

## 📄 License

This project is licensed under the [Apache License 2.0](LICENSE).

Derived from [ColdP/HyperChanger](https://github.com/ColdP/HyperChanger) (which was licensed under MIT up to v1.0.1). Full upstream attribution, third-party component listings, and license texts are preserved in [NOTICE](NOTICE) and [THIRD-PARTY.md](THIRD-PARTY.md).
