package com.example.mobilescholarschool.PaymentsTab;

import androidx.annotation.NonNull;

import com.google.firebase.firestore.DocumentSnapshot;

import java.util.Date;

/**
 * One payment, copied out of a Firestore document into a plain Java object.
 *
 * Why not use the DocumentSnapshot everywhere? A snapshot is tied to Firestore and returns
 * things by field NAME (typos compile fine). A record has real fields, so a typo is a compile
 * error, and the screens don't need to know Firestore exists.
 */
final class PaymentRecord {

    // These must match the values the rules and PaymentActivity write.
    static final String STATUS_PENDING = "pending";
    static final String STATUS_SUCCESS = "success";
    static final String STATUS_FAILED = "failed";
    static final String STATUS_CANCELLED = "cancelled";

    private static final String CHECKOUT_AWAITING = "awaiting";

    final String id;
    final long amount;
    final String status;              // already "safe": see from()
    final String checkoutRequestId;   // may be null or "awaiting"
    final String resultDesc;          // may be null
    final String mpesaReceipt;        // may be null (the Query endpoint gives none)
    final Date createdAt;             // never null
    final Date updatedAt;             // may be null until a result is recorded

    private PaymentRecord(String id, long amount, String status, String checkoutRequestId,
                          String resultDesc, String mpesaReceipt, Date createdAt, Date updatedAt) {
        this.id = id;
        this.amount = amount;
        this.status = status;
        this.checkoutRequestId = checkoutRequestId;
        this.resultDesc = resultDesc;
        this.mpesaReceipt = mpesaReceipt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    static PaymentRecord from(@NonNull DocumentSnapshot doc) {
        Long amount = doc.getLong("amount");
        String rawStatus = doc.getString("status");

        // Firestore shows OUR OWN writes before the server has accepted them, and the server
        // can still reject one (rolling it back). So a "success" that the server has not
        // confirmed yet is treated as still pending. Otherwise the student could briefly be
        // shown as Registered for a payment the rules then refuse.
        boolean confirmed = !doc.getMetadata().hasPendingWrites();
        String status = rawStatus == null ? STATUS_PENDING : rawStatus;
        if (STATUS_SUCCESS.equals(status) && !confirmed) {
            status = STATUS_PENDING;
        }

        // A server timestamp is empty until the server stamps it. ESTIMATE substitutes this
        // phone's clock for that moment, so we never get null for a payment made just now.
        Date created = doc.getDate("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE);
        Date updated = doc.getDate("updatedAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE);

        return new PaymentRecord(
                doc.getId(),
                amount == null ? 0 : amount,
                status,
                doc.getString("checkoutRequestId"),
                doc.getString("resultDesc"),
                doc.getString("mpesaReceipt"),
                created == null ? new Date() : created,
                updated);
    }

    boolean isSuccess() {
        return STATUS_SUCCESS.equals(status);
    }

    /** When the interesting thing happened: the result time if there is one, else the start. */
    Date eventDate() {
        return updatedAt != null ? updatedAt : createdAt;
    }

    /** The receipt if we have one, otherwise Daraja's request id, otherwise nothing. */
    String reference() {
        if (mpesaReceipt != null && !mpesaReceipt.isEmpty()) {
            return mpesaReceipt;
        }
        if (checkoutRequestId != null && !checkoutRequestId.isEmpty()
                && !CHECKOUT_AWAITING.equals(checkoutRequestId)) {
            return checkoutRequestId;
        }
        return null;
    }
}