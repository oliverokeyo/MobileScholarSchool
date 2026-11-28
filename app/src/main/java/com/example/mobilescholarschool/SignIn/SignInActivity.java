package com.example.mobilescholarschool.SignIn;

import android.content.Context;
import android.content.Intent;
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
import com.example.mobilescholarschool.Components.ConnectivityHelper;              // NEW: replaces this screen's own isOnline()
import com.example.mobilescholarschool.Components.OnboardingPrefs;                // NEW
import com.example.mobilescholarschool.Components.PasswordToggle;
import com.example.mobilescholarschool.Components.SessionUser;
import com.example.mobilescholarschool.Components.PendingRegistrationStore;
import com.example.mobilescholarschool.Data.AppDatabase;                          // NEW
import com.example.mobilescholarschool.Data.DbExecutor;                           // NEW
import com.example.mobilescholarschool.Security.OfflineAuthManager;               // NEW
import com.example.mobilescholarschool.Security.OfflineSession;                   // NEW
import com.example.mobilescholarschool.Security.SignInErrorMapper;                // NEW
import com.example.mobilescholarschool.SignUp.SignUpActivity;
import com.example.mobilescholarschool.Sync.SyncScheduler;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;                                     // NEW

import org.json.JSONObject;

import java.util.Arrays;                                                          // NEW

/**
 * CHANGED (offline sign-in). The order of decisions when the student taps "Sign in":
 *
 *   1. A registration still waiting for internet?          -> explain (unchanged)
 *   2. Phone says it is offline?                           -> check the password against the
 *                                                             verifier saved on THIS phone
 *   3. Otherwise ask FIREBASE (the real authority):
 *        success                -> end any offline session, refresh the verifier, go Home
 *        network failure        -> fall back to step 2's local check
 *        account disabled/gone  -> delete the verifier, NO offline fallback
 *        wrong password         -> if our saved copy still matches, the password was changed on
 *                                  the server: delete the verifier. Otherwise just a typo.
 *
 * LESSON: only a NETWORK failure may fall back to the offline check. Wrong password, disabled
 * account and "too many requests" are the SERVER giving an answer, and an answer is never
 * overridden by a local guess.
 */
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
    @Override
    protected void onStart() {
        super.onStart();
        // hasSession() is true for a real account, a waiting offline sign-up, OR a valid offline
        // session (NEW), so none of them is asked to sign in again.
        if (SessionUser.hasSession(this)) {
            goToHome();
        }
    }

    private void attemptSignIn() {
        String emailAddress = editEmail.getText().toString().trim();
        String password = editPassword.getText().toString();   // never trimmed

        if (!isFormValid(emailAddress, password)) {
            return;
        }

        // This student registered offline and the account is not on the server yet.
        // Firebase would say "Incorrect email or password", which would be wrong and
        // alarming, so we explain what is really happening instead.
        if (isPendingRegistration(emailAddress, password)) {
            // Safety net: make sure the sync job is scheduled (KEEP prevents duplicates)
            SyncScheduler.enqueue(this);

            String message = ConnectivityHelper.isOnline(this)
                    ? "Finishing setting up your account. Please try again in a moment."
                    : "Your registration is saved on this device. Connect to the internet "
                    + "and your account will be created automatically.";
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            return;
        }

        // CHANGED: offline used to stop here with "Logging in needs internet".
        // Now the password is checked against the verifier saved on this phone.
        if (!ConnectivityHelper.isOnline(this)) {
            signInOffline(emailAddress, password);
            return;
        }

        login(emailAddress, password);
    }

    /**
     * True if the typed email AND password match the registration waiting in the
     * encrypted outbox. Matching the password too means a stranger who only knows the
     * email learns nothing about whether a registration is pending.
     */
    private boolean isPendingRegistration(String emailAddress, String password) {
        try {
            JSONObject pending = PendingRegistrationStore.load(this);
            return pending != null
                    // NEW: a leftover registration nobody is waiting for must not hijack a normal sign-in
                    && SessionUser.provisionalUid(this) != null
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

    // ================================================================ ONLINE: Firebase decides

    private void login(String emailAddress, String password) {

        btnSignIn.setEnabled(false); // stop double taps while we wait

        mAuth.signInWithEmailAndPassword(emailAddress, password)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        onOnlineSignInSucceeded(password, task.getResult().getUser());
                    } else {
                        onOnlineSignInFailed(emailAddress, password, task.getException());
                    }
                });
    }

    /**
     * Firebase accepted the password. NEW: a real session now exists, so any local-only session
     * ends, and the offline verifier is created or refreshed (this also resets its lock counters
     * and restarts its 14-day clock).
     */
    private void onOnlineSignInSucceeded(String password, FirebaseUser user) {
        OfflineSession.end();

        final Context app = getApplicationContext();
        final char[] chars = password.toCharArray();
        // The hashing is slow on purpose, so it runs on the disk thread. The Home screen does
        // not wait for it.
        DbExecutor.run(() -> {
            try {
                // NEW (account isolation): this student is now the ONLY account on the phone
                AppDatabase.get(app).clearSyncedExcept(user.getUid());
                OfflineAuthManager.enroll(app, user, chars);
            } finally {
                Arrays.fill(chars, '\0');   // overwrite our copy of the password
            }
        });
        goToHome();
    }

    private void onOnlineSignInFailed(String emailAddress, String password, Exception e) {
        SignInErrorMapper.Info info = SignInErrorMapper.map(e);

        if (info.allowsOfflineFallback()) {
            // The phone looked online but Firebase could not be reached: use the local check.
            signInOffline(emailAddress, password);
            return;
        }

        btnSignIn.setEnabled(true);
        final Context app = getApplicationContext();

        if (info.revokesOfflineAccess()) {
            // The school disabled or deleted the account: it must not work offline either
            DbExecutor.run(() -> OfflineAuthManager.forgetEmail(app, emailAddress));
        } else if (info.kind == SignInErrorMapper.Kind.WRONG_CREDENTIALS) {
            // A typo, or a password changed elsewhere? Delete our copy only if it still MATCHES
            // the rejected password (a typo will not match).
            final char[] chars = password.toCharArray();
            DbExecutor.run(() -> {
                try {
                    OfflineAuthManager.onServerRejectedCredentials(app, emailAddress, chars);
                } finally {
                    Arrays.fill(chars, '\0');
                }
            });
        }
        Toast.makeText(this, info.message, Toast.LENGTH_LONG).show();
    }

    // ================================================================ OFFLINE: the local check

    /**
     * NEW: checks the password against the salted verifier on this phone. The slow hashing runs
     * on the disk thread; the answer comes back on the main thread.
     */
    private void signInOffline(String emailAddress, String password) {
        btnSignIn.setEnabled(false);

        final Context app = getApplicationContext();
        final char[] chars = password.toCharArray();
        DbExecutor.async(() -> {
            try {
                return OfflineAuthManager.signInOffline(app, emailAddress, chars);
            } finally {
                Arrays.fill(chars, '\0');
            }
        }, result -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            btnSignIn.setEnabled(true);
            showOfflineResult(result);
        });
    }

    private void showOfflineResult(OfflineAuthManager.Result result) {
        if (result == null) {   // DbExecutor delivers null if the background work crashed
            toast("Offline sign-in is not available right now. Connect to the internet and "
                    + "sign in online.");
            return;
        }
        switch (result.status) {
            case OK:
                // A LOCAL session: no Firebase user, no token. Home shows saved data only.
                OfflineSession.start(result.uid, result.email);
                final String signedInUid = result.uid;
                DbExecutor.run(() -> AppDatabase.get(getApplicationContext())
                        .clearSyncedExcept(signedInUid));   // NEW: same isolation as an online sign-in
                toast("Signed in offline. You can view your saved data.");
                goToHome();
                break;
            case WRONG_CREDENTIALS:
                toast("You are offline, and the email or password is incorrect. "
                        + result.attemptsLeft + " attempt(s) left before a short lock.");
                break;
            case LOCKED:
                toast("Too many wrong attempts. Try again in "
                        + formatWait(result.retryAfterMillis) + ".");
                break;
            case NOT_ENROLLED:
                toast("You are offline, and this phone has no saved sign-in for this account. "
                        + "Connect to the internet and sign in online once.");
                break;
            case EXPIRED:
                toast("Offline sign-in has expired. Connect to the internet and sign in online.");
                break;
            case REQUIRES_ONLINE:
                toast("For your security, offline sign-in is switched off after too many wrong "
                        + "attempts. Connect to the internet and sign in online.");
                break;
            default:
                toast("Offline sign-in is not available on this phone right now. Connect to "
                        + "the internet and sign in online.");
                break;
        }
    }

    /** 45000 -> "45 seconds", 200000 -> "4 minutes" (always rounded UP). */
    private static String formatWait(long millis) {
        long seconds = Math.max(1, (millis + 999) / 1000);
        if (seconds < 60) {
            return seconds + (seconds == 1 ? " second" : " seconds");
        }
        long minutes = (seconds + 59) / 60;
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void goToHome() {
        // Every time a student gets in, deliver anything saved on this phone while they
        // were offline or signed out (KEEP inside the scheduler prevents duplicate jobs).
        // In an offline session the worker finds nobody signed in and simply waits.
        OnboardingPrefs.markCompleted(this);   // NEW: someone who signed in has certainly seen the intro
        SyncScheduler.enqueue(this);
        Intent intent = new Intent(SignInActivity.this, HomeActivity.class);
        startActivity(intent);
        finish();
    }

    private void goToSignUp() {
        Intent intent = new Intent(SignInActivity.this, SignUpActivity.class);
        startActivity(intent);
        finish();
    }
}