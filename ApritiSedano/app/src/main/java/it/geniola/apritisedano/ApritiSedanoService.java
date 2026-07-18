package it.geniola.apritisedano;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Foreground Service "Faro ed Eco" per gestione BLE asincrona.
 */
public class ApritiSedanoService extends Service {
    private static final String TAG = "ApritiSedanoService";
    public static final String ACTION_SERVICE_STOPPED = "it.geniola.apritisedano.SERVICE_STOPPED";
    private static final String CHANNEL_ID = "ApritiSedanoChannel";
    private static final int NOTIFICATION_ID = 101;
    private static final long TIMEOUT_MS = 60000; // 1 minuto
    
    // UUID esempio del microcontrollore
    private static final UUID TARGET_SERVICE_UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0");

    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager.getAdapter();
        if (adapter != null) {
            advertiser = adapter.getBluetoothLeAdvertiser();
            scanner = adapter.getBluetoothLeScanner();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_title)));
        
        boolean isSyncAction = intent != null && "ACTION_SYNC_TIME".equals(intent.getAction());
        startRadioOperations(isSyncAction);
        
        long timeout = TIMEOUT_MS; // Default per sincronizzazione
        if (!isSyncAction) {
            long currentTsSeconds = System.currentTimeMillis() / 1000L;
            long remainingSeconds = 30 - (currentTsSeconds % 30);
            timeout = (remainingSeconds * 1000L) + 500L; // +500ms di margine
            Log.d(TAG, "Timeout dinamico impostato a " + timeout + " ms (fine validità TOTP)");
        }
        
        timeoutHandler.postDelayed(this::stopSelf, timeout);
        
        return START_NOT_STICKY;
    }

    private void startRadioOperations(boolean isSyncAction) {
        if (!checkBluetoothPermissions()) {
            Log.e(TAG, "Permessi Bluetooth non concessi");
            notifyCompletion(null);
            stopSelf();
            return;
        }

        if (advertiser == null || scanner == null) {
            Log.e(TAG, "Hardware Bluetooth non pronto");
            stopSelf();
            return;
        }

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(false)
                .setTimeout(0) // Gestito manualmente dal servizio
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build();

        AdvertiseData data;
        if (isSyncAction) {
            String secretKey = SecretStore.getSecretKey(this);
            if (secretKey == null || secretKey.trim().isEmpty()) {
                Log.e(TAG, "Nessuna chiave segreta configurata. Impossibile sincronizzare.");
                notifyCompletion(null);
                stopSelf();
                return;
            }
            int ts = (int)(System.currentTimeMillis() / 1000L);
            byte[] syncPayload = TOTPGenerator.generateTimeSyncPayload(secretKey, ts);
            if (syncPayload == null) {
                Log.e(TAG, "Formato chiave non valido. Impossibile generare payload.");
                new Handler(Looper.getMainLooper()).post(() -> 
                    android.widget.Toast.makeText(this, "La chiave segreta non è valida (deve essere Base32).", android.widget.Toast.LENGTH_LONG).show()
                );
                notifyCompletion(null);
                stopSelf();
                return;
            }
            Log.d(TAG, "Avvio Advertising con Time Sync Payload");
            DebugLogger.getInstance().addLog(new DebugLogEntry(System.currentTimeMillis(), true, "", "Sincronizzazione Ora (TX)"));
            data = new AdvertiseData.Builder()
                    .addServiceUuid(new ParcelUuid(TARGET_SERVICE_UUID))
                    .addManufacturerData(0x02E5, syncPayload)
                    .build();
        } else {
            String secretKey = SecretStore.getSecretKey(this);
            if (secretKey == null || secretKey.trim().isEmpty()) {
                Log.e(TAG, "Nessuna chiave segreta configurata. Impossibile inviare comando.");
                new Handler(Looper.getMainLooper()).post(() -> 
                    android.widget.Toast.makeText(this, "Nessuna chiave configurata! Impostala nella schermata principale.", android.widget.Toast.LENGTH_LONG).show()
                );
                notifyCompletion(null);
                stopSelf();
                return;
            }
            String totp = TOTPGenerator.generateTOTP(secretKey);
            if (totp == null) {
                Log.e(TAG, "Formato chiave non valido. Impossibile generare TOTP.");
                new Handler(Looper.getMainLooper()).post(() -> 
                    android.widget.Toast.makeText(this, "La chiave segreta non è valida (deve essere Base32).", android.widget.Toast.LENGTH_LONG).show()
                );
                notifyCompletion(null);
                stopSelf();
                return;
            }
            Log.d(TAG, "Avvio Advertising con TOTP: " + totp);
            DebugLogger.getInstance().addLog(new DebugLogEntry(System.currentTimeMillis(), true, totp, ""));
            data = new AdvertiseData.Builder()
                    .addServiceUuid(new ParcelUuid(TARGET_SERVICE_UUID))
                    .addManufacturerData(0x02E5, totp.getBytes(StandardCharsets.UTF_8))
                    .build();
        }

        advertiser.startAdvertising(settings, data, advertiseCallback);

        // 2. BLE SCANNING (L' "Eco")
        ScanSettings scanSettings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        // Ripristiniamo SOLO il filtro hardware sul Manufacturer ID
        ScanFilter filter = new ScanFilter.Builder()
                .setManufacturerData(0x02E5, new byte[]{}, new byte[]{})
                .setServiceUuid(new ParcelUuid(TARGET_SERVICE_UUID))
                .build();

        scanner.startScan(Collections.singletonList(filter), scanSettings, scanCallback);
    }

    private boolean checkBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
                   ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.d(TAG, "Advertising avviato con successo");
        }

        @Override
        public void onStartFailure(int errorCode) {
            Log.e(TAG, "Errore Advertising: " + errorCode);
        }
    };
    public static final String ACTION_OPERATION_RESULT = "it.geniola.apritisedano.ACTION_OPERATION_RESULT";
    public static final String EXTRA_BOX_STATE = "it.geniola.apritisedano.EXTRA_BOX_STATE";

    public enum BoxState {
        OPEN,
        CLOSED,
        UNKNOWN
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (result.getScanRecord() != null) {
                byte[] payload = result.getScanRecord().getManufacturerSpecificData(0x02E5);
                if (payload != null) {
                    String response = new String(payload, StandardCharsets.UTF_8);
                    
                    if (response.contains("ACK_OK_OPEN")) {
                        Log.d(TAG, "Ricevuto ACK DAL BOX: APERTO");
                        DebugLogger.getInstance().addLog(new DebugLogEntry(System.currentTimeMillis(), false, "", "ACK_OK_OPEN (Stato: APERTO)"));
                        notifyCompletion(BoxState.OPEN);
                        stopSelf();
                    } else if (response.contains("ACK_OK_CLOSED")) {
                        Log.d(TAG, "Ricevuto ACK DAL BOX: CHIUSO");
                        DebugLogger.getInstance().addLog(new DebugLogEntry(System.currentTimeMillis(), false, "", "ACK_OK_CLOSED (Stato: CHIUSO)"));
                        notifyCompletion(BoxState.CLOSED);
                        stopSelf();
                    } else if (response.contains("NACK_TIME_ERR")) {
                        Log.d(TAG, "Ricevuto NACK DAL BOX: TIME_INVALID");
                        DebugLogger.getInstance().addLog(new DebugLogEntry(System.currentTimeMillis(), false, "", "NACK_TIME_ERR (Orologio non allineato)"));
                        notifyCompletion(BoxState.UNKNOWN);
                        stopSelf();
                    } else {
                        // Other packets or partial payloads (e.g. while BLE is sending)
                        DebugLogger.getInstance().addLog(new DebugLogEntry(System.currentTimeMillis(), false, "", "Dato Sconosciuto/Parziale: " + response));
                    }
                }
            }
        }
    };

    private void notifyCompletion(BoxState boxState) {
        // Notifica di sistema
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        String msg;
        if (boxState == BoxState.OPEN) {
            msg = "Operazione completata. Stato box: APERTO";
        } else if (boxState == BoxState.CLOSED) {
            msg = "Operazione completata. Stato box: CHIUSO";
        } else {
            msg = getString(R.string.failure_message);
        }
        manager.notify(NOTIFICATION_ID + 1, buildNotification(msg));

        // Invia broadcast all'interfaccia utente (MainActivity)
        Intent intent = new Intent(ACTION_OPERATION_RESULT);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_BOX_STATE, boxState != null ? boxState.name() : BoxState.UNKNOWN.name());
        sendBroadcast(intent);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    @Override
    public void onDestroy() {
        if (advertiser != null) advertiser.stopAdvertising(advertiseCallback);
        if (scanner != null) scanner.stopScan(scanCallback);
        timeoutHandler.removeCallbacksAndMessages(null);
        
        // Notifica l'activity che il servizio si è fermato
        Intent stopIntent = new Intent(ACTION_SERVICE_STOPPED);
        stopIntent.setPackage(getPackageName());
        sendBroadcast(stopIntent);

        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}