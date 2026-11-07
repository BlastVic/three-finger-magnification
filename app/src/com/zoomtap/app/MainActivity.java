package com.zoomtap.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity {

    private TextView tvServiceStatus;
    private TextView tvZoomStatus;
    private TextView tvScale;
    private TextView tvLog;
    private Switch swEnabled;
    private Switch swVibrate;
    private Switch swKeepZoom;
    private Switch swZeroInterference;
    private SeekBar sbScale;
    private RadioGroup rgCenter;

    private BroadcastReceiver logReceiver;

    private final Runnable mRefresh = new Runnable() {
        @Override
        public void run() {
            if (!isFinishing()) {
                refreshStatus();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvServiceStatus = findViewById(R.id.tv_service_status);
        tvZoomStatus = findViewById(R.id.tv_zoom_status);
        tvScale = findViewById(R.id.tv_scale);
        tvLog = findViewById(R.id.tv_log);
        swEnabled = findViewById(R.id.sw_enabled);
        swVibrate = findViewById(R.id.sw_vibrate);
        swKeepZoom = findViewById(R.id.sw_keep_zoom);
        swZeroInterference = findViewById(R.id.sw_zero_interference);
        sbScale = findViewById(R.id.sb_scale);
        rgCenter = findViewById(R.id.rg_center);

        swEnabled.setChecked(Prefs.isEnabled(this));
        swVibrate.setChecked(Prefs.isVibrate(this));
        swKeepZoom.setChecked(Prefs.isKeepZoom(this));
        swZeroInterference.setChecked(Prefs.isZeroInterference(this));
        rgCenter.check(Prefs.getCenterMode(this) == 1
                ? R.id.rb_center_gesture : R.id.rb_center_screen);

        float scale = Prefs.getScale(this);
        sbScale.setProgress(Math.round((scale - 1.5f) * 10f));
        tvScale.setText(String.format(Locale.getDefault(), "%.1f×", scale));

        swEnabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                Prefs.setEnabled(MainActivity.this, checked);
                if (checked) {
                    ZoomAccessibilityService.refresh();
                } else {
                    ZoomAccessibilityService.resetIfDisabled(MainActivity.this);
                }
                ZoomTileService.requestUpdate(MainActivity.this);
                refreshStatus();
            }
        });

        swVibrate.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                Prefs.setVibrate(MainActivity.this, checked);
            }
        });

        swKeepZoom.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                Prefs.setKeepZoom(MainActivity.this, checked);
            }
        });

        swZeroInterference.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                Prefs.setZeroInterference(MainActivity.this, checked);
                ZoomAccessibilityService.refresh();
                toast(L.s(MainActivity.this, checked
                        ? R.string.toast_zero_on : R.string.toast_zero_off));
                refreshStatus();
            }
        });

        rgCenter.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                Prefs.setCenterMode(MainActivity.this,
                        checkedId == R.id.rb_center_gesture ? 1 : 0);
            }
        });

        sbScale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float s = 1.5f + progress * 0.1f;
                tvScale.setText(String.format(Locale.getDefault(), "%.1f×", s));
                Prefs.setScale(MainActivity.this, s);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                ZoomAccessibilityService.updateScale(MainActivity.this);
            }
        });

        findViewById(R.id.btn_open_accessibility).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAccessibilitySettings();
            }
        });

        findViewById(R.id.btn_test).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!ZoomAccessibilityService.isConnected()) {
                    toast(L.s(MainActivity.this, R.string.toast_enable_service));
                    return;
                }
                ZoomAccessibilityService.toggleFromUi(MainActivity.this);
                refreshStatus();
            }
        });

        findViewById(R.id.btn_battery).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                } catch (Exception e) {
                    toast(L.s(MainActivity.this, R.string.toast_battery_fail));
                }
            }
        });

        findViewById(R.id.btn_system_zoom).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toast(L.s(MainActivity.this, R.string.toast_system_zoom));
                openAccessibilitySettings();
            }
        });

        findViewById(R.id.btn_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                GestureLog.clear(MainActivity.this);
                tvLog.setText(GestureLog.text(MainActivity.this));
            }
        });

        refreshStatus();
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            toast(L.s(this, R.string.toast_settings_fail));
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void refreshStatus() {
        boolean connected = ZoomAccessibilityService.isConnected();
        boolean bound = serviceEnabledInSettings();
        String mode = L.s(this, Prefs.isZeroInterference(this)
                ? R.string.mode_zero : R.string.mode_takeover);
        String shizuku;
        try {
            shizuku = ShizukuTouchBridge.get(this).getState();
        } catch (Throwable t) {
            shizuku = L.s(this, R.string.state_unavailable_ui, t);
        }
        // Right after an update or a process restart the binding is live in
        // Settings while sInstance has not been re-created yet. Show a neutral
        // "connecting" state instead of a misleading red "not enabled".
        String status = connected ? L.s(this, R.string.status_running, mode)
                : bound ? L.s(this, R.string.status_connecting)
                        : L.s(this, R.string.status_not_enabled);
        tvServiceStatus.setText(status + "\n" + L.s(this, R.string.status_shizuku, shizuku));
        tvServiceStatus.setTextColor(connected ? 0xFF1E8E3E
                : bound ? 0xFFB06000 : 0xFFD93025);
        tvZoomStatus.setText(L.s(this, ZoomAccessibilityService.isZoomed()
                ? R.string.zoom_active : R.string.zoom_normal));
        tvLog.setText(GestureLog.text(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        ZoomAccessibilityService.refresh();
        try {
            ShizukuTouchBridge.get(this).start();
        } catch (Throwable ignored) {
        }
        registerLogReceiver();
        refreshStatus();
        // Reconnection is asynchronous; refresh again once it has settled.
        tvServiceStatus.postDelayed(mRefresh, 900);
        tvServiceStatus.postDelayed(mRefresh, 2500);
    }

    private boolean serviceEnabledInSettings() {
        try {
            String s = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.contains("com.zoomtap.app");
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    protected void onPause() {
        tvServiceStatus.removeCallbacks(mRefresh);
        if (logReceiver != null) {
            try {
                unregisterReceiver(logReceiver);
            } catch (Exception ignored) {
            }
            logReceiver = null;
        }
        super.onPause();
    }

    private void registerLogReceiver() {
        if (logReceiver != null) {
            return;
        }
        logReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                refreshStatus();
            }
        };
        IntentFilter filter = new IntentFilter(GestureLog.ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(logReceiver, filter);
        }
    }
}
