package it.geniola.apritisedano;

import android.Manifest;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
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
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
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
import android.os.PowerManager;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
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
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.app.Dialog;
import android.content.res.ColorStateList;
import android.database.DataSetObserver;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.WindowManager;
import androidx.core.widget.ImageViewCompat;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final int PERMISSION_REQUEST_AUTO_OPEN_FG = 1002;
    private static final int PERMISSION_REQUEST_AUTO_OPEN_BG = 1003;
    private static final int PERMISSION_REQUEST_GPS_HOME_FIX = 1004;
    private Runnable pendingHomeFixCallback = null;
    private static final UUID TARGET_SERVICE_UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0");

    private RecyclerView rvDevices;
    private DeviceAdapter deviceAdapter;
    private List<Device> deviceList;

    private NfcAdapter nfcAdapter;
    private PendingIntent pendingIntent;
    private BluetoothLeScanner stateScanner;
    private BluetoothLeScanner discoveryScanner;
    private Dialog discoveryDialog;
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

    // Backup & Ripristino Globale (Seed BLE + Shelly + Configurazione Rientro)
    private byte[] pendingExportBackupBytes = null;

    private final ActivityResultLauncher<String> exportBackupLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"),
            uri -> {
                if (uri != null) {
                    writePendingBackupToUri(uri);
                } else {
                    pendingExportBackupBytes = null;
                }
            }
    );

    private final ActivityResultLauncher<String[]> importBackupLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri != null) {
                    handleSelectedBackupImportUri(uri);
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
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action) || UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                updateUsbUiState();
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

    private final ActivityResultLauncher<String[]> qrImagePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri == null) {
                    Toast.makeText(this, "Selezione immagine annullata", Toast.LENGTH_SHORT).show();
                    return;
                }
                handleSelectedQrImageUri(uri);
            }
    );

    private final ActivityResultLauncher<String[]> otaFilePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri == null || pendingOtaDevice == null) {
                    return;
                }
                handleSelectedOtaFirmwareUri(pendingOtaDevice, uri);
            }
    );

    private boolean processScannedQrPayload(String scanned) {
        if (scanned == null) return false;
        scanned = scanned.trim();
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
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Errore importazione QR", e);
        }
        return false;
    }

    private void handleSelectedQrImageUri(Uri uri) {
        try (java.io.InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) {
                Toast.makeText(this, "Impossibile aprire l'immagine selezionata.", Toast.LENGTH_SHORT).show();
                return;
            }
            Bitmap bitmap = BitmapFactory.decodeStream(is);
            if (bitmap == null) {
                Toast.makeText(this, "File immagine non valido.", Toast.LENGTH_SHORT).show();
                return;
            }
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            LuminanceSource source = new RGBLuminanceSource(width, height, pixels);
            BinaryBitmap binaryBitmap = new BinaryBitmap(new HybridBinarizer(source));
            MultiFormatReader reader = new MultiFormatReader();
            Result result = reader.decode(binaryBitmap);
            if (result != null && result.getText() != null) {
                boolean ok = processScannedQrPayload(result.getText());
                if (!ok) {
                    Toast.makeText(this, "QR Code di backup non valido.", Toast.LENGTH_LONG).show();
                }
            } else {
                Toast.makeText(this, "Nessun QR Code trovato nell'immagine.", Toast.LENGTH_LONG).show();
            }
        } catch (com.google.zxing.NotFoundException nfe) {
            Toast.makeText(this, "Nessun QR Code rilevato nell'immagine selezionata.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "Errore lettura QR da immagine", e);
            Toast.makeText(this, "Errore lettura QR da immagine: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private final ActivityResultLauncher<ScanOptions> barcodeLauncher = registerForActivityResult(new ScanContract(),
            result -> {
                if (result.getContents() != null) {
                    boolean ok = processScannedQrPayload(result.getContents());
                    if (!ok) {
                        Toast.makeText(this, "QR Code di backup non valido.", Toast.LENGTH_LONG).show();
                    }
                } else {
                    Toast.makeText(this, "Scansione annullata", Toast.LENGTH_SHORT).show();
                }
            });

    private final BroadcastReceiver serviceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            String targetMac = intent.getStringExtra(ApritiSedanoService.EXTRA_TARGET_MAC);

            if (ProximityConfigManager.ACTION_PROXIMITY_STATE_CHANGED.equals(action)) {
                if (deviceAdapter != null) {
                    deviceAdapter.notifyDataSetChanged();
                }
                return;
            }

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
                } else if ("ALREADY_TRIGGERED".equals(state)) {
                    Toast.makeText(context, "Operazione completata! (Comando già ricevuto ed eseguito dal ricevitore)", Toast.LENGTH_SHORT).show();
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
        fabAdd.setOnClickListener(v -> showAddDeviceTypeChoiceDialog());

        ImageView ivBackupRestore = findViewById(R.id.iv_backup_restore);
        if (ivBackupRestore != null) {
            ivBackupRestore.setOnClickListener(v -> showGlobalBackupMenuDialog());
        }

        ImageView ivDebug = findViewById(R.id.iv_debug_mode);
        if (ivDebug != null) {
            ivDebug.setOnClickListener(v -> startActivity(new Intent(this, DebugActivity.class)));
        }

        ImageView ivSerialConsole = findViewById(R.id.iv_serial_console);
        if (ivSerialConsole != null) {
            ivSerialConsole.setOnClickListener(v -> startActivity(new Intent(this, SerialConsoleActivity.class)));
        }

        ImageView ivFlashCable = findViewById(R.id.iv_flash_cable);
        if (ivFlashCable != null) {
            ivFlashCable.setOnClickListener(v -> {
                if (isSerialDeviceConnected()) {
                    new AlertDialog.Builder(this)
                            .setTitle("Strumenti USB / Cavo OTG")
                            .setItems(new CharSequence[]{
                                    "Terminale Seriale (CLI)",
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

        IntentFilter usbFilter = new IntentFilter();
        usbFilter.addAction(ACTION_USB_PERMISSION);
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, usbFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbPermissionReceiver, usbFilter);
        }

        updateUsbUiState();
        setupAppVersionFooter();
        CarNotificationHelper.ensureChannels(this);
        GeofenceHelper.syncGeofences(this);

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
        if (device.isShellyCloud()) {
            triggerShellyDeviceFromUi(device);
            return;
        }
        if (device.isActionPending()) {
            cancelPendingAction(device);
            return;
        }
        if (!hasRequiredPermissions()) {
            checkAndRequestPermissions();
            return;
        }
        long usedCounter = DeviceManager.incrementAndGetCounter(this, device.getMacAddress());
        if (usedCounter > 0) {
            device.setCounter(usedCounter);
        }
        deviceAdapter.markActionPending(device.getMacAddress(), true);

        Intent serviceIntent = new Intent(this, ApritiSedanoService.class);
        serviceIntent.setAction(ApritiSedanoService.ACTION_TRIGGER);
        serviceIntent.putExtra(ApritiSedanoService.EXTRA_TARGET_MAC, device.getMacAddress());
        serviceIntent.putExtra(ApritiSedanoService.EXTRA_SECRET_KEY, device.getSecretKey());
        serviceIntent.putExtra(ApritiSedanoService.EXTRA_CLIENT_ID, device.getClientId());
        serviceIntent.putExtra(ApritiSedanoService.EXTRA_USED_COUNTER, usedCounter);

        startServiceCompat(serviceIntent);
    }

    private void cancelPendingAction(Device device) {
        deviceAdapter.markActionPending(device.getMacAddress(), false);
        Intent stopIntent = new Intent(this, ApritiSedanoService.class);
        stopIntent.setAction(ApritiSedanoService.ACTION_STOP);
        stopService(stopIntent);
        Toast.makeText(this, "Comando annullato (codice scartato).", Toast.LENGTH_SHORT).show();
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
        updateUsbUiState();
        if (nfcAdapter != null) nfcAdapter.enableForegroundDispatch(this, pendingIntent, null, null);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ApritiSedanoService.ACTION_SERVICE_STOPPED);
        filter.addAction(ApritiSedanoService.ACTION_OPERATION_RESULT);
        filter.addAction(ProximityConfigManager.ACTION_PROXIMITY_STATE_CHANGED);
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
                Device target = null;
                for (Device d : devices) {
                    if (d.isBleMcu()) {
                        target = d;
                        break;
                    }
                }
                onDeviceActionClick(target != null ? target : devices.get(0));
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

        if (currentDevice.isShellyCloud()) {
            showShellyDeviceSettingsDialog(currentDevice);
            return;
        }

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
        if (currentDevice.isMaster()) {
            optionList.add(optOta);
        }
        // Il terminale seriale appare solo se è rilevato un dispositivo seriale USB collegato
        if (isSerialDeviceConnected()) {
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
        String antSuffix = "";
        if (currentDevice.getAntennaState() == 0) {
            antSuffix = " • 📶 Ant: Interna";
        } else if (currentDevice.getAntennaState() == 1) {
            antSuffix = " • 📡 Ant: Esterna";
        }
        builder.setTitle(String.format(Locale.US, "%s%s (ID: 0x%04X, Cnt: %d)%s",
                currentDevice.getName(), roleSuffix, currentDevice.getClientId(), currentDevice.getCounter(), antSuffix));

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

        final CharSequence[] otaChoices = new CharSequence[]{
                "📦 XIAO ESP32-C6 (Integrato v1.1)\nChip: ESP32-C6 • Default: Ant. Interna Ceramica, LED RGB D0, Relè D1, Sensore D2, Buzzer Passivo D3",
                "📦 ESP32-WROOM-32 (Integrato v1.1)\nChip: ESP32 • Default: Ant. PCB fissa, LED D2, Relè D4, Sensore D5, Buzzer Passivo D18",
                "📂 Scegli file personalizzato (.bin) dal telefono..."
        };

        new AlertDialog.Builder(this)
                .setTitle("Seleziona Firmware OTA")
                .setItems(otaChoices, (dialog, which) -> {
                    if (which == 0) {
                        loadBundledOtaFirmware(
                                device,
                                "ApritiSedano_XIAO_ESP32C6_InternalAntenna.bin",
                                "XIAO ESP32-C6 (v1.1 • Default: Antenna Interna Ceramica)"
                        );
                    } else if (which == 1) {
                        loadBundledOtaFirmware(
                                device,
                                "ApritiSedano_ESP32_WROOM.bin",
                                "ESP32-WROOM-32 (v1.1 • Default: Antenna PCB Fissa)"
                        );
                    } else {
                        otaFilePickerLauncher.launch(new String[]{"application/octet-stream", "*/*"});
                    }
                })
                .setNegativeButton("Annulla", null)
                .show();
    }

    private void loadBundledOtaFirmware(Device device, String assetFileName, String displayLabel) {
        byte[] fwBytes = readBundledFirmwareBytes(assetFileName);
        if (fwBytes == null) {
            showResultAlertDialog("Errore Risorsa Firmware", "Impossibile caricare il firmware integrato: " + assetFileName);
            return;
        }
        validateAndConfirmOtaFirmware(device, displayLabel + " [" + assetFileName + "]", fwBytes);
    }

    private byte[] readBundledFirmwareBytes(String fileName) {
        try (InputStream is = getAssets().open("firmware/" + fileName)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int r;
            while ((r = is.read(buf)) != -1) {
                bos.write(buf, 0, r);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            Log.w(TAG, "Firmware non presente in assets/firmware/" + fileName + ", provo in Download...", e);
        }

        File downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
        File fwFile = new File(downloadDir, fileName);
        if (!fwFile.exists() || fwFile.length() == 0) {
            fwFile = new File("/sdcard/Download", fileName);
        }
        if (fwFile.exists() && fwFile.length() > 0) {
            try (java.io.FileInputStream fis = new java.io.FileInputStream(fwFile)) {
                byte[] bytes = new byte[(int) fwFile.length()];
                int read = 0;
                while (read < bytes.length) {
                    int r = fis.read(bytes, read, bytes.length - read);
                    if (r < 0) break;
                    read += r;
                }
                return bytes;
            } catch (Exception ignored) {}
        }
        return null;
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

        validateAndConfirmOtaFirmware(device, fileName, fwBytes);
    }

    private void validateAndConfirmOtaFirmware(Device device, String fileName, byte[] fwBytes) {
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
                "Firmware: %s\n" +
                "Dimensione: %.1f KB (%d byte)\n" +
                "SHA-256: %s...\n\n" +
                "Nota: la partizione NVS (chiavi e ruolo Master) viene preservata.\n" +
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

        View view = getLayoutInflater().inflate(R.layout.dialog_scan_devices, null);
        ListView lvScanned = view.findViewById(R.id.lv_scanned_devices);
        View llEmptyState = view.findViewById(R.id.ll_scan_empty_state);
        View btnCancel = view.findViewById(R.id.btn_cancel_scan);

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

                MaterialCardView cardView = new MaterialCardView(MainActivity.this);
                cardView.setRadius(dpToPx(14));
                cardView.setCardElevation(0f);
                cardView.setStrokeWidth(dpToPx(1));

                LinearLayout card = new LinearLayout(MainActivity.this);
                card.setOrientation(LinearLayout.VERTICAL);
                int pad = dpToPx(14);
                card.setPadding(pad, pad, pad, pad);
                cardView.addView(card, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                ));

                LinearLayout topRow = new LinearLayout(MainActivity.this);
                topRow.setOrientation(LinearLayout.HORIZONTAL);
                topRow.setGravity(Gravity.CENTER_VERTICAL);
                card.addView(topRow);

                ImageView ivIcon = new ImageView(MainActivity.this);
                int iconSize = dpToPx(40);
                int iconPad = dpToPx(9);
                ivIcon.setPadding(iconPad, iconPad, iconPad, iconPad);
                ivIcon.setImageResource(R.drawable.ic_garage);
                LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
                iconLp.setMarginEnd(dpToPx(12));
                topRow.addView(ivIcon, iconLp);

                LinearLayout textCol = new LinearLayout(MainActivity.this);
                textCol.setOrientation(LinearLayout.VERTICAL);
                topRow.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView tvHeader = new TextView(MainActivity.this);
                tvHeader.setText(enrolled != null ? enrolled.getName() : item.mac);
                tvHeader.setTextSize(15.5f);
                tvHeader.setTypeface(null, Typeface.BOLD);
                tvHeader.setTextColor(Color.parseColor("#202124"));
                textCol.addView(tvHeader);

                TextView tvSubtitle = new TextView(MainActivity.this);
                tvSubtitle.setTextSize(12.5f);
                tvSubtitle.setPadding(0, dpToPx(2), 0, 0);
                textCol.addView(tvSubtitle);

                TextView tvRssi = new TextView(MainActivity.this);
                tvRssi.setText(String.format(Locale.US, "%d dBm", item.rssi));
                tvRssi.setTextSize(11.5f);
                tvRssi.setTypeface(null, Typeface.BOLD);
                tvRssi.setTextColor(Color.parseColor("#5F6368"));
                tvRssi.setBackgroundResource(R.drawable.bg_chip_neutral);
                tvRssi.setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));
                topRow.addView(tvRssi);

                MaterialButton actionBtn = new MaterialButton(MainActivity.this);
                actionBtn.setAllCaps(false);
                actionBtn.setCornerRadius(dpToPx(10));
                actionBtn.setTextSize(13.5f);
                actionBtn.setInsetTop(0);
                actionBtn.setInsetBottom(0);
                LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dpToPx(42)
                );
                btnLp.topMargin = dpToPx(12);
                card.addView(actionBtn, btnLp);

                if (item.inPairingMode) {
                    int remSec = Math.max(1, item.getRemainingSeconds(System.currentTimeMillis()));
                    cardView.setCardBackgroundColor(Color.parseColor("#F6FBF6"));
                    cardView.setStrokeColor(Color.parseColor("#C8E6C9"));
                    ivIcon.setBackgroundResource(R.drawable.bg_icon_green);
                    ImageViewCompat.setImageTintList(ivIcon, ColorStateList.valueOf(Color.parseColor("#2E7D32")));
                    tvSubtitle.setText(String.format(Locale.US, "Pronto all'associazione • %ds", remSec));
                    tvSubtitle.setTextColor(Color.parseColor("#2E7D32"));
                    tvSubtitle.setTypeface(null, Typeface.BOLD);

                    actionBtn.setText("Associa smartphone");
                    actionBtn.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#2E7D32")));
                    actionBtn.setTextColor(Color.WHITE);
                    actionBtn.setOnClickListener(v -> {
                        stopDiscoveryScan();
                        if (discoveryDialog != null && discoveryDialog.isShowing()) {
                            discoveryDialog.dismiss();
                        }
                        showQrImportMethodChoiceDialog(item.mac, "ApritiSedano");
                    });
                } else if (enrolled != null) {
                    cardView.setCardBackgroundColor(Color.parseColor("#F6FAFE"));
                    cardView.setStrokeColor(Color.parseColor("#BBDEFB"));
                    ivIcon.setBackgroundResource(R.drawable.bg_icon_blue);
                    ImageViewCompat.setImageTintList(ivIcon, ColorStateList.valueOf(Color.parseColor("#1565C0")));
                    tvSubtitle.setText(item.mac + " • Già associato");
                    tvSubtitle.setTextColor(Color.parseColor("#1565C0"));

                    actionBtn.setText("Attiva modalità accoppiamento");
                    actionBtn.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#E3F2FD")));
                    actionBtn.setTextColor(Color.parseColor("#1565C0"));
                    actionBtn.setOnClickListener(v -> enableRemotePairingMode(enrolled));
                } else {
                    cardView.setCardBackgroundColor(Color.parseColor("#FFFDF9"));
                    cardView.setStrokeColor(Color.parseColor("#FFE0B2"));
                    ivIcon.setBackgroundResource(R.drawable.bg_icon_amber);
                    ImageViewCompat.setImageTintList(ivIcon, ColorStateList.valueOf(Color.parseColor("#EF6C00")));
                    tvSubtitle.setText("Premi 3s il tasto fisico per associare");
                    tvSubtitle.setTextColor(Color.parseColor("#EF6C00"));

                    actionBtn.setText("Importa da QR Code");
                    actionBtn.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#F1F3F4")));
                    actionBtn.setTextColor(Color.parseColor("#3C4043"));
                    actionBtn.setOnClickListener(v -> {
                        stopDiscoveryScan();
                        if (discoveryDialog != null && discoveryDialog.isShowing()) {
                            discoveryDialog.dismiss();
                        }
                        showQrImportMethodChoiceDialog(item.mac, "ApritiSedano");
                    });
                }

                return cardView;
            }
        };

        Runnable syncEmptyView = () -> {
            boolean empty = discoveredDevices.isEmpty();
            if (llEmptyState != null) {
                llEmptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
            }
            lvScanned.setVisibility(empty ? View.GONE : View.VISIBLE);
        };
        discoveryAdapter.registerDataSetObserver(new DataSetObserver() {
            @Override
            public void onChanged() {
                syncEmptyView.run();
            }
        });
        syncEmptyView.run();

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
                            "Tieni premuto per 3s il tasto fisico sul ricevitore.",
                            Toast.LENGTH_SHORT
                    ).show();
                }
            }
        });

        BottomSheetDialog sheet = createModernBottomSheet(view);
        sheet.setOnCancelListener(d -> stopDiscoveryScan());
        sheet.setOnDismissListener(d -> stopDiscoveryScan());
        btnCancel.setOnClickListener(v -> {
            stopDiscoveryScan();
            sheet.dismiss();
        });

        discoveryDialog = sheet;
        sheet.show();
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

        if (payload.length >= 11) {
            int antState = payload[10] & 0xFF;
            if (dev.getAntennaState() != antState) {
                dev.setAntennaState(antState);
                DeviceManager.updateAntennaState(this, mac, antState);
                deviceAdapter.updateDeviceAntennaStatus(mac, antState);
            }
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

    private void showQrImportMethodChoiceDialog(String targetMac, String deviceName) {
        pendingQrTargetMac = targetMac;
        pendingQrDeviceName = (deviceName != null && !deviceName.isEmpty()) ? deviceName : "ApritiSedano";

        View view = getLayoutInflater().inflate(R.layout.sheet_qr_import_choice, null);
        BottomSheetDialog sheet = createModernBottomSheet(view);

        view.findViewById(R.id.card_qr_scan_camera).setOnClickListener(v -> {
            sheet.dismiss();
            ScanOptions options = new ScanOptions();
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
            options.setPrompt("Inquadra il QR Code di backup");
            barcodeLauncher.launch(options);
        });

        view.findViewById(R.id.card_qr_pick_image).setOnClickListener(v -> {
            sheet.dismiss();
            qrImagePickerLauncher.launch(new String[]{"image/*"});
        });

        View btnCancel = view.findViewById(R.id.btn_cancel_qr_sheet);
        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> sheet.dismiss());
        }

        sheet.show();
    }

    private void showFinalDeviceConfigDialog(String macAddress) {
        View view = getLayoutInflater().inflate(R.layout.dialog_ble_pair_config, null);
        BottomSheetDialog sheet = createModernBottomSheet(view);

        TextView tvMac = view.findViewById(R.id.tv_ble_pair_mac);
        TextInputLayout tilName = view.findViewById(R.id.til_ble_device_name);
        TextInputEditText etName = view.findViewById(R.id.et_ble_device_name);
        MaterialButton btnConfirm = view.findViewById(R.id.btn_confirm_ble_pair);
        MaterialButton btnImportQr = view.findViewById(R.id.btn_ble_import_qr);

        tvMac.setText(macAddress + " • Pronto all'associazione");

        Device existing = DeviceManager.getDeviceByMac(this, macAddress);
        if (existing != null && existing.getName() != null) {
            etName.setText(existing.getName());
        }

        etName.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                tilName.setError(null);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        btnImportQr.setOnClickListener(v -> {
            String curName = etName.getText() != null ? etName.getText().toString().trim() : "";
            sheet.dismiss();
            showQrImportMethodChoiceDialog(macAddress, curName);
        });

        btnConfirm.setOnClickListener(v -> {
            String name = etName.getText() != null ? etName.getText().toString().trim() : "";
            if (name.isEmpty()) {
                tilName.setError("Inserisci un nome per il dispositivo");
                return;
            }

            int newClientId = TOTPGenerator.generateRandomClientId();
            String newSeedHex = TOTPGenerator.generateRandomSeedHex();

            sheet.dismiss();

            showOperationProgressDialog(
                    "Accoppiamento BLE",
                    "Registrazione sul ricevitore " + macAddress + " in corso...",
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

        sheet.show();
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
        if (!hasAnyBleMcuDevice()) {
            stopStateScanning();
            return;
        }
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
        } else if (requestCode == PERMISSION_REQUEST_AUTO_OPEN_FG) {
            ensureAutoOpenPermissionsGuided();
        } else if (requestCode == PERMISSION_REQUEST_AUTO_OPEN_BG) {
            GeofenceHelper.syncGeofences(this);
            if (GeofenceHelper.hasBackgroundLocationPermissions(this)) {
                Toast.makeText(this, "Posizione 'Consenti sempre' attiva! Geofence rientro registrati.", Toast.LENGTH_SHORT).show();
                promptBatteryOptimizationHintIfNeeded();
            } else {
                Toast.makeText(this, "Attenzione: seleziona 'Consenti sempre' nelle impostazioni Posizione per l'auto-apertura a schermo spento.", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == PERMISSION_REQUEST_GPS_HOME_FIX) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted && pendingHomeFixCallback != null) {
                Runnable cb = pendingHomeFixCallback;
                pendingHomeFixCallback = null;
                cb.run();
            } else {
                pendingHomeFixCallback = null;
            }
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
            itemSerial.setVisible(isSerialDeviceConnected());
        }
        return super.onPrepareOptionsMenu(menu);
    }

    private boolean isSerialDeviceConnected() {
        try {
            UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
            if (usbManager == null) return false;
            List<UsbSerialDriver> drivers = Esp32SerialFlasher.getAvailableDrivers(usbManager);
            return drivers != null && !drivers.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private void updateUsbUiState() {
        boolean connected = isSerialDeviceConnected();
        ImageView ivSerial = findViewById(R.id.iv_serial_console);
        if (ivSerial != null) {
            ivSerial.setVisibility(connected ? View.VISIBLE : View.GONE);
        }
        invalidateOptionsMenu();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_backup_export) {
            showExportGlobalBackupDialog();
            return true;
        } else if (id == R.id.action_backup_import) {
            startImportGlobalBackupFlow();
            return true;
        } else if (id == R.id.action_serial_menu) {
            startActivity(new Intent(this, SerialConsoleActivity.class));
            return true;
        } else if (id == R.id.action_flash_cable) {
            showCableFlashDialog();
            return true;
        } else if (id == R.id.action_debug_mode) {
            startActivity(new Intent(this, DebugActivity.class));
            return true;
        } else if (id == R.id.action_about) {
            showAboutDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void setupAppVersionFooter() {
        TextView tvAppVersion = findViewById(R.id.tv_app_version);
        if (tvAppVersion != null) {
            String versionName = getAppVersionString();
            tvAppVersion.setText("Versione " + versionName);
            tvAppVersion.setOnClickListener(v -> showAboutDialog());
        }
    }

    private String getAppVersionString() {
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pInfo.versionName != null ? pInfo.versionName : "1.0";
        } catch (Exception e) {
            return "1.0";
        }
    }

    private void showAboutDialog() {
        String versionName = "Sconosciuta";
        long versionCode = 0;
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (pInfo.versionName != null) {
                versionName = pInfo.versionName;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                versionCode = pInfo.getLongVersionCode();
            } else {
                versionCode = pInfo.versionCode;
            }
        } catch (Exception ignored) {
        }

        new AlertDialog.Builder(this)
                .setTitle("ApritiSedano")
                .setMessage("Versione installata:\n" + versionName + "\n\n"
                        + "Codice build: " + versionCode + "\n"
                        + "Package: " + getPackageName() + "\n\n"
                        + "Puoi verificare la presenza di nuove build nella scheda Test Interno del Play Store.")
                .setPositiveButton("Chiudi", null)
                .show();
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
        tvQuickHeader.setText("Oppure seleziona un firmware integrato nell'app (v1.1):");
        tvQuickHeader.setTextSize(12.5f);
        tvQuickHeader.setTypeface(null, Typeface.BOLD);
        tvQuickHeader.setTextColor(Color.parseColor("#424242"));
        tvQuickHeader.setPadding(0, dpToPx(10), 0, dpToPx(2));
        container.addView(tvQuickHeader);

        TextView tvFwDefaultsInfo = new TextView(this);
        tvFwDefaultsInfo.setText(
                "• XIAO ESP32-C6 (Chip: ESP32-C6) — Default: Antenna Interna Ceramica (commutabile a U.FL esterna via CLI/tasto 10s), LED RGB D0, Relè D1, Sensore D2, Buzzer Passivo D3\n" +
                "• ESP32-WROOM-32 (Chip: ESP32) — Default: Antenna PCB fissa, LED D2, Relè D4, Sensore D5, Buzzer Passivo D18"
        );
        tvFwDefaultsInfo.setTextSize(11f);
        tvFwDefaultsInfo.setTextColor(Color.parseColor("#616161"));
        tvFwDefaultsInfo.setPadding(0, 0, 0, dpToPx(6));
        container.addView(tvFwDefaultsInfo);

        LinearLayout quickRow1 = new LinearLayout(this);
        quickRow1.setOrientation(LinearLayout.HORIZONTAL);

        Button btnQuickWroom = new Button(this);
        btnQuickWroom.setText("WROOM v1.1 (App 0x10000)");
        btnQuickWroom.setTextSize(10.5f);
        btnQuickWroom.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnQuickWroom.setOnClickListener(v -> loadPredefinedFirmware(
                "ApritiSedano_ESP32_WROOM.bin",
                0x10000,
                "ESP32-WROOM v1.1 (App 0x10000 • Default: Ant. PCB fissa)"
        ));
        quickRow1.addView(btnQuickWroom);

        Button btnQuickXiao = new Button(this);
        btnQuickXiao.setText("XIAO C6 v1.1 (App 0x10000)");
        btnQuickXiao.setTextSize(10.5f);
        btnQuickXiao.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnQuickXiao.setOnClickListener(v -> loadPredefinedFirmware(
                "ApritiSedano_XIAO_ESP32C6_InternalAntenna.bin",
                0x10000,
                "XIAO ESP32-C6 v1.1 (App 0x10000 • Default: Ant. Interna)"
        ));
        quickRow1.addView(btnQuickXiao);

        container.addView(quickRow1);

        LinearLayout quickRow2 = new LinearLayout(this);
        quickRow2.setOrientation(LinearLayout.HORIZONTAL);

        Button btnQuickWroomMerged = new Button(this);
        btnQuickWroomMerged.setText("WROOM v1.1 Full (0x0000)");
        btnQuickWroomMerged.setTextSize(10.5f);
        btnQuickWroomMerged.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnQuickWroomMerged.setOnClickListener(v -> loadPredefinedFirmware(
                "ApritiSedano_ESP32_WROOM_full_merged.bin",
                0x0000,
                "ESP32-WROOM v1.1 Full Merged (0x0000 • Default: Ant. PCB fissa)"
        ));
        quickRow2.addView(btnQuickWroomMerged);

        Button btnQuickXiaoMerged = new Button(this);
        btnQuickXiaoMerged.setText("XIAO C6 v1.1 Full (0x0000)");
        btnQuickXiaoMerged.setTextSize(10.5f);
        btnQuickXiaoMerged.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnQuickXiaoMerged.setOnClickListener(v -> loadPredefinedFirmware(
                "ApritiSedano_XIAO_ESP32C6_InternalAntenna_full_merged.bin",
                0x0000,
                "XIAO ESP32-C6 v1.1 Full Merged (0x0000 • Default: Ant. Interna)"
        ));
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

    private void loadPredefinedFirmware(String fileName, int defaultOffset, String displayLabel) {
        byte[] bytes = readBundledFirmwareBytes(fileName);
        if (bytes == null || bytes.length == 0) {
            Toast.makeText(this, "Firmware non trovato: " + fileName, Toast.LENGTH_SHORT).show();
            return;
        }
        pendingCableFirmwareBytes = bytes;
        pendingCableFirmwareName = (displayLabel != null && !displayLabel.isEmpty()) ? displayLabel : fileName;
        pendingCableFlashOffset = defaultOffset;

        if (etCableOffset != null) {
            etCableOffset.setText(String.format(Locale.US, "0x%05X", pendingCableFlashOffset));
        }
        updateCableFirmwareStatusText();
        Toast.makeText(this, "Caricato: " + pendingCableFirmwareName, Toast.LENGTH_SHORT).show();
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

    // ==========================================
    // MODERN BOTTOM SHEET HELPER
    // ==========================================

    private BottomSheetDialog createModernBottomSheet(View contentView) {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        sheet.setContentView(contentView);
        if (sheet.getWindow() != null) {
            sheet.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        sheet.setOnShowListener(d -> {
            if (contentView.getParent() instanceof View) {
                ((View) contentView.getParent()).setBackgroundColor(Color.TRANSPARENT);
            }
            BottomSheetBehavior<?> behavior = sheet.getBehavior();
            behavior.setSkipCollapsed(true);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        });
        return sheet;
    }

    // ==========================================
    // SHELLY CLOUD & AUTO-APERTURA ANDROID AUTO
    // ==========================================

    private boolean hasAnyBleMcuDevice() {
        List<Device> list = (deviceList != null) ? deviceList : DeviceManager.getDevices(this);
        for (Device d : list) {
            if (d.isBleMcu()) {
                return true;
            }
        }
        return false;
    }

    private void showAddDeviceTypeChoiceDialog() {
        View view = getLayoutInflater().inflate(R.layout.sheet_add_device, null);
        BottomSheetDialog sheet = createModernBottomSheet(view);

        view.findViewById(R.id.card_add_ble).setOnClickListener(v -> {
            sheet.dismiss();
            showAddDeviceScanDialog();
        });

        view.findViewById(R.id.card_add_shelly).setOnClickListener(v -> {
            sheet.dismiss();
            showShellyDeviceConfigDialog(null);
        });

        view.findViewById(R.id.card_add_import_backup).setOnClickListener(v -> {
            sheet.dismiss();
            startImportGlobalBackupFlow();
        });

        View btnCancel = view.findViewById(R.id.btn_cancel_add_sheet);
        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> sheet.dismiss());
        }

        sheet.show();
    }

    private void triggerShellyDeviceFromUi(Device device) {
        if (device.getShellyUiState() == 1) {
            return;
        }
        final String devKey = device.getMacAddress();
        deviceAdapter.updateShellyFeedback(devKey, 1, "");

        ShellyCloudClient.triggerDeviceAsync(device, 1, res -> {
            if (isFinishing() || isDestroyed()) return;
            if (res.success) {
                deviceAdapter.updateShellyFeedback(devKey, 2, "Apertura inviata!");
                Toast.makeText(this, "Apertura inviata a " + device.getName() + "!", Toast.LENGTH_SHORT).show();
                pairingTimerHandler.postDelayed(() -> {
                    if (!isFinishing() && !isDestroyed() && deviceAdapter != null) {
                        deviceAdapter.updateShellyFeedback(devKey, 0, "");
                    }
                }, 4500L);
            } else {
                deviceAdapter.updateShellyFeedback(devKey, 3, res.message);
                Toast.makeText(this, "Errore Shelly: " + res.message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showShellyDeviceSettingsDialog(Device currentDevice) {
        View view = getLayoutInflater().inflate(R.layout.sheet_shelly_actions, null);
        BottomSheetDialog sheet = createModernBottomSheet(view);

        TextView tvName = view.findViewById(R.id.tv_sheet_shelly_name);
        TextView tvSub = view.findViewById(R.id.tv_sheet_shelly_sub);
        TextView tvBadge = view.findViewById(R.id.tv_sheet_shelly_badge);
        MaterialButton btnEdit = view.findViewById(R.id.btn_action_shelly_edit);
        MaterialButton btnCheck = view.findViewById(R.id.btn_action_shelly_check);
        MaterialButton btnTestNotif = view.findViewById(R.id.btn_action_shelly_test_notif);
        MaterialButton btnToggleArm = view.findViewById(R.id.btn_action_shelly_toggle_arm);
        MaterialButton btnNfc = view.findViewById(R.id.btn_action_shelly_nfc);
        MaterialButton btnDelete = view.findViewById(R.id.btn_action_shelly_delete);

        tvName.setText(currentDevice.getName());
        tvSub.setText(String.format(
                Locale.US,
                "ID %s • Canale %d",
                currentDevice.getShellyDeviceId(),
                currentDevice.getShellyChannel()
        ));

        final boolean currentlyArmed = ProximityConfigManager.isArmed(this);
        if (currentDevice.isAutoOpenEnabled()) {
            tvBadge.setVisibility(View.VISIBLE);
            if (currentlyArmed) {
                tvBadge.setText("ARMATO");
                tvBadge.setTextColor(Color.parseColor("#2E7D32"));
                tvBadge.setBackgroundResource(R.drawable.badge_shelly_cloud);
            } else {
                tvBadge.setText("A CASA");
                tvBadge.setTextColor(Color.parseColor("#5F6368"));
                tvBadge.setBackgroundResource(R.drawable.bg_chip_neutral);
            }

            btnToggleArm.setVisibility(View.VISIBLE);
            btnToggleArm.setText(currentlyArmed
                    ? "Segna come a casa (disarma)"
                    : "Arma rientro manualmente");
            btnToggleArm.setOnClickListener(v -> {
                sheet.dismiss();
                boolean nextArmed = !ProximityConfigManager.isArmed(this);
                ProximityConfigManager.setArmed(this, nextArmed);
                notifyProximityServiceConfigChanged();
                deviceAdapter.notifyDataSetChanged();
                Toast.makeText(
                        this,
                        nextArmed
                                ? "Rientro armato (< " + currentDevice.getAutoOpenRadiusMeters() + "m)"
                                : "Rientro disarmato (a casa)",
                        Toast.LENGTH_SHORT
                ).show();
            });
        } else {
            tvBadge.setVisibility(View.GONE);
            btnToggleArm.setVisibility(View.GONE);
        }

        btnEdit.setOnClickListener(v -> {
            sheet.dismiss();
            showShellyDeviceConfigDialog(currentDevice);
        });

        btnCheck.setOnClickListener(v -> {
            sheet.dismiss();
            showOperationProgressDialog(
                    "Verifica Shelly Cloud",
                    "Controllo stato online di " + currentDevice.getName() + "...",
                    "SHELLY_CHECK"
            );
            ShellyCloudClient.checkStatusAsync(
                    currentDevice.getShellyServerUri(),
                    currentDevice.getShellyAuthKey(),
                    currentDevice.getShellyDeviceId(),
                    res -> {
                        dismissOperationProgressDialog();
                        pendingModalOperation = null;
                        showResultAlertDialog(
                                res.success ? "Stato Shelly Cloud" : "Errore Shelly Cloud",
                                res.message
                        );
                    }
            );
        });

        btnTestNotif.setOnClickListener(v -> {
            sheet.dismiss();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        PERMISSION_REQUEST_CODE
                );
                return;
            }
            CarNotificationHelper.showAutoOpenAlert(
                    this,
                    "Bentornato a casa!",
                    currentDevice.resolveAutoOpenMessage()
            );
            Toast.makeText(
                    this,
                    "Notifica di test inviata!",
                    Toast.LENGTH_SHORT
            ).show();
        });

        btnNfc.setOnClickListener(v -> {
            sheet.dismiss();
            startNfcConfig(currentDevice.getMacAddress());
        });

        btnDelete.setOnClickListener(v -> {
            sheet.dismiss();
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Elimina " + currentDevice.getName() + "?")
                    .setMessage("Il dispositivo Shelly verrà rimosso da questo smartphone.")
                    .setPositiveButton("Elimina", (d, w) -> {
                        DeviceManager.removeDevice(this, currentDevice.getMacAddress());
                        deviceList = DeviceManager.getDevices(this);
                        deviceAdapter.updateDevices(deviceList);
                        GeofenceHelper.syncGeofences(this);
                        notifyProximityServiceConfigChanged();
                        Toast.makeText(this, "Dispositivo eliminato", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Annulla", null)
                    .show();
        });

        sheet.show();
    }

    private void notifyProximityServiceConfigChanged() {
        if (ProximityConfigManager.isServiceRunning()) {
            Intent refreshIntent = new Intent(this, ProximityAutoOpenService.class);
            refreshIntent.setAction(ProximityAutoOpenService.ACTION_REFRESH_CONFIG);
            try {
                startService(refreshIntent);
            } catch (Exception ignored) {
            }
        } else if (ProximityConfigManager.hasAnyAutoOpenDevice(this) && ProximityConfigManager.hasHomeLocation(this)) {
            ProximityAutoOpenService.startMonitoring(this, ProximityAutoOpenService.ACTION_CHECK_CAR_STATE);
        }
    }

    private void showShellyDeviceConfigDialog(@Nullable Device existing) {
        final boolean isEdit = (existing != null);

        View view = getLayoutInflater().inflate(R.layout.dialog_shelly_config, null);
        BottomSheetDialog sheet = createModernBottomSheet(view);

        TextView tvTitle = view.findViewById(R.id.tv_shelly_dialog_title);
        TextInputEditText etName = view.findViewById(R.id.et_shelly_name);
        TextInputEditText etDeviceId = view.findViewById(R.id.et_shelly_device_id);
        TextInputEditText etChannel = view.findViewById(R.id.et_shelly_channel);
        TextInputEditText etServerUri = view.findViewById(R.id.et_shelly_server);
        TextInputEditText etAuthKey = view.findViewById(R.id.et_shelly_auth_key);
        MaterialButton btnTestCloud = view.findViewById(R.id.btn_shelly_test_cloud);
        TextView tvTestCloudStatus = view.findViewById(R.id.tv_shelly_test_status);

        SwitchMaterial swAutoOpen = view.findViewById(R.id.sw_shelly_auto_open);
        View llAutoBody = view.findViewById(R.id.ll_shelly_auto_body);
        TextInputEditText etHomeLat = view.findViewById(R.id.et_home_lat);
        TextInputEditText etHomeLon = view.findViewById(R.id.et_home_lon);
        MaterialButton btnUseCurrentGps = view.findViewById(R.id.btn_use_current_gps);
        TextView tvGpsFixStatus = view.findViewById(R.id.tv_gps_fix_status);
        View cardPickCarBt = view.findViewById(R.id.card_pick_car_bt);
        TextView tvSelectedCarBt = view.findViewById(R.id.tv_selected_car_bt);
        TextInputEditText etArmDist = view.findViewById(R.id.et_arm_distance);
        TextInputEditText etTriggerRadius = view.findViewById(R.id.et_trigger_radius);
        TextInputEditText etNotifText = view.findViewById(R.id.et_auto_notif_text);
        SwitchMaterial swDryRun = view.findViewById(R.id.sw_shelly_dry_run);
        MaterialButton btnVerifyPerms = view.findViewById(R.id.btn_verify_auto_perms);
        MaterialButton btnSave = view.findViewById(R.id.btn_save_shelly);

        tvTitle.setText(isEdit ? "Configura " + existing.getName() : "Nuovo dispositivo Shelly");
        etName.setText(isEdit ? existing.getName() : "Cancello Condominiale");
        if (isEdit) {
            etDeviceId.setText(existing.getShellyDeviceId());
        }
        etChannel.setText(String.valueOf(isEdit ? existing.getShellyChannel() : 0));

        String prefillServer = isEdit ? existing.getShellyServerUri() : ProximityConfigManager.getDefaultShellyServer(this);
        if (!prefillServer.isEmpty()) {
            etServerUri.setText(prefillServer);
        }

        String prefillKey = isEdit ? existing.getShellyAuthKey() : ProximityConfigManager.getDefaultShellyAuthKey(this);
        if (!prefillKey.isEmpty()) {
            etAuthKey.setText(prefillKey);
        }

        btnTestCloud.setOnClickListener(v -> {
            String srv = etServerUri.getText() != null ? etServerUri.getText().toString().trim() : "";
            String key = etAuthKey.getText() != null ? etAuthKey.getText().toString().trim() : "";
            String id = etDeviceId.getText() != null ? etDeviceId.getText().toString().trim() : "";
            if (srv.isEmpty() || key.isEmpty() || id.isEmpty()) {
                tvTestCloudStatus.setVisibility(View.VISIBLE);
                tvTestCloudStatus.setTextColor(Color.parseColor("#C62828"));
                tvTestCloudStatus.setText("Compila Device ID, Server e Auth Key.");
                return;
            }
            btnTestCloud.setEnabled(false);
            tvTestCloudStatus.setVisibility(View.VISIBLE);
            tvTestCloudStatus.setTextColor(Color.parseColor("#1565C0"));
            tvTestCloudStatus.setText("Verifica in corso...");

            ShellyCloudClient.checkStatusAsync(srv, key, id, res -> {
                btnTestCloud.setEnabled(true);
                if (res.success && res.online) {
                    tvTestCloudStatus.setTextColor(Color.parseColor("#2E7D32"));
                    tvTestCloudStatus.setText("🟢 " + res.message);
                } else if (res.success) {
                    tvTestCloudStatus.setTextColor(Color.parseColor("#EF6C00"));
                    tvTestCloudStatus.setText("🟠 " + res.message);
                } else {
                    tvTestCloudStatus.setTextColor(Color.parseColor("#C62828"));
                    tvTestCloudStatus.setText("🔴 " + res.message);
                }
            });
        });

        boolean initialAuto = isEdit && existing.isAutoOpenEnabled();
        swAutoOpen.setChecked(initialAuto);
        llAutoBody.setVisibility(initialAuto ? View.VISIBLE : View.GONE);
        swAutoOpen.setOnCheckedChangeListener((buttonView, isChecked) ->
                llAutoBody.setVisibility(isChecked ? View.VISIBLE : View.GONE));

        if (ProximityConfigManager.hasHomeLocation(this)) {
            etHomeLat.setText(String.format(Locale.US, "%.6f", ProximityConfigManager.getHomeLatitude(this)));
            etHomeLon.setText(String.format(Locale.US, "%.6f", ProximityConfigManager.getHomeLongitude(this)));
        }

        btnUseCurrentGps.setOnClickListener(v -> populateCurrentGpsIntoFields(etHomeLat, etHomeLon, tvGpsFixStatus));

        final String[] selectedCarBtMac = new String[]{ProximityConfigManager.getCarBluetoothMac(this)};
        final String[] selectedCarBtName = new String[]{ProximityConfigManager.getCarBluetoothName(this)};
        updateCarBtLabelText(tvSelectedCarBt, selectedCarBtMac[0], selectedCarBtName[0]);
        cardPickCarBt.setOnClickListener(v ->
                showCarBluetoothPickerDialog(selectedCarBtMac, selectedCarBtName, tvSelectedCarBt));

        etArmDist.setText(String.valueOf(ProximityConfigManager.getArmDistanceMeters(this)));
        etTriggerRadius.setText(String.valueOf(isEdit ? existing.getAutoOpenRadiusMeters() : 300));

        String existingMsg = isEdit ? existing.getAutoOpenNotificationText() : Device.DEFAULT_AUTO_OPEN_MESSAGE;
        etNotifText.setText(existingMsg.isEmpty() ? Device.DEFAULT_AUTO_OPEN_MESSAGE : existingMsg);

        swDryRun.setChecked(isEdit && existing.isAutoOpenDryRun());
        btnVerifyPerms.setOnClickListener(v -> ensureAutoOpenPermissionsGuided());

        btnSave.setOnClickListener(v -> {
            String name = etName.getText() != null ? etName.getText().toString().trim() : "";
            String rawDevId = etDeviceId.getText() != null ? etDeviceId.getText().toString().trim() : "";
            String rawServer = etServerUri.getText() != null ? etServerUri.getText().toString().trim() : "";
            String authKey = etAuthKey.getText() != null ? etAuthKey.getText().toString().trim() : "";
            String chStr = etChannel.getText() != null ? etChannel.getText().toString().trim() : "";

            if (name.isEmpty()) {
                Toast.makeText(this, "Inserisci il nome del dispositivo.", Toast.LENGTH_SHORT).show();
                return;
            }
            String normDevId = ShellyCloudClient.normalizeDeviceId(rawDevId);
            if (normDevId.isEmpty()) {
                Toast.makeText(this, "Inserisci il Device ID Shelly.", Toast.LENGTH_SHORT).show();
                return;
            }
            String normServer = ShellyCloudClient.normalizeServerUri(rawServer);
            if (normServer.isEmpty()) {
                Toast.makeText(this, "Inserisci il Server Shelly Cloud.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (authKey.isEmpty()) {
                Toast.makeText(this, "Inserisci l'Auth Key Shelly Cloud.", Toast.LENGTH_SHORT).show();
                return;
            }

            int channel = 0;
            if (!chStr.isEmpty()) {
                try {
                    channel = Math.max(0, Integer.parseInt(chStr));
                } catch (NumberFormatException e) {
                    Toast.makeText(this, "Canale non valido.", Toast.LENGTH_SHORT).show();
                    return;
                }
            }

            boolean autoEnabled = swAutoOpen.isChecked();
            int triggerRadius = 300;
            int armDistance = ProximityConfigManager.DEFAULT_ARM_DISTANCE_METERS;
            double homeLat;
            double homeLon;

            if (autoEnabled) {
                String latStr = (etHomeLat.getText() != null ? etHomeLat.getText().toString().trim() : "").replace(',', '.');
                String lonStr = (etHomeLon.getText() != null ? etHomeLon.getText().toString().trim() : "").replace(',', '.');
                try {
                    homeLat = Double.parseDouble(latStr);
                    homeLon = Double.parseDouble(lonStr);
                } catch (NumberFormatException e) {
                    Toast.makeText(this, "Imposta le coordinate GPS di Casa.", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (Math.abs(homeLat) > 90.0 || Math.abs(homeLon) > 180.0 || (Math.abs(homeLat) < 0.0001 && Math.abs(homeLon) < 0.0001)) {
                    Toast.makeText(this, "Coordinate Casa non valide.", Toast.LENGTH_SHORT).show();
                    return;
                }

                try {
                    armDistance = Integer.parseInt(etArmDist.getText() != null ? etArmDist.getText().toString().trim() : "");
                } catch (NumberFormatException e) {
                    Toast.makeText(this, "Distanza di armamento non valida.", Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    triggerRadius = Integer.parseInt(etTriggerRadius.getText() != null ? etTriggerRadius.getText().toString().trim() : "");
                } catch (NumberFormatException e) {
                    Toast.makeText(this, "Raggio di apertura non valido.", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (triggerRadius < 30 || triggerRadius > 2000) {
                    Toast.makeText(this, "Il raggio deve essere tra 30 e 2000 m.", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (armDistance < triggerRadius + 200) {
                    Toast.makeText(this, "L'uscita armamento deve superare il raggio di almeno 200 m.", Toast.LENGTH_LONG).show();
                    return;
                }

                ProximityConfigManager.setHomeLocation(this, homeLat, homeLon);
                ProximityConfigManager.setCarBluetoothFilter(this, selectedCarBtMac[0], selectedCarBtName[0]);
                ProximityConfigManager.setArmDistanceMeters(this, armDistance);
                int realtimeDist = Math.min(armDistance - 100, Math.max(1000, triggerRadius * 2));
                ProximityConfigManager.setRealtimeDistanceMeters(this, realtimeDist);
            }

            ProximityConfigManager.saveDefaultShellyCredentials(this, normServer, authKey);

            String previousMac = isEdit ? existing.getMacAddress() : null;
            Device savedDevice;
            if (isEdit) {
                savedDevice = existing;
                savedDevice.setName(name);
                savedDevice.updateShellyIdentity(normDevId, channel);
                savedDevice.setShellyServerUri(normServer);
                savedDevice.setShellyAuthKey(authKey);
            } else {
                savedDevice = Device.createShellyDevice(name, normDevId, normServer, authKey, channel);
            }

            savedDevice.setAutoOpenEnabled(autoEnabled);
            savedDevice.setAutoOpenRadiusMeters(triggerRadius);
            savedDevice.setAutoOpenDryRun(swDryRun.isChecked());
            String notifMsg = etNotifText.getText() != null ? etNotifText.getText().toString().trim() : "";
            savedDevice.setAutoOpenNotificationText(notifMsg.isEmpty() ? Device.DEFAULT_AUTO_OPEN_MESSAGE : notifMsg);

            DeviceManager.upsertDevice(this, previousMac, savedDevice);
            deviceList = DeviceManager.getDevices(this);
            deviceAdapter.updateDevices(deviceList);
            startStateScanning();

            GeofenceHelper.syncGeofences(this);
            notifyProximityServiceConfigChanged();

            sheet.dismiss();
            Toast.makeText(this, "Dispositivo salvato!", Toast.LENGTH_SHORT).show();

            if (autoEnabled) {
                ensureAutoOpenPermissionsGuided();
            }
        });

        sheet.show();
    }

    private void updateCarBtLabelText(TextView tv, String mac, String name) {
        if (mac == null || mac.isEmpty()) {
            tv.setText("Qualsiasi auto Android Auto");
        } else {
            String displayName = (name != null && !name.isEmpty()) ? name : "Veicolo";
            tv.setText(displayName + " • " + mac);
        }
    }

    private void populateCurrentGpsIntoFields(EditText etLat, EditText etLon, TextView tvStatus) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            pendingHomeFixCallback = () -> populateCurrentGpsIntoFields(etLat, etLon, tvStatus);
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    PERMISSION_REQUEST_GPS_HOME_FIX
            );
            return;
        }

        tvStatus.setVisibility(View.VISIBLE);
        tvStatus.setTextColor(Color.parseColor("#1565C0"));
        tvStatus.setText("Acquisizione GPS in corso...");

        FusedLocationProviderClient fused = LocationServices.getFusedLocationProviderClient(this);
        try {
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener(loc -> {
                        if (loc != null) {
                            etLat.setText(String.format(Locale.US, "%.6f", loc.getLatitude()));
                            etLon.setText(String.format(Locale.US, "%.6f", loc.getLongitude()));
                            tvStatus.setTextColor(Color.parseColor("#2E7D32"));
                            tvStatus.setText(String.format(Locale.US, "🟢 Posizione acquisita (±%.0f m)", loc.getAccuracy()));
                        } else {
                            fused.getLastLocation().addOnSuccessListener(last -> {
                                if (last != null) {
                                    etLat.setText(String.format(Locale.US, "%.6f", last.getLatitude()));
                                    etLon.setText(String.format(Locale.US, "%.6f", last.getLongitude()));
                                    tvStatus.setTextColor(Color.parseColor("#2E7D32"));
                                    tvStatus.setText(String.format(Locale.US, "🟢 Posizione acquisita (±%.0f m)", last.getAccuracy()));
                                } else {
                                    tvStatus.setTextColor(Color.parseColor("#C62828"));
                                    tvStatus.setText("🔴 Attiva il GPS dello smartphone e riprova.");
                                }
                            });
                        }
                    })
                    .addOnFailureListener(e -> {
                        tvStatus.setTextColor(Color.parseColor("#C62828"));
                        tvStatus.setText("🔴 Errore GPS: " + e.getMessage());
                    });
        } catch (SecurityException se) {
            tvStatus.setTextColor(Color.parseColor("#C62828"));
            tvStatus.setText("🔴 Permesso posizione mancante.");
        }
    }

    private void showCarBluetoothPickerDialog(String[] selectedMacHolder, String[] selectedNameHolder, TextView tvSelectedBt) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                    PERMISSION_REQUEST_CODE
            );
            Toast.makeText(this, "Concedi il permesso Bluetooth e riprova.", Toast.LENGTH_SHORT).show();
            return;
        }

        List<String> labels = new ArrayList<>();
        List<String> macs = new ArrayList<>();
        List<String> names = new ArrayList<>();

        labels.add("Qualsiasi auto Android Auto");
        macs.add("");
        names.add("");

        int checkedIdx = 0;
        String currentMac = selectedMacHolder[0] != null ? selectedMacHolder[0] : "";

        try {
            BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = (bm != null) ? bm.getAdapter() : null;
            if (adapter != null) {
                Set<BluetoothDevice> bonded = adapter.getBondedDevices();
                if (bonded != null) {
                    for (BluetoothDevice bd : bonded) {
                        String bName = bd.getName() != null ? bd.getName() : "Dispositivo Bluetooth";
                        String bMac = bd.getAddress() != null ? bd.getAddress() : "";
                        if (!bMac.isEmpty()) {
                            if (bMac.equalsIgnoreCase(currentMac)) {
                                checkedIdx = labels.size();
                            }
                            labels.add(bName + "  (" + bMac + ")");
                            macs.add(bMac);
                            names.add(bName);
                        }
                    }
                }
            }
        } catch (SecurityException ignored) {
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("Bluetooth veicolo")
                .setSingleChoiceItems(labels.toArray(new CharSequence[0]), checkedIdx, (d, which) -> {
                    selectedMacHolder[0] = macs.get(which);
                    selectedNameHolder[0] = names.get(which);
                    updateCarBtLabelText(tvSelectedBt, selectedMacHolder[0], selectedNameHolder[0]);
                    d.dismiss();
                })
                .setNegativeButton("Annulla", null)
                .show();
    }

    private void ensureAutoOpenPermissionsGuided() {
        List<String> fgMissing = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            fgMissing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            fgMissing.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            fgMissing.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (!fgMissing.isEmpty()) {
            ActivityCompat.requestPermissions(
                    this,
                    fgMissing.toArray(new String[0]),
                    PERMISSION_REQUEST_AUTO_OPEN_FG
            );
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Posizione in background")
                    .setMessage("Seleziona \"Consenti sempre\" nella prossima schermata per permettere l'apertura automatica durante la guida con Android Auto.")
                    .setPositiveButton("Configura", (d, w) -> {
                        ActivityCompat.requestPermissions(
                                MainActivity.this,
                                new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                                PERMISSION_REQUEST_AUTO_OPEN_BG
                        );
                    })
                    .setNegativeButton("Più tardi", null)
                    .show();
            return;
        }

        GeofenceHelper.syncGeofences(this);
        notifyProximityServiceConfigChanged();
        promptBatteryOptimizationHintIfNeeded();
    }

    private void promptBatteryOptimizationHintIfNeeded() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Ottimizzazione batteria")
                    .setMessage("Per la massima affidabilità con Android Auto Wireless, imposta Batteria su \"Senza restrizioni\".")
                    .setPositiveButton("Impostazioni", (d, w) -> {
                        try {
                            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            intent.setData(Uri.fromParts("package", getPackageName(), null));
                            startActivity(intent);
                        } catch (Exception ignored) {
                        }
                    })
                    .setNegativeButton("Chiudi", null)
                    .show();
        } else {
            Toast.makeText(this, "Permessi GPS e batteria verificati!", Toast.LENGTH_SHORT).show();
        }
    }

    // ==========================================
    // BACKUP & RIPRISTINO GLOBALE (MEMORIA ESTERNA)
    // ==========================================

    private void showGlobalBackupMenuDialog() {
        List<Device> current = DeviceManager.getDevices(this);
        View view = getLayoutInflater().inflate(R.layout.sheet_backup_menu, null);
        BottomSheetDialog sheet = createModernBottomSheet(view);

        TextView tvCount = view.findViewById(R.id.tv_backup_device_count);
        int count = current.size();
        tvCount.setText(count == 1 ? "1 dispositivo" : count + " dispositivi");

        view.findViewById(R.id.card_backup_export).setOnClickListener(v -> {
            sheet.dismiss();
            showExportGlobalBackupDialog();
        });

        view.findViewById(R.id.card_backup_import).setOnClickListener(v -> {
            sheet.dismiss();
            startImportGlobalBackupFlow();
        });

        View btnClose = view.findViewById(R.id.btn_close_backup_sheet);
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> sheet.dismiss());
        }

        sheet.show();
    }

    private void showExportGlobalBackupDialog() {
        List<Device> devices = DeviceManager.getDevices(this);
        boolean hasHome = ProximityConfigManager.hasHomeLocation(this);
        if (devices.isEmpty() && !hasHome) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Nessun dato da esportare")
                    .setMessage("Aggiungi almeno un dispositivo prima di creare un backup.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        int bleCount = 0;
        int shellyCount = 0;
        for (Device d : devices) {
            if (d.isShellyCloud()) {
                shellyCount++;
            } else {
                bleCount++;
            }
        }

        View view = getLayoutInflater().inflate(R.layout.dialog_backup_export, null);
        BottomSheetDialog sheet = createModernBottomSheet(view);

        TextView chipBle = view.findViewById(R.id.chip_export_ble);
        TextView chipShelly = view.findViewById(R.id.chip_export_shelly);
        TextView chipGps = view.findViewById(R.id.chip_export_gps);
        TextInputEditText etPassword = view.findViewById(R.id.et_export_password);
        MaterialButton btnConfirm = view.findViewById(R.id.btn_confirm_export);

        if (bleCount > 0 || shellyCount == 0) {
            chipBle.setVisibility(View.VISIBLE);
            chipBle.setText(bleCount + " BLE");
        } else {
            chipBle.setVisibility(View.GONE);
        }

        if (shellyCount > 0) {
            chipShelly.setVisibility(View.VISIBLE);
            chipShelly.setText(shellyCount + " Shelly");
        } else {
            chipShelly.setVisibility(View.GONE);
        }

        chipGps.setVisibility(hasHome ? View.VISIBLE : View.GONE);

        btnConfirm.setOnClickListener(v -> {
            String pwd = etPassword.getText() != null ? etPassword.getText().toString() : "";
            try {
                pendingExportBackupBytes = BackupManager.createGlobalBackupBytes(this, pwd);
                sheet.dismiss();
                exportBackupLauncher.launch(BackupManager.buildSuggestedFileName());
            } catch (Exception e) {
                Log.e(TAG, "Errore generazione backup globale", e);
                pendingExportBackupBytes = null;
                showResultAlertDialog("Errore backup", "Impossibile generare il backup: " + e.getMessage());
            }
        });

        sheet.show();
    }

    private void writePendingBackupToUri(Uri uri) {
        byte[] bytesToWrite = pendingExportBackupBytes;
        pendingExportBackupBytes = null;
        if (bytesToWrite == null || bytesToWrite.length == 0) {
            return;
        }

        OutputStream os = null;
        try {
            try {
                os = getContentResolver().openOutputStream(uri, "wt");
            } catch (Exception fallbackErr) {
                os = getContentResolver().openOutputStream(uri);
            }
            if (os == null) {
                showResultAlertDialog("Errore scrittura", "Impossibile scrivere nella posizione selezionata.");
                return;
            }
            os.write(bytesToWrite);
            os.flush();

            String savedName = queryDisplayName(uri);
            Toast.makeText(this, "Backup salvato: " + savedName, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "Errore scrittura file backup", e);
            showResultAlertDialog("Errore salvataggio", "Errore durante il salvataggio: " + e.getMessage());
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void startImportGlobalBackupFlow() {
        importBackupLauncher.launch(new String[]{"application/json", "application/octet-stream", "*/*"});
    }

    private void handleSelectedBackupImportUri(Uri uri) {
        String fileName = queryDisplayName(uri);
        String rawJson;

        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) {
                showResultAlertDialog("Errore lettura", "Impossibile leggere il file selezionato.");
                return;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int read;
            int total = 0;
            while ((read = is.read(buf)) != -1) {
                total += read;
                if (total > BackupManager.MAX_BACKUP_FILE_BYTES) {
                    showResultAlertDialog(
                            "File non valido",
                            "Il file supera la dimensione massima consentita per un backup."
                    );
                    return;
                }
                bos.write(buf, 0, read);
            }
            rawJson = new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "Errore lettura file backup", e);
            showResultAlertDialog("Errore lettura", "Impossibile leggere il file: " + e.getMessage());
            return;
        }

        try {
            boolean encrypted = BackupManager.isBackupEncrypted(rawJson);
            if (encrypted) {
                showDecryptBackupPasswordDialog(fileName, rawJson);
            } else {
                BackupManager.ParsedBackup parsed = BackupManager.parseGlobalBackup(rawJson, null);
                showConfirmImportBackupDialog(fileName, parsed);
            }
        } catch (BackupManager.InvalidBackupFormatException e) {
            showResultAlertDialog("Backup non valido", e.getMessage());
        } catch (BackupManager.WrongPasswordException e) {
            showDecryptBackupPasswordDialog(fileName, rawJson);
        }
    }

    private void showDecryptBackupPasswordDialog(String fileName, String rawJson) {
        View view = getLayoutInflater().inflate(R.layout.dialog_backup_decrypt, null);
        BottomSheetDialog sheet = createModernBottomSheet(view);

        TextView tvFileName = view.findViewById(R.id.tv_decrypt_filename);
        TextInputLayout tilPassword = view.findViewById(R.id.til_decrypt_password);
        TextInputEditText etPassword = view.findViewById(R.id.et_decrypt_password);
        MaterialButton btnConfirm = view.findViewById(R.id.btn_confirm_decrypt);

        tvFileName.setText(fileName);

        etPassword.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                tilPassword.setError(null);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        btnConfirm.setOnClickListener(v -> {
            String pwd = etPassword.getText() != null ? etPassword.getText().toString() : "";
            if (pwd.isEmpty()) {
                tilPassword.setError("Inserisci la password");
                return;
            }
            try {
                BackupManager.ParsedBackup parsed = BackupManager.parseGlobalBackup(rawJson, pwd);
                sheet.dismiss();
                showConfirmImportBackupDialog(fileName, parsed);
            } catch (BackupManager.WrongPasswordException wrongPwd) {
                tilPassword.setError("Password non corretta");
            } catch (BackupManager.InvalidBackupFormatException badFmt) {
                sheet.dismiss();
                showResultAlertDialog("Backup non valido", badFmt.getMessage());
            }
        });

        sheet.show();
    }

    private void showConfirmImportBackupDialog(String fileName, BackupManager.ParsedBackup parsed) {
        List<Device> currentLocal = DeviceManager.getDevices(this);
        boolean hasExistingLocal = !currentLocal.isEmpty();

        View view = getLayoutInflater().inflate(R.layout.dialog_backup_import_preview, null);
        BottomSheetDialog sheet = createModernBottomSheet(view);

        TextView tvMeta = view.findViewById(R.id.tv_import_file_meta);
        View nsvDevices = view.findViewById(R.id.nsv_import_devices);
        LinearLayout llContainer = view.findViewById(R.id.ll_import_devices_container);
        View llModeSection = view.findViewById(R.id.ll_import_mode_section);
        MaterialCardView cardMerge = view.findViewById(R.id.card_mode_merge);
        TextView tvMergeTitle = view.findViewById(R.id.tv_mode_merge_title);
        MaterialCardView cardOverwrite = view.findViewById(R.id.card_mode_overwrite);
        TextView tvOverwriteTitle = view.findViewById(R.id.tv_mode_overwrite_title);
        SwitchMaterial swAdvance = view.findViewById(R.id.sw_advance_ble_counter);
        MaterialButton btnConfirm = view.findViewById(R.id.btn_confirm_import);

        int devCount = parsed.devices.size();
        tvMeta.setText((devCount == 1 ? "1 dispositivo" : devCount + " dispositivi") + " • " + fileName);

        for (Device d : parsed.devices) {
            if (d.isShellyCloud()) {
                String sub = "ID " + d.getShellyDeviceId();
                String tag = d.isAutoOpenEnabled() ? ("Auto " + d.getAutoOpenRadiusMeters() + "m") : "Cloud";
                llContainer.addView(buildImportPreviewRow(
                        R.drawable.ic_cloud,
                        R.drawable.bg_icon_blue,
                        "#1565C0",
                        d.getName(),
                        sub,
                        tag
                ));
            } else {
                String sub = d.getMacAddress();
                String tag = (d.isMaster() ? "MASTER • " : "") + "Cnt " + d.getCounter();
                llContainer.addView(buildImportPreviewRow(
                        R.drawable.ic_garage,
                        R.drawable.bg_icon_green,
                        "#2E7D32",
                        d.getName(),
                        sub,
                        tag
                ));
            }
        }

        boolean hasHomeInfo = parsed.proximity != null && parsed.proximity.hasHomeLocation;
        if (hasHomeInfo) {
            String btInfo = !parsed.proximity.carBluetoothName.isEmpty()
                    ? parsed.proximity.carBluetoothName
                    : (!parsed.proximity.carBluetoothMac.isEmpty() ? parsed.proximity.carBluetoothMac : "Coordinate GPS");
            llContainer.addView(buildImportPreviewRow(
                    R.drawable.ic_my_location,
                    R.drawable.bg_icon_green,
                    "#2E7D32",
                    "Posizione Casa",
                    btInfo,
                    "GPS"
            ));
        }

        int totalRows = devCount + (hasHomeInfo ? 1 : 0);
        if (totalRows > 3) {
            ViewGroup.LayoutParams lp = nsvDevices.getLayoutParams();
            lp.height = dpToPx(200);
            nsvDevices.setLayoutParams(lp);
        }

        final boolean[] overwriteMode = new boolean[]{!hasExistingLocal};
        if (hasExistingLocal) {
            llModeSection.setVisibility(View.VISIBLE);
            Runnable syncModeUi = () -> {
                boolean isOverwrite = overwriteMode[0];
                cardMerge.setCardBackgroundColor(Color.parseColor(isOverwrite ? "#FFFFFF" : "#F1F8E9"));
                cardMerge.setStrokeColor(Color.parseColor(isOverwrite ? "#E0E4E8" : "#4CAF50"));
                cardMerge.setStrokeWidth(dpToPx(isOverwrite ? 1 : 2));
                tvMergeTitle.setTextColor(Color.parseColor(isOverwrite ? "#202124" : "#2E7D32"));

                cardOverwrite.setCardBackgroundColor(Color.parseColor(isOverwrite ? "#FFF8E1" : "#FFFFFF"));
                cardOverwrite.setStrokeColor(Color.parseColor(isOverwrite ? "#EF6C00" : "#E0E4E8"));
                cardOverwrite.setStrokeWidth(dpToPx(isOverwrite ? 2 : 1));
                tvOverwriteTitle.setTextColor(Color.parseColor(isOverwrite ? "#E65100" : "#202124"));

                btnConfirm.setText(isOverwrite ? "Sovrascrivi e importa" : "Unisci dispositivi");
            };
            cardMerge.setOnClickListener(v -> {
                overwriteMode[0] = false;
                syncModeUi.run();
            });
            cardOverwrite.setOnClickListener(v -> {
                overwriteMode[0] = true;
                syncModeUi.run();
            });
            syncModeUi.run();
        } else {
            llModeSection.setVisibility(View.GONE);
            btnConfirm.setText("Importa dispositivi");
        }

        if (parsed.getBleCount() > 0) {
            swAdvance.setVisibility(View.VISIBLE);
            swAdvance.setChecked(false);
        } else {
            swAdvance.setVisibility(View.GONE);
        }

        btnConfirm.setOnClickListener(v -> {
            int offset = (swAdvance.getVisibility() == View.VISIBLE && swAdvance.isChecked()) ? 10 : 0;
            sheet.dismiss();
            completeBackupImport(parsed, overwriteMode[0], offset);
        });

        sheet.show();
    }

    private View buildImportPreviewRow(
            int iconRes,
            int iconBgRes,
            String tintHex,
            String title,
            String subtitle,
            String tagText
    ) {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dpToPx(12));
        card.setCardElevation(0f);
        card.setCardBackgroundColor(Color.parseColor("#FAFAFB"));
        card.setStrokeColor(Color.parseColor("#E8EAED"));
        card.setStrokeWidth(dpToPx(1));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cardLp.bottomMargin = dpToPx(8);
        card.setLayoutParams(cardLp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = dpToPx(12);
        int padV = dpToPx(10);
        row.setPadding(padH, padV, padH, padV);
        card.addView(row);

        ImageView iv = new ImageView(this);
        int sz = dpToPx(36);
        int ip = dpToPx(8);
        iv.setPadding(ip, ip, ip, ip);
        iv.setBackgroundResource(iconBgRes);
        iv.setImageResource(iconRes);
        ImageViewCompat.setImageTintList(iv, ColorStateList.valueOf(Color.parseColor(tintHex)));
        LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(sz, sz);
        ivLp.setMarginEnd(dpToPx(12));
        row.addView(iv, ivLp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(14.5f);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setTextColor(Color.parseColor("#202124"));
        tvTitle.setSingleLine(true);
        textCol.addView(tvTitle);

        TextView tvSub = new TextView(this);
        tvSub.setText(subtitle);
        tvSub.setTextSize(12f);
        tvSub.setTextColor(Color.parseColor("#5F6368"));
        tvSub.setSingleLine(true);
        textCol.addView(tvSub);

        TextView tvTag = new TextView(this);
        tvTag.setText(tagText);
        tvTag.setTextSize(11f);
        tvTag.setTypeface(null, Typeface.BOLD);
        tvTag.setTextColor(Color.parseColor(tintHex));
        tvTag.setBackgroundResource(R.drawable.bg_chip_neutral);
        tvTag.setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));
        row.addView(tvTag);

        return card;
    }

    private void completeBackupImport(BackupManager.ParsedBackup parsed, boolean overwriteAll, int bleCounterOffset) {
        BackupManager.applyImportedBackup(this, parsed, overwriteAll, bleCounterOffset);
        deviceList = DeviceManager.getDevices(this);
        deviceAdapter.updateDevices(deviceList);
        startStateScanning();
        GeofenceHelper.syncGeofences(this);
        notifyProximityServiceConfigChanged();

        Toast.makeText(
                this,
                "Importati " + deviceList.size() + " dispositivi!",
                Toast.LENGTH_SHORT
        ).show();

        if (ProximityConfigManager.hasAnyAutoOpenDevice(this)) {
            ensureAutoOpenPermissionsGuided();
        }
    }
}
