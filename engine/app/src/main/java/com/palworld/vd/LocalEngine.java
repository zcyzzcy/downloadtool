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
import java.util.HashMap;
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
    private int slotCount = 2;
    // 任务执行通道：ssr=分享页直连 / daemon=常驻进程 / legacy=每任务一个进程 / bt=磁力
    private static final Map<String, String> taskMode = new ConcurrentHashMap<>();

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
    private static volatile LocalEngine sActive;   // 当前引擎实例：守护回调永远推给最新的 Activity
    private String sslCertPath, pythonHome, ldLibPath, nativeDirPath, ytdlpZipPath, ffmpegBinPath;

    private static class DaemonTask {
        final long startAtMs;
        final LocalEngine eng;   // 任务归属的引擎（slots/vdDir 都用它），跨 Activity 重建也不释放错对象
        volatile String lastTitle, destName, url;
        DaemonTask(LocalEngine e, long s, String u) { eng = e; startAtMs = s; url = u; }
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
        slots = new java.util.concurrent.Semaphore(n);
    }

    public String state() { return initState; }
    public String detail() { return initDetail; }
    public File dir() { return vdDir; }

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
        try {
            java.util.HashSet<String> s = ownedSet();
            boolean ch = false;
            for (String n : names) if (n != null && !n.startsWith(".") && s.add(n)) ch = true;
            if (ch) prefs.edit().putStringSet("vd_owned", new java.util.HashSet<>(s)).apply();
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
                    if (!n.matches("(?i).+\\.(" + MEDIA_EXT + ")$")) continue;
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
        try {
            if (!"private".equals(mode)) {
                if (probeWritable(want) == null) return null;   // 没授权/不可写
            } else {
                want.mkdirs();
            }
            File old = vdDir;
            if (old != null && old.isDirectory() && !old.getCanonicalPath().equals(want.getCanonicalPath())) {
                java.util.HashSet<String> own = ownedSet();
                File[] all = old.listFiles();
                if (all != null) {
                    for (File f : all) {
                        String n = f.getName();
                        if (f.isDirectory() || n.startsWith(".")) continue;
                        if (!own.contains(n)) continue;   // 只搬自己下载的
                        f.renameTo(new File(want, uniqueName(want, n)));
                    }
                }
                File thumbs = new File(old, ".thumbs");
                if (thumbs.isDirectory()) { File[] kids = thumbs.listFiles(); if (kids != null) for (File k : kids) k.delete(); thumbs.delete(); }
                File probe2 = new File(old, ".write-probe");
                if (probe2.exists()) probe2.delete();
                // 旧目录不删：里面可能还有不属于本 App 的内容（自定义目录/相册目录绝不能删）
            }
            vdDir = want;
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

    /** 封面目录 + .nomedia：缩略图不进相册。切目录后新目录也要有 */
    private void ensureThumbDir() {
        try {
            File tdir = new File(vdDir, ".thumbs");
            tdir.mkdirs();
            File nomedia = new File(tdir, ".nomedia");
            if (!nomedia.exists()) nomedia.createNewFile();
        } catch (Throwable ignored) {}
    }

    /** 封面补齐队列：双线程抽帧（单线程时几十个存量视频要排队十几分钟，看着像"没生成"） */
    private final java.util.concurrent.ExecutorService thumbExec =
        java.util.concurrent.Executors.newFixedThreadPool(2);

    /** MediaMetadataRetriever 会在个别 ROM/编码上卡死（不抛错不返回），放独立线程池并限时——
     *  卡住只废掉那一个池线程，封面队列照常推进（否则一个坏文件堵死全部封面） */
    private final java.util.concurrent.ExecutorService mmrPool =
        java.util.concurrent.Executors.newCachedThreadPool();

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
            if (w > 360) bmp = android.graphics.Bitmap.createScaledBitmap(bmp, 360, Math.max(1, h * 360 / w), true);
            java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, fo);
            fo.close();
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
                args.add("-y");
                args.add("-ss");
                args.add(ss);
                args.add("-i");
                args.add(src.getAbsolutePath());
                args.add("-frames:v");
                args.add("1");
                args.add("-vf");
                args.add("scale=360:-2");
                args.add("-q:v");
                args.add("4");
                args.add(out.getAbsolutePath());
                runFfmpeg(args, 25000);
                if (out.isFile() && out.length() > 0) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** 生成一个视频的封面：mmr（快）→ ffmpeg（稳）。已有封面直接跳过 */
    private void genThumb(File dir, String name) {
        try {
            File out = new File(new File(dir, ".thumbs"), name + ".jpg");
            if (out.isFile() && out.length() > 0) return;
            new File(dir, ".thumbs").mkdirs();
            File src = new File(dir, name);
            if (!src.isFile() || src.length() == 0 || !isVideoName(name)) return;
            boolean ok = false;
            try {
                ok = mmrPool.submit(new java.util.concurrent.Callable<Boolean>() {
                    @Override public Boolean call() { return extractFrameMmr(dir, name, out); }
                }).get(12, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Throwable ignored) {}
            if (!ok) ffmpegThumb(src, out);
        } catch (Throwable ignored) {}
    }

    /** 下载完成/列表刷新后补缺失的封面：页面渲染前就有了，不再依赖 404 重试碰运气 */
    public void ensureThumbs(final List<String> names) {
        if (vdDir == null || names == null || names.isEmpty()) return;
        final File d = vdDir;
        try {
            thumbExec.submit(new Runnable() {
                @Override public void run() { for (String n : names) genThumb(d, n); }
            });
        } catch (Throwable ignored) {}
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
                        if (!f.getName().matches("(?i).+\\.(" + MEDIA_EXT + ")$")) continue;
                        String target = uniqueName(pub, f.getName());
                        if (f.renameTo(new File(pub, target))) { n++; moved.add(target); }
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

    /** 跑一次 ffmpeg，返回合并输出（探测/转码共用），超时强杀 */
    private String runFfmpeg(List<String> args, long timeoutMs) {
        Process p = null;
        try {
            if (nativeDirPath == null) computeRuntimePaths();
            List<String> cmd = new ArrayList<>();
            cmd.add(ffmpegBin());
            cmd.addAll(args);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (ldLibPath != null) pb.environment().put("LD_LIBRARY_PATH", ldLibPath);
            pb.redirectErrorStream(true);
            final java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            p = pb.start();
            final Process proc = p;
            Thread rd = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        java.io.InputStream in = proc.getInputStream();
                        byte[] b = new byte[8192];
                        int n;
                        while ((n = in.read(b)) > 0) { synchronized (bos) { bos.write(b, 0, n); } }
                    } catch (Exception ignored) {}
                }
            }, "vd-ffmpeg-read");
            rd.setDaemon(true);
            rd.start();
            long waitEnd = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < waitEnd) {
                try { proc.exitValue(); break; } catch (IllegalThreadStateException e) { Thread.sleep(100); }
            }
            try { proc.destroyForcibly(); } catch (Throwable ignored) {}
            synchronized (bos) { return new String(bos.toByteArray(), "UTF-8"); }
        } catch (Throwable t) {
            return "";
        } finally {
            if (p != null) { try { p.destroyForcibly(); } catch (Exception ignored) {} }
        }
    }

    /** 探测媒体：返回 {videoCodec, audioCodec, durSec}；探测失败返回 null */
    private String[] probeMedia(String path) {
        List<String> args = new ArrayList<>();
        args.add("-hide_banner");
        args.add("-i");
        args.add(path);
        String out = runFfmpeg(args, 10000);
        java.util.regex.Matcher mv = java.util.regex.Pattern.compile("Stream #\\d+:\\d+.*?: Video: (\\w+)").matcher(out);
        if (!mv.find()) return null;
        java.util.regex.Matcher ma = java.util.regex.Pattern.compile("Stream #\\d+:\\d+.*?: Audio: (\\w+)").matcher(out);
        java.util.regex.Matcher md = java.util.regex.Pattern.compile("Duration: (\\d+):(\\d+):(\\d+)").matcher(out);
        long dur = 0;
        if (md.find()) dur = Long.parseLong(md.group(1)) * 3600 + Long.parseLong(md.group(2)) * 60 + Long.parseLong(md.group(3));
        return new String[]{ mv.group(1).toLowerCase(), ma.find() ? ma.group(1).toLowerCase() : "", String.valueOf(dur) };
    }

    private static boolean isVideoName(String n) {
        return n != null && n.toLowerCase().matches(".+\\.(mp4|m4v|mov|webm|mkv|flv|avi|ts)$");
    }

    private static boolean isImageName(String n) {
        return n != null && n.toLowerCase().matches(".+\\.(jpe?g|png|gif|webp|bmp|heic|heif|avif)$");
    }

    /** 页内播放黑屏自救（v2.39）：把 WebView 解不了的编码（HEVC 等）转成 H.264/AAC mp4。
     *  返回新文件名（已是万能格式则原样返回），异步调用，进度经 merging 状态静默推进 */
    public String transcodeToH264(final String name) {
        String out = normalizeVideo(name, "tc" + Long.toString(System.currentTimeMillis(), 36), null);
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

    /** 视频归一成 H.264/AAC 的 mp4。已合规原样返回名；容器不对→秒级 remux；编码不对→转码。
     *  任何失败都返回原文件名（原文件绝不删）。需要干活时先发 merging 状态（卡片显示 合并转码中…） */
    private String normalizeVideo(final String name, final String id, final String title) {
        try {
            File in = new File(vdDir, name);
            if (!in.isFile() || in.length() == 0 || !isVideoName(name)) return name;
            if (in.length() > 700L * 1024 * 1024) return name;   // 太大：手机软转太久，保持原样
            String low = name.toLowerCase();
            String[] p = probeMedia(in.getAbsolutePath());
            if (p == null) return name;
            String vc = p[0], ac = p[1];
            long durSec = Long.parseLong(p[2]);
            boolean vOk = "h264".equals(vc);
            boolean aOk = ac.isEmpty() || "aac".equals(ac) || "mp3".equals(ac);
            if (vOk && aOk && low.endsWith(".mp4")) return name;   // 已经是万能格式
            final String t = title == null ? titleFromName(name) : title;
            dPostState(id, "merging", t, null, null);
            String outName = uniqueName(name.replaceAll("\\.[^.]+$", "") + ".mp4");
            File out = new File(vdDir, ".tmp-" + id + "-" + Integer.toHexString(name.hashCode()) + ".mp4");
            out.delete();
            long timeout = Math.max(90000, Math.min(1800000, durSec * 6000));   // 转码限时：按时长估
            runFfmpeg(argsFor(in, out, vOk, aOk, ac, false), timeout);
            boolean ok = out.isFile() && out.length() > 0;
            if (!ok && !vOk) {   // libx264 不在这个 ffmpeg 构建里 → 用内置 mpeg4 编码器兜底
                out.delete();
                runFfmpeg(argsFor(in, out, vOk, aOk, ac, true), timeout);
                ok = out.isFile() && out.length() > 0;
            }
            if (ok) {
                // v2.42 产物验证：ffmpeg 退出≠产出合规（个别 ROM 上半途而废也能留下非空文件），
                // 转完再 probe 一次，视频轨不是 h264/mpeg4 就当失败——绝不能拿一个"转了但没转对"
                // 的文件冒充 H.264 交给页面（黑屏会更难排查）
                String[] q = probeMedia(out.getAbsolutePath());
                String nvc = q != null ? q[0] : "";
                if (!"h264".equals(nvc) && !"mpeg4".equals(nvc)) { out.delete(); return name; }
                // 先挪成功再删原文件：挪失败时原视频还在
                if (out.renameTo(new File(vdDir, outName))) { in.delete(); return outName; }
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
            if (od >= 0 && cut - od > 1.0) {
                out.delete();
                args = new ArrayList<>();
                args.add("-hide_banner");
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
                runFfmpeg(args, 300000);
            }
            // 切坏了/切空了都不要：产物至少得有原文件的一半大
            if (!out.isFile() || out.length() == 0 || out.length() < in.length() / 2) { out.delete(); return name; }
            String outName = uniqueName(name.replaceAll("\\.[^.]+$", "") + ".mp4");
            if (out.renameTo(new File(vdDir, outName))) { in.delete(); return outName; }
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
        String low = name == null ? "" : name.toLowerCase();
        if (!(low.endsWith(".webp") || low.endsWith(".avif") || low.endsWith(".heic") || low.endsWith(".heif"))) return name;
        try {
            File in = new File(vdDir, name);
            // 临时文件名必须带源文件名：图集是并行转换，同一任务 id 下多张图共用一个 tmp 会互相覆盖/抢走
            File out = new File(vdDir, ".tmp-" + id + "-" + Integer.toHexString(name.hashCode()) + ".jpg");
            out.delete();
            List<String> args = new ArrayList<>();
            args.add("-hide_banner");
            args.add("-y");
            args.add("-i");
            args.add(in.getAbsolutePath());
            args.add("-q:v");
            args.add("3");
            args.add(out.getAbsolutePath());
            runFfmpeg(args, 30000);
            if (out.isFile() && out.length() > 0) {
                String fin = uniqueName(name.replaceAll("\\.[^.]+$", "") + ".jpg");
                File dst = new File(vdDir, fin);
                // 先挪成功再删原文件：挪失败（重名/存储抖动）时原图还在，绝不让用户丢图
                if (out.renameTo(dst)) { in.delete(); return fin; }
                out.delete();
            }
            out.delete();
        } catch (Throwable ignored) {}
        return name;
    }

    public void startInit() {
        new Thread(new Runnable() {
            @Override public void run() {
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
                    // 更早版本（≤1.23）存在 下载/vd 的遗留文件收进来
                    if (dirIsPublic()) absorbLegacyDir(vdDir);
                    ensureThumbDir();   // 封面目录加 .nomedia：缩略图不进相册
                    YoutubeDL.getInstance().init(ctx);   // 核心引擎：必须成功
                    try { FFmpeg.getInstance().init(ctx); ffmpegOk = true; } catch (Throwable t) { ffmpegOk = false; }
                    try { Aria2c.getInstance().init(ctx); aria2Ok = true; } catch (Throwable t) { aria2Ok = false; }
                    initState = "ready";
                    // 预热常驻守护进程：App 一启动 Python/yt-dlp 就加载好，用户第一个任务也不用等冷启动
                    new Thread(new Runnable() {
                        @Override public void run() {
                            try { startDaemon(); } catch (Throwable ignored) {}
                        }
                    }, "vd-engine-warm").start();
                    scanAllToGallery();   // 老版本下载的文件从没扫过媒体库：启动时补扫，图库才能看到
                } catch (Throwable t) {
                    initState = "error";
                    initDetail = "引擎初始化失败: " + String.valueOf(t.getMessage());
                }
                post(new Runnable() {
                    @Override public void run() { listener.onState(null, initState, null, initDetail, null); }
                });
            }
        }, "vd-engine-init").start();
    }

    /** 提交普通链接。三级路径，快到慢依次尝试，任何一级失败静默落下一级：
     *  ① 抖音分享页快通道（一次请求直出数据，零 Python）
     *  ② 常驻 yt-dlp 守护进程（省掉每任务 1.5~3 秒的 Python 冷启动）
     *  ③ 旧路径兜底（每任务独立进程，最稳但最慢）
     *  cookies 为页面生成的 Netscape 格式文本。不做单独的 getInfo 探测——探测和正式下载会各抓一遍网页元数据 */
    public String submit(final String url, final String quality, final String cookies) {
        final String id = newId();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    postState(id, "parsing", null, null, null);
                    final long startAtMs = System.currentTimeMillis();
                    // 分享短链（v.douyin.com/b23.tv/xhslink.com）内置 lazy 版 yt-dlp 不认识，
                    // 会落到 generic 提取器被站点风控掐断——先展开成标准视频页链接。
                    // 放在抢并发槽之前：排队中的任务不占着槽做展开
                    final String url2 = expandShortLink(url);
                    slots.acquire();   // 并发槽（按设备内存自适应），其余排队
                    boolean handedToDaemon = false;   // 守护路径的释放由完成回调负责
                    try {
                        // ① 抖音快通道：分享页直出视频/图集（数据拿不到就静默落下一级）。
                        // taskMode 先登记再进通道：下载进行中 cancel() 才能走对 ssr 分支（杀 aria2c + 中断线程）；
                        // 之前 put 在调用之后，SSR 整个下载期间 mode 是空的，取消会落错分支
                        taskMode.put(id, "ssr");
                        boolean ssrOk = tryDouyinSsr(id, url2, startAtMs);
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
                                jobDir.mkdirs();
                                JSONObject req = new JSONObject();
                                req.put("id", id);
                                req.put("action", "download");
                                req.put("url", url2);
                                req.put("format", formatFilter(quality));
                                req.put("outtmpl", new File(jobDir, "%(title).180s.%(ext)s").getAbsolutePath());
                                req.put("merge", true);
                                if (aria2Ok) req.put("downloader", "libaria2c.so");
                                String ck = writeCookies(id, cookies);
                                if (ck != null) req.put("cookiefile", ck);
                                dTasks.put(id, new DaemonTask(LocalEngine.this, startAtMs, url2));
                                if (daemonSend(req)) {
                                    handedToDaemon = true;
                                    taskMode.put(id, "daemon");
                                    postState(id, "downloading", url2, null, null);
                                    return;
                                }
                                dTasks.remove(id);
                            } catch (Throwable ignored) {}
                        }
                        // ③ 旧路径兜底
                        legacyRun(url2, quality, cookies, id, startAtMs);
                    } finally {
                        if (!handedToDaemon) {
                            new File(ctx.getFilesDir(), "cookies-" + id + ".txt").delete();
                            deleteRecursive(new File(vdDir, ".job-" + id));   // 旧路径收尾：清掉本任务的独立目录（含残件）
                            taskMode.remove(id);
                            slots.release();
                        }
                    }
                } catch (InterruptedException ie) {
                    postState(id, "error", null, "任务被取消", null);
                } catch (Throwable t) {
                    processIds.remove(id);
                    String msg = String.valueOf(t.getMessage());
                    if (msg == null || msg.isEmpty()) msg = t.getClass().getSimpleName();
                    msg = friendlyError(msg);
                    postState(id, "error", null, msg, null);
                } finally {
                    threads.remove(id);   // 线程收尾即出表（threads 表只服务 cancel 的 interrupt）
                }
            }
        });
        threads.put(id, t);
        t.start();
        return id;
    }

    /** 旧路径：每任务独立 yt-dlp 进程（结构上最稳，作为守护进程的兜底保留）。
     *  产物同样落在 .job-<id> 独立目录：并发批量不会互相污染，收尾搬到根目录 */
    private void legacyRun(final String url2, final String quality, final String cookies, final String id, final long startAtMs) throws Exception {
        postState(id, "downloading", url2, null, null);
        final File jobDir = new File(vdDir, ".job-" + id);
        jobDir.mkdirs();
        YoutubeDLRequest req = new YoutubeDLRequest(url2);
        applyCommon(req, quality, cookies, id);
        req.addOption("-o", new File(jobDir, "%(title).180s.%(ext)s").getAbsolutePath());
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
                post(new Runnable() {
                    @Override public void run() { listener.onProgress(id, p, l); }
                });
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
            // 搬到根目录（标题清洗命名 + 防撞后缀），再视频归一成 H.264 mp4
            ArrayList<String> fin = new ArrayList<>();
            for (String n : produced) {
                String moved = moveToRoot(jobDir, n);
                if (moved == null) continue;
                fin.add(isVideoName(moved) ? normalizeVideo(moved, id, null) : moved);
            }
            if (fin.isEmpty()) {
                postState(id, "error", null, "下载结束但产出文件搬移失败，重试一次", null);
                return;
            }
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
                    if (!n.matches("(?i).+\\.(" + MEDIA_EXT + ")$")) continue;
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
    private String moveToRoot(File jobDir, String n) {
        try {
            File src = new File(jobDir, n);
            if (!src.isFile()) {
                File alt = new File(vdDir, n);   // 兼容极老脚本直落根目录的情况
                if (alt.isFile()) src = alt;
            }
            if (!src.isFile() || src.length() == 0) return null;
            String ext = extOf(n);
            String stem = n.substring(0, n.length() - ext.length());
            String target = sanitizeName(stem) + ext;
            // 已在根目录且名字没变：别自搬自改
            if (vdDir.equals(src.getParentFile()) && target.equals(n)) return n;
            String want = uniqueName(target);
            if (src.renameTo(new File(vdDir, want))) return want;
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 守护进程刚（重）启动：清掉上次会话留下的孤儿 .job-* 目录（没有对应在跑任务的） */
    private void sweepJobDirs() {
        try {
            if (vdDir == null) return;
            java.util.HashSet<String> live = new java.util.HashSet<>(dTasks.keySet());
            File[] all = vdDir.listFiles();
            if (all == null) return;
            for (File f : all) {
                String n = f.getName();
                if (!f.isDirectory() || !n.startsWith(".job-")) continue;
                if (!live.contains(n.substring(5))) deleteRecursive(f);
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
        String safe = "[vcodec!^=hevc][vcodec!^=h265][vcodec!^=av01]";
        // 兜底必须限定真视频流：无 cookie 被 playurl 风控(412)拿不到视频格式时，
        // 不带过滤的 /b 会选中页面图集（一打 .jpg 当任务"成功"），必须逼它报错走重试/提示
        String vid = "[vcodec!^=none]";
        if (quality != null && quality.matches("\\d+")) {
            return "bv*[height<=" + quality + "]" + safe + "+ba/b[height<=" + quality + "]" + safe
                + "/bv*[height<=" + quality + "]+ba/b[height<=" + quality + "]/bv*+ba/b" + vid;
        }
        return "bv*" + safe + "+ba/b" + safe + "/bv*+ba/b" + vid;
    }

    /** 页面 cookies 写成任务专属文件（守护/旧路径共用），返回路径；内容为空返回 null */
    private String writeCookies(String id, String cookies) {
        try {
            if (cookies == null || cookies.trim().length() <= 20) return null;
            File ck = new File(ctx.getFilesDir(), "cookies-" + id + ".txt");
            FileOutputStream fo = new FileOutputStream(ck);
            fo.write(cookies.getBytes("UTF-8"));
            fo.close();
            return ck.getAbsolutePath();
        } catch (Exception e) { return null; }
    }

    // ================= 抖音分享页快通道（最快路径：一次 HTTP 拿到视频/图集数据，零 Python） =================

    /** 抖音 video/note 链接走移动分享页直取数据；成功返回 true（已发 done）。任何拿不到都返回 false 落常规路径 */
    private boolean tryDouyinSsr(String id, String url2, long startAtMs) {
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
                postState(id, "done", rawTitle, null, new JSONArray(saved).toString());
                scanToGallery(saved);
                markOwned(saved);
                ensureThumbs(saved);
                return true;
            }
            // 单视频：aria2c 16 连接直下，失败退单连接；下完归一成 H.264 mp4（部分手机解不了 HEVC）
            String want = uniqueName(fileBase + ".mp4");
            File out = new File(vdDir, want);
            boolean ok = aria2Ok && aria2Download(id, videoUrl, "https://www.iesdouyin.com/", out);
            // 已取消就不退单连接（那是"取消后偷偷重下"）；aria2c 真失败（网络断/链接过期）才退
            if (!ok && !Thread.currentThread().isInterrupted()) ok = downloadProgress(id, videoUrl, out);
            if (!ok || out.length() == 0) {
                out.delete();   // 半截文件绝不留：会污染后续任务的产物扫描，也会以坏文件身份进"我的保存"
                return false;
            }
            // 片尾推广先裁掉（v1.39），再归一成 H.264 mp4（部分手机解不了 HEVC）
            String finalName = normalizeVideo(trimDouyinTail(want, id, apiDurMs), id, dispTitle);
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
        try {
            File tdir = new File(vdDir, ".thumbs");
            tdir.mkdirs();
            File out = new File(tdir, videoName + ".jpg");
            tmp = new File(tdir, ".cover-" + newId() + ".tmp");
            HttpURLConnection c = (HttpURLConnection) new URL(coverUrl).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA_ENGINE);
            if (c.getResponseCode() != 200) { c.disconnect(); return false; }
            InputStream ci = c.getInputStream();
            FileOutputStream fo = new FileOutputStream(tmp);
            byte[] buf = new byte[16384];
            int n;
            while ((n = ci.read(buf)) > 0) fo.write(buf, 0, n);
            fo.close();
            ci.close();
            if (tmp.length() < 2048) return false;   // 太小不是真图
            if (tmp.renameTo(out)) return true;
            return false;
        } catch (Throwable t) {
            return false;
        } finally {
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
            int n;
            while (bos.size() < 2097152 && (n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            c.disconnect();
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
            ProcessBuilder pb = new ProcessBuilder(args);
            if (ldLibPath != null) pb.environment().put("LD_LIBRARY_PATH", ldLibPath);
            p = pb.start();
            btProcs.put(id, p);
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) return false;   // 取消：finally 会杀掉进程
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\((\\d{1,3})%\\)").matcher(line);
                if (m.find()) {
                    final int pct = Math.min(99, Integer.parseInt(m.group(1)));
                    final String l = line;
                    final String tid = id;
                    post(new Runnable() {
                        @Override public void run() { listener.onProgress(tid, pct, l); }
                    });
                }
            }
            int code = p.waitFor();
            return code == 0 && out.isFile() && out.length() > 0;
        } catch (Throwable t) {
            return false;
        } finally {
            btProcs.remove(id);
            if (p != null) { try { p.destroyForcibly(); } catch (Exception ignored) {} }
        }
    }

    /** 单连接下载（带进度，aria2c 不可用/失败时的兜底） */
    private boolean downloadProgress(final String id, String url, File out) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA_ENGINE);
            if (url.contains("douyin")) c.setRequestProperty("Referer", "https://www.iesdouyin.com/");
            long total = c.getContentLength();
            InputStream in = c.getInputStream();
            OutputStream fo = new FileOutputStream(out);
            byte[] buf = new byte[65536];
            long got = 0;
            int n;
            long lastP = -1;
            while ((n = in.read(buf)) > 0) {
                fo.write(buf, 0, n);
                got += n;
                if (Thread.currentThread().isInterrupted()) return false;   // 取消：半截文件由调用方删除
                if (total > 0) {
                    long pct = got * 100 / total;
                    if (pct != lastP) {
                        lastP = pct;
                        final int pp = (int) Math.min(99, pct);
                        final String l = pct + "% of " + (total / 1048576) + "MiB";
                        post(new Runnable() {
                            @Override public void run() { listener.onProgress(id, pp, l); }
                        });
                    }
                }
            }
            fo.close();
            in.close();
            c.disconnect();
            return out.isFile() && out.length() > 0;
        } catch (Throwable t) {
            return false;
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
        File f = new File(ctx.getFilesDir(), "vd_daemon.py");
        InputStream in = ctx.getAssets().open("vd_daemon.py");
        FileOutputStream fo = new FileOutputStream(f);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
        fo.close();
        in.close();
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
                dReady = false;
                ProcessBuilder pb = new ProcessBuilder(
                    new File(nativeDirPath, "libpython.so").getAbsolutePath(),
                    new File(ctx.getFilesDir(), "vd_daemon.py").getAbsolutePath(), ytdlpZipPath, ffmpegBinPath);
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
        synchronized (dStartLock) {
            dReady = false;
            dProc = null;
            try { if (dOut != null) dOut.close(); } catch (Exception ignored) {}
            dOut = null;
            dStartLock.notifyAll();
        }
        for (Map.Entry<String, DaemonTask> e : dTasks.entrySet()) {
            DaemonTask t = dTasks.remove(e.getKey());
            if (t == null) continue;
            taskMode.remove(e.getKey());
            new File(t.eng.ctx.getFilesDir(), "cookies-" + e.getKey() + ".txt").delete();
            dPostState(e.getKey(), "error", null, "下载引擎重启了一下，点「重试」继续", null);
            t.eng.slots.release();
        }
    }

    private void handleDaemonLine(String line) {
        try {
            JSONObject o = new JSONObject(line);
            String type = o.optString("type");
            final String id = o.optString("id");
            if ("ready".equals(type)) {
                synchronized (dStartLock) { dReady = true; dStartLock.notifyAll(); }
                sweepJobDirs();   // 守护进程刚（重）启动：上次会话留下的 .job-* 目录全是孤儿，清掉
                return;
            }
            if ("fatal".equals(type)) {
                synchronized (dStartLock) { dReady = false; dStartLock.notifyAll(); }
                return;
            }
            if ("progress".equals(type)) {
                DaemonTask t = dTasks.get(id);
                if (t != null) {
                    String title = o.optString("title", "");
                    if (title.length() > 0) t.lastTitle = title;
                    String fn = o.optString("filename", "");
                    if (fn.length() > 0) t.destName = fn;
                }
                final int pct = o.optInt("progress", 0);
                final String l = o.optString("line", "");
                dPostProgress(id, pct, l);
                return;
            }
            if ("done".equals(type)) { finishDaemonTask(id, null, o.optJSONArray("files")); return; }
            if ("error".equals(type)) { finishDaemonTask(id, o.optString("line", "下载失败"), null); return; }
        } catch (Exception ignored) {}
    }

    /** 守护任务收尾：产物在 .job-<id> 独立目录里各认各的，搬到根目录（清洗命名+防撞）再上报。
     *  上报清单缺失才扫 job 目录兜底（绝不扫全目录时间窗——并发任务会互相污染）。
     *  释放并发槽/清 cookie/job 目录都按任务归属的引擎实例来。
     *  归一可能要转码（几十秒级），放独立线程跑——绝不能堵住守护进程的输出泵 */
    private void finishDaemonTask(final String id, final String isErr, final JSONArray files) {
        final DaemonTask t = dTasks.remove(id);
        if (t == null) return;
        taskMode.remove(id);
        new File(t.eng.ctx.getFilesDir(), "cookies-" + id + ".txt").delete();
        if (isErr != null) {
            dPostState(id, "error", null, friendlyError(isErr), null);
            deleteRecursive(new File(t.eng.vdDir, ".job-" + id));
            t.eng.slots.release();
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                final File jobDir = new File(t.eng.vdDir, ".job-" + id);
                try {
                    final LocalEngine eng = t.eng;
                    ArrayList<String> produced = new ArrayList<>();
                    if (files != null) {
                        for (int i = 0; i < files.length(); i++) {
                            String n = files.optString(i, "");
                            if (n.length() == 0 || n.startsWith(".") || n.contains("/")) continue;
                            if (CAMERA_NAME.matcher(n).find()) continue;
                            // 新脚本落在 .job 独立目录；极老版本直落根目录也兼容
                            File f = new File(jobDir, n).isFile() ? new File(jobDir, n) : new File(eng.vdDir, n);
                            if (!f.isFile()) continue;   // 上报了但没落盘（被清理）的跳过
                            produced.add(f.getName());
                        }
                    }
                    if (produced.isEmpty() && t.destName != null) {
                        File f = new File(jobDir, t.destName).isFile() ? new File(jobDir, t.destName) : new File(eng.vdDir, t.destName);
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
                        ArrayList<String> fin = new ArrayList<>();
                        for (String n : produced) {
                            String moved = eng.moveToRoot(jobDir, n);
                            if (moved == null) continue;
                            // 抖音片尾推广自动裁剪（v1.39）：API 时长拿不到（守护路径），走静态尾巴检测
                            if (isVideoName(moved) && t.url != null && t.url.contains("douyin")) {
                                moved = eng.trimDouyinTail(moved, id, 0);
                            }
                            fin.add(isVideoName(moved) ? eng.normalizeVideo(moved, id, t.lastTitle) : moved);
                        }
                        if (fin.isEmpty()) {
                            dPostState(id, "error", null, "下载结束但产出文件搬移失败，重试一次", null);
                        } else {
                        java.util.Collections.sort(fin);
                        eng.scanToGallery(fin);
                        eng.markOwned(fin);
                        eng.ensureThumbs(fin);
                        // 标题发原始描述（v1.39）：页面端 cleanTitle 取正文、tagLine 取 #话题做第二行
                        dPostState(id, "done", t.lastTitle != null ? t.lastTitle : titleFromName(fin.get(0)), null, new JSONArray(fin).toString());
                        }
                        }
                    }
                } catch (Throwable e) {
                    dPostState(id, "error", null, String.valueOf(e.getMessage()), null);
                } finally {
                    deleteRecursive(jobDir);   // 收尾清理（分轨残件 / .aria2 控制文件 / 没搬动的文件）
                    t.eng.slots.release();
                }
            }
        }, "vd-finish-" + id).start();
    }

    /** 提交磁力链接：手机本地 aria2c BT 模式 */
    public String magnetSubmit(final String magnet) {
        final String id = newId();
        Thread t = new Thread(new Runnable() {
        @Override public void run() {
            final File staging = new File(vdDir, ".staging-" + id);
            try { slots.acquire(); } catch (InterruptedException ie) { postState(id, "error", null, "任务被取消", null); return; }
            try {
                staging.mkdirs();
                    postState(id, "parsing", "磁力：正在解析元数据…", null, null);
                    if (nativeDirPath == null) computeRuntimePaths();
                    // ① 元数据快速通道（v1.39）：公共 .torrent 缓存直接拿种子文件——国内网络
                    //    DHT/tracker 的 UDP 常被限速，元数据拿不到就什么都下不了；拿到 .torrent
                    //    后 aria2c 只需要连节点传数据，起步快得多。拿不到再回纯磁力（DHT 慢慢磨）
                    String torrentFile = null;
                    java.util.regex.Matcher mh = java.util.regex.Pattern.compile("urn:btih:([a-fA-F0-9]{40})").matcher(magnet);
                    if (mh.find()) {
                        try {
                            HttpURLConnection c = (HttpURLConnection) new URL("https://itorrents.org/torrent/" + mh.group(1).toUpperCase(java.util.Locale.ROOT) + ".torrent").openConnection();
                            c.setConnectTimeout(8000);
                            c.setReadTimeout(15000);
                            c.setInstanceFollowRedirects(true);
                            c.setRequestProperty("User-Agent", UA_ENGINE);
                            if (c.getResponseCode() == 200) {
                                java.io.ByteArrayOutputStream mb = new java.io.ByteArrayOutputStream();
                                InputStream mi = c.getInputStream();
                                byte[] mbuf = new byte[16384];
                                int mn;
                                while (mb.size() < 20971520 && (mn = mi.read(mbuf)) > 0) mb.write(mbuf, 0, mn);
                                mi.close();
                                byte[] mbb = mb.toByteArray();
                                if (mbb.length > 200 && mbb[0] == 'd') {   // bencode 字典开头才是真种子
                                    File tf = new File(staging, "meta.torrent");
                                    FileOutputStream mo = new FileOutputStream(tf);
                                    mo.write(mbb);
                                    mo.close();
                                    torrentFile = tf.getAbsolutePath();
                                    postState(id, "parsing", "磁力：已取到种子信息，正在连接节点…", null, null);
                                }
                            } else c.disconnect();
                        } catch (Throwable ignored) {}
                    }
                    List<String> args = new ArrayList<>();
                    args.add(aria2cBin());
                    args.add("--dir=" + staging.getAbsolutePath());
                    args.add("--seed-time=0");
                    args.add("--file-allocation=none");
                    args.add("--summary-interval=2");
                    args.add("--console-log-level=notice");
                    args.add("--enable-dht=true");
                    args.add("--enable-dht6=false");
                    args.add("--dht-entry-point=router.bittorrent.com:6881");
                    args.add("--bt-enable-lpd=true");
                    args.add("--enable-peer-exchange=true");
                    args.add("--listen-port=51413-51423");
                    args.add("--dht-listen-port=51413-51423");
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
                    final long startAt = System.currentTimeMillis();
                    final long[] lastProgressAt = {0};
                    final StringBuilder titleBuf = new StringBuilder();
                    // 读输出行：进度 + 无源看门狗（冷门种子元数据永久挂起的根治）
                    final String[] tail = {""};
                    Thread reader = new Thread(new Runnable() {
                        @Override public void run() {
                            try {
                                BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                                String line;
                                while ((line = br.readLine()) != null) {
                                    String t = tail[0] + "\n" + line;
                                    if (t.length() > 600) t = t.substring(t.length() - 600);   // 保留尾部用于报错
                                    tail[0] = t;
                                    final String l = line;
                                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\((\\d{1,3})%\\)").matcher(line);
                                    if (m.find()) {
                                        lastProgressAt[0] = System.currentTimeMillis();
                                    if (titleBuf.length() == 0) {
                                        // 哈希片段做任务名：长度必须按替换后的串算（之前按原磁力串长度算，
                                        // 参数顺序不同/短哈希时 substring 越界会炸掉读输出线程→进度全丢→被看门狗误杀）
                                        String h = magnet.replaceFirst("magnet:\\?xt=urn:btih:", "");
                                        titleBuf.append("磁力：" + h.substring(0, Math.min(12, h.length())));
                                    }
                                        final int pct = Math.min(99, Integer.parseInt(m.group(1)));
                                        post(new Runnable() {
                                            @Override public void run() { listener.onProgress(id, pct, l); }
                                        });
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                    });
                    reader.start();
                    // 看门狗：10 分钟没有新进度 → 判定无源，杀掉并报错。
                    // 基准是"最近一次收到进度"——元数据解析不出（一直 0）和下载中途断种（进度停住）
                    // 都会触发；之前只盯"从未有过进度"，断种的任务会永久挂起占死并发槽
                    while (true) {
                        long ref = lastProgressAt[0] == 0 ? startAt : lastProgressAt[0];
                        if (System.currentTimeMillis() - ref > 600000) {
                            p.destroyForcibly();
                            postState(id, "error", null, lastProgressAt[0] == 0
                                ? (torrentFile != null
                                    ? "已取到种子信息，但 10 分钟没连上可用节点：该资源可能已无做种，建议换一个链接或稍后再试"
                                    : "磁力元数据解析超时（10 分钟没有可用源）：该资源可能已无做种或太冷门，建议换一个链接或稍后再试")
                                : "磁力下载超时（10 分钟没有收到新数据）：做种已断或太冷门，建议换一个链接或稍后再试", null);
                            deleteRecursive(staging);
                            btProcs.remove(id);
                            return;
                        }
                        try { p.exitValue(); break; } catch (IllegalThreadStateException e) { /* 还在跑 */ }
                        Thread.sleep(2000);
                    }
                    int code = p.exitValue();
                    btProcs.remove(id);
                    if (code != 0) {
                        deleteRecursive(staging);
                        String reason = tail[0].trim().replace('\n', ' ');
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
                    for (File f : files) {
                        if (f.length() == 0) continue;
                        if (f.getName().equals("meta.torrent")) continue;   // 元数据快速通道留下的种子文件，不是产物
                        String ext = extOf(f.getName());
                        String target = uniqueName(sanitizeName(f.getName().substring(0, Math.max(0, f.getName().length() - ext.length()))) + ext);
                        f.renameTo(new File(vdDir, target));
                        saved.add(target);
                    }
                    deleteRecursive(staging);
                    String filesJson = new JSONArray(saved).toString();
                    scanToGallery(saved);
                    markOwned(saved);
                    ensureThumbs(saved);
                    String title = saved.size() == 1 ? saved.get(0).replaceAll("\\.[^.]+$", "") : "磁力下载（" + saved.size() + " 个文件）";
                    postState(id, "done", title, null, filesJson);
                } catch (Throwable t) {
                    btProcs.remove(id);
                    deleteRecursive(staging);
                    postState(id, "error", null, String.valueOf(t.getMessage()), null);
                } finally {
                    slots.release();
                    threads.remove(id);
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
            try { slots.acquire(); } catch (InterruptedException ie) { postState(id, "error", null, "任务被取消", null); return; }
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
                    List<String> saved = downloadMediaUrls(id, urls, fileBase);
                    if (saved.isEmpty()) { postState(id, "error", null, "图片/视频都没下载下来：网络被掐或链接过期，重试一次", null); return; }
                    // 视频归一成 H.264 mp4（og:video/网页采集抓到的可能是 HEVC，部分手机解不了）——与守护进程路径行为一致
                    List<String> fin = new ArrayList<>();
                    for (String n : saved) fin.add(isVideoName(n) ? normalizeVideo(n, id, rawTitle) : n);
                    String title = fin.size() == urls.size() ? rawTitle : rawTitle + "（" + fin.size() + "/" + urls.size() + "）";
                    postState(id, "done", title, null, new JSONArray(fin).toString());
                    scanToGallery(fin);
                    markOwned(fin);
                    ensureThumbs(fin);
                } catch (InterruptedException ie) {
                    postState(id, "error", null, "任务被取消", null);
                } catch (Throwable t2) {
                    postState(id, "error", null, String.valueOf(t2.getMessage()), null);
                } finally {
                    slots.release();
                    threads.remove(id);
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
            String stem = gbase + "-" + (i + 1);
            String ext = extFromUrl(urls.get(i));
            if (new File(vdDir, stem + ext).exists() || new File(vdDir, stem + ".jpg").exists()) return true;
        }
        return false;
    }

    /** 并行下载一组直链（图集）：最多 6 线程同时拉，图片顺手转 jpg。
     *  命名"基名-序号"成组（基名整组去重）——「我的保存」按这个模式聚成一条图集记录 */
    private List<String> downloadMediaUrls(final String id, List<String> urls, String base) throws InterruptedException {
        String gbase = base;
        if (urls.size() > 1) {
            int n = 1;
            while (galleryTaken(gbase, urls)) gbase = base + "(" + (n++) + ")";
        }
        final String[] targets = new String[urls.size()];
        for (int i = 0; i < urls.size(); i++)
            targets[i] = gbase + (urls.size() > 1 ? "-" + (i + 1) : "") + extFromUrl(urls.get(i));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
            Math.min(6, Math.max(2, urls.size())));
        final java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger(0);
        for (int i = 0; i < urls.size(); i++) {
            final int idx2 = i;
            pool.submit(new Runnable() {
                @Override public void run() {
                    try {
                        File f = new File(vdDir, targets[idx2]);
                        downloadTo(urls.get(idx2), f);
                        if (f.isFile() && f.length() > 0) imageToJpg(targets[idx2], id);
                    } catch (Throwable ignored) {}
                    final int d = done.incrementAndGet();
                    post(new Runnable() {
                        @Override public void run() { listener.onProgress(id, Math.min(99, d * 100 / urls.size()), d + "/" + urls.size()); }
                    });
                }
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(15, java.util.concurrent.TimeUnit.MINUTES);
        } catch (InterruptedException ie) {
            pool.shutdownNow();   // 任务被取消：停掉还在跑的图片下载线程
            // 本组目标文件名已经过整组查重，全属于本任务——半截文件一并清掉，
            // 不能留在目录里以坏图身份混进「我的保存」（下载/vd 模式是全量列文件的）
            for (String tg : targets) {
                new File(vdDir, tg).delete();
                new File(vdDir, tg.replaceAll("\\.[^.]+$", "") + ".jpg").delete();
            }
            throw ie;
        }
        // 按原始顺序整理成品名（转 jpg 后名字可能变了）
        List<String> saved = new ArrayList<>();
        for (int i = 0; i < urls.size(); i++) {
            String stem = targets[i].replaceAll("\\.[^.]+$", "");
            String cand = new File(vdDir, stem + ".jpg").isFile() ? stem + ".jpg"
                : (new File(vdDir, targets[i]).isFile() ? targets[i] : null);
            if (cand != null) saved.add(cand);
        }
        return saved;
    }

    public void cancel(String id) {
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
            if (bp != null) { try { bp.destroyForcibly(); } catch (Exception ignored) {} }
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
            try { bp.destroyForcibly(); } catch (Exception ignored) {}
            deleteRecursive(new File(vdDir, ".staging-" + id));
        }
        Thread t = threads.remove(id);
        if (t != null) t.interrupt();
    }

    private void applyCommon(YoutubeDLRequest req, String quality, String cookies, String id) throws Exception {
        // 编码优先选 WebView 能直接播的（h264/vp9）：yt-dlp 默认排序 h265 优先于 h264，
        // 而大量手机的 WebView 解不了 HEVC（应用内表现为有声无画）——先滤掉 hevc/h265/av01 挑，
        // 挑不到再回退任意编码（此时页面有「系统播放器」兜底）
        req.addOption("-f", formatFilter(quality));
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

    // 短链展开结果缓存（值 [展开结果, 时间戳]，10 分钟内同链接零等待）
    private final Map<String, String[]> expandCache = new ConcurrentHashMap<>();

    /** 预取：用户还在看链接没点解析时，后台先把短链展开好，点「解析」时直接进提取 */
    public void prefetch(final String url) {
        if (url == null || url.trim().isEmpty()) return;
        new Thread(new Runnable() {
            @Override public void run() { try { expandShortLink(url.trim()); } catch (Throwable ignored) {} }
        }, "vd-prefetch").start();
    }

    private String expandShortLink(String url) {
        String u = url == null ? "" : url.trim();
        String low = u.toLowerCase();
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
        try {
            String low = u.toLowerCase();
            boolean dy = low.contains("://v.douyin.com") || low.contains(".iesdouyin.com") || low.contains("://iesdouyin.com");
            boolean b23 = low.contains("://b23.tv");
            boolean xhs = low.contains("://xhslink.com") || low.contains("://xhslink.cn");
            // 快手/微博 t.cn/西瓜短链：跟完 302 落到哪页就交哪页（yt-dlp 或采集兜底接着处理）
            boolean gen = low.contains("://v.kuaishou.com") || low.contains("://t.cn/") || low.contains("://v.ixigua.com");
            if (!dy && !b23 && !xhs && !gen) return u;

            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
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
                int n;
                while (bos.size() < 262144 && (n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
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
        } catch (Throwable ignored) {}
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
                    if (!f.getName().matches("(?i).+\\.(" + MEDIA_EXT + ")$")) continue;
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
        return base + "-" + System.currentTimeMillis() + ext;
    }

    private void downloadTo(String url, File out) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        // 各站 CDN 对 Referer 敏感度不同：带上归属站的，别的站不发（乱发反而可能被掐）
        if (url.contains("douyin")) c.setRequestProperty("Referer", "https://www.douyin.com/");
        else if (url.contains("xiaohongshu") || url.contains("xhscdn")) c.setRequestProperty("Referer", "https://www.xiaohongshu.com/");
        try {
            long expect = c.getContentLength();   // 服务器给了长度就能校验完整性
            InputStream in = c.getInputStream();
            OutputStream fo = new FileOutputStream(out);
            byte[] buf = new byte[65536];
            long got = 0;
            int n;
            while ((n = in.read(buf)) > 0) { fo.write(buf, 0, n); got += n; }
            try { fo.close(); } catch (Throwable ignored) {}
            in.close();
            // 长度对不上/空body=半截文件：删掉报错，绝不让坏图坏视频混进「我的保存」
            if (expect > 0 && got < expect) throw new java.io.IOException("short read: " + got + "/" + expect);
            if (got == 0) throw new java.io.IOException("empty body");
        } catch (Exception e) {
            out.delete();
            throw e;
        } finally {
            c.disconnect();
        }
    }

    /** 下载目录里的媒体文件列表（[{name,sizeMB,mtime}]，页面直接渲染）。
     *  自定义目录/相册目录可能与别人的文件同住一个屋檐——只列本 App 下载的文件；
     *  下载/vd 和应用私有目录是本 App 独占的，全列 */
    public String listFiles() {
        try {
            if (vdDir == null) return "[]";
            String mode = dirMode();
            boolean filterOwned = "custom".equals(mode) || "dcim".equals(mode);
            java.util.HashSet<String> own = filterOwned ? ownedSet() : null;
            java.util.HashSet<String> hidden = hiddenNames();
            File[] all = vdDir.listFiles();
            JSONArray arr = new JSONArray();
            ArrayList<String> needThumb = null;
            if (all != null) {
                ArrayList<File> files = new ArrayList<>();
                for (File f : all) {
                    String n = f.getName();
                    if (!f.isFile() || n.startsWith(".")) continue;
                    if (!n.matches("(?i).+\\.(" + MEDIA_EXT + ")$")) continue;
                    if (own != null && !own.contains(n)) continue;
                    if (hidden.contains(n)) continue;   // 仅删记录：不再列出
                    files.add(f);
                }
                java.util.Collections.sort(files, new java.util.Comparator<File>() {
                    public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
                });
                for (File f : files) {
                    JSONObject o = new JSONObject();
                    o.put("name", f.getName());
                    o.put("sizeMB", Math.round(f.length() / 104857.6) / 10.0);
                    o.put("mtime", f.lastModified());
                    arr.put(o);
                    if (isVideoName(f.getName()) && !new File(new File(vdDir, ".thumbs"), f.getName() + ".jpg").isFile()) {
                        if (needThumb == null) needThumb = new ArrayList<>();
                        needThumb.add(f.getName());
                    }
                }
            }
            if (needThumb != null) ensureThumbs(needThumb);   // 存量视频的封面自愈
            return arr.toString();
        } catch (Exception e) { return "[]"; }
    }

    /** 删除文件。namesJson: ["a.mp4","b.jpg"] */
    public String deleteFiles(String namesJson) {
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
        String low = u == null ? "" : u.toLowerCase();
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
        if (path.endsWith(".webp") || low.contains("webp")) return ".webp";
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
    private String uniqueName(String want) {
        File f = new File(vdDir, want);
        if (!f.exists()) return want;
        String ext = extOf(want);
        String base = want.substring(0, want.length() - ext.length());
        for (int n = 1; n < 1000; n++) {
            File g = new File(vdDir, base + "(" + n + ")" + ext);
            if (!g.exists()) return g.getName();
        }
        return base + "-" + System.currentTimeMillis() + ext;
    }
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
        post(new Runnable() {
            @Override public void run() { listener.onState(id, state, title, error, filesJson); }
        });
    }

    /** 守护回调专用：推给当前引擎实例（Activity 重建后旧实例的 WebView 已死，推过去等于丢事件） */
    private static void dPostState(String id, String state, String title, String error, String filesJson) {
        final LocalEngine e = sActive;
        if (e == null) return;
        e.main.post(new Runnable() {
            @Override public void run() { e.listener.onState(id, state, title, error, filesJson); }
        });
    }

    private static void dPostProgress(final String id, final int pct, final String l) {
        final LocalEngine e = sActive;
        if (e == null) return;
        e.main.post(new Runnable() {
            @Override public void run() { e.listener.onProgress(id, pct, l); }
        });
    }

    private void post(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run(); else main.post(r);
    }
}
