package ca.vibetv;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.FileInputStream;
import java.io.OutputStream;
import android.os.Build;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Writes errors and session events to a log file you can pull from the device. */
public final class VibeLog {
    private static final String TAG = "VibeTV";
    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static File logFile;
    private static Context appCtx;
    private static long lastExport;

    private VibeLog() {}

    public static synchronized void init(Context ctx) {
        try {
            Context app = ctx.getApplicationContext();
            if (app == null) app = ctx;
            appCtx = app;
            File dir = app.getExternalFilesDir(null);
            if (dir == null) dir = app.getFilesDir();
            dir = new File(dir, "logs");
            dir.mkdirs();
            logFile = new File(dir, "vibetv_errors.log");
            final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override public void uncaughtException(Thread t, Throwable e) {
                    write("CRASH", "Uncaught exception in thread " + t.getName(), e);
                    exportToDownloads();
                    if (prev != null) prev.uncaughtException(t, e);
                }
            });
            write("START", "App started - device " + Build.MANUFACTURER + " " + Build.MODEL
                    + ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")", null);
            exportToDownloads();
        } catch (Throwable ignored) {}
    }

    public static void e(Throwable t) {
        write("ERROR", t == null ? "null error" : t.toString(), t);
        if (System.currentTimeMillis() - lastExport > 30000) exportToDownloads();
    }

    /** Copies the log to Download/VibeTV/vibetv_errors.log so it can be opened and shared from the phone. */
    public static synchronized void exportToDownloads() {
        try {
            lastExport = System.currentTimeMillis();
            if (appCtx == null || logFile == null || !logFile.exists()) return;
            byte[] data = readAll(logFile);
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = appCtx.getContentResolver();
                File marker = new File(logFile.getParentFile(), "export_uri.txt");
                if (marker.exists()) {
                    try { cr.delete(Uri.parse(new String(readAll(marker), "UTF-8").trim()), null, null); } catch (Throwable ignored) {}
                }
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, "vibetv_errors.log");
                v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/VibeTV");
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return;
                OutputStream os = cr.openOutputStream(uri);
                try { os.write(data); } finally { os.close(); }
                FileWriter w = new FileWriter(marker, false);
                try { w.write(uri.toString()); } finally { w.close(); }
            } else {
                File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "VibeTV");
                dir.mkdirs();
                java.io.FileOutputStream os = new java.io.FileOutputStream(new File(dir, "vibetv_errors.log"));
                try { os.write(data); } finally { os.close(); }
            }
        } catch (Throwable ignored) {}
    }

    private static byte[] readAll(File f) throws java.io.IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally { in.close(); }
    }

    public static void event(String msg) { write("EVENT", msg, null); }

    /** HTTP error body. Called once per failed API response. */
    public static void http(int code, String body) {
        String b = body == null ? "" : body;
        if (b.length() > 2000) b = b.substring(0, 2000) + "...";
        write("HTTP", "HTTP " + code + " body: " + b, null);
    }

    /** Same rule as the original app: HTTP 500 whose body says the session is invalid or the IP changed. */
    public static boolean sessionInvalid(int code, String body) {
        if (code != 500 || body == null) return false;
        boolean r = body.contains("is not valid anymore") || body.contains("ip address has changed");
        if (r) event("Server reported session invalid -> app will silently re-login");
        return r;
    }

    private static synchronized void write(String level, String msg, Throwable t) {
        try {
            if (t != null) Log.e(TAG, msg, t); else Log.w(TAG, msg);
            if (logFile == null) return;
            if (logFile.length() > MAX_BYTES) {
                File old = new File(logFile.getParentFile(), "vibetv_errors.old.log");
                old.delete();
                logFile.renameTo(old);
            }
            StringBuilder sb = new StringBuilder();
            sb.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()))
              .append(" [").append(level).append("] [").append(Thread.currentThread().getName()).append("] ")
              .append(msg).append('\n');
            if (t != null) {
                StringWriter sw = new StringWriter();
                t.printStackTrace(new PrintWriter(sw));
                sb.append(sw).append('\n');
            }
            FileWriter w = new FileWriter(logFile, true);
            try { w.write(sb.toString()); } finally { w.close(); }
        } catch (Throwable ignored) {}
    }
}
