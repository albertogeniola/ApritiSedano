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
    public static final String ACTION_OPERATION_RESULT = "it.geniola.apritisedano.ACTION_OPERATION_RESULT";
    public static final String EXTRA_BOX_STATE = "it.geniola.apritisedano.EXTRA_BOX_STATE";
    public static final String EXTRA_TARGET_MAC = "it.geniola.apritisedano.EXTRA_TARGET_MAC";
    public static final String EXTRA_SECRET_KEY = "it.geniola.apritisedano.EXTRA_SECRET_KEY";
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

        // Annulla eventuali timeout pendenti (es. da una precedente operazione di sync)
        timeoutHandler.removeCallbacksAndMessages(null);

        // Ferma le trasmissioni precedenti se il servizio era già attivo
        if (advertiser != null) {
            try { advertiser.stopAdvertising(advertiseCallback); } catch (Exception e) {}
        }
        if (scanner != null) {
            try { scanner.stopScan(scanCallback); } catch (Exception e) {}
        }

        boolean isSyncAction = intent != null && "ACTION_SYNC_TIME".equals(intent.getAction());
        String targetMac = intent != null ? intent.getStringExtra(EXTRA_TARGET_MAC) : null;
        String secretKey = intent != null ? intent.getStringExtra(EXTRA_SECRET_KEY) : null;
        
        startRadioOperations(isSyncAction, targetMac, secretKey);
        
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

    private String currentTargetMac = null;

    private void startRadioOperations(boolean isSyncAction, String targetMac, String secretKey) {
        if (!checkBluetoothPermissions()) {
            notifyCompletion(null);
            stopSelf();
            return;
        }

        if (advertiser == null || scanner == null || targetMac == null || secretKey == null) {
            stopSelf();
            return;
        }

        this.currentTargetMac = targetMac;

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(false)
                .setTimeout(0) 
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build();

        AdvertiseData data;
        
        // Convert MAC string (e.g. "AA:BB:CC:DD:EE:FF") to 6 bytes
        byte[] macBytes = new byte[6];
        String[] macParts = targetMac.split(":");
        if (macParts.length == 6) {
            for (int i = 0; i < 6; i++) {
                macBytes[i] = (byte) Integer.parseInt(macParts[i], 16);
            }
        }

        if (isSyncAction) {
            int ts = (int)(System.currentTimeMillis() / 1000L);
            byte[] syncPayload = TOTPGenerator.generateTimeSyncPayload(secretKey, ts);
            if (syncPayload == null) {
                notifyCompletion(null);
                stopSelf();
                return;
            }
            
            // Sync payload with MAC addressing is not strictly defined in the strategy, 
            // but we can append it if we want. For now, let's keep it broadcast or we can prepend MAC.
            // Let's prepend MAC to make it addressed too (though we didn't change MCU for this yet, so we'll just broadcast)
            data = new AdvertiseData.Builder()
                    .addManufacturerData(0x02E5, syncPayload)
                    .build();
        } else {
            String totp = TOTPGenerator.generateTOTP(secretKey);
            if (totp == null) {
                notifyCompletion(null);
                stopSelf();
                return;
            }
            
            // New Payload: [MAC_0..MAC_5] + [TOTP_0..TOTP_5]
            byte[] totpBytes = totp.getBytes(StandardCharsets.UTF_8);
            byte[] payload = new byte[12];
            System.arraycopy(macBytes, 0, payload, 0, 6);
            System.arraycopy(totpBytes, 0, payload, 6, 6);

            DebugLogger.getInstance().addLog(new DebugLogEntry(System.currentTimeMillis(), true, totp, "To: " + targetMac));
            data = new AdvertiseData.Builder()
                    .addManufacturerData(0x02E5, payload)
                    .build();
        }

        advertiser.startAdvertising(settings, data, advertiseCallback);

        ScanSettings scanSettings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        ScanFilter filter = new ScanFilter.Builder()
                .setManufacturerData(0x02E5, new byte[]{}, new byte[]{})
                .setServiceUuid(new ParcelUuid(TARGET_SERVICE_UUID))
                .setDeviceAddress(targetMac) // Listen only for ACKs from THIS MAC!
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
                    // Ignora i Beacon di stato (binari di 10 byte che iniziano per 0x01, 0x02 o 0x03) per non sporcare i log
                    if (payload.length == 10 && (payload[0] == 0x01 || payload[0] == 0x02 || payload[0] == 0x03)) {
                        return;
                    }

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
        if (currentTargetMac != null) {
            intent.putExtra(EXTRA_TARGET_MAC, currentTargetMac);
        }
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
        if (currentTargetMac != null) {
            stopIntent.putExtra(EXTRA_TARGET_MAC, currentTargetMac);
        }
        sendBroadcast(stopIntent);

        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}