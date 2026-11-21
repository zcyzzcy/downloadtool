package com.palworld.vd;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.ffmpeg.FFmpeg;
import com.yausername.aria2c.Aria2c;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.json.JSONArray;
import org.json.JSONObject;

import kotlin.Unit;
import kotlin.jvm.functions.Function3;

/**
 * 本地下载引擎：解析/下载/磁力/图集采集下载全部在手机上完成（yt-dlp/Python/ffmpeg/aria2c 已打包在 APK 里），
 * 完全不依赖服务器。回调一律切回主线程，由 MainActivity 推给网页。
 */
public class LocalEngine {

    public interface Listener {
        /** state: init | ready | error:xxx（id==null 表示引擎整体状态）；files 为完成时产出的文件名 JSON 数组 */
        void onState(String id, String state, String title, String error, String filesJson);
        /** 下载进度：line 为 yt-dlp/aria2 原始输出行（含速度信息），供页面解析 */
        void onProgress(String id, int progress, String line);
    }

    // 公共 BT tracker（元数据交换靠它们）
    private static final String[] BT_TRACKERS = {
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.tracker.cl:1337/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.dler.org:6969/announce",
        "udp://tracker.moeking.me:6969/announce",
        "udp://explodie.org:6969/announce",
        "udp://tracker1.bt.moack.co.kr:80/announce",
        "udp://tracker.tiny-vps.com:6969/announce",
        "udp://tracker.dutas.eu:6969/announce",
        "http://tracker.opentrackr.org:1337/announce",
        "http://tracker.openbittorrent.com:80/announce",
        "http://tracker.gbitt.info:80/announce",
        "https://tracker.gbitt.info:443/announce",
        "https://tracker.tamersunion.org:443/announce",
        "http://open.acgnxtracker.com:80/announce",
    };
    private static final String MEDIA_EXT = "mp4|mkv|webm|mov|m4v|mp3|m4a|aac|flac|wav|jpg|jpeg|png|gif|webp|avif|heic|heif|bmp";

    // v1.99：热路径正则一律 static——String.matches 每次调用都重新 Pattern.compile，
    // listFiles 每文件 / aria2 读输出每行都在白烧 CPU（几百文件 × 每次编译）
    private static final java.util.regex.Pattern MEDIA_NAME =
        java.util.regex.Pattern.compile("(?i).+\\.(" + MEDIA_EXT + ")$");
    private static final java.util.regex.Pattern VIDEO_NAME_P =
        java.util.regex.Pattern.compile(".+\\.(mp4|m4v|mov|webm|mkv|flv|avi|ts)$");
    private static final java.util.regex.Pattern IMAGE_NAME_P =
        java.util.regex.Pattern.compile(".+\\.(jpe?g|png|gif|webp|bmp|heic|heif|avif)$");
    /** aria2 摘要行里的 (NN%)——直下/磁力两个读输出循环里每行都要匹配一次 */
    private static final java.util.regex.Pattern ARIA_PCT =
        java.util.regex.Pattern.compile("\\((\\d{1,3})%\\)");

    private static final java.util.regex.Pattern BT_SUMMARY = java.util.regex.Pattern.compile(
        "\\s(\\d+)B?/(\\d+)B?\\((\\d+)%\\).*?CN:(\\d+).*?SD:(\\d+).*?DL:(\\d+)B?");
    private static final java.util.regex.Pattern MAGNET_PEER_HINT = java.util.regex.Pattern.compile("(?i)[?&](?:tr|x\\.pe)=[^&]+");

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    // 任务登记表一律 static：Activity 重建（深色模式/系统语言切换都会触发）后新实例要能
    // 接管存量任务的取消/收尾，否则旧表里的任务永远没人管
    private static final Map<String, String> processIds = new ConcurrentHashMap<>();
    private static final Map<String, Process> btProcs = new ConcurrentHashMap<>();
    private static final Map<String, Thread> threads = new ConcurrentHashMap<>();
    // 并发上限按设备内存自适应：每个 yt-dlp/aria2 进程要几十上百 MB。
    // 小内存机器保守 2 个防 OOM；大内存机器放开，批量任务吞吐显著提升
    private final java.util.concurrent.Semaphore slots;
    private static java.util.concurrent.Semaphore sharedSlots;
    private int slotCount = 2;
    /** v1.99 转码并发限流：多路 x264 同跑会抢满所有核（热降频、互相拖慢），限 2 路。
     *  转码不再占下载槽（见 finishDaemonTask），但自身也要有度 */
    private static final java.util.concurrent.Semaphore tcSlots = new java.util.concurrent.Semaphore(2);
    private static void tcEnter() throws InterruptedException {
        try { tcSlots.acquire(); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw ie; }
    }
    /** v1.99 前台保活服务的任务计数：首个任务拉起服务（进程钉成前台优先级，退后台下载
     *  不再被系统当缓存进程回收），全部结束停掉。计数异常漂移也不影响下载本身 */
    private static final Object SVC_LOCK = new Object();
    private static int svcCount = 0;
    private static void svcAcquire(Context ctx) {
        synchronized (SVC_LOCK) {
            boolean first = svcCount == 0;
            svcCount++;
            if (first) {
                try {
                    android.content.Intent it = new android.content.Intent(ctx, VdService.class);
                    if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(it);
                    else ctx.startService(it);
                } catch (Throwable ignored) {}
            }
        }
    }
    private static void svcRelease(Context ctx) {
        synchronized (SVC_LOCK) {
            if (svcCount <= 0) return;
            svcCount--;
            if (svcCount == 0) {
                try { ctx.startService(new android.content.Intent(ctx, VdService.class).setAction(VdService.ACTION_STOP)); } catch (Throwable ignored) {}
            }
        }
    }
    /** v1.99：一次性并发槽释放（下载产物落定即还槽；转码收尾是纯 CPU 活，不再占网络并发额度） */
    private void freeSlot(boolean[] freed) {
        if (freed != null && !freed[0]) { freed[0] = true; slots.release(); }
    }
    // 任务执行通道：ssr=分享页直连 / daemon=常驻进程 / legacy=每任务一个进程 / bt=磁力
    private static final Map<String, String> taskMode = new ConcurrentHashMap<>();
    private static final Map<String, String[]> pendingTaskStates = new ConcurrentHashMap<>();

    // ---------- 常驻 yt-dlp 守护进程（Python 启动+模块加载整个会话只付一次，任务不再各花 1.5~3 秒冷启动） ----------
    // 守护进程同样必须 static：每个引擎实例各拉一个的话，Activity 每重建一次就孤儿化一个
    // 100MB+ 的 Python 进程。新实例通过 sActive 接管存量任务回调；槽/目录等资源按任务
    // 归属的引擎实例（DaemonTask.eng）释放，不会串到新实例的 semaphore 上
    private static Process dProc;
    private static java.io.OutputStream dOut;
    private static volatile boolean dReady = false;
    private static final Object dStartLock = new Object();
    private static final Object dSendLock = new Object();
    private static final Map<String, DaemonTask> dTasks = new ConcurrentHashMap<>();
    private static final Map<String, DaemonTask> finishingTasks = new ConcurrentHashMap<>();
    private static volatile LocalEngine sActive;   // 当前引擎实例：守护回调永远推给最新的 Activity
    private String sslCertPath, pythonHome, ldLibPath, nativeDirPath, ytdlpZipPath, ffmpegBinPath;

    /** 磁力监听端口段轮转序号（v1.69）：固定同一段时并发磁力任务端口冲突，第二个直接失败 */
    private static final java.util.concurrent.atomic.AtomicInteger BT_PORT_SEQ = new java.util.concurrent.atomic.AtomicInteger(0);
    /** 用过的保存目录全集（含已切走的）：守护重启清扫孤儿 .job-* 时要覆盖历史目录 */
    private static final java.util.Set<String> dDirHistory = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    /** v1.75 断点续传登记：url → 上次失败任务留下的 .job 目录（里有 .part/.aria2/分片记录）。
     *  重试同一链接时复用该目录，yt-dlp/aria2 见到半截文件接着下，不再从零开始。
     *  上限 20 条防膨胀（超了全清，牺牲最早的断点） */
    private static final Map<String, File> dResume = new ConcurrentHashMap<>();

    private static final String TAG = "VDEngine";
    /** owned 名单的读改写锁：两个任务并发收尾时各读一份再加会互相覆盖（丢失归属登记→「我的保存」漏文件） */
    private static final Object OWNED_LOCK = new Object();
    private static final Object FILE_COMMIT_LOCK = new Object();
    /** 磁力任务 staging 目录快照：cancel 按它删，不能按"当前 vdDir"拼（用户中途切目录会删错地方） */
    private static final Map<String, File> stagingDirs = new ConcurrentHashMap<>();

    /** Throwable → 人话：getMessage() 为空时 String.valueOf 会打出字面量 "null" 甩用户脸上 */
    private static String errMsg(Throwable t) {
        String m = t == null ? null : t.getMessage();
        return m == null || m.isEmpty() ? (t == null ? "未知错误" : t.getClass().getSimpleName()) : m;
    }

    private static class DaemonTask {
        final long startAtMs;
        final LocalEngine eng;   // 任务归属的引擎（slots/vdDir 都用它），跨 Activity 重建也不释放错对象
        final File jobDir;       // v1.69：任务创建时的 .job-<id> 快照——用户中途切保存目录后，收尾不能再按"当前"vdDir 找产物（否则必报"没找到产出文件"）
        volatile String lastTitle, destName, url;
        volatile long lastAliveMs;   // v1.69：最后一次收到该任务消息的时间（卡死看门狗：done/error 行丢了要能自动收尾）
        volatile boolean cancelled;
        String titleHint;   // v1.98 浏览器带来的页面标题：yt-dlp 的标题是垃圾（URL 尾巴/空）时用它落盘命名
        DaemonTask(LocalEngine e, long s, String u, File jd) { eng = e; startAtMs = s; url = u; jobDir = jd; lastAliveMs = s; }
    }

    private static final String UA_ENGINE =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36";
    // 设置/文件归属（"我的保存"只列本 App 下载的文件——保存在相册目录时绝不能把相机拍的也算进来）
    private final android.content.SharedPreferences prefs;
    /** 相机拍摄的照片/视频文件名特征（IMG_20260904_123456 / Screenshot_… / mmexport… 等），归属判定和产物扫描都排除 */
    private static final java.util.regex.Pattern CAMERA_NAME = java.util.regex.Pattern.compile(
        "^(img|vid|mvimg|pano|juv|hlv)_[0-9]{8}_[0-9]{6}|^screenshot[_ ]?[0-9]{2,}|^screen_?record|^mmexport[0-9]+|^wx_camera_[0-9]{8}|^[0-9]{8}_[0-9]{6}",
        java.util.regex.Pattern.CASE_INSENSITIVE);

    private volatile String initState = "init";
    private volatile String initDetail = "";
    private volatile File vdDir;
    private volatile boolean ffmpegOk = false, aria2Ok = false;

    public LocalEngine(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        sActive = this;   // 接管守护进程回调与存量任务（Activity 重建后由新实例顶上）
        this.prefs = ctx.getSharedPreferences("vd", Context.MODE_PRIVATE);
        int n = 2;
        try {
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            ((android.app.ActivityManager) this.ctx.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
            long gb = mi.totalMem >> 30;
            if (gb >= 10) n = 4;
            else if (gb >= 6) n = 3;
        } catch (Throwable ignored) {}
        slotCount = n;
        synchronized (LocalEngine.class) {
            if (sharedSlots == null) sharedSlots = new java.util.concurrent.Semaphore(n);
            slots = sharedSlots;
        }
    }

    public String state() { return initState; }
    public String detail() { return initDetail; }
    public File dir() { return vdDir; }

    public String activeTaskIds() {
        java.util.HashSet<String> ids = new java.util.HashSet<>(threads.keySet());
        ids.addAll(dTasks.keySet());
        ids.addAll(finishingTasks.keySet());
        return new JSONArray(new ArrayList<>(ids)).toString();
    }

    public String taskStateSnapshots() {
        return new JSONArray(new ArrayList<>(pendingTaskStates.values())).toString();
    }

    public void acknowledgeTask(String id) {
        pendingTaskStates.remove(id);
    }

    /** v1.99：Activity 销毁时解绑——后台任务还在跑也不能往已销毁的 WebView/Activity 上推事件
     *  （listener 捕获整棵 View 树成泄漏；对已 destroy 的 WebView evaluateJavascript 还会崩）。
     *  守护进程是 static 的照常活；Activity 重建后新实例的监听器顶上 */
    private volatile boolean detached = false;
    public void detach() {
        detached = true;
        if (sActive == this) sActive = null;   // 让位给下一个引擎实例
    }

    /** 页面/桥传来的相对文件名防穿越校验：带路径分隔符或 ".." 的一律拒绝。
     *  转码/探测/删除这些"拿名字直接拼路径"的入口都要过一遍，否则网页侧一个恶意名字
     *  就能变成任意路径读写删 */
    private static boolean badRel(String name) {
        return name == null || name.contains("/") || name.contains("\\") || name.contains("..");
    }

    /** 相册默认目录（DCIM/Camera）——dcim 模式 / 迁移判断的基准 */
    private static File galleryDir() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera");
    }

    /** 保存目录模式 → 目录：download=下载/vd / private=应用私有 / custom=自选目录（dcim 仅内部兼容保留） */
    private File dirForMode(String mode) {
        if ("dcim".equals(mode)) return galleryDir();
        if ("private".equals(mode)) return new File(ctx.getExternalFilesDir(null), "vd");
        if ("custom".equals(mode)) {
            String p = prefs.getString("vd_dir_custom", null);
            if (p != null && p.length() > 1) return new File(p);
        }
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "vd");
    }

    private static File probeWritable(File d) {
        try {
            d.mkdirs();
            File probe = new File(d, ".write-probe");
            probe.delete();   // 上次崩溃残留的探针文件会让 createNewFile 永远 false → 好目录被误判只读
            if (probe.createNewFile()) { probe.delete(); return d; }
        } catch (Throwable ignored) {}
        return null;
    }

    public String dirMode() { return prefs.getString("vd_dir_mode", "download"); }

    /** 本 App 下载过的文件名单（SharedPreferences 持久化）：切目录只搬这些，相机内容绝不碰 */
    private java.util.HashSet<String> ownedSet() {
        java.util.HashSet<String> s = new java.util.HashSet<>();
        try { java.util.Set<String> p = prefs.getStringSet("vd_owned", null); if (p != null) s.addAll(p); } catch (Throwable ignored) {}
        return s;
    }

    private void markOwned(List<String> names) {
        mediaRevision.incrementAndGet();
        try {
            synchronized (OWNED_LOCK) {
                java.util.HashSet<String> s = ownedSet();
                boolean ch = false;
                for (String n : names) if (n != null && !n.startsWith(".") && s.add(n)) ch = true;
                if (ch) prefs.edit().putStringSet("vd_owned", new java.util.HashSet<>(s)).apply();
            }
        } catch (Throwable ignored) {}
    }

    /** dcim 模式首次启用时认领目录里"非相机命名"的媒体文件（1.24/1.25 存进去的下载都认回来）。只跑一次 */
    private void seedOwnership(File dir) {
        try {
            if (!dir.isDirectory()) return;
            if (prefs.getBoolean("vd_seeded", false)) return;
            ArrayList<String> mine = new ArrayList<>();
            File[] all = dir.listFiles();
            if (all != null) {
                for (File f : all) {
                    String n = f.getName();
                    if (!f.isFile() || n.startsWith(".")) continue;
                    if (!MEDIA_NAME.matcher(n).matches()) continue;
                    if (CAMERA_NAME.matcher(n).find()) continue;   // 相机拍的绝不算本 App 的
                    mine.add(n);
                }
            }
            markOwned(mine);
            prefs.edit().putBoolean("vd_seeded", true).apply();
        } catch (Throwable ignored) {}
    }

    /** 切换保存目录：只搬本 App 下载的文件（名单制），相机图库内容绝不碰。失败返回 null */
    public String setDirMode(String mode) {
        if ("custom".equals(mode)) {
            String p = prefs.getString("vd_dir_custom", null);
            return p == null ? null : setDirCustom(p);
        }
        if (!"dcim".equals(mode) && !"download".equals(mode) && !"private".equals(mode)) return null;
        return setDirTo(mode, dirForMode(mode), null);
    }

    /** 自选目录：/storage 下任意可写目录（页面里用 listDirs 浏览选择） */
    public String setDirCustom(String path) {
        try {
            if (path == null || path.trim().length() < 2) return null;
            File want = new File(path.trim());
            if (!want.isAbsolute() || !want.getPath().startsWith("/storage")) return null;
            return setDirTo("custom", want, want.getAbsolutePath());
        } catch (Throwable t) {
            return null;
        }
    }

    private String setDirTo(String mode, File want, String customPath) {
        synchronized (SVC_LOCK) {
            if (svcCount > 0 || !threads.isEmpty() || !dTasks.isEmpty()) return null;
            try {
                if (!"private".equals(mode)) {
                    if (probeWritable(want) == null) return null;   // 没授权/不可写
                } else {
                    want.mkdirs();
                }
                File old = vdDir;
                if (old != null && old.isDirectory() && !old.getCanonicalPath().equals(want.getCanonicalPath())) {
                    if (!moveOwnedFiles(old, want, ownedSet())) return null;
                    File thumbs = new File(old, ".thumbs");
                    if (thumbs.isDirectory()) { File[] kids = thumbs.listFiles(); if (kids != null) for (File k : kids) k.delete(); thumbs.delete(); }
                    File probe2 = new File(old, ".write-probe");
                    if (probe2.exists()) probe2.delete();
                    // 旧目录不删：里面可能还有不属于本 App 的内容（自定义目录/相册目录绝不能删）
                }
                vdDir = want;
                try { dDirHistory.add(want.getCanonicalPath()); } catch (Throwable ignored) {}   // v1.69：切走的目录也要能被清扫
                initDetail = want.getAbsolutePath();
                android.content.SharedPreferences.Editor ed = prefs.edit().putString("vd_dir_mode", mode);
                if (customPath != null) ed.putString("vd_dir_custom", customPath);
                ed.apply();
                ensureThumbDir();
                absorbLegacyDir(want);   // 老版本 下载/vd 的遗留文件也一并收进来（目录相同则内部去重）
                scanAllToGallery();
                return want.getAbsolutePath();
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /** 保持文件名与任务记录一致。同名冲突先拒绝，搬移失败时撤回已搬的文件。 */
    private static boolean moveOwnedFiles(File old, File want, java.util.Set<String> own) {
        File[] all = old.listFiles();
        if (all == null) return false;
        ArrayList<File> pending = new ArrayList<>();
        for (File file : all) {
            String name = file.getName();
            if (!file.isFile() || name.startsWith(".") || !own.contains(name)) continue;
            if (new File(want, name).exists()) return false;
            pending.add(file);
        }
        ArrayList<File> moved = new ArrayList<>();
        for (File file : pending) {
            if (!file.renameTo(new File(want, file.getName()))) {
                for (File previous : moved) {
                    new File(want, previous.getName()).renameTo(previous);
                }
                return false;
            }
            moved.add(file);
        }
        return true;
    }

    /** 封面目录 + .nomedia：缩略图不进相册。切目录后新目录也要有 */
    private void ensureThumbDir() {
        try {
            File tdir = new File(vdDir, ".thumbs");
            tdir.mkdirs();
            File nomedia = new File(tdir, ".nomedia");
            if (!nomedia.exists()) nomedia.createNewFile();
        } catch (Throwable ignored) {}
    }

    /** 封面补齐队列：双线程抽帧（单线程时几十个存量视频要排队十几分钟，看着像"没生成"）。
     *  static：之前每个引擎实例各建一对常驻线程，Activity 每重建一次就漏 2 个 */
    private static final java.util.concurrent.ExecutorService thumbExec =
        java.util.concurrent.Executors.newFixedThreadPool(2);

    /** MediaMetadataRetriever 会在个别 ROM/编码上卡死（不抛错不返回），放独立线程池并限时——
     *  卡住只废掉那一个池线程，封面队列照常推进（否则一个坏文件堵死全部封面） */
    private static final java.util.concurrent.ExecutorService mmrPool =
        new java.util.concurrent.ThreadPoolExecutor(0, 2, 30, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.SynchronousQueue<Runnable>());

    /** 系统解码器给视频抽一帧当封面（宽边压到 360）。部分 ROM 对某些编码静默失败 → 返回 false 由上层退 ffmpeg */
    private static boolean extractFrameMmr(File dir, String name, File out) {
        android.media.MediaMetadataRetriever mmr = null;
        try {
            if (dir == null || !isVideoName(name)) return false;
            File src = new File(dir, name);
            if (!src.isFile() || src.length() == 0) return false;
            mmr = new android.media.MediaMetadataRetriever();
            mmr.setDataSource(src.getAbsolutePath());
            android.graphics.Bitmap bmp = mmr.getFrameAtTime(1000000);
            if (bmp == null) bmp = mmr.getFrameAtTime(0);   // 1 秒处可能没帧（极短视频）
            if (bmp == null) return false;
            int w = bmp.getWidth(), h = bmp.getHeight();
            int edge = Math.max(w, h);
            if (edge > 360) {
                android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(bmp, Math.max(1, w * 360 / edge), Math.max(1, h * 360 / edge), true);
                if (scaled != bmp) bmp.recycle();
                bmp = scaled;
            }
            java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
            try { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, fo); }
            finally { try { fo.close(); } catch (Throwable ignored) {} bmp.recycle(); }
            return out.isFile() && out.length() > 0;
        } catch (Throwable t) {
            return false;
        } finally {
            if (mmr != null) { try { mmr.release(); } catch (Throwable ignored) {} }
        }
    }

    /** ffmpeg 兜底抽帧：自带超时强杀，任何解码器怪癖都杀不死它 */
    private boolean ffmpegThumb(File src, File out) {
        try {
            String[] seeks = {"1", "0"};   // 1 秒处抽不到（极短视频）就从 0 秒再试一次
            for (String ss : seeks) {
                out.delete();
                List<String> args = new ArrayList<>();
                args.add("-hide_banner");
                args.add("-nostats");
                args.add("-loglevel");
                args.add("error");
                args.add("-y");
                args.add("-ss");
                args.add(ss);
                args.add("-i");
                args.add(src.getAbsolutePath());
                args.add("-frames:v");
                args.add("1");
                args.add("-vf");
                args.add("scale=360:360:force_original_aspect_ratio=decrease");
                args.add("-q:v");
                args.add("4");
                args.add(out.getAbsolutePath());
                runFfmpeg(args, 25000);
                if (out.isFile() && out.length() > 0) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** 封面生成在飞去重（v1.99）：正在生成中的封面不满足"已存在且≥1.5KB"的跳过条件，
     *  连刷几次列表会堆出多个 ffmpeg 抢写同一个 jpg——CPU 白烧还可能互相覆盖出坏图 */
    private static final java.util.Set<String> thumbQueued = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private static final java.util.Set<String> thumbInflight = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /** 生成一个视频的封面：mmr（快）→ ffmpeg（稳）。已有且健康的封面直接跳过
     *  （v2.44：旧文件 <1.5KB 视为损坏——曾被写坏过的封面不再永远挡着重新生成） */
    private static String thumbStamp(File source) {
        if (!source.isFile()) return "";
        try {
            return "v2|" + source.length() + "|" + source.lastModified() + "|"
                + android.system.Os.stat(source.getAbsolutePath()).st_ino;
        } catch (Exception ignored) { return ""; }
    }

    public static boolean validThumb(File dir, String name) {
        File image = new File(new File(dir, ".thumbs"), name + ".jpg");
        if (!image.isFile() || image.length() == 0) return false;
        String expected = thumbStamp(new File(dir, name));
        try {
            try (java.io.DataInputStream input = new java.io.DataInputStream(new java.io.FileInputStream(image.getPath() + ".stamp"))) {
                return !expected.isEmpty() && expected.equals(input.readUTF());
            }
        } catch (Exception ignored) { return false; }
    }

    private static void invalidateThumb(File dir, String name) {
        File image = new File(new File(dir, ".thumbs"), name + ".jpg");
        image.delete();
        new File(image.getPath() + ".stamp").delete();
    }

    private static boolean publishThumb(File dir, String name, String stamp, File temp) {
        synchronized (FILE_COMMIT_LOCK) {
            if (stamp.isEmpty() || !stamp.equals(thumbStamp(new File(dir, name))) || temp.length() == 0) return false;
            File image = new File(new File(dir, ".thumbs"), name + ".jpg");
            invalidateThumb(dir, name);
            if (!temp.renameTo(image)) return false;
            try {
                try (java.io.DataOutputStream output = new java.io.DataOutputStream(new java.io.FileOutputStream(image.getPath() + ".stamp"))) {
                    output.writeUTF(stamp);
                }
                return true;
            } catch (Exception ignored) { image.delete(); return false; }
        }
    }

    private void genThumb(File dir, String name) {
        String key = dir.getAbsolutePath() + "/" + name;
        if (validThumb(dir, name) || !thumbInflight.add(key)) return;
        File temp = null;
        try {
            File src = new File(dir, name);
            String stamp = thumbStamp(src);
            if (stamp.isEmpty() || !isVideoName(name)) return;
            File cache = new File(dir, ".thumbs");
            cache.mkdirs();
            final File nativeTemp = File.createTempFile("frame-", ".jpg", cache);
            java.util.concurrent.Future<Boolean> future = null;
            boolean ok = false;
            try {
                future = mmrPool.submit(() -> {
                    try {
                        boolean result = extractFrameMmr(dir, name, nativeTemp);
                        if (Thread.currentThread().isInterrupted()) { nativeTemp.delete(); return false; }
                        return result;
                    } finally { if (Thread.currentThread().isInterrupted()) nativeTemp.delete(); }
                });
                ok = future.get(12, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
                if (future != null) future.cancel(true);
            }
            if (ok) {
                try { publishThumb(dir, name, stamp, nativeTemp); }
                finally { nativeTemp.delete(); }
            } else {
                nativeTemp.delete();
                temp = File.createTempFile("fallback-", ".jpg", cache);
                if (ffmpegThumb(src, temp)) publishThumb(dir, name, stamp, temp);
            }
        } catch (Exception ignored) {
        } finally {
            if (temp != null) temp.delete();
            thumbInflight.remove(key);
        }
    }

    /** 下载完成/列表刷新后补缺失的封面：页面渲染前就有了，不再依赖 404 重试碰运气 */
    public void ensureThumbs(final List<String> names) {
        if (vdDir == null || names == null || names.isEmpty()) return;
        final File directory = vdDir;
        for (String name : names) {
            if (name == null || name.contains("/") || name.contains("\\") || !isVideoName(name)) continue;
            File thumbnail = new File(new File(directory, ".thumbs"), name + ".jpg");
            if (validThumb(directory, name)) continue;
            final String key = directory.getAbsolutePath() + "/" + name;
            if (!thumbQueued.add(key)) continue;
            try {
                thumbExec.submit(() -> {
                    try { genThumb(directory, name); }
                    finally { thumbQueued.remove(key); }
                });
            } catch (java.util.concurrent.RejectedExecutionException ignored) { thumbQueued.remove(key); }
        }
    }

    /** 「仅删记录」的隐藏名单（页面经 kvSet 写到 kv_vd_hidden）：文件保留在磁盘，只是不再列出 */
    private java.util.HashSet<String> hiddenNames() {
        java.util.HashSet<String> s = new java.util.HashSet<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("kv_vd_hidden", "[]"));
            for (int i = 0; i < a.length(); i++) s.add(a.getString(i));
        } catch (Throwable ignored) {}
        return s;
    }

    /** 视频存放目录：按设置的模式选；所选位置不可写时依次退到 下载/vd、应用私有目录 */
    private File pickDir() {
        File want = probeWritable(dirForMode(dirMode()));
        if (want != null) return want;
        want = probeWritable(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "vd"));
        if (want != null) return want;
        File priv = new File(ctx.getExternalFilesDir(null), "vd");
        priv.mkdirs();
        return priv;
    }

    /** 老版本目录（下载/vd）里的媒体文件平移进新目录：目录换了之后「我的保存」跟着当前目录走，
     *  不搬的话旧下载会集体"消失"。搬完顺手清掉旧目录。搬动的文件同时登记归属 */
    private int absorbLegacyDir(File pub) {
        int n = 0;
        try {
            File legacy = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "vd");
            if (legacy.isDirectory() && !pub.getCanonicalPath().equals(legacy.getCanonicalPath())) {
                File[] olds = legacy.listFiles();
                ArrayList<String> moved = new ArrayList<>();
                if (olds != null) {
                    for (File f : olds) {
                        if (f.isDirectory() || f.getName().startsWith(".")) continue;
                        if (!MEDIA_NAME.matcher(f.getName()).matches()) continue;
                        String target = commitFile(f, pub, f.getName());
                        if (target != null) { n++; moved.add(target); }
                    }
                }
                markOwned(moved);
                File thumbs = new File(legacy, ".thumbs");
                if (thumbs.isDirectory()) { File[] kids = thumbs.listFiles(); if (kids != null) for (File k : kids) k.delete(); thumbs.delete(); }
                File probe = new File(legacy, ".write-probe");
                if (probe.exists()) probe.delete();
                legacy.delete();   // 搬空的删掉；没搬空的（重命名失败）留着下次再试
            }
        } catch (Throwable ignored) {}
        return n;
    }

    /** 引擎自带的 aria2c 命令行二进制（youtubedl-android 的 aria2c 模块提供，install 时已解压） */
    private String aria2cBin() {
        return ctx.getApplicationInfo().nativeLibraryDir + "/libaria2c.so";
    }

    // ================= 落地格式归一（APK 自带 ffmpeg CLI）：视频→H.264 mp4，图片→jpg =================
    // 有些视频是 HEVC/VP9 编码或 webm/mkv 容器，部分手机播放器/图库认不了；webp/avif 图片同理。
    // 下载完自动归一成"万能格式"，任何 App 都能打开。ffmpeg 失败时保留原文件，绝不丢东西。

    /** ffmpeg 可执行文件（youtubedl-android 的 ffmpeg 模块就是个能跑的程序，yt-dlp 也用它合并） */
    private String ffmpegBin() {
        return new File(ctx.getApplicationInfo().nativeLibraryDir, "libffmpeg.so").getAbsolutePath();
    }

    /** 跑一次 ffmpeg，返回合并输出（探测类调用只要输出），超时强杀 */
    private String runFfmpeg(List<String> args, long timeoutMs) {
        return runFfmpegFull(args, timeoutMs).out;
    }

    private static class FfmpegResult {
        int code = -1;   // -1 = 没等到正常退出（超时被杀/启动失败），产物绝不能当成功
        String out = "";
    }

    /** 跑一次 ffmpeg 并给出退出码。转码类调用必须检查 code==0：超时被杀也会留下"非空但半截"
     *  的产物，只看文件存在与否会把废品当成品（曾把转码超时的半截 mp4 顶掉完好的原文件） */
    private FfmpegResult runFfmpegFull(List<String> args, long timeoutMs) {
        FfmpegResult r = new FfmpegResult();
        Process p = null;
        try {
            if (nativeDirPath == null) computeRuntimePaths();
            List<String> cmd = new ArrayList<>();
            cmd.add(ffmpegBin());
            cmd.addAll(args);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (ldLibPath != null) pb.environment().put("LD_LIBRARY_PATH", ldLibPath);
            pb.redirectErrorStream(true);
            // v1.99：输出捕获封顶 256KB（超了丢头部留尾部——ffmpeg 的报错都在末尾）。
            // 之前无上限：30 分钟转码约 3600 行 stats 全被捕获；管道写满 64KB 还会反过来阻塞 ffmpeg
            final java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            p = pb.start();
            final Process proc = p;
            Thread rd = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        java.io.InputStream in = proc.getInputStream();
                        byte[] b = new byte[8192];
                        int n;
                        while ((n = in.read(b)) > 0) {
                            synchronized (bos) {
                                bos.write(b, 0, n);
                                if (bos.size() > 262144) {
                                    byte[] all = bos.toByteArray();
                                    bos.reset();
                                    bos.write(all, all.length - 131072, 131072);
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }, "vd-ffmpeg-read");
            rd.setDaemon(true);
            rd.start();
            boolean done = false;
            try { done = FfmpegTool.waitForProcess(proc, timeoutMs); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            if (done) r.code = proc.exitValue();
            try { FfmpegTool.destroyProcess(proc); } catch (Throwable ignored) {}
            synchronized (bos) { r.out = new String(bos.toByteArray(), "UTF-8"); }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "ffmpeg run failed: " + args, t);
        } finally {
            if (p != null) { try { FfmpegTool.destroyProcess(p); } catch (Exception ignored) {} }
        }
        return r;
    }

    /** 探测结果缓存（v1.99）：path+mtime → {videoCodec, audioCodec, durSec}。
     *  probeVideoCodec 每次打开视频、归一/裁剪/静音检测链路一个视频最多探 8 次，
     *  同一文件转码前后内容变了 mtime 也变，缓存自动失效 */
    private static final Map<String, String[]> probeCache = new ConcurrentHashMap<>();

    /** 探测媒体：返回 {videoCodec, audioCodec, durSec}；探测失败返回 null。
     *  v1.99：首选进程内 MediaExtractor（10-50ms）——冷启动一个 ffmpeg 要 200-400ms，
     *  每视频收尾最多 7-8 次；MediaExtractor 认不出（冷门容器/损坏文件）再退 ffmpeg */
    private String[] probeMedia(String path) {
        String key = null;
        try {
            File pf = new File(path);
            key = path + "|" + pf.lastModified();
        } catch (Throwable ignored) {}
        if (key != null) {
            String[] hit = probeCache.get(key);
            if (hit != null) return hit;
        }
        String[] r = probeMediaExtract(path);
        if (r == null) r = probeMediaFfmpeg(path);
        if (key != null) {
            try {
                if (probeCache.size() > 64) probeCache.clear();
                probeCache.put(key, r);
            } catch (Throwable ignored) {}
        }
        return r;
    }

    private static String[] probeMediaExtract(String path) {
        java.util.concurrent.Future<String[]> future = null;
        try {
            future = mmrPool.submit(new java.util.concurrent.Callable<String[]>() {
                @Override public String[] call() {
                    android.media.MediaExtractor ex = null;
                    try {
                        ex = new android.media.MediaExtractor();
                        ex.setDataSource(path);
                        String vc = null, ac = "";
                        long durUs = 0;
                        int n = ex.getTrackCount();
                        for (int i = 0; i < n; i++) {
                            android.media.MediaFormat f = ex.getTrackFormat(i);
                            String mime = f.getString(android.media.MediaFormat.KEY_MIME);
                            if (mime == null) continue;
                            if (vc == null && mime.startsWith("video/")) vc = mapVideoMime(mime);
                            else if (ac.isEmpty() && mime.startsWith("audio/")) ac = mapAudioMime(mime);
                            try {   // 时长在轨道格式里（微秒）；个别容器不带这个键
                                if (f.containsKey(android.media.MediaFormat.KEY_DURATION))
                                    durUs = Math.max(durUs, f.getLong(android.media.MediaFormat.KEY_DURATION));
                            } catch (Throwable ignored) {}
                        }
                        if (vc == null) return null;   // 没认出视频轨：交给 ffmpeg 再看
                        return new String[]{ vc, ac, String.valueOf(durUs / 1000000L) };
                    } catch (Throwable t) {
                        return null;
                    } finally {
                        if (ex != null) { try { ex.release(); } catch (Throwable ignored) {} }
                    }
                }
            });
            return future.get(8, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable t) {
            if (future != null) future.cancel(true);
            return null;
        }
    }

    /** mime → ffmpeg 风格的编解码名（页面/归一判定只认 h264/mpeg4 这类名字） */
    private static String mapVideoMime(String mime) {
        if (mime.contains("avc")) return "h264";
        if (mime.contains("hevc")) return "hevc";
        if (mime.contains("vp9")) return "vp9";
        if (mime.contains("vp8")) return "vp8";
        if (mime.contains("av01")) return "av01";
        if (mime.contains("mp4v")) return "mpeg4";
        return mime.length() > 6 ? mime.substring(6) : mime;
    }

    private static String mapAudioMime(String mime) {
        if (mime.contains("mp4a")) return "aac";
        if (mime.contains("mpeg")) return "mp3";
        return mime.length() > 6 ? mime.substring(6) : mime;
    }

    /** ffmpeg 探测兜底（输出行是 info 级，不能加 -loglevel error；只加 -nostats） */
    private String[] probeMediaFfmpeg(String path) {
        List<String> args = new ArrayList<>();
        args.add("-hide_banner");
        args.add("-nostats");
        args.add("-i");
        args.add(path);
        String out = runFfmpeg(args, 10000);
        java.util.regex.Matcher mv = java.util.regex.Pattern.compile("Stream #\\d+:\\d+.*?: Video: (\\w+)").matcher(out);
        if (!mv.find()) return null;
        java.util.regex.Matcher ma = java.util.regex.Pattern.compile("Stream #\\d+:\\d+.*?: Audio: (\\w+)").matcher(out);
        java.util.regex.Matcher md = java.util.regex.Pattern.compile("Duration: (\\d+):(\\d+):(\\d+)").matcher(out);
        long dur = 0;
        if (md.find()) dur = Long.parseLong(md.group(1)) * 3600 + Long.parseLong(md.group(2)) * 60 + Long.parseLong(md.group(3));
        return new String[]{ mv.group(1).toLowerCase(java.util.Locale.ROOT), ma.find() ? ma.group(1).toLowerCase(java.util.Locale.ROOT) : "", String.valueOf(dur) };
    }

    private static boolean isVideoName(String n) {
        return n != null && VIDEO_NAME_P.matcher(n).matches();
    }

    private static boolean isImageName(String n) {
        return n != null && IMAGE_NAME_P.matcher(n).matches();
    }

    /** 页内播放黑屏自救（v2.39）：把 WebView 解不了的编码（HEVC 等）转成 H.264/AAC mp4。
     *  返回新文件名（已是万能格式则原样返回），异步调用，进度经 merging 状态静默推进 */
    public String transcodeToH264(final String name) {
        String out = normalizeVideo(name, "tc" + Long.toString(System.currentTimeMillis(), 36), null, true);
        if (out != null && !out.equals(name)) {   // 转出来的新文件补登记，别在「我的保存」里消失
            try {
                ArrayList<String> one = new ArrayList<>();
                one.add(out);
                scanToGallery(one);
                markOwned(one);
                ensureThumbs(one);
            } catch (Throwable ignored) {}
        }
        return out;
    }

    /** v2.44：给页面查视频编码（h264/hevc/…）。打开视频先看这个——不是 h264/mpeg4 就先转码再播，
     *  不再赌"播放后帧数检测"（个别 ROM 的 WebView 会谎报解码帧数，黑屏检测永远不触发） */
    public String probeVideoCodec(String name) {
        try {
            if (badRel(name) || vdDir == null) return "";
            File in = new File(vdDir, name);
            if (!in.isFile() || !isVideoName(name)) return "";
            String[] p = probeMedia(in.getAbsolutePath());
            return p != null ? p[0] : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** 视频归一成 H.264/AAC 的 mp4。已合规原样返回名；容器不对→秒级 remux；编码不对→转码。
     *  任何失败都返回原文件名（原文件绝不删）。需要干活时先发 merging 状态（卡片显示 合并转码中…） */
    private String normalizeVideo(final String name, final String id, final String title) {
        return normalizeVideo(name, id, title, false);
    }

    private String normalizeVideo(final String name, final String id, final String title, boolean manual) {
        try {
            if (badRel(name) || vdDir == null) return name;
            File in = new File(vdDir, name);
            if (!in.isFile() || in.length() == 0 || !isVideoName(name)) return name;
            if (in.length() > 700L * 1024 * 1024) return name;   // 太大：手机软转太久，保持原样
            String low = name.toLowerCase(java.util.Locale.ROOT);
            String[] p = probeMedia(in.getAbsolutePath());
            if (p == null) return name;
            String vc = p[0], ac = p[1];
            // Native playback supports HEVC. Preserve it when this device has a decoder.
            if (!manual && "hevc".equals(vc) && low.endsWith(".mp4")
                && (ac.isEmpty() || "aac".equals(ac) || "mp3".equals(ac))) {
                android.media.MediaFormat format = android.media.MediaFormat.createVideoFormat("video/hevc", 1920, 1080);
                if (new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) != null) return name;
            }
            long durSec = Long.parseLong(p[2]);
            boolean vOk = "h264".equals(vc);
            boolean aOk = ac.isEmpty() || "aac".equals(ac) || "mp3".equals(ac);
            if (vOk && aOk && low.endsWith(".mp4")) return name;   // 已经是万能格式
            final String t = title == null ? titleFromName(name) : title;
            dPostState(id, "merging", t, null, null);
            File out = new File(vdDir, ".tmp-" + id + "-" + Integer.toHexString(name.hashCode()) + ".mp4");
            out.delete();
            long timeout = Math.max(90000, Math.min(1800000, durSec * 6000));   // 转码限时：按时长估
            FfmpegResult fr = runFfmpegFull(argsFor(in, out, vOk, aOk, ac, false), timeout);
            boolean ok = fr.code == 0 && out.isFile() && out.length() > 0;
            if (!ok && !vOk) {   // libx264 不在这个 ffmpeg 构建里 → 用内置 mpeg4 编码器兜底
                out.delete();
                fr = runFfmpegFull(argsFor(in, out, vOk, aOk, ac, true), timeout);
                ok = fr.code == 0 && out.isFile() && out.length() > 0;
            }
            if (ok) {
                // v2.42 产物验证：ffmpeg 退出≠产出合规（个别 ROM 上半途而废也能留下非空文件），
                // 转完再 probe 一次，视频轨不是 h264/mpeg4 就当失败——绝不能拿一个"转了但没转对"
                // 的文件冒充 H.264 交给页面（黑屏会更难排查）
                String[] q = probeMedia(out.getAbsolutePath());
                String nvc = q != null ? q[0] : "";
                long odur = -1;
                try { if (q != null) odur = Long.parseLong(q[2]); } catch (Throwable ignored) {}
                if (!"h264".equals(nvc) && !"mpeg4".equals(nvc)) { out.delete(); return name; }
                // 时长对不上 = 转码中途断掉（超时被杀）：半截成品绝不能顶掉完好的原文件
                if (durSec > 0 && odur >= 0 && odur < durSec * 9 / 10) { out.delete(); return name; }
                // 先挪成功再删原文件：挪失败时原视频还在
                String outName = commitFile(out, vdDir, name.replaceAll("\\.[^.]+$", "") + ".mp4");
                if (outName != null) { in.delete(); return outName; }
                out.delete();
                return name;
            }
            out.delete();
            return name;
        } catch (Throwable t) {
            return name;
        }
    }

    private static List<String> argsFor(File in, File out, boolean vOk, boolean aOk, String ac, boolean mpeg4) {
        List<String> args = new ArrayList<>();
        args.add("-hide_banner");
        args.add("-nostats");
        args.add("-loglevel");
        args.add("error");   // 转码输出没人看：默认每 0.5s 一行 stats 白捕获几 MB
        args.add("-y");
        args.add("-i");
        args.add(in.getAbsolutePath());
        if (vOk && aOk) {
            args.add("-c"); args.add("copy");   // 编码都合规只是容器不对：秒级 remux
        } else {
            if (vOk) { args.add("-c:v"); args.add("copy"); }
            else if (mpeg4) { args.add("-c:v"); args.add("mpeg4"); args.add("-q:v"); args.add("4"); }
            else { args.add("-c:v"); args.add("libx264"); args.add("-preset"); args.add("veryfast"); args.add("-crf"); args.add("23"); }
            if (ac.isEmpty()) args.add("-an");
            else if (aOk) { args.add("-c:a"); args.add("copy"); }
            else { args.add("-c:a"); args.add("aac"); args.add("-b:a"); args.add("128k"); }
            args.add("-movflags"); args.add("+faststart");
        }
        args.add(out.getAbsolutePath());
        return args;
    }

    /** 抖音片尾推广自动裁剪（v2.42 三重判定）：抖音直链视频常在正片后拼一段"博主名片"尾巴。
     *  ① API 时长已知 → 文件比它多出来的就是尾巴，精确切（窗口放宽到 0.8~20 秒）；
     *  ② 尾部静态卡片检测（名片常常有轻动画，v2.42 放宽了"之后不动"的阈值）；
     *  ③ 尾部静音检测（名片基本没人声/BGM 收尾）：最后一段 ≥1.2 秒的静音一路铺到片尾 → 从静音起点切。
     *  判不出/切不动都原样返回名，原文件绝不丢 */
    private String trimDouyinTail(final String name, final String id, long apiDurMs) {
        try {
            File in = new File(vdDir, name);
            if (!in.isFile() || in.length() < 300000 || !isVideoName(name)) return name;
            String[] p = probeMedia(in.getAbsolutePath());
            if (p == null) return name;
            long durSec = Long.parseLong(p[2]);
            if (durSec < 15 || durSec > 1200) return name;   // 太短没尾巴可言，太长检测窗口没意义
            double cut = 0;
            double api = apiDurMs / 1000.0;
            if (api >= 3 && durSec - api >= 0.8 && durSec - api <= 20) {
                cut = api + 0.12;   // 正片时长已知：多出来的整段都是尾巴
            }
            if (cut <= 1) cut = detectStaticTail(in, durSec);   // 兜底一：内容检测
            if (cut <= 1) cut = detectSilenceTail(in, durSec);  // 兜底二：片尾静音
            if (cut <= 1 || cut >= durSec - 0.8) return name;
            dPostState(id, "merging", "正在裁掉片尾推广…", null, null);
            File out = new File(vdDir, ".tmp-trim-" + id + ".mp4");
            out.delete();
            List<String> args = new ArrayList<>();
            args.add("-hide_banner");
            args.add("-nostats");
            args.add("-loglevel");
            args.add("error");
            args.add("-y");
            args.add("-i");
            args.add(in.getAbsolutePath());
            args.add("-t");
            args.add(String.format(java.util.Locale.US, "%.2f", cut));
            args.add("-c");
            args.add("copy");
            args.add("-movflags");
            args.add("+faststart");
            args.add(out.getAbsolutePath());
            runFfmpeg(args, 120000);
            // -c copy 只能切到关键帧：尾巴残留超过 1 秒就重编码精确切一次
            String[] q = out.isFile() && out.length() > 0 ? probeMedia(out.getAbsolutePath()) : null;
            double od = q != null && q[2] != null ? Double.parseDouble(q[2]) : -1;
            int code2 = 0;
            if (od >= 0 && cut - od > 1.0) {
                out.delete();
                args = new ArrayList<>();
                args.add("-hide_banner");
                args.add("-nostats");
                args.add("-loglevel");
                args.add("error");
                args.add("-y");
                args.add("-i");
                args.add(in.getAbsolutePath());
                args.add("-t");
                args.add(String.format(java.util.Locale.US, "%.2f", cut));
                args.add("-c:v");
                args.add("libx264");
                args.add("-preset");
                args.add("veryfast");
                args.add("-crf");
                args.add("23");
                args.add("-c:a");
                args.add("aac");
                args.add("-b:a");
                args.add("128k");
                args.add("-movflags");
                args.add("+faststart");
                args.add(out.getAbsolutePath());
                code2 = runFfmpegFull(args, 300000).code;
            }
            // 切坏了/切空了都不要：产物至少得有原文件的一半大；重编码中途被杀（超时）的半截
            // 成品体积往往达标，必须看退出码——宁可不裁，绝不拿废品顶掉原片
            if (code2 != 0 || !out.isFile() || out.length() == 0 || out.length() < in.length() / 2) { out.delete(); return name; }
            String outName = commitFile(out, vdDir, name.replaceAll("\\.[^.]+$", "") + ".mp4");
            if (outName != null) { in.delete(); return outName; }
            out.delete();
            return name;
        } catch (Throwable t) {
            return name;
        }
    }

    /** 尾部静态卡片检测：抽最后 ~12 秒（2fps、48x27 灰度）原始帧（输出端 seek，帧级准确），
     *  找最后一处"硬切"边界，边界之后帧间差几乎为 0（静态推广卡）才认定是尾巴。
     *  返回应保留的秒数；判不出返回 0 */
    private double detectStaticTail(File in, long durSec) {
        File raw = null;
        try {
            double win = 12.0;
            if (durSec <= win + 4) win = Math.max(4.0, durSec / 2);
            double start = Math.max(0, durSec - win - 0.4);
            raw = new File(vdDir, ".tmp-tail-" + Integer.toHexString(in.getName().hashCode()) + ".raw");
            raw.delete();
            List<String> args = new ArrayList<>();
            args.add("-hide_banner");
            args.add("-nostats");
            args.add("-loglevel");
            args.add("error");
            args.add("-y");
            args.add("-i");
            args.add(in.getAbsolutePath());
            args.add("-ss");
            args.add(String.format(java.util.Locale.US, "%.2f", start));
            args.add("-t");
            args.add(String.valueOf((int) Math.ceil(win)));
            args.add("-an");
            args.add("-vf");
            args.add("fps=2,scale=48:27,format=gray");
            args.add("-f");
            args.add("rawvideo");
            args.add(raw.getAbsolutePath());
            runFfmpeg(args, 90000);
            if (!raw.isFile()) return 0;
            final int FS = 48 * 27;
            byte[] b = new byte[(int) Math.min(raw.length(), (long) FS * 40)];
            java.io.FileInputStream fi = new java.io.FileInputStream(raw);
            try {
                int off = 0;
                while (off < b.length) { int n = fi.read(b, off, b.length - off); if (n <= 0) break; off += n; }
            } finally { try { fi.close(); } catch (Throwable ignored) {} }
            int n = b.length / FS;
            if (n < 5) return 0;
            // d[i] = 第 i 帧与第 i+1 帧的平均像素差（0-255）
            double[] d = new double[n - 1];
            for (int i = 0; i < n - 1; i++) {
                long acc = 0;
                for (int j = 0; j < FS; j++) acc += Math.abs((b[i * FS + j] & 0xFF) - (b[(i + 1) * FS + j] & 0xFF));
                d[i] = acc / (double) FS;
            }
            // 从后往前找边界：d[bd] 是硬切（大），其后全部近似静止（小）。
            // v2.42 阈值放宽（v1.39 的 ≥14/≤3.5 在名片带轻动画/渐变时整段判不出）：
            // 硬切 ≥11、边界后帧差 ≤5.5、尾巴 1.2~12 秒
            for (int bd = n - 5; bd >= 0; bd--) {
                if (d[bd] < 11) continue;
                boolean calm = true;
                for (int j = bd + 1; j < n - 1; j++) if (d[j] > 5.5) { calm = false; break; }
                if (!calm) continue;
                double tail = (n - 2 - bd) * 0.5;   // 边界后每帧 0.5 秒
                if (tail < 1.2 || tail > 12) continue;
                return start + (bd + 1) * 0.5 - 0.2;
            }
            return 0;
        } catch (Throwable t) {
            return 0;
        } finally {
            if (raw != null) { try { raw.delete(); } catch (Throwable ignored) {} }
        }
    }

    /** 片尾静音检测（v2.42 第三重）：抖音名片尾巴基本没人声、BGM 也收掉。
     *  对最后 ~16 秒跑 silencedetect，若最后一段 ≥1.2 秒的静音一路铺到片尾，从静音起点切。
     *  返回应保留的秒数；判不出返回 0 */
    private double detectSilenceTail(File in, long durSec) {
        try {
            double win = 16.0;
            if (durSec <= win + 4) win = Math.max(5.0, durSec / 2);
            double start = Math.max(0, durSec - win - 0.4);
            List<String> args = new ArrayList<>();
            args.add("-hide_banner");
            args.add("-nostats");   // silencedetect 的结果行是 info 级，这里不能压 loglevel
            args.add("-ss");
            args.add(String.format(java.util.Locale.US, "%.2f", start));
            args.add("-i");
            args.add(in.getAbsolutePath());
            args.add("-t");
            args.add(String.valueOf((int) Math.ceil(win + 1)));
            args.add("-af");
            args.add("silencedetect=noise=-35dB:d=1.2");
            args.add("-f");
            args.add("null");
            args.add("-");
            String out = runFfmpeg(args, 60000);
            if (out == null || out.isEmpty()) return 0;
            // 解析成对的 silence_start/silence_end：要"贴着片尾结束"的那一段
            java.util.regex.Matcher ms = java.util.regex.Pattern.compile(
                "silence_start: ([0-9.]+)").matcher(out);
            java.util.regex.Matcher me = java.util.regex.Pattern.compile(
                "silence_end: ([0-9.]+)").matcher(out);
            java.util.List<double[]> spans = new java.util.ArrayList<>();
            java.util.List<Double> ss = new java.util.ArrayList<>();
            while (ms.find()) ss.add(Double.parseDouble(ms.group(1)));
            java.util.List<Double> se = new java.util.ArrayList<>();
            while (me.find()) se.add(Double.parseDouble(me.group(1)));
            for (int i = 0; i < ss.size(); i++) {
                double a = ss.get(i);
                double b = i < se.size() ? se.get(i) : (start + win + 1);   // 有始无终：静音一直到截尾
                spans.add(new double[]{ a, b });
            }
            if (spans.isEmpty()) return 0;
            double[] last = spans.get(spans.size() - 1);
            double winEnd = start + win + 1;
            // 最后一段静音要覆盖到窗口末尾（= 覆盖到文件末尾）且长度 ≥1.2 秒
            if (last[1] >= winEnd - 0.8 && last[1] - last[0] >= 1.2 && last[0] >= start + 0.5) {
                double cut = start + last[0] + 0.15;   // -ss 在 -i 前，silencedetect 的时间轴从窗口起点重算，加回偏移；留 0.15 秒余量别切到正片收尾的声音
                if (cut >= 3 && cut < durSec - 0.8) return cut;
            }
            return 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 图片归一成 jpg（webp/avif/heic 部分图库和 App 不认）。成功返回新名，失败返回原名（原文件保留） */
    private String imageToJpg(String name, String id) {
        String low = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        if (!(low.endsWith(".webp") || low.endsWith(".avif") || low.endsWith(".heic") || low.endsWith(".heif"))) return name;
        try {
            File in = new File(vdDir, name);
            // 临时文件名必须带源文件名：图集是并行转换，同一任务 id 下多张图共用一个 tmp 会互相覆盖/抢走
            File out = new File(vdDir, ".tmp-" + id + "-" + Integer.toHexString(name.hashCode()) + ".jpg");
            out.delete();
            List<String> args = new ArrayList<>();
            args.add("-hide_banner");
            args.add("-nostats");
            args.add("-loglevel");
            args.add("error");
            args.add("-y");
            args.add("-i");
            args.add(in.getAbsolutePath());
            args.add("-q:v");
            args.add("3");
            args.add(out.getAbsolutePath());
            if (runFfmpegFull(args, 30000).code == 0 && out.isFile() && out.length() > 0) {
                // 先挪成功再删原文件：挪失败（重名/存储抖动）时原图还在，绝不让用户丢图
                String fin = commitFile(out, vdDir, name.replaceAll("\\.[^.]+$", "") + ".jpg");
                if (fin != null) { in.delete(); return fin; }
                out.delete();
            }
            out.delete();
        } catch (Throwable ignored) {}
        return name;
    }

    public void startInit() {
        new Thread(new Runnable() {
            @Override public void run() {
                // v1.2 启动卡顿治理：引擎初始化（解包二进制/目录搬迁/媒体库补扫）降到后台优先级，
                // 不跟 UI 抢 CPU；它本来就慢几秒无所谓，界面先流畅起来
                try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); } catch (Throwable ignored) {}
                try {
                    String savedMode = prefs.getString("vd_dir_mode", null);
                    if (savedMode == null) {
                        // 1.24~1.25 把文件存在相册目录（DCIM/Camera）。1.26 起默认独立目录 下载/vd：
                        // 先在旧目录认领出本 App 下载的文件（相机命名特征排除），再只搬这些过去，相机内容绝不动
                        File dcim = probeWritable(galleryDir());
                        vdDir = dcim != null ? dcim : pickDir();
                        initDetail = vdDir.getAbsolutePath();
                        if (dcim != null) {
                            seedOwnership(dcim);
                            if (setDirMode("download") == null) setDirMode("dcim");   // 没授权时先留在相册目录
                        } else {
                            prefs.edit().putString("vd_dir_mode", "download").apply();
                            vdDir = pickDir();
                            initDetail = vdDir.getAbsolutePath();
                        }
                    } else {
                        // 1.28 起"相册文件夹"选项已移除：还在 dcim 模式的存量用户自动迁回 下载/vd
                        // （先把 vdDir 指到旧目录，setDirTo 才能按归属名单把文件搬走）
                        if ("dcim".equals(savedMode)) {
                            File g = probeWritable(galleryDir());
                            if (g != null) vdDir = g;
                            if (setDirMode("download") == null && g != null) {
                                vdDir = g;   // 没授权"所有文件访问"：留在原处，等授权后再迁
                            }
                        }
                        if (vdDir == null) vdDir = pickDir();
                        initDetail = vdDir.getAbsolutePath();
                    }
                    try { dDirHistory.add(vdDir.getCanonicalPath()); } catch (Throwable ignored) {}   // v1.69：孤儿 .job-* 清扫覆盖所有用过的目录
                    // 更早版本（≤1.23）存在 下载/vd 的遗留文件收进来
                    if (dirIsPublic()) absorbLegacyDir(vdDir);
                    ensureThumbDir();   // 封面目录加 .nomedia：缩略图不进相册
                    YoutubeDL.getInstance().init(ctx);   // 核心引擎：必须成功
                    try { FFmpeg.getInstance().init(ctx); ffmpegOk = true; } catch (Throwable t) { ffmpegOk = false; }
                    try { Aria2c.getInstance().init(ctx); aria2Ok = true; } catch (Throwable t) { aria2Ok = false; }
                    initState = "ready";
                    // 预热常驻守护进程：App 一启动 Python/yt-dlp 就加载好，用户第一个任务也不用等冷启动。
                    // v1.2：这个预热是启动期最大的 CPU 尖峰（Python 导入 yt-dlp），往后推迟 6 秒 +
                    // 后台优先级——首页渲染和用户第一次点击的窗口让给它
                    new Thread(new Runnable() {
                        @Override public void run() {
                            try {
                                try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); } catch (Throwable ignored) {}
                                Thread.sleep(1500);
                                startDaemon();
                            } catch (Throwable ignored) {}
                        }
                    }, "vd-engine-warm").start();
                } catch (Throwable t) {
                    initState = "error";
                    initDetail = "引擎初始化失败: " + errMsg(t);
                }
                post(new Runnable() {
                    @Override public void run() { listener.onState(null, initState, null, initDetail, null); }
                });
                if ("ready".equals(initState)) {
                    String scannedDirectory = vdDir.getAbsolutePath();
                    if (!scannedDirectory.equals(prefs.getString("vd_media_scan_v1", ""))) {
                        scanAllToGallery();
                        prefs.edit().putString("vd_media_scan_v1", scannedDirectory).apply();
                    }
                }
            }
        }, "vd-engine-init").start();
    }

    /** 提交普通链接。三级路径，快到慢依次尝试，任何一级失败静默落下一级：
     *  ① 抖音分享页快通道（一次请求直出数据，零 Python）
     *  ② 常驻 yt-dlp 守护进程（省掉每任务 1.5~3 秒的 Python 冷启动）
     *  ③ 旧路径兜底（每任务独立进程，最稳但最慢）
     *  cookies 为页面生成的 Netscape 格式文本。不做单独的 getInfo 探测——探测和正式下载会各抓一遍网页元数据 */
    public String submit(final String url, final String quality, final String cookies) {
        return submit(url, quality, cookies, "", "", "", 0);
    }
    public String submit(final String url, final String quality, final String cookies, final String referer, final String userAgent) {
        return submit(url, quality, cookies, referer, userAgent, "", 0);
    }
    public String submit(final String url, final String quality, final String cookies, final String referer, final String userAgent, final String outTitle) {
        return submit(url, quality, cookies, referer, userAgent, outTitle, 0);
    }
    /** force=1（嗅探媒体直链）：outtmpl 直接用页面标题强制命名；否则 outTitle 只作垃圾标题兜底（titleHint） */
    public String submit(final String url, final String quality, final String cookies, final String referer, final String userAgent, final String outTitle, final int force) {
        String clean = outTitle == null || outTitle.trim().isEmpty() ? "" : sanitizeName(outTitle.trim());
        if (clean.length() > 120) clean = clean.substring(0, 120).trim();
        final String fBase = force == 1 ? clean : "";   // 强制命名基名
        final String hint = clean;                       // 兜底改名用（整页下载的垃圾标题兜底）
        final String id = newId();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                svcAcquire(ctx);   // v1.99：任务计数 → 首个任务拉起前台保活（退后台下载不再被杀）
                boolean handedToDaemon = false;   // 守护路径的释放（槽/服务计数）由完成回调负责
                try {
                    postState(id, "parsing", null, null, null);
                    final long startAtMs = System.currentTimeMillis();
                    // 分享短链（v.douyin.com/b23.tv/xhslink.com）内置 lazy 版 yt-dlp 不认识，
                    // 会落到 generic 提取器被站点风控掐断——先展开成标准视频页链接。
                    // 放在抢并发槽之前：排队中的任务不占着槽做展开
                    final String url2 = expandShortLink(url);
                    slots.acquire();   // 并发槽（按设备内存自适应），其余排队
                    final boolean[] slotFreed = {false};
                    try {
                        // ① 抖音快通道：分享页直出视频/图集（数据拿不到就静默落下一级）。
                        // taskMode 先登记再进通道：下载进行中 cancel() 才能走对 ssr 分支（杀 aria2c + 中断线程）；
                        // 之前 put 在调用之后，SSR 整个下载期间 mode 是空的，取消会落错分支
                        taskMode.put(id, "ssr");
                        boolean ssrOk = tryDouyinSsr(id, url2, startAtMs, slotFreed);
                        taskMode.remove(id);
                        if (ssrOk) return;
                        // 用户已取消：绝不再落下一级（否则杀掉 aria2c 后又用单连接把整个文件偷偷重下一遍）
                        if (Thread.currentThread().isInterrupted()) { postState(id, "error", null, "任务被取消", null); return; }
                        // ② 常驻守护进程
                        if (ensureDaemon()) {
                            try {
                                // 每任务独立隐藏目录：批量并发时产物互不可见，收尾再搬到根目录——
                                // 之前直接落根目录，同名文件会被 yt-dlp 判"已下载"复用旧文件、
                                // 产物扫描也会把别的任务正在写的半成品认过来（首页标题和视频对不上的根源）
                                File jobDir = new File(vdDir, ".job-" + id);
                                File resumeDir = url2 == null ? null : dResume.remove(url2);   // v1.75：接着上次的断点下（切网失败/取消留下的半截文件）
                                if (resumeDir != null && resumeDir.isDirectory()) {
                                    jobDir = resumeDir;
                                } else {
                                    jobDir.mkdirs();
                                }
                                JSONObject req = new JSONObject();
                                req.put("id", id);
                                req.put("action", "download");
                                req.put("url", url2);
                                String host = android.net.Uri.parse(url2).getHost();
                                req.put("resolve_collection", host != null && ("bilibili.com".equalsIgnoreCase(host)
                                    || host.toLowerCase(java.util.Locale.ROOT).endsWith(".bilibili.com")));
                                JSONObject headers = new JSONObject();
                                if (referer != null && referer.startsWith("http") && !referer.contains("\n") && !referer.contains("\r")) headers.put("Referer", referer);
                                if (userAgent != null && !userAgent.isEmpty() && !userAgent.contains("\n") && !userAgent.contains("\r")) headers.put("User-Agent", userAgent);
                                req.put("headers", headers);
                                req.put("direct_media", force == 1 && isDirectMediaUrl(url2));
                                req.put("format", downloadFormat(url2, quality));
                                req.put("outtmpl", new File(jobDir, (fBase.isEmpty() ? "%(title).180s" : fBase.replace("%", "%%")) + ".%(ext)s").getAbsolutePath());
                                if (!fBase.isEmpty()) req.put("title", fBase);   // 守护进程进度/完结事件也用这个标题（别再被通用提取器的垃圾标题覆盖）
                                req.put("merge", true);
                                if (aria2Ok) req.put("downloader", "libaria2c.so");
                                String ck = writeCookies(id, cookies);
                                if (ck != null) req.put("cookiefile", ck);
                                DaemonTask daemonTask = new DaemonTask(LocalEngine.this, startAtMs, url2, jobDir);
                                daemonTask.titleHint = hint;
                                dTasks.put(id, daemonTask);
                                taskMode.put(id, "daemon");
                                handedToDaemon = true;
                                if (daemonSend(req)) {
                                    if (dTasks.containsKey(id)) postState(id, "downloading", hint.isEmpty() ? url2 : hint, null, null);
                                    return;
                                }
                                if (!dTasks.remove(id, daemonTask)) return;
                                handedToDaemon = false;
                                taskMode.remove(id, "daemon");
                            } catch (Throwable ignored) {}
                        }
                        // ③ 旧路径兜底
                        if (isCollectionUrl(url2)) throw new java.io.IOException("合集解析引擎尚未就绪，请重试合集下载");
                        legacyRun(url2, quality, cookies, id, startAtMs, referer, userAgent, fBase, slotFreed);
                    } finally {
                        if (!handedToDaemon) {
                            new File(ctx.getFilesDir(), "cookies-" + id + ".txt").delete();
                            deleteRecursive(new File(vdDir, ".job-" + id));   // 旧路径收尾：清掉本任务的独立目录（含残件）
                            taskMode.remove(id);
                            freeSlot(slotFreed);   // v1.99：legacyRun 中途已提前还槽的话这里不重复
                        }
                    }
                } catch (InterruptedException ie) {
                    postState(id, "error", null, "任务被取消", null);
                } catch (Throwable t) {
                    processIds.remove(id);
                    postState(id, "error", null, friendlyError(errMsg(t)), null);
                } finally {
                    threads.remove(id, Thread.currentThread());   // 线程收尾即出表（threads 表只服务 cancel 的 interrupt）
                    if (!handedToDaemon) svcRelease(ctx);   // 守护路径由 finishDaemonTask 收尾时撤
                }
            }
        });
        threads.put(id, t);
        t.start();
        return id;
    }

    /** 旧路径：每任务独立 yt-dlp 进程（结构上最稳，作为守护进程的兜底保留）。
     *  产物同样落在 .job-<id> 独立目录：并发批量不会互相污染，收尾搬到根目录 */
    private void legacyRun(final String url2, final String quality, final String cookies, final String id, final long startAtMs, final String referer, final String userAgent, final String forcedBase, final boolean[] slotFreed) throws Exception {
        postState(id, "downloading", url2, null, null);
        final File jobDir = new File(vdDir, ".job-" + id);
        jobDir.mkdirs();
        YoutubeDLRequest req = new YoutubeDLRequest(url2);
        if (referer != null && !referer.isEmpty()) req.addOption("--referer", referer);
        if (userAgent != null && !userAgent.isEmpty()) req.addOption("--user-agent", userAgent);
        applyCommon(req, quality, cookies, id, url2);
        req.addOption("-o", new File(jobDir, (forcedBase == null || forcedBase.isEmpty() ? "%(title).180s" : forcedBase.replace("%", "%%")) + ".%(ext)s").getAbsolutePath());
        if (ffmpegOk) req.addOption("--merge-output-format", "mp4");   // 分轨合并成 mp4
        req.addOption("--no-playlist");

        final String processId = "vd_" + id;
        processIds.put(id, processId);
        final StringBuilder destFile = new StringBuilder();
        final boolean[] titleSent = {false};
        Function3<Float, Long, String, Unit> cb = new Function3<Float, Long, String, Unit>() {
            @Override public Unit invoke(Float progress, Long eta, String line) {
                final int p = progress == null ? 0 : Math.min(99, Math.round(progress));
                final String l = line == null ? "" : line;
                // 抓真实落盘文件名：下载流(Destination)与合并产物(Merging formats into)都算，
                // 后者才是最终文件（合并完分轨文件会被删除，只有合并行可靠）
                String marker = null;
                if (l.contains("Destination:")) marker = "Destination:";
                else if (l.contains("Merging formats into")) marker = "Merging formats into";
                if (marker != null) {
                    try {
                        String path = l.substring(l.indexOf(marker) + marker.length()).trim().replace("\"", "");
                        destFile.setLength(0);
                        destFile.append(new File(path).getName());
                        if (!titleSent[0]) {   // 第一时间把标题顶到卡片上
                            titleSent[0] = true;
                            postState(id, "downloading", titleFromName(new File(path).getName()), null, null);
                        }
                    } catch (Exception ignored) {}
                }
                dPostProgress(id, p, l);
                return Unit.INSTANCE;
            }
        };
        YoutubeDL.getInstance().execute(req, processId, cb);   // 失败会抛异常
        processIds.remove(id);
        // 产物只在本任务的独立目录里找（独占无并发污染）；上报缺失再按输出行抓到的文件名补
        ArrayList<String> produced = scanJobDir(jobDir);
        if (produced.isEmpty() && destFile.length() > 0 && new File(jobDir, destFile.toString()).isFile()) {
            produced.add(destFile.toString());
        }
        if (produced.isEmpty()) {
            postState(id, "error", null, "下载结束但没找到产出文件：可能是存储权限受限（我的保存页顶部可去授权）或目录被系统清理，重试一次", null);
        } else {
            // 与 daemon 路径同规：只产出预览图片（未登录被风控）按失败处理，详见 finishDaemonTask 注释
            boolean onlyImages = true;
            for (String n : produced) if (!isImageName(n)) { onlyImages = false; break; }
            if (onlyImages) {
                postState(id, "error", null, "该站点没有提供视频流（未登录时部分站点只给预览图）。设置 → 登录信息管理 里登录后重试", null);
                return;   // jobDir 由 submit() 的 finally 整体清掉
            }
            // v1.99：先全部搬到根目录（快）就还下载槽，转码（CPU 活，可能几十秒到几十分钟）
            // 不再占网络并发——2 槽机上批量任务不会"两个在转码、全部在排队"
            ArrayList<String> moved0 = new ArrayList<>();
            for (String n : produced) {
                String moved = moveToRoot(jobDir, n);
                if (moved != null) moved0.add(moved);
            }
            freeSlot(slotFreed);
            if (moved0.isEmpty()) {
                postState(id, "error", null, "下载结束但产出文件搬移失败，重试一次", null);
                return;
            }
            tcEnter();   // v1.99：转码并发限 2
            ArrayList<String> fin = new ArrayList<>();
            try {
                for (String moved : moved0) {
                    fin.add(isVideoName(moved) ? normalizeVideo(moved, id, null) : moved);
                }
            } finally { tcSlots.release(); }
            java.util.Collections.sort(fin);
            scanToGallery(fin);
            markOwned(fin);
            ensureThumbs(fin);
            postState(id, "done", titleSent[0] ? null : titleFromName(fin.get(0)), null, new JSONArray(fin).toString());
        }
    }

    /** 本任务 .job 独立目录里的媒体产物（独占目录，无并发污染；新旧两条下载路径共用） */
    private static ArrayList<String> scanJobDir(File jobDir) {
        ArrayList<String> produced = new ArrayList<>();
        try {
            File[] all = jobDir.listFiles();
            if (all != null) {
                for (File f : all) {
                    String n = f.getName();
                    if (!f.isFile() || n.startsWith(".")) continue;
                    if (!MEDIA_NAME.matcher(n).matches()) continue;
                    if (CAMERA_NAME.matcher(n).find()) continue;   // 下载期间相机刚拍的不算本任务产物
                    produced.add(n);
                }
            }
        } catch (Exception ignored) {}
        return produced;
    }

    /** .job 产物搬到下载根目录：防撞后缀（同名文件共存）。
     *  文件名保留原始文案全文（#话题/@提及都在）——展示层的标题才做清洗；同名任务不会被
     *  yt-dlp 判"已下载"复用旧文件（.job 是空目录，必然真下载） */
    private String moveToRoot(File jobDir, String n) { return moveToRoot(jobDir, n, null); }
    /** forcedStem 非空时落盘文件主干用它（v1.98：浏览器页面标题兜底改名） */
    private String moveToRoot(File jobDir, String n, String forcedStem) {
        try {
            File src = new File(jobDir, n);
            if (!src.isFile() || src.length() == 0) return null;
            String ext = extOf(n);
            String stem = forcedStem != null && !forcedStem.trim().isEmpty()
                ? forcedStem.trim() : n.substring(0, n.length() - ext.length());
            String target = sanitizeName(stem) + ext;
            // 已在根目录且名字没变：别自搬自改
            if (vdDir.equals(src.getParentFile()) && target.equals(n)) return n;
            return commitFile(src, vdDir, target);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 守护进程刚（重）启动：清掉上次会话留下的孤儿 .job-* 目录（没有对应在跑任务的）。
     *  v1.69：遍历所有用过的保存目录——只扫当前目录的话，切目录后旧目录里的残件永远没人清。
     *  v1.75：登记在 dResume 里的断点目录不清（重试还要用） */
    private void sweepJobDirs() {
        try {
            java.util.HashSet<String> live = new java.util.HashSet<>(dTasks.keySet());
            live.addAll(threads.keySet());          // 磁力/SSR/旧路径任务的 staging 在 threads 里
            live.addAll(btProcs.keySet());
            for (File f : dResume.values()) live.add(f.getName().startsWith(".job-") ? f.getName().substring(5) : f.getName());
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            for (String p : dDirHistory) {
                if (p == null || !seen.add(p)) continue;
                File[] all = new File(p).listFiles();
                if (all == null) continue;
                for (File f : all) {
                    String n = f.getName();
                    if (f.isDirectory()) {
                        if (n.startsWith(".job-") && !live.contains(n.substring(5))) deleteRecursive(f);
                        else if (n.startsWith(".staging-") && !live.contains(n.substring(9))) deleteRecursive(f);
                        continue;
                    }
                    // 转码/裁剪/封面的临时产物：进程被杀会留孤儿，超过 2 小时没动过的清掉
                    // （在飞的临时文件 mtime 很新，不会误伤；上限 30 分钟的活，2 小时余量足够）
                    if ((n.startsWith(".tmp-") || n.startsWith(".cover-"))
                        && System.currentTimeMillis() - f.lastModified() > 2L * 60 * 60 * 1000) {
                        f.delete();
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    /** 下载文案标题清洗（与页面 cleanTitle 同规则）：取第一个 #话题/@提及 之前的部分；
     *  全文只有话题时取首个话题词；都没有则剥掉 @提及 返回剩余。批量后"标题和视频对不上"的
     *  一半原因是文件名带着 #尾巴 而 UI 标题不带——两边同规则后永远一致 */
    private static String cleanDescTitle(String s) {
        String raw = s == null ? "" : s.trim();
        if (raw.isEmpty()) return raw;
        int cut = raw.length();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '#' || c == '@') { cut = i; break; }
        }
        String head = raw.substring(0, cut).trim();
        String tail = ":：,，。.!！~·-—|　 \t";
        while (!head.isEmpty() && tail.indexOf(head.charAt(head.length() - 1)) >= 0) {
            head = head.substring(0, head.length() - 1).trim();
        }
        if (!head.isEmpty()) return head;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("#([^#\\s]{1,40})").matcher(raw);
        if (m.find()) return m.group(1).trim();
        String noMention = raw.replaceAll("@[^\\s@:：,，]+", " ").replaceAll("\\s+", " ").trim();
        return noMention;
    }

    /** 清晰度 → yt-dlp 格式串（新旧路径共用；优先 WebView 能播的 h264/vp9，避开 HEVC 有声无画） */
    private static String formatFilter(String quality) {
        String safe = "[vcodec!^=hevc][vcodec!^=h265][vcodec!^=hev1][vcodec!^=hvc1][vcodec!^=av01]";
        // 兜底必须限定真视频流：无 cookie 被 playurl 风控(412)拿不到视频格式时，
        // 不带过滤的 /b 会选中页面图集（一打 .jpg 当任务"成功"），必须逼它报错走重试/提示
        String vid = "[vcodec!^=none]";
        if (quality != null && quality.matches("\\d+")) {
            return "bv*[height<=" + quality + "]" + safe + "+ba/b[height<=" + quality + "]" + safe
                + "/bv*[height<=" + quality + "]+ba/b[height<=" + quality + "]/bv*+ba/b" + vid;
        }
        return "bv*" + safe + "+ba/b" + safe + "/bv*+ba/b" + vid;
    }

    private static boolean isDirectMediaUrl(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) return false;
            String path = uri.getPath();
            return path != null && path.matches("(?i).+\\.(mp4|m4v|mkv|webm|mov|flv|avi|mp3|m4a|aac|flac|wav|ogg|opus)$");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isDirectStreamUrl(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && uri.getPath() != null && uri.getPath().matches("(?i).+\\.(m3u8|mpd)$");
        } catch (IllegalArgumentException e) { return false; }
    }

    private static String downloadFormat(String url, String quality) {
        if (isDirectMediaUrl(url)) return "best";
        // Explicit manifests often omit codecs until segments are inspected.
        // The page extractor keeps its strict guard against thumbnail-only results.
        if (isDirectStreamUrl(url)) {
            if (quality != null && quality.matches("\\d+"))
                return "bv*[height<=?" + quality + "]+ba/b[height<=?" + quality + "]/bv*+ba/b";
            return "bv*+ba/b";
        }
        return formatFilter(quality);
    }

    private static boolean isCollectionUrl(String url) {
        android.net.Uri uri = android.net.Uri.parse(url);
        String host = uri.getHost();
        if (host == null || !("bilibili.com".equalsIgnoreCase(host)
            || host.toLowerCase(java.util.Locale.ROOT).endsWith(".bilibili.com"))) return false;
        String path = uri.getPath() == null ? "" : uri.getPath();
        return "season".equals(uri.getQueryParameter("share_from")) || path.contains("/lists/")
            || path.contains("/favlist") || path.contains("/channel/collectiondetail")
            || path.contains("/channel/seriesdetail") || path.contains("/medialist/detail/");
    }

    /** 页面 cookies 写成任务专属文件（守护/旧路径共用），返回路径；内容为空返回 null */
    private String writeCookies(String id, String cookies) {
        try {
            if (cookies == null || cookies.trim().length() <= 20) return null;
            File ck = new File(ctx.getFilesDir(), "cookies-" + id + ".txt");
            FileOutputStream fo = new FileOutputStream(ck);
            try { fo.write(cookies.getBytes("UTF-8")); } finally { try { fo.close(); } catch (Exception ignored) {} }
            return ck.getAbsolutePath();
        } catch (Exception e) { return null; }
    }

    // ================= 抖音分享页快通道（最快路径：一次 HTTP 拿到视频/图集数据，零 Python） =================

    /** 抖音 video/note 链接走移动分享页直取数据；成功返回 true（已发 done）。任何拿不到都返回 false 落常规路径 */
    private boolean tryDouyinSsr(String id, String url2, long startAtMs, boolean[] slotFreed) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("douyin\\.com/(video|note)/(\\d{6,})").matcher(url2);
            if (!m.find()) return false;
            String kind = m.group(1), awemeId = m.group(2);
            JSONObject item = fetchDouyinShareItem(kind, awemeId);
            if (item == null) return false;
            String desc = item.optString("desc", "");
            ArrayList<String> images = new ArrayList<>();
            JSONArray imgs = item.optJSONArray("images");
            if (imgs != null) {
                for (int i = 0; i < imgs.length(); i++) {
                    JSONObject im = imgs.optJSONObject(i);
                    JSONArray ul = im == null ? null : im.optJSONArray("url_list");
                    if (ul != null && ul.length() > 0 && ul.optString(0).length() > 8) images.add(ul.optString(0));
                }
            }
            String videoUrl = null;
            String coverUrl = null;   // 链接封面（v2.43）：下载后直接当视频缩略图，比抽帧好看
            long apiDurMs = 0;   // 作品真实时长（毫秒）：文件比它长出来的部分=片尾推广
            JSONObject v = item.optJSONObject("video");
            if (v != null) {
                JSONObject pa = v.optJSONObject("play_addr");
                JSONArray ul = pa == null ? null : pa.optJSONArray("url_list");
                if (ul != null && ul.length() > 0 && ul.optString(0).length() > 8) videoUrl = ul.optString(0);
                JSONObject oc = v.optJSONObject("origin_cover");
                if (oc == null) oc = v.optJSONObject("cover");
                JSONArray cul = oc == null ? null : oc.optJSONArray("url_list");
                if (cul != null && cul.length() > 0 && cul.optString(0).length() > 8) coverUrl = cul.optString(0);
                if (videoUrl == null) {
                    String uri = pa == null ? "" : pa.optString("uri", "");
                    if (uri.length() > 0) videoUrl = "https://www.iesdouyin.com/aweme/v1/play/?video_id=" + uri + "&ratio=1080p&line=0";
                }
                long d = v.optLong("duration", 0);
                if (d <= 0) d = item.optLong("duration", 0);
                apiDurMs = d > 1200 ? d : d * 1000;   // 字段可能是毫秒也可能是秒：>20 分钟的一定是毫秒
            }
            if (images.isEmpty()) {
                // 图文（note）链接取不到图片：宁可回退下一级，也绝不把 note 自带的轮播合成视频当"图文"下下来
                // （抖音图集在数据里带 video 字段 = 图片轮播生成的视频，之前的"图文存成视频"就是它）
                if ("note".equals(kind)) return false;
                if (videoUrl == null) return false;
            }
            String fileBase = sanitizeName(desc.isEmpty() ? "douyin" : desc);   // 文件名保留 #话题/@提及 全文
            String rawTitle = desc.isEmpty() ? fileBase : desc;                // 状态发原始描述：页面清洗出标题+话题行
            String dispTitle = cleanDescTitle(desc);                           // （本地日志/兜底用）
            if (dispTitle.isEmpty()) dispTitle = fileBase;
            postState(id, "downloading", rawTitle, null, null);
            if (!images.isEmpty()) {   // 图集：并行直下全部图片
                List<String> saved = downloadMediaUrls(id, images, fileBase);
                if (saved.isEmpty()) return false;
                scanToGallery(saved);
                markOwned(saved);
                ensureThumbs(saved);
                postState(id, saved.size() == images.size() ? "done" : "error", rawTitle,
                    saved.size() == images.size() ? null : "仅保存成功 " + saved.size() + "/" + images.size()
                    + " 张图片，已保存文件仍可查看，未标记为全部完成。", new JSONArray(saved).toString());
                return true;
            }
            // 单视频：aria2c 16 连接直下，失败退单连接；下完归一成 H.264 mp4（部分手机解不了 HEVC）。
            // 全程写 .part 临时名、成功才改成正式名——直接写正式名的话，下载中 listFiles() 会把
            // 半截文件当成品列出来，崩溃/被杀还会留下个假"完好"文件
            File out = new File(vdDir, ".tmp-" + id + ".mp4.part");
            out.delete();
            boolean ok = aria2Ok && aria2Download(id, videoUrl, "https://www.iesdouyin.com/", out);
            // 已取消就不退单连接（那是"取消后偷偷重下"）；aria2c 真失败（网络断/链接过期）才退
            if (!ok && !Thread.currentThread().isInterrupted()) ok = downloadProgress(id, videoUrl, out);
            String want = ok && out.length() > 0 ? commitFile(out, vdDir, fileBase + ".mp4") : null;
            if (want == null) {
                out.delete();   // 半截文件绝不留：会污染后续任务的产物扫描，也会以坏文件身份进「我的保存」
                new File(out.getAbsolutePath() + ".aria2").delete();   // aria2 断点控制文件一并清
                return false;
            }
            // v1.99：产物已安全落到根目录，立刻还下载槽——下面的裁剪/归一是纯 CPU 活，
            // 不再占网络并发（2 槽机上转码几分钟 = 后续任务白排队几分钟）
            freeSlot(slotFreed);
            // 片尾推广先裁掉（v1.39），再归一成 H.264 mp4（部分手机解不了 HEVC）；转码限 2 路并发
            tcEnter();
            String finalName;
            try {
                finalName = normalizeVideo(trimDouyinTail(want, id, apiDurMs), id, dispTitle);
            } finally { tcSlots.release(); }
            ArrayList<String> saved = new ArrayList<>();
            saved.add(finalName);
            postState(id, "done", rawTitle, null, new JSONArray(saved).toString());
            scanToGallery(saved);
            markOwned(saved);
            // v2.43：优先把链接封面存成这个视频的缩略图（列表/详情页/模糊背景全用它）；
            // 存不进去就照旧抽帧（ensureThumbs 见已有封面会自动跳过）
            if (coverUrl == null || !saveCoverAsThumb(coverUrl, finalName)) ensureThumbs(saved);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 下载链接封面写到 .thumbs/<视频名>.jpg（v2.43）。失败返回 false（调用方退回抽帧） */
    private boolean saveCoverAsThumb(String coverUrl, String videoName) {
        java.io.File tmp = null;
        HttpURLConnection c = null;
        try {
            File tdir = new File(vdDir, ".thumbs");
            tdir.mkdirs();
            File out = new File(tdir, videoName + ".jpg");
            String stamp = thumbStamp(new File(vdDir, videoName));
            tmp = new File(tdir, ".cover-" + newId() + ".tmp");
            c = (HttpURLConnection) new URL(coverUrl).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA_ENGINE);
            if (c.getResponseCode() != 200) return false;
            InputStream ci = c.getInputStream();
            FileOutputStream fo = new FileOutputStream(tmp);
            byte[] buf = new byte[16384];
            try {
                int n;
                while ((n = ci.read(buf)) > 0) fo.write(buf, 0, n);
            } finally {
                try { fo.close(); } catch (Throwable ignored) {}
                try { ci.close(); } catch (Throwable ignored) {}
            }
            if (tmp.length() < 2048) return false;   // 太小不是真图
            return publishThumb(vdDir, videoName, stamp, tmp);
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) { try { c.disconnect(); } catch (Throwable ignored) {} }
            if (tmp != null) { try { tmp.delete(); } catch (Throwable ignored) {} }
        }
    }

    /** 拉移动分享页（无反爬挑战），解析 _ROUTER_DATA 里的作品数据 */
    private JSONObject fetchDouyinShareItem(String kind, String awemeId) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://www.iesdouyin.com/share/" + kind + "/" + awemeId + "/").openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(9000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA_ENGINE);
            c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            // 带 App 里 WebView 攒的抖音 cookies（有则更稳，没有游客也能拿）
            String ck = null;
            try { ck = android.webkit.CookieManager.getInstance().getCookie("https://www.iesdouyin.com/"); } catch (Throwable ignored) {}
            if (ck == null || ck.isEmpty()) {
                try { ck = android.webkit.CookieManager.getInstance().getCookie("https://www.douyin.com/"); } catch (Throwable ignored) {}
            }
            if (ck != null && !ck.isEmpty()) c.setRequestProperty("Cookie", ck);
            if (c.getResponseCode() != 200) { c.disconnect(); return null; }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            InputStream in = c.getInputStream();
            byte[] buf = new byte[16384];
            try {
                int n;
                while (bos.size() < 2097152 && (n = in.read(buf)) > 0) bos.write(buf, 0, n);
            } finally {
                try { in.close(); } catch (Throwable ignored) {}
                c.disconnect();
            }
            String html = new String(bos.toByteArray(), "UTF-8");
            int i = html.indexOf("window._ROUTER_DATA");
            if (i < 0) return null;
            int s = html.indexOf('{', i);
            int e = html.indexOf("</script>", i);
            if (s < 0 || e <= s) return null;
            JSONObject router = new JSONObject(html.substring(s, e).trim());
            JSONObject loader = router.optJSONObject("loaderData");
            if (loader == null) return null;
            // 定向路径：loaderData[*].videoInfoRes/noteInfoRes.item_list[0]
            java.util.Iterator<String> keys = loader.keys();
            while (keys.hasNext()) {
                JSONObject page = loader.optJSONObject(keys.next());
                if (page == null) continue;
                for (String k : new String[]{"videoInfoRes", "noteInfoRes"}) {
                    JSONObject r = page.optJSONObject(k);
                    JSONArray arr = r == null ? null : r.optJSONArray("item_list");
                    JSONObject it0 = arr != null && arr.length() > 0 ? arr.optJSONObject(0) : null;
                    if (it0 != null && (it0.optJSONObject("video") != null || it0.optJSONArray("images") != null)) return it0;
                }
                JSONArray direct = page.optJSONArray("item_list");
                if (direct != null && direct.length() > 0) {
                    JSONObject it0 = direct.optJSONObject(0);
                    if (it0 != null && (it0.optJSONObject("video") != null || it0.optJSONArray("images") != null)) return it0;
                }
            }
            // 结构漂移兜底：全树找第一个"带 aweme_id 且有 video/images"的对象
            return findAweme(loader);
        } catch (Throwable t) {
            return null;
        }
    }

    private static JSONObject findAweme(Object node) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            if (o.has("aweme_id") && (o.optJSONObject("video") != null || o.optJSONArray("images") != null)) return o;
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) { JSONObject r = findAweme(o.opt(it.next())); if (r != null) return r; }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) { JSONObject r = findAweme(a.opt(i)); if (r != null) return r; }
        }
        return null;
    }

    /** aria2c 直链下载（16 连接），退出码 0 且文件非空才算成功 */
    private boolean aria2Download(String id, String url, String referer, File out) {
        Process p = null;
        try {
            List<String> args = new ArrayList<>();
            args.add(aria2cBin());
            args.add("--dir=" + out.getParent());
            args.add("--out=" + out.getName());
            args.add("--allow-overwrite=true");
            args.add("--auto-file-renaming=false");
            args.add("--continue=true");
            args.add("--file-allocation=none");
            args.add("--max-connection-per-server=16");
            args.add("--split=16");
            args.add("--min-split-size=1M");
            args.add("--summary-interval=1");
            args.add("--console-log-level=notice");
            args.add("--user-agent=" + UA_ENGINE);
            args.add("--referer=" + referer);
            if (sslCertPath != null) args.add("--ca-certificate=" + sslCertPath);
            args.add(url);
            // v1.69：合并 stderr——aria2c 出错时往 stderr 猛写（tracker 全挂的场景），64KB 管道
            // 写满后进程阻塞、stdout 永不 EOF，本线程和并发槽一起挂死
            ProcessBuilder pb = new ProcessBuilder(args).redirectErrorStream(true);
            if (ldLibPath != null) pb.environment().put("LD_LIBRARY_PATH", ldLibPath);
            p = pb.start();
            btProcs.put(id, p);
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) return false;   // 取消：finally 会杀掉进程
                java.util.regex.Matcher m = ARIA_PCT.matcher(line);
                if (m.find()) {
                    final int pct = Math.min(99, Integer.parseInt(m.group(1)));
                    final String l = line;
                    final String tid = id;
                    dPostProgress(tid, pct, l);
                }
            }
            int code = p.waitFor();
            return code == 0 && out.isFile() && out.length() > 0;
        } catch (Throwable t) {
            return false;
        } finally {
            btProcs.remove(id);
            if (p != null) { try { FfmpegTool.destroyProcess(p); } catch (Exception ignored) {} }
        }
    }

    /** 单连接下载（带进度，aria2c 不可用/失败时的兜底）。
     *  流的关闭必须走 finally：以前中断/异常路径直接 return，连接和句柄全漏 */
    private boolean downloadProgress(final String id, String url, File out) {
        HttpURLConnection c = null;
        InputStream in = null;
        OutputStream fo = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA_ENGINE);
            if (url.contains("douyin")) c.setRequestProperty("Referer", "https://www.iesdouyin.com/");
            long total = c.getContentLengthLong();
            in = c.getInputStream();
            fo = new FileOutputStream(out);
            byte[] buf = new byte[65536];
            long got = 0;
            int n;
            long lastAt = android.os.SystemClock.elapsedRealtime();
            long lastBytes = 0;
            while ((n = in.read(buf)) > 0) {
                if (Thread.currentThread().isInterrupted()) return false;   // 取消：半截文件由调用方删除
                fo.write(buf, 0, n);
                got += n;
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - lastAt >= 500 || (total > 0 && got >= total)) {
                    final int pp = total > 0 ? (int) Math.min(99, got * 100 / total) : 0;
                    JSONObject metrics = new JSONObject();
                    metrics.put("status", "downloading");
                    metrics.put("downloaded_bytes", got);
                    metrics.put("total_bytes", Math.max(0, total));
                    metrics.put("speed", (got - lastBytes) * 1000 / Math.max(1, now - lastAt));
                    final String l = metrics.toString();
                    lastAt = now;
                    lastBytes = got;
                    dPostProgress(id, pp, l);
                }
            }
            return out.isFile() && out.length() > 0 && (total < 0 || got == total);
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (fo != null) fo.close(); } catch (Throwable ignored) {}
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            if (c != null) { try { c.disconnect(); } catch (Throwable ignored) {} }
        }
    }

    // ================= 常驻 yt-dlp 守护进程 =================

    /** 环境路径与 youtubedl-android 的 YoutubeDL.kt 完全一致（libpython.so / yt-dlp zipapp / 证书 / LD_LIBRARY_PATH） */
    private void computeRuntimePaths() {
        File base = new File(ctx.getNoBackupFilesDir(), "youtubedl-android");
        File packagesDir = new File(base, "packages");
        String pythonUsr = new File(packagesDir, "python/usr").getAbsolutePath();
        nativeDirPath = ctx.getApplicationInfo().nativeLibraryDir;
        sslCertPath = pythonUsr + "/etc/tls/cert.pem";
        pythonHome = pythonUsr;
        ldLibPath = pythonUsr + "/lib:" + new File(packagesDir, "ffmpeg/usr/lib").getAbsolutePath()
            + ":" + new File(packagesDir, "aria2c/usr/lib").getAbsolutePath();
        ytdlpZipPath = new File(new File(base, "yt-dlp"), "yt-dlp").getAbsolutePath();
        ffmpegBinPath = new File(nativeDirPath, "libffmpeg.so").getAbsolutePath();
    }

    /** assets 里的守护脚本拷到 filesDir（进程要用真实文件路径执行） */
    private void copyDaemonScript() throws Exception {
        for (String name : new String[]{"vd_daemon.py", "bilibili_collection.py", "zhuliwa.py"}) {
            try (InputStream in = ctx.getAssets().open(name);
                 FileOutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), name))) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
    }

    // v2.53：热加载新版 yt-dlp——PyPI wheel 本身就是 zipapp 结构（zip 根下 yt_dlp/ 包），
    // daemon 直接 zipimport，不必重打包 youtubedl-android 库。B站等站的风控/接口变动
    // 靠升级这个资产文件解决（内置的库版 yt-dlp 停在 2025.09，B站已 412）
    private static final String HOT_YTDLP_VER = "2026.8.19";
    private File ensureHotYtdlp() {
        try {
            File f = new File(ctx.getFilesDir(), "yt-dlp-hot-" + HOT_YTDLP_VER + ".zipapp");
            if (f.isFile() && f.length() > 1000000) return f;
            InputStream in = ctx.getAssets().open("ytdlp_hot.zipapp");
            FileOutputStream fo = new FileOutputStream(f);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            fo.close();
            in.close();
            File[] olds = ctx.getFilesDir().listFiles();   // 清掉旧版本热更包
            if (olds != null) {
                for (File o : olds) {
                    if (o.getName().startsWith("yt-dlp-hot-") && !o.getName().equals(f.getName())) o.delete();
                }
            }
            return f;
        } catch (Exception e) { return null; }
    }

    private java.util.Map<String, String> daemonEnv() {
        java.util.Map<String, String> env = new java.util.HashMap<>();
        env.put("LD_LIBRARY_PATH", ldLibPath);
        env.put("SSL_CERT_FILE", sslCertPath);
        env.put("PYTHONHOME", pythonHome);
        env.put("HOME", pythonHome);
        env.put("TMPDIR", ctx.getCacheDir().getAbsolutePath());
        env.put("PATH", (System.getenv("PATH") == null ? "" : System.getenv("PATH")) + ":" + nativeDirPath);
        env.put("VD_SLOTS", String.valueOf(slotCount));
        env.put("PYTHONIOENCODING", "utf-8");
        return env;
    }

    /** 拉起守护进程（幂等；死了会由下一次 ensureDaemon 重启） */
    private void startDaemon() {
        synchronized (dStartLock) {
            if (dProc != null) return;
            try {
                if (nativeDirPath == null) computeRuntimePaths();
                copyDaemonScript();   // 每次都重拷：App 升级后脚本跟着新（就 4KB）
                File hot = ensureHotYtdlp();   // 新版 yt-dlp 优先；没有才退回库内置的旧版
                String ytdlpZip = hot != null ? hot.getAbsolutePath() : ytdlpZipPath;
                dReady = false;
                ProcessBuilder pb = new ProcessBuilder(
                    new File(nativeDirPath, "libpython.so").getAbsolutePath(),
                    new File(ctx.getFilesDir(), "vd_daemon.py").getAbsolutePath(), ytdlpZip, ffmpegBinPath);
                pb.environment().putAll(daemonEnv());
                final Process p = pb.start();
                dOut = p.getOutputStream();
                dProc = p;
                Thread out = new Thread(new Runnable() {
                    @Override public void run() { pumpDaemon(p); }
                }, "vd-daemon-out");
                out.setDaemon(true);
                out.start();
                Thread err = new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getErrorStream()));
                            while (r.readLine() != null) { /* 排空防堵 */ }
                        } catch (Exception ignored) {}
                    }
                }, "vd-daemon-err");
                err.setDaemon(true);
                err.start();
            } catch (Throwable t) {
                dReady = false;
                dProc = null;
                dStartLock.notifyAll();
            }
        }
    }

    /** 守护进程就绪检查：ready 或能在 45 秒内拉起并就绪（首次含 yt-dlp 导入） */
    private boolean ensureDaemon() {
        if (dReady && dProc != null) return true;
        startDaemon();
        long deadline = System.currentTimeMillis() + 45000;
        synchronized (dStartLock) {
            while (!dReady && dProc != null && System.currentTimeMillis() < deadline) {
                try { dStartLock.wait(1000); } catch (InterruptedException e) { return false; }
            }
        }
        return dReady && dProc != null;
    }

    private boolean daemonSend(JSONObject req) {
        synchronized (dSendLock) {
            try {
                java.io.OutputStream os = dOut;
                if (os == null) return false;
                os.write((req.toString() + "\n").getBytes("UTF-8"));
                os.flush();
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    /** 读守护进程输出直到退出；进程死掉时收尾在飞任务并允许重启 */
    private void pumpDaemon(Process p) {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) handleDaemonLine(line);
        } catch (Exception ignored) {}
        // 读循环退出≠进程已死（读异常也可能到这）：不补刀的话进程会泄漏成孤儿
        try { FfmpegTool.destroyProcess(p); } catch (Throwable ignored) {}
        synchronized (dStartLock) {
            // 只清理"自己这一任"的登记：读异常退出期间 ensureDaemon 可能已拉起新进程，
            // 盲清会把新进程的 dProc/dOut 一并抹掉，接着就是 45 秒假死等
            if (dProc == p) {
                dReady = false;
                dProc = null;
                try { if (dOut != null) dOut.close(); } catch (Exception ignored) {}
                dOut = null;
            }
            dStartLock.notifyAll();
        }
        for (Map.Entry<String, DaemonTask> e : dTasks.entrySet()) {
            DaemonTask t = dTasks.remove(e.getKey());
            if (t == null) continue;
            taskMode.remove(e.getKey());
            new File(t.eng.ctx.getFilesDir(), "cookies-" + e.getKey() + ".txt").delete();
            dPostState(e.getKey(), "error", null, "下载引擎重启了一下，点「重试」继续", null);
            t.eng.slots.release();
            svcRelease(t.eng.ctx);   // v1.99：守护进程死掉时在飞任务的保活计数也要撤
        }
    }

    private void handleDaemonLine(String line) {
        try {
            // v1.99：aria2 子进程继承的 notice 脏行直接丢（守护侧已尽力拦，这里兜底）——
            // 之前每条脏行都走一次带异常堆栈的 Log.w
            if (line == null) return;
            line = line.trim();
            if (line.isEmpty() || line.charAt(0) != '{') return;
            JSONObject o = new JSONObject(line);
            String type = o.optString("type");
            final String id = o.optString("id");
            if ("ready".equals(type)) {
                synchronized (dStartLock) { dReady = true; dStartLock.notifyAll(); }
                sweepJobDirs();   // 守护进程刚（重）启动：上次会话留下的 .job-* 目录全是孤儿，清掉
                return;
            }
            if ("fatal".equals(type)) {
                // 脚本发完 fatal 随即退出；进程收尾（补刀+清登记）统一由 pumpDaemon 做——
                // 这里绝不能按 dProc 盲杀：此刻 ensureDaemon 可能已拉起新进程，会误伤
                synchronized (dStartLock) { dStartLock.notifyAll(); }
                return;
            }
            if ("progress".equals(type)) {
                DaemonTask t = dTasks.get(id);
                if (t != null) {
                    t.lastAliveMs = System.currentTimeMillis();   // v1.69：心跳——看门狗据此判活
                    String title = o.optString("title", "");
                    if (title.length() > 0) t.lastTitle = title;
                    String fn = o.optString("filename", "");
                    if (fn.length() > 0) t.destName = fn;
                }
                final int pct = o.optInt("progress", 0);
                final String l = o.has("downloaded_bytes") ? o.toString() : o.optString("line", "");
                dPostProgress(id, pct, l);
                // v1.99：看门狗巡检从每条消息一次改为 30s 一次——progress 高频到达时
                // 全表扫描纯属白干（守护侧已按 250ms 节流，但 16 分片并发仍不稀疏）
                long now = System.currentTimeMillis();
                if (now - lastSweepAt > 30000) { lastSweepAt = now; sweepStuckDaemonTasks(); }
                return;
            }
            if ("done".equals(type)) { finishDaemonTask(id, null, o.optJSONArray("files")); return; }
            if ("collection".equals(type)) {
                DaemonTask task = dTasks.remove(id);
                if (task == null) return;
                taskMode.remove(id);
                new File(task.eng.ctx.getFilesDir(), "cookies-" + id + ".txt").delete();
                deleteRecursive(task.jobDir);
                task.eng.slots.release();
                svcRelease(task.eng.ctx);
                JSONArray entries = o.optJSONArray("entries");
                dPostState(id, "collection", o.optString("title", "B站合集"), null,
                    entries == null ? "[]" : entries.toString());
                return;
            }
            if ("error".equals(type)) { finishDaemonTask(id, o.optString("line", "下载失败"), null); return; }
        } catch (Exception e) {
            // 坏行不能完全无声：丢的是 done/error 时任务要挂到看门狗才收尾，得留排查线索
            android.util.Log.w(TAG, "daemon line dropped: " + line, e);
        }
    }

    /** v1.69 卡死看门狗：守护任务 3 小时零消息（done/error 行损坏或丢失）就强制收尾，
     *  释放并发槽——小内存机只有 2 槽，两次就把全部下载排队死等。正常任务秒级有进度心跳，
     *  合并/转码阶段最长几分钟，3 小时余量足够 */
    private static volatile long lastSweepAt = 0;
    private void sweepStuckDaemonTasks() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, DaemonTask> e : dTasks.entrySet()) {
            DaemonTask t = e.getValue();
            if (t == null) continue;
            if (now - Math.max(t.startAtMs, t.lastAliveMs) > 3L * 60 * 60 * 1000) {
                finishDaemonTask(e.getKey(), "任务长时间无响应，已自动收尾（可重试）", null);
            }
        }
    }

    /** 守护任务收尾：产物在 .job-<id> 独立目录里各认各的，搬到根目录（清洗命名+防撞）再上报。
     *  上报清单缺失才扫 job 目录兜底（绝不扫全目录时间窗——并发任务会互相污染）。
     *  释放并发槽/清 cookie/job 目录都按任务归属的引擎实例来。
     *  归一可能要转码（几十秒级），放独立线程跑——绝不能堵住守护进程的输出泵 */
    private void finishDaemonTask(final String id, final String isErr, final JSONArray files) {
        final DaemonTask t;
        synchronized (dTasks) {
            t = dTasks.remove(id);
            if (t == null) return;
            if (isErr == null) finishingTasks.put(id, t);
        }
        taskMode.remove(id);
        new File(t.eng.ctx.getFilesDir(), "cookies-" + id + ".txt").delete();
        if (isErr != null || t.cancelled) {
            finishingTasks.remove(id);
            dPostState(id, "error", null, t.cancelled ? "任务被取消" : friendlyError(isErr), null);
            // v1.75 断点续传：目录里还有半截文件（.part / .aria2 控制文件 / .ytdl 分片记录）就
            // 保留并按链接登记——重试同链接接着上次的进度下。以前一律删目录，切网一次前功尽弃
            boolean salvage = false;
            try {
                File[] kids = t.jobDir.listFiles();
                if (kids != null) {
                    for (File k : kids) {
                        String n = k.getName();
                        if (n.endsWith(".part") || n.endsWith(".aria2") || n.endsWith(".ytdl")) { salvage = true; break; }
                    }
                }
            } catch (Throwable ignored) {}
            if (salvage && t.url != null && t.url.startsWith("http")) {
                if (dResume.size() > 20) dResume.clear();
                dResume.put(t.url, t.jobDir);
            } else {
                deleteRecursive(t.jobDir);   // 用任务创建时快照的目录（用户可能已切走保存目录）
            }
            t.eng.slots.release();
            svcRelease(t.eng.ctx);
            return;
        }
        Thread finishing = new Thread(new Runnable() {
            @Override public void run() {
                final File jobDir = t.jobDir;
                final boolean[] slotFreed = {false};
                try {
                    final LocalEngine eng = t.eng;
                    if (t.cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException("任务被取消");
                    ArrayList<String> produced = new ArrayList<>();
                    if (files != null) {
                        for (int i = 0; i < files.length(); i++) {
                            String n = files.optString(i, "");
                            if (n.length() == 0 || n.startsWith(".") || n.contains("/")) continue;
                            if (CAMERA_NAME.matcher(n).find()) continue;
                            // 新脚本落在 .job 独立目录；极老版本直落根目录也兼容
                            File f = new File(jobDir, n);
                            if (!f.isFile()) continue;   // 上报了但没落盘（被清理）的跳过
                            produced.add(f.getName());
                        }
                    }
                    if (produced.isEmpty() && t.destName != null) {
                        File f = new File(jobDir, t.destName);
                        if (f.isFile()) produced.add(f.getName());
                    }
                    if (produced.isEmpty()) produced = scanJobDir(jobDir);   // 上报缺失才扫本任务独占目录
                    if (produced.isEmpty()) {
                        dPostState(id, "error", null, "下载结束但没找到产出文件：可能是存储权限受限（我的保存页顶部可去授权）或目录被系统清理，重试一次", null);
                    } else {
                        // B 站等站对未登录用户只给「预览图轮播」（playurl 被风控，拿不到视频流），
                        // yt-dlp 会把这组预览图当产物下满——预期视频的任务若只产出图片，按失败处理
                        // 并引导登录，绝不让用户拿一打截图当"下载成功"（真正的图集站走 App 内采集通道，不进这里）
                        boolean onlyImages = true;
                        for (String n : produced) if (!isImageName(n)) { onlyImages = false; break; }
                        if (onlyImages) {
                            dPostState(id, "error", null, "该站点没有提供视频流（未登录时部分站点只给预览图）。设置 → 登录信息管理 里登录后重试", null);
                        } else {
                        // v1.98 垃圾标题判定：yt-dlp 对无提取器站点给的"标题"就是 URL 尾巴/空——
                        // 浏览器带了页面标题（titleHint）就兜底用它命名，所见（浏览器标题）=所得（文件名）
                        // v2.02：垃圾形态补上含点号的——B站 CDN 直链（cn-xx.bilivideo.com-1-30280）
                        // 这类主机名尾巴全是 ASCII+点横线，旧正则不认，垃圾名直接落了盘
                        String hint = t.titleHint;
                        String firstStem = produced.isEmpty() ? "" : produced.get(0).replaceAll("\\.[^.]+$", "");
                        boolean useHint = hint != null && !hint.trim().isEmpty() && (
                            t.lastTitle == null || t.lastTitle.trim().isEmpty()
                            || t.lastTitle.equals(firstStem)
                            || t.lastTitle.matches("^[A-Za-z0-9_\\-.]{8,}$")
                            || "video".equalsIgnoreCase(t.lastTitle) || "index".equalsIgnoreCase(t.lastTitle));
                        // v1.99：先把产物全部搬到根目录（快），随即释放下载槽——转码/裁剪是
                        // 纯 CPU 活（单视频最长 30 分钟），占着网络槽会把后续下载全部饿死
                        // （2 槽机上两个任务进转码 = 网络空转、批量全部排队几十分钟）
                        ArrayList<String> moved0 = new ArrayList<>();
                        for (String n : produced) {
                            String moved = eng.moveToRoot(jobDir, n, useHint ? hint : null);
                            if (moved != null) moved0.add(moved);
                        }
                        eng.markOwned(moved0);
                        eng.freeSlot(slotFreed);
                        if (moved0.isEmpty()) {
                            dPostState(id, "error", null, "下载结束但产出文件搬移失败，重试一次", null);
                        } else {
                        if (t.cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException("任务被取消");
                        tcEnter();   // v1.99：转码并发限 2——多路 x264 同跑会抢满所有核热降频
                        ArrayList<String> fin = new ArrayList<>();
                        try {
                            for (String moved : moved0) {
                                // 抖音片尾推广自动裁剪（v1.39）：API 时长拿不到（守护路径），走静态尾巴检测
                                if (isVideoName(moved) && t.url != null && t.url.contains("douyin")) {
                                    moved = eng.trimDouyinTail(moved, id, 0);
                                }
                                fin.add(isVideoName(moved) ? eng.normalizeVideo(moved, id, useHint ? hint : t.lastTitle) : moved);
                            }
                        } finally { tcSlots.release(); }
                        eng.markOwned(fin);
                        if (t.cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException("任务被取消");
                        java.util.Collections.sort(fin);
                        eng.scanToGallery(fin);
                        eng.ensureThumbs(fin);
                        // 标题发原始描述（v1.39）：页面端 cleanTitle 取正文、tagLine 取 #话题做第二行
                        dPostState(id, "done", useHint ? hint : (t.lastTitle != null ? t.lastTitle : titleFromName(fin.get(0))), null, new JSONArray(fin).toString());
                        }
                        }
                    }
                } catch (Throwable e) {
                    dPostState(id, "error", null, errMsg(e), null);
                } finally {
                    finishingTasks.remove(id);
                    threads.remove(id, Thread.currentThread());
                    taskMode.remove(id, "finishing");
                    deleteRecursive(jobDir);   // 收尾清理（分轨残件 / .aria2 控制文件 / 没搬动的文件）
                    t.eng.freeSlot(slotFreed);
                    svcRelease(t.eng.ctx);   // v1.99：任务全部结束（含转码）才撤前台保活
                }
            }
        }, "vd-finish-" + id);
        threads.put(id, finishing);
        taskMode.put(id, "finishing");
        finishing.start();
    }

    /** 提交磁力链接：手机本地 aria2c BT 模式 */
    public String magnetSubmit(final String magnet) {
        final String id = newId();
        final MagnetStream stream;
        try { stream = new MagnetStream(id, new File(vdDir, ".staging-" + id)); }
        catch (java.io.IOException e) { return null; }
        Thread t = new Thread(new Runnable() {
        @Override public void run() {
            final File staging = new File(vdDir, ".staging-" + id);
            boolean keepStaging = false;
            stagingDirs.put(id, staging);   // cancel 按快照删（中途切目录也不会删错地方）
            try { slots.acquire(); }
            catch (InterruptedException ie) {
                stagingDirs.remove(id);
                threads.remove(id);
                postState(id, "error", null, "任务被取消", null);
                stream.fail("下载已取消");
                return;
            }
            svcAcquire(ctx);   // v1.99 前台保活
            final boolean[] slotFreed = {false};
            try {
                staging.mkdirs();
                    postState(id, "parsing", "磁力：正在解析元数据…", null, null);
                    if (nativeDirPath == null) computeRuntimePaths();
                    // ① 元数据快速通道（v1.39）：公共 .torrent 缓存直接拿种子文件——国内网络
                    //    DHT/tracker 的 UDP 常被限速，元数据拿不到就什么都下不了；拿到 .torrent
                    //    后 aria2c 只需要连节点传数据，起步快得多。拿不到再回纯磁力（DHT 慢慢磨）
                    String torrentFile = null;
                    java.util.regex.Matcher mh = java.util.regex.Pattern.compile("urn:btih:([a-fA-F0-9]{40})").matcher(magnet);
                    if (mh.find() && !MAGNET_PEER_HINT.matcher(magnet).find()) {
                        HttpURLConnection c = null;
                        InputStream mi = null;
                        try {
                            c = (HttpURLConnection) new URL("https://itorrents.org/torrent/" + mh.group(1).toUpperCase(java.util.Locale.ROOT) + ".torrent").openConnection();
                            c.setConnectTimeout(2000);
                            c.setReadTimeout(3000);
                            c.setInstanceFollowRedirects(true);
                            c.setRequestProperty("User-Agent", UA_ENGINE);
                            if (c.getResponseCode() == 200) {
                                java.io.ByteArrayOutputStream mb = new java.io.ByteArrayOutputStream();
                                mi = c.getInputStream();
                                byte[] mbuf = new byte[16384];
                                int mn;
                                while (mb.size() < 20971520 && (mn = mi.read(mbuf)) > 0) mb.write(mbuf, 0, mn);
                                byte[] mbb = mb.toByteArray();
                                if (mbb.length > 200 && mbb[0] == 'd') {   // bencode 字典开头才是真种子
                                    File tf = new File(staging, "meta.torrent");
                                    FileOutputStream mo = new FileOutputStream(tf);
                                    try { mo.write(mbb); } finally { try { mo.close(); } catch (Throwable ignored) {} }
                                    torrentFile = tf.getAbsolutePath();
                                    postState(id, "parsing", "磁力：已取到种子信息，正在连接节点…", null, null);
                                }
                            }
                        } catch (Throwable ignored) {
                        } finally {
                            try { if (mi != null) mi.close(); } catch (Throwable ignored) {}
                            if (c != null) { try { c.disconnect(); } catch (Throwable ignored) {} }
                        }
                    }
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("任务被取消");
                    List<String> args = new ArrayList<>();
                    args.add(aria2cBin());
                    stream.options(args);
                    args.add("--dir=" + staging.getAbsolutePath());
                    args.add("--seed-time=0");
                    args.add("--file-allocation=none");
                    args.add("--summary-interval=1");
                    args.add("--human-readable=false");
                    args.add("--enable-color=false");
                    args.add("--show-console-readout=false");
                    args.add("--console-log-level=notice");
                    args.add("--enable-dht=true");
                    args.add("--enable-dht6=false");
                    args.add("--dht-entry-point=router.bittorrent.com:6881");
                    args.add("--bt-enable-lpd=true");
                    args.add("--enable-peer-exchange=true");
                    // v1.69：端口段按任务错开（5 段×10 口循环）——固定同一段时并发磁力第二个
                    // 绑定失败，表现为随机"磁力下载失败"
                    int pseg = (BT_PORT_SEQ.getAndIncrement() % 5 + 5) % 5;
                    int plo = 51413 + pseg * 10;
                    args.add("--listen-port=" + plo + "-" + (plo + 9));
                    args.add("--dht-listen-port=" + plo + "-" + (plo + 9));
                    args.add("--split=8");
                    args.add("--max-connection-per-server=8");
                    args.add("--bt-max-peers=80");
                    args.add("--bt-request-peer-speed-limit=5M");
                    args.add("--user-agent=Transmission/2.94");
                    if (sslCertPath != null) args.add("--ca-certificate=" + sslCertPath);   // https tracker 需要
                    args.add("--bt-tracker=" + joinTrackers());
                    args.add(torrentFile != null ? torrentFile : magnet);
                    // 环境必须与 SSR 快通道一致：libaria2c.so 的依赖库在 packages/aria2c/usr/lib，
                    // 不设 LD_LIBRARY_PATH 进程可能直接起不来（磁力"解析失败"的根因）
                    ProcessBuilder pb = new ProcessBuilder(args).redirectErrorStream(true);
                    if (ldLibPath != null) pb.environment().put("LD_LIBRARY_PATH", ldLibPath);
                    final Process p = pb.start();
                    btProcs.put(id, p);
                    stream.observe(p);
                    final long startAt = System.currentTimeMillis();
                    final java.util.concurrent.atomic.AtomicLong lastProgressAt = new java.util.concurrent.atomic.AtomicLong(0);
                    // 读输出行：进度 + 无源看门狗（冷门种子元数据永久挂起的根治）
                    final StringBuilder tail = new StringBuilder();
                    Thread reader = new Thread(new Runnable() {
                        @Override public void run() {
                            try {
                                BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                                String line;
                                long transferred = 0;
                                boolean announced = false;
                                while ((line = br.readLine()) != null) {
                                    synchronized (tail) {
                                        tail.append('\n').append(line);
                                        if (tail.length() > 600) tail.delete(0, tail.length() - 600);   // 保留尾部用于报错
                                    }
                                    java.util.regex.Matcher m = BT_SUMMARY.matcher(line);
                                    if (!m.find()) continue;
                                    long downloaded = Long.parseLong(m.group(1));
                                    long total = Long.parseLong(m.group(2));
                                    if (total <= 0) continue;
                                    if (!announced) {
                                        postState(id, "downloading", "磁力下载", null, null);
                                        announced = true;
                                    }
                                    if (downloaded > transferred) {
                                        lastProgressAt.set(System.currentTimeMillis());
                                        transferred = downloaded;
                                    }
                                    JSONObject metrics = new JSONObject();
                                    metrics.put("status", "downloading");
                                    metrics.put("downloaded_bytes", downloaded);
                                    metrics.put("total_bytes", total);
                                    metrics.put("speed", Long.parseLong(m.group(6)));
                                    metrics.put("note", "连接节点 " + m.group(4) + " · 做种 " + m.group(5));
                                    dPostProgress(id, Math.min(99, Integer.parseInt(m.group(3))), metrics.toString());
                                }
                            } catch (Exception ignored) {}
                        }
                    });
                    reader.setDaemon(true);   // 不设守护的话，极端情况下读线程能拖住进程退出
                    reader.start();
                    // 看门狗：10 分钟没有新进度 → 判定无源，杀掉并报错。
                    // 基准是"最近一次收到进度"——元数据解析不出（一直 0）和下载中途断种（进度停住）
                    // 都会触发；之前只盯"从未有过进度"，断种的任务会永久挂起占死并发槽
                    while (true) {
                        long lpa = lastProgressAt.get();
                        long ref = lpa == 0 ? startAt : lpa;
                        if (System.currentTimeMillis() - ref > 600000) {
                            FfmpegTool.destroyProcess(p);
                            postState(id, "error", null, lpa == 0
                                ? (torrentFile != null
                                    ? "已取到种子信息，但 10 分钟没连上可用节点：该资源可能已无做种，建议换一个链接或稍后再试"
                                    : "磁力元数据解析超时（10 分钟没有可用源）：该资源可能已无做种或太冷门，建议换一个链接或稍后再试")
                                : "磁力下载超时（10 分钟没有收到新数据）：做种已断或太冷门，建议换一个链接或稍后再试", null);
                            deleteRecursive(staging);
                            btProcs.remove(id);
                            return;
                        }
                        // v1.99：waitFor(2s) 代替 exitValue 抛异常 + sleep——语义相同，无每 2 秒一次的异常开销
                        if (FfmpegTool.waitForProcess(p, 2000)) break;
                    }
                    int code = p.exitValue();
                    btProcs.remove(id);
                    if (code != 0) {
                        deleteRecursive(staging);
                        String reason;
                        synchronized (tail) { reason = tail.toString().trim().replace('\n', ' '); }
                        // 模拟器/转译层跑不了 ARM BT 引擎（真机不受影响）：别把一长串链接器报错甩给用户
                        if (reason.indexOf("CANNOT LINK EXECUTABLE") >= 0 || reason.indexOf("ndk_translation") >= 0) {
                            postState(id, "error", null, "当前环境跑不了 BT 引擎（模拟器/转译层限制），真机上不受影响", null);
                            return;
                        }
                        if (reason.isEmpty()) reason = "可能是元数据解析失败或没有可用节点";
                        postState(id, "error", null, "磁力下载失败（退出码 " + code + "）：" + reason, null);
                        return;
                    }
                    // 平移 staging 内全部文件（种子常带子目录）进主目录，重名自动加序号
                    final List<File> files = new ArrayList<>();
                    walk(staging, files);
                    if (files.isEmpty()) {
                        deleteRecursive(staging);
                        postState(id, "error", null, "磁力下载结束但没有拿到文件：元数据可能没解析出来，换个磁力链接（或稍后）再试", null);
                        return;
                    }
                    java.util.Collections.sort(files, new java.util.Comparator<File>() {
                        public int compare(File a, File b) { return Long.compare(b.length(), a.length()); }
                    });
                    final List<String> saved = new ArrayList<>();
                    int moveFailed = 0;
                    for (File f : files) {
                        if (f.length() == 0) continue;
                        if (f.getName().endsWith(".torrent") || f.getName().endsWith(".aria2")) continue;   // 元数据快速通道留下的种子文件，不是产物
                        String ext = extOf(f.getName());
                        String target;
                        synchronized (stream) {
                            target = commitFile(f, vdDir, sanitizeName(f.getName().substring(0, Math.max(0, f.getName().length() - ext.length()))) + ext);
                            if (target != null) stream.moved(f, new File(vdDir, target));
                        }
                        if (target == null) moveFailed++;
                        if (target != null) saved.add(target);   // 挪失败绝不能进"成功"清单（会报 done 却没有文件）
                    }
                    if (moveFailed > 0) {
                        keepStaging = true;
                        scanToGallery(saved); markOwned(saved); ensureThumbs(saved);
                        postState(id, "error", null, moveFailed + " 个文件未能移入保存目录；已下载内容保留在 " + staging.getAbsolutePath(), new JSONArray(saved).toString());
                        return;
                    }
                    if (saved.isEmpty()) {
                        deleteRecursive(staging);
                        postState(id, "error", null, "磁力下载结束但文件搬移失败（存储权限/同名占用），重试一次", null);
                        return;
                    }
                    stream.complete();
                    deleteRecursive(staging);
                    String filesJson = new JSONArray(saved).toString();
                    scanToGallery(saved);
                    markOwned(saved);
                    ensureThumbs(saved);
                    String title = saved.size() == 1 ? saved.get(0).replaceAll("\\.[^.]+$", "") : "磁力下载（" + saved.size() + " 个文件）";
                    postState(id, "done", title, null, filesJson);
                } catch (InterruptedException ie) {
                    postState(id, "error", null, "任务被取消", null);   // v1.70：取消不再显示 "null"（getMessage 为空被 String.valueOf 打成 "null"）
                } catch (Throwable t) {
                    postState(id, "error", null, errMsg(t), null);
                } finally {
                    stream.stopped();
                    Process remaining = btProcs.remove(id);
                    if (remaining != null) FfmpegTool.destroyProcess(remaining);
                    if (!keepStaging) deleteRecursive(staging);
                    freeSlot(slotFreed);
                    threads.remove(id, Thread.currentThread());
                    stagingDirs.remove(id);
                    svcRelease(ctx);
                }
            }
        });
        threads.put(id, t);
        t.start();
        return id;
    }

    /** 图集/笔记救援：App 内采集到的直链在手机上直接下载（并行，见 downloadMediaUrls） */
    public String rescueSubmit(final String mediaJson) {
        final String id = newId();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try { slots.acquire(); }
            catch (InterruptedException ie) {
                stagingDirs.remove(id);
                threads.remove(id);
                postState(id, "error", null, "任务被取消", null);
                return;
            }
                svcAcquire(ctx);   // v1.99 前台保活
                final boolean[] slotFreed = {false};
                try {
                JSONObject o = new JSONObject(mediaJson);
                    JSONArray images = o.optJSONArray("images");
                    JSONArray videos = o.optJSONArray("videos");
                    String desc = (o.optString("desc") + "").trim();
                    List<String> urls = new ArrayList<>();
                    if (videos != null) for (int i = 0; i < videos.length(); i++) urls.add(videos.getString(i));
                    if (images != null) for (int i = 0; i < images.length(); i++) urls.add(images.getString(i));
                    if (urls.isEmpty()) { postState(id, "error", null, "采集到 0 条媒体：页面结构可能变了", null); return; }
                    String fileBase = sanitizeName(desc.isEmpty() ? "采集" : desc);   // 文件名保留 #话题/@提及 全文
                    String rawTitle = desc.isEmpty() ? fileBase : desc;               // 状态发原始描述：页面清洗出标题+话题行
                    postState(id, "downloading", rawTitle, null, null);
                    java.util.Set<String> videoUrls = new java.util.HashSet<>();
                    if (videos != null) for (int i = 0; i < videos.length(); i++) videoUrls.add(videos.getString(i));
                    List<String> saved = downloadMediaUrls(id, urls, fileBase, o.optString("referer", ""), o.optString("userAgent", ""), videoUrls);
                    if (saved.isEmpty()) { postState(id, "error", null, "图片/视频都没下载下来：网络被掐或链接过期，重试一次", null); return; }
                    freeSlot(slotFreed);   // v1.99：产物已落根目录（downloadMediaUrls 直写），转码不再占下载槽
                    // 视频归一成 H.264 mp4（og:video/网页采集抓到的可能是 HEVC，部分手机解不了）——与守护进程路径行为一致
                    tcEnter();
                    List<String> fin;
                    try {
                        fin = new ArrayList<>();
                        for (String n : saved) fin.add(isVideoName(n) ? normalizeVideo(n, id, rawTitle) : n);
                    } finally { tcSlots.release(); }
                    String title = fin.size() == urls.size() ? rawTitle : rawTitle + "（" + fin.size() + "/" + urls.size() + "）";
                    scanToGallery(fin);
                    markOwned(fin);
                    ensureThumbs(fin);
                    if (fin.size() != urls.size())
                        postState(id, "error", title, "仅保存成功 " + fin.size() + "/" + urls.size()
                            + " 项，已保存文件仍可在文件页查看。其余媒体下载失败，未标记为全部完成。", new JSONArray(fin).toString());
                    else postState(id, "done", title, null, new JSONArray(fin).toString());
                } catch (InterruptedException ie) {
                    postState(id, "error", null, "任务被取消", null);
                } catch (Throwable t2) {
                    postState(id, "error", null, errMsg(t2), null);
                } finally {
                    freeSlot(slotFreed);
                    threads.remove(id, Thread.currentThread());
                    svcRelease(ctx);
                }
            }
        });
        threads.put(id, t);
        t.start();
        return id;
    }

    /** 整组文件名是否已被占用（含转 jpg 后的名字）——两组同标题图集不能互相穿插 */
    private boolean galleryTaken(String gbase, List<String> urls) {
        for (int i = 0; i < urls.size(); i++) {
            String stem = gbase + (urls.size() > 1 ? "-" + (i + 1) : "");
            String ext = extFromUrl(urls.get(i));
            if (new File(vdDir, stem + ext).exists() || new File(vdDir, stem + ".jpg").exists() || new File(vdDir, stem + ".mp4").exists()) return true;
        }
        return false;
    }

    /** 并行下载一组直链（图集）：最多 6 线程同时拉，图片顺手转 jpg。
     *  命名"基名-序号"成组（基名整组去重）——「我的保存」按这个模式聚成一条图集记录 */
    private List<String> downloadMediaUrls(final String id, List<String> urls, String base) throws InterruptedException {
        return downloadMediaUrls(id, urls, base, "", "", java.util.Collections.emptySet());
    }

    private List<String> downloadMediaUrls(final String id, List<String> urls, String base, final String referer, final String userAgent, final java.util.Set<String> videoUrls) throws InterruptedException {
        final File staging = new File(vdDir, ".staging-" + id);
        if (!staging.mkdirs() && !staging.isDirectory()) return new ArrayList<>();
        stagingDirs.put(id, staging);
        final String[] targets = new String[urls.size()];
        for (int i = 0; i < urls.size(); i++) targets[i] = (i + 1) + (videoUrls.contains(urls.get(i)) ? ".mp4" : extFromUrl(urls.get(i)));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
            Math.min(6, Math.max(2, urls.size())));
        final java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger(0);
        try {
            for (int i = 0; i < urls.size(); i++) {
                final int index = i;
                pool.submit(new Runnable() {
                    @Override public void run() {
                        try { downloadTo(urls.get(index), new File(staging, targets[index]), referer, userAgent); }
                        catch (Exception ignored) {}
                        final int completed = done.incrementAndGet();
                        dPostProgress(id, Math.min(99, completed * 100 / urls.size()), completed + "/" + urls.size());
                    }
                });
            }
            pool.shutdown();
            if (!pool.awaitTermination(15, java.util.concurrent.TimeUnit.MINUTES)) return new ArrayList<>();
            List<String> saved = new ArrayList<>();
            synchronized (FILE_COMMIT_LOCK) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("任务被取消");
                String group = base;
                int suffix = 1;
                while (galleryTaken(group, urls)) group = base + "(" + (suffix++) + ")";
                for (int i = 0; i < targets.length; i++) {
                    File file = new File(staging, targets[i]);
                    if (!file.isFile() || file.length() == 0) continue;
                    String name = group + (urls.size() > 1 ? "-" + (i + 1) : "") + targets[i].substring(targets[i].lastIndexOf('.'));
                    String moved = commitFile(file, vdDir, name);
                    if (moved != null) saved.add(moved);
                }
            }
            List<String> converted = new ArrayList<>();
            for (String name : saved) converted.add(imageToJpg(name, id));
            return converted;
        } finally {
            pool.shutdownNow();
            stagingDirs.remove(id, staging);
            deleteRecursive(staging);
        }
    }

    public void cancel(String id) {
        MagnetStream.cancel(id);
        synchronized (dTasks) {
            DaemonTask task = dTasks.get(id);
            if (task == null) task = finishingTasks.get(id);
            if (task != null) task.cancelled = true;
        }
        Thread worker = threads.get(id);
        if (worker != null) worker.interrupt();
        String mode = taskMode.remove(id);
        if ("daemon".equals(mode)) {
            // 守护任务：通知守护进程里的进度钩子抛取消异常（回调会负责收尾+释放并发槽）
            try {
                JSONObject c = new JSONObject();
                c.put("id", id);
                c.put("action", "cancel");
                daemonSend(c);
            } catch (Throwable ignored) {}
            return;
        }
        if ("ssr".equals(mode)) {
            Process bp = btProcs.remove(id);   // 快通道的 aria2c 挂在 btProcs 里
            if (bp != null) { try { FfmpegTool.destroyProcess(bp); } catch (Exception ignored) {} }
            Thread th = threads.remove(id);
            if (th != null) th.interrupt();
            return;
        }
        // 旧路径 / 磁力
        String p = processIds.remove(id);
        if (p != null) {
            try { YoutubeDL.getInstance().destroyProcessById(p); } catch (Exception ignored) {}
        }
        Process bp = btProcs.remove(id);
        if (bp != null) {
            try { FfmpegTool.destroyProcess(bp); } catch (Exception ignored) {}
        }
        File st = stagingDirs.remove(id);
        if (st != null) deleteRecursive(st);   // 用任务自己的 staging 快照（用户中途切过目录时 vdDir 已变，按当前目录拼会删错/删不到）
        Thread t = threads.remove(id);
        if (t != null) t.interrupt();
    }

    private void applyCommon(YoutubeDLRequest req, String quality, String cookies, String id, String url) throws Exception {
        // 编码优先选 WebView 能直接播的（h264/vp9）：yt-dlp 默认排序 h265 优先于 h264，
        // 而大量手机的 WebView 解不了 HEVC（应用内表现为有声无画）——先滤掉 hevc/h265/av01 挑，
        // 挑不到再回退任意编码（此时页面有「系统播放器」兜底）
        req.addOption("-f", downloadFormat(url, quality));
        if (aria2Ok) req.addOption("--downloader", "libaria2c.so");   // aria2c 下载：快、支持断点（yt-dlp 对它默认 -x16 -s16）
        req.addOption("--concurrent-fragments", "8");   // HLS/DASH 分片并行拉取：4 → 8
        req.addOption("--retries", "3");                // 失败场景收敛更快
        req.addOption("--extractor-retries", "2");
        req.addOption("--no-warnings");                 // 少输出警告行：每行都要过一遍 JNI 回调
        req.addOption("--socket-timeout", "15");
        req.addOption("--no-mtime");
        req.addOption("--no-update");                      // 静默内置版过旧提醒（更新走 App 安装包）
        String ck = writeCookies(id, cookies);              // 页面已生成 Netscape 格式（含表头）；每任务独立文件防并发互踩
        if (ck != null) req.addOption("--cookies", ck);
    }

    // 短链展开结果缓存（值 [展开结果, 时间戳]，10 分钟内同链接零等待）。
    // static：Activity 重建会换引擎实例，实例级缓存跟着丢，预热白做
    private static final Map<String, String[]> expandCache = new ConcurrentHashMap<>();

    /** 预取：用户还在看链接没点解析时，后台先把短链展开好，点「解析」时直接进提取 */
    public void prefetch(final String url) {
        if (url == null || url.trim().isEmpty()) return;
        new Thread(new Runnable() {
            @Override public void run() { try { expandShortLink(url.trim()); } catch (Throwable ignored) {} }
        }, "vd-prefetch").start();
    }

    private String expandShortLink(String url) {
        String u = url == null ? "" : url.trim();
        String low = u.toLowerCase(java.util.Locale.ROOT);
        if (!(low.contains("://v.douyin.com") || low.contains(".iesdouyin.com") || low.contains("://iesdouyin.com")
            || low.contains("://b23.tv") || low.contains("://xhslink.com") || low.contains("://xhslink.cn")
            || low.contains("://v.kuaishou.com") || low.contains("://t.cn/") || low.contains("://v.ixigua.com"))) return u;
        String[] hit = expandCache.get(u);
        if (hit != null && System.currentTimeMillis() - Long.parseLong(hit[1]) < 600000) return hit[0];
        String r = expandOnce(u);
        if (!r.equals(u)) {   // 展开成功才缓存；失败（原样返回）不缓存——弱网恢复后重试同一链接还能再展开
            if (expandCache.size() > 200) expandCache.clear();
            expandCache.put(u, new String[]{r, String.valueOf(System.currentTimeMillis())});
        }
        return r;
    }

    /**
     * 分享短链展开成标准视频页链接。内置 lazy 版 yt-dlp 的解析器只认标准格式
     * （www.douyin.com/video/ID、bilibili.com/video/BV…、xiaohongshu.com/explore/ID），
     * v.douyin.com / b23.tv / xhslink.com 这类 App 分享短链会落到 generic 提取器，直接被站点风控掐断。
     * 任何一步失败都原样返回，由 yt-dlp 自己报错。
     */
    private static String expandOnce(String url) {
        String u = url == null ? "" : url.trim();
        HttpURLConnection c = null;
        try {
            String low = u.toLowerCase(java.util.Locale.ROOT);
            boolean dy = low.contains("://v.douyin.com") || low.contains(".iesdouyin.com") || low.contains("://iesdouyin.com");
            boolean b23 = low.contains("://b23.tv");
            boolean xhs = low.contains("://xhslink.com") || low.contains("://xhslink.cn");
            // 快手/微博 t.cn/西瓜短链：跟完 302 落到哪页就交哪页（yt-dlp 或采集兜底接着处理）
            boolean gen = low.contains("://v.kuaishou.com") || low.contains("://t.cn/") || low.contains("://v.ixigua.com");
            if (!dy && !b23 && !xhs && !gen) return u;

            c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36");
            int code = c.getResponseCode();
            String fin = c.getURL().toString();   // 跟完 302 的最终地址

            if (dy) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("/video/(\\d{6,})").matcher(fin);
                if (m.find()) return "https://www.douyin.com/video/" + m.group(1);
                m = java.util.regex.Pattern.compile("/note/(\\d{6,})").matcher(fin);
                if (m.find()) return "https://www.douyin.com/note/" + m.group(1);
                m = java.util.regex.Pattern.compile("modal_id=(\\d{6,})").matcher(fin);
                if (m.find()) return "https://www.douyin.com/video/" + m.group(1);
            } else if (b23) {
                // 保留完整 query（分P ?p=2 等信息在最终地址里）
                if (fin.contains("bilibili.com/video/")) return fin;
                String host = android.net.Uri.parse(fin).getHost();
                if (host != null && ("bilibili.com".equalsIgnoreCase(host)
                    || host.toLowerCase(java.util.Locale.ROOT).endsWith(".bilibili.com"))) return fin;
            } else if (xhs) {
                // 保留完整 query（?xsec_token= 小红书解析必需）
                if (fin.contains("xiaohongshu.com/explore/") || fin.contains("xiaohongshu.com/discovery/item/")) return fin;
            } else if (gen) {
                // 快手/t.cn/西瓜：落到真实页面即可（没跳转=可能 200 落地页，交给采集兜底）
                if (!fin.equals(u) && fin.startsWith("http")) return fin;
            }

            // 有些短链不回 302 而是回 200 落地页：读页面正文再找一遍目标链接。
            // 页面里可能出现多个视频 ID（推荐流/用户主页）——只有唯一 ID 才敢用，
            // 多个不同 ID 时无法确定分享的是哪个，宁可原样返回让 yt-dlp 报错，不能下错内容
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                try {
                    int n;
                    while (bos.size() < 262144 && (n = in.read(buf)) > 0) bos.write(buf, 0, n);
                } finally {
                    try { in.close(); } catch (Throwable ignored) {}
                }
                String body = new String(bos.toByteArray(), "UTF-8");
                java.util.HashSet<String> ids = new java.util.HashSet<>();
                if (dy) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:video|note)/(\\d{6,})").matcher(body);
                    while (m.find()) ids.add(m.group(1));
                    if (ids.size() == 1) {
                        String id = ids.iterator().next();
                        return "https://www.douyin.com/" + (body.contains("note/" + id) ? "note/" : "video/") + id;
                    }
                } else if (b23) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("bilibili\\.com/video/[A-Za-z0-9]+").matcher(body);
                    while (m.find()) ids.add(m.group());
                    if (ids.size() == 1) return "https://www." + ids.iterator().next();
                } else if (xhs) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("xiaohongshu\\.com/(?:explore|discovery/item)/[0-9a-f]{8,}").matcher(body);
                    while (m.find()) ids.add(m.group());
                    if (ids.size() == 1) return "https://www." + ids.iterator().next();
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) { try { c.disconnect(); } catch (Throwable ignored) {} }
        }
        return u;
    }

    /** 给 yt-dlp 原始报错补一句人话指引 */
    private static String friendlyError(String msg) {
        if (msg.contains("[generic]")) {
            if (msg.contains("v.douyin.com") || msg.contains("b23.tv") || msg.contains("xhslink"))
                msg += "。短链没能展开成标准链接：在浏览器里打开这个链接，把地址栏的完整链接复制回来重试";
            else
                msg += "。内置引擎不支持这个页面（图集/图文链接会由 App 自动转采集下载，一般无需手动处理）";
        } else if (msg.contains("Remote end closed") || msg.contains("Connection reset") || msg.contains("timed out")) {
            msg += "。目标站点掐断了连接（移动网络常见）：切换 WiFi/流量后重试一次";
        }
        return msg;
    }

    /** 扫描进系统媒体库：相册/图库里立刻能看到刚下载的视频和图片 */
    private void scanToGallery(List<String> names) {
        try {
            ArrayList<String> paths = new ArrayList<>();
            for (String n : names) {
                File f = new File(vdDir, n);
                if (f.isFile()) paths.add(f.getAbsolutePath());
            }
            if (paths.isEmpty()) return;
            android.media.MediaScannerConnection.scanFile(ctx, paths.toArray(new String[0]), null, null);
        } catch (Throwable ignored) {}
    }

    /** 文件名 → 展示标题：去扩展名、去分轨后缀（title.f137.mp4 → title） */
    private static String titleFromName(String n) {
        String t = n.replaceAll("\\.[^.]+$", "");
        t = t.replaceAll("\\.f\\d+$", "");
        return t;
    }

    /** 目录里所有媒体文件扫描进系统媒体库（App 启动时兜底：老版本下载的文件从没扫过） */
    private void scanAllToGallery() {
        try {
            if (vdDir == null) return;
            File[] all = vdDir.listFiles();
            ArrayList<String> paths = new ArrayList<>();
            if (all != null) {
                for (File f : all) {
                    if (!f.isFile() || f.getName().startsWith(".")) continue;
                    if (!MEDIA_NAME.matcher(f.getName()).matches()) continue;
                    paths.add(f.getAbsolutePath());
                }
            }
            if (!paths.isEmpty()) {
                android.media.MediaScannerConnection.scanFile(ctx, paths.toArray(new String[0]), null, null);
            }
        } catch (Throwable ignored) {}
    }

    /** 当前保存目录是否就是所选模式的目标目录（public 模式下写进去了才算） */
    public boolean dirIsPublic() {
        try {
            String mode = dirMode();
            if ("private".equals(mode)) return false;
            return vdDir != null && vdDir.getCanonicalPath().equals(dirForMode(mode).getCanonicalPath());
        } catch (Exception e) { return false; }
    }

    /** 授权后调用：按当前设置模式重新探测目录并把 App 自己的文件迁过去（名单制，相机内容不动）。
     *  返回迁移后的目录路径；仍不可写时返回 null */
    public String migrateToPublic() {
        return setDirMode(dirMode());
    }

    /** uniqueName 的公共目录版（迁移时目标目录与当前 vdDir 不同，不能用成员版） */
    private static String uniqueName(File dir, String want) {
        File f = new File(dir, want);
        if (!f.exists()) return want;
        int i = want.lastIndexOf('.');
        String ext = i >= 0 ? want.substring(i) : "";
        String base = i >= 0 ? want.substring(0, i) : want;
        for (int n = 1; n < 1000; n++) {
            File g = new File(dir, base + "(" + n + ")" + ext);
            if (!g.exists()) return g.getName();
        }
        return base + "-" + UUID.randomUUID() + ext;
    }

    /** 应用内所有成品提交共用同一把锁，选名到搬移之间不会被另一任务抢占。 */
    private static String commitFile(File source, File directory, String name) {
        synchronized (FILE_COMMIT_LOCK) {
            String target = uniqueName(directory, name);
            if (!source.renameTo(new File(directory, target))) return null;
            invalidateThumb(directory, target);
            return target;
        }
    }

    private void downloadTo(String url, File out, String referer, String userAgent) throws Exception {
        HttpURLConnection c = null;
        try {
            URL target = new URL(url);
            for (int redirects = 0; ; redirects++) {
                if (!"http".equals(target.getProtocol()) && !"https".equals(target.getProtocol()))
                    throw new java.io.IOException("Unsupported media URL");
                c = (HttpURLConnection) target.openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(60000);
                c.setInstanceFollowRedirects(false);
                c.setRequestProperty("User-Agent", userAgent != null && !userAgent.isEmpty() && !userAgent.contains("\r") && !userAgent.contains("\n")
                    ? userAgent : "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
                if (referer != null && referer.startsWith("http") && !referer.contains("\r") && !referer.contains("\n"))
                    c.setRequestProperty("Referer", referer);
                else if (url.contains("douyin")) c.setRequestProperty("Referer", "https://www.douyin.com/");
                else if (url.contains("xiaohongshu") || url.contains("xhscdn")) c.setRequestProperty("Referer", "https://www.xiaohongshu.com/");
                String cookie = android.webkit.CookieManager.getInstance().getCookie(target.toString());
                if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
                int code = c.getResponseCode();
                if (code != 301 && code != 302 && code != 303 && code != 307 && code != 308) break;
                String location = c.getHeaderField("Location");
                if (redirects >= 8 || location == null) throw new java.io.IOException("Media redirect failed");
                target = new URL(target, location);
                c.disconnect();
            }
            long expect = c.getContentLengthLong();
            long got = 0;
            try (java.io.BufferedInputStream in = new java.io.BufferedInputStream(c.getInputStream());
                    OutputStream fo = new FileOutputStream(out)) {
                in.mark(32);
                byte[] prefix = new byte[32];
                int size = 0, part;
                while (size < prefix.length && (part = in.read(prefix, size, prefix.length - size)) != -1) size += part;
                if (!MediaProbe.accepts(prefix, size))
                    throw new java.io.IOException("媒体地址返回了网页、错误信息或不支持的文件；请登录来源网页后重新识别");
                in.reset();
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (Thread.currentThread().isInterrupted()) throw new java.io.IOException("任务被取消");
                    fo.write(buf, 0, n);
                    got += n;
                }
            }
            if (expect > 0 && got < expect) throw new java.io.IOException("short read: " + got + "/" + expect);
            if (got == 0) throw new java.io.IOException("empty body");
        } catch (Exception e) {
            out.delete();
            throw e;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 下载目录里的媒体文件列表（[{name,sizeMB,mtime}]，页面直接渲染）。
     *  自定义目录/相册目录可能与别人的文件同住一个屋檐——只列本 App 下载的文件；
     *  下载/vd 和应用私有目录是本 App 独占的，全列 */
    private static final java.util.concurrent.atomic.AtomicLong mediaRevision = new java.util.concurrent.atomic.AtomicLong();
    private String filesCacheKey = "", filesCacheJson = "[]";
    private long filesCacheAt;

    public synchronized String refreshFiles() {
        filesCacheAt = 0;
        return listFiles();
    }

    public synchronized String listFiles() {
        try {
            final File directory = vdDir;
            if (directory == null) return "[]";
            long now = android.os.SystemClock.elapsedRealtime();
            String cacheKey = directory.getAbsolutePath() + "|" + directory.lastModified() + "|" + mediaRevision.get()
                + "|" + dirMode() + "|" + prefs.getString("kv_vd_hidden", "[]");
            if (filesCacheAt > 0 && now - filesCacheAt < 3000 && cacheKey.equals(filesCacheKey)) return filesCacheJson;
            String mode = dirMode();
            boolean filterOwned = "custom".equals(mode) || "dcim".equals(mode);
            java.util.HashSet<String> own = filterOwned ? ownedSet() : null;
            java.util.HashSet<String> hidden = hiddenNames();
            File[] all = directory.listFiles();
            JSONArray arr = new JSONArray();
            if (all != null) {
                ArrayList<File> files = new ArrayList<>();
                java.util.Map<File, Long> modified = new java.util.HashMap<>();
                for (File f : all) {
                    String n = f.getName();
                    if (!f.isFile() || n.startsWith(".")) continue;
                    if (!MEDIA_NAME.matcher(n).matches()) continue;
                    if (own != null && !own.contains(n)) continue;
                    if (hidden.contains(n)) continue;   // 仅删记录：不再列出
                    files.add(f);
                    modified.put(f, f.lastModified());
                }
                java.util.Collections.sort(files, new java.util.Comparator<File>() {
                    public int compare(File a, File b) { return Long.compare(modified.get(b), modified.get(a)); }
                });
                for (File f : files) {
                    JSONObject o = new JSONObject();
                    o.put("name", f.getName());
                    o.put("sizeMB", Math.round(f.length() / 104857.6) / 10.0);
                    o.put("mtime", modified.get(f));
                    arr.put(o);

                }
            }
            filesCacheJson = arr.toString();
            filesCacheKey = cacheKey;
            filesCacheAt = now;
            return filesCacheJson;
        } catch (Exception e) { return "[]"; }
    }

    /** 删除文件。namesJson: ["a.mp4","b.jpg"] */
    public String deleteFiles(String namesJson) {
        mediaRevision.incrementAndGet();
        JSONObject out = new JSONObject();
        try {
            JSONArray names = new JSONArray(namesJson);
            int ok = 0; StringBuilder err = new StringBuilder();
            for (int i = 0; i < names.length(); i++) {
                String n = names.getString(i);
                if (n.contains("/") || n.contains("\\") || n.contains("..")) continue;   // 防穿越
                File f = new File(vdDir, n);
                if (f.exists() && f.delete()) {
                    // 同步清掉媒体库里的行：光删文件会留孤儿行——图库里还挂着、部分 ROM（MIUI 等）
                    // 还会弹"应用删除了图集"并把条目塞进图库回收站。有所有文件访问权限可直接删行，不进回收站
                    purgeMediaRow(f);
                    invalidateThumb(vdDir, n);
                    ok++;
                } else err.append(n).append(' ');
            }
            out.put("ok", ok);
            out.put("error", err.length() == 0 ? "" : "未找到或删除失败: " + err.toString().trim());
        } catch (Exception e) {
            try { out.put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) {}
        }
        return out.toString();
    }

    /** 按路径把文件对应的媒体库行删掉（Images/Video/Down­loads 三张表都试；有全文件权限时是硬删，不进回收站） */
    private void purgeMediaRow(File f) {
        try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            String path = f.getAbsolutePath();
            android.net.Uri[] tables = {
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            };
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                tables = java.util.Arrays.copyOf(tables, 3);
                tables[2] = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            }
            for (android.net.Uri table : tables) {
                try {
                    android.database.Cursor c = cr.query(table, new String[]{android.provider.BaseColumns._ID},
                        android.provider.MediaStore.MediaColumns.DATA + "=?", new String[]{path}, null);
                    while (c != null && c.moveToNext()) {
                        long id = c.getLong(0);
                        try { cr.delete(android.content.ContentUris.withAppendedId(table, id), null, null); } catch (Throwable ignored) {}
                    }
                    if (c != null) c.close();
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    // ---------- 小工具 ----------
    private String newId() { return UUID.randomUUID().toString().replace("-", "").substring(0, 10); }
    private String joinTrackers() {
        StringBuilder sb = new StringBuilder();
        for (String t : BT_TRACKERS) { if (sb.length() > 0) sb.append(','); sb.append(t); }
        return sb.toString();
    }
    private static String extOf(String name) {
        int i = name.lastIndexOf('.');
        return i >= 0 ? name.substring(i) : "";
    }
    private static String extFromUrl(String u) {
        // 小红书图片 URL 带 "!nd_dft_wlteh_webp_3" 这类处理后缀：先剥掉再判断扩展名
        String low = u == null ? "" : u.toLowerCase(java.util.Locale.ROOT);
        String path = low.split("[?#]")[0];
        int bang = path.indexOf('!');
        if (bang > 0) path = path.substring(0, bang);
        if (path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mov") || path.endsWith(".webm") || path.endsWith(".mkv"))
            return path.substring(path.lastIndexOf('.'));
        // 图片 CDN 直链一律按图片存——之前用"URL 含 video 字样"猜视频，图集直链误存成 .mp4，
        // 相册里整组图全被归到"视频"，这就是"图文下成视频"的根因
        boolean imgCdn = low.contains("douyinpic") || low.contains("webpic") || low.contains("mmbiz")
            || low.contains("sinaimg") || low.contains("ci.xiaohongshu");
        if (path.endsWith(".png")) return ".png";
        if (path.endsWith(".gif") || low.contains("wx_fmt=gif")) return ".gif";
        // 只看 path 段：整个 URL（含 query）里撞见 "webp" 字样就把视频存成 .webp，
        // 之后 imageToJpg 会把它"转"成一张单帧图——视频就这么没了
        if (path.endsWith(".webp") || path.contains("webp")) return ".webp";
        if (path.endsWith(".avif")) return ".avif";
        if (path.endsWith(".heic") || path.endsWith(".heif")) return ".heic";
        if (path.endsWith(".bmp")) return ".bmp";
        if (imgCdn) return ".jpg";
        if (low.contains("/video/") || low.contains("sns-video") || low.contains("douyinvod") || low.contains(".mp4")) return ".mp4";
        return ".jpg";
    }
    private static String sanitizeName(String s) {
        String out = s.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", " ").replaceAll("\\s+", " ").trim();
        return out.isEmpty() ? "download" : out;
    }
    /** 重名自动加序号：a.mp4 → a(1).mp4 */
    private static void deleteRecursive(File f) {
        try {
            if (!f.exists()) return;
            if (f.isDirectory()) {
                File[] kids = f.listFiles();
                if (kids != null) for (File k : kids) deleteRecursive(k);
            }
            f.delete();
        } catch (Exception ignored) {}
    }
    private static void walk(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) walk(k, out);
            else out.add(k);
        }
    }

    private void postState(String id, String state, String title, String error, String filesJson) {
        dPostState(id, state, title, error, filesJson);
    }

    /** 所有任务通道均向当前页面投递，主线程执行时再次确认接收实例。 */
    private static void dPostState(String id, String state, String title, String error, String filesJson) {
        if (id != null && ("done".equals(state) || "error".equals(state) || "collection".equals(state)))
            pendingTaskStates.put(id, new String[]{id, state, title, error, filesJson});
        final LocalEngine e = sActive;
        if (e == null || e.detached) return;
        e.main.post(new Runnable() {
            @Override public void run() {
                LocalEngine current = sActive;
                if (current != null && !current.detached) current.listener.onState(id, state, title, error, filesJson);
            }
        });
    }

    private static void dPostProgress(final String id, final int pct, final String l) {
        final LocalEngine e = sActive;
        if (e == null || e.detached) return;
        e.main.post(new Runnable() {
            @Override public void run() {
                LocalEngine current = sActive;
                if (current != null && !current.detached) current.listener.onProgress(id, pct, l);
            }
        });
    }

    private void post(Runnable r) {
        if (detached) return;   // v1.99：post() 只承载 listener 回调——宿主销毁后一并不再投递
        if (Looper.myLooper() == Looper.getMainLooper()) r.run(); else main.post(r);
    }
}
