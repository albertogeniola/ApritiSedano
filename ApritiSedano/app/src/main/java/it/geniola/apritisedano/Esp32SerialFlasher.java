package it.geniola.apritisedano;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.util.Log;

import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
import com.hoho.android.usbserial.driver.Ch34xSerialDriver;
import com.hoho.android.usbserial.driver.ProbeTable;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

/**
 * Flasher seriale USB per ESP32, ESP32-WROOM ed ESP32-C6 su Android via cavo USB OTG.
 * Implementa il protocollo ROM Bootloader Espressif (SLIP framing, SYNC auto-baud, rilevamento chip,
 * SPI ATTACH, SPI SET PARAMS, FLASH BEGIN/DATA/END e verifica MD5 finale), seguendo il riferimento esptool.
 */
public class Esp32SerialFlasher {
    private static final String TAG = "Esp32SerialFlasher";

    // Opcodes Protocollo Serial ROM Espressif
    private static final byte ESP_FLASH_BEGIN     = 0x02;
    private static final byte ESP_FLASH_DATA      = 0x03;
    private static final byte ESP_FLASH_END       = 0x04;
    private static final byte ESP_SYNC            = 0x08;
    private static final byte ESP_READ_REG        = 0x0A;
    private static final byte ESP_SPI_SET_PARAMS  = 0x0B;
    private static final byte ESP_SPI_ATTACH      = 0x0D;
    private static final byte ESP_SPI_FLASH_MD5   = 0x13;

    // SLIP Delimitatori (RFC 1055)
    private static final byte SLIP_END     = (byte) 0xC0;
    private static final byte SLIP_ESC     = (byte) 0xDB;
    private static final byte SLIP_ESC_END = (byte) 0xDC;
    private static final byte SLIP_ESC_ESC = (byte) 0xDD;

    private static final int DEFAULT_BAUD_RATE = 115200;
    private static final int FLASH_BLOCK_SIZE  = 0x400; // FLASH_WRITE_SIZE del ROM loader
    private static final int FLASH_SECTOR_SIZE = 0x1000;
    private static final int FLASH_TOTAL_SIZE  = 4 * 1024 * 1024; // XIAO ESP32-C6 ed ESP32-WROOM-32: 4 MB

    // Registro "magic" per il rilevamento del chip (come esptool)
    private static final int CHIP_DETECT_MAGIC_REG_ADDR = 0x40001000;
    private static final int MAGIC_ESP32    = 0x00F01D83;
    private static final int MAGIC_ESP32C6  = 0x2CE0806F;
    private static final int MAGIC_ESP8266  = 0xFFF0C101;

    // Chip ID nell'header dell'immagine firmware (byte 12..13)
    private static final int IMAGE_CHIP_ID_ESP32   = 0x0000;
    private static final int IMAGE_CHIP_ID_ESP32C6 = 0x000D;

    // Timeout ROM (esptool: ERASE_REGION_TIMEOUT_PER_MB = 30, MD5_TIMEOUT_PER_MB = 8)
    private static final int ERASE_TIMEOUT_MS_PER_MB = 30000;
    private static final int MD5_TIMEOUT_MS_PER_MB   = 8000;

    private volatile boolean mCancelled = false;

    public interface Callback {
        void onStatus(String status);
        void onProgress(int percent, int writtenBytes, int totalBytes, long bytesPerSec);
        void onSuccess();
        void onError(String errorMessage);
    }

    /** Informazioni sul chip rilevato tramite il ROM bootloader. */
    private static class ChipInfo {
        String name;
        boolean isEsp32Classic;   // ESP32 originale: FLASH_BEGIN a 4 parametri, bootloader a 0x1000
        int bootloaderOffset;     // 0x1000 per ESP32, 0x0 per ESP32-C6 e chip successivi
        int expectedImageChipId;  // -1 se sconosciuto (validazione saltata)
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
        boolean isNativeUsbJtag = (device.getVendorId() == 0x303A);

        try {
            port.open(connection);
            port.setParameters(DEFAULT_BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            port.setDTR(false);
            port.setRTS(false);

            callback.onStatus("Riavvio ESP32 in modalità bootloader...");
            if (isNativeUsbJtag) {
                resetIntoBootloaderUsbJtag(port);
            } else {
                resetIntoBootloaderClassic(port);
            }

            callback.onStatus("Sincronizzazione con il bootloader ROM...");
            syncBootloader(port);
            checkCancelled();

            // 1. Rilevamento chip: determina formato comandi, offset bootloader e chip ID atteso
            ChipInfo chip = detectChip(port);
            callback.onStatus("Chip rilevato: " + chip.name);
            checkCancelled();

            // 2. Prepara e valida l'immagine PRIMA di toccare la flash
            int writeLen = trimTrailingErasedBytes(firmwareData);
            validateImage(firmwareData, writeLen, flashOffset, chip);
            if (writeLen < firmwareData.length) {
                callback.onStatus(String.format(Locale.US,
                        "Ottimizzazione: %,d byte finali vuoti (0xFF) non verranno trasmessi.",
                        firmwareData.length - writeLen));
            }

            callback.onStatus("Configurazione controller SPI Flash...");
            attachSpiFlash(port);
            setFlashParameters(port);
            checkCancelled();

            // 3. Erase + scrittura
            int blockCount = (writeLen + FLASH_BLOCK_SIZE - 1) / FLASH_BLOCK_SIZE;
            int eraseSize = ((writeLen + FLASH_SECTOR_SIZE - 1) / FLASH_SECTOR_SIZE) * FLASH_SECTOR_SIZE;

            callback.onStatus(String.format(Locale.US, "Cancellazione memoria flash (%,d byte a 0x%05X)...", eraseSize, flashOffset));
            flashBegin(port, chip, eraseSize, blockCount, flashOffset);

            callback.onStatus("Scrittura del firmware in corso...");
            long startTime = System.currentTimeMillis();
            byte[] blockBuffer = new byte[FLASH_BLOCK_SIZE];

            for (int seq = 0; seq < blockCount; seq++) {
                checkCancelled();

                int offset = seq * FLASH_BLOCK_SIZE;
                int len = Math.min(FLASH_BLOCK_SIZE, writeLen - offset);

                // Riempi blocco (i byte mancanti vengono riempiti con 0xFF come da specifica flash)
                int checksum = 0xEF;
                for (int i = 0; i < FLASH_BLOCK_SIZE; i++) {
                    byte b = (i < len) ? firmwareData[offset + i] : (byte) 0xFF;
                    blockBuffer[i] = b;
                    checksum ^= (b & 0xFF);
                }

                flashDataBlock(port, blockBuffer, seq, checksum);

                int written = Math.min(writeLen, (seq + 1) * FLASH_BLOCK_SIZE);
                int percent = (int) (((long) (seq + 1) * 100) / blockCount);
                long elapsed = System.currentTimeMillis() - startTime;
                long speed = (elapsed > 0) ? (written * 1000L / elapsed) : 0;

                callback.onProgress(percent, written, writeLen, speed);
            }

            // 4. Verifica MD5 del contenuto effettivamente scritto in flash
            callback.onStatus("Verifica integrità (MD5) del firmware scritto...");
            verifyFlashMd5(port, firmwareData, writeLen, flashOffset);
            callback.onStatus("Verifica MD5 superata.");

            callback.onStatus("Finalizzazione e chiusura sessione flash...");
            flashEnd(port);

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

    private void checkCancelled() throws IOException {
        if (mCancelled) throw new IOException("Flashing annullato dall'utente.");
    }

    /**
     * Sequenza di reset "classica" (circuito a due transistor su DevKit con CP210x/CH340: EN = RTS, IO0 = DTR).
     */
    private void resetIntoBootloaderClassic(UsbSerialPort port) throws IOException, InterruptedException {
        port.setDTR(false);
        port.setRTS(true);  // EN = LOW (Chip in reset)
        Thread.sleep(100);

        port.setDTR(true);  // IO0 = LOW (Bootloader strap attivo)
        port.setRTS(false); // EN = HIGH (Chip rilasciato dal reset con IO0 LOW)
        Thread.sleep(80);

        port.setDTR(false); // Rilascia IO0
        Thread.sleep(50);

        drainInput(port, 200);
    }

    /**
     * Sequenza di reset per periferica nativa USB-Serial/JTAG (ESP32-C6/C3/S3), come esptool "USBJTAGSerialReset".
     */
    private void resetIntoBootloaderUsbJtag(UsbSerialPort port) throws IOException, InterruptedException {
        port.setRTS(false);
        port.setDTR(false); // Idle
        Thread.sleep(100);

        port.setDTR(true);  // Set IO0
        port.setRTS(false);
        Thread.sleep(100);

        port.setRTS(true);  // Reset (transizione via (1,1) invece di (0,0))
        port.setDTR(false);
        port.setRTS(true);
        Thread.sleep(100);

        port.setDTR(false);
        port.setRTS(false); // Chip fuori dal reset
        Thread.sleep(150);

        drainInput(port, 200);
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

        for (int attempt = 1; attempt <= 15; attempt++) {
            checkCancelled();
            port.write(syncFrame, 200);
            try {
                Response resp = readResponse(port, ESP_SYNC, 250);
                if (resp != null && resp.data.length >= 2 && resp.data[0] == 0) {
                    // Il bootloader invia diverse risposte di sync aggiuntive: svuotiamo il buffer
                    drainInput(port, 150);
                    return;
                }
            } catch (Exception ignored) {
                // Riprova
            }
            Thread.sleep(80);
        }

        throw new IOException("Impossibile sincronizzare con l'ESP32. Se necessario, tieni premuto il tasto BOOT sulla scheda e riprova.");
    }

    /**
     * Rileva il tipo di chip leggendo il registro "magic" (come esptool).
     */
    private ChipInfo detectChip(UsbSerialPort port) throws IOException {
        byte[] payload = new byte[4];
        writeUint32LE(payload, 0, CHIP_DETECT_MAGIC_REG_ADDR);
        Response resp = sendCommand(port, "Lettura registro identificativo chip", ESP_READ_REG, payload, 0, 0, 3000);
        int magic = resp.value;

        ChipInfo info = new ChipInfo();
        if (magic == MAGIC_ESP32) {
            info.name = "ESP32 (ESP32-WROOM-32)";
            info.isEsp32Classic = true;
            info.bootloaderOffset = 0x1000;
            info.expectedImageChipId = IMAGE_CHIP_ID_ESP32;
        } else if (magic == MAGIC_ESP8266) {
            throw new IOException("Rilevato un ESP8266: chip non supportato da questo firmware.");
        } else {
            info.isEsp32Classic = false;
            info.bootloaderOffset = 0x0;
            if (magic == MAGIC_ESP32C6) {
                info.name = "ESP32-C6 (XIAO ESP32-C6)";
                info.expectedImageChipId = IMAGE_CHIP_ID_ESP32C6;
            } else {
                info.name = String.format(Locale.US, "ESP32 serie recente (magic 0x%08X)", magic);
                info.expectedImageChipId = -1;
            }
        }
        return info;
    }

    /**
     * Rimuove i byte 0xFF finali (flash già vuota dopo l'erase) allineando a 4 KB, per velocizzare le immagini "merged".
     */
    private static int trimTrailingErasedBytes(byte[] data) {
        int end = data.length;
        while (end > 0 && data[end - 1] == (byte) 0xFF) {
            end--;
        }
        if (end == 0) return data.length;
        int aligned = ((end + FLASH_SECTOR_SIZE - 1) / FLASH_SECTOR_SIZE) * FLASH_SECTOR_SIZE;
        return Math.min(aligned, data.length);
    }

    /**
     * Valida che l'immagine sia coerente con offset e chip rilevato, per non scrivere mai firmware incompatibili.
     */
    private void validateImage(byte[] data, int writeLen, int flashOffset, ChipInfo chip) throws IOException {
        if ((long) flashOffset + writeLen > FLASH_TOTAL_SIZE) {
            throw new IOException(String.format(Locale.US,
                    "L'immagine (%,d byte a 0x%X) supera la dimensione della flash (4 MB).", writeLen, flashOffset));
        }
        if (flashOffset % FLASH_SECTOR_SIZE != 0) {
            throw new IOException("L'offset di scrittura deve essere allineato a 4 KB (0x1000).");
        }

        int headerPos;
        if (flashOffset == 0) {
            // Immagine completa "merged": deve contenere il bootloader all'offset corretto per questo chip
            headerPos = chip.bootloaderOffset;
        } else if (flashOffset == chip.bootloaderOffset) {
            headerPos = 0; // solo bootloader
        } else {
            headerPos = 0; // immagine applicativa (es. 0x10000)
        }

        if (data.length < headerPos + 16 || (data[headerPos] & 0xFF) != 0xE9) {
            if (flashOffset == 0) {
                throw new IOException(String.format(Locale.US,
                        "Immagine non compatibile con %s: bootloader non trovato all'offset 0x%X dell'immagine completa.\n" +
                                "Probabilmente stai usando un'immagine creata per un altro modello di scheda.",
                        chip.name, headerPos));
            }
            throw new IOException("Il file non è un'immagine firmware ESP32 valida (magic byte 0xE9 assente).");
        }

        int imageChipId = (data[headerPos + 12] & 0xFF) | ((data[headerPos + 13] & 0xFF) << 8);
        if (chip.expectedImageChipId >= 0 && imageChipId != chip.expectedImageChipId) {
            throw new IOException(String.format(Locale.US,
                    "Firmware incompatibile: l'immagine è compilata per un altro chip (chip ID %d), " +
                            "ma la scheda collegata è %s (chip ID %d).",
                    imageChipId, chip.name, chip.expectedImageChipId));
        }
    }

    /**
     * Configura il controller SPI Flash inviando ESP_SPI_ATTACH (pin di default).
     */
    private void attachSpiFlash(UsbSerialPort port) throws IOException {
        byte[] attachPayload = new byte[8]; // hspi_arg = 0 (pin di default) + is_legacy = 0 + 3 byte riservati
        sendCommand(port, "SPI Attach", ESP_SPI_ATTACH, attachPayload, 0, 0, 3000);
    }

    /**
     * Comunica al ROM la geometria della flash (4 MB), necessario per scrivere oltre i 2 MB predefiniti.
     */
    private void setFlashParameters(UsbSerialPort port) throws IOException {
        byte[] payload = new byte[24];
        writeUint32LE(payload, 0, 0);                 // fl_id
        writeUint32LE(payload, 4, FLASH_TOTAL_SIZE);  // total_size
        writeUint32LE(payload, 8, 64 * 1024);         // block_size
        writeUint32LE(payload, 12, FLASH_SECTOR_SIZE);// sector_size
        writeUint32LE(payload, 16, 256);              // page_size
        writeUint32LE(payload, 20, 0xFFFF);           // status_mask
        sendCommand(port, "Impostazione parametri flash", ESP_SPI_SET_PARAMS, payload, 0, 0, 3000);
    }

    /**
     * Avvia la sessione di scrittura e cancella la regione di memoria necessaria.
     * Sui chip successivi all'ESP32 (S2/S3/C3/C6) il ROM richiede un 5° parametro "encrypted":
     * se omesso il ROM legge un valore indefinito e può scrivere dati cifrati (immagine illeggibile).
     */
    private void flashBegin(UsbSerialPort port, ChipInfo chip, int eraseSize, int blockCount, int offset) throws IOException {
        byte[] payload = new byte[chip.isEsp32Classic ? 16 : 20];
        writeUint32LE(payload, 0, eraseSize);
        writeUint32LE(payload, 4, blockCount);
        writeUint32LE(payload, 8, FLASH_BLOCK_SIZE);
        writeUint32LE(payload, 12, offset);
        if (!chip.isEsp32Classic) {
            writeUint32LE(payload, 16, 0); // encrypted = false
        }

        // Il ROM esegue l'erase completo prima di rispondere (esptool: 30 s per MB)
        int timeout = timeoutPerMb(ERASE_TIMEOUT_MS_PER_MB, eraseSize);
        sendCommand(port, "Flash Begin (erase)", ESP_FLASH_BEGIN, payload, 0, 0, timeout);
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

        sendCommand(port, "Scrittura blocco " + seq, ESP_FLASH_DATA, payload, checksum, 0, 5000);
    }

    /**
     * Calcola l'MD5 della regione scritta direttamente sul chip e lo confronta con quello atteso.
     */
    private void verifyFlashMd5(UsbSerialPort port, byte[] data, int len, int offset) throws IOException {
        String expected;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(data, 0, len);
            expected = toHex(md.digest());
        } catch (Exception e) {
            throw new IOException("Impossibile calcolare l'MD5 locale: " + e.getMessage());
        }

        byte[] payload = new byte[16];
        writeUint32LE(payload, 0, offset);
        writeUint32LE(payload, 4, len);
        writeUint32LE(payload, 8, 0);
        writeUint32LE(payload, 12, 0);

        int timeout = timeoutPerMb(MD5_TIMEOUT_MS_PER_MB, len);
        // Il ROM loader restituisce l'MD5 come 32 caratteri esadecimali ASCII
        Response resp = sendCommand(port, "Calcolo MD5 flash", ESP_SPI_FLASH_MD5, payload, 0, 32, timeout);

        String actual = new String(resp.data, 0, 32, StandardCharsets.US_ASCII).toLowerCase(Locale.US);
        if (!expected.equals(actual)) {
            throw new IOException("Verifica MD5 fallita: il contenuto scritto in flash non corrisponde al file.\n" +
                    "Atteso: " + expected + "\nLetto:  " + actual + "\nRiprova il flash.");
        }
    }

    /**
     * Termina la sessione di flash restando nel bootloader (il riavvio è gestito da hardReset).
     */
    private void flashEnd(UsbSerialPort port) {
        byte[] payload = new byte[4];
        writeUint32LE(payload, 0, 1); // 1 = resta nel loader
        try {
            sendCommand(port, "Flash End", ESP_FLASH_END, payload, 0, 0, 2000);
        } catch (Exception e) {
            // Non fatale: i dati sono già stati scritti e verificati via MD5
            Log.w(TAG, "Avviso su Flash End: " + e.getMessage());
        }
    }

    /**
     * Esegue un hard reset (impulso su EN via RTS) per avviare il firmware appena caricato.
     */
    private void hardReset(UsbSerialPort port) {
        try {
            port.setDTR(false);
            port.setRTS(true);
            Thread.sleep(100);
            port.setRTS(false);
        } catch (Exception ignored) {}
    }

    // ==========================================
    // TRASPORTO: comandi, risposte, SLIP
    // ==========================================

    private static class Response {
        int value;   // campo "val" dell'header di risposta (es. valore registro per READ_REG)
        byte[] data; // payload della risposta (dati + byte di stato)
    }

    /**
     * Invia un comando e verifica il risultato come esptool.check_command:
     * i byte di stato si trovano subito dopo i dati attesi (respDataLen); il primo byte != 0 indica errore.
     * Funziona sia con ESP32 (4 byte di stato) sia con i chip recenti (2 byte di stato).
     */
    private Response sendCommand(UsbSerialPort port, String opName, byte opcode, byte[] payload,
                                 int checksum, int respDataLen, int timeoutMs) throws IOException {
        byte[] frame = buildSlipPacket(opcode, payload, checksum);
        port.write(frame, 3000);

        Response resp = readResponse(port, opcode, timeoutMs);
        if (resp == null) {
            throw new IOException("Nessuna risposta dal dispositivo per il comando: " + opName);
        }

        if (resp.data.length < respDataLen + 2) {
            if (resp.data.length >= 2 && resp.data[0] != 0) {
                throw new IOException(describeRomError(opName, resp.data[1] & 0xFF));
            }
            throw new IOException("Risposta incompleta dal dispositivo per il comando: " + opName);
        }

        int status = resp.data[respDataLen] & 0xFF;
        int error = resp.data[respDataLen + 1] & 0xFF;
        if (status != 0) {
            throw new IOException(describeRomError(opName, error));
        }
        return resp;
    }

    private static String describeRomError(String opName, int error) {
        String reason;
        switch (error) {
            case 0x05: reason = "messaggio ricevuto non valido"; break;
            case 0x06: reason = "impossibile eseguire il comando"; break;
            case 0x07: reason = "CRC/checksum non valido"; break;
            case 0x08: reason = "errore di scrittura flash"; break;
            case 0x09: reason = "errore di lettura flash"; break;
            case 0x0A: reason = "lunghezza di lettura flash non valida"; break;
            case 0x0B: reason = "errore di decompressione"; break;
            default:   reason = "errore sconosciuto"; break;
        }
        return String.format(Locale.US, "Errore restituito dall'ESP32 durante '%s': %s (codice 0x%02X)", opName, reason, error);
    }

    private void drainInput(UsbSerialPort port, int timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        byte[] buf = new byte[512];
        while (System.currentTimeMillis() < end) {
            try {
                if (port.read(buf, 30) <= 0) break;
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

    /**
     * Legge e decodifica frame SLIP fino a trovare la risposta (direction = 0x01) al comando atteso.
     * Header risposta: [0] 0x01, [1] opcode, [2..3] size LE, [4..7] value LE, [8..] data (size byte).
     */
    private Response readResponse(UsbSerialPort port, byte expectedOpcode, int timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        ByteArrayOutputStream packetStream = new ByteArrayOutputStream();
        boolean inFrame = false;
        boolean escaped = false;
        byte[] readBuf = new byte[512];

        while (System.currentTimeMillis() < deadline) {
            int readLen = port.read(readBuf, 50);
            for (int i = 0; i < readLen; i++) {
                byte b = readBuf[i];

                if (!inFrame) {
                    if (b == SLIP_END) {
                        inFrame = true;
                        packetStream.reset();
                        escaped = false;
                    }
                    continue;
                }

                if (b == SLIP_END) {
                    if (packetStream.size() >= 8) {
                        byte[] packet = packetStream.toByteArray();
                        if (packet[0] == 0x01 && packet[1] == expectedOpcode) {
                            int size = (packet[2] & 0xFF) | ((packet[3] & 0xFF) << 8);
                            int available = Math.min(size, packet.length - 8);
                            Response r = new Response();
                            r.value = (packet[4] & 0xFF) | ((packet[5] & 0xFF) << 8)
                                    | ((packet[6] & 0xFF) << 16) | ((packet[7] & 0xFF) << 24);
                            r.data = new byte[Math.max(0, available)];
                            System.arraycopy(packet, 8, r.data, 0, r.data.length);
                            return r;
                        }
                    }
                    // Frame vuoto (doppio 0xC0) o non pertinente: resta "in frame" per il successivo
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
        return null;
    }

    private static int timeoutPerMb(int msPerMb, int sizeBytes) {
        long t = (long) msPerMb * sizeBytes / (1024L * 1024L);
        return (int) Math.max(3000L, t);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.US, "%02x", b & 0xFF));
        }
        return sb.toString();
    }

    private static void writeUint32LE(byte[] dest, int offset, long val) {
        dest[offset]     = (byte) (val & 0xFF);
        dest[offset + 1] = (byte) ((val >> 8) & 0xFF);
        dest[offset + 2] = (byte) ((val >> 16) & 0xFF);
        dest[offset + 3] = (byte) ((val >> 24) & 0xFF);
    }
}
