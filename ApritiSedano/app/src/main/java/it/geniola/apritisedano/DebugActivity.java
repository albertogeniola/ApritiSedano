package it.geniola.apritisedano;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Collections;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

public class DebugActivity extends AppCompatActivity {

    private RecyclerView rvLogs;
    private DebugLogAdapter adapter;
    private List<DebugLogEntry> logsList;
    private Timer timer;
    private Timer packetTimer;
    private int packetsSentCount = 0;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextView tvBoxState;
    private Button btnActionToggle;
    private Button btnActionStop;
    private BluetoothLeScanner stateScanner;
    private long lastReceivedTimestamp = 0;
    private int currentBoxState = -1;
    private static final UUID TARGET_SERVICE_UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0");

    private final BroadcastReceiver serviceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ApritiSedanoService.ACTION_SERVICE_STOPPED.equals(action) || 
                ApritiSedanoService.ACTION_OPERATION_RESULT.equals(action)) {
                btnActionToggle.setEnabled(true);
                if (btnActionStop != null) {
                    btnActionStop.setEnabled(false);
                }
                if (adapter != null) {
                    adapter.setTransmitting(false);
                }
            }
        }
    };

    private final ScanCallback stateScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (result.getScanRecord() != null) {
                byte[] payload = result.getScanRecord().getManufacturerSpecificData(0x02E5);
                if (payload != null && payload.length >= 1) {
                    if (payload[0] == 0x01 && payload.length >= 10) {
                        byte state = payload[1];
                        long ts = ((payload[2] & 0xFFL) << 24) |
                                  ((payload[3] & 0xFFL) << 16) |
                                  ((payload[4] & 0xFFL) << 8) |
                                  (payload[5] & 0xFFL);
                        byte[] hmac = new byte[4];
                        System.arraycopy(payload, 6, hmac, 0, 4);

                        long currentTs = System.currentTimeMillis() / 1000L;
                        if (Math.abs(currentTs - ts) > 300) return;
                        if (ts <= lastReceivedTimestamp) return;

                        if (TOTPGenerator.verifyStateBeacon(SecretStore.getSecretKey(DebugActivity.this), state, (int)ts, hmac)) {
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
                                if (btnActionStop != null && !btnActionStop.isEnabled()) {
                                    btnActionToggle.setEnabled(true);
                                }
                                btnActionToggle.setText(state == 1 ? "CHIUDI" : "APRI");
                            });
                        }
                    } else if (payload[0] == 0x02) {
                        runOnUiThread(() -> {
                            tvBoxState.setText("Stato: Orologio Scarico");
                            tvBoxState.setTextColor(Color.parseColor("#FF9800"));
                        });
                    } else if (payload[0] == 0x03) {
                        runOnUiThread(() -> {
                            tvBoxState.setText("Stato: Sincronizzazione...");
                            tvBoxState.setTextColor(Color.parseColor("#2196F3"));
                        });
                    }
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        setContentView(R.layout.activity_debug);

        rvLogs = findViewById(R.id.rv_debug_logs);
        rvLogs.setLayoutManager(new LinearLayoutManager(this));

        findViewById(R.id.btn_clear_logs).setOnClickListener(v -> {
            DebugLogger.getInstance().clearLogs();
            updateList();
        });

        tvBoxState = findViewById(R.id.tv_debug_box_state);
        btnActionToggle = findViewById(R.id.btn_debug_action_toggle);
        btnActionStop = findViewById(R.id.btn_debug_action_stop);

        btnActionToggle.setOnClickListener(v -> {
            btnActionToggle.setEnabled(false);
            btnActionStop.setEnabled(true);
            if (adapter != null) {
                adapter.setTransmitting(true);
            }
            Intent serviceIntent = new Intent(this, ApritiSedanoService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        });

        btnActionStop.setOnClickListener(v -> {
            btnActionToggle.setEnabled(true);
            btnActionStop.setEnabled(false);
            if (adapter != null) {
                adapter.setTransmitting(false);
            }
            stopService(new Intent(this, ApritiSedanoService.class));
            android.widget.Toast.makeText(this, "Operazione interrotta", android.widget.Toast.LENGTH_SHORT).show();
        });

        // Setup adapter with current logs
        logsList = DebugLogger.getInstance().getLogs();
        adapter = new DebugLogAdapter(logsList);
        rvLogs.setAdapter(adapter);

        // Listen for new logs
        DebugLogger.getInstance().setListener(entry -> {
            mainHandler.post(() -> {
                logsList.add(0, entry);
                if (logsList.size() > 100) {
                    logsList.remove(logsList.size() - 1);
                }
                adapter.notifyDataSetChanged();
                rvLogs.scrollToPosition(0);
            });
        });
    }

    private void updateList() {
        logsList.clear();
        logsList.addAll(DebugLogger.getInstance().getLogs());
        adapter.notifyDataSetChanged();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateList();
        
        IntentFilter filter = new IntentFilter();
        filter.addAction(ApritiSedanoService.ACTION_SERVICE_STOPPED);
        filter.addAction(ApritiSedanoService.ACTION_OPERATION_RESULT);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(serviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(serviceReceiver, filter);
        }
        startStateScanning();

        // Timer to refresh the UI every second so the "VALIDO/SCADUTO" label updates dynamically
        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                mainHandler.post(() -> {
                    if (adapter != null) {
                        adapter.toggleBlink();
                    }
                });
            }
        }, 500, 500);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        unregisterReceiver(serviceReceiver);
        stopStateScanning();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        DebugLogger.getInstance().setListener(null);
    }

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
                    Log.e("DebugActivity", "Permessi BLE mancanti per lo scan", e);
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

    private boolean hasRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
}
