package com.example.mobilescholarschool.Onboarding;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.viewpager2.widget.ViewPager2;

import com.example.mobilescholarschool.R;
import com.example.mobilescholarschool.SignIn.SignInActivity;
import com.example.mobilescholarschool.SignUp.SignUpActivity;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

public class OnboardingActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    // There is no Next button. Pages are changed by swiping, and the page
    // indicator shows where you are.
    private MaterialButton btnSkip;
    private View authButtons; // the row holding Sign In + Sign Up (last page only)
    private int pageCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WindowCompat.enableEdgeToEdge(getWindow());
        setContentView(R.layout.activity_onboarding);

        // Photos sit behind the status bar, so use white status-bar icons.
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(false);

        viewPager = findViewById(R.id.viewPager);
        btnSkip = findViewById(R.id.btnSkip);
        authButtons = findViewById(R.id.authButtons);
        MaterialButton btnSignIn = findViewById(R.id.btnSignIn);
        MaterialButton btnSignUp = findViewById(R.id.btnSignUp);
        OnboardingPageIndicator pageIndicator = findViewById(R.id.pageIndicator);
        View bottomPanel = findViewById(R.id.bottomPanel);

        applyWindowInsets(bottomPanel);

        // 1) Data
        List<OnboardingItem> items = buildPages();
        pageCount = items.size();

        // 2) Adapter
        viewPager.setAdapter(new OnboardingAdapter(items));

        // 3) Page indicator: one dot per page. It reads the pager's position every frame,
        // so it always matches the visible page and animates as the pages move.
        // It must be attached AFTER the adapter is set (it counts the pages).
        pageIndicator.attachToViewPager(viewPager);

        // 4) React to swiping
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateControls(position);
            }
        });
        updateControls(0);

        // 5) Buttons
        btnSkip.setOnClickListener(v -> openSignIn());
        btnSignIn.setOnClickListener(v -> openSignIn());
        btnSignUp.setOnClickListener(v -> openSignUp());

        // 6) Back button goes to the previous page before leaving the app
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                int current = viewPager.getCurrentItem();
                if (current > 0) {
                    viewPager.setCurrentItem(current - 1, true);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    /** The content of every page lives here: add a line to add a page. */
    private List<OnboardingItem> buildPages() {
        List<OnboardingItem> items = new ArrayList<>();
        items.add(new OnboardingItem(
                R.drawable.onboarding_campus,
                R.string.onboarding_title_campus,
                R.string.onboarding_desc_campus));
        items.add(new OnboardingItem(
                R.drawable.onboarding_library,
                R.string.onboarding_title_library,
                R.string.onboarding_desc_library));
        items.add(new OnboardingItem(
                R.drawable.onboarding_hostel,
                R.string.onboarding_title_hostel,
                R.string.onboarding_desc_hostel));
        items.add(new OnboardingItem(
                R.drawable.onboarding_payments,
                R.string.onboarding_title_payments,
                R.string.onboarding_desc_payments));
        return items;
    }

    /**
     * Pages before the last: Skip only (swipe to continue).
     * Last page: Sign In + Sign Up buttons appear, and Skip is hidden.
     *
     * The buttons use INVISIBLE rather than GONE on purpose: an invisible view
     * still takes up its space, so the page indicator stays put instead of
     * jumping on the last page.
     */
    private void updateControls(int position) {
        boolean isLast = position == pageCount - 1;
        authButtons.setVisibility(isLast ? View.VISIBLE : View.INVISIBLE);
        btnSkip.setVisibility(isLast ? View.INVISIBLE : View.VISIBLE);
    }

    /**
     * Edge-to-edge means our UI draws under the status and navigation bars.
     * We push ONLY the controls clear of the bars, so the photos stay full-bleed.
     */
    private void applyWindowInsets(View bottomPanel) {
        final int skipBaseTop = ((ViewGroup.MarginLayoutParams) btnSkip.getLayoutParams()).topMargin;
        final int panelLeft = bottomPanel.getPaddingLeft();
        final int panelTop = bottomPanel.getPaddingTop();
        final int panelRight = bottomPanel.getPaddingRight();
        final int panelBottom = bottomPanel.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());

            ViewGroup.MarginLayoutParams skipParams = (ViewGroup.MarginLayoutParams) btnSkip.getLayoutParams();
            skipParams.topMargin = skipBaseTop + bars.top;
            btnSkip.setLayoutParams(skipParams);

            bottomPanel.setPadding(
                    panelLeft + bars.left,
                    panelTop,
                    panelRight + bars.right,
                    panelBottom + bars.bottom);
            return insets;
        });
    }

    private void openSignIn() {
        startActivity(new Intent(this, SignInActivity.class));
        finish(); // remove onboarding from the back stack
    }

    private void openSignUp() {
        startActivity(new Intent(this, SignUpActivity.class));
        finish(); // remove onboarding from the back stack
    }
}