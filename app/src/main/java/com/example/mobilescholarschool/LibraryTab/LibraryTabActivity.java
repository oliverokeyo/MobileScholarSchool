package com.example.mobilescholarschool.LibraryTab;

import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;                               // NEW
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.mobilescholarschool.Components.AuthGuard;
import com.example.mobilescholarschool.Components.BottomNavHelper;
import com.example.mobilescholarschool.Components.SessionUser;                  // NEW
import com.example.mobilescholarschool.Components.SwipeNavigator;
import com.example.mobilescholarschool.Data.AppDatabase;                 // NEW
import com.example.mobilescholarschool.Data.BookRequestRepository;       // NEW
import com.example.mobilescholarschool.Data.DataChangeNotifier;          // NEW
import com.example.mobilescholarschool.Data.DbExecutor;                  // NEW
import com.example.mobilescholarschool.Data.ProfileRepository;           // NEW
import com.example.mobilescholarschool.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Library screen:
 *   - searches the "books" collection                      (Find a book)
 *   - opens the request form and saves the request          (Request a book)
 *   - tracks Pending / Approved / Rejected requests         (My requests)
 *   - tracks borrowed, overdue and returned books           (Borrowed books)
 *   - keeps the Borrowed / Pending / Overdue counters live, and each counter opens the matching list
 */
public class LibraryTabActivity extends AppCompatActivity {

    // Drag-to-switch-tab with spring animation. Null until the screen is set up.
    private SwipeNavigator swipeNavigator;

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

    // ---- NEW: offline support ----
    // The "Pending" number on screen = requests the server already knows are pending
    //                                + requests saved on this phone that are not sent yet.
    private int serverPending = 0;
    private int localUnsent = 0;

    // Fires whenever the local database changes (a request was saved, delivered or refused)
    private final DataChangeNotifier.Listener dataListener = table -> {
        if (AppDatabase.T_BOOK_REQUESTS.equals(table)) {
            refreshLocalPending();
        }
    };

    // The "some requests were not sent" dialog, kept so onStop can close it
    private AlertDialog failedDialog;

    /**
     * Every touch on this screen passes through here BEFORE any view sees it. We ask the swipe
     * navigator first: if the user is dragging sideways it takes over (returns true) and the
     * views underneath never see that gesture. Otherwise everything works exactly as before.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (swipeNavigator != null && swipeNavigator.onTouchEvent(ev)) {
            return true;
        }
        return super.dispatchTouchEvent(ev);
    }

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
        // Must come AFTER BottomNavHelper.setup (it reads what setup leaves behind).
        swipeNavigator = new SwipeNavigator(this, R.id.nav_library);

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
            DataChangeNotifier.register(dataListener);   // NEW
            refreshLocalPending();                       // NEW
            showFailedRequests();                        // NEW
        }
    }

    // ...and disconnect when it is hidden, so they never leak. Also lets the "opening" flag
    // reset if the screen is hidden while we were waiting.
    @Override
    protected void onStop() {
        super.onStop();
        openingRequestForm = false;
        stopListeningForCounts();
        DataChangeNotifier.unregister(dataListener);     // NEW
        if (failedDialog != null) {                      // NEW
            failedDialog.dismiss();
            failedDialog = null;
        }
    }

    // ---------------------------------------------------------------- NEW: unsent requests

    /** Counts this student's requests that are saved on the phone but not yet on the server. */
    private void refreshLocalPending() {
        String uid = SessionUser.uid(getApplicationContext());   // real uid OR provisional id
        if (uid == null) {
            return;
        }
        DbExecutor.async(() -> BookRequestRepository.countUnsynced(getApplicationContext(), uid),
                count -> {
                    if (count == null || isFinishing() || isDestroyed()) {
                        return;
                    }
                    localUnsent = count;
                    showPendingCount();
                });
    }

    private void showPendingCount() {
        txtPending.setText(String.valueOf(serverPending + localUnsent));
    }

    /**
     * If the server refused a request we sent for the student later (for example the borrow date
     * had passed while they were offline), tell them ONCE, then forget it.
     */
    private void showFailedRequests() {
        String uid = SessionUser.uid(getApplicationContext());   // real uid OR provisional id
        if (uid == null) {
            return;
        }
        DbExecutor.async(() -> BookRequestRepository.failed(getApplicationContext(), uid), failed -> {
            if (failed == null || failed.isEmpty() || isFinishing() || isDestroyed()
                    || failedDialog != null) {
                return;
            }
            StringBuilder message = new StringBuilder();
            List<String> ids = new ArrayList<>();
            for (BookRequestRepository.Request r : failed) {
                ids.add(r.localId);
                String title = (r.bookTitle == null || r.bookTitle.isEmpty()) ? "Book request" : r.bookTitle;
                message.append("• ").append(title).append("\n  ")
                        .append(r.syncError == null ? "Not accepted." : r.syncError).append("\n\n");
            }
            failedDialog = new MaterialAlertDialogBuilder(this)
                    .setTitle("Some requests were not sent")
                    .setMessage(message.toString().trim())
                    .setPositiveButton("OK", (d, which) ->
                            DbExecutor.run(() -> BookRequestRepository.deleteRows(
                                    getApplicationContext(), ids)))
                    .setOnDismissListener(d -> failedDialog = null)
                    .show();
        });
    }

    // ---------------------------------------------------------------- NEW: the live counters

    /**
     * Keeps the Pending / Borrowed / Overdue numbers up to date. They come from the same two
     * collections the dialogs read, so a number and its list always agree. Each listener runs
     * once with the current data and again on every change.
     */
    private void listenForCounts() {
        // SERVER listeners need a real account: a waiting sign-up has none, so they stay off
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
                    serverPending = pending;   // CHANGED: was txtPending.setText(...)
                    showPendingCount();
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

        String uid = SessionUser.uid(getApplicationContext());   // real uid OR provisional id
        if (uid == null) {
            return;   // signed out meanwhile; AuthGuard will redirect on the next launch
        }

        openingRequestForm = true;

        // CHANGED. STEP 1: ask the LOCAL database first. It answers in milliseconds and works
        // with no internet. (Before, every tap waited for a Firestore round trip.)
        DbExecutor.async(() -> ProfileRepository.get(getApplicationContext(), uid), local -> {
            if (isFinishing() || isDestroyed()) {
                openingRequestForm = false;
                return;
            }
            if (local != null && local.registrationNo != null
                    && !local.registrationNo.trim().isEmpty()) {
                openingRequestForm = false;
                showRequestForm(local.registrationNo.trim());
            } else {
                // STEP 2: nothing saved here yet (first run): ask Firestore, like before
                if (!SessionUser.isSignedIn()) {
                    openingRequestForm = false;
                    Toast.makeText(this, "Your student number is not available yet.",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                fetchRegistrationNoFromServer(uid);
            }
        });
    }

    /** Opens the form, but only if the screen is still in the foreground. */
    private void showRequestForm(String registrationNo) {
        if (isFinishing() || isDestroyed() || getSupportFragmentManager().isStateSaved()) {
            return;   // showing a dialog now would crash with "state already saved"
        }
        // The three nulls mean "start with empty boxes"; later, a book picked from the search
        // results will pass its ISBN, title and author here.
        BorrowRequestDialogFragment
                .newInstance(registrationNo, null, null, null)
                .show(getSupportFragmentManager(), BorrowRequestDialogFragment.TAG);
    }

    /** The original Firestore read, now only the fallback for a phone with no local copy yet. */
    private void fetchRegistrationNoFromServer(String uid) {
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
                    showRequestForm(registrationNo.trim());
                })
                .addOnFailureListener(this, e -> {
                    openingRequestForm = false;
                    // The student sees a friendly message; YOU see the real reason in Logcat
                    Log.e(TAG, "Profile read failed", e);
                    Toast.makeText(this, "Could not load your profile. Connect to the internet once "
                                    + "so it can be saved on this phone.",
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
     * CHANGED: saves the finished form in the LOCAL database, not straight to Firestore.
     *
     * The row goes into book_requests (sync_state = pending_sync) and the outbox in one
     * transaction, then SyncWorker delivers it to "bookRequests" as soon as there is internet
     * (usually within seconds when online). The security rules are unchanged: the worker still
     * sends exactly the nine fields they require, with status "pending".
     *
     * Note this creates a REQUEST, not a loan. The librarian approves it later.
     */
    private void saveRequest(Bundle form) {
        // The student's identity is taken from Firebase Auth here, never from the dialog,
        // so a request can only ever be made in the signed-in student's own name.
        String uid = SessionUser.uid(getApplicationContext());   // real uid OR provisional id
        if (uid == null) {
            Toast.makeText(this, "Please sign in again.", Toast.LENGTH_LONG).show();
            return;
        }

        BookRequestRepository.Request request = new BookRequestRepository.Request();
        request.uid = uid;
        request.studentIdNumber = form.getString(BorrowRequestDialogFragment.RESULT_STUDENT_ID, "");
        request.isbn = form.getString(BorrowRequestDialogFragment.RESULT_ISBN, "");
        request.bookTitle = form.getString(BorrowRequestDialogFragment.RESULT_TITLE, "");
        request.author = form.getString(BorrowRequestDialogFragment.RESULT_AUTHOR, "");
        request.borrowMillis = form.getLong(BorrowRequestDialogFragment.RESULT_BORROW_MILLIS);
        request.returnMillis = form.getLong(BorrowRequestDialogFragment.RESULT_RETURN_MILLIS);

        DbExecutor.async(() -> BookRequestRepository.createLocal(getApplicationContext(), request),
                id -> {
                    if (id == null) {
                        // The student sees a friendly message; YOU see the reason in Logcat
                        Toast.makeText(getApplicationContext(),
                                "Could not save your request on this phone. Please try again.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    Toast.makeText(getApplicationContext(),
                            "Request saved. It is sent automatically when you are online.",
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

        if (!catalogueLoaded && !SessionUser.isSignedIn()) {
            Toast.makeText(this, "Searching the catalogue needs your account. It works once "
                    + "your account has been created.", Toast.LENGTH_LONG).show();
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
                Toast.makeText(this, "Could not load the catalogue. Connect to the internet once so it can be saved on this phone.",
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