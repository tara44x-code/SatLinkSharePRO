package com.satlink.sharepro;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.NetworkInfo;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * «شبكة SatLink» بدون راوتر: جهاز الاستلام ينشئ شبكة Wi‑Fi Direct خاصة (مثل شبكة وهمية)،
 * ويعلن نفسه عليها. الهاتف المرسل يكتشفه ويتصل به تلقائياً، والحاسبة تتصل بها مثل أي شبكة Wi‑Fi
 * باستخدام الاسم وكلمة المرور الظاهرة في شاشة الاستلام.
 * يبقى الاتصال بالراوتر شغالاً بنفس الوقت على أغلب الهواتف.
 */
@SuppressLint("MissingPermission")
public class VirtualNetwork {
    private static final String SERVICE_TYPE = "_satlinkshare._tcp";
    private static final String PREFS = "satlink_vnet";

    public interface Callback {
        void onLog(String message);
        default void onGroupReady(String ssid, String passphrase, String ownerIp) {}
        default void onGroupFailed(String reason) {}
        default void onTargetFound(Target target) {}
        default void onConnected(String ownerIp, boolean iAmOwner) {}
        default void onDisconnected() {}
    }

    /** جهاز استلام ظاهر على شبكة SatLink بدون راوتر. */
    public static final class Target {
        public final WifiP2pDevice device;
        public final String name;
        public final String ssid;
        public final String passphrase;
        public final int port;

        Target(WifiP2pDevice device, String name, String ssid, String passphrase, int port) {
            this.device = device; this.name = name; this.ssid = ssid; this.passphrase = passphrase; this.port = port;
        }

        public String key() { return device == null ? name : device.deviceAddress; }
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private Callback callback;
    private boolean hosting;
    private boolean searching;
    private boolean connecting;
    private String hostName = "SatLink";
    private String ssid = "";
    private String passphrase = "";
    private int groupInfoTries;

    private final Runnable keepVisible = new Runnable() {
        @Override public void run() {
            if (!hosting) return;
            // جهاز الاستلام يبقى في وضع "الاستماع" حتى يظهر للهواتف القريبة.
            try { manager.discoverPeers(channel, null); } catch (Exception ignored) {}
            main.postDelayed(this, 25000);
        }
    };

    private final Runnable keepSearching = new Runnable() {
        @Override public void run() {
            if (!searching || connecting) return;
            requestServices();
            main.postDelayed(this, 12000);
        }
    };

    public VirtualNetwork(Context context) {
        this.context = context.getApplicationContext();
        this.manager = (WifiP2pManager) this.context.getSystemService(Context.WIFI_P2P_SERVICE);
    }

    public boolean isSupported() {
        return manager != null && context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT);
    }

    public boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= 33)
            return context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    public String getSsid() { return ssid; }
    public String getPassphrase() { return passphrase; }
    public boolean isHosting() { return hosting; }

    private boolean ensureChannel(Callback cb) {
        this.callback = cb;
        if (!isSupported()) { log("الجهاز لا يدعم Wi‑Fi Direct"); return false; }
        if (!hasPermission()) { log("اسمح بصلاحية الأجهزة القريبة/الموقع حتى تشتغل شبكة SatLink"); return false; }
        if (channel == null) {
            channel = manager.initialize(context, Looper.getMainLooper(), () -> {
                channel = null;
                log("انقطعت قناة Wi‑Fi Direct");
            });
        }
        registerReceiver();
        return channel != null;
    }

    // ------------------------------------------------------------------ جهاز الاستلام

    public void startHosting(String deviceName, Callback cb) {
        hostName = deviceName == null ? "SatLink" : deviceName;
        if (!ensureChannel(cb)) { if (cb != null) cb.onGroupFailed("شبكة SatLink غير متاحة على هذا الجهاز"); return; }
        hosting = true;
        manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { createGroup(); }
            @Override public void onFailure(int reason) { createGroup(); }
        });
    }

    private void createGroup() {
        if (!hosting) return;
        WifiP2pManager.ActionListener listener = new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                log("تم إنشاء شبكة SatLink بدون راوتر");
                groupInfoTries = 0;
                main.postDelayed(VirtualNetwork.this::fetchGroupInfo, 600);
            }
            @Override public void onFailure(int reason) {
                hosting = false;
                String text = "تعذر إنشاء شبكة SatLink: " + reasonText(reason);
                log(text);
                if (callback != null) callback.onGroupFailed(text);
            }
        };
        if (Build.VERSION.SDK_INT >= 29) {
            String[] creds = savedCredentials();
            try {
                WifiP2pConfig config = new WifiP2pConfig.Builder()
                        .setNetworkName(creds[0])
                        .setPassphrase(creds[1])
                        .enablePersistentMode(false)
                        .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
                        .build();
                manager.createGroup(channel, config, listener);
                return;
            } catch (Exception e) {
                log("تعذر تثبيت اسم الشبكة، سيتم إنشاء اسم تلقائي");
            }
        }
        manager.createGroup(channel, listener);
    }

    private void fetchGroupInfo() {
        if (!hosting || channel == null) return;
        manager.requestGroupInfo(channel, (WifiP2pGroup group) -> {
            if (group == null || !group.isGroupOwner()) {
                if (++groupInfoTries < 8) main.postDelayed(this::fetchGroupInfo, 700);
                else if (callback != null) callback.onGroupFailed("لم تكتمل شبكة SatLink، أعد المحاولة");
                return;
            }
            ssid = group.getNetworkName() == null ? "" : group.getNetworkName();
            passphrase = group.getPassphrase() == null ? "" : group.getPassphrase();
            String ip = interfaceIp(group.getInterface());
            if (ip == null) ip = "192.168.49.1";
            advertise(ip);
            main.removeCallbacks(keepVisible);
            main.post(keepVisible);
            if (callback != null) callback.onGroupReady(ssid, passphrase, ip);
        });
    }

    private void advertise(String ownerIp) {
        Map<String, String> txt = new HashMap<>();
        txt.put("n", trim(hostName, 60));
        txt.put("p", String.valueOf(NetworkUtils.PORT));
        txt.put("s", ssid);
        txt.put("k", passphrase);
        txt.put("ip", ownerIp);
        WifiP2pDnsSdServiceInfo info = WifiP2pDnsSdServiceInfo.newInstance(trim(hostName, 40), SERVICE_TYPE, txt);
        manager.clearLocalServices(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { addLocal(info); }
            @Override public void onFailure(int reason) { addLocal(info); }
        });
    }

    private void addLocal(WifiP2pDnsSdServiceInfo info) {
        manager.addLocalService(channel, info, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { log("جهازك ظاهر للهواتف القريبة بدون راوتر"); }
            @Override public void onFailure(int reason) { log("تعذر إعلان الجهاز على شبكة SatLink: " + reasonText(reason)); }
        });
    }

    public void stopHosting() {
        boolean was = hosting;
        hosting = false;
        main.removeCallbacks(keepVisible);
        if (channel == null) return;
        if (was) {
            try { manager.clearLocalServices(channel, null); } catch (Exception ignored) {}
            try { manager.removeGroup(channel, null); } catch (Exception ignored) {}
        }
        ssid = ""; passphrase = "";
        if (!searching) unregisterReceiver();
    }

    private String[] savedCredentials() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String name = prefs.getString("ssid", null);
        String pass = prefs.getString("pass", null);
        if (name == null || pass == null) {
            name = "DIRECT-SL-SatLink-" + randomText(4);
            pass = randomText(10);
            prefs.edit().putString("ssid", name).putString("pass", pass).apply();
        }
        return new String[]{name, pass};
    }

    // ------------------------------------------------------------------ جهاز الإرسال

    public void startSearching(Callback cb) {
        if (!ensureChannel(cb)) return;
        searching = true;
        connecting = false;
        manager.setDnsSdResponseListeners(channel,
                (instanceName, registrationType, device) -> { },
                (fullDomainName, record, device) -> {
                    if (record == null || !record.containsKey("n")) return;
                    if (fullDomainName != null && !fullDomainName.toLowerCase().contains("satlinkshare")) return;
                    int port = NetworkUtils.PORT;
                    try { port = Integer.parseInt(record.get("p")); } catch (Exception ignored) {}
                    Target t = new Target(device, record.get("n"), record.get("s"), record.get("k"), port);
                    if (callback != null) callback.onTargetFound(t);
                });
        main.removeCallbacks(keepSearching);
        main.post(keepSearching);
    }

    private void requestServices() {
        if (channel == null) return;
        manager.discoverPeers(channel, null);
        manager.clearServiceRequests(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { addRequest(); }
            @Override public void onFailure(int reason) { addRequest(); }
        });
    }

    private void addRequest() {
        manager.addServiceRequest(channel, WifiP2pDnsSdServiceRequest.newInstance(), new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                manager.discoverServices(channel, new WifiP2pManager.ActionListener() {
                    @Override public void onSuccess() { }
                    @Override public void onFailure(int reason) {
                        if (reason != WifiP2pManager.BUSY) log("بحث شبكة SatLink: " + reasonText(reason));
                    }
                });
            }
            @Override public void onFailure(int reason) { }
        });
    }

    public void stopSearching() {
        searching = false;
        main.removeCallbacks(keepSearching);
        if (channel == null) return;
        try { manager.clearServiceRequests(channel, null); } catch (Exception ignored) {}
        try { manager.stopPeerDiscovery(channel, null); } catch (Exception ignored) {}
        if (!hosting && !connecting) unregisterReceiver();
    }

    public void connect(Target target, Callback cb) {
        if (!ensureChannel(cb)) return;
        connecting = true;
        main.removeCallbacks(keepSearching);
        WifiP2pConfig config;
        if (Build.VERSION.SDK_INT >= 29 && notEmpty(target.ssid) && notEmpty(target.passphrase)) {
            // الانضمام بالاسم وكلمة المرور: بدون نافذة موافقة عند جهاز الاستلام.
            config = new WifiP2pConfig.Builder()
                    .setNetworkName(target.ssid)
                    .setPassphrase(target.passphrase)
                    .enablePersistentMode(false)
                    .build();
        } else {
            config = new WifiP2pConfig();
            config.deviceAddress = target.device.deviceAddress;
            config.wps.setup = WpsInfo.PBC;
            config.groupOwnerIntent = 0;
        }
        final WifiP2pConfig finalConfig = config;
        manager.stopPeerDiscovery(channel, null);
        manager.connect(channel, finalConfig, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { log("جاري الاتصال بشبكة " + target.name + " ..."); }
            @Override public void onFailure(int reason) {
                connecting = false;
                log("تعذر الاتصال بشبكة SatLink: " + reasonText(reason));
                if (callback != null) callback.onDisconnected();
            }
        });
        // إذا كان الهاتف متصلاً مسبقاً بنفس الشبكة لن يصل بث جديد، فنسأل عن حالة الاتصال مباشرة.
        main.postDelayed(this::checkConnection, 1500);
    }

    public void disconnect() {
        connecting = false;
        if (channel == null) return;
        try { manager.cancelConnect(channel, null); } catch (Exception ignored) {}
        if (!hosting) try { manager.removeGroup(channel, null); } catch (Exception ignored) {}
    }

    private void checkConnection() {
        if (channel == null) return;
        manager.requestConnectionInfo(channel, (WifiP2pInfo info) -> {
            if (info == null || !info.groupFormed) return;
            String owner = info.groupOwnerAddress == null ? null : info.groupOwnerAddress.getHostAddress();
            if (connecting) {
                connecting = false;
                if (callback != null) callback.onConnected(owner, info.isGroupOwner);
            }
        });
    }

    // ------------------------------------------------------------------ مشترك

    private void registerReceiver() {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                String action = intent == null ? null : intent.getAction();
                if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                    NetworkInfo ni = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                    if (ni != null && ni.isConnected()) checkConnection();
                    else if (!hosting && callback != null && !connecting) callback.onDisconnected();
                } else if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                    int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                    if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) log("شغّل Wi‑Fi حتى تعمل شبكة SatLink");
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        try {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else context.registerReceiver(receiver, filter);
        } catch (Exception e) {
            receiver = null;
        }
    }

    private void unregisterReceiver() {
        if (receiver == null) return;
        try { context.unregisterReceiver(receiver); } catch (Exception ignored) {}
        receiver = null;
    }

    public void shutdown() {
        stopSearching();
        stopHosting();
        unregisterReceiver();
    }

    private void log(String message) {
        Callback cb = callback;
        if (cb != null) main.post(() -> cb.onLog(message));
    }

    private static String interfaceIp(String name) {
        if (name == null) return null;
        try {
            NetworkInterface ni = NetworkInterface.getByName(name);
            if (ni == null) return null;
            for (InetAddress a : Collections.list(ni.getInetAddresses()))
                if (a instanceof Inet4Address) return a.getHostAddress();
        } catch (Exception ignored) {}
        return null;
    }

    private static String randomText(int length) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) sb.append(chars.charAt(random.nextInt(chars.length())));
        return sb.toString();
    }

    private static String trim(String s, int max) {
        if (s == null) return "SatLink";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static boolean notEmpty(String s) { return s != null && !s.trim().isEmpty(); }

    static String reasonText(int reason) {
        if (reason == WifiP2pManager.P2P_UNSUPPORTED) return "الجهاز لا يدعم Wi‑Fi Direct";
        if (reason == WifiP2pManager.BUSY) return "النظام مشغول، حاول بعد ثواني";
        if (reason == WifiP2pManager.NO_SERVICE_REQUESTS) return "لا يوجد طلب بحث";
        return "خطأ من النظام (" + reason + ")";
    }
}
