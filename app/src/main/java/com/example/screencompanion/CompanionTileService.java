package com.example.screencompanion;

import android.app.PendingIntent;
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
            if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle("打开后分享屏幕");
            tile.setState(Tile.STATE_ACTIVE);
            tile.updateTile();
        }
    }

    @Override
    public void onClick() {
        super.onClick();
        // Do not start CaptureService directly from Quick Settings. On recent Android
        // versions this can crash because background foreground-service starts are
        // restricted. Open MainActivity instead; it will check projection readiness.
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(Actions.ACTION_CAPTURE_ONCE);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                PendingIntent pi = PendingIntent.getActivity(
                        this,
                        31,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                );
                startActivityAndCollapse(pi);
            } else {
                startActivityAndCollapse(intent);
            }
        } catch (Exception ignored) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        }

        Tile tile = getQsTile();
        if (tile != null) {
            if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle("已打开");
            tile.updateTile();
        }
    }
}
