package com.example.mobilescholarschool.Admin;

import android.app.Activity;
import android.content.Intent;

import com.example.mobilescholarschool.SignIn.SignInActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.Locale;

/**
 * LESSON: "who is an admin?" is answered by DATA, not by code. A person is an admin when a
 * document exists at admins/{their uid}. The Firestore rules enforce the same fact on the server,
 * so even a tampered app cannot write admin data: this class only decides which SCREENS to show.
 */
public final class AdminSession {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_SUSPENDED = "suspended";
    public static final String STATUS_DEACTIVATED = "deactivated";

    /** isAdmin is TRUE / FALSE when the server answered, and null when we could not find out (offline). */
    public interface Callback {
        void onResult(Boolean isAdmin);
    }

    private AdminSession() {}

    /** Asks Firestore whether the signed-in person has an admins/{uid} document. */
    public static void check(Activity activity, Callback callback) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            callback.onResult(Boolean.FALSE);
            return;
        }
        FirebaseFirestore.getInstance().collection("admins").document(user.getUid()).get()
                .addOnSuccessListener(activity, doc -> callback.onResult(doc.exists()))
                .addOnFailureListener(activity, e -> callback.onResult(null));
    }

    /** Called by the student Home screen: if this person is an admin, swap to the admin side. */
    public static void redirectIfAdmin(Activity activity) {
        check(activity, isAdmin -> {
            if (Boolean.TRUE.equals(isAdmin) && !activity.isFinishing()) {
                openAdmin(activity);
            }
        });
    }

    public static void openAdmin(Activity activity) {
        Intent intent = new Intent(activity, AdminMainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(intent);
        activity.finish();
    }

    /** Signs out and opens Sign In with an empty back stack (Back cannot return to the admin screens). */
    public static void signOutToSignIn(Activity activity) {
        FirebaseAuth.getInstance().signOut();
        Intent intent = new Intent(activity, SignInActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(intent);
        activity.finish();
    }

    /** A missing or unknown value means "active", so profiles made before this feature keep working. */
    public static String normalizeStatus(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return (s.equals(STATUS_SUSPENDED) || s.equals(STATUS_DEACTIVATED)) ? s : STATUS_ACTIVE;
    }

    /** True for a student who may not use the app. */
    public static boolean isBlocked(String raw) {
        return !STATUS_ACTIVE.equals(normalizeStatus(raw));
    }
}
