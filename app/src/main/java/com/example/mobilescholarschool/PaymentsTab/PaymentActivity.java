package com.example.mobilescholarschool.PaymentsTab;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.mobilescholarschool.Components.BottomNavHelper;
import com.example.mobilescholarschool.R;

public class PaymentActivity extends AppCompatActivity {

    private Button btnPayment;
    private EditText editStudentNo, editAmount, editPhone;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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

        btnPayment.setOnClickListener(v -> {
            if (inputsAreComplete()) {
                Toast.makeText(
                        PaymentActivity.this,
                        "Enter your M-PESA PIN",
                        Toast.LENGTH_LONG
                ).show();
            }
        });
    }

    private boolean inputsAreComplete() {
        String studentNo = removeSpaces(editStudentNo.getText().toString());
        String amount = removeSpaces(editAmount.getText().toString());
        String phone = removeSpaces(editPhone.getText().toString());

        boolean complete = true;

        // --- Phone: required, and must not contain any letters ---
        if (phone.isEmpty()) {
            editPhone.setError("Enter your M-PESA phone number");
            editPhone.requestFocus();
            complete = false;
        } else if (containsLetters(phone)) {
            editPhone.setError("Phone number cannot contain letters");
            editPhone.requestFocus();
            complete = false;
        }

        // --- Amount: required, and must be at least 1 ---
        if (amount.isEmpty()) {
            editAmount.setError("Enter the amount");
            editAmount.requestFocus();
            complete = false;
        } else if (!isAtLeastOne(amount)) {
            editAmount.setError("Amount must be at least 1");
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