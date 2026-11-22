package com.example.mobilescholarschool.Sync;

import android.content.Context;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * NEW: the ONE place that schedules syncing. It replaces RegistrationSyncScheduler (and the
 * copy of the same code that used to sit inside SignUpActivity).
 *
 * LESSON: two ways to ask for a sync.
 *
 *   enqueue()  "make sure a sync WILL happen". Used after saving something. If a sync is already
 *              queued or running it is left alone (KEEP), so taps never pile up duplicate jobs.
 *              WorkManager holds the job while the phone is offline (it survives the app being
 *              closed and the phone rebooting) and starts it when a connection appears.
 *
 *   kick()     "internet just came back: sync NOW". Used by ScholarApp's network listener. A job
 *              that failed earlier sits out a retry delay; kick() REPLACES it with a fresh job so
 *              there is no waiting. It does nothing if a sync is already running.
 */
public final class SyncScheduler {

    private static final String UNIQUE_NAME = "data_sync";

    private SyncScheduler() { }

    private static OneTimeWorkRequest newRequest() {
        Constraints needsInternet = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build();

        return new OneTimeWorkRequest.Builder(SyncWorker.class)
                .setConstraints(needsInternet)
                // Only used when the worker returns retry(): wait 30s, then 60s, 120s ...
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();
    }

    /** Make sure a sync is scheduled (KEEP: no duplicates). */
    public static void enqueue(Context context) {
        // SyncWorker re-checks the outbox before it finishes, so a row saved while it was
        // busy is not forgotten.
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, newRequest());
    }

    /** Internet just appeared: start a sync immediately, replacing any job that is waiting. */
    public static void kick(Context context) {
        if (SyncWorker.isRunning()) {
            return;   // the running sync re-reads the outbox, so nothing is lost
        }
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, newRequest());
    }
}