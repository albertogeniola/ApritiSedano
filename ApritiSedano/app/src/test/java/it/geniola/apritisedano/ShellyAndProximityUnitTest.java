package it.geniola.apritisedano;

import org.junit.Test;

import static org.junit.Assert.*;

public class ShellyAndProximityUnitTest {

    @Test
    public void normalizeServerUri_handlesDomainAndEndpoints() {
        assertEquals("", ShellyCloudClient.normalizeServerUri(null));
        assertEquals("", ShellyCloudClient.normalizeServerUri("   "));
        assertEquals(
                "https://shelly-11-eu.shelly.cloud",
                ShellyCloudClient.normalizeServerUri("shelly-11-eu.shelly.cloud")
        );
        assertEquals(
                "https://shelly-11-eu.shelly.cloud",
                ShellyCloudClient.normalizeServerUri("  https://shelly-11-eu.shelly.cloud/  ")
        );
        assertEquals(
                "https://shelly-11-eu.shelly.cloud",
                ShellyCloudClient.normalizeServerUri("https://shelly-11-eu.shelly.cloud/device/relay/control/")
        );
    }

    @Test
    public void normalizeDeviceId_handlesHexAndHomeAssistantEntityId() {
        assertEquals("", ShellyCloudClient.normalizeDeviceId(null));
        assertEquals("485519c93fec", ShellyCloudClient.normalizeDeviceId("485519C93FEC"));
        assertEquals("485519c93fec", ShellyCloudClient.normalizeDeviceId("cover.shelly1_485519c93fec"));
        assertEquals("485519c93fec", ShellyCloudClient.normalizeDeviceId("shelly1-485519c93fec"));
        assertEquals("485519c93fec", ShellyCloudClient.normalizeDeviceId("485519c93fec (Cancello)"));
    }

    @Test
    public void bleMcuDevice_enforcesManualOnlySafetyBoundary() {
        Device boxBle = new Device("AA:BB:CC:DD:EE:FF", "Box Basculante", "00112233445566778899AABBCCDDEEFF", 1234, 10L);
        assertTrue(boxBle.isBleMcu());
        assertFalse(boxBle.isShellyCloud());

        // Anche se si tentasse di abilitare autoOpenEnabled su un dispositivo BLE MCU,
        // isAutoOpenEnabled() deve restare rigorosamente false per sicurezza (nessuna fotocellula).
        boxBle.setAutoOpenEnabled(true);
        assertFalse(boxBle.isAutoOpenEnabled());
    }

    @Test
    public void shellyCloudDevice_creationAndAutoOpenMessageFormatting() {
        Device gate = Device.createShellyDevice(
                "Cancello Condominiale",
                "cover.shelly1_485519c93fec",
                "shelly-11-eu.shelly.cloud/",
                "  my_secret_auth_key  ",
                0
        );

        assertTrue(gate.isShellyCloud());
        assertFalse(gate.isBleMcu());
        assertEquals("SHELLY:485519c93fec:0", gate.getMacAddress());
        assertEquals("485519c93fec", gate.getShellyDeviceId());
        assertEquals("https://shelly-11-eu.shelly.cloud", gate.getShellyServerUri());
        assertEquals("my_secret_auth_key", gate.getShellyAuthKey());
        assertEquals(0, gate.getShellyChannel());

        assertFalse(gate.isAutoOpenEnabled());
        gate.setAutoOpenEnabled(true);
        assertTrue(gate.isAutoOpenEnabled());

        // Default welcome message
        assertEquals(
                "Stai rientrando a casa, bentornato! Apro subito il box!",
                gate.resolveAutoOpenMessage()
        );

        // Placeholder %s substitution
        gate.setAutoOpenNotificationText("Bentornato! Apro %s...");
        assertEquals("Bentornato! Apro Cancello Condominiale...", gate.resolveAutoOpenMessage());

        // Radius bounds clamping
        gate.setAutoOpenRadiusMeters(10);
        assertEquals(30, gate.getAutoOpenRadiusMeters());
        gate.setAutoOpenRadiusMeters(300);
        assertEquals(300, gate.getAutoOpenRadiusMeters());
        gate.setAutoOpenRadiusMeters(9999);
        assertEquals(2000, gate.getAutoOpenRadiusMeters());

        // Identity update keeps unique macAddress in sync
        gate.updateShellyIdentity("AABBCCDDEEFF", 1);
        assertEquals("SHELLY:aabbccddeeff:1", gate.getMacAddress());
    }
}
