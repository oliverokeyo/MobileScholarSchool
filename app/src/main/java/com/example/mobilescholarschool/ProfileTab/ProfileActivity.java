package com.example.mobilescholarschool.ProfileTab;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.mobilescholarschool.Components.BottomNavHelper;
import com.example.mobilescholarschool.R;
import com.example.mobilescholarschool.SignIn.SignInActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.Map;

public class ProfileActivity extends AppCompatActivity {

    FirebaseAuth auth;
    FirebaseUser user;
    FirebaseFirestore db;
    ListenerRegistration profileListener;   // lets us stop listening when we sign out

    Button btnSignOut, btnEditDetails;
    TextView txtViewWelcome,
            txtViewProfileEmail,
            txtViewProfileRegNo,
            txtViewProfileIdNumber,
            txtViewProfileDepartment,
            txtViewProfileGender;

    // The latest saved values. They pre-fill the edit dialog and feed the screen.
    String currentFullName, currentIdNumber, currentRegNo, currentGender, currentDepartment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_profile);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        auth = FirebaseAuth.getInstance();
        user = auth.getCurrentUser();

        // Not signed in -> go to Sign In and STOP
        if (user == null) {
            goToSignIn();
            return;
        }

        // Signed in: highlight the Profile tab and make the other tabs navigate.
        // After the null check so a signed-out user is never shown the bar.
        BottomNavHelper.setup(this, R.id.nav_profile);

        db = FirebaseFirestore.getInstance();

        btnSignOut = findViewById(R.id.btnSignOut);
        btnEditDetails = findViewById(R.id.btnEditDetails);
        txtViewWelcome = findViewById(R.id.txtViewWelcome);
        txtViewProfileEmail = findViewById(R.id.txtViewProfileEmail);
        txtViewProfileRegNo = findViewById(R.id.txtViewProfileRegNo);
        txtViewProfileIdNumber = findViewById(R.id.txtViewProfileIdNumber);
        txtViewProfileDepartment = findViewById(R.id.txtViewProfileDepartment);
        txtViewProfileGender = findViewById(R.id.txtViewProfileGender);

        txtViewProfileEmail.setText(getString(R.string.profile_email, orDash(user.getEmail())));

        // Nothing to edit until the profile has loaded
        btnEditDetails.setEnabled(false);

        listenToProfile();

        btnEditDetails.setOnClickListener(v -> showEditDialog());
        btnSignOut.setOnClickListener(v -> signOut());
    }

    /**
     * Live connection to users/{uid}. The lambda runs once with the current data and again
     * on every change, which is why the screen updates by itself after an edit.
     */
    private void listenToProfile() {
        profileListener = db.collection("users").document(user.getUid())
                .addSnapshotListener(this, (doc, error) -> {
                    if (error != null) {
                        // NEW: log the real reason (e.g. PERMISSION_DENIED) for Logcat
                        Log.e("ProfileActivity", "Profile listener failed", error);
                        txtViewWelcome.setText(R.string.profile_load_failed);
                        Toast.makeText(ProfileActivity.this,
                                "Could not load your profile. Check your connection.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }

                    if (doc == null || !doc.exists()) {
                        txtViewWelcome.setText(R.string.profile_not_found);
                        return;
                    }

                    currentFullName = doc.getString("fullName");
                    currentIdNumber = doc.getString("idNumber");
                    currentRegNo = doc.getString("registrationNo");
                    currentGender = doc.getString("gender");
                    currentDepartment = doc.getString("department");

                    displayProfile();
                    btnEditDetails.setEnabled(true);   // safe to edit now
                });
    }

    /** One place that paints the profile; used after loading AND after saving an edit. */
    private void displayProfile() {
        txtViewWelcome.setText(getString(R.string.welcome_user, orDash(currentFullName)));
        txtViewProfileRegNo.setText(getString(R.string.profile_reg_no, orDash(currentRegNo)));
        txtViewProfileIdNumber.setText(getString(R.string.profile_id_number, orDash(currentIdNumber)));
        txtViewProfileDepartment.setText(getString(R.string.profile_department, orDash(currentDepartment)));
        txtViewProfileGender.setText(getString(R.string.profile_gender, orDash(currentGender)));
    }


    private void showEditDialog() {
        // Inflate = turn dialog_edit_details.xml into real View objects
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_edit_details, null);

        EditText dlgFullName = dialogView.findViewById(R.id.dlgFullName);
        EditText dlgIdNumber = dialogView.findViewById(R.id.dlgIdNumber);
        EditText dlgRegNo = dialogView.findViewById(R.id.dlgRegNo);
        RadioGroup dlgGenderGroup = dialogView.findViewById(R.id.dlgGenderGroup);
        Spinner dlgDepartment = dialogView.findViewById(R.id.dlgDepartment);

        // Pre-fill with what is saved now
        dlgFullName.setText(currentFullName);
        dlgIdNumber.setText(currentIdNumber);
        dlgRegNo.setText(currentRegNo);
        if ("Male".equals(currentGender)) {
            dlgGenderGroup.check(R.id.dlgMale);
        } else if ("Female".equals(currentGender)) {
            dlgGenderGroup.check(R.id.dlgFemale);
        }
        dlgDepartment.setSelection(indexOfDepartment(currentDepartment));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.title_edit_details)
                .setView(dialogView)
                .setNegativeButton(R.string.btn_cancel, null)
                // null here on purpose: we attach our own click listener below
                .setPositiveButton(R.string.btn_save, null)
                .create();
        dialog.show();

        // Must be attached AFTER show(). A listener passed to setPositiveButton would
        // close the dialog on every tap, even when the input is invalid.
        Button saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        saveButton.setOnClickListener(v -> {
            String fullName = dlgFullName.getText().toString().trim();
            String idNumber = dlgIdNumber.getText().toString().trim();
            String regNo = dlgRegNo.getText().toString().trim();

            if (!isEditFormValid(dlgFullName, dlgIdNumber, dlgRegNo,
                    dlgGenderGroup, dlgDepartment, fullName, idNumber, regNo)) {
                return;   // dialog stays open so the user can fix the problem
            }

            if (!isOnline()) {
                Toast.makeText(this,
                        "No internet connection. Saving changes needs internet.",
                        Toast.LENGTH_LONG).show();
                return;
            }

            String gender = dlgGenderGroup.getCheckedRadioButtonId() == R.id.dlgMale
                    ? "Male" : "Female";
            String department = dlgDepartment.getSelectedItem().toString();

            saveChanges(dialog, fullName, idNumber, regNo, gender, department);
        });
    }

    private boolean isEditFormValid(EditText dlgFullName, EditText dlgIdNumber, EditText dlgRegNo,
                                    RadioGroup dlgGenderGroup, Spinner dlgDepartment,
                                    String fullName, String idNumber, String regNo) {
        if (fullName.isEmpty()) {
            dlgFullName.setError("Full name is required");
            dlgFullName.requestFocus();
            return false;
        }
        if (idNumber.isEmpty()) {
            dlgIdNumber.setError("ID number is required");
            dlgIdNumber.requestFocus();
            return false;
        }
        if (regNo.isEmpty()) {
            dlgRegNo.setError("Registration number is required");
            dlgRegNo.requestFocus();
            return false;
        }
        if (!regNo.matches("[A-Za-z0-9/\\-]+")) {
            dlgRegNo.setError("Use letters and numbers only");
            dlgRegNo.requestFocus();
            return false;
        }
        if (dlgGenderGroup.getCheckedRadioButtonId() == -1) {
            Toast.makeText(this, "Please select a gender", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (dlgDepartment.getSelectedItemPosition() == 0) {
            Toast.makeText(this, "Please select a department", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private void saveChanges(AlertDialog dialog, String fullName, String idNumber,
                             String regNo, String gender, String department) {

        // The email is NOT here: it belongs to Firebase Auth, not to this profile document
        Map<String, Object> updates = new HashMap<>();
        updates.put("fullName", fullName);
        updates.put("idNumber", idNumber);
        updates.put("registrationNo", regNo);
        updates.put("gender", gender);
        updates.put("department", department);


        updates.put("updatedAt", FieldValue.serverTimestamp());

        // merge: change ONLY these fields and leave the rest (email, createdAt) untouched
        db.collection("users").document(user.getUid())
                .set(updates, SetOptions.merge())
                // Fires when the server CONFIRMS the write (can lag on a slow connection)
                .addOnSuccessListener(this, unused ->
                        Toast.makeText(ProfileActivity.this,
                                "Details updated", Toast.LENGTH_SHORT).show())
                // If the server rejects the write, Firestore also rolls the screen back
                .addOnFailureListener(this, e -> {
                    // NEW: the user sees a friendly message, YOU see the real reason in Logcat
                    Log.e("ProfileActivity", "Save failed", e);
                    Toast.makeText(ProfileActivity.this,
                            "Could not save changes. Your details were not updated.",
                            Toast.LENGTH_LONG).show();
                });

        // Closing right away is fine: the snapshot listener shows the new values instantly
        // from Firestore's local cache, before the server has even replied.
        dialog.dismiss();
    }

    /** Finds the saved department in the dropdown list; 0 ("Select Department") if not found. */
    private int indexOfDepartment(String department) {
        String[] departments = getResources().getStringArray(R.array.department_list);
        for (int i = 0; i < departments.length; i++) {
            if (departments[i].equals(department)) {
                return i;
            }
        }
        return 0;
    }


    private void signOut() {
        // Stop listening FIRST: once signed out, the rules would reject this read
        if (profileListener != null) {
            profileListener.remove();
        }
        auth.signOut();
        goToSignIn();
    }

    private void goToSignIn() {
        Intent intent = new Intent(ProfileActivity.this, SignInActivity.class);
        // Clear the back stack so Back can't return to a signed-out profile screen
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    /** Shows "-" instead of the word "null" when a field is missing. */
    private String orDash(String value) {
        return (value == null || value.isEmpty()) ? "-" : value;
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