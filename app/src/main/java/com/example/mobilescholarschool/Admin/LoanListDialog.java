package com.example.mobilescholarschool.Admin;

import android.os.Bundle;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Issued and returned books, and the overdue monitor. Overdue = due DAY has passed and the book is
 * not back. A fine of Ksh. 10 a day (AdminRepo.FINE_PER_DAY) is worked out on return, or written
 * onto all overdue loans at once with "Update fines" so students see it on their Home card.
 */
public class LoanListDialog extends BaseListDialog {

    private static final String ARG_FILTER = "filter";

    public static LoanListDialog newInstance(String filter) {
        LoanListDialog d = new LoanListDialog();
        Bundle b = new Bundle();
        b.putString(ARG_FILTER, filter);
        d.setArguments(b);
        return d;
    }

    private final List<DocumentSnapshot> loaded = new ArrayList<>();

    @Override protected String title() {
        return "overdue".equals(initialFilter()) ? "Overdue Books" : "Issued & Returned Books";
    }

    @Override protected String searchHint() { return "Search registration no. or book"; }

    @Override protected String[][] filters() {
        return new String[][]{{"", "All"}, {"issued-all", "Issued (out)"}, {"overdue", "Overdue"}, {"returned", "Returned"}};
    }

    @Override protected String initialFilter() { return requireArguments().getString(ARG_FILTER, ""); }

    @Override protected String addLabel() { return "Update fines"; }

    @Override protected void onAdd() {
        confirm("Update fines?", "Writes each overdue book's fine so far (Ksh. " + AdminRepo.FINE_PER_DAY
                + " per day late) onto the loan. Students see it on their Home card.", "Update",
                () -> AdminRepo.updateFines(loaded, afterAction("Fines updated.")));
    }

    @Override protected String emptyText() { return "No loans yet. Approve a book request to issue a book."; }

    @Override protected void load(Loader loader) {
        FirebaseFirestore.getInstance().collection("loans").limit(AdminRepo.MAX_DOCS).get()
                .addOnSuccessListener(snap -> {
                    loaded.clear();
                    loaded.addAll(snap.getDocuments());
                    List<DocumentSnapshot> docs = new ArrayList<>(loaded);
                    AdminRepo.sortNewest(docs, "issuedAt");
                    List<Row> rows = new ArrayList<>();
                    for (DocumentSnapshot d : docs) {
                        boolean returned = d.get("returnedAt") != null;
                        long due = AdminRepo.millis(d, "dueDate");
                        boolean overdue = !returned && AdminRepo.isOverdue(due);
                        String state = returned ? "returned" : overdue ? "overdue" : "issued";
                        long fine = Math.max(AdminRepo.num(d, "fineAmount"),
                                overdue ? AdminRepo.daysLate(due) * AdminRepo.FINE_PER_DAY : 0);
                        Row r = new Row();
                        r.id = d.getId();
                        r.data = d;
                        r.title = AdminRepo.str(d, "bookTitle");
                        r.subtitle = "Student " + AdminRepo.str(d, "studentIdNumber");
                        r.extra = "Issued " + AdminUi.date(AdminRepo.millis(d, "issuedAt")) + "  ·  Due " + AdminUi.date(due)
                                + (returned ? "  ·  Returned " + AdminUi.date(AdminRepo.millis(d, "returnedAt")) : "")
                                + (overdue ? "  ·  " + AdminRepo.daysLate(due) + " day(s) late" : "")
                                + (fine > 0 ? "  ·  Fine " + AdminUi.ksh(fine) : "");
                        r.status = AdminUi.cap(state);
                        r.statusColor = AdminUi.colorOfStatus(state);
                        r.tags.add(state);
                        if (!returned) r.tags.add("issued-all");
                        r.search = (AdminRepo.str(d, "studentIdNumber") + " " + r.title + " " + AdminRepo.str(d, "isbn")).toLowerCase(Locale.ROOT);
                        rows.add(r);
                    }
                    loader.done(rows);
                })
                .addOnFailureListener(loader::fail);
    }

    @Override protected void onRowClick(Row row) {
        DocumentSnapshot loan = (DocumentSnapshot) row.data;
        boolean returned = loan.get("returnedAt") != null;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (!returned) {
            labels.add("Mark as returned");
            actions.add(() -> confirm("Mark as returned?", "The copy goes back on the shelf. A late fine is added if the book is overdue.",
                    "Returned", () -> AdminRepo.markReturned(loan, afterAction("Book returned."))));
        }
        if (AdminRepo.num(loan, "fineAmount") > 0) {
            labels.add("Clear fine (" + AdminUi.ksh(AdminRepo.num(loan, "fineAmount")) + " paid)");
            actions.add(() -> confirm("Clear fine?", "Only do this once the student has paid.", "Clear",
                    () -> AdminRepo.clearFine(loan, afterAction("Fine cleared."))));
        }
        if (labels.isEmpty()) {
            info(row.title, row.subtitle + "\n" + row.extra);
            return;
        }
        choose(row.title, labels.toArray(new String[0]), actions.toArray(new Runnable[0]));
    }
}
