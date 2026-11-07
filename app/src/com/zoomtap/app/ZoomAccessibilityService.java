package com.zoomtap.app;

import android.accessibilityservice.AccessibilityGestureEvent;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.MagnificationConfig;
import android.accessibilityservice.TouchInteractionController;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import java.util.List;
import java.util.Locale;

/**
 * Recognises a three-finger double tap and toggles full-screen magnification.
 *
 * Framework constraint (AOSP TouchExplorer + official API docs):
 *  - FLAG_REQUEST_TOUCH_EXPLORATION_MODE is REQUIRED to see any multi-finger
 *    gesture. But it also puts the device in TalkBack-like "touch exploration":
 *    a single finger behaves like a hovering mouse pointer, taps/holds can turn
 *    into phantom long presses, and edge/bottom system gestures are swallowed.
 *  - TouchInteractionController receives all MotionEvents only while the service
 *    is in STATE_TOUCH_INTERACTING. Events are buffered (not delivered to apps or
 *    to SystemUI gesture monitors) until the service classifies the interaction.
 *    requestDelegating() releases them; afterwards no more events reach us.
 *
 * Therefore the ONLY default strategy is ZERO-INTERFERENCE: a full-screen
 * touch-exploration passthrough region makes TouchExplorer call startDelegating()
 * on the very first ACTION_DOWN, so the ENTIRE gesture is handed to apps and
 * SystemUI untouched - single taps, long presses, edge/back/home gestures all
 * keep their stock behaviour, with no buffering at all. The service only reads
 * the MotionEvent copies attached to GESTURE_PASSTHROUGH (FLAG_SEND_MOTION_EVENTS)
 * and recognises the triple double-tap itself.
 *
 * The old TouchInteractionController interception mode is retained purely as an
 * opt-in fallback (app switch / adb broadcast). It buffers the touch stream and
 * on some OEM frameworks the replay turns a single tap into a phantom long press,
 * so it must never be the default.
 */
public class ZoomAccessibilityService extends AccessibilityService {

    // Used only by the legacy accessibility-gesture path. Shizuku taps are
    // de-duplicated by trigger id, so closely spaced double taps are no longer
    // swallowed by a wall-clock cooldown.
    private static final long COOLDOWN_MS = 900;
    private static ZoomAccessibilityService sInstance;

    private static final class PendingTap {
        final long id;
        final float nx;
        final float ny;
        final int peak;

        PendingTap(long id, float nx, float ny, int peak) {
            this.id = id;
            this.nx = nx;
            this.ny = ny;
            this.peak = peak;
        }
    }

    private static volatile PendingTap sPendingTap;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mConnected;
    private boolean mZoomed;
    private long mLastTrigger;
    private long mLastTapId = -1;
    private long mLastSingleTap;
    private float mLastCx = -1;
    private float mLastCy = -1;

    // Passthrough gestures never reach the framework gesture detector. With
    // FLAG_SEND_MOTION_EVENTS the framework still hands us a GESTURE_PASSTHROUGH
    // event carrying the whole MotionEvent stream, so we recognise the triple
    // double-tap ourselves instead of relying on onGesture(GESTURE_3_FINGER_DOUBLE_TAP).
    // Hidden in the public SDK: AccessibilityService.GESTURE_PASSTHROUGH == -1.
    private static final int GESTURE_PASSTHROUGH = -1;
    // Three real fingers are much noisier than a single-finger tap, so the
    // tolerances are deliberately generous - false negatives are far worse here
    // than a rare extra trigger (there is a COOLDOWN_MS guard anyway).
    private static final int TAP_SLOP_PX = 90;
    private static final long TAP_MAX_MS = 600;
    private static final long DOUBLE_TAP_MS = 700;

    private int mTapPeak;
    private long mTapDownTime;
    private boolean mTapMoved;
    private float mTapStartX;
    private float mTapStartY;
    private long mLastTripleTapTime;
    private boolean mGestureMotionSeen;
    private boolean mMotionCallbackSeen;
    private int mLastLoggedGestureId = -1;
    private long mLastDiagLogTime;
    private long mLastEmptyPassthroughLog;
    // true (default) = ZERO-INTERFERENCE: full-screen passthrough, events come from
    //   GESTURE_PASSTHROUGH motion-event copies. No touch stream is ever buffered.
    // false = legacy interception via TouchInteractionController. Kept as an
    //   opt-in fallback only; see the class comment for why it is not the default.
    private boolean mPassthroughEnabled = true;
    private BroadcastReceiver mDebugReceiver;

    private static final long DELEGATE_DELAY_MS = 80;
    private TouchInteractionController mTouchController;
    private boolean mHoldForTriple;
    private boolean mDelegated;
    private int mLastControllerState = -1;
    private long mLastTouchLog;
    // Cached on connect / rotation so ACTION_DOWN stays cheap (touch hot path).
    private int mScreenW;
    private int mScreenH;
    private int mZoneBottom;
    private int mZoneEdge;
    private int mZoneTop;
    private final Runnable mDelegateRunnable = new Runnable() {
        @Override
        public void run() {
            delegateNow();
        }
    };

    private final Runnable mReapply = new Runnable() {
        @Override
        public void run() {
            if (!mConnected || !mZoomed) {
                return;
            }
            if (!Prefs.isEnabled(ZoomAccessibilityService.this)
                    || !Prefs.isKeepZoom(ZoomAccessibilityService.this)) {
                return;
            }
            MagnificationController mc = getMagnificationController();
            if (mc != null && mc.getScale() <= 1.01f) {
                applyZoom(mc, Prefs.getScale(ZoomAccessibilityService.this), mLastCx, mLastCy);
                GestureLog.add(ZoomAccessibilityService.this,
                    L.s(ZoomAccessibilityService.this, R.string.log_restored_zoom));
            }
        }
    };

    public static boolean isConnected() {
        return sInstance != null && sInstance.mConnected;
    }

    public static boolean isZoomed() {
        return sInstance != null && sInstance.mZoomed;
    }

    public static void refresh() {
        if (sInstance != null) {
            sInstance.applyConfiguration();
        }
    }

    public static void toggleFromUi(Context c) {
        if (sInstance != null) {
            sInstance.toggleZoom(null, -1, -1);
        }
    }

    /**
     * Called from the Shizuku user service when the raw evdev stream shows a
     * three-finger double tap. nx/ny are normalised touch coordinates (0..1).
     * This is the only gesture source that does not touch the input pipeline.
     */
    public static synchronized void onShizukuTripleDoubleTap(final long id,
                                                             final float nx,
                                                             final float ny,
                                                             final int peak) {
        final ZoomAccessibilityService s = sInstance;
        if (s == null || !s.mConnected || !Prefs.isEnabled(s)) {
            return;
        }
        // Binder callback, broadcast and delivery retries all carry the same id.
        // Only the first copy toggles magnification; unrelated double taps keep
        // working even when they are only ~600 ms apart.
        if (id > 0 && id == s.mLastTapId) {
            return;
        }
        s.mLastTapId = id;
        s.mLastTrigger = SystemClock.uptimeMillis();
        GestureLog.add(s, L.s(s, R.string.log_triple_tap, peak));
        s.mHandler.post(new Runnable() {
            @Override
            public void run() {
                s.toggleZoom(null, nx, ny);
            }
        });
    }

    /**
     * A tap that arrived while the accessibility service was still reconnecting.
     * onServiceConnected() consumes it once the service is ready.
     */
    public static void queueExternalTap(long id, float nx, float ny, int peak) {
        sPendingTap = new PendingTap(id, nx, ny, peak);
    }

    /** Returns true when the tap was handled (or intentionally discarded). */
    public static synchronized boolean tryDeliverTap(long id, float nx, float ny, int peak) {
        ZoomAccessibilityService s = sInstance;
        if (s == null || !s.mConnected) {
            return false;
        }
        sPendingTap = null;
        if (Prefs.isEnabled(s)) {
            onShizukuTripleDoubleTap(id, nx, ny, peak);
        }
        return true;
    }

    private void consumePendingTap() {
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                PendingTap p = sPendingTap;
                if (p != null) {
                    tryDeliverTap(p.id, p.nx, p.ny, p.peak);
                }
            }
        }, 400);
    }

    /** Re-apply the configured scale while zoomed (used when the slider changes). */
    public static void updateScale(Context c) {
        ZoomAccessibilityService s = sInstance;
        if (s == null || !s.mZoomed) {
            return;
        }
        MagnificationController mc = s.getMagnificationController();
        if (mc != null) {
            s.applyZoom(mc, Prefs.getScale(c), s.mLastCx, s.mLastCy);
        }
    }

    /** Turning the feature off must never leave the user stuck in a zoomed screen. */
    public static void resetIfDisabled(Context c) {
        ZoomAccessibilityService s = sInstance;
        if (s == null || Prefs.isEnabled(c)) {
            return;
        }
        if (s.mZoomed) {
            MagnificationController mc = s.getMagnificationController();
            if (mc != null) {
                mc.reset(true);
            }
            s.mZoomed = false;
            GestureLog.add(c, L.s(c, R.string.log_disabled_reset));
        }
        s.mHandler.removeCallbacks(s.mReapply);
        ZoomTileService.requestUpdate(c);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        mConnected = true;
        mPassthroughEnabled = Prefs.isZeroInterference(this);
        registerDebugReceiver();
        applyConfiguration();
        GestureLog.add(this, L.s(this, R.string.log_service_connected));
        ZoomTileService.requestUpdate(this);
        try {
            ShizukuTouchBridge.get(this).start();
        } catch (Throwable t) {
            GestureLog.add(this, L.s(this, R.string.log_shizuku_init_failed, t));
        }
        consumePendingTap();
    }

    /** Lets adb flip the passthrough strategy without reinstalling the APK. */
    private void registerDebugReceiver() {
        if (mDebugReceiver != null) {
            return;
        }
        mDebugReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                mPassthroughEnabled = intent.getBooleanExtra(
                        "passthrough", Prefs.isZeroInterference(c));
                Prefs.setZeroInterference(c, mPassthroughEnabled);
                GestureLog.add(c, L.s(c, R.string.log_debug_zero, mPassthroughEnabled));
                applyConfiguration();
            }
        };
        IntentFilter filter = new IntentFilter("com.zoomtap.app.DEBUG_CONFIG");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mDebugReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(mDebugReceiver, filter);
        }
    }

    private void applyConfiguration() {
        if (!mConnected) {
            return;
        }
        // Also picks up a switch flipped from the Activity (same process).
        mPassthroughEnabled = Prefs.isZeroInterference(this);
        // Android 11+ disables multi-finger gesture detection by default. These flags must be
        // present or GESTURE_3_FINGER_DOUBLE_TAP will never be delivered. The 2-finger
        // passthrough flag keeps ordinary two-finger game controls untouched.
        int wanted = AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
                | AccessibilityServiceInfo.FLAG_REQUEST_MULTI_FINGER_GESTURES
                | AccessibilityServiceInfo.FLAG_SEND_MOTION_EVENTS;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wanted |= AccessibilityServiceInfo.FLAG_REQUEST_2_FINGER_PASSTHROUGH;
        }
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null && (info.flags & wanted) != wanted) {
            info.flags |= wanted;
            setServiceInfo(info);
        }
        computeZones();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                // Anything starting in the system gesture zones must NEVER enter
                // touch exploration: home / recents / edge-back / notification shade.
                // In ZERO-INTERFERENCE mode this is simply the whole display.
                Region passthrough = mPassthroughEnabled
                        ? new Region(0, 0, 10000, 10000) : systemGestureRegion();
                setTouchExplorationPassthroughRegion(Display.DEFAULT_DISPLAY, passthrough);
                // Keep the accessibility gesture detector active in the centre so the
                // controller still receives the three-finger stream.
                setGestureDetectionPassthroughRegion(Display.DEFAULT_DISPLAY, new Region());
                GestureLog.add(this, L.s(this, mPassthroughEnabled
                        ? R.string.log_passthrough_full
                        : R.string.log_passthrough_edges,
                        mZoneBottom, mZoneEdge, mZoneTop));
            } catch (Throwable t) {
                GestureLog.add(this, L.s(this, R.string.log_region_failed, t));
            }
        }
        if (mPassthroughEnabled) {
            unregisterTouchController();
        } else {
            registerTouchController();
        }
        MagnificationController mc = getMagnificationController();
        mZoomed = mc != null && mc.getScale() > 1.01f;
        AccessibilityServiceInfo current = getServiceInfo();
        String flagsHex = current == null ? "?" : Integer.toHexString(current.flags);
        GestureLog.add(this, L.s(this, R.string.log_config_done,
                mPassthroughEnabled, flagsHex));
    }

    /** Caches physical screen size + the system gesture edge zones. */
    private void computeZones() {
        int w = 0;
        int h = 0;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager wm = getSystemService(WindowManager.class);
                Rect bounds = wm.getCurrentWindowMetrics().getBounds();
                w = bounds.width();
                h = bounds.height();
            }
        } catch (Throwable ignored) {
        }
        if (w <= 0 || h <= 0) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            w = dm.widthPixels;
            h = dm.heightPixels;
        }
        mScreenW = w;
        mScreenH = h;
        mZoneBottom = Math.max(160, Math.round(h * 0.08f));
        mZoneEdge = Math.max(56, Math.round(w * 0.035f));
        mZoneTop = Math.max(80, Math.round(h * 0.03f));
    }

    private Region systemGestureRegion() {
        Region r = new Region();
        r.union(new Rect(0, mScreenH - mZoneBottom, mScreenW, mScreenH));
        r.union(new Rect(0, 0, mZoneEdge, mScreenH));
        r.union(new Rect(mScreenW - mZoneEdge, 0, mScreenW, mScreenH));
        r.union(new Rect(0, 0, mScreenW, mZoneTop));
        return r;
    }

    private boolean isSystemGestureZone(float x, float y) {
        return y >= mScreenH - mZoneBottom || y <= mZoneTop
                || x <= mZoneEdge || x >= mScreenW - mZoneEdge;
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Recompute the edge zones after a rotation so navigation gestures still
        // start outside the interception area.
        computeZones();
        if (mConnected) {
            applyConfiguration();
        }
    }

    /**
     * Passive observation: the framework hands us every MotionEvent while in
     * STATE_TOUCH_INTERACTING. Ordinary interactions are delegated back to the
     * app; three-finger interactions are held so we can detect the double tap.
     */
    private void registerTouchController() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || mTouchController != null) {
            return;
        }
        try {
            mTouchController = getTouchInteractionController(Display.DEFAULT_DISPLAY);
            if (mTouchController == null) {
                GestureLog.add(this, L.s(this, R.string.log_controller_unavailable));
                return;
            }
            mTouchController.registerCallback(new java.util.concurrent.Executor() {
                @Override
                public void execute(Runnable command) {
                    // Synchronous keeps event order and minimises delegation latency.
                    command.run();
                }
            }, new TouchInteractionController.Callback() {
                @Override
                public void onMotionEvent(MotionEvent event) {
                    handleTouchEvent(event);
                }

                @Override
                public void onStateChanged(int state) {
                    if (state != mLastControllerState) {
                        mLastControllerState = state;
                        GestureLog.add(ZoomAccessibilityService.this,
                                L.s(ZoomAccessibilityService.this,
                                        R.string.log_controller_state,
                                        TouchInteractionController.stateToString(state)));
                    }
                }
            });
            GestureLog.add(this, L.s(this, R.string.log_controller_attached));
        } catch (Throwable t) {
            GestureLog.add(this, L.s(this, R.string.log_controller_failed, t));
        }
    }

    private void unregisterTouchController() {
        mHandler.removeCallbacks(mDelegateRunnable);
        if (mTouchController == null) {
            return;
        }
        try {
            mTouchController.unregisterAllCallbacks();
        } catch (Throwable ignored) {
        }
        mTouchController = null;
    }

    private void handleTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        // Reuse the same triple-tap state machine used for gesture-event streams.
        feedMotionEvent(e, null);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mHoldForTriple = false;
                mDelegated = false;
                mHandler.removeCallbacks(mDelegateRunnable);
                if (isSystemGestureZone(e.getX(), e.getY())) {
                    // Home / recents / edge-back / notification shade must never be
                    // buffered, otherwise SystemUI never sees the initial ACTION_DOWN.
                    delegateNow();
                } else {
                    // Hold briefly in case a 3rd finger is about to land, then release.
                    mHandler.postDelayed(mDelegateRunnable, DELEGATE_DELAY_MS);
                }
                long now = SystemClock.uptimeMillis();
                if (now - mLastTouchLog > 1500) {
                    mLastTouchLog = now;
                    String controllerState = mTouchController == null ? "?"
                            : TouchInteractionController.stateToString(
                                    mTouchController.getState());
                    GestureLog.add(this, L.s(this, R.string.log_touch_stream,
                            controllerState));
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() >= 3) {
                    mHoldForTriple = true;
                    mHandler.removeCallbacks(mDelegateRunnable);
                    GestureLog.add(this, L.s(this, R.string.log_three_down,
                            e.getPointerCount()));
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (!mDelegated
                        && (Math.abs(e.getX(0) - mTapStartX) > TAP_SLOP_PX
                            || Math.abs(e.getY(0) - mTapStartY) > TAP_SLOP_PX)) {
                    // Any genuine swipe (1, 2 or 3 fingers) is not our double tap.
                    // Release immediately so scrolling, edge gestures and OEM
                    // three-finger screenshot swipes keep working.
                    mHandler.removeCallbacks(mDelegateRunnable);
                    mHoldForTriple = false;
                    delegateNow();
                }
                break;
            case MotionEvent.ACTION_UP:
                mHandler.removeCallbacks(mDelegateRunnable);
                if (!mHoldForTriple) {
                    delegateNow();
                }
                mHoldForTriple = false;
                break;
            case MotionEvent.ACTION_CANCEL:
                mHandler.removeCallbacks(mDelegateRunnable);
                delegateNow();
                mHoldForTriple = false;
                break;
            default:
                break;
        }
    }

    private void delegateNow() {
        if (mDelegated || mTouchController == null) {
            return;
        }
        mDelegated = true;
        try {
            // No STATE_TOUCH_INTERACTING gate: requestDelegating() is valid whenever
            // touch exploration is enabled and a callback is registered. That gate
            // left some interactions stuck in the framework's default touch-exploration
            // transition, producing phantom long presses and swallowed swipes.
            // Only skip when the framework has already finished the transition.
            int state = mTouchController.getState();
            if (state != TouchInteractionController.STATE_DELEGATING
                    && state != TouchInteractionController.STATE_CLEAR) {
                mTouchController.requestDelegating();
            }
        } catch (Throwable ignored) {
            // Races with the framework clearing the interaction are harmless.
        }
    }

    @Override
    public boolean onGesture(AccessibilityGestureEvent event) {
        List<MotionEvent> evs = event.getMotionEvents();
        if (evs != null && !evs.isEmpty()) {
            mGestureMotionSeen = true;
            inspectMotionEvents(evs, event);
        } else if (event.getGestureId() == GESTURE_PASSTHROUGH) {
            // The framework did report the passthrough gesture but attached no
            // MotionEvents. Some OEM builds do this - if this is the only line in
            // the log after a triple tap, this ROM cannot support the clean mode.
            long now = SystemClock.uptimeMillis();
            if (now - mLastEmptyPassthroughLog > 1500) {
                mLastEmptyPassthroughLog = now;
                GestureLog.add(this, L.s(this, R.string.log_passthrough_no_events));
            }
        }
        return handleGesture(event.getGestureId(), event);
    }

    @Override
    public void onMotionEvent(MotionEvent event) {
        if (!mMotionCallbackSeen) {
            mMotionCallbackSeen = true;
            GestureLog.add(this, L.s(this, R.string.log_motion_channel));
        }
        // Legacy-mode fallback only. In controller mode the same events arrive via
        // handleTouchEvent, so feeding them here would double-count taps.
        if (!mGestureMotionSeen && mPassthroughEnabled) {
            feedMotionEvent(event, null);
        }
    }

    private void inspectMotionEvents(List<MotionEvent> evs,
                                     AccessibilityGestureEvent source) {
        for (MotionEvent m : evs) {
            if (m != null) {
                feedMotionEvent(m, source);
            }
        }
    }

    private void feedMotionEvent(MotionEvent e, AccessibilityGestureEvent source) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mTapPeak = e.getPointerCount();
                mTapDownTime = e.getEventTime();
                mTapMoved = false;
                mTapStartX = e.getX(0);
                mTapStartY = e.getY(0);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                mTapPeak = Math.max(mTapPeak, e.getPointerCount());
                break;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(e.getX(0) - mTapStartX) > TAP_SLOP_PX
                        || Math.abs(e.getY(0) - mTapStartY) > TAP_SLOP_PX) {
                    mTapMoved = true;
                }
                break;
            case MotionEvent.ACTION_UP:
                finishTapRound(e.getEventTime(), source);
                break;
            case MotionEvent.ACTION_CANCEL:
                resetTapRound();
                break;
            default:
                break;
        }
    }

    private void finishTapRound(long upTime, AccessibilityGestureEvent source) {
        boolean tripleTap = mTapPeak >= 3 && !mTapMoved
                && mTapDownTime > 0 && (upTime - mTapDownTime) <= TAP_MAX_MS;
        if (tripleTap) {
            GestureLog.add(this, L.s(this, R.string.log_triple_single,
                    mTapPeak, upTime - mTapDownTime));
            if (mLastTripleTapTime > 0 && upTime - mLastTripleTapTime <= DOUBLE_TAP_MS) {
                mLastTripleTapTime = 0;
                onTripleDoubleTap(source);
            } else {
                mLastTripleTapTime = upTime;
            }
        }
        resetTapRound();
    }

    private void resetTapRound() {
        mTapPeak = 0;
        mTapDownTime = 0;
        mTapMoved = false;
    }

    private void onTripleDoubleTap(AccessibilityGestureEvent source) {
        if (!Prefs.isEnabled(this)) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - mLastTrigger < COOLDOWN_MS) {
            return;
        }
        mLastTrigger = now;
        GestureLog.add(this, L.s(this, R.string.log_triple_legacy));
        toggleZoom(source, -1, -1);
    }

    /** Throttled diagnostics so ordinary single-finger taps don't flood the panel. */
    private void logGesture(int gestureId, AccessibilityGestureEvent event) {
        int count = 0;
        int peak = 0;
        List<MotionEvent> evs = event == null ? null : event.getMotionEvents();
        if (evs != null) {
            count = evs.size();
            for (MotionEvent m : evs) {
                if (m != null) {
                    peak = Math.max(peak, m.getPointerCount());
                }
            }
        }
        long now = SystemClock.uptimeMillis();
        // Always log anything carrying a MotionEvent stream - that is exactly the
        // evidence needed to tell whether this ROM supports the zero-interference
        // path. Only repeated, uninteresting gestures are throttled.
        boolean interesting = peak >= 3 || count > 0;
        if (!interesting && gestureId == mLastLoggedGestureId
                && now - mLastDiagLogTime < 1500) {
            return;
        }
        mLastLoggedGestureId = gestureId;
        mLastDiagLogTime = now;
        GestureLog.add(this, L.s(this, R.string.log_gesture, gestureId,
                gestureName(this, gestureId), count, peak,
                count > 0 ? L.s(this, R.string.log_gesture_events) : "",
                peak >= 3 ? " ★" : ""));
    }

    @Override
    protected boolean onGesture(int gestureId) {
        // Some Android 11 builds still deliver the legacy callback.
        return handleGesture(gestureId, null);
    }

    private boolean handleGesture(int gestureId, AccessibilityGestureEvent event) {
        if (event != null && event.getDisplayId() != Display.DEFAULT_DISPLAY) {
            return false;
        }
        logGesture(gestureId, event);

        boolean doubleTap = gestureId == AccessibilityService.GESTURE_3_FINGER_DOUBLE_TAP;
        boolean singleTap = gestureId == AccessibilityService.GESTURE_3_FINGER_SINGLE_TAP;
        if (!doubleTap && !singleTap) {
            return false;
        }
        if (!Prefs.isEnabled(this)) {
            return false;
        }

        long now = SystemClock.uptimeMillis();
        if (singleTap) {
            // Fallback for ROMs that only report two separate 3-finger single taps.
            if (now - mLastSingleTap > 450) {
                mLastSingleTap = now;
                return true;
            }
            mLastSingleTap = 0;
            GestureLog.add(this, L.s(this, R.string.log_compat_double));
        }
        if (now - mLastTrigger < COOLDOWN_MS) {
            return true;
        }
        mLastTrigger = now;
        toggleZoom(event, -1, -1);
        return true;
    }

    private void toggleZoom(AccessibilityGestureEvent event, float nx, float ny) {
        MagnificationController mc = getMagnificationController();
        if (mc == null) {
            GestureLog.add(this, L.s(this, R.string.log_magnifier_unavailable));
            return;
        }
        if (mc.getScale() > 1.01f) {
            boolean ok = mc.reset(true);
            if (!ok && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ok = mc.resetCurrentMagnification(true);
            }
            mZoomed = false;
            mHandler.removeCallbacks(mReapply);
            GestureLog.add(this, L.s(this, ok ? R.string.log_zoom_off
                    : R.string.log_zoom_off_failed));
        } else {
            float[] center = resolveCenter(event);
            if (nx >= 0 && ny >= 0 && Prefs.getCenterMode(this) == 1) {
                DisplayMetrics dm = getResources().getDisplayMetrics();
                center = new float[]{nx * dm.widthPixels, ny * dm.heightPixels};
            }
            mLastCx = center[0];
            mLastCy = center[1];
            float scale = Prefs.getScale(this);
            mZoomed = applyZoom(mc, scale, mLastCx, mLastCy);
            GestureLog.add(this, L.s(this, R.string.log_zoom_on,
                    String.format(Locale.getDefault(), "%.1f", scale)));
        }
        if (Prefs.isVibrate(this)) {
            vibrate();
        }
        ZoomTileService.requestUpdate(this);
    }

    private boolean applyZoom(MagnificationController mc, float scale, float cx, float cy) {
        float[] center = normalizeCenter(cx, cy);
        boolean ok = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            MagnificationConfig config = new MagnificationConfig.Builder()
                    .setMode(MagnificationConfig.MAGNIFICATION_MODE_FULLSCREEN)
                    .setActivated(true)
                    .setScale(scale)
                    .setCenterX(center[0])
                    .setCenterY(center[1])
                    .build();
            ok = mc.setMagnificationConfig(config, true);
        }
        if (!ok) {
            // Legacy controllers are still honoured by some OEM frameworks even on API 33+.
            ok = mc.setScale(scale, true);
            mc.setCenter(center[0], center[1], true);
        }
        android.util.Log.d("ZoomTap", "applyZoom scale=" + scale + " result=" + ok);
        GestureLog.add(this, L.s(this, ok ? R.string.log_magnify_ok
                    : R.string.log_magnify_denied));
        return ok;
    }

    private float[] resolveCenter(AccessibilityGestureEvent event) {
        if (Prefs.getCenterMode(this) != 1 || event == null) {
            return screenCenter();
        }
        try {
            List<MotionEvent> events = event.getMotionEvents();
            if (events != null) {
                for (int i = events.size() - 1; i >= 0; i--) {
                    MotionEvent m = events.get(i);
                    if (m == null || m.getPointerCount() < 3) {
                        continue;
                    }
                    float sx = 0;
                    float sy = 0;
                    int n = m.getPointerCount();
                    for (int p = 0; p < n; p++) {
                        sx += m.getX(p);
                        sy += m.getY(p);
                    }
                    DisplayMetrics dm = getResources().getDisplayMetrics();
                    float cx = sx / n;
                    float cy = sy / n;
                    if (cx >= 0 && cy >= 0
                            && cx <= dm.widthPixels * 1.2f && cy <= dm.heightPixels * 1.2f) {
                        return new float[]{cx, cy};
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return screenCenter();
    }

    private float[] screenCenter() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return new float[]{dm.widthPixels / 2f, dm.heightPixels / 2f};
    }

    private float[] normalizeCenter(float cx, float cy) {
        if (cx < 0 || cy < 0) {
            return screenCenter();
        }
        return new float[]{cx, cy};
    }

    private void vibrate() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(35);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null
                && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && mZoomed
                && Prefs.isKeepZoom(this)) {
            mHandler.removeCallbacks(mReapply);
            mHandler.postDelayed(mReapply, 350);
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        mHandler.removeCallbacks(mReapply);
        unregisterTouchController();
        try {
            ShizukuTouchBridge.get(this).stop();
        } catch (Throwable ignored) {
        }
        if (mDebugReceiver != null) {
            try {
                unregisterReceiver(mDebugReceiver);
            } catch (Throwable ignored) {
            }
            mDebugReceiver = null;
        }
        // Best effort: never leave the display magnified when the service goes away.
        try {
            MagnificationController mc = getMagnificationController();
            if (mc != null && mc.getScale() > 1.01f) {
                mc.reset(true);
            }
        } catch (Throwable ignored) {
        }
        mConnected = false;
        mZoomed = false;
        if (sInstance == this) {
            sInstance = null;
        }
        super.onDestroy();
    }

    private static String gestureName(Context c, int id) {
        switch (id) {
            case GESTURE_PASSTHROUGH:
                return L.s(c, R.string.gesture_passthrough);
            case GESTURE_UNKNOWN:
                return L.s(c, R.string.gesture_unknown);
            case AccessibilityService.GESTURE_3_FINGER_DOUBLE_TAP:
                return L.s(c, R.string.gesture_3_double);
            case AccessibilityService.GESTURE_3_FINGER_SINGLE_TAP:
                return L.s(c, R.string.gesture_3_single);
            case AccessibilityService.GESTURE_3_FINGER_TRIPLE_TAP:
                return L.s(c, R.string.gesture_3_triple);
            case AccessibilityService.GESTURE_3_FINGER_DOUBLE_TAP_AND_HOLD:
                return L.s(c, R.string.gesture_3_double_hold);
            case AccessibilityService.GESTURE_2_FINGER_DOUBLE_TAP:
                return L.s(c, R.string.gesture_2_double);
            case AccessibilityService.GESTURE_DOUBLE_TAP:
                return L.s(c, R.string.gesture_1_double);
            case AccessibilityService.GESTURE_3_FINGER_SWIPE_UP:
                return L.s(c, R.string.gesture_3_up);
            case AccessibilityService.GESTURE_3_FINGER_SWIPE_DOWN:
                return L.s(c, R.string.gesture_3_down);
            case AccessibilityService.GESTURE_3_FINGER_SWIPE_LEFT:
                return L.s(c, R.string.gesture_3_left);
            case AccessibilityService.GESTURE_3_FINGER_SWIPE_RIGHT:
                return L.s(c, R.string.gesture_3_right);
            default:
                return L.s(c, R.string.gesture_other);
        }
    }
}
