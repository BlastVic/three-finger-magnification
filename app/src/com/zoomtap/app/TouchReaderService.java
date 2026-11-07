package com.zoomtap.app;

import android.content.Context;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs inside the Shizuku user service process (uid 2000 / shell).
 *
 * It opens the touchscreen's evdev node and reads the raw multi-touch stream.
 * Nothing is injected, consumed or intercepted: the kernel keeps delivering the
 * exact same events to the app and SystemUI, so single taps, long presses and
 * edge/navigation gestures keep their stock behaviour.
 *
 * Recognition is done here (rather than through AccessibilityService) because
 * the accessibility layer cannot observe touch events without entering touch
 * exploration, which rewrites single-finger semantics.
 */
public class TouchReaderService extends ITouchReader.Stub {

    private static final String TAG = "ZoomTapReader";
    private static final int DESTROY_TRANSACTION = 16777115;

    private static final int EV_SYN = 0x00;
    private static final int EV_ABS = 0x03;
    private static final int SYN_REPORT = 0x00;
    private static final int ABS_MT_SLOT = 0x2f;
    private static final int ABS_MT_TRACKING_ID = 0x39;
    private static final int ABS_MT_POSITION_X = 0x35;
    private static final int ABS_MT_POSITION_Y = 0x36;

    // 64-bit kernel struct input_event: timeval(16) + type(2) + code(2) + value(4)
    private static final int EVENT_SIZE = 24;
    private static final long TAP_MAX_MS = 600;
    // Real fingers rarely land twice within 400 ms; measured gaps on this
    // device went up to 961 ms. Keep enough headroom for a relaxed double tap.
    private static final long DOUBLE_TAP_MS = 1100;
    private static final float MOVE_SLOP_RATIO = 0.06f;

    private static final Pattern DEVICE = Pattern.compile("(/dev/input/event\\d+)");
    private static final Pattern MAX_X =
            Pattern.compile("0035\\s*:\\s*value[^\\n]*?max\\s+(\\d+)");
    private static final Pattern MAX_Y =
            Pattern.compile("0036\\s*:\\s*value[^\\n]*?max\\s+(\\d+)");

    // Framework-delivered fallback. A Binder callback handed to a Shizuku user
    // service dies as soon as the app process is frozen or recycled (very
    // aggressive on vivo). An explicit broadcast is delivered by ActivityManager,
    // which unfreezes / restarts the app process first, so it survives that.
    private static final String RECEIVER = "com.zoomtap.app/.TripleTapReceiver";
    private static final String ACTION_TAP = "com.zoomtap.app.SHIZUKU_TAP";
    private static final String ACTION_DIAG = "com.zoomtap.app.SHIZUKU_DIAG";

    public TouchReaderService() {
    }

    public TouchReaderService(Context ignored) {
    }

    private volatile boolean mRunning;
    private Thread mThread;
    private FileInputStream mInput;
    private ITouchCallback mCallback;
    private String mNode = "/dev/input/event5";
    private float mMaxX = 14399f;
    private float mMaxY = 23039f;

    private final boolean[] mActive = new boolean[32];
    private final float[] mX = new float[32];
    private final float[] mY = new float[32];
    // Per-slot start coordinates. Using "first active slot" instead makes the
    // reference jump to a different finger whenever a slot is released, which
    // is read as a large move and rejects perfectly valid three-finger taps.
    private final float[] mDownX = new float[32];
    private final float[] mDownY = new float[32];
    private final boolean[] mStartRecorded = new boolean[32];
    private long mFireSeq;
    private int mSlot;
    private int mActiveCount;
    private boolean mInGesture;
    private long mDownMs;
    private int mPeak;
    private float mStartX;
    private float mStartY;
    private float mCenterX;
    private float mCenterY;
    private boolean mMoved;
    private long mLastTripleMs;
    private int mRoundCount;
    private boolean mFirstEventLogged;
    private int mDiagSent;
    private long mLastDiagMs;
    // The shell process cannot read app resources, so the app passes the
    // device language through ITouchReader.start() for diagnostics.
    private boolean mZh;

    @Override
    public int start(String nodeHint, String language, ITouchCallback callback)
            throws RemoteException {
        mZh = "zh".equalsIgnoreCase(language);
        if (mRunning) {
            // The app process was replaced (per-app language change, force-stop)
            // while this daemon kept reading evdev. Refresh the Binder endpoint
            // and the language instead of reporting a spurious failure.
            mCallback = callback;
            return 0;
        }
        mCallback = callback;
        String[] info = probeDevice();
        if (info != null) {
            mNode = info[0];
            mMaxX = parseFloat(info[1], mMaxX);
            mMaxY = parseFloat(info[2], mMaxY);
        } else if (nodeHint != null && nodeHint.length() > 0) {
            mNode = nodeHint;
        }
        log(pick("设备=", "device=") + mNode + pick(" 轴=", " axes=")
                + (long) mMaxX + "x" + (long) mMaxY);
        try {
            mInput = new FileInputStream(mNode);
        } catch (Throwable t) {
            log(pick("打开失败：", "Open failed: ") + t);
            return 2;
        }
        resetState();
        mRunning = true;
        mThread = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "zoomtap-touch");
        mThread.start();
        return 0;
    }

    @Override
    public void stop() throws RemoteException {
        mRunning = false;
        Thread t = mThread;
        if (t != null) {
            t.interrupt();
        }
        try {
            if (mInput != null) {
                mInput.close();
            }
        } catch (Throwable ignored) {
        }
        mInput = null;
    }

    @Override
    public int status() throws RemoteException {
        return mRunning ? 1 : 0;
    }

    @Override
    public String probe() throws RemoteException {
        String[] info = probeDevice();
        return info == null ? "probe failed" : info[0] + " " + info[1] + "x" + info[2];
    }

    /** Shizuku calls this transaction to ask a user service to shut down. */
    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code == DESTROY_TRANSACTION) {
            try {
                stop();
            } catch (Throwable ignored) {
            }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ignored) {
                    }
                    System.exit(0);
                }
            }).start();
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }

    private void readLoop() {
        final FileInputStream in = mInput;
        if (in == null) {
            mRunning = false;
            return;
        }
        DataInputStream data = new DataInputStream(in);
        byte[] buf = new byte[EVENT_SIZE];
        while (mRunning) {
            try {
                data.readFully(buf);
            } catch (Throwable t) {
                if (mRunning) {
                    log(pick("读取中断：", "Read interrupted: ") + t);
                }
                break;
            }
            long sec = leLong(buf, 0);
            long usec = leLong(buf, 8);
            int type = leUShort(buf, 16);
            int code = leUShort(buf, 18);
            int value = leInt(buf, 20);
            long ms = sec * 1000L + usec / 1000L;
            if (!mFirstEventLogged) {
                mFirstEventLogged = true;
                log(pick("首个原始事件已读取 type=", "First raw event type=")
                        + type + " code=" + code + " value=" + value
                        + pick(" 时间=", " time=") + sec + "." + usec);
            }
            handle(type, code, value, ms);
        }
        mRunning = false;
    }

    private void handle(int type, int code, int value, long ms) {
        if (type == EV_ABS) {
            switch (code) {
                case ABS_MT_SLOT:
                    if (value >= 0 && value < mActive.length) {
                        mSlot = value;
                    }
                    break;
                case ABS_MT_TRACKING_ID: {
                    boolean down = value != -1;
                    if (mActive[mSlot] != down) {
                        mActive[mSlot] = down;
                        mActiveCount += down ? 1 : -1;
                        if (mActiveCount < 0) {
                            mActiveCount = 0;
                        }
                        if (!down) {
                            mStartRecorded[mSlot] = false;
                        }
                    }
                    break;
                }
                case ABS_MT_POSITION_X:
                    mX[mSlot] = value;
                    break;
                case ABS_MT_POSITION_Y:
                    mY[mSlot] = value;
                    break;
                default:
                    break;
            }
        } else if (type == EV_SYN && code == SYN_REPORT) {
            onFrame(ms);
        }
    }

    private void onFrame(long ms) {
        if (!mInGesture) {
            if (mActiveCount > 0) {
                mInGesture = true;
                mDownMs = ms;
                mPeak = mActiveCount;
                mStartX = firstX();
                mStartY = firstY();
                mCenterX = mStartX;
                mCenterY = mStartY;
                mMoved = false;
                trackMovement();
            }
        } else {
            if (mActiveCount > mPeak) {
                mPeak = mActiveCount;
            }
            trackMovement();
            if (mActiveCount >= 3) {
                mCenterX = averageX();
                mCenterY = averageY();
            }
        }
        if (mInGesture && mActiveCount == 0) {
            finishRound(ms);
        }
    }

    /** Compare every finger against its OWN down position, never against another finger. */
    private void trackMovement() {
        float sx = mMaxX * MOVE_SLOP_RATIO;
        float sy = mMaxY * MOVE_SLOP_RATIO;
        for (int i = 0; i < mActive.length; i++) {
            if (!mActive[i]) {
                mStartRecorded[i] = false;
                continue;
            }
            if (!mStartRecorded[i]) {
                mDownX[i] = mX[i];
                mDownY[i] = mY[i];
                mStartRecorded[i] = true;
            } else if (Math.abs(mX[i] - mDownX[i]) > sx
                    || Math.abs(mY[i] - mDownY[i]) > sy) {
                mMoved = true;
            }
        }
    }

    private void finishRound(long upMs) {
        long duration = upMs - mDownMs;
        boolean tripleTap = mPeak >= 3 && !mMoved && duration >= 0
                && duration <= TAP_MAX_MS;
        mRoundCount++;
        String round = pick("触摸#", "round#") + mRoundCount
                + pick(" 峰值=", " peak=") + mPeak
                + pick(" 时长=", " dur=") + duration + "ms"
                + pick(" 移动=", " moved=") + mMoved;
        // Every round goes to logcat; only multi-finger rounds (and the first
        // few taps, to prove the channel works) go to the on-screen log.
        Log.i("ZoomTapLog", "[shell] " + round);
        if (mPeak >= 2 || mRoundCount <= 5) {
            log(round);
        }
        if (tripleTap) {
            log(pick("三指轻点 峰值=", "three-finger tap peak=") + mPeak
                    + pick(" 时长=", " dur=") + duration + "ms");
            if (mLastTripleMs > 0 && upMs - mLastTripleMs <= DOUBLE_TAP_MS) {
                mLastTripleMs = 0;
                fire(mCenterX / mMaxX, mCenterY / mMaxY, mPeak);
            } else {
                mLastTripleMs = upMs;
            }
        } else if (mPeak < 3 || duration > TAP_MAX_MS) {
            // A clearly different intention (one/two fingers, or a long press)
            // breaks the pair. A three-finger round rejected only because of a
            // small jitter must NOT break it: fingers do not land perfectly
            // together, and losing the pair was a major cause of missed taps.
            mLastTripleMs = 0;
        }
        resetState();
    }

    private void fire(float nx, float ny, int peak) {
        // One id per recognised double tap. Both delivery channels carry it, so
        // the app can de-duplicate by id instead of by a time cooldown (which
        // used to swallow genuine, closely spaced double taps).
        long id = (System.currentTimeMillis() << 8) | (++mFireSeq & 0xFFL);
        log(pick("★ 三指双击 → 回调 App (id=", "★ triple double tap -> app (id=")
                + id + ")");
        ITouchCallback cb = mCallback;
        boolean delivered = false;
        if (cb != null) {
            try {
                cb.onTripleDoubleTap(id, nx, ny, peak);
                delivered = true;
            } catch (Throwable t) {
                Log.w(TAG, "callback failed", t);
            }
        }
        if (!delivered) {
            Log.w(TAG, "callback dead -> broadcast fallback");
        }
        // ActivityManager wakes / restarts the app process before delivering,
        // so this survives the vivo freezer.
        sendBroadcast(ACTION_TAP, id, nx, ny, peak, null);
    }

    private void resetState() {
        for (int i = 0; i < mActive.length; i++) {
            mActive[i] = false;
            mX[i] = 0;
            mY[i] = 0;
            mDownX[i] = 0;
            mDownY[i] = 0;
            mStartRecorded[i] = false;
        }
        mSlot = 0;
        mActiveCount = 0;
        mInGesture = false;
        mDownMs = 0;
        mPeak = 0;
        mMoved = false;
    }

    private float firstX() {
        for (int i = 0; i < mActive.length; i++) {
            if (mActive[i]) {
                return mX[i];
            }
        }
        return mStartX;
    }

    private float firstY() {
        for (int i = 0; i < mActive.length; i++) {
            if (mActive[i]) {
                return mY[i];
            }
        }
        return mStartY;
    }

    private float averageX() {
        float sum = 0;
        int n = 0;
        for (int i = 0; i < mActive.length; i++) {
            if (mActive[i]) {
                sum += mX[i];
                n++;
            }
        }
        return n > 0 ? sum / n : mStartX;
    }

    private float averageY() {
        float sum = 0;
        int n = 0;
        for (int i = 0; i < mActive.length; i++) {
            if (mActive[i]) {
                sum += mY[i];
                n++;
            }
        }
        return n > 0 ? sum / n : mStartY;
    }

    /** Picks the diagnostic wording matching the device language. */
    private String pick(String zh, String en) {
        return mZh ? zh : en;
    }

    private void log(String s) {
        // INFO rather than DEBUG: this ROM filters DEBUG lines out of logcat.
        Log.i("ZoomTapLog", "[shell] " + s);
        ITouchCallback cb = mCallback;
        if (cb != null) {
            try {
                cb.onLog(s);
                return;
            } catch (Throwable ignored) {
            }
        }
        long now = System.currentTimeMillis();
        if (mDiagSent < 24 && now - mLastDiagMs > 300) {
            mLastDiagMs = now;
            mDiagSent++;
            sendBroadcast(ACTION_DIAG, 0L, 0f, 0f, 0, s);
        }
    }

    /**
     * `am` takes ~100-300 ms, so it must never run on the evdev read thread
     * (otherwise the second tap of the double tap could be lost).
     */
    private void sendBroadcast(final String action, final long id,
                               final float nx, final float ny,
                               final int peak, final String message) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    java.util.ArrayList<String> cmd = new java.util.ArrayList<String>();
                    cmd.add("/system/bin/am");
                    cmd.add("broadcast");
                    cmd.add("-n");
                    cmd.add(RECEIVER);
                    cmd.add("-a");
                    cmd.add(action);
                    cmd.add("-f");
                    cmd.add("0x00000020"); // FLAG_INCLUDE_STOPPED_PACKAGES
                    if (message != null) {
                        cmd.add("--es");
                        cmd.add("msg");
                        cmd.add(message);
                    } else {
                        cmd.add("--el");
                        cmd.add("id");
                        cmd.add(Long.toString(id));
                        cmd.add("--ef");
                        cmd.add("x");
                        cmd.add(Float.toString(nx));
                        cmd.add("--ef");
                        cmd.add("y");
                        cmd.add(Float.toString(ny));
                        cmd.add("--ei");
                        cmd.add("peak");
                        cmd.add(Integer.toString(peak));
                    }
                    Process p = Runtime.getRuntime().exec(
                            cmd.toArray(new String[cmd.size()]));
                    drain(p.getInputStream());
                    drain(p.getErrorStream());
                    p.waitFor();
                } catch (Throwable t) {
                    Log.w(TAG, "broadcast failed", t);
                }
            }
        }, "zoomtap-am").start();
    }

    private static void drain(java.io.InputStream in) {
        try {
            byte[] b = new byte[256];
            while (in.read(b) != -1) {
                // discard
            }
            in.close();
        } catch (Throwable ignored) {
        }
    }

    /**
     * sysfs and /proc/bus/input/devices are not readable by the shell, but
     * getevent -p opens each node with ioctl and prints its capabilities.
     * A real touchscreen reports ABS_MT_TRACKING_ID (0039) plus X/Y axes.
     */
    private static String[] probeDevice() {
        BufferedReader br = null;
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"/system/bin/getevent", "-p"});
            br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            String node = null;
            StringBuilder block = new StringBuilder();
            String[] found = null;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("add device")) {
                    found = parseBlock(node, block.toString());
                    if (found != null) {
                        break;
                    }
                    block.setLength(0);
                    Matcher m = DEVICE.matcher(line);
                    node = m.find() ? m.group(1) : null;
                } else {
                    block.append(line).append('\n');
                }
            }
            if (found == null) {
                found = parseBlock(node, block.toString());
            }
            return found;
        } catch (Throwable t) {
            Log.w(TAG, "probe failed", t);
            return null;
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Throwable ignored) {
                }
            }
            if (p != null) {
                p.destroy();
            }
        }
    }

    private static String[] parseBlock(String node, String block) {
        if (node == null || !block.contains("0039")) {
            return null;
        }
        Matcher mx = MAX_X.matcher(block);
        Matcher my = MAX_Y.matcher(block);
        if (!mx.find() || !my.find()) {
            return null;
        }
        return new String[]{node, mx.group(1), my.group(1)};
    }

    private static float parseFloat(String s, float fallback) {
        try {
            return Float.parseFloat(s);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static long leLong(byte[] b, int o) {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8)
                | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24)
                | ((b[o + 4] & 0xFFL) << 32) | ((b[o + 5] & 0xFFL) << 40)
                | ((b[o + 6] & 0xFFL) << 48) | ((b[o + 7] & 0xFFL) << 56);
    }

    private static int leUShort(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static int leInt(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8)
                | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }
}
