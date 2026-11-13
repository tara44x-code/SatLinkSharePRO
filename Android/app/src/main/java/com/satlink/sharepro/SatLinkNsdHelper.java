package com.satlink.sharepro;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;

public class SatLinkNsdHelper {
    public interface DiscoveryCallback {
        void onDeviceFound(DeviceInfo deviceInfo);
        void onDiscoveryLog(String message);
    }

    private static final String SERVICE_TYPE = "_satlinkshare._tcp.";

    private final Context context;
    private final NsdManager nsdManager;
    private final WifiManager wifiManager;

    private NsdManager.RegistrationListener registrationListener;
    private NsdManager.DiscoveryListener discoveryListener;
    private WifiManager.MulticastLock multicastLock;
    private String localServiceName;
    private final AtomicBoolean resolving = new AtomicBoolean(false);

    public SatLinkNsdHelper(Context context) {
        this.context = context.getApplicationContext();
        this.nsdManager = (NsdManager) this.context.getSystemService(Context.NSD_SERVICE);
        this.wifiManager = (WifiManager) this.context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    public void registerService(int port, String displayName, DiscoveryCallback logCallback) {
        if (nsdManager == null) {
            if (logCallback != null) logCallback.onDiscoveryLog("NSD غير متوفر على هذا الجهاز");
            return;
        }
        unregisterService();
        localServiceName = displayName == null || displayName.trim().isEmpty() ? NetworkUtils.getDeviceName() : displayName.trim();

        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName(localServiceName);
        serviceInfo.setServiceType(SERVICE_TYPE);
        serviceInfo.setPort(port);

        registrationListener = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo info) {
                localServiceName = info.getServiceName();
                if (logCallback != null) logCallback.onDiscoveryLog("ظهر جهازك للآخرين باسم: " + localServiceName);
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo info, int errorCode) {
                if (logCallback != null) logCallback.onDiscoveryLog("فشل إعلان الجهاز. كود: " + errorCode);
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo info) {
                if (logCallback != null) logCallback.onDiscoveryLog("تم إيقاف ظهور الجهاز");
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo info, int errorCode) {
                if (logCallback != null) logCallback.onDiscoveryLog("فشل إيقاف الإعلان. كود: " + errorCode);
            }
        };

        try {
            acquireMulticastLock();
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener);
        } catch (Exception e) {
            if (logCallback != null) logCallback.onDiscoveryLog("تعذر إعلان الجهاز: " + e.getMessage());
        }
    }

    public void unregisterService() {
        if (nsdManager != null && registrationListener != null) {
            try { nsdManager.unregisterService(registrationListener); } catch (Exception ignored) {}
        }
        registrationListener = null;
    }

    public void startDiscovery(DiscoveryCallback callback) {
        if (nsdManager == null) {
            if (callback != null) callback.onDiscoveryLog("NSD غير متوفر على هذا الجهاز");
            return;
        }
        stopDiscovery();
        resolving.set(false);
        acquireMulticastLock();

        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String regType) {
                if (callback != null) callback.onDiscoveryLog("بدأ البحث عن أجهزة SatLink القريبة");
            }

            @Override
            public void onServiceFound(NsdServiceInfo service) {
                if (service == null || service.getServiceType() == null) return;
                if (!SERVICE_TYPE.equals(service.getServiceType())) return;
                if (localServiceName != null && localServiceName.equals(service.getServiceName())) return;
                resolveService(service, callback);
            }

            @Override
            public void onServiceLost(NsdServiceInfo service) {
                if (callback != null && service != null) callback.onDiscoveryLog("اختفى جهاز: " + service.getServiceName());
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                if (callback != null) callback.onDiscoveryLog("توقف البحث");
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                if (callback != null) callback.onDiscoveryLog("فشل بدء البحث. كود: " + errorCode);
                try { nsdManager.stopServiceDiscovery(this); } catch (Exception ignored) {}
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                if (callback != null) callback.onDiscoveryLog("فشل إيقاف البحث. كود: " + errorCode);
            }
        };

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (Exception e) {
            if (callback != null) callback.onDiscoveryLog("تعذر البحث: " + e.getMessage());
        }
    }

    public void stopDiscovery() {
        if (nsdManager != null && discoveryListener != null) {
            try { nsdManager.stopServiceDiscovery(discoveryListener); } catch (Exception ignored) {}
        }
        discoveryListener = null;
        resolving.set(false);
    }

    public void shutdown() {
        stopDiscovery();
        unregisterService();
        releaseMulticastLock();
    }

    private void resolveService(NsdServiceInfo service, DiscoveryCallback callback) {
        if (!resolving.compareAndSet(false, true)) return;
        try {
            nsdManager.resolveService(service, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                    resolving.set(false);
                    if (callback != null) callback.onDiscoveryLog("تعذر قراءة جهاز: " + errorCode);
                }

                @Override
                public void onServiceResolved(NsdServiceInfo info) {
                    resolving.set(false);
                    try {
                        InetAddress host = info.getHost();
                        if (host == null) return;
                        String hostIp = host.getHostAddress();
                        if (hostIp == null || hostIp.length() == 0) return;
                        String myIp = NetworkUtils.findDeviceIp(context);
                        if (hostIp.equals(myIp)) return;
                        if (callback != null) callback.onDeviceFound(new DeviceInfo(info.getServiceName(), hostIp, info.getPort()));
                    } catch (Exception e) {
                        if (callback != null) callback.onDiscoveryLog("خطأ قراءة الجهاز: " + e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            resolving.set(false);
            if (callback != null) callback.onDiscoveryLog("فشل حل الجهاز: " + e.getMessage());
        }
    }

    private void acquireMulticastLock() {
        try {
            if (wifiManager == null) return;
            if (multicastLock == null) {
                multicastLock = wifiManager.createMulticastLock("satlink_share_nsd_lock");
                multicastLock.setReferenceCounted(false);
            }
            if (!multicastLock.isHeld()) multicastLock.acquire();
        } catch (Exception ignored) {}
    }

    private void releaseMulticastLock() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        } catch (Exception ignored) {}
        multicastLock = null;
    }
}
