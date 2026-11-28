package com.example.mobilescholarschool.Security;

import android.os.SystemClock;

import java.util.concurrent.TimeUnit;

/**
 * NEW: the LOCAL-ONLY session created after a successful offline password check.
 *
 * LESSON 1: THIS IS NOT A FIREBASE SESSION. It holds no token and no credential, only "who" and
 * "since when". FirebaseAuth.getCurrentUser() stays null, so every Firestore rule and server
 * check still treats the phone as signed out. Code that needs real authorization must use
 * OnlineOnlyGuard, never OfflineSession.isActive().
 *
 * LESSON 2: WHY IN MEMORY ONLY? When Android kills the app, the session disappears and the
 * student types the password again. That is the safe default for something that was never
 * proven by the server. (We also keep NO "remember me" flag on disk that a thief could copy.)
 *
 * LESSON 3: EXPIRY. A session ends after IDLE_TIMEOUT of inactivity or MAX_LIFETIME in total.
 * We measure with elapsedRealtime (time since boot), which the user cannot change in Settings,
 * unlike the wall clock.
 */
public final class OfflineSession {

    private static final long IDLE_TIMEOUT = TimeUnit.MINUTES.toMillis(15);
    private static final long MAX_LIFETIME = TimeUnit.HOURS.toMillis(8);

    private static String uid;
    private static String email;
    private static long startedAt;
    private static long lastActiveAt;

    private OfflineSession() { }

    /** Call only after OfflineAuthManager.signInOffline(...).isOk(). */
    public static synchronized void start(String sessionUid, String sessionEmail) {
        uid = sessionUid;
        email = sessionEmail;
        startedAt = SystemClock.elapsedRealtime();
        lastActiveAt = startedAt;
    }

    /** True while a valid offline session exists. Ends it (and returns false) if it timed out. */
    public static synchronized boolean isActive() {
        if (uid == null) {
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastActiveAt > IDLE_TIMEOUT || now - startedAt > MAX_LIFETIME) {
            end();
            return false;
        }
        return true;
    }

    /** Call from screens' onResume/user actions so a student who is working is not timed out. */
    public static synchronized void touch() {
        if (isActive()) {
            lastActiveAt = SystemClock.elapsedRealtime();
        }
    }

    /** The student's Firebase uid (same value as online) or null when there is no session. */
    public static synchronized String uid() {
        return isActive() ? uid : null;
    }

    public static synchronized String email() {
        return isActive() ? email : null;
    }

    /** Sign out of offline mode, or switch to a real session after online re-authentication. */
    public static synchronized void end() {
        uid = null;
        email = null;
        startedAt = 0;
        lastActiveAt = 0;
    }
}
