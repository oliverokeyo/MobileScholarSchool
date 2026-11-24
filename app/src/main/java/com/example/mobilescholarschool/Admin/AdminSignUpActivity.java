package com.example.mobilescholarschool.Admin;

import android.content.Intent;
import android.os.Bundle;
import android.util.Patterns;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.mobilescholarschool.R;
import com.example.mobilescholarschool.SignIn.SignInActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

/**
 * Creates an administrator account.
 *
 * LESSON - ORDER MATTERS: (1) create the sign-in account, (2) write admins/{uid}. Step 2 is the one
 * the Firestore rules guard: it only succeeds when the invite code exists in "adminInvites". If it
 * is refused, we DELETE the account made in step 1 so no half-made user is left behind.
 */
public class AdminSignUpActivity extends AppCompatActivity {

    private EditText editName, editEmail, editPassword, editConfirm, editInvite;
    private TextView txtError;
    private View btnSignUp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.admin_activity_signup);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        editName = findViewById(R.id.editAdminName);
        editEmail = findViewById(R.id.editAdminEmail);
        editPassword = findViewById(R.id.editAdminPassword);
        editConfirm = findViewById(R.id.editAdminConfirm);
        editInvite = findViewById(R.id.editAdminInvite);
        txtError = findViewById(R.id.txtAdminError);
        btnSignUp = findViewById(R.id.btnAdminSignUp);

        btnSignUp.setOnClickListener(v -> submit());
        findViewById(R.id.txtAdminBackToSignIn).setOnClickListener(v -> {
            startActivity(new Intent(this, SignInActivity.class));
            finish();
        });
    }

    private void submit() {
        String name = editName.getText().toString().trim();
        String email = editEmail.getText().toString().trim();
        String password = editPassword.getText().toString();
        String confirm = editConfirm.getText().toString();
        String invite = editInvite.getText().toString().trim();

        if (name.isEmpty()) { showError("Enter your full name."); return; }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) { showError("Enter a valid email address."); return; }
        if (password.length() < 6) { showError("The password needs at least 6 characters."); return; }
        if (!password.equals(confirm)) { showError("The passwords do not match."); return; }
        if (!invite.matches("[A-Za-z0-9_-]{4,40}")) { showError("Enter the invite code you were given."); return; }

        setBusy(true);
        FirebaseAuth auth = FirebaseAuth.getInstance();
        auth.createUserWithEmailAndPassword(email, password)
                .addOnSuccessListener(result -> {
                    FirebaseUser user = result.getUser();
                    if (user == null) { setBusy(false); showError("Could not create the account."); return; }
                    Map<String, Object> admin = new HashMap<>();
                    admin.put("fullName", name);
                    admin.put("email", email);
                    admin.put("inviteCode", invite);
                    admin.put("createdAt", FieldValue.serverTimestamp());   // rules demand the SERVER clock
                    FirebaseFirestore.getInstance().collection("admins").document(user.getUid()).set(admin)
                            .addOnSuccessListener(x -> AdminSession.openAdmin(this))
                            .addOnFailureListener(e -> {
                                // Wrong code (or rules not published): undo step 1.
                                user.delete();
                                auth.signOut();
                                setBusy(false);
                                showError("That invite code was not accepted. " + AdminRepo.message(e));
                            });
                })
                .addOnFailureListener(e -> {
                    setBusy(false);
                    showError(e.getMessage() == null ? "Could not create the account." : e.getMessage());
                });
    }

    private void setBusy(boolean busy) {
        btnSignUp.setEnabled(!busy);
        if (busy) txtError.setVisibility(View.GONE);
    }

    private void showError(String message) {
        txtError.setText(message);
        txtError.setVisibility(View.VISIBLE);
    }
}
