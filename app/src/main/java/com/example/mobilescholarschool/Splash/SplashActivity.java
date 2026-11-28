package com.example.mobilescholarschool.Splash;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.mobilescholarschool.Components.SessionUser;
import com.example.mobilescholarschool.HomeTab.HomeActivity;
import com.example.mobilescholarschool.Onboarding.OnboardingActivity;
import com.example.mobilescholarschool.R;

public class SplashActivity extends AppCompatActivity {


    private static final long SPLASH_DELAY_MS = 5000;

    private final Handler handler = new Handler(Looper.getMainLooper());

    // The job to run after the delay. Kept in a field so onDestroy can cancel it.
    private final Runnable openNextScreen = () -> {
        Intent intent = new Intent(SplashActivity.this, chooseDestination());
        startActivity(intent);
        finish();   // remove the splash from the back stack so Back can't return to it
    };

    /**
     * CHANGED: the ONE place that decides where a launch goes. Only one question matters now:
     *
     *   Is somebody signed in?  SessionUser.hasSession() asks Firebase (a real account), or an
     *   offline sign-up that is still waiting for internet. Nothing else counts.
     *
     *      signed in    -> Home         (returning student)
     *      signed out   -> Onboarding   (EVERY launch, whether first time or not)
     *
     * We no longer remember "the intro was seen": that memory is what used to skip onboarding.
     */
    private Class<?> chooseDestination() {
        return SessionUser.hasSession(this)
                ? HomeActivity.class
                : OnboardingActivity.class;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_splash);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        handler.postDelayed(openNextScreen, SPLASH_DELAY_MS);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // If the splash closes early (rotation, user leaves), cancel the waiting job so it can't later open a second screen.
        handler.removeCallbacks(openNextScreen);
    }
}