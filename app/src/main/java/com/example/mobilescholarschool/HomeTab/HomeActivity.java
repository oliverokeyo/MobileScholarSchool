package com.example.mobilescholarschool.HomeTab;

import android.os.Bundle;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.mobilescholarschool.Components.AuthGuard;
import com.example.mobilescholarschool.Components.BottomNavHelper;
import com.example.mobilescholarschool.LibraryTab.BorrowedBooksDialogFragment;
import com.example.mobilescholarschool.R;
import com.google.android.material.card.MaterialCardView;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public class HomeActivity extends AppCompatActivity {

    private static final String TAG = "HomeActivity";

    private TextView txtViewWelcome, txtViewStudentID;

    // The LinearLayout that holds all four cards (needed so the animation covers the whole grid)
    private ViewGroup cardsContainer;

    private String profileName;
    private String studentId;

    // Remembers live connection to Firestore so we can switch it off in onStop
    private ListenerRegistration profileListener;

    // ---- the four cards: each has a short headline (always visible) and details (when expanded) ----
    private TextView txtViewBalance, txtViewFeeDetails;
    private TextView txtViewLibraryUpdates, txtViewLibraryDetails;
    private TextView txtViewBookedStatus, txtViewHostelDetails;
    private TextView textViewUnitStatus, txtViewUnitDetails;

    // Live connections feeding the cards, so onStop can switch them all off
    private final List<ListenerRegistration> cardListeners = new ArrayList<>();

    // Hostel card: the student's latest booking, plus the warden looked up from the hostel itself
    private String hostelStatus = "";                 // "", "pending", "approved" or "rejected"
    private String hostelName, hostelRoom, hostelId;
    private Long hostelCheckInMillis;
    private final Map<String, String> wardenByHostel = new HashMap<>();

    // Units card needs TWO sources (the student's registrations and the deadline), so we keep both
    private final List<UnitReg> unitRegs = new ArrayList<>();
    private boolean unitsLoaded = false;
    private Long registrationDeadlineMillis;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_home);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        txtViewWelcome = findViewById(R.id.txtViewWelcome);
        txtViewStudentID = findViewById(R.id.txtViewStudentID);

        // Reusable bottom bar: tell it which tab this screen is
        BottomNavHelper.setup(this, R.id.nav_home);

        cardsContainer = findViewById(R.id.cardsContainer);
        setupExpandableCard(R.id.cardFees, R.id.txtViewFeeDetails);
        setupExpandableCard(R.id.cardLibrary, R.id.txtViewLibraryDetails);
        setupExpandableCard(R.id.cardHostel, R.id.txtViewHostelDetails);
        setupExpandableCard(R.id.cardUnits, R.id.txtViewUnitDetails);

        // NEW: the layout still contains placeholders like "[Balance]". We replace them with
        // "Loading…" here, before the first frame is drawn, so a student never sees them.
        txtViewBalance = findViewById(R.id.txtViewBalance);
        txtViewFeeDetails = findViewById(R.id.txtViewFeeDetails);
        txtViewLibraryUpdates = findViewById(R.id.txtViewLibraryUpdates);
        txtViewLibraryDetails = findViewById(R.id.txtViewLibraryDetails);
        txtViewBookedStatus = findViewById(R.id.txtViewBookedStatus);
        txtViewHostelDetails = findViewById(R.id.txtViewHostelDetails);
        textViewUnitStatus = findViewById(R.id.textViewUnitStatus);
        txtViewUnitDetails = findViewById(R.id.txtViewUnitDetails);
        showLoadingCards();

        ImageButton btnNotifications = findViewById(R.id.imgNotifications);
        btnNotifications.setOnClickListener(v ->
                Toast.makeText(this, R.string.notifications_none, Toast.LENGTH_SHORT).show());
    }

    /**
     * CHANGED: the sign-in check and the Firestore connection now live in onStart.
     * onStart runs every time the screen becomes visible, and onStop (below) switches the
     * connection off when it is hidden. The pair keeps the listener from leaking.
     */
    @Override
    protected void onStart() {
        super.onStart();

        // Safety net: if nobody is signed in, AuthGuard sends them to the login screen
        if (AuthGuard.redirectIfSignedOut(this)) {
            return;
        }

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        showWelcome(user);
        listenToProfile(user);
        listenToCards(user.getUid());   // NEW
    }

    /** NEW: stop listening while the screen is hidden (saves battery and data). */
    @Override
    protected void onStop() {
        super.onStop();
        if (profileListener != null) {
            profileListener.remove();
            profileListener = null;
        }
        stopCardListeners();   // NEW
    }

    /** refreshes the greeting, because "Good morning" can change to
     * "Good afternoon" while the app sits in the background.
     */
    @Override
    protected void onResume() {
        super.onResume();
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) {
            showWelcome(user);
        }
    }

    /**
     * This is a LIVE connection: Firestore
     * calls the lambda once with the current data, then again every time the document changes.
     * So when a student edits their registration number on the Profile screen, Home updates itself.
     */
    private void listenToProfile(FirebaseUser user) {
        // Only show "loading" if we have nothing yet, so a refresh doesn't flash the screen
        if (studentId == null) {
            txtViewStudentID.setText(R.string.student_id_loading);
        }

        profileListener = FirebaseFirestore.getInstance()
                .collection("users")
                .document(user.getUid())
                .addSnapshotListener((doc, error) -> {
                    if (error != null) {
                        // Log the real reason (e.g. PERMISSION_DENIED) so you can find it in Logcat
                        Log.e("HomeActivity", "Profile listener failed", error);
                        // Keep showing old data if we already have some
                        if (studentId == null) {
                            txtViewStudentID.setText(R.string.student_id_unavailable);
                        }
                        return;
                    }

                    if (doc == null || !doc.exists()) {
                        profileName = null;
                        studentId = null;
                        showWelcome(user);
                        showStudentId();
                        return;
                    }

                    // "registrationNo" and "fullName" must match the names SignUpActivity saves
                    profileName = doc.getString("fullName");
                    studentId = doc.getString("registrationNo");

                    showWelcome(user);
                    showStudentId();
                });
    }

    /**
     * Makes one card tappable: first tap grows it and reveals the hidden details,
     * second tap shrinks it back and hides them again.
     */
    private void setupExpandableCard(int cardId, int detailsId) {
        MaterialCardView card = findViewById(cardId);
        View details = findViewById(detailsId);

        // Convert 8dp to pixels so the "lift" looks the same on every screen density
        float lift = 8 * getResources().getDisplayMetrics().density;

        card.setOnClickListener(v -> {
            boolean expanding = details.getVisibility() == View.GONE;

            // 1. Tell Android to animate whatever layout change happens next
            TransitionManager.beginDelayedTransition(cardsContainer, new AutoTransition());

            // 2. The layout change: show or hide the details
            details.setVisibility(expanding ? View.VISIBLE : View.GONE);

            // 3. The scale animation (a visual effect on top of the layout change)
            float scale = expanding ? 1.05f : 1f;
            card.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .translationZ(expanding ? lift : 0f) // draws the card above its neighbour
                    .setDuration(200)
                    .start();
        });
    }

    // ================================================================ the four cards (NEW)

    private void showLoadingCards() {
        txtViewBalance.setText("Loading…");
        txtViewFeeDetails.setText("Loading…");
        txtViewLibraryUpdates.setText("Loading…");
        txtViewLibraryDetails.setText("Loading…");
        txtViewBookedStatus.setText("Loading…");
        txtViewHostelDetails.setText("Loading…");
        textViewUnitStatus.setText("Loading…");
        txtViewUnitDetails.setText("Loading…");
    }

    /** A card whose data could not be read (for example the rules are not published yet). */
    private void showUnavailable(TextView headline, TextView details) {
        headline.setText("Unavailable");
        details.setText("Could not load this. Check your connection.");
    }

    /**
     * Starts one live connection per card. Each runs once with the current data and again every
     * time that data changes, so a card updates by itself (for example when the librarian marks a
     * book returned). EVERY query asks for this student's own records only (whereEqualTo "studentUid"):
     * rules are not filters, so a query for everyone's records would be rejected outright.
     */
    private void listenToCards(String uid) {
        stopCardListeners();   // never keep two sets of connections open
        FirebaseFirestore db = FirebaseFirestore.getInstance();

        // ---- FEES: ONE document, named after the student's UID (feeAccounts/{uid}) ----
        cardListeners.add(db.collection("feeAccounts").document(uid)
                .addSnapshotListener((doc, error) -> {
                    if (error != null) {
                        Log.e(TAG, "Fee listener failed", error);
                        showUnavailable(txtViewBalance, txtViewFeeDetails);
                        return;
                    }
                    try {
                        Long billedValue = (doc != null && doc.exists()) ? doc.getLong("totalBilled") : null;
                        boolean hasRecord = billedValue != null;
                        long billed = hasRecord ? billedValue : 0;
                        long paid = 0;
                        String dueText = "Not set";
                        boolean pastDue = false;

                        if (hasRecord) {
                            Long paidValue = doc.getLong("totalPaid");
                            paid = paidValue == null ? 0 : paidValue;
                            Timestamp due = doc.getTimestamp("dueDate");
                            if (due != null) {
                                dueText = formatDate(due.toDate().getTime());
                                pastDue = BorrowedBooksDialogFragment.daysUntil(
                                        due.toDate().getTime(), System.currentTimeMillis()) < 0;
                            }
                        }
                        // The balance is never stored: it is billed minus paid, worked out here,
                        // so it can never be out of date.
                        txtViewBalance.setText(feeHeadline(hasRecord, billed, paid));
                        txtViewFeeDetails.setText(feeDetails(hasRecord, billed, paid, dueText, pastDue));
                    } catch (RuntimeException e) {
                        Log.e(TAG, "Fee document has a wrongly typed field", e);
                        showUnavailable(txtViewBalance, txtViewFeeDetails);
                    }
                }));

        // ---- LIBRARY: this student's loans ----
        cardListeners.add(db.collection("loans").whereEqualTo("studentUid", uid)
                .addSnapshotListener((snap, error) -> {
                    if (error != null || snap == null) {
                        Log.e(TAG, "Loans listener failed", error);
                        showUnavailable(txtViewLibraryUpdates, txtViewLibraryDetails);
                        return;
                    }
                    int borrowed = 0;
                    int overdue = 0;
                    long fines = 0;
                    String overdueTitle = null, nextTitle = null;
                    Timestamp overdueDue = null, nextDue = null;   // the EARLIEST of each kind

                    for (QueryDocumentSnapshot doc : snap) {
                        try {
                            // "fineAmount" is the fine still owed on this loan (the librarian clears it
                            // when paid). It counts even for a book that has been returned.
                            Long fine = doc.getLong("fineAmount");
                            if (fine != null && fine > 0) {
                                fines += fine;
                            }

                            if (doc.getTimestamp("returnedAt") != null) {
                                continue;   // returned: no longer borrowed
                            }
                            borrowed++;

                            Timestamp due = doc.getTimestamp("dueDate");
                            String title = BorrowedBooksDialogFragment.headingOf(
                                    doc.getString("bookTitle"), doc.getString("author"), doc.getString("isbn"));

                            // The SAME definition of "overdue" the Library screen uses
                            if (BorrowedBooksDialogFragment.isOverdue(due, null)) {
                                overdue++;
                                if (overdueDue == null || due.compareTo(overdueDue) < 0) {
                                    overdueDue = due;
                                    overdueTitle = title;
                                }
                            } else if (due != null && (nextDue == null || due.compareTo(nextDue) < 0)) {
                                nextDue = due;
                                nextTitle = title;
                            }
                        } catch (RuntimeException e) {
                            Log.e(TAG, "Skipping bad loan document: " + doc.getId(), e);
                        }
                    }

                    txtViewLibraryUpdates.setText(libraryHeadline(borrowed, overdue));
                    txtViewLibraryDetails.setText(libraryDetails(borrowed,
                            overdueDue == null ? null
                                    : libraryOverdueLine(overdueTitle, overdueDue.toDate().getTime()),
                            nextDue == null ? null
                                    : libraryDueSoonLine(nextTitle, nextDue.toDate().getTime()),
                            fines));
                }));

        // ---- HOSTEL: this student's latest booking ----
        cardListeners.add(db.collection("hostelBookings").whereEqualTo("studentUid", uid)
                .addSnapshotListener((snap, error) -> {
                    if (error != null || snap == null) {
                        Log.e(TAG, "Bookings listener failed", error);
                        showUnavailable(txtViewBookedStatus, txtViewHostelDetails);
                        return;
                    }
                    DocumentSnapshot latest = null;
                    for (DocumentSnapshot doc : snap.getDocuments()) {
                        try {
                            if (latest == null
                                    || isNewer(doc.getTimestamp("createdAt"), latest.getTimestamp("createdAt"))) {
                                latest = doc;
                            }
                        } catch (RuntimeException e) {
                            Log.e(TAG, "Skipping bad booking document: " + doc.getId(), e);
                        }
                    }

                    hostelStatus = "";
                    hostelName = hostelRoom = hostelId = null;
                    hostelCheckInMillis = null;
                    if (latest != null) {
                        try {
                            hostelStatus = normalize(latest.getString("status"));
                            hostelName = latest.getString("hostelName");
                            hostelId = latest.getString("hostelId");
                            // "roomNumber" and "checkInDate" are filled in by the admin when approving
                            hostelRoom = latest.getString("roomNumber");
                            Timestamp checkIn = latest.getTimestamp("checkInDate");
                            hostelCheckInMillis = checkIn == null ? null : checkIn.toDate().getTime();
                        } catch (RuntimeException e) {
                            Log.e(TAG, "Booking has a wrongly typed field", e);
                        }
                    }
                    if (hostelId != null && !hostelId.trim().isEmpty()) {
                        fetchWarden(hostelId.trim());
                    }
                    renderHostel();
                }));

        // ---- UNITS (1 of 2): this student's registrations ----
        cardListeners.add(db.collection("unitRegistrations").whereEqualTo("studentUid", uid)
                .addSnapshotListener((snap, error) -> {
                    if (error != null || snap == null) {
                        Log.e(TAG, "Unit registrations listener failed", error);
                        showUnavailable(textViewUnitStatus, txtViewUnitDetails);
                        return;
                    }
                    unitRegs.clear();
                    for (QueryDocumentSnapshot doc : snap) {
                        try {
                            unitRegs.add(new UnitReg(doc.getString("semester"),
                                    doc.getString("unitCode"), doc.getString("status")));
                        } catch (RuntimeException e) {
                            Log.e(TAG, "Skipping bad unit registration: " + doc.getId(), e);
                        }
                    }
                    unitsLoaded = true;
                    renderUnits();
                }));

        // ---- UNITS (2 of 2): the registration deadline, the same for every student ----
        cardListeners.add(db.collection("settings").document("registration")
                .addSnapshotListener((doc, error) -> {
                    if (error != null) {
                        // Not fatal: the card still works, the deadline just shows "Not set"
                        Log.e(TAG, "Registration settings listener failed", error);
                        registrationDeadlineMillis = null;
                    } else {
                        try {
                            Timestamp deadline = (doc != null && doc.exists()) ? doc.getTimestamp("deadline") : null;
                            registrationDeadlineMillis = deadline == null ? null : deadline.toDate().getTime();
                        } catch (RuntimeException e) {
                            Log.e(TAG, "deadline has the wrong type", e);
                            registrationDeadlineMillis = null;
                        }
                    }
                    renderUnits();
                }));
    }

    private void stopCardListeners() {
        for (ListenerRegistration listener : cardListeners) {
            listener.remove();
        }
        cardListeners.clear();
    }

    /** The warden belongs to the HOSTEL, not the booking, so we read it from hostels/{id} once. */
    private void fetchWarden(String id) {
        if (wardenByHostel.containsKey(id)) {
            return;   // already asked
        }
        wardenByHostel.put(id, "");   // mark as asked, so a burst of updates doesn't ask many times
        FirebaseFirestore.getInstance().collection("hostels").document(id).get()
                .addOnSuccessListener(this, doc -> {
                    String warden = null;
                    try {
                        warden = doc.exists() ? doc.getString("wardenName") : null;
                    } catch (RuntimeException e) {
                        Log.e(TAG, "wardenName has the wrong type", e);
                    }
                    wardenByHostel.put(id, warden == null ? "" : warden.trim());
                    renderHostel();
                })
                .addOnFailureListener(this, e -> {
                    Log.e(TAG, "Could not read the hostel", e);
                    wardenByHostel.remove(id);   // let the next update try again
                });
    }

    private void renderHostel() {
        String warden = hostelId == null ? "" : wardenByHostel.get(hostelId.trim());
        String checkIn = hostelCheckInMillis == null ? "" : formatDate(hostelCheckInMillis);
        txtViewBookedStatus.setText(hostelHeadline(hostelStatus));
        txtViewHostelDetails.setText(hostelDetails(hostelStatus, hostelName, hostelRoom, checkIn,
                warden == null ? "" : warden));
    }

    private void renderUnits() {
        if (!unitsLoaded) {
            return;   // the registrations have not arrived yet: keep showing "Loading…"
        }
        UnitSummary summary = summarizeUnits(unitRegs);
        String deadline = registrationDeadlineMillis == null
                ? "Not set" : formatDate(registrationDeadlineMillis);
        textViewUnitStatus.setText(unitsHeadline(summary.registered, summary.pending));
        txtViewUnitDetails.setText(unitsDetails(summary.semester, summary.registered,
                deadline, summary.pendingCodes));
    }

    /** A booking still waiting to reach the server has no createdAt yet, so it counts as the newest. */
    private static boolean isNewer(@Nullable Timestamp a, @Nullable Timestamp b) {
        if (a == null) return b != null;
        if (b == null) return false;
        return a.compareTo(b) > 0;
    }

    // >>> PURE-HELPERS-BEGIN  (plain Java: no Android code, so they can be unit-tested)

    /** Lower-case and trimmed, so "Approved " and "approved" mean the same. */
    public static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** 12000 becomes "12,000". Always commas, whatever the phone's language. */
    public static String formatKes(long amount) {
        return String.format(Locale.US, "%,d", amount);
    }

    /** 12000 becomes "Ksh. 12,000". */
    public static String ksh(long amount) {
        return "Ksh. " + formatKes(amount);
    }

    /** A saved date shown in the phone's local time. */
    public static String formatDate(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date(millis));
    }

    // ---------------------------------------------------------------- Fees

    /** The big number under "Outstanding Fee". */
    public static String feeHeadline(boolean hasRecord, long billed, long paid) {
        if (!hasRecord) {
            return "No fee record yet";
        }
        long balance = billed - paid;
        if (balance > 0) return ksh(balance);
        if (balance == 0) return "Ksh. 0 (fully paid)";
        return "Ksh. 0 (credit of " + ksh(-balance) + ")";   // paid more than billed
    }

    public static String feeDetails(boolean hasRecord, long billed, long paid,
                                    String dueText, boolean pastDue) {
        if (!hasRecord) {
            return "Your fee statement has not been issued yet.\nPlease check with the Finance office.";
        }
        long balance = billed - paid;
        StringBuilder out = new StringBuilder();
        out.append("Total billed: ").append(ksh(billed)).append("\n");
        out.append("Paid so far: ").append(ksh(paid)).append("\n");
        out.append("Due date: ").append(dueText);
        if (balance > 0 && pastDue) {
            out.append("  (overdue)");
        }
        if (balance > 0) {
            out.append("\nTap Finance to pay via M-Pesa.");   // nothing to pay when the balance is cleared
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- Library

    public static String libraryHeadline(int borrowed, int overdue) {
        if (borrowed == 0) {
            return "No books borrowed";
        }
        String text = borrowed + " borrowed";
        if (overdue > 0) {
            text += "  ·  " + overdue + " overdue";
        }
        return text;
    }

    public static String libraryOverdueLine(String title, long dueMillis) {
        return "Overdue: " + title + " (was due " + formatDate(dueMillis) + ")";
    }

    public static String libraryDueSoonLine(String title, long dueMillis) {
        return "Due soon: " + title + " on " + formatDate(dueMillis);
    }

    /** @param overdueLine / dueSoonLine null when there is no such book */
    public static String libraryDetails(int borrowed, String overdueLine, String dueSoonLine, long fines) {
        StringBuilder out = new StringBuilder("Books borrowed: ").append(borrowed);
        if (overdueLine != null) {
            out.append("\n").append(overdueLine);
        }
        if (dueSoonLine != null) {
            out.append("\n").append(dueSoonLine);
        }
        if (overdueLine == null && dueSoonLine == null) {
            out.append("\nDue soon: Nothing due");
        }
        out.append("\nFines: ").append(ksh(fines));
        return out.toString();
    }

    // ---------------------------------------------------------------- Hostel

    /** Booked / Pending approval / Request rejected / Unbooked. */
    public static String hostelHeadline(String status) {
        switch (normalize(status)) {
            case "approved": return "Booked";
            case "pending":  return "Pending approval";
            case "rejected": return "Request rejected";
            default:         return "Unbooked";
        }
    }

    public static String hostelDetails(String status, String hostelName, String roomNumber,
                                       String checkInText, String warden) {
        String state = normalize(status);
        String name = isBlank(hostelName) ? "-" : hostelName.trim();

        if (state.equals("approved") || state.equals("pending")) {
            StringBuilder out = new StringBuilder();
            out.append("Hostel: ").append(name);
            out.append("\nRoom: ").append(isBlank(roomNumber) ? "Not assigned yet" : roomNumber.trim());
            out.append("\nCheck-in: ").append(isBlank(checkInText) ? "To be confirmed" : checkInText.trim());
            out.append("\nWarden: ").append(isBlank(warden) ? "-" : warden.trim());
            if (state.equals("pending")) {
                out.append("\nYour request is waiting for approval.");
            }
            return out.toString();
        }
        if (state.equals("rejected")) {
            return "Your last request for " + name + " was rejected.\n"
                    + "Open the Hostel tab to choose another room.";
        }
        return "You have no hostel booking yet.\nOpen the Hostel tab to find and book a room.";
    }

    // ---------------------------------------------------------------- Units

    /** One row of the student's unit registrations. */
    public static class UnitReg {
        public final String semester;   // for example "Y1 S1"
        public final String code;       // for example "CS101"
        public final String status;     // "registered" or "pending"

        public UnitReg(String semester, String code, String status) {
            this.semester = semester;
            this.code = code;
            this.status = status;
        }
    }

    public static class UnitSummary {
        public String semester;                                  // null when there are no registrations
        public int registered = 0;
        public int pending = 0;
        public List<String> pendingCodes = new ArrayList<>();
    }

    /**
     * Counts the registrations of the student's CURRENT semester, which is the latest one in the data
     * ("Y1 S1" is before "Y1 S2", which is before "Y2 S1"). Older semesters stay as history and are
     * not counted. Anything that is neither "registered" nor "pending" (for example "rejected") is
     * not counted either.
     */
    public static UnitSummary summarizeUnits(List<UnitReg> registrations) {
        UnitSummary out = new UnitSummary();
        for (UnitReg reg : registrations) {
            String semester = reg.semester == null ? "" : reg.semester.trim();
            if (!semester.isEmpty() && (out.semester == null || semester.compareTo(out.semester) > 0)) {
                out.semester = semester;
            }
        }
        if (out.semester == null) {
            return out;
        }
        for (UnitReg reg : registrations) {
            String semester = reg.semester == null ? "" : reg.semester.trim();
            if (!semester.equals(out.semester)) {
                continue;
            }
            String status = normalize(reg.status);
            if (status.equals("registered")) {
                out.registered++;
            } else if (status.equals("pending")) {
                out.pending++;
                out.pendingCodes.add(isBlank(reg.code) ? "?" : reg.code.trim());
            }
        }
        Collections.sort(out.pendingCodes);
        return out;
    }

    /** Registered / Pending / Unregistered. */
    public static String unitsHeadline(int registered, int pending) {
        if (registered + pending == 0) return "Unregistered";
        return pending > 0 ? "Pending" : "Registered";
    }

    public static String unitsDetails(String semester, int registered, String deadlineText,
                                      List<String> pendingCodes) {
        StringBuilder out = new StringBuilder();
        out.append("Semester: ").append(isBlank(semester) ? "-" : semester.trim());
        out.append("\nUnits registered: ").append(registered);
        out.append("\nDeadline: ").append(deadlineText);
        out.append("\nPending approval: ");
        if (pendingCodes == null || pendingCodes.isEmpty()) {
            out.append("None");
        } else {
            for (int i = 0; i < pendingCodes.size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append(pendingCodes.get(i));
            }
        }
        return out.toString();
    }

    // <<< PURE-HELPERS-END

    private void showWelcome(FirebaseUser user) {
        txtViewWelcome.setText(getString(R.string.welcome_format,
                getGreeting(), getUserName(user)));
    }

    private void showStudentId() {
        if (studentId == null || studentId.trim().isEmpty()) {
            txtViewStudentID.setText(R.string.student_id_not_available);
        } else {
            txtViewStudentID.setText(getString(R.string.student_id_format, studentId));
        }
    }

    private String getGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);

        if (hour >= 5 && hour < 12) {
            return getString(R.string.greeting_morning);
        } else if (hour >= 12 && hour < 17) {
            return getString(R.string.greeting_afternoon);
        } else {
            return getString(R.string.greeting_evening);
        }
    }

    private String getUserName(FirebaseUser user) {
        // 1. Full name saved at sign-up (Firestore)
        if (profileName != null && !profileName.trim().isEmpty()) {
            return profileName;
        }
        // 2. Firebase display name, if one was ever set
        String displayName = user.getDisplayName();
        if (displayName != null && !displayName.trim().isEmpty()) {
            return displayName;
        }
        // 3. Part of the email before the @
        String email = user.getEmail();
        if (email != null && email.contains("@")) {
            return email.substring(0, email.indexOf("@"));
        }
        return getString(R.string.default_user_name);
    }
}