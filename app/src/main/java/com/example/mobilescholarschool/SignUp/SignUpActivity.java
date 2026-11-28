package com.example.mobilescholarschool.SignUp;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Patterns;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.mobilescholarschool.Components.SessionUser;                  // NEW
import com.example.mobilescholarschool.Data.DbExecutor;                         // NEW
import com.example.mobilescholarschool.Data.ProfileRepository;                  // NEW
import com.example.mobilescholarschool.HomeTab.HomeActivity;                 // NEW
import com.example.mobilescholarschool.R;
import com.example.mobilescholarschool.Components.PasswordToggle;
import com.example.mobilescholarschool.Components.PendingRegistrationStore;   // NEW
import com.example.mobilescholarschool.Data.AppDatabase;                 // NEW
import com.example.mobilescholarschool.Components.OnboardingPrefs;           // NEW
import com.example.mobilescholarschool.SignIn.SignInActivity;
import com.example.mobilescholarschool.Sync.SyncScheduler;                    // CHANGED: one shared scheduler
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import org.json.JSONObject;                                           // NEW

import java.util.HashMap;
import java.util.Map;

public class SignUpActivity extends AppCompatActivity {

    EditText editxtTxtFullName,
            editxtTxtIDNumber,
            editxtTxtRegisterationNo,
            editxtTxtEmailAddress,
            editxtTxtPassword,
            editxtTxtConfirmPassword;
    RadioGroup radioGroupGender;

    Spinner spinnerDepartment;
    Spinner spinnerCourse;
    Button btnSignUp;

    CheckBox checkBoxTC;
    TextView txtViewLogin;

    FirebaseAuth mAuth;
    FirebaseFirestore db;

    // How long we wait for Firestore to confirm the profile
    private static final long SAVE_TIMEOUT_MS = 15_000;
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable saveTimeout;
    // Numbers each save attempt so a late reply from an OLD attempt can be ignored
    private int saveAttempt = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_sign_up);
        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            // systemBars = status bar + navigation bar, ime = the on-screen keyboard.
            // Including ime makes the ScrollView leave room for the keyboard so the
            // whole form can be scrolled above it.
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        editxtTxtFullName = findViewById(R.id.editTxtTxtFullName);
        editxtTxtIDNumber = findViewById(R.id.editTxtIDNumber);
        editxtTxtRegisterationNo = findViewById(R.id.editTxtTxtRegisterationNo);
        editxtTxtEmailAddress = findViewById(R.id.editTxtTxtEmailAddress);
        editxtTxtPassword = findViewById(R.id.editTxtTxtPassword);
        editxtTxtConfirmPassword = findViewById(R.id.editTxtTxtConfirmPassword);

        radioGroupGender = findViewById(R.id.radioGroupGender);
        spinnerDepartment = findViewById(R.id.spinnerDepartment);
        spinnerCourse = findViewById(R.id.spinnerCourse);
        checkBoxTC = findViewById(R.id.checkBoxTC);
        btnSignUp = findViewById(R.id.btnSignUp);
        txtViewLogin = findViewById(R.id.txtViewLogin);

        // Make the eye icons clickable (show / hide password)
        PasswordToggle.attach(editxtTxtPassword);
        PasswordToggle.attach(editxtTxtConfirmPassword);

        txtViewLogin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goToSignIn();
            }
        });

        // NEW (admin): administrators register on their own screen, with an invite code
        findViewById(R.id.txtViewAdminSignUp).setOnClickListener(v ->
                startActivity(new Intent(this,
                        com.example.mobilescholarschool.Admin.AdminSignUpActivity.class)));

        btnSignUp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                attemptSignUp();
            }
        });
    }

    // CHANGED: no longer refuses when offline. It now chooses between two paths:
    //   online  -> create the account right now (original behaviour)
    //   offline -> save the form on the device and let WorkManager finish it later
    private void attemptSignUp() {
        String fullName = editxtTxtFullName.getText().toString().trim();
        String idNumber = editxtTxtIDNumber.getText().toString().trim();
        String registrationNo = editxtTxtRegisterationNo.getText().toString().trim();
        String emailAddress = editxtTxtEmailAddress.getText().toString().trim();
        String password = editxtTxtPassword.getText().toString();
        String confirmPassword = editxtTxtConfirmPassword.getText().toString();

        if (!isFormValid(fullName, idNumber, registrationNo, emailAddress, password, confirmPassword)) {
            return;
        }

        String gender = radioGroupGender.getCheckedRadioButtonId() == R.id.radioMale
                ? "Male" : "Female";
        String department = spinnerDepartment.getSelectedItem().toString();
        String course = spinnerCourse.getSelectedItem().toString();

        if (isOnline()) {
            createAccount(fullName, idNumber, registrationNo, emailAddress, password,
                    gender, department, course);
        } else {
            queueForLater(fullName, idNumber, registrationNo, emailAddress, password,
                    gender, department, course);
        }
    }

    // NEW: the "drop box". Saves the form encrypted on the device, then asks WorkManager
    // to run SyncWorker (via SyncScheduler) as soon as the phone has an internet connection.
    private void queueForLater(String fullName, String idNumber, String registrationNo,
                               String email, String password,
                               String gender, String department, String course) {
        try {
            JSONObject data = new JSONObject();
            data.put("fullName", fullName);
            data.put("idNumber", idNumber);
            data.put("registrationNo", registrationNo);
            data.put("email", email);
            data.put("password", password);
            data.put("gender", gender);
            data.put("department", department);
            data.put("course", course);

            // NEW: the PROVISIONAL id. Everything this student does before the account exists is
            // saved under it; SyncWorker swaps it for the real uid once the account is created.
            // It goes INTO the saved registration so the worker can find it even if the app
            // is killed and restarted.
            String localUid = SessionUser.newProvisionalUid();
            data.put("localUid", localUid);
            PendingRegistrationStore.save(this, data);
            SessionUser.setProvisional(this, localUid);   // only AFTER the registration is saved

            // NEW: a local copy of the profile, so Profile, Library and Hostel have the student's
            // name and student number straight away. (It is "synced" because there is nothing
            // to send yet: the registration itself creates the server profile.)
            ProfileRepository.LocalProfile copy = new ProfileRepository.LocalProfile();
            copy.uid = localUid;
            copy.fullName = fullName;
            copy.idNumber = idNumber;
            copy.registrationNo = registrationNo;
            copy.email = email;
            copy.gender = gender;
            copy.department = department;
            copy.course = course;
            DbExecutor.run(() -> ProfileRepository.saveFromServer(getApplicationContext(), copy));

            // CHANGED: the 10 lines of WorkManager setup that used to be here now live in ONE
            // place (SyncScheduler), shared by every screen. SyncWorker finishes the sign-up.
            SyncScheduler.enqueue(this);

            Toast.makeText(this,
                    "Saved! Your account will be created when you're back online.",
                    Toast.LENGTH_LONG).show();
            // CHANGED: open Home (in its "waiting for internet" mode) instead of Sign In
            goToHome();
        } catch (Exception e) {
            Log.e("SignUpActivity", "Could not queue registration", e);
            Toast.makeText(this, "Could not save your details. Please try again.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private boolean isFormValid(String fullName, String idNumber, String registrationNo,
                                String emailAddress, String password, String confirmPassword) {

        if (fullName.isEmpty()) {
            editxtTxtFullName.setError("Full name is required");
            editxtTxtFullName.requestFocus();
            return false;
        }
        if (idNumber.isEmpty()) {
            editxtTxtIDNumber.setError("ID number is required");
            editxtTxtIDNumber.requestFocus();
            return false;
        }
        if (registrationNo.isEmpty()) {
            editxtTxtRegisterationNo.setError("Registration number is required");
            editxtTxtRegisterationNo.requestFocus();
            return false;
        }
        // Letters and numbers only (we also allow / and - as in SC211/0045/2023)
        if (!registrationNo.matches("[A-Za-z0-9/\\-]+")) {
            editxtTxtRegisterationNo.setError("Use letters and numbers only");
            editxtTxtRegisterationNo.requestFocus();
            return false;
        }
        if (radioGroupGender.getCheckedRadioButtonId() == -1) {
            Toast.makeText(this, "Please select a gender", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (spinnerDepartment.getSelectedItemPosition() == 0) {
            Toast.makeText(this, "Please select a department", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (spinnerCourse.getSelectedItemPosition() == 0) {
            Toast.makeText(this, "Please select a course", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (emailAddress.isEmpty()) {
            editxtTxtEmailAddress.setError("Email address is required");
            editxtTxtEmailAddress.requestFocus();
            return false;
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(emailAddress).matches()) {
            editxtTxtEmailAddress.setError("Enter a valid email address");
            editxtTxtEmailAddress.requestFocus();
            return false;
        }
        if (password.isEmpty()) {
            editxtTxtPassword.setError("Password is required");
            editxtTxtPassword.requestFocus();
            return false;
        }

        if (password.length() < 6) {
            editxtTxtPassword.setError("Password must be at least 6 characters");
            editxtTxtPassword.requestFocus();
            return false;
        }
        if (confirmPassword.isEmpty()) {
            editxtTxtConfirmPassword.setError("Please confirm your password");
            editxtTxtConfirmPassword.requestFocus();
            return false;
        }

        if (!password.equals(confirmPassword)) {
            editxtTxtConfirmPassword.setError("Passwords do not match");
            editxtTxtConfirmPassword.requestFocus();
            return false;
        }
        if (!checkBoxTC.isChecked()) {
            Toast.makeText(this, "Please accept the Terms and Conditions", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }


    private void createAccount(String fullName, String idNumber, String registrationNo,
                               String emailAddress, String password,
                               String gender, String department, String course) {

        btnSignUp.setEnabled(false); // stop double taps while we wait

        // RETRY AFTER A TIMEOUT
        FirebaseUser existing = mAuth.getCurrentUser();
        if (existing != null) {
            if (emailAddress.equalsIgnoreCase(existing.getEmail())) {
                saveProfile(existing, fullName, idNumber, registrationNo,
                        emailAddress, gender, department, course);
                return;
            }
            // A different account is signed in: sign it out so we never attach this
            // profile to the wrong person.
            // CHANGED: through the ONE complete sign-out (it also ends an offline session, drops a
            // waiting registration and forgets the old student's cached rows), and the new account
            // is created only AFTER that has finished, never while the old one is half signed out.
            SessionUser.signOut(getApplicationContext(), existing.getUid(),
                    () -> createFirebaseUser(fullName, idNumber, registrationNo, emailAddress,
                            password, gender, department, course));
            return;
        }

        createFirebaseUser(fullName, idNumber, registrationNo, emailAddress, password,
                gender, department, course);
    }

    /** NEW: the part of createAccount that talks to Firebase (split out so it can run after sign-out). */
    private void createFirebaseUser(String fullName, String idNumber, String registrationNo,
                                    String emailAddress, String password,
                                    String gender, String department, String course) {

        mAuth.createUserWithEmailAndPassword(emailAddress, password)
                .addOnCompleteListener(new OnCompleteListener<AuthResult>() {
                    @Override
                    public void onComplete(@NonNull Task<AuthResult> task) {
                        if (task.isSuccessful()) {
                            FirebaseUser user = mAuth.getCurrentUser();
                            if (user != null) {
                                saveProfile(user, fullName, idNumber, registrationNo,
                                        emailAddress, gender, department, course);
                            }
                        } else {
                            btnSignUp.setEnabled(true);
                            showAuthError(task.getException());
                        }
                    }
                });
    }


    private void saveProfile(FirebaseUser user, String fullName, String idNumber,
                             String registrationNo, String emailAddress,
                             String gender, String department, String course) {


        Map<String, Object> profile = new HashMap<>();
        profile.put("fullName", fullName);
        profile.put("idNumber", idNumber);
        profile.put("registrationNo", registrationNo);
        profile.put("email", emailAddress);
        profile.put("gender", gender);
        profile.put("department", department);
        profile.put("course", course);
        profile.put("createdAt", FieldValue.serverTimestamp());

        final int attempt = ++saveAttempt;
        startSaveTimeout(attempt);

        // Document id = the user's Firebase uid, so each login maps to exactly one profile
        db.collection("users").document(user.getUid())
                .set(profile)
                .addOnCompleteListener(new OnCompleteListener<Void>() {
                    @Override
                    public void onComplete(@NonNull Task<Void> task) {
                        if (attempt != saveAttempt) {
                            return;
                        }
                        cancelSaveTimeout();

                        if (task.isSuccessful()) {
                            if (isFinishing() || isDestroyed()) {
                                return;
                            }
                            Toast.makeText(SignUpActivity.this,
                                    "Account created successfully. Welcome!",
                                    Toast.LENGTH_LONG).show();
                            // CHANGED: Firebase has already signed the new user in, so we keep
                            // that session and open Home (it used to sign out and ask them to
                            // log in again).
                            // NEW (account isolation): this student is now the ONLY account on
                            // the phone, so cached rows of anyone else are removed
                            DbExecutor.run(() -> AppDatabase.get(getApplicationContext())
                                    .clearSyncedExcept(user.getUid()));
                            goToHome();
                        } else {
                            Log.e("SignUpActivity", "Profile save failed", task.getException());

                            // Roll back: delete the half-finished account so the user can retry
                            // (otherwise retrying would say "email already registered")
                            user.delete();
                            if (isFinishing() || isDestroyed()) {
                                return;
                            }
                            btnSignUp.setEnabled(true);
                            Toast.makeText(SignUpActivity.this,
                                    "Could not save your details. Please try again.",
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
    }

    private void startSaveTimeout(int attempt) {
        cancelSaveTimeout();
        saveTimeout = () -> {
            // Ignore if a newer attempt started, or the screen is already gone
            if (attempt != saveAttempt || isFinishing() || isDestroyed()) {
                return;
            }
            Log.w("SignUpActivity", "Profile save timed out after " + SAVE_TIMEOUT_MS + " ms");
            btnSignUp.setEnabled(true);
            Toast.makeText(SignUpActivity.this,
                    "Saving is taking too long. Check your connection, then tap Sign Up again.",
                    Toast.LENGTH_LONG).show();
        };
        timeoutHandler.postDelayed(saveTimeout, SAVE_TIMEOUT_MS);
    }

    /** Cancels the countdown, if one is running. */
    private void cancelSaveTimeout() {
        if (saveTimeout != null) {
            timeoutHandler.removeCallbacks(saveTimeout);
            saveTimeout = null;
        }
    }

    /** Stop the countdown when the screen closes so it can't touch a dead screen. */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelSaveTimeout();
    }

    private void showAuthError(Exception e) {
        String message;
        if (e instanceof FirebaseAuthUserCollisionException) {
            message = "This email is already registered. Try signing in.";
        } else if (e instanceof FirebaseNetworkException) {
            message = "Network error. Check your internet connection and try again.";
        } else {
            message = "Account creation failed. Please try again.";
        }
        Toast.makeText(SignUpActivity.this, message, Toast.LENGTH_LONG).show();
    }

    /**
     * NEW: opens Home and clears the back stack, so Back cannot return to the sign-up form.
     * Online: the student is signed in and sees the real Home.
     * Offline: there is no account yet, so Home shows a waiting state (see HomeActivity).
     */
    private void goToHome() {
        OnboardingPrefs.markCompleted(this);   // NEW: someone who registered has certainly seen the intro
        Intent intent = new Intent(SignUpActivity.this, HomeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void goToSignIn() {
        Intent intent = new Intent(SignUpActivity.this, SignInActivity.class);
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