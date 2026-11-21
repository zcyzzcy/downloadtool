package com.palworld.vd;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;

/**
 * App 内登录窗口：默认手机版 UA（干净 Chrome 手机 UA——系统默认 UA 带 "; wv)" WebView 标记，
 * 抖音等站点识别到会给桌面版/降级页面）。
 * GET 请求全部由本窗口代取并剥掉 X-Requested-With 头（WebView 必带包名，抖音 passport 靠它
 * 识别内嵌浏览器直接回「非法应用」）；POST 拦不了（系统限制），密码提交仍由页面自身完成。
 * 登录成功自动检测：每 1.5s 查一次 cookies，出现登录标志（如 sessionid/SESSDATA）即自动返回，
 * 不需要手动点「我已完成登录」。
 */
public class LoginWebActivity extends Activity {

    private static final String MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36";
    private static final String DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";

    private WebView web;
    private String url;
    private String watchUrl;        // 要盯的 cookie 域（SSO 登录页在 sso.douyin.com，sessionid 落在 www.douyin.com）
    private boolean desktop = false;
    private String marks = "";          // 登录标志 cookie 名，逗号分隔（页面传入）
    private boolean captured = false;
    private boolean browse = false;     // 浏览模式（搜索引擎用）：返回=网页后退，带「下载本页」按钮
    private String curUrl = "";         // 浏览模式下当前页网址
    private android.widget.FrameLayout progWrap;   // 浏览模式：工具栏下的加载进度线
    private View progFill;
    private final Handler handler = new Handler();

    /** cookies 串里某个标志 cookie 的值（没找到返回 null） */
    private static String markValue(String c, String m) {
        int i = c.indexOf(m + "=");
        while (i >= 0) {
            boolean boundary = i == 0 || c.charAt(i - 1) == ' ' || c.charAt(i - 1) == ';';
            if (boundary) {
                int vs = i + m.length() + 1;
                int ve = c.indexOf(';', vs);
                return ve < 0 ? c.substring(vs) : c.substring(vs, ve);
            }
            i = c.indexOf(m + "=", i + 1);
        }
        return null;
    }

    /** 快照：打开窗口那一刻各标志 cookie 的值。之后只有值变化（或从无到有）才算"新登录"——
     *  防止手机里残留的过期登录 cookie 一开窗就误触发自动返回，用户反而没机会重新登录 */
    private String snapshot = "";

    /** marks 预拆结果（v1.99）：原来每 1.5s 的轮询里 split 一遍，没必要 */
    private String[] markArr = new String[0];

    private String snapshotOf(String c) {
        StringBuilder sb = new StringBuilder();
        for (String mk : markArr) {
            String m = mk.trim();
            if (m.isEmpty()) continue;
            String v = c == null ? null : markValue(c, m);
            sb.append(m).append('=').append(v == null ? "" : v).append('\n');
        }
        return sb.toString();
    }

    /** v1.99：只有抖音系站点靠 X-Requested-With 识别内嵌浏览器（回「非法应用」）——
     *  其他站点的 GET 再全部经代取就是纯降级：无 HTTP/2、无缓存、无连接复用，
     *  页面更慢更费电费流量。白名单外的请求放行给 WebView 原生网络栈 */
    private static boolean wantsNoXrw(String u) {
        int hs = u.startsWith("https://") ? 8 : 7;
        int he = u.indexOf('/', hs);
        String host = (he > 0 ? u.substring(hs, he) : u.substring(hs)).toLowerCase(java.util.Locale.ROOT);
        if (host.contains(":")) host = host.substring(0, host.indexOf(':'));
        return host.endsWith("douyin.com") || host.endsWith("iesdouyin.com") || host.endsWith("snssdk.com")
            || host.endsWith("bytedance.com") || host.endsWith("amemv.com") || host.endsWith("douyinvod.com")
            || host.endsWith("douyinpic.com") || host.endsWith("ibytedtos.com");
    }

    private final Runnable loginWatcher = new Runnable() {
        @Override public void run() {
            if (captured || web == null) return;
            try {
                String c = CookieManager.getInstance().getCookie(watchUrl);
                if (c != null && !snapshotOf(c).equals(snapshot)) {
                    // 变化后给半秒缓冲：登录跳转过程中 cookie 可能先落一半
                    handler.postDelayed(new Runnable() {
                        @Override public void run() {
                            if (captured) return;
                            String c2 = null;
                            try { c2 = CookieManager.getInstance().getCookie(watchUrl); } catch (Exception ignored) {}
                            String now = c2 == null ? "" : snapshotOf(c2);
                            if (now.equals(snapshot)) { loginWatcher.run(); return; }   // 又变回去了，继续盯
                            captured = true;
                            CookieManager.getInstance().flush();
                            Toast.makeText(LoginWebActivity.this, "已捕获登录信息，自动返回", Toast.LENGTH_SHORT).show();
                            setResult(RESULT_OK);
                            finish();
                        }
                    }, 500);
                    return;
                }
            } catch (Exception ignored) {}
            handler.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        url = getIntent().getStringExtra("url");
        marks = getIntent().getStringExtra("marks");
        if (marks == null) marks = "";
        try { markArr = marks.split(","); } catch (Exception e) { markArr = new String[0]; }
        browse = "browse".equals(getIntent().getStringExtra("mode"));
        // 页面指定初始 UA（"desktop"）：抖音要桌面 UA；请求头的问题由下面的代取逻辑剥掉
        if ("desktop".equals(getIntent().getStringExtra("ua"))) desktop = true;
        watchUrl = getIntent().getStringExtra("watch");
        if (watchUrl == null || watchUrl.isEmpty()) watchUrl = url;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        final float dDen = getResources().getDisplayMetrics().density;
        final TextView title;
        final Button toggle;
        if (browse) {
            // v1.56 浏览窗口换新装：浅色工具栏 + 品牌色主按钮 + 加载进度线（登录窗口保持原蓝条不动）
            bar.setBackgroundColor(0xFFF6F8FC);
            try {
                getWindow().setStatusBarColor(0xFFF6F8FC);
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            } catch (Exception ignored) {}
            bar.setPadding((int)(8*dDen), (int)(8*dDen), (int)(8*dDen), (int)(8*dDen));
            bar.setGravity(android.view.Gravity.CENTER_VERTICAL);

            TextView back = new TextView(this);
            back.setText("‹");
            back.setTextSize(22);
            back.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            back.setTextColor(0xFF3358D4);
            back.setGravity(android.view.Gravity.CENTER);
            android.graphics.drawable.GradientDrawable bgB = new android.graphics.drawable.GradientDrawable();
            bgB.setColor(0xFFE8EEFB); bgB.setCornerRadius(21*dDen);
            back.setBackground(bgB);
            back.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { backOrFinish(); }
            });
            LinearLayout.LayoutParams lpB = new LinearLayout.LayoutParams((int)(42*dDen), (int)(42*dDen));
            lpB.rightMargin = (int)(6*dDen);
            bar.addView(back, lpB);

            title = new TextView(this);
            title.setText(t0());
            title.setTextColor(0xFF1C2B4A);
            title.setTextSize(14);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            title.setPadding((int)(4*dDen), 0, (int)(8*dDen), 0);
            LinearLayout.LayoutParams lpT = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            lpT.gravity = android.view.Gravity.CENTER_VERTICAL;
            bar.addView(title, lpT);

            TextView pick = new TextView(this);
            pick.setText("⤓ 下载本页");
            pick.setTextSize(12.5f);
            pick.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            pick.setTextColor(0xFFFFFFFF);
            android.graphics.drawable.GradientDrawable bgP = new android.graphics.drawable.GradientDrawable();
            bgP.setColor(0xFF4F7CFF); bgP.setCornerRadius(19*dDen);
            pick.setBackground(bgP);
            pick.setPadding((int)(14*dDen), (int)(9*dDen), (int)(14*dDen), (int)(9*dDen));
            pick.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { pickCurrent(); }
            });
            bar.addView(pick);

            toggle = new Button(this);   // 浏览模式不用电脑版切换？——仍要：Yandex/部分站移动版缺功能
            toggle.setAllCaps(false);
            toggle.setText(desktop ? "手机版" : "电脑版");
            toggle.setTextSize(12);
            toggle.setTextColor(0xFF5A6B8C);
            android.graphics.drawable.GradientDrawable bgT = new android.graphics.drawable.GradientDrawable();
            bgT.setColor(0xFFEDF1F8); bgT.setCornerRadius(16*dDen);
            toggle.setBackground(bgT);
            toggle.setPadding((int)(12*dDen), (int)(8*dDen), (int)(12*dDen), (int)(8*dDen));
            toggle.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    desktop = !desktop;
                    applyUa();
                    toggle.setText(desktop ? "手机版" : "电脑版");
                    web.reload();
                }
            });
            LinearLayout.LayoutParams lpTg = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (int)(38*dDen));
            lpTg.leftMargin = (int)(6*dDen);
            lpTg.gravity = android.view.Gravity.CENTER_VERTICAL;
            bar.addView(toggle, lpTg);
        } else {
            bar.setBackgroundColor(0xFF3D6AE8);
            bar.setPadding(16, 12, 16, 12);

            Button back = new Button(this);
            back.setText("← 返回");
            back.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { backOrFinish(); }
            });
            bar.addView(back);

            // 窗口标题：让用户一眼知道当前窗口在登哪个站、什么方式（之前"这页面是什么"看不明白）
            title = new TextView(this);
            title.setText(t0());
            title.setTextColor(0xFFFFFFFF);
            title.setTextSize(15);
            title.setPadding(14, 0, 10, 0);
            LinearLayout.LayoutParams lpT = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            lpT.gravity = android.view.Gravity.CENTER_VERTICAL;
            bar.addView(title, lpT);

            toggle = new Button(this);
            toggle.setText(desktop ? "手机版" : "电脑版");
            toggle.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    desktop = !desktop;
                    applyUa();
                    toggle.setText(desktop ? "手机版" : "电脑版");
                    web.reload();
                }
            });
            bar.addView(toggle);
        }

        box.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        if (browse) {
            // 加载进度线：3dp，品牌色，只在加载中显示（v1.57：创建时先藏，别空挂一条灰线）
            progWrap = new android.widget.FrameLayout(this);
            progWrap.setBackgroundColor(0xFFE4E9F2);
            progWrap.setVisibility(View.GONE);
            View line = new View(this);
            line.setBackgroundColor(0xFF4F7CFF);
            progFill = line;
            progWrap.addView(line, new android.widget.FrameLayout.LayoutParams(0, (int)(3*dDen)));
            box.addView(progWrap, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int)(3*dDen)));
        }

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String u, android.graphics.Bitmap fa) {
                curUrl = u == null ? "" : u;
            }
            @Override
            public void onPageFinished(WebView view, String u) {
                // 浏览模式：标题栏跟着显示当前站点，用户「在哪一页」一目了然
                if (browse && u != null) {
                    try { title.setText(android.net.Uri.parse(u).getHost()); } catch (Exception ignored) {}
                }
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String u) {
                // 只允许 http/https 在登录窗口内打开；拦截试图拉起抖音/B站 App 的深链（intent:// snssdk…:// 等），
                // 否则登录跳到系统 App 里完成，cookies 不会落在我们的 WebView 里
                if (u != null && (u.startsWith("http://") || u.startsWith("https://"))) return false;
                return true;
            }
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // 剥掉 WebView 必带的 X-Requested-With:包名 头（抖音 passport 接口据此识别内嵌浏览器，
                // 直接回「非法应用」）。POST 无法拦截（系统限制）。
                // v1.99：只对抖音系域名代取（wantsNoXrw）——其他站点不查这个头，
                // 全量代取只会失去 HTTP/2/缓存/连接复用，页面慢、费电费流量
                try {
                    if (!"GET".equalsIgnoreCase(request.getMethod())) return null;
                    String u = request.getUrl().toString();
                    if (!(u.startsWith("https://") || u.startsWith("http://"))) return null;
                    if (!wantsNoXrw(u)) return null;
                    return fetchNoXrw(u, request.getRequestHeaders());
                } catch (Exception e) { return null; }
            }
        });
        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int p) {
                if (progWrap == null || progFill == null) return;
                if (p >= 100) { progWrap.setVisibility(View.GONE); return; }
                progWrap.setVisibility(View.VISIBLE);
                int w = progWrap.getWidth();
                if (w > 0) {
                    android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) progFill.getLayoutParams();
                    lp.width = Math.max((int)(3*dDen), w * p / 100);
                    progFill.setLayoutParams(lp);
                }
            }
        });
        box.addView(web, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        setContentView(box);

        applyUa();
        if (url != null) web.loadUrl(url);
        if (!marks.isEmpty() && url != null) {
            try { snapshot = snapshotOf(CookieManager.getInstance().getCookie(watchUrl)); } catch (Exception e) { snapshot = ""; }
            handler.postDelayed(loginWatcher, 3000);
        }
    }

    private void applyUa() {
        web.getSettings().setUserAgentString(desktop ? DESKTOP_UA : MOBILE_UA);
    }

    /** 窗口标题（页面传入；登录默认「登录」，浏览默认「浏览」） */
    private String t0() {
        String t = getIntent().getStringExtra("title");
        if (t != null && !t.isEmpty()) return t;
        return browse ? "浏览" : "登录";
    }

    /** 浏览模式：有浏览历史先回网页上一页，没有了才关窗口；登录窗口保持原样直接关 */
    private void backOrFinish() {
        if (browse && web != null && web.canGoBack()) { web.goBack(); return; }
        finish();
    }

    @Override
    public void onBackPressed() {
        if (browse && web != null && web.canGoBack()) { web.goBack(); return; }
        super.onBackPressed();
    }

    /** 「下载本页」：当前网址回传给主界面填进解析框。搜索引擎自己的结果页拦下（那是搜索页不是可下载的视频页）——
     *  v1.57 从只拦 Yandex 扩展到必应/百度（默认引擎换了，搜索页拦截也得跟上） */
    private void pickCurrent() {
        String u = curUrl != null && !curUrl.isEmpty() ? curUrl : (url == null ? "" : url);
        if (u.isEmpty()) { Toast.makeText(this, "页面还没加载出来", Toast.LENGTH_SHORT).show(); return; }
        String host = "", path = "";
        try {
            android.net.Uri uri = android.net.Uri.parse(u);
            host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
            path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(java.util.Locale.ROOT);
        } catch (Exception ignored) {}
        boolean searchPage = host.contains("yandex.")
            || (host.endsWith("bing.com") && (path.startsWith("/search") || path.startsWith("/images/search") || path.startsWith("/videos/search")))
            || ((host.endsWith("baidu.com") || host.endsWith("baidu.cn")) && (path.startsWith("/s") || path.startsWith("/sf")));
        if (searchPage) {
            Toast.makeText(this, "这是搜索页——先点进具体的视频/网页，再按「下载本页」", Toast.LENGTH_SHORT).show();
            return;
        }
        setResult(RESULT_OK, new Intent().putExtra("url", u));
        finish();
    }

    /** 代取 GET 并剥掉 X-Requested-With：逐跳跟重定向、逐跳把 Set-Cookie 落进 CookieManager，
     *  保证登录跳转链（SSO 票据 → sessionid）的 cookie 一滴不丢。任何一步异常返回 null，
     *  交回 WebView 原生加载（虽带头，页面仍可用） */
    private WebResourceResponse fetchNoXrw(String url, Map<String, String> reqHeaders) {
        String cur = url;
        try {
            CookieManager cm = CookieManager.getInstance();
            String ua = web.getSettings().getUserAgentString();
            for (int hop = 0; hop < 6; hop++) {
                HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(20000);
                c.setInstanceFollowRedirects(false);
                if (reqHeaders != null) {
                    for (Map.Entry<String, String> e : reqHeaders.entrySet()) {
                        if (e.getKey() == null || e.getValue() == null) continue;
                        String lk = e.getKey().toLowerCase(java.util.Locale.ROOT);
                        // X-Requested-With 必须剥；Cookie/Accept-Encoding 由本方法或系统处理；
                        // Range v1.70 起转发——之前剥掉后 <video>/m3u8 的分段请求全变 200 全量，
                        // 拖进度条失效、流量翻倍（206 响应照原样透传，配合下方响应头透传才完整）
                        if (lk.equals("x-requested-with") || lk.equals("cookie") || lk.equals("accept-encoding")
                            || lk.startsWith("if-")) continue;
                        try { c.setRequestProperty(e.getKey(), e.getValue()); } catch (Exception ignored) {}
                    }
                }
                if (ua != null && ua.length() > 0 && c.getRequestProperty("User-Agent") == null) c.setRequestProperty("User-Agent", ua);
                String ck = cm.getCookie(cur);
                if (ck != null && !ck.isEmpty()) c.setRequestProperty("Cookie", ck);
                int code = c.getResponseCode();
                storeCookies(cm, cur, c.getHeaderFields());
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    c.disconnect();
                    if (loc == null || loc.isEmpty()) return null;
                    cur = new URL(new URL(cur), loc).toString();   // 相对/绝对 Location 都能拼
                    continue;
                }
                String ct = c.getContentType();
                String charset = null;
                if (ct != null) {
                    int i = ct.indexOf("charset=");
                    if (i >= 0) charset = ct.substring(i + 8).trim().replace("\"", "");
                    int s = ct.indexOf(';');
                    if (s >= 0) ct = ct.substring(0, s);
                }
                // 不发 Accept-Encoding → HttpURLConnection 自动 gzip 且已解压，这里拿到的是裸字节，直接给 WebView
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                if (in == null) in = new java.io.ByteArrayInputStream(new byte[0]);
                return new WebResourceResponse(ct, charset, code, c.getResponseMessage(), filterRespHeaders(c.getHeaderFields()), in);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** v1.70：代取响应头过滤透传——之前传 null，站点下发的 CSP/X-Frame-Options 等安全头和
     *  Content-Range/Accept-Ranges 全被剥掉（视频拖进度需要的分段语义也一起没了）。
     *  跳过编码/长度类头：gzip 已被 HttpURLConnection 解掉，长度与实际流不符 */
    private static Map<String, String> filterRespHeaders(Map<String, List<String>> hf) {
        try {
            if (hf == null) return null;
            Map<String, String> out = new java.util.HashMap<>();
            for (Map.Entry<String, List<String>> e : hf.entrySet()) {
                String k = e.getKey();
                if (k == null || e.getValue() == null || e.getValue().isEmpty()) continue;
                String lk = k.toLowerCase(java.util.Locale.ROOT);
                if (lk.equals("set-cookie") || lk.equals("transfer-encoding") || lk.equals("content-encoding")
                    || lk.equals("connection") || lk.equals("keep-alive") || lk.equals("content-type")
                    || lk.equals("content-length")) continue;
                out.put(k, e.getValue().get(0));
            }
            return out;
        } catch (Exception e) { return null; }
    }

    /** 拦截代取的响应，WebView 不会自己解析 Set-Cookie（响应头被我们接管了），必须手动落账 */
    private static void storeCookies(CookieManager cm, String url, Map<String, List<String>> hf) {
        try {
            if (hf == null) return;
            for (Map.Entry<String, List<String>> e : hf.entrySet()) {
                String k = e.getKey();
                if (k == null || !k.equalsIgnoreCase("set-cookie")) continue;
                for (String v : e.getValue()) {
                    try { cm.setCookie(url, v); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void onPause() {
        // 确保 cookies 立刻落盘，返回主界面后网页才能收割到
        CookieManager.getInstance().flush();
        // v1.99：退后台（Home/息屏/切 App）时停掉 WebView 的 JS 定时器与媒体，
        // 登录检测轮询也暂停——1.5s 周期唤醒在后台纯属耗电（回 onResume 重挂）
        try { if (web != null) web.onPause(); } catch (Throwable ignored) {}
        handler.removeCallbacks(loginWatcher);
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        try { if (web != null) web.onResume(); } catch (Throwable ignored) {}
        if (!captured && markArr.length > 0) handler.postDelayed(loginWatcher, 1500);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (web != null) {
            // attached 状态直接 destroy 是未定义行为（部分 ROM 泄漏窗口）：先摘下来再销毁
            android.view.ViewGroup p = (android.view.ViewGroup) web.getParent();
            if (p != null) p.removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }
}
