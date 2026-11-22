package com.example.mobilescholarschool.Components;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.example.mobilescholarschool.Data.AppDatabase;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.UUID;

/**
 * NEW: "who is using the app?", answered in ONE place.
 *
 * LESSON: IDENTITY OFFLINE. Firebase cannot create an account without internet, so a student who
 * signs up offline has no Firebase user and no user id. But every row we save (requests, bookings,
 * profile edits) must belong to somebody. So at sign-up we invent a PROVISIONAL id, "local-<random>".
 *
 *   signed in            uid() = the real Firebase uid
 *   signed up offline    uid() = the provisional id   (isProvisional() is true)
 *   nobody               uid() = null
 *
 * Everything the student does is saved under whichever id applies. When the account is finally
 * created, SyncWorker re-labels all those rows with the real uid (AppDatabase.rekeyUid), then
 * sends them.
 *
 * The provisional id is NOT a secret, so it lives in ordinary SharedPreferences (fast: screens
 * read it on every launch). The password stays in the encrypted PendingRegistrationStore.
 */
public final class SessionUser {

    private static final String PREFS = "session";
    private static final String KEY_PROVISIONAL_UID = "provisional_uid";
    private static final String KEY_REGISTRATION_ERROR = "registration_error";

    private SessionUser() { }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** True when a real Firebase account is signed in. */
    public static boolean isSignedIn() {
        return FirebaseAuth.getInstance().getCurrentUser() != null;
    }

    /** The provisional id of an offline sign-up that has not been delivered yet, or null. */
    @Nullable
    public static String provisionalUid(Context c) {
        return prefs(c).getString(KEY_PROVISIONAL_UID, null);
    }

    /** True for a student whose offline sign-up is still waiting for internet. */
    public static boolean isProvisional(Context c) {
        return !isSignedIn() && provisionalUid(c) != null;
    }

    /** True if the app may show its screens at all (a real account OR a waiting sign-up). */
    public static boolean hasSession(Context c) {
        return isSignedIn() || provisionalUid(c) != null;
    }

    /** The id to save rows under. Null only when nobody is using the app. */
    @Nullable
    public static String uid(Context c) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user != null ? user.getUid() : provisionalUid(c);
    }

    /** A new random provisional id. It is not stored until setProvisional() is called. */
    public static String newProvisionalUid() {
        return "local-" + UUID.randomUUID();
    }

    /** Call AFTER the registration has been saved in PendingRegistrationStore. */
    public static void setProvisional(Context c, String uid) {
        prefs(c).edit().putString(KEY_PROVISIONAL_UID, uid).remove(KEY_REGISTRATION_ERROR).apply();
    }

    public static void clearProvisional(Context c) {
        prefs(c).edit().remove(KEY_PROVISIONAL_UID).remove(KEY_REGISTRATION_ERROR).apply();
    }

    // ---- why a waiting sign-up failed for good (shown on Home, with a "Start over" button)

    public static void setRegistrationError(Context c, String message) {
        prefs(c).edit().putString(KEY_REGISTRATION_ERROR, message).apply();
    }

    @Nullable
    public static String registrationError(Context c) {
        return prefs(c).getString(KEY_REGISTRATION_ERROR, null);
    }

    /**
     * Throws away a waiting sign-up AND everything saved under its provisional id.
     * Touches the disk: call from a background thread (DbExecutor).
     */
    public static void discardPendingRegistration(Context c) {
        String uid = provisionalUid(c);
        try {
            PendingRegistrationStore.clear(c);
        } catch (Exception ignored) {
            // nothing more we can do; the rows below are still removed
        }
        if (uid != null) {
            AppDatabase.get(c).deleteAllFor(uid);
        }
        clearProvisional(c);
    }
}
