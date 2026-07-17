package it.geniola.apritisedano;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Color;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.ParcelUuid;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.Menu;
import android.view.MenuItem;
import java.nio.charset.StandardCharsets;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import com.journeyapps.barcodescanner.BarcodeEncoder;
import com.google.zxing.BarcodeFormat;
import androidx.activity.result.ActivityResultLauncher;
import android.net.Uri;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import android.graphics.Bitmap;
import android.widget.ImageView;

/**
 * Activity principale per la configurazione e il trigger NFC.
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private NfcAdapter nfcAdapter;
    private PendingIntent pendingIntent;
    private Button btnActionToggle;
    private Button btnActionStop;
    private int currentBoxState = -1;
    private TextView tvBoxState;
    private android.os.CountDownTimer totpTimer;
    private long lastSentTotpWindow = -1;
    private BluetoothLeScanner stateScanner;
    private long lastReceivedTimestamp = 0;
    private static final UUID TARGET_SERVICE_UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0");
    private boolean isNfcWriteMode = false;
    private AlertDialog nfcWriteDialog = null;

    private final ActivityResultLauncher<ScanOptions> barcodeLauncher = registerForActivityResult(new ScanContract(),
            result -> {
                if (result.getContents() != null) {
                    String scanned = result.getContents();
                    if (scanned.startsWith("otpauth://")) {
                        Uri uri = Uri.parse(scanned);
                        String secret = uri.getQueryParameter("secret");
                        if (secret != null) {
                            scanned = secret;
                        }
                    }
                    SecretStore.setSecretKey(MainActivity.this, scanned);
                    Toast.makeText(MainActivity.this, "Chiave importata con successo!", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MainActivity.this, "Scansione annullata", Toast.LENGTH_SHORT).show();
                }
            });

    private final BroadcastReceiver serviceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ApritiSedanoService.ACTION_SERVICE_STOPPED.equals(action)) {
                updateButtonsState(false);
            } else if (ApritiSedanoService.ACTION_OPERATION_RESULT.equals(action)) {
                updateButtonsState(false);
                String state = intent.getStringExtra(ApritiSedanoService.EXTRA_BOX_STATE);
                String displayState = "Sconosciuto";
                if ("OPEN".equals(state)) {
                    displayState = "APERTO";
                    Toast.makeText(context, "Operazione completata! Stato attuale: " + displayState, Toast.LENGTH_LONG).show();
                } else if ("CLOSED".equals(state)) {
                    displayState = "CHIUSO";
                    Toast.makeText(context, "Operazione completata! Stato attuale: " + displayState, Toast.LENGTH_LONG).show();
                } else if ("UNKNOWN".equals(state)) {
                    Toast.makeText(context, "Operazione fallita o bloccata (Es. Orologio Scarico).", Toast.LENGTH_LONG).show();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        tvBoxState = findViewById(R.id.tv_box_state);

        checkAndRequestPermissions();

        checkOrSetSecretKey();

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter == null) {
            Toast.makeText(this, "NFC non supportato", Toast.LENGTH_LONG).show();
        }

        // Preparazione per Foreground Dispatch
        Intent intent = new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE);

        // Gestione trigger se l'app è stata aperta da un Intent NFC o Vocale
        handleIntent(getIntent());

        setupActionButtons();
        
        // Registrazione Shortcut Dinamico per Google Assistant / Schermata Home
        createDynamicShortcut();
    }

    private void createDynamicShortcut() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            android.content.pm.ShortcutManager shortcutManager = getSystemService(android.content.pm.ShortcutManager.class);
            if (shortcutManager != null) {
                Intent shortcutIntent = new Intent(this, MainActivity.class);
                shortcutIntent.setAction(ApritiSedanoReceiver.ACTION_OPEN_BOX);
                
                android.content.pm.ShortcutInfo shortcut = new android.content.pm.ShortcutInfo.Builder(this, "id_open_box")
                        .setShortLabel("Apri Box")
                        .setLongLabel("Invia comando apertura box")
                        .setIcon(android.graphics.drawable.Icon.createWithResource(this, R.mipmap.ic_launcher))
                        .setIntent(shortcutIntent)
                        .build();

                shortcutManager.setDynamicShortcuts(java.util.Collections.singletonList(shortcut));
            }
        }
    }

    private void setupActionButtons() {
        btnActionToggle = findViewById(R.id.btn_action_toggle);
        btnActionStop = findViewById(R.id.btn_action_stop);

        btnActionToggle.setOnClickListener(v -> {
            Log.d(TAG, "Trigger manuale - TOGGLE");
            startApritiSedanoService();
            updateButtonsState(true);
        });

        btnActionStop.setOnClickListener(v -> {
            Log.d(TAG, "Trigger manuale - STOP");
            stopApritiSedanoService();
            updateButtonsState(false);
        });
    }

    private void updateButtonsState(boolean isServiceRunning) {
        if (btnActionToggle != null && btnActionStop != null) {
            btnActionStop.setEnabled(isServiceRunning);
            
            if (isServiceRunning) {
                btnActionToggle.setEnabled(false);
                if (totpTimer != null) {
                    totpTimer.cancel();
                    totpTimer = null;
                }
                btnActionToggle.setText(currentBoxState == 1 ? "CHIUDI" : "APRI");
            } else {
                startTotpCountdownIfNeeded();
            }
        }
    }

    private void startTotpCountdownIfNeeded() {
        long currentWindow = System.currentTimeMillis() / 1000L / 30;
        if (currentWindow == lastSentTotpWindow) {
            long currentSeconds = System.currentTimeMillis() / 1000L;
            long secondsToNextTotp = 30 - (currentSeconds % 30);
            
            btnActionToggle.setEnabled(false);
            if (totpTimer != null) totpTimer.cancel();
            
            totpTimer = new android.os.CountDownTimer(secondsToNextTotp * 1000, 1000) {
                @Override
                public void onTick(long millisUntilFinished) {
                    long sec = millisUntilFinished / 1000;
                    btnActionToggle.setText("Attendi " + sec + "s");
                }

                @Override
                public void onFinish() {
                    btnActionToggle.setEnabled(true);
                    btnActionToggle.setText(currentBoxState == 1 ? "CHIUDI" : "APRI");
                    totpTimer = null;
                }
            }.start();
        } else {
            btnActionToggle.setEnabled(true);
            btnActionToggle.setText(currentBoxState == 1 ? "CHIUDI" : "APRI");
        }
    }

    private void stopApritiSedanoService() {
        Intent serviceIntent = new Intent(this, ApritiSedanoService.class);
        stopService(serviceIntent);
        Toast.makeText(this, "Operazioni Bluetooth fermate", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcAdapter != null) {
            nfcAdapter.enableForegroundDispatch(this, pendingIntent, null, null);
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(ApritiSedanoService.ACTION_SERVICE_STOPPED);
        filter.addAction(ApritiSedanoService.ACTION_OPERATION_RESULT);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(serviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(serviceReceiver, filter);
        }
        startStateScanning();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfcAdapter != null) {
            nfcAdapter.disableForegroundDispatch(this);
        }
        unregisterReceiver(serviceReceiver);
        stopStateScanning();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "Intent action received: " + action);
        
        // Handle NFC Tag Discovery for Writing
        if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action) || 
            NfcAdapter.ACTION_TAG_DISCOVERED.equals(action) || 
            NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)) {
            
            if (isNfcWriteMode) {
                Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
                if (tag != null) {
                    writeTag(tag);
                }
                return; // Ferma il processing: stavamo solo configurando
            }
        }

        if (intent.getExtras() != null) {
            for (String key : intent.getExtras().keySet()) {
                Log.d(TAG, "Intent Extra: " + key + " = " + intent.getExtras().get(key));
            }
        }

        boolean isAutoTrigger = false;
        if (intent.getComponent() != null && intent.getComponent().getClassName().endsWith("AutoTrigger")) {
            isAutoTrigger = true;
        }

        if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action) || 
            ApritiSedanoReceiver.ACTION_OPEN_BOX.equals(action) || 
            isAutoTrigger) {
            
            Log.d(TAG, "Trigger automatico rilevato (NFC, Voce o Alias)! Avvio sblocco...");
            startApritiSedanoService();
            updateButtonsState(true); // Se la UI è visibile, mostriamo che stiamo lavorando
        }
    }

    private void startApritiSedanoService() {
        if (!hasRequiredPermissions()) {
            Toast.makeText(this, "Permessi Bluetooth mancanti", Toast.LENGTH_LONG).show();
            checkAndRequestPermissions();
            return;
        }

        Intent serviceIntent = new Intent(this, ApritiSedanoService.class);
        lastSentTotpWindow = System.currentTimeMillis() / 1000L / 30; // Registra l'ultimo TOTP inviato
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        Toast.makeText(this, "Apertura box avviata via NFC", Toast.LENGTH_SHORT).show();
    }

    private void startApritiSedanoServiceForSync() {
        if (!hasRequiredPermissions()) return;
        Intent serviceIntent = new Intent(this, ApritiSedanoService.class);
        serviceIntent.setAction("ACTION_SYNC_TIME");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        Log.d(TAG, "Avviata sincronizzazione orario");
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
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(p);
            }
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
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (!allGranted) {
                Toast.makeText(this, "L'app richiede i permessi Bluetooth per funzionare correttamente.", Toast.LENGTH_LONG).show();
            } else {
                startStateScanning();
            }
        }
    }

    private final ScanCallback stateScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (result.getScanRecord() != null) {
                byte[] payload = result.getScanRecord().getManufacturerSpecificData(0x02E5);
                if (payload != null && payload.length >= 1) {
                    if (payload[0] == 0x02) {
                        runOnUiThread(() -> {
                            tvBoxState.setText("Errore: Orologio Scarico. Premi BOOT sulla scheda.");
                            tvBoxState.setTextColor(Color.parseColor("#FF9800"));
                            if (btnActionToggle != null) btnActionToggle.setEnabled(false);
                        });
                    } else if (payload[0] == 0x03) {
                        runOnUiThread(() -> {
                            tvBoxState.setText("Sincronizzazione in corso...");
                            tvBoxState.setTextColor(Color.parseColor("#2196F3"));
                            if (btnActionToggle != null) btnActionToggle.setEnabled(false);
                            startApritiSedanoServiceForSync();
                        });
                    } else if (payload[0] == 0x01 && payload.length >= 10) {
                        byte state = payload[1];
                        long ts = ((payload[2] & 0xFFL) << 24) |
                                  ((payload[3] & 0xFFL) << 16) |
                                  ((payload[4] & 0xFFL) << 8) |
                                  (payload[5] & 0xFFL);
                        byte[] hmac = new byte[4];
                        System.arraycopy(payload, 6, hmac, 0, 4);

                        long currentTs = System.currentTimeMillis() / 1000L;
                        if (Math.abs(currentTs - ts) > 300) {
                            return; // Oltre 5 minuti di differenza
                        }
                        if (ts <= lastReceivedTimestamp) {
                            return; // Prevenzione Replay
                        }

                        if (TOTPGenerator.verifyStateBeacon(SecretStore.getSecretKey(MainActivity.this), state, (int)ts, hmac)) {
                            lastReceivedTimestamp = ts;
                            currentBoxState = state;
                            runOnUiThread(() -> {
                                if (state == 1) {
                                    tvBoxState.setText("Stato: APERTO");
                                    tvBoxState.setTextColor(Color.parseColor("#4CAF50"));
                                } else {
                                    tvBoxState.setText("Stato: CHIUSO");
                                    tvBoxState.setTextColor(Color.parseColor("#F44336"));
                                }
                                if (totpTimer == null && btnActionToggle != null && !btnActionToggle.isEnabled()) {
                                    btnActionToggle.setEnabled(true);
                                }
                                if (totpTimer == null && btnActionToggle != null && btnActionToggle.isEnabled()) {
                                    btnActionToggle.setText(state == 1 ? "CHIUDI" : "APRI");
                                }
                            });
                        }
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
                ScanSettings scanSettings = new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .build();

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
                if (hasRequiredPermissions()) {
                    stateScanner.stopScan(stateScanCallback);
                }
            } catch (SecurityException e) {
                // Ignore
            }
        }
    }

    private void writeTag(Tag tag) {
        NdefMessage message = createNdefMessage();
        try {
            Ndef ndef = Ndef.get(tag);
            if (ndef != null) {
                ndef.connect();
                if (!ndef.isWritable()) {
                    Toast.makeText(this, "Il Tag NFC è di sola lettura.", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (ndef.getMaxSize() < message.toByteArray().length) {
                    Toast.makeText(this, "Il Tag NFC non ha abbastanza spazio.", Toast.LENGTH_SHORT).show();
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
                } else {
                    Toast.makeText(this, "Tag NFC non supportato per la formattazione.", Toast.LENGTH_SHORT).show();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Errore durante la scrittura del tag NFC", e);
            Toast.makeText(this, "Errore in scrittura: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        } finally {
            isNfcWriteMode = false;
            if (nfcWriteDialog != null && nfcWriteDialog.isShowing()) {
                nfcWriteDialog.dismiss();
            }
        }
    }

    private NdefMessage createNdefMessage() {
        // Create custom MIME record
        String mimeType = "application/it.geniola.apritisedano";
        byte[] payload = "apritisedano_trigger".getBytes(StandardCharsets.UTF_8);
        byte[] mimeBytes = mimeType.getBytes(StandardCharsets.US_ASCII);
        NdefRecord mimeRecord = new NdefRecord(NdefRecord.TNF_MIME_MEDIA, mimeBytes, new byte[0], payload);
        
        // Create AAR to ensure the app is opened even if it's completely closed
        NdefRecord aarRecord = NdefRecord.createApplicationRecord("it.geniola.apritisedano");
        
        return new NdefMessage(new NdefRecord[]{mimeRecord, aarRecord});
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_config_nfc) {
            startNfcConfig();
            return true;
        } else if (item.getItemId() == R.id.action_set_secret) {
            showSecretKeyDialog();
            return true;
        } else if (item.getItemId() == R.id.action_debug_mode) {
            startActivity(new Intent(this, DebugActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void checkOrSetSecretKey() {
        String currentKey = SecretStore.getSecretKey(this);
        if (currentKey == null || currentKey.trim().isEmpty()) {
            showSecretKeyDialog();
        }
    }

    private void showSecretKeyDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Imposta Chiave Segreta");

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_secret_key, null);
        builder.setView(dialogView);

        final EditText input = dialogView.findViewById(R.id.et_secret_key);
        input.setText(SecretStore.getSecretKey(this));

        Button btnScanQr = dialogView.findViewById(R.id.btn_scan_qr);
        Button btnShowQr = dialogView.findViewById(R.id.btn_show_qr);

        btnScanQr.setOnClickListener(v -> {
            ScanOptions options = new ScanOptions();
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
            options.setPrompt("Inquadra il QR Code con la chiave");
            options.setBeepEnabled(false);
            barcodeLauncher.launch(options);
        });

        btnShowQr.setOnClickListener(v -> {
            String currentKey = input.getText().toString().trim();
            if (!currentKey.isEmpty()) {
                showQrDialog(currentKey);
            } else {
                Toast.makeText(this, "Inserisci prima una chiave", Toast.LENGTH_SHORT).show();
            }
        });

        builder.setPositiveButton("Salva", (dialog, which) -> {
            String newKey = input.getText().toString().trim();
            SecretStore.setSecretKey(MainActivity.this, newKey);
            Toast.makeText(MainActivity.this, "Chiave salvata", Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("Annulla", (dialog, which) -> dialog.cancel());
        builder.setCancelable(false);
        builder.show();
    }

    private void showQrDialog(String key) {
        try {
            BarcodeEncoder barcodeEncoder = new BarcodeEncoder();
            String uriContent = "otpauth://totp/ApritiSedano?secret=" + key + "&issuer=ApritiSedano";
            Bitmap bitmap = barcodeEncoder.encodeBitmap(uriContent, BarcodeFormat.QR_CODE, 600, 600);

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("QR Code Chiave");

            ImageView imageView = new ImageView(this);
            imageView.setImageBitmap(bitmap);
            imageView.setPadding(32, 32, 32, 32);
            builder.setView(imageView);

            builder.setPositiveButton("Condividi", (dialog, which) -> {
                shareQrCode(bitmap);
            });
            builder.setNegativeButton("Chiudi", (dialog, which) -> dialog.dismiss());
            builder.show();
        } catch (Exception e) {
            Toast.makeText(this, "Errore nella generazione del QR", Toast.LENGTH_SHORT).show();
        }
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
        } catch (Exception e) {
            Toast.makeText(this, "Errore durante la condivisione", Toast.LENGTH_SHORT).show();
        }
    }

    private void startNfcConfig() {
        if (nfcAdapter == null) {
            Toast.makeText(this, "NFC non supportato su questo dispositivo", Toast.LENGTH_LONG).show();
            return;
        }
        if (!nfcAdapter.isEnabled()) {
            Toast.makeText(this, "Attiva l'NFC nelle impostazioni per continuare", Toast.LENGTH_LONG).show();
            return;
        }
        
        isNfcWriteMode = true;
        
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Configura Tag NFC");
        builder.setMessage("Avvicina un Tag NFC (vuoto o da sovrascrivere) al retro del telefono...");
        builder.setNegativeButton("Annulla", (dialog, which) -> {
            isNfcWriteMode = false;
        });
        builder.setOnCancelListener(dialog -> {
            isNfcWriteMode = false;
        });
        nfcWriteDialog = builder.create();
        nfcWriteDialog.show();
    }
}