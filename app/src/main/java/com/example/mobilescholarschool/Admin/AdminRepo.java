package com.example.mobilescholarschool.Admin;

import com.google.android.gms.tasks.Task;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.Transaction;
import com.google.firebase.firestore.WriteBatch;

import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every WRITE the admin side makes lives here, so the rules of the business (how a fine is
 * worked out, when a room counts as full) are in ONE place and each screen stays small.
 *
 * LESSON - TRANSACTIONS: "issue a book" changes three documents (request, loan, book stock).
 * If the phone died after two of them, the library would be wrong. A transaction makes all the
 * changes succeed together or not at all, and re-checks what it read if someone else changed it
 * meanwhile (two admins approving the same last copy cannot both win).
 */
public final class AdminRepo {

    public static final int MAX_DOCS = 1000;      // safety cap on one list (add paging for bigger schools)
    public static final long FINE_PER_DAY = 10;   // Ksh per day late: change it here

    public interface Result {
        void ok();
        void fail(String message);
    }

    private AdminRepo() {}

    // ------------------------------------------------------------ small helpers

    static FirebaseFirestore db() { return FirebaseFirestore.getInstance(); }

    static String uid() { return FirebaseAuth.getInstance().getUid(); }

    static String str(DocumentSnapshot d, String field) {
        try {
            String v = d.getString(field);
            return v == null ? "" : v;
        } catch (RuntimeException e) {
            return "";
        }
    }

    static long num(DocumentSnapshot d, String field) {
        try {
            Long v = d.getLong(field);
            return v == null ? 0 : v;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** A saved date as milliseconds, or 0 when missing. */
    static long millis(DocumentSnapshot d, String field) {
        try {
            Timestamp t = d.getTimestamp(field);
            return t == null ? 0 : t.toDate().getTime();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Lower-case, trimmed status ("Approved " and "approved" mean the same). */
    static String status(DocumentSnapshot d) {
        return str(d, "status").trim().toLowerCase(Locale.ROOT);
    }

    static Timestamp ts(long millis) { return new Timestamp(new Date(millis)); }

    static long startOfToday() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** Overdue = the due DAY has passed (due today is still fine). */
    static boolean isOverdue(long dueMillis) {
        return dueMillis > 0 && dueMillis < startOfToday();
    }

    static long daysLate(long dueMillis) {
        if (!isOverdue(dueMillis)) return 0;
        return Math.max(1, (startOfToday() - dueMillis) / (24L * 60 * 60 * 1000));
    }

    static void sortNewest(List<DocumentSnapshot> docs, String field) {
        Collections.sort(docs, new Comparator<DocumentSnapshot>() {
            @Override public int compare(DocumentSnapshot a, DocumentSnapshot b) {
                return Long.compare(millis(b, field), millis(a, field));
            }
        });
    }

    static String message(Exception e) {
        if (e instanceof FirebaseFirestoreException) {
            FirebaseFirestoreException.Code code = ((FirebaseFirestoreException) e).getCode();
            if (code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                return "Permission denied. Publish the updated firestore.rules and make sure you are signed in as an admin.";
            }
            if (code == FirebaseFirestoreException.Code.UNAVAILABLE) {
                return "No connection. Try again when you are online.";
            }
        }
        return e.getMessage() == null ? "Something went wrong." : e.getMessage();
    }

    private static void run(Task<?> task, Result r) {
        task.addOnSuccessListener(x -> r.ok()).addOnFailureListener(e -> r.fail(message(e)));
    }

    private static FirebaseFirestoreException stop(String why) {
        return new FirebaseFirestoreException(why, FirebaseFirestoreException.Code.ABORTED);
    }

    // ------------------------------------------------------------ students

    public static void setAccountStatus(String studentUid, String status, Result r) {
        Map<String, Object> m = new HashMap<>();
        m.put("accountStatus", status);
        m.put("updatedAt", FieldValue.serverTimestamp());
        run(db().collection("users").document(studentUid).update(m), r);
    }

    public static void updateStudent(String studentUid, Map<String, Object> fields, Result r) {
        fields.put("updatedAt", FieldValue.serverTimestamp());
        run(db().collection("users").document(studentUid).update(fields), r);
    }

    // ------------------------------------------------------------ library

    /**
     * id == null creates a book; otherwise edits it, keeping the number of copies on loan correct.
     * LESSON - FIELD NAMES: the student app's Book class reads "copiesAvailable" (copies on the shelf
     * now) and "shelfLocation". The admin writes exactly those names, plus "totalCopies" (copies the
     * library owns), so what the admin changes is what students see.
     */
    public static void saveBook(String id, String title, String author, String isbn, String category,
                                String shelfLocation, long totalCopies, Result r) {
        Map<String, Object> m = new HashMap<>();
        m.put("title", title);
        m.put("author", author);
        m.put("isbn", isbn);
        m.put("category", category);
        m.put("shelfLocation", shelfLocation);   // the student Library screen shows this
        m.put("totalCopies", totalCopies);
        if (id == null) {
            m.put("copiesAvailable", totalCopies);
            m.put("createdAt", FieldValue.serverTimestamp());
            run(db().collection("books").add(m), r);
            return;
        }
        DocumentReference ref = db().collection("books").document(id);
        run(db().runTransaction((Transaction.Function<Void>) tx -> {
            DocumentSnapshot old = tx.get(ref);
            long onLoan = Math.max(0, num(old, "totalCopies") - num(old, "copiesAvailable"));
            if (totalCopies < onLoan) {
                throw stop("Total copies cannot be lower than the " + onLoan + " copy(ies) currently on loan.");
            }
            m.put("copiesAvailable", totalCopies - onLoan);
            tx.update(ref, m);
            return null;
        }), r);
    }

    public static void deleteBook(DocumentSnapshot book, Result r) {
        if (num(book, "totalCopies") - num(book, "copiesAvailable") > 0) {
            r.fail("Some copies are on loan. Wait until they are returned.");
            return;
        }
        run(book.getReference().delete(), r);
    }

    /** Finds the catalogue book a request means: ISBN first, then title (+author), then author. */
    static DocumentSnapshot findBook(QuerySnapshot books, String isbn, String title, String author) {
        String i = isbn.replaceAll("[^0-9Xx]", "").toUpperCase(Locale.ROOT);
        DocumentSnapshot byIsbn = null, byTitle = null, byAuthor = null;
        for (DocumentSnapshot d : books.getDocuments()) {
            if (!i.isEmpty() && i.equals(str(d, "isbn").replaceAll("[^0-9Xx]", "").toUpperCase(Locale.ROOT))) {
                byIsbn = moreCopies(byIsbn, d);
            }
            if (!title.isEmpty() && title.equalsIgnoreCase(str(d, "title").trim())
                    && (author.isEmpty() || author.equalsIgnoreCase(str(d, "author").trim()))) {
                byTitle = moreCopies(byTitle, d);
            }
            if (title.isEmpty() && i.isEmpty() && !author.isEmpty()
                    && author.equalsIgnoreCase(str(d, "author").trim())) {
                byAuthor = moreCopies(byAuthor, d);
            }
        }
        return byIsbn != null ? byIsbn : byTitle != null ? byTitle : byAuthor;
    }

    private static DocumentSnapshot moreCopies(DocumentSnapshot best, DocumentSnapshot d) {
        return (best == null || num(d, "copiesAvailable") > num(best, "copiesAvailable")) ? d : best;
    }

    /** Approve = issue the book: request -> approved, a loan is created, stock goes down by one. */
    public static void approveRequest(DocumentSnapshot req, Result r) {
        db().collection("books").limit(MAX_DOCS).get().addOnSuccessListener(books -> {
            DocumentSnapshot book = findBook(books, str(req, "isbn").trim(),
                    str(req, "bookTitle").trim(), str(req, "author").trim());
            if (book == null) {
                r.fail("No matching book in the catalogue. Add the book first (Manage Books).");
                return;
            }
            DocumentReference reqRef = req.getReference();
            DocumentReference bookRef = book.getReference();
            DocumentReference loanRef = db().collection("loans").document();
            run(db().runTransaction((Transaction.Function<Void>) tx -> {
                DocumentSnapshot freshReq = tx.get(reqRef);
                DocumentSnapshot freshBook = tx.get(bookRef);
                if (!status(freshReq).equals("pending")) throw stop("This request was already decided.");
                long available = num(freshBook, "copiesAvailable");
                if (available <= 0) throw stop("No copies of \"" + str(freshBook, "title") + "\" are available.");

                Map<String, Object> loan = new HashMap<>();
                loan.put("studentUid", str(freshReq, "studentUid"));
                loan.put("studentIdNumber", str(freshReq, "studentIdNumber"));
                loan.put("bookId", bookRef.getId());
                loan.put("bookTitle", str(freshBook, "title"));
                loan.put("author", str(freshBook, "author"));
                loan.put("isbn", str(freshBook, "isbn"));
                loan.put("issuedAt", FieldValue.serverTimestamp());
                loan.put("dueDate", freshReq.getTimestamp("returnDate"));
                loan.put("returnedAt", null);   // null = still out (the student app checks this field)
                loan.put("fineAmount", 0L);
                loan.put("requestId", reqRef.getId());

                Map<String, Object> decision = new HashMap<>();
                decision.put("status", "approved");
                decision.put("decidedAt", FieldValue.serverTimestamp());
                decision.put("decidedBy", uid());
                decision.put("loanId", loanRef.getId());

                tx.set(loanRef, loan);
                tx.update(bookRef, "copiesAvailable", available - 1);
                tx.update(reqRef, decision);
                return null;
            }), r);
        }).addOnFailureListener(e -> r.fail(message(e)));
    }

    public static void rejectRequest(DocumentSnapshot req, Result r) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", "rejected");
        m.put("decidedAt", FieldValue.serverTimestamp());
        m.put("decidedBy", uid());
        run(req.getReference().update(m), r);
    }

    /** Returns a book: stamps returnedAt, puts the copy back on the shelf, and records any late fine. */
    public static void markReturned(DocumentSnapshot loan, Result r) {
        DocumentReference ref = loan.getReference();
        run(db().runTransaction((Transaction.Function<Void>) tx -> {
            DocumentSnapshot l = tx.get(ref);
            if (l.get("returnedAt") != null) throw stop("This book was already returned.");
            String bookId = str(l, "bookId");
            DocumentSnapshot book = bookId.isEmpty() ? null : tx.get(db().collection("books").document(bookId));

            long fine = daysLate(millis(l, "dueDate")) * FINE_PER_DAY;
            Map<String, Object> m = new HashMap<>();
            m.put("returnedAt", FieldValue.serverTimestamp());
            if (fine > num(l, "fineAmount")) m.put("fineAmount", fine);
            tx.update(ref, m);

            if (book != null && book.exists()) {
                long total = num(book, "totalCopies");
                long back = num(book, "copiesAvailable") + 1;
                tx.update(book.getReference(), "copiesAvailable", total > 0 ? Math.min(total, back) : back);
            }
            return null;
        }), r);
    }

    public static void clearFine(DocumentSnapshot loan, Result r) {
        run(loan.getReference().update("fineAmount", 0L), r);
    }

    /** Writes the fine earned so far onto every overdue loan, so students can SEE it on their Home card. */
    public static void updateFines(List<DocumentSnapshot> loans, Result r) {
        WriteBatch batch = db().batch();
        int n = 0;
        for (DocumentSnapshot l : loans) {
            if (l.get("returnedAt") != null) continue;
            long fine = daysLate(millis(l, "dueDate")) * FINE_PER_DAY;
            if (fine > num(l, "fineAmount") && n < 450) {
                batch.update(l.getReference(), "fineAmount", fine);
                n++;
            }
        }
        if (n == 0) { r.fail("Every fine is already up to date."); return; }
        run(batch.commit(), r);
    }

    // ------------------------------------------------------------ hostels and rooms

    public static void saveHostel(String id, Map<String, Object> fields, Result r) {
        if (id == null) {
            fields.put("createdAt", FieldValue.serverTimestamp());
            run(db().collection("hostels").add(fields), r);
        } else {
            run(db().collection("hostels").document(id).set(fields, SetOptions.merge()), r);
        }
    }

    public static void deleteHostel(DocumentSnapshot hostel, Result r) {
        db().collection("rooms").whereEqualTo("hostelId", hostel.getId()).limit(1).get()
                .addOnSuccessListener(s -> {
                    if (!s.isEmpty()) r.fail("Delete or move this hostel's rooms first.");
                    else run(hostel.getReference().delete(), r);
                }).addOnFailureListener(e -> r.fail(message(e)));
    }

    /** id == null creates a room (all places free); otherwise edits it and keeps the occupants. */
    public static void saveRoom(String id, String hostelId, String roomNumber, String roomType,
                                String sharing, long rent, long capacity, Result r) {
        Map<String, Object> m = new HashMap<>();
        m.put("hostelId", hostelId);
        m.put("roomNumber", roomNumber);
        m.put("roomType", roomType);
        m.put("sharing", sharing);
        m.put("rentPerMonth", rent);
        m.put("capacity", capacity);
        if (id == null) {
            m.put("slotsAvailable", capacity);
            m.put("createdAt", FieldValue.serverTimestamp());
            run(db().collection("rooms").add(m), r);
            return;
        }
        DocumentReference ref = db().collection("rooms").document(id);
        run(db().runTransaction((Transaction.Function<Void>) tx -> {
            DocumentSnapshot old = tx.get(ref);
            long oldCap = num(old, "capacity") > 0 ? num(old, "capacity") : num(old, "slotsAvailable");
            long occupied = Math.max(0, oldCap - num(old, "slotsAvailable"));
            if (capacity < occupied) {
                throw stop("Capacity cannot be lower than the " + occupied + " student(s) already allocated.");
            }
            m.put("slotsAvailable", capacity - occupied);
            tx.update(ref, m);
            return null;
        }), r);
    }

    public static void deleteRoom(DocumentSnapshot room, Result r) {
        long cap = num(room, "capacity");
        if (cap > 0 && cap - num(room, "slotsAvailable") > 0) {
            r.fail("Students are allocated to this room.");
            return;
        }
        run(room.getReference().delete(), r);
    }

    /**
     * Approves an application (or moves an approved student) into newRoom. Free places move with the
     * student: the new room loses one, the old room (if any) gets one back.
     */
    public static void allocate(DocumentSnapshot booking, DocumentReference newRoomRef, long checkInMillis, Result r) {
        DocumentReference bRef = booking.getReference();
        run(db().runTransaction((Transaction.Function<Void>) tx -> {
            DocumentSnapshot b = tx.get(bRef);
            String cur = status(b);
            if (!cur.equals("pending") && !cur.equals("approved")) throw stop("This application is already closed.");
            boolean same = cur.equals("approved") && newRoomRef.getId().equals(str(b, "roomId"));
            DocumentSnapshot room = tx.get(newRoomRef);
            DocumentSnapshot old = (cur.equals("approved") && !same && !str(b, "roomId").isEmpty())
                    ? tx.get(db().collection("rooms").document(str(b, "roomId"))) : null;

            if (!same) {
                long free = num(room, "slotsAvailable");
                if (free <= 0) throw stop("That room is full.");
                tx.update(newRoomRef, "slotsAvailable", free - 1);
            }
            if (old != null && old.exists()) {
                long cap = num(old, "capacity");
                long back = num(old, "slotsAvailable") + 1;
                tx.update(old.getReference(), "slotsAvailable", cap > 0 ? Math.min(cap, back) : back);
            }
            Map<String, Object> m = new HashMap<>();
            m.put("status", "approved");
            m.put("roomId", newRoomRef.getId());
            m.put("roomNumber", str(room, "roomNumber"));
            m.put("roomType", str(room, "roomType"));
            m.put("sharing", str(room, "sharing"));
            m.put("rentPerMonth", num(room, "rentPerMonth"));
            m.put("checkInDate", ts(checkInMillis));
            m.put("decidedAt", FieldValue.serverTimestamp());
            m.put("decidedBy", uid());
            tx.update(bRef, m);
            return null;
        }), r);
    }

    public static void rejectBooking(DocumentSnapshot booking, Result r) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", "rejected");
        m.put("decidedAt", FieldValue.serverTimestamp());
        m.put("decidedBy", uid());
        run(booking.getReference().update(m), r);
    }

    /** Student leaves: the booking becomes "vacated" and the place is free again. */
    public static void releaseBooking(DocumentSnapshot booking, Result r) {
        DocumentReference bRef = booking.getReference();
        run(db().runTransaction((Transaction.Function<Void>) tx -> {
            DocumentSnapshot b = tx.get(bRef);
            if (!status(b).equals("approved")) throw stop("Only an approved booking can be vacated.");
            String roomId = str(b, "roomId");
            DocumentSnapshot room = roomId.isEmpty() ? null : tx.get(db().collection("rooms").document(roomId));
            if (room != null && room.exists()) {
                long cap = num(room, "capacity");
                long back = num(room, "slotsAvailable") + 1;
                tx.update(room.getReference(), "slotsAvailable", cap > 0 ? Math.min(cap, back) : back);
            }
            Map<String, Object> m = new HashMap<>();
            m.put("status", "vacated");
            m.put("decidedAt", FieldValue.serverTimestamp());
            m.put("decidedBy", uid());
            tx.update(bRef, m);
            return null;
        }), r);
    }

    // ------------------------------------------------------------ fees and payments

    public static void saveFee(String studentUid, String regNo, long billed, long dueMillis, Result r) {
        Map<String, Object> m = new HashMap<>();
        m.put("totalBilled", billed);
        m.put("dueDate", ts(dueMillis));
        m.put("studentIdNumber", regNo);
        m.put("updatedAt", FieldValue.serverTimestamp());
        run(db().collection("feeAccounts").document(studentUid).set(m, SetOptions.merge()), r);
    }

    /** Bills many students at once. pairs[i] = {uid, regNo}. One batch holds up to 500 writes. */
    public static void billMany(List<String[]> pairs, long billed, long dueMillis, Result r) {
        if (pairs.isEmpty()) { r.fail("Every student already has a fee record."); return; }
        WriteBatch batch = db().batch();
        int n = 0;
        for (String[] p : pairs) {
            if (n >= 450) break;
            Map<String, Object> m = new HashMap<>();
            m.put("totalBilled", billed);
            m.put("dueDate", ts(dueMillis));
            m.put("studentIdNumber", p[1]);
            m.put("updatedAt", FieldValue.serverTimestamp());
            batch.set(db().collection("feeAccounts").document(p[0]), m, SetOptions.merge());
            n++;
        }
        run(batch.commit(), r);
    }

    public static void verifyPayment(DocumentSnapshot payment, Result r) {
        if (!status(payment).equals("success") || payment.getLong("resultCode") == null
                || payment.getLong("resultCode") != 0) {
            r.fail("Only a payment with result code 0 can be verified.");
            return;
        }
        Map<String, Object> m = new HashMap<>();
        m.put("verified", true);
        m.put("verifiedAt", FieldValue.serverTimestamp());
        m.put("verifiedBy", uid());
        run(payment.getReference().update(m), r);
    }
}
