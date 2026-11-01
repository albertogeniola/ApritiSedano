package it.geniola.apritisedano;

import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.car.app.Screen;
import androidx.car.app.Session;

public class ApritiSedanoCarSession extends Session {
    @NonNull
    @Override
    public Screen onCreateScreen(@NonNull Intent intent) {
        ProximityAutoOpenService.startMonitoring(
                getCarContext(),
                ProximityAutoOpenService.ACTION_START_FROM_CAR_SESSION
        );
        return new ApritiSedanoCarScreen(getCarContext());
    }
}
