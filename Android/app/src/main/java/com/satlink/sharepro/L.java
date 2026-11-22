package com.satlink.sharepro;

import android.content.Context;

/** اللغة والثيم: L.t("عربي", "English") يرجع النص حسب لغة التطبيق المختارة. */
public final class L {
    private static volatile boolean english;
    private static volatile boolean light;
    private L() {}

    public static void init(Context c) {
        android.content.SharedPreferences p = c.getApplicationContext().getSharedPreferences("satlink_share_pro", Context.MODE_PRIVATE);
        english = "en".equals(p.getString("lang", "ar"));
        light = p.getBoolean("light_theme", false);
    }

    public static void setEnglish(Context c, boolean value) {
        english = value;
        c.getApplicationContext().getSharedPreferences("satlink_share_pro", Context.MODE_PRIVATE).edit().putString("lang", value ? "en" : "ar").apply();
    }

    public static void setLight(Context c, boolean value) {
        light = value;
        c.getApplicationContext().getSharedPreferences("satlink_share_pro", Context.MODE_PRIVATE).edit().putBoolean("light_theme", value).apply();
    }

    public static boolean english() { return english; }
    public static boolean light() { return light; }

    public static String t(String ar, String en) { return english ? en : ar; }
}
