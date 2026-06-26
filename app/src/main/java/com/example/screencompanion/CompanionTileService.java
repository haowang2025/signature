package com.example.screencompanion;

import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class CompanionTileService extends TileService {
    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setLabel("给 Ta 看一眼");
            if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle("短按分享屏幕");
            tile.setState(Tile.STATE_ACTIVE);
            tile.updateTile();
        }
    }

    @Override
    public void onClick() {
        super.onClick();
        Intent i = new Intent(this, CaptureService.class);
        i.setAction(Actions.ACTION_CAPTURE_ONCE);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        Tile tile = getQsTile();
        if (tile != null) {
            if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle("已分享");
            tile.updateTile();
        }
    }
}
