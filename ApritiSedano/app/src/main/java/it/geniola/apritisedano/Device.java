package it.geniola.apritisedano;

import org.json.JSONException;
import org.json.JSONObject;

public class Device {
    private String macAddress;
    private String name;
    private String secretKey;
    
    // UI State (Not persisted)
    private int currentState = -1; // -1: Unknown, 1: Open, 2: Closed, 3: Syncing, 4: Time Invalid
    private long lastSeen = 0;
    private boolean isActionPending = false;

    public Device(String macAddress, String name, String secretKey) {
        this.macAddress = macAddress;
        this.name = name;
        this.secretKey = secretKey;
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

    public int getCurrentState() {
        return currentState;
    }

    public void setCurrentState(int currentState) {
        this.currentState = currentState;
        this.lastSeen = System.currentTimeMillis();
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
        return json;
    }

    public static Device fromJson(JSONObject json) throws JSONException {
        return new Device(
                json.getString("macAddress"),
                json.getString("name"),
                json.getString("secretKey")
        );
    }
}
