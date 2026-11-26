package com.example.mobilescholarschool.HostelTab;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Everything the Hostel screens SHARE that is not about drawing: the words, the money helpers,
 * the Hostel / Room / Entry classes and the rules that decide which hostels and rooms match.
 *
 * LESSON: PURE JAVA. There is not one line of Android in this file (no Context, no View, no
 * Firebase). That means it can be unit-tested on a laptop in milliseconds, and every screen can
 * reuse it. It replaces the "pure helpers" that used to be copy-pasted inside three different
 * dialog classes (HostelChoiceDialogFragment, HostelBudgetDialogFragment and
 * HostelSearchResultDialogFragment, which are now deleted).
 *
 * The VALUES are exactly what is stored in Firestore (lowercase). The LABELS are what the
 * student reads. Each pair of arrays must stay in the same order.
 */
public final class HostelData {

    private HostelData() { }   // nobody should create one: it only holds static helpers

    // ================================================================ the words

    public static final String[] LOCATION_VALUES = {"campus", "nearby", "off-campus"};
    public static final String[] LOCATION_LABELS = {"On campus", "Nearby (walking distance)", "Off-campus"};

    public static final String[] SHARING_VALUES = {"private", "shared"};
    public static final String[] SHARING_LABELS = {"Private (just me)", "Shared (with other students)"};

    public static final String[] ROOM_TYPE_VALUES = {"bedsitter", "single", "one-bedroom", "shared"};
    public static final String[] ROOM_TYPE_LABELS = {"Bedsitter", "Single room", "One-bedroom", "Shared room"};
    /** The plural headings used when rooms are grouped by type. */
    public static final String[] ROOM_TYPE_HEADINGS = {"Bedsitters", "Single rooms", "One-bedrooms", "Shared room spaces"};

    public static final String[] FACILITY_VALUES = {"wifi", "water", "security", "laundry", "parking", "kitchen"};
    public static final String[] FACILITY_LABELS = {"Wi-Fi", "Water", "Security", "Laundry", "Parking", "Kitchen"};

    /** The most a student can type (KES per month). Stops a slip like an extra row of zeros. */
    public static final long MAX_RENT_LIMIT = 1_000_000L;

    /** Lower-case and trimmed, so "Campus " and "campus" count as the same word. */
    public static String norm(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** Position of a value in the list, or -1 when it is blank or unknown. */
    public static int indexOf(String[] values, String value) {
        String wanted = norm(value);
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(wanted)) {
                return i;
            }
        }
        return -1;
    }

    /** The readable label for a stored value, or null when nothing (or an unknown value) is chosen. */
    public static String labelOf(String[] values, String[] labels, String value) {
        int index = indexOf(values, value);
        return index < 0 ? null : labels[index];
    }

    public static String locationLabel(String value) {
        return labelOf(LOCATION_VALUES, LOCATION_LABELS, value);
    }

    public static String sharingLabel(String value) {
        return labelOf(SHARING_VALUES, SHARING_LABELS, value);
    }

    public static String roomTypeLabel(String value) {
        return labelOf(ROOM_TYPE_VALUES, ROOM_TYPE_LABELS, value);
    }

    /** The readable label for a stored facility, or the value itself if it is unknown. */
    public static String facilityLabel(String value) {
        String label = labelOf(FACILITY_VALUES, FACILITY_LABELS, value);
        return label != null ? label : (value == null ? "" : value.trim());
    }

    /** "Wi-Fi  ·  Water  ·  Security", or "" when the list is empty. */
    public static String facilitiesText(List<String> facilities) {
        StringBuilder out = new StringBuilder();
        if (facilities != null) {
            for (String facility : facilities) {
                if (out.length() > 0) {
                    out.append("  ·  ");
                }
                out.append(facilityLabel(facility));
            }
        }
        return out.toString();
    }

    /** True when the value is one of the three locations we know how to show. */
    public static boolean isKnownLocation(String value) {
        return indexOf(LOCATION_VALUES, value) >= 0;
    }

    // ================================================================ money

    /**
     * Turns what the student typed into a rent.
     *   0  = nothing typed (no limit)
     *  -1  = not a valid amount
     *  else the amount in KES. It forgives "12,000" and spaces.
     */
    public static long parseRent(String raw) {
        String clean = raw == null ? "" : raw.replaceAll("[\\s,]", "");
        if (clean.isEmpty()) {
            return 0;
        }
        if (!clean.matches("[0-9]{1,9}")) {
            return -1;
        }
        long amount = Long.parseLong(clean);
        if (amount < 1 || amount > MAX_RENT_LIMIT) {
            return -1;
        }
        return amount;
    }

    /** 12000 becomes "12,000". Always commas, whatever the phone's language. */
    public static String formatKes(long amount) {
        return String.format(Locale.US, "%,d", amount);
    }

    /** How far above a budget we still make a "little over budget" suggestion: 20% more. */
    public static long suggestionCeiling(long maxRent) {
        return maxRent + maxRent / 5;
    }

    // ================================================================ the data classes

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

    // ================================================================ the matching rules

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

    /**
     * Room-level checks: a free place, the chosen sharing and type, and a rent in range.
     * A blank sharing / roomType means "any". maxRent 0 means "no upper limit".
     * minRentExclusive lets a screen ask for "more than X" (used by the budget suggestions).
     */
    public static boolean roomMatches(Room room, String sharing, String roomType,
                                      long minRentExclusive, long maxRent) {
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
        if (room.rentPerMonth <= minRentExclusive) {
            return false;
        }
        return maxRent <= 0 || room.rentPerMonth <= maxRent;
    }

    /**
     * THE HEART OF EVERY SCREEN. Returns the hostels that pass the hostel-level checks, each with
     * only the rooms that pass the room-level checks.
     *
     * @param requireRooms true  = drop a hostel that has no matching room (use this when the
     *                             student is looking for something to BOOK);
     *                     false = keep it, with an empty room list (use this when the screen is
     *                             simply listing hostels).
     *
     * Rooms are cheapest-first inside a hostel. Hostels come out cheapest-room-first, with the
     * ones that have no matching room last (by name).
     */
    public static List<Entry> entries(List<Hostel> hostels, List<Room> rooms,
                                      String location, String sharing, String roomType,
                                      long minRentExclusive, long maxRent,
                                      List<String> facilities, boolean requireRooms) {
        List<Entry> out = new ArrayList<>();
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
                        && roomMatches(room, sharing, roomType, minRentExclusive, maxRent)) {
                    entry.rooms.add(room);
                }
            }
            if (requireRooms && entry.rooms.isEmpty()) {
                continue;   // nothing bookable here
            }
            Collections.sort(entry.rooms, (a, b) -> a.rentPerMonth != b.rentPerMonth
                    ? Long.compare(a.rentPerMonth, b.rentPerMonth)
                    : String.valueOf(a.id).compareTo(String.valueOf(b.id)));
            out.add(entry);
        }
        Collections.sort(out, (a, b) -> {
            boolean aHas = !a.rooms.isEmpty();
            boolean bHas = !b.rooms.isEmpty();
            if (aHas != bHas) {
                return aHas ? -1 : 1;
            }
            if (aHas && a.rooms.get(0).rentPerMonth != b.rooms.get(0).rentPerMonth) {
                return Long.compare(a.rooms.get(0).rentPerMonth, b.rooms.get(0).rentPerMonth);
            }
            return String.valueOf(a.hostel.name).compareToIgnoreCase(String.valueOf(b.hostel.name));
        });
        return out;
    }

    /** Re-orders entries A to Z by hostel name (for the screens that simply list hostels). */
    public static void sortByName(List<Entry> entries) {
        Collections.sort(entries, (a, b) ->
                String.valueOf(a.hostel.name).compareToIgnoreCase(String.valueOf(b.hostel.name)));
    }

    /**
     * Does this hostel match what the student typed in the search box? It looks at the name, the
     * area and the location wording ("campus", "On campus"...). Blank text matches everything.
     */
    public static boolean matchesQuery(Hostel hostel, String query) {
        String wanted = norm(query);
        if (wanted.isEmpty()) {
            return true;
        }
        return norm(hostel.name).contains(wanted)
                || norm(hostel.area).contains(wanted)
                || norm(hostel.locationType).contains(wanted)
                || norm(locationLabel(hostel.locationType)).contains(wanted);
    }

    /** The cheapest rent among all rooms that can be booked right now, or -1 if there is none. */
    public static long cheapestRent(List<Hostel> hostels, List<Room> rooms) {
        long best = -1;
        for (Entry entry : entries(hostels, rooms, "", "", "", 0, 0, null, true)) {
            long rent = entry.rooms.get(0).rentPerMonth;   // rooms are sorted cheapest-first
            if (best < 0 || rent < best) {
                best = rent;
            }
        }
        return best;
    }
}
