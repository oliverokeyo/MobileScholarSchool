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

import com.example.mobilescholarschool.R;
import com.example.mobilescholarschool.SignIn.SignInActivity;

public class SplashActivity extends AppCompatActivity {


    private static final long SPLASH_DELAY_MS = 5000;

    private final Handler handler = new Handler(Looper.getMainLooper());

    // The job to run after the delay. Kept in a field so onDestroy can cancel it.
    private final Runnable openSignIn = () -> {
        Intent intent = new Intent(SplashActivity.this, SignInActivity.class);
        startActivity(intent);
        finish();   // remove the splash from the back stack so Back can't return to it
    };

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

        handler.postDelayed(openSignIn, SPLASH_DELAY_MS);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // If the splash closes early (rotation, user leaves), cancel the waiting job so it can't later open a second Sign In screen.
        handler.removeCallbacks(openSignIn);
    }
}