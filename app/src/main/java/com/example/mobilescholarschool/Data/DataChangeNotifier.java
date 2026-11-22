package com.example.mobilescholarschool.Data;

import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * NEW: a tiny "something changed" announcement board.
 *
 * LESSON: with Firestore, a snapshot listener refreshes the screen by itself. SQLite does not
 * do that, so whenever a repository changes a table it calls notifyChanged("table"), and any
 * open screen that registered a Listener reloads. (Room's LiveData does this job for you;
 * here you build it by hand so you can see how it works.)
 *
 * Screens: register in onStart, unregister in onStop (same pairing as Firestore listeners).
 */
public final class DataChangeNotifier {

    public interface Listener {
        /** Runs on the main thread. @param table one of AppDatabase.T_* */
        void onDataChanged(String table);
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private DataChangeNotifier() { }

    public static void register(Listener listener) {
        if (!LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    public static void unregister(Listener listener) {
        LISTENERS.remove(listener);
    }

    /** Safe to call from any thread. */
    public static void notifyChanged(String table) {
        MAIN.post(() -> {
            for (Listener listener : LISTENERS) {
                listener.onDataChanged(table);
            }
        });
    }
}
