# 视频下载器（downloadtool）

纯离线单机版 Android 视频下载器。包名 `com.palworld.vd`，当前版本 **1.45（versionCode 42，页面 v2.39）**。

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

# 签名（zipalign 后）。keystore 不在本仓库——密码不能进 git，所以不写在命令里：
# 不带 --ks-pass 参数，apksigner 会交互式询问（密钥库口令 + 密钥口令，本人在手机备忘/密码管理器里自存）
zipalign -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk out.apk
apksigner sign --ks vd.keystore --ks-key-alias vd out.apk
# 注意：必须用同一把 vd.keystore 签名才能覆盖升级（签名变了系统会拒绝安装），keystore 丢失 = 无法再更新已装的 App
```

### 签名这几样东西分别是什么

| 名字 | 是什么 |
|---|---|
| `app-release-unsigned.apk` | Gradle 构建出的**未签名包**：功能齐全但没有身份，系统拒绝安装 |
| `zipalign` | 构建工具链自带的**对齐优化**：包内资源按 4 字节边界对齐，运行更省内存，发布前必做 |
| `vd.keystore` | **签名私钥库**，相当于这个 App 的"公章"（只存在本人电脑，绝不入库）。系统凭它认"还是同一个 App"；换钥匙签 = 用户无法覆盖升级 |
| `--ks-key-alias vd` | 钥匙在库里的**别名**（一个库可放多把钥匙，本项目用的是名叫 `vd` 的这把） |
| `apksigner` | 官方**签名工具**：用私钥给整个安装包算出签名并写进去 |
| 两个口令 | **密钥库口令**（打开 keystore 文件）和**密钥口令**（使用那把钥匙），把私钥加密存放——文件被偷也打不开 |
| `out.apk` | 签完名的**最终安装包**，发给用户的就是它 |

仅 `arm64-v8a`（近 8 年手机全是 64 位）。内置 yt-dlp 版本更新：换 `tools/` 下的 zipapp 后按 youtubedl-android 的方式重打包（参考官方文档）。

## 免责声明

本应用仅供个人学习、研究及备份自有内容使用。请遵守所在地区法律法规及各平台服务条款；下载、使用和传播相关内容产生的一切责任由使用者自行承担。
