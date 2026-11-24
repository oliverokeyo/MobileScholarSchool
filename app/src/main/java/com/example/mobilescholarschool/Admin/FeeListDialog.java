package com.example.mobilescholarschool.Admin;

import android.text.InputType;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fee statements and outstanding balances. Balance = billed (feeAccounts) minus SUCCESSFUL payments,
 * worked out here and never stored, exactly as the student's Home card does it, so both always agree.
 * Setting a student's billed amount here is what makes the "Outstanding Fee" card appear for them.
 */
public class FeeListDialog extends BaseListDialog {

    private final List<String[]> withoutRecord = new ArrayList<>();   // {uid, regNo}

    @Override protected String title() { return "Outstanding Fees"; }

    @Override protected String searchHint() { return "Search registration no. or ID"; }

    @Override protected String[][] filters() {
        return new String[][]{{"", "All"}, {"owing", "Owing"}, {"cleared", "Cleared"}, {"norecord", "No fee record"}};
    }

    @Override protected String initialFilter() { return "owing"; }

    @Override protected String addLabel() { return "Bill all"; }

    @Override protected void onAdd() {
        if (withoutRecord.isEmpty()) { info("Bill all", "Every student already has a fee record."); return; }
        new FormBuilder(requireContext(), "Bill " + withoutRecord.size() + " student(s) without a record")
                .text("amount", "Total fees (Ksh)", "", InputType.TYPE_CLASS_NUMBER)
                .date("due", "Due date", System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000)
                .show("Bill", v -> {
                    long amount;
                    try { amount = Long.parseLong(v.get("amount")); } catch (NumberFormatException e) { return "Enter the amount."; }
                    if (amount < 0) return "The amount cannot be negative.";
                    AdminRepo.billMany(new ArrayList<>(withoutRecord), amount, Long.parseLong(v.get("due")),
                            afterAction("Fee records created."));
                    return null;
                });
    }

    @Override protected String emptyText() { return "No students yet."; }

    @Override protected void load(Loader loader) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.collection("users").limit(AdminRepo.MAX_DOCS).get().addOnSuccessListener(users ->
                db.collection("feeAccounts").limit(AdminRepo.MAX_DOCS).get().addOnSuccessListener(fees ->
                        PaymentIndex.load(new PaymentIndex.Callback() {
                            @Override public void onLoaded(PaymentIndex index) {
                                Map<String, DocumentSnapshot> feeByUid = new HashMap<>();
                                for (DocumentSnapshot f : fees.getDocuments()) feeByUid.put(f.getId(), f);
                                List<DocumentSnapshot> docs = new ArrayList<>(users.getDocuments());
                                java.util.Collections.sort(docs, (a, b) -> AdminRepo.str(a, "registrationNo")
                                        .compareToIgnoreCase(AdminRepo.str(b, "registrationNo")));
                                withoutRecord.clear();
                                List<Row> rows = new ArrayList<>();
                                for (DocumentSnapshot u : docs) {
                                    DocumentSnapshot fee = feeByUid.get(u.getId());
                                    long paid = index.paidBy(u.getId());
                                    Row r = new Row();
                                    r.id = u.getId();
                                    r.data = u;
                                    String reg = AdminRepo.str(u, "registrationNo");
                                    r.title = AdminRepo.str(u, "fullName");
                                    r.subtitle = reg + "  ·  ID " + AdminRepo.str(u, "idNumber");
                                    r.search = (reg + " " + AdminRepo.str(u, "idNumber") + " " + r.title).toLowerCase(Locale.ROOT);
                                    if (fee == null) {
                                        withoutRecord.add(new String[]{u.getId(), reg});
                                        r.extra = "No fee statement issued  ·  Paid " + AdminUi.ksh(paid);
                                        r.status = "No record";
                                        r.statusColor = AdminUi.GREY;
                                        r.tags.add("norecord");
                                    } else {
                                        long billed = AdminRepo.num(fee, "totalBilled");
                                        long balance = billed - paid;
                                        r.extra = "Billed " + AdminUi.ksh(billed) + "  ·  Paid " + AdminUi.ksh(paid)
                                                + "  ·  Due " + AdminUi.date(AdminRepo.millis(fee, "dueDate"));
                                        r.status = balance > 0 ? "Owes " + AdminUi.ksh(balance) : "Cleared";
                                        r.statusColor = balance > 0 ? AdminUi.ORANGE : AdminUi.GREEN;
                                        r.tags.add(balance > 0 ? "owing" : "cleared");
                                        r.data = new Object[]{u, fee};
                                    }
                                    rows.add(r);
                                }
                                loader.done(rows);
                            }
                            @Override public void onError(Exception e) { loader.fail(e); }
                        })).addOnFailureListener(loader::fail)).addOnFailureListener(loader::fail);
    }

    @Override protected void onRowClick(Row row) {
        DocumentSnapshot user = row.data instanceof Object[] ? (DocumentSnapshot) ((Object[]) row.data)[0] : (DocumentSnapshot) row.data;
        DocumentSnapshot fee = row.data instanceof Object[] ? (DocumentSnapshot) ((Object[]) row.data)[1] : null;
        new FormBuilder(requireContext(), fee == null ? "Issue fee statement" : "Edit fee statement")
                .note(row.title + " · " + AdminRepo.str(user, "registrationNo"))
                .text("amount", "Total fees billed (Ksh)", fee == null ? "" : String.valueOf(AdminRepo.num(fee, "totalBilled")), InputType.TYPE_CLASS_NUMBER)
                .date("due", "Due date", fee != null && AdminRepo.millis(fee, "dueDate") > 0 ? AdminRepo.millis(fee, "dueDate")
                        : System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000)
                .show("Save", v -> {
                    long amount;
                    try { amount = Long.parseLong(v.get("amount")); } catch (NumberFormatException e) { return "Enter the amount."; }
                    if (amount < 0) return "The amount cannot be negative.";
                    AdminRepo.saveFee(user.getId(), AdminRepo.str(user, "registrationNo"), amount,
                            Long.parseLong(v.get("due")), afterAction("Fee statement saved."));
                    return null;
                });
    }
}
