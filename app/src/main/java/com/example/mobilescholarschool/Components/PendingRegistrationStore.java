package com.example.mobilescholarschool.Components;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONObject;

/** Encrypted "outbox" holding one registration until it has been synced to Firebase. */
public final class PendingRegistrationStore {

    private static final String KEY = "pending";

    private PendingRegistrationStore() { }

    private static SharedPreferences prefs(Context c) throws Exception {
        MasterKey mk = new MasterKey.Builder(c)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
        return EncryptedSharedPreferences.create(c, "pending_registration", mk,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    }

    public static void save(Context c, JSONObject data) throws Exception {
        prefs(c).edit().putString(KEY, data.toString()).apply();
    }

    /** @return the saved registration, or null if nothing is waiting */
    public static JSONObject load(Context c) throws Exception {
        String s = prefs(c).getString(KEY, null);
        return s == null ? null : new JSONObject(s);
    }

    public static void clear(Context c) throws Exception {
        prefs(c).edit().remove(KEY).apply();
    }
}