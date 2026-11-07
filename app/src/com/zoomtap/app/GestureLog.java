package com.zoomtap.app;

import android.content.Context;
import android.content.Intent;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/** In-memory ring buffer used by the on-screen gesture diagnostics panel. */
public final class GestureLog {

    public static final String ACTION = "com.zoomtap.app.GESTURE_LOG_CHANGED";

    private static final ArrayList<String> LINES = new ArrayList<>();
    private static final SimpleDateFormat TIME =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private GestureLog() {
    }

    public static synchronized void add(Context c, String line) {
        String entry = TIME.format(new Date()) + "  " + line;
        LINES.add(0, entry);
        // Mirror to logcat and to a private file so diagnostics survive adb
        // reconnects, process restarts and off-screen UI.
        android.util.Log.i("ZoomTapLog", entry);
        appendToFile(c, entry);
        while (LINES.size() > 30) {
            LINES.remove(LINES.size() - 1);
        }
        broadcast(c);
    }

    public static synchronized void clear(Context c) {
        LINES.clear();
        broadcast(c);
    }

    public static synchronized String text(Context c) {
        if (LINES.isEmpty()) {
            return L.s(c, R.string.log_empty);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < LINES.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(LINES.get(i));
        }
        return sb.toString();
    }

    /** Path: /sdcard/Android/data/com.zoomtap.app/files/gesture.log */
    private static void appendToFile(Context c, String entry) {
        try {
            File dir = c.getExternalFilesDir(null);
            if (dir == null) {
                return;
            }
            File f = new File(dir, "gesture.log");
            if (f.length() > 65536) {
                // Keep the log bounded; old diagnostics are no longer useful.
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            FileOutputStream out = new FileOutputStream(f, true);
            out.write((entry + "\n").getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) {
        }
    }

    private static void broadcast(Context c) {
        try {
            c.sendBroadcast(new Intent(ACTION).setPackage(c.getPackageName()));
        } catch (Throwable ignored) {
        }
    }
}
