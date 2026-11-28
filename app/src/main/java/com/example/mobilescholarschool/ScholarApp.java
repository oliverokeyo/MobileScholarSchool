package com.example.mobilescholarschool;

import android.app.Application;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;

import com.example.mobilescholarschool.Components.OfflineBanner;
import com.example.mobilescholarschool.Data.DataChangeNotifier;
import com.example.mobilescholarschool.Sync.SyncScheduler;

/**
 * NEW: the Application class. Android creates exactly one of these when the app's process starts,
 * before any screen. (Registered with android:name=".ScholarApp" in AndroidManifest.xml.)
 *
 * LESSON: "UPDATED THE MOMENT CONNECTION RESUMES". WorkManager already runs the sync once the
 * phone is connected, but a job that failed and is waiting out its retry delay would sleep on for
 * minutes. Here we listen to the network ourselves: the instant ANY internet connection appears,
 * we ask for a sync right now. (SyncScheduler.kick skips it if a sync is already running.)
 *
 * Because it is registered here and never removed, it works on every screen, and it is also the
 * reason a sync happens at every app launch while online (Android reports the existing network
 * as "available" as soon as we start listening).
 *
 * CHANGED (offline sign-in): the listener ALSO announces every connectivity change on the
 * DataChangeNotifier board, so any open screen's OfflineBanner can switch between
 * "Offline mode" and "Back online, tap to sign in and sync" without polling.
 * Note what it does NOT do: it never signs the student in by itself. Going from the local
 * offline session to a real Firebase session needs the password (see OnlineReauthDialog).
 */
public class ScholarApp extends Application {

    private static final String TAG = "ScholarApp";

    @Override
    public void onCreate() {
        super.onCreate();

        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) {
            return;
        }
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        try {
            cm.registerNetworkCallback(request, new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    SyncScheduler.kick(ScholarApp.this);
                    DataChangeNotifier.notifyChanged(OfflineBanner.EVENT_CONNECTIVITY);
                }

                // NEW: a network can appear before Android has VALIDATED that it reaches the
                // internet; this fires when that check finishes, so the banner text is right.
                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    DataChangeNotifier.notifyChanged(OfflineBanner.EVENT_CONNECTIVITY);
                }

                // NEW: connection lost, so the banner can switch back to "Offline mode".
                @Override
                public void onLost(Network network) {
                    DataChangeNotifier.notifyChanged(OfflineBanner.EVENT_CONNECTIVITY);
                }
            });
        } catch (RuntimeException e) {
            // WorkManager's "connected" rule still delivers the data, just not instantly
            Log.e(TAG, "Could not watch the network", e);
        }
    }
}