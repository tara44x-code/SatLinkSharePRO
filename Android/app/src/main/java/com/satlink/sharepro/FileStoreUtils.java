package com.satlink.sharepro;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import android.content.res.AssetFileDescriptor;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.security.MessageDigest;

public class FileStoreUtils {
    public interface CopyProgress {
        void onProgress(long copiedBytes);
    }

    public static FileItem fromUri(Context context, Uri uri) {
        String name = "satlink_file_" + System.currentTimeMillis();
        long size = -1L;

        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex >= 0) name = cursor.getString(nameIndex);
                if (sizeIndex >= 0) size = cursor.getLong(sizeIndex);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }

        if (size < 0) {
            try (AssetFileDescriptor afd = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
                if (afd != null) size = afd.getLength();
            } catch (Exception ignored) {}
        }
        if (size < 0) {
            try (ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "r")) {
                if (fd != null) size = fd.getStatSize();
            } catch (Exception ignored) {}
        }

        return new FileItem(uri, sanitizeFileName(name), size);
    }

    public static String sanitizeFileName(String name) {
        if (name == null || name.trim().isEmpty()) return "satlink_file_" + System.currentTimeMillis();
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    public static String sanitizeRelativePath(String name) throws IOException {
        if (name == null || name.isEmpty() || name.length() > 32767 || name.startsWith("/") || name.startsWith("\\"))
            throw new IOException("مسار ملف غير صالح");
        String[] parts = name.replace('\\', '/').split("/", -1);
        if (parts.length > 128) throw new IOException("مسار طويل جداً");
        StringBuilder path = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.indexOf(':') >= 0)
                throw new IOException("مسار ملف غير صالح");
            String safePart = sanitizeFileName(part);
            if (safePart.isEmpty() || safePart.equals(".") || safePart.equals(".."))
                throw new IOException("مسار ملف غير صالح");
            if (path.length() > 0) path.append('/');
            path.append(safePart);
        }
        return path.toString();
    }

    private static String baseName(String relativeName) {
        int slash = relativeName.lastIndexOf('/');
        return relativeName.substring(slash + 1);
    }

    private static String downloadsPath(String relativeName) {
        int slash = relativeName.lastIndexOf('/');
        return Environment.DIRECTORY_DOWNLOADS + "/SatLink Share PRO/Received/" +
                (slash < 0 ? "" : relativeName.substring(0, slash + 1));
    }

    /**
     * يفتح الملف من موضع معيّن بقفزة مباشرة (بدون قراءة ما قبله). مهم للأجزاء المتوازية:
     * القراءة من البداية حتى منتصف ملف 4 GB كانت تشغل الذاكرة وتبطّئ باقي الأجزاء.
     */
    public static InputStream openInputAt(Context context, Uri uri, long offset) throws IOException {
        try {
            final ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r");
            if (pfd != null) {
                final FileInputStream in = new FileInputStream(pfd.getFileDescriptor());
                try {
                    in.getChannel().position(offset);
                    return new java.io.FilterInputStream(in) {
                        @Override public void close() throws IOException { try { super.close(); } finally { pfd.close(); } }
                    };
                } catch (IOException e) { in.close(); pfd.close(); }
            }
        } catch (Exception ignored) {}
        InputStream in = openInput(context, uri);
        long skipped = 0;
        while (skipped < offset) {
            long n = in.skip(offset - skipped);
            if (n <= 0) {
                if (in.read() == -1) { in.close(); throw new IOException("تعذر الوصول لموضع الجزء"); }
                n = 1;
            }
            skipped += n;
        }
        return in;
    }

    public static InputStream openInput(Context context, Uri uri) throws IOException {
        InputStream inputStream = context.getContentResolver().openInputStream(uri);
        if (inputStream == null) throw new IOException("تعذر فتح الملف");
        return inputStream;
    }

    public static File getPartialFile(Context context, String fileName, long totalSize) throws IOException {
        File dir = new File(context.getExternalFilesDir(null), "resume_parts");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("تعذر إنشاء مجلد الاستئناف");
        String safe = sanitizeFileName(baseName(fileName));
        String key = fileName.indexOf('/') < 0
                ? Math.abs((safe + "_" + totalSize).hashCode()) + "_" + safe + ".part"
                : pendingKey(fileName, totalSize) + "_" + safe + ".part";
        return new File(dir, key);
    }

    // On Android 10+, keep the unpublished Downloads item itself as the resume target.
    // This avoids copying a 40+ GiB temporary file and consuming twice the free space.
    private static String pendingKey(String fileName, long size) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((fileName + "\u0000" + size).getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder("pending_");
            for (byte b : bytes) hex.append(String.format(Locale.US, "%02x", b & 255));
            return hex.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static Uri pendingUri(Context context, String name, long size) {
        String value = context.getSharedPreferences("satlink_pending", Context.MODE_PRIVATE)
                .getString(pendingKey(name, size), null);
        return value == null ? null : Uri.parse(value);
    }

    private static Uri ensurePendingUri(Context context, String name, long size) throws IOException {
        Uri uri = pendingUri(context, name, size);
        if (uri != null) {
            try (ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "rw")) {
                if (fd != null) return uri;
            } catch (Exception ignored) {}
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, baseName(name));
        values.put(MediaStore.Downloads.MIME_TYPE, guessMimeType(name));
        values.put(MediaStore.Downloads.RELATIVE_PATH, downloadsPath(name));
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("تعذر إنشاء ملف التنزيلات");
        context.getSharedPreferences("satlink_pending", Context.MODE_PRIVATE).edit()
                .putString(pendingKey(name, size), uri.toString()).commit();
        return uri;
    }

    // للملفات الكبيرة التي تصل عبر عدة اتصالات بالتوازي: آخر نقطة متصلة مكتوبة بالكامل.
    public static long getSafeOffset(Context context, String fileName, long totalSize) {
        return context.getSharedPreferences("satlink_safe", Context.MODE_PRIVATE).getLong(pendingKey(fileName, totalSize), -1L);
    }

    public static void setSafeOffset(Context context, String fileName, long totalSize, long offset) {
        context.getSharedPreferences("satlink_safe", Context.MODE_PRIVATE).edit().putLong(pendingKey(fileName, totalSize), offset).apply();
    }

    public static void clearSafeOffset(Context context, String fileName, long totalSize) {
        context.getSharedPreferences("satlink_safe", Context.MODE_PRIVATE).edit().remove(pendingKey(fileName, totalSize)).apply();
    }

    /** كتابة في أي موضع داخل الملف المؤقت (لمسارات النقل المتوازية). */
    public interface RandomWriter extends java.io.Closeable {
        void write(byte[] b, int off, int len, long position) throws IOException;
    }

    public static RandomWriter openPartialRandom(Context context, String fileName, long totalSize) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            Uri uri = ensurePendingUri(context, fileName, totalSize);
            final ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "rw");
            if (fd == null) throw new IOException("تعذر فتح ملف التنزيلات");
            final FileOutputStream out = new FileOutputStream(fd.getFileDescriptor());
            final java.nio.channels.FileChannel ch = out.getChannel();
            return new RandomWriter() {
                @Override public void write(byte[] b, int off, int len, long position) throws IOException {
                    java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(b, off, len);
                    while (buf.hasRemaining()) position += ch.write(buf, position);
                }
                @Override public void close() throws IOException { try { out.close(); } finally { fd.close(); } }
            };
        }
        final java.io.RandomAccessFile raf = new java.io.RandomAccessFile(getPartialFile(context, fileName, totalSize), "rw");
        return new RandomWriter() {
            @Override public synchronized void write(byte[] b, int off, int len, long position) throws IOException {
                raf.seek(position); raf.write(b, off, len);
            }
            @Override public void close() throws IOException { raf.close(); }
        };
    }

    public static long getPartialSize(Context context, String fileName, long totalSize) {
        long safe = getSafeOffset(context, fileName, totalSize);
        if (safe >= 0 && safe <= totalSize) return safe;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                Uri uri = pendingUri(context, fileName, totalSize);
                if (uri == null) return 0L;
                try (ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "rw")) {
                    if (fd == null) return 0L;
                    long length = fd.getStatSize();
                    return length >= 0 && length <= totalSize ? length : 0L;
                }
            }
            File file = getPartialFile(context, fileName, totalSize);
            long length = file.exists() ? file.length() : 0L;
            if (length < 0 || length > totalSize) return 0L;
            return length;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    public static OutputStream openPartialOutput(Context context, String fileName, long totalSize, long offset) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            Uri uri = ensurePendingUri(context, fileName, totalSize);
            ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "rw");
            if (fd == null) throw new IOException("تعذر فتح ملف التنزيلات");
            try {
                FileOutputStream out = new FileOutputStream(fd.getFileDescriptor());
                out.getChannel().position(offset);
                return new OutputStream() {
                    @Override public void write(int b) throws IOException { out.write(b); }
                    @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); }
                    @Override public void flush() throws IOException { out.flush(); }
                    @Override public void close() throws IOException { try { out.close(); } finally { fd.close(); } }
                };
            } catch (IOException e) { fd.close(); throw e; }
        }
        final java.io.RandomAccessFile raf = new java.io.RandomAccessFile(getPartialFile(context, fileName, totalSize), "rw");
        raf.seek(offset);
        return new OutputStream() {
            @Override public void write(int b) throws IOException { raf.write(b); }
            @Override public void write(byte[] b, int off, int len) throws IOException { raf.write(b, off, len); }
            @Override public void close() throws IOException { raf.close(); }
        };
    }

    /** يقص الملف المؤقت على حجمه الصحيح (بعد نقل متوازي قد يكون أطول من المطلوب). */
    private static void truncatePartial(Context context, String fileName, long totalSize) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                Uri uri = pendingUri(context, fileName, totalSize);
                if (uri == null) return;
                try (ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "rw");
                     FileOutputStream out = new FileOutputStream(fd.getFileDescriptor())) {
                    if (out.getChannel().size() > totalSize) out.getChannel().truncate(totalSize);
                }
                return;
            }
            File f = getPartialFile(context, fileName, totalSize);
            if (f.exists() && f.length() > totalSize) {
                try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "rw")) { raf.setLength(totalSize); }
            }
        } catch (Exception ignored) {}
    }

    public static void resetPartial(Context context, String fileName, long totalSize) {
        clearSafeOffset(context, fileName, totalSize);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                Uri uri = pendingUri(context, fileName, totalSize);
                if (uri != null) context.getContentResolver().delete(uri, null, null);
                context.getSharedPreferences("satlink_pending", Context.MODE_PRIVATE).edit().remove(pendingKey(fileName, totalSize)).apply();
                return;
            }
            File file = getPartialFile(context, fileName, totalSize);
            if (file.exists()) file.delete();
        } catch (Exception ignored) {}
    }

    /** ينقل الملف المكتمل إلى Downloads/SatLink Share PRO/Received ويرجع رابطه. */
    public static Uri copyPartialToDownloads(Context context, String fileName, long totalSize) throws IOException {
        truncatePartial(context, fileName, totalSize);
        clearSafeOffset(context, fileName, totalSize);
        if (Build.VERSION.SDK_INT >= 29) {
            Uri uri = pendingUri(context, fileName, totalSize);
            if (uri == null && totalSize == 0) uri = ensurePendingUri(context, fileName, totalSize);
            if (uri == null) throw new IOException("ملف التنزيل المؤقت غير موجود");
            ContentValues values = new ContentValues(); values.put(MediaStore.Downloads.IS_PENDING, 0);
            if (context.getContentResolver().update(uri, values, null, null) != 1)
                throw new IOException("تعذر إظهار الملف المكتمل");
            return uri;
        }
        File partial = getPartialFile(context, fileName, totalSize);
        if (!partial.exists()) {
            if (totalSize != 0) throw new IOException("ملف الاستئناف غير موجود");
            if (!partial.createNewFile()) throw new IOException("تعذر إنشاء الملف");
        }
        File target = legacyTarget(fileName);
        if (!partial.renameTo(target)) {
            try (InputStream in = new FileInputStream(partial);
                 OutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[256 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                out.flush();
            }
        }
        return Uri.fromFile(target);
    }

    private static File legacyTarget(String fileName) throws IOException {
        fileName = sanitizeRelativePath(fileName);
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SatLink Share PRO/Received");
        File file = new File(dir, fileName);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("تعذر إنشاء مجلد الاستلام");
        if (!file.exists()) return file;
        String base = file.getName();
        int dot = base.lastIndexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;
        String ext = dot > 0 ? base.substring(dot) : "";
        for (int n = 1; ; n++) {
            File candidate = new File(parent, stem + " (" + n + ")" + ext);
            if (!candidate.exists()) return candidate;
        }
    }

    public static void deletePartial(Context context, String fileName, long totalSize) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                context.getSharedPreferences("satlink_pending", Context.MODE_PRIVATE).edit().remove(pendingKey(fileName, totalSize)).apply();
                return;
            }
            File file = getPartialFile(context, fileName, totalSize);
            if (file.exists()) file.delete();
        } catch (Exception ignored) {}
    }

    public static String guessMimeType(String fileName) {
        String ext = "";
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0 && dot < fileName.length() - 1) ext = fileName.substring(dot + 1).toLowerCase(Locale.US);
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return type == null ? "application/octet-stream" : type;
    }
}
