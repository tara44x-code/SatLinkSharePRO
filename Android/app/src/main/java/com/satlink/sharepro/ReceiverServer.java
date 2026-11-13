package com.satlink.sharepro;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** خادم الاستلام: يقبل الاتصالات على المنفذ 8988، كل اتصال بخيط مستقل. */
public class ReceiverServer {
    public interface Listener {
        void onLog(String message);
        void onSessionStarted(String session, String sender, int count, long totalBytes);
        void onFileStarted(String session, String sender, String name, long size, long resumeFrom);
        void onProgress(String session, String name, long done, long size, long speed);
        void onFileSaved(String session, String name, long size, Uri uri);
        void onSessionEnded(String session, boolean completed, boolean cancelled);
        void onServerFailed(String message, Exception e);
    }

    private static final int BUFFER = 512 * 1024;
    private final Context context;
    private final Listener listener;
    private final ExecutorService acceptor = Executors.newSingleThreadExecutor();
    private final ExecutorService clients = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger sessions = new AtomicInteger();
    private final Set<Socket> active = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<Socket> cancelled = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    private volatile ServerSocket serverSocket;

    public ReceiverServer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public boolean isRunning() { return running.get(); }

    public int activeTransfers() { return active.size(); }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        acceptor.execute(() -> {
            try {
                ServerSocket server = new ServerSocket();
                server.setReuseAddress(true);
                server.setReceiveBufferSize(2 * 1024 * 1024);
                server.bind(new InetSocketAddress(NetworkUtils.PORT));
                serverSocket = server;
                listener.onLog("جاهز للاستلام على المنفذ " + NetworkUtils.PORT);
                while (running.get()) {
                    Socket socket = server.accept();
                    clients.execute(() -> handleClient(socket));
                }
            } catch (Exception e) {
                if (running.get()) listener.onServerFailed("تعذر تشغيل الاستلام", e);
            } finally {
                running.set(false);
                closeServerQuietly();
            }
        });
    }

    public void stop() {
        running.set(false);
        cancelAll();
        closeServerQuietly();
    }

    public void cancelAll() {
        for (Socket s : active) {
            cancelled.add(s);
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private void handleClient(Socket socket) {
        String session = System.currentTimeMillis() + "-" + sessions.incrementAndGet();
        boolean started = false;
        boolean completed = false;
        try (Socket s = socket) {
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            s.setSoTimeout(120000);
            try { s.setReceiveBufferSize(2 * 1024 * 1024); } catch (Exception ignored) {}
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), BUFFER));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 64 * 1024));

            String header = in.readUTF();
            if ("SATLINK_SHARE_PRO_PING_V1".equals(header)) {
                out.writeUTF(NetworkUtils.getDeviceName());
                out.writeInt(NetworkUtils.PORT);
                out.flush();
                return;
            }
            boolean folderProtocol = SenderClient.FOLDER_PROTOCOL.equals(header);
            if (!folderProtocol && !SenderClient.PROTOCOL.equals(header)) throw new IllegalStateException("اتصال غير معروف أو نسخة قديمة");
            String senderName = in.readUTF();
            int count = in.readInt();
            long totalBytes = in.readLong();
            if (count < 0 || count > 10000 || totalBytes < 0) throw new IllegalStateException("بيانات إرسال غير صالحة");

            // فتح صفحة الاستلام يعني الموافقة على الاستلام من الأجهزة على نفس الشبكة.
            out.writeBoolean(true);
            out.flush();
            active.add(socket);
            started = true;
            listener.onSessionStarted(session, senderName, count, totalBytes);

            byte[] buffer = new byte[BUFFER];
            for (int i = 0; i < count && running.get(); i++) {
                String rawName = in.readUTF();
                String fileName = folderProtocol ? FileStoreUtils.sanitizeRelativePath(rawName) : FileStoreUtils.sanitizeFileName(rawName);
                long total = in.readLong();
                if (total < 0) throw new IllegalStateException("حجم ملف غير معروف");

                long resumeFrom = FileStoreUtils.getPartialSize(context, fileName, total);
                if (resumeFrom < 0 || resumeFrom > total) {
                    FileStoreUtils.resetPartial(context, fileName, total);
                    resumeFrom = 0L;
                }
                out.writeLong(resumeFrom);
                out.flush();
                listener.onFileStarted(session, senderName, fileName, total, resumeFrom);
                if (resumeFrom > 0 && resumeFrom < total)
                    listener.onLog("إكمال الاستلام: " + fileName + " من " + NetworkUtils.formatBytes(resumeFrom));

                long received = resumeFrom;
                long lastBytes = received;
                long lastTick = System.currentTimeMillis();
                if (received < total) {
                    try (OutputStream rawOut = FileStoreUtils.openPartialOutput(context, fileName, total, received);
                         BufferedOutputStream fileOut = new BufferedOutputStream(rawOut, BUFFER)) {
                        while (received < total) {
                            int read = in.read(buffer, 0, (int) Math.min(buffer.length, total - received));
                            if (read == -1) break;
                            fileOut.write(buffer, 0, read);
                            received += read;
                            long now = System.currentTimeMillis();
                            if (now - lastTick >= 300) {
                                long speed = (received - lastBytes) * 1000 / Math.max(1, now - lastTick);
                                listener.onProgress(session, fileName, received, total, speed);
                                lastBytes = received;
                                lastTick = now;
                            }
                        }
                        fileOut.flush();
                    }
                }
                if (received < total) throw new java.io.EOFException("انقطع الاتصال أثناء استلام " + fileName);
                listener.onProgress(session, fileName, received, total, 0);
                Uri saved = FileStoreUtils.copyPartialToDownloads(context, fileName, total);
                FileStoreUtils.deletePartial(context, fileName, total);
                listener.onFileSaved(session, fileName, total, saved);
            }
            completed = true;
        } catch (Exception e) {
            if (started && !cancelled.contains(socket) && running.get())
                listener.onLog("انقطع الاستلام؛ الجزء المستلم محفوظ ويكمل عند إعادة الإرسال");
        } finally {
            boolean wasCancelled = cancelled.remove(socket);
            active.remove(socket);
            if (started) listener.onSessionEnded(session, completed, wasCancelled);
        }
    }

    private void closeServerQuietly() {
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        serverSocket = null;
    }
}
