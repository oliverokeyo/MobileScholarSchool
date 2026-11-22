package com.example.mobilescholarschool.Data;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * NEW: runs database work OFF the main thread, then hands the answer back ON the main thread.
 *
 * LESSON: the main thread draws the screen 60 times a second. A database call on it can
 * freeze the app ("Application Not Responding"). So the pattern is always:
 *
 *     DbExecutor.async(
 *         () -> ProfileRepository.get(ctx, uid),     // runs on the background thread
 *         profile -> showIt(profile));               // runs on the main thread
 *
 * ONE background thread (not many) means database jobs run in the order you started them.
 */
public final class DbExecutor {

    private static final String TAG = "DbExecutor";
    private static final ExecutorService DISK = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Receives the answer on the main thread. */
    public interface Callback<T> {
        /** @param result the answer, or null if the background work threw an error */
        void onResult(T result);
    }

    private DbExecutor() { }

    /** Fire and forget: run this on the background thread. */
    public static void run(Runnable work) {
        DISK.execute(() -> {
            try {
                work.run();
            } catch (RuntimeException e) {
                Log.e(TAG, "Background database work failed", e);
            }
        });
    }

    /** Run work on the background thread, then deliver its result on the main thread. */
    public static <T> void async(Callable<T> work, Callback<T> done) {
        DISK.execute(() -> {
            T result = null;
            try {
                result = work.call();
            } catch (Exception e) {
                Log.e(TAG, "Background database work failed", e);
            }
            final T finalResult = result;
            MAIN.post(() -> done.onResult(finalResult));
        });
    }
}
