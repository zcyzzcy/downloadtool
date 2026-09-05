package com.palworld.vd;

import android.app.Activity;
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

    private String snapshotOf(String c) {
        StringBuilder sb = new StringBuilder();
        for (String mk : marks.split(",")) {
            String m = mk.trim();
            if (m.isEmpty()) continue;
            String v = c == null ? null : markValue(c, m);
            sb.append(m).append('=').append(v == null ? "" : v).append('\n');
        }
        return sb.toString();
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
        // 页面指定初始 UA（"desktop"）：抖音要桌面 UA；请求头的问题由下面的代取逻辑剥掉
        if ("desktop".equals(getIntent().getStringExtra("ua"))) desktop = true;
        watchUrl = getIntent().getStringExtra("watch");
        if (watchUrl == null || watchUrl.isEmpty()) watchUrl = url;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(0xFF3D6AE8);
        bar.setPadding(16, 12, 16, 12);

        Button back = new Button(this);
        back.setText("← 返回");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        bar.addView(back);

        // 窗口标题：让用户一眼知道当前窗口在登哪个站、什么方式（之前"这页面是什么"看不明白）
        TextView title = new TextView(this);
        String t = getIntent().getStringExtra("title");
        title.setText(t == null || t.isEmpty() ? "登录" : t);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(15);
        title.setPadding(14, 0, 10, 0);
        LinearLayout.LayoutParams lpT = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        lpT.gravity = android.view.Gravity.CENTER_VERTICAL;
        bar.addView(title, lpT);

        final Button toggle = new Button(this);
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

        box.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        web.setWebViewClient(new WebViewClient() {
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
                // 直接回「非法应用」）。POST 无法拦截（系统限制），页面加载与 XHR GET 全部由这里代取
                try {
                    if (!"GET".equalsIgnoreCase(request.getMethod())) return null;
                    String u = request.getUrl().toString();
                    if (!(u.startsWith("https://") || u.startsWith("http://"))) return null;
                    return fetchNoXrw(u, request.getRequestHeaders());
                } catch (Exception e) { return null; }
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
                        String lk = e.getKey().toLowerCase();
                        // X-Requested-With 必须剥；Cookie/Accept-Encoding/Range/缓存校验头由本方法或系统处理
                        if (lk.equals("x-requested-with") || lk.equals("cookie") || lk.equals("accept-encoding")
                            || lk.equals("range") || lk.startsWith("if-")) continue;
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
                return new WebResourceResponse(ct, charset, code, c.getResponseMessage(), null, in);
            }
        } catch (Exception ignored) {}
        return null;
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
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
