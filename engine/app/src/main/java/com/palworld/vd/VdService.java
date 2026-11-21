package com.palworld.vd;

/**
 * v1.99 下载保活前台服务：有活跃任务时把进程钉成前台优先级，用户切去别的 App 后
 * 下载/转码不再被系统当缓存进程回收（此前没有任何 service，退后台全靠进程不被杀）。
 * 空壳设计：不做任何业务，生命周期完全由 LocalEngine 的任务计数（svcAcquire/svcRelease）驱动。
 * 通知权限（Android 13+ POST_NOTIFICATIONS）未授时通知不显示，但前台保活依然生效。
 */
public class VdService extends android.app.Service {

    static final String ACTION_STOP = "com.palworld.vd.STOP_DOWNLOAD_SERVICE";
    private static final String CH = "vd_download";
    private static final int NOTIF_ID = 4210;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                android.app.NotificationChannel c = new android.app.NotificationChannel(
                    CH, "下载任务", android.app.NotificationManager.IMPORTANCE_LOW);
                c.setShowBadge(false);
                nm.createNotificationChannel(c);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public int onStartCommand(android.content.Intent intent, int flags, int startId) {
        try {
            android.app.Notification.Builder b = android.os.Build.VERSION.SDK_INT >= 26
                ? new android.app.Notification.Builder(this, CH)
                : new android.app.Notification.Builder(this);
            b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("正在下载")
                .setContentText("视频下载任务进行中，可切到其他应用")
                .setOngoing(true)
                .setOnlyAlertOnce(true);
            startForeground(NOTIF_ID, b.build());
        } catch (Throwable ignored) {}
        if (intent != null && ACTION_STOP.equals(intent.getAction())) stopSelfResult(startId);
        return START_NOT_STICKY;   // 进程被杀重启没有对应任务，空转没有意义
    }

    @Override
    public android.os.IBinder onBind(android.content.Intent intent) {
        return null;
    }
}
