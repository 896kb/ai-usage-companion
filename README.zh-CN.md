# Magic Rear

[English](README.md) · **简体中文**

![小米 17 Pro 背屏上的全部功能：时钟和实时背景、通知、控制中心、带键盘的应用、带歌词的媒体、息屏显示](assets/rear-panel.mp4)

小米自带的背屏能做的不多。Magic Rear 用一个能做很多事的面板取而代之，适用于 **小米 18 Pro / MAX & 小米 17 Pro / MAX**。
它原名 AI Usage：AI 用量显示仍然保留，只是众多功能之一。

网站、指南和新闻：**[rear.896.ro](https://rear.896.ro)**

> 安装前请先阅读[请先读这里](#请先读这里)。这是个人项目，需要 Shizuku；如果使用 AI 用量显示，还需要登录你的账号。

## 功能

- **时钟页**：时间、日期、电量和天气行；下雨、雷暴或下雪时，玻璃上也会出现天气效果。
- **媒体**：封面（方形或旋转的唱片）、进度、拖动、音量和同步歌词。
- **背屏通知**：预览、打开列表、滑动清除或一键全部清除。
- **App Portal**：从底部边缘上拉，在背屏打开你固定的应用，每个应用都有自己的 DPI。应用可以避开摄像头显示在其右侧，
  支持返回，键盘也会在背屏弹出。
- **控制中心**、**息屏显示**、**口袋模式**、手机屏幕朝下放置时的**桌面灯**、背屏**相机**、一个小**游戏**，以及充电动画。
- **外观**：玻璃或平面风格、字体、CRT 屏幕效果；背景可以是纯色、图片、视频或实时着色器（Silk、Color Bends、
  Liquid Metal），并随手机倾斜产生视差。
- **AI 用量**：以圆环显示 Claude Code 的用量限制，每个账号一个吉祥物。

所有设置都在正面屏幕的应用里完成，并实时预览背屏效果。

## 要求

- **小米 18 Pro / MAX & 小米 17 Pro / MAX**。在其他手机上没有用处。
- **HyperOS 3 / 4 及更新版本，Android 16 / 17 及更新版本。** 它依赖系统内部机制，新版 HyperOS 可能需要重新验证。
- **[Shizuku](https://shizuku.rikka.app/)**，并保持运行。把任何内容放到背屏都需要 shell 级权限的调用，普通应用做不到；
  Shizuku 会把这些权限借给你允许的应用。

## 安装

最简单的方式是 **[896 Store](https://store.896.ro)**：它会安装 Magic Rear 并自动更新。也可以从
[rear.896.ro](https://rear.896.ro) 或 [Releases](https://gitlab.com/896kb/ai-usage-companion/-/releases) 下载 APK。

从 AI Usage 更新会保留你的设置：它是同一个应用（`dev.kb896.aiusage`），只是换了名字。

## 设置

1. **启动 Shizuku**：在开发者选项中打开无线调试，然后在 Shizuku 中选择**通过无线调试启动**。重启后它不会自动运行，
   每次重启后都需要再次启动（点它的通知最快）。如果有电脑：
   `adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh`。
2. **打开 Magic Rear**，按设置顶部的设置清单逐项完成。每一项都会打开对应的系统页面，完成后自动打勾：
   - 在 Shizuku 中允许 Magic Rear；
   - 让小米自带的背屏应用让开（只对你的用户隐藏，不删除任何东西；一键即可恢复）；
   - 允许通知；如果想在背屏显示通知和媒体，再授予通知使用权；
   - 关闭省电限制并允许自启动，否则 HyperOS 会停止它。
3. **在最近任务中锁定它**，这样“全部清除”不会把它关掉。

面板由应用本身绘制，所以它需要在后台保持运行。

## 请先读这里

**与 Anthropic、OpenAI 或小米没有任何关联。** 这是一个人的个人项目。“Claude”和 Claude 吉祥物归 Anthropic 所有。

**不收集任何数据**：没有统计分析、没有遥测、没有崩溃报告，本项目也没有自己的服务器。应用只会连接这些地址：

```
https://claude.ai/oauth/authorize       登录（用于 AI 用量）
https://api.anthropic.com/              你的用量数据
https://platform.claude.com/            你的套餐等级
https://lrclib.net/                     歌词（开启后）
https://api.open-meteo.com/             天气（开启后）
https://geocoding-api.open-meteo.com/   把你输入的地点转换成坐标
https://gitlab.com/api/v4/projects/…    检查更新
```

**你的登录信息只保存在手机上**，用 Android Keystore 中的密钥加密。在已 root 或被入侵的手机上，它仍可能被读取。
在官方客户端之外使用令牌可能违反服务商的条款；据我所知还没有人因此被封号，但**对账号被封、凭据丢失或数据丢失，我不承担任何责任。**
参见[隐私政策](https://rear.896.ro/zh-hans/privacy/)。

**不提供任何担保。** 参见 [LICENSE](LICENSE)。

## 构建

```bash
./build-install.sh    # 调试版，安装到已连接的手机
```

调试版是一个独立的应用，`dev.kb896.aiusage.dev`（“Magic Rear (dev)”），可以和正式版同时安装。它是可调试的：
任何有 ADB 权限的人都能读取它的文件，包括登录信息，所以日常使用的手机请装正式版。正式版由 CI 根据版本标签构建。

## 致谢

- **[u/Over_Squash3318](https://www.reddit.com/user/Over_Squash3318/)** 让面板在小米 17 Pro Max 上跑了起来。

## 署名

- 吉祥物是 **Anthropic 的 Claude 吉祥物**，根据 [ayotomcs.me/claude-mascot](https://ayotomcs.me/claude-mascot) 重建。
  制作者标记属于 **ACRNM**。两者都不在本仓库的许可范围内；如果你是所有者并希望移除，请提交 issue。
- 字体：Helena Zhang 的 [Departure Mono](https://departuremono.com/) 和 Smartsheet 的
  [Outfit](https://github.com/Outfitio/Outfit-Fonts)，SIL Open Font License 1.1。
- 玻璃效果：Kyant0 的 [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)，Apache 2.0
  （着色器内置，控件移植自其示例应用）。
- 图标：Material Symbols Rounded 和 Material Icons，Apache 2.0。
- 着色器：Silk、Color Bends 和 Electric Border 来自 [Vue Bits](https://vue-bits.dev)（DavidHDev）；Liquid Metal 来自
  [Paper Shaders](https://github.com/paper-design/shaders)（Apache 2.0），按 Jakub Antalik 的
  [metal-fx](https://libraries.dev/metal)（MIT）的用法；雪花改编自 [Godot Shaders](https://godotshaders.com/shader/multilayer-snowfall-shader/)
  上 Lumine 的 multilayer snowfall（CC0）。
- 天气数据来自 [Open-Meteo](https://open-meteo.com/)（CC BY 4.0）。歌词来自 [LRCLIB](https://lrclib.net/)。

## 许可

代码采用 MIT 许可：参见 [LICENSE](LICENSE)。上面提到的第三方作品不在此许可范围内。
