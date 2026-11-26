package com.satlink.sharepro;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * بحث مباشر: يسأل كل عنوان على الشبكة «هل أنت SatLink؟» عبر TCP.
 * ينجح حتى لو جدار حماية Windows منع النداء الجماعي (UDP)، ويبدأ بالأجهزة التي ظهرت سابقاً.
 */
public class SubnetScanner {
    public interface Callback { void onDeviceFound(DeviceInfo device); }

    private static final String PREFS = "satlink_known_devices";
    private final Context context;
    private volatile AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService pool;

    public SubnetScanner(Context context) { this.context = context.getApplicationContext(); }

    public void start(Callback callback) {
        stop();
        final AtomicBoolean myRun = new AtomicBoolean(true);
        running = myRun;
        pool = Executors.newFixedThreadPool(64);
        final ExecutorService p = pool;
        // 1) الأجهزة المعروفة أولاً (تظهر فوراً)
        for (String ip : knownIps()) p.execute(() -> { if (myRun.get()) report(probe(ip), callback, myRun); });
        // 2) كل العناوين على كل شبكة متصلة، مرتين حتى نلحق الأجهزة النائمة
        LinkedHashSet<String> prefixes = new LinkedHashSet<>();
        ArrayList<String> own = new ArrayList<>();
        for (String[] a : NetworkUtils.listAddresses()) {
            own.add(a[1]);
            String[] parts = a[1].split("\\.");
            if (parts.length == 4 && !a[1].startsWith("169.254.")) prefixes.add(parts[0] + "." + parts[1] + "." + parts[2]);
        }
        for (String ip : knownIps()) {
            String[] parts = ip.split("\\.");
            if (parts.length == 4) prefixes.add(parts[0] + "." + parts[1] + "." + parts[2]);
        }
        final List<String> hosts = new ArrayList<>();
        int n = 0;
        for (String prefix : prefixes) {
            if (n++ >= 4) break;
            for (int i = 1; i < 255; i++) { String h = prefix + "." + i; if (!own.contains(h)) hosts.add(h); }
        }
        for (int pass = 0; pass < 2; pass++) {
            for (String h : hosts) p.execute(() -> { if (myRun.get()) report(probe(h), callback, myRun); });
        }
        p.shutdown();
    }

    public void stop() {
        running.set(false);
        if (pool != null) pool.shutdownNow();
        pool = null;
    }

    private void report(DeviceInfo d, Callback callback, AtomicBoolean run) {
        if (d == null || !run.get()) return;
        remember(d.hostIp);
        callback.onDeviceFound(d);
    }

    /** يسأل الجهاز عن اسمه. الهواتف بوضع توفير الطاقة قد تتأخر بالرد، لذلك المهلة أطول من السابق. */
    static DeviceInfo probe(String host) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, NetworkUtils.PORT), 900);
            s.setSoTimeout(2500);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            out.writeUTF("SATLINK_SHARE_PRO_PING_V1");
            out.flush();
            DataInputStream in = new DataInputStream(s.getInputStream());
            String name = in.readUTF();
            int port = NetworkUtils.PORT;
            try { port = in.readInt(); } catch (Exception ignored) {}
            return new DeviceInfo(name, host, port);
        } catch (Exception e) {
            return null;
        }
    }

    private synchronized List<String> knownIps() {
        String raw = prefs().getString("ips", "");
        ArrayList<String> out = new ArrayList<>();
        for (String ip : raw.split(",")) if (!ip.trim().isEmpty()) out.add(ip.trim());
        return out;
    }

    public synchronized void remember(String ip) {
        List<String> ips = knownIps();
        ips.remove(ip);
        ips.add(0, ip);
        while (ips.size() > 20) ips.remove(ips.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (String s : ips) { if (sb.length() > 0) sb.append(','); sb.append(s); }
        prefs().edit().putString("ips", sb.toString()).apply();
    }

    private SharedPreferences prefs() { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
}
