package com.example.mobilescholarschool.Components;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.mobilescholarschool.Data.DataChangeNotifier;
import com.example.mobilescholarschool.R;
import com.example.mobilescholarschool.SignIn.SignInActivity;
import com.example.mobilescholarschool.Security.OfflineSession;

/**
 * NEW: the strip that tells the student they are in OFFLINE MODE.
 *
 * It is built in code and added to the screen's content area, so no layout XML has to change.
 * It sits at the BOTTOM, just above the tab bar (like the "waiting for account" Snackbar), so it
 * never covers the screen's header or the notification button. It appears only while an
 * OfflineSession is active:
 *   offline -> "Offline mode: saved data only. Payments and account changes need internet."
 *   online  -> "Back online. Tap here to sign in and sync."   (tap opens the password prompt)
 *
 * USE IN EACH SCREEN (same pairing as DataChangeNotifier / Firestore listeners):
 *     private OfflineBanner banner;
 *     onCreate: banner = new OfflineBanner(this, reauthCallback);
 *     onStart:  banner.start();
 *     onStop:   banner.stop();
 *
 * It refreshes itself when ScholarApp announces a connectivity change (EVENT_CONNECTIVITY).
 */
public final class OfflineBanner implements DataChangeNotifier.Listener {

    /** The "table" name ScholarApp / the re-auth dialog pass to DataChangeNotifier.notifyChanged. */
    public static final String EVENT_CONNECTIVITY = "connectivity";

    private static final String VIEW_TAG = "offline_mode_banner";

    // Must match BAR_HEIGHT_DP in BottomNavHelper: the banner floats exactly this far above the bottom
    private static final int NAV_BAR_HEIGHT_DP = 80;

    private final Activity activity;
    private final OnlineReauthDialog.Callback callback;

    public OfflineBanner(Activity activity, OnlineReauthDialog.Callback callback) {
        this.activity = activity;
        this.callback = callback;
    }

    /**
     * NEW: the banner with the usual reactions, so a screen needs no callback code of its own:
     *   signed in to the server -> reload this screen (it now has a real Firebase user)
     *   access revoked          -> back to Sign In, clearing the back stack
     */
    public static OfflineBanner standard(final Activity activity) {
        return new OfflineBanner(activity, standardCallback(activity));
    }

    /** NEW: just the usual reactions (also used by OnlineOnlyGuard when it asks for a sign-in). */
    public static OnlineReauthDialog.Callback standardCallback(final Activity activity) {
        return new OnlineReauthDialog.Callback() {
            @Override
            public void onSignedIn() {
                activity.recreate();
            }

            @Override
            public void onAccessRevoked() {
                Intent intent = new Intent(activity, SignInActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                activity.startActivity(intent);
                activity.finish();
            }
        };
    }

    public void start() {
        DataChangeNotifier.register(this);
        refresh();
    }

    public void stop() {
        DataChangeNotifier.unregister(this);
    }

    @Override
    public void onDataChanged(String table) {
        if (EVENT_CONNECTIVITY.equals(table)) {
            refresh();
        }
    }

    /** Show, update or remove the banner to match the current session and connection. */
    public void refresh() {
        ViewGroup root = activity.findViewById(android.R.id.content);
        if (root == null) {
            return;
        }
        View existing = root.findViewWithTag(VIEW_TAG);
        if (!OfflineSession.isActive()) {
            if (existing != null) {
                root.removeView(existing);
            }
            return;
        }
        TextView banner = existing instanceof TextView ? (TextView) existing : create(root);

        if (ConnectivityHelper.isOnline(activity)) {
            banner.setText("Back online. Tap here to sign in and sync your saved changes.");
            banner.setOnClickListener(v -> OnlineReauthDialog.show(activity, callback));
        } else {
            banner.setText("Offline mode: saved data only. Payments and account changes "
                    + "need internet.");
            banner.setOnClickListener(null);
            banner.setClickable(false);
        }
    }

    private TextView create(ViewGroup root) {
        final int pad = dp(10);
        // Only the tab screens have a bottom bar to sit above
        final boolean hasTabBar = activity.findViewById(R.id.bottomNav) != null;

        TextView banner = new TextView(activity);
        banner.setTag(VIEW_TAG);
        banner.setTextColor(Color.WHITE);
        banner.setBackgroundColor(0xFFB45309);   // amber: noticeable but not alarming
        banner.setTextSize(12.5f);
        banner.setGravity(Gravity.CENTER);
        banner.setPadding(pad, pad, pad, pad);
        banner.setElevation(dp(8));              // draw above the screen's own content

        // The app draws edge to edge: lift the strip above the system navigation bar (and the tab bar)
        ViewCompat.setOnApplyWindowInsetsListener(banner, (v, insets) -> {
            int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            lp.bottomMargin = bottom + (hasTabBar ? dp(NAV_BAR_HEIGHT_DP) : 0);
            v.setLayoutParams(lp);
            return insets;
        });

        root.addView(banner, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
        ViewCompat.requestApplyInsets(banner);
        return banner;
    }

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density);
    }
}