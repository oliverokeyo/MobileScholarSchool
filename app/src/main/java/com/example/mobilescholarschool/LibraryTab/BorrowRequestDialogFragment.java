package com.example.mobilescholarschool.LibraryTab;

import android.app.Dialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;

import com.google.android.material.datepicker.CalendarConstraints;
import com.google.android.material.datepicker.CompositeDateValidator;
import com.google.android.material.datepicker.DateValidatorPointBackward;
import com.google.android.material.datepicker.DateValidatorPointForward;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The "Request a Book" form: ISBN, title, author, borrowing date, return date and the
 * student's ID number. EVERYTHING lives in this one class: the form, the validation, the
 * ISBN checksum and the date helpers. No XML layout and no strings file are needed.
 *
 * Book details: the student must fill in AT LEAST ONE of ISBN, title or author. None of the
 * three is required on its own, but anything typed is still checked (a typed ISBN must be valid).
 *
 * It only COLLECTS and CHECKS the input. It does not talk to Firestore. When the form is
 * valid it sends the answers back to the Activity with the Fragment Result API and closes.
 *
 * Open it with:
 *   BorrowRequestDialogFragment.newInstance(studentIdNumber, null, null, null)
 *           .show(getSupportFragmentManager(), BorrowRequestDialogFragment.TAG);
 */
public class BorrowRequestDialogFragment extends DialogFragment {

    public static final String TAG = "BorrowRequestDialog";

    // ---- what the Activity listens for ---------------------------------------------------
    public static final String REQUEST_KEY = "borrow_request";
    public static final String RESULT_STUDENT_ID = "result_student_id";
    public static final String RESULT_ISBN = "result_isbn";
    public static final String RESULT_TITLE = "result_title";
    public static final String RESULT_AUTHOR = "result_author";
    public static final String RESULT_BORROW_MILLIS = "result_borrow_millis";   // local midnight
    public static final String RESULT_RETURN_MILLIS = "result_return_millis";   // local midnight

    // ---- what the Activity hands IN (arguments survive rotation and process death) ----
    private static final String ARG_STUDENT_ID = "arg_student_id";
    private static final String ARG_ISBN = "arg_isbn";
    private static final String ARG_TITLE = "arg_title";
    private static final String ARG_AUTHOR = "arg_author";

    // ---- saved across rotation ------------------------------------------------------------
    private static final String STATE_BORROW_UTC = "state_borrow_utc";
    private static final String STATE_RETURN_UTC = "state_return_utc";

    private static final String TAG_BORROW_PICKER = "borrowDatePicker";
    private static final String TAG_RETURN_PICKER = "returnDatePicker";

    // ---- limits (the security rules repeat these, so keep them in step) -------------------
    private static final int MAX_LOAN_DAYS = 30;
    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_AUTHOR_LENGTH = 100;
    private static final int MAX_ISBN_INPUT = 20;   // 13 digits plus hyphens or spaces

    // Fixed IDs for the text boxes. Android saves typed text by view ID, so these must be the
    // SAME every time the dialog is built (View.generateViewId() would change them and the text
    // would be lost on rotation). Small numbers can never collide with R.id values (0x7f......).
    private static final int ID_ISBN = 4101;
    private static final int ID_TITLE = 4102;
    private static final int ID_AUTHOR = 4103;
    private static final int ID_BORROW = 4104;
    private static final int ID_RETURN = 4105;
    private static final int ID_STUDENT = 4106;

    // The date picker's raw values (midnight UTC). Null until the student picks a date.
    private Long borrowUtc;
    private Long returnUtc;

    private TextInputLayout tilIsbn, tilTitle, tilAuthor, tilBorrow, tilReturn, tilStudentNo;
    private EditText edtIsbn, edtTitle, edtAuthor, edtBorrow, edtReturn, edtStudentNo;

    /**
     * A fragment must have an EMPTY constructor (Android recreates it by itself after a
     * rotation), so anything it needs travels in a Bundle of arguments instead.
     *
     * @param studentIdNumber the registration number from the student's profile
     * @param isbn / title / author  optional: pre-fill the form when a book was picked from search
     */
    public static BorrowRequestDialogFragment newInstance(String studentIdNumber,
                                                          @Nullable String isbn,
                                                          @Nullable String title,
                                                          @Nullable String author) {
        Bundle args = new Bundle();
        args.putString(ARG_STUDENT_ID, studentIdNumber);
        args.putString(ARG_ISBN, isbn);
        args.putString(ARG_TITLE, title);
        args.putString(ARG_AUTHOR, author);

        BorrowRequestDialogFragment fragment = new BorrowRequestDialogFragment();
        fragment.setArguments(args);
        return fragment;
    }

    // ================================================================ building the form

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        LinearLayout form = new LinearLayout(requireContext());
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), dp(8));

        // A short note so the student knows the rule before they hit Submit
        TextView note = new TextView(requireContext());
        note.setText("Fill in at least one of: ISBN, book title or author.");
        form.addView(note, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tilIsbn = addField(form, "ISBN (10 or 13 digits)", ID_ISBN,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        tilTitle = addField(form, "Book title", ID_TITLE,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        tilAuthor = addField(form, "Author", ID_AUTHOR,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME
                        | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        tilBorrow = addField(form, "Borrowing date", ID_BORROW, InputType.TYPE_NULL);
        tilReturn = addField(form, "Return date", ID_RETURN, InputType.TYPE_NULL);
        tilStudentNo = addField(form, "Student ID number", ID_STUDENT, InputType.TYPE_CLASS_TEXT);

        edtIsbn = tilIsbn.getEditText();
        edtTitle = tilTitle.getEditText();
        edtAuthor = tilAuthor.getEditText();
        edtBorrow = tilBorrow.getEditText();
        edtReturn = tilReturn.getEditText();
        edtStudentNo = tilStudentNo.getEditText();

        // Cap how much can be typed (the security rules enforce the same limits)
        edtIsbn.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_ISBN_INPUT)});
        edtTitle.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_TITLE_LENGTH)});
        edtAuthor.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_AUTHOR_LENGTH)});

        // The date boxes are not typed into: tapping them opens a calendar.
        for (EditText dateBox : new EditText[]{edtBorrow, edtReturn}) {
            dateBox.setFocusable(false);
            dateBox.setClickable(true);
            dateBox.setCursorVisible(false);
        }
        edtBorrow.setOnClickListener(v -> pickBorrowDate());
        edtReturn.setOnClickListener(v -> pickReturnDate());

        // The student number comes from the profile and is read-only: the security rules
        // compare it with the saved profile, so letting the student type it would only
        // invite mismatches.
        Bundle args = requireArguments();
        edtStudentNo.setText(args.getString(ARG_STUDENT_ID, ""));
        edtStudentNo.setEnabled(false);

        if (savedInstanceState == null) {
            // First time only: pre-fill from the book chosen in the search results
            edtIsbn.setText(args.getString(ARG_ISBN, ""));
            edtTitle.setText(args.getString(ARG_TITLE, ""));
            edtAuthor.setText(args.getString(ARG_AUTHOR, ""));
        } else {
            // After a rotation the text boxes restore themselves, but our two date VALUES
            // are plain Java fields and would be lost, so we restore them here.
            if (savedInstanceState.containsKey(STATE_BORROW_UTC)) {
                borrowUtc = savedInstanceState.getLong(STATE_BORROW_UTC);
            }
            if (savedInstanceState.containsKey(STATE_RETURN_UTC)) {
                returnUtc = savedInstanceState.getLong(STATE_RETURN_UTC);
            }
            reattachPickerListeners();
        }

        // A tap outside the dialog would throw away everything the student typed.
        setCancelable(false);

        // The ScrollView keeps the Submit button reachable on small screens with the keyboard open.
        ScrollView scroll = new ScrollView(requireContext());
        scroll.addView(form);

        return new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Request a book")
                .setView(scroll)
                .setNegativeButton("Cancel", null)   // closes the dialog by itself
                // null on purpose: a listener passed here would close the dialog on EVERY tap,
                // even when the input is invalid. We attach our own in onStart().
                .setPositiveButton("Submit request", null)
                .create();
    }

    /** Creates one outlined text box (with room for a red error message) and adds it to the form. */
    private TextInputLayout addField(LinearLayout parent, String hint, int editTextId, int inputType) {
        TextInputLayout til = new TextInputLayout(requireContext(), null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        til.setHint(hint);

        // Built from the layout's OWN context so it picks up the right theme overlay
        TextInputEditText edit = new TextInputEditText(til.getContext());
        edit.setId(editTextId);
        edit.setInputType(inputType);
        til.addView(edit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        parent.addView(til, params);
        return til;
    }

    /** Converts dp to pixels. The original used raw pixels (48), which look different on every phone. */
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** The dialog's buttons only exist once the dialog is showing, so we attach here. */
    @Override
    public void onStart() {
        super.onStart();
        AlertDialog dialog = (AlertDialog) getDialog();
        if (dialog != null) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> submit());
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (borrowUtc != null) {
            outState.putLong(STATE_BORROW_UTC, borrowUtc);
        }
        if (returnUtc != null) {
            outState.putLong(STATE_RETURN_UTC, returnUtc);
        }
    }

    // ================================================================ submit

    private void submit() {
        String isbn = normalizeIsbn(text(edtIsbn));   // no spaces or hyphens, upper-case X
        String title = text(edtTitle).trim();
        String author = text(edtAuthor).trim();
        String studentId = text(edtStudentNo).trim();

        // One message per box. A null message means "this box is fine".
        String isbnError;
        String titleError;
        String authorError;
        if (!hasAtLeastOne(isbn, title, author)) {
            // None of the three is required ON ITS OWN, but they cannot ALL be empty.
            // The same message goes under each box so the student sees any of them will do.
            String message = "Fill in at least one of the three";
            isbnError = message;
            titleError = message;
            authorError = message;
        } else {
            // Each box is only checked if something was typed in it.
            isbnError = isbn.isEmpty() ? null : checkIsbn(isbn);
            titleError = checkTooLong(title, "Book title", MAX_TITLE_LENGTH);
            authorError = checkTooLong(author, "Author", MAX_AUTHOR_LENGTH);
        }
        String borrowError = checkBorrowDate();
        String returnError = checkReturnDate();
        String studentError = studentId.isEmpty() ? "Student ID number is required" : null;

        // setError(null) clears a message that was fixed, so valid boxes turn normal again.
        tilIsbn.setError(isbnError);
        tilTitle.setError(titleError);
        tilAuthor.setError(authorError);
        tilBorrow.setError(borrowError);
        tilReturn.setError(returnError);
        tilStudentNo.setError(studentError);

        if (isbnError != null || titleError != null || authorError != null
                || borrowError != null || returnError != null || studentError != null) {
            return;   // the dialog stays open so the student can fix the problem
        }

        Bundle answer = new Bundle();
        answer.putString(RESULT_STUDENT_ID, studentId);
        // Empty text (not null) for the boxes the student left blank, so the keys always exist
        answer.putString(RESULT_ISBN, isbn);
        answer.putString(RESULT_TITLE, title);
        answer.putString(RESULT_AUTHOR, author);
        // The picker gives midnight UTC; we pass midnight LOCAL time so the day never shifts.
        answer.putLong(RESULT_BORROW_MILLIS, utcMidnightToLocalMidnight(borrowUtc));
        answer.putLong(RESULT_RETURN_MILLIS, utcMidnightToLocalMidnight(returnUtc));

        // Hand the finished form to whoever is listening for REQUEST_KEY (the Activity).
        getParentFragmentManager().setFragmentResult(REQUEST_KEY, answer);
        dismiss();
    }

    // ================================================================ the checks

    private static String checkIsbn(String normalizedIsbn) {
        if (normalizedIsbn.isEmpty()) {
            return "ISBN is required";
        }
        if (!isValidIsbn(normalizedIsbn)) {
            return "Enter a valid 10 or 13 digit ISBN";
        }
        return null;
    }

    /** Empty is fine here (the "at least one" rule handles that); only an overlong value is wrong. */
    private static String checkTooLong(String value, String label, int maxLength) {
        if (value != null && value.length() > maxLength) {
            return label + " is too long";
        }
        return null;
    }

    private String checkBorrowDate() {
        if (borrowUtc == null) {
            return "Choose a borrowing date";
        }
        if (borrowUtc < MaterialDatePicker.todayInUtcMilliseconds()) {
            return "Borrowing date cannot be in the past";
        }
        return null;
    }

    private String checkReturnDate() {
        if (returnUtc == null) {
            return "Choose a return date";
        }
        if (borrowUtc != null) {
            if (returnUtc <= borrowUtc) {
                return "Return date must be after the borrowing date";
            }
            if ((returnUtc - borrowUtc) / DAY_MS > MAX_LOAN_DAYS) {
                return "Books can be borrowed for at most " + MAX_LOAN_DAYS + " days";
            }
        }
        return null;
    }

    // ================================================================ date pickers

    private void pickBorrowDate() {
        long today = MaterialDatePicker.todayInUtcMilliseconds();

        // Only today and later can be tapped.
        CalendarConstraints constraints = new CalendarConstraints.Builder()
                .setStart(today)
                .setValidator(DateValidatorPointForward.from(today))
                .build();

        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("Select borrowing date")
                .setSelection(borrowUtc != null ? borrowUtc : today)
                .setCalendarConstraints(constraints)
                .build();

        picker.addOnPositiveButtonClickListener(this::onBorrowDatePicked);
        picker.show(getChildFragmentManager(), TAG_BORROW_PICKER);
    }

    private void pickReturnDate() {
        if (borrowUtc == null) {
            tilReturn.setError("Choose the borrowing date first");
            return;
        }

        // From the day after borrowing up to MAX_LOAN_DAYS later.
        long min = borrowUtc + DAY_MS;
        long max = borrowUtc + MAX_LOAN_DAYS * DAY_MS;

        List<CalendarConstraints.DateValidator> validators = new ArrayList<>();
        validators.add(DateValidatorPointForward.from(min));
        validators.add(DateValidatorPointBackward.before(max));

        CalendarConstraints constraints = new CalendarConstraints.Builder()
                .setStart(min)
                .setEnd(max)
                .setValidator(CompositeDateValidator.allOf(validators))   // BOTH rules must pass
                .build();

        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("Select return date")
                .setSelection(returnUtc != null ? returnUtc : min)
                .setCalendarConstraints(constraints)
                .build();

        picker.addOnPositiveButtonClickListener(this::onReturnDatePicked);
        picker.show(getChildFragmentManager(), TAG_RETURN_PICKER);
    }

    private void onBorrowDatePicked(Long selection) {
        borrowUtc = selection;
        edtBorrow.setText(formatUtc(selection));
        tilBorrow.setError(null);

        // A new borrowing date can make the old return date invalid, so clear it.
        if (returnUtc != null && !isReturnDateAllowed(returnUtc)) {
            returnUtc = null;
            edtReturn.setText("");
        }
    }

    private void onReturnDatePicked(Long selection) {
        returnUtc = selection;
        edtReturn.setText(formatUtc(selection));
        tilReturn.setError(null);
    }

    private boolean isReturnDateAllowed(long candidateUtc) {
        long days = (candidateUtc - borrowUtc) / DAY_MS;
        return days >= 1 && days <= MAX_LOAN_DAYS;
    }

    /**
     * After a rotation, a calendar that was open is restored by Android, but its "OK" listener
     * is NOT (a listener is code, and code can't be saved). We find the restored calendar by
     * its tag and plug the listener back in.
     */
    @SuppressWarnings("unchecked")
    private void reattachPickerListeners() {
        Fragment borrowPicker = getChildFragmentManager().findFragmentByTag(TAG_BORROW_PICKER);
        if (borrowPicker instanceof MaterialDatePicker) {
            ((MaterialDatePicker<Long>) borrowPicker)
                    .addOnPositiveButtonClickListener(this::onBorrowDatePicked);
        }
        Fragment returnPicker = getChildFragmentManager().findFragmentByTag(TAG_RETURN_PICKER);
        if (returnPicker instanceof MaterialDatePicker) {
            ((MaterialDatePicker<Long>) returnPicker)
                    .addOnPositiveButtonClickListener(this::onReturnDatePicked);
        }
    }

    /** getText() can be null, so never call toString() on it directly. */
    private static String text(EditText editText) {
        Editable editable = editText.getText();
        return editable == null ? "" : editable.toString();
    }

    // >>> PURE-HELPERS-BEGIN  (plain Java: no Android code, so they can be unit-tested)

    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    /** True when at least one of the three book fields has something in it (spaces don't count). */
    public static boolean hasAtLeastOne(String isbn, String title, String author) {
        return !isBlank(isbn) || !isBlank(title) || !isBlank(author);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** Removes spaces and hyphens and upper-cases the X: "978-0-262-04630-5" becomes "9780262046305". */
    public static String normalizeIsbn(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    /** True for a correct ISBN-10 or ISBN-13, checksum included. Expects a normalized value. */
    public static boolean isValidIsbn(String isbn) {
        if (isbn == null) {
            return false;
        }
        if (isbn.matches("[0-9]{9}[0-9X]")) {
            // ISBN-10: multiply the digits by 10, 9, 8 ... 1 (an X counts as 10); total must divide by 11
            int sum = 0;
            for (int i = 0; i < 10; i++) {
                char c = isbn.charAt(i);
                int value = (c == 'X') ? 10 : (c - '0');
                sum += value * (10 - i);
            }
            return sum % 11 == 0;
        }
        if (isbn.matches("[0-9]{13}")) {
            // ISBN-13: multiply the digits by 1, 3, 1, 3 ... ; total must divide by 10
            int sum = 0;
            for (int i = 0; i < 13; i++) {
                int digit = isbn.charAt(i) - '0';
                sum += (i % 2 == 0) ? digit : digit * 3;
            }
            return sum % 10 == 0;
        }
        return false;
    }

    /** A date-picker value (midnight UTC) shown as the day the student actually picked. */
    public static String formatUtc(long utcMillis) {
        SimpleDateFormat format = new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(utcMillis));
    }

    /** A saved date shown in the phone's local time. Use this in the Activity. */
    public static String formatDate(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date(millis));
    }

    /**
     * Android's date picker returns the chosen day as MIDNIGHT IN UTC, but people think in LOCAL
     * time. This turns "picked day at 00:00 UTC" into "the same day at 00:00 local time", so
     * the day never shifts for the student.
     */
    public static long utcMidnightToLocalMidnight(long utcMillis) {
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.setTimeInMillis(utcMillis);

        Calendar local = Calendar.getInstance();
        local.clear();   // zero every field, including milliseconds
        local.set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH),
                utc.get(Calendar.DAY_OF_MONTH), 0, 0, 0);
        return local.getTimeInMillis();
    }

    // <<< PURE-HELPERS-END
}