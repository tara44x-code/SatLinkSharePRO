package com.satlink.sharepro;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** مرسل الملفات. عند انقطاع الاتصال يعيد المحاولة تلقائياً ويكمل من آخر بايت وصل. */
public class SenderClient {
    public static final String PROTOCOL = "SATLINK_SHARE_PRO_V3_RESUME";
    public static final String FOLDER_PROTOCOL = "SATLINK_SHARE_PRO_V4_FOLDERS";
    private static final int BUFFER = 512 * 1024;
    private static final int MAX_ATTEMPTS = 5;

    public interface Listener {
        void onLog(String message);
        void onBatchQueued(String batch, DeviceInfo target, List<FileItem> files);
        void onFileStarted(String batch, String name, long size, long resumeFrom);
        void onProgress(String batch, String name, long done, long size, long speed);
        void onFileFinished(String batch, String name);
        void onBatchEnded(String batch, boolean completed, boolean cancelled, String error);
    }

    private final Context context;
    private final Listener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile Socket currentSocket;
    private volatile boolean busy;

    public SenderClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public boolean isBusy() { return busy; }

    public void send(DeviceInfo device, List<FileItem> files) {
        final List<FileItem> copy = new ArrayList<>(files);
        final String batch = String.valueOf(System.currentTimeMillis());
        listener.onBatchQueued(batch, device, copy);
        executor.execute(() -> runBatch(batch, device, copy));
    }

    private void runBatch(String batch, DeviceInfo device, List<FileItem> files) {
        busy = true;
        cancelled.set(false);
        String error = null;
        boolean completed = false;
        try {
            for (FileItem item : files)
                if (item.size < 0) throw new IllegalArgumentException("حجم ملف غير معروف: " + item.name);
            int attempt = 0;
            java.util.HashSet<String> finished = new java.util.HashSet<>();
            while (!cancelled.get()) {
                attempt++;
                try {
                    // بعد الانقطاع لا نعيد الملفات التي اكتملت، ونكمل الملف الناقص من مكان توقفه.
                    List<FileItem> remaining = new ArrayList<>();
                    long remainingBytes = 0L;
                    for (FileItem item : files) if (!finished.contains(item.name)) { remaining.add(item); remainingBytes += item.size; }
                    if (remaining.isEmpty()) { completed = true; break; }
                    sendOnce(batch, device, remaining, remainingBytes, finished);
                    completed = !cancelled.get();
                    break;
                } catch (IOException e) {
                    if (cancelled.get()) break;
                    if (e instanceof RejectedException || attempt >= MAX_ATTEMPTS) throw e;
                    listener.onLog("انقطع الاتصال، إعادة المحاولة " + attempt + " والإكمال من آخر نقطة...");
                    sleep(Math.min(8000, 1500L * attempt));
                }
            }
        } catch (Exception e) {
            error = e.getMessage() == null ? "تعذر الإرسال" : e.getMessage();
        } finally {
            closeSocketQuietly();
            busy = false;
            listener.onBatchEnded(batch, completed, cancelled.get(), error);
        }
    }

    private static final class RejectedException extends IOException {
        RejectedException(String message) { super(message); }
    }

    private void sendOnce(String batch, DeviceInfo device, List<FileItem> files, long totalBytes, java.util.Set<String> finished) throws IOException {
        Socket socket = new Socket();
        currentSocket = socket;
        try {
            try { socket.setSendBufferSize(2 * 1024 * 1024); } catch (Exception ignored) {}
            socket.connect(new InetSocketAddress(device.hostIp, device.port), 12000);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setSoTimeout(120000);

            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), BUFFER));
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 64 * 1024));

            boolean hasFolders = false;
            for (FileItem item : files) if (item.name.contains("/")) { hasFolders = true; break; }
            out.writeUTF(hasFolders ? FOLDER_PROTOCOL : PROTOCOL);
            out.writeUTF(NetworkUtils.getDeviceName());
            out.writeInt(files.size());
            out.writeLong(totalBytes);
            out.flush();

            if (!in.readBoolean()) throw new RejectedException("الجهاز الآخر رفض الاستلام");

            byte[] buffer = new byte[BUFFER];
            for (FileItem item : files) {
                if (cancelled.get()) return;
                long size = item.size;
                out.writeUTF(item.name);
                out.writeLong(size);
                out.flush();

                long resumeFrom = in.readLong();
                if (resumeFrom < 0 || resumeFrom > size) resumeFrom = 0L;
                listener.onFileStarted(batch, item.name, size, resumeFrom);
                if (resumeFrom >= size) {
                    listener.onProgress(batch, item.name, size, size, 0);
                    finished.add(item.name);
                    listener.onFileFinished(batch, item.name);
                    continue;
                }

                long sent = resumeFrom;
                long lastBytes = sent;
                long lastTick = System.currentTimeMillis();
                try (InputStream raw = FileStoreUtils.openInput(context, item.uri);
                     BufferedInputStream fileIn = new BufferedInputStream(raw, BUFFER)) {
                    skipFully(fileIn, resumeFrom);
                    int read;
                    while (!cancelled.get() && sent < size &&
                            (read = fileIn.read(buffer, 0, (int) Math.min(buffer.length, size - sent))) != -1) {
                        out.write(buffer, 0, read);
                        sent += read;
                        long now = System.currentTimeMillis();
                        if (now - lastTick >= 300) {
                            long speed = (sent - lastBytes) * 1000 / Math.max(1, now - lastTick);
                            listener.onProgress(batch, item.name, sent, size, speed);
                            lastBytes = sent;
                            lastTick = now;
                        }
                    }
                }
                if (cancelled.get()) return;
                out.flush();
                if (sent != size) throw new IllegalStateException("الملف تغيّر أثناء الإرسال: " + item.name);
                listener.onProgress(batch, item.name, sent, size, 0);
                finished.add(item.name);
                listener.onFileFinished(batch, item.name);
            }
            out.flush();
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
            if (currentSocket == socket) currentSocket = null;
        }
    }

    public void cancel() {
        cancelled.set(true);
        closeSocketQuietly();
    }

    private static void skipFully(InputStream inputStream, long bytesToSkip) throws IOException {
        long skippedTotal = 0L;
        while (skippedTotal < bytesToSkip) {
            long skipped = inputStream.skip(bytesToSkip - skippedTotal);
            if (skipped <= 0) {
                if (inputStream.read() == -1) throw new IOException("تعذر تجاوز الجزء المرسل سابقاً");
                skipped = 1;
            }
            skippedTotal += skipped;
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private void closeSocketQuietly() {
        Socket s = currentSocket;
        try { if (s != null) s.close(); } catch (Exception ignored) {}
        currentSocket = null;
    }
}
