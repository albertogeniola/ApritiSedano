package it.geniola.apritisedano;

import android.Manifest;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.BarcodeEncoder;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ScrollView;
import com.hoho.android.usbserial.driver.UsbSerialDriver;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final UUID TARGET_SERVICE_UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0");

    private RecyclerView rvDevices;
    private DeviceAdapter deviceAdapter;
    private List<Device> deviceList;

    private NfcAdapter nfcAdapter;
    private PendingIntent pendingIntent;
    private BluetoothLeScanner stateScanner;
    private BluetoothLeScanner discoveryScanner;
    private AlertDialog discoveryDialog;
    private AlertDialog operationProgressDialog;
    private AlertDialog pairingCountdownDialog;
    private TextView tvPairingCountdownDialogTimer;
    private long activePairingModalEndTimeMs = 0L;
    private String activePairingModalMac = null;
    private String pendingModalOperation = null;

    // OTA Firmware Upgrade state (Master App only)
    private Device pendingOtaDevice = null;
    private byte[] pendingOtaFirmwareBytes = null;
    private byte[] pendingOtaSha256 = null;
    private OtaUpdater activeOtaUpdater = null;
    private boolean isOtaSessionInProgress = false;
    private AlertDialog otaProgressDialog = null;
    private ProgressBar pbOtaProgress = null;
    private TextView tvOtaStatus = null;
    private TextView tvOtaDetails = null;

    // Flashing Firmware via Cavo USB OTG
    private byte[] pendingCableFirmwareBytes = null;
    private String pendingCableFirmwareName = null;
    private int pendingCableFlashOffset = 0x10000;
    private AlertDialog cableFlashDialog = null;
    private TextView tvCableFirmwareStatus = null;
    private TextView tvCableDeviceStatus = null;
    private EditText etCableOffset = null;
    private Button btnStartCableFlash = null;
    private UsbSerialDriver selectedCableDriver = null;
    private Esp32SerialFlasher activeFlasher = null;
    private AlertDialog cableProgressDialog = null;
    private ProgressBar pbCableProgress = null;
    private TextView tvCableProgressPercent = null;
    private TextView tvCableProgressPhase = null;
    private TextView tvCableProgressLog = null;
    private ScrollView svCableProgressLog = null;
    private static final String ACTION_USB_PERMISSION = "it.geniola.apritisedano.USB_PERMISSION";

    private final ActivityResultLauncher<String[]> cableFilePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri != null) {
                    handleSelectedCableFirmwareUri(uri);
                }
            }
    );

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            Log.d(TAG, "Permesso USB concesso: " + device.getDeviceName());
                            executeCableFlashing();
                        }
                    } else {
                        Toast.makeText(MainActivity.this, "Permesso USB negato dall'utente.", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        }
    };

    private final Handler pairingTimerHandler = new Handler(Looper.getMainLooper());
    private final Runnable pairingTimerRunnable = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();

            // 1. Aggiorna i timer su tutte le schede dispositivo nella lista principale
            if (deviceAdapter != null) {
                deviceAdapter.tickPairingCountdowns();
            }

            // 2. Aggiorna i timer sui dispositivi scoperti nella finestra di scansione (+)
            boolean discoveryChanged = false;
            for (DiscoveredDevice d : discoveredDevices) {
                if (d.inPairingMode) {
                    if (d.getRemainingSeconds(now) <= 0) {
                        d.inPairingMode = false;
                        d.pairingEndTimeMs = 0L;
                    }
                    discoveryChanged = true;
                }
            }
            if (discoveryChanged && discoveryAdapter != null && discoveryDialog != null && discoveryDialog.isShowing()) {
                discoveryAdapter.notifyDataSetChanged();
            }

            // 3. Aggiorna eventuale finestra modale di conto alla rovescia aperta
            if (pairingCountdownDialog != null && pairingCountdownDialog.isShowing()) {
                long diffMs = activePairingModalEndTimeMs - now;
                int remSec = diffMs > 0 ? (int) Math.min(60L, (diffMs + 999L) / 1000L) : 0;
                if (remSec <= 0) {
                    try {
                        pairingCountdownDialog.dismiss();
                    } catch (Exception ignored) {}
                    pairingCountdownDialog = null;
                } else if (tvPairingCountdownDialogTimer != null) {
                    tvPairingCountdownDialogTimer.setText(String.format(Locale.US, "⏱️ Tempo rimanente: %ds", remSec));
                }
            }

            pairingTimerHandler.postDelayed(this, 1000L);
        }
    };

    private static class DiscoveredDevice {
        final String mac;
        int rssi;
        boolean inPairingMode;
        long lastPairingSeenMs;
        long pairingEndTimeMs;

        DiscoveredDevice(String mac, int rssi, boolean inPairingMode, int initialRemSec) {
            this.mac = mac;
            this.rssi = rssi;
            this.inPairingMode = inPairingMode;
            long now = System.currentTimeMillis();
            this.lastPairingSeenMs = inPairingMode ? now : 0L;
            this.pairingEndTimeMs = inPairingMode ? (now + initialRemSec * 1000L) : 0L;
        }

        void updatePairingState(long now, int beaconRemSec) {
            boolean wasPairing = this.inPairingMode;
            this.inPairingMode = true;
            this.lastPairingSeenMs = now;
            if (beaconRemSec >= 1 && beaconRemSec <= 60) {
                this.pairingEndTimeMs = now + beaconRemSec * 1000L;
            } else if (!wasPairing || this.pairingEndTimeMs <= now) {
                this.pairingEndTimeMs = now + 60_000L;
            }
        }

        int getRemainingSeconds(long now) {
            if (!inPairingMode || pairingEndTimeMs <= now) return 0;
            return (int) Math.min(60L, (pairingEndTimeMs - now + 999L) / 1000L);
        }
    }

    private final List<DiscoveredDevice> discoveredDevices = new ArrayList<>();
    private BaseAdapter discoveryAdapter;

    private boolean isNfcWriteMode = false;
    private String nfcWriteTargetMac = null;
    private AlertDialog nfcWriteDialog = null;

    private String pendingQrTargetMac = null;
    private String pendingQrDeviceName = null;

    private final ActivityResultLauncher<String[]> otaFilePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri == null || pendingOtaDevice == null) {
                    return;
                }
                handleSelectedOtaFirmwareUri(pendingOtaDevice, uri);
            }
    );

    private final ActivityResultLauncher<ScanOptions> barcodeLauncher = registerForActivityResult(new ScanContract(),
            result -> {
                if (result.getContents() != null) {
                    String scanned = result.getContents().trim();
                    try {
                        if (scanned.startsWith("apritisedano://rolling")) {
                            Uri uri = Uri.parse(scanned);
                            String seed = uri.getQueryParameter("seed");
                            String idStr = uri.getQueryParameter("id");
                            String cntStr = uri.getQueryParameter("cnt");
                            String qrName = uri.getQueryParameter("name");
                            String qrMac = uri.getQueryParameter("mac");

                            String targetMac = (pendingQrTargetMac != null) ? pendingQrTargetMac : qrMac;
                            String name = (pendingQrDeviceName != null && !pendingQrDeviceName.isEmpty())
                                    ? pendingQrDeviceName
                                    : (qrName != null && !qrName.isEmpty() ? qrName : "ApritiSedano");

                            int clientId = (idStr != null) ? Integer.parseInt(idStr) : TOTPGenerator.generateRandomClientId();
                            long counter = (cntStr != null) ? Long.parseLong(cntStr) : 0L;

                            if (targetMac != null && TOTPGenerator.isValidBase32Secret(seed)) {
                                Device restored = new Device(targetMac, name, seed, clientId, counter);
                                DeviceManager.addDevice(this, restored);
                                deviceList = DeviceManager.getDevices(this);
                                deviceAdapter.updateDevices(deviceList);
                                Toast.makeText(this, "Backup dispositivo importato con successo!", Toast.LENGTH_SHORT).show();
                                return;
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Errore importazione QR", e);
                    }
                    Toast.makeText(this, "QR Code di backup non valido.", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Scansione annullata", Toast.LENGTH_SHORT).show();
                }
            });

    private final BroadcastReceiver serviceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            String targetMac = intent.getStringExtra(ApritiSedanoService.EXTRA_TARGET_MAC);

            if (ApritiSedanoService.ACTION_SERVICE_STOPPED.equals(action)) {
                if (targetMac != null && !isOtaSessionInProgress) {
                    deviceAdapter.markActionPending(targetMac, false);
                }
            } else if (ApritiSedanoService.ACTION_OPERATION_RESULT.equals(action)) {
                dismissOperationProgressDialog();
                String opType = pendingModalOperation;
                pendingModalOperation = null;

                String state = intent.getStringExtra(ApritiSedanoService.EXTRA_BOX_STATE);

                // Gestione esito attivazione modalità OTA (fase 1/3 -> fase 2/3 GATT)
                if ("OTA_READY".equals(state)) {
                    long usedCounter = intent.getLongExtra(ApritiSedanoService.EXTRA_USED_COUNTER, 0L);
                    startGattOtaTransfer(usedCounter);
                    return;
                } else if ("NACK_NOT_MASTER".equals(state)) {
                    finishOtaUiSession();
                    if (targetMac != null) {
                        DeviceManager.updateMasterStatus(context, targetMac, false);
                        deviceAdapter.updateDeviceMasterStatus(targetMac, false);
                        deviceAdapter.markActionPending(targetMac, false);
                    }
                    showResultAlertDialog(
                            "Accesso OTA Negato",
                            "Il ricevitore ha rifiutato l'aggiornamento perché questo smartphone non risulta configurato come MASTER."
                    );
                    return;
                } else if (ApritiSedanoService.ACTION_ENTER_OTA.equals(opType)) {
                    finishOtaUiSession();
                    if (targetMac != null) {
                        deviceAdapter.markActionPending(targetMac, false);
                    }
                    if ("TIMEOUT".equals(state)) {
                        showResultAlertDialog(
                                "Nessuna Risposta dal Ricevitore",
                                "Il ricevitore non ha confermato l'ingresso in modalità OTA entro il tempo limite.\nAssicurati di essere vicino al dispositivo."
                        );
                    } else if ("NACK_USED".equals(state)) {
                        showResultAlertDialog(
                                "Codice Rifiutato (Già Usato)",
                                "Il contatore rolling-code inviato risulta già utilizzato. Riprova."
                        );
                    } else {
                        showResultAlertDialog(
                                "Impossibile Avviare OTA",
                                "Il ricevitore ha rifiutato l'ingresso in modalità OTA (verifica che la scheda abbia la nuova tabella partizioni Dual-OTA)."
                        );
                    }
                    return;
                }

                if (targetMac != null) {
                    deviceAdapter.markActionPending(targetMac, false);
                }

                if ("OPEN".equals(state)) {
                    if (targetMac != null) deviceAdapter.updateDeviceState(targetMac, 1);
                    Toast.makeText(context, "Operazione completata! Stato: APERTO", Toast.LENGTH_SHORT).show();
                } else if ("CLOSED".equals(state)) {
                    if (targetMac != null) deviceAdapter.updateDeviceState(targetMac, 2);
                    Toast.makeText(context, "Operazione completata! Stato: CHIUSO", Toast.LENGTH_SHORT).show();
                } else if ("PAIRED".equals(state)) {
                    deviceList = DeviceManager.getDevices(context);
                    deviceAdapter.updateDevices(deviceList);
                    showResultAlertDialog(
                            "Accoppiamento Completato",
                            "Lo smartphone è stato registrato con successo sul ricevitore (" +
                                    (targetMac != null ? targetMac : "") + "). Ora puoi aprire e chiudere il varco!"
                    );
                } else if ("PAIR_MODE_ENABLED".equals(state)) {
                    long now = System.currentTimeMillis();
                    if (targetMac != null) {
                        deviceAdapter.startDevicePairingCountdown(targetMac, 60);
                        for (DiscoveredDevice d : discoveredDevices) {
                            if (d.mac.equalsIgnoreCase(targetMac)) {
                                d.updatePairingState(now, 60);
                            }
                        }
                        if (discoveryAdapter != null) discoveryAdapter.notifyDataSetChanged();
                    }
                    showPairingCountdownDialog(targetMac, 60);
                } else if ("TIME_SYNC_OK".equals(state)) {
                    Toast.makeText(context, "Orologio RTC sincronizzato con successo!", Toast.LENGTH_SHORT).show();
                } else if ("NACK_USED".equals(state)) {
                    showResultAlertDialog(
                            "Codice Rifiutato (Già Usato)",
                            "Il ricevitore ha rifiutato il comando perché il contatore rolling-code inviato risulta già utilizzato."
                    );
                } else if ("NACK_ERR".equals(state)) {
                    if (ApritiSedanoService.ACTION_PAIR_NEW_DEVICE.equals(opType)) {
                        showResultAlertDialog(
                                "Accoppiamento Rifiutato",
                                "Il ricevitore ha rifiutato la richiesta di accoppiamento.\n\n" +
                                        "Verifica che il ricevitore sia in Modalità Accoppiamento (tieni premuto il tasto fisico per 3 secondi oppure attivala da uno smartphone già associato) e che non abbia raggiunto il limite di 16 dispositivi."
                        );
                    } else {
                        showResultAlertDialog(
                                "Operazione Rifiutata",
                                "Il ricevitore ha rifiutato il comando. Verifica che questo smartphone sia regolarmente associato al dispositivo."
                        );
                    }
                } else if ("TIMEOUT".equals(state)) {
                    if (ApritiSedanoService.ACTION_PAIR_NEW_DEVICE.equals(opType)) {
                        showResultAlertDialog(
                                "Nessuna Risposta dal Ricevitore",
                                "Il ricevitore non ha confermato l'accoppiamento entro il tempo limite.\n\n" +
                                        "Assicurati che:\n" +
                                        "1. Il ricevitore abbia il firmware aggiornato e sia in Modalità Accoppiamento (LED lampeggiante).\n" +
                                        "2. Lo smartphone sia vicino al ricevitore."
                        );
                    } else if (ApritiSedanoService.ACTION_ENABLE_REMOTE_PAIRING.equals(opType)) {
                        showResultAlertDialog(
                                "Nessuna Risposta dal Ricevitore",
                                "Il ricevitore non ha risposto alla richiesta di attivazione della Modalità Accoppiamento. Assicurati di essere a portata Bluetooth."
                        );
                    } else {
                        Toast.makeText(context, "Nessuna risposta dal ricevitore (verifica che sia a portata).", Toast.LENGTH_LONG).show();
                    }
                } else {
                    Toast.makeText(context, "Operazione fallita o bloccata.", Toast.LENGTH_LONG).show();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        rvDevices = findViewById(R.id.rv_devices);
        rvDevices.setLayoutManager(new LinearLayoutManager(this));

        deviceList = DeviceManager.getDevices(this);
        deviceAdapter = new DeviceAdapter(
                deviceList,
                this::onDeviceActionClick,
                this::showDeviceSettingsDialog
        );
        rvDevices.setAdapter(deviceAdapter);

        FloatingActionButton fabAdd = findViewById(R.id.fab_add_device);
        fabAdd.setOnClickListener(v -> showAddDeviceScanDialog());

        ImageView ivDebug = findViewById(R.id.iv_debug_mode);
        if (ivDebug != null) {
            ivDebug.setOnClickListener(v -> startActivity(new Intent(this, DebugActivity.class)));
        }

        ImageView ivFlashCable = findViewById(R.id.iv_flash_cable);
        if (ivFlashCable != null) {
            ivFlashCable.setOnClickListener(v -> {
                if (DeviceManager.hasMasterDevice(this)) {
                    new AlertDialog.Builder(this)
                            .setTitle("Strumenti USB / Cavo OTG")
                            .setItems(new CharSequence[]{
                                    "Menu Seriale di Configurazione (CLI)",
                                    "Flash Firmware via Cavo USB (OTG)"
                            }, (dialog, which) -> {
                                if (which == 0) {
                                    startActivity(new Intent(this, SerialConsoleActivity.class));
                                } else {
                                    showCableFlashDialog();
                                }
                            })
                            .show();
                } else {
                    showCableFlashDialog();
                }
            });
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, new IntentFilter(ACTION_USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbPermissionReceiver, new IntentFilter(ACTION_USB_PERMISSION));
        }

        checkAndRequestPermissions();

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter == null) {
            Toast.makeText(this, "NFC non supportato", Toast.LENGTH_LONG).show();
        }

        Intent intent = new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE);

        handleIntent(getIntent());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (activeOtaUpdater != null) {
            activeOtaUpdater.cancel();
            activeOtaUpdater = null;
        }
        if (activeFlasher != null) {
            activeFlasher.cancel();
            activeFlasher = null;
        }
        try {
            unregisterReceiver(usbPermissionReceiver);
        } catch (Exception ignored) {}
    }

    private void showPairingCountdownDialog(String targetMac, int initialSeconds) {
        if (isFinishing() || isDestroyed()) return;
        if (pairingCountdownDialog != null && pairingCountdownDialog.isShowing()) {
            try { pairingCountdownDialog.dismiss(); } catch (Exception ignored) {}
        }

        activePairingModalMac = targetMac;
        activePairingModalEndTimeMs = System.currentTimeMillis() + (initialSeconds * 1000L);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Modalità Accoppiamento Attiva");

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(20);
        container.setPadding(pad, pad, pad, pad);

        TextView tvInfo = new TextView(this);
        tvInfo.setText("Il ricevitore " + (targetMac != null ? targetMac : "") +
                " è ora in ascolto per registrare un nuovo smartphone.\n\n" +
                "Apri l'app sul nuovo telefono, premi '+' e seleziona il dispositivo per associarlo.");
        tvInfo.setTextSize(14f);
        container.addView(tvInfo);

        tvPairingCountdownDialogTimer = new TextView(this);
        tvPairingCountdownDialogTimer.setText(String.format(Locale.US, "⏱️ Tempo rimanente: %ds", initialSeconds));
        tvPairingCountdownDialogTimer.setTextSize(18f);
        tvPairingCountdownDialogTimer.setTypeface(null, Typeface.BOLD);
        tvPairingCountdownDialogTimer.setTextColor(Color.parseColor("#0097A7"));
        tvPairingCountdownDialogTimer.setGravity(Gravity.CENTER_HORIZONTAL);
        tvPairingCountdownDialogTimer.setPadding(0, dpToPx(16), 0, dpToPx(4));
        container.addView(tvPairingCountdownDialogTimer);

        builder.setView(container);
        builder.setPositiveButton("Chiudi", (d, w) -> d.dismiss());

        pairingCountdownDialog = builder.create();
        pairingCountdownDialog.show();
    }

    private void showOperationProgressDialog(String title, String message, String operationAction) {
        dismissOperationProgressDialog();
        pendingModalOperation = operationAction;

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dpToPx(20);
        layout.setPadding(pad, pad, pad, pad);

        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminate(true);
        LinearLayout.LayoutParams pbParams = new LinearLayout.LayoutParams(dpToPx(36), dpToPx(36));
        pbParams.setMarginEnd(dpToPx(16));
        layout.addView(pb, pbParams);

        TextView tv = new TextView(this);
        tv.setText(message);
        tv.setTextSize(14f);
        layout.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        builder.setView(layout);
        builder.setCancelable(false);
        operationProgressDialog = builder.create();
        operationProgressDialog.show();
    }

    private void dismissOperationProgressDialog() {
        if (operationProgressDialog != null && operationProgressDialog.isShowing()) {
            try {
                operationProgressDialog.dismiss();
            } catch (Exception ignored) {}
        }
        operationProgressDialog = null;
    }

    private void showResultAlertDialog(String title, String message) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", (d, w) -> d.dismiss())
                .show();
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private void onDeviceActionClick(Device device) {
        if (!hasRequiredPermissions()) {
            checkAndRequestPermissions();
            return;
        }
        deviceAdapter.markActionPending(device.getMacAddress(), true);

        Intent serviceIntent = new Intent(this, ApritiSedanoService.class);
        serviceIntent.setAction(ApritiSedanoService.ACTION_TRIGGER);
        serviceIntent.putExtra(ApritiSedanoService.EXTRA_TARGET_MAC, device.getMacAddress());
        serviceIntent.putExtra(ApritiSedanoService.EXTRA_SECRET_KEY, device.getSecretKey());
        serviceIntent.putExtra(ApritiSedanoService.EXTRA_CLIENT_ID, device.getClientId());

        startServiceCompat(serviceIntent);
    }

    private void startServiceCompat(Intent serviceIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcAdapter != null) nfcAdapter.enableForegroundDispatch(this, pendingIntent, null, null);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ApritiSedanoService.ACTION_SERVICE_STOPPED);
        filter.addAction(ApritiSedanoService.ACTION_OPERATION_RESULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(serviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(serviceReceiver, filter);
        }

        if (!isOtaSessionInProgress) {
            deviceList = DeviceManager.getDevices(this);
            deviceAdapter.updateDevices(deviceList);
            startStateScanning();
        }

        pairingTimerHandler.removeCallbacks(pairingTimerRunnable);
        pairingTimerHandler.postDelayed(pairingTimerRunnable, 1000L);
    }

    @Override
    protected void onPause() {
        super.onPause();
        pairingTimerHandler.removeCallbacks(pairingTimerRunnable);
        if (nfcAdapter != null) nfcAdapter.disableForegroundDispatch(this);
        unregisterReceiver(serviceReceiver);
        stopStateScanning();
        stopDiscoveryScan();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Log.d(TAG, "Intent action received: " + action);

        if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action) ||
            NfcAdapter.ACTION_TAG_DISCOVERED.equals(action) ||
            NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)) {

            if (isNfcWriteMode) {
                Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
                if (tag != null) {
                    writeTag(tag, nfcWriteTargetMac);
                }
                return;
            }
        }

        if (ApritiSedanoReceiver.ACTION_OPEN_BOX.equals(action)) {
            List<Device> devices = DeviceManager.getDevices(this);
            if (!devices.isEmpty()) {
                onDeviceActionClick(devices.get(0));
            }
            return;
        }

        Uri data = intent.getData();
        if (data != null && "apritisedano".equals(data.getScheme()) && "trigger".equals(data.getHost())) {
            String targetMac = data.getQueryParameter("target");
            if (targetMac != null) {
                Device device = DeviceManager.getDeviceByMac(this, targetMac);
                if (device != null) {
                    Log.d(TAG, "NFC Deep Link trigger for: " + targetMac);
                    onDeviceActionClick(device);
                } else {
                    Toast.makeText(this, "Dispositivo " + targetMac + " non configurato.", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private void showDeviceSettingsDialog(Device device) {
        Device fresh = DeviceManager.getDeviceByMac(this, device.getMacAddress());
        final Device currentDevice = (fresh != null) ? fresh : device;

        final String optPairing    = "Attiva Modalità Accoppiamento";
        final String optOta        = "Aggiornamento Firmware (OTA)";
        final String optSerialMenu = "Menu di Configurazione Seriale (USB)";
        final String optCable      = "Flash Firmware via Cavo USB (OTG)";
        final String optDebug      = "Debug Dispositivo";
        final String optNfc        = "Scrivi Tag NFC";
        final String optQr         = "Mostra QR Backup Dispositivo";
        final String optRtc        = "Sincronizza Orologio RTC";
        final String optDelete     = "Elimina Dispositivo";

        List<String> optionList = new ArrayList<>();
        optionList.add(optPairing);
        // Le voci riservate alla Master App (OTA e CLI seriale) sono visibili ESCLUSIVAMENTE se questo smartphone è Master
        if (currentDevice.isMaster()) {
            optionList.add(optOta);
            optionList.add(optSerialMenu);
        }
        optionList.add(optCable);
        optionList.add(optDebug);
        optionList.add(optNfc);
        optionList.add(optQr);
        optionList.add(optRtc);
        optionList.add(optDelete);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        String roleSuffix = currentDevice.isMaster() ? " [MASTER]" : "";
        builder.setTitle(String.format(Locale.US, "%s%s (ID: 0x%04X, Cnt: %d)",
                currentDevice.getName(), roleSuffix, currentDevice.getClientId(), currentDevice.getCounter()));

        CharSequence[] options = optionList.toArray(new CharSequence[0]);
        builder.setItems(options, (dialog, which) -> {
            String selected = optionList.get(which);
            if (optPairing.equals(selected)) {
                enableRemotePairingMode(currentDevice);
            } else if (optOta.equals(selected)) {
                promptSelectOtaFirmware(currentDevice);
            } else if (optSerialMenu.equals(selected)) {
                startActivity(new Intent(this, SerialConsoleActivity.class));
            } else if (optCable.equals(selected)) {
                showCableFlashDialog();
            } else if (optDebug.equals(selected)) {
                Intent debugIntent = new Intent(this, DebugActivity.class);
                debugIntent.putExtra("EXTRA_TARGET_MAC", currentDevice.getMacAddress());
                startActivity(debugIntent);
            } else if (optNfc.equals(selected)) {
                startNfcConfig(currentDevice.getMacAddress());
            } else if (optQr.equals(selected)) {
                showQrDialog(currentDevice);
            } else if (optRtc.equals(selected)) {
                syncDeviceRtcTime(currentDevice);
            } else if (optDelete.equals(selected)) {
                DeviceManager.removeDevice(this, currentDevice.getMacAddress());
                deviceList = DeviceManager.getDevices(this);
                deviceAdapter.updateDevices(deviceList);
                Toast.makeText(this, "Dispositivo eliminato", Toast.LENGTH_SHORT).show();
            }
        });
        builder.show();
    }

    private void promptSelectOtaFirmware(Device device) {
        if (!hasRequiredPermissions()) {
            checkAndRequestPermissions();
            return;
        }
        if (!device.isMaster()) {
            Toast.makeText(this, "Operazione consentita solo alla Master App.", Toast.LENGTH_SHORT).show();
            return;
        }
        this.pendingOtaDevice = device;
        this.pendingOtaFirmwareBytes = null;
        this.pendingOtaSha256 = null;
        otaFilePickerLauncher.launch(new String[]{"application/octet-stream", "*/*"});
    }

    private String queryDisplayName(Uri uri) {
        String displayName = "firmware.bin";
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0 && !cursor.isNull(idx)) {
                    displayName = cursor.getString(idx);
                }
            }
        } catch (Exception ignored) {}
        return displayName;
    }

    private void handleSelectedOtaFirmwareUri(Device device, Uri uri) {
        String fileName = queryDisplayName(uri);
        byte[] fwBytes;

        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) {
                showResultAlertDialog("Errore Lettura File", "Impossibile aprire il file selezionato.");
                return;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int read;
            int total = 0;
            while ((read = is.read(buf)) != -1) {
                total += read;
                if (total > OtaUpdater.MAX_OTA_FIRMWARE_BYTES) {
                    showResultAlertDialog(
                            "File Firmware Troppo Grande",
                            String.format(Locale.US,
                                    "Il file '%s' supera la dimensione massima della partizione OTA (1.875 MB / %d byte).\n\n" +
                                    "Assicurati di selezionare il file dell'applicazione compilata (es. MCU.ino.bin) e NON il dump completo di flash (merged.bin).",
                                    fileName, OtaUpdater.MAX_OTA_FIRMWARE_BYTES)
                    );
                    return;
                }
                bos.write(buf, 0, read);
            }
            fwBytes = bos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "Errore caricamento file OTA", e);
            showResultAlertDialog("Errore Lettura File", "Errore durante la lettura del file: " + e.getMessage());
            return;
        }

        if (fwBytes.length < 65536) {
            showResultAlertDialog(
                    "File Firmware Non Valido",
                    "Il file selezionato (" + fwBytes.length + " byte) è troppo piccolo per essere un firmware ESP32 valido."
            );
            return;
        }

        // Ogni immagine applicativa ESP32 / ESP32-C6 valida inizia con il magic byte 0xE9
        if ((fwBytes[0] & 0xFF) != 0xE9) {
            showResultAlertDialog(
                    "Formato Firmware Non Valido",
                    String.format(Locale.US,
                            "Il file '%s' non è un'immagine firmware ESP32 valida (magic byte iniziale 0x%02X invece di 0xE9).\n\n" +
                            "Seleziona il file binario compilato 'MCU.ino.bin'.",
                            fileName, fwBytes[0] & 0xFF)
            );
            return;
        }

        byte[] sha256 = TOTPGenerator.computeSha256(fwBytes);
        StringBuilder shaHexPrefix = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            shaHexPrefix.append(String.format(Locale.US, "%02X", sha256[i] & 0xFF));
        }

        this.pendingOtaDevice = device;
        this.pendingOtaFirmwareBytes = fwBytes;
        this.pendingOtaSha256 = sha256;

        String msg = String.format(Locale.US,
                "Dispositivo: %s (%s)\n" +
                "File: %s\n" +
                "Dimensione: %.1f KB (%d byte)\n" +
                "SHA-256: %s...\n\n" +
                "Durante l'aggiornamento mantieni lo smartphone vicino al ricevitore e non chiudere l'applicazione.",
                device.getName(),
                device.getMacAddress(),
                fileName,
                fwBytes.length / 1024.0f,
                fwBytes.length,
                shaHexPrefix.toString());

        new AlertDialog.Builder(this)
                .setTitle("Conferma Aggiornamento OTA")
                .setMessage(msg)
                .setPositiveButton("Avvia Aggiornamento OTA", (d, w) -> beginAuthenticatedOtaFlow())
                .setNegativeButton("Annulla", (d, w) -> {
                    pendingOtaFirmwareBytes = null;
                    pendingOtaSha256 = null;
                })
                .show();
    }

    private void beginAuthenticatedOtaFlow() {
        if (pendingOtaDevice == null || pendingOtaFirmwareBytes == null || pendingOtaSha256 == null) {
            return;
        }

        isOtaSessionInProgress = true;
        stopStateScanning();
        deviceAdapter.markActionPending(pendingOtaDevice.getMacAddress(), true);

        showOtaUploadProgressDialog(pendingOtaDevice);
        pendingModalOperation = ApritiSedanoService.ACTION_ENTER_OTA;

        Intent serviceIntent = new Intent(this, ApritiSedanoService.class);
        serviceIntent.setAction(ApritiSedanoService.ACTION_ENTER_OTA);
        serviceIntent.putExtra(ApritiSedanoService.EXTRA_TARGET_MAC, pendingOtaDevice.getMacAddress());
        serviceIntent.putExtra(ApritiSedanoService.EXTRA_SECRET_KEY, pendingOtaDevice.getSecretKey());
        serviceIntent.putExtra(ApritiSedanoService.EXTRA_CLIENT_ID, pendingOtaDevice.getClientId());
        startServiceCompat(serviceIntent);
    }

    private void showOtaUploadProgressDialog(Device device) {
        dismissOtaUploadProgressDialog();

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Aggiornamento Firmware OTA");

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(20);
        container.setPadding(pad, pad, pad, pad);

        TextView tvDevice = new TextView(this);
        tvDevice.setText(device.getName() + " (" + device.getMacAddress() + ")");
        tvDevice.setTypeface(null, Typeface.BOLD);
        tvDevice.setTextSize(14f);
        tvDevice.setPadding(0, 0, 0, dpToPx(10));
        container.addView(tvDevice);

        tvOtaStatus = new TextView(this);
        tvOtaStatus.setText("Attivazione modalità OTA sul ricevitore (Rolling-Code Master)...");
        tvOtaStatus.setTextSize(13.5f);
        tvOtaStatus.setPadding(0, 0, 0, dpToPx(14));
        container.addView(tvOtaStatus);

        pbOtaProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pbOtaProgress.setIndeterminate(false);
        pbOtaProgress.setMax(100);
        pbOtaProgress.setProgress(0);
        container.addView(pbOtaProgress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        tvOtaDetails = new TextView(this);
        tvOtaDetails.setText("In attesa di ACK_OTA_READY...");
        tvOtaDetails.setTextSize(13f);
        tvOtaDetails.setTextColor(Color.parseColor("#616161"));
        tvOtaDetails.setGravity(Gravity.END);
        tvOtaDetails.setPadding(0, dpToPx(8), 0, 0);
        container.addView(tvOtaDetails);

        builder.setView(container);
        builder.setCancelable(false);
        builder.setNegativeButton("Annulla", (d, w) -> {
            stopService(new Intent(this, ApritiSedanoService.class));
            if (activeOtaUpdater != null) {
                activeOtaUpdater.cancel();
                activeOtaUpdater = null;
            } else {
                String mac = (pendingOtaDevice != null) ? pendingOtaDevice.getMacAddress() : null;
                finishOtaUiSession();
                if (mac != null) deviceAdapter.markActionPending(mac, false);
            }
        });

        otaProgressDialog = builder.create();
        otaProgressDialog.show();
    }

    private void dismissOtaUploadProgressDialog() {
        if (otaProgressDialog != null && otaProgressDialog.isShowing()) {
            try { otaProgressDialog.dismiss(); } catch (Exception ignored) {}
        }
        otaProgressDialog = null;
        pbOtaProgress = null;
        tvOtaStatus = null;
        tvOtaDetails = null;
    }

    private void finishOtaUiSession() {
        isOtaSessionInProgress = false;
        activeOtaUpdater = null;
        pendingOtaFirmwareBytes = null;
        pendingOtaSha256 = null;
        dismissOtaUploadProgressDialog();
        startStateScanning();
    }

    private void startGattOtaTransfer(long authorizedCounter) {
        if (pendingOtaDevice == null || pendingOtaFirmwareBytes == null || pendingOtaSha256 == null) {
            finishOtaUiSession();
            return;
        }

        final String targetMac = pendingOtaDevice.getMacAddress();
        final String targetName = pendingOtaDevice.getName();

        if (tvOtaStatus != null) {
            tvOtaStatus.setText("Modalità OTA attiva! Connessione BLE GATT in corso...");
        }
        if (tvOtaDetails != null) {
            tvOtaDetails.setText(String.format(Locale.US, "0%%  •  0.0 / %.1f KB",
                    pendingOtaFirmwareBytes.length / 1024.0f));
        }

        activeOtaUpdater = new OtaUpdater(
                this,
                targetMac,
                pendingOtaDevice.getSecretKey(),
                authorizedCounter,
                pendingOtaFirmwareBytes,
                pendingOtaSha256,
                new OtaUpdater.Listener() {
                    @Override
                    public void onStatusChanged(String statusMessage) {
                        if (tvOtaStatus != null) {
                            tvOtaStatus.setText(statusMessage);
                        }
                    }

                    @Override
                    public void onProgress(int bytesSent, int totalBytes, int percent) {
                        if (pbOtaProgress != null) {
                            pbOtaProgress.setProgress(percent);
                        }
                        if (tvOtaDetails != null) {
                            tvOtaDetails.setText(String.format(Locale.US,
                                    "%d%%  •  %.1f / %.1f KB",
                                    percent,
                                    bytesSent / 1024.0f,
                                    totalBytes / 1024.0f));
                        }
                    }

                    @Override
                    public void onSuccess() {
                        finishOtaUiSession();
                        deviceAdapter.markActionPending(targetMac, false);
                        showResultAlertDialog(
                                "Aggiornamento OTA Completato",
                                "Il nuovo firmware è stato trasferito, verificato crittograficamente (SHA-256) e attivato con successo su " +
                                targetName + "!\n\nIl ricevitore si sta riavviando sulla nuova versione."
                        );
                    }

                    @Override
                    public void onError(String errorMessage) {
                        finishOtaUiSession();
                        deviceAdapter.markActionPending(targetMac, false);
                        showResultAlertDialog("Aggiornamento OTA Non Riuscito", errorMessage);
                    }
                }
        );
        activeOtaUpdater.start();
    }

    private void enableRemotePairingMode(Device device) {
        if (!hasRequiredPermissions()) {
            checkAndRequestPermissions();
            return;
        }
        deviceAdapter.markActionPending(device.getMacAddress(), true);

        showOperationProgressDialog(
                "Attivazione Modalità Accoppiamento",
                "Invio comando al ricevitore " + device.getName() + " (" + device.getMacAddress() + ") per abilitare la registrazione di un nuovo smartphone...",
                ApritiSedanoService.ACTION_ENABLE_REMOTE_PAIRING
        );

        Intent serviceIntent = new Intent(this, ApritiSedanoService.class);
        serviceIntent.setAction(ApritiSedanoService.ACTION_ENABLE_REMOTE_PAIRING);
        serviceIntent.putExtra(ApritiSedanoService.EXTRA_TARGET_MAC, device.getMacAddress());
        serviceIntent.putExtra(ApritiSedanoService.EXTRA_SECRET_KEY, device.getSecretKey());
        serviceIntent.putExtra(ApritiSedanoService.EXTRA_CLIENT_ID, device.getClientId());
        startServiceCompat(serviceIntent);
    }

    private void syncDeviceRtcTime(Device device) {
        if (!hasRequiredPermissions()) {
            checkAndRequestPermissions();
            return;
        }
        deviceAdapter.markActionPending(device.getMacAddress(), true);

        Intent serviceIntent = new Intent(this, ApritiSedanoService.class);
        serviceIntent.setAction(ApritiSedanoService.ACTION_SYNC_TIME);
        serviceIntent.putExtra(ApritiSedanoService.EXTRA_TARGET_MAC, device.getMacAddress());
        serviceIntent.putExtra(ApritiSedanoService.EXTRA_SECRET_KEY, device.getSecretKey());
        serviceIntent.putExtra(ApritiSedanoService.EXTRA_CLIENT_ID, device.getClientId());
        startServiceCompat(serviceIntent);
    }

    private void showAddDeviceScanDialog() {
        if (!hasRequiredPermissions()) {
            checkAndRequestPermissions();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_scan_devices, null);
        builder.setView(view);

        ListView lvScanned = view.findViewById(R.id.lv_scanned_devices);
        Button btnCancel = view.findViewById(R.id.btn_cancel_scan);

        discoveredDevices.clear();
        discoveryAdapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return discoveredDevices.size();
            }

            @Override
            public Object getItem(int position) {
                return discoveredDevices.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                DiscoveredDevice item = discoveredDevices.get(position);
                Device enrolled = DeviceManager.getDeviceByMac(MainActivity.this, item.mac);

                LinearLayout card = new LinearLayout(MainActivity.this);
                card.setOrientation(LinearLayout.VERTICAL);
                int pad = dpToPx(12);
                card.setPadding(pad, pad, pad, pad);

                TextView tvHeader = new TextView(MainActivity.this);
                String titleText = (enrolled != null)
                        ? enrolled.getName() + " (" + item.mac + ") • " + item.rssi + " dBm"
                        : item.mac + " • " + item.rssi + " dBm";
                tvHeader.setText(titleText);
                tvHeader.setTextSize(15f);
                tvHeader.setTypeface(null, Typeface.BOLD);
                tvHeader.setTextColor(Color.parseColor("#212121"));
                card.addView(tvHeader);

                TextView tvBadge = new TextView(MainActivity.this);
                tvBadge.setTextSize(13f);
                tvBadge.setTypeface(null, Typeface.BOLD);
                tvBadge.setPadding(0, dpToPx(4), 0, dpToPx(4));
                card.addView(tvBadge);

                TextView tvHint = new TextView(MainActivity.this);
                tvHint.setTextSize(12.5f);
                tvHint.setTextColor(Color.parseColor("#424242"));
                tvHint.setPadding(0, 0, 0, dpToPx(8));
                card.addView(tvHint);

                LinearLayout btnRow = new LinearLayout(MainActivity.this);
                btnRow.setOrientation(LinearLayout.HORIZONTAL);
                card.addView(btnRow);

                if (item.inPairingMode) {
                    int remSec = Math.max(1, item.getRemainingSeconds(System.currentTimeMillis()));
                    card.setBackgroundColor(Color.parseColor("#E8F5E9"));
                    tvBadge.setText(String.format(Locale.US, "🟢 IN MODALITÀ ACCOPPIAMENTO (%ds)", remSec));
                    tvBadge.setTextColor(Color.parseColor("#2E7D32"));
                    tvHint.setText("Il ricevitore è pronto per registrare un nuovo smartphone.");

                    Button btnPairNow = new Button(MainActivity.this);
                    btnPairNow.setText("Associa questo smartphone");
                    btnPairNow.setAllCaps(false);
                    btnPairNow.setOnClickListener(v -> {
                        stopDiscoveryScan();
                        if (discoveryDialog != null && discoveryDialog.isShowing()) {
                            discoveryDialog.dismiss();
                        }
                        showFinalDeviceConfigDialog(item.mac);
                    });
                    btnRow.addView(btnPairNow);
                } else if (enrolled != null) {
                    card.setBackgroundColor(Color.parseColor("#E3F2FD"));
                    tvBadge.setText("🔵 GIÀ ASSOCIATO A QUESTO SMARTPHONE");
                    tvBadge.setTextColor(Color.parseColor("#1565C0"));
                    tvHint.setText("Il dispositivo non è in modalità accoppiamento. Premi il pulsante qui sotto per metterlo in modalità accoppiamento e registrare un altro smartphone.");

                    Button btnEnableRemotePair = new Button(MainActivity.this);
                    btnEnableRemotePair.setText("Attiva Modalità Accoppiamento");
                    btnEnableRemotePair.setAllCaps(false);
                    btnEnableRemotePair.setOnClickListener(v -> enableRemotePairingMode(enrolled));
                    btnRow.addView(btnEnableRemotePair);
                } else {
                    card.setBackgroundColor(Color.parseColor("#FFF3E0"));
                    tvBadge.setText("🟠 NON IN MODALITÀ ACCOPPIAMENTO");
                    tvBadge.setTextColor(Color.parseColor("#E65100"));
                    tvHint.setText("💡 Tieni premuto il pulsante fisico sul dispositivo per 3 secondi per metterlo in modalità accoppiamento (oppure attivala da uno smartphone già associato).");

                    Button btnQr = new Button(MainActivity.this);
                    btnQr.setText("Importa QR Backup");
                    btnQr.setAllCaps(false);
                    btnQr.setOnClickListener(v -> {
                        stopDiscoveryScan();
                        if (discoveryDialog != null && discoveryDialog.isShowing()) {
                            discoveryDialog.dismiss();
                        }
                        pendingQrTargetMac = item.mac;
                        pendingQrDeviceName = "ApritiSedano";
                        ScanOptions options = new ScanOptions();
                        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
                        options.setPrompt("Inquadra il QR Code di backup");
                        barcodeLauncher.launch(options);
                    });
                    btnRow.addView(btnQr);
                }

                return card;
            }
        };
        lvScanned.setAdapter(discoveryAdapter);

        lvScanned.setOnItemClickListener((parent, v, position, id) -> {
            DiscoveredDevice item = discoveredDevices.get(position);
            if (item.inPairingMode) {
                stopDiscoveryScan();
                if (discoveryDialog != null && discoveryDialog.isShowing()) {
                    discoveryDialog.dismiss();
                }
                showFinalDeviceConfigDialog(item.mac);
            } else {
                Device enrolled = DeviceManager.getDeviceByMac(MainActivity.this, item.mac);
                if (enrolled != null) {
                    enableRemotePairingMode(enrolled);
                } else {
                    Toast.makeText(
                            MainActivity.this,
                            "Dispositivo non in modalità accoppiamento: tieni premuto per 3s il pulsante fisico sul ricevitore.",
                            Toast.LENGTH_LONG
                    ).show();
                }
            }
        });

        discoveryDialog = builder.create();
        discoveryDialog.setCancelable(false);

        btnCancel.setOnClickListener(v -> {
            stopDiscoveryScan();
            discoveryDialog.dismiss();
        });

        discoveryDialog.show();
        startDiscoveryScan();
    }

    private void startDiscoveryScan() {
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (manager != null && manager.getAdapter() != null) {
            discoveryScanner = manager.getAdapter().getBluetoothLeScanner();
            if (discoveryScanner != null) {
                ScanSettings scanSettings = new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .build();
                ScanFilter filter = new ScanFilter.Builder()
                        .setServiceUuid(new ParcelUuid(TARGET_SERVICE_UUID))
                        .build();
                try {
                    discoveryScanner.startScan(Collections.singletonList(filter), scanSettings, discoveryScanCallback);
                } catch (SecurityException e) {
                    Log.e(TAG, "Permessi BLE mancanti", e);
                }
            }
        }
    }

    private void stopDiscoveryScan() {
        if (discoveryScanner != null) {
            try {
                if (hasRequiredPermissions()) discoveryScanner.stopScan(discoveryScanCallback);
            } catch (SecurityException ignored) {}
        }
    }

    private static boolean isPayloadPairingMode(byte[] payload) {
        if (payload == null || payload.length == 0) return false;
        if (payload[0] == 0x04) return true;
        if (payload.length >= 12) {
            String ascii = new String(payload, StandardCharsets.UTF_8);
            if (ascii.contains("ACK_PAIR_MODE")) return true;
        }
        return false;
    }

    private static int extractBeaconRemainingSeconds(byte[] payload) {
        if (payload != null && payload.length >= 6 && payload[0] == 0x04) {
            long val = ((long) (payload[2] & 0xFF) << 24) |
                       ((long) (payload[3] & 0xFF) << 16) |
                       ((long) (payload[4] & 0xFF) << 8)  |
                       ((long) (payload[5] & 0xFF));
            if (val >= 1L && val <= 60L) {
                return (int) val;
            }
        }
        return -1;
    }

    private static boolean isPayloadNormalState(byte[] payload) {
        return payload != null && payload.length >= 2 && payload[0] == 0x01;
    }

    private static boolean isPayloadAckPaired(byte[] payload) {
        if (payload != null && payload.length >= 10) {
            String ascii = new String(payload, StandardCharsets.UTF_8);
            return ascii.contains("ACK_PAIRED");
        }
        return false;
    }

    private void updateMasterStatusFromBeacon(String mac, byte[] payload) {
        if (mac == null || payload == null || payload.length < 10) return;
        if (payload[0] != 0x01 && payload[0] != 0x04 && payload[0] != 0x05) return;

        Device dev = deviceAdapter.getDeviceInList(mac);
        if (dev == null) return;

        long beaconMasterTag = ((payload[6] & 0xFFL) << 24) |
                               ((payload[7] & 0xFFL) << 16) |
                               ((payload[8] & 0xFFL) << 8)  |
                               (payload[9] & 0xFFL);

        boolean isMaster = false;
        if (beaconMasterTag != 0L) {
            long localTag = TOTPGenerator.computeMasterTag(dev.getSecretKey(), dev.getMacAddress());
            isMaster = (localTag != 0L && localTag == beaconMasterTag);
        }

        if (dev.isMaster() != isMaster) {
            dev.setMaster(isMaster);
            DeviceManager.updateMasterStatus(this, mac, isMaster);
            deviceAdapter.updateDeviceMasterStatus(mac, isMaster);
        }
    }

    private final ScanCallback discoveryScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (result.getDevice() != null) {
                final String mac = result.getDevice().getAddress();
                final int rssi = result.getRssi();
                final byte[] payload = (result.getScanRecord() != null)
                        ? result.getScanRecord().getManufacturerSpecificData(0x02E5)
                        : null;

                runOnUiThread(() -> {
                    long now = System.currentTimeMillis();
                    DiscoveredDevice existing = null;
                    for (DiscoveredDevice d : discoveredDevices) {
                        if (d.mac.equalsIgnoreCase(mac)) {
                            existing = d;
                            break;
                        }
                    }

                    boolean seenPairingNow = isPayloadPairingMode(payload);
                    boolean seenNormalNow = isPayloadNormalState(payload) || isPayloadAckPaired(payload);
                    int beaconRemSec = extractBeaconRemainingSeconds(payload);

                    if (existing == null) {
                        existing = new DiscoveredDevice(mac, rssi, seenPairingNow, beaconRemSec > 0 ? beaconRemSec : 60);
                        discoveredDevices.add(existing);
                    } else {
                        existing.rssi = rssi;
                        if (seenPairingNow) {
                            existing.updatePairingState(now, beaconRemSec);
                        } else if (seenNormalNow) {
                            if (isPayloadAckPaired(payload) || (now - existing.lastPairingSeenMs > 1500L)) {
                                existing.inPairingMode = false;
                                existing.pairingEndTimeMs = 0L;
                            }
                        }
                    }

                    if (discoveryAdapter != null) {
                        discoveryAdapter.notifyDataSetChanged();
                    }
                });
            }
        }
    };

    private void showFinalDeviceConfigDialog(String macAddress) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Associa Ricevitore: " + macAddress);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(20);
        container.setPadding(pad, pad, pad, pad);

        TextView tvInstructions = new TextView(this);
        tvInstructions.setText("🟢 Il ricevitore è in Modalità Accoppiamento!\nInserisci un nome per identificare il varco e premi 'Accoppia via BLE'.");
        tvInstructions.setTextColor(Color.parseColor("#2E7D32"));
        tvInstructions.setPadding(0, 0, 0, pad / 2);
        container.addView(tvInstructions);

        EditText etName = new EditText(this);
        etName.setHint("Nome Dispositivo (es. Cancello)");
        Device existing = DeviceManager.getDeviceByMac(this, macAddress);
        if (existing != null && existing.getName() != null) {
            etName.setText(existing.getName());
        }
        container.addView(etName);

        Button btnImportQr = new Button(this);
        btnImportQr.setText("Importa da QR Backup");
        btnImportQr.setAllCaps(false);
        btnImportQr.setOnClickListener(v -> {
            pendingQrTargetMac = macAddress;
            pendingQrDeviceName = etName.getText().toString().trim();
            ScanOptions options = new ScanOptions();
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
            options.setPrompt("Inquadra il QR Code di backup");
            barcodeLauncher.launch(options);
        });
        container.addView(btnImportQr);

        builder.setView(container);
        builder.setPositiveButton("Accoppia via BLE", null);
        builder.setNegativeButton("Annulla", (dialog, which) -> dialog.cancel());

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String name = etName.getText().toString().trim();
                if (name.isEmpty()) {
                    Toast.makeText(this, "Inserisci il nome del dispositivo", Toast.LENGTH_SHORT).show();
                    return;
                }

                int newClientId = TOTPGenerator.generateRandomClientId();
                String newSeedHex = TOTPGenerator.generateRandomSeedHex();

                dialog.dismiss();

                showOperationProgressDialog(
                        "Accoppiamento BLE in corso",
                        "Registrazione del seed rolling-code sul ricevitore " + macAddress + " in corso...\nAttendere la conferma dal dispositivo.",
                        ApritiSedanoService.ACTION_PAIR_NEW_DEVICE
                );

                Intent pairIntent = new Intent(this, ApritiSedanoService.class);
                pairIntent.setAction(ApritiSedanoService.ACTION_PAIR_NEW_DEVICE);
                pairIntent.putExtra(ApritiSedanoService.EXTRA_TARGET_MAC, macAddress);
                pairIntent.putExtra(ApritiSedanoService.EXTRA_DEVICE_NAME, name);
                pairIntent.putExtra(ApritiSedanoService.EXTRA_CLIENT_ID, newClientId);
                pairIntent.putExtra(ApritiSedanoService.EXTRA_SECRET_KEY, newSeedHex);
                startServiceCompat(pairIntent);
            });
        });
        dialog.show();
    }

    private final ScanCallback stateScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (result.getScanRecord() != null && result.getDevice() != null) {
                byte[] payload = result.getScanRecord().getManufacturerSpecificData(0x02E5);
                String mac = result.getDevice().getAddress();

                if (payload != null && payload.length >= 2) {
                    if (isPayloadPairingMode(payload)) {
                        int beaconRemSec = extractBeaconRemainingSeconds(payload);
                        runOnUiThread(() -> {
                            updateMasterStatusFromBeacon(mac, payload);
                            if (beaconRemSec > 0) {
                                deviceAdapter.startDevicePairingCountdown(mac, beaconRemSec);
                                if (mac.equalsIgnoreCase(activePairingModalMac)) {
                                    activePairingModalEndTimeMs = System.currentTimeMillis() + (beaconRemSec * 1000L);
                                }
                            } else {
                                deviceAdapter.updateDeviceState(mac, 5);
                            }
                        });
                    } else if (payload[0] == 0x01 || isPayloadAckPaired(payload)) {
                        final int newState = (payload[0] == 0x01) ? (payload[1] == 1 ? 1 : 2) : -1;
                        runOnUiThread(() -> {
                            updateMasterStatusFromBeacon(mac, payload);
                            deviceAdapter.updateDeviceState(mac, newState);
                            if (mac.equalsIgnoreCase(activePairingModalMac) &&
                                    pairingCountdownDialog != null &&
                                    pairingCountdownDialog.isShowing()) {
                                try { pairingCountdownDialog.dismiss(); } catch (Exception ignored) {}
                                pairingCountdownDialog = null;
                            }
                        });
                    } else if (payload[0] == 0x05) {
                        runOnUiThread(() -> updateMasterStatusFromBeacon(mac, payload));
                    }
                }
            }
        }
    };

    private void startStateScanning() {
        if (!hasRequiredPermissions() || isOtaSessionInProgress) return;
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (manager != null && manager.getAdapter() != null) {
            stateScanner = manager.getAdapter().getBluetoothLeScanner();
            if (stateScanner != null) {
                ScanSettings scanSettings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
                ScanFilter filter = new ScanFilter.Builder()
                        .setManufacturerData(0x02E5, new byte[]{}, new byte[]{})
                        .setServiceUuid(new ParcelUuid(TARGET_SERVICE_UUID))
                        .build();
                try {
                    stateScanner.startScan(Collections.singletonList(filter), scanSettings, stateScanCallback);
                } catch (SecurityException e) {
                    Log.e(TAG, "Permessi BLE mancanti per lo scan", e);
                }
            }
        }
    }

    private void stopStateScanning() {
        if (stateScanner != null) {
            try {
                if (hasRequiredPermissions()) stateScanner.stopScan(stateScanCallback);
            } catch (SecurityException ignored) {}
        }
    }

    private void startNfcConfig(String targetMac) {
        if (nfcAdapter == null || !nfcAdapter.isEnabled()) {
            Toast.makeText(this, "NFC non attivo o non supportato", Toast.LENGTH_LONG).show();
            return;
        }
        isNfcWriteMode = true;
        nfcWriteTargetMac = targetMac;

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Configura Tag NFC");
        builder.setMessage("Avvicina un Tag NFC (vuoto o da sovrascrivere) al retro del telefono per configurare il dispositivo " + targetMac);
        builder.setOnCancelListener(dialog -> isNfcWriteMode = false);
        nfcWriteDialog = builder.create();
        nfcWriteDialog.show();
    }

    private void writeTag(Tag tag, String targetMac) {
        NdefMessage message = createNdefMessage(targetMac);
        try {
            Ndef ndef = Ndef.get(tag);
            if (ndef != null) {
                ndef.connect();
                if (!ndef.isWritable()) {
                    Toast.makeText(this, "Tag NFC in sola lettura.", Toast.LENGTH_SHORT).show();
                    return;
                }
                ndef.writeNdefMessage(message);
                Toast.makeText(this, "Tag NFC configurato con successo!", Toast.LENGTH_LONG).show();
            } else {
                NdefFormatable format = NdefFormatable.get(tag);
                if (format != null) {
                    format.connect();
                    format.format(message);
                    Toast.makeText(this, "Tag NFC formattato e configurato!", Toast.LENGTH_LONG).show();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Errore NFC", e);
            Toast.makeText(this, "Errore in scrittura", Toast.LENGTH_SHORT).show();
        } finally {
            isNfcWriteMode = false;
            if (nfcWriteDialog != null && nfcWriteDialog.isShowing()) nfcWriteDialog.dismiss();
        }
    }

    private NdefMessage createNdefMessage(String targetMac) {
        String uri = "apritisedano://trigger?target=" + targetMac;
        NdefRecord uriRecord = NdefRecord.createUri(uri);
        NdefRecord aarRecord = NdefRecord.createApplicationRecord("it.geniola.apritisedano");
        return new NdefMessage(new NdefRecord[]{uriRecord, aarRecord});
    }

    private boolean hasRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
                   ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                   ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void checkAndRequestPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsNeeded.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN);
            permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        List<String> listPermissionsNeeded = new ArrayList<>();
        for (String p : permissionsNeeded) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) listPermissionsNeeded.add(p);
        }

        if (!listPermissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, listPermissionsNeeded.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) if (result != PackageManager.PERMISSION_GRANTED) allGranted = false;
            if (allGranted) startStateScanning();
        }
    }

    private void showQrDialog(Device device) {
        try {
            BarcodeEncoder barcodeEncoder = new BarcodeEncoder();
            String uriContent = String.format(Locale.US,
                    "apritisedano://rolling?mac=%s&name=%s&id=%d&seed=%s&cnt=%d",
                    Uri.encode(device.getMacAddress()),
                    Uri.encode(device.getName()),
                    device.getClientId(),
                    Uri.encode(device.getSecretKey()),
                    device.getCounter());
            Bitmap bitmap = barcodeEncoder.encodeBitmap(uriContent, BarcodeFormat.QR_CODE, 600, 600);

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("QR Backup Dispositivo");

            ImageView imageView = new ImageView(this);
            imageView.setImageBitmap(bitmap);
            imageView.setPadding(32, 32, 32, 32);
            builder.setView(imageView);

            builder.setPositiveButton("Condividi", (dialog, which) -> shareQrCode(bitmap));
            builder.setNegativeButton("Chiudi", (dialog, which) -> dialog.dismiss());
            builder.show();
        } catch (Exception ignored) {}
    }

    private void shareQrCode(Bitmap bitmap) {
        try {
            File cachePath = new File(getCacheDir(), "shared_qr");
            cachePath.mkdirs();
            File file = new File(cachePath, "qr_code.png");
            FileOutputStream stream = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            stream.close();

            Uri contentUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            if (contentUri != null) {
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                shareIntent.setDataAndType(contentUri, getContentResolver().getType(contentUri));
                shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
                startActivity(Intent.createChooser(shareIntent, "Condividi QR Code"));
            }
        } catch (Exception ignored) {}
    }

    // ==========================================
    // MENU OPZIONI PRINCIPALE (ACTION BAR)
    // ==========================================

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem itemSerial = menu.findItem(R.id.action_serial_menu);
        if (itemSerial != null) {
            itemSerial.setVisible(DeviceManager.hasMasterDevice(this));
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_serial_menu) {
            startActivity(new Intent(this, SerialConsoleActivity.class));
            return true;
        } else if (id == R.id.action_flash_cable) {
            showCableFlashDialog();
            return true;
        } else if (id == R.id.action_debug_mode) {
            startActivity(new Intent(this, DebugActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ==========================================
    // FLASHING FIRMWARE VIA CAVO USB (OTG)
    // ==========================================

    private void showCableFlashDialog() {
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        List<UsbSerialDriver> drivers = Esp32SerialFlasher.getAvailableDrivers(usbManager);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Flash Firmware via Cavo USB (OTG)");

        ScrollView scrollView = new ScrollView(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(18);
        container.setPadding(pad, pad, pad, pad);
        scrollView.addView(container);

        // 1. STATO DISPOSITIVO USB
        TextView tvDevHeader = new TextView(this);
        tvDevHeader.setText("1. Dispositivo USB collegato:");
        tvDevHeader.setTypeface(null, Typeface.BOLD);
        tvDevHeader.setTextSize(14.5f);
        container.addView(tvDevHeader);

        tvCableDeviceStatus = new TextView(this);
        tvCableDeviceStatus.setTextSize(13.5f);
        tvCableDeviceStatus.setPadding(0, dpToPx(4), 0, dpToPx(6));
        container.addView(tvCableDeviceStatus);

        updateCableDeviceStatus(drivers);

        Button btnRefreshUsb = new Button(this);
        btnRefreshUsb.setText("Aggiorna Rilevamento USB");
        btnRefreshUsb.setTextSize(12.5f);
        btnRefreshUsb.setOnClickListener(v -> {
            List<UsbSerialDriver> refreshed = Esp32SerialFlasher.getAvailableDrivers(usbManager);
            updateCableDeviceStatus(refreshed);
        });
        container.addView(btnRefreshUsb);

        // 2. SELEZIONE FIRMWARE (.BIN)
        TextView tvFwHeader = new TextView(this);
        tvFwHeader.setText("2. Seleziona file firmware (.bin):");
        tvFwHeader.setTypeface(null, Typeface.BOLD);
        tvFwHeader.setTextSize(14.5f);
        tvFwHeader.setPadding(0, dpToPx(14), 0, dpToPx(4));
        container.addView(tvFwHeader);

        tvCableFirmwareStatus = new TextView(this);
        tvCableFirmwareStatus.setTextSize(13.5f);
        tvCableFirmwareStatus.setPadding(0, 0, 0, dpToPx(6));
        updateCableFirmwareStatusText();
        container.addView(tvCableFirmwareStatus);

        Button btnPickFile = new Button(this);
        btnPickFile.setText("Scegli file dal telefono (.bin)...");
        btnPickFile.setTextSize(12.5f);
        btnPickFile.setOnClickListener(v -> {
            cableFilePickerLauncher.launch(new String[]{"application/octet-stream", "*/*"});
        });
        container.addView(btnPickFile);

        TextView tvQuickHeader = new TextView(this);
        tvQuickHeader.setText("Oppure seleziona firmware pronti in Download:");
        tvQuickHeader.setTextSize(12f);
        tvQuickHeader.setTextColor(Color.parseColor("#616161"));
        tvQuickHeader.setPadding(0, dpToPx(8), 0, dpToPx(4));
        container.addView(tvQuickHeader);

        LinearLayout quickRow1 = new LinearLayout(this);
        quickRow1.setOrientation(LinearLayout.HORIZONTAL);

        Button btnQuickWroom = new Button(this);
        btnQuickWroom.setText("ESP32-WROOM");
        btnQuickWroom.setTextSize(11f);
        btnQuickWroom.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnQuickWroom.setOnClickListener(v -> loadPredefinedFirmware("ApritiSedano_ESP32_WROOM.bin", 0x10000));
        quickRow1.addView(btnQuickWroom);

        Button btnQuickXiao = new Button(this);
        btnQuickXiao.setText("XIAO C6 (Ant. Interna)");
        btnQuickXiao.setTextSize(11f);
        btnQuickXiao.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnQuickXiao.setOnClickListener(v -> loadPredefinedFirmware("ApritiSedano_XIAO_ESP32C6_InternalAntenna.bin", 0x10000));
        quickRow1.addView(btnQuickXiao);

        container.addView(quickRow1);

        LinearLayout quickRow2 = new LinearLayout(this);
        quickRow2.setOrientation(LinearLayout.HORIZONTAL);

        Button btnQuickWroomMerged = new Button(this);
        btnQuickWroomMerged.setText("WROOM Full (4MB)");
        btnQuickWroomMerged.setTextSize(11f);
        btnQuickWroomMerged.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnQuickWroomMerged.setOnClickListener(v -> loadPredefinedFirmware("ApritiSedano_ESP32_WROOM_full_merged.bin", 0x0000));
        quickRow2.addView(btnQuickWroomMerged);

        Button btnQuickXiaoMerged = new Button(this);
        btnQuickXiaoMerged.setText("XIAO C6 Full (4MB)");
        btnQuickXiaoMerged.setTextSize(11f);
        btnQuickXiaoMerged.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnQuickXiaoMerged.setOnClickListener(v -> loadPredefinedFirmware("ApritiSedano_XIAO_ESP32C6_InternalAntenna_full_merged.bin", 0x0000));
        quickRow2.addView(btnQuickXiaoMerged);

        container.addView(quickRow2);

        // 3. OFFSET DI SCRITTURA
        TextView tvOffsetHeader = new TextView(this);
        tvOffsetHeader.setText("3. Offset Flash (Indirizzo di scrittura):");
        tvOffsetHeader.setTypeface(null, Typeface.BOLD);
        tvOffsetHeader.setTextSize(14.5f);
        tvOffsetHeader.setPadding(0, dpToPx(14), 0, dpToPx(4));
        container.addView(tvOffsetHeader);

        etCableOffset = new EditText(this);
        etCableOffset.setText(String.format(Locale.US, "0x%05X", pendingCableFlashOffset));
        etCableOffset.setTextSize(13.5f);
        container.addView(etCableOffset);

        TextView tvOffsetHint = new TextView(this);
        tvOffsetHint.setText("Usa 0x10000 per app standard/OTA; usa 0x0000 per immagini complete 'merged'.");
        tvOffsetHint.setTextSize(11.5f);
        tvOffsetHint.setTextColor(Color.parseColor("#757575"));
        tvOffsetHint.setPadding(0, 0, 0, dpToPx(10));
        container.addView(tvOffsetHint);

        builder.setView(scrollView);
        builder.setPositiveButton("Avvia Flash", null);
        builder.setNegativeButton("Chiudi", (d, w) -> d.dismiss());

        cableFlashDialog = builder.create();
        cableFlashDialog.setOnShowListener(d -> {
            btnStartCableFlash = cableFlashDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            btnStartCableFlash.setOnClickListener(v -> startCableFlashingProcess());
        });
        cableFlashDialog.show();
    }

    private void updateCableDeviceStatus(List<UsbSerialDriver> drivers) {
        if (tvCableDeviceStatus == null) return;
        if (drivers == null || drivers.isEmpty()) {
            selectedCableDriver = null;
            tvCableDeviceStatus.setText("Nessun dispositivo USB rilevato.\nCollega l'ESP32 allo smartphone con cavo USB OTG.");
            tvCableDeviceStatus.setTextColor(Color.parseColor("#C62828"));
        } else {
            selectedCableDriver = drivers.get(0);
            String desc = Esp32SerialFlasher.getDeviceDescription(selectedCableDriver);
            tvCableDeviceStatus.setText("Rilevato: " + desc);
            tvCableDeviceStatus.setTextColor(Color.parseColor("#2E7D32"));
        }
    }

    private void updateCableFirmwareStatusText() {
        if (tvCableFirmwareStatus == null) return;
        if (pendingCableFirmwareBytes == null) {
            tvCableFirmwareStatus.setText("Nessun file selezionato.");
            tvCableFirmwareStatus.setTextColor(Color.parseColor("#757575"));
        } else {
            String name = (pendingCableFirmwareName != null) ? pendingCableFirmwareName : "firmware.bin";
            tvCableFirmwareStatus.setText(String.format(Locale.US, "Selezionato: %s (%,d byte)", name, pendingCableFirmwareBytes.length));
            tvCableFirmwareStatus.setTextColor(Color.parseColor("#1565C0"));
        }
    }

    private void handleSelectedCableFirmwareUri(Uri uri) {
        String fileName = queryDisplayName(uri);
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) {
                Toast.makeText(this, "Impossibile aprire il file selezionato.", Toast.LENGTH_SHORT).show();
                return;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) != -1) {
                bos.write(buf, 0, r);
            }
            byte[] bytes = bos.toByteArray();
            if (bytes.length < 4096) {
                Toast.makeText(this, "File troppo piccolo per essere un firmware ESP32 valido.", Toast.LENGTH_LONG).show();
                return;
            }
            if ((bytes[0] & 0xFF) != 0xE9) {
                Toast.makeText(this, "Attenzione: Magic byte ESP32 (0xE9) non rilevato all'offset 0.", Toast.LENGTH_LONG).show();
            }

            pendingCableFirmwareBytes = bytes;
            pendingCableFirmwareName = fileName;

            if (bytes.length >= 2 * 1024 * 1024) {
                pendingCableFlashOffset = 0x0000;
            } else {
                pendingCableFlashOffset = 0x10000;
            }

            if (etCableOffset != null) {
                etCableOffset.setText(String.format(Locale.US, "0x%05X", pendingCableFlashOffset));
            }
            updateCableFirmwareStatusText();
            Toast.makeText(this, "File caricato: " + fileName, Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Log.e(TAG, "Errore caricamento firmware cavo", e);
            Toast.makeText(this, "Errore caricamento file: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void loadPredefinedFirmware(String fileName, int defaultOffset) {
        File downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
        File fwFile = new File(downloadDir, fileName);
        if (!fwFile.exists() || fwFile.length() == 0) {
            fwFile = new File("/sdcard/Download", fileName);
        }

        if (!fwFile.exists() || fwFile.length() == 0) {
            Toast.makeText(this, "File non trovato in Download: " + fileName, Toast.LENGTH_SHORT).show();
            return;
        }

        try (java.io.FileInputStream fis = new java.io.FileInputStream(fwFile)) {
            byte[] bytes = new byte[(int) fwFile.length()];
            int read = 0;
            while (read < bytes.length) {
                int r = fis.read(bytes, read, bytes.length - read);
                if (r < 0) break;
                read += r;
            }
            pendingCableFirmwareBytes = bytes;
            pendingCableFirmwareName = fileName;
            pendingCableFlashOffset = defaultOffset;

            if (etCableOffset != null) {
                etCableOffset.setText(String.format(Locale.US, "0x%05X", pendingCableFlashOffset));
            }
            updateCableFirmwareStatusText();
            Toast.makeText(this, "Caricato: " + fileName, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Errore lettura file: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void startCableFlashingProcess() {
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (selectedCableDriver == null) {
            List<UsbSerialDriver> drivers = Esp32SerialFlasher.getAvailableDrivers(usbManager);
            if (drivers.isEmpty()) {
                Toast.makeText(this, "Nessun dispositivo USB rilevato. Collega l'ESP32 con cavo OTG.", Toast.LENGTH_LONG).show();
                return;
            }
            selectedCableDriver = drivers.get(0);
        }

        if (pendingCableFirmwareBytes == null || pendingCableFirmwareBytes.length == 0) {
            Toast.makeText(this, "Seleziona prima un file firmware (.bin) da flashare.", Toast.LENGTH_LONG).show();
            return;
        }

        if (etCableOffset != null) {
            String offStr = etCableOffset.getText().toString().trim();
            try {
                if (offStr.toLowerCase().startsWith("0x")) {
                    pendingCableFlashOffset = (int) Long.parseLong(offStr.substring(2), 16);
                } else {
                    pendingCableFlashOffset = Integer.parseInt(offStr);
                }
            } catch (Exception e) {
                Toast.makeText(this, "Offset non valido. Esempio valido: 0x10000 o 0x0000.", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        UsbDevice device = selectedCableDriver.getDevice();
        if (!usbManager.hasPermission(device)) {
            PendingIntent pi = PendingIntent.getBroadcast(
                    this,
                    0,
                    new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()),
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0
            );
            usbManager.requestPermission(device, pi);
            Toast.makeText(this, "Richiesta permessi USB inviata: autorizza il dispositivo a schermo.", Toast.LENGTH_SHORT).show();
            return;
        }

        executeCableFlashing();
    }

    private void executeCableFlashing() {
        if (cableFlashDialog != null && cableFlashDialog.isShowing()) {
            cableFlashDialog.dismiss();
        }

        showCableProgressDialog();

        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        activeFlasher = new Esp32SerialFlasher();

        new Thread(() -> {
            activeFlasher.flashFirmware(
                    usbManager,
                    selectedCableDriver,
                    pendingCableFirmwareBytes,
                    pendingCableFlashOffset,
                    new Esp32SerialFlasher.Callback() {
                        @Override
                        public void onStatus(String status) {
                            runOnUiThread(() -> {
                                if (tvCableProgressPhase != null) {
                                    tvCableProgressPhase.setText(status);
                                }
                                appendCableProgressLog(status);
                            });
                        }

                        @Override
                        public void onProgress(int percent, int writtenBytes, int totalBytes, long bytesPerSec) {
                            runOnUiThread(() -> {
                                if (pbCableProgress != null) {
                                    pbCableProgress.setProgress(percent);
                                }
                                if (tvCableProgressPercent != null) {
                                    tvCableProgressPercent.setText(String.format(Locale.US,
                                            "%d%% (%d / %d KB - %d KB/s)",
                                            percent, writtenBytes / 1024, totalBytes / 1024, bytesPerSec / 1024));
                                }
                            });
                        }

                        @Override
                        public void onSuccess() {
                            runOnUiThread(() -> {
                                dismissCableProgressDialog();
                                showResultAlertDialog(
                                        "Flash Completato con Successo!",
                                        "Il firmware Ã¨ stato scritto sull'ESP32 via cavo USB.\n" +
                                                "Il microcontrollore Ã¨ stato riavviato ed Ã¨ ora operativo con la nuova versione!"
                                );
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> {
                                dismissCableProgressDialog();
                                showResultAlertDialog(
                                        "Errore durante il Flash USB",
                                        "Si Ã¨ verificato un errore:\n" + errorMessage +
                                                "\n\nSuggerimento: se la sincronizzazione fallisce, tieni premuto il pulsante BOOT (o GPIO 0 / GPIO 9) sulla scheda mentre colleghi il cavo USB e riprova."
                                );
                            });
                        }
                    }
            );
        }).start();
    }

    private void showCableProgressDialog() {
        dismissCableProgressDialog();

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Flashing Firmware via Cavo USB");

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(18);
        container.setPadding(pad, pad, pad, pad);

        tvCableProgressPhase = new TextView(this);
        tvCableProgressPhase.setText("Avvio procedura...");
        tvCableProgressPhase.setTextSize(14f);
        tvCableProgressPhase.setTypeface(null, Typeface.BOLD);
        tvCableProgressPhase.setPadding(0, 0, 0, dpToPx(10));
        container.addView(tvCableProgressPhase);

        pbCableProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pbCableProgress.setMax(100);
        pbCableProgress.setProgress(0);
        container.addView(pbCableProgress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        tvCableProgressPercent = new TextView(this);
        tvCableProgressPercent.setText("0%");
        tvCableProgressPercent.setTextSize(12.5f);
        tvCableProgressPercent.setGravity(Gravity.END);
        tvCableProgressPercent.setTextColor(Color.parseColor("#616161"));
        tvCableProgressPercent.setPadding(0, dpToPx(4), 0, dpToPx(10));
        container.addView(tvCableProgressPercent);

        svCableProgressLog = new ScrollView(this);
        tvCableProgressLog = new TextView(this);
        tvCableProgressLog.setTextSize(11f);
        tvCableProgressLog.setTypeface(Typeface.MONOSPACE);
        tvCableProgressLog.setTextColor(Color.parseColor("#424242"));
        svCableProgressLog.addView(tvCableProgressLog);

        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(120)
        );
        container.addView(svCableProgressLog, logParams);

        builder.setView(container);
        builder.setCancelable(false);
        builder.setNegativeButton("Annulla", (d, w) -> {
            if (activeFlasher != null) {
                activeFlasher.cancel();
            }
            d.dismiss();
        });

        cableProgressDialog = builder.create();
        cableProgressDialog.show();
    }

    private void appendCableProgressLog(String line) {
        if (tvCableProgressLog != null) {
            tvCableProgressLog.append(line + "\n");
            if (svCableProgressLog != null) {
                svCableProgressLog.post(() -> svCableProgressLog.fullScroll(View.FOCUS_DOWN));
            }
        }
    }

    private void dismissCableProgressDialog() {
        if (cableProgressDialog != null && cableProgressDialog.isShowing()) {
            try { cableProgressDialog.dismiss(); } catch (Exception ignored) {}
        }
        cableProgressDialog = null;
    }
}
