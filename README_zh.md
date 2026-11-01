<p align="center">
  <img src="app/src/main/res/drawable/ic_hyperlock_full.webp" alt="HyperLock 图标" width="128">
</p>

<h1 align="center"><strong>HyperLock</strong></h1>

<p align="center">
  轻量级小米澎湃 OS 4 锁屏定制模块。
</p>

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

## ✨ 功能特性

- 🎵 **锁屏迷你音乐播放器**：优雅嵌入底部两个快捷功能按钮之间，自动同步当前媒体会话，并支持接入词幕（Lyricon）协议渲染浮动动态歌词。
- 🖼️ **解除景深遮挡限制**：彻底解除锁屏因“主体遮挡时间过多”而无法开启景深壁纸的限制，即使主体大面积遮挡时钟也能强行启用景深。
- 🕒 **解锁“玻璃”时钟材质**：解除系统时钟样式的材质选择限制，支持全款式选用“玻璃”质感，并阻止系统 OTA 预设强制覆盖重置。
- 📐 **通知与指纹布局优化**：解除锁屏通知下沉位置限制，消除指纹区域上方不必要的空白；支持按需隐藏屏下指纹图标（仅锁屏隐藏 / 全局隐藏）。
- 🎨 **背景与材质深度定制**：快捷功能与播放器均支持纯色、高级材质及基于 RuntimeShader 的实时柔光玻璃（Backdrop 模糊与物理混色）；支持锁屏数字密码键盘圆形毛玻璃背景。
- 📝 **锁屏底部文案精简**：支持独立勾选隐藏底部文本（充电中 / 勿扰模式 / X 个通知），保持锁屏整洁纯净。
- 🌐 **全功能国际化与应用内语言切换**：内置完整中英文双语资源，支持应用内自由选择语言（跟随系统 / 简体中文 / English），并原生联动 HyperOS 系统级应用语言偏好。

## 📋 环境要求

- **适配系统**：小米澎湃 OS 4（Xiaomi HyperOS 4，基于 Android 13 及以上）
- **基础环境**：已获取 Root 权限，并安装 LSPosed 框架
- **推荐勾选的 LSPosed 作用域**：
  - **系统界面** (`com.android.systemui`)：负责锁屏播放器、浮动歌词、快捷按键、通知与密码盘等大部分锁屏功能
  - **息屏与锁屏编辑** (`com.miui.aod`)：负责锁屏景深壁纸与“玻璃”时钟材质解除限制

## 🎶 关于锁屏歌词

本模块本身不获取歌词，而是接入词幕（Lyricon）协议进行实时渲染与推镜。配置步骤如下：

1. **安装词幕核心**：下载并安装 [Lyricon Core（词幕核心）](https://github.com/tomakino/lyricon/releases/tag/core)，在 LSPosed 中激活并勾选「系统界面 (`com.android.systemui`)」作用域。
2. **播放器与插件配置**：
   - 原生支持词幕协议的播放器可直接广播歌词；
   - 未原生适配词幕的常规音乐应用，需配合安装或配置对应的 Provider 扩展插件。
3. **开启显示**：在 HyperLock 的「锁屏」设置中，打开「锁屏歌词显示」即可联动展示。

## 🛠️ 构建

预编译安装包可前往 [Releases](https://github.com/windsnn/HyperLock/releases) 页面下载。

若需自行编译构建：
- 环境要求：JDK 17 + Android SDK 37 起
- 构建命令：`./gradlew assembleRelease`
- CI/CD：仓库内置 GitHub Actions 自动化工作流；推送 `v*` 格式版本标签时将自动触发打包并发布 Release。

## ⚠️ 免责声明

1. 本项目代码由 AI 编写，按「现状」（AS IS）提供，不包含任何明示或暗示的使用保证；他人修改后再分发的版本与本项目无关。
2. 刷机、获取 Root 权限及使用 LSPosed 模块均存在一定风险，请自行评估并承担使用过程中的全部风险与后果。
3. 本项目为个人开源的第三方独立模块，与小米科技（Xiaomi Inc.）无任何关联，亦未获得其官方授权或背书；文中所述 HyperOS、小米及相关商标均归其各自所有者所有。

## 📄 开源许可

本项目采用 [Apache License 2.0](LICENSE) 开源协议。

本项目衍生自 [ColdP/HyperChanger](https://github.com/ColdP/HyperChanger)（其 v1.0.1 及早期版本遵循 MIT 协议）。完整的上游署名、第三方组件清单及许可证文本已在 [NOTICE](NOTICE) 与 [THIRD-PARTY.md](THIRD-PARTY.md) 中完整保留。
