package com.palworld.vd;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;

import java.io.File;
import java.util.Locale;

/**
 * App 内嵌播放器 v1.48：叠在 WebView 上的原生视频组件（ExoPlayer + MediaCodec 解码）。
 * 用户手机的 WebView 解不出 HEVC 画面（连续多版"有声无画"），1.47 曾改成跳独立播放页，
 * 用户明确要求：视频必须留在 App 内——详情页里直接播、全屏是同一个播放器放大，不换页面。
 *
 * 两种形态（同一个实例无缝切换）：
 *  · 嵌入态：页面把舞台矩形容过来（videoRect），播放器贴在那儿，底栏控制条常显；
 *  · 全屏态：铺满整个窗口、按视频画幅转屏、顶栏（返回/标题）+底栏 3.5 秒自动隐藏。
 * 页面通过 AppBridge 驱动：videoOpen/videoRect/videoPlay/videoPause/videoSeek/
 * videoSpeed/videoFull/videoClose，轮询 videoState() 拿 "状态,位置ms,总长ms"（连播/进度记忆由页面管）。
 */
public class VideoBox extends FrameLayout {

    private final Activity act;
    private ExoPlayer player;
    private TextureView tex;
    private LinearLayout topFull, botBar;
    private TextView titleV, curT, durT, cueV, spdBtn;
    private View fullBtn;
    private View previousEpisode, nextEpisode;
    private int playlistIndex, playlistCount = 1;
    private Runnable previousAction, nextAction;

    public void setPlaylist(String name, int index, int count, Runnable previous, Runnable next) {
        if (!name.equals(curName)) return;
        playlistIndex = index;
        playlistCount = Math.max(1, count);
        previousAction = previous;
        nextAction = next;
        updatePlaylistControls();
        previousEpisode.setEnabled(index > 0);
        nextEpisode.setEnabled(index < count - 1);
        previousEpisode.setAlpha(index > 0 ? 1f : 0.4f);
        nextEpisode.setAlpha(index < count - 1 ? 1f : 0.4f);
    }

    private void updatePlaylistControls() {
        int visibility = full && playlistCount > 1 ? View.VISIBLE : View.GONE;
        previousEpisode.setVisibility(visibility);
        nextEpisode.setVisibility(visibility);
    }

    private void stepEpisode(int direction) {
        if (playlistIndex + direction < 0 || playlistIndex + direction >= playlistCount) {
            cue(direction < 0 ? "已经是第一个视频" : "已经是最后一个视频");
            return;
        }
        Runnable action = direction < 0 ? previousAction : nextAction;
        if (action != null) action.run();
    }
    private FullIco fullIco;
    private LinearLayout speedPanel;
    private android.widget.ScrollView speedMenu;
    private boolean holdingSpeed;
    private PBar pbar;
    private PlayIco playIco;

    private String title = "";
    private boolean full = false;
    private boolean sizedOnce = false;
    private long pendingResumeMs = 0;
    private long lastPosMs = 0;
    private int vw = 0, vh = 0;
    /** 最近一次嵌入态舞台矩形：退全屏时先回到这儿，不用等页面重新容矩形过来 */
    private int lastX = 0, lastY = 0, lastW = 0, lastH = 0;
    private boolean hasRect = false;
    /** 页面轮询用快照（JS 桥线程直接读，不碰播放器对象） */
    private volatile String stateSnap = "idle,0,0,";
    /** v1.69：出错后锁定 error 快照——tick 每 400ms 的 saveSnap 会把 error 盖成 paused，
     *  页面 400ms 轮询必然错过错误（连播/重试全失效）。open/成功 READY 才解锁 */
    private volatile boolean errored = false;

    private final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
    private GestureDetector gd;
    private int savedSysVis = -1;
    private boolean embHide = false;   // 嵌入态底栏显隐（v1.63 修正）：单击视频切换——只藏播放器控制条，系统状态栏不动
    private java.io.File curVideo = null;
    private String curName = "";

    public VideoBox(Activity act) {
        super(act);
        this.act = act;
        setBackgroundColor(0xFF000000);
        setVisibility(View.GONE);
        buildUi();
        bindTouch();
    }

    // ================= 对页面开放的操作（都在 UI 线程调用） =================

    public void open(File f, long startMs, String title, String logicalName) {
        if (!f.isFile()) { snap("error,0,0"); return; }
        this.title = title == null ? "" : title;
        titleV.setText(this.title);
        restoreHeldSpeed();
        curVideo = f;
        curName = logicalName;
        tex.setAlpha(0f);
        snap("loading,0,0");
        playlistCount = 1;
        previousAction = nextAction = null;
        previousEpisode.setVisibility(View.GONE);
        nextEpisode.setVisibility(View.GONE);
        if (player == null) buildPlayer();
        sizedOnce = false;
        pendingResumeMs = Math.max(0, startMs);
        lastPosMs = Math.max(0, startMs);
        speed = PlaybackMath.speed(act.getSharedPreferences("vd", 0).getString("vd_speed_n", "1"));
        player.setPlaybackSpeed(speed);
        spdBtn.setText(speed == 1f ? "倍速" : trim(speed) + "x");
        pbar.setFrac(0);
        pbar.setBuf(0);
        curT.setText(getContext().getString(R.string.player_initial_time));
        durT.setText("--:--");
        errored = false;
        loadMedia();
        player.prepare();
        player.play();
        setVisibility(View.VISIBLE);
        setKeepScreenOn(true);
        botBar.setVisibility(View.VISIBLE);
        botBar.setAlpha(1f);
        speedMenu.setVisibility(View.GONE);
        cue("加载中…");
        tick.run();
    }

    /** 装载当前视频，续播位置用 pendingResumeMs */
    private void loadMedia() {
        if (player == null || curVideo == null) return;
        MediaItem.Builder b = new MediaItem.Builder().setUri(android.net.Uri.fromFile(curVideo));
        player.setMediaItem(b.build(), Math.max(0, pendingResumeMs));
    }

    public String curName() { return curName; }

    public void close() {
        restoreHeldSpeed();
        curName = "";
        saveSnap();
        if (player != null) {
            try { player.stop(); player.release(); } catch (Exception ignored) {}
            player = null;
        }
        h.removeCallbacksAndMessages(null);
        if (full) exitFull();
        setEmbHide(false);   // 离开详情页时把状态栏/底栏还回去（ immersive 残留会带去别的页面 ）
        setVisibility(View.GONE);
        setKeepScreenOn(false);
        snap("idle,0,0");
    }

    public void play() { if (player != null) { setKeepScreenOn(true); player.play(); tick.run(); } }   // v1.99：tick 可能已停表（暂停/后台），播放时拉起
    public void pause() { setKeepScreenOn(false); restoreHeldSpeed(); if (player != null) { player.pause(); cueHide(); saveSnap(); showHud(); } }
    public void seekTo(long ms) { if (player != null) player.seekTo(Math.max(0, ms)); }
    public void setSpeed(float s) { setSpeedSel(PlaybackMath.speed(String.valueOf(s))); }

    /** v1.99：宿主 onPause 时停表——tick 是 10Hz→4Hz 的定时唤醒，退后台还跑纯属耗电
     *  （以前 MainActivity.onPause 只 pause 播放器，tick 循环照转直到销毁）。
     *  回前台由用户点播放（play() → tick.run()）自然恢复 */
    public void stopTick() { h.removeCallbacks(tick); }

    /** 嵌入态定位：窗口像素坐标（MainActivity 已把 CSS px 换算好）。
     *  用 MarginLayoutParams 而不是 layout()：转屏/键盘等任何重排版都会把 layout() 的结果冲掉，
     *  写进布局参数才能稳定保持在舞台矩形上 */
    public void setRect(int x, int y, int w, int h) {
        if (full) return;
        if (w < 40 || h < 40) return;   // 异常矩形直接忽略（不再隐身：1.48 隐身后声音还在，成了"有声无画"）
        // 页面每 400ms 轮询重发同一个矩形：原值没变就别 setLayoutParams（每次都是一轮全量重排版）
        if (hasRect && lastX == x && lastY == y && lastW == w && lastH == h
            && getLayoutParams() != null && getVisibility() == View.VISIBLE) return;
        setVisibility(View.VISIBLE);
        lastX = x; lastY = y; lastW = w; lastH = h; hasRect = true;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        if (lp == null) return;   // 还没挂上布局树就先记下矩形，attach 后自然生效
        lp.leftMargin = x; lp.topMargin = y; lp.width = w; lp.height = h;
        setLayoutParams(lp);
    }

    public boolean isFull() { return full; }

    public void enterFull() {
        if (full || player == null) return;
        full = true;
        updatePlaylistControls();
        // 跟视频画幅转屏（竖视频竖屏、横视频横屏）；configChanges 已带旋转，Activity 不重建
        setRequested(vw >= vh ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                              : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        View dec = act.getWindow().getDecorView();
        if (savedSysVis < 0) savedSysVis = dec.getSystemUiVisibility();
        dec.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        lp.leftMargin = 0; lp.topMargin = 0; lp.width = -1; lp.height = -1;
        setLayoutParams(lp);
        fullIco.setFull(true);
        topFull.setVisibility(View.VISIBLE);
        topFull.setAlpha(1f);
        topFull.setTranslationY(0f);
        showHud();
    }

    public void exitFull() {
        if (!full) return;
        restoreHeldSpeed();
        full = false;
        updatePlaylistControls();
        embHide = false;   // 全屏的退出态 = 正常嵌入（底栏可见、系统栏还原），不吃嵌入沉浸的残留状态
        setRequested(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);   // 回到 App 常规竖屏
        if (savedSysVis >= 0) {
            act.getWindow().getDecorView().setSystemUiVisibility(savedSysVis);
            savedSysVis = -1;
        }
        topFull.setVisibility(View.GONE);
        h.removeCallbacks(hudHideR);
        botBar.animate().cancel();
        botBar.setTranslationY(0f);
        botBar.setAlpha(1f);
        botBar.setVisibility(View.VISIBLE);
        fullIco.setFull(false);
        // 直接回到最近的舞台矩形（返回键/‹ 退全屏时页面收不到通知，靠这个不闪不错位；
        // 页面轮询到全屏标志归零后还会再容一次矩形过来校正）
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        if (hasRect) {
            lp.leftMargin = lastX; lp.topMargin = lastY; lp.width = lastW; lp.height = lastH;
        } else {
            View p = (View) getParent();
            lp.leftMargin = 0; lp.topMargin = 0;
            lp.width = p != null ? p.getWidth() : -1;
            lp.height = dp(210);
        }
        setLayoutParams(lp);
    }

    /** v1.63 修正：嵌入态单击视频只收起播放器自己的底栏（进度条/按钮），
     *  手机系统状态栏/导航栏不动——v1.59 连系统栏一起藏是理解偏了。全屏模式不受影响 */
    private void setEmbHide(boolean on) {
        if (embHide == on) return;
        embHide = on;
        if (on) {
            botBar.animate().cancel();
            botBar.setVisibility(View.GONE);
        } else {
            botBar.setTranslationY(0f);
            botBar.setAlpha(1f);
            botBar.setVisibility(View.VISIBLE);
        }
    }

    /** 轮询快照末尾带全屏标志和画幅："状态,位置ms,总长ms,full,宽,高"——页面据此感知原生侧退了全屏，
     *  并按视频画幅（竖/横）调整详情页舞台的宽高比 */
    public String state() {
        String snapshot = stateSnap;
        int nameStart = snapshot.lastIndexOf(',');
        return snapshot.substring(0, nameStart) + (full ? ",1" : ",0") + "," + vw + "," + vh
            + "," + snapshot.substring(nameStart + 1);
    }

    private void setRequested(int o) { try { act.setRequestedOrientation(o); } catch (Exception ignored) {} }

    private void snap(String s) { stateSnap = s + "," + android.net.Uri.encode(curName); }
    private void saveSnap() {
        if (player == null) return;
        if (errored) return;   // error 快照不许被定时刷成 paused（页面靠它做连播/重试决策）
        try {
            long pos = player.getCurrentPosition(), dur = player.getDuration();
            boolean playing = player.isPlaying();
            String st = player.getPlaybackState() == Player.STATE_ENDED ? "ended"
                : player.getPlayWhenReady() && player.getPlaybackState() == Player.STATE_BUFFERING ? "loading" : playing ? "playing" : "paused";
            snap(st + "," + pos + "," + (dur < 0 ? 0 : dur));
        } catch (Exception ignored) {}
    }

    // ================= 内部 =================

    /** 画面按视频宽高比装进组件（留黑边），不拉伸：舞台/屏幕是 16:10 或 20:9，
     *  抖音类竖视频是 9:16，直接铺满会把人拉宽拉瘦（1.48 的变形问题） */
    private void fitTex() {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tex.getLayoutParams();
        int W = getWidth(), H = getHeight();
        int w, h;
        if (W <= 0 || H <= 0 || vw <= 0 || vh <= 0) { w = -1; h = -1; }   // 画幅未知：先铺满（拿到画幅立刻重排）
        else if ((float) vw / vh > (float) W / H) { w = W; h = Math.max(1, Math.round(W * (float) vh / vw)); }
        else { h = H; w = Math.max(1, Math.round(H * (float) vw / vh)); }
        if (lp.width != w || lp.height != h) {
            lp.width = w; lp.height = h; lp.gravity = Gravity.CENTER;
            tex.setLayoutParams(lp);
        }
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (speedMenu != null) {
            FrameLayout.LayoutParams menuLp = (FrameLayout.LayoutParams) speedMenu.getLayoutParams();
            menuLp.height = Math.max(dp(64), Math.min(dp(290), h - dp(112)));
            speedMenu.setLayoutParams(menuLp);
        }
        fitTex();   // 嵌入态贴舞台/全屏铺满，尺寸一变就按新尺寸重排画面
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private void buildPlayer() {
        // v1.99：本地文件不需要默认 LoadControl 的 50 秒前向缓冲（高码率下白占上百 MB 配额），
        // 15 秒足够流畅，起播/seek 反而更快
        player = new ExoPlayer.Builder(act)
            .setLoadControl(new androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(15000, 15000, 1500, 3000)
                .build())
            .build();
        player.setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.addListener(new Player.Listener() {
            @Override public void onRenderedFirstFrame() {
                tex.setAlpha(1f);
            }
            @Override public void onVideoSizeChanged(VideoSize vs) {
                if (vs.width > 0 && vs.height > 0) {
                    // 带旋转元数据的视频（手机竖拍常见）：解码器没吃掉旋转时 w/h 是编码方向，
                    // 显示画幅要互换，否则竖视频会被当横视频量画幅（舞台适配 + fitTex 全用这对数）
                    boolean swap = vs.unappliedRotationDegrees == 90 || vs.unappliedRotationDegrees == 270;
                    vw = swap ? vs.height : vs.width;
                    vh = swap ? vs.width : vs.height;
                    fitTex();   // 画幅到手：按宽高比重排画面（不拉伸）
                    if (full && !sizedOnce) {   // 全屏中途才拿到画幅：修正转屏方向
                        sizedOnce = true;
                        setRequested(vw >= vh ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                              : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
                    }
                }
            }
            @Override public void onPlaybackStateChanged(int st) {
                if (st == Player.STATE_READY) {
                    errored = false;   // 成功就绪：解除 error 快照锁定
                    cueHide();
                    // v1.99：loadMedia 已经把起始位置传给 setMediaItem，正常不需要再 seek——
                    // 只有位置真不符（>500ms）才兜底补一次，避免每次续播都白白多一次
                    // 解码器重初始化 + 缓冲 flush（起播延迟近乎翻倍的元凶）
                    if (pendingResumeMs > 5000 && player.getDuration() > 0
                        && pendingResumeMs < player.getDuration() - 5000
                        && Math.abs(player.getCurrentPosition() - pendingResumeMs) > 500) {
                        player.seekTo(pendingResumeMs);
                        cue("从上次看过的地方继续");
                    }
                    pendingResumeMs = 0;
                    showHud();
                    tick.run();   // v1.99：READY 也可能是从 BUFFERING 恢复（tick 已停），拉起
                } else if (st == Player.STATE_BUFFERING) {
                    scrubCue("正在缓冲…");
                    tick.run();
                } else if (st == Player.STATE_ENDED) {
                    setKeepScreenOn(false);
                    saveSnap();
                    showHud();
                }
            }
            @Override public void onIsPlayingChanged(boolean p) {
                setPlayIco();
                if (!p) restoreHeldSpeed();
                showHud(); saveSnap();
                if (p) tick.run();
            }
            @Override public void onPlayerError(PlaybackException e) {
                errored = true;
                setKeepScreenOn(false);
                snap("error," + lastPosMs + ",0");
                cue("播放失败：" + (e.getErrorCodeName() == null ? "未知错误" : e.getErrorCodeName()));
            }
        });
        player.setVideoTextureView(tex);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            h.removeCallbacks(tick);
            if (player == null) return;
            if (!errored) {   // 出错态冻结界面进度（getCurrentPosition 会归零，别把 lastPosMs 冲掉）
                try {
                    lastPosMs = player.getCurrentPosition();
                    long dur = player.getDuration();
                    if (dur > 0) {
                        pbar.setFrac(lastPosMs / (float) dur);
                        pbar.setBuf(player.getBufferedPosition() / (float) dur);
                        if (!pbar.scrub && swipeMode != SWIPE_SEEK) {
                            String current = fmt(lastPosMs);
                            if (!current.contentEquals(curT.getText())) curT.setText(current);
                        }
                        String ds = fmt(dur);
                        if (!ds.contentEquals(durT.getText())) durT.setText(ds);   // 时长 READY 后就不变，别每拍都 setText
                    }
                    setPlayIco();
                    saveSnap();
                } catch (Exception ignored) {}
            }
            // v1.99：只在真播放/缓冲时重排（250ms 一拍足够页面 400ms 轮询和肉眼进度条）——
            // 以前 100ms 常驻自跳：暂停、出错、退后台都照转，10Hz 唤醒纯耗电
            boolean active = false;
            try { active = !errored && (player.isPlaying() || player.getPlayWhenReady() && player.getPlaybackState() == Player.STATE_BUFFERING); } catch (Exception ignored) {}
            if (active) h.postDelayed(tick, 250);
        }
    };

    private void buildUi() {
        tex = new TextureView(act);
        addView(tex, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        // 全屏顶栏：返回 + 标题（嵌入态不显示——详情页自己有标题信息区）
        topFull = new LinearLayout(act);
        topFull.setOrientation(LinearLayout.HORIZONTAL);
        topFull.setGravity(Gravity.CENTER_VERTICAL);
        topFull.setPadding(dp(10), dp(12), dp(14), dp(18));
        topFull.setVisibility(View.GONE);
        TextView back = txtBtn("‹", 24, new Runnable() { @Override public void run() { exitFull(); } });
        topFull.addView(back);
        titleV = new TextView(act);
        titleV.setTextColor(0xFFFFFFFF);
        titleV.setTextSize(15);
        titleV.setSingleLine(true);
        titleV.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleV.setPadding(dp(10), 0, 0, 0);
        titleV.setShadowLayer(dp(3), 0, dp(1), 0x99000000);
        topFull.addView(titleV, new LinearLayout.LayoutParams(0, -2, 1f));
        previousEpisode = icoCtrl(new SkipIco(false), () -> stepEpisode(-1));
        nextEpisode = icoCtrl(new SkipIco(true), () -> stepEpisode(1));
        previousEpisode.setContentDescription("上一个视频"); nextEpisode.setContentDescription("下一个视频");
        previousEpisode.setVisibility(View.GONE); nextEpisode.setVisibility(View.GONE);
        topFull.addView(previousEpisode, new LinearLayout.LayoutParams(dp(44), dp(44)));
        topFull.addView(nextEpisode, new LinearLayout.LayoutParams(dp(44), dp(44)));
        addView(topFull, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // 底栏：进度条 + 播放/时间/倍速/全屏（嵌入态常显；全屏态 3.5 秒自动藏）
        botBar = new LinearLayout(act);
        botBar.setOrientation(LinearLayout.VERTICAL);
        botBar.setPadding(dp(12), dp(10), dp(12), dp(12));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
            new int[]{0xE6000000, 0x66000000, 0x00000000});
        botBar.setBackgroundDrawable(bg);

        pbar = new PBar();
        botBar.addView(pbar, new LinearLayout.LayoutParams(-1, dp(48)));

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout playBtn = new FrameLayout(act);
        playIco = new PlayIco();
        playBtn.addView(playIco, new FrameLayout.LayoutParams(-1, -1));
        playBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            togglePlayback();
        }});
        row.addView(playBtn, new LinearLayout.LayoutParams(dp(44), dp(44)));
        curT = white(13.5f);
        curT.setFontFeatureSettings("tnum");
        LinearLayout.LayoutParams tm = new LinearLayout.LayoutParams(-2, -2);
        tm.leftMargin = dp(10);
        row.addView(curT, tm);
        TextView slash = white(13.5f);
        slash.setText("/");
        slash.setAlpha(0.5f);
        LinearLayout.LayoutParams sm0 = new LinearLayout.LayoutParams(-2, -2);
        sm0.leftMargin = dp(4);
        row.addView(slash, sm0);
        durT = white(13.5f);
        durT.setAlpha(0.6f);
        durT.setFontFeatureSettings("tnum");
        LinearLayout.LayoutParams dm = new LinearLayout.LayoutParams(-2, -2);
        dm.leftMargin = dp(4);
        row.addView(durT, dm);
        row.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));
        // v2.53：倍速/全屏按钮按 B站样式微小化——纯白小字/小图标，不再用药丸底
        spdBtn = txtCtrl("倍速", new Runnable() { @Override public void run() { toggleSpeedPanel(); } });
        LinearLayout.LayoutParams sm = new LinearLayout.LayoutParams(-2, -2);
        sm.leftMargin = dp(8);
        row.addView(spdBtn, sm);
        fullIco = new FullIco();
        fullBtn = icoCtrl(fullIco, new Runnable() { @Override public void run() {
            if (full) exitFull(); else enterFull();
        }});
        LinearLayout.LayoutParams fm = new LinearLayout.LayoutParams(dp(44), dp(44));
        fm.leftMargin = dp(6);
        row.addView(fullBtn, fm);
        botBar.addView(row, new LinearLayout.LayoutParams(-1, -2));
        addView(botBar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        // 倍速可选列表（B站式竖排面板，从倍速键上方弹出）
        speedPanel = new LinearLayout(act);
        speedPanel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable spBg = new GradientDrawable();
        spBg.setColor(0xF21A1D26);
        spBg.setCornerRadius(dp(14));
        speedPanel.setBackgroundDrawable(spBg);
        speedPanel.setPadding(dp(6), dp(5), dp(6), dp(5));
        for (final float s : SPEEDS) {
            TextView it = white(13.5f);
            it.setText(getContext().getString(R.string.player_speed_value, trim(s)));
            it.setGravity(Gravity.CENTER);
            it.setPadding(dp(26), dp(9), dp(26), dp(9));
            it.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { setSpeedSel(s); } });
            speedPanel.addView(it, new LinearLayout.LayoutParams(-1, -2));
        }
        FrameLayout.LayoutParams splp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        splp.rightMargin = dp(12);
        splp.bottomMargin = dp(104);
        speedMenu = new android.widget.ScrollView(act);
        speedMenu.setFillViewport(false); speedMenu.setVerticalScrollBarEnabled(true);
        speedMenu.setScrollbarFadingEnabled(false);
        speedMenu.setContentDescription("播放倍速，可上下滑动查看更多选项");
        speedMenu.addView(speedPanel);
        speedMenu.setVisibility(View.GONE);
        addView(speedMenu, splp);

        cueV = new TextView(act);
        cueV.setTextColor(0xFFFFFFFF);
        cueV.setTextSize(14);
        cueV.setPadding(dp(20), dp(11), dp(20), dp(11));
        GradientDrawable cb = new GradientDrawable();
        cb.setColor(0xCC1A1D26);
        cb.setCornerRadius(dp(22));
        cueV.setBackgroundDrawable(cb);
        cueV.setVisibility(View.GONE);
        cueV.setMaxWidth(dp(300));
        cueV.setGravity(Gravity.CENTER);
        addView(cueV, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
    }

    private float speed = 1f;
    /** 倍速列表从大到小排（B站习惯：常用的 2x/3x 在上面） */
    private static final float[] SPEEDS = {3f, 2f, 1.5f, 1.25f, 1f, 0.75f, 0.5f};

    private void toggleSpeedPanel() {
        if (speedMenu.getVisibility() == View.VISIBLE) { speedMenu.setVisibility(View.GONE); return; }
        for (int i = 0; i < speedPanel.getChildCount(); i++) {
            TextView tv = (TextView) speedPanel.getChildAt(i);
            tv.setTextColor(Math.abs(SPEEDS[i] - speed) < 0.01f ? 0xFF6E9BFF : 0xFFC9CFDA);
        }
        speedMenu.setVisibility(View.VISIBLE);
        speedMenu.post(() -> {
            int selected = 0;
            for (int i = 0; i < SPEEDS.length; i++) if (Math.abs(SPEEDS[i] - speed) < 0.01f) selected = i;
            View item = speedPanel.getChildAt(selected);
            speedMenu.scrollTo(0, Math.max(0, item.getTop() - (speedMenu.getHeight() - item.getHeight()) / 2));
        });
        showHud();
        h.removeCallbacks(hudHideR);   // 再撤掉 showHud 挂的自动收定时：面板开着时 HUD/面板不许消失
    }

    private void setSpeedSel(float s) {
        restoreHeldSpeed();
        speed = PlaybackMath.speed(String.valueOf(s));
        act.getSharedPreferences("vd", 0).edit().putString("vd_speed_n", String.valueOf(speed)).apply();
        if (player != null) player.setPlaybackSpeed(speed);
        spdBtn.setText(speed == 1f ? "倍速" : trim(speed) + "x");
        speedMenu.setVisibility(View.GONE);
        cue(speed == 1f ? "1x 正常" : trim(speed) + "x");
        showHud();
    }

    // ================= 手势：单击切控制条 / 双击暂停 / 横滑 seek =================

    private static final int SWIPE_NONE = 0, SWIPE_SEEK = 1, SWIPE_EPISODE = 2;
    private int swipeMode = SWIPE_NONE;
    private float downX = 0, downY = 0;
    private long scrubFromMs = 0, scrubTargetMs = 0;

    /** 横滑改播放进度：横轴整屏对应 min(总长, 2 分钟)，拖动中气泡显示目标时间，松手落点 */
    private void handleSeekTouch(MotionEvent e) {
        if (player == null) return;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                swipeMode = SWIPE_NONE;
                downX = e.getX(); downY = e.getY();
                scrubFromMs = player.getCurrentPosition();
                scrubTargetMs = scrubFromMs;
                break;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (swipeMode == SWIPE_NONE && player.getDuration() > 0 && Math.abs(dx) > dp(16) && Math.abs(dx) > Math.abs(dy) * 1.3f) {
                    swipeMode = SWIPE_SEEK;
                    h.removeCallbacks(hudHideR);
                }
                if (swipeMode == SWIPE_NONE && playlistCount > 1
                    && Math.abs(dy) > dp(20) && Math.abs(dy) > Math.abs(dx) * 1.5f) {
                    swipeMode = SWIPE_EPISODE;
                    h.removeCallbacks(hudHideR);
                }
                if (swipeMode == SWIPE_SEEK) {
                    long dur = player.getDuration();
                    if (dur <= 0) return;
                    scrubTargetMs = PlaybackMath.swipe(scrubFromMs, dur, dx, getWidth());
                    scrubCue(fmt(scrubTargetMs) + " / " + fmt(dur));
                }
                break;
            }
            case MotionEvent.ACTION_UP:
                if (swipeMode == SWIPE_EPISODE) {
                    swipeMode = SWIPE_NONE;
                    if (Math.abs(e.getY() - downY) > dp(64)) stepEpisode(e.getY() < downY ? 1 : -1);
                    showHud();
                } else if (swipeMode == SWIPE_SEEK) {
                    swipeMode = SWIPE_NONE;
                    try { player.seekTo(scrubTargetMs); } catch (Exception ignored) {}
                    cue(fmt(scrubTargetMs));
                    showHud();
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                swipeMode = SWIPE_NONE;
                cueHide();
                showHud();
                break;
        }
    }

    /** 拖动中的时间气泡：不走 cue() 的 1.8 秒自动隐藏（拖多久亮多久），松手再由 cue() 收尾 */
    private void scrubCue(String t) {
        cueV.setText(t);
        cueV.setVisibility(View.VISIBLE);
        cueV.animate().cancel();
        cueV.setAlpha(1f);
    }

    private void bindTouch() {
        gd = new GestureDetector(act, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                // 全屏单击 = 唤出/收起 HUD；嵌入态单击 = 切换沉浸（v1.59 用户修正：点一下藏状态栏和底栏，
                // 再点一下显回来——不要最大化；全屏仍走 ⛶ 按钮）
                return performClick();
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                togglePlayback(); return true;
            }
            @Override public void onLongPress(MotionEvent e) {
                if (player == null || swipeMode != SWIPE_NONE || !player.isPlaying()) return;
                holdingSpeed = true; player.setPlaybackSpeed(Math.max(2f, speed));
                scrubCue("长按加速 · " + trim(Math.max(2f, speed)) + "x");
            }
        });
        setClickable(true);
        setContentDescription("视频画面，单击切换控制栏，双击播放或暂停");
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (gd != null) gd.onTouchEvent(e);
        if (holdingSpeed && e.getActionMasked() == MotionEvent.ACTION_MOVE
            && (Math.abs(e.getX()-downX) > dp(16) || Math.abs(e.getY()-downY) > dp(16))) restoreHeldSpeed();
        handleSeekTouch(e);
        if (swipeMode != SWIPE_NONE || e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL)
            restoreHeldSpeed();
        return true;
    }

    private void togglePlayback() {
        if (player == null) return;
        restoreHeldSpeed();
        if (player.getPlayWhenReady()) pause(); else play();
        showHud(); saveSnap();
    }
    private void restoreHeldSpeed() {
        if (!holdingSpeed) return;
        holdingSpeed = false;
        if (player != null) player.setPlaybackSpeed(speed);
        cueHide();
    }

    @Override public boolean performClick() {
        super.performClick();
        if (full) toggleHud();
        else setEmbHide(!embHide);
        return true;
    }

    private void showHud() {
        botBar.animate().cancel();
        botBar.setAlpha(1f);
        botBar.setTranslationY(0f);   // 藏的时候平移出去了，唤出必须复位（1.48 没复位，进度条半截出屏）
        botBar.setVisibility(View.VISIBLE);
        if (full) {
            topFull.animate().cancel();
            topFull.setAlpha(1f);
            topFull.setTranslationY(0f);
            topFull.setVisibility(View.VISIBLE);
            h.removeCallbacks(hudHideR);
            if (player != null && player.isPlaying()) h.postDelayed(hudHideR, 3500);
        }
    }

    private final Runnable hudHideR = new Runnable() { @Override public void run() { hideHud(); } };

    private void hideHud() {
        // 嵌入态底栏常显（1.46 教训：藏了就把别处的线当成进度条）；只有全屏态才收顶栏+底栏
        if (!full) { showHud(); return; }
        if (speedPanel != null) speedMenu.setVisibility(View.GONE);
        topFull.animate().alpha(0f).translationY(-dp(56)).setDuration(200)
            .withEndAction(new Runnable() { @Override public void run() { topFull.setVisibility(View.GONE); } });
        botBar.animate().alpha(0f).translationY(dp(88)).setDuration(200)
            .withEndAction(new Runnable() { @Override public void run() { botBar.setVisibility(View.GONE); } });
    }

    private void toggleHud() {
        if (botBar.getVisibility() == View.VISIBLE && botBar.getAlpha() > 0.5f) hideHud();
        else showHud();
    }

    private void setPlayIco() { try { playIco.setPlaying(player != null && player.isPlaying()); } catch (Exception ignored) {} }

    public void cue(String t) {   // v1.62 public：进度气泡给 MainActivity 桥调
        cueV.setText(t);
        cueV.setVisibility(View.VISIBLE);
        cueV.animate().cancel();
        cueV.setAlpha(1f);
        h.removeCallbacks(cueHideR);   // 先撤旧定时：拖动 seek 的常亮气泡不会被上一次 cue 的定时误关
        h.postDelayed(cueHideR, 1800);
    }
    private final Runnable cueHideR = new Runnable() { @Override public void run() { cueHide(); } };
    private void cueHide() { cueV.setVisibility(View.GONE); }

    private TextView white(float sp) {
        TextView v = new TextView(act);
        v.setTextColor(0xFFFFFFFF);
        v.setTextSize(sp);
        return v;
    }

    /** 透明文字按钮（返回 ‹） */
    private TextView txtBtn(String t, float sp, final Runnable click) {
        TextView v = new TextView(act);
        v.setText(t);
        v.setTextColor(0xFFFFFFFF);
        v.setTextSize(sp);
        v.setGravity(Gravity.CENTER);
        v.setShadowLayer(dp(3), 0, dp(1), 0x99000000);
        v.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View w) { click.run(); } });
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        return v;
    }

    /** 纯文字小控制钮（倍速，B站式）：白字 11sp，无底色 */
    private TextView txtCtrl(String t, final Runnable click) {
        TextView v = new TextView(act);
        v.setText(t);
        v.setTextColor(0xFFE8EAF0);
        v.setTextSize(11);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(6), dp(4), dp(6), dp(4));
        v.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View w) { click.run(); } });
        return v;
    }

    /** 小图标控制钮（全屏，B站式）：无底色纯图标 */
    private View icoCtrl(View ico, final Runnable click) {
        FrameLayout b = new FrameLayout(act);
        b.addView(ico, new FrameLayout.LayoutParams(-1, -1));
        b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View w) { click.run(); } });
        return b;
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static String trim(float f) {
        return (f == Math.floor(f)) ? String.valueOf((int) f) : String.format(Locale.US, "%.2f", f).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        long hh = s / 3600, m = (s % 3600) / 60, ss = s % 60;   // v1.70：≥1h 显示 1:23:45（原来打 75:30）
        if (hh > 0) return hh + ":" + String.format(Locale.US, "%02d:%02d", m, ss);
        return m + ":" + String.format(Locale.US, "%02d", ss);
    }

    // ================= 自绘进度条 / 播放图标（与 VideoActivity 同款） =================

    private class PBar extends View {
        private float frac = 0, buf = 0;
        private boolean scrub = false;
        private final Paint pTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pBuf = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pOn = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pThumb = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pBub = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pBubTx = new Paint(Paint.ANTI_ALIAS_FLAG);

        PBar() {
            super(VideoBox.this.act);
            pTrack.setColor(0x4DFFFFFF);
            pBuf.setColor(0x73FFFFFF);
            pOn.setColor(0xFFFFFFFF);
            pThumb.setColor(0xFFFFFFFF);
            pBub.setColor(0xE6141622);
            pBubTx.setColor(0xFFFFFFFF);
            pBubTx.setTextSize(dp(12.5f));
            pBubTx.setTextAlign(Paint.Align.CENTER);
        }

        void setBuf(float f) { buf = Math.max(0f, Math.min(1f, f)); invalidate(); }

        void setFrac(float f) { if (!scrub) { frac = Math.max(0f, Math.min(1f, f)); invalidate(); } }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), hgt = getHeight();
            float cy = hgt - dp(12), r = dp(1.75f);
            c.drawRoundRect(0, cy - r, w, cy + r, r, r, pTrack);
            if (buf > 0) c.drawRoundRect(0, cy - r, w * buf, cy + r, r, r, pBuf);
            float pw = Math.max(r * 2, w * frac);
            c.drawRoundRect(0, cy - r, pw, cy + r, r, r, pOn);
            float tx = Math.max(dp(6), Math.min(w - dp(6), w * frac));
            c.drawCircle(tx, cy, scrub ? dp(7) : dp(4.5f), pThumb);
            if (scrub && player != null && player.getDuration() > 0) {
                String t = fmt((long) (frac * player.getDuration()));
                float tw = pBubTx.measureText(t);
                float bw = tw + dp(20), bh = dp(26);
                float bx = Math.max(dp(2), Math.min(w - bw - dp(2), tx - bw / 2));
                float by = Math.max(0, cy - dp(9) - bh);
                c.drawRoundRect(bx, by, bx + bw, by + bh, bh / 2, bh / 2, pBub);
                c.drawText(t, bx + bw / 2, by + bh / 2 - (pBubTx.ascent() + pBubTx.descent()) / 2, pBubTx);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float x = Math.max(0f, Math.min((float) getWidth(), e.getX()));
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (player == null || player.getDuration() <= 0) return true;
                    scrub = true;
                    h.removeCallbacks(hudHideR);
                    frac = Math.max(0f, Math.min(1f, x / Math.max(1, getWidth())));   // clamp：贴边手指会让 frac>1，seek 越界
                    invalidate();
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!scrub) return true;
                    frac = Math.max(0f, Math.min(1f, x / Math.max(1, getWidth())));
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!scrub) return true;
                    scrub = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    if (player != null && player.getDuration() > 0) {
                        try { player.seekTo(Math.round(frac * player.getDuration())); } catch (Exception ignored) {}
                    }
                    invalidate();
                    showHud();
                    performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    scrub = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    setFrac(player != null && player.getDuration() > 0
                        ? player.getCurrentPosition() / (float) player.getDuration() : 0f);
                    showHud();
                    return true;
            }
            return true;
        }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }
    }

    private class SkipIco extends View {
        private final boolean forward;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path triangle = new android.graphics.Path();

        SkipIco(boolean forward) {
            super(VideoBox.this.act);
            this.forward = forward;
            paint.setColor(0xFFFFFFFF);
            paint.setStyle(Paint.Style.FILL);
        }

        @Override protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, size = dp(7);
            canvas.save();
            if (!forward) canvas.scale(-1f, 1f, cx, cy);
            triangle.rewind();
            triangle.moveTo(cx - size, cy - size);
            triangle.lineTo(cx + size * .55f, cy);
            triangle.lineTo(cx - size, cy + size);
            triangle.close();
            canvas.drawPath(triangle, paint);
            canvas.drawRect(cx + size * .7f, cy - size, cx + size, cy + size, paint);
            canvas.restore();
        }
    }

    private class PlayIco extends View {
        private boolean playing = false;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path iconPath = new android.graphics.Path();
        PlayIco() {
            super(VideoBox.this.act);
            p.setColor(0xFFFFFFFF);
            p.setStyle(Paint.Style.FILL);
        }
        void setPlaying(boolean pg) { if (playing != pg) { playing = pg; invalidate(); } }
        @Override protected void onDraw(Canvas cv) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, s = dp(8f);
            if (playing) {
                float bw = dp(3.2f), gap = dp(2.4f);
                cv.drawRoundRect(cx - gap - bw, cy - s, cx - gap, cy + s, bw / 2, bw / 2, p);
                cv.drawRoundRect(cx + gap, cy - s, cx + gap + bw, cy + s, bw / 2, bw / 2, p);
            } else {
                android.graphics.Path path = iconPath;
                path.rewind();
                path.moveTo(cx - s * 0.7f, cy - s);
                path.lineTo(cx - s * 0.7f, cy + s);
                path.lineTo(cx + s, cy);
                path.close();
                cv.drawPath(path, p);
            }
        }
    }

    /** 全屏/收起图标：四角括号（⛶ 的自绘版，B站式细线）。收起态四角往内收一档，一眼区分 */
    private class FullIco extends View {
        private boolean full = false;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path iconPath = new android.graphics.Path();
        FullIco() {
            super(VideoBox.this.act);
            p.setColor(0xFFE8EAF0);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1.5f));
            p.setStrokeCap(Paint.Cap.ROUND);
        }
        void setFull(boolean f) { if (full != f) { full = f; invalidate(); } }
        @Override protected void onDraw(Canvas cv) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float s = full ? dp(4.5f) : dp(6f), arm = dp(4f);
            float x0 = cx - s, x1 = cx + s, y0 = cy - s, y1 = cy + s;
            android.graphics.Path path = iconPath;
                path.rewind();
            path.moveTo(x0, y0 + arm); path.lineTo(x0, y0); path.lineTo(x0 + arm, y0);
            path.moveTo(x1 - arm, y0); path.lineTo(x1, y0); path.lineTo(x1, y0 + arm);
            path.moveTo(x1, y1 - arm); path.lineTo(x1, y1); path.lineTo(x1 - arm, y1);
            path.moveTo(x0 + arm, y1); path.lineTo(x0, y1); path.lineTo(x0, y1 - arm);
            cv.drawPath(path, p);
        }
    }
}
