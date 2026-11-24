package com.example.mobilescholarschool.Admin;

import android.text.InputType;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Add, edit and remove books, and manage how many copies the library owns (inventory). */
public class BookListDialog extends BaseListDialog {

    @Override protected String title() { return "Manage Books"; }

    @Override protected String searchHint() { return "Search title, author or ISBN"; }

    @Override protected String[][] filters() {
        return new String[][]{{"", "All"}, {"available", "In stock"}, {"full", "Out of stock"}};
    }

    @Override protected String addLabel() { return "+ Add book"; }

    @Override protected String emptyText() { return "No books yet. Tap + Add book."; }

    @Override protected void onAdd() { form(null); }

    @Override protected void load(Loader loader) {
        FirebaseFirestore.getInstance().collection("books").limit(AdminRepo.MAX_DOCS).get()
                .addOnSuccessListener(snap -> {
                    List<DocumentSnapshot> docs = new ArrayList<>(snap.getDocuments());
                    java.util.Collections.sort(docs, (a, b) ->
                            AdminRepo.str(a, "title").compareToIgnoreCase(AdminRepo.str(b, "title")));
                    List<Row> rows = new ArrayList<>();
                    for (DocumentSnapshot d : docs) {
                        long total = AdminRepo.num(d, "totalCopies"), avail = AdminRepo.num(d, "copiesAvailable");
                        Row r = new Row();
                        r.id = d.getId();
                        r.data = d;
                        r.title = AdminRepo.str(d, "title");
                        r.subtitle = AdminRepo.str(d, "author") + "  ·  ISBN " + AdminRepo.str(d, "isbn");
                        r.extra = "Copies: " + avail + " available" + (total > 0 ? " of " + total : "") + "  ·  "
                                + AdminRepo.str(d, "category") + "  ·  Shelf " + AdminRepo.str(d, "shelfLocation");
                        String state = avail > 0 ? "available" : "full";
                        r.status = avail > 0 ? "In stock" : "Out";
                        r.statusColor = AdminUi.colorOfStatus(state);
                        r.tags.add(state);
                        r.search = (r.title + " " + AdminRepo.str(d, "author") + " " + AdminRepo.str(d, "isbn")).toLowerCase(Locale.ROOT);
                        rows.add(r);
                    }
                    loader.done(rows);
                })
                .addOnFailureListener(loader::fail);
    }

    @Override protected void onRowClick(Row row) {
        DocumentSnapshot book = (DocumentSnapshot) row.data;
        choose(row.title, new String[]{"Edit book / manage copies", "Remove book"}, new Runnable[]{
                () -> form(book),
                () -> confirm("Remove book?", "\"" + row.title + "\" will disappear from the catalogue.", "Remove",
                        () -> AdminRepo.deleteBook(book, afterAction("Book removed.")))});
    }

    private void form(DocumentSnapshot book) {
        boolean edit = book != null;
        new FormBuilder(requireContext(), edit ? "Edit book" : "Add book")
                .text("title", "Title", edit ? AdminRepo.str(book, "title") : "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS)
                .text("author", "Author", edit ? AdminRepo.str(book, "author") : "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS)
                .text("isbn", "ISBN (10 or 13 digits, optional)", edit ? AdminRepo.str(book, "isbn") : "", InputType.TYPE_CLASS_TEXT)
                .text("category", "Category", edit ? AdminRepo.str(book, "category") : "", InputType.TYPE_CLASS_TEXT)
                .text("shelf", "Shelf location", edit ? AdminRepo.str(book, "shelfLocation") : "", InputType.TYPE_CLASS_TEXT)
                .text("copies", "Total copies owned", edit ? String.valueOf(Math.max(AdminRepo.num(book, "totalCopies"),
                        AdminRepo.num(book, "copiesAvailable"))) : "1", InputType.TYPE_CLASS_NUMBER)
                .show(edit ? "Save" : "Add", v -> {
                    String title = v.get("title"), isbn = v.get("isbn").replace("-", "").replace(" ", "");
                    if (title.isEmpty() || title.length() > 200) return "Enter the title (up to 200 characters).";
                    if (v.get("author").length() > 100) return "The author is too long (100 characters at most).";
                    if (!isbn.isEmpty() && !isbn.matches("([0-9]{9}[0-9Xx]|[0-9]{13})")) return "The ISBN must be 10 or 13 digits.";
                    long copies;
                    try { copies = Long.parseLong(v.get("copies")); } catch (NumberFormatException e) { return "Enter the number of copies."; }
                    if (copies < 0 || copies > 10000) return "Copies must be between 0 and 10,000.";
                    AdminRepo.saveBook(edit ? book.getId() : null, title, v.get("author"), isbn.toUpperCase(Locale.ROOT),
                            v.get("category"), v.get("shelf"), copies, afterAction(edit ? "Book updated." : "Book added."));
                    return null;
                });
    }
}
