package com.example.mobilescholarschool.Data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;

import com.example.mobilescholarschool.Sync.SyncScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * NEW: library book requests made on this phone.
 *
 * LESSON: IDEMPOTENCY. The row's local_id (a random UUID) is ALSO used as the Firestore
 * document id. If the phone sends a request, loses the "OK" reply, and sends it again, the
 * second send targets the SAME document, so the library never receives two copies. Before,
 * the app used add(), which makes a NEW random id on every call.
 *
 * Call from a background thread (see DbExecutor).
 */
public final class BookRequestRepository {

    private static final String TAG = "BookRequestRepository";

    public static final class Request {
        public String localId, uid, studentIdNumber, isbn, bookTitle, author;
        public String status, syncState, syncError;
        public long borrowMillis, returnMillis, createdLocalMillis;
    }

    private BookRequestRepository() { }

    /**
     * Saves a new request and drops a note in the outbox, in ONE transaction.
     * @return the new local id
     */
    public static String createLocal(Context c, Request r) {
        String id = UUID.randomUUID().toString();
        ContentValues v = new ContentValues();
        v.put("local_id", id);
        v.put("uid", r.uid);
        v.put("student_id_number", r.studentIdNumber);
        v.put("isbn", r.isbn == null ? "" : r.isbn);
        v.put("book_title", r.bookTitle == null ? "" : r.bookTitle);
        v.put("author", r.author == null ? "" : r.author);
        v.put("borrow_millis", r.borrowMillis);
        v.put("return_millis", r.returnMillis);
        v.put("status", "pending");                       // same as before: only the librarian changes it
        v.put("sync_state", SyncState.PENDING_SYNC);
        v.put("created_local_millis", System.currentTimeMillis());

        SQLiteDatabase db = AppDatabase.get(c).getWritableDatabase();
        db.beginTransaction();
        try {
            db.insertOrThrow(AppDatabase.T_BOOK_REQUESTS, null, v);
            Outbox.enqueue(db, r.uid, Outbox.ENTITY_BOOK_REQUEST, id, Outbox.OP_CREATE);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        SyncLog.i(c, TAG, "Book request " + id + " saved on this device");
        DataChangeNotifier.notifyChanged(AppDatabase.T_BOOK_REQUESTS);
        SyncScheduler.enqueue(c);
        return id;
    }

    public static Request get(Context c, String localId) {
        SQLiteDatabase db = AppDatabase.get(c).getReadableDatabase();
        try (Cursor cur = db.query(AppDatabase.T_BOOK_REQUESTS, null, "local_id = ?",
                new String[]{localId}, null, null, null)) {
            return cur.moveToFirst() ? read(cur) : null;
        }
    }

    /** How many of this student's requests are saved here but not yet on the server. */
    public static int countUnsynced(Context c, String uid) {
        SQLiteDatabase db = AppDatabase.get(c).getReadableDatabase();
        return (int) DatabaseUtils.queryNumEntries(db, AppDatabase.T_BOOK_REQUESTS,
                "uid = ? AND sync_state = ?", new String[]{uid, SyncState.PENDING_SYNC});
    }

    /** Requests the server refused. */
    public static List<Request> failed(Context c, String uid) {
        List<Request> list = new ArrayList<>();
        SQLiteDatabase db = AppDatabase.get(c).getReadableDatabase();
        try (Cursor cur = db.query(AppDatabase.T_BOOK_REQUESTS, null, "uid = ? AND sync_state = ?",
                new String[]{uid, SyncState.FAILED}, null, null, "created_local_millis ASC")) {
            while (cur.moveToNext()) {
                list.add(read(cur));
            }
        }
        return list;
    }

    /**
     * Requests the server does NOT have as a normal request: still waiting to be sent
     * (pending_sync) or refused (failed). Requests already delivered (synced) are left out
     * because Firestore supplies those. Used by the "My requests" dialog.
     */
    public static List<Request> listUnsent(Context c, String uid) {
        List<Request> list = new ArrayList<>();
        SQLiteDatabase db = AppDatabase.get(c).getReadableDatabase();
        try (Cursor cur = db.query(AppDatabase.T_BOOK_REQUESTS, null,
                "uid = ? AND sync_state <> ?", new String[]{uid, SyncState.SYNCED},
                null, null, "created_local_millis DESC")) {
            while (cur.moveToNext()) {
                list.add(read(cur));
            }
        }
        return list;
    }

    public static void markSynced(Context c, String localId) {
        update(c, localId, SyncState.SYNCED, null);
    }

    public static void markFailed(Context c, String localId, String error) {
        update(c, localId, SyncState.FAILED, error);
    }

    /** The student has read the "not sent" message: remove those rows. */
    public static void deleteRows(Context c, List<String> localIds) {
        SQLiteDatabase db = AppDatabase.get(c).getWritableDatabase();
        db.beginTransaction();
        try {
            for (String id : localIds) {
                db.delete(AppDatabase.T_BOOK_REQUESTS, "local_id = ?", new String[]{id});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        DataChangeNotifier.notifyChanged(AppDatabase.T_BOOK_REQUESTS);
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
                .update(AppDatabase.T_BOOK_REQUESTS, v, "local_id = ?", new String[]{localId});
        DataChangeNotifier.notifyChanged(AppDatabase.T_BOOK_REQUESTS);
    }

    private static Request read(Cursor cur) {
        Request r = new Request();
        r.localId = cur.getString(cur.getColumnIndexOrThrow("local_id"));
        r.uid = cur.getString(cur.getColumnIndexOrThrow("uid"));
        r.studentIdNumber = cur.getString(cur.getColumnIndexOrThrow("student_id_number"));
        r.isbn = cur.getString(cur.getColumnIndexOrThrow("isbn"));
        r.bookTitle = cur.getString(cur.getColumnIndexOrThrow("book_title"));
        r.author = cur.getString(cur.getColumnIndexOrThrow("author"));
        r.borrowMillis = cur.getLong(cur.getColumnIndexOrThrow("borrow_millis"));
        r.returnMillis = cur.getLong(cur.getColumnIndexOrThrow("return_millis"));
        r.status = cur.getString(cur.getColumnIndexOrThrow("status"));
        r.syncState = cur.getString(cur.getColumnIndexOrThrow("sync_state"));
        r.syncError = cur.getString(cur.getColumnIndexOrThrow("sync_error"));
        r.createdLocalMillis = cur.getLong(cur.getColumnIndexOrThrow("created_local_millis"));
        return r;
    }
}
