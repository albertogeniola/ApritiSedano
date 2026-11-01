package it.geniola.apritisedano;

import org.json.JSONException;
import org.json.JSONObject;

public class Device {
    public static final String TYPE_BLE_MCU = "BLE_MCU";
    public static final String TYPE_SHELLY_CLOUD = "SHELLY_CLOUD";

    public static final String DEFAULT_AUTO_OPEN_MESSAGE = "Stai rientrando a casa, bentornato! Apro subito il box!";

    private String macAddress; // Unique identifier: BLE MAC for MCU, or "SHELLY:<id>:<ch>" for Shelly
    private String name;
    private String deviceType = TYPE_BLE_MCU;

    // BLE MCU Rolling-Code fields
    private String secretKey; // 128-bit Hex seed (32 chars)
    private int clientId;     // 16-bit identifier registered with the MCU (1..65534)
    private long counter;     // Monotonically increasing rolling code counter
    private boolean isMaster; // True if the MCU beacon identifies this smartphone's seed as Master OTA
    private int antennaState = -1; // -1: Sconosciuto/Non supportato, 0: Interna, 1: Esterna

    // Shelly Cloud fields
    private String shellyDeviceId = "";
    private String shellyServerUri = "";
    private String shellyAuthKey = "";
    private int shellyChannel = 0;

    // Auto-open proximity configuration (for Shelly Cloud devices on Android Auto)
    private boolean autoOpenEnabled = false;
    private int autoOpenRadiusMeters = 300;
    private boolean autoOpenDryRun = false;
    private String autoOpenNotificationText = DEFAULT_AUTO_OPEN_MESSAGE;

    // UI State (Not persisted)
    private int currentState = -1; // -1: Unknown, 1: Open, 2: Closed, 5: Pairing Mode
    private long lastSeen = 0;
    private long pairingEndTimeMs = 0;
    private boolean isActionPending = false;
    private long actionEndTimeMs = 0;

    // Shelly transient UI State (Not persisted): 0=Idle, 1=Sending, 2=Success, 3=Error
    private int shellyUiState = 0;
    private String shellyUiMessage = "";

    public Device(String macAddress, String name, String secretKey) {
        this(macAddress, name, secretKey, TOTPGenerator.generateRandomClientId(), 0L, false);
    }

    public Device(String macAddress, String name, String secretKey, int clientId, long counter) {
        this(macAddress, name, secretKey, clientId, counter, false);
    }

    public Device(String macAddress, String name, String secretKey, int clientId, long counter, boolean isMaster) {
        this.macAddress = macAddress;
        this.name = name;
        this.deviceType = TYPE_BLE_MCU;
        this.secretKey = secretKey != null ? secretKey : "";
        this.clientId = clientId;
        this.counter = counter;
        this.isMaster = isMaster;
    }

    public static String buildShellyUniqueId(String shellyDeviceId, int channel) {
        String normId = ShellyCloudClient.normalizeDeviceId(shellyDeviceId);
        return "SHELLY:" + normId + ":" + Math.max(0, channel);
    }

    public static Device createShellyDevice(
            String name,
            String shellyDeviceId,
            String shellyServerUri,
            String shellyAuthKey,
            int shellyChannel
    ) {
        String normId = ShellyCloudClient.normalizeDeviceId(shellyDeviceId);
        String normServer = ShellyCloudClient.normalizeServerUri(shellyServerUri);
        int ch = Math.max(0, shellyChannel);
        Device dev = new Device(buildShellyUniqueId(normId, ch), name, "", 1, 0L, false);
        dev.setDeviceType(TYPE_SHELLY_CLOUD);
        dev.setShellyDeviceId(normId);
        dev.setShellyServerUri(normServer);
        dev.setShellyAuthKey(shellyAuthKey != null ? shellyAuthKey.trim() : "");
        dev.setShellyChannel(ch);
        return dev;
    }

    public String getMacAddress() {
        return macAddress;
    }

    public void updateShellyIdentity(String shellyDeviceId, int channel) {
        String normId = ShellyCloudClient.normalizeDeviceId(shellyDeviceId);
        int ch = Math.max(0, channel);
        this.shellyDeviceId = normId;
        this.shellyChannel = ch;
        if (isShellyCloud()) {
            this.macAddress = buildShellyUniqueId(normId, ch);
        }
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDeviceType() {
        return deviceType != null ? deviceType : TYPE_BLE_MCU;
    }

    public void setDeviceType(String deviceType) {
        this.deviceType = (deviceType != null && !deviceType.isEmpty()) ? deviceType : TYPE_BLE_MCU;
    }

    public boolean isShellyCloud() {
        return TYPE_SHELLY_CLOUD.equals(this.deviceType);
    }

    public boolean isBleMcu() {
        return !isShellyCloud();
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public int getClientId() {
        return clientId;
    }

    public void setClientId(int clientId) {
        this.clientId = clientId;
    }

    public long getCounter() {
        return counter;
    }

    public void setCounter(long counter) {
        this.counter = counter;
    }

    public boolean isMaster() {
        return isMaster;
    }

    public void setMaster(boolean master) {
        isMaster = master;
    }

    public int getAntennaState() {
        return antennaState;
    }

    public void setAntennaState(int antennaState) {
        this.antennaState = antennaState;
    }

    public String getShellyDeviceId() {
        return shellyDeviceId != null ? shellyDeviceId : "";
    }

    public void setShellyDeviceId(String shellyDeviceId) {
        this.shellyDeviceId = ShellyCloudClient.normalizeDeviceId(shellyDeviceId);
    }

    public String getShellyServerUri() {
        return shellyServerUri != null ? shellyServerUri : "";
    }

    public void setShellyServerUri(String shellyServerUri) {
        this.shellyServerUri = ShellyCloudClient.normalizeServerUri(shellyServerUri);
    }

    public String getShellyAuthKey() {
        return shellyAuthKey != null ? shellyAuthKey : "";
    }

    public void setShellyAuthKey(String shellyAuthKey) {
        this.shellyAuthKey = shellyAuthKey != null ? shellyAuthKey.trim() : "";
    }

    public int getShellyChannel() {
        return shellyChannel;
    }

    public void setShellyChannel(int shellyChannel) {
        this.shellyChannel = Math.max(0, shellyChannel);
    }

    public boolean isAutoOpenEnabled() {
        return isShellyCloud() && autoOpenEnabled;
    }

    public void setAutoOpenEnabled(boolean autoOpenEnabled) {
        this.autoOpenEnabled = autoOpenEnabled;
    }

    public int getAutoOpenRadiusMeters() {
        return autoOpenRadiusMeters > 0 ? autoOpenRadiusMeters : 300;
    }

    public void setAutoOpenRadiusMeters(int autoOpenRadiusMeters) {
        this.autoOpenRadiusMeters = Math.max(30, Math.min(2000, autoOpenRadiusMeters));
    }

    public boolean isAutoOpenDryRun() {
        return autoOpenDryRun;
    }

    public void setAutoOpenDryRun(boolean autoOpenDryRun) {
        this.autoOpenDryRun = autoOpenDryRun;
    }

    public String getAutoOpenNotificationText() {
        return autoOpenNotificationText != null ? autoOpenNotificationText : "";
    }

    public void setAutoOpenNotificationText(String autoOpenNotificationText) {
        this.autoOpenNotificationText = autoOpenNotificationText != null ? autoOpenNotificationText.trim() : "";
    }

    public String resolveAutoOpenMessage() {
        String tpl = getAutoOpenNotificationText();
        if (tpl.isEmpty()) {
            return DEFAULT_AUTO_OPEN_MESSAGE;
        }
        String devName = (name != null && !name.isEmpty()) ? name : "il cancello";
        if (tpl.contains("%s")) {
            return tpl.replace("%s", devName);
        }
        return tpl;
    }

    public int getShellyUiState() {
        return shellyUiState;
    }

    public String getShellyUiMessage() {
        return shellyUiMessage != null ? shellyUiMessage : "";
    }

    public void setShellyUiFeedback(int state, String message) {
        this.shellyUiState = state;
        this.shellyUiMessage = message != null ? message : "";
    }

    public int getCurrentState() {
        return currentState;
    }

    public void setCurrentState(int currentState) {
        long now = System.currentTimeMillis();
        if (currentState == 5) {
            if (this.currentState != 5 || this.pairingEndTimeMs <= now) {
                this.pairingEndTimeMs = now + 60_000L;
            }
        } else {
            this.pairingEndTimeMs = 0L;
        }
        this.currentState = currentState;
        this.lastSeen = now;
    }

    public void startPairingCountdown(int durationSeconds) {
        long now = System.currentTimeMillis();
        this.currentState = 5;
        this.lastSeen = now;
        this.pairingEndTimeMs = now + (durationSeconds * 1000L);
    }

    public int getRemainingPairingSeconds() {
        if (currentState != 5 || pairingEndTimeMs <= 0) return 0;
        long diffMs = pairingEndTimeMs - System.currentTimeMillis();
        if (diffMs <= 0) return 0;
        return (int) Math.min(60L, (diffMs + 999L) / 1000L);
    }

    public long getLastSeen() {
        return lastSeen;
    }

    public boolean isActionPending() {
        return isActionPending;
    }

    public void setActionPending(boolean pending) {
        this.isActionPending = pending;
        if (pending) {
            this.actionEndTimeMs = System.currentTimeMillis() + 180_000L;
        } else {
            this.actionEndTimeMs = 0L;
        }
    }

    public int getRemainingActionSeconds() {
        if (!isActionPending || actionEndTimeMs <= 0) return 0;
        long diffMs = actionEndTimeMs - System.currentTimeMillis();
        if (diffMs <= 0) return 0;
        return (int) Math.min(180L, (diffMs + 999L) / 1000L);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("macAddress", macAddress);
        json.put("name", name);
        json.put("deviceType", getDeviceType());
        json.put("secretKey", secretKey != null ? secretKey : "");
        json.put("clientId", clientId);
        json.put("counter", counter);
        json.put("isMaster", isMaster);
        json.put("antennaState", antennaState);

        if (isShellyCloud()) {
            json.put("shellyDeviceId", getShellyDeviceId());
            json.put("shellyServerUri", getShellyServerUri());
            json.put("shellyAuthKey", getShellyAuthKey());
            json.put("shellyChannel", shellyChannel);
            json.put("autoOpenEnabled", autoOpenEnabled);
            json.put("autoOpenRadiusMeters", getAutoOpenRadiusMeters());
            json.put("autoOpenDryRun", autoOpenDryRun);
            json.put("autoOpenNotificationText", getAutoOpenNotificationText());
        }
        return json;
    }

    public static Device fromJson(JSONObject json) throws JSONException {
        String mac = json.getString("macAddress");
        String name = json.getString("name");
        String type = json.optString("deviceType", TYPE_BLE_MCU);
        String secret = json.optString("secretKey", "");
        int clientId = json.optInt("clientId", 0);
        if (clientId <= 0 || clientId > 0xFFFF) {
            clientId = TOTPGenerator.generateRandomClientId();
        }
        long counter = json.optLong("counter", 0L);
        boolean isMaster = json.optBoolean("isMaster", false);

        Device dev = new Device(mac, name, secret, clientId, counter, isMaster);
        dev.setDeviceType(type);
        dev.setAntennaState(json.optInt("antennaState", -1));

        if (TYPE_SHELLY_CLOUD.equals(type)) {
            dev.setShellyDeviceId(json.optString("shellyDeviceId", ""));
            dev.setShellyServerUri(json.optString("shellyServerUri", ""));
            dev.setShellyAuthKey(json.optString("shellyAuthKey", ""));
            dev.setShellyChannel(json.optInt("shellyChannel", 0));
            dev.setAutoOpenEnabled(json.optBoolean("autoOpenEnabled", false));
            dev.setAutoOpenRadiusMeters(json.optInt("autoOpenRadiusMeters", 300));
            dev.setAutoOpenDryRun(json.optBoolean("autoOpenDryRun", false));
            dev.setAutoOpenNotificationText(json.optString("autoOpenNotificationText", DEFAULT_AUTO_OPEN_MESSAGE));
        }
        return dev;
    }
}
