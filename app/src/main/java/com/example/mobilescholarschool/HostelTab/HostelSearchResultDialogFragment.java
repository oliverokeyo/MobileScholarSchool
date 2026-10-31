package com.example.mobilescholarschool.HostelTab;

import android.app.Dialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QuerySnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * "Hostel Search Result": lists the hostels that fit what the student chose on the Hostel screen
 * (location, sharing preference, room type, budget) and lets them BOOK a room from here.
 *
 *   - If some hostels match ALL the choices, only those are shown (with only the matching rooms).
 *   - If none match exactly, ALL available hostels are shown instead, with a note saying so.
 *   - "Available" means: the hostel is active AND the room still has a free place.
 *
 * EVERYTHING lives in this one class. It READS "hostels" and "rooms" and never writes anything.
 * When the student confirms a booking it sends the room's details back with the Fragment Result API
 * and the screen saves the booking request.
 *
 * The lists are LIVE: if the admin changes a rent or a room fills up, an open dialog updates itself.
 *
 * Open it with:
 *   HostelSearchResultDialogFragment.newInstance(location, sharing, roomType, maxRent, facilities)
 *           .show(getSupportFragmentManager(), HostelSearchResultDialogFragment.TAG);
 */
public class HostelSearchResultDialogFragment extends DialogFragment {

    public static final String TAG = "HostelSearchResultDialog";

    // ---- what the screen listens for when the student confirms a booking -------------------
    public static final String REQUEST_KEY = "hostel_booking";
    public static final String RESULT_HOSTEL_ID = "result_hostel_id";
    public static final String RESULT_HOSTEL_NAME = "result_hostel_name";
    public static final String RESULT_ROOM_ID = "result_room_id";
    public static final String RESULT_ROOM_TYPE = "result_room_type";
    public static final String RESULT_SHARING = "result_sharing";
    public static final String RESULT_RENT = "result_rent";   // long, KES per month

    // ---- what the screen hands IN (the student's choices) ----------------------------------
    private static final String ARG_LOCATION = "arg_location";
    private static final String ARG_SHARING = "arg_sharing";
    private static final String ARG_ROOM_TYPE = "arg_room_type";
    private static final String ARG_MAX_RENT = "arg_max_rent";
    private static final String ARG_FACILITIES = "arg_facilities";

    // The WORD (the banner text) always explains the result, so colour is never the only clue.
    private static final int COLOR_GOOD = 0xFF43A047;
    private static final int COLOR_NOTE = 0xFFF9A825;
    private static final int COLOR_BAD = 0xFFE53935;

    // The student's choices ("" / 0 / empty = no preference). Read from the arguments in onCreate.
    private String location = "";
    private String sharing = "";
    private String roomType = "";
    private long maxRent = 0;
    private final List<String> facilities = new ArrayList<>();

    private LinearLayout list;

    private final List<Hostel> hostels = new ArrayList<>();
    private final List<Room> rooms = new ArrayList<>();
    private boolean hostelsLoaded = false;
    private boolean roomsLoaded = false;
    private boolean loadFailed = false;

    // True when the student already has a pending or approved booking: booking is switched off
    private boolean hasActiveBooking = false;

    private ListenerRegistration hostelsListener;
    private ListenerRegistration roomsListener;
    private ListenerRegistration bookingsListener;

    // >>> PURE-HELPERS-BEGIN  (plain Java: no Android code, so they can be unit-tested)

    /** One hostel, as stored in the "hostels" collection. */
    public static class Hostel {
        public String id;                 // the document ID, for example "greenview-hostel"
        public String name;
        public String locationType;       // "campus", "nearby" or "off-campus"
        public String area;
        public List<String> facilities = new ArrayList<>();
        public boolean active = true;     // only an explicit false hides a hostel
    }

    /** One room offering, as stored in the "rooms" collection. */
    public static class Room {
        public String id;                 // the document ID, for example "greenview-hostel_bedsitter_private"
        public String hostelId;           // must equal a hostel's document ID
        public String roomType;           // "bedsitter", "single", "one-bedroom" or "shared"
        public String sharing;            // "private" or "shared"
        public long rentPerMonth;         // KES per student
        public long slotsAvailable;       // free places; 0 = full
    }

    /** One hostel together with the rooms that qualify. */
    public static class Entry {
        public Hostel hostel;
        public List<Room> rooms = new ArrayList<>();
    }

    /** The answer: the hostels to show, and whether they match the student's choices EXACTLY. */
    public static class Results {
        public boolean exact;
        public List<Entry> entries = new ArrayList<>();
    }

    /** Lower-case and trimmed, so "Campus " and "campus" count as the same word. */
    public static String norm(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** True when the student chose at least one filter. */
    public static boolean anyFilter(String location, String sharing, String roomType,
                                    long maxRent, List<String> facilities) {
        return !norm(location).isEmpty() || !norm(sharing).isEmpty() || !norm(roomType).isEmpty()
                || maxRent > 0 || (facilities != null && !facilities.isEmpty());
    }

    /** Hostel-level checks: it is active, in the chosen location, and has EVERY facility asked for. */
    public static boolean hostelMatches(Hostel hostel, String location, List<String> requiredFacilities) {
        if (!hostel.active) {
            return false;
        }
        String wantedLocation = norm(location);
        if (!wantedLocation.isEmpty() && !wantedLocation.equals(norm(hostel.locationType))) {
            return false;
        }
        if (requiredFacilities != null) {
            for (String needed : requiredFacilities) {
                boolean found = false;
                for (String offered : hostel.facilities) {
                    if (norm(offered).equals(norm(needed))) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Room-level checks: a free place, the chosen sharing and type, and rent within budget. */
    public static boolean roomMatches(Room room, String sharing, String roomType, long maxRent) {
        if (room.slotsAvailable <= 0) {
            return false;   // a full room can't be booked, so it is never "available"
        }
        String wantedSharing = norm(sharing);
        if (!wantedSharing.isEmpty() && !wantedSharing.equals(norm(room.sharing))) {
            return false;
        }
        String wantedType = norm(roomType);
        if (!wantedType.isEmpty() && !wantedType.equals(norm(room.roomType))) {
            return false;
        }
        return maxRent <= 0 || room.rentPerMonth <= maxRent;
    }

    /**
     * The heart of the search. First look for hostels that match EVERYTHING the student chose.
     * If there are none, fall back to ALL available hostels (no filters at all) and say so.
     */
    public static Results buildResults(List<Hostel> hostels, List<Room> rooms,
                                       String location, String sharing, String roomType,
                                       long maxRent, List<String> facilities) {
        Results exact = collect(hostels, rooms, location, sharing, roomType, maxRent, facilities);
        if (!exact.entries.isEmpty()) {
            exact.exact = true;
            return exact;
        }
        Results everything = collect(hostels, rooms, "", "", "", 0, null);
        everything.exact = false;
        return everything;
    }

    private static Results collect(List<Hostel> hostels, List<Room> rooms,
                                   String location, String sharing, String roomType,
                                   long maxRent, List<String> facilities) {
        Results out = new Results();
        for (Hostel hostel : hostels) {
            if (!hostelMatches(hostel, location, facilities)) {
                continue;
            }
            Entry entry = new Entry();
            entry.hostel = hostel;
            for (Room room : rooms) {
                String roomsHostel = room.hostelId == null ? null : room.hostelId.trim();
                // A room whose hostelId matches no hostel (a typo) is simply never shown
                if (hostel.id != null && hostel.id.equals(roomsHostel)
                        && roomMatches(room, sharing, roomType, maxRent)) {
                    entry.rooms.add(room);
                }
            }
            if (entry.rooms.isEmpty()) {
                continue;   // nothing bookable here
            }
            // Cheapest room first inside a hostel
            Collections.sort(entry.rooms, (a, b) -> a.rentPerMonth != b.rentPerMonth
                    ? Long.compare(a.rentPerMonth, b.rentPerMonth)
                    : String.valueOf(a.id).compareTo(String.valueOf(b.id)));
            out.entries.add(entry);
        }
        // Hostels with the cheapest room first, then by name
        Collections.sort(out.entries, (a, b) -> {
            long rentA = a.rooms.get(0).rentPerMonth;
            long rentB = b.rooms.get(0).rentPerMonth;
            if (rentA != rentB) {
                return Long.compare(rentA, rentB);
            }
            return String.valueOf(a.hostel.name).compareToIgnoreCase(String.valueOf(b.hostel.name));
        });
        return out;
    }

    // <<< PURE-HELPERS-END

    /**
     * A fragment must have an EMPTY constructor (Android recreates it by itself after a rotation),
     * so everything it needs travels in a Bundle of arguments instead.
     */
    public static HostelSearchResultDialogFragment newInstance(@Nullable String location,
                                                               @Nullable String sharing,
                                                               @Nullable String roomType,
                                                               long maxRent,
                                                               @Nullable List<String> facilities) {
        Bundle args = new Bundle();
        args.putString(ARG_LOCATION, location == null ? "" : location);
        args.putString(ARG_SHARING, sharing == null ? "" : sharing);
        args.putString(ARG_ROOM_TYPE, roomType == null ? "" : roomType);
        args.putLong(ARG_MAX_RENT, maxRent);
        args.putStringArray(ARG_FACILITIES, facilities == null
                ? new String[0] : facilities.toArray(new String[0]));

        HostelSearchResultDialogFragment fragment = new HostelSearchResultDialogFragment();
        fragment.setArguments(args);
        return fragment;
    }

    // ================================================================ building the dialog

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        if (args != null) {
            location = args.getString(ARG_LOCATION, "");
            sharing = args.getString(ARG_SHARING, "");
            roomType = args.getString(ARG_ROOM_TYPE, "");
            maxRent = args.getLong(ARG_MAX_RENT, 0);
            String[] chosen = args.getStringArray(ARG_FACILITIES);
            facilities.clear();
            if (chosen != null) {
                Collections.addAll(facilities, chosen);
            }
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        list = new LinearLayout(requireContext());
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(24), dp(8), dp(24), dp(8));

        ScrollView scroll = new ScrollView(requireContext());
        scroll.setMinimumHeight(dp(220));   // so the dialog doesn't jump in size for short messages
        scroll.addView(list);

        render();   // shows "Loading hostels…" until the first answers arrive

        return new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Hostel Search Result")
                .setView(scroll)
                .setPositiveButton("Close", null)
                .create();
    }

    // ================================================================ live data

    // The live connections start when the dialog becomes visible...
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

    private void attachListeners() {
        detachListeners();   // never keep two connections open
        loadFailed = false;

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
        if (!isAdded() || list == null) {
            return;   // the dialog closed while we waited
        }
        if (error != null || snap == null) {
            // (PERMISSION_DENIED means the "hostels" read rule is not published yet)
            Log.e(TAG, "Hostels listener failed", error);
            loadFailed = true;
            render();
            return;
        }
        hostels.clear();
        for (DocumentSnapshot doc : snap.getDocuments()) {
            Hostel hostel = parseHostel(doc);
            if (hostel != null) {
                hostels.add(hostel);
            }
        }
        hostelsLoaded = true;
        render();
    }

    private void onRooms(@Nullable QuerySnapshot snap, @Nullable Exception error) {
        if (!isAdded() || list == null) {
            return;
        }
        if (error != null || snap == null) {
            Log.e(TAG, "Rooms listener failed", error);
            loadFailed = true;
            render();
            return;
        }
        rooms.clear();
        for (DocumentSnapshot doc : snap.getDocuments()) {
            Room room = parseRoom(doc);
            if (room != null) {
                rooms.add(room);
            }
        }
        roomsLoaded = true;
        render();
    }

    private void onBookings(@Nullable QuerySnapshot snap, @Nullable Exception error) {
        if (!isAdded() || list == null) {
            return;
        }
        if (error != null || snap == null) {
            // Not fatal: the list still works. (The screen and the security rules check again on save.)
            Log.e(TAG, "Bookings listener failed", error);
            return;
        }
        boolean active = false;
        for (DocumentSnapshot doc : snap.getDocuments()) {
            try {
                String status = norm(doc.getString("status"));
                if (status.equals("pending") || status.equals("approved")) {
                    active = true;
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "Skipping bad booking document: " + doc.getId(), e);
            }
        }
        hasActiveBooking = active;
        render();
    }

    private static Hostel parseHostel(DocumentSnapshot doc) {
        // One badly typed document (for example "active" saved as TEXT) must not crash the whole
        // dialog, so each document gets its own safety net.
        try {
            Hostel hostel = new Hostel();
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
            Log.e(TAG, "Skipping bad hostel document: " + doc.getId(), e);
            return null;
        }
    }

    private static Room parseRoom(DocumentSnapshot doc) {
        try {
            Long rent = doc.getLong("rentPerMonth");
            if (rent == null) {
                Log.e(TAG, "Skipping room with no rentPerMonth: " + doc.getId());
                return null;
            }
            Room room = new Room();
            room.id = doc.getId();
            room.hostelId = doc.getString("hostelId");
            room.roomType = doc.getString("roomType");
            room.sharing = doc.getString("sharing");
            room.rentPerMonth = rent;
            Long slots = doc.getLong("slotsAvailable");
            room.slotsAvailable = slots == null ? 0 : slots;
            return room;
        } catch (RuntimeException e) {
            Log.e(TAG, "Skipping bad room document: " + doc.getId(), e);
            return null;
        }
    }

    // ================================================================ drawing the list

    private void render() {
        if (list == null) {
            return;
        }
        list.removeAllViews();

        if (!hostelsLoaded || !roomsLoaded) {
            addMessage(loadFailed ? "Could not load the hostels. Check your connection."
                    : "Loading hostels…", 0);
            return;
        }

        // What the student asked for, so the result is easy to check against it
        boolean filtered = anyFilter(location, sharing, roomType, maxRent, facilities);
        TextView choices = makeText(filtered
                ? "Your choices: " + describeFilters()
                : "You haven't chosen any filters.", 14, false, 0);
        list.addView(choices);

        Results results = buildResults(hostels, rooms, location, sharing, roomType, maxRent, facilities);

        if (results.entries.isEmpty()) {
            addMessage("No hostel has free rooms right now. Please check again later.", 0);
            return;
        }

        int count = results.entries.size();
        String hostelWord = count == 1 ? "hostel" : "hostels";
        if (!filtered) {
            addBanner("Showing all " + count + " available " + hostelWord + ".", COLOR_GOOD);
        } else if (results.exact) {
            addBanner(count + " " + hostelWord + " match" + (count == 1 ? "es" : "")
                    + " your choices.", COLOR_GOOD);
        } else {
            addBanner("No hostel matches all your choices exactly. Showing all " + count
                    + " available " + hostelWord + " instead.", COLOR_NOTE);
        }

        if (hasActiveBooking) {
            addBanner("You already have an active booking, so booking is switched off. "
                    + "Check My Accommodation for its status.", COLOR_BAD);
        }

        for (int i = 0; i < results.entries.size(); i++) {
            if (i > 0) {
                list.addView(divider());
            }
            list.addView(buildCard(results.entries.get(i)));
        }
    }

    /** One hostel: name, where it is, its facilities, and one row (with a Book button) per room. */
    private View buildCard(Entry entry) {
        Hostel hostel = entry.hostel;

        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, dp(12), 0, dp(12));

        addLine(card, makeText(orDash(hostel.name), 17, true, 0), 0);

        String where = labelOrRaw(HostelChoiceDialogFragment.locationLabel(norm(hostel.locationType)),
                hostel.locationType);
        if (hostel.area != null && !hostel.area.trim().isEmpty()) {
            where = where.isEmpty() ? hostel.area.trim() : where + "  ·  " + hostel.area.trim();
        }
        addLine(card, makeText(where, 14, false, 0), 2);

        StringBuilder facilityText = new StringBuilder();
        for (String facility : hostel.facilities) {
            if (facilityText.length() > 0) {
                facilityText.append("  ·  ");
            }
            facilityText.append(HostelBudgetDialogFragment.facilityLabel(norm(facility)));
        }
        addLine(card, makeText("Facilities: " + (facilityText.length() == 0 ? "none listed"
                : facilityText.toString()), 14, false, 0), 2);

        for (Room room : entry.rooms) {
            card.addView(buildRoomRow(hostel, room));
        }
        return card;
    }

    private View buildRoomRow(Hostel hostel, Room room) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        String type = labelOrRaw(HostelChoiceDialogFragment.roomTypeLabel(norm(room.roomType)), room.roomType);
        String share = labelOrRaw(HostelChoiceDialogFragment.sharingLabel(norm(room.sharing)), room.sharing);
        String text = type + "  ·  " + share
                + "\nKES " + HostelBudgetDialogFragment.formatKes(room.rentPerMonth) + " per month"
                + "  ·  " + room.slotsAvailable + " free";

        TextView info = makeText(text, 14, false, 0);
        row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        MaterialButton book = new MaterialButton(requireContext());
        book.setText("Book");
        book.setEnabled(!hasActiveBooking);
        book.setOnClickListener(v -> confirmBooking(hostel, room));
        row.addView(book, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(8);
        row.setLayoutParams(rowParams);
        return row;
    }

    // ================================================================ booking

    /** Asks "are you sure?" first, because a booking request is a commitment. */
    private void confirmBooking(Hostel hostel, Room room) {
        if (!isAdded()) {
            return;
        }
        String type = labelOrRaw(HostelChoiceDialogFragment.roomTypeLabel(norm(room.roomType)), room.roomType);
        String share = labelOrRaw(HostelChoiceDialogFragment.sharingLabel(norm(room.sharing)), room.sharing);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Confirm booking")
                .setMessage("Book a " + type + " (" + share + ") at " + orDash(hostel.name)
                        + " for KES " + HostelBudgetDialogFragment.formatKes(room.rentPerMonth)
                        + " per month?\n\nYour request will be reviewed before it is confirmed.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Send request", (dialog, which) -> {
                    // This dialog does NOT save anything. It hands the room's details to the screen
                    // under REQUEST_KEY, and the screen saves the booking request.
                    Bundle booking = new Bundle();
                    booking.putString(RESULT_HOSTEL_ID, hostel.id);
                    booking.putString(RESULT_HOSTEL_NAME, hostel.name == null ? "" : hostel.name);
                    booking.putString(RESULT_ROOM_ID, room.id);
                    booking.putString(RESULT_ROOM_TYPE, room.roomType == null ? "" : room.roomType);
                    booking.putString(RESULT_SHARING, room.sharing == null ? "" : room.sharing);
                    booking.putLong(RESULT_RENT, room.rentPerMonth);
                    getParentFragmentManager().setFragmentResult(REQUEST_KEY, booking);
                    dismiss();
                })
                .show();
    }

    // ================================================================ small helpers

    /** "Nearby (walking distance)  ·  Private (just me)  ·  Up to KES 12,000  ·  Needs Wi-Fi". */
    private String describeFilters() {
        List<String> parts = new ArrayList<>();
        String locationLabel = HostelChoiceDialogFragment.locationLabel(location);
        String sharingLabel = HostelChoiceDialogFragment.sharingLabel(sharing);
        String roomLabel = HostelChoiceDialogFragment.roomTypeLabel(roomType);
        String budget = HostelBudgetDialogFragment.summaryOf(maxRent, facilities);
        if (locationLabel != null) parts.add(locationLabel);
        if (sharingLabel != null) parts.add(sharingLabel);
        if (roomLabel != null) parts.add(roomLabel);
        if (!budget.isEmpty()) parts.add(budget);

        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (out.length() > 0) {
                out.append("  ·  ");
            }
            out.append(part);
        }
        return out.toString();
    }

    private static String labelOrRaw(@Nullable String label, @Nullable String raw) {
        if (label != null) return label;
        return raw == null ? "" : raw.trim();
    }

    /** Shows "-" instead of the word "null" when a field is missing. */
    private static String orDash(@Nullable String value) {
        return (value == null || value.trim().isEmpty()) ? "-" : value.trim();
    }

    private void addBanner(String message, int color) {
        TextView banner = makeText(message, 14, true, color);
        banner.setPadding(0, dp(12), 0, 0);
        list.addView(banner);
    }

    private void addMessage(String message, int color) {
        TextView view = makeText(message, 15, false, color);
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
}
