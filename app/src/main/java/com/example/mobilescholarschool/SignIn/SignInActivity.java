package com.example.mobilescholarschool.SignIn;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.util.Patterns;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.mobilescholarschool.HomeTab.HomeActivity;
import com.example.mobilescholarschool.R;
import com.example.mobilescholarschool.Components.PasswordToggle;
import com.example.mobilescholarschool.Components.PendingRegistrationStore;       // NEW
import com.example.mobilescholarschool.Components.RegistrationSyncScheduler;      // NEW
import com.example.mobilescholarschool.SignUp.SignUpActivity;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.FirebaseTooManyRequestsException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthInvalidUserException;

import org.json.JSONObject;                                                       // NEW

public class SignInActivity extends AppCompatActivity {

    EditText editEmail, editPassword;
    Button btnSignIn;
    TextView txtViewRegister;

    FirebaseAuth mAuth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_sign_in);
        mAuth = FirebaseAuth.getInstance();
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        editEmail = findViewById(R.id.editTxtTxtEmailAddress);
        editPassword = findViewById(R.id.editTxtTxtPassword);
        btnSignIn = findViewById(R.id.btnSignIn);
        txtViewRegister = findViewById(R.id.txtViewRegister);

        // Make the eye icons clickable (show / hide password)
        PasswordToggle.attach(editPassword);

        btnSignIn.setOnClickListener(v -> attemptSignIn());

        txtViewRegister.setOnClickListener(v -> goToSignUp());
    }

    // If the user signed in earlier and never signed out, skip this screen
    // (this is also why returning users already work offline: Firebase remembers them)
    @Override
    protected void onStart() {
        super.onStart();
        if (mAuth.getCurrentUser() != null) {
            goToHome();
        }
    }

    // CHANGED: checks for a pending (not yet synced) registration BEFORE the offline check
    private void attemptSignIn() {
        String emailAddress = editEmail.getText().toString().trim();
        String password = editPassword.getText().toString();   // never trimmed

        if (!isFormValid(emailAddress, password)) {
            return;
        }

        // NEW: this student registered offline and the account is not on the server yet.
        // Firebase would say "Incorrect email or password", which would be wrong and
        // alarming, so we explain what is really happening instead.
        if (isPendingRegistration(emailAddress, password)) {
            // Safety net: make sure the sync job is scheduled (KEEP prevents duplicates)
            RegistrationSyncScheduler.enqueue(this);

            String message = isOnline()
                    ? "Finishing setting up your account. Please try again in a moment."
                    : "Your registration is saved on this device. Connect to the internet "
                    + "and your account will be created automatically.";
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            return;
        }

        if (!isOnline()) {
            Toast.makeText(this,
                    "No internet connection. Logging in needs internet.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        login(emailAddress, password);
    }

    /**
     * NEW: true if the typed email AND password match the registration waiting in the
     * encrypted outbox. Matching the password too means a stranger who only knows the
     * email learns nothing about whether a registration is pending.
     */
    private boolean isPendingRegistration(String emailAddress, String password) {
        try {
            JSONObject pending = PendingRegistrationStore.load(this);
            return pending != null
                    && emailAddress.equalsIgnoreCase(pending.optString("email"))
                    && password.equals(pending.optString("password"));
        } catch (Exception e) {
            // If the store cannot be read, behave as if nothing is pending
            return false;
        }
    }

    private boolean isFormValid(String emailAddress, String password) {
        if (emailAddress.isEmpty()) {
            editEmail.setError("Email address is required");
            editEmail.requestFocus();
            return false;
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(emailAddress).matches()) {
            editEmail.setError("Enter a valid email address");
            editEmail.requestFocus();
            return false;
        }
        if (password.isEmpty()) {
            editPassword.setError("Password is required");
            editPassword.requestFocus();
            return false;
        }
        return true;
    }

    private void login(String emailAddress, String password) {

        btnSignIn.setEnabled(false); // stop double taps while we wait

        mAuth.signInWithEmailAndPassword(emailAddress, password)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        goToHome();
                    } else {
                        btnSignIn.setEnabled(true);
                        showAuthError(task.getException());
                    }
                });
    }

    private void showAuthError(Exception e) {
        String message;
        if (e instanceof FirebaseAuthInvalidUserException
                || e instanceof FirebaseAuthInvalidCredentialsException) {
            message = "Incorrect email or password.";
        } else if (e instanceof FirebaseTooManyRequestsException) {
            message = "Too many attempts. Please wait a moment and try again.";
        } else if (e instanceof FirebaseNetworkException) {
            message = "Network error. Check your internet connection and try again.";
        } else {
            message = "Login failed. Please try again.";
        }
        Toast.makeText(SignInActivity.this, message, Toast.LENGTH_LONG).show();
    }

    private void goToHome() {
        Intent intent = new Intent(SignInActivity.this, HomeActivity.class);
        startActivity(intent);
        finish();
    }

    private void goToSignUp() {
        Intent intent = new Intent(SignInActivity.this, SignUpActivity.class);
        startActivity(intent);
        finish();
    }

    /** Needs the ACCESS_NETWORK_STATE permission in AndroidManifest.xml */
    private boolean isOnline() {
        ConnectivityManager cm =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network = cm.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
}