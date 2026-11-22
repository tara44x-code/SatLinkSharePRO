package com.satlink.sharepro;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** خدمة الإرسال: تكمل الإرسال حتى لو خرجت من التطبيق أو أطفأت الشاشة. */
public class SendService extends Service implements SenderClient.Listener {
    public static final String ACTION_CANCEL = "com.satlink.sharepro.CANCEL_SEND";
    private static final String CHANNEL = "satlink_sending";
    private static final int NOTIFICATION_ID = 8990;

    public interface UiListener {
        void onSendStatus(String status, boolean busy);
        /** رمز التحقق: يجب أن يطابق الرمز الظاهر على جهاز الاستلام. */
        void onVerifyCode(String peer, String code);
    }

    private static final class Job {
        final DeviceInfo target; final List<FileItem> files;
        Job(DeviceInfo target, List<FileItem> files) { this.target = target; this.files = files; }
    }

    private static final ConcurrentLinkedQueue<Job> queue = new ConcurrentLinkedQueue<>();
    private static volatile SendService instance;
    private static WeakReference<UiListener> uiListener = new WeakReference<>(null);

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger running = new AtomicInteger();
    private SenderClient client;
    private TransferLocks locks;
    private NotificationManager notifications;
    private volatile String status = "";
    private volatile int percent = -1;
    private volatile String batchPeer = "";
    private long lastNotification;

    public static void send(Context context, DeviceInfo target, List<FileItem> files) {
        queue.add(new Job(target, new ArrayList<>(files)));
        Intent intent = new Intent(context, SendService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent); else context.startService(intent);
    }

    public static void cancel() {
        queue.clear();
        SendService s = instance;
        if (s != null) s.client.cancel();
    }

    public static boolean isBusy() {
        SendService s = instance;
        return s != null && s.running.get() > 0;
    }

    public static void attach(UiListener l) {
        uiListener = new WeakReference<>(l);
        SendService s = instance;
        if (s != null && l != null) s.main.post(() -> l.onSendStatus(s.status, s.running.get() > 0));
    }

    public static void detach(UiListener l) { if (uiListener.get() == l) uiListener.clear(); }

    @Override public void onCreate() {
        super.onCreate();
        L.init(this);
        instance = this;
        client = new SenderClient(this, this);
        locks = new TransferLocks(this, "send");
        notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && notifications != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, L.t("إرسال SatLink", "SatLink sending"), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("تقدم إرسال الملفات");
            notifications.createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(NOTIFICATION_ID, n);
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancel();
            stopIfIdle();
            return START_NOT_STICKY;
        }
        Job job;
        client.parallel = getSharedPreferences("satlink_share_pro", MODE_PRIVATE).getBoolean("parallel", true);
        while ((job = queue.poll()) != null) {
            running.incrementAndGet();
            locks.acquire();
            client.send(job.target, job.files);
        }
        stopIfIdle();
        return START_NOT_STICKY;
    }

    private void stopIfIdle() {
        if (running.get() == 0 && queue.isEmpty()) {
            locks.release();
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
            stopSelf();
        }
    }

    @Override public void onDestroy() {
        locks.release();
        instance = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // ---------------------------------------------------------------- أحداث الإرسال

    @Override public void onLog(String message) { update(message, percent); }

    @Override public void onBatchQueued(String batch, DeviceInfo target, List<FileItem> files) {
        batchPeer = target.name;
        for (FileItem f : files) {
            TransferLog.start(this, TransferLog.SEND, batch, f.name, target.name, f.size, TransferLog.STATE_CONNECTING);
            TransferLog.setSource(this, TransferLog.SEND, batch, f.name, f.uri == null ? null : f.uri.toString(), target.hostIp, target.port);
        }
        update(L.t("جاري الاتصال بـ ", "Connecting to ") + target.name, 0);
    }

    @Override public void onVerifyCode(String batch, String code) {
        update(L.t("رمز التحقق ", "Verification code ") + code + L.t(" · بانتظار موافقة ", " · waiting for ") + batchPeer, 0);
        UiListener l = uiListener.get();
        final String peer = batchPeer;
        if (l != null) main.post(() -> l.onVerifyCode(peer, code));
    }

    @Override public void onFileStarted(String batch, String name, long size, long resumeFrom) {
        TransferLog.progress(this, TransferLog.SEND, batch, name, resumeFrom, size, 0);
    }

    @Override public void onProgress(String batch, String name, long done, long size, long speed) {
        TransferLog.progress(this, TransferLog.SEND, batch, name, done, size, speed);
        int p = size > 0 ? (int) Math.min(100, done * 100L / size) : 0;
        update(L.t("إرسال: ", "Sending: ") + name + " · " + p + "% · " + NetworkUtils.formatSpeed(speed), p);
    }

    @Override public void onFileFinished(String batch, String name) {
        TransferLog.finish(this, TransferLog.SEND, batch, name, TransferLog.STATE_DONE, null);
    }

    @Override public void onBatchEnded(String batch, boolean completed, boolean cancelled, String error) {
        boolean rejected = error != null && error.contains("رفض");
        TransferLog.endSession(this, TransferLog.SEND, batch,
                cancelled ? TransferLog.STATE_CANCELLED : rejected ? TransferLog.STATE_REJECTED : TransferLog.STATE_FAILED);
        String text = completed ? L.t("اكتمل الإرسال إلى ", "Sent to ") + batchPeer
                : cancelled ? L.t("تم إلغاء الإرسال", "Sending cancelled")
                : (rejected ? L.t("رفض الجهاز الآخر الاستلام", "The receiver declined") : L.t("تعذر الإرسال", "Sending failed") + (error == null ? "" : ": " + error) + L.t(" · اضغط «أكمل» ليكمل من آخر نقطة", " · tap Resume to continue"));
        running.decrementAndGet();
        update(text, -1);
        showResult(text);
        main.post(this::stopIfIdle);
    }

    private void showResult(String text) {
        if (notifications == null) return;
        Intent open = new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_SCREEN, "send");
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openIntent = PendingIntent.getActivity(this, 5, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_sys_upload_done).setContentTitle("SatLink").setContentText(text)
                .setContentIntent(openIntent).setAutoCancel(true);
        try { notifications.notify(NOTIFICATION_ID + 1, b.build()); } catch (Exception ignored) {}
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_SCREEN, "send");
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openIntent = PendingIntent.getActivity(this, 3, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent cancel = new Intent(this, SendService.class).setAction(ACTION_CANCEL);
        PendingIntent cancelIntent = PendingIntent.getService(this, 4, cancel, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(L.t("SatLink · إرسال الملفات", "SatLink · sending files"))
                .setContentText(status.isEmpty() ? L.t("جاري التجهيز...", "Preparing...") : status)
                .setContentIntent(openIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, L.t("إلغاء", "Cancel"), cancelIntent);
        if (percent >= 0 && percent < 100) b.setProgress(100, percent, percent == 0);
        return b.build();
    }

    private void update(String message, int progress) {
        status = message;
        percent = progress;
        boolean busy = running.get() > 0;
        UiListener l = uiListener.get();
        if (l != null) main.post(() -> l.onSendStatus(message, busy));
        long now = System.currentTimeMillis();
        if (notifications != null && running.get() > 0 && (now - lastNotification > 1000 || progress <= 0)) {
            lastNotification = now;
            try { notifications.notify(NOTIFICATION_ID, buildNotification()); } catch (Exception ignored) {}
        }
    }
}
