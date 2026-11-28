package com.example.mobilescholarschool.Security;

import android.app.Activity;
import android.widget.Toast;

import com.example.mobilescholarschool.Components.ConnectivityHelper;
import com.example.mobilescholarschool.Components.OfflineBanner;
import com.example.mobilescholarschool.Components.OnlineReauthDialog;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

/**
 * NEW: the gate in front of anything that must NOT work on an offline session:
 * M-Pesa payments, changing the account (email/password), and every administrative function.
 *
 * LESSON: THE CLIENT IS NOT THE LOCK. This guard only gives the student a friendly message and
 * stops pointless requests. A modified app could skip it. The real protection is on the server:
 *   - Firestore security rules require request.auth != null (offline mode has no Firebase user),
 *   - the M-Pesa call must go through a backend / Cloud Function that verifies the caller's
 *     Firebase ID token and checks the student's role, and the admin screens must be guarded
 *     by rules or custom claims, not by hiding buttons.
 *
 * requireServerAuth() makes THREE checks, in order:
 *   1. not in offline mode (offer to sign in online if the phone has connection again),
 *   2. the phone is online and a Firebase user exists,
 *   3. getIdToken(true): a forced round-trip to Firebase. It FAILS if the account was disabled
 *      or deleted or the password changed, so a stale cached sign-in cannot start a payment.
 */
public final class OnlineOnlyGuard {

    private OnlineOnlyGuard() { }

    /** Cheap, offline-safe question for the UI (hide/disable buttons). NOT a security check. */
    public static boolean hasFullSession() {
        return FirebaseAuth.getInstance().getCurrentUser() != null && !OfflineSession.isActive();
    }

    /** NEW: the same check with the usual reactions (sign-in prompt, reload, back to Sign In). */
    public static void requireServerAuth(final Activity activity, final Runnable proceed) {
        requireServerAuth(activity, proceed, OfflineBanner.standardCallback(activity));
    }

    /**
     * Runs {@code proceed} only if the server has just confirmed this student.
     * Call on the main thread, for example from a "Pay with M-Pesa" button.
     */
    public static void requireServerAuth(final Activity activity, final Runnable proceed,
                                         final OnlineReauthDialog.Callback reauthCallback) {
        if (OfflineSession.isActive()) {
            if (ConnectivityHelper.isOnline(activity)) {
                toast(activity, "Please sign in to the server first.");
                OnlineReauthDialog.show(activity, reauthCallback);
            } else {
                toast(activity, "This needs an internet connection and a full sign-in.");
            }
            return;
        }
        final FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            toast(activity, "Please sign in first.");
            return;
        }
        if (!ConnectivityHelper.isOnline(activity)) {
            toast(activity, "This needs an internet connection.");
            return;
        }
        user.getIdToken(true)
                .addOnSuccessListener(token -> {
                    if (!activity.isFinishing()) {
                        proceed.run();
                    }
                })
                .addOnFailureListener(e -> toast(activity, SignInErrorMapper.map(e).message));
    }

    private static void toast(Activity activity, String message) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }
}