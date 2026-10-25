package it.geniola.apritisedano;

import org.json.JSONException;
import org.json.JSONObject;

public class Device {
    private String macAddress;
    private String name;
    private String secretKey; // 128-bit Hex seed (32 chars)
    private int clientId;     // 16-bit identifier registered with the MCU (1..65534)
    private long counter;     // Monotonically increasing rolling code counter

    // UI State (Not persisted)
    private int currentState = -1; // -1: Unknown, 1: Open, 2: Closed, 5: Pairing Mode
    private long lastSeen = 0;
    private long pairingEndTimeMs = 0;
    private boolean isActionPending = false;

    public Device(String macAddress, String name, String secretKey) {
        this(macAddress, name, secretKey, TOTPGenerator.generateRandomClientId(), 0L);
    }

    public Device(String macAddress, String name, String secretKey, int clientId, long counter) {
        this.macAddress = macAddress;
        this.name = name;
        this.secretKey = secretKey;
        this.clientId = clientId;
        this.counter = counter;
    }

    public String getMacAddress() {
        return macAddress;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("macAddress", macAddress);
        json.put("name", name);
        json.put("secretKey", secretKey);
        json.put("clientId", clientId);
        json.put("counter", counter);
        return json;
    }

    public static Device fromJson(JSONObject json) throws JSONException {
        String mac = json.getString("macAddress");
        String name = json.getString("name");
        String secret = json.getString("secretKey");
        int clientId = json.optInt("clientId", 0);
        if (clientId <= 0 || clientId > 0xFFFF) {
            clientId = TOTPGenerator.generateRandomClientId();
        }
        long counter = json.optLong("counter", 0L);
        return new Device(mac, name, secret, clientId, counter);
    }
}
