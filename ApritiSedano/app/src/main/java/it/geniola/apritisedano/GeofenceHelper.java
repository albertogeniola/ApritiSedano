package it.geniola.apritisedano;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingClient;
import com.google.android.gms.location.GeofencingRequest;
import com.google.android.gms.location.LocationServices;

import java.util.ArrayList;
import java.util.List;

/**
 * Registra geofence passivi hardware-offloaded (0% batteria, nessun servizio attivo):
 * - GEOFENCE_ARM_OUTER: arma il sistema quando si esce oltre la soglia (es. 3 km) anche senza Android Auto.
 * - GEOFENCE_DISARM_HOME: disarma il sistema se si rientra e si sosta a casa (80 m per 3 min) senza Android Auto.
 */
public class GeofenceHelper {
    private static final String TAG = "GeofenceHelper";

    public static final String GEOFENCE_ID_ARM_OUTER = "APRITI_ARM_OUTER";
    public static final String GEOFENCE_ID_DISARM_HOME = "APRITI_DISARM_HOME";
    private static final float HOME_DISARM_RADIUS_METERS = 80f;
    private static final int HOME_DWELL_DELAY_MS = 3 * 60 * 1000; // 3 minuti

    public static void syncGeofences(Context context) {
        Context appCtx = context.getApplicationContext();
        GeofencingClient client = LocationServices.getGeofencingClient(appCtx);
        PendingIntent pi = getGeofencePendingIntent(appCtx);

        boolean shouldBeActive = ProximityConfigManager.hasHomeLocation(appCtx)
                && ProximityConfigManager.hasAnyAutoOpenDevice(appCtx);

        if (!shouldBeActive) {
            client.removeGeofences(pi);
            Log.d(TAG, "Geofence passivi rimossi (nessun dispositivo auto-open attivo o posizione casa assente).");
            return;
        }

        if (!hasBackgroundLocationPermissions(appCtx)) {
            Log.w(TAG, "Permessi ACCESS_FINE_LOCATION / ACCESS_BACKGROUND_LOCATION mancanti per geofence passivi.");
            return;
        }

        double homeLat = ProximityConfigManager.getHomeLatitude(appCtx);
        double homeLon = ProximityConfigManager.getHomeLongitude(appCtx);
        float armRadius = (float) ProximityConfigManager.getArmDistanceMeters(appCtx);

        List<Geofence> geofenceList = new ArrayList<>();

        geofenceList.add(new Geofence.Builder()
                .setRequestId(GEOFENCE_ID_ARM_OUTER)
                .setCircularRegion(homeLat, homeLon, armRadius)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
                .build());

        geofenceList.add(new Geofence.Builder()
                .setRequestId(GEOFENCE_ID_DISARM_HOME)
                .setCircularRegion(homeLat, homeLon, HOME_DISARM_RADIUS_METERS)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setLoiteringDelay(HOME_DWELL_DELAY_MS)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_DWELL)
                .build());

        GeofencingRequest request = new GeofencingRequest.Builder()
                // Non forziamo INITIAL_TRIGGER_EXIT o DWELL per non sovrascrivere lo stato corrente al salvataggio
                .setInitialTrigger(0)
                .addGeofences(geofenceList)
                .build();

        try {
            client.removeGeofences(pi).addOnCompleteListener(task -> {
                try {
                    client.addGeofences(request, pi)
                            .addOnSuccessListener(unused -> Log.d(TAG, "Geofence passivi registrati (Arm=" + (int) armRadius + "m, Home=80m)."))
                            .addOnFailureListener(e -> Log.w(TAG, "Registrazione geofence fallita", e));
                } catch (SecurityException se) {
                    Log.w(TAG, "SecurityException addGeofences", se);
                }
            });
        } catch (SecurityException e) {
            Log.w(TAG, "SecurityException syncGeofences", e);
        }
    }

    private static PendingIntent getGeofencePendingIntent(Context context) {
        Intent intent = new Intent(context, GeofenceBroadcastReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // GeofencingClient richiede FLAG_MUTABLE per iniettare i dati di GeofencingEvent
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        return PendingIntent.getBroadcast(context, 401, intent, flags);
    }

    public static boolean hasBackgroundLocationPermissions(Context context) {
        boolean fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (!fine) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }
}
