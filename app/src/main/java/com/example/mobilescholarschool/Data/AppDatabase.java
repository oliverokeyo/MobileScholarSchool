package com.example.mobilescholarschool.Data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * NEW: The app's local SQLite database ("scholar_school.db" in the app's private storage).
 *
 * LESSON 1: What is SQLiteOpenHelper?
 * Android ships SQLite inside the phone. SQLiteOpenHelper is the official helper that
 *   (a) creates the file the first time (onCreate), and
 *   (b) upgrades it when you ship a new version of the app (onUpgrade).
 * You never open the file yourself; you ask the helper for getWritableDatabase().
 *
 * LESSON 2: The five tables and what each is for
 *   profile          a copy of users/{uid}                  (so the app works offline)
 *   book_requests    book requests the student made         (saved here FIRST, sent later)
 *   hostel_bookings  hostel bookings                        (saved here FIRST, sent later)
 *   outbox           "things still to send to Firestore"    (the to-do list for the sync worker)
 *   sync_log         a diary of what the sync did           (this is the "logging")
 *
 * LESSON 3: sync_state, the column that makes offline work
 *   Every row that can be sent to Firestore has a sync_state:
 *     pending_sync  saved on the phone, the server does not know yet
 *     synced        the server has it
 *     failed        the server refused it (sync_error says why)
 *
 * LESSON 4: ALL database calls here touch the disk, so NEVER call them on the main thread.
 * Use DbExecutor (activities) or call them from the sync worker (already a background thread).
 */
public final class AppDatabase extends SQLiteOpenHelper {

    private static final String DB_NAME = "scholar_school.db";

    // Raise this by 1 every time the table layout changes, and add a step in onUpgrade.
    private static final int DB_VERSION = 1;

    public static final String T_PROFILE = "profile";
    public static final String T_BOOK_REQUESTS = "book_requests";
    public static final String T_HOSTEL_BOOKINGS = "hostel_bookings";
    public static final String T_OUTBOX = "outbox";
    public static final String T_SYNC_LOG = "sync_log";

    private static final String CREATE_PROFILE =
            "CREATE TABLE profile ("
                    + "uid TEXT PRIMARY KEY NOT NULL, "
                    + "full_name TEXT, "
                    + "id_number TEXT, "
                    + "registration_no TEXT, "
                    + "email TEXT, "
                    + "gender TEXT, "
                    + "department TEXT, "
                    + "course TEXT, "
                    + "sync_state TEXT NOT NULL DEFAULT 'synced', "
                    + "sync_error TEXT, "
                    // Goes UP on every local edit. The sync worker uses it to notice "the student edited
                    // again while I was sending", so a newer edit is never marked as sent by mistake.
                    + "updated_local_millis INTEGER NOT NULL DEFAULT 0)";

    private static final String CREATE_BOOK_REQUESTS =
            "CREATE TABLE book_requests ("
                    // local_id is a random UUID made on the phone. It also becomes the Firestore document
                    // id, so sending the same request twice can never create two documents.
                    + "local_id TEXT PRIMARY KEY NOT NULL, "
                    + "uid TEXT NOT NULL, "
                    + "student_id_number TEXT NOT NULL, "
                    + "isbn TEXT NOT NULL DEFAULT '', "
                    + "book_title TEXT NOT NULL DEFAULT '', "
                    + "author TEXT NOT NULL DEFAULT '', "
                    + "borrow_millis INTEGER NOT NULL, "
                    + "return_millis INTEGER NOT NULL, "
                    + "status TEXT NOT NULL DEFAULT 'pending', "
                    + "sync_state TEXT NOT NULL DEFAULT 'pending_sync', "
                    + "sync_error TEXT, "
                    + "created_local_millis INTEGER NOT NULL)";

    private static final String CREATE_HOSTEL_BOOKINGS =
            "CREATE TABLE hostel_bookings ("
                    + "local_id TEXT PRIMARY KEY NOT NULL, "
                    + "uid TEXT NOT NULL, "
                    + "student_id_number TEXT NOT NULL, "
                    + "hostel_id TEXT NOT NULL, "
                    + "hostel_name TEXT NOT NULL, "
                    + "room_id TEXT NOT NULL, "
                    + "room_type TEXT NOT NULL, "
                    + "sharing TEXT NOT NULL, "
                    + "rent_per_month INTEGER NOT NULL, "
                    + "status TEXT NOT NULL DEFAULT 'pending', "
                    + "sync_state TEXT NOT NULL DEFAULT 'pending_sync', "
                    + "sync_error TEXT, "
                    + "created_millis INTEGER NOT NULL)";

    // The to-do list. It stores only a POINTER (which row, which action), never a copy of the
    // data: when the worker runs it reads the LATEST row, so there is one source of truth.
    // UNIQUE(...) + INSERT OR IGNORE means "already on the list" is never added twice.
    private static final String CREATE_OUTBOX =
            "CREATE TABLE outbox ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "uid TEXT NOT NULL, "
                    + "entity TEXT NOT NULL, "
                    + "entity_id TEXT NOT NULL, "
                    + "op TEXT NOT NULL, "
                    + "attempts INTEGER NOT NULL DEFAULT 0, "
                    + "last_error TEXT, "
                    + "created_millis INTEGER NOT NULL, "
                    + "UNIQUE (entity, entity_id, op))";

    private static final String CREATE_SYNC_LOG =
            "CREATE TABLE sync_log ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "time_millis INTEGER NOT NULL, "
                    + "level TEXT NOT NULL, "
                    + "tag TEXT NOT NULL, "
                    + "message TEXT NOT NULL)";

    private static final String CREATE_INDEX_REQUESTS =
            "CREATE INDEX idx_book_requests_uid_state ON book_requests (uid, sync_state)";

    private static final String CREATE_INDEX_BOOKINGS =
            "CREATE INDEX idx_hostel_bookings_uid_state ON hostel_bookings (uid, sync_state)";

    private static AppDatabase instance;

    /**
     * LESSON 5: Singleton. Only ONE helper may exist per database file; two helpers fighting
     * over one file cause "database is locked" errors. getApplicationContext() is used so the
     * helper never holds on to an Activity (that would leak the screen's memory).
     */
    public static synchronized AppDatabase get(Context context) {
        if (instance == null) {
            instance = new AppDatabase(context.getApplicationContext());
        }
        return instance;
    }

    private AppDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        // Write-Ahead Logging: a reader (the screen) and a writer (the sync worker) no longer
        // block each other.
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_PROFILE);
        db.execSQL(CREATE_BOOK_REQUESTS);
        db.execSQL(CREATE_HOSTEL_BOOKINGS);
        db.execSQL(CREATE_OUTBOX);
        db.execSQL(CREATE_SYNC_LOG);
        db.execSQL(CREATE_INDEX_REQUESTS);
        db.execSQL(CREATE_INDEX_BOOKINGS);
    }

    /**
     * LESSON 6: Migrations. NEVER "drop everything and recreate" here: the tables may hold
     * requests the student made offline that have not been sent yet, and dropping them would
     * destroy that work. Add one step per version instead, for example:
     *
     *     if (oldVersion < 2) { db.execSQL("ALTER TABLE book_requests ADD COLUMN note TEXT"); }
     *     if (oldVersion < 3) { ... }
     */
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Version 1 is the first version: nothing to upgrade yet.
    }

    /**
     * NEW: re-labels everything saved under a PROVISIONAL id with the real Firebase uid, in ONE
     * transaction (all or nothing). Called by SyncWorker the moment an offline sign-up becomes a
     * real account, BEFORE the saved items are sent.
     *
     * LESSON: "OR REPLACE" on profile and outbox. Their uid / entity_id are part of a unique key,
     * so if a row with the real uid already exists the plain UPDATE would fail. OR REPLACE lets the
     * student's own saved row win (it may hold an unsent edit).
     */
    public void rekeyUid(String fromUid, String toUid) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.execSQL("UPDATE OR REPLACE profile SET uid = ? WHERE uid = ?",
                    new Object[]{toUid, fromUid});
            db.execSQL("UPDATE book_requests SET uid = ? WHERE uid = ?",
                    new Object[]{toUid, fromUid});
            db.execSQL("UPDATE hostel_bookings SET uid = ? WHERE uid = ?",
                    new Object[]{toUid, fromUid});
            // A profile outbox entry points at the uid itself, so that pointer changes too
            db.execSQL("UPDATE OR REPLACE outbox SET uid = ?, "
                            + "entity_id = CASE WHEN entity = 'profile' THEN ? ELSE entity_id END "
                            + "WHERE uid = ?",
                    new Object[]{toUid, toUid, fromUid});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** NEW: deletes EVERYTHING saved under one id (used when a waiting sign-up is abandoned). */
    public void deleteAllFor(String uid) {
        SQLiteDatabase db = getWritableDatabase();
        String[] args = {uid};
        db.beginTransaction();
        try {
            db.delete(T_PROFILE, "uid = ?", args);
            db.delete(T_BOOK_REQUESTS, "uid = ?", args);
            db.delete(T_HOSTEL_BOOKINGS, "uid = ?", args);
            db.delete(T_OUTBOX, "uid = ?", args);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Privacy mop-up for sign-out: delete this student's rows that the server already has.
     * Rows still waiting to be sent (pending_sync) or refused (failed) are KEPT, so no work is lost.
     */
    public void clearSyncedFor(String uid) {
        SQLiteDatabase db = getWritableDatabase();
        String[] args = {uid, SyncState.SYNCED};
        db.beginTransaction();
        try {
            db.delete(T_PROFILE, "uid = ? AND sync_state = ?", args);
            db.delete(T_BOOK_REQUESTS, "uid = ? AND sync_state = ?", args);
            db.delete(T_HOSTEL_BOOKINGS, "uid = ? AND sync_state = ?", args);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }
}