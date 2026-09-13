<p align="center">
  <img src="app/src/main/res/drawable/ic_hyperlock_full.webp" alt="HyperLock 图标" width="128">
</p>

<h1 align="center"><strong>澎湃锁屏 (HyperLock)</strong></h1>

<p align="center">小米澎湃 OS 锁屏与息屏定制 LSPosed 模块。</p>

<p align="center">
  <a href="README.md">English</a> |
  <a href="https://github.com/windsnn/HyperLock/releases">下载 Release</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/适配系统-HyperOS_OS4-blue?style=flat-square" alt="适配系统">
  <img src="https://img.shields.io/badge/平台-Android_13+-green?style=flat-square&logo=android" alt="平台">
  <img src="https://img.shields.io/badge/许可证-Apache--2.0-yellow?style=flat-square" alt="许可证">
</p>

---

## 功能

- 锁屏迷你音乐播放器：位于两个快捷按钮之间，跟随系统媒体会话，并支持浮动歌词卡片
- 解除锁屏与息屏的景深壁纸限制
- 去掉通知下沉的位置限制，减少指纹区域的空白
- 隐藏屏下指纹图标
- 数字键盘高斯模糊背景
- 快捷按钮的背景材质与图标颜色自定义
- 解除澎湃「OTA 预设」对玻璃时钟材质的改写
- 锁屏底部文案控制（充电中 / 勿扰 / X 个通知）

## 环境要求

- 小米澎湃 OS 4（Android 13 及以上）
- Root + LSPosed
- 模块作用域：系统界面（`com.android.systemui`）、息屏与锁屏编辑（`com.miui.aod`）

## 关于歌词

歌词不是本模块的功能。要显示锁屏歌词，请自行安装 [词幕 Lyricon](https://github.com/proify/lyricon)，并在 LSPosed 里给它勾上「系统界面」作用域；没有原生适配词幕的播放器，再装对应的 Provider 插件。之后在设置里打开「锁屏歌词 → 显示歌词」。

## 构建

预编译包见 [Releases](https://github.com/windsnn/HyperLock/releases)。自己编：`./gradlew assembleRelease`（JDK 17 + Android SDK 37 起）。仓库里的 `Build APK` 工作流可以手动触发，推 `v*` tag 会自动出包并发 Release。

## 免责声明

本项目代码由 AI 编写，按「现状」提供，不保证可用性与安全性；他人修改后再分发的内容与本项目无关。刷机、Root、LSPosed 相关操作请自行评估风险，使用本项目造成的一切后果由使用者自负。

本项目为个人第三方模块，与小米公司无任何关联，未获其授权或背书；HyperOS、小米等名称与商标归各自所有者所有。

## 协议

[Apache License 2.0](LICENSE)。项目衍生自 [ColdP/HyperChanger](https://github.com/ColdP/HyperChanger)（其 1.0.1 及之前为 MIT 许可），上游署名与 MIT 许可正文见
[NOTICE](NOTICE) 与 [THIRD-PARTY.md](THIRD-PARTY.md)。
