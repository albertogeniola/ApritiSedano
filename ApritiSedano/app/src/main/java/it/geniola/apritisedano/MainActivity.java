package it.geniola.apritisedano;

import android.Manifest;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
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
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
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
import java.util.UUID;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.ParcelUuid;

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
    private List<String> discoveredMacs = new ArrayList<>();
    private ArrayAdapter<String> discoveryAdapter;

    private boolean isNfcWriteMode = false;
    private String nfcWriteTargetMac = null;
    private AlertDialog nfcWriteDialog = null;

    private String tempAddingMac = null; // MAC of the device currently being added
    private EditText currentSecretKeyEditText = null;

    private final ActivityResultLauncher<ScanOptions> barcodeLauncher = registerForActivityResult(new ScanContract(),
            result -> {
                if (result.getContents() != null) {
                    String scanned = result.getContents();
                    if (scanned.startsWith("otpauth://")) {
                        Uri uri = Uri.parse(scanned);
                        String secret = uri.getQueryParameter("secret");
                        if (secret != null) scanned = secret;
                    }
                    if (TOTPGenerator.isValidBase32Secret(scanned)) {
                        if (currentSecretKeyEditText != null) {
                            currentSecretKeyEditText.setText(scanned);
                            Toast.makeText(this, "QR Scansionato! Clicca Salva per confermare.", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        Toast.makeText(this, "La chiave scansionata non è valida (richiesto formato Base32).", Toast.LENGTH_LONG).show();
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

            if (ApritiSedanoService.ACTION_SERVICE_STOPPED.equals(action)) {
                if (targetMac != null) {
                    deviceAdapter.markActionPending(targetMac, false);
                }
            } else if (ApritiSedanoService.ACTION_OPERATION_RESULT.equals(action)) {
                if (targetMac != null) {
                    deviceAdapter.markActionPending(targetMac, false);
                    String state = intent.getStringExtra(ApritiSedanoService.EXTRA_BOX_STATE);
                    if ("OPEN".equals(state)) {
                        Toast.makeText(context, "Operazione completata! Stato: APERTO", Toast.LENGTH_SHORT).show();
                    } else if ("CLOSED".equals(state)) {
                        Toast.makeText(context, "Operazione completata! Stato: CHIUSO", Toast.LENGTH_SHORT).show();
                    } else if ("UNKNOWN".equals(state)) {
                        Toast.makeText(context, "Operazione fallita o bloccata.", Toast.LENGTH_LONG).show();
                    }
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
        deviceAdapter = new DeviceAdapter(deviceList, this::onDeviceActionClick, this::showDeviceSettingsDialog);
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

    private void onDeviceActionClick(Device device) {
        if (!hasRequiredPermissions()) {
            checkAndRequestPermissions();
            return;
        }
        deviceAdapter.markActionPending(device.getMacAddress(), true);

        Intent serviceIntent = new Intent(this, ApritiSedanoService.class);
        serviceIntent.putExtra(ApritiSedanoService.EXTRA_TARGET_MAC, device.getMacAddress());
        serviceIntent.putExtra(ApritiSedanoService.EXTRA_SECRET_KEY, device.getSecretKey());
        
        if (device.getCurrentState() == 4) {
            // Time invalid, sync it
            serviceIntent.setAction("ACTION_SYNC_TIME");
        }
        
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
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfcAdapter != null) nfcAdapter.disableForegroundDispatch(this);
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

        // Handle auto-triggering via Deep Link (NFC)
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
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Impostazioni: " + device.getName());
        
        CharSequence[] options = {"Debug Dispositivo", "Scrivi Tag NFC", "Mostra QR Code Chiave", "Sincronizza Orologio", "Elimina Dispositivo"};
        builder.setItems(options, (dialog, which) -> {
            if (which == 0) {
                Intent debugIntent = new Intent(this, DebugActivity.class);
                debugIntent.putExtra("EXTRA_TARGET_MAC", device.getMacAddress());
                startActivity(debugIntent);
            } else if (which == 1) {
                startNfcConfig(device.getMacAddress());
            } else if (which == 2) {
                showQrDialog(device.getSecretKey());
            } else if (which == 3) {
                device.setCurrentState(4); // Force sync visually
                onDeviceActionClick(device);
            } else if (which == 4) {
                DeviceManager.removeDevice(this, device.getMacAddress());
                deviceList = DeviceManager.getDevices(this);
                deviceAdapter.updateDevices(deviceList);
                Toast.makeText(this, "Dispositivo eliminato", Toast.LENGTH_SHORT).show();
            }
        });
        builder.show();
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
        
        discoveredMacs.clear();
        discoveryAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, discoveredMacs);
        lvScanned.setAdapter(discoveryAdapter);
        
        lvScanned.setOnItemClickListener((parent, v, position, id) -> {
            String item = discoveredMacs.get(position);
            String mac = item.split(" ")[0]; // Extract MAC part
            stopDiscoveryScan();
            if (discoveryDialog != null && discoveryDialog.isShowing()) {
                discoveryDialog.dismiss();
            }
            showFinalDeviceConfigDialog(mac);
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
                ScanSettings scanSettings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
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
            } catch (SecurityException e) {}
        }
    }

    private final ScanCallback discoveryScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (result.getDevice() != null) {
                String mac = result.getDevice().getAddress();
                int rssi = result.getRssi();
                String entry = mac + " (RSSI: " + rssi + "dBm)";
                
                runOnUiThread(() -> {
                    // Check if MAC is already in list to update RSSI, otherwise add
                    boolean found = false;
                    for (int i = 0; i < discoveredMacs.size(); i++) {
                        if (discoveredMacs.get(i).startsWith(mac)) {
                            discoveredMacs.set(i, entry);
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        discoveredMacs.add(entry);
                    }
                    if (discoveryAdapter != null) discoveryAdapter.notifyDataSetChanged();
                });
            }
        }
    };

    private void showFinalDeviceConfigDialog(String macAddress) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Configura: " + macAddress);
        
        View view = getLayoutInflater().inflate(R.layout.dialog_secret_key, null);
        builder.setView(view);
        
        EditText etSecret = view.findViewById(R.id.et_secret_key);
        currentSecretKeyEditText = etSecret;
        
        EditText etName = new EditText(this);
        etName.setHint("Nome Dispositivo (es. Cancello)");
        
        ((android.widget.LinearLayout)view).addView(etName, 0);

        view.findViewById(R.id.btn_scan_qr).setOnClickListener(v -> {
            ScanOptions options = new ScanOptions();
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
            options.setPrompt("Inquadra il QR Code con la chiave");
            barcodeLauncher.launch(options);
        });

        builder.setPositiveButton("Salva", null);
        builder.setNegativeButton("Annulla", (dialog, which) -> dialog.cancel());
        
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String name = etName.getText().toString().trim();
                String secret = etSecret.getText().toString().trim();
                
                if (name.isEmpty()) {
                    Toast.makeText(this, "Compila Nome Dispositivo", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                if (TOTPGenerator.isValidBase32Secret(secret)) {
                    Device newDev = new Device(macAddress, name, secret);
                    DeviceManager.addDevice(this, newDev);
                    deviceList = DeviceManager.getDevices(this);
                    deviceAdapter.updateDevices(deviceList);
                    Toast.makeText(this, "Dispositivo Aggiunto!", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                } else {
                    Toast.makeText(this, "Formato chiave non valido.", Toast.LENGTH_LONG).show();
                }
            });
        });
        dialog.show();
    }

    private final ScanCallback stateScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (result.getScanRecord() != null) {
                byte[] payload = result.getScanRecord().getManufacturerSpecificData(0x02E5);
                String mac = result.getDevice().getAddress();
                
                if (payload != null && payload.length >= 1) {
                    if (payload[0] == 0x02) {
                        runOnUiThread(() -> deviceAdapter.updateDeviceState(mac, 4)); // Time Invalid
                    } else if (payload[0] == 0x03) {
                        runOnUiThread(() -> deviceAdapter.updateDeviceState(mac, 3)); // Syncing
                    } else if (payload[0] == 0x01 && payload.length >= 10) {
                        byte state = payload[1];
                        runOnUiThread(() -> {
                            if (state == 1) deviceAdapter.updateDeviceState(mac, 1); // Open
                            else deviceAdapter.updateDeviceState(mac, 2); // Closed
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
            } catch (SecurityException e) {}
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

            builder.setPositiveButton("Condividi", (dialog, which) -> shareQrCode(bitmap));
            builder.setNegativeButton("Chiudi", (dialog, which) -> dialog.dismiss());
            builder.show();
        } catch (Exception e) {}
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
        } catch (Exception e) {}
    }

}