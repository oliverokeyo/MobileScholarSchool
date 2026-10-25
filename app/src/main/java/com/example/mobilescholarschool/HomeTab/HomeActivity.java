package com.example.mobilescholarschool.HomeTab;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.mobilescholarschool.Components.BottomNavHelper;
import com.example.mobilescholarschool.R;
import com.example.mobilescholarschool.SignIn.SignInActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.Calendar;

public class HomeActivity extends AppCompatActivity {
    private static final String STUDENT_ID_FIELD = "registrationNo";

    private TextView txtViewWelcome, txtViewStudentID;

    private String profileName;
    private String studentId;

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

    // Runs every time the screen comes to the front, so the greeting stays current
    @Override
    protected void onResume() {
        super.onResume();

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();

        // Safety net: if nobody is signed in, send them to the login screen
        if (user == null) {
            startActivity(new Intent(this, SignInActivity.class));
            finish();
            return;
        }

        showWelcome(user);

        if (studentId == null) {
            loadProfile(user);
        } else {
            showStudentId();
        }
    }

    /** Reads the profile that SignUpActivity saved at users/{uid} in Firestore. */
    private void loadProfile(FirebaseUser user) {
        txtViewStudentID.setText(R.string.student_id_loading);

        FirebaseFirestore.getInstance()
                .collection("users")
                .document(user.getUid())
                .get()
                .addOnSuccessListener(this, doc -> {
                    if (!doc.exists()) {
                        txtViewStudentID.setText(R.string.student_id_not_available);
                        return;
                    }
                    profileName = doc.getString("fullName");
                    studentId = doc.getString(STUDENT_ID_FIELD);

                    showWelcome(user);
                    showStudentId();
                })
                .addOnFailureListener(this, e ->
                        txtViewStudentID.setText(R.string.student_id_unavailable));
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