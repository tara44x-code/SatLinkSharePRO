package com.satlink.sharepro;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * سجل الملفات المرسلة والمستلمة. يُحفظ في SharedPreferences حتى يظهر بعد إعادة فتح التطبيق،
 * وتُبلّغ الواجهة بكل تحديث (مع تقليل عدد التحديثات أثناء النقل).
 */
public final class TransferLog {
    public static final String SEND = "send";
    public static final String RECEIVE = "receive";

    public static final String STATE_CONNECTING = "connecting";
    public static final String STATE_RUNNING = "running";
    public static final String STATE_DONE = "done";
    public static final String STATE_CANCELLED = "cancelled";
    public static final String STATE_FAILED = "failed";
    public static final String STATE_PAUSED = "paused";
    public static final String STATE_REJECTED = "rejected";

    private static final int MAX_ENTRIES = 300;
    private static final String PREFS = "satlink_transfer_log";
    private static final String KEY = "entries_v1";

    public static final class Entry {
        public final String id;
        public final String direction;
        public final String name;
        public final String peer;
        public long size;
        public long done;
        public long speed;
        public String state;
        public String uri;
        public final long time;
        public long endTime;
        /** للإرسال: رابط الملف الأصلي وعنوان الجهاز، حتى يمكن الإكمال بضغطة. */
        public String source;
        /** تقدم كل جزء عند تقسيم الملف (null إذا ما انقسم). */
        public volatile int[] parts;
        public String targetIp;
        public int targetPort;

        Entry(String id, String direction, String name, String peer, long size, long time) {
            this.id = id; this.direction = direction; this.name = name; this.peer = peer;
            this.size = size; this.time = time; this.state = STATE_RUNNING;
        }

        public boolean isActive() {
            return STATE_RUNNING.equals(state) || STATE_CONNECTING.equals(state);
        }

        public int percent() {
            if (STATE_DONE.equals(state)) return 100;
            if (size <= 0) return 0;
            return (int) Math.max(0, Math.min(100, done * 100L / size));
        }
    }

    public interface Listener {
        void onTransferChanged(Entry entry, boolean isNew);
    }

    private static final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static WeakReference<Listener> listener = new WeakReference<>(null);
    private static boolean loaded;
    private static long lastSave;

    private TransferLog() {}

    public static synchronized void setListener(Listener l) { listener = new WeakReference<>(l); }

    public static synchronized void clearListener(Listener l) { if (listener.get() == l) listener.clear(); }

    public static synchronized List<Entry> list(Context context, String direction) {
        load(context);
        ArrayList<Entry> result = new ArrayList<>();
        for (Entry e : entries.values()) if (e.direction.equals(direction)) result.add(0, e);
        return result;
    }

    public static synchronized boolean hasActive(String direction) {
        for (Entry e : entries.values()) if (e.direction.equals(direction) && e.isActive()) return true;
        return false;
    }

    public static synchronized Entry start(Context context, String direction, String session, String name, String peer, long size, String state) {
        load(context);
        String id = direction + "|" + session + "|" + name;
        Entry e = entries.get(id);
        if (e != null && (STATE_DONE.equals(e.state) || STATE_CANCELLED.equals(e.state))) {
            // ملف جديد بنفس الاسم: نحتفظ بالسطر القديم في السجل ونبدأ سطراً جديداً.
            archive(id, e);
            e = null;
        }
        boolean isNew = e == null;
        if (isNew) {
            e = new Entry(id, direction, name, peer == null ? "" : peer, size, System.currentTimeMillis());
            entries.put(id, e);
            trim();
        }
        e.size = size;
        e.state = state;
        e.speed = 0;
        save(context, true);
        notify(e, isNew);
        return e;
    }

    public static synchronized void progress(Context context, String direction, String session, String name, long done, long size, long speed) {
        Entry e = entries.get(direction + "|" + session + "|" + name);
        if (e == null) return;
        e.done = done; e.size = size; e.speed = speed; e.state = STATE_RUNNING;
        save(context, false);
        notify(e, false);
    }

    public static synchronized void finish(Context context, String direction, String session, String name, String state, String uri) {
        Entry e = entries.get(direction + "|" + session + "|" + name);
        if (e == null) return;
        e.state = state;
        e.speed = 0;
        if (STATE_DONE.equals(state)) e.done = e.size;
        if (uri != null) e.uri = uri;
        e.endTime = System.currentTimeMillis();
        save(context, true);
        notify(e, false);
    }

    /** يُعلَّم كل نقل لم يكتمل في جلسة معينة (مثلاً بعد الإلغاء أو انقطاع الاتصال). */
    public static synchronized void endSession(Context context, String direction, String session, String state) {
        String prefix = direction + "|" + session + "|";
        for (Entry e : entries.values()) {
            if (e.id.startsWith(prefix) && e.isActive()) {
                e.state = state; e.speed = 0; e.endTime = System.currentTimeMillis();
                notify(e, false);
            }
        }
        save(context, true);
    }

    public static synchronized void setSource(Context context, String direction, String session, String name, String source, String ip, int port) {
        Entry e = entries.get(direction + "|" + session + "|" + name);
        if (e == null) return;
        e.source = source; e.targetIp = ip; e.targetPort = port;
        save(context, true);
    }

    public static synchronized Entry get(String id) { return entries.get(id); }

    public static synchronized void parts(Context context, String direction, String session, String name, int[] percents) {
        Entry e = entries.get(direction + "|" + session + "|" + name);
        if (e == null) return;
        e.parts = percents;
        notify(e, false);
    }

    public static synchronized void remove(Context context, String id) {
        entries.remove(id);
        save(context, true);
    }

    public static synchronized void clear(Context context, String direction) {
        load(context);
        ArrayList<String> remove = new ArrayList<>();
        for (Entry e : entries.values()) if (e.direction.equals(direction) && !e.isActive()) remove.add(e.id);
        for (String id : remove) entries.remove(id);
        save(context, true);
    }

    private static void archive(String id, Entry old) {
        Entry copy = new Entry(id + "#" + old.time, old.direction, old.name, old.peer, old.size, old.time);
        copy.done = old.done; copy.state = old.state; copy.uri = old.uri; copy.endTime = old.endTime;
        copy.source = old.source; copy.targetIp = old.targetIp; copy.targetPort = old.targetPort;
        LinkedHashMap<String, Entry> rebuilt = new LinkedHashMap<>();
        for (java.util.Map.Entry<String, Entry> m : entries.entrySet()) {
            if (m.getKey().equals(id)) rebuilt.put(copy.id, copy); else rebuilt.put(m.getKey(), m.getValue());
        }
        entries.clear();
        entries.putAll(rebuilt);
    }

    private static void notify(Entry e, boolean isNew) {
        Listener l = listener.get();
        if (l != null) main.post(() -> l.onTransferChanged(e, isNew));
    }

    private static void trim() {
        while (entries.size() > MAX_ENTRIES) {
            String first = entries.keySet().iterator().next();
            entries.remove(first);
        }
    }

    private static void load(Context context) {
        if (loaded) return;
        loaded = true;
        try {
            SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Entry e = new Entry(o.getString("id"), o.getString("dir"), o.getString("name"), o.optString("peer"), o.optLong("size"), o.optLong("time"));
                e.done = o.optLong("done");
                e.state = o.optString("state", STATE_DONE);
                // أي نقل كان شغالاً عند إغلاق التطبيق صار متوقفاً؛ إعادة الإرسال تكمل من نفس النقطة.
                if (e.isActive()) e.state = STATE_PAUSED;
                e.uri = o.optString("uri", null);
                e.endTime = o.optLong("end");
                e.source = o.optString("src", null);
                e.targetIp = o.optString("ip", null);
                e.targetPort = (int) o.optLong("port");
                entries.put(e.id, e);
            }
        } catch (Exception ignored) {}
    }

    private static void save(Context context, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastSave < 2000) return;
        lastSave = now;
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : entries.values()) {
                JSONObject o = new JSONObject();
                o.put("id", e.id); o.put("dir", e.direction); o.put("name", e.name); o.put("peer", e.peer);
                o.put("size", e.size); o.put("done", e.done); o.put("state", e.state); o.put("time", e.time);
                if (e.uri != null) o.put("uri", e.uri);
                if (e.endTime > 0) o.put("end", e.endTime);
                if (e.source != null) o.put("src", e.source);
                if (e.targetIp != null) { o.put("ip", e.targetIp); o.put("port", (long) e.targetPort); }
                arr.put(o);
            }
            context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignored) {}
    }
}
