package com.palworld.vd;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * v2.04 ffmpeg 工具运行器：给视频工具箱等复用（二进制来自 APK 的 libffmpeg.so，
 * 路径与 youtubedl-android 解压目录的依赖库环境和 LocalEngine 完全一致）。
 * -progress pipe:1 + -nostats 拿处理进度（out_time_us 行），stdout 解析、stderr 排空防堵。
 */
public final class FfmpegTool {

    public interface Progress {
        /** pct: 0..99 */
        void onProgress(int pct);
    }

    private FfmpegTool() {}

    /** ffmpeg 可执行文件（安装时已由系统解压到 nativeLibraryDir） */
    public static String bin(Context ctx) {
        return new File(ctx.getApplicationInfo().nativeLibraryDir, "libffmpeg.so").getAbsolutePath();
    }

    /** 与 LocalEngine.computeRuntimePaths 同源的依赖库环境（ffmpeg 的 so 依赖在 packages/ffmpeg/usr/lib） */
    public static String ldLibPath(Context ctx) {
        File pkgs = new File(new File(ctx.getNoBackupFilesDir(), "youtubedl-android"), "packages");
        return new File(pkgs, "ffmpeg/usr/lib").getAbsolutePath();
    }

    /**
     * 跑一次 ffmpeg。返回退出码（0 成功；-1 = 启动失败/被取消/超时）。
     * durUs：媒体总时长（微秒），用于把 out_time_us 换算成百分比；≤0 时只报不确定进度。
     */
    public static int run(Context ctx, List<String> args, long durUs, long timeoutMs,
                          AtomicBoolean cancel, Progress cb) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(bin(ctx), "-nostats", "-loglevel", "error",
                "-progress", "pipe:1", "-y");
            pb.command().addAll(args);
            Map<String, String> env = pb.environment();
            env.put("LD_LIBRARY_PATH", ldLibPath(ctx));
            p = pb.start();
            final Process proc = p;
            // stderr 排空（防管道写满阻塞）
            Thread err = new Thread(new Runnable() {
                @Override public void run() {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
                        while (r.readLine() != null) { /* 丢弃 */ }
                    } catch (Exception ignored) {}
                }
            }, "vd-fftool-err");
            err.setDaemon(true);
            err.start();
            BufferedReader out = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String line;
            while ((line = out.readLine()) != null) {
                if (cancel.get()) { FfmpegTool.destroyProcess(proc); return -1; }
                if (cb != null && durUs > 0 && line.startsWith("out_time_us=")) {
                    try {
                        long t = Long.parseLong(line.substring(12).trim());
                        int pct = (int) Math.max(0, Math.min(99, t * 100 / durUs));
                        cb.onProgress(pct);
                    } catch (Exception ignored) {}
                }
            }
            boolean done = false;
            long deadline = System.currentTimeMillis() + Math.max(1000, timeoutMs);
            while (!done && System.currentTimeMillis() < deadline) {
                if (cancel.get()) { FfmpegTool.destroyProcess(proc); return -1; }
                done = FfmpegTool.waitForProcess(proc, 200);
            }
            if (!done) { FfmpegTool.destroyProcess(proc); return -1; }
            return proc.exitValue();
        } catch (Exception e) {
            if (p != null) { try { FfmpegTool.destroyProcess(p); } catch (Exception ignored) {} }
            return -1;
        }
    }

    static void destroyProcess(Process process) {
        if (android.os.Build.VERSION.SDK_INT >= 26) process.destroyForcibly();
        else process.destroy();
    }

    static boolean waitForProcess(Process process, long timeoutMs) throws InterruptedException {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            return process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            try { process.exitValue(); return true; }
            catch (IllegalThreadStateException ignored) {}
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            Thread.sleep(Math.min(200, Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining))));
        }
    }

    /** 通用媒体保存（API 29+ MediaStore 直写 Download/vd/<sub>，旧机直写文件），返回是否成功 */
    public static boolean saveToDownloads(Context ctx, java.io.File src, String displayName, String mime, String sub) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, displayName);
                cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime);
                cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/vd/" + sub);
                cv.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
                android.net.Uri uri = ctx.getContentResolver().insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return false;
                try {
                    try (java.io.InputStream fi = new java.io.FileInputStream(src);
                         java.io.OutputStream os = ctx.getContentResolver().openOutputStream(uri)) {
                        if (os == null) throw new java.io.IOException("无法写入保存目录");
                        byte[] b = new byte[65536];
                        int n;
                        while ((n = fi.read(b)) > 0) os.write(b, 0, n);
                    }
                    android.content.ContentValues ready = new android.content.ContentValues();
                    ready.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
                    ctx.getContentResolver().update(uri, ready, null, null);
                } catch (Exception e) {
                    try { ctx.getContentResolver().delete(uri, null, null); } catch (Exception ignored) {}
                    throw e;
                }
                return true;
            } else {
                File dir = new File(android.os.Environment
                    .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "vd/" + sub);
                dir.mkdirs();
                File dst = new File(dir, displayName);
                try (java.io.InputStream fi = new java.io.FileInputStream(src);
                     java.io.OutputStream fo = new java.io.FileOutputStream(dst)) {
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = fi.read(b)) > 0) fo.write(b, 0, n);
                }
                android.media.MediaScannerConnection.scanFile(ctx, new String[]{ dst.getAbsolutePath() }, null, null);
                return true;
            }
        } catch (Exception e) {
            android.util.Log.w("FfmpegTool", "save failed", e);
            return false;
        }
    }
}
