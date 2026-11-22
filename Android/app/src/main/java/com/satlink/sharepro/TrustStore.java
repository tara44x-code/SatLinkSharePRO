package com.satlink.sharepro;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * الأجهزة الموثوقة: المستلم يحفظ «رمز ثقة» لكل مرسل وافق عليه دائماً،
 * والمرسل يحفظ بصمة شهادة المستلم مع الرمز حتى لا يرسله لجهاز منتحل.
 */
public final class TrustStore {
    private static final String PREFS = "satlink_trust";
    private TrustStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized String deviceId(Context c) {
        SharedPreferences p = prefs(c);
        String id = p.getString("device_id", null);
        if (id == null || id.length() != 32) {
            id = hex(random(16));
            p.edit().putString("device_id", id).apply();
        }
        return id;
    }

    // ---------------- جهة المستلم
    public static synchronized boolean isTrustedSender(Context c, String senderId, String token) {
        if (senderId == null || token == null || token.isEmpty()) return false;
        String stored = prefs(c).getString("trusted_token_" + senderId, null);
        return stored != null && MessageDigest.isEqual(stored.getBytes(), token.getBytes());
    }

    public static synchronized String trustSender(Context c, String senderId, String name) {
        String token = hex(random(32));
        prefs(c).edit().putString("trusted_token_" + senderId, token).putString("trusted_name_" + senderId, name).apply();
        return token;
    }

    /** قائمة الأجهزة الموثوقة: [id, name]. */
    public static synchronized List<String[]> trustedSenders(Context c) {
        ArrayList<String[]> out = new ArrayList<>();
        for (Map.Entry<String, ?> e : prefs(c).getAll().entrySet()) {
            if (e.getKey().startsWith("trusted_name_"))
                out.add(new String[]{e.getKey().substring("trusted_name_".length()), String.valueOf(e.getValue())});
        }
        return out;
    }

    public static synchronized void forgetSender(Context c, String senderId) {
        prefs(c).edit().remove("trusted_token_" + senderId).remove("trusted_name_" + senderId).apply();
    }

    // ---------------- جهة المرسل
    public static synchronized String tokenForReceiver(Context c, String fingerprint) {
        return prefs(c).getString("known_token_" + fingerprint, "");
    }

    public static synchronized void rememberReceiver(Context c, String fingerprint, String name, String token) {
        prefs(c).edit().putString("known_token_" + fingerprint, token).putString("known_name_" + fingerprint, name).apply();
    }

    // ---------------- أدوات
    public static String verifyCode(byte[] certDer, byte[] nonce) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(certDer);
            md.update(nonce);
            byte[] h = md.digest();
            long v = ((h[0] & 0xffL) << 24) | ((h[1] & 0xffL) << 16) | ((h[2] & 0xffL) << 8) | (h[3] & 0xffL);
            return String.format(java.util.Locale.US, "%04d", v % 10000);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static String fingerprint(byte[] certDer) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(certDer)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static byte[] random(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(java.util.Locale.US, "%02x", x & 0xff));
        return sb.toString();
    }
}
