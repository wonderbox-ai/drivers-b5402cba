package com.local.planomagic;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class PinManager {
    private static final String PREFS = "wonder_apps_security";
    private static final String KEY_MODE = "unlock_mode";
    private static final String KEY_SALT = "pin_salt";
    private static final String KEY_HASH = "pin_hash";
    private static final int ITERATIONS = 150000;
    private static final int KEY_BITS = 256;

    public static final String MODE_BIOMETRIC = "biometric";
    public static final String MODE_PIN = "pin";
    public static final String MODE_DISABLED = "disabled";

    private final SharedPreferences prefs;

    public PinManager(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getMode() {
        return prefs.getString(KEY_MODE, MODE_BIOMETRIC);
    }

    public void setMode(String mode) {
        prefs.edit().putString(KEY_MODE, mode).apply();
    }

    public boolean hasPin() {
        return !prefs.getString(KEY_SALT, "").isEmpty()
                && !prefs.getString(KEY_HASH, "").isEmpty();
    }

    public void setPin(String pin) {
        try {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            byte[] hash = derive(pin.toCharArray(), salt);
            prefs.edit()
                    .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                    .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                    .apply();
        } catch (Exception e) {
            throw new IllegalStateException("Impossible de sécuriser le code PIN", e);
        }
    }

    public boolean verifyPin(String pin) {
        try {
            String saltB64 = prefs.getString(KEY_SALT, "");
            String hashB64 = prefs.getString(KEY_HASH, "");
            if (saltB64.isEmpty() || hashB64.isEmpty()) return false;
            byte[] salt = Base64.decode(saltB64, Base64.NO_WRAP);
            byte[] expected = Base64.decode(hashB64, Base64.NO_WRAP);
            byte[] actual = derive(pin.toCharArray(), salt);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    public void clearPin() {
        prefs.edit().remove(KEY_SALT).remove(KEY_HASH).apply();
    }

    private byte[] derive(char[] pin, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(pin, salt, ITERATIONS, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
        } finally {
            spec.clearPassword();
        }
    }
}
