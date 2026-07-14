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
import androidx.car.app.model.Pane;
import androidx.car.app.model.PaneTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

public class ApritiSedanoCarScreen extends Screen implements DefaultLifecycleObserver {
    private boolean isOperating = false;

    private final BroadcastReceiver resultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ApritiSedanoService.ACTION_OPERATION_RESULT.equals(intent.getAction())) {
                isOperating = false;
                String state = intent.getStringExtra(ApritiSedanoService.EXTRA_BOX_STATE);
                String display = "Sconosciuto";
                if ("OPEN".equals(state)) {
                    display = "APERTO";
                } else if ("CLOSED".equals(state)) {
                    display = "CHIUSO";
                }
                CarToast.makeText(getCarContext(), "Operazione completata: " + display, CarToast.LENGTH_LONG).show();
                invalidate();
            } else if (ApritiSedanoService.ACTION_SERVICE_STOPPED.equals(intent.getAction())) {
                isOperating = false;
                invalidate();
            }
        }
    };

    public ApritiSedanoCarScreen(CarContext carContext) {
        super(carContext);
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
        Pane.Builder paneBuilder = new Pane.Builder();
        
        if (isOperating) {
            paneBuilder.setLoading(true);
        } else {
            // Icona del garage (che abbiamo creato)
            androidx.core.graphics.drawable.IconCompat iconCompat = androidx.core.graphics.drawable.IconCompat.createWithResource(getCarContext(), R.drawable.ic_magic_box);
            androidx.car.app.model.CarIcon carIcon = new androidx.car.app.model.CarIcon.Builder(iconCompat).setTint(CarColor.DEFAULT).build();

            Action openAction = new Action.Builder()
                .setTitle("APRI SEDANO")
                .setBackgroundColor(CarColor.BLUE)
                .setOnClickListener(() -> {
                    isOperating = true;
                    invalidate(); // Aggiorna la UI per mostrare il caricamento
                    
                    Intent serviceIntent = new Intent(getCarContext(), ApritiSedanoService.class);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        getCarContext().startForegroundService(serviceIntent);
                    } else {
                        getCarContext().startService(serviceIntent);
                    }
                })
                .build();
            
            paneBuilder.addAction(openAction);
            
            Row row = new Row.Builder()
                .setTitle("ApritiSedano è Pronto")
                .addText("Premi il pulsante per aprire o chiudere il portone del tuo garage.")
                .setImage(carIcon, Row.IMAGE_TYPE_LARGE)
                .build();
                
            paneBuilder.addRow(row);
            paneBuilder.setImage(carIcon);
        }
        
        return new PaneTemplate.Builder(paneBuilder.build())
            .setTitle("ApritiSedano")
            .setHeaderAction(Action.APP_ICON)
            .build();
    }
}
