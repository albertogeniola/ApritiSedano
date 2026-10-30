package it.geniola.apritisedano;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

/**
 * Client BLE GATT per l'aggiornamento firmware OTA autenticato (riservato alla Master App).
 * Compatibile sia con stack NimBLE (XIAO ESP32-C6) sia con Bluedroid (ESP32-WROOM-32).
 */
@SuppressLint("MissingPermission")
public class OtaUpdater {
    private static final String TAG = "OtaUpdater";

    public static final UUID OTA_SERVICE_UUID   = UUID.fromString("12345678-1234-5678-1234-56789abcdef1");
    public static final UUID OTA_CTRL_CHAR_UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef2");
    public static final UUID OTA_DATA_CHAR_UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef3");
    private static final UUID CCCD_UUID         = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public static final int MAX_OTA_FIRMWARE_BYTES = 1966080; // 1.875 MiB (dimensione partizione ota_0 / ota_1)
    private static final int OTA_WINDOW_SIZE       = 16384;   // Finestra di controllo flusso da 16 KiB
    private static final long STEP_TIMEOUT_MS      = 25000L;  // Watchdog di 25s per singolo step/finestra

    public interface Listener {
        void onStatusChanged(String statusMessage);
        void onProgress(int bytesSent, int totalBytes, int percent);
        void onSuccess();
        void onError(String errorMessage);
    }

    private final Context appContext;
    private final String targetMac;
    private final String masterSecretKey;
    private final long authorizedCounter;
    private final byte[] firmwareBytes;
    private final byte[] firmwareSha256;
    private final Listener listener;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final HandlerThread workerThread;
    private final Handler workerHandler;

    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic ctrlChar;
    private BluetoothGattCharacteristic dataChar;

    private int chunkSize = 240;
    private int sendOffset = 0;
    private int windowEndOffset = 0;
    private int inFlightChunkLen = 0;
    private int connectAttempt = 0;
    private long lastUiProgressMs = 0L;

    private boolean transferStarted = false;
    private boolean finished = false;
    private boolean completedSuccessfully = false;
    private boolean servicesDiscoveryTriggered = false;

    private final Runnable watchdogRunnable = () -> {
        if (!finished) {
            failInternal("Timeout durante il trasferimento BLE GATT OTA (nessuna risposta dal ricevitore).");
        }
    };

    private final Runnable mtuFallbackRunnable = () -> {
        if (!finished && bluetoothGatt != null && !servicesDiscoveryTriggered) {
            servicesDiscoveryTriggered = true;
            Log.w(TAG, "onMtuChanged non ricevuto entro 2.5s, avvio discoverServices() di fallback");
            bluetoothGatt.discoverServices();
        }
    };

    public OtaUpdater(
            Context context,
            String targetMac,
            String masterSecretKey,
            long authorizedCounter,
            byte[] firmwareBytes,
            byte[] firmwareSha256,
            Listener listener
    ) {
        this.appContext = context.getApplicationContext();
        this.targetMac = targetMac;
        this.masterSecretKey = masterSecretKey;
        this.authorizedCounter = authorizedCounter;
        this.firmwareBytes = firmwareBytes;
        this.firmwareSha256 = firmwareSha256;
        this.listener = listener;

        this.workerThread = new HandlerThread("OtaGattWorker");
        this.workerThread.start();
        this.workerHandler = new Handler(this.workerThread.getLooper());
    }

    public void start() {
        postStatus("Connessione BLE GATT al ricevitore in corso...");
        resetWatchdog();
        // Attende 300ms affinché lo stack BLE completi la chiusura dello scanner del Foreground Service
        workerHandler.postDelayed(this::connectInternal, 300L);
    }

    public void cancel() {
        workerHandler.post(() -> {
            if (finished) return;
            if (bluetoothGatt != null && ctrlChar != null) {
                try {
                    bluetoothGatt.writeCharacteristic(
                            ctrlChar,
                            new byte[]{0x03}, // OTA_OP_ABORT
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    );
                } catch (Exception ignored) {}
            }
            failInternal("Aggiornamento OTA annullato.");
        });
    }

    private void connectInternal() {
        if (finished) return;

        BluetoothManager manager = (BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = (manager != null) ? manager.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) {
            failInternal("Bluetooth disattivato o non disponibile.");
            return;
        }

        BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(targetMac);
        } catch (IllegalArgumentException e) {
            failInternal("Indirizzo MAC non valido: " + targetMac);
            return;
        }

        connectAttempt++;
        servicesDiscoveryTriggered = false;
        Log.i(TAG, "Tentativo connessione GATT #" + connectAttempt + " verso " + targetMac);
        DebugLogger.getInstance().addLog(new DebugLogEntry(
                System.currentTimeMillis(), true, "OTA_GATT_CONNECT (#" + connectAttempt + ")", "To: " + targetMac
        ));

        bluetoothGatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        if (bluetoothGatt == null) {
            failInternal("Impossibile avviare la connessione Bluetooth GATT.");
        }
    }

    private void resetWatchdog() {
        workerHandler.removeCallbacks(watchdogRunnable);
        workerHandler.postDelayed(watchdogRunnable, STEP_TIMEOUT_MS);
    }

    private void cleanupGatt() {
        workerHandler.removeCallbacksAndMessages(null);
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.disconnect();
            } catch (Exception ignored) {}
            try {
                bluetoothGatt.close();
            } catch (Exception ignored) {}
            bluetoothGatt = null;
        }
        workerThread.quitSafely();
    }

    private void failInternal(String message) {
        if (finished) return;
        finished = true;
        Log.e(TAG, "OTA Fallito: " + message);
        DebugLogger.getInstance().addLog(new DebugLogEntry(
                System.currentTimeMillis(), false, "", "OTA_ERRORE: " + message
        ));
        cleanupGatt();
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onError(message);
            }
        });
    }

    private void succeedInternal() {
        if (finished) return;
        finished = true;
        completedSuccessfully = true;
        Log.i(TAG, "OTA Completato con successo!");
        DebugLogger.getInstance().addLog(new DebugLogEntry(
                System.currentTimeMillis(), false, "", "OTA_OK (Firmware verificato e attivato)"
        ));
        cleanupGatt();
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onProgress(firmwareBytes.length, firmwareBytes.length, 100);
                listener.onSuccess();
            }
        });
    }

    private void postStatus(String status) {
        mainHandler.post(() -> {
            if (listener != null && !finished) {
                listener.onStatusChanged(status);
            }
        });
    }

    private void postProgress(int sent, int total, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && (now - lastUiProgressMs < 80L)) {
            return;
        }
        lastUiProgressMs = now;
        final int pct = (total > 0) ? (int) ((sent * 100L) / total) : 0;
        mainHandler.post(() -> {
            if (listener != null && !finished) {
                listener.onProgress(sent, total, pct);
            }
        });
    }

    private void sendNextChunk() {
        if (finished || bluetoothGatt == null || dataChar == null) return;

        if (sendOffset >= windowEndOffset) {
            // Finestra da 16 KiB inviata: attendiamo la notifica "ACK:<offset>" dall'MCU
            // prima di inviare la finestra successiva (evita qualsiasi buffer overrun su NimBLE/Bluedroid).
            return;
        }

        int len = Math.min(chunkSize, windowEndOffset - sendOffset);
        byte[] chunk = Arrays.copyOfRange(firmwareBytes, sendOffset, sendOffset + len);

        int res = bluetoothGatt.writeCharacteristic(
                dataChar,
                chunk,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        );

        if (res == BluetoothStatusCodes.SUCCESS) {
            inFlightChunkLen = len;
        } else if (res == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY) {
            workerHandler.postDelayed(this::sendNextChunk, 8L);
        } else {
            failInternal("Errore scrittura pacchetto BLE GATT (codice: " + res + ").");
        }
    }

    private static String translateMcuError(String errCode) {
        if (errCode.contains("ERR:AUTH")) {
            return "Autenticazione Master fallita (firma HMAC o contatore non valido).";
        } else if (errCode.contains("ERR:SPACE")) {
            return "Il file firmware supera lo spazio disponibile nella partizione OTA del ricevitore.";
        } else if (errCode.contains("ERR:BEGIN") || errCode.contains("ERR:FLASH")) {
            return "Errore scrittura Flash sull'MCU (verifica che il file .bin sia compilato per la scheda corretta: XIAO ESP32-C6 vs ESP32-WROOM-32).";
        } else if (errCode.contains("ERR:HASH")) {
            return "Verifica integrità SHA-256 fallita! Il firmware corrotto è stato scartato in sicurezza.";
        } else if (errCode.contains("ERR:TIMEOUT")) {
            return "Timeout di inattività rilevato dal ricevitore durante il trasferimento.";
        } else if (errCode.contains("ERR:FINALIZE")) {
            return "Impossibile validare l'immagine ESP32 finale (verifica di aver selezionato MCU.ino.bin e non merged.bin).";
        }
        return "Il ricevitore ha restituito un errore OTA (" + errCode + ").";
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            workerHandler.post(() -> {
                if (finished) return;

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    // Gestione errore transitorio Android BLE (es. status 133) in fase di aggancio iniziale
                    if (!transferStarted && connectAttempt < 3) {
                        Log.w(TAG, "Connessione GATT fallita con status=" + status + ", nuovo tentativo...");
                        try { gatt.close(); } catch (Exception ignored) {}
                        bluetoothGatt = null;
                        resetWatchdog();
                        workerHandler.postDelayed(OtaUpdater.this::connectInternal, 450L);
                        return;
                    }
                    if (completedSuccessfully) {
                        return;
                    }
                    failInternal("Connessione BLE GATT interrotta (status: " + status + ").");
                    return;
                }

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    resetWatchdog();
                    postStatus("Connesso! Negoziazione parametri BLE ad alta velocità...");
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                    gatt.setPreferredPhy(
                            BluetoothDevice.PHY_LE_2M_MASK,
                            BluetoothDevice.PHY_LE_2M_MASK,
                            BluetoothDevice.PHY_OPTION_NO_PREFERRED
                    );
                    servicesDiscoveryTriggered = false;
                    boolean mtuReq = gatt.requestMtu(517);
                    if (!mtuReq) {
                        servicesDiscoveryTriggered = true;
                        gatt.discoverServices();
                    } else {
                        workerHandler.postDelayed(mtuFallbackRunnable, 2500L);
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (!completedSuccessfully) {
                        failInternal("Il ricevitore si è disconnesso prima del completamento dell'OTA.");
                    }
                }
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            workerHandler.post(() -> {
                if (finished) return;
                workerHandler.removeCallbacks(mtuFallbackRunnable);
                if (status == BluetoothGatt.GATT_SUCCESS && mtu > 23) {
                    chunkSize = Math.min(480, mtu - 3);
                } else {
                    chunkSize = 20;
                }
                Log.i(TAG, "MTU negoziato: " + mtu + " -> chunkSize=" + chunkSize + " byte");
                if (!servicesDiscoveryTriggered) {
                    servicesDiscoveryTriggered = true;
                    gatt.discoverServices();
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            workerHandler.post(() -> {
                if (finished) return;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    failInternal("Ricerca servizi BLE GATT fallita (status: " + status + ").");
                    return;
                }

                BluetoothGattService service = gatt.getService(OTA_SERVICE_UUID);
                if (service == null) {
                    failInternal("Servizio OTA non trovato sul ricevitore.");
                    return;
                }

                ctrlChar = service.getCharacteristic(OTA_CTRL_CHAR_UUID);
                dataChar = service.getCharacteristic(OTA_DATA_CHAR_UUID);
                if (ctrlChar == null || dataChar == null) {
                    failInternal("Caratteristiche GATT OTA mancanti sul ricevitore.");
                    return;
                }

                resetWatchdog();
                postStatus("Autenticazione Master Seed (HMAC-SHA1 + SHA-256)...");

                if (!gatt.setCharacteristicNotification(ctrlChar, true)) {
                    failInternal("Impossibile abilitare le notifiche GATT OTA.");
                    return;
                }

                BluetoothGattDescriptor cccd = ctrlChar.getDescriptor(CCCD_UUID);
                if (cccd != null) {
                    int res = gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    if (res != BluetoothStatusCodes.SUCCESS) {
                        failInternal("Errore sottoscrizione notifiche CCCD (codice: " + res + ").");
                    }
                } else {
                    sendBeginPacket(gatt);
                }
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            workerHandler.post(() -> {
                if (finished) return;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    failInternal("Abilitazione notifiche GATT fallita (status: " + status + ").");
                    return;
                }
                sendBeginPacket(gatt);
            });
        }

        private void sendBeginPacket(BluetoothGatt gatt) {
            resetWatchdog();
            byte[] beginPacket = TOTPGenerator.buildOtaBeginPacket(
                    masterSecretKey,
                    targetMac,
                    firmwareBytes.length,
                    authorizedCounter,
                    firmwareSha256
            );
            if (beginPacket == null) {
                failInternal("Errore generazione firma crittografica OTA.");
                return;
            }
            int res = gatt.writeCharacteristic(
                    ctrlChar,
                    beginPacket,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            );
            if (res != BluetoothStatusCodes.SUCCESS) {
                failInternal("Errore invio comando OP_BEGIN (codice: " + res + ").");
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            workerHandler.post(() -> {
                if (finished) return;

                UUID uuid = characteristic.getUuid();
                if (OTA_CTRL_CHAR_UUID.equals(uuid)) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        failInternal("Scrittura comando di controllo OTA fallita (status: " + status + ").");
                    }
                    return;
                }

                if (OTA_DATA_CHAR_UUID.equals(uuid)) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        failInternal("Errore trasmissione dati firmware (status: " + status + ").");
                        return;
                    }
                    resetWatchdog();
                    sendOffset += inFlightChunkLen;
                    inFlightChunkLen = 0;
                    postProgress(sendOffset, firmwareBytes.length, false);
                    sendNextChunk();
                }
            });
        }

        @Override
        public void onCharacteristicChanged(
                @NonNull BluetoothGatt gatt,
                @NonNull BluetoothGattCharacteristic characteristic,
                @NonNull byte[] value
        ) {
            workerHandler.post(() -> {
                if (finished || !OTA_CTRL_CHAR_UUID.equals(characteristic.getUuid())) return;

                String msg = new String(value, StandardCharsets.UTF_8).trim();
                Log.d(TAG, "Notifica MCU OTA: " + msg);
                resetWatchdog();

                if ("OTA_READY".equals(msg)) {
                    transferStarted = true;
                    sendOffset = 0;
                    inFlightChunkLen = 0;
                    windowEndOffset = Math.min(OTA_WINDOW_SIZE, firmwareBytes.length);
                    postStatus("Trasferimento firmware via BLE GATT in corso...");
                    postProgress(0, firmwareBytes.length, true);
                    sendNextChunk();
                } else if (msg.startsWith("ACK:")) {
                    try {
                        int acked = Integer.parseInt(msg.substring(4).trim());
                        postProgress(acked, firmwareBytes.length, true);

                        if (acked >= firmwareBytes.length) {
                            postStatus("Verifica integrità SHA-256 e attivazione nuovo firmware...");
                            int res = gatt.writeCharacteristic(
                                    ctrlChar,
                                    new byte[]{0x02}, // OTA_OP_END
                                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            );
                            if (res != BluetoothStatusCodes.SUCCESS) {
                                failInternal("Errore invio comando finalizzazione OP_END (codice: " + res + ").");
                            }
                        } else {
                            windowEndOffset = Math.min(acked + OTA_WINDOW_SIZE, firmwareBytes.length);
                            sendNextChunk();
                        }
                    } catch (NumberFormatException e) {
                        failInternal("Risposta ACK non valida dal ricevitore: " + msg);
                    }
                } else if ("OTA_OK".equals(msg)) {
                    succeedInternal();
                } else if (msg.startsWith("ERR:")) {
                    failInternal(translateMcuError(msg));
                }
            });
        }
    };
}
