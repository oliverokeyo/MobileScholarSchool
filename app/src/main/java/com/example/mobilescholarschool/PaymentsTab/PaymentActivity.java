package com.example.mobilescholarschool.PaymentsTab;

import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
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

public class PaymentActivity extends AppCompatActivity {

    private Button btnPayment;
    private EditText editStudentNo, editAmount, editPhone;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Payment screen must never be reachable while signed out.
        if (AuthGuard.redirectIfSignedOut(this)) {
            return;
        }

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_payment);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Bottom navigation: highlight the Payments tab and wire up the other tabs.
        BottomNavHelper.setup(this, R.id.nav_payments);

        btnPayment = findViewById(R.id.btnPayment);
        editStudentNo = findViewById(R.id.editTxtTxtStudentNo);
        editAmount = findViewById(R.id.editTxtNumberAmount);
        editPhone = findViewById(R.id.editTxtNumberPhoneNo);

        // Fill in the student number from the saved profile
        prefillStudentNumber();

        btnPayment.setOnClickListener(v -> {
            if (inputsAreComplete()) {
                // NOTE: this is still a placeholder. See the message below this code for
                // how a real M-PESA payment should be triggered.
                Toast.makeText(
                        PaymentActivity.this,
                        "Enter your M-PESA PIN",
                        Toast.LENGTH_LONG
                ).show();
            }
        });
    }

    /**
     * reads users/{uid} once and copies registrationNo into the student number box
     */
    private void prefillStudentNumber() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();

        FirebaseFirestore.getInstance()
                .collection("users")
                .document(user.getUid())
                .get()
                .addOnSuccessListener(this, doc -> {
                    String regNo = doc.getString("registrationNo");
                    // Don't overwrite anything the student already typed while we were loading
                    boolean boxIsEmpty = editStudentNo.getText().toString().trim().isEmpty();
                    if (regNo != null && boxIsEmpty) {
                        editStudentNo.setText(regNo);
                    }
                })
                // Failing quietly is fine here: the student can simply type the number
                .addOnFailureListener(this, e ->
                        Log.e("PaymentActivity", "Could not prefill student number", e));
    }

    private boolean inputsAreComplete() {
        String studentNo = removeSpaces(editStudentNo.getText().toString());
        String amount = removeSpaces(editAmount.getText().toString());
        String phone = removeSpaces(editPhone.getText().toString());

        boolean complete = true;


        // --- Phone: required, no letters, and must look like a Kenyan number ---
        if (phone.isEmpty()) {
            editPhone.setError("Enter your M-PESA phone number");
            editPhone.requestFocus();
            complete = false;
        } else if (containsLetters(phone)) {
            editPhone.setError("Phone number cannot contain letters");
            editPhone.requestFocus();
            complete = false;
        } else if (!isValidKenyanPhone(phone)) {
            // Catches numbers that are too short or too long before they reach M-PESA
            editPhone.setError("Use a format like 0712345678 or +254712345678");
            editPhone.requestFocus();
            complete = false;
        }

        // --- Amount: required, and must be a whole number of at least 1 ---
        if (amount.isEmpty()) {
            editAmount.setError("Enter the amount");
            editAmount.requestFocus();
            complete = false;
        } else if (!isAtLeastOne(amount)) {
            editAmount.setError("Enter a whole number of 1 or more");
            editAmount.requestFocus();
            complete = false;
        }

        // --- Student number: required ---
        if (studentNo.isEmpty()) {
            editStudentNo.setError("Enter your student number");
            editStudentNo.requestFocus();
            complete = false;
        }

        return complete;
    }

    /** Removes every space, tab and newline: "07xx xxx xxx " becomes "07xxxxxxx". */
    private String removeSpaces(String text) {
        return text.replaceAll("\\s+", "");
    }

    /** True if the text has at least one alphabetical character. */
    private boolean containsLetters(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetter(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }


    private boolean isValidKenyanPhone(String phone) {
        return phone.matches("^(?:\\+?254|0)[17]\\d{8}$");
    }

    /** True if the text is a whole number that is 1 or more. */
    private boolean isAtLeastOne(String text) {
        try {
            return Long.parseLong(text) >= 1;
        } catch (NumberFormatException e) {
            // Not a valid number, or too big to store
            return false;
        }
    }
}