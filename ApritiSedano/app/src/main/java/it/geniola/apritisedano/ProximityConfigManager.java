package it.geniola.apritisedano;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

/**
 * Gestisce in modo cifrato (AES256-GCM) la configurazione globale di rientro a casa
 * (coordinate Casa, filtro Bluetooth Auto, soglie di armamento/real-time, stato armato,
 * credenziali Shelly Cloud predefinite) e lo snapshot telemetrico live.
 */
public class ProximityConfigManager {
    private static final String TAG = "ProximityConfigMgr";
    private static final String PREFS_FILENAME = "apritisedano_proximity_prefs";

    public static final String ACTION_PROXIMITY_STATE_CHANGED = "it.geniola.apritisedano.ACTION_PROXIMITY_STATE_CHANGED";

    private static final String KEY_HAS_HOME_LOCATION = "has_home_location";
    private static final String KEY_HOME_LAT_BITS = "home_lat_bits";
    private static final String KEY_HOME_LON_BITS = "home_lon_bits";
    private static final String KEY_CAR_BT_MAC = "car_bt_mac";
    private static final String KEY_CAR_BT_NAME = "car_bt_name";
    private static final String KEY_ARM_DISTANCE_M = "arm_distance_m";
    private static final String KEY_REALTIME_DISTANCE_M = "realtime_distance_m";
    private static final String KEY_IS_ARMED = "is_armed";
    private static final String KEY_LAST_AUTO_TRIGGER_MS = "last_auto_trigger_ms";
    private static final String KEY_DEFAULT_SHELLY_SERVER = "default_shelly_server";
    private static final String KEY_DEFAULT_SHELLY_AUTH_KEY = "default_shelly_auth_key";

    public static final int DEFAULT_ARM_DISTANCE_METERS = 3000;
    public static final int DEFAULT_REALTIME_DISTANCE_METERS = 1000;
    public static final long AUTO_TRIGGER_COOLDOWN_MS = 3 * 60 * 1000L; // 3 minuti anti-rimbalzo

    // Live runtime telemetry (in-memory)
    private static volatile boolean serviceRunning = false;
    private static volatile boolean androidAutoConnected = false;
    private static volatile String currentGearLabel = "Inattivo";
    private static volatile String currentDirectionLabel = "--";
    private static volatile float lastDistanceMeters = -1f;

    private static SharedPreferences getPrefs(Context context) throws GeneralSecurityException, IOException {
        Context appCtx = context.getApplicationContext();
        MasterKey masterKey = new MasterKey.Builder(appCtx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();
        try {
            return EncryptedSharedPreferences.create(
                    appCtx,
                    PREFS_FILENAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            Log.e(TAG, "EncryptedSharedPreferences corrotte, ricreazione in corso...", e);
            appCtx.deleteSharedPreferences(PREFS_FILENAME);
            return EncryptedSharedPreferences.create(
                    appCtx,
                    PREFS_FILENAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        }
    }

    public static synchronized boolean hasHomeLocation(Context context) {
        try {
            return getPrefs(context).getBoolean(KEY_HAS_HOME_LOCATION, false);
        } catch (Exception e) {
            return false;
        }
    }

    public static synchronized double getHomeLatitude(Context context) {
        try {
            long bits = getPrefs(context).getLong(KEY_HOME_LAT_BITS, Double.doubleToRawLongBits(0.0));
            return Double.longBitsToDouble(bits);
        } catch (Exception e) {
            return 0.0;
        }
    }

    public static synchronized double getHomeLongitude(Context context) {
        try {
            long bits = getPrefs(context).getLong(KEY_HOME_LON_BITS, Double.doubleToRawLongBits(0.0));
            return Double.longBitsToDouble(bits);
        } catch (Exception e) {
            return 0.0;
        }
    }

    public static synchronized void setHomeLocation(Context context, double latitude, double longitude) {
        try {
            getPrefs(context).edit()
                    .putBoolean(KEY_HAS_HOME_LOCATION, true)
                    .putLong(KEY_HOME_LAT_BITS, Double.doubleToRawLongBits(latitude))
                    .putLong(KEY_HOME_LON_BITS, Double.doubleToRawLongBits(longitude))
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "Errore salvataggio coordinate casa", e);
        }
    }

    public static synchronized void clearHomeLocation(Context context) {
        try {
            getPrefs(context).edit()
                    .putBoolean(KEY_HAS_HOME_LOCATION, false)
                    .remove(KEY_HOME_LAT_BITS)
                    .remove(KEY_HOME_LON_BITS)
                    .putBoolean(KEY_IS_ARMED, false)
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "Errore rimozione coordinate casa", e);
        }
    }

    public static synchronized String getCarBluetoothMac(Context context) {
        try {
            return getPrefs(context).getString(KEY_CAR_BT_MAC, "");
        } catch (Exception e) {
            return "";
        }
    }

    public static synchronized String getCarBluetoothName(Context context) {
        try {
            return getPrefs(context).getString(KEY_CAR_BT_NAME, "");
        } catch (Exception e) {
            return "";
        }
    }

    public static synchronized void setCarBluetoothFilter(Context context, String mac, String name) {
        try {
            getPrefs(context).edit()
                    .putString(KEY_CAR_BT_MAC, mac != null ? mac.trim() : "")
                    .putString(KEY_CAR_BT_NAME, name != null ? name.trim() : "")
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "Errore salvataggio filtro BT auto", e);
        }
    }

    public static synchronized int getArmDistanceMeters(Context context) {
        try {
            int val = getPrefs(context).getInt(KEY_ARM_DISTANCE_M, DEFAULT_ARM_DISTANCE_METERS);
            return Math.max(500, val);
        } catch (Exception e) {
            return DEFAULT_ARM_DISTANCE_METERS;
        }
    }

    public static synchronized void setArmDistanceMeters(Context context, int meters) {
        try {
            getPrefs(context).edit()
                    .putInt(KEY_ARM_DISTANCE_M, Math.max(500, Math.min(50000, meters)))
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "Errore salvataggio distanza armamento", e);
        }
    }

    public static synchronized int getRealtimeDistanceMeters(Context context) {
        try {
            int val = getPrefs(context).getInt(KEY_REALTIME_DISTANCE_M, DEFAULT_REALTIME_DISTANCE_METERS);
            return Math.max(200, val);
        } catch (Exception e) {
            return DEFAULT_REALTIME_DISTANCE_METERS;
        }
    }

    public static synchronized void setRealtimeDistanceMeters(Context context, int meters) {
        try {
            getPrefs(context).edit()
                    .putInt(KEY_REALTIME_DISTANCE_M, Math.max(200, Math.min(10000, meters)))
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "Errore salvataggio distanza real-time", e);
        }
    }

    public static synchronized boolean isArmed(Context context) {
        try {
            return getPrefs(context).getBoolean(KEY_IS_ARMED, false);
        } catch (Exception e) {
            return false;
        }
    }

    public static synchronized void setArmed(Context context, boolean armed) {
        try {
            boolean prev = isArmed(context);
            getPrefs(context).edit().putBoolean(KEY_IS_ARMED, armed).apply();
            if (prev != armed) {
                broadcastStateChanged(context);
            }
        } catch (Exception e) {
            Log.e(TAG, "Errore salvataggio stato armato", e);
        }
    }

    public static synchronized long getLastAutoTriggerMs(Context context) {
        try {
            return getPrefs(context).getLong(KEY_LAST_AUTO_TRIGGER_MS, 0L);
        } catch (Exception e) {
            return 0L;
        }
    }

    public static synchronized void recordAutoTriggerNow(Context context) {
        try {
            getPrefs(context).edit()
                    .putBoolean(KEY_IS_ARMED, false)
                    .putLong(KEY_LAST_AUTO_TRIGGER_MS, System.currentTimeMillis())
                    .apply();
            broadcastStateChanged(context);
        } catch (Exception e) {
            Log.e(TAG, "Errore registrazione auto-trigger", e);
        }
    }

    public static synchronized boolean isCooldownActive(Context context) {
        long last = getLastAutoTriggerMs(context);
        return last > 0L && (System.currentTimeMillis() - last) < AUTO_TRIGGER_COOLDOWN_MS;
    }

    public static synchronized void saveDefaultShellyCredentials(Context context, String serverUri, String authKey) {
        try {
            getPrefs(context).edit()
                    .putString(KEY_DEFAULT_SHELLY_SERVER, ShellyCloudClient.normalizeServerUri(serverUri))
                    .putString(KEY_DEFAULT_SHELLY_AUTH_KEY, authKey != null ? authKey.trim() : "")
                    .apply();
        } catch (Exception ignored) {}
    }

    public static synchronized String getDefaultShellyServer(Context context) {
        try {
            return getPrefs(context).getString(KEY_DEFAULT_SHELLY_SERVER, "");
        } catch (Exception e) {
            return "";
        }
    }

    public static synchronized String getDefaultShellyAuthKey(Context context) {
        try {
            return getPrefs(context).getString(KEY_DEFAULT_SHELLY_AUTH_KEY, "");
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean hasAnyAutoOpenDevice(Context context) {
        List<Device> devices = DeviceManager.getDevices(context);
        for (Device d : devices) {
            if (d.isShellyCloud() && d.isAutoOpenEnabled()) {
                return true;
            }
        }
        return false;
    }

    public static void updateLiveTelemetry(
            Context context,
            boolean running,
            boolean aaConnected,
            String gearLabel,
            String directionLabel,
            float distanceMeters
    ) {
        serviceRunning = running;
        androidAutoConnected = aaConnected;
        currentGearLabel = gearLabel != null ? gearLabel : "Inattivo";
        currentDirectionLabel = directionLabel != null ? directionLabel : "--";
        lastDistanceMeters = distanceMeters;
        broadcastStateChanged(context);
    }

    public static boolean isServiceRunning() {
        return serviceRunning;
    }

    public static boolean isAndroidAutoConnected() {
        return androidAutoConnected;
    }

    public static String getCurrentGearLabel() {
        return currentGearLabel;
    }

    public static String getCurrentDirectionLabel() {
        return currentDirectionLabel;
    }

    public static float getLastDistanceMeters() {
        return lastDistanceMeters;
    }

    public static void broadcastStateChanged(Context context) {
        if (context == null) return;
        Intent intent = new Intent(ACTION_PROXIMITY_STATE_CHANGED);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }
}
