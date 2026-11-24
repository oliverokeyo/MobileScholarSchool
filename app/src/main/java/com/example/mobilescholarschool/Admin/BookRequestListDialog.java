package com.example.mobilescholarschool.Admin;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Book requests from students. Approving ISSUES the book: a loan is created, stock goes down and
 * the student's "My requests" and "Borrowed books" update by themselves.
 */
public class BookRequestListDialog extends BaseListDialog {

    @Override protected String title() { return "Book Requests"; }

    @Override protected String searchHint() { return "Search registration no. or book"; }

    @Override protected String[][] filters() {
        return new String[][]{{"", "All"}, {"pending", "Pending"}, {"approved", "Approved"}, {"rejected", "Rejected"}};
    }

    @Override protected String initialFilter() { return "pending"; }

    @Override protected String emptyText() { return "No book requests."; }

    @Override protected void load(Loader loader) {
        FirebaseFirestore.getInstance().collection("bookRequests").limit(AdminRepo.MAX_DOCS).get()
                .addOnSuccessListener(snap -> {
                    List<DocumentSnapshot> docs = new ArrayList<>(snap.getDocuments());
                    AdminRepo.sortNewest(docs, "createdAt");
                    List<Row> rows = new ArrayList<>();
                    for (DocumentSnapshot d : docs) {
                        String status = AdminRepo.status(d);
                        String book = !AdminRepo.str(d, "bookTitle").isEmpty() ? AdminRepo.str(d, "bookTitle")
                                : !AdminRepo.str(d, "isbn").isEmpty() ? "ISBN " + AdminRepo.str(d, "isbn")
                                : "Author: " + AdminRepo.str(d, "author");
                        Row r = new Row();
                        r.id = d.getId();
                        r.data = d;
                        r.title = book;
                        r.subtitle = "Student " + AdminRepo.str(d, "studentIdNumber");
                        r.extra = "Borrow " + AdminUi.date(AdminRepo.millis(d, "borrowDate"))
                                + " → return " + AdminUi.date(AdminRepo.millis(d, "returnDate"))
                                + "  ·  " + AdminRepo.str(d, "author");
                        r.status = AdminUi.cap(status);
                        r.statusColor = AdminUi.colorOfStatus(status);
                        r.tags.add(status);
                        r.search = (AdminRepo.str(d, "studentIdNumber") + " " + book + " " + AdminRepo.str(d, "author")
                                + " " + AdminRepo.str(d, "isbn")).toLowerCase(Locale.ROOT);
                        rows.add(r);
                    }
                    loader.done(rows);
                })
                .addOnFailureListener(loader::fail);
    }

    @Override protected void onRowClick(Row row) {
        DocumentSnapshot req = (DocumentSnapshot) row.data;
        if (!AdminRepo.status(req).equals("pending")) {
            info(row.title, row.subtitle + "\n" + row.extra + "\nStatus: " + row.status
                    + (AdminRepo.millis(req, "decidedAt") > 0 ? "\nDecided: " + AdminUi.dateTime(AdminRepo.millis(req, "decidedAt")) : ""));
            return;
        }
        choose(row.title, new String[]{"Approve (issue the book)", "Reject"}, new Runnable[]{
                () -> confirm("Approve request?", "A loan is created and one copy leaves the shelf. The student is due to return it on "
                        + AdminUi.date(AdminRepo.millis(req, "returnDate")) + ".", "Approve",
                        () -> AdminRepo.approveRequest(req, afterAction("Approved. The book is now issued."))),
                () -> confirm("Reject request?", "The student will see it as rejected.", "Reject",
                        () -> AdminRepo.rejectRequest(req, afterAction("Request rejected.")))});
    }
}
