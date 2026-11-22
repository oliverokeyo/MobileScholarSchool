package com.example.mobilescholarschool.LibraryTab;

import android.app.Dialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.example.mobilescholarschool.Components.SessionUser;                  // NEW
import com.example.mobilescholarschool.Data.AppDatabase;                 // NEW
import com.example.mobilescholarschool.Data.BookRequestRepository;       // NEW
import com.example.mobilescholarschool.Data.DataChangeNotifier;          // NEW
import com.example.mobilescholarschool.Data.DbExecutor;                  // NEW
import com.example.mobilescholarschool.Data.SyncState;                   // NEW
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QuerySnapshot;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;


public class MyRequestsDialogFragment extends DialogFragment {

    public static final String TAG = "MyRequestsDialog";

    // Which tab to open on. The values match the status text stored in Firestore.
    public static final String FILTER_ALL = "all";
    public static final String FILTER_PENDING = "pending";
    public static final String FILTER_APPROVED = "approved";
    public static final String FILTER_REJECTED = "rejected";

    private static final String[] FILTERS =
            {FILTER_ALL, FILTER_PENDING, FILTER_APPROVED, FILTER_REJECTED};
    private static final String[] TAB_NAMES = {"All", "Pending", "Approved", "Rejected"};

    private static final String ARG_FILTER = "arg_filter";
    private static final String STATE_TAB = "state_tab";

    // Colours for the status line. The WORD (PENDING, APPROVED...) is always shown too,
    // so colour is never the only clue (colour-blind users, dark mode).
    private static final int COLOR_PENDING = 0xFFF9A825;
    private static final int COLOR_APPROVED = 0xFF43A047;
    private static final int COLOR_REJECTED = 0xFFE53935;
    private static final int COLOR_UNKNOWN = 0xFF9E9E9E;

    private TabLayout tabs;
    private LinearLayout list;
    private int selectedTab = 0;

    // CHANGED: what is on screen = requests the SERVER has + requests saved only on this PHONE
    private final List<RequestRow> requests = new ArrayList<>();        // the merged list that is drawn
    private final List<RequestRow> serverRows = new ArrayList<>();      // from Firestore
    private final List<RequestRow> localRows = new ArrayList<>();       // from SQLite (unsent / refused)
    private boolean localLoaded = false;  // NEW: true once SQLite has answered
    private boolean loaded = false;       // true once Firestore has answered at least once

    // NEW: redraw when a request is saved, delivered or refused while the dialog is open
    private final DataChangeNotifier.Listener dataListener = table -> {
        if (AppDatabase.T_BOOK_REQUESTS.equals(table)) {
            loadLocalRows();
        }
    };
    private boolean loadFailed = false;
    private ListenerRegistration listener;

    /** @param filter FILTER_ALL, FILTER_PENDING, FILTER_APPROVED or FILTER_REJECTED */
    public static MyRequestsDialogFragment newInstance(String filter) {
        Bundle args = new Bundle();
        args.putString(ARG_FILTER, filter);
        MyRequestsDialogFragment fragment = new MyRequestsDialogFragment();
        fragment.setArguments(args);
        return fragment;
    }

    // ================================================================ building the dialog

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);

        // The tabs: All | Pending | Approved | Rejected (each shows how many it holds)
        tabs = new TabLayout(requireContext());
        tabs.setTabMode(TabLayout.MODE_SCROLLABLE);
        for (String name : TAB_NAMES) {
            tabs.addTab(tabs.newTab().setText(name));
        }
        root.addView(tabs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // The list of requests, rebuilt by render() whenever the data or the tab changes
        list = new LinearLayout(requireContext());
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(24), dp(8), dp(24), dp(8));

        ScrollView scroll = new ScrollView(requireContext());
        scroll.setMinimumHeight(dp(180));   // so the dialog doesn't jump in size for short messages
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Which tab to start on: restored after a rotation, otherwise the one the caller asked for
        if (savedInstanceState != null) {
            selectedTab = savedInstanceState.getInt(STATE_TAB, 0);
        } else {
            Bundle args = getArguments();
            selectedTab = indexOfFilter(args == null ? FILTER_ALL : args.getString(ARG_FILTER, FILTER_ALL));
        }
        TabLayout.Tab initial = tabs.getTabAt(selectedTab);
        if (initial != null) {
            initial.select();
        }

        // Attached AFTER the first selection, so it only reacts to the student's own taps
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                selectedTab = tab.getPosition();
                render();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) { }

            @Override
            public void onTabReselected(TabLayout.Tab tab) { }
        });

        render();   // shows "Loading…" until the first answer arrives

        return new MaterialAlertDialogBuilder(requireContext())
                .setTitle("My requests")
                .setView(root)
                .setPositiveButton("Close", null)
                .create();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, selectedTab);
    }

    // ================================================================ live data

    // The live connection starts when the dialog becomes visible...
    @Override
    public void onStart() {
        super.onStart();
        attachListener();
        DataChangeNotifier.register(dataListener);   // NEW
        loadLocalRows();                             // NEW
    }

    // ...and stops when it is hidden or closed, so it never leaks.
    @Override
    public void onStop() {
        super.onStop();
        detachListener();
        DataChangeNotifier.unregister(dataListener);   // NEW
    }

    private void attachListener() {
        detachListener();   // never keep two connections open

        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) {
            loadFailed = true;
            render();
            return;
        }

        listener = FirebaseFirestore.getInstance()
                .collection("bookRequests")
                .whereEqualTo("studentUid", uid)
                .addSnapshotListener((snap, error) -> onSnapshot(snap, error));
    }

    private void detachListener() {
        if (listener != null) {
            listener.remove();
            listener = null;
        }
    }

    /** Runs once with the current data, then again every time any of the student's requests changes. */
    private void onSnapshot(@Nullable QuerySnapshot snap, @Nullable Exception error) {
        if (!isAdded() || list == null) {
            return;   // the dialog closed while we waited
        }

        if (error != null || snap == null) {
            // The student sees a friendly message; YOU see the real reason in Logcat
            // (PERMISSION_DENIED means the "bookRequests" read rule is not published yet).
            Log.e(TAG, "Requests listener failed", error);
            loadFailed = true;
            mergeAndRender();   // CHANGED: still show what is saved on the phone
            return;
        }

        loadFailed = false;
        loaded = true;
        serverRows.clear();
        for (DocumentSnapshot doc : snap.getDocuments()) {
            RequestRow row = RequestRow.from(doc);
            if (row != null) {
                serverRows.add(row);
            }
        }
        mergeAndRender();
    }

    // ================================================================ NEW: the local database

    /** Reads this student's unsent and refused requests from SQLite. */
    private void loadLocalRows() {
        if (getContext() == null) {
            return;
        }
        android.content.Context appContext = requireContext().getApplicationContext();
        String uid = SessionUser.uid(appContext);   // real uid OR provisional id
        if (uid == null) {
            return;
        }
        DbExecutor.async(() -> BookRequestRepository.listUnsent(appContext, uid), saved -> {
            if (saved == null || !isAdded() || list == null) {
                return;
            }
            localRows.clear();
            for (BookRequestRepository.Request r : saved) {
                localRows.add(RequestRow.fromLocal(r));
            }
            localLoaded = true;
            mergeAndRender();
        });
    }

    /**
     * LESSON: MERGING TWO SOURCES. A request is in exactly one of two places: only on the phone
     * (waiting or refused), or on the server. The worker uses the SAME id in both, so if a
     * request is in both for a moment (sent, but not yet marked as sent) we drop the phone's
     * copy and show the server's.
     */
    private void mergeAndRender() {
        requests.clear();
        requests.addAll(serverRows);
        for (RequestRow local : localRows) {
            boolean serverHasIt = false;
            for (RequestRow server : serverRows) {
                if (local.id != null && local.id.equals(server.id)) {
                    serverHasIt = true;
                    break;
                }
            }
            if (!serverHasIt) {
                requests.add(local);
            }
        }
        // Unsent rows have no createdAt, so they sort first (newest)
        Collections.sort(requests, (a, b) -> newestFirst(a.createdAt, b.createdAt));
        render();
    }

    // ================================================================ drawing the list

    /** Redraws the tab counts and the list for the selected tab. */
    private void render() {
        if (list == null || tabs == null) {
            return;
        }

        int pending = 0;
        int approved = 0;
        int rejected = 0;
        for (RequestRow row : requests) {
            if (STATUS_PENDING.equals(row.status)) {
                pending++;
            } else if (STATUS_APPROVED.equals(row.status)) {
                approved++;
            } else if (STATUS_REJECTED.equals(row.status)) {
                rejected++;
            }
        }
        setTabText(0, requests.size());
        setTabText(1, pending);
        setTabText(2, approved);
        setTabText(3, rejected);

        list.removeAllViews();

        // CHANGED: "ready" once EITHER source has answered, so offline the saved requests still show
        boolean ready = loaded || localLoaded;
        if (!ready) {
            addMessage(loadFailed ? "Could not load your requests. Check your connection."
                    : "Loading…");
            return;
        }

        String filter = FILTERS[selectedTab];
        int shown = 0;
        for (RequestRow row : requests) {
            if (!matchesFilter(filter, row.status)) {
                continue;
            }
            if (shown > 0) {
                list.addView(divider());
            }
            list.addView(buildRow(row));
            shown++;
        }

        if (shown == 0) {
            addMessage(emptyMessage(filter));
        }
    }

    private void setTabText(int index, int count) {
        TabLayout.Tab tab = tabs.getTabAt(index);
        if (tab != null) {
            // Until the first answer arrives we don't know the counts, so show only the name
            tab.setText((loaded || localLoaded) ? TAB_NAMES[index] + " (" + count + ")" : TAB_NAMES[index]);
        }
    }

    /** One request: heading, coloured status, dates, extra details and the librarian's note. */
    private View buildRow(RequestRow row) {
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(12), 0, dp(12));

        addLine(box, makeText(headingOf(row.title, row.author, row.isbn), 16, true, 0), 0);

        // CHANGED: the phone's own record of "not on the server yet" replaces hasPendingWrites
        String statusLine = "● " + (row.notSent ? "NOT SENT" : statusLabel(row.status))
                + (row.waitingToSend ? "  (waiting to send…)" : row.pendingWrite ? "  (sending…)" : "");
        addLine(box, makeText(statusLine, 13, true, colorOf(row.status)), 2);

        addLine(box, makeText("Borrow: " + formatDate(row.borrowDate)
                + "  →  Return: " + formatDate(row.returnDate), 14, false, 0), 4);

        String details = detailsOf(row.title, row.author, row.isbn);
        if (!details.isEmpty()) {
            addLine(box, makeText(details, 14, false, 0), 2);
        }

        // Filled in by the librarian later (for example why a request was rejected)
        if (row.adminNote != null && !row.adminNote.trim().isEmpty()) {
            addLine(box, makeText((row.notSent ? "Reason: " : "Librarian note: ")
                    + row.adminNote.trim(), 14, false, 0), 6);
        }
        return box;
    }

    private void addMessage(String message) {
        TextView view = makeText(message, 15, false, 0);
        view.setPadding(0, dp(24), 0, dp(24));
        list.addView(view);
    }

    private TextView makeText(String text, int sp, boolean bold, int color) {
        TextView view = new TextView(requireContext());
        view.setText(text);
        view.setTextSize(sp);
        if (bold) {
            view.setTypeface(null, Typeface.BOLD);
        }
        if (color != 0) {
            view.setTextColor(color);
        }
        return view;
    }

    private void addLine(LinearLayout parent, TextView view, int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topMarginDp);
        parent.addView(view, params);
    }

    private View divider() {
        View line = new View(requireContext());
        line.setBackgroundColor(0x33808080);   // faint grey that suits light and dark themes
        line.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        return line;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ================================================================ one request, as read from Firestore

    /** A small copy of the fields we show, so the screen never touches raw Firestore documents. */
    private static class RequestRow {
        String id;                         // NEW: the document id (= the local id), used to merge
        String isbn, title, author, status, adminNote;
        Timestamp borrowDate, returnDate, createdAt;
        boolean pendingWrite;
        boolean waitingToSend;             // NEW: saved on the phone, not sent yet
        boolean notSent;                   // NEW: the server refused it

        /** NEW: a row that exists only in the phone's database. createdAt stays null so it sorts first. */
        static RequestRow fromLocal(BookRequestRepository.Request r) {
            RequestRow row = new RequestRow();
            row.id = r.localId;
            row.isbn = r.isbn;
            row.title = r.bookTitle;
            row.author = r.author;
            row.borrowDate = new Timestamp(new Date(r.borrowMillis));
            row.returnDate = new Timestamp(new Date(r.returnMillis));
            if (SyncState.FAILED.equals(r.syncState)) {
                row.status = STATUS_REJECTED;   // so it appears under the "Rejected" tab
                row.notSent = true;
                row.adminNote = r.syncError;
            } else {
                row.status = STATUS_PENDING;
                row.waitingToSend = true;
            }
            return row;
        }

        static RequestRow from(DocumentSnapshot doc) {
            // One badly typed document (for example "status" saved as a number) must not crash
            // the whole dialog, so each document gets its own safety net.
            try {
                RequestRow row = new RequestRow();
                row.id = doc.getId();
                row.isbn = doc.getString("isbn");
                row.title = doc.getString("bookTitle");
                row.author = doc.getString("author");
                row.status = normalizeStatus(doc.getString("status"));
                row.adminNote = doc.getString("adminNote");
                row.borrowDate = doc.getTimestamp("borrowDate");
                row.returnDate = doc.getTimestamp("returnDate");
                row.createdAt = doc.getTimestamp("createdAt");
                row.pendingWrite = doc.getMetadata().hasPendingWrites();
                return row;
            } catch (RuntimeException e) {
                Log.e(TAG, "Skipping bad request document: " + doc.getId(), e);
                return null;
            }
        }
    }

    private static int indexOfFilter(String filter) {
        for (int i = 0; i < FILTERS.length; i++) {
            if (FILTERS[i].equals(filter)) {
                return i;
            }
        }
        return 0;   // unknown value: open on "All"
    }

    private static int colorOf(String status) {
        if (STATUS_PENDING.equals(status)) return COLOR_PENDING;
        if (STATUS_APPROVED.equals(status)) return COLOR_APPROVED;
        if (STATUS_REJECTED.equals(status)) return COLOR_REJECTED;
        return COLOR_UNKNOWN;
    }

    private static String emptyMessage(String filter) {
        if (FILTER_PENDING.equals(filter)) return "No pending requests.";
        if (FILTER_APPROVED.equals(filter)) return "No approved requests yet.";
        if (FILTER_REJECTED.equals(filter)) return "No rejected requests.";
        return "You have not requested any books yet.";
    }

    /** Requests still waiting to reach the server have no createdAt yet, so they sort first (newest). */
    private static int newestFirst(Timestamp a, Timestamp b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        return b.compareTo(a);
    }

    private static String formatDate(Timestamp timestamp) {
        return timestamp == null ? "-" : formatDate(timestamp.toDate().getTime());
    }

    // >>> PURE-HELPERS-BEGIN  (plain Java: no Android code, so they can be unit-tested)

    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_APPROVED = "approved";
    private static final String STATUS_REJECTED = "rejected";
    private static final String STATUS_UNKNOWN = "unknown";

    /**
     * Turns whatever is stored in Firestore ("Approved", " PENDING ", a typo, nothing) into one of
     * our four known values, so a small typo in the console can't break the screen.
     */
    public static String normalizeStatus(String raw) {
        if (raw == null) {
            return STATUS_UNKNOWN;
        }
        String clean = raw.trim().toLowerCase(Locale.ROOT);
        if (clean.equals(STATUS_PENDING) || clean.equals(STATUS_APPROVED)
                || clean.equals(STATUS_REJECTED)) {
            return clean;
        }
        return STATUS_UNKNOWN;
    }

    /** The word shown to the student: PENDING, APPROVED, REJECTED or UNKNOWN. */
    public static String statusLabel(String normalizedStatus) {
        return normalizedStatus == null ? "UNKNOWN" : normalizedStatus.toUpperCase(Locale.ROOT);
    }

    /** "all" matches everything (even an unknown status); otherwise the status must be equal. */
    public static boolean matchesFilter(String filter, String normalizedStatus) {
        if (FILTER_ALL.equals(filter)) {
            return true;
        }
        return filter != null && filter.equals(normalizedStatus);
    }

    /** The big first line: the title if there is one, else the author, else the ISBN. */
    public static String headingOf(String title, String author, String isbn) {
        if (!isBlank(title)) return title.trim();
        if (!isBlank(author)) return author.trim();
        if (!isBlank(isbn)) return isbn.trim();
        return "Untitled request";   // can't happen once the form insists on one of the three
    }

    /** The smaller line with whatever the heading did NOT already show. */
    public static String detailsOf(String title, String author, String isbn) {
        boolean titleIsHeading = !isBlank(title);
        boolean authorIsHeading = !titleIsHeading && !isBlank(author);

        StringBuilder out = new StringBuilder();
        if (titleIsHeading && !isBlank(author)) {
            out.append("Author: ").append(author.trim());
        }
        if ((titleIsHeading || authorIsHeading) && !isBlank(isbn)) {
            if (out.length() > 0) {
                out.append("  ·  ");
            }
            out.append("ISBN: ").append(isbn.trim());
        }
        return out.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** A saved date shown in the phone's local time. */
    public static String formatDate(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date(millis));
    }

    // <<< PURE-HELPERS-END
}