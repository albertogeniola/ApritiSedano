package it.geniola.apritisedano;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class BackupManagerUnitTest {

    @Test
    public void aes256GcmEncryption_roundTripAndWrongPasswordRejection() throws Exception {
        String sampleSecretPayload = "{\"devices\":[{\"macAddress\":\"AA:BB:CC:11:22:33\",\"secretKey\":\"00112233445566778899AABBCCDDEEFF\",\"name\":\"Box Basculante 🚗\"}]}";
        String password = "SuperSecretPassword!2026";

        BackupManager.EncryptedPayload enc = BackupManager.encryptString(sampleSecretPayload, password);
        assertNotNull(enc.saltBase64);
        assertNotNull(enc.ivBase64);
        assertNotNull(enc.ciphertextBase64);
        assertNotEquals(sampleSecretPayload, enc.ciphertextBase64);

        String decrypted = BackupManager.decryptString(
                enc.iterations,
                enc.saltBase64,
                enc.ivBase64,
                enc.ciphertextBase64,
                password
        );
        assertEquals(sampleSecretPayload, decrypted);

        // Verifica rifiuto password errata (AEAD tag mismatch)
        try {
            BackupManager.decryptString(
                    enc.iterations,
                    enc.saltBase64,
                    enc.ivBase64,
                    enc.ciphertextBase64,
                    "WrongPassword123"
            );
            fail("Doveva sollevare WrongPasswordException con password errata");
        } catch (BackupManager.WrongPasswordException expected) {
            // OK
        }
    }

    @Test
    public void mergeDeviceLists_preservesHigherRollingCodeCounterAndAddsNewDevices() {
        String seed = "0123456789ABCDEF0123456789ABCDEF";
        Device localBox = new Device("AA:BB:CC:DD:EE:01", "Box Locale", seed, 0x1234, 58L, true);

        // Il backup è più vecchio (contatore a 42, non ancora rilevato master), ma ha un nome aggiornato
        Device backupBoxOlderCounter = new Device("aa:bb:cc:dd:ee:01", "Box Principale", seed, 0x1234, 42L, false);
        Device backupShellyGate = Device.createShellyDevice(
                "Cancello Condominiale",
                "485519c93fec",
                "https://shelly-11-eu.shelly.cloud",
                "auth_key_xyz",
                0
        );

        List<Device> merged = BackupManager.mergeDeviceLists(
                Collections.singletonList(localBox),
                Arrays.asList(backupBoxOlderCounter, backupShellyGate),
                0
        );

        assertEquals(2, merged.size());
        Device mergedBox = merged.get(0);
        assertEquals("Box Principale", mergedBox.getName());
        // Il contatore DEVE restare 58 (e non tornare indietro a 42!)
        assertEquals(58L, mergedBox.getCounter());
        assertTrue(mergedBox.isMaster());

        Device mergedGate = merged.get(1);
        assertTrue(mergedGate.isShellyCloud());
        assertEquals("485519c93fec", mergedGate.getShellyDeviceId());
    }

    @Test
    public void prepareOverwriteList_appliesOptionalBleCounterOffsetSafely() {
        Device ble = new Device("11:22:33:44:55:66", "Garage", "FFEEDDCCBBAA99887766554433221100", 500, 25L, false);
        Device shelly = Device.createShellyDevice("Cancello", "485519c93fec", "shelly-11-eu.shelly.cloud", "key", 0);

        List<Device> prepared = BackupManager.prepareOverwriteList(Arrays.asList(ble, shelly), 10);
        assertEquals(2, prepared.size());
        assertEquals(35L, prepared.get(0).getCounter());
        assertEquals(0L, prepared.get(1).getCounter());
    }
}
