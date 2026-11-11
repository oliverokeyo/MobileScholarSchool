package com.example.mobilescholarschool.HostelTab;

import android.os.Bundle;
import android.util.Log;
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

import com.example.mobilescholarschool.Components.AuthGuard;
import com.example.mobilescholarschool.Components.BottomNavHelper;
import com.example.mobilescholarschool.R;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;


public class HostelActivity extends AppCompatActivity {

    private static final String TAG = "HostelActivity";

    // Saved across rotation (an Activity is rebuilt when the phone turns, and loses its fields)
    private static final String STATE_LOCATION = "state_location";
    private static final String STATE_SHARING = "state_sharing";
    private static final String STATE_ROOM_TYPE = "state_room_type";
    private static final String STATE_MAX_RENT = "state_max_rent";
    private static final String STATE_FACILITIES = "state_facilities";

    private TextView txtViewStudentID;

    // The student's registration number; null until the profile has loaded
    private String studentId;

    // Our live connection to the profile, so onStop can switch it off
    private ListenerRegistration profileListener;

    private String selectedLocation = "";                 // "campus", "nearby" or "off-campus"
    private String selectedSharing = "";                  // "private" or "shared"
    private String selectedRoomType = "";                 // "bedsitter", "single", "one-bedroom", "shared"
    private long selectedMaxRent = 0;                     // KES per month, per student; 0 = no limit
    private final ArrayList<String> selectedFacilities = new ArrayList<>();   // e.g. "wifi", "water"
    private TextView descLocation, descSharing, descRoom, descBudget;

    // NEW: the "My Accommodation" card shows the student's latest booking
    private TextView txtBookingStatus;
    private String originalBookingText;
    private ListenerRegistration bookingsListener;
    private boolean hasActiveBooking = false;   // true while a booking is pending or approved
    private String originalLocationText, originalSharingText, originalRoomText, originalBudgetText;

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

        // Replaces the fixed "Student ID:(SB06/SR/1234/90)" text from the layout straight away,
        txtViewStudentID = findViewById(R.id.txtViewStudentID);
        txtViewStudentID.setText(R.string.student_id_loading);

        setUpFindAccommodation(savedInstanceState);
    }

    // The live connection starts when the screen becomes visible...
    @Override
    protected void onStart() {
        super.onStart();
        if (txtViewStudentID != null) {   // null only if AuthGuard sent a signed-out user away
            listenToStudentId();
            listenToBookings();
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
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_LOCATION, selectedLocation);
        outState.putString(STATE_SHARING, selectedSharing);
        outState.putString(STATE_ROOM_TYPE, selectedRoomType);
        outState.putLong(STATE_MAX_RENT, selectedMaxRent);
        outState.putStringArrayList(STATE_FACILITIES, selectedFacilities);
    }


    private void setUpFindAccommodation(@Nullable Bundle savedInstanceState) {
        // The description lines: remember the original wording so "Any" can put it back
        descLocation = findViewById(R.id.txtViewLocationDescription);
        descSharing = findViewById(R.id.textViewPreferenceDescription);
        descRoom = findViewById(R.id.txtViewRoomDescription);
        descBudget = findViewById(R.id.textViewBudgetDescription);
        originalLocationText = descLocation.getText().toString();
        originalSharingText = descSharing.getText().toString();
        originalRoomText = descRoom.getText().toString();
        originalBudgetText = descBudget.getText().toString();

        // After a rotation, bring the choices back
        if (savedInstanceState != null) {
            selectedLocation = savedInstanceState.getString(STATE_LOCATION, "");
            selectedSharing = savedInstanceState.getString(STATE_SHARING, "");
            selectedRoomType = savedInstanceState.getString(STATE_ROOM_TYPE, "");
            selectedMaxRent = savedInstanceState.getLong(STATE_MAX_RENT, 0);
            ArrayList<String> facilities = savedInstanceState.getStringArrayList(STATE_FACILITIES);
            if (facilities != null) {
                selectedFacilities.addAll(facilities);
            }
        }

        // Each row is made of several views (icon, title, description, arrow). Giving each one the
        // same listener makes the whole row tappable. (The Sharing row has no icon in your layout.)
        bindClick(v -> openChoice(HostelChoiceDialogFragment.forLocation(selectedLocation)),
                R.id.imgLocation, R.id.txtViewLocation,
                R.id.txtViewLocationDescription, R.id.imgLocationArrow);
        bindClick(v -> openChoice(HostelChoiceDialogFragment.forSharing(selectedSharing)),
                R.id.txtViewPreferance, R.id.textViewPreferenceDescription, R.id.imgPreferenceArrow);
        bindClick(v -> openChoice(HostelChoiceDialogFragment.forRoomType(selectedRoomType)),
                R.id.imgRoom, R.id.txtViewRoom,
                R.id.txtViewRoomDescription, R.id.imgRoomArrow);
        bindClick(v -> openBudget(),
                R.id.imgBudget, R.id.txtViewBudget,
                R.id.textViewBudgetDescription, R.id.imgBudgetArrow);

        getSupportFragmentManager().setFragmentResultListener(
                HostelChoiceDialogFragment.KEY_LOCATION, this, (key, result) -> {
                    selectedLocation = result.getString(HostelChoiceDialogFragment.RESULT_VALUE, "");
                    showSelections();
                });
        getSupportFragmentManager().setFragmentResultListener(
                HostelChoiceDialogFragment.KEY_SHARING, this, (key, result) -> {
                    selectedSharing = result.getString(HostelChoiceDialogFragment.RESULT_VALUE, "");
                    showSelections();
                });
        getSupportFragmentManager().setFragmentResultListener(
                HostelChoiceDialogFragment.KEY_ROOM_TYPE, this, (key, result) -> {
                    selectedRoomType = result.getString(HostelChoiceDialogFragment.RESULT_VALUE, "");
                    showSelections();
                });
        getSupportFragmentManager().setFragmentResultListener(
                HostelBudgetDialogFragment.REQUEST_KEY, this, (key, result) -> {
                    selectedMaxRent = result.getLong(HostelBudgetDialogFragment.RESULT_MAX_RENT, 0);
                    selectedFacilities.clear();
                    String[] chosen = result.getStringArray(HostelBudgetDialogFragment.RESULT_FACILITIES);
                    if (chosen != null) {
                        Collections.addAll(selectedFacilities, chosen);
                    }
                    showSelections();
                });

        // NEW: the status card, the Search button, and the answer from the results dialog
        txtBookingStatus = findViewById(R.id.textView2);
        originalBookingText = txtBookingStatus.getText().toString();

        findViewById(R.id.btnSearchHostels).setOnClickListener(v -> openSearchResults());

        // The results dialog saves nothing itself. When the student confirms a room it sends the
        // room's details back under this key, and saveBooking() writes the booking request.
        getSupportFragmentManager().setFragmentResultListener(
                HostelSearchResultDialogFragment.REQUEST_KEY, this, (key, result) -> saveBooking(result));

        showSelections();
    }

    private void openChoice(HostelChoiceDialogFragment dialog) {
        // Already open (for example after a rotation)? Don't stack a second one.
        if (getSupportFragmentManager().findFragmentByTag(HostelChoiceDialogFragment.TAG) != null) {
            return;
        }
        dialog.show(getSupportFragmentManager(), HostelChoiceDialogFragment.TAG);
    }

    private void openBudget() {
        if (getSupportFragmentManager().findFragmentByTag(HostelBudgetDialogFragment.TAG) != null) {
            return;
        }
        HostelBudgetDialogFragment.newInstance(selectedMaxRent, selectedFacilities)
                .show(getSupportFragmentManager(), HostelBudgetDialogFragment.TAG);
    }

    /** Shows the current choice on each row, or the original wording when nothing is chosen. */
    private void showSelections() {
        setDescription(descLocation, originalLocationText,
                HostelChoiceDialogFragment.locationLabel(selectedLocation));
        setDescription(descSharing, originalSharingText,
                HostelChoiceDialogFragment.sharingLabel(selectedSharing));
        setDescription(descRoom, originalRoomText,
                HostelChoiceDialogFragment.roomTypeLabel(selectedRoomType));

        String budget = HostelBudgetDialogFragment.summaryOf(selectedMaxRent, selectedFacilities);
        setDescription(descBudget, originalBudgetText, budget.isEmpty() ? null : budget);
    }

    private static void setDescription(TextView view, String original, @Nullable String choice) {
        view.setText(choice == null ? original : "Selected: " + choice);
    }

    /** Gives every one of the listed views the same click listener (so a whole row is tappable). */
    private void bindClick(View.OnClickListener listener, int... viewIds) {
        for (int id : viewIds) {
            findViewById(id).setOnClickListener(listener);
        }
    }

    // ================================================================ search and booking (NEW)

    /** Opens "Hostel Search Result" with the four choices the student has made. */
    private void openSearchResults() {
        // Already open (for example after a rotation)? Don't stack a second one.
        if (getSupportFragmentManager().findFragmentByTag(HostelSearchResultDialogFragment.TAG) != null) {
            return;
        }
        HostelSearchResultDialogFragment
                .newInstance(selectedLocation, selectedSharing, selectedRoomType,
                        selectedMaxRent, selectedFacilities)
                .show(getSupportFragmentManager(), HostelSearchResultDialogFragment.TAG);
    }

    /**
     * Turns the student's confirmed choice into a document in "hostelBookings".
     * It is a REQUEST with status "pending": the admin approves it later (and only then is the
     * place theirs). Free places are not reduced here; that is the admin side's job.
     */
    private void saveBooking(Bundle booking) {
        // The student's identity comes from Firebase Auth here, never from the dialog,
        // so a booking can only ever be made in the signed-in student's own name.
        String uid = FirebaseAuth.getInstance().getUid();
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
            // The dialog already switches Book off; this is the second line of defence.
            Toast.makeText(this, "You already have an active booking.", Toast.LENGTH_LONG).show();
            return;
        }

        // EXACTLY these ten fields: the security rules reject any extra or missing field.
        Map<String, Object> data = new HashMap<>();
        data.put("studentUid", uid);
        data.put("studentIdNumber", studentId.trim());
        data.put("hostelId", booking.getString(HostelSearchResultDialogFragment.RESULT_HOSTEL_ID, ""));
        data.put("hostelName", booking.getString(HostelSearchResultDialogFragment.RESULT_HOSTEL_NAME, ""));
        data.put("roomId", booking.getString(HostelSearchResultDialogFragment.RESULT_ROOM_ID, ""));
        data.put("roomType", booking.getString(HostelSearchResultDialogFragment.RESULT_ROOM_TYPE, ""));
        data.put("sharing", booking.getString(HostelSearchResultDialogFragment.RESULT_SHARING, ""));
        data.put("rentPerMonth", booking.getLong(HostelSearchResultDialogFragment.RESULT_RENT, 0));
        data.put("status", "pending");                          // only the admin changes this later
        data.put("createdAt", FieldValue.serverTimestamp());    // the SERVER's clock, not the phone's

        // Firestore records the booking on the phone straight away (the card below shows it as
        // "sending…") and delivers it to the server when it can.
        Toast.makeText(this, "Booking request sent. Track it under My Accommodation.",
                Toast.LENGTH_LONG).show();

        FirebaseFirestore.getInstance().collection("hostelBookings").add(data)
                .addOnSuccessListener(this, ref ->
                        Log.d(TAG, "Booking saved with ID " + ref.getId()))
                .addOnFailureListener(this, e -> {
                    // The student sees a friendly message; YOU see the real reason in Logcat
                    // (PERMISSION_DENIED means a rule rejected it: see the troubleshooting list).
                    Log.e(TAG, "Saving the booking failed", e);
                    Toast.makeText(this, "Your booking could not be saved. Please try again.",
                            Toast.LENGTH_LONG).show();
                });
    }

    /**
     * Keeps the "My Accommodation" card up to date with the student's LATEST booking, live.
     * Also works out whether a booking is already pending/approved (saveBooking checks that).
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

                    boolean active = false;
                    DocumentSnapshot latest = null;
                    for (DocumentSnapshot doc : snap.getDocuments()) {
                        try {
                            String status = bookingStatusOf(doc.getString("status"));
                            if (status.equals("pending") || status.equals("approved")) {
                                active = true;
                            }
                            if (latest == null
                                    || isNewer(doc.getTimestamp("createdAt"), latest.getTimestamp("createdAt"))) {
                                latest = doc;
                            }
                        } catch (RuntimeException e) {
                            Log.e(TAG, "Skipping bad booking document: " + doc.getId(), e);
                        }
                    }
                    hasActiveBooking = active;
                    showBookingStatus(latest);
                });
    }

    /** Fills the card: "Pending: Greenview Hostel · Bedsitter · private · KES 12,000 per month". */
    private void showBookingStatus(@Nullable DocumentSnapshot latest) {
        if (latest == null) {
            txtBookingStatus.setText(originalBookingText);   // "No active booking"
            return;
        }
        try {
            String status = bookingStatusOf(latest.getString("status"));
            String word = status.isEmpty() ? "Unknown"
                    : Character.toUpperCase(status.charAt(0)) + status.substring(1);

            String hostel = latest.getString("hostelName");
            String roomLabel = HostelChoiceDialogFragment.roomTypeLabel(
                    bookingStatusOf(latest.getString("roomType")));
            String roomType = roomLabel != null ? roomLabel : latest.getString("roomType");
            String sharing = latest.getString("sharing");
            Long rent = latest.getLong("rentPerMonth");

            StringBuilder text = new StringBuilder(word).append(": ")
                    .append(hostel == null || hostel.trim().isEmpty() ? "-" : hostel.trim());
            if (roomType != null && !roomType.trim().isEmpty()) {
                text.append("  ·  ").append(roomType.trim());
            }
            if (sharing != null && !sharing.trim().isEmpty()) {
                text.append("  ·  ").append(sharing.trim());
            }
            if (rent != null) {
                text.append("  ·  KES ").append(HostelBudgetDialogFragment.formatKes(rent)).append(" per month");
            }
            // "(sending…)" = saved on the phone but the server has not confirmed it yet
            if (latest.getMetadata().hasPendingWrites()) {
                text.append("  (sending…)");
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

    /** A booking still waiting to reach the server has no createdAt yet, so it counts as the newest. */
    private static boolean isNewer(@Nullable Timestamp a, @Nullable Timestamp b) {
        if (a == null) return b != null;
        if (b == null) return false;
        return a.compareTo(b) > 0;
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
                        if (studentId == null) {
                            txtViewStudentID.setText(R.string.student_id_unavailable);
                        }
                        return;
                    }

                    try {
                        studentId = (doc != null && doc.exists()) ? doc.getString("registrationNo") : null;
                    } catch (RuntimeException e) {
                        Log.e(TAG, "registrationNo has the wrong type", e);
                        studentId = null;
                    }
                    showStudentId();
                });
    }

    private void showStudentId() {
        if (studentId == null || studentId.trim().isEmpty()) {
            txtViewStudentID.setText(R.string.student_id_not_available);
        } else {
            txtViewStudentID.setText(getString(R.string.student_id_format, studentId));
        }
    }
}