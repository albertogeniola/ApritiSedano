package it.geniola.apritisedano;

import android.content.pm.ApplicationInfo;
import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.Session;
import androidx.car.app.validation.HostValidator;

public class ApritiSedanoCarAppService extends CarAppService {

    @NonNull
    @Override
    public HostValidator createHostValidator() {
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
        } else {
            return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR; // For this private app, we allow all
        }
    }

    @NonNull
    @Override
    public Session onCreateSession() {
        return new ApritiSedanoCarSession();
    }
}
