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
import java.util.Locale;
import java.util.UUID;

/**
 * Foreground Service connectionless BLE ("Faro ed Eco") con Rolling-Code (Counter-based OTP).
 */
public class ApritiSedanoService extends Service {
    private static final String TAG = "ApritiSedanoService";

    public static final String ACTION_SERVICE_STOPPED = "it.geniola.apritisedano.SERVICE_STOPPED";
    public static final String ACTION_OPERATION_RESULT = "it.geniola.apritisedano.ACTION_OPERATION_RESULT";

    public static final String ACTION_TRIGGER = "ACTION_TRIGGER";
    public static final String ACTION_PAIR_NEW_DEVICE = "ACTION_PAIR_NEW_DEVICE";
    public static final String ACTION_ENABLE_REMOTE_PAIRING = "ACTION_ENABLE_REMOTE_PAIRING";
    public static final String ACTION_ENTER_OTA = "ACTION_ENTER_OTA";
    public static final String ACTION_SYNC_TIME = "ACTION_SYNC_TIME";

    public static final String EXTRA_BOX_STATE = "it.geniola.apritisedano.EXTRA_BOX_STATE";
    public static final String EXTRA_TARGET_MAC = "it.geniola.apritisedano.EXTRA_TARGET_MAC";
    public static final String EXTRA_SECRET_KEY = "it.geniola.apritisedano.EXTRA_SECRET_KEY";
    public static final String EXTRA_CLIENT_ID = "it.geniola.apritisedano.EXTRA_CLIENT_ID";
    public static final String EXTRA_DEVICE_NAME = "it.geniola.apritisedano.EXTRA_DEVICE_NAME";
    public static final String EXTRA_USED_COUNTER = "it.geniola.apritisedano.EXTRA_USED_COUNTER";

    private static final String CHANNEL_ID = "ApritiSedanoChannel";
    private static final int NOTIFICATION_ID = 101;
    private static final long COMMAND_TIMEOUT_MS = 5000L; // 5 secondi per trigger/comandi rolling code
    private static final long PAIRING_TIMEOUT_MS = 8000L; // 8 secondi per registrazione nuovo seed

    private static final UUID TARGET_SERVICE_UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0");

    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());

    private String currentTargetMac = null;
    private String currentAction = null;
    private String currentSecretKey = null;
    private String currentDeviceName = null;
    private int currentClientId = 0;
    private long currentUsedCounter = 0L;
    private boolean operationCompleted = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (manager != null) {
            BluetoothAdapter adapter = manager.getAdapter();
            if (adapter != null) {
                advertiser = adapter.getBluetoothLeAdvertiser();
                scanner = adapter.getBluetoothLeScanner();
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_title)));

        timeoutHandler.removeCallbacksAndMessages(null);

        if (advertiser != null) {
            try { advertiser.stopAdvertising(advertiseCallback); } catch (Exception ignored) {}
        }
        if (scanner != null) {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        }

        operationCompleted = false;
        currentUsedCounter = 0L;
        String action = (intent != null && intent.getAction() != null) ? intent.getAction() : ACTION_TRIGGER;
        String targetMac = intent != null ? intent.getStringExtra(EXTRA_TARGET_MAC) : null;
        String secretKey = intent != null ? intent.getStringExtra(EXTRA_SECRET_KEY) : null;
        int clientId = intent != null ? intent.getIntExtra(EXTRA_CLIENT_ID, 0) : 0;
        String deviceName = intent != null ? intent.getStringExtra(EXTRA_DEVICE_NAME) : null;

        startRadioOperations(action, targetMac, secretKey, clientId, deviceName);

        long timeout = ACTION_PAIR_NEW_DEVICE.equals(action) ? PAIRING_TIMEOUT_MS : COMMAND_TIMEOUT_MS;
        timeoutHandler.postDelayed(() -> {
            if (!operationCompleted) {
                notifyCompletion("TIMEOUT");
                stopSelf();
            }
        }, timeout);

        return START_NOT_STICKY;
    }

    private void startRadioOperations(String action, String targetMac, String secretKey, int clientId, String deviceName) {
        if (!checkBluetoothPermissions()) {
            notifyCompletion("NACK_ERR");
            stopSelf();
            return;
        }

        if (advertiser == null || scanner == null || targetMac == null) {
            notifyCompletion("NACK_ERR");
            stopSelf();
            return;
        }

        this.currentTargetMac = targetMac;
        this.currentAction = action;
        this.currentDeviceName = deviceName;

        // Se non stiamo registrando un nuovo dispositivo, carichiamo i dati dello smartphone associato
        if (!ACTION_PAIR_NEW_DEVICE.equals(action)) {
            Device enrolled = DeviceManager.getDeviceByMac(this, targetMac);
            if (enrolled != null) {
                secretKey = enrolled.getSecretKey();
                clientId = enrolled.getClientId();
            }
        }

        if (secretKey == null || clientId <= 0) {
            notifyCompletion("NACK_ERR");
            stopSelf();
            return;
        }

        this.currentSecretKey = secretKey;
        this.currentClientId = clientId;

        byte[] manufacturerPayload;
        String debugDesc;

        if (ACTION_PAIR_NEW_DEVICE.equals(action)) {
            manufacturerPayload = TOTPGenerator.buildPairingRegistrationPayload(targetMac, clientId, secretKey);
            debugDesc = String.format(Locale.US, "PAIR_SEED (ClientID=0x%04X)", clientId);
        } else if (ACTION_ENABLE_REMOTE_PAIRING.equals(action)) {
            long counter = DeviceManager.incrementAndGetCounter(this, targetMac);
            if (counter <= 0) {
                notifyCompletion("NACK_ERR");
                stopSelf();
                return;
            }
            this.currentUsedCounter = counter;
            manufacturerPayload = TOTPGenerator.buildCommandPayload(
                    TOTPGenerator.CMD_ENTER_PAIRING, targetMac, clientId, secretKey, counter
            );
            debugDesc = String.format(Locale.US, "ENTER_PAIR_MODE (Cnt=%d)", counter);
        } else if (ACTION_ENTER_OTA.equals(action)) {
            long counter = DeviceManager.incrementAndGetCounter(this, targetMac);
            if (counter <= 0) {
                notifyCompletion("NACK_ERR");
                stopSelf();
                return;
            }
            this.currentUsedCounter = counter;
            manufacturerPayload = TOTPGenerator.buildCommandPayload(
                    TOTPGenerator.CMD_ENTER_OTA, targetMac, clientId, secretKey, counter
            );
            debugDesc = String.format(Locale.US, "ENTER_OTA_MODE (Cnt=%d)", counter);
        } else if (ACTION_SYNC_TIME.equals(action)) {
            long ts = System.currentTimeMillis() / 1000L;
            manufacturerPayload = TOTPGenerator.buildCommandPayload(
                    TOTPGenerator.CMD_TIME_SYNC, targetMac, clientId, secretKey, ts
            );
            debugDesc = String.format(Locale.US, "TIME_SYNC (TS=%d)", ts);
        } else {
            long counter = DeviceManager.incrementAndGetCounter(this, targetMac);
            if (counter <= 0) {
                notifyCompletion("NACK_ERR");
                stopSelf();
                return;
            }
            this.currentUsedCounter = counter;
            manufacturerPayload = TOTPGenerator.buildCommandPayload(
                    TOTPGenerator.CMD_TRIGGER, targetMac, clientId, secretKey, counter
            );
            debugDesc = String.format(Locale.US, "ROLLING_TRIGGER (Cnt=%d)", counter);
        }

        if (manufacturerPayload == null) {
            notifyCompletion("NACK_ERR");
            stopSelf();
            return;
        }

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(false)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .addManufacturerData(0x02E5, manufacturerPayload)
                .build();

        DebugLogger.getInstance().addLog(new DebugLogEntry(System.currentTimeMillis(), true, debugDesc, "To: " + targetMac));
        advertiser.startAdvertising(settings, data, advertiseCallback);

        ScanSettings scanSettings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        ScanFilter filter = new ScanFilter.Builder()
                .setManufacturerData(0x02E5, new byte[]{}, new byte[]{})
                .setServiceUuid(new ParcelUuid(TARGET_SERVICE_UUID))
                .setDeviceAddress(targetMac)
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
            Log.d(TAG, "Advertising BLE avviato con successo");
        }

        @Override
        public void onStartFailure(int errorCode) {
            Log.e(TAG, "Errore Advertising BLE: " + errorCode);
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (operationCompleted || result.getScanRecord() == null) return;

            byte[] payload = result.getScanRecord().getManufacturerSpecificData(0x02E5);
            if (payload == null || payload.length == 0) return;

            // Ignora i beacon di stato continui (0x01 = Normal, 0x04 = Pairing Mode, 0x05 = OTA Wait)
            if ((payload.length == 6 || payload.length == 10) &&
                (payload[0] == 0x01 || payload[0] == 0x04 || payload[0] == 0x05)) {
                return;
            }

            String response = new String(payload, StandardCharsets.UTF_8);

            if (response.contains("ACK_OK_OPEN")) {
                operationCompleted = true;
                DebugLogger.getInstance().addLog(new DebugLogEntry(System.currentTimeMillis(), false, "", "ACK_OK_OPEN (APERTO)"));
                notifyCompletion("OPEN");
                stopSelf();
            } else if (response.contains("ACK_OK_CLOSED")) {
                operationCompleted = true;
                DebugLogger.getInstance().addLog(new DebugLogEntry(System.currentTimeMillis(), false, "", "ACK_OK_CLOSED (CHIUSO)"));
                notifyCompletion("CLOSED");
                stopSelf();
            } else if (response.contains("ACK_PAIRED")) {
                operationCompleted = true;
                if (ACTION_PAIR_NEW_DEVICE.equals(currentAction) && currentTargetMac != null && currentSecretKey != null) {
                    String name = (currentDeviceName != null && !currentDeviceName.isEmpty()) ? currentDeviceName : "ApritiSedano";
                    Device newDev = new Device(currentTargetMac, name, currentSecretKey, currentClientId, 0L);
                    DeviceManager.addDevice(ApritiSedanoService.this, newDev);
                }
                DebugLogger.getInstance().addLog(new DebugLogEntry(System.currentTimeMillis(), false, "", "ACK_PAIRED (Accoppiato)"));
                notifyCompletion("PAIRED");
                stopSelf();
            } else if (response.contains("ACK_PAIR_MODE")) {
                operationCompleted = true;
                DebugLogger.getInstance().addLog(new DebugLogEntry(System.currentTimeMillis(), false, "", "ACK_PAIR_MODE (Ricevitore in ascolto 60s)"));
                notifyCompletion("PAIR_MODE_ENABLED");
                stopSelf();
            } else if (response.contains("ACK_OTA_READY")) {
                operationCompleted = true;
                DebugLogger.getInstance().addLog(new DebugLogEntry(System.currentTimeMillis(), false, "", "ACK_OTA_READY (Modalità OTA attiva)"));
                notifyCompletion("OTA_READY");
                stopSelf();
            } else if (response.contains("NACK_NOT_MASTER")) {
                operationCompleted = true;
                DebugLogger.getInstance().addLog(new DebugLogEntry(System.currentTimeMillis(), false, "", "NACK_NOT_MASTER (Non autorizzato all'OTA)"));
                notifyCompletion("NACK_NOT_MASTER");
                stopSelf();
            } else if (response.contains("ACK_TIME_OK")) {
                operationCompleted = true;
                DebugLogger.getInstance().addLog(new DebugLogEntry(System.currentTimeMillis(), false, "", "ACK_TIME_OK (RTC sincronizzato)"));
                notifyCompletion("TIME_SYNC_OK");
                stopSelf();
            } else if (response.contains("NACK_USED")) {
                operationCompleted = true;
                DebugLogger.getInstance().addLog(new DebugLogEntry(System.currentTimeMillis(), false, "", "NACK_USED (Seed già usato)"));
                notifyCompletion("NACK_USED");
                stopSelf();
            } else if (response.contains("NACK_ERR")) {
                operationCompleted = true;
                DebugLogger.getInstance().addLog(new DebugLogEntry(System.currentTimeMillis(), false, "", "NACK_ERR (Rifiutato)"));
                notifyCompletion("NACK_ERR");
                stopSelf();
            }
        }
    };

    private void notifyCompletion(String resultState) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        String msg;
        if ("OPEN".equals(resultState)) {
            msg = "Operazione completata. Stato box: APERTO";
        } else if ("CLOSED".equals(resultState)) {
            msg = "Operazione completata. Stato box: CHIUSO";
        } else if ("PAIRED".equals(resultState)) {
            msg = "Smartphone associato con successo al ricevitore!";
        } else if ("PAIR_MODE_ENABLED".equals(resultState)) {
            msg = "Ricevitore in modalità accoppiamento per 60 secondi.";
        } else if ("OTA_READY".equals(resultState)) {
            msg = "Ricevitore in modalità OTA. Avvio trasferimento firmware...";
        } else if ("NACK_NOT_MASTER".equals(resultState)) {
            msg = "Aggiornamento OTA negato: questo smartphone non è Master.";
        } else if ("TIME_SYNC_OK".equals(resultState)) {
            msg = "Orologio RTC sincronizzato con successo.";
        } else if ("NACK_USED".equals(resultState)) {
            msg = "Codice rifiutato: seed già utilizzato.";
        } else {
            msg = getString(R.string.failure_message);
        }
        if (manager != null) {
            manager.notify(NOTIFICATION_ID + 1, buildNotification(msg));
        }

        Intent intent = new Intent(ACTION_OPERATION_RESULT);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_BOX_STATE, resultState != null ? resultState : "UNKNOWN");
        intent.putExtra(EXTRA_USED_COUNTER, currentUsedCounter);
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
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String contentText) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        timeoutHandler.removeCallbacksAndMessages(null);
        if (advertiser != null && checkBluetoothPermissions()) {
            try { advertiser.stopAdvertising(advertiseCallback); } catch (Exception ignored) {}
        }
        if (scanner != null && checkBluetoothPermissions()) {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        }

        Intent stopIntent = new Intent(ACTION_SERVICE_STOPPED);
        stopIntent.setPackage(getPackageName());
        if (currentTargetMac != null) {
            stopIntent.putExtra(EXTRA_TARGET_MAC, currentTargetMac);
        }
        sendBroadcast(stopIntent);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
