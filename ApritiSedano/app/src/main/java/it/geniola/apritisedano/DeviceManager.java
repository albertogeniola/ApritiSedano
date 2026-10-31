package it.geniola.apritisedano;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;

public class DeviceManager {
    private static final String PREFS_FILENAME = "apritisedano_devices_prefs";
    private static final String KEY_DEVICES = "devices_list";

    private static SharedPreferences getEncryptedSharedPreferences(Context context) throws GeneralSecurityException, IOException {
        MasterKey masterKey = new MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();

        try {
            return EncryptedSharedPreferences.create(
                    context,
                    PREFS_FILENAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            android.util.Log.e("DeviceManager", "Corrupted EncryptedSharedPreferences detected (Keystore key mismatch from reinstall/backup). Recreating clean prefs...", e);
            context.deleteSharedPreferences(PREFS_FILENAME);
            return EncryptedSharedPreferences.create(
                    context,
                    PREFS_FILENAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        }
    }

    public static synchronized List<Device> getDevices(Context context) {
        List<Device> devices = new ArrayList<>();
        try {
            SharedPreferences prefs = getEncryptedSharedPreferences(context);
            String jsonStr = prefs.getString(KEY_DEVICES, "[]");
            JSONArray jsonArray = new JSONArray(jsonStr);
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject obj = jsonArray.getJSONObject(i);
                devices.add(Device.fromJson(obj));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return devices;
    }

    public static synchronized void saveDevices(Context context, List<Device> devices) {
        try {
            JSONArray jsonArray = new JSONArray();
            for (Device d : devices) {
                jsonArray.put(d.toJson());
            }
            SharedPreferences prefs = getEncryptedSharedPreferences(context);
            prefs.edit().putString(KEY_DEVICES, jsonArray.toString()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static synchronized void addDevice(Context context, Device device) {
        List<Device> devices = getDevices(context);
        devices.removeIf(d -> d.getMacAddress().equalsIgnoreCase(device.getMacAddress()));
        devices.add(device);
        saveDevices(context, devices);
    }

    public static synchronized void removeDevice(Context context, String macAddress) {
        List<Device> devices = getDevices(context);
        devices.removeIf(d -> d.getMacAddress().equalsIgnoreCase(macAddress));
        saveDevices(context, devices);
    }

    public static synchronized Device getDeviceByMac(Context context, String macAddress) {
        if (macAddress == null) return null;
        List<Device> devices = getDevices(context);
        for (Device d : devices) {
            if (d.getMacAddress().equalsIgnoreCase(macAddress)) {
                return d;
            }
        }
        return null;
    }

    /**
     * Updates the persisted Master OTA status of a device only if it actually changed.
     * @return true if the value changed and was persisted, false otherwise.
     */
    public static synchronized boolean updateMasterStatus(Context context, String macAddress, boolean isMaster) {
        if (macAddress == null) return false;
        List<Device> devices = getDevices(context);
        for (Device d : devices) {
            if (d.getMacAddress().equalsIgnoreCase(macAddress)) {
                if (d.isMaster() != isMaster) {
                    d.setMaster(isMaster);
                    saveDevices(context, devices);
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    /**
     * Checks if this smartphone is configured as Master for at least one registered device.
     */
    public static synchronized boolean hasMasterDevice(Context context) {
        List<Device> devices = getDevices(context);
        for (Device d : devices) {
            if (d.isMaster()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Increments the rolling code counter for the given device MAC and persists it immediately.
     * @return The new incremented counter value, or -1 if device not found.
     */
    public static synchronized long incrementAndGetCounter(Context context, String macAddress) {
        if (macAddress == null) return -1;
        List<Device> devices = getDevices(context);
        for (Device d : devices) {
            if (d.getMacAddress().equalsIgnoreCase(macAddress)) {
                long nextCounter = d.getCounter() + 1L;
                d.setCounter(nextCounter);
                saveDevices(context, devices);
                return nextCounter;
            }
        }
        return -1;
    }
}
