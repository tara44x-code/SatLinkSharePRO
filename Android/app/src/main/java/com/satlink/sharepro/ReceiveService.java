package com.satlink.sharepro;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;

/** خدمة الاستلام: تبقى شغالة بالخلفية، على الراوتر وعلى «شبكة SatLink» بدون راوتر بنفس الوقت. */
public class ReceiveService extends Service implements ReceiverServer.Listener {
    public static final String ACTION_STOP = "com.satlink.sharepro.STOP_RECEIVING";
    public static final String ACTION_VNET_ON = "com.satlink.sharepro.VNET_ON";
    public static final String ACTION_VNET_OFF = "com.satlink.sharepro.VNET_OFF";
    private static final String CHANNEL = "satlink_receiving";
    private static final int NOTIFICATION_ID = 8988;

    public interface UiListener {
        void onReceiveStatus(String status);
        void onVirtualNetwork(boolean on, String ssid, String passphrase, String ip, String message);
    }

    private static volatile ReceiveService instance;
    private static WeakReference<UiListener> uiListener = new WeakReference<>(null);

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ConcurrentHashMap<String, String> sessionPeers = new ConcurrentHashMap<>();
    private ReceiverServer receiver;
    private SatLinkNsdHelper nsd;
    private UdpDiscoveryHelper udp;
    private VirtualNetwork vnet;
    private TransferLocks locks;
    private NotificationManager notifications;
    private volatile String status = "جاري تجهيز الاستلام...";
    private volatile int percent = -1;
    private volatile boolean vnetOn;
    private volatile String vnetSsid = "", vnetPass = "", vnetIp = "", vnetMessage = "";
    private long lastNotification;

    public static boolean isActive() { return instance != null; }

    public static boolean virtualNetworkWanted(Context context) {
        return prefs(context).getBoolean("vnet_enabled", true);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences("satlink_share_pro", MODE_PRIVATE);
    }

    public static void start(Context context, String action) {
        Intent intent = new Intent(context, ReceiveService.class);
        if (action != null) intent.setAction(action);
        if (Build.VERSION.SDK_INT >= 26 && !ACTION_STOP.equals(action)) context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static void attach(UiListener listener) {
        uiListener = new WeakReference<>(listener);
        ReceiveService s = instance;
        if (s != null && listener != null) {
            s.main.post(() -> {
                listener.onReceiveStatus(s.status);
                listener.onVirtualNetwork(s.vnetOn, s.vnetSsid, s.vnetPass, s.vnetIp, s.vnetMessage);
            });
        }
    }

    public static void detach(UiListener listener) {
        if (uiListener.get() == listener) uiListener.clear();
    }

    /** يوقف شبكة SatLink مؤقتاً (بدون حفظ الإعداد) حتى يتصل هذا الهاتف كمرسل بشبكة جهاز آخر. */
    public static void pauseVirtualNetwork() {
        ReceiveService s = instance;
        if (s != null) s.main.post(s::stopVirtualNetwork);
    }

    public static void cancelCurrent() {
        ReceiveService s = instance;
        if (s != null) s.receiver.cancelAll();
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        receiver = new ReceiverServer(this, this);
        nsd = new SatLinkNsdHelper(this);
        udp = new UdpDiscoveryHelper(this);
        vnet = new VirtualNetwork(this);
        locks = new TransferLocks(this, "receive");
        notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && notifications != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "استلام SatLink", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("استلام الملفات من الأجهزة القريبة");
            notifications.createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(NOTIFICATION_ID, notification);

        if (!receiver.isRunning()) {
            receiver.start();
            nsd.registerService(NetworkUtils.PORT, NetworkUtils.getDeviceName(), null);
            udp.startAnnouncing(NetworkUtils.getDeviceName(), NetworkUtils.PORT, null);
            update("الاستلام شغال", -1);
        }
        if (ACTION_VNET_ON.equals(action)) prefs(this).edit().putBoolean("vnet_enabled", true).apply();
        if (ACTION_VNET_OFF.equals(action)) prefs(this).edit().putBoolean("vnet_enabled", false).apply();
        if (virtualNetworkWanted(this)) startVirtualNetwork(); else stopVirtualNetwork();
        return START_STICKY;
    }

    private void startVirtualNetwork() {
        if (vnetOn || vnet.isHosting()) return;
        vnetOn = true;
        vnetMessage = "جاري تشغيل شبكة SatLink...";
        publishVnet();
        vnet.startHosting(NetworkUtils.getDeviceName(), new VirtualNetwork.Callback() {
            @Override public void onLog(String message) { }
            @Override public void onGroupReady(String ssid, String passphrase, String ownerIp) {
                vnetSsid = ssid; vnetPass = passphrase; vnetIp = ownerIp; vnetMessage = "شغالة";
                publishVnet();
            }
            @Override public void onGroupFailed(String reason) {
                vnetOn = false; vnetSsid = ""; vnetPass = ""; vnetIp = ""; vnetMessage = reason;
                publishVnet();
            }
        });
    }

    private void stopVirtualNetwork() {
        if (!vnetOn && !vnet.isHosting()) return;
        vnet.stopHosting();
        vnetOn = false; vnetSsid = ""; vnetPass = ""; vnetIp = ""; vnetMessage = "متوقفة";
        publishVnet();
    }

    private void publishVnet() {
        UiListener l = uiListener.get();
        if (l != null) main.post(() -> l.onVirtualNetwork(vnetOn, vnetSsid, vnetPass, vnetIp, vnetMessage));
    }

    @Override public void onDestroy() {
        try { vnet.shutdown(); } catch (Exception ignored) {}
        try { udp.shutdown(); } catch (Exception ignored) {}
        try { nsd.shutdown(); } catch (Exception ignored) {}
        try { receiver.stop(); } catch (Exception ignored) {}
        locks.release();
        instance = null;
        UiListener l = uiListener.get();
        if (l != null) main.post(() -> {
            l.onReceiveStatus("الاستلام متوقف");
            l.onVirtualNetwork(false, "", "", "", "متوقفة");
        });
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // ---------------------------------------------------------------- أحداث الاستلام

    @Override public void onLog(String message) { }

    @Override public void onSessionStarted(String session, String sender, int count, long totalBytes) {
        sessionPeers.put(session, sender);
        locks.acquire();
        update("استلام " + count + " ملف من " + sender, 0);
    }

    /** نفس الملف من نفس المرسل يبقى بنفس السطر حتى بعد الانقطاع والإكمال. */
    private String key(String session) {
        String peer = sessionPeers.get(session);
        return peer == null ? session : peer;
    }

    @Override public void onFileStarted(String session, String sender, String name, long size, long resumeFrom) {
        TransferLog.start(this, TransferLog.RECEIVE, key(session), name, sender, size, TransferLog.STATE_RUNNING);
        TransferLog.progress(this, TransferLog.RECEIVE, key(session), name, resumeFrom, size, 0);
    }

    @Override public void onProgress(String session, String name, long done, long size, long speed) {
        TransferLog.progress(this, TransferLog.RECEIVE, key(session), name, done, size, speed);
        int p = size > 0 ? (int) Math.min(100, done * 100L / size) : 0;
        update("استلام: " + name + " · " + p + "% · " + NetworkUtils.formatSpeed(speed), p);
    }

    @Override public void onFileSaved(String session, String name, long size, Uri uri) {
        TransferLog.finish(this, TransferLog.RECEIVE, key(session), name, TransferLog.STATE_DONE, uri == null ? null : uri.toString());
    }

    @Override public void onSessionEnded(String session, boolean completed, boolean cancelled) {
        String key = key(session);
        sessionPeers.remove(session);
        TransferLog.endSession(this, TransferLog.RECEIVE, key, cancelled ? TransferLog.STATE_CANCELLED : TransferLog.STATE_PAUSED);
        if (receiver.activeTransfers() == 0) locks.release();
        update(completed ? "اكتمل الاستلام · الاستلام ما زال شغال" : cancelled ? "تم الإلغاء · الاستلام ما زال شغال" : "انقطع الاتصال؛ أعد الإرسال ليكمل من آخر نقطة", -1);
    }

    @Override public void onServerFailed(String message, Exception e) {
        update("تعذر تشغيل الاستلام. أغلق التطبيقات التي تستخدم المنفذ 8988 وحاول مجدداً", -1);
        main.post(this::stopSelf);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_SCREEN, "receive");
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openIntent = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, ReceiveService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 2, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("SatLink · الاستلام شغال")
                .setContentText(status)
                .setContentIntent(openIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف الاستلام", stopIntent);
        if (percent >= 0 && percent < 100) b.setProgress(100, percent, false);
        return b.build();
    }

    private void update(String message, int progress) {
        status = message;
        percent = progress;
        UiListener l = uiListener.get();
        if (l != null) main.post(() -> l.onReceiveStatus(message));
        long now = System.currentTimeMillis();
        if (notifications != null && (now - lastNotification > 1000 || progress <= 0 || progress >= 100)) {
            lastNotification = now;
            try { notifications.notify(NOTIFICATION_ID, buildNotification()); } catch (Exception ignored) {}
        }
    }
}
