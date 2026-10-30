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
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * "Borrowed books": the books the librarian has handed to the signed-in student.
 * Tabs: BORROWED (not returned yet, including overdue ones), OVERDUE, RETURNED (history).
 *
 * EVERYTHING lives in this one class (no XML, no other files). It READS the "loans" collection
 * and never writes to it. Only the librarian/admin side creates a loan or marks it returned.
 *
 * The list is LIVE: when the librarian marks a book returned, the student's open dialog updates
 * by itself.
 *
 * Fields it reads from each document in "loans" (a missing field simply shows as "-"):
 *   studentUid (string)             the student's Firebase UID; the rules and the query use it
 *   bookTitle, author, isbn         what the book is (at least one should be filled in)
 *   borrowDate, dueDate             timestamps
 *   returnedAt                      timestamp; leave it out until the book comes back
 *
 * Open it with:
 *   BorrowedBooksDialogFragment.newInstance(BorrowedBooksDialogFragment.FILTER_BORROWED)
 *           .show(getSupportFragmentManager(), BorrowedBooksDialogFragment.TAG);
 */
public class BorrowedBooksDialogFragment extends DialogFragment {

    public static final String TAG = "BorrowedBooksDialog";

    // Which tab to open on
    public static final String FILTER_BORROWED = "borrowed";
    public static final String FILTER_OVERDUE = "overdue";
    public static final String FILTER_RETURNED = "returned";

    private static final String[] FILTERS = {FILTER_BORROWED, FILTER_OVERDUE, FILTER_RETURNED};
    private static final String[] TAB_NAMES = {"Borrowed", "Overdue", "Returned"};

    private static final String ARG_FILTER = "arg_filter";
    private static final String STATE_TAB = "state_tab";

    // The WORD (DUE TODAY, OVERDUE...) is always shown too, so colour is never the only clue.
    private static final int COLOR_OK = 0xFF43A047;        // plenty of time left
    private static final int COLOR_SOON = 0xFFF9A825;      // due within 3 days
    private static final int COLOR_OVERDUE = 0xFFE53935;
    private static final int COLOR_RETURNED = 0xFF9E9E9E;

    private TabLayout tabs;
    private LinearLayout list;
    private int selectedTab = 0;

    private final List<LoanRow> loans = new ArrayList<>();
    private boolean loaded = false;
    private boolean loadFailed = false;
    private ListenerRegistration listener;

    /** @param filter FILTER_BORROWED, FILTER_OVERDUE or FILTER_RETURNED */
    public static BorrowedBooksDialogFragment newInstance(String filter) {
        Bundle args = new Bundle();
        args.putString(ARG_FILTER, filter);
        BorrowedBooksDialogFragment fragment = new BorrowedBooksDialogFragment();
        fragment.setArguments(args);
        return fragment;
    }

    /**
     * ONE shared definition of "overdue", also used by the Library screen's Overdue counter, so the
     * number on the screen can never disagree with the list in this dialog.
     * A book is overdue when it has NOT been returned and its due DAY has passed
     * (a book due today is not overdue until tomorrow).
     */
    public static boolean isOverdue(@Nullable Timestamp dueDate, @Nullable Timestamp returnedAt) {
        return STATE_OVERDUE.equals(loanState(returnedAt != null,
                dueDate == null ? null : dueDate.toDate().getTime(), System.currentTimeMillis()));
    }

    // ================================================================ building the dialog

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);

        // The tabs: Borrowed | Overdue | Returned (each shows how many it holds)
        tabs = new TabLayout(requireContext());
        tabs.setTabMode(TabLayout.MODE_SCROLLABLE);
        for (String name : TAB_NAMES) {
            tabs.addTab(tabs.newTab().setText(name));
        }
        root.addView(tabs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // The list, rebuilt by render() whenever the data or the tab changes
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
            selectedTab = indexOfFilter(args == null ? FILTER_BORROWED : args.getString(ARG_FILTER, FILTER_BORROWED));
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
                .setTitle("Borrowed books")
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

    @Override
    public void onStart() {
        super.onStart();
        attachListener();
    }

    @Override
    public void onStop() {
        super.onStop();
        detachListener();
    }

    private void attachListener() {
        detachListener();   // never keep two connections open

        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) {
            loadFailed = true;
            render();
            return;
        }

        // IMPORTANT: the whereEqualTo MUST match the security rule (studentUid == request.auth.uid).
        // Rules are not filters: asking for ALL loans would be rejected outright.
        // One filtered field and no Firestore sorting means no index is needed.
        listener = FirebaseFirestore.getInstance()
                .collection("loans")
                .whereEqualTo("studentUid", uid)
                .addSnapshotListener((snap, error) -> onSnapshot(snap, error));
    }

    private void detachListener() {
        if (listener != null) {
            listener.remove();
            listener = null;
        }
    }

    private void onSnapshot(@Nullable QuerySnapshot snap, @Nullable Exception error) {
        if (!isAdded() || list == null) {
            return;   // the dialog closed while we waited
        }

        if (error != null || snap == null) {
            // (PERMISSION_DENIED means the "loans" read rule is not published yet)
            Log.e(TAG, "Loans listener failed", error);
            loadFailed = true;
            render();
            return;
        }

        loadFailed = false;
        loaded = true;
        loans.clear();
        for (DocumentSnapshot doc : snap.getDocuments()) {
            LoanRow row = LoanRow.from(doc);
            if (row != null) {
                loans.add(row);
            }
        }
        render();
    }

    // ================================================================ drawing the list

    private void render() {
        if (list == null || tabs == null) {
            return;
        }

        long now = System.currentTimeMillis();

        int borrowed = 0;
        int overdue = 0;
        int returned = 0;
        for (LoanRow row : loans) {
            String state = stateOf(row, now);
            if (STATE_RETURNED.equals(state)) {
                returned++;
            } else {
                borrowed++;                      // overdue books are still borrowed...
                if (STATE_OVERDUE.equals(state)) {
                    overdue++;                   // ...and also counted as overdue
                }
            }
        }
        setTabText(0, borrowed);
        setTabText(1, overdue);
        setTabText(2, returned);

        list.removeAllViews();

        if (!loaded) {
            addMessage(loadFailed ? "Could not load your borrowed books. Check your connection."
                    : "Loading…");
            return;
        }

        String filter = FILTERS[selectedTab];
        List<LoanRow> shown = new ArrayList<>();
        for (LoanRow row : loans) {
            if (matchesFilter(filter, stateOf(row, now))) {
                shown.add(row);
            }
        }

        if (FILTER_RETURNED.equals(filter)) {
            // History: most recently returned first
            Collections.sort(shown, (a, b) -> compareDates(b.returnedAt, a.returnedAt));
        } else {
            // Current loans: the most urgent (earliest due date) first
            Collections.sort(shown, (a, b) -> compareDates(a.dueDate, b.dueDate));
        }

        for (int i = 0; i < shown.size(); i++) {
            if (i > 0) {
                list.addView(divider());
            }
            list.addView(buildRow(shown.get(i), now));
        }

        if (shown.isEmpty()) {
            addMessage(emptyMessage(filter));
        }
    }

    private void setTabText(int index, int count) {
        TabLayout.Tab tab = tabs.getTabAt(index);
        if (tab != null) {
            // Until the first answer arrives we don't know the counts, so show only the name
            tab.setText(loaded ? TAB_NAMES[index] + " (" + count + ")" : TAB_NAMES[index]);
        }
    }

    /** One loan: heading, coloured due/returned line, dates and extra details. */
    private View buildRow(LoanRow row, long now) {
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(12), 0, dp(12));

        addLine(box, makeText(headingOf(row.title, row.author, row.isbn), 16, true, 0), 0);

        String statusLine;
        int color;
        if (row.returnedAt != null) {
            statusLine = "● RETURNED on " + formatDate(row.returnedAt);
            color = COLOR_RETURNED;
        } else if (row.dueDate == null) {
            statusLine = "● NO DUE DATE";
            color = COLOR_RETURNED;
        } else {
            int days = daysUntil(row.dueDate.toDate().getTime(), now);
            statusLine = "● " + dueText(days);
            color = days < 0 ? COLOR_OVERDUE : (days <= 3 ? COLOR_SOON : COLOR_OK);
        }
        addLine(box, makeText(statusLine, 13, true, color), 2);

        addLine(box, makeText("Borrowed: " + formatDate(row.borrowDate)
                + "  →  Due: " + formatDate(row.dueDate), 14, false, 0), 4);

        String details = detailsOf(row.title, row.author, row.isbn);
        if (!details.isEmpty()) {
            addLine(box, makeText(details, 14, false, 0), 2);
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

    // ================================================================ one loan, as read from Firestore

    private static class LoanRow {
        String title, author, isbn;
        Timestamp borrowDate, dueDate, returnedAt;

        static LoanRow from(DocumentSnapshot doc) {
            // One badly typed document (for example dueDate saved as TEXT) must not crash the
            // whole dialog, so each document gets its own safety net.
            try {
                LoanRow row = new LoanRow();
                row.title = doc.getString("bookTitle");
                row.author = doc.getString("author");
                row.isbn = doc.getString("isbn");
                row.borrowDate = doc.getTimestamp("borrowDate");
                row.dueDate = doc.getTimestamp("dueDate");
                row.returnedAt = doc.getTimestamp("returnedAt");   // null until the book comes back
                return row;
            } catch (RuntimeException e) {
                Log.e(TAG, "Skipping bad loan document: " + doc.getId(), e);
                return null;
            }
        }
    }

    private static String stateOf(LoanRow row, long now) {
        return loanState(row.returnedAt != null,
                row.dueDate == null ? null : row.dueDate.toDate().getTime(), now);
    }

    private static int indexOfFilter(String filter) {
        for (int i = 0; i < FILTERS.length; i++) {
            if (FILTERS[i].equals(filter)) {
                return i;
            }
        }
        return 0;   // unknown value: open on "Borrowed"
    }

    private static String emptyMessage(String filter) {
        if (FILTER_OVERDUE.equals(filter)) return "Nothing is overdue. Well done!";
        if (FILTER_RETURNED.equals(filter)) return "You have not returned any books yet.";
        return "You have no borrowed books right now.";
    }

    /** Orders two dates earliest-first; a missing date goes last. */
    private static int compareDates(@Nullable Timestamp a, @Nullable Timestamp b) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        return a.compareTo(b);
    }

    private static String formatDate(@Nullable Timestamp timestamp) {
        return timestamp == null ? "-" : formatDate(timestamp.toDate().getTime());
    }

    // >>> PURE-HELPERS-BEGIN  (plain Java: no Android code, so they can be unit-tested)

    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    private static final String STATE_BORROWED = "borrowed";
    private static final String STATE_OVERDUE = "overdue";
    private static final String STATE_RETURNED = "returned";

    /** Midnight at the start of the given moment's day, in the phone's local time. */
    public static long startOfLocalDay(long millis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    /**
     * How many CALENDAR DAYS from today until the due date (negative = days ago).
     * We compare whole days, not hours: a book due at 09:00 today is "due today" all day,
     * and only becomes overdue tomorrow. Math.round absorbs daylight-saving 23/25 hour days.
     */
    public static int daysUntil(long dueMillis, long nowMillis) {
        long difference = startOfLocalDay(dueMillis) - startOfLocalDay(nowMillis);
        return (int) Math.round(difference / (double) DAY_MS);
    }

    /** "returned", "overdue" or "borrowed" (a book with no due date is just "borrowed"). */
    public static String loanState(boolean returned, @Nullable Long dueMillis, long nowMillis) {
        if (returned) {
            return STATE_RETURNED;
        }
        if (dueMillis != null && daysUntil(dueMillis, nowMillis) < 0) {
            return STATE_OVERDUE;
        }
        return STATE_BORROWED;
    }

    /** The Borrowed tab holds everything not returned, so overdue books appear there too. */
    public static boolean matchesFilter(String filter, String state) {
        if (FILTER_BORROWED.equals(filter)) {
            return !STATE_RETURNED.equals(state);
        }
        return filter != null && filter.equals(state);
    }

    /** The coloured status words for a book that is still out. */
    public static String dueText(int daysUntilDue) {
        if (daysUntilDue < 0) {
            int late = -daysUntilDue;
            return "OVERDUE BY " + late + (late == 1 ? " DAY" : " DAYS");
        }
        if (daysUntilDue == 0) return "DUE TODAY";
        if (daysUntilDue == 1) return "DUE TOMORROW";
        return "DUE IN " + daysUntilDue + " DAYS";
    }

    /** The big first line: the title if there is one, else the author, else the ISBN. */
    public static String headingOf(String title, String author, String isbn) {
        if (!isBlank(title)) return title.trim();
        if (!isBlank(author)) return author.trim();
        if (!isBlank(isbn)) return isbn.trim();
        return "Untitled book";
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
