package it.geniola.apritisedano;

import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.car.app.Screen;
import androidx.car.app.Session;

public class ApritiSedanoCarSession extends Session {
    @NonNull
    @Override
    public Screen onCreateScreen(@NonNull Intent intent) {
        return new ApritiSedanoCarScreen(getCarContext());
    }
}
