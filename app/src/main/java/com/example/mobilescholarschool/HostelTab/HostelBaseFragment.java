package com.example.mobilescholarschool.HostelTab;

import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentManager;

import com.example.mobilescholarschool.Components.ListScreenFragment;
import com.example.mobilescholarschool.Components.SessionUser;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QuerySnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * The parent of EVERY hostel screen (Search, Location, Sharing, Room type, Budget, Custom search).
 *
 * It does the work they all need, ONCE:
 *   1. keeps live copies of the "hostels" and "rooms" collections (they update by themselves
 *      when the admin changes a rent or a room fills up),
 *   2. knows whether the student already has an active booking,
 *   3. draws a hostel as a card with its rooms and a BOOK button, and
 *   4. handles the booking: "are you sure?" -> send the choice to the Activity -> close.
 *
 * A child screen only has to say WHICH hostels to show, by implementing renderLoaded().
 *
 * LESSON: this is why every screen can take a booking. The Book button is not written six
 * times; it is written here once, and six screens inherit it. Fix a bug here and it is fixed
 * everywhere.
 *
 * It READS hostels and rooms and never writes them. When the student confirms, the booking is
 * handed to the Activity with the Fragment Result API and the Activity saves it.
 */
public abstract class HostelBaseFragment extends ListScreenFragment {

    private static final String LOG_TAG = "HostelBaseFragment";

    // ---- what the Activity listens for when the student confirms a booking ----
    public static final String REQUEST_KEY = "hostel_booking";
    public static final String RESULT_HOSTEL_ID = "result_hostel_id";
    public static final String RESULT_HOSTEL_NAME = "result_hostel_name";
    public static final String RESULT_ROOM_ID = "result_room_id";
    public static final String RESULT_ROOM_TYPE = "result_room_type";
    public static final String RESULT_SHARING = "result_sharing";
    public static final String RESULT_RENT = "result_rent";   // long, KES per month

    // The live copies of the two collections
    protected final List<HostelData.Hostel> hostels = new ArrayList<>();
    protected final List<HostelData.Room> rooms = new ArrayList<>();
    private boolean hostelsLoaded = false;
    private boolean roomsLoaded = false;
    private boolean loadFailed = false;

    // True when the student already has a pending or approved booking: Book is switched off
    protected boolean hasActiveBooking = false;

    private ListenerRegistration hostelsListener;
    private ListenerRegistration roomsListener;
    private ListenerRegistration bookingsListener;

    private AlertDialog confirmDialog;

    // ================================================================ what a child screen provides

    /**
     * Fill `out` with this screen's hostels. Called ONLY when the data has loaded, so the lists
     * `hostels` and `rooms` can be trusted. Use addSection() and addHostelCard() to draw.
     */
    protected abstract void renderLoaded(@NonNull LinearLayout out);

    /**
     * Where the results are drawn. By default the whole scrolling list; a screen with a form
     * above its results (Custom search) overrides this to return just the results area.
     */
    @Nullable
    protected LinearLayout out() {
        return list;
    }

    // ================================================================ live data

    // The live connections start when the screen becomes visible...
    @Override
    public void onStart() {
        super.onStart();
        attachListeners();
    }

    // ...and stop when it is hidden or closed, so they never leak.
    @Override
    public void onStop() {
        super.onStop();
        detachListeners();
    }

    @Override
    public void onDestroyView() {
        if (confirmDialog != null) {
            confirmDialog.dismiss();   // a dialog must not outlive the screen that opened it
            confirmDialog = null;
        }
        super.onDestroyView();
    }

    private void attachListeners() {
        detachListeners();   // never keep two connections open
        loadFailed = false;

        // The lists live on the server and need a real account. A waiting sign-up has none yet.
        if (!SessionUser.isSignedIn()) {
            render();
            return;
        }

        FirebaseFirestore db = FirebaseFirestore.getInstance();
        hostelsListener = db.collection("hostels")
                .addSnapshotListener((snap, error) -> onHostels(snap, error));
        roomsListener = db.collection("rooms")
                .addSnapshotListener((snap, error) -> onRooms(snap, error));

        // The student's own bookings: used only to switch the Book buttons off when they already
        // have an active one. The whereEqualTo MUST match the security rule (studentUid == their UID).
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid != null) {
            bookingsListener = db.collection("hostelBookings")
                    .whereEqualTo("studentUid", uid)
                    .addSnapshotListener((snap, error) -> onBookings(snap, error));
        }
        render();
    }

    private void detachListeners() {
        if (hostelsListener != null) {
            hostelsListener.remove();
            hostelsListener = null;
        }
        if (roomsListener != null) {
            roomsListener.remove();
            roomsListener = null;
        }
        if (bookingsListener != null) {
            bookingsListener.remove();
            bookingsListener = null;
        }
    }

    private void onHostels(@Nullable QuerySnapshot snap, @Nullable Exception error) {
        if (!isAdded()) {
            return;   // the screen closed while we waited
        }
        if (error != null || snap == null) {
            // (PERMISSION_DENIED means the "hostels" read rule is not published yet)
            Log.e(LOG_TAG, "Hostels listener failed", error);
            loadFailed = true;
            render();
            return;
        }
        hostels.clear();
        for (DocumentSnapshot doc : snap.getDocuments()) {
            HostelData.Hostel hostel = parseHostel(doc);
            if (hostel != null) {
                hostels.add(hostel);
            }
        }
        hostelsLoaded = true;
        render();
    }

    private void onRooms(@Nullable QuerySnapshot snap, @Nullable Exception error) {
        if (!isAdded()) {
            return;
        }
        if (error != null || snap == null) {
            Log.e(LOG_TAG, "Rooms listener failed", error);
            loadFailed = true;
            render();
            return;
        }
        rooms.clear();
        for (DocumentSnapshot doc : snap.getDocuments()) {
            HostelData.Room room = parseRoom(doc);
            if (room != null) {
                rooms.add(room);
            }
        }
        roomsLoaded = true;
        render();
    }

    private void onBookings(@Nullable QuerySnapshot snap, @Nullable Exception error) {
        if (!isAdded()) {
            return;
        }
        if (error != null || snap == null) {
            // Not fatal: the list still works. (The Activity and the security rules check again on save.)
            Log.e(LOG_TAG, "Bookings listener failed", error);
            return;
        }
        boolean active = false;
        for (DocumentSnapshot doc : snap.getDocuments()) {
            try {
                String status = HostelData.norm(doc.getString("status"));
                if (status.equals("pending") || status.equals("approved")) {
                    active = true;
                }
            } catch (RuntimeException e) {
                Log.e(LOG_TAG, "Skipping bad booking document: " + doc.getId(), e);
            }
        }
        hasActiveBooking = active;
        render();
    }

    private static HostelData.Hostel parseHostel(DocumentSnapshot doc) {
        // One badly typed document (for example "active" saved as TEXT) must not crash the
        // whole screen, so each document gets its own safety net.
        try {
            HostelData.Hostel hostel = new HostelData.Hostel();
            hostel.id = doc.getId();
            hostel.name = doc.getString("name");
            hostel.locationType = doc.getString("locationType");
            hostel.area = doc.getString("area");
            Object raw = doc.get("facilities");
            if (raw instanceof List) {
                for (Object item : (List<?>) raw) {
                    if (item instanceof String) {
                        hostel.facilities.add((String) item);
                    }
                }
            }
            hostel.active = !Boolean.FALSE.equals(doc.getBoolean("active"));
            return hostel;
        } catch (RuntimeException e) {
            Log.e(LOG_TAG, "Skipping bad hostel document: " + doc.getId(), e);
            return null;
        }
    }

    private static HostelData.Room parseRoom(DocumentSnapshot doc) {
        try {
            Long rent = doc.getLong("rentPerMonth");
            if (rent == null) {
                Log.e(LOG_TAG, "Skipping room with no rentPerMonth: " + doc.getId());
                return null;
            }
            HostelData.Room room = new HostelData.Room();
            room.id = doc.getId();
            room.hostelId = doc.getString("hostelId");
            room.roomType = doc.getString("roomType");
            room.sharing = doc.getString("sharing");
            room.rentPerMonth = rent;
            Long slots = doc.getLong("slotsAvailable");
            room.slotsAvailable = slots == null ? 0 : slots;
            return room;
        } catch (RuntimeException e) {
            Log.e(LOG_TAG, "Skipping bad room document: " + doc.getId(), e);
            return null;
        }
    }

    // ================================================================ drawing

    /**
     * Redraws the results area. Every time ANYTHING changes (hostels, rooms, bookings, the
     * student typing) the child calls this, and the area is rebuilt from scratch.
     */
    protected final void render() {
        LinearLayout out = out();
        if (out == null || !isAdded()) {
            return;
        }
        out.removeAllViews();

        if (!SessionUser.isSignedIn()) {
            addMessage(out, "Searching hostels needs your account. It works once your "
                    + "account has been created.");
            return;
        }
        if (!hostelsLoaded || !roomsLoaded) {
            addMessage(out, loadFailed ? "Could not load the hostels. Check your connection."
                    : "Loading hostels…");
            return;
        }
        if (hasActiveBooking) {
            addBanner(out, "You already have an active booking, so booking is switched off. "
                    + "Check My Accommodation for its status.", COLOR_BAD);
        }
        renderLoaded(out);
    }

    /** A heading with a count, then a card for each hostel (or a short note when there are none). */
    protected void addSection(@NonNull LinearLayout out, String title,
                              List<HostelData.Entry> entries, String emptyText) {
        addSectionTitle(out, title + " (" + entries.size() + ")");
        if (entries.isEmpty()) {
            TextView none = makeText(emptyText, 14, false, 0);
            addLine(out, none, 6);
            return;
        }
        for (HostelData.Entry entry : entries) {
            addHostelCard(out, entry);
        }
    }

    /** One hostel: name, where it is, its facilities, and one row (with a Book button) per room. */
    protected void addHostelCard(@NonNull LinearLayout out, HostelData.Entry entry) {
        HostelData.Hostel hostel = entry.hostel;
        LinearLayout card = addCard(out);

        addLine(card, makeText(orDash(hostel.name), 17, true, 0), 0);

        String where = labelOrRaw(HostelData.locationLabel(hostel.locationType), hostel.locationType);
        if (hostel.area != null && !hostel.area.trim().isEmpty()) {
            where = where.isEmpty() ? hostel.area.trim() : where + "  ·  " + hostel.area.trim();
        }
        addLine(card, makeText(where.isEmpty() ? "Location not listed" : where, 14, false, 0), 2);

        String facilityText = HostelData.facilitiesText(hostel.facilities);
        addLine(card, makeText("Facilities: " + (facilityText.isEmpty() ? "none listed" : facilityText),
                14, false, 0), 2);

        if (entry.rooms.isEmpty()) {
            addLine(card, makeText("No free rooms right now.", 14, true, COLOR_NOTE), 8);
            return;
        }
        for (HostelData.Room room : entry.rooms) {
            card.addView(buildRoomRow(hostel, room));
        }
    }

    private LinearLayout buildRoomRow(HostelData.Hostel hostel, HostelData.Room room) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        String type = labelOrRaw(HostelData.roomTypeLabel(room.roomType), room.roomType);
        String share = labelOrRaw(HostelData.sharingLabel(room.sharing), room.sharing);
        String text = type + "  ·  " + share
                + "\nKES " + HostelData.formatKes(room.rentPerMonth) + " per month"
                + "  ·  " + room.slotsAvailable + " free";
        row.addView(makeText(text, 14, false, 0), new LinearLayout.LayoutParams(0, WRAP, 1f));

        // THE BOOK BUTTON. It is switched off while the student already has an active booking.
        MaterialButton book = new MaterialButton(requireContext());
        book.setText("Book");
        book.setEnabled(!hasActiveBooking);
        book.setOnClickListener(v -> confirmBooking(hostel, room));
        row.addView(book, new LinearLayout.LayoutParams(WRAP, WRAP));

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(MATCH, WRAP);
        rowParams.topMargin = dp(8);
        row.setLayoutParams(rowParams);
        return row;
    }

    // ================================================================ booking

    /** Asks "are you sure?" first, because a booking request is a commitment. */
    private void confirmBooking(HostelData.Hostel hostel, HostelData.Room room) {
        if (!isAdded()) {
            return;
        }
        String type = labelOrRaw(HostelData.roomTypeLabel(room.roomType), room.roomType);
        String share = labelOrRaw(HostelData.sharingLabel(room.sharing), room.sharing);

        if (confirmDialog != null) {
            confirmDialog.dismiss();
        }
        confirmDialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Confirm booking")
                .setMessage("Book a " + type + " (" + share + ") at " + orDash(hostel.name)
                        + " for KES " + HostelData.formatKes(room.rentPerMonth)
                        + " per month?\n\nYour request will be reviewed before it is confirmed.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Send request", (dialog, which) -> sendBooking(hostel, room))
                .show();
    }

    /**
     * This screen does NOT save anything. It hands the room's details to the Activity under
     * REQUEST_KEY, and the Activity saves the booking request on the phone. Then this screen
     * closes, so the student lands back on "My Accommodation" and sees the new status.
     */
    private void sendBooking(HostelData.Hostel hostel, HostelData.Room room) {
        if (!isAdded()) {
            return;
        }
        Bundle booking = new Bundle();
        booking.putString(RESULT_HOSTEL_ID, hostel.id == null ? "" : hostel.id);
        booking.putString(RESULT_HOSTEL_NAME, hostel.name == null ? "" : hostel.name);
        booking.putString(RESULT_ROOM_ID, room.id == null ? "" : room.id);
        booking.putString(RESULT_ROOM_TYPE, room.roomType == null ? "" : room.roomType);
        booking.putString(RESULT_SHARING, room.sharing == null ? "" : room.sharing);
        booking.putLong(RESULT_RENT, room.rentPerMonth);

        FragmentManager fm = getParentFragmentManager();
        fm.setFragmentResult(REQUEST_KEY, booking);
        fm.popBackStack();
    }

    // ================================================================ small helpers

    protected static String labelOrRaw(@Nullable String label, @Nullable String raw) {
        if (label != null) return label;
        return raw == null ? "" : raw.trim();
    }

    /** Shows "-" instead of the word "null" when a field is missing. */
    protected static String orDash(@Nullable String value) {
        return (value == null || value.trim().isEmpty()) ? "-" : value.trim();
    }
}
