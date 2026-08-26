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
import androidx.car.app.model.Pane;
import androidx.car.app.model.PaneTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.core.graphics.drawable.IconCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

public class ApritiSedanoOperatingScreen extends Screen implements DefaultLifecycleObserver {
    private final String operatingDeviceName;

    private final BroadcastReceiver resultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ApritiSedanoService.ACTION_OPERATION_RESULT.equals(intent.getAction())) {
                String state = intent.getStringExtra(ApritiSedanoService.EXTRA_BOX_STATE);
                String display = "Sconosciuto";
                if ("OPEN".equals(state)) {
                    display = "APERTO";
                } else if ("CLOSED".equals(state)) {
                    display = "CHIUSO";
                }
                CarToast.makeText(getCarContext(), "Operazione completata: " + display, CarToast.LENGTH_LONG).show();
                getScreenManager().pop();
            } else if (ApritiSedanoService.ACTION_SERVICE_STOPPED.equals(intent.getAction())) {
                getScreenManager().pop();
            }
        }
    };

    public ApritiSedanoOperatingScreen(CarContext carContext, String deviceName) {
        super(carContext);
        this.operatingDeviceName = deviceName;
        getLifecycle().addObserver(this);
    }

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ApritiSedanoService.ACTION_OPERATION_RESULT);
        filter.addAction(ApritiSedanoService.ACTION_SERVICE_STOPPED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getCarContext().registerReceiver(resultReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            getCarContext().registerReceiver(resultReceiver, filter);
        }
    }

    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        getCarContext().unregisterReceiver(resultReceiver);
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        IconCompat iconCompat = IconCompat.createWithResource(getCarContext(), R.drawable.ic_garage);
        CarIcon carIcon = new CarIcon.Builder(iconCompat).setTint(CarColor.DEFAULT).build();

        Pane.Builder paneBuilder = new Pane.Builder();
        
        Action.Builder cancelActionBuilder = new Action.Builder()
            .setTitle("ANNULLA")
            .setBackgroundColor(CarColor.RED)
            .setOnClickListener(() -> {
                Intent serviceIntent = new Intent(getCarContext(), ApritiSedanoService.class);
                getCarContext().stopService(serviceIntent);
                getScreenManager().pop();
            });
            
        paneBuilder.addAction(cancelActionBuilder.build());
        
        Row row = new Row.Builder()
            .setTitle(operatingDeviceName)
            .addText("Trasmissione e attesa segnale in corso...")
            .setImage(carIcon, Row.IMAGE_TYPE_LARGE)
            .build();
            
        paneBuilder.addRow(row);
        paneBuilder.setImage(carIcon);
        
        return new PaneTemplate.Builder(paneBuilder.build())
            .setTitle("ApritiSedano")
            .setHeaderAction(Action.BACK)
            .build();
    }
}
