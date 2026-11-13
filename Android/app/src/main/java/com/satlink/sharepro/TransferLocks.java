package com.satlink.sharepro;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.PowerManager;

/**
 * يمنع النظام من تخفيض سرعة الواي فاي أو إيقاف المعالج أثناء النقل، وهذا يرفع السرعة كثيراً
 * عند إطفاء الشاشة أو العمل بالخلفية.
 */
final class TransferLocks {
    private final WifiManager.WifiLock wifiLock;
    private final PowerManager.WakeLock wakeLock;

    @SuppressWarnings("deprecation")
    TransferLocks(Context context, String tag) {
        Context app = context.getApplicationContext();
        WifiManager wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        PowerManager power = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        WifiManager.WifiLock w = null;
        PowerManager.WakeLock p = null;
        try {
            if (wifi != null) {
                w = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SatLink:" + tag);
                w.setReferenceCounted(false);
            }
        } catch (Exception ignored) {}
        try {
            if (power != null) {
                p = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SatLink:" + tag);
                p.setReferenceCounted(false);
            }
        } catch (Exception ignored) {}
        wifiLock = w;
        wakeLock = p;
    }

    synchronized void acquire() {
        try { if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire(); } catch (Exception ignored) {}
        // حد أقصى 6 ساعات كحماية للبطارية إذا علِق النقل.
        try { if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(6 * 60 * 60 * 1000L); } catch (Exception ignored) {}
    }

    synchronized void release() {
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) {}
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
    }
}
