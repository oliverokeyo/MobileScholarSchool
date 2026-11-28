package com.example.mobilescholarschool.Data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

/**
 * NEW: reads and writes the offline_auth table. It is ONLY storage: every security decision
 * (is the password right? is the student locked out?) is made by Security/OfflineAuthManager.
 *
 * LESSON: keep storage and decisions apart. This class can be read in a minute and has no
 * rules to get wrong; the rules live in one place that can be reviewed carefully.
 *
 * Call from a background thread (see DbExecutor).
 */
public final class OfflineAuthRepository {

    /** One row of offline_auth. */
    public static final class Record {
        public String uid, email, algorithm;
        public byte[] salt, verifier;
        public int iterations, failedAttempts;
        public long lastOnlineMillis, lockedUntilMillis, lastSeenMillis;
    }

    private OfflineAuthRepository() { }

    /**
     * Creates or replaces the student's verifier. Called after a successful ONLINE sign-in, so it
     * also resets the failed-attempt counter (Firebase has just proven the password).
     *
     * CONFLICT_REPLACE: if a row with the same uid OR the same email exists it is replaced, so a
     * changed password simply overwrites the old verifier.
     */
    public static void save(Context c, String uid, String email, byte[] salt, int iterations,
                            String algorithm, byte[] verifier, long nowMillis) {
        ContentValues v = new ContentValues();
        v.put("uid", uid);
        v.put("email", email);
        v.put("algorithm", algorithm);
        v.put("iterations", iterations);
        v.put("salt", salt);
        v.put("verifier", verifier);
        v.put("last_online_millis", nowMillis);
        v.put("failed_attempts", 0);
        v.put("locked_until_millis", 0);
        v.put("last_seen_millis", nowMillis);
        AppDatabase.get(c).getWritableDatabase().insertWithOnConflict(
                AppDatabase.T_OFFLINE_AUTH, null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** @return the record for this (already normalised) email, or null if never signed in online */
    public static Record findByEmail(Context c, String email) {
        SQLiteDatabase db = AppDatabase.get(c).getReadableDatabase();
        try (Cursor cur = db.query(AppDatabase.T_OFFLINE_AUTH, null, "email = ?",
                new String[]{email}, null, null, null)) {
            return cur.moveToFirst() ? read(cur) : null;
        }
    }

    public static boolean existsForEmail(Context c, String email) {
        return findByEmail(c, email) != null;
    }

    /** A wrong password: remember the new count and (maybe) the time the lock ends. */
    public static void recordFailure(Context c, String uid, int failedAttempts,
                                     long lockedUntilMillis, long lastSeenMillis) {
        ContentValues v = new ContentValues();
        v.put("failed_attempts", failedAttempts);
        v.put("locked_until_millis", lockedUntilMillis);
        v.put("last_seen_millis", lastSeenMillis);
        AppDatabase.get(c).getWritableDatabase().update(AppDatabase.T_OFFLINE_AUTH, v,
                "uid = ?", new String[]{uid});
    }

    /** A correct password: clear the counters. */
    public static void recordSuccess(Context c, String uid, long lastSeenMillis) {
        recordFailure(c, uid, 0, 0, lastSeenMillis);
    }

    public static void delete(Context c, String uid) {
        AppDatabase.get(c).getWritableDatabase().delete(AppDatabase.T_OFFLINE_AUTH,
                "uid = ?", new String[]{uid});
    }

    public static void deleteByEmail(Context c, String email) {
        AppDatabase.get(c).getWritableDatabase().delete(AppDatabase.T_OFFLINE_AUTH,
                "email = ?", new String[]{email});
    }

    private static Record read(Cursor cur) {
        Record r = new Record();
        r.uid = cur.getString(cur.getColumnIndexOrThrow("uid"));
        r.email = cur.getString(cur.getColumnIndexOrThrow("email"));
        r.algorithm = cur.getString(cur.getColumnIndexOrThrow("algorithm"));
        r.iterations = cur.getInt(cur.getColumnIndexOrThrow("iterations"));
        r.salt = cur.getBlob(cur.getColumnIndexOrThrow("salt"));
        r.verifier = cur.getBlob(cur.getColumnIndexOrThrow("verifier"));
        r.lastOnlineMillis = cur.getLong(cur.getColumnIndexOrThrow("last_online_millis"));
        r.failedAttempts = cur.getInt(cur.getColumnIndexOrThrow("failed_attempts"));
        r.lockedUntilMillis = cur.getLong(cur.getColumnIndexOrThrow("locked_until_millis"));
        r.lastSeenMillis = cur.getLong(cur.getColumnIndexOrThrow("last_seen_millis"));
        return r;
    }
}
