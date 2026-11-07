package com.zoomtap.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * App-side endpoint for the shell (Shizuku user service) process.
 *
 * A direct Binder callback is not reliable on this device: vivo freezes or
 * recycles the app process as soon as it is in the background, and the shell
 * process then gets DeadObjectException. An explicit `am broadcast` is queued
 * by ActivityManager, which unfreezes / restarts the app process first.
 *
 * This receiver is statically declared so it works even when nothing else in
 * the app is running. The accessibility service may still be reconnecting at
 * that moment, so delivery is retried while holding goAsync(). Both channels
 * carry the same trigger id, so retries and duplicates are de-duplicated by
 * the service rather than by a time cooldown.
 */
public class TripleTapReceiver extends BroadcastReceiver {

    public static final String ACTION_TAP = "com.zoomtap.app.SHIZUKU_TAP";
    public static final String ACTION_DIAG = "com.zoomtap.app.SHIZUKU_DIAG";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_DIAG.equals(action)) {
            String msg = intent.getStringExtra("msg");
            if (msg != null) {
                GestureLog.add(context, "[shell] " + msg);
            }
            return;
        }
        if (!ACTION_TAP.equals(action)) {
            return;
        }

        final long id = intent.getLongExtra("id", 0L);
        final float nx = intent.getFloatExtra("x", 0.5f);
        final float ny = intent.getFloatExtra("y", 0.5f);
        final int peak = intent.getIntExtra("peak", 3);

        // No log line here: the single ★ entry is written by the service at the
        // point where the tap is actually applied, so "one gesture = one ★"
        // stays true even though two delivery channels are used.
        ZoomAccessibilityService.queueExternalTap(id, nx, ny, peak);
        if (ZoomAccessibilityService.tryDeliverTap(id, nx, ny, peak)) {
            return;
        }

        // The accessibility service is usually reconnected by the system a few
        // hundred ms after the process is woken up. Keep the receiver alive
        // and retry; queueExternalTap also survives a later service reconnect.
        final PendingResult result = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (int i = 0; i < 12; i++) {
                        Thread.sleep(300);
                        if (ZoomAccessibilityService.tryDeliverTap(id, nx, ny, peak)) {
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    result.finish();
                }
            }
        }, "zoomtap-deliver").start();
    }
}
