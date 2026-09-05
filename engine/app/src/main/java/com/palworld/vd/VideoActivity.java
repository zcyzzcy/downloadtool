package com.palworld.vd;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

/**
 * 原生视频播放页（v2 重构）：MediaPlayer + TextureView，系统解码器——图库能放的它都能放
 * （WebView 的 video 解不了 H.265 等编码，表现为有声无画黑屏）。
 * 视觉对齐主流播放器：自绘细进度条（圆角端点/缓冲段/拖拽气泡）、上下栏滑入滑出、
 * 双击两侧快退快进带快闪提示、中央大播放键、玻璃质感药丸按钮。
 * 手势对齐 B站：单击显隐控件、双击两侧±10s、双击中央播放暂停、横拖快进、
 * 左半竖拖亮度、右半竖拖音量、长按 2 倍速；进度与倍速跨视频记忆。
 */
public class VideoActivity extends Activity {

    private String[] files;
    private int idx = 0;
    private String title = "";

    private TextureView tex;
    private MediaPlayer mp;
    private Surface surf;

    private FrameLayout root;
    private LinearLayout topBar, botBar;
    private TextView titleV, curT, durT, cueV, playIco, spdBtn, fillBtn, prevB, nextB, cntV, centerPlay, flashV, spd2x;
    private PBar pbar;

    private final Handler h = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private AudioManager am;
    private float speed = 1f;
    private boolean fillMode = false;   // false=适配（完整画面留黑边，默认） true=铺满（裁切边缘）
    private int vw = 0, vh = 0;
    private boolean sizedOnce = false;
    private int lastSaveMs = 0;

    // 手势状态
    private String gmode = null;         // seek | bright | vol（null=未锁定方向）
    private long seekBaseMs = 0, seekTargetMs = -1;
    private float bright0 = 0.55f;
    private int vol0 = 0;
    private boolean holding2x = false;
    private GestureDetector gd;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("vd", MODE_PRIVATE);
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try { speed = Float.parseFloat(prefs.getString("vd_speed_n", "1")); } catch (Exception e) { speed = 1f; }
        if (speed <= 0 || speed > 4) speed = 1f;
        fillMode = "1".equals(prefs.getString("vd_fill_n", "0"));

        files = getIntent().getStringArrayExtra("files");
        idx = getIntent().getIntExtra("index", 0);
        title = getIntent().getStringExtra("title");
        if (files == null || files.length == 0) { finish(); return; }
        if (idx < 0 || idx >= files.length) idx = 0;

        buildUi();
        immersive();
        bindGestures();
        play(idx);
    }

    // ================= 播放控制 =================

    private void play(int i) {
        saveProg();
        releaseMp();
        h.removeCallbacks(tick);
        sizedOnce = false;
        idx = i;
        String name = new File(files[i]).getName();
        String disp = (title != null && title.length() > 0 && files.length == 1)
            ? title : name.replaceAll("\\.[^.]+$", "");
        titleV.setText(disp);
        cntV.setText(files.length > 1 ? (i + 1) + " / " + files.length : "");
        int pb = files.length > 1 ? View.VISIBLE : View.GONE;
        prevB.setVisibility(pb);
        nextB.setVisibility(pb);
        pbar.setFrac(0);
        pbar.setBuf(0);
        curT.setText("0:00");
        durT.setText("--:--");
        applySpeedLabel();
        try {
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
            mp.setDataSource(files[i]);
            if (surf != null && surf.isValid()) mp.setSurface(surf);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer p) {
                    cueHide();
                    durT.setText(fmt(p.getDuration()));
                    int t = progMap().optInt(baseName(files[idx]), 0) * 1000;
                    if (t > 5000 && t < p.getDuration() - 5000) {
                        p.seekTo(t);
                        cue("从上次看过的地方继续 · " + fmt(t));
                    }
                    if (speed != 1f) { try { p.setPlaybackParams(new PlaybackParams().setSpeed(speed)); } catch (Exception ignored) {} }
                    p.start();
                    setPlayIco();
                    showHud();
                    h.removeCallbacks(tick);
                    h.post(tick);
                }
            });
            mp.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                @Override public void onVideoSizeChanged(MediaPlayer p, int w, int hh) {
                    if (w > 0 && hh > 0) { vw = w; vh = hh; fitVideo(); }
                    if (!sizedOnce && w > 0) {   // 初始方向跟随视频画幅（横屏视频横着放、竖屏视频竖着放）
                        sizedOnce = true;
                        setRequestedOrientation(w >= hh
                            ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
                    }
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer p) {
                    clearProg();
                    if (idx < files.length - 1) play(idx + 1);   // 连播：图集/多视频任务
                    else { setPlayIco(); showHud(); }
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer p, int what, int extra) { fail(); return true; }
            });
            mp.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                @Override public boolean onInfo(MediaPlayer p, int what, int extra) {
                    if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) cue("缓冲中…", 0);
                    else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) cueHide();
                    return false;
                }
            });
            mp.setOnBufferingUpdateListener(new MediaPlayer.OnBufferingUpdateListener() {
                @Override public void onBufferingUpdate(MediaPlayer p, int percent) { pbar.setBuf(percent / 100f); }
            });
            mp.prepareAsync();
            cue("加载中…", 0);
            try { am.requestAudioFocus(afL, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN); } catch (Exception ignored) {}
        } catch (Exception e) {
            fail();
        }
    }

    private void releaseMp() {
        if (mp != null) {
            try { mp.stop(); } catch (Exception ignored) {}
            try { mp.release(); } catch (Exception ignored) {}
            mp = null;
        }
    }

    private void togglePlay() {
        if (mp == null) return;
        try {
            if (mp.isPlaying()) mp.pause(); else mp.start();
            setPlayIco();
            showHud();
        } catch (Exception ignored) {}
    }

    private void skip(int ms) {
        if (mp == null) return;
        try {
            int t = Math.max(0, Math.min(mp.getDuration() - 200, mp.getCurrentPosition() + ms));
            mp.seekTo(t);
            cue((ms > 0 ? "10秒 »" : "« 10秒") + "  " + fmt(t));
        } catch (Exception ignored) {}
    }

    private void step(int d) {
        if (files == null || files.length < 2) return;
        play((idx + d + files.length) % files.length);
    }

    /** 倍速列表选择（比循环点击直观：一屏看全所有档位，当前档打勾） */
    private void showSpeedMenu() {
        final float[] opts = {3f, 2f, 1.5f, 1.25f, 1f, 0.75f, 0.5f};
        String[] labels = new String[opts.length];
        int sel = 0;
        for (int i = 0; i < opts.length; i++) {
            labels[i] = trimF(opts[i]) + "x" + (opts[i] == 1f ? "（正常）" : "");
            if (opts[i] == speed) sel = i;
        }
        new AlertDialog.Builder(this)
            .setTitle("播放倍速")
            .setSingleChoiceItems(labels, sel, new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int w) {
                    speed = opts[w];
                    applySpeed();
                    prefs.edit().putString("vd_speed_n", String.valueOf(speed)).apply();
                    cue(trimF(speed) + "x");
                    showHud();
                    d.dismiss();
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private float cur() {
        try { if (mp != null && !holding2x) { float r = mp.getPlaybackParams().getSpeed(); if (r > 0) return r; } } catch (Exception ignored) {}
        return speed;
    }

    private void applySpeed() {
        boolean wasPaused = mp != null && !mp.isPlaying();
        try { if (mp != null) mp.setPlaybackParams(new PlaybackParams().setSpeed(speed)); } catch (Exception ignored) {}
        if (wasPaused && mp != null) { try { mp.pause(); } catch (Exception ignored) {} }
        applySpeedLabel();
    }

    private void applySpeedLabel() { spdBtn.setText((speed == 1f ? "倍速" : trimF(speed) + "x") + " ▾"); }

    private void toggleFill() {
        fillMode = !fillMode;
        prefs.edit().putString("vd_fill_n", fillMode ? "1" : "0").apply();
        fillBtn.setText(fillMode ? "铺满" : "适配");   // 按钮显示当前模式（此前只在进播放器时设置一次，切完不刷新）
        fitVideo();
        cue(fillMode ? "铺满（可能裁切边缘）" : "适配（完整画面）");
        showHud();
    }

    private void toggleRotate() {
        int o = getRequestedOrientation();
        setRequestedOrientation(o == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        showHud();
    }

    private void setPlayIco() {
        boolean playing = mp != null && mp.isPlaying();
        playIco.setText(playing ? "⏸" : "▶");
        centerPlay.setText(playing ? "" : "▶");
        if (playing) { centerPlay.setVisibility(View.GONE); return; }
        centerPlay.setVisibility(View.VISIBLE);
        centerPlay.setScaleX(0.7f);
        centerPlay.setScaleY(0.7f);
        centerPlay.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
    }

    private void setBright(float f) {
        try {
            android.view.Window w = getWindow();
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.screenBrightness = Math.max(0.06f, Math.min(1f, f));
            w.setAttributes(lp);
        } catch (Exception ignored) {}
    }

    /** 画面适配：直接改 TextureView 的布局尺寸（居中、黑边在根容器上）——比矩阵变换直观可靠。
     *  适配=完整画面（默认）；铺满=放大裁切铺满屏幕 */
    private void fitVideo() {
        if (vw <= 0 || vh <= 0 || tex == null) return;
        int cw = root.getWidth(), ch = root.getHeight();
        if (cw <= 0 || ch <= 0) return;
        float s = fillMode ? Math.max(cw / (float) vw, ch / (float) vh)
            : Math.min(cw / (float) vw, ch / (float) vh);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tex.getLayoutParams();
        int w = Math.max(1, Math.round(vw * s)), hh = Math.max(1, Math.round(vh * s));
        if (lp.width != w || lp.height != hh) {
            lp.width = w;
            lp.height = hh;
            lp.gravity = Gravity.CENTER;
            tex.setLayoutParams(lp);
        }
    }

    // ================= 进度记忆（与网页端共用 kv_vd_progress，按文件名记） =================

    private JSONObject progMap() {
        try { return new JSONObject(prefs.getString("kv_vd_progress", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    private void saveProg() {
        if (mp == null || files == null) return;
        try {
            int pos = mp.getCurrentPosition(), dur = mp.getDuration();
            JSONObject m = progMap();
            String k = baseName(files[idx]);
            if (dur > 0 && pos > 5000 && pos < dur - 5000) m.put(k, pos / 1000);
            else m.remove(k);
            prefs.edit().putString("kv_vd_progress", m.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void clearProg() {
        try {
            JSONObject m = progMap();
            m.remove(baseName(files[idx]));
            prefs.edit().putString("kv_vd_progress", m.toString()).apply();
        } catch (Exception ignored) {}
    }

    private static String baseName(String p) { int i = p.lastIndexOf('/'); return i >= 0 ? p.substring(i + 1) : p; }

    // ================= UI =================

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /**
     * 自绘进度条（主流播放器样式）：3.5dp 圆角细轨道 + 缓冲段 + 白色进度，
     * 平时只有一个小圆点，按住/拖拽时圆点放大并弹出时间气泡（跟着手指走）。
     */
    private class PBar extends View {
        private float frac = 0, buf = 0;
        private boolean scrub = false;
        private final Paint pTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pBuf = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pOn = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pThumb = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pBub = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pBubTx = new Paint(Paint.ANTI_ALIAS_FLAG);

        PBar(android.content.Context c) {
            super(c);
            pTrack.setColor(0x2EFFFFFF);
            pBuf.setColor(0x59FFFFFF);
            pOn.setColor(0xFFFFFFFF);
            pThumb.setColor(0xFFFFFFFF);
            pBub.setColor(0xE6141622);
            pBubTx.setColor(0xFFFFFFFF);
            pBubTx.setTextSize(dp(12.5f));
            pBubTx.setTextAlign(Paint.Align.CENTER);
        }

        void setFrac(float f) { if (!scrub) { frac = clamp01(f); invalidate(); } }
        void setBuf(float b) { buf = clamp01(b); invalidate(); }

        private float clamp01(float f) { return Math.max(0f, Math.min(1f, f)); }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), hgt = getHeight();
            float cy = hgt - dp(13);            // 轨道贴近底部，上方留气泡空间
            float r = dp(1.75f);
            c.drawRoundRect(0, cy - r, w, cy + r, r, r, pTrack);
            if (buf > 0.01f) c.drawRoundRect(0, cy - r, w * buf, cy + r, r, r, pBuf);
            float pw = Math.max(r * 2, w * frac);
            c.drawRoundRect(0, cy - r, pw, cy + r, r, r, pOn);
            float tx = Math.max(dp(6), Math.min(w - dp(6), w * frac));
            float tr = scrub ? dp(6.5f) : dp(2.75f);
            c.drawCircle(tx, cy, tr, pThumb);
            if (scrub) {   // 拖拽气泡：时间跟手指
                String t = fmt(durMs() > 0 ? (int) (frac * durMs()) : 0);
                float tw = pBubTx.measureText(t);
                float bw = tw + dp(20), bh = dp(26);
                float bx = Math.max(dp(2), Math.min(w - bw - dp(2), tx - bw / 2));
                float by = cy - dp(9) - bh;
                c.drawRoundRect(bx, by, bx + bw, by + bh, bh / 2, bh / 2, pBub);
                c.drawText(t, bx + bw / 2, by + bh / 2 - (pBubTx.ascent() + pBubTx.descent()) / 2, pBubTx);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float x = Math.max(0f, Math.min((float) getWidth(), e.getX()));
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    scrub = true;
                    h.removeCallbacks(hideHudR);
                    frac = clamp01(x / getWidth());
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    frac = clamp01(x / getWidth());
                    curT.setText(fmt((int) (frac * Math.max(0, durMs()))));
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    scrub = false;
                    int dur = durMs();
                    if (mp != null && dur > 0) {
                        try { mp.seekTo(Math.round(frac * dur)); } catch (Exception ignored) {}
                    }
                    invalidate();
                    showHud();
                    return true;
            }
            return true;
        }

        private int durMs() { try { return mp != null ? mp.getDuration() : 0; } catch (Exception e) { return 0; } }
    }

    /** 玻璃药丸按钮（底部栏：倍速/铺满/旋转/上一集/下一集） */
    private TextView pill(String t, View.OnClickListener l) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(0xFFFFFFFF);
        v.setTextSize(13);
        v.setPadding(dp(14), dp(7), dp(14), dp(7));
        v.setBackgroundDrawable(glass(19));
        v.setOnClickListener(l);
        return v;
    }

    /** 圆形图标按钮（返回/播放/上一集/下一集） */
    private TextView circle(String t, float sizeSp, int dimDp, View.OnClickListener l) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(0xFFFFFFFF);
        v.setTextSize(sizeSp);
        v.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0x2E000000);
        g.setStroke(dp(1), 0x2EFFffff);
        v.setBackgroundDrawable(g);
        v.setOnClickListener(l);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(dimDp), dp(dimDp)));
        return v;
    }

    private GradientDrawable glass(float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x2EFFFFFF);
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(dp(1), 0x2EFFFFFF);
        return g;
    }

    private static GradientDrawable roundBg(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusDp);
        return g;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        setContentView(root);

        tex = new TextureView(this);
        tex.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int hh) {
                surf = new Surface(st);
                if (mp != null) { try { mp.setSurface(surf); } catch (Exception ignored) {} }
                fitVideo();
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                if (surf != null) { try { surf.release(); } catch (Exception ignored) {} }
                surf = null;
                return true;
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int hh) { fitVideo(); }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
        });
        root.addView(tex, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b2, int ol, int ot, int or, int ob) { fitVideo(); }
        });

        // 中央提示气泡（快进/亮度/音量/倍速/缓冲）
        cueV = new TextView(this);
        cueV.setTextColor(0xFFFFFFFF);
        cueV.setTextSize(15);
        cueV.setPadding(dp(22), dp(12), dp(22), dp(12));
        cueV.setBackgroundDrawable(roundBg(0xD9141622, 24));
        cueV.setVisibility(View.GONE);
        cueV.setMaxWidth(dp(320));
        cueV.setGravity(Gravity.CENTER);
        root.addView(cueV, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        // 双击快退/快进的快闪圈（跟随点按位置）
        flashV = new TextView(this);
        flashV.setTextColor(0xFFFFFFFF);
        flashV.setTextSize(13.5f);
        flashV.setGravity(Gravity.CENTER);
        {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(0x40000000);
            g.setStroke(dp(1), 0x40FFFFFF);
            flashV.setBackgroundDrawable(g);
        }
        flashV.setVisibility(View.GONE);
        root.addView(flashV, new FrameLayout.LayoutParams(dp(76), dp(76), Gravity.CENTER));

        // 长按 2 倍速角标（屏幕下方居中）
        spd2x = new TextView(this);
        spd2x.setText("▶▶ 2x");
        spd2x.setTextColor(0xFFFFFFFF);
        spd2x.setTextSize(13);
        spd2x.setPadding(dp(16), dp(7), dp(16), dp(7));
        spd2x.setBackgroundDrawable(glass(17));
        spd2x.setVisibility(View.GONE);
        FrameLayout.LayoutParams s2 = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        s2.bottomMargin = dp(28);
        root.addView(spd2x, s2);

        // 暂停时中央大播放键（点击继续）
        centerPlay = new TextView(this);
        centerPlay.setTextColor(0xFFFFFFFF);
        centerPlay.setTextSize(32);
        centerPlay.setGravity(Gravity.CENTER);
        centerPlay.setPadding(0, dp(4), 0, 0);
        {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(0x66000000);
            g.setStroke(dp(2), 0x59FFFFFF);
            centerPlay.setBackgroundDrawable(g);
        }
        centerPlay.setVisibility(View.GONE);
        centerPlay.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { togglePlay(); } });
        root.addView(centerPlay, new FrameLayout.LayoutParams(dp(80), dp(80), Gravity.CENTER));

        // 顶栏：返回 + 标题 + 序号
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(10), dp(12), dp(14), dp(18));
        GradientDrawable tg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xB3000000, 0x00000000});
        topBar.setBackgroundDrawable(tg);
        TextView back = circle("‹", 24, 42, new View.OnClickListener() { @Override public void onClick(View v) { finish(); } });
        topBar.addView(back);
        titleV = new TextView(this);
        titleV.setTextColor(0xFFFFFFFF);
        titleV.setTextSize(15);
        titleV.setSingleLine(true);
        titleV.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleV.setPadding(dp(10), 0, 0, 0);
        topBar.addView(titleV, new LinearLayout.LayoutParams(0, -2, 1f));
        cntV = new TextView(this);
        cntV.setTextColor(0xFFFFFFFF);
        cntV.setTextSize(12.5f);
        cntV.setPadding(dp(11), dp(5), dp(11), dp(5));
        cntV.setBackgroundDrawable(roundBg(0x33000000, 16));
        topBar.addView(cntV, new LinearLayout.LayoutParams(-2, -2));
        root.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // 底栏：进度条 + 按钮排
        botBar = new LinearLayout(this);
        botBar.setOrientation(LinearLayout.VERTICAL);
        botBar.setPadding(dp(14), dp(10), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xD9000000, 0x73000000, 0x00000000});
        botBar.setBackgroundDrawable(bg);

        pbar = new PBar(this);
        botBar.addView(pbar, new LinearLayout.LayoutParams(-1, dp(36)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams w2 = new LinearLayout.LayoutParams(-2, -2);

        playIco = circle("", 19, 42, new View.OnClickListener() { @Override public void onClick(View v) { togglePlay(); } });
        prevB = pill("‹ 上一集", new View.OnClickListener() { @Override public void onClick(View v) { step(-1); } });
        nextB = pill("下一集 ›", new View.OnClickListener() { @Override public void onClick(View v) { step(1); } });
        curT = white(13.5f);
        curT.setFontFeatureSettings("tnum");
        durT = white(13.5f);
        durT.setAlpha(0.6f);
        durT.setFontFeatureSettings("tnum");
        TextView slash = white(13.5f);
        slash.setAlpha(0.45f);
        slash.setText("/");
        spdBtn = pill("倍速 ▾", new View.OnClickListener() { @Override public void onClick(View v) { showSpeedMenu(); } });
        spdBtn.setMinWidth(dp(58));
        spdBtn.setGravity(Gravity.CENTER);
        fillBtn = pill(fillMode ? "铺满" : "适配", new View.OnClickListener() { @Override public void onClick(View v) { toggleFill(); } });
        TextView rotBtn = pill("↻", new View.OnClickListener() { @Override public void onClick(View v) { toggleRotate(); } });

        row.addView(playIco, w2);
        LinearLayout.LayoutParams tm = new LinearLayout.LayoutParams(-2, -2);
        tm.leftMargin = dp(12);
        row.addView(curT, tm);
        row.addView(slash, w2);
        row.addView(durT, w2);
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        row.addView(prevB, w2);
        row.addView(nextB, w2);
        row.addView(spdBtn, w2);
        row.addView(fillBtn, w2);
        row.addView(rotBtn, w2);
        botBar.addView(row, new LinearLayout.LayoutParams(-1, -2));
        root.addView(botBar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    }

    private TextView white(float sizeSp) {
        TextView v = new TextView(this);
        v.setTextColor(0xFFFFFFFF);
        v.setTextSize(sizeSp);
        return v;
    }

    // ================= HUD / 提示 =================

    private void showHud() {
        topBar.setVisibility(View.VISIBLE);
        botBar.setVisibility(View.VISIBLE);
        topBar.animate().cancel();
        botBar.animate().cancel();
        topBar.setAlpha(1f);
        botBar.setAlpha(1f);
        topBar.setTranslationY(0f);
        botBar.setTranslationY(0f);
        h.removeCallbacks(hideHudR);
        h.postDelayed(hideHudR, 3500);
    }

    private final Runnable hideHudR = new Runnable() { @Override public void run() { hideHud(); } };

    private void hideHud() {
        topBar.animate().alpha(0f).translationY(-dp(64)).setDuration(220)
            .withEndAction(new Runnable() { @Override public void run() { topBar.setVisibility(View.GONE); } });
        botBar.animate().alpha(0f).translationY(dp(96)).setDuration(220)
            .withEndAction(new Runnable() { @Override public void run() { botBar.setVisibility(View.GONE); } });
        immersive();
    }

    private void toggleHud() {
        if (topBar.getVisibility() == View.VISIBLE && topBar.getAlpha() > 0.5f) hideHud();
        else showHud();
    }

    private void immersive() {
        View dec = getWindow().getDecorView();
        dec.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private void cue(String t) { cue(t, 900); }

    private void cue(String t, int ms) {
        cueV.setText(t);
        cueV.setVisibility(View.VISIBLE);
        cueV.animate().cancel();
        cueV.setAlpha(1f);
        h.removeCallbacks(cueHideR);
        if (ms > 0) h.postDelayed(cueHideR, ms);
    }

    private final Runnable cueHideR = new Runnable() { @Override public void run() { cueV.setVisibility(View.GONE); } };

    private void cueHide() { h.removeCallbacks(cueHideR); cueV.setVisibility(View.GONE); }

    /** 双击两侧快退/快进的快闪反馈（B站式：点哪边哪边亮） */
    private void flash(boolean forward, float x) {
        flashV.setText(forward ? "10秒 ››" : "‹‹ 10秒");
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) flashV.getLayoutParams();
        lp.leftMargin = Math.max(dp(20), Math.min(root.getWidth() - dp(96), (int) x - dp(38)));
        flashV.setLayoutParams(lp);
        flashV.setVisibility(View.VISIBLE);
        flashV.animate().cancel();
        flashV.setAlpha(1f);
        flashV.setScaleX(0.82f);
        flashV.setScaleY(0.82f);
        flashV.animate().alpha(0f).scaleX(1.15f).scaleY(1.15f).setDuration(560)
            .withEndAction(new Runnable() { @Override public void run() { flashV.setVisibility(View.GONE); } });
    }

    // ================= 手势（B站式） =================

    private void bindGestures() {
        gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { toggleHud(); return true; }

            @Override public boolean onDoubleTap(MotionEvent e) {
                int w = root.getWidth();
                if (e.getX() < w / 3f) { flash(false, e.getX()); skip(-10000); }
                else if (e.getX() > w * 2f / 3f) { flash(true, e.getX()); skip(10000); }
                else togglePlay();
                return true;
            }

            @Override public void onLongPress(MotionEvent e) {
                if (mp == null || gmode != null || !mp.isPlaying()) return;
                holding2x = true;
                try { mp.setPlaybackParams(new PlaybackParams().setSpeed(2f)); } catch (Exception ignored) {}
                spd2x.setVisibility(View.VISIBLE);
            }

            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (e1 == null || mp == null) return true;
                int w = root.getWidth(), hg = root.getHeight();
                if (gmode == null) {
                    if (holding2x) {   // 长按加速中开始拖动：先恢复正常倍速，别两个手势叠加
                        holding2x = false;
                        spd2x.setVisibility(View.GONE);
                        try { mp.setPlaybackParams(new PlaybackParams().setSpeed(speed)); } catch (Exception ignored) {}
                        cueHide();
                    }
                    float tdx = e2.getX() - e1.getX(), tdy = e2.getY() - e1.getY();
                    long dur = 0;
                    try { dur = mp.getDuration(); } catch (Exception ignored) {}
                    if (Math.abs(tdx) > 26 && Math.abs(tdx) > Math.abs(tdy) && dur > 0) {
                        gmode = "seek";
                        seekBaseMs = safePos();
                        seekTargetMs = seekBaseMs;
                    } else if (Math.abs(tdy) > 26) {
                        gmode = e1.getX() < w / 2f ? "bright" : "vol";
                        float b0 = getWindow().getAttributes().screenBrightness;
                        bright0 = (b0 > 0 && b0 <= 1) ? b0 : 0.55f;
                        try { vol0 = am.getStreamVolume(AudioManager.STREAM_MUSIC); } catch (Exception ignored) {}
                    } else return true;
                }
                if ("seek".equals(gmode)) {
                    long dur = 0;
                    try { dur = mp.getDuration(); } catch (Exception ignored) {}
                    if (dur <= 0) return true;
                    // 整屏横拖 ≈ 1.6 倍时长（B站手感）
                    seekTargetMs = Math.max(0, Math.min(dur - 300,
                        seekBaseMs + (long) ((e2.getX() - e1.getX()) / w * (dur * 1.6f))));
                    pbar.setFrac(seekTargetMs / (float) dur);
                    curT.setText(fmt((int) seekTargetMs));
                    long dd = (seekTargetMs - seekBaseMs) / 1000;
                    cue((dd >= 0 ? "+" : "") + dd + "s · " + fmt((int) seekBaseMs) + " → " + fmt((int) seekTargetMs), 0);
                } else if ("bright".equals(gmode)) {
                    float f = Math.max(0.06f, Math.min(1f, bright0 + (e1.getY() - e2.getY()) / (hg * 0.8f)));
                    setBright(f);
                    cue("☀️ 亮度 " + Math.round(f * 100) + "%", 0);
                } else if ("vol".equals(gmode)) {
                    int max = 0;
                    try { max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC); } catch (Exception ignored) {}
                    if (max <= 0) return true;
                    float f = Math.max(0f, Math.min(1f, (vol0 + (e1.getY() - e2.getY()) / (hg * 0.8f) * max) / max));
                    try { am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(f * max), 0); } catch (Exception ignored) {}
                    cue((f <= 0.001f ? "🔇" : "🔊") + " 音量 " + Math.round(f * 100) + "%", 0);
                }
                return true;
            }
        });
    }

    private int safePos() {
        try { return mp != null ? mp.getCurrentPosition() : 0; } catch (Exception e) { return 0; }
    }

    /** 手指抬起：落快进、恢复长按倍速、收提示 */
    private void endGestures() {
        if (holding2x) {
            holding2x = false;
            spd2x.setVisibility(View.GONE);
            try { if (mp != null) mp.setPlaybackParams(new PlaybackParams().setSpeed(speed)); } catch (Exception ignored) {}
        }
        if ("seek".equals(gmode) && seekTargetMs >= 0 && mp != null) {
            try { mp.seekTo((int) seekTargetMs); } catch (Exception ignored) {}
            seekTargetMs = -1;
        }
        if (gmode != null) { gmode = null; cueHide(); showHud(); }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (gd != null) gd.onTouchEvent(e);
        if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) endGestures();
        return true;
    }

    // ================= 杂项 =================

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (mp != null) {
                try {
                    int pos = mp.getCurrentPosition(), dur = mp.getDuration();
                    if (dur > 0) {
                        pbar.setFrac(pos / (float) dur);
                        curT.setText(fmt(pos));
                    }
                    setPlayIco();
                    if (mp.isPlaying() && Math.abs(pos - lastSaveMs) > 3000) { lastSaveMs = pos; saveProg(); }
                } catch (Exception ignored) {}
            }
            h.postDelayed(this, 500);
        }
    };

    private final AudioManager.OnAudioFocusChangeListener afL = new AudioManager.OnAudioFocusChangeListener() {
        @Override public void onAudioFocusChange(int f) {
            if ((f == AudioManager.AUDIOFOCUS_LOSS || f == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
                && mp != null && mp.isPlaying()) {
                try { mp.pause(); } catch (Exception ignored) {}
                setPlayIco();
                showHud();
            }
        }
    };

    /** 本机也解不动时的兜底：交给系统里能放的 App（图库/VLC 等） */
    private void fail() {
        cueHide();
        new AlertDialog.Builder(this)
            .setTitle("播放不了")
            .setMessage("这台手机的解码器放不了这个文件（编码比较少见）。\n可以试试用其他应用打开。")
            .setPositiveButton("用其他应用打开", new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int w) { openExternal(); }
            })
            .setNegativeButton("返回", new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int w) { finish(); }
            })
            .setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                @Override public void onCancel(android.content.DialogInterface d) { finish(); }
            })
            .show();
    }

    private void openExternal() {
        try {
            File f = new File(files[idx]);
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(this, "com.palworld.vd.fileprovider", f);
            Intent it = new Intent(Intent.ACTION_VIEW);
            it.setDataAndType(uri, MainActivity.mimeOf(f.getName()));
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(it);
            finish();
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "打不开：" + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private static String fmt(int ms) {
        int s = Math.max(0, ms / 1000);
        int hh = s / 3600, m = (s % 3600) / 60, ss = s % 60;
        return hh > 0 ? hh + ":" + String.format(Locale.US, "%02d:%02d", m, ss)
            : m + ":" + String.format(Locale.US, "%02d", ss);
    }

    private static String trimF(float f) { return f == Math.rint(f) ? String.valueOf((int) f) : String.valueOf(f); }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        fitVideo();
        immersive();
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        try { am.requestAudioFocus(afL, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN); } catch (Exception ignored) {}
        if (mp != null) { h.removeCallbacks(tick); h.post(tick); }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveProg();
        h.removeCallbacks(tick);
        if (mp != null && mp.isPlaying()) { try { mp.pause(); setPlayIco(); } catch (Exception ignored) {} }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
        saveProg();
        releaseMp();
        try { am.abandonAudioFocus(afL); } catch (Exception ignored) {}
        try {
            android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            getWindow().setAttributes(lp);
        } catch (Exception ignored) {}
    }
}
