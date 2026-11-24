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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SSLSocket;

/**
 * مرسل الملفات.
 * V6: اتصال مشفر، رمز تحقق، أجهزة موثوقة، إرسال متواصل لكل الملفات بدون توقف بينها،
 * ومسارات متوازية للملفات الكبيرة. الملف يعتبر مكتملاً فقط بعد تأكيد المستلم.
 * عند الانقطاع يعيد الاتصال ويكمل من آخر بايت، ويرجع تلقائياً للبروتوكول القديم مع النسخ القديمة.
 */
public class SenderClient {
    public static final String PROTOCOL = "SATLINK_SHARE_PRO_V3_RESUME";
    public static final String FOLDER_PROTOCOL = "SATLINK_SHARE_PRO_V4_FOLDERS";
    public static final String ACK_PROTOCOL = "SATLINK_SHARE_PRO_V5_ACK";
    public static final String SECURE_PROTOCOL = "SATLINK_SHARE_PRO_V6_SECURE";
    public static final String RANGE_PROTOCOL = "SATLINK_SHARE_PRO_V6_RANGE";
    static final long PARALLEL_MIN = 32L * 1024 * 1024;
    static final int PARALLEL_STREAMS = 3;
    static final long BIG_FILE = Long.getLong("satlink.bigfile", 1024L * 1024 * 1024);
    static final int BIG_FILE_STREAMS = 6;

    /** عدد الأجزاء: 6 للملفات فوق 1 GB، و3 للأصغر. */
    static int streamsFor(long remaining) { return remaining >= BIG_FILE ? BIG_FILE_STREAMS : PARALLEL_STREAMS; }
    private static final int BUFFER = 512 * 1024;
    private static final int MAX_ATTEMPTS = 10;

    public interface Listener {
        void onLog(String message);
        void onBatchQueued(String batch, DeviceInfo target, List<FileItem> files);
        /** رمز التحقق الذي يجب أن يطابق الرمز الظاهر على جهاز الاستلام. */
        void onVerifyCode(String batch, String code);
        void onFileStarted(String batch, String name, long size, long resumeFrom);
        void onProgress(String batch, String name, long done, long size, long speed);
        void onFileFinished(String batch, String name);
        /** تقدم كل جزء (0..100) للملف المقسّم. */
        default void onParts(String batch, String name, int[] percents) {}
        void onBatchEnded(String batch, boolean completed, boolean cancelled, String error);
    }

    private final Context context;
    private final Listener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final Set<Socket> sockets = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    private volatile boolean busy;
    public volatile boolean parallel = true;

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

    public void cancel() {
        cancelled.set(true);
        for (Socket s : sockets) { try { s.close(); } catch (Exception ignored) {} }
    }

    private static final class RejectedException extends IOException {
        RejectedException(String message) { super(message); }
    }

    private static final class LegacyPeerException extends IOException {
        LegacyPeerException() { super("legacy receiver"); }
    }

    private void runBatch(String batch, DeviceInfo device, List<FileItem> files) {
        busy = true;
        cancelled.set(false);
        String error = null;
        boolean completed = false;
        try {
            for (FileItem item : files)
                if (item.size < 0) throw new IllegalArgumentException("حجم ملف غير معروف: " + item.name);
            int level = TlsIdentity.available(context) || System.getProperty("satlink.keystore") != null ? 0 : 1; // 0=V6 1=V5 2=V3/V4
            int attempt = 0;
            String batchId = TrustStore.hex(TrustStore.random(8));
            Set<String> finished = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
            while (!cancelled.get()) {
                List<FileItem> remaining = new ArrayList<>();
                for (FileItem item : files) if (!finished.contains(item.name)) remaining.add(item);
                if (remaining.isEmpty()) { completed = true; break; }
                attempt++;
                try {
                    if (level == 0) sendV6(batch, batchId, device, remaining, finished);
                    else sendLegacy(batch, device, remaining, finished, level == 1);
                    completed = !cancelled.get();
                    break;
                } catch (LegacyPeerException e) {
                    if (level >= 2) throw new IOException("الجهاز المستلم لا يستجيب");
                    level++;
                    attempt--;
                    if (level == 1) listener.onLog("الجهاز المستلم بنسخة قديمة؛ النقل بدون تشفير. يُفضّل تحديثه");
                } catch (RejectedException e) {
                    throw e;
                } catch (IOException e) {
                    if (cancelled.get()) break;
                    if (attempt >= MAX_ATTEMPTS) throw e;
                    listener.onLog("انقطع الاتصال، إعادة المحاولة " + attempt + " والإكمال من آخر نقطة...");
                    sleep(Math.min(8000, 1500L * attempt));
                }
            }
        } catch (Exception e) {
            error = e.getMessage() == null ? "تعذر الإرسال" : e.getMessage();
        } finally {
            boolean userCancelled = cancelled.get();
            for (Socket s : sockets) close(s);
            busy = false;
            listener.onBatchEnded(batch, completed, userCancelled && !completed, error);
        }
    }

    // ------------------------------------------------------------------ V6

    private SSLSocket openTls(DeviceInfo device, String header) throws IOException {
        Socket raw = new Socket();
        sockets.add(raw);
        try {
            try { raw.setSendBufferSize(2 * 1024 * 1024); } catch (Exception ignored) {}
            raw.connect(new InetSocketAddress(device.hostIp, device.port), 12000);
            raw.setTcpNoDelay(true);
            raw.setKeepAlive(true);
            raw.setSoTimeout(150000);
        } catch (IOException e) {
            sockets.remove(raw);
            try { raw.close(); } catch (Exception ignored) {}
            throw e;
        }
        try {
            DataOutputStream h = new DataOutputStream(raw.getOutputStream());
            h.writeUTF(header);
            h.flush();
            SSLSocket tls = TlsIdentity.wrapClient(raw, device.hostIp, device.port);
            sockets.add(tls);
            return tls;
        } catch (java.net.SocketTimeoutException e) {
            sockets.remove(raw);
            try { raw.close(); } catch (Exception ignored) {}
            throw e;
        } catch (Exception e) {
            sockets.remove(raw);
            try { raw.close(); } catch (Exception ignored) {}
            // المستلم القديم يغلق الاتصال عند رأس لا يعرفه فتفشل مصافحة التشفير.
            if (SECURE_PROTOCOL.equals(header)) throw new LegacyPeerException();
            throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage());
        }
    }

    private void close(Socket s) {
        if (s == null) return;
        sockets.remove(s);
        try { s.close(); } catch (Exception ignored) {}
    }

    private void sendV6(String batch, String batchId, DeviceInfo device, List<FileItem> files, Set<String> finished) throws IOException {
        SSLSocket s = openTls(device, SECURE_PROTOCOL);
        Thread ackThread = null;
        try {
            byte[] der;
            try { der = TlsIdentity.peerCertificate(s); } catch (Exception e) { throw new IOException("شهادة غير صالحة"); }
            final String fingerprint = TrustStore.fingerprint(der);
            byte[] nonce = TrustStore.random(16);
            String token = TrustStore.tokenForReceiver(context, fingerprint);
            if (token.isEmpty()) listener.onVerifyCode(batch, TrustStore.verifyCode(der, nonce));

            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), BUFFER));
            final DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 64 * 1024));
            long total = 0;
            for (FileItem f : files) total += f.size;
            out.writeUTF(NetworkUtils.getDeviceName());
            out.writeUTF(TrustStore.deviceId(context));
            ReceiverServer.writeBlob(out, nonce);
            out.writeUTF(token);
            out.writeUTF(batchId);
            out.writeInt(files.size());
            out.writeLong(total);
            for (FileItem f : files) { out.writeUTF(f.name); out.writeLong(f.size); }
            out.flush();

            int decision = in.readByte();
            if (decision == ReceiverServer.DECISION_TRUST)
                TrustStore.rememberReceiver(context, fingerprint, device.name, in.readUTF());
            final byte[] key = ReceiverServer.readBlob(in, 64);
            if (decision == ReceiverServer.DECISION_REJECT) throw new RejectedException("الجهاز الآخر رفض الاستلام");
            long[] offsets = new long[files.size()];
            for (int i = 0; i < offsets.length; i++) offsets[i] = in.readLong();

            final Map<Integer, Long> acked = new ConcurrentHashMap<>();
            final IOException[] ackError = new IOException[1];
            final List<FileItem> list = files;
            ackThread = new Thread(() -> {
                try {
                    while (true) {
                        int index = in.readInt();
                        if (index == -1) { acked.put(-1, 0L); return; }
                        long saved = in.readLong();
                        acked.put(index, saved);
                        if (index >= 0 && index < list.size() && saved == list.get(index).size) {
                            FileItem f = list.get(index);
                            finished.add(f.name);
                            listener.onProgress(batch, f.name, f.size, f.size, 0);
                            listener.onFileFinished(batch, f.name);
                        }
                    }
                } catch (IOException e) { ackError[0] = e; }
            }, "satlink-acks");
            ackThread.start();

            byte[] buffer = new byte[BUFFER];
            for (int index = 0; index < files.size(); index++) {
                if (cancelled.get()) return;
                if (ackError[0] != null) throw ackError[0];
                FileItem item = files.get(index);
                long size = item.size;
                long offset = Math.min(Math.max(0, offsets[index]), size);
                listener.onFileStarted(batch, item.name, size, offset);
                long remaining = size - offset;
                if (parallel && remaining >= PARALLEL_MIN) {
                    int streams = streamsFor(remaining);
                    out.writeInt(index); out.writeByte(1); out.writeInt(streams); out.flush();
                    sendParallel(batch, device, fingerprint, key, index, item, offset, streams);
                } else {
                    out.writeInt(index); out.writeByte(0);
                    streamFile(batch, item, offset, out, buffer);
                    out.flush();
                }
            }
            out.writeInt(-1);
            out.flush();
            try { ackThread.join(300000); } catch (InterruptedException ignored) {}
            if (cancelled.get()) return;
            for (int i = 0; i < files.size(); i++) {
                Long saved = acked.get(i);
                if (saved == null || saved != files.get(i).size) {
                    if (ackError[0] != null) throw ackError[0];
                    throw new IOException("لم يؤكد المستلم: " + files.get(i).name);
                }
            }
        } finally {
            close(s);
            if (ackThread != null) ackThread.interrupt();
        }
    }

    private void streamFile(String batch, FileItem item, long offset, DataOutputStream out, byte[] buffer) throws IOException {
        long size = item.size, sent = offset, lastBytes = offset, lastTick = System.currentTimeMillis();
        if (sent >= size) return;
        try (InputStream raw = FileStoreUtils.openInputAt(context, item.uri, offset);
             BufferedInputStream fileIn = new BufferedInputStream(raw, BUFFER)) {
            int read;
            while (!cancelled.get() && sent < size &&
                    (read = fileIn.read(buffer, 0, (int) Math.min(buffer.length, size - sent))) != -1) {
                out.write(buffer, 0, read);
                sent += read;
                long now = System.currentTimeMillis();
                if (now - lastTick >= 300) {
                    // يبقى أقل من 100% حتى يؤكد المستلم الحفظ.
                    listener.onProgress(batch, item.name, Math.min(sent, size - 1), size, (sent - lastBytes) * 1000 / Math.max(1, now - lastTick));
                    lastBytes = sent;
                    lastTick = now;
                }
            }
        }
        if (cancelled.get()) throw new IOException("تم الإلغاء");
        if (sent != size) throw new IOException("الملف تغيّر أثناء الإرسال: " + item.name);
    }

    private void sendParallel(String batch, DeviceInfo device, String fingerprint, byte[] key, int index, FileItem item, long offset, int streams) throws IOException {
        long remaining = item.size - offset;
        long chunk = (remaining + streams - 1) / streams;
        final AtomicLong progress = new AtomicLong();
        final long[] partDone = new long[streams];
        final long[] partLen = new long[streams];
        final IOException[] error = new IOException[1];
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < streams; i++) {
            final int part = i;
            final long start = offset + i * chunk;
            final long length = Math.min(item.size, start + chunk) - start;
            partLen[i] = length;
            Thread t = new Thread(() -> {
                SSLSocket r = null;
                try {
                    r = openTls(device, RANGE_PROTOCOL);
                    try {
                        if (!TrustStore.fingerprint(TlsIdentity.peerCertificate(r)).equals(fingerprint)) throw new IOException("شهادة مختلفة");
                    } catch (IOException e) { throw e; } catch (Exception e) { throw new IOException(e.getMessage()); }
                    DataOutputStream out = new DataOutputStream(new BufferedOutputStream(r.getOutputStream(), BUFFER));
                    DataInputStream in = new DataInputStream(r.getInputStream());
                    ReceiverServer.writeBlob(out, key);
                    out.writeInt(index); out.writeLong(start); out.writeLong(length);
                    byte[] buffer = new byte[BUFFER];
                    long left = length;
                    try (InputStream raw = FileStoreUtils.openInputAt(context, item.uri, start);
                         BufferedInputStream fileIn = new BufferedInputStream(raw, BUFFER)) {
                        while (left > 0 && !cancelled.get()) {
                            int n = fileIn.read(buffer, 0, (int) Math.min(buffer.length, left));
                            if (n == -1) throw new IOException("الملف أقصر من حجمه");
                            out.write(buffer, 0, n);
                            left -= n;
                            progress.addAndGet(n);
                            synchronized (partDone) { partDone[part] += n; }
                        }
                    }
                    out.flush();
                    if (cancelled.get()) return;
                    if (in.readLong() != length) throw new IOException("range not confirmed");
                } catch (IOException e) {
                    synchronized (error) { if (error[0] == null) error[0] = e; }
                } finally {
                    close(r);
                }
            }, "satlink-range-" + i);
            threads.add(t);
            t.start();
        }
        long lastBytes = 0, lastTick = System.currentTimeMillis();
        for (Thread t : threads) {
            while (t.isAlive()) {
                try { t.join(300); } catch (InterruptedException ignored) {}
                long now = System.currentTimeMillis(), got = progress.get();
                if (now - lastTick >= 300) {
                    listener.onProgress(batch, item.name, Math.min(offset + got, item.size - 1), item.size, (got - lastBytes) * 1000 / Math.max(1, now - lastTick));
                    int[] pct = new int[streams];
                    synchronized (partDone) { for (int p = 0; p < streams; p++) pct[p] = (int) (partLen[p] == 0 ? 100 : partDone[p] * 100 / partLen[p]); }
                    listener.onParts(batch, item.name, pct);
                    lastBytes = got; lastTick = now;
                }
            }
        }
        if (error[0] != null) throw error[0];
        if (cancelled.get()) throw new IOException("تم الإلغاء");
    }

    // ------------------------------------------------------------------ V3/V4/V5

    private void sendLegacy(String batch, DeviceInfo device, List<FileItem> files, Set<String> finished, boolean ack) throws IOException {
        Socket socket = new Socket();
        sockets.add(socket);
        try {
            try { socket.setSendBufferSize(2 * 1024 * 1024); } catch (Exception ignored) {}
            socket.connect(new InetSocketAddress(device.hostIp, device.port), 12000);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setSoTimeout(120000);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), BUFFER));
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 64 * 1024));
            boolean hasFolders = false;
            long totalBytes = 0;
            for (FileItem item : files) { if (item.name.contains("/")) hasFolders = true; totalBytes += item.size; }
            boolean accepted;
            try {
                out.writeUTF(ack ? ACK_PROTOCOL : hasFolders ? FOLDER_PROTOCOL : PROTOCOL);
                out.writeUTF(NetworkUtils.getDeviceName());
                out.writeInt(files.size());
                out.writeLong(totalBytes);
                out.flush();
                accepted = in.readBoolean();
            } catch (IOException e) {
                if (ack && !(e instanceof java.net.SocketTimeoutException)) throw new LegacyPeerException();
                throw e;
            }
            if (!accepted) throw new RejectedException("الجهاز الآخر رفض الاستلام");
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
                if (resumeFrom < size) streamFile(batch, item, resumeFrom, out, buffer);
                out.flush();
                if (ack && in.readLong() != size) throw new IOException("المستلم لم يحفظ الملف كاملاً: " + item.name);
                listener.onProgress(batch, item.name, size, size, 0);
                finished.add(item.name);
                listener.onFileFinished(batch, item.name);
            }
        } finally {
            close(socket);
        }
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
}
