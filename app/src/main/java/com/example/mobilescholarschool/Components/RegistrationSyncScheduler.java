package com.example.mobilescholarschool.Components;

import android.content.Context;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.example.mobilescholarschool.SignUp.RegistrationSyncWorker;

import java.util.concurrent.TimeUnit;

/**
 * NEW: One place that knows HOW to schedule the registration sync.
 * Any screen that wants to make sure a pending registration gets delivered
 * just calls RegistrationSyncScheduler.enqueue(this).
 */
public final class RegistrationSyncScheduler {

    private RegistrationSyncScheduler() { }

    public static void enqueue(Context context) {
        Constraints needsInternet = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build();

        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(RegistrationSyncWorker.class)
                .setConstraints(needsInternet)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();

        // KEEP = if a sync is already queued or running, leave it alone (no duplicates)
        WorkManager.getInstance(context)
                .enqueueUniqueWork("registration_sync", ExistingWorkPolicy.KEEP, work);
    }
}