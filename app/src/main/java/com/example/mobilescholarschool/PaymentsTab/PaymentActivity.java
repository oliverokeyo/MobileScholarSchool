package com.example.mobilescholarschool.PaymentsTab;

import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.mobilescholarschool.Components.AuthGuard;
import com.example.mobilescholarschool.Components.BottomNavHelper;
import com.example.mobilescholarschool.Components.ConnectivityHelper;           // NEW
import com.example.mobilescholarschool.Components.SessionUser;                  // NEW
import com.example.mobilescholarschool.Components.SwipeNavigator;
import com.example.mobilescholarschool.Data.DbExecutor;                  // NEW
import com.example.mobilescholarschool.Data.ProfileRepository;           // NEW
import com.example.mobilescholarschool.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.regex.Pattern;

public class PaymentActivity extends AppCompatActivity {

    // Drag-to-switch-tab with spring animation. Null until the screen is set up.
    private SwipeNavigator swipeNavigator;

    private static final String TAG = "PaymentActivity";

    // Firestore location of the student's profile: users/{uid}
    private static final String COLLECTION_USERS = "users";
    private static final String FIELD_REGISTRATION_NO = "registrationNo";

    // Compiled once and reused, instead of being rebuilt on every button tap.
    // Accepts 07xxxxxxxx, 01xxxxxxxx, 2547xxxxxxxx and +2547xxxxxxxx.
    private static final Pattern KENYAN_PHONE =
            Pattern.compile("^(?:\\+?254|0)[17]\\d{8}$");

    private Button btnPayment;
    private EditText editStudentNo, editAmount, editPhone;

    /**
     * Every touch on this screen passes through here BEFORE any view sees it. We ask the swipe
     * navigator first: if the user is dragging sideways it takes over (returns true) and the
     * views underneath never see that gesture. Otherwise everything works exactly as before.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (swipeNavigator != null && swipeNavigator.onTouchEvent(ev)) {
            return true;
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Payment screen must never be reachable while signed out.
        if (AuthGuard.redirectIfSignedOut(this)) {
            return;
        }

        setupEdgeToEdge();

        // Bottom navigation: highlight the Payments tab and wire up the other tabs.
        BottomNavHelper.setup(this, R.id.nav_payments);
        // Must come AFTER BottomNavHelper.setup (it reads what setup leaves behind).
        swipeNavigator = new SwipeNavigator(this, R.id.nav_payments);

        bindViews();
        prefillStudentNumber();

        btnPayment.setOnClickListener(v -> onPayClicked());
    }

    // ------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------

    private void setupEdgeToEdge() {
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_payment);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    private void bindViews() {
        btnPayment = findViewById(R.id.btnPayment);
        editStudentNo = findViewById(R.id.editTxtTxtStudentNo);
        editAmount = findViewById(R.id.editTxtNumberAmount);
        editPhone = findViewById(R.id.editTxtNumberPhoneNo);
    }

    // ------------------------------------------------------------------
    // Student number
    // ------------------------------------------------------------------

    /**
     * CHANGED: asks the LOCAL database first (instant, works offline), and only falls back to
     * Firestore when this phone has no saved copy yet.
     *
     * NOTE: paying itself stays ONLINE-ONLY. An M-PESA prompt is a live conversation with the
     * phone network, so queuing it for later would charge the student at a surprise moment.
     */
    private void prefillStudentNumber() {
        String uid = SessionUser.uid(getApplicationContext());   // real uid OR provisional id
        if (uid == null) {
            return; // AuthGuard already handles signed-out users; this just avoids a crash
        }

        DbExecutor.async(() -> ProfileRepository.get(getApplicationContext(), uid), local -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (local != null && local.registrationNo != null && !local.registrationNo.trim().isEmpty()) {
                fillStudentNumber(local.registrationNo);
            } else {
                FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
                if (user != null) {
                    prefillFromServer(user);   // a waiting sign-up has no server profile to ask
                }
            }
        });
    }

    /** The original Firestore read, now only the fallback. */
    private void prefillFromServer(FirebaseUser user) {
        FirebaseFirestore.getInstance()
                .collection(COLLECTION_USERS)
                .document(user.getUid())
                .get()
                .addOnSuccessListener(this, doc -> {
                    String regNo = doc.getString(FIELD_REGISTRATION_NO);
                    if (regNo != null) {
                        fillStudentNumber(regNo);
                    }
                })
                // Failing quietly is fine here: the student can simply type the number
                .addOnFailureListener(this, e ->
                        Log.e(TAG, "Could not prefill student number", e));
    }

    private void fillStudentNumber(@NonNull String regNo) {
        // Don't overwrite anything the student already typed while we were loading
        boolean boxIsEmpty = editStudentNo.getText().toString().trim().isEmpty();
        if (boxIsEmpty) {
            editStudentNo.setText(regNo);
        }
    }

    // ------------------------------------------------------------------
    // Payment
    // ------------------------------------------------------------------

    private void onPayClicked() {
        // Paying is a live conversation with M-PESA, so unlike everything else it is NOT saved for
        // later. Say why, instead of failing in a confusing way.
        if (!SessionUser.isSignedIn()) {
            Toast.makeText(this, "Payments need your account. Please wait until it has been created.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!ConnectivityHelper.isOnline(this)) {
            Toast.makeText(this, "Payments need an internet connection.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!inputsAreComplete()) {
            return;
        }
        // TODO: placeholder. Replace with a real M-PESA STK push request.
        // TODO: registration must only be confirmed AFTER the payment succeeds.
        //       Do not mark the student as registered from this click handler.
        Toast.makeText(this, R.string.toast_enter_mpesa_pin, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    private boolean inputsAreComplete() {
        String studentNo = removeSpaces(editStudentNo.getText().toString());
        String amount = removeSpaces(editAmount.getText().toString());
        String phone = removeSpaces(editPhone.getText().toString());

        // Each failed check calls requestFocus(), and the LAST call wins. So we check
        // from the bottom field up: the cursor ends on the TOPMOST field with a problem.
        // The results go into variables first so that every field shows its own error
        // (using && directly would stop at the first failure and hide the rest).
        boolean phoneOk = check(editPhone, phoneError(phone));
        boolean amountOk = check(editAmount, amountError(amount));
        boolean studentNoOk = check(editStudentNo, studentNoError(studentNo));

        return phoneOk && amountOk && studentNoOk;
    }

    /**
     * If error is null the field is fine. Otherwise show the error on the field,
     * move focus to it, and return false.
     */
    private boolean check(EditText field, String error) {
        if (error == null) {
            return true;
        }
        field.setError(error);
        field.requestFocus();
        return false;
    }

    /** Phone: required, no letters, and must look like a Kenyan number. */
    private String phoneError(String phone) {
        if (phone.isEmpty()) {
            return getString(R.string.error_phone_required);
        }
        if (containsLetters(phone)) {
            return getString(R.string.error_phone_letters);
        }
        if (!KENYAN_PHONE.matcher(phone).matches()) {
            // Catches numbers that are too short or too long before they reach M-PESA
            return getString(R.string.error_phone_format);
        }
        return null;
    }

    /** Amount: required, and must be a whole number of at least 1. */
    private String amountError(String amount) {
        if (amount.isEmpty()) {
            return getString(R.string.error_amount_required);
        }
        if (!isAtLeastOne(amount)) {
            return getString(R.string.error_amount_invalid);
        }
        return null;
    }

    /** Student number: required. */
    private String studentNoError(String studentNo) {
        return studentNo.isEmpty() ? getString(R.string.error_student_no_required) : null;
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    /** Removes every space, tab and newline: "07xx xxx xxx " becomes "07xxxxxxxx". */
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