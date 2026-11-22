package com.example.mobilescholarschool.Data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

/**
 * NEW: the "outbox" pattern, the heart of offline-first apps.
 *
 * Think of a post office drop box. The student drops a letter in (saves a row) and walks away.
 * The postman (SyncWorker) empties the box whenever the road (internet) is open.
 *
 * KEY IDEA: a row and its outbox entry are saved in ONE transaction (see the repositories).
 * Either both exist or neither does, so a request can never be saved without being queued,
 * even if the phone dies in the middle.
 */
public final class Outbox {

    public static final String ENTITY_PROFILE = "profile";
    public static final String ENTITY_BOOK_REQUEST = "book_request";
    public static final String ENTITY_HOSTEL_BOOKING = "hostel_booking";

    public static final String OP_UPSERT = "upsert";   // create-or-update (profile edit)
    public static final String OP_CREATE = "create";   // brand new document

    /** One line on the to-do list. */
    public static final class Item {
        public long id;
        public String uid;
        public String entity;
        public String entityId;
        public String op;
        public int attempts;
    }

    private Outbox() { }

    /** Call INSIDE the same transaction that saves the row itself. Duplicates are ignored. */
    static void enqueue(SQLiteDatabase db, String uid, String entity, String entityId, String op) {
        ContentValues v = new ContentValues();
        v.put("uid", uid);
        v.put("entity", entity);
        v.put("entity_id", entityId);
        v.put("op", op);
        v.put("created_millis", System.currentTimeMillis());
        db.insertWithOnConflict(AppDatabase.T_OUTBOX, null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    /** This student's to-do list, oldest first. Rows of OTHER students are never returned. */
    public static List<Item> pendingFor(Context c, String uid) {
        List<Item> out = new ArrayList<>();
        SQLiteDatabase db = AppDatabase.get(c).getReadableDatabase();
        try (Cursor cur = db.query(AppDatabase.T_OUTBOX, null, "uid = ?", new String[]{uid},
                null, null, "id ASC")) {
            while (cur.moveToNext()) {
                Item item = new Item();
                item.id = cur.getLong(cur.getColumnIndexOrThrow("id"));
                item.uid = cur.getString(cur.getColumnIndexOrThrow("uid"));
                item.entity = cur.getString(cur.getColumnIndexOrThrow("entity"));
                item.entityId = cur.getString(cur.getColumnIndexOrThrow("entity_id"));
                item.op = cur.getString(cur.getColumnIndexOrThrow("op"));
                item.attempts = cur.getInt(cur.getColumnIndexOrThrow("attempts"));
                out.add(item);
            }
        }
        return out;
    }

    public static int countFor(Context c, String uid) {
        SQLiteDatabase db = AppDatabase.get(c).getReadableDatabase();
        return (int) DatabaseUtils.queryNumEntries(db, AppDatabase.T_OUTBOX, "uid = ?",
                new String[]{uid});
    }

    /** The job is finished (sent, or refused for good): take it off the list. */
    public static void remove(Context c, long id) {
        AppDatabase.get(c).getWritableDatabase()
                .delete(AppDatabase.T_OUTBOX, "id = ?", new String[]{String.valueOf(id)});
    }

    /** The job failed for a temporary reason: count the attempt and remember why. */
    public static void recordFailure(Context c, long id, String error) {
        AppDatabase.get(c).getWritableDatabase().execSQL(
                "UPDATE outbox SET attempts = attempts + 1, last_error = ? WHERE id = ?",
                new Object[]{error, id});
    }
}
