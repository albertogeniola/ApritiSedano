package it.geniola.apritisedano;

public class DebugLogEntry {
    private long timestamp;
    private boolean isTx;
    private String totp;
    private String message;

    public DebugLogEntry(long timestamp, boolean isTx, String totp, String message) {
        this.timestamp = timestamp;
        this.isTx = isTx;
        this.totp = totp;
        this.message = message;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public boolean isTx() {
        return isTx;
    }

    public String getTotp() {
        return totp;
    }

    public String getMessage() {
        return message;
    }
}
