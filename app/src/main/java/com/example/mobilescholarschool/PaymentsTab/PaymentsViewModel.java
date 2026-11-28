package com.example.mobilescholarschool.PaymentsTab;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * The ONE place the payments screen's data lives. The status chip, the history dialog, the
 * registration dialog and the bell all read from here.
 *
 * Two reasons it is a ViewModel:
 *  - It SURVIVES screen rotation. A rotated activity is destroyed and rebuilt, but its
 *    ViewModel is kept, so we do not reload everything or lose the live listener.
 *  - Dialogs can reach the SAME instance through the activity (ViewModelProvider(activity)),
 *    so they see exactly what the screen sees, with no data passed around by hand.
 *
 * A ViewModel must NEVER hold an Activity, View or Context: it outlives them and would leak them.
 */
public class PaymentsViewModel extends ViewModel {

    private static final String TAG = "PaymentsViewModel";

    /** Called on the main thread whenever anything here changes. */
    public interface Listener {
        void onPaymentsChanged();
    }

    private final List<PaymentRecord> payments = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();

    private ListenerRegistration paymentsRegistration;
    private boolean started = false;
    // NEW (account isolation): WHICH student this data belongs to (null = a waiting sign-up).
    private String activeUid;
    private boolean paymentsLoaded = false;

    // From users/{uid}
    private boolean profileSaysRegistered = false;
    private String fullName;
    private String registrationNo;

    // ------------------------------------------------------------------
    // Starting
    // ------------------------------------------------------------------

    /**
     * Safe to call more than once (a rotation calls it again) for the SAME student: only the first
     * call does work.
     *
     * CHANGED (account isolation): it used to ignore every later call, whoever was asking. If this
     * object were ever reused after another student signed in, it would have kept showing the
     * FIRST student's payments. Now a different uid throws the old data away and starts fresh.
     */
    public void start(@NonNull String uid) {
        if (started && uid.equals(activeUid)) {
            return;
        }
        if (started) {
            reset();   // a different account: forget everything about the previous one
        }
        started = true;
        activeUid = uid;

        FirebaseFirestore db = FirebaseFirestore.getInstance();

        // Live: fires now, and again whenever any of this student's payments change.
        // We ask only for the student's OWN payments (the rules demand that filter).
        // We sort in Java rather than in the query: sorting in the query would force you to
        // create a composite index in the console, and a student has few payments anyway.
        paymentsRegistration = db.collection("payments")
                .whereEqualTo("studentUid", uid)
                .addSnapshotListener((snapshot, error) -> {
                    if (!uid.equals(activeUid)) {
                        return;   // NEW: a late answer for a student who is no longer active
                    }
                    if (error != null) {
                        Log.e(TAG, "Payments listener failed", error);
                        return;
                    }
                    if (snapshot == null) {
                        return;
                    }

                    List<PaymentRecord> fresh = new ArrayList<>();
                    for (DocumentSnapshot doc : snapshot.getDocuments()) {
                        fresh.add(PaymentRecord.from(doc));
                    }
                    Collections.sort(fresh, (a, b) -> b.createdAt.compareTo(a.createdAt)); // newest first

                    payments.clear();
                    payments.addAll(fresh);

                    // Offline with nothing saved, Firestore answers "empty, from the cache". That
                    // does NOT mean the student has no payments, so we do not claim to know yet.
                    paymentsLoaded = !(snapshot.getMetadata().isFromCache() && snapshot.isEmpty());
                    notifyChanged();
                });

        // One read: name, registration number, and the profile's own registration flag.
        db.collection("users").document(uid).get()
                .addOnSuccessListener(doc -> {
                    if (!uid.equals(activeUid)) {
                        return;   // NEW: same protection for the one-off profile read
                    }
                    fullName = doc.getString("fullName");
                    registrationNo = doc.getString("registrationNo");
                    profileSaysRegistered = "registered".equalsIgnoreCase(doc.getString("registrationStatus"));
                    notifyChanged();
                })
                .addOnFailureListener(e -> Log.e(TAG, "Could not load the profile", e));
    }

    /** For a provisional (not yet created) account: there is no server data, so nothing to wait for. */
    public void startWithoutAccount() {
        if (started && activeUid == null) {
            return;
        }
        if (started) {
            reset();   // NEW: this used to belong to a real account: do not keep its data
        }
        started = true;
        activeUid = null;
        paymentsLoaded = true;
        notifyChanged();
    }

    /** NEW: forgets one student completely (listener, payments, name, flags). */
    private void reset() {
        if (paymentsRegistration != null) {
            paymentsRegistration.remove();
            paymentsRegistration = null;
        }
        payments.clear();
        paymentsLoaded = false;
        profileSaysRegistered = false;
        fullName = null;
        registrationNo = null;
        started = false;
        activeUid = null;
    }

    @Override
    protected void onCleared() {
        // The screen is gone for good: stop the live listener (it bills reads while it runs).
        if (paymentsRegistration != null) {
            paymentsRegistration.remove();
            paymentsRegistration = null;
        }
        listeners.clear();
    }

    // ------------------------------------------------------------------
    // Listeners
    // ------------------------------------------------------------------

    public void addListener(@NonNull Listener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(@NonNull Listener listener) {
        listeners.remove(listener);
    }

    private void notifyChanged() {
        // Iterate a COPY: a listener may remove itself while we are looping.
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.onPaymentsChanged();
        }
    }

    // ------------------------------------------------------------------
    // What the screens read
    // ------------------------------------------------------------------

    /** All of the student's payments, newest first. */
    public List<PaymentRecord> getPayments() {
        return payments;
    }

    /** False until we have real data (so screens can say "Loading" instead of "No payments"). */
    public boolean isLoaded() {
        return paymentsLoaded;
    }

    /**
     * Registered = the profile says so (set by the server side one day) OR at least one payment
     * has been CONFIRMED successful by the server.
     */
    public boolean isRegistered() {
        return profileSaysRegistered || getSuccessCount() > 0;
    }

    /** True once we can honestly say Registered or Unregistered (never guess from missing data). */
    public boolean isRegistrationKnown() {
        return isRegistered() || paymentsLoaded;
    }

    public int getSuccessCount() {
        int count = 0;
        for (PaymentRecord payment : payments) {
            if (payment.isSuccess()) {
                count++;
            }
        }
        return count;
    }

    /** Sum of the successful payments only. */
    public long getTotalPaid() {
        long total = 0;
        for (PaymentRecord payment : payments) {
            if (payment.isSuccess()) {
                total += payment.amount;
            }
        }
        return total;
    }

    /** The EARLIEST successful payment: the one that made the student registered. Or null. */
    public PaymentRecord getFirstSuccess() {
        PaymentRecord first = null;
        for (PaymentRecord payment : payments) {
            if (payment.isSuccess()
                    && (first == null || payment.eventDate().before(first.eventDate()))) {
                first = payment;
            }
        }
        return first;
    }

    /** When the student became registered, or null if we don't know. */
    public Date getRegisteredDate() {
        PaymentRecord first = getFirstSuccess();
        return first == null ? null : first.eventDate();
    }

    public String getFullName() {
        return fullName;
    }

    public String getRegistrationNo() {
        return registrationNo;
    }
}