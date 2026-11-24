package com.example.mobilescholarschool.Admin;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * All student M-PESA transactions: search by registration number, see the M-PESA receipt / checkout
 * codes, spot pending and failed payments, and VERIFY successful ones.
 */
public class TransactionListDialog extends BaseListDialog {

    private static final String ARG_FILTER = "filter";

    public static TransactionListDialog newInstance(String filter) {
        TransactionListDialog d = new TransactionListDialog();
        Bundle b = new Bundle();
        b.putString(ARG_FILTER, filter);
        d.setArguments(b);
        return d;
    }

    @Override protected String title() { return "Transactions"; }

    @Override protected String searchHint() { return "Search by registration number"; }

    @Override protected String[][] filters() {
        return new String[][]{{"", "All"}, {"success", "Successful"}, {"unverified", "Not verified"},
                {"pending", "Pending"}, {"failed", "Failed"}, {"cancelled", "Cancelled"}};
    }

    @Override protected String initialFilter() { return requireArguments().getString(ARG_FILTER, ""); }

    @Override protected String addLabel() { return "Report"; }

    @Override protected void onAdd() { ReportHelper.showPaymentReport(requireActivity()); }

    @Override protected String emptyText() { return "No transactions yet."; }

    static String codeOf(DocumentSnapshot d) {
        String receipt = AdminRepo.str(d, "mpesaReceipt");
        return !receipt.isEmpty() ? receipt : AdminRepo.str(d, "checkoutRequestId");
    }

    @Override protected void load(Loader loader) {
        FirebaseFirestore.getInstance().collection("payments").limit(AdminRepo.MAX_DOCS).get()
                .addOnSuccessListener(snap -> {
                    List<DocumentSnapshot> docs = new ArrayList<>(snap.getDocuments());
                    AdminRepo.sortNewest(docs, "createdAt");
                    List<Row> rows = new ArrayList<>();
                    for (DocumentSnapshot d : docs) {
                        String status = AdminRepo.status(d);
                        boolean verified = Boolean.TRUE.equals(d.getBoolean("verified"));
                        Row r = new Row();
                        r.id = d.getId();
                        r.data = d;
                        r.title = AdminRepo.str(d, "studentIdNumber") + "  ·  " + AdminUi.ksh(AdminRepo.num(d, "amount"));
                        r.subtitle = "Code: " + codeOf(d) + (verified ? "  ·  Verified ✓" : "");
                        r.extra = AdminUi.dateTime(AdminRepo.millis(d, "createdAt")) + "  ·  " + AdminRepo.str(d, "phone")
                                + (AdminRepo.str(d, "resultDesc").isEmpty() ? "" : "  ·  " + AdminRepo.str(d, "resultDesc"));
                        r.status = status.equals("success") ? "Successful" : AdminUi.cap(status);
                        r.statusColor = AdminUi.colorOfStatus(status);
                        r.tags.add(status);
                        if (status.equals("success") && !verified) r.tags.add("unverified");
                        r.search = (AdminRepo.str(d, "studentIdNumber") + " " + codeOf(d)).toLowerCase(Locale.ROOT);
                        rows.add(r);
                    }
                    loader.done(rows);
                })
                .addOnFailureListener(loader::fail);
    }

    @Override protected void onRowClick(Row row) {
        DocumentSnapshot p = (DocumentSnapshot) row.data;
        boolean canVerify = AdminRepo.status(p).equals("success") && !Boolean.TRUE.equals(p.getBoolean("verified"));
        String details = "Student: " + AdminRepo.str(p, "studentIdNumber")
                + "\nAmount: " + AdminUi.ksh(AdminRepo.num(p, "amount"))
                + "\nStatus: " + row.status
                + "\nPhone: " + AdminRepo.str(p, "phone")
                + "\nM-PESA receipt: " + (AdminRepo.str(p, "mpesaReceipt").isEmpty() ? "(none returned)" : AdminRepo.str(p, "mpesaReceipt"))
                + "\nCheckoutRequestID: " + AdminRepo.str(p, "checkoutRequestId")
                + "\nMerchantRequestID: " + AdminRepo.str(p, "merchantRequestId")
                + "\nResult code: " + (p.getLong("resultCode") == null ? "-" : p.getLong("resultCode"))
                + "\nResult: " + AdminRepo.str(p, "resultDesc")
                + "\nMode: " + AdminRepo.str(p, "mode")
                + "\nStarted: " + AdminUi.dateTime(AdminRepo.millis(p, "createdAt"))
                + "\nUpdated: " + AdminUi.dateTime(AdminRepo.millis(p, "updatedAt"))
                + "\nVerified: " + (Boolean.TRUE.equals(p.getBoolean("verified"))
                        ? "Yes, " + AdminUi.dateTime(AdminRepo.millis(p, "verifiedAt")) : "No");
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Transaction")
                .setMessage(details)
                .setNeutralButton("Copy code", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("M-PESA code", codeOf(p)));
                    AdminUi.toast(getContext(), "Code copied.");
                })
                .setNegativeButton("Close", null);
        if (canVerify) {
            b.setPositiveButton("Verify payment", (d, w) -> AdminRepo.verifyPayment(p, afterAction("Payment verified.")));
        }
        b.show();
    }
}
