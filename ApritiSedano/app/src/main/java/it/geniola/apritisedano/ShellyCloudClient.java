package it.geniola.apritisedano;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Client HTTPS leggero per il controllo diretto dei dispositivi Shelly tramite Shelly Cloud API.
 * Non richiede VPN né esposizione di Home Assistant.
 */
public class ShellyCloudClient {
    private static final String TAG = "ShellyCloudClient";
    private static final int CONNECT_TIMEOUT_MS = 6500;
    private static final int READ_TIMEOUT_MS = 8500;

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private static volatile Handler mainHandler;

    public static class Result {
        public final boolean success;
        public final boolean online;
        public final String message;
        public final int httpCode;

        public Result(boolean success, boolean online, String message, int httpCode) {
            this.success = success;
            this.online = online;
            this.message = message;
            this.httpCode = httpCode;
        }
    }

    public interface Callback {
        void onComplete(Result result);
    }

    /**
     * Normalizza l'URI del server Shelly Cloud (es. "shelly-11-eu.shelly.cloud" -> "https://shelly-11-eu.shelly.cloud").
     */
    public static String normalizeServerUri(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty()) return "";
        if (!s.startsWith("https://") && !s.startsWith("http://")) {
            s = "https://" + s;
        }
        int endpointIdx = s.indexOf("/device/");
        if (endpointIdx > 0) {
            s = s.substring(0, endpointIdx);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * Normalizza il Device ID Shelly (es. "cover.shelly1_485519c93fec" o "485519C93FEC" -> "485519c93fec").
     */
    public static String normalizeDeviceId(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        int parenIdx = s.indexOf('(');
        if (parenIdx > 0) {
            s = s.substring(0, parenIdx).trim();
        }
        int underscoreIdx = s.lastIndexOf('_');
        if (underscoreIdx >= 0 && underscoreIdx < s.length() - 1) {
            s = s.substring(underscoreIdx + 1);
        }
        int dashIdx = s.lastIndexOf('-');
        if (dashIdx >= 0 && dashIdx < s.length() - 1 && s.toLowerCase(Locale.US).startsWith("shelly")) {
            s = s.substring(dashIdx + 1);
        }
        return s.trim().toLowerCase(Locale.US);
    }

    public static void triggerDeviceAsync(Device device, int maxRetries, Callback callback) {
        if (device == null || !device.isShellyCloud()) {
            postCallback(callback, new Result(false, false, "Dispositivo non di tipo Shelly Cloud.", 0));
            return;
        }
        final String serverUri = device.getShellyServerUri();
        final String authKey = device.getShellyAuthKey();
        final String deviceId = device.getShellyDeviceId();
        final int channel = device.getShellyChannel();
        final String devName = device.getName();

        EXECUTOR.execute(() -> {
            Result res = triggerRelaySync(devName, serverUri, authKey, deviceId, channel, maxRetries);
            postCallback(callback, res);
        });
    }

    public static void checkStatusAsync(String serverUri, String authKey, String deviceId, Callback callback) {
        final String normServer = normalizeServerUri(serverUri);
        final String normKey = authKey != null ? authKey.trim() : "";
        final String normId = normalizeDeviceId(deviceId);

        EXECUTOR.execute(() -> {
            Result res = checkDeviceStatusSync(normServer, normKey, normId);
            postCallback(callback, res);
        });
    }

    public static Result triggerRelaySync(
            String deviceLabel,
            String serverUri,
            String authKey,
            String deviceId,
            int channel,
            int maxRetries
    ) {
        String normServer = normalizeServerUri(serverUri);
        String normId = normalizeDeviceId(deviceId);
        String normKey = authKey != null ? authKey.trim() : "";

        if (normServer.isEmpty() || normId.isEmpty() || normKey.isEmpty()) {
            return new Result(false, false, "Parametri Shelly Cloud incompleti (Server, Device ID o Auth Key).", 0);
        }

        String endpoint = normServer + "/device/relay/control";
        int attempts = Math.max(1, maxRetries + 1);
        Result lastResult = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            HttpURLConnection conn = null;
            try {
                String body = "id=" + URLEncoder.encode(normId, "UTF-8")
                        + "&auth_key=" + URLEncoder.encode(normKey, "UTF-8")
                        + "&channel=" + Math.max(0, channel)
                        + "&turn=on";

                byte[] postBytes = body.getBytes(StandardCharsets.UTF_8);
                URL url = new URL(endpoint);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setDoOutput(true);
                conn.setUseCaches(false);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                conn.setRequestProperty("Accept", "application/json");
                conn.setFixedLengthStreamingMode(postBytes.length);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(postBytes);
                    os.flush();
                }

                int httpCode = conn.getResponseCode();
                String responseBody = readStream(httpCode >= 400 ? conn.getErrorStream() : conn.getInputStream());

                if (httpCode >= 500 && attempt < attempts) {
                    logDebug("SHELLY:" + normId, "HTTP " + httpCode + " (tentativo " + attempt + "/" + attempts + "), nuovo tentativo...");
                    Thread.sleep(1500L);
                    continue;
                }

                if (httpCode == 200) {
                    JSONObject json = new JSONObject(responseBody);
                    boolean isOk = json.optBoolean("isok", false);
                    if (isOk) {
                        logDebug("SHELLY:" + normId, "TX OK -> Impulso apertura inviato a " + deviceLabel + " (ch=" + channel + ")");
                        return new Result(true, true, "Comando di apertura inviato con successo!", httpCode);
                    } else {
                        String errDetail = extractShellyErrors(json);
                        logDebug("SHELLY:" + normId, "ERR Cloud -> " + errDetail);
                        return new Result(false, false, "Shelly Cloud ha rifiutato il comando: " + errDetail, httpCode);
                    }
                } else {
                    String msg = "Errore HTTP " + httpCode + (httpCode == 401 || httpCode == 403 ? " (Auth Key non valida)" : "");
                    logDebug("SHELLY:" + normId, msg);
                    return new Result(false, false, msg, httpCode);
                }

            } catch (Exception e) {
                Log.w(TAG, "Errore rete Shelly (tentativo " + attempt + "/" + attempts + ")", e);
                lastResult = new Result(false, false, "Errore di rete: " + e.getClass().getSimpleName() + " (" + e.getMessage() + ")", 0);
                if (attempt < attempts) {
                    logDebug("SHELLY:" + normId, "Rete instabile (tentativo " + attempt + "/" + attempts + "), retry tra 1.5s...");
                    try {
                        Thread.sleep(1500L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    logDebug("SHELLY:" + normId, "ERR Rete -> " + e.getMessage());
                }
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        return lastResult != null ? lastResult : new Result(false, false, "Errore sconosciuto Shelly Cloud.", 0);
    }

    public static Result checkDeviceStatusSync(String normServer, String normKey, String normId) {
        if (normServer.isEmpty() || normId.isEmpty() || normKey.isEmpty()) {
            return new Result(false, false, "Inserisci Server URI, Device ID e Auth Key prima del test.", 0);
        }

        HttpURLConnection conn = null;
        try {
            String endpoint = normServer + "/device/status";
            String body = "id=" + URLEncoder.encode(normId, "UTF-8")
                    + "&auth_key=" + URLEncoder.encode(normKey, "UTF-8");

            byte[] postBytes = body.getBytes(StandardCharsets.UTF_8);
            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setFixedLengthStreamingMode(postBytes.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(postBytes);
                os.flush();
            }

            int httpCode = conn.getResponseCode();
            String responseBody = readStream(httpCode >= 400 ? conn.getErrorStream() : conn.getInputStream());

            if (httpCode == 200) {
                JSONObject json = new JSONObject(responseBody);
                boolean isOk = json.optBoolean("isok", false);
                if (!isOk) {
                    String err = extractShellyErrors(json);
                    return new Result(false, false, "Credenziali o ID rifiutati da Shelly Cloud:\n" + err, httpCode);
                }
                JSONObject data = json.optJSONObject("data");
                boolean online = data != null && data.optBoolean("online", false);
                if (online) {
                    return new Result(true, true, "Connessione riuscita! Dispositivo '" + normId + "' trovato ed ONLINE su Shelly Cloud.", httpCode);
                } else {
                    return new Result(true, false, "Autenticazione riuscita, ma il dispositivo '" + normId + "' risulta attualmente OFFLINE su Shelly Cloud.", httpCode);
                }
            } else {
                return new Result(false, false, "Risposta HTTP " + httpCode + " dal server " + normServer, httpCode);
            }
        } catch (Exception e) {
            return new Result(false, false, "Impossibile contattare " + normServer + ":\n" + e.getMessage(), 0);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String extractShellyErrors(JSONObject json) {
        JSONObject errors = json.optJSONObject("errors");
        if (errors == null) {
            return json.toString();
        }
        StringBuilder sb = new StringBuilder();
        Iterator<String> keys = errors.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            if (sb.length() > 0) sb.append("; ");
            sb.append(k).append(": ").append(errors.optString(k));
        }
        return sb.toString();
    }

    private static String readStream(InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static void postCallback(Callback cb, Result res) {
        if (cb == null) return;
        Handler h = mainHandler;
        if (h == null) {
            synchronized (ShellyCloudClient.class) {
                if (mainHandler == null) {
                    mainHandler = new Handler(Looper.getMainLooper());
                }
                h = mainHandler;
            }
        }
        h.post(() -> cb.onComplete(res));
    }

    private static void logDebug(String sourceId, String info) {
        try {
            boolean isTx = info != null && info.startsWith("TX");
            DebugLogger.getInstance().addLog(new DebugLogEntry(System.currentTimeMillis(), isTx, sourceId, info));
        } catch (Exception ignored) {}
    }
}
