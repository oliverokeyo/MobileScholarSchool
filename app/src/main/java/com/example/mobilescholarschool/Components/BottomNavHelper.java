package com.example.mobilescholarschool.Components;

import android.app.Activity;
import android.content.Intent;
import android.widget.Toast;

import com.example.mobilescholarschool.HomeTab.HomeActivity;
import com.example.mobilescholarschool.LibraryTab.LibraryTabActivity;
import com.example.mobilescholarschool.R;
import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * Shared bottom navigation logic.
 *
 * To add a new tab screen: create the Activity, add its layout's <include> of
 * layout_bottom_nav, call BottomNavHelper.setup(this, R.id.nav_xxx) in onCreate,
 * then add one line for it in destinationFor() below.
 */
public final class BottomNavHelper {

    private BottomNavHelper() {}   // utility class: nobody should create one

    /**
     * Call this from every screen that shows the bottom bar.
     * @param selectedItemId the tab that matches the current screen, e.g. R.id.nav_home
     */
    public static void setup(Activity activity, int selectedItemId) {
        BottomNavigationView nav = activity.findViewById(R.id.bottomNav);

        // Highlight the current tab BEFORE attaching the listener,
        nav.setSelectedItemId(selectedItemId);

        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();

            if (id == selectedItemId) {
                return true;
            }

            Class<?> target = destinationFor(id);
            if (target == null) {
                Toast.makeText(activity,
                        activity.getString(R.string.nav_coming_soon, item.getTitle()),
                        Toast.LENGTH_SHORT).show();
                return false;  // false = don't move the highlight
            }

            activity.startActivity(new Intent(activity, target));
            activity.finish();
            return true;
        });
    }

    /** Which screen each tab opens. */
    private static Class<?> destinationFor(int itemId) {
        if (itemId == R.id.nav_home) return HomeActivity.class;
        if (itemId == R.id.nav_library) return LibraryTabActivity.class;
        return null;   // screen not built yet
    }
}