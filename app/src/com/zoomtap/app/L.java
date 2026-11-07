package com.zoomtap.app;

import android.content.Context;

/** Resolves localized strings for code paths that have no Activity to call. */
public final class L {

    private L() {
    }

    public static String s(Context c, int resId, Object... args) {
        try {
            if (args == null || args.length == 0) {
                return c.getString(resId);
            }
            return c.getString(resId, args);
        } catch (Throwable t) {
            return "";
        }
    }
}
