package com.example.mobilescholarschool.PaymentsTab;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;

import com.example.mobilescholarschool.Components.AuthGuard;
import com.example.mobilescholarschool.Components.BottomNavHelper;
import com.example.mobilescholarschool.Components.ConnectivityHelper;
import com.example.mobilescholarschool.Components.SessionUser;
import com.example.mobilescholarschool.Components.SwipeNavigator;
import com.example.mobilescholarschool.Data.DbExecutor;
import com.example.mobilescholarschool.Data.ProfileRepository;
import com.example.mobilescholarschool.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public class PaymentActivity extends AppCompatActivity {

    // Drag-to-switch-tab with spring animation. Null until the screen is set up.
    private SwipeNavigator swipeNavigator;

    private static final String TAG = "PaymentActivity";

    // Firestore location of the student's profile: users/{uid}
    private static final String COLLECTION_USERS = "users";
    private static final String FIELD_REGISTRATION_NO = "registrationNo";

    // Firestore location of payment attempts: payments/{paymentId}
    private static final String COLLECTION_PAYMENTS = "payments";

    // CHANGED: the status words now live in PaymentRecord (PaymentRecord.STATUS_PENDING and so on),
    // so the rules, this screen and the dialogs all share ONE definition of them.
    private static final String MODE_SANDBOX = "sandbox";

    // Placeholder stored until Daraja tells us the real CheckoutRequestID.
    private static final String CHECKOUT_AWAITING = "awaiting";

    // Daraja ResultCodes (the real callback and the Query endpoint use these same numbers).
    private static final int RESULT_SUCCESS = 0;
    private static final int RESULT_CANCELLED_BY_USER = 1032;
    private static final int RESULT_TIMEOUT = 1037;
    private static final int RESULT_COULD_NOT_START = -1;   // ours: the push never left the phone

    // How we ask Daraja "has the customer answered yet?"
    private static final long POLL_INTERVAL_MS = 5_000;     // asking too early just says "processing"
    private static final long POLL_TIMEOUT_MS = 90_000;     // the prompt on the phone expires too

    // Shown on the customer's M-PESA statement. Daraja allows 13 characters at most.
    private static final String TRANSACTION_DESC = "School fees";

    // Button text while waiting. Move into strings.xml when you next tidy up.
    private static final String LABEL_WAITING = "Waiting for M-PESA...";

    // NEW: the three looks of the registration chip (background, text).
    private static final int CHIP_NEUTRAL_BG = 0xFFECEFF1;        // checking: calm grey
    private static final int CHIP_NEUTRAL_TEXT = 0xFF455A64;
    private static final int CHIP_UNREGISTERED_BG = 0xFFFFF3E0;   // unregistered: warm, attention-getting
    private static final int CHIP_UNREGISTERED_TEXT = 0xFFE65100;
    private static final int CHIP_REGISTERED_BG = 0xFFE8F5E9;     // registered: green
    private static final int CHIP_REGISTERED_TEXT = 0xFF1B5E20;

    // Compiled once and reused, instead of being rebuilt on every button tap.
    // Accepts 07xxxxxxxx, 01xxxxxxxx, 2547xxxxxxxx and +2547xxxxxxxx.
    private static final Pattern KENYAN_PHONE =
            Pattern.compile("^(?:\\+?254|0)[17]\\d{8}$");

    private Button btnPayment;
    private EditText editStudentNo, editAmount, editPhone;

    // NEW: the grey/orange/green "Registration status" pill at the top.
    private TextView txtStatus;

    // NEW: the shared payment data (also used by the three dialogs).
    private PaymentsViewModel paymentsViewModel;

    // NEW: told whenever the payments change, so the chip updates itself the moment a payment
    // succeeds. (A field, not a lambda in onStart, so onStop can remove the SAME object.)
    private final PaymentsViewModel.Listener paymentsListener = this::refreshRegistrationStatus;

    // The button's normal text, remembered so we can restore it after a payment.
    private CharSequence payButtonLabel;

    // True from the tap until the payment reaches a final status. Stops double taps from
    // sending the student two prompts for one payment.
    private boolean paymentInFlight = false;

    // Our live "subscription" to one payment document. Must be removed when we are done,
    // or it keeps running (and billing reads) in the background.
    private ListenerRegistration paymentListener;

    // ONE background thread for all Daraja calls. Network calls on the main thread freeze
    // the screen (and Android throws NetworkOnMainThreadException), so they all go here.
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();

    // Set to false when the screen closes so the polling loop knows to stop.
    private volatile boolean screenAlive = true;

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
        setupRegistrationStatus();   // NEW
        setupDialogTriggers();       // NEW
        prefillStudentNumber();

        btnPayment.setOnClickListener(v -> onPayClicked());
    }

    // NEW: only listen while the screen is visible. Nothing to update when nobody can see it.
    @Override
    protected void onStart() {
        super.onStart();
        if (paymentsViewModel != null) {
            paymentsViewModel.addListener(paymentsListener);
            refreshRegistrationStatus();   // catch anything that changed while we were away
        }
    }

    @Override
    protected void onStop() {
        if (paymentsViewModel != null) {
            paymentsViewModel.removeListener(paymentsListener);
        }
        super.onStop();
    }

    // Clean up everything that could outlive the screen.
    @Override
    protected void onDestroy() {
        screenAlive = false;
        networkExecutor.shutdownNow();   // also wakes the polling loop if it is sleeping
        stopListeningForResult();
        super.onDestroy();
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
        txtStatus = findViewById(R.id.txtViewStatus);
        payButtonLabel = btnPayment.getText();
    }

    // ------------------------------------------------------------------
    // NEW: registration status chip
    // ------------------------------------------------------------------

    /** Gets (or re-uses, after a rotation) the shared data and starts it loading. */
    private void setupRegistrationStatus() {
        paymentsViewModel = new ViewModelProvider(this).get(PaymentsViewModel.class);

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) {
            paymentsViewModel.start(user.getUid());
        } else {
            paymentsViewModel.startWithoutAccount();   // a waiting sign-up has nothing on the server
        }
        refreshRegistrationStatus();
    }

    /**
     * Unregistered before a successful payment, Registered after. The chip has a third,
     * "Checking...", look so we never claim Unregistered just because data has not arrived yet.
     */
    private void refreshRegistrationStatus() {
        if (paymentsViewModel == null) {
            return;
        }
        if (!paymentsViewModel.isRegistrationKnown()) {
            applyStatusChip(R.string.registration_status_checking, CHIP_NEUTRAL_BG, CHIP_NEUTRAL_TEXT);
        } else if (paymentsViewModel.isRegistered()) {
            applyStatusChip(R.string.registration_status_registered,
                    CHIP_REGISTERED_BG, CHIP_REGISTERED_TEXT);
        } else {
            applyStatusChip(R.string.registration_status_unregistered,
                    CHIP_UNREGISTERED_BG, CHIP_UNREGISTERED_TEXT);
        }
    }

    private void applyStatusChip(@StringRes int labelRes, int backgroundColor, int textColor) {
        // Draw the pill in code so its colour can change. We keep the padding the layout gave it,
        // because swapping a background can reset padding.
        int left = txtStatus.getPaddingLeft();
        int top = txtStatus.getPaddingTop();
        int right = txtStatus.getPaddingRight();
        int bottom = txtStatus.getPaddingBottom();

        GradientDrawable pill = new GradientDrawable();
        pill.setColor(backgroundColor);
        pill.setCornerRadius(dp(100));   // far larger than the chip is tall: gives fully round ends
        txtStatus.setBackground(pill);
        txtStatus.setPadding(left, top, right, bottom);

        txtStatus.setTextColor(textColor);
        txtStatus.setText(getString(R.string.registration_status_format, getString(labelRes)));
    }

    private float dp(int value) {
        return value * getResources().getDisplayMetrics().density;
    }

    // ------------------------------------------------------------------
    // NEW: the cards and the bell open the dialogs
    // ------------------------------------------------------------------

    private void setupDialogTriggers() {
        // The card AND its arrow button both open it: a button inside a card swallows the tap
        // before the card sees it, so each needs its own listener.
        View.OnClickListener openHistory = v ->
                showDialogOnce(new TransactionHistoryDialogFragment(), TransactionHistoryDialogFragment.TAG);
        findViewById(R.id.cardTransactions).setOnClickListener(openHistory);
        findViewById(R.id.imgForward).setOnClickListener(openHistory);

        View.OnClickListener openRegistration = v ->
                showDialogOnce(new RegistrationDetailsDialogFragment(), RegistrationDetailsDialogFragment.TAG);
        findViewById(R.id.cardRegistration).setOnClickListener(openRegistration);
        findViewById(R.id.imgForwardPrint).setOnClickListener(openRegistration);

        findViewById(R.id.imgNotifications).setOnClickListener(v ->
                showDialogOnce(new NotificationsDialogFragment(), NotificationsDialogFragment.TAG));
    }

    /** Shows a dialog unless one with this tag is already up (a fast double tap would stack two). */
    private void showDialogOnce(DialogFragment dialog, String tag) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        // isStateSaved(): showing a fragment after onSaveInstanceState throws an exception.
        if (fragmentManager.isStateSaved() || fragmentManager.findFragmentByTag(tag) != null) {
            return;
        }
        dialog.show(fragmentManager, tag);
    }

    // ------------------------------------------------------------------
    // Student number
    // ------------------------------------------------------------------

    /**
     * Asks the LOCAL database first (instant, works offline), and only falls back to
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
        // Ignore taps while a payment is already waiting for its result.
        if (paymentInFlight) {
            return;
        }
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
        if (!MpesaConfig.isConfigured()) {
            Toast.makeText(this, "Add your Daraja sandbox keys to MpesaConfig.java first.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!inputsAreComplete()) {
            return;
        }
        startStkPush();
    }

    // ------------------------------------------------------------------
    // Daraja STK push (SANDBOX, called straight from the app)
    //
    //   1. write payments/{id} as 'pending'        (record the INTENT before touching money)
    //   2. background thread: token -> STK push    (the prompt appears on the phone)
    //   3. attach the CheckoutRequestID to the document
    //   4. background thread: ask Daraja every 5s  (our substitute for the callback)
    //   5. write the final result                  (the listener below shows it to the student)
    //
    // Production differs in ONE way: a server owns steps 2-5 and Safaricom's callback replaces
    // step 4. The document and the listener stay exactly the same.
    // ------------------------------------------------------------------

    /** Step 1: record the intent, then start the call to Daraja. */
    private void startStkPush() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Please sign in again.", Toast.LENGTH_LONG).show();
            return;
        }

        String studentNo = removeSpaces(editStudentNo.getText().toString());
        long amount = Long.parseLong(removeSpaces(editAmount.getText().toString())); // validated already
        String phone = toDarajaPhone(removeSpaces(editPhone.getText().toString()));

        setPaymentInFlight(true);

        // document() with no argument asks Firestore for a fresh, unique id (no write yet).
        DocumentReference paymentRef = FirebaseFirestore.getInstance()
                .collection(COLLECTION_PAYMENTS)
                .document();

        Map<String, Object> payment = new HashMap<>();
        payment.put("studentUid", user.getUid());
        payment.put("studentIdNumber", studentNo);
        payment.put("phone", phone);
        payment.put("amount", amount);
        payment.put("status", PaymentRecord.STATUS_PENDING);
        payment.put("mode", MODE_SANDBOX);
        payment.put("checkoutRequestId", CHECKOUT_AWAITING);
        payment.put("createdAt", FieldValue.serverTimestamp());   // rules demand the SERVER's clock

        paymentRef.set(payment)
                .addOnSuccessListener(this, unused -> {
                    listenForResult(paymentRef);
                    callDaraja(paymentRef, phone, amount, studentNo);
                })
                .addOnFailureListener(this, e -> {
                    Log.e(TAG, "Could not create payment", e);
                    setPaymentInFlight(false);
                    Toast.makeText(this,
                            "Could not start the payment. Check that your student number matches your profile.",
                            Toast.LENGTH_LONG).show();
                });
    }

    /** Steps 2-4: everything that touches the network runs on the background thread. */
    private void callDaraja(DocumentReference paymentRef, String phone, long amount, String studentNo) {
        networkExecutor.execute(() -> {
            String checkoutRequestId;
            String token;
            try {
                token = DarajaClient.getAccessToken();
                DarajaClient.StkPushResult push =
                        DarajaClient.stkPush(token, phone, amount, studentNo, TRANSACTION_DESC);
                checkoutRequestId = push.checkoutRequestId;

                // Back on the main thread for anything that touches the screen or Firestore.
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    attachDarajaIds(paymentRef, push);
                    // CHANGED: the same words the bell shows (R.string.toast_check_phone).
                    Toast.makeText(this, R.string.toast_check_phone, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                // Covers: no network, bad keys, Daraja refusing the request, unreadable answer.
                Log.e(TAG, "STK push failed", e);
                String reason = e.getMessage() != null ? e.getMessage() : "Could not reach M-PESA.";
                runOnUiThread(() -> recordResult(paymentRef, PaymentRecord.STATUS_FAILED,
                        RESULT_COULD_NOT_START, reason));
                return;
            }

            pollForResult(paymentRef, token, checkoutRequestId);
        });
    }

    /** Step 3: remember which Daraja transaction this document belongs to. */
    private void attachDarajaIds(DocumentReference paymentRef, DarajaClient.StkPushResult push) {
        paymentRef.update(
                        "checkoutRequestId", push.checkoutRequestId,
                        "merchantRequestId", push.merchantRequestId)
                .addOnFailureListener(this, e ->
                        Log.e(TAG, "Could not save the Daraja ids", e));
    }

    /**
     * Step 4: runs on the BACKGROUND thread. Asks Daraja how it went until the customer has
     * answered or we run out of patience. This loop is what the callback would replace.
     */
    private void pollForResult(DocumentReference paymentRef, String token, String checkoutRequestId) {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;

        while (screenAlive && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                // The screen closed (shutdownNow). Stop quietly.
                Thread.currentThread().interrupt();
                return;
            }

            try {
                DarajaClient.StkQueryResult answer = DarajaClient.stkQuery(token, checkoutRequestId);
                if (!answer.stillProcessing) {
                    int code = answer.resultCode;
                    String desc = answer.resultDesc;
                    runOnUiThread(() -> recordResult(paymentRef, statusForCode(code), code, desc));
                    return;
                }
            } catch (Exception e) {
                // One failed check (a blip in the network) is not the end: try again next round.
                Log.w(TAG, "Status check failed, will retry", e);
            }
        }

        if (screenAlive) {
            runOnUiThread(() -> recordResult(paymentRef, PaymentRecord.STATUS_FAILED, RESULT_TIMEOUT,
                    "No response from the phone in time."));
        }
        // If the screen closed, the document stays 'pending'. A server callback fixes that
        // properly, because it keeps listening even when the app is closed.
    }

    /** Daraja's number to our status word. */
    private String statusForCode(int resultCode) {
        if (resultCode == RESULT_SUCCESS) {
            return PaymentRecord.STATUS_SUCCESS;
        }
        if (resultCode == RESULT_CANCELLED_BY_USER) {
            return PaymentRecord.STATUS_CANCELLED;
        }
        return PaymentRecord.STATUS_FAILED;   // 1 (insufficient balance), 1037 (timeout), anything else
    }

    /** Step 5: write the final result. The listener below reacts when the server accepts it. */
    private void recordResult(DocumentReference paymentRef, String status, int resultCode,
                              String resultDesc) {
        Map<String, Object> result = new HashMap<>();
        result.put("status", status);
        result.put("resultCode", resultCode);
        result.put("resultDesc", resultDesc);
        result.put("updatedAt", FieldValue.serverTimestamp());
        // No receipt here: the Query endpoint does not return one. A callback would.

        paymentRef.update(result)
                .addOnFailureListener(this, e -> {
                    Log.e(TAG, "Could not record the result", e);
                    stopListeningForResult();
                    setPaymentInFlight(false);
                    Toast.makeText(this,
                            "Could not save the result. Is settings/payments set to 'sandbox'?",
                            Toast.LENGTH_LONG).show();
                });
    }

    /**
     * Watches the payment document. Firestore calls us every time it changes.
     * The pending write we made earlier also triggers it, so we ignore 'pending'.
     */
    private void listenForResult(DocumentReference paymentRef) {
        stopListeningForResult();   // never keep two listeners running

        // Passing "this" ties the listener to the screen's lifecycle as a safety net.
        paymentListener = paymentRef.addSnapshotListener(this, (snapshot, error) -> {
            if (error != null) {
                Log.e(TAG, "Payment listener failed", error);
                return;
            }
            if (snapshot == null || !snapshot.exists()) {
                return;
            }
            // Firestore shows OUR OWN writes instantly, before the server has accepted them. If
            // the rules then reject the write, it is rolled back. So never announce a result
            // until the server has confirmed it.
            if (snapshot.getMetadata().hasPendingWrites()) {
                return;
            }
            if (PaymentRecord.STATUS_PENDING.equals(snapshot.getString("status"))) {
                return;
            }
            // A final status has arrived: stop listening, free the button, tell the student.
            stopListeningForResult();
            setPaymentInFlight(false);
            showResult(snapshot);
        });
    }

    /**
     * Tells the student how it went. CHANGED: the words now come from strings.xml, the same
     * strings the bell uses, so a result and its notification can never disagree.
     */
    private void showResult(DocumentSnapshot payment) {
        String status = payment.getString("status");
        String description = payment.getString("resultDesc");
        Long amount = payment.getLong("amount");

        String title;
        String message;
        if (PaymentRecord.STATUS_SUCCESS.equals(status)) {
            title = getString(R.string.payment_success_title);
            message = getString(R.string.payment_success_message,
                    PaymentFormat.money(amount == null ? 0 : amount))
                    + "\n" + getString(R.string.payment_sandbox_note);
            editAmount.setText("");   // ready for the next payment
        } else if (PaymentRecord.STATUS_CANCELLED.equals(status)) {
            title = getString(R.string.payment_cancelled_title);
            message = getString(R.string.payment_cancelled_message);
        } else {
            title = getString(R.string.payment_failed_title);
            message = description != null && !description.isEmpty()
                    ? description : getString(R.string.payment_failed_default);
        }

        if (isFinishing() || isDestroyed()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();

        // TODO (production): the SERVER, not the app, must update feeAccounts and the student's
        //       registrationStatus AFTER Safaricom's callback reports ResultCode 0.
    }

    private void stopListeningForResult() {
        if (paymentListener != null) {
            paymentListener.remove();
            paymentListener = null;
        }
    }

    /** Locks the button and changes its text while we wait; restores it afterwards. */
    private void setPaymentInFlight(boolean inFlight) {
        paymentInFlight = inFlight;
        btnPayment.setEnabled(!inFlight);
        btnPayment.setText(inFlight ? LABEL_WAITING : payButtonLabel);
    }

    /** Daraja wants 2547XXXXXXXX: no "+", no leading 0. Input is already validated. */
    private String toDarajaPhone(String phone) {
        String digits = phone.startsWith("+") ? phone.substring(1) : phone;
        if (digits.startsWith("0")) {
            digits = "254" + digits.substring(1);
        }
        return digits;
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