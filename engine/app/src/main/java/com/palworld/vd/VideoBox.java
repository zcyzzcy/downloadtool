package com.palworld.vd;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
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
    private TextView titleV, curT, durT, cueV, spdBtn, fullBtn;
    private PBar pbar;
    private PlayIco playIco;

    private String title = "";
    private boolean full = false;
    private boolean sizedOnce = false;
    private long pendingResumeMs = 0;
    private long lastPosMs = 0;
    private int vw = 0, vh = 0;
    /** 页面轮询用快照（JS 桥线程直接读，不碰播放器对象） */
    private volatile String stateSnap = "idle,0,0";

    private final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
    private GestureDetector gd;
    private int savedSysVis = -1;

    public VideoBox(Activity act) {
        super(act);
        this.act = act;
        setBackgroundColor(0xFF000000);
        setVisibility(View.GONE);
        buildUi();
        bindTouch();
    }

    // ================= 对页面开放的操作（都在 UI 线程调用） =================

    public void open(File f, long startMs, String title) {
        if (!f.isFile()) { snap("error,0,0"); return; }
        this.title = title == null ? "" : title;
        titleV.setText(this.title);
        if (player == null) buildPlayer();
        sizedOnce = false;
        pendingResumeMs = Math.max(0, startMs);
        lastPosMs = Math.max(0, startMs);
        player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(f)), 0);
        player.prepare();
        player.play();
        setVisibility(View.VISIBLE);
        setKeepScreenOn(true);
        botBar.setVisibility(View.VISIBLE);
        botBar.setAlpha(1f);
        cue("加载中…");
        tick.run();
    }

    public void close() {
        saveSnap();
        if (player != null) {
            try { player.stop(); player.release(); } catch (Exception ignored) {}
            player = null;
        }
        h.removeCallbacksAndMessages(null);
        if (full) exitFull();
        setVisibility(View.GONE);
        setKeepScreenOn(false);
        snap("idle,0,0");
    }

    public void play() { if (player != null) player.play(); }
    public void pause() { if (player != null) player.pause(); }
    public void seekTo(long ms) { if (player != null) player.seekTo(Math.max(0, ms)); }
    public void setSpeed(float s) { if (player != null) player.setPlaybackSpeed(s); }

    /** 嵌入态定位：窗口像素坐标（MainActivity 已把 CSS px 换算好）。
     *  用 MarginLayoutParams 而不是 layout()：转屏/键盘等任何重排版都会把 layout() 的结果冲掉，
     *  写进布局参数才能稳定保持在舞台矩形上 */
    public void setRect(int x, int y, int w, int h) {
        if (full) return;
        if (w < 40 || h < 40) { setVisibility(View.INVISIBLE); return; }
        setVisibility(View.VISIBLE);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        lp.leftMargin = x; lp.topMargin = y; lp.width = w; lp.height = h;
        setLayoutParams(lp);
    }

    public boolean isFull() { return full; }

    public void enterFull() {
        if (full || player == null) return;
        full = true;
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
        topFull.setVisibility(View.VISIBLE);
        topFull.setAlpha(1f);
        topFull.setTranslationY(0f);
        showHud();
    }

    public void exitFull() {
        if (!full) return;
        full = false;
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
        // 具体舞台位置由页面紧跟着的 videoRect 校正；先给个居中宽度防闪白
        View p = (View) getParent();
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        lp.leftMargin = 0; lp.topMargin = 0;
        lp.width = p != null ? p.getWidth() : -1;
        lp.height = dp(210);
        setLayoutParams(lp);
    }

    public String state() { return stateSnap; }

    private void setRequested(int o) { try { act.setRequestedOrientation(o); } catch (Exception ignored) {} }

    private void snap(String s) { stateSnap = s; }
    private void saveSnap() {
        if (player == null) return;
        try {
            long pos = player.getCurrentPosition(), dur = player.getDuration();
            boolean playing = player.isPlaying();
            String st = player.getPlaybackState() == Player.STATE_ENDED ? "ended"
                : playing ? "playing" : "paused";
            snap(st + "," + pos + "," + (dur < 0 ? 0 : dur));
        } catch (Exception ignored) {}
    }

    // ================= 内部 =================

    private void buildPlayer() {
        player = new ExoPlayer.Builder(act).build();
        player.setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.addListener(new Player.Listener() {
            @Override public void onVideoSizeChanged(VideoSize vs) {
                if (vs.width > 0 && vs.height > 0) {
                    vw = vs.width; vh = vs.height;
                    if (full && !sizedOnce) {   // 全屏中途才拿到画幅：修正转屏方向
                        sizedOnce = true;
                        setRequested(vw >= vh ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                              : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
                    }
                }
            }
            @Override public void onPlaybackStateChanged(int st) {
                if (st == Player.STATE_READY) {
                    cueHide();
                    if (pendingResumeMs > 5000 && player.getDuration() > 0
                        && pendingResumeMs < player.getDuration() - 5000) {
                        player.seekTo(pendingResumeMs);
                        cue("从上次看过的地方继续");
                    }
                    pendingResumeMs = 0;
                    showHud();
                } else if (st == Player.STATE_BUFFERING) {
                    cue("加载中…");
                } else if (st == Player.STATE_ENDED) {
                    saveSnap();
                    showHud();
                }
            }
            @Override public void onIsPlayingChanged(boolean p) { setPlayIco(); if (p) showHud(); }
            @Override public void onPlayerError(PlaybackException e) {
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
            try {
                lastPosMs = player.getCurrentPosition();
                long dur = player.getDuration();
                if (dur > 0) {
                    pbar.setFrac(lastPosMs / (float) dur);
                    curT.setText(fmt(lastPosMs));
                    durT.setText(dur > 0 ? fmt(dur) : "--:--");
                }
                setPlayIco();
                saveSnap();
            } catch (Exception ignored) {}
            h.postDelayed(tick, 400);
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
        addView(topFull, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // 底栏：进度条 + 播放/时间/倍速/全屏（嵌入态常显；全屏态 3.5 秒自动藏）
        botBar = new LinearLayout(act);
        botBar.setOrientation(LinearLayout.VERTICAL);
        botBar.setPadding(dp(12), dp(10), dp(12), dp(12));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
            new int[]{0xE6000000, 0x66000000, 0x00000000});
        botBar.setBackgroundDrawable(bg);

        pbar = new PBar();
        botBar.addView(pbar, new LinearLayout.LayoutParams(-1, dp(34)));

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout playBtn = new FrameLayout(act);
        playIco = new PlayIco();
        playBtn.addView(playIco, new FrameLayout.LayoutParams(-1, -1));
        playBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            if (player != null) { if (player.isPlaying()) player.pause(); else player.play(); }
        }});
        row.addView(playBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));
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
        spdBtn = pillBtn("倍速", new Runnable() { @Override public void run() { cycleSpeed(); } });
        LinearLayout.LayoutParams sm = new LinearLayout.LayoutParams(-2, -2);
        sm.leftMargin = dp(10);
        row.addView(spdBtn, sm);
        fullBtn = pillBtn("全屏", new Runnable() { @Override public void run() {
            if (full) exitFull(); else enterFull();
        }});
        LinearLayout.LayoutParams fm = new LinearLayout.LayoutParams(-2, -2);
        fm.leftMargin = dp(8);
        row.addView(fullBtn, fm);
        botBar.addView(row, new LinearLayout.LayoutParams(-1, -2));
        addView(botBar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

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
    private static final float[] SPEEDS = {1f, 1.25f, 1.5f, 2f, 3f, 0.5f};
    private void cycleSpeed() {
        int i = 0;
        for (int k = 0; k < SPEEDS.length; k++) if (Math.abs(SPEEDS[k] - speed) < 0.01f) { i = k; break; }
        speed = SPEEDS[(i + 1) % SPEEDS.length];
        if (player != null) player.setPlaybackSpeed(speed);
        spdBtn.setText(speed == 1f ? "倍速" : trim(speed) + "x");
        cue(speed == 1f ? "1x 正常" : trim(speed) + "x");
        showHud();
    }

    private void bindTouch() {
        gd = new GestureDetector(act, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (full) { toggleHud(); return true; }
                if (player != null) { if (player.isPlaying()) player.pause(); else player.play(); }
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (player != null) { if (player.isPlaying()) player.pause(); else player.play(); }
                return true;
            }
        });
        setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (gd != null) gd.onTouchEvent(e);
                return true;
            }
        });
    }

    private void showHud() {
        botBar.animate().cancel();
        botBar.setAlpha(1f);
        botBar.setVisibility(View.VISIBLE);
        if (full) {
            topFull.animate().cancel();
            topFull.setAlpha(1f);
            topFull.setVisibility(View.VISIBLE);
            h.removeCallbacks(hudHideR);
            h.postDelayed(hudHideR, 3500);
        }
    }

    private final Runnable hudHideR = new Runnable() { @Override public void run() { hideHud(); } };

    private void hideHud() {
        // 嵌入态底栏常显（1.46 教训：藏了就把别处的线当成进度条）；只有全屏态才收顶栏+底栏
        if (!full) { showHud(); return; }
        topFull.animate().alpha(0f).translationY(-dp(56)).setDuration(200)
            .withEndAction(new Runnable() { @Override public void run() { topFull.setVisibility(View.GONE); } });
        botBar.animate().alpha(0f).translationY(dp(88)).setDuration(200)
            .withEndAction(new Runnable() { @Override public void run() { botBar.setVisibility(View.GONE); } });
    }

    private void toggleHud() {
        if (topFull.getVisibility() == View.VISIBLE && topFull.getAlpha() > 0.5f) hideHud();
        else showHud();
    }

    private void setPlayIco() { try { playIco.setPlaying(player != null && player.isPlaying()); } catch (Exception ignored) {} }

    private void cue(String t) {
        cueV.setText(t);
        cueV.setVisibility(View.VISIBLE);
        cueV.animate().cancel();
        cueV.setAlpha(1f);
        h.postDelayed(new Runnable() { @Override public void run() { cueHide(); } }, 1800);
    }
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

    /** 药丸按钮（倍速/全屏）：玻璃底、白字 */
    private TextView pillBtn(String t, final Runnable click) {
        TextView v = new TextView(act);
        v.setText(t);
        v.setTextColor(0xFFFFFFFF);
        v.setTextSize(13);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(14), dp(7), dp(14), dp(7));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x2EFFFFFF);
        g.setCornerRadius(dp(18));
        g.setStroke(dp(1), 0x45FFFFFF);
        v.setBackgroundDrawable(g);
        v.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View w) { click.run(); } });
        return v;
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static String trim(float f) {
        return (f == Math.floor(f)) ? String.valueOf((int) f) : String.format(Locale.US, "%.2f", f).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        long m = s / 60, ss = s % 60;
        return m + ":" + String.format(Locale.US, "%02d", ss);
    }

    // ================= 自绘进度条 / 播放图标（与 VideoActivity 同款） =================

    private class PBar extends View {
        private float frac = 0;
        private boolean scrub = false;
        private final Paint pTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pOn = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pThumb = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pBub = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pBubTx = new Paint(Paint.ANTI_ALIAS_FLAG);

        PBar() {
            super(VideoBox.this.act);
            pTrack.setColor(0x4DFFFFFF);
            pOn.setColor(0xFFFFFFFF);
            pThumb.setColor(0xFFFFFFFF);
            pBub.setColor(0xE6141622);
            pBubTx.setColor(0xFFFFFFFF);
            pBubTx.setTextSize(dp(12.5f));
            pBubTx.setTextAlign(Paint.Align.CENTER);
        }

        void setFrac(float f) { if (!scrub) { frac = Math.max(0f, Math.min(1f, f)); invalidate(); } }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), hgt = getHeight();
            float cy = hgt - dp(12), r = dp(1.75f);
            c.drawRoundRect(0, cy - r, w, cy + r, r, r, pTrack);
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
                    scrub = true;
                    h.removeCallbacks(hudHideR);
                    frac = x / getWidth();
                    invalidate();
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    frac = x / getWidth();
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    scrub = false;
                    if (player != null && player.getDuration() > 0) {
                        try { player.seekTo(Math.round(frac * player.getDuration())); } catch (Exception ignored) {}
                    }
                    invalidate();
                    showHud();
                    return true;
            }
            return true;
        }
    }

    private class PlayIco extends View {
        private boolean playing = false;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
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
                android.graphics.Path path = new android.graphics.Path();
                path.moveTo(cx - s * 0.7f, cy - s);
                path.lineTo(cx - s * 0.7f, cy + s);
                path.lineTo(cx + s, cy);
                path.close();
                cv.drawPath(path, p);
            }
        }
    }
}
