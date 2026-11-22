package com.satlink.sharepro;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** التطبيقات والألعاب المثبتة، لإرسالها كملفات APK (مع أجزائها إن وُجدت). */
public final class AppsCatalog {
    public static final class App {
        public String label, packageName, base;
        public String[] splits;
        public Drawable icon;
        public long size;
        public boolean game;
    }

    private AppsCatalog() {}

    @SuppressWarnings("deprecation")
    public static List<App> load(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(launcher, 0);
        HashSet<String> seen = new HashSet<>();
        ArrayList<App> apps = new ArrayList<>();
        for (ResolveInfo ri : infos) {
            try {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(context.getPackageName()) || !seen.add(pkg)) continue;
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                if (ai.sourceDir == null || !new File(ai.sourceDir).canRead()) continue;
                App a = new App();
                a.packageName = pkg;
                a.label = String.valueOf(ai.loadLabel(pm));
                a.base = ai.sourceDir;
                a.splits = ai.splitSourceDirs;
                a.size = new File(ai.sourceDir).length();
                if (a.splits != null) for (String s : a.splits) a.size += new File(s).length();
                a.game = (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0
                        || (Build.VERSION.SDK_INT >= 26 && ai.category == ApplicationInfo.CATEGORY_GAME);
                a.icon = ai.loadIcon(pm);
                apps.add(a);
            } catch (Exception ignored) {}
        }
        Collections.sort(apps, (x, y) -> x.label.compareToIgnoreCase(y.label));
        return apps;
    }

    /** تطبيق بدون أجزاء = ملف APK واحد. تطبيق مقسّم (ألعاب كثيرة) = مجلد فيه كل الأجزاء للتثبيت ببرنامج مثل SAI. */
    public static List<FileItem> toFiles(App a) {
        ArrayList<FileItem> out = new ArrayList<>();
        String name = FileStoreUtils.sanitizeFileName(a.label);
        if (a.splits == null || a.splits.length == 0) {
            File f = new File(a.base);
            out.add(new FileItem(Uri.fromFile(f), name + ".apk", f.length()));
            return out;
        }
        File base = new File(a.base);
        out.add(new FileItem(Uri.fromFile(base), name + "/" + name + ".apk", base.length()));
        for (String s : a.splits) {
            File f = new File(s);
            out.add(new FileItem(Uri.fromFile(f), name + "/" + FileStoreUtils.sanitizeFileName(f.getName()), f.length()));
        }
        return out;
    }
}
