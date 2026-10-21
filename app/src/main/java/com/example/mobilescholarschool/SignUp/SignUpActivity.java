package com.example.mobilescholarschool.SignUp;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
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

import com.example.mobilescholarschool.R;
import com.example.mobilescholarschool.SignIn.SignInActivity;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

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
    Button btnSignUp;

    CheckBox checkBoxTC;
    TextView txtViewLogin;

    FirebaseAuth mAuth;
    FirebaseFirestore db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_sign_up);
        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
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
        checkBoxTC = findViewById(R.id.checkBoxTC);
        btnSignUp = findViewById(R.id.btnSignUp);
        txtViewLogin = findViewById(R.id.txtViewLogin);

        txtViewLogin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goToSignIn();
            }
        });

        btnSignUp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                attemptSignUp();
            }
        });
    }

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

        if (!isOnline()) {
            Toast.makeText(this,
                    "No internet connection. Creating an account needs internet.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        String gender = radioGroupGender.getCheckedRadioButtonId() == R.id.radioMale
                ? "Male" : "Female";
        String department = spinnerDepartment.getSelectedItem().toString();

        createAccount(fullName, idNumber, registrationNo, emailAddress, password, gender, department);
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
                               String gender, String department) {

        btnSignUp.setEnabled(false); // stop double taps while we wait

        mAuth.createUserWithEmailAndPassword(emailAddress, password)
                .addOnCompleteListener(new OnCompleteListener<AuthResult>() {
                    @Override
                    public void onComplete(@NonNull Task<AuthResult> task) {
                        if (task.isSuccessful()) {
                            FirebaseUser user = mAuth.getCurrentUser();
                            if (user != null) {
                                saveProfile(user, fullName, idNumber, registrationNo,
                                        emailAddress, gender, department);
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
                             String gender, String department) {


        Map<String, Object> profile = new HashMap<>();
        profile.put("fullName", fullName);
        profile.put("idNumber", idNumber);
        profile.put("registrationNo", registrationNo);
        profile.put("email", emailAddress);
        profile.put("gender", gender);
        profile.put("department", department);
        profile.put("createdAt", System.currentTimeMillis());

        // Document id = the user's Firebase uid, so each login maps to exactly one profile
        db.collection("users").document(user.getUid())
                .set(profile)
                .addOnCompleteListener(new OnCompleteListener<Void>() {
                    @Override
                    public void onComplete(@NonNull Task<Void> task) {
                        if (task.isSuccessful()) {
                            Toast.makeText(SignUpActivity.this,
                                    "Account created successfully. Please sign in.",
                                    Toast.LENGTH_LONG).show();
                            // Firebase signs the new user in automatically; sign out so
                            // they log in themselves on the Sign In screen
                            mAuth.signOut();
                            goToSignIn();
                        } else {
                            // Roll back: delete the half-finished account so the user can retry
                            // (otherwise retrying would say "email already registered")
                            user.delete();
                            btnSignUp.setEnabled(true);
                            Toast.makeText(SignUpActivity.this,
                                    "Could not save your details. Please try again.",
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
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