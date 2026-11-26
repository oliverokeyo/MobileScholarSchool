package com.example.mobilescholarschool.HostelTab;

import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.example.mobilescholarschool.Components.AuthGuard;
import com.example.mobilescholarschool.Components.BottomNavHelper;
import com.example.mobilescholarschool.Components.SessionUser;                  // NEW
import com.example.mobilescholarschool.Components.SwipeNavigator;
import com.example.mobilescholarschool.Data.AppDatabase;                 // NEW
import com.example.mobilescholarschool.Data.DataChangeNotifier;          // NEW
import com.example.mobilescholarschool.Data.DbExecutor;                  // NEW
import com.example.mobilescholarschool.Data.HostelBookingRepository;     // NEW
import com.example.mobilescholarschool.Data.ProfileRepository;           // NEW
import com.example.mobilescholarschool.Data.SyncState;                   // NEW
import com.example.mobilescholarschool.R;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.List;


/**
 * The Hostel screen. It is now a HOME screen: the student's "My Accommodation" card plus six rows.
 * Each row opens its own full-screen Fragment in R.id.fragmentContainer, and every one of them
 * lets the student BOOK from it:
 *
 *   Find a Hostel      -> HostelSearchFragment        search bar + every hostel
 *   Hostel Location    -> HostelLocationFragment      hostels grouped by where they are
 *   Sharing Preference -> HostelSharingFragment       private and shared rooms
 *   Room Type          -> HostelRoomTypeFragment      bedsitters, singles, one-bedrooms, shared
 *   Budget             -> HostelBudgetFragment        type a maximum rent, get suggestions
 *   Custom Search      -> HostelCustomSearchFragment  radio buttons + budget + facilities
 *
 * LESSON: the old design remembered a choice from each dialog and combined them all behind one
 * "Search hostels" button. Now there is NO shared selection to remember. Each screen is
 * complete on its own, so this Activity is much simpler: it opens screens and saves bookings.
 */
public class HostelActivity extends AppCompatActivity {

    // Drag-to-switch-tab with spring animation. Null until the screen is set up.
    private SwipeNavigator swipeNavigator;

    private static final String TAG = "HostelActivity";

    // The student's registration number; null until the profile has loaded
    private String studentId;

    // Our live connection to the profile, so onStop can switch it off
    private ListenerRegistration profileListener;

    private View fragmentContainer;   // where the full-screen Fragments are placed

    // NEW: the "My Accommodation" card shows the student's latest booking
    private TextView txtBookingStatus;
    private String originalBookingText;
    private ListenerRegistration bookingsListener;

    // NEW: fires when the local database changes (a booking was saved, delivered or refused,
    // or the profile copy changed), so the card and the student ID refresh by themselves
    private final DataChangeNotifier.Listener dataListener = table -> {
        if (AppDatabase.T_HOSTEL_BOOKINGS.equals(table)) {
            loadLocalBookings();
        } else if (AppDatabase.T_PROFILE.equals(table)) {
            loadLocalProfile(true);
        }
    };
    private boolean hasActiveBooking = false;   // true while a booking is pending or approved

    /**
     * Every touch on this screen passes through here BEFORE any view sees it. We ask the swipe
     * navigator first: if the user is dragging sideways it takes over (returns true) and the
     * views underneath never see that gesture. Otherwise everything works exactly as before.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (swipeNavigator != null && !isScreenOpen() && swipeNavigator.onTouchEvent(ev)) {
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
        setContentView(R.layout.activity_hostel);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Highlight the Hostel tab and make the other tabs navigate
        BottomNavHelper.setup(this, R.id.nav_hostel);
        // Must come AFTER BottomNavHelper.setup (it reads what setup leaves behind).
        swipeNavigator = new SwipeNavigator(this, R.id.nav_hostel);

        setUpScreens();
    }

    // The live connection starts when the screen becomes visible...
    @Override
    protected void onStart() {
        super.onStart();
        if (txtBookingStatus != null) {   // null only if AuthGuard sent a signed-out user away
            DataChangeNotifier.register(dataListener);   // NEW
            loadLocalProfile(false);                     // NEW: instant, works offline
            loadLocalBookings();                         // NEW: instant, works offline
            listenToStudentId();    // server -> local copy
            listenToBookings();     // server -> local copy
        }
    }

    // ...and stops when it is hidden, so it never leaks.
    @Override
    protected void onStop() {
        super.onStop();
        if (profileListener != null) {
            profileListener.remove();
            profileListener = null;
        }
        if (bookingsListener != null) {
            bookingsListener.remove();
            bookingsListener = null;
        }
        DataChangeNotifier.unregister(dataListener);   // NEW
    }

    // ================================================================ the six screens

    private void setUpScreens() {
        // The container that holds the full-screen Fragments. It is hidden until one is open.
        fragmentContainer = findViewById(R.id.fragmentContainer);
        getSupportFragmentManager().addOnBackStackChangedListener(this::syncFragmentContainer);
        syncFragmentContainer();   // also needed after a rotation: the back stack was restored

        // The "My Accommodation" card
        txtBookingStatus = findViewById(R.id.textView2);
        originalBookingText = txtBookingStatus.getText().toString();

        // LESSON: a MaterialCardView can be clicked as a whole. Children that are not clickable
        // themselves (our icons and texts) pass the tap UP to the card, so one listener per
        // card is enough (the older screens bound every view in a row separately).
        findViewById(R.id.cardFindHostel).setOnClickListener(
                v -> showScreen(HostelSearchFragment.newInstance(), HostelSearchFragment.TAG));
        findViewById(R.id.cardLocation).setOnClickListener(
                v -> showScreen(HostelLocationFragment.newInstance(), HostelLocationFragment.TAG));
        findViewById(R.id.cardPreference).setOnClickListener(
                v -> showScreen(HostelSharingFragment.newInstance(), HostelSharingFragment.TAG));
        findViewById(R.id.cardRoom).setOnClickListener(
                v -> showScreen(HostelRoomTypeFragment.newInstance(), HostelRoomTypeFragment.TAG));
        findViewById(R.id.cardBudget).setOnClickListener(
                v -> showScreen(HostelBudgetFragment.newInstance(), HostelBudgetFragment.TAG));
        findViewById(R.id.cardCustomSearch).setOnClickListener(
                v -> showScreen(HostelCustomSearchFragment.newInstance(), HostelCustomSearchFragment.TAG));

        // The screens save nothing themselves. When the student confirms a room, whichever screen
        // they were on sends the room's details back under this key, and saveBooking() writes the
        // booking request. ONE listener serves all six screens, because they all use the same key.
        getSupportFragmentManager().setFragmentResultListener(
                HostelBaseFragment.REQUEST_KEY, this, (key, result) -> saveBooking(result));
    }

    /** True while a full-screen Fragment is open on top of the Hostel screen. */
    private boolean isScreenOpen() {
        return getSupportFragmentManager().getBackStackEntryCount() > 0;
    }

    /** Shows the Fragment container while a screen is open, hides it when the last one closes. */
    private void syncFragmentContainer() {
        if (fragmentContainer != null) {
            fragmentContainer.setVisibility(isScreenOpen() ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * Puts a Fragment in the container. LESSON:
     *   replace()        puts the new screen in the container
     *   addToBackStack() makes the Back button close it and reveal the Hostel screen again
     *   commit()         does it on the next frame; executePendingTransactions() makes it happen
     *                    NOW, so a quick double-tap can't open the same screen twice
     */
    private void showScreen(Fragment fragment, String tag) {
        FragmentManager fm = getSupportFragmentManager();
        if (fm.isStateSaved() || fm.findFragmentByTag(tag) != null) {
            return;   // already open (or the screen is being rotated): don't stack a second one
        }
        fm.beginTransaction()
                .replace(R.id.fragmentContainer, fragment, tag)
                .addToBackStack(tag)
                .commit();
        fm.executePendingTransactions();
    }

    // ================================================================ search and booking (NEW)

    /**
     * CHANGED: saves the student's confirmed choice (from ANY of the six screens) in the LOCAL database (sync_state =
     * pending_sync). SyncWorker later creates the document in "hostelBookings" with status
     * "pending" and sends it as soon as there is internet. The admin approves it afterwards,
     * and only then is the place theirs. Free places are not reduced here.
     */
    private void saveBooking(Bundle booking) {
        // The student's identity comes from the session here (real uid, or the provisional id of a
        // waiting sign-up), never from the screen that reported the booking, so a booking is always
        // in the student's own name.
        String uid = SessionUser.uid(getApplicationContext());
        if (uid == null) {
            Toast.makeText(this, "Please sign in again.", Toast.LENGTH_LONG).show();
            return;
        }
        if (studentId == null || studentId.trim().isEmpty()) {
            Toast.makeText(this, "Your student ID is not available. Complete your profile first.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (hasActiveBooking) {
            // The screens already switch Book off; this is the second line of defence.
            Toast.makeText(this, "You already have an active booking.", Toast.LENGTH_LONG).show();
            return;
        }

        HostelBookingRepository.Booking b = new HostelBookingRepository.Booking();
        b.uid = uid;
        b.studentIdNumber = studentId.trim();
        b.hostelId = booking.getString(HostelBaseFragment.RESULT_HOSTEL_ID, "");
        b.hostelName = booking.getString(HostelBaseFragment.RESULT_HOSTEL_NAME, "");
        b.roomId = booking.getString(HostelBaseFragment.RESULT_ROOM_ID, "");
        b.roomType = booking.getString(HostelBaseFragment.RESULT_ROOM_TYPE, "");
        b.sharing = booking.getString(HostelBaseFragment.RESULT_SHARING, "");
        b.rentPerMonth = booking.getLong(HostelBaseFragment.RESULT_RENT, 0);

        // Block a quick double tap before the database has answered
        hasActiveBooking = true;

        DbExecutor.async(() -> HostelBookingRepository.createLocal(getApplicationContext(), b), id -> {
            if (id == null) {
                hasActiveBooking = false;
                Toast.makeText(getApplicationContext(),
                        "Could not save your booking on this phone. Please try again.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(getApplicationContext(),
                    "Booking saved. It is sent automatically when you are online. "
                            + "Track it under My Accommodation.",
                    Toast.LENGTH_LONG).show();
        });
    }

    /**
     * CHANGED: this Firestore listener no longer draws the card. It only COPIES the server's
     * bookings into the local database (so admin decisions such as "approved" arrive here).
     * The card is drawn from the local database by loadLocalBookings().
     *
     * LESSON: ONE source of truth for the screen. Server -> local database -> screen.
     * Offline, the chain simply stops at the first arrow and the screen still has data.
     */
    private void listenToBookings() {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) {
            return;
        }
        if (bookingsListener != null) {
            bookingsListener.remove();   // never keep two connections open
        }

        // The whereEqualTo MUST match the security rule (studentUid == request.auth.uid).
        bookingsListener = FirebaseFirestore.getInstance()
                .collection("hostelBookings")
                .whereEqualTo("studentUid", uid)
                .addSnapshotListener((snap, error) -> {
                    if (error != null || snap == null) {
                        Log.e(TAG, "Bookings listener failed", error);
                        return;   // keep showing what we had
                    }
                    List<HostelBookingRepository.Booking> rows = new ArrayList<>();
                    for (DocumentSnapshot doc : snap.getDocuments()) {
                        try {
                            rows.add(bookingFrom(uid, doc));
                        } catch (RuntimeException e) {
                            Log.e(TAG, "Skipping bad booking document: " + doc.getId(), e);
                        }
                    }
                    DbExecutor.run(() ->
                            HostelBookingRepository.mirrorFromServer(getApplicationContext(), rows));
                });
    }

    /** Turns a Firestore document into a row. Missing text becomes "" (the table forbids NULL). */
    private static HostelBookingRepository.Booking bookingFrom(String uid, DocumentSnapshot doc) {
        HostelBookingRepository.Booking b = new HostelBookingRepository.Booking();
        b.localId = doc.getId();   // the document id IS the local id (see the repository)
        b.uid = uid;
        b.studentIdNumber = orEmpty(doc.getString("studentIdNumber"));
        b.hostelId = orEmpty(doc.getString("hostelId"));
        b.hostelName = orEmpty(doc.getString("hostelName"));
        b.roomId = orEmpty(doc.getString("roomId"));
        b.roomType = orEmpty(doc.getString("roomType"));
        b.sharing = orEmpty(doc.getString("sharing"));
        Long rent = doc.getLong("rentPerMonth");
        b.rentPerMonth = rent == null ? 0 : rent;
        b.status = orEmpty(doc.getString("status"));
        Timestamp created = doc.getTimestamp("createdAt");
        b.createdMillis = created == null ? 0 : created.toDate().getTime();   // 0 = not known yet
        return b;
    }

    private static String orEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }

    /**
     * Reads this student's bookings from the LOCAL database (newest first, unsent ones on top),
     * works out whether one is still active, and redraws the card.
     */
    private void loadLocalBookings() {
        String uid = SessionUser.uid(getApplicationContext());   // real uid OR provisional id
        if (uid == null || txtBookingStatus == null) {
            return;
        }
        DbExecutor.async(() -> HostelBookingRepository.listFor(getApplicationContext(), uid), list -> {
            if (list == null || isFinishing() || isDestroyed()) {
                return;
            }
            boolean active = false;
            for (HostelBookingRepository.Booking b : list) {
                String status = bookingStatusOf(b.status);
                // A booking the server refused is not "active": the student may book again
                if (!SyncState.FAILED.equals(b.syncState)
                        && (status.equals("pending") || status.equals("approved"))) {
                    active = true;
                }
            }
            hasActiveBooking = active;
            showBookingStatus(list.isEmpty() ? null : list.get(0));
        });
    }

    /** Fills the card: "Pending: Greenview Hostel · Bedsitter · private · KES 12,000 per month". */
    private void showBookingStatus(@Nullable HostelBookingRepository.Booking latest) {
        if (latest == null) {
            txtBookingStatus.setText(originalBookingText);   // "No active booking"
            return;
        }
        try {
            boolean failed = SyncState.FAILED.equals(latest.syncState);
            String status = bookingStatusOf(latest.status);
            String word = failed ? "Not sent"
                    : status.isEmpty() ? "Unknown"
                    : Character.toUpperCase(status.charAt(0)) + status.substring(1);

            String roomLabel = HostelData.roomTypeLabel(bookingStatusOf(latest.roomType));
            String roomType = roomLabel != null ? roomLabel : latest.roomType;

            StringBuilder text = new StringBuilder(word).append(": ")
                    .append(latest.hostelName.trim().isEmpty() ? "-" : latest.hostelName.trim());
            if (roomType != null && !roomType.trim().isEmpty()) {
                text.append("  ·  ").append(roomType.trim());
            }
            if (!latest.sharing.trim().isEmpty()) {
                text.append("  ·  ").append(latest.sharing.trim());
            }
            if (latest.rentPerMonth > 0) {
                text.append("  ·  KES ").append(HostelData.formatKes(latest.rentPerMonth))
                        .append(" per month");
            }
            if (SyncState.PENDING_SYNC.equals(latest.syncState)) {
                // CHANGED: was latest.getMetadata().hasPendingWrites(). Saved here, not on the server yet.
                text.append("  (waiting to send…)");
            } else if (failed && latest.syncError != null) {
                text.append("\n").append(latest.syncError);
            }
            txtBookingStatus.setText(text.toString());
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not show the booking status", e);
            txtBookingStatus.setText(originalBookingText);
        }
    }

    /** "Pending ", "PENDING" and "pending" all mean the same, so a console typo can't break the card. */
    private static String bookingStatusOf(@Nullable String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
    }

    // ================================================================ student ID (step 3)

    /**
     * Reads the profile that SignUpActivity saved at users/{uid}. This is a LIVE connection: if the
     * student edits their registration number in Profile, this screen updates the next time it is
     * shown. "registrationNo" must match the field name SignUpActivity saves.
     */
    private void listenToStudentId() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            return;
        }

        if (profileListener != null) {
            profileListener.remove();   // never keep two connections open
        }

        profileListener = FirebaseFirestore.getInstance()
                .collection("users")
                .document(user.getUid())
                .addSnapshotListener((doc, error) -> {
                    if (error != null) {
                        // The student sees a short message; YOU see the real reason in Logcat
                        // (PERMISSION_DENIED means the "users" rules are not published).
                        Log.e(TAG, "Profile listener failed", error);
                        return;
                    }

                    // NEW. LESSON: offline, with nothing cached, Firestore answers "document does
                    // not exist". That only means "not on this phone", so do not wipe what we have.
                    if (doc != null && !doc.exists() && doc.getMetadata().isFromCache()) {
                        return;
                    }

                    try {
                        studentId = (doc != null && doc.exists()) ? doc.getString("registrationNo") : null;
                        if (doc != null && doc.exists()) {
                            // NEW: keep the local copy fresh (Profile and Payment can use it offline)
                            ProfileRepository.LocalProfile copy =
                                    ProfileRepository.fromSnapshot(user.getUid(), doc);
                            DbExecutor.run(() ->
                                    ProfileRepository.saveFromServer(getApplicationContext(), copy));
                        }
                    } catch (RuntimeException e) {
                        Log.e(TAG, "registrationNo has the wrong type", e);
                        studentId = null;
                    }
                });
    }

    /**
     * NEW: reads the student ID from the LOCAL copy, so it is ready instantly and offline.
     * It is no longer SHOWN on this screen, but every booking is saved with it.
     * @param force false = only fill it if we have nothing yet; true = replace (the copy changed)
     */
    private void loadLocalProfile(boolean force) {
        String uid = SessionUser.uid(getApplicationContext());   // real uid OR provisional id
        if (uid == null) {
            return;
        }
        DbExecutor.async(() -> ProfileRepository.get(getApplicationContext(), uid), p -> {
            if (p == null || isFinishing() || isDestroyed()) {
                return;
            }
            if (p.registrationNo != null && !p.registrationNo.trim().isEmpty()
                    && (force || studentId == null)) {
                studentId = p.registrationNo;
            }
        });
    }
}