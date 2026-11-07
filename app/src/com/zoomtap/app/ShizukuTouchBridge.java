package com.zoomtap.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.Locale;

import rikka.shizuku.Shizuku;

/**
 * App-side half of the raw-touch pipeline.
 *
 * Starts a Shizuku user service (shell identity) that only READS evdev events,
 * and receives a Binder callback whenever a three-finger double tap is seen.
 * Magnification itself is still performed by the accessibility service, which
 * already has the required capability.
 */
public final class ShizukuTouchBridge {

    private static final String TAG = "ZoomTapBridge";
    private static final int REQUEST_CODE = 7711;
    private static ShizukuTouchBridge sInstance;

    private final Context mApp;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private volatile String mState;
    private ITouchReader mReader;
    private Shizuku.UserServiceArgs mArgs;
    private boolean mBound;
    private boolean mBinding;
    private boolean mListenersAdded;
    private int mRetry;

    public static synchronized ShizukuTouchBridge get(Context c) {
        if (sInstance == null) {
            sInstance = new ShizukuTouchBridge(c.getApplicationContext());
        }
        return sInstance;
    }

    private ShizukuTouchBridge(Context c) {
        mApp = c;
        mState = L.s(c, R.string.state_not_initialized);
    }

    public String getState() {
        return mState;
    }

    private void state(int resId, Object... args) {
        mState = L.s(mApp, resId, args);
        Log.d(TAG, mState);
        GestureLog.add(mApp, L.s(mApp, R.string.log_shizuku, mState));
    }

    public synchronized void start() {
        if (!mListenersAdded) {
            mListenersAdded = true;
            try {
                Shizuku.addBinderReceivedListenerSticky(mBinderReceived);
                Shizuku.addBinderDeadListener(mBinderDead);
                Shizuku.addRequestPermissionResultListener(mPermissionResult);
            } catch (Throwable t) {
                state(R.string.state_listener_failed, t);
            }
        }
        ensure();
    }

    private final Shizuku.OnBinderReceivedListener mBinderReceived =
            new Shizuku.OnBinderReceivedListener() {
                @Override
                public void onBinderReceived() {
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            ensure();
                        }
                    });
                }
            };

    private final Shizuku.OnBinderDeadListener mBinderDead =
            new Shizuku.OnBinderDeadListener() {
                @Override
                public void onBinderDead() {
                    mBound = false;
                    mBinding = false;
                    mReader = null;
                    state(R.string.state_disconnected);
                    scheduleRetry();
                }
            };

    private final Shizuku.OnRequestPermissionResultListener mPermissionResult =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode != REQUEST_CODE) {
                        return;
                    }
                    boolean granted = grantResult == PackageManager.PERMISSION_GRANTED;
                    state(granted ? R.string.state_granted : R.string.state_denied);
                    if (granted) {
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                ensure();
                            }
                        });
                    }
                }
            };

    private final Runnable mRetryRunnable = new Runnable() {
        @Override
        public void run() {
            ensure();
        }
    };

    private synchronized void ensure() {
        if (mBound && mReader != null) {
            return;
        }
        if (mBinding) {
            return;
        }
        try {
            if (!Shizuku.pingBinder()) {
                state(R.string.state_not_running);
                scheduleRetry();
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                state(R.string.state_waiting_auth);
                Shizuku.requestPermission(REQUEST_CODE);
                return;
            }
        } catch (Throwable t) {
            state(R.string.state_unavailable, t);
            scheduleRetry();
            return;
        }
        bind();
    }

    private void bind() {
        mBinding = true;
        state(R.string.state_starting);
        try {
            mArgs = new Shizuku.UserServiceArgs(
                    new ComponentName(mApp, TouchReaderService.class))
                    .daemon(true)
                    .version(2)
                    .tag("zoomtap-touch-v2")
                    .processNameSuffix("touch");
            Shizuku.bindUserService(mArgs, mConnection);
        } catch (Throwable t) {
            mBinding = false;
            state(R.string.state_bind_failed, t);
            scheduleRetry();
        }
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mBinding = false;
            mBound = true;
            ITouchReader reader = ITouchReader.Stub.asInterface(service);
            mReader = reader;
            try {
                // The shell process cannot read app resources; pass the device
                // language so its diagnostics match the UI language.
                String lang = mApp.getResources().getConfiguration()
                        .getLocales().get(0).getLanguage();
                int rc = reader.start("/dev/input/event5", lang, mCallback);
                if (rc == 0) {
                    mRetry = 0;
                    state(R.string.state_listening);
                } else {
                    state(R.string.state_start_failed, rc);
                    scheduleRetry();
                }
            } catch (Throwable t) {
                state(R.string.state_start_error, t);
                scheduleRetry();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBinding = false;
            mBound = false;
            mReader = null;
            state(R.string.state_user_service_lost);
            scheduleRetry();
        }
    };

    private final ITouchCallback.Stub mCallback = new ITouchCallback.Stub() {
        @Override
        public void onTripleDoubleTap(long id, float nx, float ny, int peak) {
            ZoomAccessibilityService.onShizukuTripleDoubleTap(id, nx, ny, peak);
        }

        @Override
        public void onLog(String message) {
            GestureLog.add(mApp, "[shell] " + message);
        }
    };

    private void scheduleRetry() {
        if (mRetry > 6) {
            return;
        }
        mRetry++;
        mHandler.removeCallbacks(mRetryRunnable);
        mHandler.postDelayed(mRetryRunnable, 3000L * mRetry);
    }

    public synchronized void stop() {
        mHandler.removeCallbacks(mRetryRunnable);
        ITouchReader reader = mReader;
        if (reader != null) {
            try {
                reader.stop();
            } catch (Throwable ignored) {
            }
        }
        if (mBound && mArgs != null) {
            try {
                Shizuku.unbindUserService(mArgs, mConnection, true);
            } catch (Throwable ignored) {
            }
        }
        mBound = false;
        mBinding = false;
        mReader = null;
    }
}
