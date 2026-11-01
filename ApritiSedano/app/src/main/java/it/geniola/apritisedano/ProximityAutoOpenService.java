package it.geniola.apritisedano;

import android.Manifest;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.connection.CarConnection;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Observer;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Servizio Foreground attivo ESCLUSIVAMENTE durante una sessione Android Auto.
 * Implementa il tracciamento GPS adattivo a 3 marce:
 * - Rilassato (60s / Balanced): lontano da casa (>3 km) o disarmato o in allontanamento.
 * - Granulare (15s / High Accuracy): armato, tra 3 km e 1 km.
 * - Real-Time (1.5s / High Accuracy): armato, sotto 1 km e in avvicinamento.
 * Quando la distanza scende sotto la soglia (es. 300 m) in avvicinamento con Android Auto attivo,
 * invia l'apertura via Shelly Cloud e mostra la notifica di benvenuto anche su Android Auto.
 */
public class ProximityAutoOpenService extends Service {
    private static final String TAG = "ProximityAutoOpenSvc";

    public static final String ACTION_START_FROM_BT = "it.geniola.apritisedano.ACTION_START_FROM_BT";
    public static final String ACTION_START_FROM_CAR_SESSION = "it.geniola.apritisedano.ACTION_START_FROM_CAR_SESSION";
    public static final String ACTION_CHECK_CAR_STATE = "it.geniola.apritisedano.ACTION_CHECK_CAR_STATE";
    public static final String ACTION_REFRESH_CONFIG = "it.geniola.apritisedano.ACTION_REFRESH_CONFIG";
    public static final String ACTION_STOP_MONITORING = "it.geniola.apritisedano.ACTION_STOP_MONITORING";

    private static final long AA_HANDSHAKE_GRACE_MS = 45_000L; // Attesa max handshake Wi-Fi Android Auto Wireless
    private static final long AA_DISCONNECT_DEBOUNCE_MS = 12_000L; // Anti-rimbalzo cavo USB

    private static final int GEAR_NONE = 0;
    private static final int GEAR_RELAXED = 1;  // 60s, min 180m
    private static final int GEAR_GRANULAR = 2; // 15s
    private static final int GEAR_REALTIME = 3; // 1.5s
    private static final int GEAR_INITIAL_PROBE = 4; // Singola acquisizione rapida iniziale

    private static final int DIR_UNKNOWN = 0;
    private static final int DIR_APPROACHING = 1;
    private static final int DIR_MOVING_AWAY = 2;

    private static final float APPROACH_HYSTERESIS_METERS = 20f;
    private static final float AWAY_HYSTERESIS_METERS = 25f;
    private static final float MAX_TRIGGER_GPS_ACCURACY_METERS = 35f;

    private FusedLocationProviderClient fusedClient;
    private CarConnection carConnection;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean isAndroidAutoActive = false;
    private int currentGear = GEAR_NONE;
    private int approachDirection = DIR_UNKNOWN;
    private float anchorDistanceMeters = -1f;
    private float sessionMaxDistanceMeters = -1f;
    private boolean initialHomeCheckDone = false;

    private final Runnable aaHandshakeTimeoutRunnable = () -> {
        if (!isAndroidAutoActive) {
            Log.i(TAG, "Android Auto non rilevato entro 45s dall'aggancio BT. Arresto servizio.");
            stopSelf();
        }
    };

    private final Runnable aaDisconnectDebounceRunnable = () -> {
        if (!isAndroidAutoActive) {
            Log.i(TAG, "Android Auto disconnesso: arresto monitoraggio GPS.");
            stopSelf();
        }
    };

    private final Observer<Integer> carConnectionObserver = connectionType -> {
        boolean connected = (connectionType != null) && (
                connectionType == CarConnection.CONNECTION_TYPE_PROJECTION
                        || connectionType == CarConnection.CONNECTION_TYPE_NATIVE
        );

        if (connected) {
            mainHandler.removeCallbacks(aaHandshakeTimeoutRunnable);
            mainHandler.removeCallbacks(aaDisconnectDebounceRunnable);
            if (!isAndroidAutoActive) {
                isAndroidAutoActive = true;
                Log.i(TAG, "Android Auto CONNESSO! Avvio macchina a stati GPS adattiva.");
                logDebug("AUTO-GPS", "Android Auto connesso -> Avvio monitoraggio rientro");
                onAndroidAutoActivated();
            }
        } else {
            if (isAndroidAutoActive) {
                isAndroidAutoActive = false;
                Log.i(TAG, "Android Auto non più attivo: attesa debounce 12s prima dello stop...");
                stopLocationUpdates();
                ProximityConfigManager.updateLiveTelemetry(
                        this, true, false, "In attesa riconnessione AA...", "--",
                        ProximityConfigManager.getLastDistanceMeters()
                );
                mainHandler.removeCallbacks(aaDisconnectDebounceRunnable);
                mainHandler.postDelayed(aaDisconnectDebounceRunnable, AA_DISCONNECT_DEBOUNCE_MS);
            }
        }
    };

    private final LocationCallback locationCallback = new LocationCallback() {
        @Override
        public void onLocationResult(@NonNull LocationResult result) {
            Location loc = result.getLastLocation();
            if (loc != null) {
                processLocationFix(loc);
            }
        }
    };

    public static void startMonitoring(Context context, String action) {
        if (context == null) return;
        if (!ProximityConfigManager.hasHomeLocation(context) || !ProximityConfigManager.hasAnyAutoOpenDevice(context)) {
            return;
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            Intent intent = new Intent(context, ProximityAutoOpenService.class);
            intent.setAction(action);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            Log.w(TAG, "Impossibile avviare ProximityAutoOpenService da background: " + e.getMessage());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        fusedClient = LocationServices.getFusedLocationProviderClient(this);
        carConnection = new CarConnection(this);
        carConnection.getType().observeForever(carConnectionObserver);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = (intent != null && intent.getAction() != null) ? intent.getAction() : ACTION_START_FROM_BT;

        if (ACTION_STOP_MONITORING.equals(action)) {
            Log.i(TAG, "Arresto manuale richiesto dall'utente.");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "ACCESS_FINE_LOCATION mancante: chiusura immediata servizio.");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!ProximityConfigManager.hasHomeLocation(this) || !ProximityConfigManager.hasAnyAutoOpenDevice(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            android.app.Notification monitorNotif = CarNotificationHelper.buildSilentMonitorNotification(
                    this,
                    isAndroidAutoActive ? "Android Auto attivo • Acquisizione posizione..." : "Verifica connessione Android Auto..."
            );
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                        CarNotificationHelper.MONITOR_NOTIFICATION_ID,
                        monitorNotif,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                );
            } else {
                startForeground(CarNotificationHelper.MONITOR_NOTIFICATION_ID, monitorNotif);
            }
        } catch (Exception e) {
            Log.e(TAG, "Errore startForeground location", e);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_START_FROM_CAR_SESSION.equals(action)) {
            // La CarSession Android Auto è sicuramente attiva!
            mainHandler.removeCallbacks(aaHandshakeTimeoutRunnable);
            if (!isAndroidAutoActive) {
                isAndroidAutoActive = true;
                onAndroidAutoActivated();
            }
        } else if (ACTION_REFRESH_CONFIG.equals(action)) {
            if (isAndroidAutoActive && currentGear != GEAR_NONE) {
                requestLastLocationAndReevaluate();
            }
        } else if (!isAndroidAutoActive) {
            ProximityConfigManager.updateLiveTelemetry(
                    this, true, false, "Verifica Android Auto...", "--", -1f
            );
            mainHandler.removeCallbacks(aaHandshakeTimeoutRunnable);
            mainHandler.postDelayed(aaHandshakeTimeoutRunnable, AA_HANDSHAKE_GRACE_MS);
        }

        return START_STICKY;
    }

    private void onAndroidAutoActivated() {
        anchorDistanceMeters = -1f;
        sessionMaxDistanceMeters = -1f;
        approachDirection = DIR_UNKNOWN;
        initialHomeCheckDone = false;

        // Acquisiamo subito la posizione iniziale per determinare istantaneamente la fascia corretta
        switchGpsGear(GEAR_INITIAL_PROBE);
        requestLastLocationAndReevaluate();
    }

    private void requestLastLocationAndReevaluate() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            fusedClient.getLastLocation().addOnSuccessListener(loc -> {
                if (loc != null && isAndroidAutoActive) {
                    long ageMs = System.currentTimeMillis() - loc.getTime();
                    if (ageMs < 60_000L) {
                        processLocationFix(loc);
                    }
                }
            });
        } catch (SecurityException ignored) {}
    }

    private void processLocationFix(@NonNull Location loc) {
        if (!isAndroidAutoActive) return;
        if (!ProximityConfigManager.hasHomeLocation(this) || !ProximityConfigManager.hasAnyAutoOpenDevice(this)) {
            stopSelf();
            return;
        }

        Location homeLoc = new Location("config");
        homeLoc.setLatitude(ProximityConfigManager.getHomeLatitude(this));
        homeLoc.setLongitude(ProximityConfigManager.getHomeLongitude(this));

        float distance = loc.distanceTo(homeLoc);
        float accuracy = loc.hasAccuracy() ? loc.getAccuracy() : 999f;

        int armThreshold = ProximityConfigManager.getArmDistanceMeters(this);
        int realtimeThreshold = ProximityConfigManager.getRealtimeDistanceMeters(this);
        boolean isArmed = ProximityConfigManager.isArmed(this);

        if (accuracy <= 120f) {
            if (sessionMaxDistanceMeters < 0f || distance > sessionMaxDistanceMeters) {
                sessionMaxDistanceMeters = distance;
            }

            // Protezione partenza da casa: se accendiamo l'auto direttamente nel garage/cortile (< 75m),
            // azzeriamo eventuali armamenti residui (es. rientro precedente avvenuto a piedi).
            if (!initialHomeCheckDone) {
                initialHomeCheckDone = true;
                if (distance < 75f && isArmed) {
                    isArmed = false;
                    ProximityConfigManager.setArmed(this, false);
                    logDebug("AUTO-GPS", "Partenza da casa rilevata (" + (int) distance + "m) -> Reset armamento residuo");
                }
            }

            // 1. Armamento oltre la soglia (es. 3+ km)
            if (!isArmed && distance >= armThreshold) {
                isArmed = true;
                ProximityConfigManager.setArmed(this, true);
                Log.i(TAG, "Soglia fuori casa superata (" + (int) distance + "m >= " + armThreshold + "m): ARMATO!");
                logDebug("AUTO-GPS", String.format(Locale.US, "Fuori casa (%.0fm >= %dm) -> Rientro ARMATO", distance, armThreshold));
            }
        }

        // 2. Calcolo direzione con isteresi anti-semaforo
        if (accuracy <= 80f) {
            if (anchorDistanceMeters < 0f) {
                anchorDistanceMeters = distance;
                approachDirection = DIR_UNKNOWN;
            } else if (distance <= anchorDistanceMeters - APPROACH_HYSTERESIS_METERS) {
                approachDirection = DIR_APPROACHING;
                anchorDistanceMeters = distance;
            } else if (distance >= anchorDistanceMeters + AWAY_HYSTERESIS_METERS) {
                approachDirection = DIR_MOVING_AWAY;
                anchorDistanceMeters = distance;
            }
        }

        // 3. Verifica condizioni di apertura automatica per i dispositivi Shelly abilitati
        if (isArmed
                && approachDirection == DIR_APPROACHING
                && accuracy <= MAX_TRIGGER_GPS_ACCURACY_METERS
                && !ProximityConfigManager.isCooldownActive(this)) {

            List<Device> devices = DeviceManager.getDevices(this);
            List<Device> toTrigger = new ArrayList<>();

            for (Device d : devices) {
                if (d.isShellyCloud() && d.isAutoOpenEnabled()) {
                    int radius = d.getAutoOpenRadiusMeters();
                    // Verifica sia che siamo dentro il raggio, sia che in questa guida proveniamo da fuori il raggio!
                    if (distance <= radius && sessionMaxDistanceMeters > (radius + 35f)) {
                        toTrigger.add(d);
                    }
                }
            }

            if (!toTrigger.isEmpty()) {
                // Disarmo atomico PRIMA di qualsiasi chiamata di rete (impossibile doppio invio)
                isArmed = false;
                ProximityConfigManager.recordAutoTriggerNow(this);
                executeAutoOpenTrigger(toTrigger, distance);
            }
        }

        // 4. Selezione dinamica della marcia GPS
        int desiredGear;
        if (!isArmed) {
            desiredGear = GEAR_RELAXED;
        } else if (distance <= realtimeThreshold && approachDirection != DIR_MOVING_AWAY) {
            desiredGear = GEAR_REALTIME;
        } else if (distance <= armThreshold && approachDirection != DIR_MOVING_AWAY) {
            desiredGear = GEAR_GRANULAR;
        } else {
            desiredGear = GEAR_RELAXED;
        }

        switchGpsGear(desiredGear);

        String gearStr = formatGearLabel(currentGear);
        String dirStr = formatDirectionLabel(approachDirection);
        ProximityConfigManager.updateLiveTelemetry(this, true, true, gearStr, dirStr, distance);

        String distFormatted = distance >= 1000f
                ? String.format(Locale.US, "%.2f km", distance / 1000f)
                : String.format(Locale.US, "%.0f m", distance);
        String armLabel = isArmed ? "Armato" : "In attesa (>3km)";
        String notifStatus = String.format(Locale.US, "%s • %s (%s) • %s", armLabel, distFormatted, dirStr, gearStr);
        CarNotificationHelper.updateSilentMonitorNotification(this, notifStatus);
    }

    private void executeAutoOpenTrigger(List<Device> targets, float currentDistanceMeters) {
        for (Device target : targets) {
            String welcomeMsg = target.resolveAutoOpenMessage();

            if (target.isAutoOpenDryRun()) {
                String dryMsg = String.format(Locale.US, "%s [Simulazione a %.0fm - Relè non azionato]", welcomeMsg, currentDistanceMeters);
                Log.i(TAG, "DRY-RUN Auto-Open per " + target.getName() + " a " + (int) currentDistanceMeters + "m");
                logDebug("AUTO-OPEN", "SIMULAZIONE (" + (int) currentDistanceMeters + "m): " + target.getName());
                CarNotificationHelper.showAutoOpenAlert(this, "🧪 Simulazione Rientro", dryMsg);
            } else {
                Log.i(TAG, "AUTO-OPEN INNESCATO per " + target.getName() + " a " + (int) currentDistanceMeters + "m!");
                logDebug("AUTO-OPEN", String.format(Locale.US, "TX Apertura automatica %s a %.0fm!", target.getName(), currentDistanceMeters));

                // 1. Notifica immediata Heads-Up su Android Auto + Telefono
                CarNotificationHelper.showAutoOpenAlert(this, "Bentornato a casa!", welcomeMsg);

                // 2. Comando HTTPS Shelly Cloud (con fino a 2 retry su eventuale cambio cella 4G)
                final Context appCtx = getApplicationContext();
                final String devName = target.getName();
                ShellyCloudClient.triggerDeviceAsync(target, 2, res -> {
                    if (!res.success) {
                        Log.e(TAG, "Errore apertura Shelly " + devName + ": " + res.message);
                        CarNotificationHelper.showAutoOpenAlert(
                                appCtx,
                                "⚠️ Apertura " + devName + " fallita",
                                res.message
                        );
                    }
                });
            }
        }
    }

    private void switchGpsGear(int newGear) {
        if (currentGear == newGear) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        LocationRequest request;
        if (newGear == GEAR_REALTIME) {
            request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1500L)
                    .setMinUpdateIntervalMillis(1000L)
                    .setWaitForAccurateLocation(false)
                    .build();
        } else if (newGear == GEAR_GRANULAR) {
            request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 15_000L)
                    .setMinUpdateIntervalMillis(8_000L)
                    .build();
        } else if (newGear == GEAR_INITIAL_PROBE) {
            request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3_000L)
                    .setMinUpdateIntervalMillis(1_500L)
                    .build();
        } else {
            // GEAR_RELAXED
            request = new LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 60_000L)
                    .setMinUpdateIntervalMillis(30_000L)
                    .setMinUpdateDistanceMeters(180f)
                    .build();
        }

        try {
            fusedClient.removeLocationUpdates(locationCallback);
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper());
            currentGear = newGear;
            Log.d(TAG, "Cambio marcia GPS -> " + formatGearLabel(newGear));
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException requestLocationUpdates", e);
        }
    }

    private void stopLocationUpdates() {
        if (fusedClient != null) {
            fusedClient.removeLocationUpdates(locationCallback);
        }
        currentGear = GEAR_NONE;
    }

    private static String formatGearLabel(int gear) {
        switch (gear) {
            case GEAR_REALTIME:
                return "Real-Time (1.5s)";
            case GEAR_GRANULAR:
                return "Granulare (15s)";
            case GEAR_INITIAL_PROBE:
                return "Aggancio GPS...";
            case GEAR_RELAXED:
                return "Rilassato (60s)";
            default:
                return "Inattivo";
        }
    }

    private static String formatDirectionLabel(int dir) {
        switch (dir) {
            case DIR_APPROACHING:
                return "In avvicinamento";
            case DIR_MOVING_AWAY:
                return "In allontanamento";
            default:
                return "Stabile";
        }
    }

    private static void logDebug(String tag, String msg) {
        try {
            DebugLogger.getInstance().addLog(new DebugLogEntry(System.currentTimeMillis(), false, tag, msg));
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
        stopLocationUpdates();
        if (carConnection != null) {
            carConnection.getType().removeObserver(carConnectionObserver);
        }
        ProximityConfigManager.updateLiveTelemetry(this, false, false, "Inattivo", "--", -1f);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
