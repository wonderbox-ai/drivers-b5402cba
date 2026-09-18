package com.local.planomagic;

import android.util.Base64;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public final class BackupCrypto {
    private static final int ITERATIONS = 180000;
    private static final int KEY_BITS = 256;

    public static String encrypt(String plaintext, String password) throws Exception {
        byte[] salt = new byte[16];
        byte[] iv = new byte[12];
        SecureRandom random = new SecureRandom();
        random.nextBytes(salt);
        random.nextBytes(iv);

        SecretKeySpec key = derive(password.toCharArray(), salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        JSONObject out = new JSONObject();
        out.put("format", "wonderbackup");
        out.put("version", 1);
        out.put("salt", Base64.encodeToString(salt, Base64.NO_WRAP));
        out.put("iv", Base64.encodeToString(iv, Base64.NO_WRAP));
        out.put("data", Base64.encodeToString(ciphertext, Base64.NO_WRAP));
        return out.toString();
    }

    public static String decrypt(String encrypted, String password) throws Exception {
        JSONObject in = new JSONObject(encrypted);
        if (!"wonderbackup".equals(in.optString("format"))) {
            throw new IllegalArgumentException("Format de sauvegarde inconnu");
        }

        byte[] salt = Base64.decode(in.getString("salt"), Base64.NO_WRAP);
        byte[] iv = Base64.decode(in.getString("iv"), Base64.NO_WRAP);
        byte[] data = Base64.decode(in.getString("data"), Base64.NO_WRAP);

        SecretKeySpec key = derive(password.toCharArray(), salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] plaintext = cipher.doFinal(data);
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    private static SecretKeySpec derive(char[] password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, KEY_BITS);
        try {
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
            return new SecretKeySpec(key, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private BackupCrypto() {}
}
