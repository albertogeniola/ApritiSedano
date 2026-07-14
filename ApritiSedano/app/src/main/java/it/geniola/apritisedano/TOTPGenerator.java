package it.geniola.apritisedano;

import android.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Locale;

/**
 * Generatore TOTP (RFC 6238) per autenticazione a 6 cifre.
 */
public class TOTPGenerator {

    private static final String HMAC_ALGO = "HmacSHA1";
    private static final int TOTP_LENGTH = 6;
    private static final int TIME_STEP = 30; // 30 secondi

    /**
     * Genera un codice TOTP basato sul segreto Base32 e sul timestamp corrente.
     * @param base32Secret Il segreto pre-condiviso in formato Base32.
     * @return Stringa di 6 cifre.
     */
    public static String generateTOTP(String base32Secret) {
        long timeWindow = System.currentTimeMillis() / 1000L / TIME_STEP;
        return generateTOTP(base32Secret, timeWindow);
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

    private static String generateTOTP(String base32Secret, long timeWindow) {
        byte[] key = decodeBase32(base32Secret);
        byte[] data = ByteBuffer.allocate(8).putLong(timeWindow).array();

        try {
            SecretKeySpec signKey = new SecretKeySpec(key, HMAC_ALGO);
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(signKey);
            byte[] hash = mac.doFinal(data);

            int offset = hash[hash.length - 1] & 0xf;
            int binary = ((hash[offset] & 0x7f) << 24) |
                         ((hash[offset + 1] & 0xff) << 16) |
                         ((hash[offset + 2] & 0xff) << 8) |
                         (hash[offset + 3] & 0xff);

            int otp = binary % (int) Math.pow(10, TOTP_LENGTH);
            return String.format(Locale.US, "%0" + TOTP_LENGTH + "d", otp);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Errore nella generazione TOTP", e);
        }
    }

    /**
     * Verifica la firma del beacon di stato.
     * @param base32Secret Il segreto pre-condiviso in formato Base32.
     * @param state Lo stato (0 o 1).
     * @param timestamp Il timestamp inviato dal beacon.
     * @param receivedHmac I primi 4 byte della firma ricevuta.
     * @return true se la firma è valida, false altrimenti.
     */
    public static boolean verifyStateBeacon(String base32Secret, byte state, int timestamp, byte[] receivedHmac) {
        if (receivedHmac == null || receivedHmac.length < 4) return false;
        byte[] key = decodeBase32(base32Secret);
        byte[] data = new byte[5];
        data[0] = state;
        data[1] = (byte) ((timestamp >> 24) & 0xFF);
        data[2] = (byte) ((timestamp >> 16) & 0xFF);
        data[3] = (byte) ((timestamp >> 8) & 0xFF);
        data[4] = (byte) (timestamp & 0xFF);

        try {
            SecretKeySpec signKey = new SecretKeySpec(key, HMAC_ALGO);
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(signKey);
            byte[] hash = mac.doFinal(data);

            for (int i = 0; i < 4; i++) {
                if (hash[i] != receivedHmac[i]) return false;
            }
            return true;
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /**
     * Generates the 9-byte signed payload for time synchronization.
     * @param base32Secret The Base32 pre-shared secret.
     * @param timestamp The current UNIX timestamp to propose.
     * @return 9-byte payload: [0xFF, ts(4), hmac(4)]
     */
    public static byte[] generateTimeSyncPayload(String base32Secret, int timestamp) {
        byte[] key = decodeBase32(base32Secret);
        byte[] data = new byte[5];
        data[0] = (byte) 0xFF;
        data[1] = (byte) ((timestamp >> 24) & 0xFF);
        data[2] = (byte) ((timestamp >> 16) & 0xFF);
        data[3] = (byte) ((timestamp >> 8) & 0xFF);
        data[4] = (byte) (timestamp & 0xFF);

        try {
            SecretKeySpec signKey = new SecretKeySpec(key, HMAC_ALGO);
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(signKey);
            byte[] hash = mac.doFinal(data);

            byte[] payload = new byte[9];
            System.arraycopy(data, 0, payload, 0, 5);
            System.arraycopy(hash, 0, payload, 5, 4);
            return payload;
        } catch (GeneralSecurityException e) {
            return null;
        }
    }
}