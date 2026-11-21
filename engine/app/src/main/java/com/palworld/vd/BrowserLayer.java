package com.palworld.vd;

import static com.palworld.vd.MenuIco.IC_BACK;
import static com.palworld.vd.MenuIco.IC_BM;
import static com.palworld.vd.MenuIco.IC_CLOCK;
import static com.palworld.vd.MenuIco.IC_COPY;
import static com.palworld.vd.MenuIco.IC_DL;
import static com.palworld.vd.MenuIco.IC_GEAR;
import static com.palworld.vd.MenuIco.IC_HOME;
import static com.palworld.vd.MenuIco.IC_MENU;
import static com.palworld.vd.MenuIco.IC_MONI;
import static com.palworld.vd.MenuIco.IC_PLUS;
import static com.palworld.vd.MenuIco.IC_QR;
import static com.palworld.vd.MenuIco.IC_REFR;
import static com.palworld.vd.MenuIco.IC_SEARCH;
import static com.palworld.vd.MenuIco.IC_SHARE;
import static com.palworld.vd.MenuIco.IC_SNIFF;
import static com.palworld.vd.MenuIco.IC_STAR;
import static com.palworld.vd.MenuIco.IC_TABS;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * v1.94 内嵌浏览器层（下载器专用版，X浏览器式布局）：叠在主界面里的原生层（和 VideoBox 同机制），
 * 底部留出 App 自己的导航栏（browserBottom 同步高度），浏览器工具栏全部叠在 App 导航栏之上。
 * 布局参照 X浏览器：顶部一条地址胶囊（显示页面标题、内嵌刷新/停止键）+ 右侧资源嗅探入口；
 * 底部五键等宽工具栏（返回/前进/⤓下载/标签页/≡菜单）。核心还是「⤓」一键下载：
 * 打开视频页自动点亮（请求旁观嗅探 + DOM 视频元素双通道），点一下把页面交给引擎后台下载，不退出浏览器。
 * v1.94 修复：网页视频全屏（onShowCustomView 原来没接，点全屏没反应）；桌面版 UA 配套
 * useWideViewPort/overview（原来挤成 360px）；嗅探列表 HEAD 补 Content-Length；SPA 换视频后
 * 当前播放资源置顶标记；地址胶囊背景色恢复不一致；主文档加载失败给中文重试页。
 * v1.90 修复：嗅探列表原来在 onPageFinished 被整体清空（加载期收集的结果全丢）——改在 onPageStarted 清。
 * 注意：请求不经代取（shouldInterceptRequest 只旁观记录），X-Requested-With 等
 * WebView 指纹头原样发出；登录/风控敏感场景走 LoginWebActivity 的代取通道。
 */
public class BrowserLayer extends FrameLayout {

    /** 与主界面（WebView 页面）的通信口 */
    public interface Host {
        void onPickUrl(String url);   // 旧路径：送回首页解析框（已基本不用）
        void onDownload(String url, String title, String referer, String userAgent);   // v1.90：后台开下载任务，不退出浏览器
        void onExit(boolean toHome);  // 退出浏览器层：🏠=回首页标签；返回手势退到栈底=留在原页面
        void runJs(String js);        // 在主 WebView 上执行（记 __browserLast 等）
    }

    private static final String MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36";
    private static final String DESK_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";
    private static final String NT = "about:vd-newtab";   // 新标签页（视频导航）标记地址
    private static final int TAB_MAX = 2;

    /** 一个标签页 = 一个独立 WebView + 当前地址。
     *  v1.99：w 可空——标签记录保留、WebView 按最近使用卸载（LRU）：满 8 个时最久未用的
     *  非活动标签只销毁 WebView（面板记录还在，切回按 url 重建）。原来 8 个标签建过就一直
     *  持有，和主 WebView/采集 WebView 叠起来低端机内存峰值很可观 */
    private static class Tab {
        WebView w;
        String url = "";
        String title;
        volatile int generation = 0;
        final java.util.LinkedHashMap<String, Sniff> resources = new java.util.LinkedHashMap<>();
        boolean loaded = false;   // 还没 loadUrl 过（首开不预载，省流量）
        String currentVideoUrl = "";   // v1.94：DOM 嗅探到的"正在播放的视频"直链（SPA 换视频后列表置顶标记用）
        String lastErrorUrl = "";   // v1.95：本页加载失败被替换成错误页的网址——别把失败网址记进历史
        long lastUsed = 0;   // v1.99：LRU 卸载的比较基准
        Tab(WebView w) { this.w = w; if (w != null) w.setTag(this); }
    }

    private final Activity act;
    private final Host host;
    private final float d;
    private final List<Tab> tabs = new ArrayList<>();
    private int active = -1;
    private String searchTpl = null;   // 引擎搜索模板（…q= + %s），JS 侧 sbApplyLabel 推来
    private String searchName = "必应";   // 当前引擎名（菜单行/地址输入态芯片显示；随模板一起推来）
    private String curUrl = "";
    // v2.02 X浏览器式双条：顶部窄条 = ‹ 后退 + 地址胶囊（含 ↻/✕、底边进度线）；底部三键 = ≡菜单/⤓下载/▢标签。
    // 两条悬浮在内容上层，滚动方向驱动藏/显（下滑藏、上滑出，检测走注入 JS 的捕获阶段 scroll 监听——
    // 能抓到内层容器滚动，v2.00 用原生回调只认整页滚动所以不可预测）
    private TextView addrTx, reloadBtn, backBtn;
    private MenuIco searchBtn;   // v2.05：胶囊输入态放大镜改自绘（原 emoji）
    private ToolBtn dlBtn, tabsBtn, menuBtn;
    private FrameLayout pill;
    private View pillProg;   // 地址胶囊底边的加载进度线（setScaleX 填充，零 layout）
    private LinearLayout topBar, bottomBar;
    private boolean barsHidden = false;
    private boolean uaDesk = false;    // 桌面版网站开关
    private EditText addrEt;   // 地址胶囊的输入态（显示态是 addrTx，就地切换不弹窗）
    private FrameLayout contentWrap;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout srl;
    private LinearLayout tabsPanel;   // 标签管理面板（盖住整个层）
    private ValueCallback<Uri[]> fileCb;   // 文件上传回调（一次一个）
    private boolean loadingActive = false;   // 当前活动标签是否在加载（决定刷新键显示 ↻ 还是 ✕）
    private View customView = null;   // v1.94 网页视频全屏层（挂在 rootLayout 顶上，盖住含 App 底栏在内的一切）
    private android.webkit.WebChromeClient.CustomViewCallback customViewCallback = null;
    private final Handler h = new Handler(Looper.getMainLooper());   // 构造线程不一定有 looper，显式钉主线程
    /** v1.94 嗅探大小补齐：小线程池并发 HEAD 请求拿 Content-Length（列表弹层里实时刷新） */
    private final java.util.concurrent.ExecutorService headPool = java.util.concurrent.Executors.newFixedThreadPool(3,
        new java.util.concurrent.ThreadFactory() {
            @Override public Thread newThread(Runnable r) { Thread t = new Thread(r, "vd-head"); t.setDaemon(true); return t; }
        });

    /** 地址胶囊常态底色（输入态换白底描边，退出输入态必须恢复成同一个色——v1.94 前恢复色写错会越用越灰） */
    private static final int PILL_BG = 0xFFF1F3F7;

    // ---------- v1.96 底部弹层（安卓原生交互：遮罩 + 白面板从底部滑入，菜单/列表全走它） ----------
    private android.widget.FrameLayout sheetDim;   // 半透明遮罩，点击关闭
    private android.widget.FrameLayout sheetPanel; // 白色面板容器（addView 到本层最顶）

    /** 弹一个底部面板（content 已造好）。面板圆角顶部、遮罩压暗网页，点遮罩/back 收起 */
    private void showSheet(View content) {
        hideSheet();
        showBars();   // v2.02：弹层打开必现双条（弹层本来也压在上面，藏着没意义）
        sheetDim = new android.widget.FrameLayout(act);
        sheetDim.setElevation(dp(12));
        sheetDim.setBackgroundColor(0x66000000);
        sheetDim.setOnClickListener(v -> hideSheet());
        addView(sheetDim, new FrameLayout.LayoutParams(-1, -1));
        sheetPanel = new android.widget.FrameLayout(act);
        sheetPanel.setElevation(dp(13));
        sheetPanel.setBackgroundColor(0xFFFFFFFF);
        android.graphics.drawable.GradientDrawable sg = new android.graphics.drawable.GradientDrawable();
        sg.setColor(0xFFFFFFFF); sg.setCornerRadius(dp(18)); sg.setBounds(0, 0, 0, 0);
        // 只要顶部圆角：用 layer-list 裁不了，直接 setClipToOutline + OutlineProvider 圆角
        sheetPanel.setClipToOutline(true);
        sheetPanel.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(18));
            }
        });
        sheetPanel.addView(content, new FrameLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2);
        lp.gravity = android.view.Gravity.BOTTOM;
        addView(sheetPanel, lp);
        sheetPanel.post(() -> {
            try {   // 从底部滑入
                sheetPanel.setTranslationY(sheetPanel.getHeight());
                sheetPanel.animate().translationY(0).setDuration(200)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            } catch (Exception ignored) {}
        });
    }
    private void hideSheet() {
        if (sheetPanel != null) {
            try { removeView(sheetPanel); } catch (Exception ignored) {}
            sheetPanel = null;
        }
        if (sheetDim != null) {
            try { removeView(sheetDim); } catch (Exception ignored) {}
            sheetDim = null;
        }
    }
    private boolean sheetShowing() { return sheetPanel != null; }
    /** 安卓水波纹前景（minSdk 24 起 LinearLayout 支持 foreground）——工具栏按钮按下有反馈。
     *  v1.99：drawable 只解析一次缓存（原来每行 resolveAttribute+getDrawable，50 行历史 = 50 次分配） */
    private android.graphics.drawable.Drawable rippleDb;
    private void ripple(View v) {
        try {
            if (rippleDb == null) {
                android.util.TypedValue tv = new android.util.TypedValue();
                act.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
                rippleDb = androidx.core.content.ContextCompat.getDrawable(act, tv.resourceId);
            }
            android.graphics.drawable.Drawable.ConstantState state = rippleDb.getConstantState();
            if (state != null) v.setForeground(state.newDrawable(act.getResources(), act.getTheme()).mutate());
        } catch (Exception ignored) {}
    }

    /** 嗅探到的媒体资源（链接去重、按出现顺序，上限 24 条）。len<0 = HEAD 问过了但拿不到；
     *  title = 记录时页面的视频标题（og:title），列表里当主标题显示——URL 尾巴只配当副标题 */
    private static class Sniff {
        String url, kind, title; long len; boolean probing;
    }

    private static final int SNIFF_MAX = 24;
    /** 代取是并发线程，Content-Length 用 ThreadLocal 传给同线程的 recordSniff */


    public BrowserLayer(Activity act, Host host) {
        super(act);
        this.act = act;
        this.host = host;
        this.d = act.getResources().getDisplayMetrics().density;
        setVisibility(GONE);
        buildUi();
        newTab(NT);   // 首个标签：视频导航页（不预载，首次 show 才渲染）
    }

    private SharedPreferences prefs() {
        return act.getSharedPreferences("vd_browser", Activity.MODE_PRIVATE);
    }

    // ---------- 书签（沿用 v1.60 存储格式；v1.99 起内存缓存 + 去抖落盘） ----------
    private List<Object[]> bmCache = null;
    private List<Object[]> bmData() {
        if (bmCache == null) {
            List<Object[]> out = new ArrayList<>();   // {title, url}
            try {
                JSONArray a = new JSONArray(prefs().getString("bookmarks", "[]"));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null) out.add(new Object[]{ o.optString("t", ""), o.optString("u", "") });
                }
            } catch (Exception ignored) {}
            bmCache = out;
        }
        return bmCache;
    }
    private final Runnable bmFlush = new Runnable() {
        @Override public void run() {
            try {
                JSONArray a = new JSONArray();
                for (Object[] e : bmData()) a.put(new JSONObject().put("t", String.valueOf(e[0])).put("u", String.valueOf(e[1])));
                prefs().edit().putString("bookmarks", a.toString()).apply();
            } catch (Exception ignored) {}
        }
    };
    /** v1.99：增删只动内存，2 秒去抖合并一次写盘——原来每次导航/收藏操作都全量
     *  parse→serialize→apply（bmHas 判断"是否已收藏"也要完整解析整个数组） */
    private void bmDirty() {
        while (bmData().size() > 200) bmData().remove(bmData().size() - 1);   // 上限防膨胀
        h.removeCallbacks(bmFlush);
        h.postDelayed(bmFlush, 2000);
    }
    private boolean bmHas(String u) {
        if (u == null || u.isEmpty()) return false;
        for (Object[] e : bmData()) if (e[1].equals(u)) return true;
        return false;
    }

    // ---------- 历史记录（最新在前、连续去重、上限 200；同样走内存缓存） ----------
    private List<Object[]> histCache = null;
    private List<Object[]> histData() {
        if (histCache == null) {
            List<Object[]> out = new ArrayList<>();   // {title, url, timeText}
            try {
                JSONArray a = new JSONArray(prefs().getString("history", "[]"));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null) out.add(new Object[]{ o.optString("t", ""), o.optString("u", ""), o.optString("at", "") });
                }
            } catch (Exception ignored) {}
            histCache = out;
        }
        return histCache;
    }
    private final Runnable histFlush = new Runnable() {
        @Override public void run() {
            try {
                JSONArray a = new JSONArray();
                for (Object[] e : histData()) a.put(new JSONObject().put("t", String.valueOf(e[0])).put("u", String.valueOf(e[1])).put("at", String.valueOf(e[2])));
                prefs().edit().putString("history", a.toString()).apply();
            } catch (Exception ignored) {}
        }
    };
    private void histDirty() {
        while (histData().size() > 200) histData().remove(histData().size() - 1);
        h.removeCallbacks(histFlush);
        h.postDelayed(histFlush, 2000);
    }
    private void histAdd(String title, String u) {
        try {
            if (u == null || u.isEmpty() || u.startsWith("about:") || u.startsWith("https://vd.nt")) return;
            List<Object[]> list = histData();
            if (!list.isEmpty() && list.get(0)[1].equals(u)) {   // 连续同一页不重复记
                list.remove(0);
            }
            String t = title == null || title.isEmpty() ? prettyUrl(u) : title;
            String at;
            try {
                at = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                    .format(new java.util.Date());
            } catch (Exception e) { at = ""; }
            list.add(0, new Object[]{ t, u, at });
            histDirty();
        } catch (Exception ignored) {}
    }

    // ---------- UI ----------
    /** v2.00 X浏览器式整体布局：网页内容铺满整层（顶部无栏，沉浸），底部单条工具栏浮层。
     *  工具栏：≡ 菜单 + 地址胶囊（占大头）+ ⤓ 下载(角标) + ▢ 标签(角标)。
     *  返回/前进不再占键位：系统返回/边缘滑动走 back() 网页后退；胶囊横滑也能前后翻 */
    private void buildUi() {
        srl = new androidx.swiperefreshlayout.widget.SwipeRefreshLayout(act);
        srl.setColorSchemeColors(0xFF4F7CFF);
        contentWrap = new FrameLayout(act);
        srl.addView(contentWrap, new FrameLayout.LayoutParams(-1, -1));
        srl.setOnChildScrollUpCallback((parent, child) -> activeWeb() != null && activeWeb().canScrollVertically(-1));
        srl.setOnRefreshListener(() -> { showBars(); reloadPage(); });
        // v2.02：内容全屏，双条悬浮其上、滚动方向驱动藏/显（下滑藏=全屏看内容，上滑出）。
        // 不再有"永远挡住内容"的问题：任何一次下滑都会把两条收走
        addView(srl, new FrameLayout.LayoutParams(-1, -1));

        // ===== 顶部窄条：‹ 后退 + 地址胶囊（胶囊右端自带 ↻/✕、底边进度线）=====
        topBar = new LinearLayout(act);
        topBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(0xFFFFFFFF);
        topBar.setElevation(dp(8));
        topBar.setPadding(dp(4), dp(3), dp(6), dp(3));
        backBtn = new TextView(act);
        backBtn.setText("‹");
        backBtn.setTextSize(20);
        backBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        backBtn.setTextColor(0xFF3358D4);
        backBtn.setGravity(android.view.Gravity.CENTER);
        backBtn.setContentDescription("网页后退");
        backBtn.setOnClickListener(v -> { WebView w = activeWeb(); if (w != null && w.canGoBack()) w.goBack(); });
        topBar.addView(backBtn, new LinearLayout.LayoutParams(dp(40), dp(44)));
        pill = new FrameLayout(act);
        pill.setBackground(round(PILL_BG, 20));
        bindPillGestures();
        addrTx = new TextView(act);
        addrTx.setText("搜索或输入网址");
        addrTx.setTextSize(14);   // v2.01：13→14，地址是高频阅读区，太小费眼
        addrTx.setTextColor(0xFF3A4352);
        addrTx.setGravity(android.view.Gravity.CENTER_VERTICAL);
        addrTx.setPaddingRelative(dp(14), 0, dp(40), dp(2));
        addrTx.setSingleLine(true);
        addrTx.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        pill.addView(addrTx, new FrameLayout.LayoutParams(-1, -1));
        // 胶囊右端内嵌刷新/停止键（显示态）
        reloadBtn = new TextView(act);
        reloadBtn.setText("↻");
        reloadBtn.setTextSize(16);
        reloadBtn.setTextColor(0xFF5C6675);
        reloadBtn.setGravity(android.view.Gravity.CENTER);
        reloadBtn.setContentDescription("刷新或停止加载");
        reloadBtn.setOnClickListener(v -> {
            if (loadingActive) { WebView w = activeWeb(); if (w != null) w.stopLoading(); }
            else reloadPage();
        });
        pill.addView(reloadBtn, new FrameLayout.LayoutParams(dp(36), dp(36), android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL));
        addrEt = new EditText(act);
        addrEt.setTextSize(14);
        addrEt.setTextColor(0xFF171A20);
        addrEt.setSingleLine(true);
        addrEt.setBackground(null);
        addrEt.setPaddingRelative(dp(14), 0, dp(40), dp(2));
        addrEt.setHint("搜索或输入网址");
        addrEt.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        addrEt.setVisibility(GONE);
        addrEt.setOnEditorActionListener((v, action, ev) -> {
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_GO) { addrGo(); return true; }
            return false;
        });
        pill.addView(addrEt, new FrameLayout.LayoutParams(-1, -1));
        // 输入态右端放大镜（v2.05 自绘，原来是 emoji 🔍 各家手机长得都不一样）：短按前往、长按弹引擎选择
        searchBtn = new MenuIco(act, IC_SEARCH);
        searchBtn.setColor(0xFF5C6675);
        searchBtn.setContentDescription("前往");
        searchBtn.setVisibility(GONE);
        searchBtn.setOnClickListener(v -> addrGo());

        pill.addView(searchBtn, new FrameLayout.LayoutParams(dp(34), dp(34), android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL));
        // 加载进度线融入胶囊底边（顶部不再有独立进度线）
        pillProg = new View(act);
        pillProg.setBackgroundColor(0xFF4F7CFF);
        pillProg.setPivotX(0f);
        pillProg.setScaleX(0f);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-1, dp(2), android.view.Gravity.BOTTOM);
        plp.leftMargin = dp(8);
        plp.rightMargin = dp(8);
        pill.addView(pillProg, plp);
        topBar.addView(pill, new LinearLayout.LayoutParams(0, dp(44), 1f));
        addView(topBar, new FrameLayout.LayoutParams(-1, -2, android.view.Gravity.TOP));

        // ===== 底部三键：≡ 菜单 / ⤓ 下载(角标) / ▢ 标签(角标)（v2.02：自绘线条图标）=====
        bottomBar = new LinearLayout(act);
        bottomBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bottomBar.setBackgroundColor(0xFFFFFFFF);
        bottomBar.setElevation(dp(8));
        bottomBar.setPadding(dp(8), dp(3), dp(8), dp(3));
        menuBtn = icoBtn(IC_MENU, "菜单", "网页下载常用操作");
        menuBtn.root.setOnClickListener(v -> { showBars(); menuDialog(); });
        bottomBar.addView(menuBtn.root, new LinearLayout.LayoutParams(0, dp(52), 1f));
        dlBtn = icoBtn(IC_DL, "下载", "点开看这页嗅探到的视频/音频资源（长按同）");
        dlBtn.root.setOnClickListener(v -> downloadTap());
        dlBtn.root.setOnLongClickListener(v -> { sniffDialog(); return true; });
        bottomBar.addView(dlBtn.root, new LinearLayout.LayoutParams(0, dp(52), 1f));
        tabsBtn = icoBtn(IC_TABS, "标签 1", "管理浏览器标签页（卡片左右滑关闭，长按更多）");
        tabsBtn.root.setOnClickListener(v -> { showBars(); showTabsPanel(); });
        bottomBar.addView(tabsBtn.root, new LinearLayout.LayoutParams(0, dp(52), 1f));
        addView(bottomBar, new FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM));

        tabsPanel = buildTabsPanel();
        FrameLayout.LayoutParams tabsLayout = new FrameLayout.LayoutParams(-1, -1);
        tabsLayout.topMargin = dp(50); // 44dp address bar plus its vertical padding.
        tabsLayout.bottomMargin = dp(58); // 52dp buttons plus their vertical padding.
        addView(tabsPanel, tabsLayout);
        // 安卓原生按压反馈
        ripple(menuBtn.root); ripple(dlBtn.root); ripple(tabsBtn.root); ripple(reloadBtn); ripple(searchBtn);
        updateDlBadge();
        updateTabsBtn();
    }

    /** 地址胶囊手势（v2.00 X浏览器式；v2.01 修正：单击零延迟进入输入态——原来用
     *  onSingleTapConfirmed 要等双击超时约 300ms，点上去明显钝。横滑=网页后退/前进。
     *  输入态时 EditText 自己消费触摸，这里的监听自然失效 */
    private void bindPillGestures() {
        pill.setContentDescription("编辑网址或搜索");
        pill.setOnClickListener(v -> addrBeginInput());
        final float[] downX = {0}, downY = {0};
        final android.view.GestureDetector gd = new android.view.GestureDetector(act,
            new android.view.GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { downX[0] = e.getX(); downY[0] = e.getY(); return false; }
                @Override public boolean onSingleTapUp(MotionEvent e) {
                    if (Math.abs(e.getX() - downX[0]) < dp(14) && Math.abs(e.getY() - downY[0]) < dp(14)) {
                        pill.performClick();
                        return true;
                    }
                    return false;
                }
                @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                    if (e1 == null || e2 == null) return false;
                    float dx = e2.getX() - e1.getX(), dy = e2.getY() - e1.getY();
                    // v2.01：阈值 44→60dp，减少和点击/误碰的混淆
                    if (Math.abs(dx) > dp(60) && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                        WebView w = activeWeb();
                        if (w == null) return false;
                        if (dx < 0 && w.canGoBack()) w.goBack();
                        else if (dx > 0 && w.canGoForward()) w.goForward();
                        return true;
                    }
                    return false;
                }
            });
        pill.setOnTouchListener((v, e) -> {
            if (addrEt != null && addrEt.getVisibility() == VISIBLE) return false;
            return gd.onTouchEvent(e);
        });
    }

    /** v2.00 浏览器层显示时状态栏融入浅色，退出还原（沉浸观感的一部分） */
    private int savedBarColor = Integer.MIN_VALUE;
    private int savedSysVis = Integer.MIN_VALUE;
    private void applyImmersiveChrome() {
        try {
            android.view.Window wnd = act.getWindow();
            if (savedBarColor == Integer.MIN_VALUE) {
                savedBarColor = wnd.getStatusBarColor();
                savedSysVis = wnd.getDecorView().getSystemUiVisibility();
            }
            wnd.setStatusBarColor(0xFFF2F4F8);
            wnd.getDecorView().setSystemUiVisibility(savedSysVis | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } catch (Throwable ignored) {}
    }
    private void restoreChrome() {
        try {
            if (savedBarColor != Integer.MIN_VALUE) {
                act.getWindow().setStatusBarColor(savedBarColor);
                act.getWindow().getDecorView().setSystemUiVisibility(savedSysVis);
                savedBarColor = Integer.MIN_VALUE;
                savedSysVis = Integer.MIN_VALUE;
            }
        } catch (Throwable ignored) {}
    }

    // ---------- v2.02 双条动态藏显：下滑藏（全屏看内容）、上滑出 ----------
    private void hideBars() {
        if (barsHidden) return;
        barsHidden = true;
        if (topBar != null) topBar.animate().translationY(-topBar.getHeight() - dp(4)).setDuration(180).start();
        if (bottomBar != null) bottomBar.animate().translationY(bottomBar.getHeight() + dp(4)).setDuration(180).start();
    }
    private void showBars() {
        if (!barsHidden) return;
        barsHidden = false;
        if (topBar != null) topBar.animate().translationY(0f).setDuration(180).start();
        if (bottomBar != null) bottomBar.animate().translationY(0f).setDuration(180).start();
    }
    /** 滚动方向信号：dir>0 向下滚（内容上移）=藏；dir<0 向上滚=出。
     *  输入态/弹层/标签面板/网页全屏视频开着时不藏（藏了反而碍事） */
    private void onScrollDir(int dir) {
        if (dir > 0) {
            if (addrEt != null && addrEt.getVisibility() == VISIBLE) return;
            if (sheetShowing()) return;
            if (tabsPanel != null && tabsPanel.getVisibility() == VISIBLE) return;
            if (customView != null) return;
            hideBars();
        } else if (dir < 0) showBars();
    }

    /** v2.02 滚动方向监听注入：捕获阶段的 scroll 事件能抓到**任意元素**的滚动（内层 div 也算，
     *  v2.00 用原生 setOnScrollChangeListener 只认整页滚动所以行为不可预测）。
     *  方向累积够 24px 才上报、120ms 节流，经 __vdSniff.y(±1) 推给原生侧 */
    private void injectScrollWatch(WebView w) {
        try {
            w.evaluateJavascript("(function(){try{if(window.__vdScrollOn)return;window.__vdScrollOn=1;"
                + "var last=new WeakMap(),acc=0,dir=0,at=0;"
                + "function send(d){try{if(window.__vdSniff&&window.__vdSniff.y)window.__vdSniff.y(d)}catch(e){}}"
                + "document.addEventListener('scroll',function(ev){try{"
                + "var t=ev.target===document?(document.scrollingElement||document.documentElement):ev.target;"
                + "if(!t||t.scrollTop===undefined)return;"
                + "var p=last.get(t);if(p===undefined)p=t.scrollTop;"
                + "var d=t.scrollTop-p;last.set(t,t.scrollTop);"
                + "if(!d)return;var nd=d>0?1:-1;var now=Date.now();"
                + "if(nd!==dir){acc=0;dir=nd;}"
                + "acc+=d;if(Math.abs(acc)>=24&&now-at>120){send(dir);at=now;acc=0}"
                + "}catch(e){}},true);"
                + "}catch(e){}})()", null);
        } catch (Exception ignored) {}
    }

    /** 底部三键按钮：自绘线条图标 + 小字标签竖排两行。label 可随时改文字当角标用 */
    private static class ToolBtn {
        final android.widget.LinearLayout root;
        final android.view.View glyph;
        final TextView label;
        final MenuIco ico;   // 非空时可 setColor
        ToolBtn(android.widget.LinearLayout root, android.view.View glyph, TextView label, MenuIco ico) {
            this.root = root; this.glyph = glyph; this.label = label; this.ico = ico;
        }
    }
    private ToolBtn icoBtn(int kind, String lab, String desc) {
        android.widget.LinearLayout b = new android.widget.LinearLayout(act);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(android.view.Gravity.CENTER);
        b.setClickable(true);
        MenuIco ic = new MenuIco(act, kind);
        b.addView(ic, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView ll = new TextView(act);
        ll.setText(lab);
        ll.setTextSize(9.5f);
        ll.setTextColor(0xFF7A88A8);
        ll.setMaxLines(1);
        ll.setGravity(android.view.Gravity.CENTER);
        b.addView(ll);
        b.setContentDescription(desc);
        return new ToolBtn(b, ic, ll, ic);
    }

    // ---------- v2.02 菜单/工具栏图标：自绘单色线条（emoji 每台手机长得都不一样，还跟浅色磁贴打架） ----------
    // v2.05：MenuIco 提成独立类（五个工具页共用新字形），字形常量静态导入，此处代码不变

    private int dp(float value) { return Math.round(value * d); }
    private android.graphics.drawable.GradientDrawable round(int color, int radius) {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(color); bg.setCornerRadius(dp(radius)); return bg;
    }
    private void reloadPage() {
        WebView w = activeWeb();
        if (w == null) { srl.setRefreshing(false); return; }
        if (isNewTab(curUrl)) w.loadDataWithBaseURL("https://vd.nt/", ntHtml(), "text/html", "utf-8", null);
        else w.reload();
    }
    private static boolean isNewTab(String url) {
        return url == null || url.isEmpty() || NT.equals(url) || "https://vd.nt/".equals(url);
    }
    private java.util.LinkedHashMap<String, Sniff> currentResources() {
        Tab t = active >= 0 && active < tabs.size() ? tabs.get(active) : null;
        return t == null ? new java.util.LinkedHashMap<>() : t.resources;
    }

    /** 每个标签一个 WebView，共用同一套客户端回调（用 view 参数区分标签） */
    private WebView buildWeb() {
        WebView w = new WebView(act);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSupportMultipleWindows(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        s.setUserAgentString(uaDesk ? DESK_UA : MOBILE_UA);
        w.addJavascriptInterface(new SniffBridge(w), "__vdSniff");   // v1.99：嗅探推送通道（配合 injectSniffer）；v2.02 兼作滚动方向通道
        // v2.02 原生滚动兜底（整页滚动的站点）：主通道是注入的捕获阶段 JS 监听（见 injectScrollWatch）
        try {
            w.setOnScrollChangeListener(new View.OnScrollChangeListener() {
                @Override public void onScrollChange(View v, int sx, int sy, int osx, int osy) {
                    if (v != activeWeb()) return;
                    int dy = sy - osy;
                    if (dy > dp(12)) onScrollDir(1);
                    else if (dy < -dp(12)) onScrollDir(-1);
                }
            });
        } catch (Throwable ignored) {}
        w.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String u, android.graphics.Bitmap fa) {
                Tab t = tabOf(view);
                if (t != null) { t.url = isNewTab(u) ? NT : u; t.loaded = true; t.generation++; t.resources.clear(); t.currentVideoUrl = ""; t.lastErrorUrl = ""; }
                if (view == activeWeb()) {
                    curUrl = isNewTab(u) ? NT : u;
                    addrTx.setText(addrDisplayText());
                    loadingActive = true;
                    setReloadIcon();
                    showBars();   // v2.02：新页面必现双条
                    // v1.90 修复：嗅探清空从 onPageFinished 挪到这里——原来在加载完成时清，
                    // 把加载期间收集到的媒体资源全丢了（嗅探经常空列表的直接原因）
                    updateDlBadge();
                }
            }
            @Override
            public void onPageFinished(WebView view, final String u) {
                Tab t = tabOf(view);
                boolean isErrorPage = t != null && u != null && u.equals(t.lastErrorUrl);
                if (!isErrorPage) histAdd(view.getTitle(), u == null ? "" : u);   // 错误页不算"看过"
                if (view == activeWeb()) {
                    try { addrTx.setText(addrDisplayText()); } catch (Exception ignored) {}
                    loadingActive = false;
                    setReloadIcon();
                    srl.setRefreshing(false);
                    // v1.90 DOM 嗅探：页面里的 <video> 元素直取 src + og:title（两遍：加载完 +
                    // 2.5 秒后——很多播放器是懒加载，第一遍抓不到）。MSE/blob 流抓不到，
                    // 由代取通道（shouldInterceptRequest）兜底。
                    // v1.99：另注入 MutationObserver 推送通道（injectSniffer）——此后不再
                    // 2.5s 轮询全页扫描，懒加载/SPA 换视频由观察者主动推
                    // v2.02：再注入滚动方向监听（injectScrollWatch）驱动双条藏显
                    sniffDom(view);
                    injectSniffer(view);
                    injectScrollWatch(view);
                    injectBannerKiller(view);   // v1.97：压掉"打开App"横幅（懒加载后弹的居多，迟一遍再补刀）
                    h.postDelayed(new Runnable() {
                        @Override public void run() {
                            WebView ww = activeWeb();
                            if (ww == view) { sniffDom(ww); injectSniffer(ww); injectScrollWatch(ww); injectBannerKiller(ww); }
                        }
                    }, 2500);
                }
            }
            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
                Tab tab = tabOf(view);
                if (tab == null) return;
                String next = isNewTab(url) ? NT : url;
                // 只更新地址、不动嗅探列表：B站/YouTube 这类 SPA 换视频走 pushState，也会进这里，
                // 在这清资源会把"看视频途中收集的媒体"全丢（真加载的清空由 onPageStarted 负责）
                if (!next.equals(tab.url)) tab.url = next;
                if (view == activeWeb()) { curUrl = next; addrTx.setText(addrDisplayText()); updateDlBadge(); }
                // v1.94：SPA 换了视频，尽快重跑一次 DOM 嗅探刷新"当前播放"标记（资源列表仍保留）
                final WebView fv = view;
                h.postDelayed(new Runnable() { @Override public void run() { if (activeWeb() == fv) sniffDom(fv); } }, 800);
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String u) {
                // v1.96 新标签页（视频导航）内部协议：搜索提交 / 引擎芯片切换（页面没有 JS 桥，走 URL 转发）
                if (u != null && u.startsWith("https://vd.nt/search?")) {
                    android.net.Uri uri = android.net.Uri.parse(u);
                    String q = uri.getQueryParameter("q");
                    String e = uri.getQueryParameter("e");
                    String tpl = searchTpl;
                    for (String[] eng : ENGINES) if (eng[0].equals(e)) tpl = eng[3];
                    if (q != null && !q.trim().isEmpty() && tpl != null) {
                        try { view.loadUrl(tpl.replace("%s", URLEncoder.encode(q.trim(), "UTF-8"))); }
                        catch (Exception ignored) {}
                    }
                    return true;
                }
                if (u != null && u.startsWith("https://vd.nt/engine?id=")) {
                    String id = android.net.Uri.parse(u).getQueryParameter("id");
                    for (String[] eng : ENGINES) {
                        if (eng[0].equals(id) && !eng[1].equals(searchName)) {
                            host.runJs("window.sbPickFromNative && window.sbPickFromNative(" + JSONObject.quote(eng[0]) + ")");
                            searchTpl = eng[3];
                            searchName = eng[1];
                        }
                    }
                    // 重新渲染新标签页：芯片 ✓ 移到新引擎上
                    Tab t = tabOf(view);
                    if (t != null) { t.url = NT; view.loadDataWithBaseURL("https://vd.nt/", ntHtml(), "text/html", "utf-8", null); }
                    return true;
                }
                if (u != null && (u.startsWith("http://") || u.startsWith("https://"))) {
                    // v1.97：应用商店跳转（"打开App"按钮的真实去处）拦下——内置浏览器就留在网页里看/播
                    if (isStoreUrl(u)) {
                        Toast.makeText(act, "已拦截「打开App」跳转，继续用内置浏览器", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    return false;
                }
                return true;   // 深链（intent:// snssdk…:// 等）拦截，留在本窗口
            }
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // Let Chromium handle cookies, redirects, range requests and caching.
                // Re-fetching every request with HttpURLConnection broke site compatibility.
                Tab t = (Tab) view.getTag();
                if (t != null) recordSniff(t, t.generation, request.getUrl().toString(), null, 0, null);
                return null;
            }
            /** v1.94 主文档加载失败：中文错误页 + 一键重试（子资源失败不掺和，只看 isForMainFrame）。
             *  v1.95：lastErrorUrl 记档，onPageFinished 不把失败网址写进历史 */
            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, android.webkit.WebResourceError err) {
                if (!req.isForMainFrame()) return;
                String failing = req.getUrl().toString();
                if (isNewTab(failing) || !(failing.startsWith("http://") || failing.startsWith("https://"))) return;
                Tab t = tabOf(view);
                if (t != null) t.lastErrorUrl = failing;
                int code = err.getErrorCode();
                String why = code == android.webkit.WebViewClient.ERROR_HOST_LOOKUP ? "域名解析失败（网址写错或断网）"
                    : code == android.webkit.WebViewClient.ERROR_CONNECT ? "服务器连不上"
                    : code == android.webkit.WebViewClient.ERROR_TIMEOUT ? "连接超时"
                    : code == android.webkit.WebViewClient.ERROR_FAILED_SSL_HANDSHAKE ? "HTTPS 证书/协议异常"
                    : "网络错误（代码 " + code + "）";
                String host;
                try { host = Uri.parse(failing).getHost(); } catch (Exception e) { host = failing; }
                String html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                    + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                    + "<style>body{font-family:system-ui;margin:0;padding:40px 24px;background:#f6f8fc;color:#1c2b4a;text-align:center}"
                    + "h1{font-size:18px;margin:0 0 10px}p{font-size:13px;color:#7a88a8;line-height:1.8;margin:0 0 26px}"
                    + "a{display:block;max-width:220px;margin:0 auto;padding:12px 0;background:#4f7cff;color:#fff;"
                    + "border-radius:22px;text-decoration:none;font-size:14px;font-weight:600}</style></head><body>"
                    + "<h1>页面打不开了</h1><p>" + android.text.TextUtils.htmlEncode(host) + "<br>" + why + "</p>"
                    + "<a href=\"" + attr(failing) + "\">重新加载</a></body></html>";
                view.stopLoading();
                view.loadDataWithBaseURL(failing, html, "text/html", "utf-8", failing);
                if (view == activeWeb()) { loadingActive = false; setReloadIcon(); srl.setRefreshing(false); }
            }
        });
        w.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int p) {
                if (view != activeWeb() || pillProg == null) return;
                if (p >= 100) { pillProg.setScaleX(0f); loadingActive = false; setReloadIcon(); return; }
                if (!loadingActive) { loadingActive = true; setReloadIcon(); }
                pillProg.setScaleX(Math.max(0.03f, p / 100f));   // v2.00：进度线融入地址胶囊底边，零 layout
            }
            /** v1.94 网页视频全屏：页面调 video.requestFullscreen() 时把全屏层挂到 rootLayout 顶上
             *  （盖住浏览器工具栏和 App 底部导航），退出由 back()/onHideCustomView 处理。
             *  和 MainActivity 主 WebView 的实现同一套模式 */
            @Override public void onShowCustomView(View view, CustomViewCallback cb) {
                if (customView != null) { try { cb.onCustomViewHidden(); } catch (Exception ignored) {} return; }
                customView = view;
                customViewCallback = cb;
                try {
                    android.view.ViewGroup parent = (android.view.ViewGroup) BrowserLayer.this.getParent();
                    parent.addView(view, new android.widget.FrameLayout.LayoutParams(-1, -1));
                    view.setKeepScreenOn(true);
                } catch (Exception e) { customView = null; customViewCallback = null; }
            }
            @Override public void onHideCustomView() { exitCustomView(); }
            /** 网页文件上传 input[type=file]（v1.65）：转系统文件选择器，结果经 requestCode 13 回来 */
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb,
                                                        android.webkit.WebChromeClient.FileChooserParams params) {
                if (fileCb != null) { try { fileCb.onReceiveValue(null); } catch (Exception ignored) {} fileCb = null; }
                try {
                    Intent it = params.createIntent();
                    it.addCategory(Intent.CATEGORY_OPENABLE);
                    fileCb = cb;
                    act.startActivityForResult(it, 13);
                    return true;
                } catch (Exception e) {
                    fileCb = null;
                    Toast.makeText(act, "打不开文件选择器", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
        });
        // 网页里点直链文件（http://…/xx.mp4 之类）：v1.90 改为后台直接下（不退出浏览器回首页）
        w.setDownloadListener(new android.webkit.DownloadListener() {
            @Override public void onDownloadStart(String u, String ua, String cd, String mime, long len) {
                if (u != null && (u.startsWith("http://") || u.startsWith("https://"))) pickDownload(u, pageTitle());
            }
        });
        return w;
    }

    // ---------- 标签页管理 ----------
    private Tab tabOf(WebView w) {
        return w != null && w.getTag() instanceof Tab ? (Tab) w.getTag() : null;
    }
    private WebView activeWeb() {
        return active >= 0 && active < tabs.size() ? tabs.get(active).w : null;
    }
    private void loadTabIfNeeded(Tab t) {
        if (t.loaded) return;
        t.loaded = true;
        if (t.url == null || t.url.isEmpty() || NT.equals(t.url)) {
            // v1.90 新标签页：视频导航（常用视频站 + 最近看过），每次现渲染（历史会变）
            t.w.loadDataWithBaseURL("https://vd.nt/", ntHtml(), "text/html", "utf-8", null);
            return;
        }
        t.w.loadUrl(t.url);
    }
    /** LRU 卸载过的标签按记录重建 WebView（url 还在，代数自增作废旧回调） */
    private void ensureWeb(Tab t) {
        if (t.w != null) return;
        int resident = 0;
        Tab victim = null;
        for (Tab candidate : tabs) {
            if (candidate.w == null) continue;
            resident++;
            if (candidate != t && (victim == null || candidate.lastUsed < victim.lastUsed)) victim = candidate;
        }
        if (resident >= TAB_MAX && victim != null) unloadTab(victim);
        t.w = buildWeb();
        t.w.setTag(t);
        t.generation++;
    }
    private void newTab(String u) {
        Tab t = new Tab(null);
        t.url = u == null || u.isEmpty() ? NT : u;
        tabs.add(t);
        switchTab(tabs.size() - 1);
    }
    /** 卸载一个标签的 WebView（先摘容器再 destroy；url/面板记录保留） */
    private void unloadTab(Tab t) {
        try {
            if (t.w != null) {
                String title = t.w.getTitle();
                if (title != null && !title.isEmpty()) t.title = title;
                android.view.ViewGroup p = (android.view.ViewGroup) t.w.getParent();
                if (p != null) p.removeView(t.w);
                t.w.destroy();
            }
        } catch (Exception ignored) {}
        t.w = null;
        t.loaded = false;
        t.resources.clear();
        t.generation++;
    }
    private void switchTab(int i) {
        if (i < 0 || i >= tabs.size()) return;
        if (addrEt != null && addrEt.getVisibility() == VISIBLE) addrEndInput(false);
        // v1.70：旧活动标签先 onPause（停 JS 定时器/视频/音频——切走后继续放声是典型投诉），
        // 新标签 onResume；从 contentWrap 摘下再挂新的
        WebView old = activeWeb();
        if (old != null && old != tabs.get(i).w) { try { pauseTab(old); } catch (Exception ignored) {} }
        active = i;
        Tab t = tabs.get(i);
        t.lastUsed = android.os.SystemClock.elapsedRealtime();   // v1.99 LRU 基准
        ensureWeb(t);   // v1.99：LRU 卸载过的标签在这里按 url 重建
        contentWrap.removeAllViews();
        contentWrap.addView(t.w, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        try { t.w.onResume(); } catch (Exception ignored) {}
        curUrl = t.url;

        updateDlBadge();
        addrTx.setText(addrDisplayText());
        loadingActive = false;   // 切进来时先按"已加载"显示，真在加载的话下一个进度事件会纠正
        setReloadIcon();
        if (pillProg != null) pillProg.setScaleX(0f);
        srl.setRefreshing(false);
        showBars();   // v2.02：切标签必现双条
        updateTabsBtn();
        hideSheet();
        hideTabsPanel();
        if (isVisible()) loadTabIfNeeded(t);
    }
    private void closeTab(int i) {
        if (i < 0 || i >= tabs.size()) return;
        boolean wasActive = i == active;
        destroyTab(tabs.remove(i));   // v1.70：先从容器摘下再 destroy（attached 状态 destroy 是未定义行为）
        if (tabs.isEmpty()) {   // 最后一个也关了：开个新标签停在主页（浏览器不退出）
            newTab(NT);
            return;
        }
        if (i < active) active--;
        if (active >= tabs.size()) active = tabs.size() - 1;
        if (wasActive) switchTab(active);
        else updateTabsBtn();   // 关的是后台标签：正在看的页面别摘下来重挂（闪一下还可能重置渲染）
    }
    private void closeOthers() {   // 保留当前标签，其余全关
        int keep = active;
        for (int i = tabs.size() - 1; i >= 0; i--) {
            if (i == keep) continue;
            destroyTab(tabs.remove(i));
        }
        switchTab(0);
        updateTabsBtn();
    }
    /** v1.70：销毁一个标签的 WebView——先从容器摘下再 destroy（attached 状态下直接 destroy
     *  是未定义行为，部分 ROM 泄漏窗口/报错） */
    private void destroyTab(Tab t) {
        if (t == null || t.w == null) return;
        try {
            android.view.ViewGroup p = (android.view.ViewGroup) t.w.getParent();
            if (p != null) p.removeView(t.w);
        } catch (Exception ignored) {}
        try { t.w.destroy(); } catch (Exception ignored) {}
    }

    /** ▢ 标签管理面板：卡片列表（点卡片切换、✕关闭）+ 新标签页 */
    private LinearLayout buildTabsPanel() {
        LinearLayout p = new LinearLayout(act);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackgroundColor(0xFFF6F8FC);
        p.setVisibility(GONE);
        return p;
    }
    private void showTabsPanel() { showBars(); rebuildTabsPanel(); tabsPanel.setVisibility(VISIBLE); }
    private void hideTabsPanel() { tabsPanel.setVisibility(GONE); }
    private void rebuildTabsPanel() {
        tabsPanel.removeAllViews();
        // 头部：标题 + ✕收起
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setPadding((int)(16*d), (int)(12*d), (int)(10*d), (int)(8*d));
        TextView title = new TextView(act);
        title.setText(act.getString(R.string.tabs_count, tabs.size()));
        title.setTextSize(15);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF1C2B4A);
        head.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView closeOth = new TextView(act);
        closeOth.setText("关闭其他");
        closeOth.setTextSize(12.5f);
        closeOth.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        closeOth.setTextColor(0xFF3358D4);
        closeOth.setPadding((int)(10*d), (int)(8*d), (int)(10*d), (int)(8*d));
        closeOth.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (tabs.size() <= 1) { Toast.makeText(act, "只有一个标签页", Toast.LENGTH_SHORT).show(); return; }
                closeOthers();
                rebuildTabsPanel();
                tabsPanel.setVisibility(VISIBLE);
            }
        });
        head.addView(closeOth);
        TextView shut = pillNav("✕", 15);
        shut.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { hideTabsPanel(); }
        });
        head.addView(shut, lpNav());
        tabsPanel.addView(head);

        ScrollView sc = new ScrollView(act);
        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding((int)(12*d), 0, (int)(12*d), (int)(8*d));
        for (int i = 0; i < tabs.size(); i++) {
            final int idx = i;
            final Tab t = tabs.get(i);
            LinearLayout card = new LinearLayout(act);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(android.view.Gravity.CENTER_VERTICAL);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xFFFFFFFF); bg.setCornerRadius(12*d);
            if (i == active) bg.setStroke((int)(2*d), 0xFF4F7CFF);
            card.setBackground(bg);
            LinearLayout.LayoutParams lpC = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lpC.topMargin = (int)(8*d);
            card.setPadding((int)(12*d), (int)(10*d), (int)(6*d), (int)(10*d));

            LinearLayout info = new LinearLayout(act);
            info.setOrientation(LinearLayout.VERTICAL);
            TextView tt = new TextView(act);
            String title2 = NT.equals(t.url) ? null : (t.loaded && t.w != null ? t.w.getTitle() : t.title);   // v2.01：新标签页别显示 about:blank
            tt.setText(title2 == null || title2.isEmpty() ? prettyUrl(t.url) : title2);
            tt.setTextSize(13.5f);
            tt.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            tt.setTextColor(0xFF1C2B4A);
            tt.setSingleLine(true);
            tt.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            info.addView(tt);
            TextView uu = new TextView(act);
            uu.setText(prettyUrl(t.url));
            uu.setTextSize(11);
            uu.setTextColor(0xFF7A88A8);
            uu.setSingleLine(true);
            uu.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            info.addView(uu);
            card.addView(info, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            TextView x = pillNav("✕", 14);
            x.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    closeTab(idx);
                    if (tabsPanel.getVisibility() != VISIBLE) {   // closeTab 里 switchTab 会把面板收起：连关多个标签时保持面板开着
                        rebuildTabsPanel();
                        tabsPanel.setVisibility(VISIBLE);
                    } else {
                        rebuildTabsPanel();
                    }
                }
            });
            card.addView(x, lpNav());

            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { switchTab(idx); }
            });
            // v2.00 X浏览器式：卡片左右滑直接关闭（跟手位移 + 越界飞出），长按弹操作菜单
            card.setOnTouchListener(swipeToClose(card, () -> {
                closeTab(idx);
                rebuildTabsPanel();
                tabsPanel.setVisibility(VISIBLE);
            }));
            card.setOnLongClickListener(v -> { tabSheetMenu(idx, t); return true; });
            list.addView(card, lpC);
        }
        sc.addView(list, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        tabsPanel.addView(sc, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView add = new TextView(act);
        add.setText("＋ 新标签页");
        add.setTextSize(13.5f);
        add.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        add.setTextColor(0xFFFFFFFF);
        add.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable bgAdd = new android.graphics.drawable.GradientDrawable();
        bgAdd.setColor(0xFF4F7CFF); bgAdd.setCornerRadius(14*d);
        add.setBackground(bgAdd);
        LinearLayout.LayoutParams lpAdd = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int)(46*d));
        lpAdd.setMargins((int)(12*d), (int)(4*d), (int)(12*d), (int)(12*d));
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { newTab(NT); }
        });
        tabsPanel.addView(add, lpAdd);
    }
    private void updateTabsBtn() {
        if (tabsBtn != null) tabsBtn.label.setText(act.getString(R.string.tabs_short_count, tabs.size()));
    }

    // ---------- 小工具 ----------
    private TextView pillNav(String txt, float sp) {
        TextView v = new TextView(act);
        v.setText(txt);
        v.setTextSize(sp);
        v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        v.setTextColor(0xFF3358D4);
        v.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0xFFE8EEFB); g.setCornerRadius(21*d);
        v.setBackground(g);
        return v;
    }
    private LinearLayout.LayoutParams lpNav() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int)(38*d), (int)(38*d));
        lp.gravity = android.view.Gravity.CENTER_VERTICAL;
        return lp;
    }
    /** 底部导航按钮：大点击区、图标居中，不可用置灰 */
    private TextView navBtn(String txt, float sp) {
        TextView v = new TextView(act);
        v.setText(txt);
        v.setTextSize(sp);
        v.setTextColor(0xFF3358D4);
        v.setGravity(android.view.Gravity.CENTER);
        return v;
    }

    /** v2.00 标签卡片横滑关闭：水平拖动跟手（拦截父容器滚动），越过 1/3 宽松手即飞出关闭，
     *  否则弹回；未构成横滑时完全不消费——单击/长按照常派发 */
    private android.view.View.OnTouchListener swipeToClose(final View card, final Runnable onClose) {
        return new android.view.View.OnTouchListener() {
            float x0 = 0, y0 = 0;
            boolean swiping = false, decided = false;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        x0 = e.getRawX(); y0 = e.getRawY();
                        swiping = false; decided = false;
                        break;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - x0, dy = e.getRawY() - y0;
                        if (!decided) {
                            if (Math.abs(dx) > dp(10) && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                                decided = true; swiping = true;
                                card.cancelLongPress();
                                card.setPressed(false);
                                card.getParent().requestDisallowInterceptTouchEvent(true);
                            } else if (Math.abs(dy) > dp(12)) {
                                decided = true; swiping = false;
                            }
                        }
                        if (swiping) {
                            card.setTranslationX(dx);
                            return true;
                        }
                        break;
                    }
                    case MotionEvent.ACTION_CANCEL:
                        boolean cancelledSwipe = swiping;
                        swiping = false;
                        card.getParent().requestDisallowInterceptTouchEvent(false);
                        card.animate().translationX(0f).alpha(1f).setDuration(150).start();
                        return cancelledSwipe;
                    case MotionEvent.ACTION_UP: {
                        boolean was = swiping;
                        swiping = false;
                        card.getParent().requestDisallowInterceptTouchEvent(false);
                        if (was) {
                            float tx = card.getTranslationX();
                            if (Math.abs(tx) > card.getWidth() / 3f) {
                                final boolean toLeft = tx < 0;
                                card.animate().translationX(toLeft ? -card.getWidth() - 8 : card.getWidth() + 8)
                                    .alpha(0f).setDuration(160).withEndAction(onClose).start();
                            } else {
                                card.animate().translationX(0f).setDuration(150).start();
                            }
                            return true;
                        }
                        break;
                    }
                }
                return false;
            }
        };
    }

    /** v2.00 标签卡片长按菜单：关闭 / 收藏此页 / 复制链接（X浏览器同款三件） */
    private void tabSheetMenu(final int idx, final Tab t) {
        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xFFFFFFFF);
        String ttl = t.loaded && t.w != null && t.w.getTitle() != null && !t.w.getTitle().isEmpty()
            ? t.w.getTitle() : prettyUrl(t.url);
        panel.addView(sheetHead(ttl, null));
        final String[] labels = { "关闭标签", "收藏此页", "复制链接" };
        for (int i = 0; i < labels.length; i++) {
            final int w2 = i;
            LinearLayout row = new LinearLayout(act);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(20), dp(13), dp(20), dp(13));
            ripple(row);
            TextView tv = new TextView(act);
            tv.setText(labels[i]);
            tv.setTextSize(14.5f);
            tv.setTextColor(0xFF1C2B4A);
            row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));
            row.setOnClickListener(v -> {
                hideSheet();
                if (w2 == 0) {
                    closeTab(idx);
                    rebuildTabsPanel();
                    tabsPanel.setVisibility(VISIBLE);
                } else if (w2 == 1) {
                    try {
                        List<Object[]> bs = bmData();
                        boolean has = false;
                        for (Object[] e : bs) if (e[1].equals(t.url)) has = true;
                        if (!has) {
                            bs.add(new Object[]{ ttl, t.url });
                            bmDirty();
                            Toast.makeText(act, "已收藏：" + ttl, Toast.LENGTH_SHORT).show();
                        } else Toast.makeText(act, "已经在收藏里了", Toast.LENGTH_SHORT).show();
                    } catch (Exception ignored) {}
                } else if (w2 == 2) {
                    try {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            act.getSystemService(Activity.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("url", t.url));
                        Toast.makeText(act, "链接已复制", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) { Toast.makeText(act, "复制失败", Toast.LENGTH_SHORT).show(); }
                }
            });
            panel.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        showSheet(panel);
    }

    // ---------- 对外：显示 / 返回键 / 生命周期 ----------
    /** 打开层（浏览器标签 / 搜索条进来）。同址不重载——页面状态和滚动位置不丢；
     *  v1.90：默认落新标签页（视频导航）；上次逛的页面还在当前标签就接着显示 */
    public void show(String u) {
        if (isNewTab(u)) u = NT;
        if (tabs.isEmpty()) newTab(u);
        Tab t = tabs.get(active < 0 ? 0 : active);
        ensureWeb(t);   // v1.99：当前标签可能被 LRU 卸载过
        boolean nt = NT.equals(u);
        if (!nt && !u.equals(t.url) && !u.equals(curUrl)) {
            // 换地址（搜索条进来）：当前标签直接导航
            t.url = u;
            t.loaded = true;
            t.w.loadUrl(u);
        } else if (nt && NT.equals(t.url)) {
            // 回到视频导航页：现渲染（最近看过会更新）；没渲染过则走 loadTabIfNeeded
            if (t.loaded) t.w.loadDataWithBaseURL("https://vd.nt/", ntHtml(), "text/html", "utf-8", null);
        }
        if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
        applyImmersiveChrome();   // v2.00：状态栏融入浅色（退出时 restoreChrome）
        showBars();   // v2.02：进浏览器必现双条
        try {
            addrTx.setText(addrDisplayText());
            if (pillProg != null) pillProg.setScaleX(0f);   // 上次会话可能停在加载中
        } catch (Exception ignored) {}
        try { WebView w = activeWeb(); if (w != null) w.onResume(); } catch (Exception ignored) {}
        loadTabIfNeeded(t);
        host.runJs("window.onBrowserOpen && window.onBrowserOpen()");
    }
    public boolean isVisible() { return getVisibility() == VISIBLE; }
    /** 返回依次关闭浮层、回退网页，起始页返回打开浏览器之前的应用页面。 */
    public boolean back() {
        if (customView != null) { exitCustomView(); return true; }   // v1.94 全屏视频最先退
        if (!isVisible()) return false;
        if (addrEt.getVisibility() == VISIBLE) { addrEndInput(false); return true; }
        if (sheetShowing()) { hideSheet(); return true; }   // v1.96 底部弹层
        if (tabsPanel != null && tabsPanel.getVisibility() == VISIBLE) { hideTabsPanel(); return true; }
        WebView w = activeWeb();
        if (w != null && w.canGoBack()) { w.goBack(); return true; }
        exitToApp(false);
        return true;
    }
    /** v1.94 退出网页全屏：摘层 + 通知内核（页面收到 fullscreenchange 恢复内联播放） */
    private void exitCustomView() {
        if (customView == null) return;
        try {
            android.view.ViewGroup p = (android.view.ViewGroup) customView.getParent();
            if (p != null) p.removeView(customView);
        } catch (Exception ignored) {}
        customView.setKeepScreenOn(false);
        if (customViewCallback != null) {
            try { customViewCallback.onCustomViewHidden(); } catch (Exception ignored) {}
            customViewCallback = null;
        }
        customView = null;
    }
    /** JS 侧 sbApplyLabel 推来的搜索模板（…q= + %s）和引擎名 */
    public void setSearchTpl(String tpl, String name) {
        if (tpl != null && tpl.contains("%s")) searchTpl = tpl;
        if (name != null && !name.trim().isEmpty()) {
            searchName = name.trim();
            h.post(new Runnable() { @Override public void run() {
                // v2.00：输入态提示语带引擎名，立即刷新
                try { if (addrEt != null && addrEt.getVisibility() == VISIBLE) addrEt.setHint(inputHint()); } catch (Exception ignored) {}
            } });
        }
    }
    /** v2.00 地址输入态提示语：带上当前引擎名（长按 🔍 可换） */
    private String inputHint() {
        return "用 " + searchName + " 搜索，或输入网址";
    }
    /** 文件上传结果（MainActivity requestCode 13 转来） */
    public void onFileResult(int resultCode, Intent data) {
        if (fileCb == null) return;
        Uri[] uris = null;
        try {
            if (resultCode == Activity.RESULT_OK && data != null) {
                uris = android.webkit.WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            }
        } catch (Exception ignored) {}
        try { fileCb.onReceiveValue(uris); } catch (Exception ignored) {}
        fileCb = null;
    }
    /** 退出浏览器层：记住最后页面（下次「浏览器」接着逛）。toHome=🏠 回首页；返回退到栈底=留在原页面 */
    public void hide() { hideBrowser(false, false); }
    private void exitToApp(boolean toHome) { hideBrowser(true, toHome); }
    private void hideBrowser(boolean notify, boolean toHome) {
        addrEndInput(false);
        hideSheet();   // v1.96：别让弹层留着
        exitCustomView();   // v1.95 复查补：万一全屏层还挂着（JS 侧主动关浏览器的路径），别让它留在界面上
        restoreChrome();   // v2.00：状态栏还原
        for (Tab t : tabs) { try { pauseTab(t.w); } catch (Exception ignored) {} }   // 全标签暂停省电
        hideTabsPanel();
        setVisibility(GONE);
        flushCookies();   // 登录 cookie 落盘：不 flush 的话进程被杀就白登了
        if (curUrl != null && !curUrl.isEmpty()) {
            host.runJs("window.__browserLast && window.__browserLast(" + JSONObject.quote(curUrl) + ")");
        }
        if (notify) host.onExit(toHome);
    }
    public void pause() {
        exitCustomView();
        for (Tab tab : tabs) pauseTab(tab.w);
    }
    private void pauseTab(WebView w) {
        if (w == null) return;
        w.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(v){v.pause()})", null);
        w.onPause();
    }
    public void resume() {
        if (!isVisible()) return;
        WebView w = activeWeb(); if (w != null) w.onResume();
    }
    public void flushCookies() {
        try { CookieManager.getInstance().flush(); } catch (Exception ignored) {}
    }
    public void destroy() {
        h.removeCallbacksAndMessages(null);
        exitCustomView();
        headPool.shutdownNow();
        for (Tab t : tabs) destroyTab(t);
        tabs.clear();
    }

    /** 地址栏展示：host + 路径尾巴（完整 URL 太长） */
    private String prettyUrl(String u) {
        if (u == null || u.isEmpty()) return "";
        if (NT.equals(u) || u.startsWith("https://vd.nt")) return "网页下载";
        try {
            android.net.Uri uri = android.net.Uri.parse(u);
            String host = uri.getHost() == null ? u : uri.getHost();
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (path.length() > 18) path = "…" + path.substring(path.length() - 17);
            return host + path;
        } catch (Exception e) { return u; }
    }

    /** v1.94 地址胶囊显示文案（X浏览器式）：有标题显示标题，没标题退回 host+路径 */
    private String addrDisplayText() {
        if (isNewTab(curUrl)) return "搜索或输入网址";
        WebView w = activeWeb();
        String t = w == null ? null : w.getTitle();
        if (t != null && !t.trim().isEmpty() && !"about:blank".equals(t)) return t.trim();
        return prettyUrl(curUrl);
    }
    /** 胶囊内右侧的键：加载中 ✕（停止），平时 ↻（刷新） */
    private void setReloadIcon() {
        reloadBtn.setText(loadingActive ? "✕" : "↻");
    }

    /** 搜索引擎切换（v1.96 底部弹层）：列表与 index.html SB_ENGINES 一致（顺序按用户偏好 Yandex/必应在前）。
     *  选择经 window.sbPickFromNative 走 JS 单一事实源（localStorage + sbApplyLabel 回推模板和名字） */
    private static final String[][] ENGINES = {
        { "yandex", "Yandex", "俄罗斯引擎 · 英文界面", "https://yandex.com/search/?text=%s" },
        { "bing",   "必应",   "中国版 · 中文界面",       "https://cn.bing.com/search?q=%s" },
        { "edge",   "Edge",   "必应国际版 · Edge 同款",  "https://www.bing.com/search?q=%s" },
    };
    private void engineDialog() {
        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xFFFFFFFF);
        TextView title = new TextView(act);
        title.setText("搜索引擎（地址栏输关键词时用）");
        title.setTextSize(14);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF1C2B4A);
        title.setPadding(dp(20), dp(16), dp(20), dp(6));
        panel.addView(title);
        for (int i = 0; i < ENGINES.length; i++) {
            final int w2 = i;
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(20), dp(12), dp(20), dp(12));
            ripple(row);
            boolean cur = ENGINES[i][1].equals(searchName);
            TextView name = new TextView(act);
            name.setText(cur ? act.getString(R.string.selected_engine, ENGINES[i][1]) : ENGINES[i][1]);
            name.setTextSize(15);
            name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            name.setTextColor(cur ? 0xFF3358D4 : 0xFF1C2B4A);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            TextView desc = new TextView(act);
            desc.setText(ENGINES[i][2]);
            desc.setTextSize(12);
            desc.setTextColor(0xFF8A97B4);
            row.addView(desc);
            row.setOnClickListener(v -> {
                hideSheet();
                if (ENGINES[w2][1].equals(searchName)) return;
                host.runJs("window.sbPickFromNative && window.sbPickFromNative(" + JSONObject.quote(ENGINES[w2][0]) + ")");
                searchTpl = ENGINES[w2][3];
                searchName = ENGINES[w2][1];
                if (addrEt != null && addrEt.getVisibility() == VISIBLE) addrEt.setHint(inputHint());
                Toast.makeText(act, "搜索引擎：" + searchName, Toast.LENGTH_SHORT).show();
            });
            panel.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        TextView foot = new TextView(act);
        foot.setText("首页搜索条右侧的徽章也能切，两边记忆互通");
        foot.setTextSize(11);
        foot.setTextColor(0xFF9AA8C4);
        foot.setPadding(dp(20), dp(4), dp(20), dp(14));
        panel.addView(foot);
        showSheet(panel);
    }

    /** 地址胶囊点击：显示态→输入态就地切换（自动聚焦+弹软键盘+光标在尾）；
     *  v2.00：输入态提示语带当前引擎名，右端 🔍 短按前往/长按换引擎（X浏览器同款） */
    private void addrBeginInput() {
        showBars();   // v2.02：输入必现双条（键盘场景）
        addrTx.setVisibility(GONE);
        reloadBtn.setVisibility(GONE);
        addrEt.setVisibility(VISIBLE);
        searchBtn.setVisibility(VISIBLE);
        addrEt.setHint(inputHint());
        addrEt.setText(isNewTab(curUrl) ? "" : curUrl);
        try { addrEt.setSelection(addrEt.getText().length()); } catch (Exception ignored) {}
        addrEt.requestFocus();
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0xFFFFFFFF); g.setCornerRadius(20*d); g.setStroke((int)(2*d), 0xFF4F7CFF);
        pill.setBackground(g);
        addrEt.postDelayed(new Runnable() {
            @Override public void run() {
                if (addrEt.getVisibility() != VISIBLE) return;
                try {
                    android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                        act.getSystemService(Activity.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(addrEt, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                } catch (Exception ignored) {}
            }
        }, 100);
    }
    /** 输入态回显示态。go=true 表示用户已按前往（值已用掉）。
     *  v1.94 修复：恢复色统一用 PILL_BG——以前写错成灰色，用一次之后胶囊就再也不是初始色了 */
    private void addrEndInput(boolean go) {
        addrEt.setVisibility(GONE);
        searchBtn.setVisibility(GONE);
        addrTx.setVisibility(VISIBLE);
        reloadBtn.setVisibility(VISIBLE);
        pill.setBackground(round(PILL_BG, 20));
        try {
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                act.getSystemService(Activity.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(addrEt.getWindowToken(), 0);
        } catch (Exception ignored) {}
    }
    /** 前往：像网址（有点号/协议头）就打开；否则当关键词用所选引擎搜 */
    private void addrGo() {
        String q = addrEt.getText().toString().trim();
        addrEndInput(true);
        if (q.isEmpty()) return;
        String u;
        boolean urlLike = !q.contains(" ") && (q.startsWith("http://") || q.startsWith("https://")
            || (q.contains(".") && q.indexOf(".") > 0 && q.indexOf(".") < q.length() - 1 && !q.contains("..")));
        if (!urlLike && searchTpl != null) {
            try {
                u = searchTpl.replace("%s", URLEncoder.encode(q, "UTF-8"));
            } catch (Exception e) { u = "https://" + q; }
        } else if (!q.startsWith("http://") && !q.startsWith("https://")) {
            u = "https://" + q;
        } else {
            u = q;
        }
        WebView wv = activeWeb();
        if (wv != null && active >= 0) { Tab t = tabs.get(active); t.url = u; t.loaded = true; wv.loadUrl(u); }
    }

    /** 网页下载常用操作。 */
    private void menuDialog() {
        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.addView(sheetHead("网页下载工具", null));
        final int[] icons = {IC_SNIFF, IC_STAR, IC_BM, IC_CLOCK, IC_MONI, IC_COPY, IC_SHARE, IC_BACK};
        final String[] labels = {"识别资源", bmHas(curUrl) ? "取消收藏" : "收藏此页", "书签", "历史",
            uaDesk ? "手机版" : "桌面版", "复制链接", "系统打开", "返回应用"};
        for (int row = 0; row < 2; row++) {
            LinearLayout line = new LinearLayout(act);
            for (int column = 0; column < 4; column++) {
                final int index = row * 4 + column;
                if (index >= icons.length) { line.addView(new View(act), new LinearLayout.LayoutParams(0, dp(76), 1)); continue; }
                LinearLayout cell = new LinearLayout(act);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(android.view.Gravity.CENTER);
                MenuIco icon = new MenuIco(act, icons[index]);
                cell.addView(icon, new LinearLayout.LayoutParams(dp(26), dp(26)));
                TextView label = new TextView(act);
                label.setText(labels[index]); label.setTextColor(0xFF3358D4); label.setTextSize(12);
                label.setGravity(android.view.Gravity.CENTER);
                cell.addView(label);
                ripple(cell);
                cell.setOnClickListener(v -> {
                    hideSheet();
                    switch (index) {
                        case 0: sniffDialog(); break;
                        case 1: starToggle(); break;
                        case 2: bmManager(); break;
                        case 3: histDialog(); break;
                        case 4: uaToggle(); break;
                        case 5:
                            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                                act.getSystemService(Activity.CLIPBOARD_SERVICE);
                            if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText("url", curUrl));
                            Toast.makeText(act, "链接已复制", Toast.LENGTH_SHORT).show(); break;
                        case 6:
                            try { act.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(curUrl))); }
                            catch (Exception error) { Toast.makeText(act, "没有可用的浏览器", Toast.LENGTH_SHORT).show(); }
                            break;
                        case 7: exitToApp(false); break;
                    }
                });
                line.addView(cell, new LinearLayout.LayoutParams(0, dp(76), 1));
            }
            panel.addView(line);
        }
        showSheet(panel);
    }

    /** 桌面版切换：全部标签换 UA + 视口模式 + 当前页重载（B站等桌面版能拿到更高清晰度）。
     *  v1.94 修复：桌面 UA 必须配 useWideViewPort+overview，否则桌面页面挤在 360px 视口里横溢 */
    private void uaToggle() {
        uaDesk = !uaDesk;
        for (Tab t : tabs) {
            if (t.w == null) continue;   // v1.99：LRU 卸载过的标签无 WebView（重建时 buildWeb 自然用新 UA）
            try {
                WebSettings s = t.w.getSettings();
                s.setUserAgentString(uaDesk ? DESK_UA : MOBILE_UA);
                s.setUseWideViewPort(uaDesk);
                s.setLoadWithOverviewMode(uaDesk);
            } catch (Exception ignored) {}
        }
        WebView w = activeWeb();
        if (w != null && !NT.equals(curUrl)) w.reload();
        Toast.makeText(act, uaDesk ? "已切桌面版（当前页重载）" : "已切回手机版", Toast.LENGTH_SHORT).show();
    }

    /** ☆：收藏/取消收藏 */
    private void starToggle() {
        String u = curUrl.isEmpty() ? (activeWeb() != null ? tabs.get(active).url : "") : curUrl;
        if (u == null || u.isEmpty()) { Toast.makeText(act, "页面还没加载出来", Toast.LENGTH_SHORT).show(); return; }
        List<Object[]> list = bmData();
        if (bmHas(u)) {
            for (int i = 0; i < list.size(); i++) if (list.get(i)[1].equals(u)) { list.remove(i); break; }
            bmDirty();
            Toast.makeText(act, "已取消收藏", Toast.LENGTH_SHORT).show();
        } else {
            WebView w = activeWeb();
            String t = w == null || w.getTitle() == null || w.getTitle().isEmpty() ? prettyUrl(u) : w.getTitle();
            list.add(new Object[]{ t, u });
            bmDirty();
            Toast.makeText(act, "已收藏：" + t, Toast.LENGTH_SHORT).show();
        }
    }

    /** 🔖：收藏列表（底部弹层，原生风：图标行 + 逐行删除 + 点开新标签）。
     *  v1.99：删除一条只移除该行 + 更新标题计数——原来整个弹层重建重播滑入动画
     *  （视觉上"删一条、面板又弹一次"），还要全量序列化一次 prefs */
    private void bmManager() {
        List<Object[]> list = bmData();
        if (list.isEmpty()) { Toast.makeText(act, "还没有收藏——浏览网页时菜单里点 ☆ 收藏", Toast.LENGTH_SHORT).show(); return; }
        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xFFFFFFFF);
        panel.addView(sheetHead("收藏（" + list.size() + "）", null));
        android.widget.ScrollView sc = new android.widget.ScrollView(act);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < list.size(); i++) {
            final Object[] e = list.get(i);
            box.addView(listRow(String.valueOf(e[0]).isEmpty() ? String.valueOf(e[1]) : String.valueOf(e[0]),
                String.valueOf(e[1]), null, IC_BM,
                v -> { hideSheet(); newTab(String.valueOf(e[1])); },
                v -> {
                    bmDelete(String.valueOf(e[1]));
                    View row = (View) v.getParent();   // ✕ 的父 = 行；行从列表里摘掉，不重建弹层
                    ViewGroup rg = (ViewGroup) row.getParent();
                    rg.removeView(row);
                    if (sheetTitleV != null) sheetTitleV.setText(act.getString(R.string.bookmarks_count, bmData().size()));
                }));
        }
        sc.addView(box, new FrameLayout.LayoutParams(-1, -2));
        panel.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));
        showSheet(panel);
    }
    private void bmDelete(String u) {
        List<Object[]> list = bmData();
        for (int i = 0; i < list.size(); i++) if (list.get(i)[1].equals(u)) { list.remove(i); break; }
        bmDirty();
        Toast.makeText(act, "已取消收藏", Toast.LENGTH_SHORT).show();
    }

    /** 🕘：历史记录（底部弹层，原生风：今天/昨天/更早分组 + 逐行删除；v1.99 删除同款只摘行） */
    private void histDialog() {
        List<Object[]> list = histData();
        if (list.isEmpty()) { Toast.makeText(act, "还没有历史记录", Toast.LENGTH_SHORT).show(); return; }
        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xFFFFFFFF);
        panel.addView(sheetHead("历史记录（" + list.size() + "）", v -> {
            histData().clear();
            histDirty();
            hideSheet();
            Toast.makeText(act, "历史已清空", Toast.LENGTH_SHORT).show();
        }));
        android.widget.ScrollView sc = new android.widget.ScrollView(act);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        String today = new java.text.SimpleDateFormat("MM-dd", java.util.Locale.CHINA).format(new java.util.Date());
        String yest = new java.text.SimpleDateFormat("MM-dd", java.util.Locale.CHINA)
            .format(new java.util.Date(System.currentTimeMillis() - 86400000L));
        String lastGroup = "";
        int max = Math.min(list.size(), 50);
        for (int i = 0; i < max; i++) {
            final Object[] e = list.get(i);
            String at = String.valueOf(e[2]);
            String mmdd = at.length() >= 5 ? at.substring(0, 5) : at;
            String grp = mmdd.equals(today) ? "今天" : mmdd.equals(yest) ? "昨天" : "更早";
            if (!grp.equals(lastGroup)) {
                lastGroup = grp;
                TextView h = new TextView(act);
                h.setText(grp);
                h.setTextSize(12);
                h.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                h.setTextColor(0xFF8A97B4);
                h.setPadding(dp(20), dp(12), dp(20), dp(2));
                box.addView(h, new LinearLayout.LayoutParams(-1, -2));
            }
            box.addView(listRow(String.valueOf(e[0]), String.valueOf(e[1]) + "  ·  " + at, null, IC_CLOCK,
                v -> { hideSheet(); newTab(String.valueOf(e[1])); },
                v -> {
                    histDelete(String.valueOf(e[1]));
                    View row = (View) v.getParent();
                    ViewGroup rg = (ViewGroup) row.getParent();
                    rg.removeView(row);
                    if (sheetTitleV != null) sheetTitleV.setText(act.getString(R.string.history_count, histData().size()));
                }));
        }
        sc.addView(box, new FrameLayout.LayoutParams(-1, -2));
        panel.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));
        showSheet(panel);
    }
    private void histDelete(String u) {
        try {
            List<Object[]> list = histData();
            for (int i = 0; i < list.size(); i++) if (list.get(i)[1].equals(u)) { list.remove(i); break; }
            histDirty();
            Toast.makeText(act, "已删除该记录", Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
    }

    /** 弹层标题行（可选右侧动作文字，如"清空"）。标题 TextView 记在 sheetTitleV——
     *  v1.99 逐行删除后原地更新计数用（不重建弹层） */
    private TextView sheetTitleV;
    private android.view.View sheetHead(String titleText, android.view.View.OnClickListener action) {
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setPadding(dp(20), dp(16), dp(12), dp(6));
        TextView title = new TextView(act);
        title.setText(titleText);
        title.setTextSize(14);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF1C2B4A);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        sheetTitleV = title;
        if (action != null) {
            TextView act2 = new TextView(act);
            act2.setText("清空");
            act2.setTextSize(13);
            act2.setTextColor(0xFF3358D4);
            act2.setPadding(dp(12), dp(6), dp(12), dp(6));
            ripple(act2);
            act2.setOnClickListener(action);
            head.addView(act2);
        }
        return head;
    }

    /** 原生风列表行：图标 + 标题/副标题 + 右侧 ✕ 删除（无删除传 null）。图标为 MenuIco 字形（v2.05 去掉 emoji） */
    private LinearLayout listRow(String title, String sub, String rightText, int icoKind,
                                 android.view.View.OnClickListener click, android.view.View.OnClickListener del) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(11), dp(8), dp(11));
        ripple(row);
        android.widget.FrameLayout iw = new android.widget.FrameLayout(act);
        iw.setBackground(round(0xFFE8EEFB, 11));
        MenuIco g = new MenuIco(act, icoKind);
        iw.addView(g, new android.widget.FrameLayout.LayoutParams(dp(20), dp(20), android.view.Gravity.CENTER));
        row.addView(iw, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout mid = new LinearLayout(act);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(12), 0, dp(8), 0);
        TextView t = new TextView(act);
        t.setText(title);
        t.setTextSize(14);
        t.setTextColor(0xFF1C2B4A);
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        mid.addView(t, new LinearLayout.LayoutParams(-1, -2));
        TextView s = new TextView(act);
        s.setText(sub == null ? "" : sub);
        s.setTextSize(11);
        s.setTextColor(0xFF8A97B4);
        s.setMaxLines(1);
        s.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        mid.addView(s, new LinearLayout.LayoutParams(-1, -2));
        row.addView(mid, new LinearLayout.LayoutParams(0, -2, 1));
        if (del != null) {
            TextView x = new TextView(act);
            x.setText("✕");
            x.setTextSize(15);
            x.setTextColor(0xFFB6C0D2);
            x.setGravity(android.view.Gravity.CENTER);
            x.setPadding(dp(10), dp(10), dp(10), dp(10));
            ripple(x);
            x.setOnClickListener(del);
            row.addView(x, new LinearLayout.LayoutParams(-2, -2));
        }
        row.setOnClickListener(click);
        return row;
    }

    // ---------- 嗅探 ----------
    /** v1.99 热路径正则 static 化：recordSniff 对页面每个子资源请求跑一次，
     *  String.matches 每次都重新 Pattern.compile（媒体页几百个请求 × 2 个 pattern） */
    private static final java.util.regex.Pattern SNIFF_VIDEO_RE =
        java.util.regex.Pattern.compile(".*\\.(mp4|webm|mkv|mov|flv|avi)$");
    private static final java.util.regex.Pattern SNIFF_AUDIO_RE =
        java.util.regex.Pattern.compile(".*\\.(mp3|m4a|flac|wav)$");
    private static final java.util.regex.Pattern SNIFF_MANIFEST_RE =
        java.util.regex.Pattern.compile(".*\\.(m3u8|mpd)(\\?.*)?$");

    /** 收集页面请求里的媒体资源（请求旁观通道，mime/标题拿不到——标题由 DOM 通道补） */
    private void recordSniff(Tab tab, int generation, String url, String mime, long len, String title) {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return;
        // v1.99：先从字符串里粗取路径尾巴（跳过 scheme+host）——原来对每个请求都 Uri.parse
        // （完整解析+分配），绝大多数非媒体请求白付这笔钱，还串在资源加载路径上
        int p1 = url.indexOf('/', url.indexOf(':') + 3);
        String low = url.substring(p1 > 0 ? p1 : 0);
        int q = low.indexOf('?'); if (q >= 0) low = low.substring(0, q);
        int hh2 = low.indexOf('#'); if (hh2 >= 0) low = low.substring(0, hh2);
        low = low.toLowerCase(java.util.Locale.ROOT);
        String kind = low.endsWith(".m3u8") || low.endsWith(".mpd") ? "视频流"
            : SNIFF_VIDEO_RE.matcher(low).matches() ? "视频"
            : SNIFF_AUDIO_RE.matcher(low).matches() ? "音频"
            : mime != null && mime.startsWith("video/") ? "视频" : null;
        if (kind == null || low.endsWith(".ts") || low.endsWith(".m4s")) return;
        final String ftitle = title;
        h.post(() -> {
            if (!tabs.contains(tab) || generation != tab.generation || tab.resources.containsKey(url)
                || tab.resources.size() >= SNIFF_MAX) return;
            Sniff item = new Sniff(); item.url = url; item.kind = kind; item.len = len; item.title = ftitle;
            tab.resources.put(url, item);
            if (tab.w == activeWeb()) scheduleBadge();   // v1.99：合并刷新——媒体密集页一次连到 24 条 = 24 次 setText/relayout 合成一帧一次
        });
    }
    /** v1.99 嗅探推送通道：页面里注入的 MutationObserver 通过它把新出现的视频直链推给原生侧。
     *  取代 2.5s 一次的 DOM 轮询（evaluateJavascript 全页扫描 + JSON 往返）——轮询注入的 JS
     *  跑在页面主线程上，与页面自身的播放/动画抢帧，低端机开着浏览器就持续微卡。
     *  URL 仍走 recordSniff 的类型校验：页面伪造的也只是 DOM src 本来就能嗅到的同等内容 */
    private class SniffBridge {
        private final WebView bw;
        SniffBridge(WebView w) { bw = w; }
        @android.webkit.JavascriptInterface
        public void p(String url, String title) {
            try {
                if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return;
                final Tab tab = tabOf(bw);
                if (tab == null) return;
                recordSniff(tab, tab.generation, url, "video/html5", 0, title);
            } catch (Throwable ignored) {}
        }
        /** v2.02：页面滚动方向信号（注入的捕获阶段 scroll 监听推来；+1 向下滚 / -1 向上滚） */
        @android.webkit.JavascriptInterface
        public void y(final int dir) {
            h.post(new Runnable() { @Override public void run() { onScrollDir(dir); } });
        }
    }
    /** v1.99：注入嗅探观察者（幂等）：初扫一遍 + MutationObserver 监听 video/source 变化，
     *  变更 600ms 去抖后重扫并推送。失败静默——DOM 轮询兜底通道已删，但 onPageFinished
     *  和 SPA 导航的重扫仍在。
     *  v1.2：标题改 og:title 优先（视频名），页面标题只是兜底——之前只传 document.title，
     *  列表里显示的是整页标题，用户认不出是哪个视频 */
    private void injectSniffer(WebView w) {
        try {
            w.evaluateJavascript("(function(){try{if(window.__vdSniffOn)return;window.__vdSniffOn=1;"
                + "var send=function(){try{var vs=document.querySelectorAll('video');"
                + "var og=document.querySelector('meta[property=\"og:title\"]');"
                + "var t=(og&&og.getAttribute('content'))||document.title||'';"
                + "for(var i=0;i<vs.length;i++){var v=vs[i];var s=v.currentSrc||v.src||'';"
                + "if(!s){var so=v.querySelector('source');if(so)s=so.getAttribute('src')||''}"
                + "if(s&&s.indexOf('//')===0)s=location.protocol+s;"
                + "if(s&&s.indexOf('http')===0&&window.__vdSniff)window.__vdSniff.p(s,t)}}catch(e){}};"
                + "send();"
                + "var tm=null;var again=function(){if(tm)clearTimeout(tm);tm=setTimeout(send,600)};"
                + "new MutationObserver(again).observe(document.documentElement,"
                + "{childList:true,subtree:true,attributes:true,attributeFilter:['src']});"
                + "}catch(e){}})()", null);
        } catch (Exception ignored) {}
    }
    private void updateDlBadge() {
        if (dlBtn == null) return;
        int count = currentResources().size();
        boolean page = !isNewTab(curUrl);
        dlBtn.root.setEnabled(page);
        dlBtn.root.setAlpha(page ? 1f : .45f);
        if (dlBtn.ico != null) dlBtn.ico.setColor(page ? 0xFF3358D4 : 0xFF9AA8C4);
        dlBtn.label.setText(count > 0 ? "下载 ·" + count : "下载");
    }
    /** v1.99：角标合并刷新——嗅探记录是逐条到达的，直接每条刷一次 UI 就是
     *  媒体密集页一秒内几十次 setText/relayout；合并到下一帧只刷一次 */
    private final Runnable badgeRun = new Runnable() {
        @Override public void run() { updateDlBadge(); }
    };
    private void scheduleBadge() {
        h.removeCallbacks(badgeRun);
        h.post(badgeRun);
    }
    public void downloadResult(String message) {
        Toast.makeText(act, message, Toast.LENGTH_SHORT).show();
    }

    /** ⤓ 下载选择（底部弹层）：整页解析优先 + 嗅探直链（当前播放置顶，大小实时补） */
    /** v1.2：⤓ 直接展示嗅探到的资源列表——不再有"整页下载"选项（用户反馈整页解析
     *  下出来的经常不是想要的，认资源列表最直观） */
    private void downloadTap() {
        String u = curUrl.isEmpty() ? (active >= 0 ? tabs.get(active).url : "") : curUrl;
        if (u == null || u.isEmpty() || u.startsWith("about:") || u.startsWith("https://vd.nt")) {
            Toast.makeText(act, "先打开一个视频页面（或用新标签页里的视频站），再点 ⤓ 下载", Toast.LENGTH_SHORT).show();
            return;
        }
        sniffDialog();
    }

    /** 弹层内容包一层标题（列表类弹层共用） */
    private LinearLayout sheetTitled(String titleText, View list) {
        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xFFFFFFFF);
        TextView title = new TextView(act);
        title.setText(titleText);
        title.setTextSize(14);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF1C2B4A);
        title.setPadding(dp(20), dp(16), dp(20), dp(8));
        panel.addView(title);
        panel.addView(list, new LinearLayout.LayoutParams(-1, -2));
        return panel;
    }

    /** v1.94 嗅探列表排序：正在播放的视频置顶（SPA 站内换视频后旧资源还在列表里，避免下错） */
    private List<Sniff> sortedSniffs() {
        List<Sniff> out = new ArrayList<>(currentResources().values());   // 读写都在主线程，无需加锁
        Tab t = active >= 0 && active < tabs.size() ? tabs.get(active) : null;
        String cur = t == null ? "" : t.currentVideoUrl;
        if (!cur.isEmpty()) {
            Sniff top = null;
            for (Sniff s : out) if (s.url.equals(cur)) { top = s; break; }
            if (top != null) { out.remove(top); out.add(0, top); }
        }
        return out;
    }
    /** v1.98 列表行：主标题 = 资源记录时的视频标题（og:title），没有退回页面标题，再退回文件名；
     *  副行 = 类型 · URL 尾巴 · 大小。用户认的是视频名，不是 CDN 文件名 */
    private String sniffRowTitle(Sniff s) {
        if (s.title != null && !s.title.trim().isEmpty()) return s.title.trim();
        String pt = pageTitle();
        if (!pt.trim().isEmpty()) return pt.trim();
        return shortName(s.url);
    }
    private String sniffRow(Sniff s) {
        Tab t = active >= 0 && active < tabs.size() ? tabs.get(active) : null;
        boolean cur = t != null && s.url.equals(t.currentVideoUrl);
        String sz = s.len > 0 ? " · " + fmtLen(s.len) : (s.probing ? " · …" : "");
        return (cur ? "▶ 当前 · " : "") + s.kind + " · " + shortName(s.url) + sz + "\n" + prettyUrl(s.url);
    }
    /** v1.94 弹层打开时并发 HEAD 补 Content-Length（旁观式嗅探拿不到大小，这里按需问）。
     *  len: 0=没问过 >0=已知 -1=问过拿不到（不重试） */
    private void probeSizes(List<Sniff> list, final android.widget.BaseAdapter adp) {
        String ua = null, ref = null;
        WebView w = activeWeb();
        if (w != null) ua = w.getSettings().getUserAgentString();
        if (curUrl != null && (curUrl.startsWith("http://") || curUrl.startsWith("https://"))) ref = curUrl;
        final String fua = ua, fref = ref;
        for (final Sniff s : list) {
            if (s.len != 0 || s.probing) continue;
            // m3u8/mpd 的 Content-Length 是清单文件自己的（几十 KB），不是视频大小——显示了反而误导，不探
            if (SNIFF_MANIFEST_RE.matcher(s.url.toLowerCase(java.util.Locale.ROOT)).matches()) continue;
            s.probing = true;
            headPool.execute(new Runnable() {
                @Override public void run() {
                    s.len = headContentLength(s.url, fua, fref);
                    h.post(new Runnable() { @Override public void run() {
                        s.probing = false;
                        try { adp.notifyDataSetChanged(); } catch (Exception ignored) {}
                    } });
                }
            });
        }
    }
    /** HEAD 拿 Content-Length：带上页面 UA/Referer（部分 CDN 校验）；206 响应从 Content-Range 取总长 */
    private long headContentLength(String u, String ua, String ref) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setInstanceFollowRedirects(true);
            c.setRequestMethod("HEAD");
            if (ua != null) c.setRequestProperty("User-Agent", ua);
            if (ref != null) c.setRequestProperty("Referer", ref);
            String cookie = CookieManager.getInstance().getCookie(u);
            if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
            int code = c.getResponseCode();
            if (code / 100 == 2 || code == 206) {
                String cr = c.getHeaderField("Content-Range");
                if (cr != null) {
                    int i = cr.lastIndexOf('/');
                    if (i >= 0) { String t2 = cr.substring(i + 1).trim(); if (t2.matches("\\d+")) return Long.parseLong(t2); }
                }
                String cl = c.getHeaderField("Content-Length");
                if (cl != null && cl.trim().matches("\\d+")) return Long.parseLong(cl.trim());
            }
            return -1;
        } catch (Exception e) { return -1; }
        finally { if (c != null) c.disconnect(); }
    }

    private String pageTitle() {
        WebView w = activeWeb();
        String t = w == null ? null : w.getTitle();
        return t == null ? "" : t;
    }

    /** v1.90 下载不断流：不退出浏览器、不清页面——任务后台跑，原生 toast 告知去哪看进度 */
    private void pickDownload(String url, String title) {
        if (url == null || url.isEmpty()) return;
        String t = title == null || title.isEmpty() ? prettyUrl(url) : title;
        WebView w = activeWeb();
        host.onDownload(url, t, curUrl, w == null ? MOBILE_UA : w.getSettings().getUserAgentString());
    }

    /** v1.97 应用商店域名：网页里"打开App"按钮的最终跳转目的地，全部拦下留在网页里。
     *  注意别拦 .apk 直链——那是文件下载（DownloadListener 接手），不是商店跳转 */
    private static boolean isStoreUrl(String u) {
        String low = u.toLowerCase(java.util.Locale.ROOT);
        return low.contains("play.google.com/store")
            || low.contains("apps.apple.com/")
            || low.contains("itunes.apple.com/")
            || low.contains("app.qq.com/")
            || low.contains("a.app.qq.com")
            || low.contains("sj.qq.com/")
            || low.contains("appgallery.huawei.com")
            || low.contains("appgallery.cloud.huawei.com")
            || low.contains("heytapmobi.com")
            || low.contains("market.xiaomi.com");
    }

    /** v1.97 压掉"打开App/下载客户端"横幅：注入一段 CSS 隐藏常见站的弹层元素。
     *  选择子保守取交集（class/id 含 openapp/open-app/callapp 等关键词 + 已知站的实名类），
     *  拦不掉的不硬拦（宁可漏不可错杀正文——本 App 自己就是下载器，泛化的 download-*
     *  类名可能命中站点的真实下载区）。SPA 懒加载晚出的横幅由 2.5s 后的补刀覆盖 */
    private void injectBannerKiller(WebView w) {
        try {
            w.evaluateJavascript("(function(){try{if(document.getElementById('vd-nobanner'))return;"
                + "var s=document.createElement('style');s.id='vd-nobanner';"
                + "s.textContent='[class*=openapp],[class*=open-app],[class*=OpenApp],[class*=callapp],[class*=call-app],"
                + "[id*=openapp],[id*=open-app],.nav_openapp,.open-app-btn,.openapp-btn,.app-open-btn,.btn-openapp,"
                + ".download-pannel,.wap-download,.open-app-bar,.app-download-bar,"
                + ".guide-download,.app-guide,.float-download,.MobileAppHeader-downloadButton{display:none!important}';"
                + "document.head.appendChild(s)}catch(e){}})()", null);
        } catch (Exception ignored) { }
    }

    /** v1.90 DOM 嗅探：页面里的 <video> 元素直取 src（blob:/MSE 流拿不到，代取通道兜底）。
     *  v1.94：第一个 <video>（正在播的那个）记进 tab.currentVideoUrl，嗅探列表里置顶加"当前"标记 */
    private void sniffDom(WebView w) {
        final Tab tab = tabOf(w);
        if (tab == null) return;
        final int generation = tab.generation;
        try {
            w.evaluateJavascript("(function(){try{var o=[];var vs=document.querySelectorAll('video');"
                + "for(var i=0;i<vs.length;i++){var v=vs[i];var s=v.currentSrc||v.src||'';"
                + "if(!s){var so=v.querySelector('source');if(so)s=so.getAttribute('src')||''}"
                + "if(s&&s.indexOf('//')===0)s=location.protocol+s;"   // 协议相对地址（//host/x.mp4）补全协议，否则会被当垃圾丢掉
                + "if(s&&s.indexOf('http')===0&&o.indexOf(s)<0)o.push(s)}"
                + "var m=document.querySelector('meta[property=\"og:title\"]')||document.querySelector('meta[name=\"title\"]');"
                + "return JSON.stringify({v:o,t:m?(m.getAttribute('content')||''):(document.title||'')})}catch(e){return '{}'}})()",
                new ValueCallback<String>() {
                    @Override public void onReceiveValue(String r) {
                        try {
                            if (r == null || "null".equals(r)) return;
                            String inner = new JSONArray("[" + r + "]").getString(0);
                            JSONObject o = new JSONObject(inner);
                            JSONArray v = o.optJSONArray("v");
                            String ogTitle = o.optString("t", "");
                            if (v != null && v.length() > 0 && generation == tab.generation) {
                                tab.currentVideoUrl = v.optString(0);
                            }
                            if (v != null) {
                                for (int i = 0; i < v.length(); i++) {
                                    String u = v.optString(i);
                                    if (u != null && u.startsWith("http")) recordSniff(tab, generation, u, "video/html5", 0, ogTitle);
                                }
                            }
                        } catch (Exception ignored) { }
                    }
                });
        } catch (Exception ignored) { }
    }

    /** 网页下载入口说明。 */
    private String ntHtml() {
        return "<!doctype html><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<style>body{font-family:system-ui;background:#f6f8fc;color:#1c2b4a;padding:32px 22px;line-height:1.8}"
            + "h1{font-size:22px}p{color:#71809a}</style><h1>网页下载</h1>"
            + "<p>在上方输入作品或收藏夹链接，登录后点击下方下载按钮。"
            + "书签和历史记录可在菜单中打开。</p>";
    }
    private static String attr(String u) { return u == null ? "" : u.replace("\"", "%22").replace("<", "%3C"); }

    private static String shortName(String u) {
        String name = u;
        int q = name.indexOf('?'); if (q > 0) name = name.substring(0, q);
        int sl = name.lastIndexOf('/');
        if (sl >= 0 && sl < name.length() - 1) name = name.substring(sl + 1);
        if (name.length() > 34) name = "…" + name.substring(name.length() - 33);
        return name;
    }

    /** ⌖：嗅探结果列表（底部弹层）——下载什么由用户挑（下载后台跑，浏览器不退出）。正在播放的置顶标记"当前" */
    private void sniffDialog() {
        final List<Sniff> list = sortedSniffs();
        final String collectionUrl = biliCollectionUrl();
        if (list.isEmpty() && collectionUrl == null) {
            Toast.makeText(act, "这页还没抓到视频资源——先播放一下视频再点 ⤓", Toast.LENGTH_LONG).show();
            return;
        }
        android.widget.BaseAdapter adp = new android.widget.BaseAdapter() {
            @Override public int getCount() { return list.size(); }
            @Override public Object getItem(int pos) { return list.get(pos); }
            @Override public long getItemId(int pos) { return pos; }
            @Override public View getView(int pos, View cv, ViewGroup parent) {
                LinearLayout row = cv instanceof LinearLayout ? (LinearLayout) cv : null;
                if (row == null) {
                    row = new LinearLayout(act);
                    row.setOrientation(LinearLayout.VERTICAL);
                    row.setPadding(dp(20), dp(12), dp(20), dp(12));
                    ripple(row);
                }
                TextView l1 = row.getChildCount() > 0 ? (TextView) row.getChildAt(0) : null;
                if (l1 == null) { l1 = new TextView(act); l1.setTextSize(14); l1.setTextColor(0xFF1C2B4A); l1.setSingleLine(true); l1.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE); row.addView(l1); }
                TextView l2 = row.getChildCount() > 1 ? (TextView) row.getChildAt(1) : null;
                if (l2 == null) { l2 = new TextView(act); l2.setTextSize(11); l2.setTextColor(0xFF8A97B4); l2.setSingleLine(true); l2.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE); row.addView(l2); }
                String r = sniffRow(list.get(pos));
                int nl = r.indexOf('\n');
                l1.setText(sniffRowTitle(list.get(pos)));
                l2.setText(nl > 0 ? r.substring(0, nl) : r);
                return row;
            }
        };
        android.widget.ListView lv = new android.widget.ListView(act);
        lv.setDivider(null);
        lv.setAdapter(adp);
        lv.setOnItemClickListener((p, v2, pos, id) -> {
            hideSheet();
            pickDownload(list.get(pos).url, sniffRowTitle(list.get(pos)));
        });
        probeSizes(list, adp);
        String pt = pageTitle();
        LinearLayout panel = sheetTitled(pt.isEmpty() ? "嗅探到 " + list.size() + " 个资源 · 点一个下载"
            : "嗅探到 " + list.size() + " 个资源 · 保存为《" + pt + "》", lv);
        if (collectionUrl != null) {
            TextView batch = new TextView(act);
            batch.setText("下载整个B站合集 / 收藏夹");
            batch.setTextSize(14);
            batch.setTextColor(0xFF3358D4);
            batch.setPadding(dp(20), dp(14), dp(20), dp(14));
            ripple(batch);
            batch.setOnClickListener(v -> { hideSheet(); pickDownload(collectionUrl, pageTitle()); });
            panel.addView(batch, 1, new LinearLayout.LayoutParams(-1, -2));
        }
        showSheet(panel);
    }

    private String biliCollectionUrl() {
        try {
            android.net.Uri uri = android.net.Uri.parse(curUrl);
            String host = uri.getHost();
            if (host == null || !("bilibili.com".equalsIgnoreCase(host)
                || host.toLowerCase(java.util.Locale.ROOT).endsWith(".bilibili.com"))) return null;
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (path.contains("/video/")) {
                android.net.Uri.Builder builder = uri.buildUpon().clearQuery();
                for (String key : uri.getQueryParameterNames()) {
                    if ("share_from".equals(key)) continue;
                    for (String value : uri.getQueryParameters(key)) builder.appendQueryParameter(key, value);
                }
                return builder.appendQueryParameter("share_from", "season").build().toString();
            }
            if (path.contains("/lists/") || path.contains("/favlist") || path.contains("/channel/")
                || path.contains("/medialist/detail/")) return curUrl;
        } catch (Exception ignored) {}
        return null;
    }
    private static String fmtLen(long b) {
        if (b > 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1fMB", b / 1048576.0);
        if (b > 1024) return (b / 1024) + "KB";
        return b + "B";
    }

}
