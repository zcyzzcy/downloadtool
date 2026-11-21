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
    private MagnetStream torrentStream;
    private boolean torrentFailureShown;
    private TextView streamStatusV;
    private TextureView tex;

    private FrameLayout root;
    private LinearLayout topBar, botBar;
    private TextView titleV, curT, durT, cueV, spdBtn, fillBtn, prevB, nextB, cntV, spd2x, moreBtn;
    private PlayIcon playIco;
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
    private float episodeDrag = 0;
    private float bright0 = 0.55f;
    private int vol0 = 0;
    private int lastVolSet = -1;         // v1.99：音量手势去重（同档位不打 AudioService）
    private boolean holding2x = false;
    private android.app.Dialog liveDlg;   // v1.70：当前挂着的弹窗（底部菜单/播放不了），onDestroy 统一 dismiss
    private GestureDetector gd;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("vd", MODE_PRIVATE);
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        speed = PlaybackMath.speed(prefs.getString("vd_speed_n", "1"));
        fillMode = "1".equals(prefs.getString("vd_fill_n", "0"));
        loop = "1".equals(prefs.getString("vd_loop_n", "0"));
        muted = "1".equals(prefs.getString("vd_mute_n", "0"));
        autoNext = !"0".equals(prefs.getString("vd_autonext_n", "1"));

        files = getIntent().getStringArrayExtra("files");
        idx = getIntent().getIntExtra("index", 0);
        title = getIntent().getStringExtra("title");
        if (files == null || files.length == 0) { finish(); return; }
        // v1.70：数组元素可为 null/空串（Intent 不保证）——后续 new File(files[i]) / baseName()
        // 会直接 NPE 崩溃，过滤掉再判空
        java.util.ArrayList<String> fl = new java.util.ArrayList<>();
        for (String f : files) if (f != null && f.trim().length() > 0) fl.add(f);
        if (fl.isEmpty()) { finish(); return; }
        files = fl.toArray(new String[0]);
        if (idx < 0 || idx >= files.length) idx = 0;

        torrentStream = MagnetStream.get(getIntent().getStringExtra("torrentSession"));
        if (getIntent().hasExtra("torrentSession") && torrentStream == null) { finish(); return; }
        if (torrentStream != null) torrentStream.attach();
        buildPlayer();
        buildUi();
        immersive();
        bindGestures();
        play(idx);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
        // v1.70：挂着的弹窗（播放不了/底部菜单）先关——不 dismiss 会 WindowLeaked 并泄漏 Activity
        if (liveDlg != null) { try { if (liveDlg.isShowing()) liveDlg.dismiss(); } catch (Exception ignored) {} liveDlg = null; }
        saveProg();
        if (torrentStream != null) torrentStream.detach();
        if (player != null) { try { player.release(); } catch (Exception ignored) {} player = null; }
        try {
            android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            getWindow().setAttributes(lp);
        } catch (Exception ignored) {}
    }

    // ================= 播放控制 =================

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private void buildPlayer() {
        // v1.99：本地文件不需要默认 LoadControl 的 50 秒前向缓冲（高码率下白占上百 MB 配额），
        // 15 秒足够流畅，起播/seek 反而更快（与 VideoBox 同款配置）
        player = new ExoPlayer.Builder(this)
            .setMediaSourceFactory(new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                new androidx.media3.datasource.DefaultDataSource.Factory(this,
                    new androidx.media3.datasource.DefaultHttpDataSource.Factory()
                        .setConnectTimeoutMs(10000).setReadTimeoutMs(torrentStream == null ? 15000 : 120000))))
            .setLoadControl(new androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(15000, 15000, 1500, 3000)
                .build())
            .build();
        // 音频焦点由 ExoPlayer 自己管（打断/恢复），不再手写监听
        player.setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.setRepeatMode(loop ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        player.setVolume(muted ? 0f : 1f);
        player.addListener(new Player.Listener() {
            @Override public void onVideoSizeChanged(VideoSize vs) {
                // 竖拍视频元数据带 90/270 旋转时真实画幅是换过边的（VideoBox 同款处理），
                // 不换算的话方向判定/画面适配全反
                int w = vs.width, hh = vs.height;
                if (vs.unappliedRotationDegrees == 90 || vs.unappliedRotationDegrees == 270) { int t = w; w = hh; hh = t; }
                if (w > 0 && hh > 0) { vw = w; vh = hh; fitVideo(); }
                if (!sizedOnce && w > 0) {   // 初始方向跟随视频画幅
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
                    // v1.99：play() 已经把续播位置传给 setMediaItem，正常不需要再 seek——
                    // 只有位置真不符（>500ms）才兜底，避免每次续播白付一次解码器重初始化
                    if (pendingResumeMs > 5000 && dur > 0 && pendingResumeMs < dur - 5000
                        && Math.abs(player.getCurrentPosition() - pendingResumeMs) > 500) {
                        player.seekTo(pendingResumeMs);
                        cue("从上次看过的地方继续 · " + fmt(pendingResumeMs));
                    }
                    pendingResumeMs = 0;
                    showHud();
                    h.removeCallbacks(tick);
                    h.post(tick);
                } else if (state == Player.STATE_BUFFERING) {
                    cue("加载中…", 0);
                    h.removeCallbacks(tick);
                    h.post(tick);   // v1.99：暂停后 tick 已停，缓冲态也要刷新界面
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
             *  重试仍失败才弹"播放不了"并给出系统播放器出路。
             *  只对"偶发型"错误重试：文件没了/解析失败是永久性的，盲重试只会让用户多等一倍时间 */
            @Override public void onPlayerError(PlaybackException e) {
                if (!retried && isRetryable(e)) {
                    retried = true;
                    long pos = Math.max(lastKnownPos, pendingResumeMs);
                    pendingResumeMs = 0;   // 续播位置已折进 seek，别让 READY 处理再叠一次
                    cue("重试中…", 0);
                    player.setMediaItem(buildItem(idx), pos);
                    player.prepare();
                    player.play();
                    return;
                }
                fail(e.getErrorCodeName());
            }
        });
    }

    /** 解码器初始化/运行期的偶发错误值得重试；文件缺失、格式解析不了重试也没用 */
    private static boolean isRetryable(PlaybackException e) {
        int c = e.errorCode;
        return c == PlaybackException.ERROR_CODE_UNSPECIFIED
            || c == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
            || c == PlaybackException.ERROR_CODE_DECODING_FAILED
            || c == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
            || c == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED;
    }

    private void play(int i) {
        saveProg();
        h.removeCallbacks(tick);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);   // v1.99：fail() 清过常亮，换集/重试恢复
        sizedOnce = false;
        idx = i;
        retried = false;
        lastKnownPos = 0;
        holding2x = false;   // v1.70：换集时长按 2x 状态不复位（连播后倍速/角标残留）
        if (spd2x != null) spd2x.setVisibility(View.GONE);
        String name = new File(files[i]).getName();
        String disp = (title != null && title.length() > 0 && files.length == 1)
            ? title : name.replaceAll("\\.[^.]+$", "");
        titleV.setText(disp);
        cntV.setText(torrentStream != null ? "磁力" : files.length > 1 ? (i + 1) + " / " + files.length : "");
        int pb = files.length > 1 ? View.VISIBLE : View.GONE;
        prevB.setVisibility(pb);
        nextB.setVisibility(pb);
        prevB.setEnabled(i > 0);
        nextB.setEnabled(i < files.length - 1);
        pbar.setFrac(0);
        pbar.setBuf(0);
        curT.setText(getString(R.string.player_initial_time));
        durT.setText("--:--");
        applySpeedLabel();
        pendingResumeMs = torrentStream == null ? progMap().optInt(baseName(files[idx]), 0) * 1000L : 0;
        // v1.99：起始位置直接传给 setMediaItem（原来固定从 0 起、READY 后再 seek——
        // 解码器白跑一段再全量 flush 重来，续播起播延迟近乎翻倍）
        player.setMediaItem(buildItem(i), Math.max(0, pendingResumeMs));
        if (speed != 1f) player.setPlaybackSpeed(speed);
        player.prepare();
        player.play();
        cue("加载中…", 0);
    }

    private MediaItem buildItem(int i) {
        if (torrentStream != null) return new MediaItem.Builder().setUri(Uri.parse(files[i])).build();
        File vf = new File(files[i]);
        return new MediaItem.Builder().setUri(Uri.fromFile(vf)).build();
    }

    private void togglePlay() {
        if (player == null) return;
        if (player.getPlayWhenReady()) { player.pause(); cueHide(); }
        else player.play();
    }

    private void step(int d) {
        if (files == null || files.length < 2) return;
        int target = idx + d;
        if (target < 0 || target >= files.length) { cue(d < 0 ? "已经是第一个视频" : "已经是最后一个视频", 1200); return; }
        play(target);
    }

    // ================= 倍速 / 画面 / 菜单 =================

    /** 底部弹层行选中回调 */
    private interface SheetPick { void onPick(int i); }

    /** 手势导航条高度（弹层别被系统导航条挡住）；取不到就算 0 */
    private int navInset() {
        androidx.core.view.WindowInsetsCompat insets = androidx.core.view.ViewCompat.getRootWindowInsets(getWindow().getDecorView());
        return insets == null ? 0 : insets.getInsetsIgnoringVisibility(
            androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom;
    }

    /** App 风格底部弹层（v1.39）：白卡圆角顶 + 行式菜单，替代系统 AlertDialog——
     *  系统弹窗的样式和 App 完全不搭。states[i] 为空串则不显示右侧状态字 */
    private Dialog showSheet(String title, String[] labels, String[] states, final SheetPick pick) {
        final Dialog d = new Dialog(this);
        liveDlg = d;   // v1.70：登记当前弹窗，onDestroy 统一 dismiss（防 WindowLeaked）
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
        cue(muted ? "静音：开" : "静音：关");
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

    private void applySpeedLabel() { spdBtn.setText(speed == 1f ? getString(R.string.player_speed_default) : getString(R.string.player_speed_selected, trimF(speed))); }

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
        playIco.setPlaying(playing);
    }

    private int lastBrightPct = -1;
    private void setBright(float f) {
        try {
            // v1.99：120Hz 触控下每个 move 事件都 setAttributes（窗口 IPC）纯浪费——取整没变就跳过
            int pct = Math.round(Math.max(0.06f, Math.min(1f, f)) * 100);
            if (pct == lastBrightPct) return;
            lastBrightPct = pct;
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
        if (player == null || files == null || torrentStream != null) return;
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
        if (torrentStream != null) return;
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
            // v2.45：轨道加亮加粗——之前未播段只有 18% 透明度，整条看着就是一根白线，
            // 跟视频画面里自带的装饰线分不清（用户把画面里的线当成了进度条）
            pTrack.setColor(0x4DFFFFFF);
            pBuf.setColor(0x73FFFFFF);
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
            float tr = scrub ? dp(7) : dp(4.5f);   /* v2.45：常态也带明显圆点，一眼认出这是进度条 */
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
                    if (player == null || durMs() <= 0) return true;
                    scrub = true;
                    h.removeCallbacks(hideHudR);
                    frac = clamp01(x / Math.max(1, getWidth()));
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!scrub) return true;
                    frac = clamp01(x / Math.max(1, getWidth()));
                    curT.setText(fmt((long) (frac * Math.max(0, durMs()))));
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!scrub) return true;
                    scrub = false;
                    long dur = durMs();
                    if (player != null && dur > 0) {
                        try { player.seekTo(Math.round(frac * dur)); } catch (Exception ignored) {}
                    }
                    invalidate();
                    showHud();
                    performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    scrub = false;
                    setFrac(durMs() > 0 && player != null ? player.getCurrentPosition() / (float) durMs() : 0f);
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

    /** 播放/暂停自绘图标：白色实心三角（播放）/ 圆角双竖条（暂停）。
     *  不再用 ▶/⏸ 字形——部分 ROM 字体把它们渲染成彩色 emoji，画风和玻璃按钮完全不搭 */
    private class PlayIcon extends View {
        private boolean playing = false;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path iconPath = new android.graphics.Path();
        PlayIcon(android.content.Context c) {
            super(c);
            p.setColor(0xFFFFFFFF);
            p.setStyle(Paint.Style.FILL);
        }
        void setPlaying(boolean pg) { if (playing != pg) { playing = pg; invalidate(); } }
        @Override protected void onDraw(Canvas cv) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, s = dp(8.5f);
            if (playing) {   // 暂停：圆角双竖条
                float bw = dp(3.4f), gap = dp(2.6f);
                cv.drawRoundRect(cx - gap - bw, cy - s, cx - gap, cy + s, bw / 2, bw / 2, p);
                cv.drawRoundRect(cx + gap, cy - s, cx + gap + bw, cy + s, bw / 2, bw / 2, p);
            } else {   // 播放：实心三角（视觉重心右移一点）
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

    /** 玻璃药丸按钮（底部栏：倍速/铺满） */
    private TextView pill(String t, View.OnClickListener l) {        TextView v = new TextView(this);
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

    /** 圆形图标按钮（返回/播放/⋮）：v2.43 去掉玻璃圆底——用户反馈底圈在部分 ROM 上很突兀，
     *  改成纯白色字形 + 轻投影，任何背景下都干净 */
    private TextView circle(String t, float sizeSp, int dimDp, View.OnClickListener l) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(0xFFFFFFFF);
        v.setTextSize(sizeSp);
        v.setGravity(Gravity.CENTER);
        v.setShadowLayer(dp(3), 0, dp(1), 0x99000000);
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
        spd2x.setText(R.string.player_speed_hold);
        spd2x.setTextColor(0xFFFFFFFF);
        spd2x.setTextSize(13);
        spd2x.setPadding(dp(16), dp(7), dp(16), dp(7));
        spd2x.setBackgroundDrawable(glass(17));
        spd2x.setVisibility(View.GONE);
        FrameLayout.LayoutParams s2 = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        s2.bottomMargin = dp(28);
        root.addView(spd2x, s2);

        // 顶栏：返回 + 标题 + 序号 + 更多(⋮)
        // v2.47：去掉整条顶部渐变底——那根深色横条被用户当成了"跑到顶部的进度条"
        // （顶部拖动又会触发全屏快进手势，越拖越像）。改成浮动字形+投影，顶部不再有任何"条"
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(10), dp(12), dp(14), dp(18));
        TextView back = circle("‹", 24, 42, new View.OnClickListener() { @Override public void onClick(View v) { finish(); } });
        topBar.addView(back);
        titleV = new TextView(this);
        titleV.setTextColor(0xFFFFFFFF);
        titleV.setTextSize(15);
        titleV.setSingleLine(true);
        titleV.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleV.setPadding(dp(10), 0, 0, 0);
        titleV.setShadowLayer(dp(3), 0, dp(1), 0x99000000);
        topBar.addView(titleV, new LinearLayout.LayoutParams(0, -2, 1f));
        cntV = new TextView(this);
        cntV.setTextColor(0xFFFFFFFF);
        cntV.setTextSize(12.5f);
        cntV.setPadding(dp(11), dp(5), dp(11), dp(5));
        cntV.setBackgroundDrawable(roundBg(0x33000000, 16));
        cntV.setShadowLayer(dp(3), 0, dp(1), 0x99000000);
        topBar.addView(cntV, new LinearLayout.LayoutParams(-2, -2));
        moreBtn = circle("⋮", 20, 42, new View.OnClickListener() { @Override public void onClick(View v) { showMoreMenu(); } });
        {
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(42), dp(42));
            mp.leftMargin = dp(8);
            topBar.addView(moreBtn, mp);
        }
        prevB = circle("‹", 20, 42, v -> step(-1));
        nextB = circle("›", 20, 42, v -> step(1));
        prevB.setContentDescription("上一个视频"); nextB.setContentDescription("下一个视频");
        topBar.addView(prevB, 2, new LinearLayout.LayoutParams(dp(42), dp(42)));
        topBar.addView(nextB, 3, new LinearLayout.LayoutParams(dp(42), dp(42)));
        root.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        if (torrentStream != null) {
            streamStatusV = white(12);
            streamStatusV.setPadding(dp(12), dp(6), dp(12), dp(6));
            streamStatusV.setBackgroundDrawable(roundBg(0xA61A1D26, dp(12)));
            streamStatusV.setSingleLine(true);
            streamStatusV.setEllipsize(android.text.TextUtils.TruncateAt.END);
            FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
            statusLp.topMargin = dp(64); statusLp.leftMargin = dp(18); statusLp.rightMargin = dp(18);
            root.addView(streamStatusV, statusLp);
            cntV.setText("磁力");
        }

        // 底栏：进度条 + 按钮排
        botBar = new LinearLayout(this);
        botBar.setOrientation(LinearLayout.VERTICAL);
        botBar.setPadding(dp(14), dp(10), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xD9000000, 0x73000000, 0x00000000});
        botBar.setBackgroundDrawable(bg);

        pbar = new PBar(this);
        botBar.addView(pbar, new LinearLayout.LayoutParams(-1, dp(48)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams w2 = new LinearLayout.LayoutParams(-2, -2);

        playIco = new PlayIcon(this);
        // 播放/暂停键：自绘矢量图标（v1.40）——▶/⏸ 字形在部分 ROM 字体上会渲染成彩色 emoji。
        // v2.43：不再垫玻璃圆环（用户反馈圈底突兀），白色图标直接放在底栏渐变上
        FrameLayout playBtn = new FrameLayout(this);
        playBtn.setBackgroundResource(0);
        playBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { togglePlay(); } });
        playBtn.addView(playIco, new FrameLayout.LayoutParams(-1, -1));
        // v2.47 修复（三版"进度条在顶部"的真正根因）：这里若 setLayoutParams 后又 addView(view, w2)
        // 传入 wrap/wrap，会把 42dp 覆盖掉；playBtn 里的 PlayIcon 是 match_parent，
        // wrap 容器一量就成了 900+px 巨块 → 按钮行撑爆 → botBar 整个全屏铺开，
        // 进度条（botBar 第一个孩子）被顶到屏幕最顶端。必须用固定的 42dp 参数加入
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        // 上一集/下一集用紧凑圆形图标（原来的文字药丸在窄屏+挖孔下会挤出屏幕被裁切）
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

        row.addView(playBtn, pbLp);
        LinearLayout.LayoutParams tm = new LinearLayout.LayoutParams(-2, -2);
        tm.leftMargin = dp(12);
        row.addView(curT, tm);
        row.addView(slash, w2);
        row.addView(durT, w2);
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
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
        if (streamStatusV != null) streamStatusV.setVisibility(View.VISIBLE);
        botBar.setVisibility(View.VISIBLE);
        topBar.animate().cancel();
        botBar.animate().cancel();
        topBar.setAlpha(1f);
        botBar.setAlpha(1f);
        topBar.setTranslationY(0f);
        botBar.setTranslationY(0f);
        h.removeCallbacks(hideHudR);
        if (player != null && player.isPlaying()) h.postDelayed(hideHudR, 3500);
    }

    private final Runnable hideHudR = new Runnable() { @Override public void run() { hideHud(); } };

    private void hideHud() {
        if (streamStatusV != null) streamStatusV.setVisibility(View.GONE);
        // v2.46：底栏（进度条+按钮）常驻不藏——之前底栏跟着 3.5 秒自动隐藏，藏掉之后画面里
        // 随便一条亮线都被当成"进度条跑到顶上去了"。现在只有顶栏自动隐藏，进度条永远在底部可见
        topBar.animate().alpha(0f).translationY(-dp(64)).setDuration(220)
            .withEndAction(new Runnable() { @Override public void run() { topBar.setVisibility(View.GONE); } });
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
        if (!t.contentEquals(cueV.getText())) cueV.setText(t);   // v1.99：手势拖动时同一文案不再每 move 一次 setText
        cueV.setVisibility(View.VISIBLE);
        cueV.animate().cancel();
        cueV.setAlpha(1f);
        h.removeCallbacks(cueHideR);
        // v2.43：ms<=0（"加载中…"这类状态提示）也必须自动消失——之前会一直蹲在屏幕中央，
        // 用户看到的就是"画面中间一个圆角矩形框"。上限 2.5 秒，真正要紧的提示走别的 UI
        h.postDelayed(cueHideR, ms > 0 ? ms : 2500);
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
                try { player.setPlaybackSpeed(Math.max(2f, speed)); } catch (Exception ignored) {}
                spd2x.setText(getString(R.string.player_speed_hold_value, trimF(Math.max(2f, speed))));
                spd2x.setVisibility(View.VISIBLE);
            }

            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (e1 == null || player == null) return true;
                int w = root.getWidth(), hg = root.getHeight();
                // v2.47：顶部一截（返回/标题/更多那行）不作为手势起点——在那里横滑也能快进的话，
                // 用户会愈发把顶部那行当成"在顶部的进度条"。要拖进度：底栏进度条/画面中部横滑
                if (e1.getY() < hg * 0.14f) return true;
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
                        gmode = files.length > 1 ? "episode" : (e1.getX() < w / 2f ? "bright" : "vol");
                        episodeDrag = 0;
                        float b0 = getWindow().getAttributes().screenBrightness;
                        bright0 = (b0 > 0 && b0 <= 1) ? b0 : 0.55f;
                        try { vol0 = am.getStreamVolume(AudioManager.STREAM_MUSIC); } catch (Exception ignored) {}
                    } else return true;
                }
                if ("seek".equals(gmode)) {
                    long dur = durMs();
                    if (dur <= 0) return true;
                    // Long videos need fine adjustment, not a whole-film jump for one swipe.
                    seekTargetMs = PlaybackMath.swipe(seekBaseMs, dur, e2.getX() - e1.getX(), w);
                    pbar.setFrac(seekTargetMs / (float) dur);
                    curT.setText(fmt(seekTargetMs));
                    long dd = (seekTargetMs - seekBaseMs) / 1000;
                    cue((dd >= 0 ? "+" : "") + dd + "s · " + fmt(seekBaseMs) + " → " + fmt(seekTargetMs), 0);
                } else if ("episode".equals(gmode)) {
                    episodeDrag = e2.getY() - e1.getY();
                    cue(episodeDrag < 0 ? "松开播放下一集" : "松开播放上一集", 0);
                } else if ("bright".equals(gmode)) {
                    float f = Math.max(0.06f, Math.min(1f, bright0 + (e1.getY() - e2.getY()) / (hg * 0.8f)));
                    setBright(f);
                    cue("亮度 " + Math.round(f * 100) + "%", 0);
                } else if ("vol".equals(gmode)) {
                    int max = 0;
                    try { max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC); } catch (Exception ignored) {}
                    if (max <= 0) return true;
                    float f = Math.max(0f, Math.min(1f, (vol0 + (e1.getY() - e2.getY()) / (hg * 0.8f) * max) / max));
                    // v1.99：取整后没变就别打 AudioService（每个 move 一次 binder 调用，120Hz 下每秒上百次）
                    int vt = Math.round(f * max);
                    if (vt != lastVolSet) {
                        lastVolSet = vt;
                        try { am.setStreamVolume(AudioManager.STREAM_MUSIC, vt, 0); } catch (Exception ignored) {}
                    }
                    cue("音量 " + Math.round(f * 100) + "%", 0);
                }
                return true;
            }
        });
    }

    /** 手指抬起：落快进、恢复长按倍速、收提示 */
    private void endGestures(boolean commitSeek) {
        if (holding2x) {
            holding2x = false;
            spd2x.setVisibility(View.GONE);
            try { if (player != null) player.setPlaybackSpeed(speed); } catch (Exception ignored) {}
        }
        if (commitSeek && "seek".equals(gmode) && seekTargetMs >= 0 && player != null) {
            try { player.seekTo(seekTargetMs); } catch (Exception ignored) {}
        }
        boolean changeEpisode = commitSeek && "episode".equals(gmode) && Math.abs(episodeDrag) > dp(64);
        int direction = episodeDrag < 0 ? 1 : -1;
        episodeDrag = 0;
        seekTargetMs = -1;
        if (gmode != null) { gmode = null; cueHide(); showHud(); }
        if (changeEpisode) step(direction);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (gd != null) gd.onTouchEvent(e);
        if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL)
            endGestures(e.getActionMasked() == MotionEvent.ACTION_UP);
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
                        if (!pbar.scrub && !"seek".equals(gmode)) pbar.setFrac(pos / (float) dur);
                        pbar.setBuf(Math.min(1f, player.getBufferedPosition() / (float) dur));
                        if (!pbar.scrub && !"seek".equals(gmode)) {
                            String current = fmt(pos);
                            if (!current.contentEquals(curT.getText())) curT.setText(current);
                        }
                    }
                    setPlayIco();
                    if (torrentStream != null) {
                        if (torrentStream.failed() && !torrentFailureShown) {
                            torrentFailureShown = true; player.pause(); fail(null); return;
                        }
                        String note = torrentStream.note();
                        if (streamStatusV != null && !note.contentEquals(streamStatusV.getText())) streamStatusV.setText(note);
                        if (player.getPlayWhenReady() && player.getPlaybackState() == Player.STATE_BUFFERING)
                            cue(torrentStream.failed() ? torrentStream.note() : "正在缓冲，等待已校验的视频片段…", 0);
                    }
                    // v1.99：3 秒→30 秒——暂停/换集/onPause/onDestroy 都有保存兜底，
                    // 每 3 秒对越来越大的进度 map 做全量 parse+serialize+写盘纯是写放大
                    if (player.isPlaying() && Math.abs(pos - lastSaveMs) > 30000) { lastSaveMs = (int) pos; saveProg(); }
                } catch (Exception ignored) {}
            }
            // v1.99：只在真播放/缓冲时重排——暂停后 2 次/秒空转唤醒纯浪费，
            // 由 onIsPlayingChanged/READY/BUFFERING 重新拉起
            boolean active = false;
            try { active = player != null && (player.isPlaying() || player.getPlayWhenReady() && player.getPlaybackState() == Player.STATE_BUFFERING); } catch (Exception ignored) {}
            if (active) h.postDelayed(this, 500);
        }
    };

    /** 解码真失败（重试过一次仍失败）的兜底：交给系统里能放的 App（图库/VLC 等） */
    private void fail(String errName) {
        if (torrentStream != null) {
            cueHide();
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            liveDlg = new AlertDialog.Builder(this).setTitle("暂时无法播放")
                .setMessage(torrentStream.failed() ? torrentStream.note() : "可能仍在等待视频片段，或当前编码不支持边下边播。下载会继续，可以稍后重试或下载完成后播放。")
                .setPositiveButton("重试播放", (d,w) -> play(idx))
                .setNegativeButton("返回选片", (d,w) -> finish()).setOnCancelListener(d -> finish()).show();
            return;
        }
        cueHide();
        // v1.99：播放不了还常亮屏幕白费电——失败弹窗/重试期间不需要 KEEP_SCREEN_ON
        try { getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); } catch (Exception ignored) {}
        liveDlg = new AlertDialog.Builder(this)   // v1.70：登记，onDestroy 统一 dismiss
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
        endGestures(false);
        saveProg();
        h.removeCallbacks(tick);   // 退后台别让进度循环空转（到 onResume/READY 会重新拉起）
        if (player != null) player.pause();
    }
}
