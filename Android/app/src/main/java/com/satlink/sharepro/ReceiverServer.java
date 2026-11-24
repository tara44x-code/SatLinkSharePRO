package com.satlink.sharepro;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * خادم الاستلام على المنفذ 8988. يدعم:
 * V6 (مشفر، طلب موافقة برمز تحقق، أجهزة موثوقة، إرسال متواصل للملفات، ومسارات متوازية للملفات الكبيرة)
 * و V3/V4/V5 للنسخ القديمة.
 */
public class ReceiverServer {
    public static final int DECISION_REJECT = 0, DECISION_ACCEPT = 1, DECISION_TRUST = 2;

    /** طلب استلام ينتظر قرار المستخدم. code = null للنسخ القديمة غير المشفرة. */
    public static final class Request {
        public final String id, sender, deviceId, code;
        public final int count;
        public final long total;
        public final boolean legacy;
        public final List<String> files;
        Request(String id, String sender, String deviceId, int count, long total, String code, boolean legacy, List<String> files) {
            this.id = id; this.sender = sender; this.deviceId = deviceId; this.count = count; this.total = total;
            this.code = code; this.legacy = legacy; this.files = files;
        }
    }

    public interface Listener {
        void onLog(String message);
        /** يرجع DECISION_*؛ يُستدعى من خيط الشبكة ويمكن أن ينتظر المستخدم. */
        int onIncoming(Request request);
        void onSessionStarted(String session, String sender, int count, long totalBytes);
        void onFileStarted(String session, String sender, String name, long size, long resumeFrom);
        void onProgress(String session, String name, long done, long size, long speed);
        void onFileSaved(String session, String name, long size, Uri uri);
        /** تقدم كل جزء (0..100) للملف المقسّم، مرتبة حسب موضعها بالملف. */
        default void onParts(String session, String name, int[] percents) {}
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
    private final Map<String, V6Session> v6Sessions = new ConcurrentHashMap<>();
    /** محاولات سابقة لنفس الدفعة (نفس المرسل): تُغلق قبل حساب نقاط الإكمال حتى لا يكتب اتصالان نفس الملف. */
    private final Map<String, List<Object[]>> batchAttempts = new HashMap<>();
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
                server.bind(new InetSocketAddress(NetworkUtils.PORT), 32);
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
        Socket tracked = socket;
        try {
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setSoTimeout(120000);
            try { socket.setReceiveBufferSize(2 * 1024 * 1024); } catch (Exception ignored) {}
            // قراءة الرأس بدون تخزين مؤقت حتى لا نستهلك بايتات مصافحة التشفير التي تليه.
            String header = new DataInputStream(socket.getInputStream()).readUTF();
            if ("SATLINK_SHARE_PRO_PING_V1".equals(header)) {
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                out.writeUTF(NetworkUtils.getDeviceName());
                out.writeInt(NetworkUtils.PORT);
                out.flush();
                return;
            }
            if (SenderClient.SECURE_PROTOCOL.equals(header) || SenderClient.RANGE_PROTOCOL.equals(header)) {
                javax.net.ssl.SSLSocket tls = TlsIdentity.wrapServer(context, socket);
                tracked = tls;
                active.add(tls);
                if (SenderClient.SECURE_PROTOCOL.equals(header)) handleV6(tls);
                else handleRange(tls);
                return;
            }
            active.add(socket);
            handleLegacy(socket, header);
        } catch (Exception e) {
            // تُعالج داخل كل نوع اتصال.
        } finally {
            active.remove(socket); active.remove(tracked);
            cancelled.remove(socket); cancelled.remove(tracked);
            try { tracked.close(); } catch (Exception ignored) {}
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    // ------------------------------------------------------------------ النسخ القديمة V3/V4/V5

    private void handleLegacy(Socket socket, String header) throws Exception {
        String session = System.currentTimeMillis() + "-" + sessions.incrementAndGet();
        boolean started = false, completed = false;
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), BUFFER));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 64 * 1024));
            boolean ackProtocol = SenderClient.ACK_PROTOCOL.equals(header);
            boolean folderProtocol = ackProtocol || SenderClient.FOLDER_PROTOCOL.equals(header);
            if (!folderProtocol && !SenderClient.PROTOCOL.equals(header)) throw new IllegalStateException("اتصال غير معروف");
            String senderName = in.readUTF();
            int count = in.readInt();
            long totalBytes = in.readLong();
            if (count < 0 || count > 10000 || totalBytes < 0) throw new IllegalStateException("بيانات إرسال غير صالحة");

            int decision = listener.onIncoming(new Request(session, senderName, "", count, totalBytes, null, true, new ArrayList<>()));
            out.writeBoolean(decision != DECISION_REJECT);
            out.flush();
            if (decision == DECISION_REJECT) return;
            started = true;
            listener.onSessionStarted(session, senderName, count, totalBytes);

            byte[] buffer = new byte[BUFFER];
            for (int i = 0; i < count && running.get(); i++) {
                String rawName = in.readUTF();
                String fileName = folderProtocol ? FileStoreUtils.sanitizeRelativePath(rawName) : FileStoreUtils.sanitizeFileName(rawName);
                long total = in.readLong();
                if (total < 0) throw new IllegalStateException("حجم ملف غير معروف");
                long resumeFrom = FileStoreUtils.getPartialSize(context, fileName, total);
                if (resumeFrom < 0 || resumeFrom > total) { FileStoreUtils.resetPartial(context, fileName, total); resumeFrom = 0L; }
                String recentKey = senderName + "\u0000" + fileName + "\u0000" + total;
                Uri alreadySaved = ackProtocol && resumeFrom == 0 ? recentlySaved(recentKey) : null;
                if (alreadySaved != null) {
                    out.writeLong(total); out.writeLong(total); out.flush();
                    listener.onFileStarted(session, senderName, fileName, total, total);
                    listener.onFileSaved(session, fileName, total, alreadySaved);
                    continue;
                }
                out.writeLong(resumeFrom);
                out.flush();
                listener.onFileStarted(session, senderName, fileName, total, resumeFrom);
                long received = receiveInline(in, session, fileName, total, resumeFrom, buffer);
                if (received < total) throw new java.io.EOFException("انقطع الاتصال أثناء استلام " + fileName);
                Uri saved = FileStoreUtils.copyPartialToDownloads(context, fileName, total);
                FileStoreUtils.deletePartial(context, fileName, total);
                rememberSaved(recentKey, saved);
                listener.onFileSaved(session, fileName, total, saved);
                if (ackProtocol) { out.writeLong(total); out.flush(); }
            }
            completed = true;
        } catch (Exception e) {
            if (started && !cancelled.contains(socket) && running.get())
                listener.onLog("انقطع الاستلام؛ الجزء المستلم محفوظ ويكمل عند إعادة الإرسال");
        } finally {
            if (started) listener.onSessionEnded(session, completed, cancelled.contains(socket));
        }
    }

    private long receiveInline(DataInputStream in, String session, String fileName, long total, long offset, byte[] buffer) throws IOException {
        long received = offset;
        long lastBytes = received;
        long lastTick = System.currentTimeMillis();
        if (received >= total) return received;
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
        listener.onProgress(session, fileName, received, total, 0);
        return received;
    }

    // ------------------------------------------------------------------ V6

    private static final class ParallelFile {
        final String name; final long size; final long origin; final int expected;
        final FileStoreUtils.RandomWriter writer;
        final TreeMap<Long, long[]> ranges = new TreeMap<>();
        int finished; String failed; long lastPersist;
        ParallelFile(String name, long size, long origin, int expected, FileStoreUtils.RandomWriter writer) {
            this.name = name; this.size = size; this.origin = origin; this.expected = expected; this.writer = writer;
        }
        synchronized long safeOffset() {
            long safe = origin;
            for (Map.Entry<Long, long[]> e : ranges.entrySet()) {
                long start = e.getKey(), end = e.getValue()[0], written = e.getValue()[1];
                if (start > safe) break;
                safe = Math.max(safe, start + written);
                if (start + written < end) break;
            }
            return safe;
        }
        synchronized int[] percents() {
            int[] out = new int[expected];
            int i = 0;
            for (Map.Entry<Long, long[]> e : ranges.entrySet()) {
                if (i >= expected) break;
                long len = e.getValue()[0] - e.getKey();
                out[i++] = (int) (len <= 0 ? 100 : Math.min(100, e.getValue()[1] * 100 / len));
            }
            return out;
        }

        synchronized long received() {
            long sum = origin;
            for (long[] r : ranges.values()) sum += r[1];
            return sum;
        }
    }

    private static final class V6Session {
        final Map<Integer, ParallelFile> files = new HashMap<>();
    }

    private void handleV6(javax.net.ssl.SSLSocket s) throws Exception {
        String session = System.currentTimeMillis() + "-" + sessions.incrementAndGet();
        boolean started = false, completed = false;
        String keyHex = null;
        Object[] claimed = null;
        V6Session state = new V6Session();
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), BUFFER));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 64 * 1024));
            String senderName = in.readUTF();
            String senderId = in.readUTF();
            byte[] nonce = readBlob(in, 64);
            String token = in.readUTF();
            String batch = in.readUTF();
            int count = in.readInt();
            long totalBytes = in.readLong();
            if (count < 0 || count > 100000 || totalBytes < 0) throw new IllegalStateException("بيانات إرسال غير صالحة");
            String[] names = new String[count];
            long[] sizes = new long[count];
            ArrayList<String> preview = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                names[i] = FileStoreUtils.sanitizeRelativePath(in.readUTF());
                sizes[i] = in.readLong();
                if (sizes[i] < 0) throw new IllegalStateException("حجم ملف غير صالح");
                if (preview.size() < 5) preview.add(names[i]);
            }
            String claim = senderId + "|" + batch;
            java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
            claimed = new Object[]{claim, s, finished};
            List<Object[]> earlier;
            synchronized (batchAttempts) {
                List<Object[]> list = batchAttempts.get(claim);
                if (list == null) { list = new ArrayList<>(); batchAttempts.put(claim, list); }
                earlier = new ArrayList<>(list);
                list.add(new Object[]{s, finished});
            }
            for (Object[] e : earlier) { try { ((Socket) e[0]).close(); } catch (Exception ignored) {} }
            for (Object[] e : earlier) {
                try { ((java.util.concurrent.CountDownLatch) e[1]).await(30, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException ignored) {}
            }
            String code = TrustStore.verifyCode(TlsIdentity.certificate(context), nonce);
            int decision = TrustStore.isTrustedSender(context, senderId, token) ? DECISION_ACCEPT
                    : listener.onIncoming(new Request(session, senderName, senderId, count, totalBytes, code, false, preview));
            out.writeByte(decision);
            if (decision == DECISION_REJECT) { writeBlob(out, new byte[0]); out.flush(); return; }
            if (decision == DECISION_TRUST) out.writeUTF(TrustStore.trustSender(context, senderId, senderName));
            byte[] key = TrustStore.random(16);
            keyHex = TrustStore.hex(key);
            v6Sessions.put(keyHex, state);
            writeBlob(out, key);

            long[] offsets = new long[count];
            for (int i = 0; i < count; i++) {
                String recentKey = senderId + "\u0000" + batch + "\u0000" + names[i] + "\u0000" + sizes[i];
                long off = FileStoreUtils.getPartialSize(context, names[i], sizes[i]);
                if (off == 0 && recentlySaved(recentKey) != null) off = sizes[i];
                if (off < 0 || off > sizes[i]) { FileStoreUtils.resetPartial(context, names[i], sizes[i]); off = 0; }
                offsets[i] = off;
                out.writeLong(off);
            }
            out.flush();
            started = true;
            listener.onSessionStarted(session, senderName, count, totalBytes);

            byte[] buffer = new byte[BUFFER];
            while (true) {
                int index = in.readInt();
                if (index == -1) break;
                if (index < 0 || index >= count) throw new IllegalStateException("bad index");
                int mode = in.readByte();
                String name = names[index];
                long size = sizes[index], offset = offsets[index];
                String recentKey = senderId + "\u0000" + batch + "\u0000" + name + "\u0000" + size;
                Uri previous = recentlySaved(recentKey);
                if (offset >= size && previous != null) {
                    // وصل وانحفظ بهذه الدفعة قبل الانقطاع؛ نؤكد فقط بدون نسخة ثانية.
                    listener.onFileStarted(session, senderName, name, size, size);
                    listener.onFileSaved(session, name, size, previous);
                    out.writeInt(index); out.writeLong(size); out.flush();
                    continue;
                }
                listener.onFileStarted(session, senderName, name, size, offset);
                if (mode == 0) {
                    long received = receiveInline(in, session, name, size, offset, buffer);
                    if (received < size) throw new java.io.EOFException("انقطع الاتصال أثناء استلام " + name);
                } else {
                    int streams = in.readInt();
                    if (streams < 1 || streams > 8) throw new IllegalStateException("bad streams");
                    ParallelFile pf = new ParallelFile(name, size, offset, streams, FileStoreUtils.openPartialRandom(context, name, size));
                    try {
                        synchronized (state) { state.files.put(index, pf); state.notifyAll(); }
                        long lastTick = System.currentTimeMillis(), lastBytes = offset;
                        synchronized (pf) {
                            while (pf.finished < pf.expected && pf.failed == null) {
                                pf.wait(300);
                                long now = System.currentTimeMillis();
                                long got = pf.received();
                                if (now - lastTick >= 300) {
                                    listener.onProgress(session, name, got, size, (got - lastBytes) * 1000 / Math.max(1, now - lastTick));
                                    listener.onParts(session, name, pf.percents());
                                    lastTick = now; lastBytes = got;
                                }
                            }
                            if (pf.failed != null) throw new IOException(pf.failed);
                        }
                    } finally {
                        try { pf.writer.close(); } catch (Exception ignored) {}
                    }
                    listener.onProgress(session, name, size, size, 0);
                }
                Uri saved = FileStoreUtils.copyPartialToDownloads(context, name, size);
                FileStoreUtils.deletePartial(context, name, size);
                rememberSaved(recentKey, saved);
                listener.onFileSaved(session, name, size, saved);
                out.writeInt(index);
                out.writeLong(size);
                out.flush();
            }
            out.writeInt(-1);
            out.flush();
            completed = true;
        } catch (Exception e) {
            synchronized (state) {
                for (ParallelFile pf : state.files.values()) synchronized (pf) {
                    if (pf.failed == null) pf.failed = "session closed";
                    pf.notifyAll();
                }
            }
            if (started && !cancelled.contains(s) && running.get())
                listener.onLog("انقطع الاستلام؛ الجزء المستلم محفوظ ويكمل عند إعادة الإرسال");
        } finally {
            if (keyHex != null) v6Sessions.remove(keyHex);
            if (claimed != null) {
                ((java.util.concurrent.CountDownLatch) claimed[2]).countDown();
                synchronized (batchAttempts) {
                    List<Object[]> list = batchAttempts.get((String) claimed[0]);
                    if (list != null) {
                        java.util.Iterator<Object[]> it = list.iterator();
                        while (it.hasNext()) if (it.next()[0] == s) it.remove();
                        if (list.isEmpty()) batchAttempts.remove((String) claimed[0]);
                    }
                }
            }
            if (started) listener.onSessionEnded(session, completed, cancelled.contains(s));
        }
    }

    private void handleRange(javax.net.ssl.SSLSocket s) throws Exception {
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), BUFFER));
        DataOutputStream out = new DataOutputStream(s.getOutputStream());
        String keyHex = TrustStore.hex(readBlob(in, 64));
        int index = in.readInt();
        long start = in.readLong(), length = in.readLong();
        V6Session state = v6Sessions.get(keyHex);
        if (state == null) throw new IllegalStateException("جلسة غير معروفة");
        ParallelFile pf;
        long deadline = System.currentTimeMillis() + 60000;
        synchronized (state) {
            while ((pf = state.files.get(index)) == null) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) throw new IllegalStateException("range setup timeout");
                state.wait(left);
            }
        }
        if (start < 0 || length < 0 || start + length > pf.size) throw new IllegalStateException("bad range");
        synchronized (pf) { pf.ranges.put(start, new long[]{start + length, 0}); }
        long written = 0;
        byte[] buffer = new byte[BUFFER];
        try {
            while (written < length) {
                int n = in.read(buffer, 0, (int) Math.min(buffer.length, length - written));
                if (n == -1) throw new java.io.EOFException("range closed");
                pf.writer.write(buffer, 0, n, start + written);
                written += n;
                persist(pf, start, written, false);
            }
            persist(pf, start, written, true);
            out.writeLong(written);
            out.flush();
            synchronized (pf) { pf.finished++; pf.notifyAll(); }
        } catch (Exception e) {
            persist(pf, start, written, true);
            synchronized (pf) { if (pf.failed == null) pf.failed = "انقطع أحد مسارات النقل"; pf.notifyAll(); }
            throw e;
        }
    }

    private void persist(ParallelFile pf, long start, long written, boolean force) {
        long safe;
        synchronized (pf) {
            pf.ranges.get(start)[1] = written;
            long now = System.currentTimeMillis();
            if (!force && now - pf.lastPersist < 1000) return;
            pf.lastPersist = now;
            safe = pf.safeOffset();
        }
        FileStoreUtils.setSafeOffset(context, pf.name, pf.size, safe);
    }

    static byte[] readBlob(DataInputStream in, int limit) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > limit) throw new IOException("bad blob");
        byte[] b = new byte[n];
        in.readFully(b);
        return b;
    }

    static void writeBlob(DataOutputStream out, byte[] b) throws IOException {
        out.writeInt(b.length);
        out.write(b);
    }

    // ------------------------------------------------------------------ منع التكرار عند ضياع التأكيد

    private static final long RECENT_MS = 15 * 60 * 1000L;
    private final HashMap<String, Object[]> recent = new HashMap<>();

    private synchronized Uri recentlySaved(String key) {
        long now = System.currentTimeMillis();
        java.util.Iterator<Map.Entry<String, Object[]>> it = recent.entrySet().iterator();
        while (it.hasNext()) if (now - (Long) it.next().getValue()[1] > RECENT_MS) it.remove();
        Object[] v = recent.get(key);
        return v == null ? null : (Uri) v[0];
    }

    private synchronized void rememberSaved(String key, Uri uri) {
        if (uri != null) recent.put(key, new Object[]{uri, System.currentTimeMillis()});
    }

    private void closeServerQuietly() {
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        serverSocket = null;
    }
}
