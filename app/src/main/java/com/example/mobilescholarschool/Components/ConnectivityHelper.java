package com.example.mobilescholarschool.Components;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/**
 * NEW: the "is there internet right now?" check that SignIn, SignUp and Profile each carried
 * their own copy of. Needs ACCESS_NETWORK_STATE in AndroidManifest.xml (you already have it).
 */
public final class ConnectivityHelper {

    private ConnectivityHelper() { }

    public static boolean isOnline(Context context) {
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network network = cm.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
}
