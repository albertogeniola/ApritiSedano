package it.geniola.apritisedano;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.car.app.notification.CarAppExtender;
import androidx.car.app.notification.CarNotificationManager;
import androidx.car.app.notification.CarPendingIntent;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

/**
 * Gestisce sia la notifica silenziosa di monitoraggio GPS (solo smartphone),
 * sia gli avvisi Heads-Up ad alta priorità proiettati anche sul display di Android Auto.
 */
public class CarNotificationHelper {
    public static final String CHANNEL_MONITOR_SILENT = "apritisedano_proximity_monitor";
    public static final String CHANNEL_CAR_ALERTS = "apritisedano_car_alerts";

    public static final int MONITOR_NOTIFICATION_ID = 201;
    private static int nextAlertNotificationId = 300;

    public static void ensureChannels(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel monitorChannel = new NotificationChannel(
                    CHANNEL_MONITOR_SILENT,
                    "Monitoraggio Rientro Android Auto",
                    NotificationManager.IMPORTANCE_LOW
            );
            monitorChannel.setDescription("Stato del tracciamento GPS adattivo durante la guida con Android Auto");
            monitorChannel.setShowBadge(false);
            nm.createNotificationChannel(monitorChannel);
        }

        CarNotificationManager carNm = CarNotificationManager.from(context);
        NotificationChannelCompat carAlertChannel = new NotificationChannelCompat.Builder(
                CHANNEL_CAR_ALERTS,
                NotificationManagerCompat.IMPORTANCE_HIGH
        )
                .setName("Apertura Automatica & Benvenuto (Android Auto)")
                .setDescription("Avvisi Heads-Up di rientro a casa e apertura cancello visibili anche su Android Auto")
                .setVibrationEnabled(true)
                .build();
        carNm.createNotificationChannel(carAlertChannel);
    }

    /**
     * Notifica silenziosa per il Foreground Service GPS (visibile solo sul telefono, NON disturba Android Auto).
     */
    public static Notification buildSilentMonitorNotification(Context context, String statusText) {
        ensureChannels(context);

        Intent openIntent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(
                context,
                210,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(context, ProximityAutoOpenService.class)
                .setAction(ProximityAutoOpenService.ACTION_STOP_MONITORING);
        PendingIntent stopPi = PendingIntent.getService(
                context,
                211,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(context, CHANNEL_MONITOR_SILENT)
                .setSmallIcon(R.drawable.ic_gate)
                .setContentTitle("Rientro Automatico (Android Auto)")
                .setContentText(statusText)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(contentPi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Sospendi", stopPi)
                .build();
    }

    public static void updateSilentMonitorNotification(Context context, String statusText) {
        if (!hasPostNotificationPermission(context)) return;
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(MONITOR_NOTIFICATION_ID, buildSilentMonitorNotification(context, statusText));
        }
    }

    /**
     * Invia una notifica Heads-Up ad alta priorità visibile sia sullo smartphone sia sullo schermo di Android Auto.
     * Toccandola su Android Auto apre direttamente la lista dispositivi ApritiSedano (pronta per il Box BLE).
     */
    public static void showAutoOpenAlert(Context context, String title, String message) {
        ensureChannels(context);
        if (!hasPostNotificationPermission(context)) return;

        try {
            Intent phoneIntent = new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent phonePi = PendingIntent.getActivity(
                    context,
                    220,
                    phoneIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            Intent carIntent = new Intent(Intent.ACTION_VIEW)
                    .setComponent(new ComponentName(context, ApritiSedanoCarAppService.class));
            PendingIntent carPi = CarPendingIntent.getCarApp(
                    context,
                    221,
                    carIntent,
                    0
            );

            CarAppExtender carExtender = new CarAppExtender.Builder()
                    .setContentTitle(title)
                    .setContentText(message)
                    .setSmallIcon(R.drawable.ic_gate)
                    .setContentIntent(carPi)
                    .setImportance(NotificationManagerCompat.IMPORTANCE_HIGH)
                    .build();

            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_CAR_ALERTS)
                    .setSmallIcon(R.drawable.ic_gate)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setAutoCancel(true)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setContentIntent(phonePi)
                    .extend(carExtender);

            int notifId = nextAlertNotificationId++;
            if (nextAlertNotificationId > 350) {
                nextAlertNotificationId = 300;
            }
            CarNotificationManager.from(context).notify(notifId, builder);
        } catch (SecurityException ignored) {
        }
    }

    private static boolean hasPostNotificationPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }
}
