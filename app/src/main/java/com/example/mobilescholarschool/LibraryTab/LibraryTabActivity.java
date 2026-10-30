package com.example.mobilescholarschool.LibraryTab;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.mobilescholarschool.Components.AuthGuard;
import com.example.mobilescholarschool.Components.BottomNavHelper;
import com.example.mobilescholarschool.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Library screen:
 *   - searches the "books" collection                      (Find a book)
 *   - opens the request form and saves the request          (Request a book)
 *   - tracks Pending / Approved / Rejected requests         (My requests)
 *   - tracks borrowed, overdue and returned books           (Borrowed books)
 *   - keeps the Borrowed / Pending / Overdue counters live, and each counter opens the matching list
 */
public class LibraryTabActivity extends AppCompatActivity {

    private static final String TAG = "LibraryTabActivity";
    private static final int MAX_RESULTS = 20;

    private final LibraryRepository repository = new LibraryRepository();

    // The catalogue is downloaded ONCE, the first time the student searches, then reused.
    private final List<Book> catalogue = new ArrayList<>();
    private boolean catalogueLoaded = false;
    private boolean catalogueLoading = false;

    // NEW: true while we are reading the profile before opening the form, so a quick
    // double-tap on the row can't open two dialogs.
    private boolean openingRequestForm = false;

    private EditText editSearch;

    // The three numbers at the top of the screen
    private TextView txtBorrowed, txtPending, txtOverdue;

    // Live connections that keep those numbers fresh (started in onStart, stopped in onStop)
    private ListenerRegistration requestsListener;
    private ListenerRegistration loansListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Signed-out visitors are sent to the login screen.
        if (AuthGuard.redirectIfSignedOut(this)) {
            return;
        }

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_library_tab);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Highlight the Library tab and make the other tabs clickable
        BottomNavHelper.setup(this, R.id.nav_library);

        editSearch = findViewById(R.id.editTxtTxt);

        // Pressing the keyboard's search key runs the search
        editSearch.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        editSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch();
                return true;
            }
            return false;
        });

        // The "Find a book" row is made of four views (icon, title, description, arrow).
        // Giving each the same listener makes the whole row tappable.
        View.OnClickListener searchClick = v -> runSearch();
        findViewById(R.id.imgFindBook).setOnClickListener(searchClick);
        findViewById(R.id.txtViewFindBook).setOnClickListener(searchClick);
        findViewById(R.id.txtViewFindBookDescription).setOnClickListener(searchClick);
        findViewById(R.id.imgFindBookArrow).setOnClickListener(searchClick);

        // NEW: the "Request a book" row works the same way, but opens the dialog fragment.
        View.OnClickListener requestClick = v -> openRequestForm();
        findViewById(R.id.imgRequestBook).setOnClickListener(requestClick);
        findViewById(R.id.txtViewRequestBook).setOnClickListener(requestClick);
        findViewById(R.id.txtViewRequestBookDescription).setOnClickListener(requestClick);
        findViewById(R.id.imgRequestBookArrow).setOnClickListener(requestClick);

        // NEW: the "My requests" row opens the dialog that tracks Pending / Approved / Rejected.
        View.OnClickListener myRequestsClick = v -> openMyRequests(MyRequestsDialogFragment.FILTER_ALL);
        findViewById(R.id.imgRequests).setOnClickListener(myRequestsClick);
        findViewById(R.id.txtViewRequests).setOnClickListener(myRequestsClick);
        findViewById(R.id.txtViewRequestsDescription).setOnClickListener(myRequestsClick);
        findViewById(R.id.imgRequestsArrow).setOnClickListener(myRequestsClick);

        // NEW: the "Borrowed books" row opens the dialog that tracks loans.
        bindClick(v -> openBorrowedBooks(BorrowedBooksDialogFragment.FILTER_BORROWED),
                R.id.imgBorrowedBooks, R.id.txtViewBorrowedBooks,
                R.id.txtViewBorrowedBooksDescription, R.id.imgBorrowedBooksArrow);

        // NEW: the three numbers at the top. They show "0" until the first answer arrives.
        txtBorrowed = findViewById(R.id.editTxtNumberBorrowed);
        txtPending = findViewById(R.id.editTxtNumberPending);
        txtOverdue = findViewById(R.id.editTxtNumberOverdue);
        for (TextView counter : new TextView[]{txtBorrowed, txtPending, txtOverdue}) {
            counter.setText("0");
            // In the layout these are EditText boxes; make sure a student can't type over them
            counter.setFocusable(false);
            counter.setCursorVisible(false);
            counter.setKeyListener(null);
        }

        // NEW: each number (and its label) opens the list it counts.
        bindClick(v -> openBorrowedBooks(BorrowedBooksDialogFragment.FILTER_BORROWED),
                R.id.txtViewBorrowed, R.id.editTxtNumberBorrowed);
        bindClick(v -> openMyRequests(MyRequestsDialogFragment.FILTER_PENDING),
                R.id.txtViewPending, R.id.editTxtNumberPending);
        bindClick(v -> openBorrowedBooks(BorrowedBooksDialogFragment.FILTER_OVERDUE),
                R.id.txtViewOverdue, R.id.editTxtNumberOverdue);

        // The dialog does NOT save anything itself. When the student submits a valid form it sends
        // the answers back under REQUEST_KEY, and this listener receives them. Passing `this` ties
        // the listener to the screen's lifecycle, so it also works after a rotation.
        getSupportFragmentManager().setFragmentResultListener(
                BorrowRequestDialogFragment.REQUEST_KEY, this,
                (requestKey, result) -> saveRequest(result));
    }

    // NEW: the live counters connect when the screen becomes visible...
    @Override
    protected void onStart() {
        super.onStart();
        if (txtBorrowed != null) {   // null only if AuthGuard sent a signed-out user away
            listenForCounts();
        }
    }

    // ...and disconnect when it is hidden, so they never leak. Also lets the "opening" flag
    // reset if the screen is hidden while we were waiting.
    @Override
    protected void onStop() {
        super.onStop();
        openingRequestForm = false;
        stopListeningForCounts();
    }

    // ---------------------------------------------------------------- NEW: the live counters

    /**
     * Keeps the Pending / Borrowed / Overdue numbers up to date. They come from the same two
     * collections the dialogs read, so a number and its list always agree. Each listener runs
     * once with the current data and again on every change.
     */
    private void listenForCounts() {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) {
            return;
        }
        stopListeningForCounts();   // never keep two connections open

        // Pending = this student's requests whose status is "pending"
        requestsListener = FirebaseFirestore.getInstance()
                .collection("bookRequests")
                .whereEqualTo("studentUid", uid)
                .addSnapshotListener((snap, error) -> {
                    if (error != null || snap == null) {
                        Log.e(TAG, "Pending counter failed", error);
                        return;   // keep showing the last known number
                    }
                    int pending = 0;
                    for (QueryDocumentSnapshot doc : snap) {
                        try {
                            String status = MyRequestsDialogFragment.normalizeStatus(doc.getString("status"));
                            if (MyRequestsDialogFragment.FILTER_PENDING.equals(status)) {
                                pending++;
                            }
                        } catch (RuntimeException e) {
                            Log.e(TAG, "Skipping bad request document: " + doc.getId(), e);
                        }
                    }
                    txtPending.setText(String.valueOf(pending));
                });

        // Borrowed = loans not yet returned. Overdue = those whose due DAY has passed.
        loansListener = FirebaseFirestore.getInstance()
                .collection("loans")
                .whereEqualTo("studentUid", uid)
                .addSnapshotListener((snap, error) -> {
                    if (error != null || snap == null) {
                        Log.e(TAG, "Loan counters failed", error);
                        return;
                    }
                    int borrowed = 0;
                    int overdue = 0;
                    for (QueryDocumentSnapshot doc : snap) {
                        try {
                            if (doc.getTimestamp("returnedAt") != null) {
                                continue;   // already returned: not borrowed any more
                            }
                            borrowed++;
                            // The SAME definition of overdue the dialog uses
                            if (BorrowedBooksDialogFragment.isOverdue(doc.getTimestamp("dueDate"), null)) {
                                overdue++;
                            }
                        } catch (RuntimeException e) {
                            Log.e(TAG, "Skipping bad loan document: " + doc.getId(), e);
                        }
                    }
                    txtBorrowed.setText(String.valueOf(borrowed));
                    txtOverdue.setText(String.valueOf(overdue));
                });
    }

    private void stopListeningForCounts() {
        if (requestsListener != null) {
            requestsListener.remove();
            requestsListener = null;
        }
        if (loansListener != null) {
            loansListener.remove();
            loansListener = null;
        }
    }

    /** Gives every one of the listed views the same click listener (so a whole row is tappable). */
    private void bindClick(View.OnClickListener listener, int... viewIds) {
        for (int id : viewIds) {
            findViewById(id).setOnClickListener(listener);
        }
    }

    // ---------------------------------------------------------------- NEW: request a book

    /**
     * Reads the student's registration number from their profile, then opens the form.
     * The form needs it because the security rules will compare it with the saved profile.
     * (This direct Firestore call will move into LibraryRepository later.)
     */
    private void openRequestForm() {
        // Already open (for example after a rotation)? Don't stack a second one.
        if (getSupportFragmentManager().findFragmentByTag(BorrowRequestDialogFragment.TAG) != null) {
            return;
        }
        if (openingRequestForm) {
            return;   // a profile read is already in flight
        }

        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) {
            return;   // signed out meanwhile; AuthGuard will redirect on the next launch
        }

        openingRequestForm = true;

        // The "this" in each listener ties it to the Activity: if the screen is stopped before
        // Firestore replies, the callback is skipped, so we never show a dialog on a screen that
        // is not in the foreground (that would crash with "state already saved").
        FirebaseFirestore.getInstance().collection("users").document(uid).get()
                .addOnSuccessListener(this, doc -> {
                    openingRequestForm = false;

                    String registrationNo = doc.getString("registrationNo");
                    if (registrationNo == null || registrationNo.trim().isEmpty()) {
                        Toast.makeText(this,
                                "Your student number is not available. Complete your profile first.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }

                    // Open the dialog. The three nulls mean "start with empty boxes"; later, a book
                    // picked from the search results will pass its ISBN, title and author here.
                    BorrowRequestDialogFragment
                            .newInstance(registrationNo.trim(), null, null, null)
                            .show(getSupportFragmentManager(), BorrowRequestDialogFragment.TAG);
                })
                .addOnFailureListener(this, e -> {
                    openingRequestForm = false;
                    // The student sees a friendly message; YOU see the real reason in Logcat
                    Log.e(TAG, "Profile read failed", e);
                    Toast.makeText(this, "Could not load your profile. Check your connection.",
                            Toast.LENGTH_LONG).show();
                });
    }

    /**
     * NEW: opens the "My requests" dialog. Unlike the request form it needs no profile read:
     * the dialog finds the signed-in student itself and listens to their requests live.
     */
    private void openMyRequests(String filter) {
        // Already open (for example after a rotation)? Don't stack a second one.
        if (getSupportFragmentManager().findFragmentByTag(MyRequestsDialogFragment.TAG) != null) {
            return;
        }
        MyRequestsDialogFragment.newInstance(filter)
                .show(getSupportFragmentManager(), MyRequestsDialogFragment.TAG);
    }

    /**
     * NEW: opens the "Borrowed books" dialog on the given tab (FILTER_BORROWED, FILTER_OVERDUE
     * or FILTER_RETURNED). Like "My requests" it needs no profile read: the dialog finds the
     * signed-in student itself and listens to their loans live.
     */
    private void openBorrowedBooks(String filter) {
        if (getSupportFragmentManager().findFragmentByTag(BorrowedBooksDialogFragment.TAG) != null) {
            return;
        }
        BorrowedBooksDialogFragment.newInstance(filter)
                .show(getSupportFragmentManager(), BorrowedBooksDialogFragment.TAG);
    }

    // ---------------------------------------------------------------- NEW: save the request

    /**
     * Turns the finished form into a document in "bookRequests".
     *
     * This is the step that was missing before: until now the form's answers only appeared in a
     * Toast, so nothing reached Firestore and "My requests" (which reads Firestore) had nothing to show.
     *
     * Note this creates a REQUEST with status "pending", not a loan. The librarian approves it
     * later, and only then does the book count as borrowed.
     */
    private void saveRequest(Bundle form) {
        // The student's identity is taken from Firebase Auth here, never from the dialog,
        // so a request can only ever be made in the signed-in student's own name.
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) {
            Toast.makeText(this, "Please sign in again.", Toast.LENGTH_LONG).show();
            return;
        }

        // EXACTLY these nine fields: the security rules reject any extra or missing field.
        Map<String, Object> request = new HashMap<>();
        request.put("studentUid", uid);
        request.put("studentIdNumber", form.getString(BorrowRequestDialogFragment.RESULT_STUDENT_ID, ""));
        request.put("isbn", form.getString(BorrowRequestDialogFragment.RESULT_ISBN, ""));
        request.put("bookTitle", form.getString(BorrowRequestDialogFragment.RESULT_TITLE, ""));
        request.put("author", form.getString(BorrowRequestDialogFragment.RESULT_AUTHOR, ""));
        request.put("borrowDate", new Timestamp(new Date(
                form.getLong(BorrowRequestDialogFragment.RESULT_BORROW_MILLIS))));
        request.put("returnDate", new Timestamp(new Date(
                form.getLong(BorrowRequestDialogFragment.RESULT_RETURN_MILLIS))));
        request.put("status", "pending");                      // only the librarian changes this later
        request.put("createdAt", FieldValue.serverTimestamp()); // the SERVER's clock, not the phone's

        // Firestore records the request on the phone straight away (it appears in "My requests" as
        // "sending…") and delivers it to the server when it can, even on a weak connection.
        Toast.makeText(this, "Request submitted. Track it under My requests.",
                Toast.LENGTH_LONG).show();

        repository.submitRequest(request)
                .addOnSuccessListener(this, ref ->
                        Log.d(TAG, "Request saved with ID " + ref.getId()))
                .addOnFailureListener(this, e -> {
                    // The student sees a friendly message; YOU see the real reason in Logcat
                    // (PERMISSION_DENIED means the "create" rule rejected it).
                    Log.e(TAG, "Saving the request failed", e);
                    Toast.makeText(this, "Your request could not be saved. Please try again.",
                            Toast.LENGTH_LONG).show();
                });
    }

    // ---------------------------------------------------------------- find a book (Stage 1, unchanged)

    /** Searches the catalogue for whatever is typed in the search box. */
    private void runSearch() {
        String query = editSearch.getText() == null ? "" : editSearch.getText().toString().trim();
        if (query.isEmpty()) {
            Toast.makeText(this, "Type a title, author or ISBN in the search box first.",
                    Toast.LENGTH_SHORT).show();
            editSearch.requestFocus();
            return;
        }

        if (catalogueLoaded) {
            showResults(query);   // already downloaded: no network needed
            return;
        }
        if (catalogueLoading) {
            return;               // already fetching: ignore extra taps
        }

        catalogueLoading = true;
        Toast.makeText(this, "Loading the catalogue…", Toast.LENGTH_SHORT).show();

        // Firebase calls run in the BACKGROUND. The code after this line runs immediately;
        // the lambda below runs later, when Firestore replies (successfully or not).
        repository.loadCatalogue().addOnCompleteListener(task -> {
            catalogueLoading = false;
            if (isFinishing() || isDestroyed()) {
                return;           // the screen closed while we waited
            }

            if (!task.isSuccessful() || task.getResult() == null) {
                // The student sees a friendly message; YOU see the real reason in Logcat
                // (PERMISSION_DENIED means the rules for "books" are not published yet).
                Log.e(TAG, "Catalogue failed", task.getException());
                Toast.makeText(this, "Could not load the catalogue. Check your connection.",
                        Toast.LENGTH_LONG).show();
                return;
            }

            catalogue.clear();
            for (QueryDocumentSnapshot doc : task.getResult()) {
                // One badly typed document (for example copiesAvailable saved as TEXT)
                // must not crash the whole screen, so each book gets its own safety net.
                try {
                    catalogue.add(doc.toObject(Book.class));
                } catch (RuntimeException e) {
                    Log.e(TAG, "Skipping bad book document: " + doc.getId(), e);
                }
            }
            catalogueLoaded = true;
            showResults(query);
        });
    }

    /** Filters the downloaded catalogue and shows the matches in a dialog. */
    private void showResults(String query) {
        String lowered = query.toLowerCase(Locale.ROOT);
        // "978-0-262-04630-5" and "9780262046305" should find the same book
        String isbnQuery = normalizeIsbn(query);

        List<Book> matches = new ArrayList<>();
        for (Book book : catalogue) {
            if (matches.size() >= MAX_RESULTS) {
                break;
            }
            boolean hit = contains(book.title, lowered)
                    || contains(book.author, lowered)
                    || (book.isbn != null && !isbnQuery.isEmpty()
                    && normalizeIsbn(book.isbn).contains(isbnQuery));
            if (hit) {
                matches.add(book);
            }
        }

        if (matches.isEmpty()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Search results")
                    .setMessage("No matching books found.")
                    .setPositiveButton("Close", null)
                    .show();
            return;
        }

        String[] labels = new String[matches.size()];
        for (int i = 0; i < matches.size(); i++) {
            Book b = matches.get(i);
            labels[i] = orDash(b.title) + "  —  " + orDash(b.author)
                    + "\nISBN " + orDash(b.isbn)
                    + "  ·  Shelf " + orDash(b.shelfLocation)
                    + "  ·  " + b.copiesAvailable + " available";
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("Search results (" + matches.size() + ")")
                .setItems(labels, null)
                .setPositiveButton("Close", null)
                .show();
    }

    // ---------------------------------------------------------------- small helpers

    private static boolean contains(String text, String loweredQuery) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(loweredQuery);
    }

    /** Removes spaces and hyphens and upper-cases an X: "978-0-262-04630-5" becomes "9780262046305". */
    private static String normalizeIsbn(String raw) {
        return raw == null ? "" : raw.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    /** Shows "-" instead of the word "null" when a field is missing. */
    private static String orDash(String value) {
        return (value == null || value.isEmpty()) ? "-" : value;
    }
}