package com.example.mobilescholarschool.Admin;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Builds the two reports as plain text and shows them with a Share button. Sharing as text means the
 * admin can send the report by email, WhatsApp or Drive, and paste it into a spreadsheet, with no
 * extra permissions. The registered / unregistered split uses the SAME rule as the student app:
 * registered = at least one successful payment.
 */
final class ReportHelper {

    private ReportHelper() {}

    static void show(Context context, String title, String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        int pad = AdminUi.dp(context, 20);
        tv.setPadding(pad, pad / 2, pad, 0);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(tv);
        new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setView(scroll)
                .setNegativeButton("Close", null)
                .setPositiveButton("Share", (d, w) -> {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_SUBJECT, title);
                    send.putExtra(Intent.EXTRA_TEXT, text);
                    context.startActivity(Intent.createChooser(send, "Share report"));
                })
                .show();
    }

    // ------------------------------------------------------------ registration report

    static void showRegistrationReport(FragmentActivity activity) {
        AdminUi.toast(activity, "Building report…");
        FirebaseFirestore.getInstance().collection("users").limit(AdminRepo.MAX_DOCS).get()
                .addOnSuccessListener(users -> PaymentIndex.load(new PaymentIndex.Callback() {
                    @Override public void onLoaded(PaymentIndex index) {
                        if (activity.isFinishing()) return;
                        show(activity, "Registration report", registrationText(users.getDocuments(), index));
                    }
                    @Override public void onError(Exception e) {
                        AdminUi.toast(activity, AdminRepo.message(e));
                    }
                }))
                .addOnFailureListener(e -> AdminUi.toast(activity, AdminRepo.message(e)));
    }

    private static String registrationText(List<DocumentSnapshot> users, PaymentIndex index) {
        List<DocumentSnapshot> sorted = new ArrayList<>(users);
        Collections.sort(sorted, new Comparator<DocumentSnapshot>() {
            @Override public int compare(DocumentSnapshot a, DocumentSnapshot b) {
                return AdminRepo.str(a, "registrationNo").compareToIgnoreCase(AdminRepo.str(b, "registrationNo"));
            }
        });
        StringBuilder reg = new StringBuilder(), unreg = new StringBuilder();
        int r = 0, u = 0;
        for (DocumentSnapshot d : sorted) {
            boolean registered = index.isRegistered(d.getId());
            String line = AdminRepo.str(d, "registrationNo") + " | " + AdminRepo.str(d, "fullName")
                    + " | " + AdminRepo.str(d, "department") + " | " + AdminRepo.str(d, "course");
            if (registered) {
                reg.append(++r).append(". ").append(line).append(" | paid ")
                        .append(AdminUi.ksh(index.paidBy(d.getId()))).append("\n");
            } else {
                unreg.append(++u).append(". ").append(line).append("\n");
            }
        }
        return "STUDENT REGISTRATION REPORT\nGenerated: " + AdminUi.dateTime(System.currentTimeMillis())
                + "\nStudents: " + users.size()
                + "\nRegistered (payment confirmed): " + r
                + "\nUnregistered (no payment made): " + u
                + "\n\n=== REGISTERED ===\n" + (r == 0 ? "None\n" : reg)
                + "\n=== UNREGISTERED ===\n" + (u == 0 ? "None\n" : unreg);
    }

    // ------------------------------------------------------------ payment report

    static void showPaymentReport(FragmentActivity activity) {
        AdminUi.toast(activity, "Building report…");
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.collection("payments").limit(AdminRepo.MAX_DOCS).get().addOnSuccessListener(payments ->
                db.collection("feeAccounts").limit(AdminRepo.MAX_DOCS).get().addOnSuccessListener(fees -> {
                    if (activity.isFinishing()) return;
                    show(activity, "Payment report", paymentText(payments.getDocuments(), fees.getDocuments()));
                }).addOnFailureListener(e -> AdminUi.toast(activity, AdminRepo.message(e))))
                .addOnFailureListener(e -> AdminUi.toast(activity, AdminRepo.message(e)));
    }

    private static String paymentText(List<DocumentSnapshot> payments, List<DocumentSnapshot> fees) {
        List<DocumentSnapshot> sorted = new ArrayList<>(payments);
        AdminRepo.sortNewest(sorted, "createdAt");
        int ok = 0, pending = 0, failed = 0, cancelled = 0, verified = 0;
        long collected = 0, billed = 0;
        java.util.Map<String, Long> paidByUid = new java.util.HashMap<>();
        StringBuilder lines = new StringBuilder();
        for (DocumentSnapshot d : sorted) {
            String s = AdminRepo.status(d);
            long amount = AdminRepo.num(d, "amount");
            if (s.equals("success")) {
                ok++;
                collected += amount;
                String uid = AdminRepo.str(d, "studentUid");
                Long before = paidByUid.get(uid);
                paidByUid.put(uid, (before == null ? 0 : before) + amount);
                boolean v = Boolean.TRUE.equals(d.getBoolean("verified"));
                if (v) verified++;
                String code = AdminRepo.str(d, "mpesaReceipt").isEmpty()
                        ? AdminRepo.str(d, "checkoutRequestId") : AdminRepo.str(d, "mpesaReceipt");
                lines.append(AdminUi.date(AdminRepo.millis(d, "createdAt"))).append(" | ")
                        .append(AdminRepo.str(d, "studentIdNumber")).append(" | ").append(AdminUi.ksh(amount))
                        .append(" | ").append(code).append(v ? " | verified" : " | NOT verified").append("\n");
            } else if (s.equals("failed")) failed++;
            else if (s.equals("cancelled")) cancelled++;
            else pending++;
        }
        long outstanding = 0;
        for (DocumentSnapshot f : fees) {
            Long paid = paidByUid.get(f.getId());
            long owed = AdminRepo.num(f, "totalBilled") - (paid == null ? 0 : paid);
            billed += AdminRepo.num(f, "totalBilled");
            if (owed > 0) outstanding += owed;
        }
        return "PAYMENT REPORT\nGenerated: " + AdminUi.dateTime(System.currentTimeMillis())
                + "\n\nTotal collected: " + AdminUi.ksh(collected)
                + "\nSuccessful: " + ok + " (verified " + verified + ")"
                + "\nPending: " + pending + "\nFailed: " + failed + "\nCancelled: " + cancelled
                + "\n\nTotal billed (students with a fee record): " + AdminUi.ksh(billed)
                + "\nOutstanding fees: " + AdminUi.ksh(outstanding)
                + "\n\n=== SUCCESSFUL TRANSACTIONS ===\n" + (ok == 0 ? "None\n" : lines);
    }
}
