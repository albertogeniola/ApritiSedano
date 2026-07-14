package it.geniola.apritisedano;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;

public class SecretStore {

    private static final String PREFS_FILENAME = "apritisedano_secure_prefs";
    private static final String KEY_SECRET = "secret_key";

    private static SharedPreferences getEncryptedSharedPreferences(Context context) throws GeneralSecurityException, IOException {
        MasterKey masterKey = new MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();

        return EncryptedSharedPreferences.create(
                context,
                PREFS_FILENAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        );
    }

    public static String getSecretKey(Context context) {
        try {
            SharedPreferences sharedPreferences = getEncryptedSharedPreferences(context);
            return sharedPreferences.getString(KEY_SECRET, "");
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    public static void setSecretKey(Context context, String secret) {
        try {
            SharedPreferences sharedPreferences = getEncryptedSharedPreferences(context);
            sharedPreferences.edit().putString(KEY_SECRET, secret).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
