package com.example.mobilescholarschool.Admin;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

/**
 * LESSON: a student is "Registered" when at least one SUCCESSFUL payment exists (the same rule the
 * student's Payments tab uses). Reading every payment once and indexing it by student id lets
 * many admin screens answer "who paid, and how much?" without asking Firestore once per student.
 */
final class PaymentIndex {

    interface Callback {
        void onLoaded(PaymentIndex index);
        void onError(Exception e);
    }

    final Map<String, Long> paid = new HashMap<>();   // studentUid -> total of SUCCESSFUL payments
    int success, pending, failed, cancelled, unverified;
    long collected;

    static void load(Callback callback) {
        FirebaseFirestore.getInstance().collection("payments").limit(AdminRepo.MAX_DOCS).get()
                .addOnSuccessListener(snap -> {
                    PaymentIndex index = new PaymentIndex();
                    for (DocumentSnapshot d : snap.getDocuments()) {
                        index.add(d);
                    }
                    callback.onLoaded(index);
                })
                .addOnFailureListener(callback::onError);
    }

    private void add(DocumentSnapshot d) {
        String status = AdminRepo.status(d);
        long amount = AdminRepo.num(d, "amount");
        switch (status) {
            case "success":
                success++;
                collected += amount;
                String uid = AdminRepo.str(d, "studentUid");
                Long before = paid.get(uid);
                paid.put(uid, (before == null ? 0 : before) + amount);
                if (!Boolean.TRUE.equals(d.getBoolean("verified"))) unverified++;
                break;
            case "failed": failed++; break;
            case "cancelled": cancelled++; break;
            default: pending++;
        }
    }

    boolean isRegistered(String uid) {
        return paid.containsKey(uid);
    }

    long paidBy(String uid) {
        Long v = paid.get(uid);
        return v == null ? 0 : v;
    }
}
