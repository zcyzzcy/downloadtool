package com.palworld.vd;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {

    // 唯一入口：APK 内置页面（file:///android_asset），完全离线单机版。
    // v2.02：URL 带 versionCode——升级后地址变化，彻底绕开 WebView 的旧文档缓存
    private static final String DEFAULT_HOME = "file:///android_asset/index.html?v=" + BuildConfig.VERSION_CODE;
    // 本地引擎的虚拟域名：页面用它访问手机上的文件（shouldInterceptRequest 拦截）
    private static final String LOCAL_HOST = "vd.local";

    private WebView web;
    private volatile boolean pageManageOn = false;   // 页面批量管理模式开着：返回键先退批量（v2.43）
    private SharedPreferences prefs;
    private String pendingBrowserUrl;
    private static final String PENDING_SHARES = "vd_pending_share_texts";
    private LocalEngine engine;
    // v1.48：根布局（web + 内嵌播放器两层）
    private android.widget.FrameLayout rootLayout;
    private VideoBox videoBox;
    private BrowserLayer browserLayer;
    // 进度推送节流：任务 id → 上次推送时刻
    private final java.util.HashMap<String, Long> progGate = new java.util.HashMap<>();
    // 视频全屏（页面点「⛶ 全屏」时由 WebChromeClient 回调）
    private android.view.View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("vd", MODE_PRIVATE);
        // 其他 App 分享链接进来（ACTION_SEND）；v2.03：分享图片直达证件照制作；
        // v2.04：内部工具（二维码"在浏览器打开"）用 vd.OPEN_URL 带链接进来
        if (getIntent() != null && "vd.OPEN_URL".equals(getIntent().getAction())) {
            pendingBrowserUrl = getIntent().getStringExtra("url");
        }
        if (savedInstanceState == null) acceptShareIntent(getIntent());
        // 通知渠道（下载完成推送）
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new android.app.NotificationChannel("vd", "下载完成", android.app.NotificationManager.IMPORTANCE_DEFAULT));
        }
        web = new WebView(this);
        // v1.48：WebView 之上叠一层原生播放器（ExoPlayer + MediaCodec）——用户手机的 WebView
        // 解不出 HEVC 画面（有声无画黑屏），视频画面改由原生层渲染：详情页内嵌播 + 无缝全屏，
        // 不再跳独立播放页/系统播放器。页面通过 videoOpen/videoRect 等桥驱动
        rootLayout = new android.widget.FrameLayout(this);
        rootLayout.addView(web, new android.widget.FrameLayout.LayoutParams(-1, -1));
        videoBox = new VideoBox(this);
        rootLayout.addView(videoBox, new android.widget.FrameLayout.LayoutParams(-1, -1));
        // v1.64 内嵌浏览器层（替代 v1.60 的独立 BrowserActivity 跳转）：底部「浏览器」标签直接开关这层，
        // 顶栏地址/收藏/嗅探/菜单 + 底部导航（后退/前进/回首页/书签/历史）
        setContentView(rootLayout);
        // 模拟器/真机调试：debug 构建开 WebView 远程调试（chrome://inspect / CDP），release 不受影响
        if (BuildConfig.DEBUG) android.webkit.WebView.setWebContentsDebuggingEnabled(true);

        WebSettings s = web.getSettings();
        // v2.02：内置页禁缓存——覆盖升级后 WebView 可能仍拿缓存的旧 index.html
        // （模拟器实测：装新包首页还是旧版，pm clear 才恢复）；URL 带版本参数是双保险
        s.setCacheMode(android.webkit.WebSettings.LOAD_NO_CACHE);
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                 // localStorage：播放进度、任务列表等本地数据
        s.setMediaPlaybackRequiresUserGesture(false); // 弹层自动播放
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);              // 禁用双指缩放（App 不该像网页一样能捏合缩放）
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        // file:// 页面要加载虚拟域 https://vd.local/ 的媒体（拦截器直读手机文件、不出网）。
        // 用 https 是因为 file: 是安全源，http: 子资源会被新版 WebView 按混合内容拦截
        s.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        // 页内媒体（视频/图集）在 App 内直接用引擎目录的 file:// 绝对路径：拦截式虚拟域对
        // Range/206 响应支持不全（新版 Chromium 上带偏移的 206 一律 ERR_FAILED，视频必挂），
        // file:// 走系统文件通道，seek/拖进度条天然可用。API 30+ 默认禁 file://，必须显式开
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        // v2.42 黑屏检测要往 <canvas> 里 drawImage(file://视频) 再 getImageData 读像素（有的 ROM
        // WebView 会谎报解码帧数，画布像素不会说谎）——不放开跨源读，getImageData 会抛安全异常，
        // 这路检测就永远处于弃用状态。App 只读自家引擎目录，不构成额外暴露面
        s.setAllowUniversalAccessFromFileURLs(true);

        // ---------- 本地引擎（解析+下载全部在手机上） ----------
        engine = new LocalEngine(this, new LocalEngine.Listener() {            @Override
            public void onState(final String id, final String state, final String title, final String error, final String filesJson) {
                if (id != null && ("done".equals(state) || "error".equals(state))) {
                    synchronized (progGate) { progGate.remove(id); }   // 节流表随任务完结出表，长会话不再慢性增长
                }
                web.post(new Runnable() {
                    @Override public void run() {
                        try {
                            if (web == null || isDestroyed()) return;   // v1.99：防销毁竞态（detach 前已入队的回调）
                            String js = "window.onLocalState && window.onLocalState("
                                + (id == null ? "null" : JSONObject.quote(id)) + ","
                                + JSONObject.quote(state) + ","
                                + (title == null ? "null" : JSONObject.quote(title)) + ","
                                + (error == null ? "null" : JSONObject.quote(error)) + ","
                                // filesJson 必须转义：直接拼进 JS 的话，文件名里的引号/反斜杠就是注入点
                                // （页面端本来就拿它 JSON.parse，传 JSON 字符串正好对上）
                                + (filesJson == null ? "null" : JSONObject.quote(filesJson)) + ")";
                            web.evaluateJavascript(js, null);
                        } catch (Throwable ignored) {}
                    }
                });
            }
            @Override
            public void onProgress(final String id, final int progress, final String line) {
                // 节流：aria2/yt-dlp 高速下载时输出行极密，每行都穿透 JS 桥开销不小；
                // 200ms 一条足够页面渲染（页面自己还有 800ms 重绘节流）
                long now = android.os.SystemClock.elapsedRealtime();
                synchronized (progGate) {
                    Long t = progGate.get(id);
                    if (t != null && now - t < 200) return;
                    progGate.put(id, now);
                }
                web.post(new Runnable() {
                    @Override public void run() {
                        try {
                            if (web == null || isDestroyed()) return;   // v1.99
                            web.evaluateJavascript("window.onLocalProgress && window.onLocalProgress("
                                + JSONObject.quote(id) + "," + progress + "," + JSONObject.quote(line) + ")", null);
                        } catch (Throwable ignored) {}
                    }
                });
            }
        });
        engine.startInit();

        // 抖音访问凭证预热：抖音解析要求"新鲜 cookies"（游客即可、无需登录）。
        // 用隐藏 WebView 跑一遍抖音首页 JS，ttwid/__ac_signature 等落进 CookieManager。
        // 网页调用原生能力：读 App 私有 cookies / 打开登录页 / 本地引擎
        web.addJavascriptInterface(new AppBridge(), "AndroidBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (isDestroyed()) return;
                notifyClipboard();
                if (pendingBrowserUrl != null) {
                    String target = pendingBrowserUrl;
                    pendingBrowserUrl = null;
                    ensureBrowser().show(target);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri u = Uri.parse(url);
                String host = u.getHost() == null ? "" : u.getHost();
                // 内置页/本地引擎链接留在 App 里；站外链接交给系统浏览器
                if ("file".equals(u.getScheme()) || LOCAL_HOST.equals(host)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (u == null || !LOCAL_HOST.equals(u.getHost())) return null;
                return serveLocal(u, request);
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                // 渲染进程崩了（低端机大页面偶发）：留着一具死 WebView 只会白屏且所有桥调用无响应，
                // 重建整棵界面还能救回来；返回 true 表示已处理，防止系统再顺手杀掉本进程
                try {
                    rootLayout.removeAllViews();
                    web.destroy();
                    recreate();
                } catch (Throwable ignored) { }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            // 视频全屏：页面调用 video.requestFullscreen() 时切换到系统全屏播放器（进度条手势也由它接管）
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) { callback.onCustomViewHidden(); return; }
                customView = view;
                customViewCallback = callback;
                rootLayout.addView(view, new android.widget.FrameLayout.LayoutParams(-1, -1));
            }
            @Override
            public void onHideCustomView() {
                exitCustomView();
            }
        });

        // 「保存」按钮、.apk 安装包等下载交给系统下载器：通知栏可见，落到 下载/ 目录
        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                try {
                    DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                    req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    String name = fileNameOf(url, contentDisposition);
                    req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                    req.setMimeType(mimeType == null || mimeType.isEmpty()
                        ? "application/octet-stream" : mimeType);
                    DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    dm.enqueue(req);
                    Toast.makeText(MainActivity.this, "已开始下载：" + name, Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    // 兜底：下载器处理不了时用系统浏览器打开该链接
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) {}
                }
            }
        });

        if (savedInstanceState == null) web.loadUrl(DEFAULT_HOME);
        else web.restoreState(savedInstanceState);
    }

    // ---------- 本地文件服务：页面用 http://vd.local/file/<名> 播放、/thumb/<名> 取封面 ----------
    private WebResourceResponse serveLocal(Uri u, WebResourceRequest request) {
        try {
            // 用编码形态的路径再统一解码一次：Uri.getPath() 已经解码过一遍，再来一次会把
            // 文件名里原本就有的 "%20""%E5" 这类字面量错误还原（比如 "50%20off.mp4" 变 "50 off.mp4"→404）
            String[] seg = u.getEncodedPath().split("/", 3);   // /file/<name> 或 /thumb/<name>
            if (seg.length < 3) return errResp();
            String kind = seg[1];
            String name = pctDecode(seg[2]);
            if (name.contains("/") || name.contains("\\") || name.contains("..")) return errResp();
            File dir = engine == null ? null : engine.dir();
            if (dir == null) return errResp();
            File f = "thumb".equals(kind) ? new File(new File(dir, ".thumbs"), name) : new File(dir, name);
            if ("thumb".equals(kind) && (!name.endsWith(".jpg") || !LocalEngine.validThumb(dir, name.substring(0, name.length() - 4)))) return errResp();
            if (!f.isFile()) return errResp();
            String low = name.toLowerCase(java.util.Locale.ROOT);
            String mime = "thumb".equals(kind) ? "image/jpeg"
                : low.endsWith(".mp4") || low.endsWith(".m4v") ? "video/mp4"
                : low.endsWith(".webm") ? "video/webm"
                : low.endsWith(".mkv") ? "video/x-matroska"
                : low.endsWith(".mp3") ? "audio/mpeg"
                : low.endsWith(".jpg") || low.endsWith(".jpeg") ? "image/jpeg"
                : low.endsWith(".png") ? "image/png"
                : low.endsWith(".gif") ? "image/gif"
                : low.endsWith(".webp") ? "image/webp"
                : low.endsWith(".avif") ? "image/avif"
                : low.endsWith(".heic") || low.endsWith(".heif") ? "image/heic"
                : low.endsWith(".bmp") ? "image/bmp"
                : "application/octet-stream";

            // Range 支持：视频拖进度条依赖 206 分段响应
            String range = null;
            for (Map.Entry<String, String> e : request.getRequestHeaders().entrySet()) {
                if ("Range".equalsIgnoreCase(e.getKey())) { range = e.getValue(); break; }
            }
            long total = f.length();
            if (range != null && range.startsWith("bytes=")) {
                long start = 0, end = total - 1;
                try {
                    String spec = range.substring(6).split(",")[0].trim();
                    int dash = spec.indexOf('-');
                    if (dash > 0) {
                        start = Long.parseLong(spec.substring(0, dash));
                        if (dash < spec.length() - 1) end = Math.min(Long.parseLong(spec.substring(dash + 1)), total - 1);
                    } else if (dash == 0 && spec.length() > 1) {
                        long suf = Long.parseLong(spec.substring(1));   // 后缀区间 bytes=-N：取最后 N 字节
                        start = Math.max(0, total - suf);
                        end = total - 1;
                    } else {
                        start = Long.parseLong(spec);
                    }
                } catch (Exception ignored) {}
                start = Math.max(0, Math.min(start, total - 1));
                end = Math.max(start, end);
                FileInputStream fis = new FileInputStream(f);
                // skip() 不保证一次跳到位（返回值被忽略时 206 偏移就错了，seek 必挂）；
                // FileChannel.position 是硬定位
                fis.getChannel().position(start);
                Map<String, String> h = new HashMap<>();
                h.put("Accept-Ranges", "bytes");
                h.put("Content-Range", "bytes " + start + "-" + end + "/" + total);
                // Content-Length 绝不能手动设：WebView 会再自动加一个，两头重复成 "6660, 6660"
                // （非法长度）→ 渲染进程按坏响应丢弃，表现为缩略图挂、页内播放报"不支持"
                return new WebResourceResponse(mime, null, 206, "Partial Content", h,
                    new LimitedStream(new java.io.BufferedInputStream(fis, 65536), end - start + 1));   // v1.99：8KB→64KB，媒体流 syscall 减 8 倍
            }
            Map<String, String> h = new HashMap<>();
            h.put("Accept-Ranges", "bytes");
            if ("thumb".equals(kind)) h.put("Cache-Control", "no-store");
            return new WebResourceResponse(mime, null, 200, "OK", h,
                new java.io.BufferedInputStream(new FileInputStream(f), 65536));
        } catch (Exception e) {
            return errResp();
        }
    }

    private String browserSearchTpl, browserSearchHome;

    private BrowserLayer ensureBrowser() {
        if (browserLayer != null) return browserLayer;
        browserLayer = new BrowserLayer(this, new BrowserLayer.Host() {
            @Override public void onPickUrl(final String u) {
                web.post(new Runnable() { @Override public void run() {
                    web.evaluateJavascript("window.onPickUrl && window.onPickUrl(" + JSONObject.quote(u) + ")", null);
                } });
            }
            /** v1.90 浏览器「⤓ 下载」：不退出浏览器——页面直接开任务后台下，逛网页不断流 */
            @Override public void onDownload(final String u, final String title, final String referer, final String userAgent) {
                web.post(new Runnable() { @Override public void run() {
                    web.evaluateJavascript("window.onBrowserDownload && window.onBrowserDownload("
                        + JSONObject.quote(u) + "," + JSONObject.quote(title == null ? "" : title)
                        + "," + JSONObject.quote(referer) + "," + JSONObject.quote(userAgent) + ")", null);
                } });
            }
            @Override public void onExit(final boolean toHome) {
                web.post(new Runnable() { @Override public void run() {
                    web.evaluateJavascript("window.onBrowserClose && window.onBrowserClose(" + (toHome ? 1 : 0) + ")", null);
                } });
            }
            @Override public void runJs(final String js) {
                web.post(new Runnable() { @Override public void run() { web.evaluateJavascript(js, null); } });
            }
        });
        android.widget.FrameLayout.LayoutParams browserParams = new android.widget.FrameLayout.LayoutParams(-1, -1);
        browserParams.bottomMargin = Math.round(58 * getResources().getDisplayMetrics().density);
        rootLayout.addView(browserLayer, browserParams);
        if (browserSearchTpl != null) browserLayer.setSearchTpl(browserSearchTpl, browserSearchHome);
        return browserLayer;
    }

    private static WebResourceResponse errResp() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null,
            new java.io.ByteArrayInputStream(new byte[0]));
    }

    /** 限制读出的字节数（206 响应内容长度） */
    private static class LimitedStream extends java.io.FilterInputStream {
        private long left;
        LimitedStream(InputStream in, long limit) { super(in); this.left = limit; }
        @Override public int read() throws java.io.IOException { if (left <= 0) return -1; left--; return super.read(); }
        @Override public int read(byte[] b, int off, int len) throws java.io.IOException {
            if (left <= 0) return -1;
            int n = super.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }
    }

    /** 百分号解码：不把 + 转成空格（文件名里的加号必须原样保留） */
    private static String pctDecode(String s) {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '%' && i + 2 < s.length()) {
                    try {
                        out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                        i += 2;
                        continue;
                    } catch (Exception ignored) {}
                }
                byte[] b = String.valueOf(c).getBytes("UTF-8");
                out.write(b, 0, b.length);
            }
            return new String(out.toByteArray(), "UTF-8");
        } catch (Exception e) { return s; }
    }

    /** 用系统解码器抽视频封面（缓存到 .thumbs/<name>.jpg，页面 404 时重试拉取） */
    private void makeThumb(final String name) {
        if (engine == null || engine.dir() == null) return;
        engine.ensureThumbs(java.util.Collections.singletonList(name));
    }

    /** 合法相对名校验（防路径穿越） */
    private static boolean okRelName(String name) {
        return name != null && !(name.contains("/") || name.contains("\\") || name.contains(".."));
    }
    /** 相对名 → 引擎目录内文件；非法返回 null */
    private static File relFile(File dir, String name) {
        if (dir == null || !okRelName(name)) return null;
        return new File(dir, name);
    }

    // ---------- 抖音访问凭证预热（隐藏 WebView，不打扰用户；2 小时内有效不重复跑） ----------
    private WebView warmWeb;
    private final android.os.Handler warmHandler = new android.os.Handler();
    private void warmDouyin() {
        if (warmWeb != null) return;   // 正在预热
        if (System.currentTimeMillis() - prefs.getLong("vd_douyin_warm", 0) < 7200000) {
            web.evaluateJavascript("window.onWarmDone && window.onWarmDone(1)", null);
            return;
        }
        runOnUiThread(new Runnable() {
            @Override public void run() {
                warmWeb = new WebView(MainActivity.this);
                final WebView currentWarm = warmWeb;
                warmHandler.postDelayed(() -> { if (warmWeb == currentWarm) finishWarm(); }, 20000);
                warmWeb.setLayoutParams(new android.view.ViewGroup.LayoutParams(1, 1));
                warmWeb.setAlpha(0f);
                WebSettings ws = warmWeb.getSettings();
                ws.setJavaScriptEnabled(true);
                ws.setDomStorageEnabled(true);
                ws.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36");
                android.webkit.CookieManager.getInstance().setAcceptCookie(true);
                android.view.ViewGroup parent = (android.view.ViewGroup) web.getParent();
                if (parent != null) parent.addView(warmWeb);   // 挂 1px 透明视图：不显示但保证 JS 正常执行
                warmWeb.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView v, String u) {
                        if (warmWeb != v) return;
                        warmHandler.postDelayed(new Runnable() {
                            @Override public void run() { if (warmWeb == v) finishWarm(); }
                        }, 6000);
                    }
                });
                warmWeb.loadUrl("https://www.douyin.com/");
            }
        });
    }

    private void finishWarm() {
        if (warmWeb == null) return;
        warmHandler.removeCallbacksAndMessages(null);
        prefs.edit().putLong("vd_douyin_warm", System.currentTimeMillis()).apply();
        web.evaluateJavascript("window.onWarmDone && window.onWarmDone(1)", null);
        if (warmWeb != null) {
            android.view.ViewGroup p = (android.view.ViewGroup) warmWeb.getParent();
            if (p != null) p.removeView(warmWeb);
            try { warmWeb.destroy(); } catch (Exception e) { /* ignore */ }
            warmWeb = null;
        }
    }

    private class AppBridge {
        @JavascriptInterface public boolean isApp() { return true; }
        /** v2.43：页面把"批量管理模式是否开着"同步给壳，返回键/手势返回据此先退批量再退页面 */
        @JavascriptInterface public void setBackOverride(int on) { pageManageOn = on != 0; }

        /** 其他 App 分享进来的文本：页面启动时拉取一次 */
        @JavascriptInterface public String getShared() { return peekSharedText(); }
        @JavascriptInterface public void clearShared() { acknowledgeSharedText(peekSharedText()); }
        @JavascriptInterface public void clearSharedText(String text) { acknowledgeSharedText(text); }
        @JavascriptInterface public void queueSharedText(String text) { enqueueSharedText(text); }

        /** 下载完成系统通知（App 在后台时由页面调用；Android 13+ 首次触发权限请求）。
         *  v1.70 修三件事：① 权限弹窗那次的通知不再被吞（先挂起，授权回调里补发）；
         *  ② 通知 ID 按内容取哈希——不同任务完成不再互相覆盖；③ 拒绝授权后不再反复弹请求 */
        @JavascriptInterface public void notify(final String title, final String text) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            if (!notiAsked) {
                                notiAsked = true;
                                pendingNoti = new String[]{title, text};
                                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
                            }
                            return;
                        }
                        postNoti(title, text);
                    } catch (Exception e) { /* 通知失败不影响功能 */ }
                }
            });
        }

        /** 读系统剪贴板（网页点「粘贴」时调用：App 正处前台，系统允许前台应用读剪贴板） */
        @JavascriptInterface public String readClipboard() {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm == null || !cm.hasPrimaryClip()) return "";
                android.content.ClipData.Item it = cm.getPrimaryClip().getItemAt(0);
                CharSequence cs = it == null ? null : it.coerceToText(MainActivity.this);
                return cs == null ? "" : cs.toString();
            } catch (Exception e) { return ""; }
        }

        /** 写系统剪贴板（链接记忆/详情页的「复制链接·标题」按钮用） */
        @JavascriptInterface public boolean writeClipboard(final String text) {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("vd", text == null ? "" : text));
                return true;
            } catch (Exception e) { return false; }
        }

        /** 读 App 私有 WebView 里存的 cookies（含 HttpOnly），格式 "k=v; k2=v2" */
        @JavascriptInterface public String getCookie(String url) {
            return android.webkit.CookieManager.getInstance().getCookie(url);
        }

        /** 后台静默采集（不跳转页面）：隐藏 1px WebView 打开链接轮询采集，结果推给 window.onCollected。
         *  v2.53 多通道：每个采集一个独立 WebView（最多 3 路并发），回报带 cid 对号入座——
         *  之前全 App 只有一路，一个卡住（如异环/鸣潮这种 SPA 社区页）后面全部"通道忙" */
        @JavascriptInterface public void collect(String url, String kind, final String cid) {
            final String u = url, k = kind, c = cid;
            runOnUiThread(new Runnable() {
                @Override public void run() { startSilentCollect(u, k, c); }
            });
        }

        @JavascriptInterface public void cancelCollect(final String cid) {
            runOnUiThread(() -> stopSilentCollect(cid));
        }

        /** 预取：剪贴板/分享里出现链接时后台先展开短链，用户点解析时零等待 */
        @JavascriptInterface public void prefetch(String url) {
            if (engine != null) engine.prefetch(url);
        }

        /** 工具入口：zipmerge=图片合并。 */
        @JavascriptInterface public void openTool(String id) {
            Class<?> c = "zipmerge".equals(id) ? ZipMergeActivity.class : null;
            if (c != null) {
                try { startActivity(new Intent(MainActivity.this, c)); }
                catch (Exception e) { Toast.makeText(MainActivity.this, "打开失败", Toast.LENGTH_SHORT).show(); }
            }
        }

        /** 打开站点登录页（新开登录窗口）。登录成功后窗口自动检测并返回（无需手动确认） */
        @JavascriptInterface public void openLogin(String url) { openLogin(url, ""); }
        /** 参数打包 marks|ua|watch|title：标志 cookie 名（逗号分隔）|初始 UA（desktop）|要盯的 cookie 域|窗口标题。
         *  watch 与登录页不同域时必填（如 SSO 扫码页在 sso.douyin.com，sessionid 却落在 www.douyin.com） */
        @JavascriptInterface public void openLogin(String url, String marks) {
            String ua = "", watch = "", title = "";
            if (marks != null) {
                String[] p = marks.split("\\|", -1);
                marks = p[0];
                if (p.length > 1) ua = p[1];
                if (p.length > 2) watch = p[2];
                if (p.length > 3) title = p[3];
            }
            Intent i = new Intent(MainActivity.this, LoginWebActivity.class);
            i.putExtra("url", url);
            if (marks != null && marks.length() > 0) i.putExtra("marks", marks);
            if (ua.length() > 0) i.putExtra("ua", ua);
            if (watch.length() > 0) i.putExtra("watch", watch);
            if (title.length() > 0) i.putExtra("title", title);
            startActivityForResult(i, 11);
        }

        /** 内置浏览器（首页搜索条/底部「浏览器」导航进）：v1.64 起是内嵌原生层（不跳 Activity）。
         *  「嗅探」选中媒体或「整页解析」把网址送回解析框 */
        @JavascriptInterface public void openBrowse(String url, String title) {
            final String u = url;
            runOnUiThread(new Runnable() { @Override public void run() {
                ensureBrowser().show(u);
            } });
        }

        @JavascriptInterface public void closeBrowser() {
            runOnUiThread(() -> { if (browserLayer != null) browserLayer.hide(); });
        }
        @JavascriptInterface public void browserBottom(final double height) {
            runOnUiThread(() -> {
                if (browserLayer == null || !Double.isFinite(height)) return;
                android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) browserLayer.getLayoutParams();
                int bottom = Math.min(rootLayout.getHeight() / 3, cssPx(Math.max(0, height)));
                if (lp.bottomMargin != bottom) { lp.bottomMargin = bottom; browserLayer.setLayoutParams(lp); }
            });
        }
        @JavascriptInterface public void browserDownloadResult(final String message) {
            runOnUiThread(() -> { if (browserLayer != null && browserLayer.isVisible()) browserLayer.downloadResult(message); });
        }

        /** 告诉浏览器层当前搜索引擎（搜索模板 …q=%s + 主页）：地址栏输关键词搜索、新标签页用 */
        @JavascriptInterface public void setBrowserSearch(String tpl, String home) {
            final String t = tpl, hm = home;
            runOnUiThread(new Runnable() { @Override public void run() {
                browserSearchTpl = t; browserSearchHome = hm;
                if (browserLayer != null) browserLayer.setSearchTpl(t, hm);
            } });
        }

        // ---------- 本地引擎（解析+下载都在手机上，不走服务器） ----------

        /** 引擎状态：init 初始化中 / ready 可用 / error 初始化失败 */
        @JavascriptInterface public String engineState() { return engine == null ? "init" : engine.state(); }
        @JavascriptInterface public String engineDetail() { return engine == null ? "" : engine.detail(); }

        /** 提交解析+下载。cookies 为 "k=v" 每行一条（空串表示不带）；返回任务 id */
        @JavascriptInterface public String localSubmit(String url, String quality, String cookies) {
            return localBrowserSubmit(url, quality, cookies, "", "", "", 0);
        }
        /** 提交解析+下载。cookies 为 "k=v" 每行一条（空串表示不带）；返回任务 id。
         *  outTitle（v1.97）：嗅探直链下载的强制命名（页面标题）——yt-dlp 通用提取器对直链
         *  给的"标题"是 URL 尾巴垃圾，落盘文件名和任务名会跟浏览器里看到的完全对不上 */
        @JavascriptInterface public String localBrowserSubmit(String url, String quality, String cookies, String referer, String userAgent, String outTitle, int force) {
            StringBuilder jar = new StringBuilder(cookies == null ? "" : cookies);
            if (jar.toString().trim().isEmpty()) jar.append("# Netscape HTTP Cookie File\n");
            else if (jar.charAt(jar.length() - 1) != '\n') jar.append('\n');
            java.util.HashSet<String> added = new java.util.HashSet<>();
            for (String target : new String[]{referer, url}) {
                try {
                    Uri uri = Uri.parse(target);
                    String domain = uri.getHost();
                    if (domain == null || !added.add(domain)) continue;
                    String raw = android.webkit.CookieManager.getInstance().getCookie(target);
                    if (raw == null) continue;
                    for (String item : raw.split(";")) {
                        int eq = item.indexOf('='); if (eq <= 0) continue;
                        jar.append(domain).append("\tFALSE\t/\t")
                            .append("https".equals(uri.getScheme()) ? "TRUE" : "FALSE")
                            .append("\t0\t").append(item.substring(0, eq).trim())
                            .append('\t').append(item.substring(eq + 1).trim()).append('\n');
                    }
                } catch (Exception ignored) { }
            }
            return engine.submit(url, quality, jar.toString(), referer, userAgent, outTitle, force);
        }
        /** 提交磁力链接（手机本地 BT 下载，不经过服务器）；返回任务 id */
        @JavascriptInterface public String localMagnet(String magnet) {
            return engine.magnetSubmit(magnet);
        }
        @JavascriptInterface public void previewMagnet(final String id) {
            runOnUiThread(() -> startActivity(new Intent(MainActivity.this, MagnetActivity.class).putExtra("task", id)));
        }
        /** 抖音图集/笔记：App 内采集到的直链在手机上直接下载；mediaJson={images:[],videos:[],desc} */
        @JavascriptInterface public String localRescue(String mediaJson) {
            return engine.rescueSubmit(mediaJson);
        }
        @JavascriptInterface public void localCancel(String id) { engine.cancel(id); }
        /** 本地文件列表 JSON：[{name,sizeMB,mtime}] */
        @JavascriptInterface public String localFiles() { return engine.listFiles(); }
        @JavascriptInterface public String localFilesRefresh() { return engine.refreshFiles(); }
        @JavascriptInterface public void localFilesAsync(final String requestId) {
            new Thread(() -> {
                final String files = engine.refreshFiles();
                web.post(() -> {
                    if (isDestroyed()) return;
                    web.evaluateJavascript("window.onLibraryFiles && window.onLibraryFiles("
                        + JSONObject.quote(requestId) + "," + JSONObject.quote(files) + ")", null);
                });
            }, "vd-file-list").start();
        }
        @JavascriptInterface public String localActiveTasks() { return engine == null ? "[]" : engine.activeTaskIds(); }
        @JavascriptInterface public String localTaskStates() { return engine == null ? "[]" : engine.taskStateSnapshots(); }
        @JavascriptInterface public void localAcknowledgeTask(String id) { if (engine != null && id != null) engine.acknowledgeTask(id); }
        /** 删除本地文件。namesJson: ["a.mp4"]；返回 {ok,error} */
        @JavascriptInterface public String localDelete(String namesJson) { return engine.deleteFiles(namesJson); }
        /** 后台生成封面（页面随后拉 http://vd.local/thumb/<name>.jpg） */
        @JavascriptInterface public void localThumb(String name) { makeThumb(name); }
        /** 页内播放黑屏自救（v2.39）：WebView 解不了的编码（HEVC 等）在手机上转成 H.264 mp4，
         *  完成后回调 window.onTranscoded(新文件名, 是否成功) */
        @JavascriptInterface public void localTranscode(final String name) {
            if (!okRelName(name)) return;
            new Thread(new Runnable() {
                @Override public void run() {
                    String out = name;
                    boolean ok = false;
                    try {
                        if (engine != null) {
                            out = engine.transcodeToH264(name);
                            ok = out != null && !out.equals(name);
                        }
                    } catch (Throwable ignored) {}
                    final String js = "window.onTranscoded && window.onTranscoded(" + JSONObject.quote(out) + "," + ok + ")";
                    runOnUiThread(new Runnable() {
                        @Override public void run() { try { web.evaluateJavascript(js, null); } catch (Throwable ignored) {} }
                    });
                }
            }, "vd-transcode").start();
        }
        /** v2.44：查视频编码（h264/hevc/…）。页面打开视频先问这个——不是 h264/mpeg4 就先转码再播 */
        @JavascriptInterface public String localProbe(final String name) {
            if (!okRelName(name)) return "";
            if (engine == null) return "";
            try { return engine.probeVideoCodec(name); } catch (Throwable t) { return ""; }
        }
        /** 预热抖音访问凭证（无需登录；完成后推 window.onWarmDone）。
         *  v2.02 修复：必须切 UI 线程——warmDouyin 的"2 小时内已预热"早退分支会直接
         *  evaluateJavascript，桥线程上调它抛 IllegalStateException 穿透成 JS 异常，
         *  把 onLocalState 引擎就绪回调的后半段炸掉（中断任务标记/列表刷新全没跑） */
        @JavascriptInterface public void localWarmDouyin() {
            runOnUiThread(new Runnable() {
                @Override public void run() { try { warmDouyin(); } catch (Throwable ignored) {} }
            });
        }

        /** 原生播放器播视频（VideoActivity）：WebView 的 video 解不了 H.265 等编码（有声无画黑屏），
         *  原生 MediaPlayer 直接走系统解码器，图库能放的它都能放。namesJson 是文件名数组，index 起始序号 */
        @JavascriptInterface public void openVideo(final String namesJson, final int index, final String title) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        File dir = engine == null ? null : engine.dir();
                        if (dir == null) { Toast.makeText(MainActivity.this, "播放器还没就绪，稍后再试", Toast.LENGTH_SHORT).show(); return; }
                        JSONArray names = new JSONArray(namesJson);
                        ArrayList<String> paths = new ArrayList<>();
                        for (int i = 0; i < names.length(); i++) {
                            String n = names.optString(i, "");
                            File f = relFile(dir, n);
                            if (f != null && f.isFile()) paths.add(f.getAbsolutePath());
                        }
                        if (paths.isEmpty()) { Toast.makeText(MainActivity.this, "文件不存在", Toast.LENGTH_SHORT).show(); return; }
                        int ix = index;
                        if (ix < 0 || ix >= paths.size()) ix = 0;
                        Intent it = new Intent(MainActivity.this, VideoActivity.class);
                        it.putExtra("files", paths.toArray(new String[0]));
                        it.putExtra("index", ix);
                        it.putExtra("title", title == null ? "" : title);
                        startActivity(it);
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "播放失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        // ---------- v1.48 App 内嵌播放器（详情页内嵌播 + 无缝全屏，不跳页面） ----------
        /** CSS px → 根布局像素（WebView 在窗口里可能有偏移：挖孔/系统栏留白） */
        private int cssToRootX(double css) {
            int[] w = new int[2], r = new int[2];
            web.getLocationInWindow(w); rootLayout.getLocationInWindow(r);
            return (w[0] - r[0]) + Math.round((float) css * getResources().getDisplayMetrics().density);
        }
        private int cssToRootY(double css) {
            int[] w = new int[2], r = new int[2];
            web.getLocationInWindow(w); rootLayout.getLocationInWindow(r);
            return (w[1] - r[1]) + Math.round((float) css * getResources().getDisplayMetrics().density);
        }
        private int cssPx(double css) { return Math.round((float) css * getResources().getDisplayMetrics().density); }

        /** 打开视频并把播放器贴到页面舞台矩形上。startMs 续播位置；title 详情标题（全屏顶栏用） */
        @JavascriptInterface public void videoOpen(final String name, final double x, final double y, final double w, final double h, final long startMs, final String title) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        File dir = engine == null ? null : engine.dir();
                        File f = relFile(dir, name);
                        if (f == null) return;
                        if (!f.isFile()) {
                            videoBox.close();
                            Toast.makeText(MainActivity.this, "文件不存在：" + name, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        videoBox.open(f, startMs, title == null ? "" : title, name);
                        videoBox.setRect(cssToRootX(x), cssToRootY(y), cssPx(w), cssPx(h));
                    } catch (Exception ignored) {}
                }
            });
        }
        @JavascriptInterface public void videoPlaylist(final String name, final int index, final int count) {
            runOnUiThread(() -> videoBox.setPlaylist(name, index, count,
                () -> web.evaluateJavascript("window.stepPlaylistFromNative(" + JSONObject.quote(name) + ",-1)", null),
                () -> web.evaluateJavascript("window.stepPlaylistFromNative(" + JSONObject.quote(name) + ",1)", null)));
        }
        /** 页面滚动/排版变化时同步舞台矩形（播放器始终贴着详情页的视频区） */
        @JavascriptInterface public void videoRect(final double x, final double y, final double w, final double h) {
            runOnUiThread(new Runnable() {
                @Override public void run() { videoBox.setRect(cssToRootX(x), cssToRootY(y), cssPx(w), cssPx(h)); }
            });
        }
        @JavascriptInterface public void videoPlay() { runOnUiThread(new Runnable() { @Override public void run() { videoBox.play(); } }); }
        @JavascriptInterface public void videoPause() { runOnUiThread(new Runnable() { @Override public void run() { videoBox.pause(); } }); }
        @JavascriptInterface public void videoSeek(final long ms) { runOnUiThread(new Runnable() { @Override public void run() { videoBox.seekTo(ms); } }); }
        @JavascriptInterface public void videoSpeed(final double s) { runOnUiThread(new Runnable() { @Override public void run() { videoBox.setSpeed((float) s); } }); }
        @JavascriptInterface public void videoFull(final int on) { runOnUiThread(new Runnable() { @Override public void run() { if (on != 0) videoBox.enterFull(); else videoBox.exitFull(); } }); }
        @JavascriptInterface public void videoClose() { runOnUiThread(new Runnable() { @Override public void run() { videoBox.close(); } }); }
        /** 页面 400ms 轮询："playing|paused|ended|error|idle,位置ms,总长ms"（连播/进度记忆由页面管） */
        @JavascriptInterface public String videoState() { return videoBox == null ? "idle,0,0" : videoBox.state(); }

        /** 目录列表（自定义保存位置用）：path 为空列存储卷（内部存储+SD卡），否则列该目录的子目录 */
        @JavascriptInterface public String listDirs(String path) {
            try {
                JSONObject o = new JSONObject();
                JSONArray es = new JSONArray();
                if (path == null || path.trim().isEmpty()) {
                    java.util.LinkedHashSet<String> roots = new java.util.LinkedHashSet<>();
                    try {
                        File prim = Environment.getExternalStorageDirectory();
                        if (prim.isDirectory()) roots.add(prim.getAbsolutePath());
                    } catch (Exception ignored) {}
                    try {   // SD 卡卷：getExternalFilesDirs 返回各卷上的应用目录，剥掉后缀就是卷根
                        File[] exts = getExternalFilesDirs(null);
                        if (exts != null) {
                            for (File e : exts) {
                                if (e == null) continue;
                                String p = e.getAbsolutePath();
                                int cut = p.indexOf("/Android/data/");
                                if (cut > 0) roots.add(p.substring(0, cut));
                            }
                        }
                    } catch (Exception ignored) {}
                    o.put("path", "");
                    o.put("parent", JSONObject.NULL);
                    for (String r : roots) {
                        JSONObject d = new JSONObject();
                        d.put("n", r.endsWith("emulated/0") ? "内部存储" : "SD 卡 · " + r.substring(r.lastIndexOf('/') + 1));
                        d.put("p", r);
                        es.put(d);
                    }
                } else {
                    File base = new File(path);
                    o.put("path", base.getAbsolutePath());
                    String par = base.getParent();
                    o.put("parent", par != null && par.startsWith("/storage") ? par : JSONObject.NULL);
                    File[] kids = base.listFiles();
                    ArrayList<File> dirs = new ArrayList<>();
                    if (kids != null) {
                        for (File k : kids) if (k.isDirectory() && !k.getName().startsWith(".")) dirs.add(k);
                    }
                    java.util.Collections.sort(dirs, new java.util.Comparator<File>() {
                        public int compare(File a, File b) { return a.getName().compareToIgnoreCase(b.getName()); }
                    });
                    for (File d : dirs) {
                        JSONObject j = new JSONObject();
                        j.put("n", d.getName());
                        j.put("p", d.getAbsolutePath());
                        es.put(j);
                    }
                }
                o.put("entries", es);
                return o.toString();
            } catch (Exception e) {
                return "{\"path\":\"\",\"entries\":[]}";
            }
        }

        /** 用系统播放器打开本地文件：WebView 解不了的编码（HEVC/AV1 等）交给系统 App，图库能放的它都能放 */
        @JavascriptInterface public void openSystem(final String name) {            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        File dir = engine == null ? null : engine.dir();
                        if (dir == null) { Toast.makeText(MainActivity.this, "文件目录还没就绪，稍后再试", Toast.LENGTH_SHORT).show(); return; }
                        File f = relFile(dir, name);
                        if (f == null || !f.isFile()) { Toast.makeText(MainActivity.this, "文件不存在", Toast.LENGTH_SHORT).show(); return; }
                        Uri uri = androidx.core.content.FileProvider.getUriForFile(MainActivity.this, "com.palworld.vd.fileprovider", f);
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setDataAndType(uri, mimeOf(name));
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "打开失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
            });
        }

        /** 把文件扫进系统媒体库（相册/文件管理器立刻可见）——详情页「保存到手机」用 */
        @JavascriptInterface public void scanFile(final String name) {
            try {
                if (name == null || name.contains("/") || name.contains("\\") || name.contains("..")) return;
                File dir = engine == null ? null : engine.dir();
                if (dir == null) return;
                File f = relFile(dir, name);
                if (f == null || !f.isFile()) return;
                android.media.MediaScannerConnection.scanFile(MainActivity.this,
                    new String[]{ f.getAbsolutePath() }, new String[]{ mimeOf(name) }, null);
            } catch (Exception ignored) {}
        }
        /** 用系统文件管理器打开视频保存目录；全打不开就直接开相册（文件就存在相册默认文件夹里） */
        @JavascriptInterface public void openFolder() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        File dir = engine.dir();
                        if (dir == null || !dir.exists()) { Toast.makeText(MainActivity.this, "保存目录还没创建（下载一个文件后就有了）", Toast.LENGTH_SHORT).show(); return; }
                        // 各 ROM 文件管理器认的"打开目录"方式不一样，逐个试。file:// 在 API24+ 会抛
                        // FileUriExposedException（之前就死在这：其实有管理器，却弹"没有可用文件管理器"），
                        // 全部改走 content://（FileProvider / DocumentsUI）
                        // 1) 系统自带"文件"应用（DocumentsUI，content:// + 目录 mime）
                        String rel = primaryRelativePath(dir);
                        if (rel != null) {
                            try {
                                Uri doc = android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:" + rel);
                                Intent i1 = new Intent(Intent.ACTION_VIEW);
                                i1.setDataAndType(doc, "vnd.android.document/directory");
                                i1.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(i1);
                                return;
                            } catch (Exception ignored) {}
                        }
                        // 2) resource/folder + FileProvider content://（小米/华为/MT管理器等认这个）
                        try {
                            Uri cu = androidx.core.content.FileProvider.getUriForFile(MainActivity.this, "com.palworld.vd.fileprovider", dir);
                            Intent i2 = new Intent(Intent.ACTION_VIEW);
                            i2.setDataAndType(cu, "resource/folder");
                            i2.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(i2);
                            return;
                        } catch (Exception ignored) {}
                        // 3) directory mime + FileProvider content://
                        try {
                            Uri cu3 = androidx.core.content.FileProvider.getUriForFile(MainActivity.this, "com.palworld.vd.fileprovider", dir);
                            Intent i3 = new Intent(Intent.ACTION_VIEW);
                            i3.setDataAndType(cu3, "vnd.android.document/directory");
                            i3.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(i3);
                            return;
                        } catch (Exception ignored) {}
                        // 4) 直接打开系统相册：文件就存在相册默认文件夹（DCIM/Camera）里，一定能看到
                        try {
                            Intent i4 = new Intent(Intent.ACTION_MAIN);
                            i4.addCategory(Intent.CATEGORY_APP_GALLERY);
                            i4.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(i4);
                            return;
                        } catch (Exception ignored) {}
                        // 5) 全都不认：弹路径对话框（可复制）
                        showFolderDialog(dir);
                    } catch (Exception e) { /* ignore */ }
                }
            });
        }

        // ---------- 存储权限：公共 下载/vd vs 应用私有目录 ----------

        /** 当前保存目录是否在公共 下载/vd（安卓10+ 没授权"所有文件访问"时会是私有目录，图库/文件管理器看不到） */
        @JavascriptInterface public boolean storagePublic() { return engine != null && engine.dirIsPublic(); }

        // ---------- 设置：文件保存位置（download=下载/vd / private=应用私有 / custom=自选目录） ----------
        /** 当前保存目录模式 */
        @JavascriptInterface public String saveDirMode() { return engine == null ? "download" : engine.dirMode(); }
        /** 切换保存目录（只搬本 App 下载的文件）。custom 模式带自选路径。返回新路径；失败返回空串 */
        @JavascriptInterface public String setSaveDir(final String mode, final String path) {
            try {
                String p = "custom".equals(mode) ? engine.setDirCustom(path) : engine.setDirMode(mode);
                return p == null ? "" : p;
            } catch (Exception e) { return ""; }
        }

        /** 去系统设置授权：安卓11+ 是"所有文件访问"开关；安卓10 及以下弹运行时写权限 */
        @JavascriptInterface public void grantStorage() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= 30) {
                            if (!android.os.Environment.isExternalStorageManager()) {
                                try {
                                    startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:" + getPackageName())));
                                    return;
                                } catch (Exception e) {
                                    startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                                    return;
                                }
                            }
                        } else if (checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"}, 2);
                            return;
                        }
                        Toast.makeText(MainActivity.this, "已授权", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) { Toast.makeText(MainActivity.this, "打开设置失败：" + e.getMessage(), Toast.LENGTH_LONG).show(); }
                }
            });
        }

        /** 授权后调用：把私有目录里的文件迁到公共 下载/vd 并扫描图库。成功返回新路径，失败返回空串 */
        @JavascriptInterface public String migrateStorage() {
            try {
                String p = engine.migrateToPublic();
                return p == null ? "" : p;
            } catch (Exception e) { return ""; }
        }

        // ---------- 持久化 kv（SharedPreferences）：播放进度等关键数据双保险，不依赖 WebView 的 localStorage ----------

        /** Dedicated durable history snapshot: the bridge thread may wait for disk, never the UI thread. */
        @JavascriptInterface public String taskRecords() {
            return getSharedPreferences("vd", MODE_PRIVATE).getString("vd_task_records_v1", "");
        }
        @JavascriptInterface public boolean saveTaskRecords(String json) {
            if (json == null || json.length() > 4 * 1024 * 1024) return false;
            try {
                new org.json.JSONArray(json);
                return getSharedPreferences("vd", MODE_PRIVATE).edit().putString("vd_task_records_v1", json).commit();
            } catch (Exception invalid) { return false; }
        }
        @JavascriptInterface public String kvGet(String k) {
            return prefs.getString("kv_" + k, "");
        }
        @JavascriptInterface public void kvSet(String k, String v) {
            prefs.edit().putString("kv_" + k, v == null ? "" : v).apply();
        }

        // ---------- 播放器亮度手势：直接调系统窗口亮度（比 CSS 滤镜更接近原生播放器手感） ----------

        /** 0.06~1；播放器左侧竖滑调节 */
        @JavascriptInterface public void setBrightness(final float b) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        android.view.Window w = getWindow();
                        android.view.WindowManager.LayoutParams lp = w.getAttributes();
                        lp.screenBrightness = Math.max(0.06f, Math.min(1f, b));
                        w.setAttributes(lp);
                    } catch (Exception ignored) {}
                }
            });
        }
        /** 恢复系统默认亮度（退出播放器/全屏时调用） */
        @JavascriptInterface public void resetBrightness() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        android.view.Window w = getWindow();
                        android.view.WindowManager.LayoutParams lp = w.getAttributes();
                        lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                        w.setAttributes(lp);
                    } catch (Exception ignored) {}
                }
            });
        }

        /** 播放视频时保持屏幕常亮（B站等播放器的标准行为）；暂停/离开播放页时关掉 */
        @JavascriptInterface public void setKeepScreenOn(final boolean on) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        if (on) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    } catch (Exception ignored) {}
                }
            });
        }
    }

    /** /storage/emulated/0/Download/vd → Download/vd（不在主存储下返回 null） */
    private static String primaryRelativePath(File dir) {
        try {
            String p = dir.getAbsolutePath();
            for (String root : new String[]{"/storage/emulated/0/", "/sdcard/"}) {
                if (p.startsWith(root)) return p.substring(root.length());
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 没有任何文件管理器可用时的兜底：路径 + 复制按钮 + 去哪找的说明 */
    private void showFolderDialog(final File dir) {
        final String path = dir.getAbsolutePath();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        box.setPadding(pad, pad, pad, pad);
        TextView tv = new TextView(this);
        tv.setText(getString(R.string.folder_fallback_help, path));
        tv.setLineSpacing(0, 1.5f);
        Button copy = new Button(this);
        copy.setText("复制保存路径");
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("path", path));
                    Toast.makeText(MainActivity.this, "路径已复制", Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) {}
            }
        });
        box.addView(tv);
        box.addView(copy);
        new AlertDialog.Builder(MainActivity.this)
            .setTitle("保存位置")
            .setView(box)
            .setPositiveButton("知道了", null)
            .show();
    }

    /** 登录窗口关闭：把结果推给页面（1=已自动捕获登录，0=手动返回）。
     *  浏览器 v1.64 起为内嵌层，不再有 requestCode 12 的回传路径 */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 11 && web != null) {
            web.evaluateJavascript("window.onLoginBack && window.onLoginBack(" + (resultCode == RESULT_OK ? 1 : 0) + ")", null);
            return;
        }
        if (requestCode == 13 && browserLayer != null) {   // 浏览器层网页文件上传的结果
            browserLayer.onFileResult(resultCode, data);
            return;
        }
    }

    @Override
    protected void onPause() {
        if (browserLayer != null) { browserLayer.flushCookies(); browserLayer.pause(); }
        // v1.70：退后台必须暂停内嵌播放器——ExoPlayer 的 handleAudioFocus 只管"别的 App 抢焦点"，
        // 自己退后台声音会继续放（setAudioAttributes 的 true 参数管不到这一层），用户按 Home 后
        // 声音还在响是典型投诉。回前台不自动续播，用户点播放键继续
        // v1.99：顺带停掉 10Hz 的 tick 自跳循环——以前退后台仍以 10 次/秒唤醒主线程直到销毁，纯耗电
        if (videoBox != null) runOnUiThread(new Runnable() { @Override public void run() { videoBox.pause(); videoBox.stopTick(); } });
        super.onPause();
    }

    // v1.70：通知权限结果——授权了就把权限弹窗吞掉的那条通知补发（AppBridge.notify 挂起的）
    private boolean notiAsked = false;
    private String[] pendingNoti = null;
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1 && pendingNoti != null) {
            String[] pn = pendingNoti;
            pendingNoti = null;
            if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                postNoti(pn[0], pn[1]);
            }
        }
    }
    private void postNoti(String title, String text) {
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            android.app.Notification n;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                n = new android.app.Notification.Builder(this, "vd")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(title).setContentText(text).setAutoCancel(true).build();
            } else {
                n = new android.app.Notification.Builder(this)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(title).setContentText(text).setAutoCancel(true).build();
            }
            nm.notify((title + "|" + text).hashCode(), n);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        // v1.99：先解绑引擎监听——后台任务/守护进程照常跑（static 单例），但不能再把事件
        // 推到即将销毁的 WebView 上（泄漏整棵 View 树 + destroy 后 evaluateJavascript 崩溃）
        if (engine != null) { try { engine.detach(); } catch (Throwable ignored) {} }
        // v1.70：完整收尾——以前只销毁了浏览器层，主 WebView/隐藏采集 WebView/预热 WebView/
        // 播放器全部泄漏，采集轮询链还会在 Activity 销毁后继续 evaluateJavascript
        collectQueue.clear();
        for (String cid : new java.util.ArrayList<>(collectRuns.keySet())) stopSilentCollect(cid);
        if (warmHandler != null) warmHandler.removeCallbacksAndMessages(null);
        if (warmWeb != null) {
            try {
                android.view.ViewGroup wp = (android.view.ViewGroup) warmWeb.getParent();
                if (wp != null) wp.removeView(warmWeb);
                warmWeb.destroy();
            } catch (Exception ignored) {}
            warmWeb = null;
        }
        if (browserLayer != null) browserLayer.destroy();
        if (videoBox != null) { try { videoBox.close(); } catch (Exception ignored) {} }
        if (web != null) {
            try {
                web.loadUrl("about:blank");
                android.view.ViewGroup rp = (android.view.ViewGroup) web.getParent();
                if (rp != null) rp.removeView(web);
                web.destroy();
            } catch (Exception ignored) {}
            web = null;   // v1.99：桥回调里的判空防御要靠它
        }
        super.onDestroy();
    }

    /** 文件名 → MIME（系统播放器 ACTION_VIEW 用；video/mp4 等常用类型系统都能路由）。
     *  VideoActivity 的外部兜底也用 */
    public static String mimeOf(String name) {
        String low = name.toLowerCase(java.util.Locale.ROOT);
        if (low.endsWith(".mp4") || low.endsWith(".m4v") || low.endsWith(".mov")) return "video/mp4";
        if (low.endsWith(".webm")) return "video/webm";
        if (low.endsWith(".mkv")) return "video/x-matroska";
        if (low.endsWith(".mp3")) return "audio/mpeg";
        if (low.endsWith(".m4a") || low.endsWith(".aac")) return "audio/aac";
        if (low.endsWith(".flac")) return "audio/flac";
        if (low.endsWith(".wav")) return "audio/wav";
        if (low.endsWith(".jpg") || low.endsWith(".jpeg")) return "image/jpeg";
        if (low.endsWith(".png")) return "image/png";
        if (low.endsWith(".gif")) return "image/gif";
        if (low.endsWith(".webp")) return "image/webp";
        if (low.endsWith(".heic") || low.endsWith(".heif")) return "image/heic";
        if (low.endsWith(".avif")) return "image/avif";
        if (low.endsWith(".bmp")) return "image/bmp";
        return "application/octet-stream";
    }

    private static String fileNameOf(String url, String contentDisposition) {
        String name = "";
        if (contentDisposition != null) {
            int i = contentDisposition.indexOf("filename=");
            if (i >= 0) name = contentDisposition.substring(i + 9).trim().replace("\"", "");
        }
        if (name.isEmpty()) {
            String path = url.split("[?#]")[0];
            name = path.substring(path.lastIndexOf('/') + 1);
            try { name = java.net.URLDecoder.decode(name, "UTF-8"); } catch (Exception ignored) {}
        }
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (name.isEmpty()) name = "download";
        return name;
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    // ---------- 后台静默采集（隐藏 WebView，不离开当前页面） ----------
    // 采集表达式与 server.js 的 extractNoteMedia/collectUserLinks 逻辑一致，并加了更严的兜底：
    // 找不到笔记容器时返回空（让外层继续等），而不是抓全页图片（曾把头像当正文图下载）。
    private static final String EXPR_NOTE =
        "(() => { try {" +
        " const root = document.querySelector('[class*=\"note-detail-container\"]') || document.querySelector('[class*=\"note-content\"]');" +
        " if (!root) return JSON.stringify({ media: null });" +
        " const videos = [];" +
        " for (const v of [...root.querySelectorAll('video')]) {" +
        "   const u = v.currentSrc || (v.querySelector('source') && v.querySelector('source').src) || v.src;" +
        // 只认真正的视频 CDN 直链：页面里的预览播放器/背景 <video> 会混进来，图集就"下成视频"了
        "   if (u && u.indexOf('uuu_') < 0 && (u.indexOf('douyinvod') >= 0 || u.indexOf('.mp4') >= 0) && videos.indexOf(u) < 0) videos.push(u);" +
        " }" +
        " let images = [...root.querySelectorAll('img')].map(i => i.src)" +
        "   .filter(u => u && u.indexOf('douyinpic') >= 0 && !/avatar|head_|icon|logo|badge/i.test(u));" +
        " if (!videos.length && !images.length) {" +
        "   const s = JSON.stringify(window.SSR_RENDER_DATA || {});" +
        "   const urls = (s.match(/https?:\\/\\/[^\\\"]{25,400}/g) || []).map(u => u.replace(/&amp;/g, '&'));" +
        "   images = urls.filter(u => u.indexOf('douyinpic') >= 0 && !/avatar|head_|icon|logo|badge/i.test(u));" +
        " }" +
        " const text = (root.innerText || '');" +
        " const desc = (text.match(/#\\s?([^#\\s]{1,30})/) || [])[1] || text.replace(/\\s+/g, ' ').slice(0, 30);" +
        " return JSON.stringify({ media: { images: [...new Set(images)], videos: videos, desc: desc || '' } });" +
        "} catch (e) { return JSON.stringify({ media: null }); } })()";

    private static final String EXPR_USER =
        "(() => { try {" +
        " const urls = [...document.querySelectorAll('a[href]')].map(a => a.href)" +
        "   .filter(h => /douyin\\.com\\/(video|note)\\/\\d+/.test(h));" +
        " return JSON.stringify({ links: [...new Set(urls)].slice(0, 50) });" +
        "} catch (e) { return JSON.stringify({ links: [] }); } })()";

    // Community extractors are separate assets so their full post schemas can be tested.
    private String communityScript;
    private String communityExpression(String site) {
        if (communityScript == null) {
            try (InputStream in = getAssets().open("community_collect.js")) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] bytes = new byte[4096];
                int n;
                while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
                communityScript = out.toString("UTF-8");
            } catch (Exception e) {
                return "JSON.stringify({media:null,error:'正文解析组件加载失败，请重新安装'})";
            }
        }
        return communityScript + "\nvdCollectCommunity(" + JSONObject.quote(site) + ")";
    }

    // 通用图集兜底（微信公众号文章 / 微博相册 / 知乎 / 快手图文 / 头条 / 游戏社区等）：
    // 正文图 CDN 域名 + 懒加载 data-src，og:title 当文件名；视频只取非 blob 直链。微博缩略图升级成大图
    private static final String EXPR_GENERIC =
        "(() => { try {" +
        " const hosts = /mmbiz\\.qpic\\.cn|sinaimg\\.cn|xhscdn\\.com|douyinpic|hdslb\\.com|zhimg|yximgs|kuaishou|gifshow|pstatp|toutiaoimg|byteimg|xiaoheihe|heybox|maxjia|skland|zizzs|aliyuncs|miyoushe|mihoyo|hoyolab|kurobbs|kurogame|tapimg|tiebapic|imgsrc|coolapk|3839|ngabbs|nga\\.178/;" +
        " const urls = [];" +
        " for (const i of [...document.querySelectorAll('img')]) {" +
        "   const d = (i.dataset && i.dataset.src) || '';" +
        "   const c = i.currentSrc || i.src || '';" +
        "   let u = hosts.test(d) ? d : c;" +
        "   if (!u || !/^https?:|^\\/\\//.test(u)) continue;" +
        "   if (u.indexOf('//') === 0) u = 'https:' + u;" +
        "   if (!hosts.test(u) || /avatar|head_|icon|logo|badge|emoji|spacer|default/i.test(u)) continue;" +
        "   u = u.replace('/thumb300/', '/large/').replace('/orj360/', '/large/');" +
        "   urls.push(u);" +
        " }" +
        " const vids = [...document.querySelectorAll('video')].map(v => v.currentSrc || v.src)" +
        "   .filter(u => u && u.indexOf('blob:') !== 0 && (u.indexOf('.mp4') >= 0 || u.indexOf('video') >= 0));" +
        " if (!urls.length && !vids.length) return JSON.stringify({ media: null });" +
        " let desc = ''; try { desc = document.querySelector('meta[property=\"og:title\"]').content; } catch (e) {}" +
        " if (!desc) desc = document.title || '';" +
        " return JSON.stringify({ media: { images: [...new Set(urls)], videos: [...new Set(vids)], desc: desc.trim().slice(0, 40) } });" +
        "} catch (e) { return JSON.stringify({ media: null }); } })()";

    // 抖音移动分享页数据采集：window._ROUTER_DATA 里这个作品的 images/video（与作品严格一一对应，
    // 不会像桌面版页面那样混进推荐流的图标和别家视频）。note 页只取图片——
    // note 数据里的 video 字段是"图片轮播合成的视频"，取了它就是"图文存成视频"的根因
    private static final String EXPR_SHARE =
        "(() => { try {" +
        " const raw = window._ROUTER_DATA;" +
        " if (!raw) return JSON.stringify({ media: null });" +
        " let item = null;" +
        " (function find(n){ if(!n||item) return;" +
        "   if(n.aweme_id!==undefined&&(n.video||n.images)){ item=n; return; }" +
        "   for(var k in n){ var v=n[k]; if(v&&typeof v==='object') find(v); } })(raw);" +
        " if(!item) return JSON.stringify({ media: null });" +
        " const imgs=((item.images||[]).map(function(im){return (im.url_list&&im.url_list[0])||'';}).filter(Boolean));" +
        " if(/share\\/note\\//.test(location.href)){ if(!imgs.length) return JSON.stringify({ media: null });" +
        "   return JSON.stringify({ media:{ images: imgs, videos: [], desc: (item.desc||'').slice(0,40) } }); }" +
        " const pa=(item.video&&item.video.play_addr)||null;" +
        " const vu=(pa&&pa.url_list&&pa.url_list[0])||'';" +
        " if(!imgs.length&&!vu) return JSON.stringify({ media: null });" +
        " return JSON.stringify({ media:{ images: imgs, videos: vu?[vu]:[], desc: (item.desc||'').slice(0,40) } });" +
        "} catch (e) { return JSON.stringify({ media: null }); } })()";

    // Unknown sites: prefer正文 DOM and explicit video metadata. Never guess an
    // image from a network URL; blob playback needs one unambiguous MP4 request.
    private static final String EXPR_ANY =
        "(() => { try {" +
        " const out = { images: [], videos: [] };" +
        " const scope = document.querySelector('article,[role=\"article\"],main,.article-content,.post-content') || document;" +
        " const meta = function(n){ const m = document.querySelector('meta[property=\"'+n+'\"],meta[name=\"'+n+'\"]'); return m ? (m.content||'') : ''; };" +
        " const clean = function(u){ return String(u).replace(/\\\\u002F/g, '/').replace(/&amp;/g, '&'); };" +
        " const ogv = meta('og:video') || meta('og:video:secure_url') || meta('og:video:url');" +
        " if (ogv && ogv.indexOf('blob:') !== 0) out.videos.push(clean(ogv));" +
        " for (const v of [...scope.querySelectorAll('video')]) {" +
        "   const u = v.currentSrc || (v.querySelector('source') && v.querySelector('source').src) || v.src || '';" +
        "   if (u && u.indexOf('blob:') !== 0 && u.indexOf('.mp4') >= 0 && out.videos.indexOf(clean(u)) < 0) out.videos.push(clean(u));" +
        " }" +
        " const imghosts = /sinaimg|xhscdn|mmbiz|douyinpic|hdslb|xiaoheihe|heybox|maxjia|skland|zizzs|aliyuncs|zhimg|yximgs|kuaishou|gifshow|pstatp|toutiaoimg|byteimg|miyoushe|mihoyo|hoyolab|kurobbs|kurogame|tapimg|tiebapic|imgsrc|coolapk|3839|ngabbs|nga\\.178/;" +
        " try {" +
        " const players = [...scope.querySelectorAll('video')];" +
        " const resources = [...new Set((performance.getEntriesByType('resource') || []).map(r => clean(r.name || '')).filter(u => /^https?:/.test(u) && /\\.mp4(\\?|$)/i.test(u)))];" +
        " if (!out.videos.length && players.length === 1 && /^blob:/.test(players[0].currentSrc || players[0].src || '') && resources.length === 1) out.videos.push(resources[0]);" +
        " } catch (_) {}" +
        " for (const i of [...scope.querySelectorAll('img')]) {" +
        "   if (i.closest && i.closest('aside,nav,header,footer,[class*=\"comment\"],[class*=\"recommend\"]')) continue;" +
        "   const u = (i.dataset && i.dataset.src) || i.currentSrc || i.src || '';" +
        "   if (!u || u.indexOf('blob:') === 0 || !imghosts.test(u)) continue;" +
        "   if (/avatar|head_|icon|logo|badge|emoji|default/i.test(u)) continue;" +
        "   const c = clean(u); if (out.images.indexOf(c) < 0) out.images.push(c);" +
        " }" +
        " const desc = meta('og:title') || document.title || '';" +
        " if (!out.videos.length && !out.images.length) return JSON.stringify({ media: null });" +
        " return JSON.stringify({ media: { images: [...new Set(out.images)], videos: [...new Set(out.videos)], desc: desc.trim().slice(0, 40) } });" +
        "} catch (e) { return JSON.stringify({ media: null }); } })()";

    private static final String UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";
    private static final String UA_MOBILE =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36";

    // ---------- 后台静默采集（v2.53 多通道：每路一个独立隐藏 WebView，最多 3 路并发） ----------
    /** 一路采集：自己的 WebView + 轮询器 + 剩余次数；cid 用于把结局回传给页面 */
    private static class CollectRun {
        final WebView web;
        final android.os.Handler h = new android.os.Handler();
        final String cid, expr;
        int tries = 0;
        String candidate;
        CollectRun(WebView w, String c, String e) { web = w; cid = c; expr = e; }
    }
    private final java.util.Map<String, CollectRun> collectRuns = new java.util.HashMap<>();
    private final java.util.Map<String, String[]> collectQueue = new java.util.LinkedHashMap<>();
    private static final int COLLECT_MAX = 1;

    private void startSilentCollect(String url, String kind, String cid) {
        if (collectRuns.containsKey(cid) || collectQueue.containsKey(cid)) return;
        if (collectRuns.size() >= COLLECT_MAX) {
            collectQueue.put(cid, new String[]{url, kind});
            return;
        }
        // 抖音一律改走移动分享页：无反爬、数据与作品一一对应（桌面版页面会混进推荐流图标/视频，曾把图标和
        // 用户主页的视频一起抓下来）
        String loadUrl = url;
        String expr;
        boolean mobile;
        if (url != null && url.toLowerCase(java.util.Locale.ROOT).contains("douyin") && !"user".equals(kind)) {
            java.util.regex.Matcher dm = java.util.regex.Pattern.compile("douyin\\.com/(video|note)/(\\d{6,})").matcher(url);
            if (dm.find()) {
                loadUrl = "https://www.iesdouyin.com/share/" + dm.group(1) + "/" + dm.group(2) + "/";
                expr = EXPR_SHARE;
                mobile = true;
            } else { expr = EXPR_NOTE; mobile = false; }
        } else if ("user".equals(kind)) {
            expr = EXPR_USER; mobile = false;
        } else if ("xhs".equals(kind)) {
            expr = communityExpression("xhs"); mobile = true;
        } else if ("skland".equals(kind)) {
            expr = communityExpression("skland"); mobile = true;
        } else if (java.util.Arrays.asList("miyoushe", "kurobbs", "taptap", "tieba").contains(kind)) {
            expr = communityExpression(kind); mobile = !"tieba".equals(kind);
            if ("tieba".equals(kind)) {
                java.util.regex.Matcher thread = java.util.regex.Pattern.compile("/p/(\\d+)").matcher(url);
                String threadId = thread.find() ? thread.group(1) : Uri.parse(url).getQueryParameter("kz");
                if (threadId != null && threadId.matches("\\d+")) loadUrl = "https://tieba.baidu.com/p/" + threadId + "?pn=1";
            }
        } else if ("wx".equals(kind) || "generic".equals(kind)) {
            expr = EXPR_GENERIC; mobile = true;
        } else if ("any".equals(kind)) {
            expr = EXPR_ANY; mobile = true;   // 未知站点（小黑盒/森空岛/异环/鸣潮社区等）：og/内嵌直链通用采集
        } else {
            expr = EXPR_NOTE; mobile = false;
        }
        final CollectRun run = new CollectRun(new WebView(MainActivity.this), cid, expr);
        run.web.setLayoutParams(new android.view.ViewGroup.LayoutParams(1, 1));
        run.web.setAlpha(0f);
        WebSettings s = run.web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(mobile ? UA_MOBILE : UA_DESKTOP);
        s.setSupportZoom(false);
        android.webkit.CookieManager.getInstance().setAcceptCookie(true);
        android.view.ViewGroup parent = (android.view.ViewGroup) web.getParent();
        if (parent != null) parent.addView(run.web);   // 挂 1px 透明视图：不显示但保证 JS 正常执行
        run.web.setWebViewClient(new WebViewClient());
        run.web.loadUrl(loadUrl);
        collectRuns.put(cid, run);
        run.h.postDelayed(new Runnable() {
            @Override public void run() { pollCollect(run); }
        }, 5000);
    }

    private void pollCollect(final CollectRun run) {
        if (!collectRuns.containsKey(run.cid)) return;   // 已被收尾（超时/页面侧取消）
        run.tries++;
        run.web.evaluateJavascript(run.expr, new android.webkit.ValueCallback<String>() {
            @Override public void onReceiveValue(String value) {
                if (collectRuns.get(run.cid) != run) return;
                String json = unwrapJs(value);
                String withCid = null;
                if (json != null) {
                    // 回报带上 cid，页面对号入座处理这一路。走 JSONObject 正经插入——
                    // 以前 replaceFirst 硬拼，值里出现特殊字符就是一份残缺 JSON，任务无声消失
                    try {
                        org.json.JSONObject o = new org.json.JSONObject(json);
                        o.put("cid", run.cid);
                        org.json.JSONObject media = o.optJSONObject("media");
                        if (media != null) {
                            media.put("referer", run.web.getUrl());
                            media.put("userAgent", run.web.getSettings().getUserAgentString());
                        }
                        if (media != null) {
                            int count = (media.optJSONArray("images") == null ? 0 : media.optJSONArray("images").length())
                                + (media.optJSONArray("videos") == null ? 0 : media.optJSONArray("videos").length())
                                + (media.optJSONArray("streams") == null ? 0 : media.optJSONArray("streams").length());
                            if (count > 500) { o.remove("media"); o.put("error", "正文超过 500 项，请分批下载，未截断保存"); }
                            else if (count == 0) { o.remove("media"); }
                        }
                        if ((o.has("media") && !o.isNull("media")) || !o.optString("error").isEmpty()
                                || (o.optJSONArray("links") != null && o.optJSONArray("links").length() > 0)) {
                            String candidate = o.toString();
                            // Unknown pages must produce the same list on two polls.
                            // Explicit post manifests and errors need no extra delay.
                            if (media == null || media.optBoolean("complete") || o.has("error")
                                    || candidate.equals(run.candidate)) withCid = candidate;
                            run.candidate = candidate;
                        } else run.candidate = null;
                    } catch (Exception e) { withCid = null; }
                }
                if (withCid != null) {
                    final String j = withCid;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            web.evaluateJavascript("window.onCollected && window.onCollected(" + j + ")", null);
                        }
                    });
                    stopSilentCollect(run.cid);
                } else if (run.tries < 12) {              // 约 60 秒超时
                    run.h.postDelayed(new Runnable() {
                        @Override public void run() { pollCollect(run); }
                    }, 5000);
                } else {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            web.evaluateJavascript("window.onCollected && window.onCollected("
                                + "{\"cid\":" + JSONObject.quote(run.cid) + ",\"media\":null})", null);
                        }
                    });
                    stopSilentCollect(run.cid);
                }
            }
        });
    }

    private void stopSilentCollect(String cid) {
        collectQueue.remove(cid);
        CollectRun run = collectRuns.remove(cid);
        if (run == null) return;
        run.h.removeCallbacksAndMessages(null);
        android.view.ViewGroup p = (android.view.ViewGroup) run.web.getParent();
        if (p != null) p.removeView(run.web);
        try { run.web.destroy(); } catch (Exception e) { /* ignore */ }
        if (!collectQueue.isEmpty() && !isDestroyed()) {
            String next = collectQueue.keySet().iterator().next();
            String[] request = collectQueue.remove(next);
            startSilentCollect(request[0], request[1], next);
        }
    }

    /** evaluateJavascript 返回带引号的字符串字面量，还原成裸 JSON */
    private static String unwrapJs(String value) {
        if (value == null) return null;
        try {
            org.json.JSONObject o = new org.json.JSONObject("{\"v\":" + value + "}");
            return o.isNull("v") ? null : o.getString("v");
        } catch (Exception e) { return null; }
    }

    // App 回到前台：把剪贴板内容推给页面（页面自行判断要不要弹"快捷填入"条；前台读剪贴板系统允许）
    @Override
    protected void onResume() {
        super.onResume();
        if (browserLayer != null) browserLayer.resume();
        if (web != null) web.postDelayed(() -> { if (hasWindowFocus()) notifyClipboard(); }, 150);
    }

    // App 已打开时又收到分享（singleTask 复用实例）
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && "vd.OPEN_URL".equals(intent.getAction())) {   // v2.04：二维码等内部工具带链接开浏览器
            String u = intent.getStringExtra("url");
            if (u != null) runOnUiThread(() -> { ensureBrowser().show(u); });
            return;
        }
        acceptShareIntent(intent);
    }

    private void acceptShareIntent(Intent intent) {
        if (intent == null || !(Intent.ACTION_SEND.equals(intent.getAction())
                || Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction()))) return;
        StringBuilder text = new StringBuilder();
        CharSequence extra = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        appendShareText(text, extra);
        if (text.length() == 0 && intent.getClipData() != null) {
            android.content.ClipData clips = intent.getClipData();
            for (int i = 0; i < clips.getItemCount(); i++) {
                CharSequence item = clips.getItemAt(i).getText();
                appendShareText(text, item);
            }
        }
        if (text.length() == 0) {
            String html = intent.getStringExtra(Intent.EXTRA_HTML_TEXT);
            if (html != null) appendShareText(text, android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY));
        }
        enqueueSharedText(text.toString());
    }

    private static void appendShareText(StringBuilder output, CharSequence text) {
        if (text == null) return;
        if (output.length() > 0) output.append('\n');
        output.append(text);
        if (text instanceof android.text.Spanned) {
            android.text.Spanned rich = (android.text.Spanned) text;
            for (android.text.style.URLSpan link : rich.getSpans(0, rich.length(), android.text.style.URLSpan.class)) {
                output.append('\n').append(link.getURL());
            }
        }
    }

    private org.json.JSONArray sharedTexts() {
        try { return new org.json.JSONArray(prefs.getString(PENDING_SHARES, "[]")); }
        catch (Exception ignored) { return new org.json.JSONArray(); }
    }
    private synchronized String peekSharedText() { return sharedTexts().optString(0, ""); }
    private synchronized void acknowledgeSharedText(String text) {
        org.json.JSONArray queue = sharedTexts();
        if (queue.length() == 0 || !queue.optString(0).equals(text)) return;
        queue.remove(0);
        prefs.edit().putString(PENDING_SHARES, queue.toString()).apply();
    }
    private synchronized void enqueueSharedText(String text) {
        if (text == null || text.trim().isEmpty()) return;
        org.json.JSONArray queue = sharedTexts();
        queue.put(text);
        prefs.edit().putString(PENDING_SHARES, queue.toString()).apply();
        if (web != null) web.post(() -> {
            if (!isDestroyed()) web.evaluateJavascript("window.receiveShared && window.receiveShared()", null);
        });
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && web != null) notifyClipboard();
    }
    private void notifyClipboard() {
        if (isDestroyed() || web == null || !hasWindowFocus()) return;
        String text = readClipboardText();
        web.evaluateJavascript("window.onAppResume && window.onAppResume(" + JSONObject.quote(text) + ")", null);
        web.evaluateJavascript("window.receiveShared && window.receiveShared()", null);
    }

    private String readClipboardText() {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return "";
            android.content.ClipData.Item it = cm.getPrimaryClip().getItemAt(0);
            CharSequence cs = it == null ? null : it.coerceToText(MainActivity.this);
            return cs == null ? "" : cs.toString();
        } catch (Exception e) { return ""; }
    }

    /** 退出全屏播放：恢复页面 + 通知内核（页面收到 fullscreenchange 后自动关闭播放页） */
    private void exitCustomView() {
        if (customView == null) return;
        try { rootLayout.removeView(customView); } catch (Exception ignored) {}
        if (customViewCallback != null) {
            try { customViewCallback.onCustomViewHidden(); } catch (Exception ignored) {}
            customViewCallback = null;
        }
        customView = null;
    }

    /** 返回键统一处理：全屏 > 批量管理模式（先退批量，绝不直接退 App） > 网页后退 > 双击退出。
     *  返回 false = 页面没消化，走系统默认（退出 App）。
     *  手势返回（侧滑）不经过 onKeyDown、只回调 onBackPressed，必须两处都接 */
    private long lastRootBack = 0;
    private boolean pageBackPending = false;
    private boolean handleBack() {
        if (browserLayer != null && browserLayer.back()) return true;   // 内嵌浏览器层在最上层：返回优先给它（网页后退/退出层）
        if (videoBox != null && videoBox.isFull()) {   // 内嵌播放器全屏：返回先退全屏（不退页面）
            videoBox.exitFull();
            return true;
        }
        if (customView != null) {   // 全屏播放中按返回 = 退全屏（页面会自动关播放页）
            exitCustomView();
            return true;
        }
        // Let the page consume dialogs and navigation even at the root history entry.
        if (pageBackPending) return true;
        pageBackPending = true;
        web.evaluateJavascript("window.__appBack ? window.__appBack() : null", result -> {
            pageBackPending = false;
            if (isFinishing() || isDestroyed()) return;
            if ("true".equals(result)) { lastRootBack = 0; return; }
            if (!"false".equals(result) && web.canGoBack()) { web.goBack(); return; }
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastRootBack > 2000) {
                lastRootBack = now;
                Toast.makeText(MainActivity.this, "再按一次退出", Toast.LENGTH_SHORT).show();
            } else finish();
        });
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && handleBack()) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (handleBack()) return;
        super.onBackPressed();
    }
}
