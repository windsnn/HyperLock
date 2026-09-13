<p align="center">
  <img src="app/src/main/res/drawable/ic_hyperlock_full.webp" alt="HyperLock icon" width="128">
</p>

<h1 align="center"><strong>HyperLock</strong></h1>

<p align="center">An LSPosed module for Xiaomi HyperOS lock screen and AOD customization.</p>

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

## Features

- Lock screen mini music player between the two shortcut buttons, following the system media session, with floating lyrics
- Removes the depth wallpaper restrictions on the lock screen and AOD
- Removes the notification shelf position limit, cutting the empty space above the fingerprint area
- Hides the in-display fingerprint icon
- Blurred background for the PIN numpad
- Custom background material and icon color for the shortcut buttons
- Keeps the "Glass" clock material that HyperOS rewrites when it applies OTA presets
- Control over the bottom texts (charging / do-not-disturb / notification count)

## Requirements

- Xiaomi HyperOS 4 (Android 13+)
- Root and LSPosed
- Module scopes: System UI (`com.android.systemui`), AOD (`com.miui.aod`)

## About the lyrics

Lyrics are not provided by this module. To get lock screen lyrics, install [Lyricon](https://github.com/proify/lyricon) yourself and enable the System UI scope for it in LSPosed; players that do not integrate Lyricon natively also need the matching Provider plugin. Then turn on "Lockscreen lyrics → Show lyrics" in the settings.

## Build

Prebuilt APKs are on the [Releases](https://github.com/windsnn/HyperLock/releases) page. To build it yourself: `./gradlew assembleRelease` (JDK 17 and Android SDK 37+). The `Build APK` workflow can be triggered manually, and pushing a `v*` tag builds and publishes a release automatically.

## Disclaimer

The code in this project was written by AI and is provided "as is", without any warranty of usability or safety; modified redistributions are not related to this project. Flashing, root and LSPosed operations are at your own risk — you are responsible for any consequences of using this project.

This is a personal third-party module, not affiliated with, authorized by, or endorsed by Xiaomi; HyperOS and other names and trademarks belong to their respective owners.

## License

[Apache License 2.0](LICENSE). Derived from [ColdP/HyperChanger](https://github.com/ColdP/HyperChanger),
which was licensed under MIT up to v1.0.1. The upstream attribution and the MIT license text are kept in
[NOTICE](NOTICE) and [THIRD-PARTY.md](THIRD-PARTY.md).
