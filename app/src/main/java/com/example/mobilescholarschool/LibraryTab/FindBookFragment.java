package com.example.mobilescholarschool.LibraryTab;

import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.mobilescholarschool.Components.ListScreenFragment;
import com.example.mobilescholarschool.Components.SessionUser;
import com.example.mobilescholarschool.R;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * "Find a book": the search bar AND the whole catalogue on one screen.
 *
 *   - With nothing typed, it lists EVERY book in the library, A to Z by title.
 *   - As the student types, the list narrows to books whose title, author or ISBN match.
 *   - Every book has a "Request this book" button. This screen does NOT save anything: it
 *     hands the book's details to the Activity (Fragment Result API), which opens the request
 *     form already filled in.
 *
 * It only READS the "books" collection, through LibraryRepository.
 */
public class FindBookFragment extends ListScreenFragment {

    public static final String TAG = "FindBookFragment";

    // ---- what the Activity listens for when the student taps "Request this book" ----
    public static final String REQUEST_KEY = "find_book_request";
    public static final String RESULT_ISBN = "result_isbn";
    public static final String RESULT_TITLE = "result_title";
    public static final String RESULT_AUTHOR = "result_author";

    // A fixed ID, so the typed text survives a rotation (Android saves text by view ID).
    private static final int ID_SEARCH = 4201;

    private final LibraryRepository repository = new LibraryRepository();

    // LESSON: these fields live in the Fragment OBJECT, not in its view. The view can be
    // destroyed and rebuilt (for example when the request form is opened on top and the
    // student comes back), but the catalogue stays here, so we never download it twice.
    private final List<Book> catalogue = new ArrayList<>();
    private boolean loaded = false;
    private boolean loading = false;
    private boolean loadFailed = false;

    private EditText editSearch;

    public static FindBookFragment newInstance() {
        return new FindBookFragment();
    }

    @Override
    protected String screenTitle() {
        return "Find a book";
    }

    // ================================================================ the search bar

    @Override
    protected void onBuildHeader(@NonNull LinearLayout header, @Nullable Bundle savedInstanceState) {
        // A plain EditText: its look comes from the theme, exactly like the one that used to
        // sit on the Library screen.
        editSearch = new EditText(requireContext());
        editSearch.setId(ID_SEARCH);
        editSearch.setHint(R.string.lib_search_hint);
        editSearch.setInputType(InputType.TYPE_CLASS_TEXT);
        editSearch.setSingleLine(true);
        editSearch.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        editSearch.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0);
        editSearch.setCompoundDrawablePadding(dp(8));
        header.addView(editSearch, new LinearLayout.LayoutParams(MATCH, WRAP));

        // The list updates on EVERY keystroke. The catalogue is already on the phone, so this
        // costs no network at all.
        editSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable s) {
                render();
            }
        });

        // The keyboard's search key just closes the keyboard (the list is already filtered).
        editSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard();
                return true;
            }
            return false;
        });
    }

    @Override
    public void onViewCreated(@NonNull android.view.View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (loaded) {
            render();            // we came back to this screen: reuse what we already have
        } else {
            loadCatalogue();
        }
    }

    // ================================================================ loading the catalogue

    private void loadCatalogue() {
        if (!SessionUser.isSignedIn()) {
            render();   // shows the "needs your account" message
            return;
        }
        if (loading) {
            return;     // already fetching: ignore extra taps
        }
        loading = true;
        loadFailed = false;
        render();       // shows "Loading…"

        // Firebase calls run in the BACKGROUND. The lambda below runs later, when Firestore
        // replies (successfully or not).
        repository.loadCatalogue().addOnCompleteListener(task -> {
            loading = false;

            if (!task.isSuccessful() || task.getResult() == null) {
                // The student sees a friendly message; YOU see the real reason in Logcat
                // (PERMISSION_DENIED means the rules for "books" are not published yet).
                Log.e(TAG, "Catalogue failed", task.getException());
                loadFailed = true;
                render();
                return;
            }

            catalogue.clear();
            for (QueryDocumentSnapshot doc : task.getResult()) {
                // One badly typed document must not crash the whole screen.
                try {
                    catalogue.add(doc.toObject(Book.class));
                } catch (RuntimeException e) {
                    Log.e(TAG, "Skipping bad book document: " + doc.getId(), e);
                }
            }

            // LESSON: orderBy("title") in Firestore puts "Zebra" BEFORE "apple" (capitals sort
            // first) and silently leaves out any book with no title. So we sort again here,
            // ignoring capitals, to make sure the list is truly A to Z.
            Collections.sort(catalogue, (a, b) -> compareTitles(a.title, b.title));

            loaded = true;
            render();
        });
    }

    // ================================================================ drawing the list

    /** Rebuilds the list. Safe to call at any time: it does nothing if the view is gone. */
    private void render() {
        if (list == null || !isAdded()) {
            return;
        }
        list.removeAllViews();

        if (!SessionUser.isSignedIn()) {
            addMessage(list, "Browsing the catalogue needs your account. It works once your "
                    + "account has been created.");
            return;
        }
        if (!loaded) {
            if (loadFailed) {
                addMessage(list, "Could not load the catalogue. Connect to the internet and try again.");
                MaterialButton retry = new MaterialButton(requireContext());
                retry.setText("Try again");
                retry.setOnClickListener(v -> loadCatalogue());
                list.addView(retry, new LinearLayout.LayoutParams(WRAP, WRAP));
            } else {
                addMessage(list, "Loading the catalogue…");
            }
            return;
        }

        String query = editSearch.getText() == null ? "" : editSearch.getText().toString().trim();
        List<Book> shown = filter(query);

        if (catalogue.isEmpty()) {
            addMessage(list, "The library has no books in the catalogue yet.");
            return;
        }

        String summary = query.isEmpty()
                ? "All " + catalogue.size() + " books, A to Z"
                : shown.size() + (shown.size() == 1 ? " match" : " matches") + " for \"" + query + "\"";
        addBanner(list, summary, 0);

        if (shown.isEmpty()) {
            addMessage(list, "No matching books found. Try a different title, author or ISBN.");
            return;
        }
        for (Book book : shown) {
            addBookCard(book);
        }
    }

    /** One book: title, author, details, availability and the Request button. */
    private void addBookCard(Book book) {
        LinearLayout box = addCard(list);

        addLine(box, makeText(orDash(book.title), 17, true, 0), 0);
        addLine(box, makeText("by " + orDash(book.author), 14, false, 0), 2);

        StringBuilder details = new StringBuilder("ISBN ").append(orDash(book.isbn))
                .append("  ·  Shelf ").append(orDash(book.shelfLocation));
        if (book.category != null && !book.category.trim().isEmpty()) {
            details.append("  ·  ").append(book.category.trim());
        }
        addLine(box, makeText(details.toString(), 13, false, 0), 2);

        boolean inStock = book.copiesAvailable > 0;
        addLine(box, makeText(inStock
                        ? book.copiesAvailable + (book.copiesAvailable == 1 ? " copy" : " copies") + " available"
                        : "No copies available right now (you can still request it)",
                13, true, inStock ? COLOR_GOOD : COLOR_NOTE), 4);

        MaterialButton request = new MaterialButton(requireContext());
        request.setText("Request this book");
        request.setOnClickListener(v -> requestBook(book));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(WRAP, WRAP);
        params.topMargin = dp(8);
        params.gravity = Gravity.END;
        box.addView(request, params);
    }

    /**
     * LESSON: this screen does not know how to save a request (the Activity does, through the
     * phone's database). It only SAYS which book was chosen, using the Fragment Result API.
     * The Activity is listening for REQUEST_KEY and opens the pre-filled form.
     */
    private void requestBook(Book book) {
        Bundle result = new Bundle();
        result.putString(RESULT_ISBN, book.isbn == null ? "" : book.isbn);
        result.putString(RESULT_TITLE, book.title == null ? "" : book.title);
        result.putString(RESULT_AUTHOR, book.author == null ? "" : book.author);
        getParentFragmentManager().setFragmentResult(REQUEST_KEY, result);
    }

    // ================================================================ searching

    /** Returns the whole catalogue when the query is empty, otherwise only the matches. */
    private List<Book> filter(String query) {
        if (query.isEmpty()) {
            return new ArrayList<>(catalogue);
        }
        String lowered = query.toLowerCase(Locale.ROOT);
        // "978-0-262-04630-5" and "9780262046305" should find the same book
        String isbnQuery = BorrowRequestDialogFragment.normalizeIsbn(query);

        List<Book> matches = new ArrayList<>();
        for (Book book : catalogue) {
            boolean hit = contains(book.title, lowered)
                    || contains(book.author, lowered)
                    || (book.isbn != null && !isbnQuery.isEmpty()
                    && BorrowRequestDialogFragment.normalizeIsbn(book.isbn).contains(isbnQuery));
            if (hit) {
                matches.add(book);
            }
        }
        return matches;
    }

    // >>> PURE-HELPERS-BEGIN  (plain Java: no Android code, so they can be unit-tested)

    private static boolean contains(String text, String loweredQuery) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(loweredQuery);
    }

    /** A to Z ignoring capitals. A book with no title goes to the end of the list. */
    public static int compareTitles(String a, String b) {
        boolean aEmpty = a == null || a.trim().isEmpty();
        boolean bEmpty = b == null || b.trim().isEmpty();
        if (aEmpty && bEmpty) return 0;
        if (aEmpty) return 1;
        if (bEmpty) return -1;
        return a.trim().compareToIgnoreCase(b.trim());
    }

    /** Shows "-" instead of the word "null" when a field is missing. */
    public static String orDash(String value) {
        return (value == null || value.trim().isEmpty()) ? "-" : value.trim();
    }

    // <<< PURE-HELPERS-END
}