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

import java.io.File;
import java.io.FileOutputStream;
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
                if (targetMac != null) {
                    deviceAdapter.markActionPending(targetMac, false);
                }
            } else if (ApritiSedanoService.ACTION_OPERATION_RESULT.equals(action)) {
                dismissOperationProgressDialog();
                String opType = pendingModalOperation;
                pendingModalOperation = null;

                if (targetMac != null) {
                    deviceAdapter.markActionPending(targetMac, false);
                }
                String state = intent.getStringExtra(ApritiSedanoService.EXTRA_BOX_STATE);
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

        checkAndRequestPermissions();

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter == null) {
            Toast.makeText(this, "NFC non supportato", Toast.LENGTH_LONG).show();
        }

        Intent intent = new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE);

        handleIntent(getIntent());
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

        deviceList = DeviceManager.getDevices(this);
        deviceAdapter.updateDevices(deviceList);
        startStateScanning();

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

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(String.format(Locale.US, "%s (ID: 0x%04X, Cnt: %d)",
                currentDevice.getName(), currentDevice.getClientId(), currentDevice.getCounter()));

        CharSequence[] options = {
                "Attiva Modalità Accoppiamento",
                "Debug Dispositivo",
                "Scrivi Tag NFC",
                "Mostra QR Backup Dispositivo",
                "Sincronizza Orologio RTC",
                "Elimina Dispositivo"
        };
        builder.setItems(options, (dialog, which) -> {
            if (which == 0) {
                enableRemotePairingMode(currentDevice);
            } else if (which == 1) {
                Intent debugIntent = new Intent(this, DebugActivity.class);
                debugIntent.putExtra("EXTRA_TARGET_MAC", currentDevice.getMacAddress());
                startActivity(debugIntent);
            } else if (which == 2) {
                startNfcConfig(currentDevice.getMacAddress());
            } else if (which == 3) {
                showQrDialog(currentDevice);
            } else if (which == 4) {
                syncDeviceRtcTime(currentDevice);
            } else if (which == 5) {
                DeviceManager.removeDevice(this, currentDevice.getMacAddress());
                deviceList = DeviceManager.getDevices(this);
                deviceAdapter.updateDevices(deviceList);
                Toast.makeText(this, "Dispositivo eliminato", Toast.LENGTH_SHORT).show();
            }
        });
        builder.show();
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
        if (payload != null && payload.length == 6 && payload[0] == 0x04) {
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
                            deviceAdapter.updateDeviceState(mac, newState);
                            if (mac.equalsIgnoreCase(activePairingModalMac) &&
                                    pairingCountdownDialog != null &&
                                    pairingCountdownDialog.isShowing()) {
                                try { pairingCountdownDialog.dismiss(); } catch (Exception ignored) {}
                                pairingCountdownDialog = null;
                            }
                        });
                    }
                }
            }
        }
    };

    private void startStateScanning() {
        if (!hasRequiredPermissions()) return;
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
}
