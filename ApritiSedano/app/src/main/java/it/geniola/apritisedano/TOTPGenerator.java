package it.geniola.apritisedano;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Generatore Rolling-Code (Counter-based HMAC-SHA1), autenticazione Master OTA e payload BLE.
 */
public class TOTPGenerator {

    private static final String HMAC_ALGO = "HmacSHA1";
    public static final int SEED_SIZE = 16; // 128-bit seed

    public static final byte CMD_TRIGGER       = (byte) 0x01;
    public static final byte CMD_ENTER_PAIRING = (byte) 0x02;
    public static final byte CMD_ENTER_OTA     = (byte) 0x03;
    public static final byte CMD_REGISTER_SEED = (byte) 0xFE;
    public static final byte CMD_TIME_SYNC     = (byte) 0xFF;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * Genera un ClientID casuale a 16 bit (1..65534).
     */
    public static int generateRandomClientId() {
        return 1 + SECURE_RANDOM.nextInt(0xFFFE);
    }

    /**
     * Genera un seed crittografico casuale a 128 bit (16 byte) codificato in esadecimale (32 caratteri).
     */
    public static String generateRandomSeedHex() {
        byte[] seed = new byte[SEED_SIZE];
        SECURE_RANDOM.nextBytes(seed);
        StringBuilder sb = new StringBuilder(SEED_SIZE * 2);
        for (byte b : seed) {
            sb.append(String.format(Locale.US, "%02X", b & 0xFF));
        }
        return sb.toString();
    }

    /**
     * Decodifica il seed di 16 byte da stringa Esadecimale (32 char) o Base32.
     */
    public static byte[] decodeSeed16(String secretStr) {
        if (secretStr == null) return null;
        String clean = secretStr.trim().toUpperCase(Locale.US).replaceAll("[= -]", "");
        if (clean.isEmpty()) return null;

        // Se sono esattamente 32 caratteri esadecimali, decodifica come Hex
        if (clean.length() == 32 && clean.matches("^[0-9A-F]{32}$")) {
            byte[] out = new byte[SEED_SIZE];
            for (int i = 0; i < SEED_SIZE; i++) {
                out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
            }
            return out;
        }

        // Fallback: decodifica Base32 e normalizza a 16 byte
        byte[] raw = decodeBase32(clean);
        if (raw.length == 0) return null;
        byte[] seed16 = new byte[SEED_SIZE];
        for (int i = 0; i < SEED_SIZE; i++) {
            seed16[i] = raw[i % raw.length];
        }
        return seed16;
    }

    public static boolean isValidBase32Secret(String secretStr) {
        return decodeSeed16(secretStr) != null;
    }

    private static byte[] decodeBase32(String base32) {
        if (base32 == null) return new byte[0];
        base32 = base32.toUpperCase(Locale.US).replaceAll("[= -]", "");
        int buffer = 0;
        int bitsLeft = 0;
        byte[] result = new byte[base32.length() * 5 / 8];
        int count = 0;
        for (char c : base32.toCharArray()) {
            int val = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c);
            if (val < 0) continue;
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                result[count++] = (byte) (buffer >> (bitsLeft - 8));
                bitsLeft -= 8;
            }
        }
        if (count < result.length) {
            byte[] truncated = new byte[count];
            System.arraycopy(result, 0, truncated, 0, count);
            return truncated;
        }
        return result;
    }

    public static byte[] parseMacBytes(String targetMac) {
        if (targetMac == null) return null;
        String[] parts = targetMac.split(":");
        if (parts.length != 6) return null;
        byte[] macBytes = new byte[6];
        try {
            for (int i = 0; i < 6; i++) {
                macBytes[i] = (byte) Integer.parseInt(parts[i], 16);
            }
            return macBytes;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Calcola il codice Rolling-Code a 32 bit identico a RollingCodeManager::computeRollingCode sull'MCU.
     */
    public static int computeRollingCode(byte[] seed16, byte cmdByte, byte[] mac6, int clientId, long value32) {
        if (seed16 == null || seed16.length != SEED_SIZE || mac6 == null || mac6.length != 6) {
            return 0;
        }
        byte[] msg = new byte[13];
        msg[0] = cmdByte;
        System.arraycopy(mac6, 0, msg, 1, 6);
        msg[7] = (byte) ((clientId >> 8) & 0xFF);
        msg[8] = (byte) (clientId & 0xFF);
        msg[9]  = (byte) ((value32 >> 24) & 0xFF);
        msg[10] = (byte) ((value32 >> 16) & 0xFF);
        msg[11] = (byte) ((value32 >> 8) & 0xFF);
        msg[12] = (byte) (value32 & 0xFF);

        try {
            SecretKeySpec signKey = new SecretKeySpec(seed16, HMAC_ALGO);
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(signKey);
            byte[] hash = mac.doFinal(msg);

            int offset = hash[hash.length - 1] & 0x0F;
            return ((hash[offset] & 0x7F) << 24) |
                   ((hash[offset + 1] & 0xFF) << 16) |
                   ((hash[offset + 2] & 0xFF) << 8) |
                   (hash[offset + 3] & 0xFF);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Errore nel calcolo Rolling Code", e);
        }
    }

    /**
     * Calcola il MasterTag a 32 bit (unsigned in long: 1..0xFFFFFFFFL) identico a
     * RollingCodeManager::computeMasterTag sull'MCU per determinare se questo smartphone è il Master OTA.
     */
    public static long computeMasterTag(String secretStr, String targetMac) {
        byte[] seed16 = decodeSeed16(secretStr);
        byte[] mac6 = parseMacBytes(targetMac);
        if (seed16 == null || mac6 == null) return 0L;

        byte[] msg = new byte[7];
        msg[0] = (byte) 0xA5;
        System.arraycopy(mac6, 0, msg, 1, 6);

        try {
            SecretKeySpec signKey = new SecretKeySpec(seed16, HMAC_ALGO);
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(signKey);
            byte[] hash = mac.doFinal(msg);

            long tag = ((hash[0] & 0xFFL) << 24) |
                       ((hash[1] & 0xFFL) << 16) |
                       ((hash[2] & 0xFFL) << 8)  |
                       (hash[3] & 0xFFL);
            return (tag == 0L) ? 1L : tag;
        } catch (GeneralSecurityException e) {
            return 0L;
        }
    }

    /**
     * Calcola l'hash SHA-256 (32 byte) dell'immagine firmware .bin.
     */
    public static byte[] computeSha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 non disponibile", e);
        }
    }

    /**
     * Costruisce il pacchetto autenticato OP_BEGIN (61 byte) per il canale di controllo BLE GATT OTA:
     * [0x01] [FwSize(4B BE)] [Counter(4B BE)] [SHA256(32B)] [HMAC_SHA1(20B)]
     * dove HMAC_SHA1 = HMAC(MasterSeed, "OTA_V1" || TargetMAC(6B) || FwSize(4B) || Counter(4B) || SHA256(32B)).
     */
    public static byte[] buildOtaBeginPacket(String secretStr, String targetMac, int fwSize, long authorizedCounter, byte[] sha256_32) {
        byte[] seed16 = decodeSeed16(secretStr);
        byte[] mac6 = parseMacBytes(targetMac);
        if (seed16 == null || mac6 == null || sha256_32 == null || sha256_32.length != 32) {
            return null;
        }

        byte[] msg = new byte[52];
        byte[] prefix = "OTA_V1".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(prefix, 0, msg, 0, 6);
        System.arraycopy(mac6, 0, msg, 6, 6);
        msg[12] = (byte) ((fwSize >> 24) & 0xFF);
        msg[13] = (byte) ((fwSize >> 16) & 0xFF);
        msg[14] = (byte) ((fwSize >> 8) & 0xFF);
        msg[15] = (byte) (fwSize & 0xFF);
        msg[16] = (byte) ((authorizedCounter >> 24) & 0xFF);
        msg[17] = (byte) ((authorizedCounter >> 16) & 0xFF);
        msg[18] = (byte) ((authorizedCounter >> 8) & 0xFF);
        msg[19] = (byte) (authorizedCounter & 0xFF);
        System.arraycopy(sha256_32, 0, msg, 20, 32);

        try {
            SecretKeySpec signKey = new SecretKeySpec(seed16, HMAC_ALGO);
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(signKey);
            byte[] hmac20 = mac.doFinal(msg);

            byte[] packet = new byte[61];
            packet[0] = (byte) 0x01; // OTA_OP_BEGIN
            packet[1] = msg[12];
            packet[2] = msg[13];
            packet[3] = msg[14];
            packet[4] = msg[15];
            packet[5] = msg[16];
            packet[6] = msg[17];
            packet[7] = msg[18];
            packet[8] = msg[19];
            System.arraycopy(sha256_32, 0, packet, 9, 32);
            System.arraycopy(hmac20, 0, packet, 41, 20);
            return packet;
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    /**
     * Costruisce il payload Manufacturer Specific Data (17 byte, +2 byte CompanyID = 19 byte) per:
     * - CMD_TRIGGER (0x01)
     * - CMD_ENTER_PAIRING (0x02)
     * - CMD_ENTER_OTA (0x03)
     * - CMD_TIME_SYNC (0xFF)
     */
    public static byte[] buildCommandPayload(byte cmdByte, String targetMac, int clientId, String secretStr, long counterOrTs) {
        byte[] seed16 = decodeSeed16(secretStr);
        byte[] mac6 = parseMacBytes(targetMac);
        if (seed16 == null || mac6 == null) return null;

        int rollingCode = computeRollingCode(seed16, cmdByte, mac6, clientId, counterOrTs);

        byte[] payload = new byte[17];
        payload[0] = cmdByte;
        System.arraycopy(mac6, 0, payload, 1, 6);
        payload[7] = (byte) ((clientId >> 8) & 0xFF);
        payload[8] = (byte) (clientId & 0xFF);
        payload[9]  = (byte) ((counterOrTs >> 24) & 0xFF);
        payload[10] = (byte) ((counterOrTs >> 16) & 0xFF);
        payload[11] = (byte) ((counterOrTs >> 8) & 0xFF);
        payload[12] = (byte) (counterOrTs & 0xFF);
        payload[13] = (byte) ((rollingCode >> 24) & 0xFF);
        payload[14] = (byte) ((rollingCode >> 16) & 0xFF);
        payload[15] = (byte) ((rollingCode >> 8) & 0xFF);
        payload[16] = (byte) (rollingCode & 0xFF);
        return payload;
    }

    /**
     * Costruisce il payload Manufacturer Specific Data (25 byte, +2 byte CompanyID = 27 byte)
     * per registrare il nuovo smartphone sull'MCU quando l'MCU è in Modalità Accoppiamento (0xFE).
     */
    public static byte[] buildPairingRegistrationPayload(String targetMac, int clientId, String secretStr) {
        byte[] seed16 = decodeSeed16(secretStr);
        byte[] mac6 = parseMacBytes(targetMac);
        if (seed16 == null || mac6 == null) return null;

        byte[] payload = new byte[25];
        payload[0] = CMD_REGISTER_SEED;
        System.arraycopy(mac6, 0, payload, 1, 6);
        payload[7] = (byte) ((clientId >> 8) & 0xFF);
        payload[8] = (byte) (clientId & 0xFF);
        System.arraycopy(seed16, 0, payload, 9, SEED_SIZE);
        return payload;
    }
}
