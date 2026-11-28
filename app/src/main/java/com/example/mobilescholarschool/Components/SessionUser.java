package com.example.mobilescholarschool.Components;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.example.mobilescholarschool.Data.AppDatabase;
import com.example.mobilescholarschool.Data.DbExecutor;           // NEW (complete sign-out)
import com.example.mobilescholarschool.Security.OfflineAuthManager;   // NEW (offline sign-in)
import com.example.mobilescholarschool.Security.OfflineSession;   // NEW (offline sign-in)
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
 *   offline sign-in      uid() = the real uid, from OfflineSession (isOfflineSession() is true)
 *   signed up offline    uid() = the provisional id   (isProvisional() is true)
 *   nobody               uid() = null
 *
 * Everything the student does is saved under whichever id applies. When the account is finally
 * created, SyncWorker re-labels all those rows with the real uid (AppDatabase.rekeyUid), then
 * sends them.
 *
 * CHANGED (offline sign-in): an OFFLINE SESSION is a third way to "have a session". The student
 * proved the password against the verifier saved on this phone, so they may see their cached data,
 * but FirebaseAuth still has NO user. isSignedIn() therefore stays false, so nothing that asks
 * "is this a real, server-approved session?" can be fooled (see Security/OnlineOnlyGuard).
 *
 * The provisional id is NOT a secret, so it lives in ordinary SharedPreferences (fast: screens
 * read it on every launch). The password stays in the encrypted PendingRegistrationStore.
 */
public final class SessionUser {

    /**
     * POLICY SWITCH for shared phones. After sign-out the offline sign-in verifier (a salted hash,
     * never the password) normally STAYS, so the same student can still sign in offline later;
     * it cannot log anyone in without the password and it expires after 14 days. Set this to
     * true if you would rather every sign-out also removes offline sign-in for that student.
     */
    public static final boolean FORGET_OFFLINE_SIGN_IN_ON_SIGN_OUT = false;

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
        return !isSignedIn() && !OfflineSession.isActive() && provisionalUid(c) != null;
    }

    /**
     * NEW: true while the student is using the app on a LOCAL-ONLY offline session.
     * This is NOT a Firebase session: use it to decide what to SHOW, never what to ALLOW.
     */
    public static boolean isOfflineSession() {
        return !isSignedIn() && OfflineSession.isActive();
    }

    /** True if the app may show its screens at all (a real account OR a waiting sign-up). */
    public static boolean hasSession(Context c) {
        // CHANGED: a valid offline session also lets the student see their saved data
        return isSignedIn() || OfflineSession.isActive() || provisionalUid(c) != null;
    }

    /** The id to save rows under. Null only when nobody is using the app. */
    @Nullable
    public static String uid(Context c) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) {
            return user.getUid();
        }
        // NEW: offline sign-in uses the student's REAL uid, so rows saved while offline already
        // carry the right owner and the SyncWorker can send them after re-authentication.
        String offlineUid = OfflineSession.uid();
        return offlineUid != null ? offlineUid : provisionalUid(c);
    }

    /**
     * NEW: may SyncWorker still create / sign in the registration that is waiting in the
     * PendingRegistrationStore?
     *
     * LESSON: a waiting registration is only valid while the APP ITSELF still says "this sign-up
     * is waiting" (the provisional marker is set and is the same one the registration was saved
     * with). If the marker is gone, the student signed out or cancelled, and the leftover
     * registration must NEVER sign anybody in. Before this check the worker signed in whatever
     * was in the store whenever nobody was signed in, which silently brought back an account the
     * student had signed out of.
     */
    public static boolean isRegistrationStillWanted(Context c, @Nullable String registrationLocalUid) {
        String marker = provisionalUid(c);
        if (marker == null) {
            return false;
        }
        return registrationLocalUid == null || registrationLocalUid.isEmpty()
                || registrationLocalUid.equals(marker);
    }

    /**
     * NEW: THE one complete sign-out. Every "Sign out" button must call this, never
     * FirebaseAuth.signOut() on its own.
     *
     * The ORDER matters (LESSON: close the doors before you leave):
     *   1. (instantly, this thread) end the offline session and clear the provisional marker, so
     *      neither can bring the student back in, and SyncWorker's check now says "not wanted";
     *   2. (disk thread) throw away any registration waiting in the encrypted store. This was the
     *      hidden auto-login: the worker would create / sign in that account again;
     *   3. sign out of Firebase (this also removes Firebase's saved login from the phone);
     *   4. forget this student's cached rows that the server already has. Rows still waiting to be
     *      sent are KEPT, so no work is lost, and they only ever reappear for their owner;
     *   5. optionally forget the offline sign-in verifier (see the policy switch above).
     * Nothing here touches the Firebase ACCOUNT, Firestore records, settings or onboarding.
     *
     * @param uidToClear the uid being signed out (may be null)
     * @param onDone     runs on the MAIN thread when everything above has finished: navigate there
     */
    public static void signOut(Context c, @Nullable String uidToClear, @Nullable Runnable onDone) {
        final Context app = c.getApplicationContext();

        OfflineSession.end();
        clearProvisional(app);

        DbExecutor.async(() -> {
            try {
                PendingRegistrationStore.clear(app);
            } catch (Exception ignored) {
                // The marker is already gone, so the worker will refuse it anyway
            }
            FirebaseAuth.getInstance().signOut();
            if (uidToClear != null) {
                AppDatabase.get(app).clearSyncedFor(uidToClear);
                if (FORGET_OFFLINE_SIGN_IN_ON_SIGN_OUT) {
                    OfflineAuthManager.forget(app, uidToClear);
                }
            }
            return Boolean.TRUE;
        }, done -> {
            if (onDone != null) {
                onDone.run();
            }
        });
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