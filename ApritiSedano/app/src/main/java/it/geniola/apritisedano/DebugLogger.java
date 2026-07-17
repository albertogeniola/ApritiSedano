package it.geniola.apritisedano;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DebugLogger {
    private static DebugLogger instance;
    private final List<DebugLogEntry> logs = new ArrayList<>();
    private LogListener listener;

    public interface LogListener {
        void onLogAdded(DebugLogEntry entry);
    }

    private DebugLogger() {
    }

    public static synchronized DebugLogger getInstance() {
        if (instance == null) {
            instance = new DebugLogger();
        }
        return instance;
    }

    public synchronized void addLog(DebugLogEntry entry) {
        logs.add(0, entry); // Add to the top
        // Keep only last 100 entries to prevent memory leak
        if (logs.size() > 100) {
            logs.remove(logs.size() - 1);
        }
        if (listener != null) {
            listener.onLogAdded(entry);
        }
    }

    public synchronized List<DebugLogEntry> getLogs() {
        return new ArrayList<>(logs);
    }

    public synchronized void setListener(LogListener listener) {
        this.listener = listener;
    }

    public synchronized void clearLogs() {
        logs.clear();
    }
}
