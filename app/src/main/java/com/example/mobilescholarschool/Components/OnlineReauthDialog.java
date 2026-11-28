package com.example.mobilescholarschool.Components;

import android.app.Activity;
import android.content.Context;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.example.mobilescholarschool.Data.DataChangeNotifier;
import com.example.mobilescholarschool.Data.DbExecutor;
import com.example.mobilescholarschool.Security.OfflineAuthManager;
import com.example.mobilescholarschool.Security.OfflineSession;
import com.example.mobilescholarschool.Security.SignInErrorMapper;
import com.example.mobilescholarschool.Sync.SyncScheduler;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.Arrays;

/**
 * NEW: "you are back online: prove yourself to the SERVER now".
 *
 * LESSON: we never stored the password, so we cannot silently sign in to Firebase for the
 * student. We ask for it once. On success:
 *   1. the account must be the SAME one as the offline session (else sign out: wrong person),
 *   2. the offline session ends (a real Firebase session replaces it),
 *   3. the verifier is refreshed (resets lock counters, restarts the 14-day clock),
 *   4. SyncScheduler.kick() sends everything saved while offline.
 * The outbox rows were saved under the student's REAL uid, so the SyncWorker finds them as soon
 * as FirebaseAuth has a signed-in user.
 */
public final class OnlineReauthDialog {

    /** The screen decides what "signed in" and "access removed" mean for navigation. */
    public interface Callback {
        void onSignedIn();

        /** The server disabled/deleted the account or the password changed: go to sign-in. */
        void onAccessRevoked();
    }

    private OnlineReauthDialog() { }

    public static void show(final Activity activity, final Callback callback) {
        final String sessionEmail = OfflineSession.email();
        final String sessionUid = OfflineSession.uid();
        if (sessionEmail == null || sessionUid == null) {
            return;
        }
        if (!ConnectivityHelper.isOnline(activity)) {
            Toast.makeText(activity, "You are still offline.", Toast.LENGTH_SHORT).show();
            return;
        }

        final EditText input = new EditText(activity);
        input.setHint("Password");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        FrameLayout box = new FrameLayout(activity);
        int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Sign in to sync")
                .setMessage("You are back online. Enter the password for " + sessionEmail
                        + " to sign in to the server and send what you saved offline.")
                .setView(box)
                .setPositiveButton("Sign in", null)   // listener set below so errors keep it open
                .setNegativeButton("Not now", null)
                .create();

        dialog.setOnShowListener(d -> {
            final Button signIn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            signIn.setOnClickListener(v -> {
                final String password = input.getText().toString();
                if (password.isEmpty()) {
                    input.setError("Enter your password");
                    return;
                }
                signIn.setEnabled(false);
                attempt(activity, dialog, signIn, sessionEmail, sessionUid, password, callback);
            });
        });
        dialog.show();
    }

    private static void attempt(final Activity activity, final AlertDialog dialog,
                                final Button button, final String email, final String expectedUid,
                                final String password, final Callback callback) {
        final Context app = activity.getApplicationContext();

        FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password)
                .addOnSuccessListener(result -> {
                    FirebaseUser user = result.getUser();
                    if (user == null || !expectedUid.equals(user.getUid())) {
                        FirebaseAuth.getInstance().signOut();
                        toast(app, "That password belongs to a different account.");
                        button.setEnabled(true);
                        return;
                    }
                    final char[] chars = password.toCharArray();
                    // Refresh the verifier on the disk thread, then wipe our copy of the password
                    DbExecutor.run(() -> {
                        OfflineAuthManager.enroll(app, user, chars);
                        Arrays.fill(chars, '\0');
                    });
                    OfflineSession.end();                 // a REAL session replaces the local one
                    SyncScheduler.kick(app);              // send everything saved offline
                    DataChangeNotifier.notifyChanged(OfflineBanner.EVENT_CONNECTIVITY);
                    if (!activity.isFinishing()) {
                        dialog.dismiss();
                    }
                    toast(app, "Signed in. Syncing your saved changes...");
                    callback.onSignedIn();
                })
                .addOnFailureListener(e -> {
                    final SignInErrorMapper.Info info = SignInErrorMapper.map(e);
                    if (info.revokesOfflineAccess()) {
                        DbExecutor.run(() -> OfflineAuthManager.forgetEmail(app, email));
                        revoke(activity, dialog, info.message, callback);
                        return;
                    }
                    if (info.kind == SignInErrorMapper.Kind.WRONG_CREDENTIALS) {
                        // Typo, or did the password change on the server? Compare with our copy.
                        final char[] chars = password.toCharArray();
                        DbExecutor.async(
                                () -> {
                                    boolean removed = OfflineAuthManager
                                            .onServerRejectedCredentials(app, email, chars);
                                    Arrays.fill(chars, '\0');
                                    return removed;
                                },
                                removed -> {
                                    if (Boolean.TRUE.equals(removed)) {
                                        revoke(activity, dialog, "Your password was changed. "
                                                + "Please sign in again.", callback);
                                    } else {
                                        toast(app, info.message);
                                        button.setEnabled(true);
                                    }
                                });
                        return;
                    }
                    toast(app, info.message);
                    button.setEnabled(true);
                });
    }

    private static void revoke(Activity activity, AlertDialog dialog, String message,
                               Callback callback) {
        OfflineSession.end();
        DataChangeNotifier.notifyChanged(OfflineBanner.EVENT_CONNECTIVITY);
        if (!activity.isFinishing()) {
            dialog.dismiss();
        }
        toast(activity.getApplicationContext(), message);
        callback.onAccessRevoked();
    }

    private static void toast(Context c, String message) {
        Toast.makeText(c, message, Toast.LENGTH_LONG).show();
    }
}