package it.geniola.apritisedano;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Receiver per gestire i trigger asincroni (Android Auto / Voice Assistant).
 * Evita di lanciare UI e avvia direttamente il servizio in background.
 */
public class ApritiSedanoReceiver extends BroadcastReceiver {
    private static final String TAG = "ApritiSedanoReceiver";
    public static final String ACTION_OPEN_BOX = "it.geniola.apritisedano.ACTION_OPEN_BOX";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "Ricevuto Intent: " + action);

        if (ACTION_OPEN_BOX.equals(action)) {
            Intent serviceIntent = new Intent(context, ApritiSedanoService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        }
    }
}