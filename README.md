# 视频下载器（downloadtool）

纯离线单机版 Android 视频下载器。包名 `com.palworld.vd`，当前版本 **1.39（versionCode 41，页面 v2.38）**。

## 架构

- **App 壳**：WebView 加载内置页面（`engine/app/src/main/assets/index.html`，单文件前端）+ JS 桥（`MainActivity.AppBridge`）
- **本地引擎**（`LocalEngine.java`）：常驻 Python 守护进程（`vd_daemon.py`，JSON-lines over stdin/stdout）跑 yt-dlp；抖音 SSR 快通道直连；磁力走 aria2c BT（v1.39 起先从公共 .torrent 缓存取元数据再连节点，避开国内 DHT/UDP 限速）；ffmpeg 落地转码归一（H.264/AAC mp4）+ 抖音片尾推广自动裁剪（API 时长对照 + 尾部静态帧检测）。每任务在 `.job-<id>` 独立目录里下载、收尾搬到根目录（标题清洗命名 + 防撞后缀）——批量并发时产物互不污染、同名文件不会被复用
- **页内媒体**（v1.39）：视频详情页在页面内直接播；媒体走引擎目录的 `file://` 绝对路径（`setAllowFileAccess(true)`）——拦截式虚拟域（vd.local）对带偏移的 206 响应在新版 WebView 上会 ERR_FAILED，seek 必挂，只留作引擎未就绪时的兜底；WebView 解不了的编码（HEVC 等）自动检出并切原生播放器
- **原生播放器**（`VideoActivity`）：Media3 ExoPlayer + TextureView（v1.33 起弃用 MediaPlayer——部分 ROM 误报解码失败、seek 不准、不认 mkv/ts），失败自动重试一次，仍失败引导外部播放器；圆形按钮/底部弹层菜单与 App 风格统一
- yt-dlp / ffmpeg / aria2c 二进制由 [youtubedl-android](https://github.com/junkfood02/youtubedl-android) 0.18.0（Maven 依赖）打进 APK

## 构建

```bash
# JDK 17 + Gradle 7.6.1 + Android SDK 34（依赖全走 Maven，无需本地大文件；国内网络临时把 settings.gradle 切到阿里云镜像）
cd engine
gradle assembleRelease -x lintVitalRelease

# 签名（zipalign 后；keystore 不在本仓库，密码本人记录）
zipalign -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk out.apk
apksigner sign --ks vd.keystore --ks-pass pass:*** out.apk
```

仅 `arm64-v8a`（近 8 年手机全是 64 位）。内置 yt-dlp 版本更新：换 `tools/` 下的 zipapp 后按 youtubedl-android 的方式重打包（参考官方文档）。

## 免责声明

本应用仅供个人学习、研究及备份自有内容使用。请遵守所在地区法律法规及各平台服务条款；下载、使用和传播相关内容产生的一切责任由使用者自行承担。
