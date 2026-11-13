package com.satlink.sharepro;

import android.content.Context;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class UdpDiscoveryHelper {
    public interface UdpCallback {
        void onDeviceFound(DeviceInfo deviceInfo);
        void onDiscoveryLog(String message);
    }

    private static final int DISCOVERY_PORT = 8989;
    private static final String DISCOVER = "SATLINK_SHARE_PRO_DISCOVER_V1";
    private static final String RESPONSE_PREFIX = "SATLINK_SHARE_PRO_RESPONSE_V1|";
    private static final String PING_PROTOCOL = "SATLINK_SHARE_PRO_PING_V1";

    private final Context context;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicBoolean announcing = new AtomicBoolean(false);
    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private final AtomicInteger announceGeneration = new AtomicInteger();
    private final AtomicInteger scanGeneration = new AtomicInteger();
    private volatile DatagramSocket announceSocket;
    private volatile DatagramSocket scanSocket;
    private String deviceName = "SatLink Device";
    private int transferPort = NetworkUtils.PORT;

    public UdpDiscoveryHelper(Context context) {
        this.context = context.getApplicationContext();
    }

    public void startAnnouncing(String displayName, int port, UdpCallback callback) {
        stopAnnouncing();
        this.deviceName = (displayName == null || displayName.trim().isEmpty()) ? NetworkUtils.getDeviceName() : displayName.trim();
        this.transferPort = port;
        announcing.set(true);
        final int generation = announceGeneration.incrementAndGet();
        executor.execute(() -> {
            DatagramSocket socket = null;
            try {
                if (announceGeneration.get() != generation) return;
                socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(DISCOVERY_PORT));
                announceSocket = socket;
                byte[] buffer = new byte[1024];
                if (callback != null) callback.onDiscoveryLog("UDP Broadcast شغال كاحتياط للراوتر");
                while (announcing.get() && announceGeneration.get() == generation) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
                    if (!DISCOVER.equals(msg)) continue;
                    InetAddress sender = packet.getAddress();
                    if (sender == null) continue;
                    String response = RESPONSE_PREFIX + safe(deviceName) + "|" + transferPort;
                    byte[] data = response.getBytes(StandardCharsets.UTF_8);
                    DatagramPacket out = new DatagramPacket(data, data.length, sender, packet.getPort());
                    socket.send(out);
                }
            } catch (Exception e) {
                if (announcing.get() && callback != null) callback.onDiscoveryLog("تعذر تشغيل اكتشاف الراوتر الاحتياطي: " + e.getMessage());
            } finally {
                if (socket != null) socket.close();
                if (announceSocket == socket) announceSocket = null;
                if (announceGeneration.get() == generation) announcing.set(false);
            }
        });
    }

    public void stopAnnouncing() {
        announceGeneration.incrementAndGet();
        announcing.set(false);
        closeAnnounceSocket();
    }

    public void startScan(UdpCallback callback) {
        stopScan();
        scanning.set(true);
        final int generation = scanGeneration.incrementAndGet();
        executor.execute(() -> {
            DatagramSocket socket = null;
            try {
                if (scanGeneration.get() != generation) return;
                // The scanner needs a separate ephemeral port so the receiver can
                // continue answering discovery requests on 8989 on this device.
                socket = new DatagramSocket(0);
                socket.setBroadcast(true);
                socket.setSoTimeout(900);
                scanSocket = socket;
                if (callback != null) callback.onDiscoveryLog("بحث احتياطي عبر الراوتر UDP Broadcast بدأ");

                long endAt = System.currentTimeMillis() + 11000L;
                int round = 0;
                while (scanning.get() && scanGeneration.get() == generation && System.currentTimeMillis() < endAt) {
                    round++;
                    broadcastDiscover(socket);
                    long listenUntil = System.currentTimeMillis() + 1200L;
                    while (scanning.get() && scanGeneration.get() == generation && System.currentTimeMillis() < listenUntil) {
                        try {
                            byte[] buffer = new byte[1024];
                            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                            socket.receive(response);
                            String msg = new String(response.getData(), 0, response.getLength(), StandardCharsets.UTF_8).trim();
                            if (!msg.startsWith(RESPONSE_PREFIX)) continue;
                            String[] parts = msg.split("\\|", 3);
                            if (parts.length < 3) continue;
                            String name = parts[1].trim().isEmpty() ? "SatLink Device" : parts[1].trim();
                            int port = NetworkUtils.PORT;
                            try { port = Integer.parseInt(parts[2].trim()); } catch (Exception ignored) {}
                            InetAddress address = response.getAddress();
                            if (address == null) continue;
                            String ip = address.getHostAddress();
                            String myIp = NetworkUtils.findDeviceIp(context);
                            if (ip == null || ip.equals(myIp)) continue;
                            if (callback != null) callback.onDeviceFound(new DeviceInfo(name + " · LAN", ip, port));
                        } catch (SocketTimeoutException ignored) {
                            break;
                        }
                    }
                    if (round < 5) sleep(800);
                }
                if (callback != null) callback.onDiscoveryLog("انتهى البحث الاحتياطي عبر الراوتر");
            } catch (Exception e) {
                if (scanning.get() && callback != null) callback.onDiscoveryLog("تعذر البحث الاحتياطي: " + e.getMessage());
            } finally {
                if (socket != null) socket.close();
                if (scanSocket == socket) scanSocket = null;
                if (scanGeneration.get() == generation) scanning.set(false);
            }
        });
    }

    public void stopScan() {
        scanGeneration.incrementAndGet();
        scanning.set(false);
        closeScanSocket();
    }

    public void shutdown() {
        stopScan();
        stopAnnouncing();
    }

    private void broadcastDiscover(DatagramSocket socket) throws Exception {
        byte[] data = DISCOVER.getBytes(StandardCharsets.UTF_8);
        List<InetAddress> targets = new ArrayList<>();
        try { targets.add(InetAddress.getByName("255.255.255.255")); } catch (Exception ignored) {}
        targets.addAll(findBroadcastAddresses());
        for (InetAddress target : targets) {
            try {
                DatagramPacket packet = new DatagramPacket(data, data.length, target, DISCOVERY_PORT);
                socket.send(packet);
            } catch (Exception ignored) {}
        }
    }

    private static List<InetAddress> findBroadcastAddresses() {
        ArrayList<InetAddress> result = new ArrayList<>();
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : interfaces) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress broadcast = ia.getBroadcast();
                    InetAddress address = ia.getAddress();
                    if (broadcast != null && address instanceof Inet4Address) result.add(broadcast);
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    private static String safe(String s) {
        if (s == null) return "SatLink Device";
        return s.replace("|", " ").replace("\n", " ").trim();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private void closeAnnounceSocket() {
        try { if (announceSocket != null) announceSocket.close(); } catch (Exception ignored) {}
        announceSocket = null;
    }

    private void closeScanSocket() {
        try { if (scanSocket != null) scanSocket.close(); } catch (Exception ignored) {}
        scanSocket = null;
    }
}
