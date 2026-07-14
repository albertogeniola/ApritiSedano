package it.geniola.apritisedano;

import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

public class ApritiSedanoTileService extends TileService {
    private static final String TAG = "ApritiSedanoTile";

    @Override
    public void onClick() {
        super.onClick();
        Log.d(TAG, "Tile premuto! Avvio sblocco...");
        
        // Se il dispositivo è bloccato, sbloccarlo prima di eseguire (opzionale)
        if (isLocked()) {
            unlockAndRun(this::startOpenerService);
        } else {
            startOpenerService();
        }
    }

    private void startOpenerService() {
        // Mostriamo visivamente che il tile sta lavorando
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_ACTIVE);
            tile.updateTile();
        }

        Intent serviceIntent = new Intent(this, ApritiSedanoService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        // Il tile tornerà inattivo automaticamente dopo 2 secondi
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (getQsTile() != null) {
                getQsTile().setState(Tile.STATE_INACTIVE);
                getQsTile().updateTile();
            }
        }, 2000);
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_INACTIVE);
            tile.updateTile();
        }
    }
}
