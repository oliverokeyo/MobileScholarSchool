package com.example.mobilescholarschool.Data;

/** NEW: the three states a locally saved row can be in (stored as text in sync_state). */
public final class SyncState {

    private SyncState() { }

    /** Saved on this phone; the server does not know about it yet. */
    public static final String PENDING_SYNC = "pending_sync";

    /** The server has it. */
    public static final String SYNCED = "synced";

    /** The server refused it (the column sync_error says why). Retrying would not help. */
    public static final String FAILED = "failed";
}
