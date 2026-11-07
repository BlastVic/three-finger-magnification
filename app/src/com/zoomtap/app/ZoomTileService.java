package com.zoomtap.app;

import android.annotation.TargetApi;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile for turning the gesture on/off without opening the app. */
@TargetApi(Build.VERSION_CODES.N)
public class ZoomTileService extends TileService {

    @Override
    public void onStartListening() {
        updateTile();
    }

    @Override
    public void onClick() {
        boolean next = !Prefs.isEnabled(this);
        Prefs.setEnabled(this, next);
        if (next) {
            ZoomAccessibilityService.refresh();
        } else {
            ZoomAccessibilityService.resetIfDisabled(this);
        }
        updateTile();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        boolean on = Prefs.isEnabled(this) && ZoomAccessibilityService.isConnected();
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(getString(R.string.tile_label));
        tile.updateTile();
    }

    public static void requestUpdate(Context c) {
        try {
            requestListeningState(c, new ComponentName(c, ZoomTileService.class));
        } catch (Throwable ignored) {
        }
    }
}
