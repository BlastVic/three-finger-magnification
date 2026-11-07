package com.zoomtap.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Small wrapper around the app's private preferences. */
public final class Prefs {

    public static final String NAME = "zoomtap_prefs";
    public static final String ENABLED = "enabled";
    public static final String SCALE = "scale";
    public static final String CENTER_MODE = "center_mode"; // 0 = screen center, 1 = gesture position
    public static final String VIBRATE = "vibrate";
    public static final String KEEP_ZOOM = "keep_zoom";
    // true = full-screen passthrough (zero interference), false = compatible interception
    public static final String ZERO_INTERFERENCE = "zero_interference";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context c) {
        return sp(c).getBoolean(ENABLED, true);
    }

    public static void setEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(ENABLED, v).apply();
    }

    public static float getScale(Context c) {
        return sp(c).getFloat(SCALE, 2.0f);
    }

    public static void setScale(Context c, float v) {
        sp(c).edit().putFloat(SCALE, v).apply();
    }

    public static int getCenterMode(Context c) {
        return sp(c).getInt(CENTER_MODE, 1);
    }

    public static void setCenterMode(Context c, int v) {
        sp(c).edit().putInt(CENTER_MODE, v).apply();
    }

    public static boolean isVibrate(Context c) {
        return sp(c).getBoolean(VIBRATE, true);
    }

    public static void setVibrate(Context c, boolean v) {
        sp(c).edit().putBoolean(VIBRATE, v).apply();
    }

    public static boolean isKeepZoom(Context c) {
        return sp(c).getBoolean(KEEP_ZOOM, true);
    }

    public static void setKeepZoom(Context c, boolean v) {
        sp(c).edit().putBoolean(KEEP_ZOOM, v).apply();
    }

    public static boolean isZeroInterference(Context c) {
        // Default ON: never take over the touch stream. Interception mode is kept
        // only as an opt-in fallback for ROMs that do deliver passthrough copies.
        return sp(c).getBoolean(ZERO_INTERFERENCE, true);
    }

    public static void setZeroInterference(Context c, boolean v) {
        sp(c).edit().putBoolean(ZERO_INTERFERENCE, v).apply();
    }
}
