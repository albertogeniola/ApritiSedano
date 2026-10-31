package it.geniola.apritisedano;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
import com.hoho.android.usbserial.driver.ProbeTable;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Console seriale USB interattiva per navigare il menu di configurazione CLI dell'ESP32.
 * Riservata esclusivamente alla Master App.
 */
public class SerialConsoleActivity extends AppCompatActivity {
    private static final String TAG = "SerialConsoleActivity";
    private static final String ACTION_USB_PERMISSION = "it.geniola.apritisedano.USB_PERMISSION_SERIAL";
    private static final int MAX_BUFFER_CHARS = 40000;

    private TextView tvStatus;
    private TextView tvTerminal;
    private ScrollView svTerminal;
    private EditText etInput;

    private UsbManager mUsbManager;
    private UsbSerialPort mPort;
    private UsbDeviceConnection mConnection;
    private Thread mReadThread;
    private volatile boolean mIsRunning = false;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                            ? intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class)
                            : intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            appendTerminalText("[Permesso USB concesso. Connessione in corso...]\n");
                            connectUsb();
                        }
                    } else {
                        appendTerminalText("[ERRORE: Permesso USB negato dall'utente.]\n");
                        updateStatus("Permesso USB negato", false);
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                appendTerminalText("[Dispositivo USB collegato. Avvio connessione...]\n");
                connectUsb();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                appendTerminalText("[Dispositivo USB disconnesso.]\n");
                disconnectUsb();
                updateStatus("Dispositivo disconnesso", false);
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_serial_console);

        mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        initViews();
        setupQuickActions();

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mUsbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mUsbReceiver, filter);
        }

        connectUsb();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(mUsbReceiver);
        } catch (Exception ignored) {}
        disconnectUsb();
    }

    private void initViews() {
        ImageButton btnBack = findViewById(R.id.btn_serial_back);
        tvStatus = findViewById(R.id.tv_serial_status);
        tvTerminal = findViewById(R.id.tv_terminal_output);
        svTerminal = findViewById(R.id.sv_terminal);
        etInput = findViewById(R.id.et_serial_input);
        Button btnSend = findViewById(R.id.btn_serial_send);
        Button btnEnter = findViewById(R.id.btn_serial_enter);
        Button btnClear = findViewById(R.id.btn_clear_serial);
        Button btnReset = findViewById(R.id.btn_reset_esp);

        btnBack.setOnClickListener(v -> finish());
        btnClear.setOnClickListener(v -> tvTerminal.setText(""));

        btnReset.setOnClickListener(v -> resetEspHardware());

        btnSend.setOnClickListener(v -> {
            String text = etInput.getText().toString();
            etInput.setText("");
            sendText(text, true);
        });

        btnEnter.setOnClickListener(v -> sendText("", true));

        etInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                String text = etInput.getText().toString();
                etInput.setText("");
                sendText(text, true);
                return true;
            }
            return false;
        });
    }

    private void setupQuickActions() {
        findViewById(R.id.btn_quick_conf).setOnClickListener(v -> sendText("conf", true));
        findViewById(R.id.btn_quick_1).setOnClickListener(v -> sendText("1", true));
        findViewById(R.id.btn_quick_2).setOnClickListener(v -> sendText("2", true));

        // Sincronizzazione automatica dell'orologio RTC: invia scelta '3' e timestamp corrente
        findViewById(R.id.btn_quick_3_sync).setOnClickListener(v -> {
            long currentEpoch = System.currentTimeMillis() / 1000L;
            appendTerminalText("[Sincronizzazione automatica RTC: " + currentEpoch + "]\n");
            sendText("3", true);
            mMainHandler.postDelayed(() -> sendText(String.valueOf(currentEpoch), true), 200);
        });

        findViewById(R.id.btn_quick_4).setOnClickListener(v -> sendText("4", true));
        findViewById(R.id.btn_quick_5).setOnClickListener(v -> sendText("5", true));
        findViewById(R.id.btn_quick_6).setOnClickListener(v -> sendText("6", true));
        findViewById(R.id.btn_quick_7).setOnClickListener(v -> sendText("7", true));
        findViewById(R.id.btn_quick_8).setOnClickListener(v -> sendText("8", true));
        findViewById(R.id.btn_quick_9).setOnClickListener(v -> sendText("9", true));
        findViewById(R.id.btn_quick_10).setOnClickListener(v -> sendText("10", true));
        findViewById(R.id.btn_quick_q).setOnClickListener(v -> sendText("Q", true));
        findViewById(R.id.btn_quick_l).setOnClickListener(v -> sendText("L", true));
        findViewById(R.id.btn_quick_none).setOnClickListener(v -> sendText("NONE", true));
        findViewById(R.id.btn_quick_all).setOnClickListener(v -> sendText("ALL", true));
    }

    private synchronized void connectUsb() {
        if (mPort != null) {
            return;
        }

        ProbeTable customTable = UsbSerialProber.getDefaultProbeTable();
        customTable.addProduct(0x303A, 0x1001, CdcAcmSerialDriver.class); // ESP32-C3 / ESP32-C6 USB Serial/JTAG
        UsbSerialProber prober = new UsbSerialProber(customTable);
        List<UsbSerialDriver> availableDrivers = prober.findAllDrivers(mUsbManager);

        if (availableDrivers == null || availableDrivers.isEmpty()) {
            updateStatus("Nessun dispositivo USB rilevato", false);
            appendTerminalText("[Nessun dispositivo USB rilevato. Collega l'ESP32 tramite cavo USB-OTG.]\n");
            return;
        }

        UsbSerialDriver driver = availableDrivers.get(0);
        UsbDevice usbDevice = driver.getDevice();

        if (!mUsbManager.hasPermission(usbDevice)) {
            updateStatus("Richiesta autorizzazione USB...", false);
            appendTerminalText("[Richiesta autorizzazione USB in corso...]\n");
            Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0, intent, flags);
            mUsbManager.requestPermission(usbDevice, permissionIntent);
            return;
        }

        try {
            mConnection = mUsbManager.openDevice(usbDevice);
            if (mConnection == null) {
                updateStatus("Errore apertura connessione USB", false);
                appendTerminalText("[ERRORE: Impossibile aprire la connessione USB.]\n");
                return;
            }

            mPort = driver.getPorts().get(0);
            mPort.open(mConnection);
            mPort.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

            // Per ESP32 CDC e USB-JTAG (XIAO C6): asserire DTR affinché il controller invii l'output seriale
            try {
                mPort.setDTR(true);
                mPort.setRTS(false);
            } catch (Exception ignored) {}

            updateStatus("Connesso: " + usbDevice.getProductName() + " (115200 baud)", true);
            appendTerminalText("\n--- [CONNESSO (115200 baud) - Premi '[conf] Menu' per avviare il menu CLI] ---\n\n");

            startReaderThread();

        } catch (IOException e) {
            updateStatus("Errore connessione: " + e.getMessage(), false);
            appendTerminalText("[ERRORE IO: " + e.getMessage() + "]\n");
            disconnectUsb();
        }
    }

    private synchronized void disconnectUsb() {
        mIsRunning = false;
        if (mReadThread != null) {
            mReadThread.interrupt();
            mReadThread = null;
        }
        if (mPort != null) {
            try {
                mPort.close();
            } catch (Exception ignored) {}
            mPort = null;
        }
        if (mConnection != null) {
            try {
                mConnection.close();
            } catch (Exception ignored) {}
            mConnection = null;
        }
    }

    private void startReaderThread() {
        mIsRunning = true;
        mReadThread = new Thread(() -> {
            byte[] buffer = new byte[1024];
            while (mIsRunning && mPort != null) {
                try {
                    int len = mPort.read(buffer, 200);
                    if (len > 0) {
                        String incoming = new String(buffer, 0, len, StandardCharsets.UTF_8);
                        mMainHandler.post(() -> appendTerminalText(incoming));
                    }
                } catch (IOException e) {
                    if (mIsRunning) {
                        mMainHandler.post(() -> {
                            appendTerminalText("\n[Connessione seriale interrotta: " + e.getMessage() + "]\n");
                            updateStatus("Connessione interrotta", false);
                        });
                    }
                    break;
                }
            }
        }, "SerialConsoleReader");
        mReadThread.start();
    }

    public synchronized void sendText(String text, boolean appendNewline) {
        if (mPort == null) {
            Toast.makeText(this, "Nessun dispositivo USB connesso.", Toast.LENGTH_SHORT).show();
            appendTerminalText("[Nessun dispositivo USB connesso.]\n");
            return;
        }

        String toSend = appendNewline ? (text + "\r\n") : text;
        try {
            byte[] data = toSend.getBytes(StandardCharsets.UTF_8);
            mPort.write(data, 1000);
            appendTerminalText("> " + text + "\n");
        } catch (IOException e) {
            appendTerminalText("[Errore invio: " + e.getMessage() + "]\n");
            Toast.makeText(this, "Errore invio: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void resetEspHardware() {
        if (mPort == null) {
            connectUsb();
            return;
        }
        appendTerminalText("[Invio segnale Hardware Reset all'ESP32...]\n");
        new Thread(() -> {
            try {
                // Sequenza standard di reset su linee DTR/RTS
                mPort.setDTR(false);
                mPort.setRTS(true);
                Thread.sleep(120);
                mPort.setDTR(true);
                mPort.setRTS(false);
            } catch (Exception e) {
                mMainHandler.post(() -> appendTerminalText("[Errore Reset: " + e.getMessage() + "]\n"));
            }
        }).start();
    }

    private void updateStatus(String status, boolean connected) {
        tvStatus.setText(status);
        tvStatus.setTextColor(connected ? 0xFF00E676 : 0xFFE0E0E0);
    }

    private void appendTerminalText(String text) {
        CharSequence current = tvTerminal.getText();
        if (current.length() > MAX_BUFFER_CHARS) {
            // Tronca i primi caratteri per non saturare la memoria
            int cutIndex = current.length() - (MAX_BUFFER_CHARS / 2);
            tvTerminal.setText(current.subSequence(cutIndex, current.length()));
        }
        tvTerminal.append(text);
        svTerminal.post(() -> svTerminal.fullScroll(ScrollView.FOCUS_DOWN));
    }
}
