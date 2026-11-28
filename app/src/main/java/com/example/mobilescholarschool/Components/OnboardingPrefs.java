package com.example.mobilescholarschool.Components;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * NEW: remembers ONE fact: "has this phone's user seen the onboarding?".
 *
 * LESSON: KEEP ONBOARDING AND SIGN-IN SEPARATE. They answer different questions:
 *   "Is somebody signed in?"          -> FirebaseAuth (via SessionUser)
 *   "Did they already see the intro?" -> this flag
 * Mixing them (for example resetting this flag on sign-out) would show the intro again to a
 * student who has seen it a hundred times. Signing out NEVER touches this file.
 *
 * It holds no account information at all, so it is safe in ordinary SharedPreferences and
 * harmless if Android restores it from a backup.
 */
public final class OnboardingPrefs {

    private static final String PREFS = "app_state";
    private static final String KEY_COMPLETED = "onboarding_completed";

    private OnboardingPrefs() { }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isCompleted(Context c) {
        return prefs(c).getBoolean(KEY_COMPLETED, false);
    }

    /** Called when the student leaves onboarding (Skip, Sign In or Sign Up) or signs in. */
    public static void markCompleted(Context c) {
        prefs(c).edit().putBoolean(KEY_COMPLETED, true).apply();
    }
}
