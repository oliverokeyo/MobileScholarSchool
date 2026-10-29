package com.example.mobilescholarschool.Components;

import android.app.Activity;
import android.content.Intent;

import com.example.mobilescholarschool.SignIn.SignInActivity;
import com.google.firebase.auth.FirebaseAuth;

/**
 * NEW: One reusable "bouncer" for every screen that needs a signed-in user.
 *
 * Why a helper? Without it, each screen repeats the same 6 lines, and the day you
 * change the sign-in screen you must remember to edit all of them. Now there is one place.
 *
 * Usage, at the top of an Activity's onCreate (after super.onCreate):
 *     if (AuthGuard.redirectIfSignedOut(this)) return;
 */
public final class AuthGuard {

    // Private constructor: this class only holds a static method, nobody should create one
    private AuthGuard() { }

    /**
     * Checks who is signed in.
     *
     * @return true  if nobody is signed in. The user has ALREADY been sent to the sign-in
     *               screen and this Activity is finishing, so the caller must `return`.
     *         false if someone is signed in and the screen can carry on normally.
     */
    public static boolean redirectIfSignedOut(Activity activity) {
        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            return false;
        }

        Intent intent = new Intent(activity, SignInActivity.class);
        // Clear the back stack so the Back button can't return to a protected screen
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(intent);
        activity.finish();
        return true;
    }
}
