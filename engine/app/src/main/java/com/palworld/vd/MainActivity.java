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

    // 唯一入口：APK 内置页面（file:///android_asset），完全离线单机版
    private static final String DEFAULT_HOME = "file:///android_asset/index.html";
    // 本地引擎的虚拟域名：页面用它访问手机上的文件（shouldInterceptRequest 拦截）
    private static final String LOCAL_HOST = "vd.local";

    private WebView web;
    private SharedPreferences prefs;
    private String pendingShare = null;   // 其他 App 分享进来的文本
    private LocalEngine engine;
    // 进度推送节流：任务 id → 上次推送时刻
    private final java.util.HashMap<String, Long> progGate = new java.util.HashMap<>();
    // 视频全屏（页面点「⛶ 全屏」时由 WebChromeClient 回调）
    private android.view.View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("vd", MODE_PRIVATE);
        // 其他 App 分享链接进来（ACTION_SEND）
        if (getIntent() != null && Intent.ACTION_SEND.equals(getIntent().getAction())) {
            String t = getIntent().getStringExtra(Intent.EXTRA_TEXT);
            if (t != null && t.trim().length() > 4) pendingShare = t;
        }
        // 通知渠道（下载完成推送）
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new android.app.NotificationChannel("vd", "下载完成", android.app.NotificationManager.IMPORTANCE_DEFAULT));
        }
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                 // localStorage：播放进度、任务列表等本地数据
        s.setMediaPlaybackRequiresUserGesture(false); // 弹层自动播放
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);              // 禁用双指缩放（App 不该像网页一样能捏合缩放）
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);

        // ---------- 本地引擎（解析+下载全部在手机上） ----------
        engine = new LocalEngine(this, new LocalEngine.Listener() {            @Override
            public void onState(final String id, final String state, final String title, final String error, final String filesJson) {
                if (id != null && ("done".equals(state) || "error".equals(state))) {
                    synchronized (progGate) { progGate.remove(id); }   // 节流表随任务完结出表，长会话不再慢性增长
                }
                web.post(new Runnable() {
                    @Override public void run() {
                        String js = "window.onLocalState && window.onLocalState("
                            + (id == null ? "null" : JSONObject.quote(id)) + ","
                            + JSONObject.quote(state) + ","
                            + (title == null ? "null" : JSONObject.quote(title)) + ","
                            + (error == null ? "null" : JSONObject.quote(error)) + ","
                            + (filesJson == null ? "null" : filesJson) + ")";
                        web.evaluateJavascript(js, null);
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
                        web.evaluateJavascript("window.onLocalProgress && window.onLocalProgress("
                            + JSONObject.quote(id) + "," + progress + "," + JSONObject.quote(line) + ")", null);
                    }
                });
            }
        });
        engine.startInit();

        // 抖音访问凭证预热：抖音解析要求"新鲜 cookies"（游客即可、无需登录）。
        // 用隐藏 WebView 跑一遍抖音首页 JS，ttwid/__ac_signature 等落进 CookieManager。
        warmDouyin();

        // 网页调用原生能力：读 App 私有 cookies / 打开登录页 / 本地引擎
        web.addJavascriptInterface(new AppBridge(), "AndroidBridge");

        web.setWebViewClient(new WebViewClient() {
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
        });
        web.setWebChromeClient(new WebChromeClient() {
            // 视频全屏：页面调用 video.requestFullscreen() 时切换到系统全屏播放器（进度条手势也由它接管）
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) { callback.onCustomViewHidden(); return; }
                customView = view;
                customViewCallback = callback;
                setContentView(view);
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
            File dir = engine == null ? null : engine.dir();
            if (dir == null) return errResp();
            File f = "thumb".equals(kind) ? new File(new File(dir, ".thumbs"), name) : new File(dir, name);
            if (!f.isFile()) return errResp();
            String low = name.toLowerCase();
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
                fis.skip(start);
                Map<String, String> h = new HashMap<>();
                h.put("Accept-Ranges", "bytes");
                h.put("Content-Range", "bytes " + start + "-" + end + "/" + total);
                h.put("Content-Length", String.valueOf(end - start + 1));
                return new WebResourceResponse(mime, null, 206, "Partial Content", h,
                    new LimitedStream(new java.io.BufferedInputStream(fis), end - start + 1));
            }
            Map<String, String> h = new HashMap<>();
            h.put("Accept-Ranges", "bytes");
            h.put("Content-Length", String.valueOf(total));
            return new WebResourceResponse(mime, null, 200, "OK", h,
                new java.io.BufferedInputStream(new FileInputStream(f)));
        } catch (Exception e) {
            return errResp();
        }
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
                        warmHandler.postDelayed(new Runnable() {
                            @Override public void run() { finishWarm(); }
                        }, 6000);
                    }
                });
                warmWeb.loadUrl("https://www.douyin.com/");
            }
        });
    }

    private void finishWarm() {
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

        /** 其他 App 分享进来的文本：页面启动时拉取一次 */
        @JavascriptInterface public String getShared() { return pendingShare == null ? "" : pendingShare; }
        @JavascriptInterface public void clearShared() { pendingShare = null; }

        /** 下载完成系统通知（App 在后台时由页面调用；Android 13+ 首次触发权限请求） */
        @JavascriptInterface public void notify(String title, String text) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
                            return;
                        }
                        android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                        android.app.Notification n;
                        if (android.os.Build.VERSION.SDK_INT >= 26) {
                            n = new android.app.Notification.Builder(MainActivity.this, "vd")
                                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                                .setContentTitle(title).setContentText(text).setAutoCancel(true).build();
                        } else {
                            n = new android.app.Notification.Builder(MainActivity.this)
                                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                                .setContentTitle(title).setContentText(text).setAutoCancel(true).build();
                        }
                        nm.notify(1, n);
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

        /** 后台静默采集（不跳转页面）：隐藏 1px WebView 打开链接轮询采集，结果推给 window.onCollected */
        @JavascriptInterface public void collect(String url, String kind) {
            final String u = url, k = kind;
            runOnUiThread(new Runnable() {
                @Override public void run() { startSilentCollect(u, k); }
            });
        }

        /** 预取：剪贴板/分享里出现链接时后台先展开短链，用户点解析时零等待 */
        @JavascriptInterface public void prefetch(String url) {
            if (engine != null) engine.prefetch(url);
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

        // ---------- 本地引擎（解析+下载都在手机上，不走服务器） ----------

        /** 引擎状态：init 初始化中 / ready 可用 / error 初始化失败 */
        @JavascriptInterface public String engineState() { return engine == null ? "init" : engine.state(); }
        @JavascriptInterface public String engineDetail() { return engine == null ? "" : engine.detail(); }

        /** 提交解析+下载。cookies 为 "k=v" 每行一条（空串表示不带）；返回任务 id */
        @JavascriptInterface public String localSubmit(String url, String quality, String cookies) {
            return engine.submit(url, quality, cookies);
        }
        /** 提交磁力链接（手机本地 BT 下载，不经过服务器）；返回任务 id */
        @JavascriptInterface public String localMagnet(String magnet) {
            return engine.magnetSubmit(magnet);
        }
        /** 抖音图集/笔记：App 内采集到的直链在手机上直接下载；mediaJson={images:[],videos:[],desc} */
        @JavascriptInterface public String localRescue(String mediaJson) {
            return engine.rescueSubmit(mediaJson);
        }
        @JavascriptInterface public void localCancel(String id) { engine.cancel(id); }
        /** 本地文件列表 JSON：[{name,sizeMB,mtime}] */
        @JavascriptInterface public String localFiles() { return engine.listFiles(); }
        /** 删除本地文件。namesJson: ["a.mp4"]；返回 {ok,error} */
        @JavascriptInterface public String localDelete(String namesJson) { return engine.deleteFiles(namesJson); }
        /** 后台生成封面（页面随后拉 http://vd.local/thumb/<name>.jpg） */
        @JavascriptInterface public void localThumb(String name) { makeThumb(name); }
        /** 预热抖音访问凭证（无需登录；完成后推 window.onWarmDone） */
        @JavascriptInterface public void localWarmDouyin() { warmDouyin(); }

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
                            if (n.contains("/") || n.contains("\\") || n.contains("..")) continue;   // 防穿越
                            File f = new File(dir, n);
                            if (f.isFile()) paths.add(f.getAbsolutePath());
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
                        if (name == null || name.contains("/") || name.contains("\\") || name.contains("..")) return;
                        File dir = engine == null ? null : engine.dir();
                        if (dir == null) { Toast.makeText(MainActivity.this, "文件目录还没就绪，稍后再试", Toast.LENGTH_SHORT).show(); return; }
                        File f = new File(dir, name);
                        if (!f.isFile()) { Toast.makeText(MainActivity.this, "文件不存在：" + name, Toast.LENGTH_SHORT).show(); return; }
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
        tv.setText("这台手机上没有能直接打开目录的文件管理器。\n\n视频和图片都保存在系统相册里：\n" + path
            + "\n\n打开「相册/图库」App 就能看到它们；也可以打开系统「文件管理」手动进入上面的路径。");
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

    /** 登录窗口关闭：把结果推给页面（1=已自动捕获登录，0=手动返回） */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 11 && web != null) {
            web.evaluateJavascript("window.onLoginBack && window.onLoginBack(" + (resultCode == RESULT_OK ? 1 : 0) + ")", null);
        }
    }

    /** 文件名 → MIME（系统播放器 ACTION_VIEW 用；video/mp4 等常用类型系统都能路由）。
     *  VideoActivity 的外部兜底也用 */
    public static String mimeOf(String name) {
        String low = name.toLowerCase();
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
        " return JSON.stringify({ media: { images: [...new Set(images)].slice(0, 40), videos: videos.slice(0, 20), desc: desc || '' } });" +
        "} catch (e) { return JSON.stringify({ media: null }); } })()";

    private static final String EXPR_USER =
        "(() => { try {" +
        " const urls = [...document.querySelectorAll('a[href]')].map(a => a.href)" +
        "   .filter(h => /douyin\\.com\\/(video|note)\\/\\d+/.test(h));" +
        " return JSON.stringify({ links: [...new Set(urls)].slice(0, 50) });" +
        "} catch (e) { return JSON.stringify({ links: [] }); } })()";

    // 小红书图文笔记：页面里的 xhscdn 图片/视频直链（yt-dlp 的小红书提取器只下视频，
    // 图文笔记会报"无视频格式"，由这里采集直链后在手机上直接下载）
    private static final String EXPR_XHS =
        "(() => { try {" +
        " const imgs = [...document.querySelectorAll('img')].map(i => i.currentSrc || i.src)" +
        "   .filter(u => u && u.indexOf('xhscdn') >= 0 && !/avatar|icon|logo|emoji|red-packages/i.test(u));" +
        " const vids = [...document.querySelectorAll('video')].map(v => v.currentSrc || v.src)" +
        "   .filter(u => u && u.indexOf('blob:') !== 0 && (u.indexOf('sns-video') >= 0 || u.indexOf('.mp4') >= 0));" +
        " if (!imgs.length && !vids.length) return JSON.stringify({ media: null });" +
        " let desc = ''; try { desc = document.querySelector('meta[property=\"og:title\"]').content; } catch (e) {}" +
        " if (!desc) desc = document.title || '';" +
        " desc = desc.replace(/\\s*[-|]\\s*小红书.*$/, '').trim();" +
        " return JSON.stringify({ media: { images: [...new Set(imgs)].slice(0, 40), videos: [...new Set(vids)].slice(0, 20), desc: desc.slice(0, 40) } });" +
        "} catch (e) { return JSON.stringify({ media: null }); } })()";

    // 通用图集兜底（微信公众号文章 / 微博相册等）：正文图 CDN 域名 + 懒加载 data-src，
    // og:title 当文件名；视频只取非 blob 直链。微博缩略图路径升级成大图
    private static final String EXPR_GENERIC =
        "(() => { try {" +
        " const hosts = /mmbiz\\.qpic\\.cn|sinaimg\\.cn|xhscdn\\.com|douyinpic|hdslb\\.com/;" +
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
        " return JSON.stringify({ media: { images: [...new Set(urls)].slice(0, 40), videos: [...new Set(vids)].slice(0, 20), desc: desc.trim().slice(0, 40) } });" +
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
        "   return JSON.stringify({ media:{ images: imgs.slice(0,40), videos: [], desc: (item.desc||'').slice(0,40) } }); }" +
        " const pa=(item.video&&item.video.play_addr)||null;" +
        " const vu=(pa&&pa.url_list&&pa.url_list[0])||'';" +
        " if(!imgs.length&&!vu) return JSON.stringify({ media: null });" +
        " return JSON.stringify({ media:{ images: imgs.slice(0,40), videos: vu?[vu]:[], desc: (item.desc||'').slice(0,40) } });" +
        "} catch (e) { return JSON.stringify({ media: null }); } })()";

    // 未知站点兜底（小黑盒/森空岛等 yt-dlp 没有提取器的社区）：og:video / video 标签 / 页面 JSON 里
    // 内嵌的 .mp4 直链三路抓，图片拿 og:image + 已知 CDN 域名的正文图。页面是 SPA 也没关系——
    // 采集会轮询 60 秒等 JS 渲染完
    private static final String EXPR_ANY =
        "(() => { try {" +
        " const out = { images: [], videos: [] };" +
        " const meta = function(n){ const m = document.querySelector('meta[property=\"'+n+'\"],meta[name=\"'+n+'\"]'); return m ? (m.content||'') : ''; };" +
        " const clean = function(u){ return String(u).replace(/\\\\u002F/g, '/').replace(/&amp;/g, '&'); };" +
        " const ogv = meta('og:video') || meta('og:video:secure_url') || meta('og:video:url');" +
        " if (ogv && ogv.indexOf('blob:') !== 0) out.videos.push(clean(ogv));" +
        " for (const v of [...document.querySelectorAll('video')]) {" +
        "   const u = v.currentSrc || (v.querySelector('source') && v.querySelector('source').src) || v.src || '';" +
        "   if (u && u.indexOf('blob:') !== 0 && u.indexOf('.mp4') >= 0 && out.videos.indexOf(clean(u)) < 0) out.videos.push(clean(u));" +
        " }" +
        " const html = document.documentElement.outerHTML;" +
        " const mus = html.match(/https?:\\/\\/[^\"'\\s<>]{10,400}?\\.mp4[^\"'\\s<>]{0,200}/g) || [];" +
        " for (const u of mus.slice(0, 12)) { const c = clean(u); if (out.videos.indexOf(c) < 0) out.videos.push(c); }" +
        " const ogi = meta('og:image');" +
        " if (ogi) out.images.push(clean(ogi));" +
        " const imghosts = /sinaimg|xhscdn|mmbiz|douyinpic|hdslb|xiaoheihe|heybox|maxjia|skland|zizzs|aliyuncs/;" +
        " for (const i of [...document.querySelectorAll('img')]) {" +
        "   const u = i.currentSrc || i.src || '';" +
        "   if (!u || u.indexOf('blob:') === 0 || !imghosts.test(u)) continue;" +
        "   if (/avatar|head_|icon|logo|badge|emoji|default/i.test(u)) continue;" +
        "   const c = clean(u); if (out.images.indexOf(c) < 0) out.images.push(c);" +
        " }" +
        " const desc = meta('og:title') || document.title || '';" +
        " if (!out.videos.length && !out.images.length) return JSON.stringify({ media: null });" +
        " return JSON.stringify({ media: { images: [...new Set(out.images)].slice(0, 40), videos: [...new Set(out.videos)].slice(0, 10), desc: desc.trim().slice(0, 40) } });" +
        "} catch (e) { return JSON.stringify({ media: null }); } })()";

    private static final String UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";
    private static final String UA_MOBILE =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36";

    private WebView collectWeb;
    private final android.os.Handler collectHandler = new android.os.Handler();
    private int collectTries = 0;

    private void startSilentCollect(String url, String kind) {
        stopSilentCollect();
        collectTries = 0;
        // 抖音一律改走移动分享页：无反爬、数据与作品一一对应（桌面版页面会混进推荐流图标/视频，曾把图标和
        // 用户主页的视频一起抓下来）
        String loadUrl = url;
        String expr;
        boolean mobile;
        if (url != null && url.toLowerCase().contains("douyin") && !"user".equals(kind)) {
            java.util.regex.Matcher dm = java.util.regex.Pattern.compile("douyin\\.com/(video|note)/(\\d{6,})").matcher(url);
            if (dm.find()) {
                loadUrl = "https://www.iesdouyin.com/share/" + dm.group(1) + "/" + dm.group(2) + "/";
                expr = EXPR_SHARE;
                mobile = true;
            } else { expr = EXPR_NOTE; mobile = false; }
        } else if ("user".equals(kind)) {
            expr = EXPR_USER; mobile = false;
        } else if ("xhs".equals(kind)) {
            expr = EXPR_XHS; mobile = true;
        } else if ("wx".equals(kind) || "generic".equals(kind)) {
            expr = EXPR_GENERIC; mobile = true;
        } else if ("any".equals(kind)) {
            expr = EXPR_ANY; mobile = true;   // 未知站点（小黑盒/森空岛等）：og/内嵌直链通用采集
        } else {
            expr = EXPR_NOTE; mobile = false;
        }
        collectWeb = new WebView(MainActivity.this);
        collectWeb.setLayoutParams(new android.view.ViewGroup.LayoutParams(1, 1));
        collectWeb.setAlpha(0f);
        WebSettings s = collectWeb.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(mobile ? UA_MOBILE : UA_DESKTOP);
        s.setSupportZoom(false);
        android.webkit.CookieManager.getInstance().setAcceptCookie(true);
        android.view.ViewGroup parent = (android.view.ViewGroup) web.getParent();
        if (parent != null) parent.addView(collectWeb);   // 挂 1px 透明视图：不显示但保证 JS 正常执行
        collectWeb.setWebViewClient(new WebViewClient());
        collectWeb.loadUrl(loadUrl);
        collectHandler.postDelayed(new Runnable() {
            @Override public void run() { pollCollect(expr); }
        }, 5000);
    }

    private void pollCollect(final String expr) {
        if (collectWeb == null) return;
        collectTries++;
        collectWeb.evaluateJavascript(expr, new android.webkit.ValueCallback<String>() {
            @Override public void onReceiveValue(String value) {
                String json = unwrapJs(value);
                if (json != null && json.contains("[\"")) {   // 数组里有真实内容
                    final String j = json;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            web.evaluateJavascript("window.onCollected && window.onCollected(" + j + ")", null);
                        }
                    });
                    stopSilentCollect();
                } else if (collectTries < 12) {              // 约 60 秒超时
                    collectHandler.postDelayed(new Runnable() {
                        @Override public void run() { pollCollect(expr); }
                    }, 5000);
                } else {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            web.evaluateJavascript("window.onCollected && window.onCollected(null)", null);
                        }
                    });
                    stopSilentCollect();
                }
            }
        });
    }

    private void stopSilentCollect() {
        collectHandler.removeCallbacksAndMessages(null);
        if (collectWeb != null) {
            android.view.ViewGroup p = (android.view.ViewGroup) collectWeb.getParent();
            if (p != null) p.removeView(collectWeb);
            try { collectWeb.destroy(); } catch (Exception e) { /* ignore */ }
            collectWeb = null;
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
        final String clip = readClipboardText();
        if (clip != null && clip.length() > 8) {
            web.evaluateJavascript("window.onAppResume && window.onAppResume(" + org.json.JSONObject.quote(clip) + ")", null);
        }
    }

    // App 已打开时又收到分享（singleTask 复用实例）
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            String t = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (t != null && t.trim().length() > 4 && web != null) {
                web.evaluateJavascript("window.onShared && window.onShared(" + org.json.JSONObject.quote(t) + ")", null);
            }
        }
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
        setContentView(web);
        if (customViewCallback != null) {
            try { customViewCallback.onCustomViewHidden(); } catch (Exception ignored) {}
            customViewCallback = null;
        }
        customView = null;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 全屏播放中按返回 = 退全屏（页面会自动关播放页），不能直接 goBack 否则双重返回
        if (keyCode == KeyEvent.KEYCODE_BACK && customView != null) {
            exitCustomView();
            return true;
        }
        // 返回键 = 网页后退，退无可退才退出 App
        if (keyCode == KeyEvent.KEYCODE_BACK && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
