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
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.model.Pane;
import androidx.car.app.model.PaneTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import java.util.List;

public class ApritiSedanoCarScreen extends Screen {

    public ApritiSedanoCarScreen(CarContext carContext) {
        super(carContext);
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

        androidx.core.graphics.drawable.IconCompat iconCompat = androidx.core.graphics.drawable.IconCompat.createWithResource(getCarContext(), R.drawable.ic_garage);
        androidx.car.app.model.CarIcon carIcon = new androidx.car.app.model.CarIcon.Builder(iconCompat).setTint(CarColor.DEFAULT).build();

        ItemList.Builder itemListBuilder = new ItemList.Builder();
        
        for (Device device : devices) {
            itemListBuilder.addItem(new Row.Builder()
                .setTitle(device.getName())
                .addText(device.getMacAddress())
                .setImage(carIcon)
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

        return new ListTemplate.Builder()
            .setTitle("I Miei Dispositivi")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(itemListBuilder.build())
            .build();
    }
}
