package it.geniola.apritisedano;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.util.Log;

import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
import com.hoho.android.usbserial.driver.Ch34xSerialDriver;
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver;
import com.hoho.android.usbserial.driver.FtdiSerialDriver;
import com.hoho.android.usbserial.driver.ProbeTable;
import com.hoho.android.usbserial.driver.ProlificSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Flasher seriale USB per ESP32, ESP32-WROOM ed ESP32-C6 su Android via cavo USB OTG.
 * Implementa il protocollo ROM Bootloader Espressif (SLIP framing, SYNC auto-baud, SPI ATTACH, FLASH BEGIN/DATA/END).
 */
public class Esp32SerialFlasher {
    private static final String TAG = "Esp32SerialFlasher";

    // Opcodes Protocollo Serial ROM Espressif
    private static final byte ESP_FLASH_BEGIN = 0x02;
    private static final byte ESP_FLASH_DATA  = 0x03;
    private static final byte ESP_FLASH_END   = 0x04;
    private static final byte ESP_MEM_BEGIN   = 0x05;
    private static final byte ESP_MEM_END     = 0x06;
    private static final byte ESP_MEM_DATA    = 0x07;
    private static final byte ESP_SYNC        = 0x08;
    private static final byte ESP_WRITE_REG   = 0x09;
    private static final byte ESP_READ_REG    = 0x0A;
    private static final byte ESP_SPI_ATTACH  = 0x0D;

    // SLIP Delimitatori (RFC 1055)
    private static final byte SLIP_END     = (byte) 0xC0;
    private static final byte SLIP_ESC     = (byte) 0xDB;
    private static final byte SLIP_ESC_END = (byte) 0xDC;
    private static final byte SLIP_ESC_ESC = (byte) 0xDD;

    private static final int DEFAULT_BAUD_RATE = 115200;
    private static final int FLASH_BLOCK_SIZE  = 1024; // Dimensione blocco compatibile universale con tutti i ROM loader

    private volatile boolean mCancelled = false;

    public interface Callback {
        void onStatus(String status);
        void onProgress(int percent, int writtenBytes, int totalBytes, long bytesPerSec);
        void onSuccess();
        void onError(String errorMessage);
    }

    public void cancel() {
        mCancelled = true;
    }

    public boolean isCancelled() {
        return mCancelled;
    }

    /**
     * Rileva tutti i driver seriali USB compatibili con ESP32, inclusi CP210x, CH34x, FTDI e USB-CDC/JTAG (ESP32-C6/S3).
     */
    public static List<UsbSerialDriver> getAvailableDrivers(UsbManager usbManager) {
        ProbeTable customTable = UsbSerialProber.getDefaultProbeTable();

        // Aggiungi VID/PID specifici Espressif nativi (USB-JTAG/Serial su ESP32-C6, ESP32-S3, ESP32-S2)
        customTable.addProduct(0x303A, 0x1001, CdcAcmSerialDriver.class); // ESP32-C6 / ESP32-S3 USB Serial/JTAG
        customTable.addProduct(0x303A, 0x0002, CdcAcmSerialDriver.class); // ESP32-S2 USB CDC
        customTable.addProduct(0x303A, 0x1002, CdcAcmSerialDriver.class); // ESP32-C3 USB Serial/JTAG
        customTable.addProduct(0x1A86, 0x55D4, Ch34xSerialDriver.class);  // CH343
        customTable.addProduct(0x1A86, 0x55D3, Ch34xSerialDriver.class);  // CH342

        UsbSerialProber prober = new UsbSerialProber(customTable);
        return prober.findAllDrivers(usbManager);
    }

    /**
     * Descrizione leggibile del dispositivo USB rilevato.
     */
    public static String getDeviceDescription(UsbSerialDriver driver) {
        if (driver == null || driver.getDevice() == null) return "Dispositivo sconosciuto";
        UsbDevice dev = driver.getDevice();
        String name = dev.getProductName();
        if (name == null || name.trim().isEmpty()) {
            name = dev.getManufacturerName();
        }
        if (name == null || name.trim().isEmpty()) {
            int vid = dev.getVendorId();
            int pid = dev.getProductId();
            if (vid == 0x10C4) name = "Silicon Labs CP210x (ESP32 DevKit)";
            else if (vid == 0x1A86) name = "WCH CH340/CH343 (ESP32 DevKit)";
            else if (vid == 0x0403) name = "FTDI Serial (ESP32)";
            else if (vid == 0x303A) name = "Espressif USB-JTAG/CDC (XIAO ESP32-C6)";
            else name = String.format("Dispositivo USB (VID: 0x%04X, PID: 0x%04X)", vid, pid);
        }
        return name;
    }

    /**
     * Esegue il flashing completo del firmware in background.
     * Da eseguire su un thread separato (non su UI thread).
     */
    public void flashFirmware(UsbManager usbManager,
                              UsbSerialDriver driver,
                              byte[] firmwareData,
                              int flashOffset,
                              Callback callback) {
        mCancelled = false;

        if (driver == null || driver.getPorts().isEmpty()) {
            callback.onError("Nessuna porta seriale disponibile sul dispositivo selezionato.");
            return;
        }

        if (firmwareData == null || firmwareData.length == 0) {
            callback.onError("Dati del firmware vuoti o non validi.");
            return;
        }

        UsbDevice device = driver.getDevice();
        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            callback.onError("Impossibile aprire la connessione USB. Verifica di aver concesso i permessi USB.");
            return;
        }

        UsbSerialPort port = driver.getPorts().get(0);

        try {
            port.open(connection);
            port.setParameters(DEFAULT_BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            port.setDTR(false);
            port.setRTS(false);

            callback.onStatus("Riavvio ESP32 in modalità bootloader...");
            resetIntoBootloader(port);

            callback.onStatus("Sincronizzazione con il bootloader ROM...");
            syncBootloader(port);

            if (mCancelled) throw new IOException("Flashing annullato dall'utente.");

            callback.onStatus("Configurazione controller SPI Flash...");
            attachSpiFlash(port);

            if (mCancelled) throw new IOException("Flashing annullato dall'utente.");

            int totalSize = firmwareData.length;
            int blockCount = (totalSize + FLASH_BLOCK_SIZE - 1) / FLASH_BLOCK_SIZE;
            int eraseSize = ((totalSize + 4095) / 4096) * 4096;

            callback.onStatus(String.format("Cancellazione memoria flash (%d byte a 0x%05X)...", eraseSize, flashOffset));
            flashBegin(port, eraseSize, blockCount, FLASH_BLOCK_SIZE, flashOffset);

            callback.onStatus("Scrittura del firmware in corso...");
            long startTime = System.currentTimeMillis();
            byte[] blockBuffer = new byte[FLASH_BLOCK_SIZE];

            for (int seq = 0; seq < blockCount; seq++) {
                if (mCancelled) throw new IOException("Flashing annullato dall'utente.");

                int offset = seq * FLASH_BLOCK_SIZE;
                int len = Math.min(FLASH_BLOCK_SIZE, totalSize - offset);

                // Riempi blocco (i byte mancanti vengono riempiti con 0xFF come da specifica flash)
                int checksum = 0xEF;
                for (int i = 0; i < FLASH_BLOCK_SIZE; i++) {
                    byte b = (i < len) ? firmwareData[offset + i] : (byte) 0xFF;
                    blockBuffer[i] = b;
                    checksum ^= (b & 0xFF);
                }

                flashDataBlock(port, blockBuffer, seq, checksum);

                int written = Math.min(totalSize, (seq + 1) * FLASH_BLOCK_SIZE);
                int percent = (int) (((long) (seq + 1) * 100) / blockCount);
                long elapsed = System.currentTimeMillis() - startTime;
                long speed = (elapsed > 0) ? (written * 1000L / elapsed) : 0;

                callback.onProgress(percent, written, totalSize, speed);
            }

            callback.onStatus("Finalizzazione e chiusura sessione flash...");
            flashEnd(port, false); // reboot = false per consentire reset hardware controllato

            callback.onStatus("Riavvio hardware dell'ESP32 sul nuovo firmware...");
            hardReset(port);

            callback.onSuccess();

        } catch (Exception e) {
            Log.e(TAG, "Errore durante il flashing seriale", e);
            callback.onError(e.getMessage() != null ? e.getMessage() : e.toString());
        } finally {
            try {
                port.close();
            } catch (Exception ignored) {}
            try {
                connection.close();
            } catch (Exception ignored) {}
        }
    }

    /**
     * Esegue la sequenza di reset hardware (toggle DTR/RTS) per portare l'ESP32 in modalità bootloader download.
     */
    private void resetIntoBootloader(UsbSerialPort port) throws IOException, InterruptedException {
        // Sequenza standard transistor circuit: EN = RTS, IO0 = DTR
        port.setDTR(false);
        port.setRTS(true); // EN = LOW (Chip in reset)
        Thread.sleep(100);

        port.setDTR(true);  // IO0 = LOW (Bootloader strap attivo)
        port.setRTS(false); // EN = HIGH (Chip rilasciato dal reset con IO0 LOW)
        Thread.sleep(80);

        port.setDTR(false); // Rilascia IO0
        Thread.sleep(50);

        // Svuota buffer di ricezione da eventuali messaggi spuri di boot
        byte[] drain = new byte[1024];
        while (port.read(drain, 50) > 0) {
            // svuota
        }
    }

    /**
     * Esegue il comando ESP_SYNC inviando il pattern auto-baud finché il chip non risponde.
     */
    private void syncBootloader(UsbSerialPort port) throws IOException, InterruptedException {
        byte[] syncPayload = new byte[36];
        syncPayload[0] = 0x07;
        syncPayload[1] = 0x07;
        syncPayload[2] = 0x12;
        syncPayload[3] = 0x20;
        for (int i = 4; i < 36; i++) {
            syncPayload[i] = 0x55;
        }

        byte[] syncFrame = buildSlipPacket(ESP_SYNC, syncPayload, 0);

        boolean synced = false;
        int maxAttempts = 15;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (mCancelled) throw new IOException("Operazione annullata.");

            port.write(syncFrame, 200);

            // Leggi risposta
            try {
                SlipResponse resp = readSlipResponse(port, ESP_SYNC, 250);
                if (resp != null && resp.status == 0) {
                    synced = true;
                    // Il bootloader invia spesso risposte di sync aggiuntive: leggiamole per pulire il buffer
                    drainResponses(port, 100);
                    break;
                }
            } catch (Exception ignored) {
                // Riprova
            }
            Thread.sleep(80);
        }

        if (!synced) {
            throw new IOException("Impossibile sincronizzare con l'ESP32. Se necessario, tieni premuto il tasto BOOT sulla scheda e riprova.");
        }
    }

    /**
     * Configura il controller SPI Flash inviando ESP_SPI_ATTACH.
     */
    private void attachSpiFlash(UsbSerialPort port) throws IOException {
        byte[] attachPayload = new byte[8]; // 8 zero bytes per pin SPI predefiniti
        sendCommand(port, "SPI Attach", ESP_SPI_ATTACH, attachPayload, 0, 1500);
    }

    /**
     * Avvia la sessione di scrittura e cancella la regione di memoria necessaria.
     */
    private void flashBegin(UsbSerialPort port, int eraseSize, int blockCount, int blockSize, int offset) throws IOException {
        byte[] payload = new byte[16];
        writeUint32LE(payload, 0, eraseSize);
        writeUint32LE(payload, 4, blockCount);
        writeUint32LE(payload, 8, blockSize);
        writeUint32LE(payload, 12, offset);

        // La cancellazione flash richiede più tempo (fino a 25 secondi)
        sendCommand(port, "Flash Begin", ESP_FLASH_BEGIN, payload, 0, 25000);
    }

    /**
     * Invia un blocco dati firmware con checksum XOR.
     */
    private void flashDataBlock(UsbSerialPort port, byte[] blockData, int seq, int checksum) throws IOException {
        byte[] payload = new byte[16 + blockData.length];
        writeUint32LE(payload, 0, blockData.length);
        writeUint32LE(payload, 4, seq);
        writeUint32LE(payload, 8, 0);
        writeUint32LE(payload, 12, 0);
        System.arraycopy(blockData, 0, payload, 16, blockData.length);

        sendCommand(port, "Flash Data Block " + seq, ESP_FLASH_DATA, payload, checksum, 3000);
    }

    /**
     * Termina la sessione di flash.
     */
    private void flashEnd(UsbSerialPort port, boolean reboot) throws IOException {
        byte[] payload = new byte[4];
        writeUint32LE(payload, 0, reboot ? 0 : 1);
        try {
            sendCommand(port, "Flash End", ESP_FLASH_END, payload, 0, 1500);
        } catch (Exception e) {
            // Alcuni ROM bootloader resettano immediatamente su flash end: non è un errore fatale
            Log.w(TAG, "Avviso su Flash End: " + e.getMessage());
        }
    }

    /**
     * Esegue un hard reset per avviare il firmware appena caricato.
     */
    private void hardReset(UsbSerialPort port) {
        try {
            port.setDTR(false);
            port.setRTS(true);
            Thread.sleep(100);
            port.setRTS(false);
        } catch (Exception ignored) {}
    }

    private void sendCommand(UsbSerialPort port, String opName, byte opcode, byte[] payload, int checksum, int timeoutMs) throws IOException {
        byte[] frame = buildSlipPacket(opcode, payload, checksum);
        port.write(frame, Math.min(timeoutMs, 2000));

        SlipResponse resp = readSlipResponse(port, opcode, timeoutMs);
        if (resp == null) {
            throw new IOException("Nessuna risposta dal dispositivo per il comando: " + opName);
        }
        if (resp.status != 0) {
            throw new IOException(String.format("Errore restituito dall'ESP32 durante '%s' (Codice errore: 0x%02X)", opName, resp.error));
        }
    }

    private void drainResponses(UsbSerialPort port, int timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        byte[] buf = new byte[256];
        while (System.currentTimeMillis() < end) {
            try {
                if (port.read(buf, 20) <= 0) break;
            } catch (Exception e) {
                break;
            }
        }
    }

    /**
     * Costruisce un pacchetto SLIP con l'header standard Espressif (8 byte) + payload.
     * Header layout:
     * [0] Direction: 0x00 (Request)
     * [1] Opcode: 1 Byte
     * [2..3] Size: 2 Byte LE
     * [4..7] Checksum: 4 Byte LE
     */
    private byte[] buildSlipPacket(byte opcode, byte[] payload, int checksum) {
        int payloadLen = (payload != null) ? payload.length : 0;
        byte[] raw = new byte[8 + payloadLen];
        raw[0] = 0x00; // Request
        raw[1] = opcode;
        raw[2] = (byte) (payloadLen & 0xFF);
        raw[3] = (byte) ((payloadLen >> 8) & 0xFF);
        writeUint32LE(raw, 4, checksum);
        if (payloadLen > 0) {
            System.arraycopy(payload, 0, raw, 8, payloadLen);
        }

        // SLIP Escape
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length + 16);
        out.write(SLIP_END);
        for (byte b : raw) {
            if (b == SLIP_END) {
                out.write(SLIP_ESC);
                out.write(SLIP_ESC_END);
            } else if (b == SLIP_ESC) {
                out.write(SLIP_ESC);
                out.write(SLIP_ESC_ESC);
            } else {
                out.write(b);
            }
        }
        out.write(SLIP_END);
        return out.toByteArray();
    }

    private static class SlipResponse {
        byte opcode;
        int status;
        int error;
    }

    /**
     * Legge e decodifica un frame SLIP dalla porta seriale gestendo framing, escaping e timeout.
     */
    private SlipResponse readSlipResponse(UsbSerialPort port, byte expectedOpcode, int timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        ByteArrayOutputStream packetStream = new ByteArrayOutputStream();
        boolean inFrame = false;
        boolean escaped = false;
        byte[] readBuf = new byte[256];

        while (System.currentTimeMillis() < deadline) {
            int readLen = port.read(readBuf, 50);
            if (readLen > 0) {
                for (int i = 0; i < readLen; i++) {
                    byte b = readBuf[i];

                    if (!inFrame) {
                        if (b == SLIP_END) {
                            inFrame = true;
                            packetStream.reset();
                            escaped = false;
                        }
                    } else {
                        if (b == SLIP_END) {
                            // Fine pacchetto SLIP
                            if (packetStream.size() >= 8) {
                                byte[] packet = packetStream.toByteArray();
                                byte respDir = packet[0];
                                byte respOpcode = packet[1];

                                if (respDir == 0x01 && (expectedOpcode == 0 || respOpcode == expectedOpcode)) {
                                    SlipResponse sr = new SlipResponse();
                                    sr.opcode = respOpcode;
                                    // Negli ultimi 2 byte del payload il bootloader restituisce [status, error]
                                    if (packet.length >= 10) {
                                        sr.status = packet[packet.length - 2] & 0xFF;
                                        sr.error = packet[packet.length - 1] & 0xFF;
                                    } else {
                                        sr.status = 0;
                                        sr.error = 0;
                                    }
                                    return sr;
                                }
                            }
                            // Pacchetto vuoto o non corrispondente, reset per il prossimo
                            packetStream.reset();
                            escaped = false;
                        } else if (escaped) {
                            if (b == SLIP_ESC_END) {
                                packetStream.write(SLIP_END);
                            } else if (b == SLIP_ESC_ESC) {
                                packetStream.write(SLIP_ESC);
                            } else {
                                packetStream.write(b);
                            }
                            escaped = false;
                        } else if (b == SLIP_ESC) {
                            escaped = true;
                        } else {
                            packetStream.write(b);
                        }
                    }
                }
            }
        }
        return null;
    }

    private static void writeUint32LE(byte[] dest, int offset, long val) {
        dest[offset]     = (byte) (val & 0xFF);
        dest[offset + 1] = (byte) ((val >> 8) & 0xFF);
        dest[offset + 2] = (byte) ((val >> 16) & 0xFF);
        dest[offset + 3] = (byte) ((val >> 24) & 0xFF);
    }
}
