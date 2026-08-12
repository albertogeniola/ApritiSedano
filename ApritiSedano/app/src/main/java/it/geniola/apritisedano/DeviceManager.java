package it.geniola.apritisedano;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONArray;
import org.json.JSONException;
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

        return EncryptedSharedPreferences.create(
                context,
                PREFS_FILENAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        );
    }

    public static List<Device> getDevices(Context context) {
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

    public static void saveDevices(Context context, List<Device> devices) {
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

    public static void addDevice(Context context, Device device) {
        List<Device> devices = getDevices(context);
        // Remove if exists with same MAC to update it
        devices.removeIf(d -> d.getMacAddress().equalsIgnoreCase(device.getMacAddress()));
        devices.add(device);
        saveDevices(context, devices);
    }

    public static void removeDevice(Context context, String macAddress) {
        List<Device> devices = getDevices(context);
        devices.removeIf(d -> d.getMacAddress().equalsIgnoreCase(macAddress));
        saveDevices(context, devices);
    }

    public static Device getDeviceByMac(Context context, String macAddress) {
        List<Device> devices = getDevices(context);
        for (Device d : devices) {
            if (d.getMacAddress().equalsIgnoreCase(macAddress)) {
                return d;
            }
        }
        return null;
    }
}
