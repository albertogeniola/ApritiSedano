package it.geniola.apritisedano;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingEvent;

import java.util.List;

/**
 * Riceve eventi passivi del sistema operativo (0% consumo background) per armare (>3 km)
 * o disarmare (rientro e sosta a casa) anche quando Android Auto non era connesso.
 */
public class GeofenceBroadcastReceiver extends BroadcastReceiver {
    private static final String TAG = "GeofenceReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        GeofencingEvent event = GeofencingEvent.fromIntent(intent);
        if (event == null || event.hasError()) {
            return;
        }

        int transition = event.getGeofenceTransition();
        List<Geofence> triggering = event.getTriggeringGeofences();
        if (triggering == null) return;

        for (Geofence g : triggering) {
            String id = g.getRequestId();
            if (GeofenceHelper.GEOFENCE_ID_ARM_OUTER.equals(id)
                    && transition == Geofence.GEOFENCE_TRANSITION_EXIT) {
                if (!ProximityConfigManager.isArmed(context)) {
                    ProximityConfigManager.setArmed(context, true);
                    Log.i(TAG, "Geofence EXIT (>3km): Rientro automatico ARMATO.");
                    DebugLogger.getInstance().addLog(new DebugLogEntry(
                            System.currentTimeMillis(), false, "GEOFENCE",
                            "Uscita raggio 3km rilevata -> Rientro ARMATO"
                    ));
                }
            } else if (GeofenceHelper.GEOFENCE_ID_DISARM_HOME.equals(id)
                    && transition == Geofence.GEOFENCE_TRANSITION_DWELL) {
                if (ProximityConfigManager.isArmed(context)) {
                    ProximityConfigManager.setArmed(context, false);
                    Log.i(TAG, "Geofence DWELL (Casa): Rientro automatico DISARMATO per sosta a casa.");
                    DebugLogger.getInstance().addLog(new DebugLogEntry(
                            System.currentTimeMillis(), false, "GEOFENCE",
                            "Sosta a casa rilevata -> Rientro DISARMATO"
                    ));
                }
            }
        }
    }
}
