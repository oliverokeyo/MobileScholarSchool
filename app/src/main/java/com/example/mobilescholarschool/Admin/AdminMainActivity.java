package com.example.mobilescholarschool.Admin;

import android.os.Bundle;
import android.view.MotionEvent;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.example.mobilescholarschool.R;

/**
 * The admin side's single screen. The pill bar picks which of the five Fragments is shown:
 * Library, Hostel, Payments, Departments &amp; Courses, System.
 */
public class AdminMainActivity extends AppCompatActivity {

    private static final String STATE_TAB = "state_tab";

    private AdminNavHelper nav;
    private AdminSwipeNavigator swipe;
    private int currentTab;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.admin_activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        nav = new AdminNavHelper(this, this::showTab);
        currentTab = savedInstanceState != null
                ? savedInstanceState.getInt(STATE_TAB, nav.firstTabId()) : nav.firstTabId();
        nav.select(currentTab, false);

        // Swipe left / right between the five tabs (same feel as the student side).
        swipe = new AdminSwipeNavigator(this, nav.tabIds(), new AdminSwipeNavigator.Host() {
            @Override public int currentTab() { return currentTab; }
            @Override public void switchTo(int tabId) {
                nav.select(tabId, true);   // the pill springs across
                showTab(tabId);            // and the Fragment is swapped
            }
        });
        if (savedInstanceState == null) {
            showTab(currentTab);
        }
    }

    /** Ask the swipe first. If it has taken over the gesture, the rest of the screen never sees it. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (swipe != null && swipe.onTouchEvent(ev)) {
            return true;
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onDestroy() {
        if (swipe != null) swipe.release();
        super.onDestroy();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Safety net: only a definite "no admin record" sends the person away. Offline (null) stays.
        AdminSession.check(this, isAdmin -> {
            if (Boolean.FALSE.equals(isAdmin)) {
                if (com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser() == null) {
                    AdminSession.signOutToSignIn(this);
                } else {
                    startActivity(new android.content.Intent(this,
                            com.example.mobilescholarschool.HomeTab.HomeActivity.class));
                    finish();
                }
            }
        });
    }

    @Override
    protected void onSaveInstanceState(android.os.Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, currentTab);
    }

    private void showTab(int itemId) {
        currentTab = itemId;
        Fragment fragment;
        if (itemId == R.id.admin_nav_library) fragment = new LibraryAdminFragment();
        else if (itemId == R.id.admin_nav_hostel) fragment = new HostelAdminFragment();
        else if (itemId == R.id.admin_nav_payments) fragment = new PaymentsAdminFragment();
        else if (itemId == R.id.admin_nav_departments) fragment = new DepartmentsAdminFragment();
        else fragment = new SystemAdminFragment();
        getSupportFragmentManager().beginTransaction()
                .setReorderingAllowed(true)
                .replace(R.id.adminContainer, fragment)
                .commit();
    }
}