package it.geniola.apritisedano;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Gestisce l'esportazione e la re-importazione globale di tutti i dispositivi associati
 * (seed Rolling-Code BLE a 128 bit, ClientID, contatori, relè Shelly Cloud) e delle
 * impostazioni globali di rientro (coordinate Casa, filtro Bluetooth Auto),
 * con supporto opzionale di cifratura autenticata AES-256-GCM (PBKDF2-HMAC-SHA256).
 */
public class BackupManager {

    public static final String FORMAT_ID = "apritisedano_global_backup";
    public static final int CURRENT_VERSION = 1;
    public static final int MAX_BACKUP_FILE_BYTES = 2 * 1024 * 1024; // 2 MB max safety guard

    private static final String KDF_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int PBKDF2_ITERATIONS = 100_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_LENGTH_BYTES = 16;
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public static class WrongPasswordException extends Exception {
        public WrongPasswordException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class InvalidBackupFormatException extends Exception {
        public InvalidBackupFormatException(String message) {
            super(message);
        }

        public InvalidBackupFormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class EncryptedPayload {
        public final int iterations;
        public final String saltBase64;
        public final String ivBase64;
        public final String ciphertextBase64;

        public EncryptedPayload(int iterations, String saltBase64, String ivBase64, String ciphertextBase64) {
            this.iterations = iterations;
            this.saltBase64 = saltBase64;
            this.ivBase64 = ivBase64;
            this.ciphertextBase64 = ciphertextBase64;
        }
    }

    public static class ProximitySnapshot {
        public boolean presentInBackup = false;
        public boolean hasHomeLocation = false;
        public double homeLatitude = 0.0;
        public double homeLongitude = 0.0;
        public String carBluetoothMac = "";
        public String carBluetoothName = "";
        public int armDistanceMeters = ProximityConfigManager.DEFAULT_ARM_DISTANCE_METERS;
        public int realtimeDistanceMeters = ProximityConfigManager.DEFAULT_REALTIME_DISTANCE_METERS;
        public String defaultShellyServer = "";
        public String defaultShellyAuthKey = "";
    }

    public static class ParsedBackup {
        public final String exportedAt;
        public final List<Device> devices;
        public final ProximitySnapshot proximity;

        public ParsedBackup(String exportedAt, List<Device> devices, ProximitySnapshot proximity) {
            this.exportedAt = exportedAt != null ? exportedAt : "";
            this.devices = devices != null ? devices : new ArrayList<>();
            this.proximity = proximity != null ? proximity : new ProximitySnapshot();
        }

        public int getBleCount() {
            int c = 0;
            for (Device d : devices) {
                if (d.isBleMcu()) c++;
            }
            return c;
        }

        public int getShellyCount() {
            int c = 0;
            for (Device d : devices) {
                if (d.isShellyCloud()) c++;
            }
            return c;
        }
    }

    public static String buildSuggestedFileName() {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return "ApritiSedano_Backup_" + ts + ".json";
    }

    /**
     * Cifra una stringa UTF-8 tramite AES-256-GCM con chiave derivata via PBKDF2-HMAC-SHA256.
     */
    public static EncryptedPayload encryptString(String plaintextUtf8, String password) throws GeneralSecurityException {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Password vuota per cifratura.");
        }
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        SECURE_RANDOM.nextBytes(salt);

        byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
        SECURE_RANDOM.nextBytes(iv);

        SecretKeySpec aesKey = deriveAesKey(password, salt, PBKDF2_ITERATIONS);
        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
        byte[] ciphertext = cipher.doFinal(plaintextUtf8.getBytes(StandardCharsets.UTF_8));

        Base64.Encoder enc = Base64.getEncoder();
        return new EncryptedPayload(
                PBKDF2_ITERATIONS,
                enc.encodeToString(salt),
                enc.encodeToString(iv),
                enc.encodeToString(ciphertext)
        );
    }

    /**
     * Decifra un payload AES-256-GCM verificando il tag di autenticazione a 128 bit.
     */
    public static String decryptString(
            int iterations,
            String saltBase64,
            String ivBase64,
            String ciphertextBase64,
            String password
    ) throws WrongPasswordException, InvalidBackupFormatException {
        if (password == null || password.isEmpty()) {
            throw new WrongPasswordException("Password richiesta per decifrare il backup.", null);
        }
        if (iterations < 1000 || iterations > 2_000_000) {
            throw new InvalidBackupFormatException("Parametro iterazioni KDF non valido nel file di backup.");
        }
        try {
            Base64.Decoder dec = Base64.getDecoder();
            byte[] salt = dec.decode(saltBase64);
            byte[] iv = dec.decode(ivBase64);
            byte[] ciphertext = dec.decode(ciphertextBase64);

            SecretKeySpec aesKey = deriveAesKey(password, salt, iterations);
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] plainBytes = cipher.doFinal(ciphertext);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (AEADBadTagException badTag) {
            throw new WrongPasswordException("Password errata oppure file di backup alterato.", badTag);
        } catch (IllegalArgumentException b64Err) {
            throw new InvalidBackupFormatException("Dati cifrati Base64 non validi nel file di backup.", b64Err);
        } catch (GeneralSecurityException secErr) {
            throw new WrongPasswordException("Impossibile decifrare il backup (verifica la password).", secErr);
        }
    }

    private static SecretKeySpec deriveAesKey(String password, byte[] salt, int iterations) throws GeneralSecurityException {
        SecretKeyFactory factory = SecretKeyFactory.getInstance(KDF_ALGORITHM);
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH_BITS);
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * Costruisce il JSON completo di backup (in chiaro o cifrato AES-256-GCM se password != empty).
     */
    public static byte[] createGlobalBackupBytes(Context context, String optionalPassword) throws Exception {
        List<Device> devices = DeviceManager.getDevices(context);
        String nowIso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new Date());

        JSONObject payload = new JSONObject();
        payload.put("exportedAt", nowIso);

        JSONArray devicesArr = new JSONArray();
        for (Device d : devices) {
            devicesArr.put(d.toJson());
        }
        payload.put("devices", devicesArr);

        JSONObject proxJson = new JSONObject();
        boolean hasHome = ProximityConfigManager.hasHomeLocation(context);
        proxJson.put("hasHomeLocation", hasHome);
        if (hasHome) {
            proxJson.put("homeLatitude", ProximityConfigManager.getHomeLatitude(context));
            proxJson.put("homeLongitude", ProximityConfigManager.getHomeLongitude(context));
        }
        proxJson.put("carBluetoothMac", ProximityConfigManager.getCarBluetoothMac(context));
        proxJson.put("carBluetoothName", ProximityConfigManager.getCarBluetoothName(context));
        proxJson.put("armDistanceMeters", ProximityConfigManager.getArmDistanceMeters(context));
        proxJson.put("realtimeDistanceMeters", ProximityConfigManager.getRealtimeDistanceMeters(context));
        proxJson.put("defaultShellyServer", ProximityConfigManager.getDefaultShellyServer(context));
        proxJson.put("defaultShellyAuthKey", ProximityConfigManager.getDefaultShellyAuthKey(context));
        payload.put("proximityConfig", proxJson);

        JSONObject root = new JSONObject();
        root.put("format", FORMAT_ID);
        root.put("version", CURRENT_VERSION);
        root.put("exportedAt", nowIso);
        root.put("deviceCount", devices.size());

        boolean encrypt = (optionalPassword != null && !optionalPassword.isEmpty());
        root.put("encrypted", encrypt);

        if (encrypt) {
            EncryptedPayload enc = encryptString(payload.toString(), optionalPassword);
            root.put("kdf", KDF_ALGORITHM);
            root.put("cipher", CIPHER_TRANSFORMATION);
            root.put("iterations", enc.iterations);
            root.put("salt", enc.saltBase64);
            root.put("iv", enc.ivBase64);
            root.put("ciphertext", enc.ciphertextBase64);
        } else {
            root.put("payload", payload);
        }

        return root.toString(2).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Verifica rapidamente se il file JSON selezionato è un backup ApritiSedano cifrato con password.
     */
    public static boolean isBackupEncrypted(String rawJsonText) throws InvalidBackupFormatException {
        if (rawJsonText == null || rawJsonText.trim().isEmpty()) {
            throw new InvalidBackupFormatException("Il file selezionato è vuoto.");
        }
        String trimmed = rawJsonText.trim();
        if (trimmed.startsWith("[")) {
            return false;
        }
        try {
            JSONObject root = new JSONObject(trimmed);
            if (!FORMAT_ID.equals(root.optString("format", "")) && !root.has("devices") && !root.has("payload")) {
                throw new InvalidBackupFormatException("Il file selezionato non è un backup ApritiSedano valido.");
            }
            return root.optBoolean("encrypted", false);
        } catch (JSONException e) {
            throw new InvalidBackupFormatException("Formato JSON non valido: assicurati di aver selezionato un file di backup ApritiSedano (.json).", e);
        }
    }

    /**
     * Effettua il parsing (ed eventuale decifratura) del file di backup globale.
     */
    public static ParsedBackup parseGlobalBackup(String rawJsonText, String optionalPassword)
            throws WrongPasswordException, InvalidBackupFormatException {
        if (rawJsonText == null || rawJsonText.trim().isEmpty()) {
            throw new InvalidBackupFormatException("Il file di backup è vuoto.");
        }
        String trimmed = rawJsonText.trim();

        try {
            JSONObject payloadObj;
            String exportedAt = "";

            if (trimmed.startsWith("[")) {
                // Supporta anche un array JSON diretto di dispositivi
                payloadObj = new JSONObject();
                payloadObj.put("devices", new JSONArray(trimmed));
            } else {
                JSONObject root = new JSONObject(trimmed);
                exportedAt = root.optString("exportedAt", "");
                boolean encrypted = root.optBoolean("encrypted", false);

                if (encrypted) {
                    int iterations = root.optInt("iterations", PBKDF2_ITERATIONS);
                    String salt = root.getString("salt");
                    String iv = root.getString("iv");
                    String ciphertext = root.getString("ciphertext");
                    String decryptedJson = decryptString(iterations, salt, iv, ciphertext, optionalPassword);
                    payloadObj = new JSONObject(decryptedJson);
                    if (exportedAt.isEmpty()) {
                        exportedAt = payloadObj.optString("exportedAt", "");
                    }
                } else if (root.has("payload")) {
                    payloadObj = root.getJSONObject("payload");
                    if (exportedAt.isEmpty()) {
                        exportedAt = payloadObj.optString("exportedAt", "");
                    }
                } else if (root.has("devices")) {
                    payloadObj = root;
                } else {
                    throw new InvalidBackupFormatException("Struttura del file di backup non riconosciuta.");
                }
            }

            List<Device> devices = new ArrayList<>();
            JSONArray devArray = payloadObj.optJSONArray("devices");
            if (devArray != null) {
                for (int i = 0; i < devArray.length(); i++) {
                    JSONObject devObj = devArray.getJSONObject(i);
                    Device dev = Device.fromJson(devObj);
                    if (dev.getMacAddress() == null || dev.getMacAddress().trim().isEmpty()) {
                        continue;
                    }
                    if (dev.isBleMcu() && !TOTPGenerator.isValidBase32Secret(dev.getSecretKey())) {
                        throw new InvalidBackupFormatException(
                                "Seed Rolling-Code non valido per il dispositivo BLE '" + dev.getName() + "'."
                        );
                    }
                    devices.add(dev);
                }
            }

            ProximitySnapshot prox = new ProximitySnapshot();
            JSONObject proxObj = payloadObj.optJSONObject("proximityConfig");
            if (proxObj != null) {
                prox.presentInBackup = true;
                prox.hasHomeLocation = proxObj.optBoolean("hasHomeLocation", false);
                prox.homeLatitude = proxObj.optDouble("homeLatitude", 0.0);
                prox.homeLongitude = proxObj.optDouble("homeLongitude", 0.0);
                prox.carBluetoothMac = proxObj.optString("carBluetoothMac", "");
                prox.carBluetoothName = proxObj.optString("carBluetoothName", "");
                prox.armDistanceMeters = proxObj.optInt("armDistanceMeters", ProximityConfigManager.DEFAULT_ARM_DISTANCE_METERS);
                prox.realtimeDistanceMeters = proxObj.optInt("realtimeDistanceMeters", ProximityConfigManager.DEFAULT_REALTIME_DISTANCE_METERS);
                prox.defaultShellyServer = proxObj.optString("defaultShellyServer", "");
                prox.defaultShellyAuthKey = proxObj.optString("defaultShellyAuthKey", "");
            }

            if (devices.isEmpty() && !prox.hasHomeLocation) {
                throw new InvalidBackupFormatException("Il file di backup non contiene alcun dispositivo o configurazione.");
            }

            return new ParsedBackup(exportedAt, devices, prox);
        } catch (WrongPasswordException | InvalidBackupFormatException e) {
            throw e;
        } catch (JSONException e) {
            throw new InvalidBackupFormatException("Contenuto JSON del backup corrotto o incompleto.", e);
        }
    }

    /**
     * Unisce i dispositivi importati con quelli esistenti:
     * - Se uno stesso ricevitore BLE ha lo stesso ClientID e lo stesso Seed, mantiene il contatore
     *   più alto (Math.max) per evitare disallineamenti Rolling-Code (NACK_USED).
     * - Applica un eventuale offset positivo ai contatori BLE importati (es. +10 se usati dopo il backup).
     */
    public static List<Device> mergeDeviceLists(
            List<Device> existingDevices,
            List<Device> importedDevices,
            int bleCounterOffset
    ) {
        List<Device> result = new ArrayList<>();
        if (existingDevices != null) {
            result.addAll(existingDevices);
        }
        if (importedDevices == null) {
            return result;
        }

        int safeOffset = Math.max(0, Math.min(40, bleCounterOffset));

        for (Device imp : importedDevices) {
            if (imp.isBleMcu() && safeOffset > 0) {
                imp.setCounter(imp.getCounter() + safeOffset);
            }

            int matchIdx = -1;
            for (int i = 0; i < result.size(); i++) {
                if (result.get(i).getMacAddress().equalsIgnoreCase(imp.getMacAddress())) {
                    matchIdx = i;
                    break;
                }
            }

            if (matchIdx >= 0) {
                Device prev = result.get(matchIdx);
                if (prev.isBleMcu() && imp.isBleMcu()
                        && prev.getClientId() == imp.getClientId()
                        && prev.getSecretKey() != null
                        && prev.getSecretKey().equalsIgnoreCase(imp.getSecretKey())) {
                    imp.setCounter(Math.max(prev.getCounter(), imp.getCounter()));
                    if (prev.isMaster() && !imp.isMaster()) {
                        imp.setMaster(true);
                    }
                }
                result.set(matchIdx, imp);
            } else {
                result.add(imp);
            }
        }
        return result;
    }

    public static List<Device> prepareOverwriteList(List<Device> importedDevices, int bleCounterOffset) {
        List<Device> result = new ArrayList<>();
        if (importedDevices == null) return result;
        int safeOffset = Math.max(0, Math.min(40, bleCounterOffset));
        for (Device imp : importedDevices) {
            if (imp.isBleMcu() && safeOffset > 0) {
                imp.setCounter(imp.getCounter() + safeOffset);
            }
            result.add(imp);
        }
        return result;
    }

    /**
     * Applica il backup parsato (in modalità Merge o Overwrite) salvando dispositivi e configurazione rientro.
     */
    public static void applyImportedBackup(
            Context context,
            ParsedBackup parsed,
            boolean overwriteAll,
            int bleCounterOffset
    ) {
        List<Device> finalDevices;
        if (overwriteAll) {
            finalDevices = prepareOverwriteList(parsed.devices, bleCounterOffset);
        } else {
            List<Device> current = DeviceManager.getDevices(context);
            finalDevices = mergeDeviceLists(current, parsed.devices, bleCounterOffset);
        }
        DeviceManager.saveDevices(context, finalDevices);

        ProximitySnapshot p = parsed.proximity;
        if (p != null && p.presentInBackup) {
            if (p.hasHomeLocation) {
                ProximityConfigManager.setHomeLocation(context, p.homeLatitude, p.homeLongitude);
            } else if (overwriteAll) {
                ProximityConfigManager.clearHomeLocation(context);
            }

            if (!p.carBluetoothMac.isEmpty() || overwriteAll) {
                ProximityConfigManager.setCarBluetoothFilter(context, p.carBluetoothMac, p.carBluetoothName);
            }
            ProximityConfigManager.setArmDistanceMeters(context, p.armDistanceMeters);
            ProximityConfigManager.setRealtimeDistanceMeters(context, p.realtimeDistanceMeters);

            if (!p.defaultShellyServer.isEmpty() || !p.defaultShellyAuthKey.isEmpty() || overwriteAll) {
                ProximityConfigManager.saveDefaultShellyCredentials(
                        context,
                        p.defaultShellyServer,
                        p.defaultShellyAuthKey
                );
            }
        }
    }
}
