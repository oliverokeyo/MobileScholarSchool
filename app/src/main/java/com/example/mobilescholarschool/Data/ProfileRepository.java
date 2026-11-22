package com.example.mobilescholarschool.Data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.example.mobilescholarschool.Sync.SyncScheduler;
import com.google.firebase.firestore.DocumentSnapshot;

/**
 * NEW: the local copy of users/{uid}.
 *
 * LESSON: CONFLICT POLICY. Two things can change the same profile: the student (offline edit)
 * and the server (a snapshot arriving). Our rule: while the student's edit is still
 * "pending_sync", the server's copy must NOT overwrite it. Once the edit is sent, the server's
 * copy wins again. That is called "local edits win until delivered".
 *
 * All methods touch the disk: call them from a background thread (see DbExecutor).
 */
public final class ProfileRepository {

    private static final String TAG = "ProfileRepository";

    /** A plain holder for one profile row. */
    public static final class LocalProfile {
        public String uid, fullName, idNumber, registrationNo, email, gender, department, course;
        public String syncState, syncError;
        public long updatedLocalMillis;
    }

    private ProfileRepository() { }

    /** Turns a Firestore document into a LocalProfile. Throws RuntimeException on a wrongly typed field. */
    public static LocalProfile fromSnapshot(String uid, DocumentSnapshot doc) {
        LocalProfile p = new LocalProfile();
        p.uid = uid;
        p.fullName = doc.getString("fullName");
        p.idNumber = doc.getString("idNumber");
        p.registrationNo = doc.getString("registrationNo");
        p.email = doc.getString("email");
        p.gender = doc.getString("gender");
        p.department = doc.getString("department");
        p.course = doc.getString("course");
        return p;
    }

    /** @return the saved profile, or null if this phone has none for that uid yet */
    public static LocalProfile get(Context c, String uid) {
        SQLiteDatabase db = AppDatabase.get(c).getReadableDatabase();
        try (Cursor cur = db.query(AppDatabase.T_PROFILE, null, "uid = ?", new String[]{uid},
                null, null, null)) {
            if (!cur.moveToFirst()) {
                return null;
            }
            LocalProfile p = new LocalProfile();
            p.uid = text(cur, "uid");
            p.fullName = text(cur, "full_name");
            p.idNumber = text(cur, "id_number");
            p.registrationNo = text(cur, "registration_no");
            p.email = text(cur, "email");
            p.gender = text(cur, "gender");
            p.department = text(cur, "department");
            p.course = text(cur, "course");
            p.syncState = text(cur, "sync_state");
            p.syncError = text(cur, "sync_error");
            p.updatedLocalMillis = cur.getLong(cur.getColumnIndexOrThrow("updated_local_millis"));
            return p;
        }
    }

    /**
     * SERVER -> PHONE. Mirrors what Firestore says, unless a local edit is still waiting to be sent.
     *
     * LESSON: "update, then insert-or-ignore" is a portable UPSERT (older Android versions do not
     * have SQLite's newer ON CONFLICT ... DO UPDATE). Step 1 updates the row if it exists and is
     * not pending. Step 2 inserts it if it does not exist at all. A pending row is left alone
     * because step 1 skips it and step 2 ignores the duplicate key.
     */
    public static void saveFromServer(Context c, LocalProfile p) {
        SQLiteDatabase db = AppDatabase.get(c).getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("full_name", p.fullName);
        v.put("id_number", p.idNumber);
        v.put("registration_no", p.registrationNo);
        v.put("email", p.email);
        v.put("gender", p.gender);
        v.put("department", p.department);
        v.put("course", p.course);
        v.put("sync_state", SyncState.SYNCED);
        v.putNull("sync_error");

        db.beginTransaction();
        try {
            int updated = db.update(AppDatabase.T_PROFILE, v,
                    "uid = ? AND sync_state <> ?", new String[]{p.uid, SyncState.PENDING_SYNC});
            if (updated == 0) {
                ContentValues row = new ContentValues(v);
                row.put("uid", p.uid);
                row.put("updated_local_millis", 0);
                db.insertWithOnConflict(AppDatabase.T_PROFILE, null, row,
                        SQLiteDatabase.CONFLICT_IGNORE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        DataChangeNotifier.notifyChanged(AppDatabase.T_PROFILE);
    }

    /**
     * PHONE -> SERVER (step 1). The student edited their details. Save them locally, drop a note
     * in the outbox (ONE transaction), then ask WorkManager to send it when there is internet.
     */
    public static void saveLocalEdit(Context c, LocalProfile p) {
        SQLiteDatabase db = AppDatabase.get(c).getWritableDatabase();
        db.beginTransaction();
        try {
            // Make sure a row exists, then update it
            db.execSQL("INSERT OR IGNORE INTO profile (uid, email, sync_state, updated_local_millis) "
                    + "VALUES (?, ?, ?, 0)", new Object[]{p.uid, p.email, SyncState.SYNCED});

            // MAX(now, old + 1): the number ALWAYS goes up, even if two edits happen in the same millisecond
            db.execSQL("UPDATE profile SET full_name = ?, id_number = ?, registration_no = ?, "
                            + "gender = ?, department = ?, course = ?, "
                            + "sync_state = ?, sync_error = NULL, "
                            + "updated_local_millis = MAX(?, updated_local_millis + 1) "
                            + "WHERE uid = ?",
                    new Object[]{p.fullName, p.idNumber, p.registrationNo, p.gender, p.department,
                            p.course, SyncState.PENDING_SYNC, System.currentTimeMillis(), p.uid});

            Outbox.enqueue(db, p.uid, Outbox.ENTITY_PROFILE, p.uid, Outbox.OP_UPSERT);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        SyncLog.i(c, TAG, "Profile edit saved on this device");
        DataChangeNotifier.notifyChanged(AppDatabase.T_PROFILE);
        SyncScheduler.enqueue(c);
    }

    /**
     * Called by the sync worker after the server accepted the edit.
     *
     * LESSON: RACE CONDITION. If the student edits AGAIN while the worker is sending, marking the
     * row "synced" would hide the newer edit forever. So we only mark it synced if
     * updated_local_millis is still the value the worker saw when it started.
     *
     * @return true if marked synced; false if a newer edit arrived (send again)
     */
    public static boolean markSyncedIfUnchanged(Context c, String uid, long capturedMillis) {
        ContentValues v = new ContentValues();
        v.put("sync_state", SyncState.SYNCED);
        v.putNull("sync_error");
        int n = AppDatabase.get(c).getWritableDatabase().update(AppDatabase.T_PROFILE, v,
                "uid = ? AND updated_local_millis = ?",
                new String[]{uid, String.valueOf(capturedMillis)});
        if (n > 0) {
            DataChangeNotifier.notifyChanged(AppDatabase.T_PROFILE);
        }
        return n > 0;
    }

    /** The server refused the edit. The next server snapshot will put the server's values back. */
    public static void markFailed(Context c, String uid, String error) {
        ContentValues v = new ContentValues();
        v.put("sync_state", SyncState.FAILED);
        v.put("sync_error", error);
        AppDatabase.get(c).getWritableDatabase()
                .update(AppDatabase.T_PROFILE, v, "uid = ?", new String[]{uid});
        DataChangeNotifier.notifyChanged(AppDatabase.T_PROFILE);
    }

    private static String text(Cursor cur, String column) {
        return cur.getString(cur.getColumnIndexOrThrow(column));
    }
}
