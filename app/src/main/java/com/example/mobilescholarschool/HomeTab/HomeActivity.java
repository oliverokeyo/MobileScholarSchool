package com.example.mobilescholarschool.HomeTab;

import android.os.Bundle;
import android.util.Log;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.mobilescholarschool.Components.AuthGuard;
import com.example.mobilescholarschool.Components.BottomNavHelper;
import com.example.mobilescholarschool.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.Calendar;

public class HomeActivity extends AppCompatActivity {

    private TextView txtViewWelcome, txtViewStudentID;

    private String profileName;
    private String studentId;

    // Remembers live connection to Firestore so we can switch it off in onStop
    private ListenerRegistration profileListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_home);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        txtViewWelcome = findViewById(R.id.txtViewWelcome);
        txtViewStudentID = findViewById(R.id.txtViewStudentID);

        // Reusable bottom bar: tell it which tab this screen is
        BottomNavHelper.setup(this, R.id.nav_home);

        ImageButton btnNotifications = findViewById(R.id.imgNotifications);
        btnNotifications.setOnClickListener(v ->
                Toast.makeText(this, R.string.notifications_none, Toast.LENGTH_SHORT).show());
    }

    /**
     * CHANGED: the sign-in check and the Firestore connection now live in onStart.
     * onStart runs every time the screen becomes visible, and onStop (below) switches the
     * connection off when it is hidden. The pair keeps the listener from leaking.
     */
    @Override
    protected void onStart() {
        super.onStart();

        // Safety net: if nobody is signed in, AuthGuard sends them to the login screen
        if (AuthGuard.redirectIfSignedOut(this)) {
            return;
        }

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        showWelcome(user);
        listenToProfile(user);
    }

    /** NEW: stop listening while the screen is hidden (saves battery and data). */
    @Override
    protected void onStop() {
        super.onStop();
        if (profileListener != null) {
            profileListener.remove();
            profileListener = null;
        }
    }

    /** refreshes the greeting, because "Good morning" can change to
     * "Good afternoon" while the app sits in the background.
     */
    @Override
    protected void onResume() {
        super.onResume();
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) {
            showWelcome(user);
        }
    }

    /**
     * This is a LIVE connection: Firestore
     * calls the lambda once with the current data, then again every time the document changes.
     * So when a student edits their registration number on the Profile screen, Home updates itself.
     */
    private void listenToProfile(FirebaseUser user) {
        // Only show "loading" if we have nothing yet, so a refresh doesn't flash the screen
        if (studentId == null) {
            txtViewStudentID.setText(R.string.student_id_loading);
        }

        profileListener = FirebaseFirestore.getInstance()
                .collection("users")
                .document(user.getUid())
                .addSnapshotListener((doc, error) -> {
                    if (error != null) {
                        // Log the real reason (e.g. PERMISSION_DENIED) so you can find it in Logcat
                        Log.e("HomeActivity", "Profile listener failed", error);
                        // Keep showing old data if we already have some
                        if (studentId == null) {
                            txtViewStudentID.setText(R.string.student_id_unavailable);
                        }
                        return;
                    }

                    if (doc == null || !doc.exists()) {
                        profileName = null;
                        studentId = null;
                        showWelcome(user);
                        showStudentId();
                        return;
                    }

                    // "registrationNo" and "fullName" must match the names SignUpActivity saves
                    profileName = doc.getString("fullName");
                    studentId = doc.getString("registrationNo");

                    showWelcome(user);
                    showStudentId();
                });
    }

    private void showWelcome(FirebaseUser user) {
        txtViewWelcome.setText(getString(R.string.welcome_format,
                getGreeting(), getUserName(user)));
    }

    private void showStudentId() {
        if (studentId == null || studentId.trim().isEmpty()) {
            txtViewStudentID.setText(R.string.student_id_not_available);
        } else {
            txtViewStudentID.setText(getString(R.string.student_id_format, studentId));
        }
    }

    private String getGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);

        if (hour >= 5 && hour < 12) {
            return getString(R.string.greeting_morning);
        } else if (hour >= 12 && hour < 17) {
            return getString(R.string.greeting_afternoon);
        } else {
            return getString(R.string.greeting_evening);
        }
    }

    private String getUserName(FirebaseUser user) {
        // 1. Full name saved at sign-up (Firestore)
        if (profileName != null && !profileName.trim().isEmpty()) {
            return profileName;
        }
        // 2. Firebase display name, if one was ever set
        String displayName = user.getDisplayName();
        if (displayName != null && !displayName.trim().isEmpty()) {
            return displayName;
        }
        // 3. Part of the email before the @
        String email = user.getEmail();
        if (email != null && email.contains("@")) {
            return email.substring(0, email.indexOf("@"));
        }
        return getString(R.string.default_user_name);
    }
}