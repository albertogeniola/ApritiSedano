package it.geniola.apritisedano;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.CarToast;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.CarColor;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.core.graphics.drawable.IconCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import java.util.List;

public class ApritiSedanoCarScreen extends Screen {

    private final BroadcastReceiver proximityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            invalidate();
        }
    };

    public ApritiSedanoCarScreen(CarContext carContext) {
        super(carContext);
        getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onStart(@NonNull LifecycleOwner owner) {
                IntentFilter filter = new IntentFilter(ProximityConfigManager.ACTION_PROXIMITY_STATE_CHANGED);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    getCarContext().registerReceiver(proximityReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    getCarContext().registerReceiver(proximityReceiver, filter);
                }
            }

            @Override
            public void onStop(@NonNull LifecycleOwner owner) {
                try {
                    getCarContext().unregisterReceiver(proximityReceiver);
                } catch (Exception ignored) {
                }
            }
        });
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        List<Device> devices = DeviceManager.getDevices(getCarContext());

        if (devices.isEmpty()) {
            return new MessageTemplate.Builder("Nessun dispositivo configurato. Apri l'app sul telefono per aggiungerne uno.")
                    .setTitle("ApritiSedano")
                    .setHeaderAction(Action.APP_ICON)
                    .build();
        }

        IconCompat garageIconCompat = IconCompat.createWithResource(getCarContext(), R.drawable.ic_garage);
        CarIcon garageCarIcon = new CarIcon.Builder(garageIconCompat).setTint(CarColor.DEFAULT).build();

        IconCompat gateIconCompat = IconCompat.createWithResource(getCarContext(), R.drawable.ic_gate);
        CarIcon gateCarIcon = new CarIcon.Builder(gateIconCompat).setTint(CarColor.DEFAULT).build();

        boolean isArmed = ProximityConfigManager.isArmed(getCarContext());
        ItemList.Builder itemListBuilder = new ItemList.Builder();

        for (Device device : devices) {
            if (device.isShellyCloud()) {
                StringBuilder subtitle = new StringBuilder("Shelly Cloud • ID: ")
                        .append(device.getShellyDeviceId());
                if (device.isAutoOpenEnabled()) {
                    subtitle.append(" • Auto ").append(device.getAutoOpenRadiusMeters()).append("m");
                    if (device.isAutoOpenDryRun()) {
                        subtitle.append(" [TEST]");
                    } else if (isArmed) {
                        subtitle.append(" [ARMATO]");
                    }
                }

                itemListBuilder.addItem(new Row.Builder()
                        .setTitle(device.getName())
                        .addText(subtitle.toString())
                        .setImage(gateCarIcon)
                        .setOnClickListener(() -> {
                            CarToast.makeText(
                                    getCarContext(),
                                    "Apertura " + device.getName() + " in corso...",
                                    CarToast.LENGTH_SHORT
                            ).show();

                            ShellyCloudClient.triggerDeviceAsync(device, 1, res -> {
                                String msg = res.success
                                        ? ("Apertura inviata: " + device.getName())
                                        : ("Errore Shelly: " + res.message);
                                CarToast.makeText(getCarContext(), msg, CarToast.LENGTH_LONG).show();
                            });
                        })
                        .build());
            } else {
                itemListBuilder.addItem(new Row.Builder()
                        .setTitle(device.getName())
                        .addText(device.getMacAddress())
                        .setImage(garageCarIcon)
                        .setOnClickListener(() -> {
                            getScreenManager().push(new ApritiSedanoOperatingScreen(getCarContext(), device.getName()));

                            Intent serviceIntent = new Intent(getCarContext(), ApritiSedanoService.class);
                            serviceIntent.putExtra(ApritiSedanoService.EXTRA_TARGET_MAC, device.getMacAddress());
                            serviceIntent.putExtra(ApritiSedanoService.EXTRA_SECRET_KEY, device.getSecretKey());

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                getCarContext().startForegroundService(serviceIntent);
                            } else {
                                getCarContext().startService(serviceIntent);
                            }
                        })
                        .build());
            }
        }

        return new ListTemplate.Builder()
                .setTitle("I Miei Dispositivi")
                .setHeaderAction(Action.APP_ICON)
                .setSingleList(itemListBuilder.build())
                .build();
    }
}
