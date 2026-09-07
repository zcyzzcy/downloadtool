package com.palworld.vd;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
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

import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

/**
 * 原生视频播放页 v3：内核换成 Media3 ExoPlayer（B站等主流播放器的同源方案）。
 * ① 之前 MediaPlayer 在部分 ROM 上会误报"解码失败"（文件本身没问题）——ExoPlayer 的
 *    渲染器选择 + 失败自动重试一次，把误报压到最低；
 * ② seek 精准即时（之前 MediaPlayer 拖动会跳回开头/落点漂移）；
 * ③ mkv/ts/flv 容器直接支持。
 * 自绘 HUD（进度条气泡/上下栏滑入滑出/中央播放键）与 B站式手势全部保留；
 * 双击快进快退按需求移除——双击=播放/暂停；右上角 ⋮ 菜单：倍速/画面/旋转/循环/外部打开。
 */
public class VideoActivity extends Activity {

    private String[] files;
    private int idx = 0;
    private String title = "";

    private ExoPlayer player;
    private TextureView tex;

    private FrameLayout root;
    private LinearLayout topBar, botBar;
    private TextView titleV, curT, durT, cueV, playIco, spdBtn, fillBtn, prevB, nextB, cntV, spd2x, moreBtn;
    private PBar pbar;

    private final Handler h = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private AudioManager am;
    private float speed = 1f;
    private boolean fillMode = false;   // false=适配（完整画面留黑边，默认） true=铺满（裁切边缘）
    private boolean loop = false;       // 单集循环
    private boolean muted = false;      // 静音（⋮ 菜单独有，外面没有开关）
    private boolean autoNext = true;    // 看完自动下一集（⋮ 菜单可关）
    private int vw = 0, vh = 0;
    private boolean sizedOnce = false;
    private int lastSaveMs = 0;
    private long pendingResumeMs = 0;   // 等 READY 后要跳到的续播位置
    private boolean retried = false;    // 本集是否已自动重试过一次（偶发解码误报的兜底）
    private long lastKnownPos = 0;      // 最近一次已知播放位置（重试/手势的基准，避免 0 值把进度拉回头）

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
        loop = "1".equals(prefs.getString("vd_loop_n", "0"));
        muted = "1".equals(prefs.getString("vd_mute_n", "0"));
        autoNext = !"0".equals(prefs.getString("vd_autonext_n", "1"));

        files = getIntent().getStringArrayExtra("files");
        idx = getIntent().getIntExtra("index", 0);
        title = getIntent().getStringExtra("title");
        if (files == null || files.length == 0) { finish(); return; }
        if (idx < 0 || idx >= files.length) idx = 0;

        buildPlayer();
        buildUi();
        immersive();
        bindGestures();
        play(idx);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
        saveProg();
        if (player != null) { try { player.release(); } catch (Exception ignored) {} player = null; }
        try {
            android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            getWindow().setAttributes(lp);
        } catch (Exception ignored) {}
    }

    // ================= 播放控制 =================

    private void buildPlayer() {
        player = new ExoPlayer.Builder(this).build();
        // 音频焦点由 ExoPlayer 自己管（打断/恢复），不再手写监听
        player.setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.setRepeatMode(loop ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        player.setVolume(muted ? 0f : 1f);
        player.addListener(new Player.Listener() {
            @Override public void onVideoSizeChanged(VideoSize vs) {
                if (vs.width > 0 && vs.height > 0) { vw = vs.width; vh = vs.height; fitVideo(); }
                if (!sizedOnce && vs.width > 0) {   // 初始方向跟随视频画幅
                    sizedOnce = true;
                    setRequestedOrientation(vw >= vh
                        ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
                }
            }

            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    cueHide();
                    long dur = durMs();
                    durT.setText(dur > 0 ? fmt(dur) : "--:--");
                    if (pendingResumeMs > 5000 && dur > 0 && pendingResumeMs < dur - 5000) {
                        player.seekTo(pendingResumeMs);
                        cue("从上次看过的地方继续 · " + fmt(pendingResumeMs));
                    }
                    pendingResumeMs = 0;
                    showHud();
                    h.removeCallbacks(tick);
                    h.post(tick);
                } else if (state == Player.STATE_BUFFERING) {
                    cue("加载中…", 0);
                } else if (state == Player.STATE_ENDED) {
                    clearProg();
                    if (autoNext && idx < files.length - 1) play(idx + 1);   // 连播（⋮ 菜单可关）：图集/多视频任务
                    else { setPlayIco(); showHud(); }
                }
            }

            @Override public void onIsPlayingChanged(boolean isPlaying) {
                setPlayIco();
                if (isPlaying) { h.removeCallbacks(tick); h.post(tick); }
            }

            /** 失败先静默重试一次（部分 ROM 偶发解码初始化失败，第二次就能播——"明明能播放却报错"的兜底）；
             *  重试仍失败才弹"播放不了"并给出系统播放器出路 */
            @Override public void onPlayerError(PlaybackException e) {
                if (!retried) {
                    retried = true;
                    long pos = lastKnownPos;
                    cue("重试中…", 0);
                    player.setMediaItem(MediaItem.fromUri(Uri.fromFile(new File(files[idx]))), pos);
                    player.prepare();
                    player.play();
                    return;
                }
                fail(e.getErrorCodeName());
            }
        });
    }

    private void play(int i) {
        saveProg();
        h.removeCallbacks(tick);
        sizedOnce = false;
        idx = i;
        retried = false;
        lastKnownPos = 0;
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
        pendingResumeMs = progMap().optInt(baseName(files[idx]), 0) * 1000L;
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(new File(files[i]))), 0);
        if (speed != 1f) player.setPlaybackSpeed(speed);
        player.prepare();
        player.play();
        cue("加载中…", 0);
    }

    private void togglePlay() {
        if (player == null) return;
        if (player.getPlayWhenReady()) player.pause();
        else player.play();
    }

    private void skip(int ms) {
        if (player == null) return;
        long dur = durMs();
        if (dur <= 0) return;
        player.seekTo(Math.max(0, Math.min(dur - 200, player.getCurrentPosition() + ms)));
    }

    private void step(int d) {
        if (files == null || files.length < 2) return;
        play((idx + d + files.length) % files.length);
    }

    // ================= 倍速 / 画面 / 菜单 =================

    /** 底部弹层行选中回调 */
    private interface SheetPick { void onPick(int i); }

    /** 手势导航条高度（弹层别被系统导航条挡住）；取不到就算 0 */
    private int navInset() {
        try {
            int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
        } catch (Throwable t) { return 0; }
    }

    /** App 风格底部弹层（v1.39）：白卡圆角顶 + 行式菜单，替代系统 AlertDialog——
     *  系统弹窗的样式和 App 完全不搭。states[i] 为空串则不显示右侧状态字 */
    private Dialog showSheet(String title, String[] labels, String[] states, final SheetPick pick) {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadii(new float[]{dp(22), dp(22), dp(22), dp(22), 0, 0, 0, 0});
        box.setBackgroundDrawable(bg);
        box.setPadding(0, dp(6), 0, dp(10) + navInset());
        // 顶部小把手 + 标题
        View handle = new View(this);
        GradientDrawable hg = new GradientDrawable();
        hg.setColor(0xFFE2E6EE);
        hg.setCornerRadius(dp(2));
        handle.setBackgroundDrawable(hg);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(36), dp(4));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.topMargin = dp(10);
        box.addView(handle, hp);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(0xFF8A93A6);
        t.setTextSize(12.5f);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = dp(8);
        tp.bottomMargin = dp(2);
        box.addView(t, tp);
        for (int i = 0; i < labels.length; i++) {
            final int fi = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(20), dp(14), dp(20), dp(14));
            TextView lab = new TextView(this);
            lab.setText(labels[i]);
            lab.setTextColor(0xFF171A20);
            lab.setTextSize(15);
            row.addView(lab, new LinearLayout.LayoutParams(0, -2, 1f));
            String st = states != null && i < states.length ? states[i] : "";
            if (st != null && st.length() > 0) {
                TextView sv = new TextView(this);
                sv.setText(st);
                sv.setTextColor(0xFF3D6AE8);
                sv.setTextSize(13);
                row.addView(sv, new LinearLayout.LayoutParams(-2, -2));
            }
            row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { d.dismiss(); pick.onPick(fi); } });
            box.addView(row, new LinearLayout.LayoutParams(-1, -2));
            if (i < labels.length - 1) {
                View div = new View(this);
                div.setBackgroundColor(0xFFF1F3F7);
                LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
                dp2.leftMargin = dp(20);
                box.addView(div, dp2);
            }
        }
        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
        box.setTranslationY(dp(380));
        box.animate().translationY(0f).setDuration(200).start();
        return d;
    }

    /** 倍速列表选择（底部弹层，一屏看全所有档位，当前档打勾） */
    private void showSpeedMenu() {
        final float[] opts = {3f, 2f, 1.5f, 1.25f, 1f, 0.75f, 0.5f};
        String[] labels = new String[opts.length];
        String[] states = new String[opts.length];
        for (int i = 0; i < opts.length; i++) {
            labels[i] = trimF(opts[i]) + "x" + (opts[i] == 1f ? "（正常）" : "");
            states[i] = opts[i] == speed ? "✓" : "";
        }
        showSheet("播放倍速", labels, states, new SheetPick() {
            @Override public void onPick(int i) {
                speed = opts[i];
                applySpeed();
                prefs.edit().putString("vd_speed_n", String.valueOf(speed)).apply();
                cue(trimF(speed) + "x");
                showHud();
            }
        });
    }

    /** 右上角 ⋮ 更多菜单（v1.39）：只放底栏没有的功能——循环/静音/自动连播/旋转/外部打开
     *  （倍速、画面适配底栏已有按钮，不在这里重复） */
    private void showMoreMenu() {
        String[] labels = { "单集循环", "静音", "自动连播", "旋转屏幕", "用其他应用打开" };
        String[] states = { loop ? "开" : "关", muted ? "开" : "关", autoNext ? "开" : "关", "", "" };
        showSheet("更多", labels, states, new SheetPick() {
            @Override public void onPick(int i) {
                switch (i) {
                    case 0: toggleLoop(); break;
                    case 1: toggleMute(); break;
                    case 2: toggleAutoNext(); break;
                    case 3: toggleRotate(); break;
                    case 4: openExternal(); break;
                }
            }
        });
    }

    private void toggleLoop() {
        loop = !loop;
        prefs.edit().putString("vd_loop_n", loop ? "1" : "0").apply();
        player.setRepeatMode(loop ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        cue(loop ? "单集循环：开" : "单集循环：关");
        showHud();
    }

    private void toggleMute() {
        muted = !muted;
        prefs.edit().putString("vd_mute_n", muted ? "1" : "0").apply();
        try { player.setVolume(muted ? 0f : 1f); } catch (Exception ignored) {}
        cue(muted ? "🔇 静音：开" : "🔊 静音：关");
        showHud();
    }

    private void toggleAutoNext() {
        autoNext = !autoNext;
        prefs.edit().putString("vd_autonext_n", autoNext ? "1" : "0").apply();
        cue(autoNext ? "自动连播：开" : "自动连播：关");
        showHud();
    }

    private void applySpeed() {
        try { player.setPlaybackSpeed(speed); } catch (Exception ignored) {}
        applySpeedLabel();
    }

    private void applySpeedLabel() { spdBtn.setText((speed == 1f ? "倍速" : trimF(speed) + "x") + " ▾"); }

    private void toggleFill() {
        fillMode = !fillMode;
        prefs.edit().putString("vd_fill_n", fillMode ? "1" : "0").apply();
        fillBtn.setText(fillMode ? "铺满" : "适配");
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
        boolean playing = player != null && player.isPlaying();
        playIco.setText(playing ? "⏸" : "▶");
    }

    private void setBright(float f) {
        try {
            android.view.Window w = getWindow();
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.screenBrightness = Math.max(0.06f, Math.min(1f, f));
            w.setAttributes(lp);
        } catch (Exception ignored) {}
    }

    /** 画面适配：直接改 TextureView 的布局尺寸（居中、黑边在根容器上） */
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

    private long durMs() {
        try {
            long d = player.getDuration();
            return (d == C.TIME_UNSET || d <= 0) ? 0 : d;
        } catch (Exception e) { return 0; }
    }

    private long safePos() {
        try {
            long p = player.getCurrentPosition();
            if (p >= 0) return p;
        } catch (Exception ignored) {}
        return lastKnownPos;   // 播放器短暂取不到位置时用最近已知值，绝不让进度手势从头算起
    }

    // ================= 进度记忆（与网页端共用 kv_vd_progress，按文件名记） =================

    private JSONObject progMap() {
        try { return new JSONObject(prefs.getString("kv_vd_progress", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    private void saveProg() {
        if (player == null || files == null) return;
        try {
            long pos = player.getCurrentPosition(), dur = durMs();
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

    /** 自绘进度条：圆角细轨道 + 缓冲段 + 白色进度，按住/拖拽时圆点放大并弹出时间气泡（跟手） */
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
            float cy = hgt - dp(13);
            float r = dp(1.75f);
            c.drawRoundRect(0, cy - r, w, cy + r, r, r, pTrack);
            if (buf > 0.01f) c.drawRoundRect(0, cy - r, w * buf, cy + r, r, r, pBuf);
            float pw = Math.max(r * 2, w * frac);
            c.drawRoundRect(0, cy - r, pw, cy + r, r, r, pOn);
            float tx = Math.max(dp(6), Math.min(w - dp(6), w * frac));
            float tr = scrub ? dp(7) : dp(3);
            c.drawCircle(tx, cy, tr, pThumb);
            if (scrub) {
                String t = fmt(durMs() > 0 ? (long) (frac * durMs()) : 0);
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
                    curT.setText(fmt((long) (frac * Math.max(0, durMs()))));
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    scrub = false;
                    long dur = durMs();
                    if (player != null && dur > 0) {
                        try { player.seekTo(Math.round(frac * dur)); } catch (Exception ignored) {}
                    }
                    invalidate();
                    showHud();
                    return true;
            }
            return true;
        }
    }

    /** 玻璃药丸按钮（底部栏：倍速/铺满） */
    private TextView pill(String t, View.OnClickListener l) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(0xFFFFFFFF);
        v.setTextSize(13);
        v.setGravity(Gravity.CENTER);
        v.setMinWidth(dp(56));
        v.setPadding(dp(15), dp(8), dp(15), dp(8));
        v.setBackgroundDrawable(glass(20));
        v.setOnClickListener(l);
        return v;
    }

    /** 圆形图标按钮（返回/播放/⋮）：与底部药丸同一套玻璃风（v1.39 统一——
     *  之前播放键是暗底、药丸是亮玻璃，两种风格混在一起很突兀） */
    private TextView circle(String t, float sizeSp, int dimDp, View.OnClickListener l) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(0xFFFFFFFF);
        v.setTextSize(sizeSp);
        v.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0x2EFFFFFF);
        g.setStroke(dp(1), 0x45FFFFFF);
        v.setBackgroundDrawable(g);
        v.setOnClickListener(l);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(dimDp), dp(dimDp)));
        return v;
    }

    private GradientDrawable glass(float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x2EFFFFFF);
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(dp(1), 0x45FFFFFF);
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
        root.addView(tex, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        player.setVideoTextureView(tex);
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b2, int ol, int ot, int or, int ob) { fitVideo(); }
        });

        // 中央提示气泡（快进/亮度/音量/倍速/缓冲）
        cueV = new TextView(this);
        cueV.setTextColor(0xFFFFFFFF);
        cueV.setTextSize(15);
        cueV.setPadding(dp(24), dp(13), dp(24), dp(13));
        cueV.setBackgroundDrawable(roundBg(0xCC1A1D26, 26));
        cueV.setVisibility(View.GONE);
        cueV.setMaxWidth(dp(320));
        cueV.setGravity(Gravity.CENTER);
        root.addView(cueV, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

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

        // 顶栏：返回 + 标题 + 序号 + 更多(⋮)
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
        moreBtn = circle("⋮", 20, 42, new View.OnClickListener() { @Override public void onClick(View v) { showMoreMenu(); } });
        {
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(42), dp(42));
            mp.leftMargin = dp(8);
            topBar.addView(moreBtn, mp);
        }
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
        // 上一集/下一集用紧凑圆形图标（原来的文字药丸在窄屏+挖孔下会挤出屏幕被裁切）
        prevB = circle("‹", 20, 42, new View.OnClickListener() { @Override public void onClick(View v) { step(-1); } });
        nextB = circle("›", 20, 42, new View.OnClickListener() { @Override public void onClick(View v) { step(1); } });
        curT = white(14);
        curT.setFontFeatureSettings("tnum");
        durT = white(14);
        durT.setAlpha(0.6f);
        durT.setFontFeatureSettings("tnum");
        TextView slash = white(14);
        slash.setAlpha(0.45f);
        slash.setText("/");
        spdBtn = pill("倍速 ▾", new View.OnClickListener() { @Override public void onClick(View v) { showSpeedMenu(); } });
        fillBtn = pill(fillMode ? "铺满" : "适配", new View.OnClickListener() { @Override public void onClick(View v) { toggleFill(); } });

        row.addView(playIco, w2);
        LinearLayout.LayoutParams tm = new LinearLayout.LayoutParams(-2, -2);
        tm.leftMargin = dp(12);
        row.addView(curT, tm);
        row.addView(slash, w2);
        row.addView(durT, w2);
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        LinearLayout.LayoutParams pm = new LinearLayout.LayoutParams(-2, -2);
        pm.leftMargin = dp(6);
        row.addView(prevB, pm);
        LinearLayout.LayoutParams nm = new LinearLayout.LayoutParams(-2, -2);
        nm.leftMargin = dp(4);
        row.addView(nextB, nm);
        LinearLayout.LayoutParams sm = new LinearLayout.LayoutParams(-2, -2);
        sm.leftMargin = dp(10);
        row.addView(spdBtn, sm);
        row.addView(fillBtn, w2);
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
        // 挖孔屏/刘海屏：内容铺进挖孔区，顶栏底栏再靠内边距避开——
        // 默认模式会把横屏两侧（=竖屏的顶底）整条让出去，按钮看着被"切掉"半截
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            try {
                android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                getWindow().setAttributes(lp);
            } catch (Throwable ignored) {}
        }
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

    // ================= 手势（B站式，双击快进快退已按需求移除：双击=播放/暂停） =================

    private void bindGestures() {
        gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { toggleHud(); return true; }

            @Override public boolean onDoubleTap(MotionEvent e) {
                togglePlay();   // 双击=播放/暂停（状态看底栏播放键，不放提示/大图标）
                showHud();
                return true;
            }

            @Override public void onLongPress(MotionEvent e) {
                if (player == null || gmode != null || !player.isPlaying()) return;
                holding2x = true;
                try { player.setPlaybackSpeed(2f); } catch (Exception ignored) {}
                spd2x.setVisibility(View.VISIBLE);
            }

            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (e1 == null || player == null) return true;
                int w = root.getWidth(), hg = root.getHeight();
                if (gmode == null) {
                    if (holding2x) {   // 长按加速中开始拖动：先恢复正常倍速，别两个手势叠加
                        holding2x = false;
                        spd2x.setVisibility(View.GONE);
                        try { player.setPlaybackSpeed(speed); } catch (Exception ignored) {}
                        cueHide();
                    }
                    float tdx = e2.getX() - e1.getX(), tdy = e2.getY() - e1.getY();
                    long dur = durMs();
                    if (Math.abs(tdx) > 20 && Math.abs(tdx) > Math.abs(tdy) && dur > 0) {
                        gmode = "seek";
                        seekBaseMs = safePos();
                        seekTargetMs = seekBaseMs;
                    } else if (Math.abs(tdy) > 20) {
                        gmode = e1.getX() < w / 2f ? "bright" : "vol";
                        float b0 = getWindow().getAttributes().screenBrightness;
                        bright0 = (b0 > 0 && b0 <= 1) ? b0 : 0.55f;
                        try { vol0 = am.getStreamVolume(AudioManager.STREAM_MUSIC); } catch (Exception ignored) {}
                    } else return true;
                }
                if ("seek".equals(gmode)) {
                    long dur = durMs();
                    if (dur <= 0) return true;
                    // 整屏横拖 ≈ 1.0 倍时长（原 1.6 倍太快，微调容易过头）
                    seekTargetMs = Math.max(0, Math.min(dur - 300,
                        seekBaseMs + (long) ((e2.getX() - e1.getX()) / w * dur)));
                    pbar.setFrac(seekTargetMs / (float) dur);
                    curT.setText(fmt(seekTargetMs));
                    long dd = (seekTargetMs - seekBaseMs) / 1000;
                    cue((dd >= 0 ? "+" : "") + dd + "s · " + fmt(seekBaseMs) + " → " + fmt(seekTargetMs), 0);
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

    /** 手指抬起：落快进、恢复长按倍速、收提示 */
    private void endGestures() {
        if (holding2x) {
            holding2x = false;
            spd2x.setVisibility(View.GONE);
            try { if (player != null) player.setPlaybackSpeed(speed); } catch (Exception ignored) {}
        }
        if ("seek".equals(gmode) && seekTargetMs >= 0 && player != null) {
            try { player.seekTo(seekTargetMs); } catch (Exception ignored) {}
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
            if (player != null) {
                try {
                    long pos = player.getCurrentPosition(), dur = durMs();
                    if (dur > 0) {
                        lastKnownPos = pos;
                        pbar.setFrac(pos / (float) dur);
                        pbar.setBuf(Math.min(1f, player.getBufferedPosition() / (float) dur));
                        curT.setText(fmt(pos));
                    }
                    setPlayIco();
                    if (player.isPlaying() && Math.abs(pos - lastSaveMs) > 3000) { lastSaveMs = (int) pos; saveProg(); }
                } catch (Exception ignored) {}
            }
            h.postDelayed(this, 500);
        }
    };

    /** 解码真失败（重试过一次仍失败）的兜底：交给系统里能放的 App（图库/VLC 等） */
    private void fail(String errName) {
        cueHide();
        new AlertDialog.Builder(this)
            .setTitle("播放不了")
            .setMessage("这台手机的解码器放不了这个文件。\n（" + (errName == null ? "未知错误" : errName) + "）\n可以试试用其他应用打开。")
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
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "打不开：" + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private static String fmt(long ms) {
        int s = Math.max(0, (int) (ms / 1000));
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
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveProg();
        if (player != null && player.isPlaying()) player.pause();
    }
}
