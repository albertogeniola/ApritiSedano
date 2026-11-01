package it.geniola.apritisedano;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * Intercetta l'aggancio Bluetooth dell'auto (esente dalle restrizioni implicit broadcast Android 15+)
 * e il riavvio del dispositivo per:
 * 1. Ri-sincronizzare i geofence passivi al boot.
 * 2. Filtrare con costo zero i dispositivi Bluetooth diversi dall'auto configurata.
 * 3. Avviare ProximityAutoOpenService che verificherà la reale proiezione Android Auto.
 */
public class CarConnectionReceiver extends BroadcastReceiver {
    private static final String TAG = "CarConnectionReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            GeofenceHelper.syncGeofences(context);
            return;
        }

        if (!ProximityConfigManager.hasHomeLocation(context) || !ProximityConfigManager.hasAnyAutoOpenDevice(context)) {
            return;
        }

        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
            BluetoothDevice btDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            String configuredCarMac = ProximityConfigManager.getCarBluetoothMac(context);

            if (configuredCarMac != null && !configuredCarMac.isEmpty()) {
                if (btDevice == null || !configuredCarMac.equalsIgnoreCase(btDevice.getAddress())) {
                    // Non è il Bluetooth dell'auto: uscita immediata (< 1ms, 0 consumo)
                    return;
                }
            }

            if (!hasLocationPermissionForBackgroundStart(context)) {
                Log.w(TAG, "Permessi posizione in background mancanti: impossibile avviare monitoraggio Android Auto.");
                return;
            }

            Log.i(TAG, "Connessione BT auto rilevata: verifica proiezione Android Auto...");
            ProximityAutoOpenService.startMonitoring(context, ProximityAutoOpenService.ACTION_START_FROM_BT);

        } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
            if (!ProximityConfigManager.isServiceRunning()) {
                return;
            }
            BluetoothDevice btDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            String configuredCarMac = ProximityConfigManager.getCarBluetoothMac(context);
            if (configuredCarMac != null && !configuredCarMac.isEmpty() && btDevice != null) {
                if (configuredCarMac.equalsIgnoreCase(btDevice.getAddress())) {
                    Log.i(TAG, "Bluetooth auto scollegato: notifica verifica chiusura a ProximityAutoOpenService.");
                    ProximityAutoOpenService.startMonitoring(context, ProximityAutoOpenService.ACTION_CHECK_CAR_STATE);
                }
            }
        }
    }

    private boolean hasLocationPermissionForBackgroundStart(Context context) {
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
