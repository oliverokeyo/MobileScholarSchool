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
import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.ArrayList;
import java.util.List;

public class OnboardingActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    private MaterialButton btnNext;
    private MaterialButton btnSkip;
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
        btnNext = findViewById(R.id.btnNext);
        btnSkip = findViewById(R.id.btnSkip);
        TabLayout dotsIndicator = findViewById(R.id.dotsIndicator);
        View bottomPanel = findViewById(R.id.bottomPanel);

        applyWindowInsets(bottomPanel);

        // 1) Data
        List<OnboardingItem> items = buildPages();
        pageCount = items.size();

        // 2) Adapter
        viewPager.setAdapter(new OnboardingAdapter(items));

        // 3) Dots: TabLayoutMediator links one tab per page and keeps them in sync.
        new TabLayoutMediator(dotsIndicator, viewPager, (tab, position) -> { }).attach();
        for (int i = 0; i < dotsIndicator.getTabCount(); i++) {
            TabLayout.Tab tab = dotsIndicator.getTabAt(i);
            if (tab != null) {
                tab.view.setClickable(false); // dots are display-only
            }
        }

        // 4) React to swiping
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateControls(position);
            }
        });
        updateControls(0);

        // 5) Buttons
        btnNext.setOnClickListener(v -> {
            int current = viewPager.getCurrentItem();
            if (current < pageCount - 1) {
                viewPager.setCurrentItem(current + 1, true);
            } else {
                openSignIn();
            }
        });
        btnSkip.setOnClickListener(v -> openSignIn());

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
        return items;
    }

    /** Last page: "Get Started" and no Skip. Other pages: "Next" and Skip. */
    private void updateControls(int position) {
        boolean isLast = position == pageCount - 1;
        btnNext.setText(isLast ? R.string.onboarding_get_started : R.string.onboarding_next);
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

            ViewGroup.MarginLayoutParams skipParams =
                    (ViewGroup.MarginLayoutParams) btnSkip.getLayoutParams();
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
}