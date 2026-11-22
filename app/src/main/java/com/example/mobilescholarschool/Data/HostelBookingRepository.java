package com.example.mobilescholarschool.Data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.example.mobilescholarschool.Sync.SyncScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * NEW: hostel bookings, in both directions.
 *   PHONE -> SERVER: createLocal() saves a booking + outbox note; the SyncWorker sends it.
 *   SERVER -> PHONE: mirrorFromServer() copies Firestore's bookings here, so the
 *                    "My Accommodation" card can read ONE place that also works offline
 *                    (and so it picks up the admin's approve / reject).
 *
 * Call from a background thread (see DbExecutor).
 */
public final class HostelBookingRepository {

    private static final String TAG = "HostelBookingRepository";

    public static final class Booking {
        public String localId, uid, studentIdNumber, hostelId, hostelName, roomId, roomType, sharing;
        public String status, syncState, syncError;
        public long rentPerMonth;
        /** 0 = unknown (a server document whose timestamp has not been filled in yet) */
        public long createdMillis;
    }

    private HostelBookingRepository() { }

    /** Saves a new booking and its outbox note in ONE transaction. @return the new local id */
    public static String createLocal(Context c, Booking b) {
        String id = UUID.randomUUID().toString();
        ContentValues v = new ContentValues();
        v.put("local_id", id);
        v.put("uid", b.uid);
        v.put("student_id_number", b.studentIdNumber);
        v.put("hostel_id", b.hostelId);
        v.put("hostel_name", b.hostelName);
        v.put("room_id", b.roomId);
        v.put("room_type", b.roomType);
        v.put("sharing", b.sharing);
        v.put("rent_per_month", b.rentPerMonth);
        v.put("status", "pending");                       // only the admin changes this later
        v.put("sync_state", SyncState.PENDING_SYNC);
        v.put("created_millis", System.currentTimeMillis());

        SQLiteDatabase db = AppDatabase.get(c).getWritableDatabase();
        db.beginTransaction();
        try {
            db.insertOrThrow(AppDatabase.T_HOSTEL_BOOKINGS, null, v);
            Outbox.enqueue(db, b.uid, Outbox.ENTITY_HOSTEL_BOOKING, id, Outbox.OP_CREATE);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        SyncLog.i(c, TAG, "Hostel booking " + id + " saved on this device");
        DataChangeNotifier.notifyChanged(AppDatabase.T_HOSTEL_BOOKINGS);
        SyncScheduler.enqueue(c);
        return id;
    }

    public static Booking get(Context c, String localId) {
        SQLiteDatabase db = AppDatabase.get(c).getReadableDatabase();
        try (Cursor cur = db.query(AppDatabase.T_HOSTEL_BOOKINGS, null, "local_id = ?",
                new String[]{localId}, null, null, null)) {
            return cur.moveToFirst() ? read(cur) : null;
        }
    }

    /**
     * All of this student's bookings, newest first.
     * LESSON: ORDER BY CASE puts rows that are still waiting to be sent FIRST (they are the
     * newest thing the student did), then everything else by date.
     */
    public static List<Booking> listFor(Context c, String uid) {
        List<Booking> list = new ArrayList<>();
        SQLiteDatabase db = AppDatabase.get(c).getReadableDatabase();
        try (Cursor cur = db.query(AppDatabase.T_HOSTEL_BOOKINGS, null, "uid = ?", new String[]{uid},
                null, null,
                "CASE WHEN sync_state = 'pending_sync' THEN 0 ELSE 1 END, created_millis DESC")) {
            while (cur.moveToNext()) {
                list.add(read(cur));
            }
        }
        return list;
    }

    /** SERVER -> PHONE: copy Firestore's bookings here. Never deletes anything. */
    public static void mirrorFromServer(Context c, List<Booking> fromServer) {
        if (fromServer.isEmpty()) {
            return;
        }
        SQLiteDatabase db = AppDatabase.get(c).getWritableDatabase();
        db.beginTransaction();
        try {
            for (Booking b : fromServer) {
                ContentValues v = new ContentValues();
                v.put("uid", b.uid);
                v.put("student_id_number", b.studentIdNumber);
                v.put("hostel_id", b.hostelId);
                v.put("hostel_name", b.hostelName);
                v.put("room_id", b.roomId);
                v.put("room_type", b.roomType);
                v.put("sharing", b.sharing);
                v.put("rent_per_month", b.rentPerMonth);
                v.put("status", b.status);
                v.put("sync_state", SyncState.SYNCED);
                v.putNull("sync_error");
                if (b.createdMillis > 0) {
                    v.put("created_millis", b.createdMillis);
                }
                // Update-then-insert-or-ignore (see ProfileRepository.saveFromServer)
                int n = db.update(AppDatabase.T_HOSTEL_BOOKINGS, v, "local_id = ?",
                        new String[]{b.localId});
                if (n == 0) {
                    v.put("local_id", b.localId);
                    if (b.createdMillis <= 0) {
                        v.put("created_millis", System.currentTimeMillis());
                    }
                    db.insertWithOnConflict(AppDatabase.T_HOSTEL_BOOKINGS, null, v,
                            SQLiteDatabase.CONFLICT_IGNORE);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        DataChangeNotifier.notifyChanged(AppDatabase.T_HOSTEL_BOOKINGS);
    }

    public static void markSynced(Context c, String localId) {
        update(c, localId, SyncState.SYNCED, null);
    }

    public static void markFailed(Context c, String localId, String error) {
        update(c, localId, SyncState.FAILED, error);
    }

    private static void update(Context c, String localId, String state, String error) {
        ContentValues v = new ContentValues();
        v.put("sync_state", state);
        if (error == null) {
            v.putNull("sync_error");
        } else {
            v.put("sync_error", error);
        }
        AppDatabase.get(c).getWritableDatabase()
                .update(AppDatabase.T_HOSTEL_BOOKINGS, v, "local_id = ?", new String[]{localId});
        DataChangeNotifier.notifyChanged(AppDatabase.T_HOSTEL_BOOKINGS);
    }

    private static Booking read(Cursor cur) {
        Booking b = new Booking();
        b.localId = cur.getString(cur.getColumnIndexOrThrow("local_id"));
        b.uid = cur.getString(cur.getColumnIndexOrThrow("uid"));
        b.studentIdNumber = cur.getString(cur.getColumnIndexOrThrow("student_id_number"));
        b.hostelId = cur.getString(cur.getColumnIndexOrThrow("hostel_id"));
        b.hostelName = cur.getString(cur.getColumnIndexOrThrow("hostel_name"));
        b.roomId = cur.getString(cur.getColumnIndexOrThrow("room_id"));
        b.roomType = cur.getString(cur.getColumnIndexOrThrow("room_type"));
        b.sharing = cur.getString(cur.getColumnIndexOrThrow("sharing"));
        b.rentPerMonth = cur.getLong(cur.getColumnIndexOrThrow("rent_per_month"));
        b.status = cur.getString(cur.getColumnIndexOrThrow("status"));
        b.syncState = cur.getString(cur.getColumnIndexOrThrow("sync_state"));
        b.syncError = cur.getString(cur.getColumnIndexOrThrow("sync_error"));
        b.createdMillis = cur.getLong(cur.getColumnIndexOrThrow("created_millis"));
        return b;
    }
}
