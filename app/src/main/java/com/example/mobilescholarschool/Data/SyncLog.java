package com.example.mobilescholarschool.Data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * NEW: the "logging". Every important sync event is written to the sync_log table AND to
 * Logcat. Logcat disappears when the phone restarts; the table survives, so you can find out
 * days later WHY a request never arrived.
 *
 * Rules: never log passwords or tokens. Call from a background thread. Logging must never
 * crash the app, so every failure here is swallowed.
 */
public final class SyncLog {

    private static final int MAX_ROWS = 500;   // keep the table small: oldest rows are trimmed

    private SyncLog() { }

    public static void i(Context c, String tag, String message) {
        Log.i(tag, message);
        write(c, "INFO", tag, message);
    }

    public static void w(Context c, String tag, String message) {
        Log.w(tag, message);
        write(c, "WARN", tag, message);
    }

    public static void e(Context c, String tag, String message) {
        Log.e(tag, message);
        write(c, "ERROR", tag, message);
    }

    private static void write(Context c, String level, String tag, String message) {
        try {
            SQLiteDatabase db = AppDatabase.get(c).getWritableDatabase();
            ContentValues v = new ContentValues();
            v.put("time_millis", System.currentTimeMillis());
            v.put("level", level);
            v.put("tag", tag);
            v.put("message", message);
            db.insert(AppDatabase.T_SYNC_LOG, null, v);
            // Trim: delete everything older than the newest MAX_ROWS rows
            db.execSQL("DELETE FROM sync_log WHERE id <= (SELECT MAX(id) FROM sync_log) - ?",
                    new Object[]{MAX_ROWS});
        } catch (RuntimeException e) {
            Log.e("SyncLog", "Could not write to sync_log", e);
        }
    }

    /** The newest entries first, for a future "Sync history" screen. */
    public static List<String> recent(Context c, int limit) {
        List<String> out = new ArrayList<>();
        SQLiteDatabase db = AppDatabase.get(c).getReadableDatabase();
        try (Cursor cur = db.query(AppDatabase.T_SYNC_LOG, null, null, null, null, null,
                "id DESC", String.valueOf(limit))) {
            while (cur.moveToNext()) {
                out.add(cur.getLong(cur.getColumnIndexOrThrow("time_millis")) + "  "
                        + cur.getString(cur.getColumnIndexOrThrow("level")) + "  "
                        + cur.getString(cur.getColumnIndexOrThrow("message")));
            }
        }
        return out;
    }
}
