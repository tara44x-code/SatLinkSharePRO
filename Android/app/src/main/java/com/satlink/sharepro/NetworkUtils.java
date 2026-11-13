package com.satlink.sharepro;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class NetworkUtils {
    public static final int PORT = 8988;

    public static String findDeviceIp(Context context) {
        String fromConnectivity = findFromConnectivity(context);
        if (fromConnectivity != null) return fromConnectivity;

        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : interfaces) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                List<InetAddress> addresses = Collections.list(ni.getInetAddresses());
                for (InetAddress addr : addresses) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        String ip = addr.getHostAddress();
                        if (ip != null && !ip.startsWith("127.")) return ip;
                    }
                }
            }
        } catch (Exception ignored) {}
        return "غير معروف";
    }

    private static String findFromConnectivity(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            Network active = cm.getActiveNetwork();
            if (active == null) return null;
            LinkProperties props = cm.getLinkProperties(active);
            if (props == null) return null;
            for (LinkAddress la : props.getLinkAddresses()) {
                InetAddress address = la.getAddress();
                if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                    return address.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** كل عناوين الجهاز مع نوع الشبكة: الراوتر، شبكة SatLink، أو Hotspot. */
    public static List<String[]> listAddresses() {
        java.util.ArrayList<String[]> result = new java.util.ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName() == null ? "" : ni.getName().toLowerCase(Locale.US);
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun") || name.startsWith("dummy")) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) continue;
                    String type = name.startsWith("p2p") ? "شبكة SatLink"
                            : (name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap")) ? "Hotspot"
                            : name.startsWith("wlan") ? "الراوتر" : name;
                    result.add(new String[]{type, addr.getHostAddress()});
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    public static String getDeviceName() {
        String manufacturer = Build.MANUFACTURER == null ? "Android" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "Device" : Build.MODEL.trim();
        String name;
        if (model.toLowerCase(Locale.US).startsWith(manufacturer.toLowerCase(Locale.US))) {
            name = model;
        } else {
            name = manufacturer + " " + model;
        }
        name = name.replaceAll("\\s+", " ").trim();
        if (name.length() > 34) name = name.substring(0, 34).trim();
        return "SatLink - " + name;
    }

    public static String formatBytes(long bytes) {
        if (bytes < 0) return "غير معروف";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.2f MB", mb);
        double gb = mb / 1024.0;
        return String.format(Locale.US, "%.2f GB", gb);
    }

    public static String formatSpeed(long bytesPerSecond) {
        return formatBytes(bytesPerSecond) + "/s";
    }
}
