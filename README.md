# 视频下载器（downloadtool）

纯离线单机版 Android 视频下载器。包名 `com.palworld.vd`，当前版本 **1.32（versionCode 33，页面 v2.31）**。

## 架构

- **App 壳**：WebView 加载内置页面（`engine/app/src/main/assets/index.html`，单文件前端）+ JS 桥（`MainActivity.AppBridge`）
- **本地引擎**（`LocalEngine.java`）：常驻 Python 守护进程（`vd_daemon.py`，JSON-lines over stdin/stdout）跑 yt-dlp；抖音 SSR 快通道直连；磁力走 aria2c BT；ffmpeg 落地转码归一（H.264/AAC mp4）
- **原生播放器**（`VideoActivity`）：MediaPlayer + TextureView，绕开 WebView 解不了 H.265 的问题
- yt-dlp / ffmpeg / aria2c 二进制由 [youtubedl-android](https://github.com/junkfood02/youtubedl-android) 0.18.0（Maven 依赖）打进 APK

## 构建

```bash
# JDK 11 + Gradle 7.6.1 + Android SDK 34（依赖全走 Maven，无需本地大文件）
cd engine
gradle assembleRelease -x lintVitalRelease

# 签名（zipalign 后；keystore 不在本仓库，密码本人记录）
zipalign -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk out.apk
apksigner sign --ks vd.keystore --ks-pass pass:*** out.apk
```

仅 `arm64-v8a`（近 8 年手机全是 64 位）。内置 yt-dlp 版本更新：换 `tools/` 下的 zipapp 后按 youtubedl-android 的方式重打包（参考官方文档）。

## 免责声明

本应用仅供个人学习、研究及备份自有内容使用。请遵守所在地区法律法规及各平台服务条款；下载、使用和传播相关内容产生的一切责任由使用者自行承担。
