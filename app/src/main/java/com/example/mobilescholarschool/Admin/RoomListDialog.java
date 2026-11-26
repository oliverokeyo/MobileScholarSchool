package com.example.mobilescholarschool.Admin;

import android.os.Bundle;
import android.text.InputType;

import com.example.mobilescholarschool.HostelTab.HostelData;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rooms: type, price, capacity and live availability. Opened for ONE hostel (can add / edit / delete
 * rooms) or for ALL hostels (the availability tracker). Students pick from these exact rooms when
 * they search for accommodation.
 */
public class RoomListDialog extends BaseListDialog {

    private static final String ARG_HOSTEL_ID = "hostelId", ARG_HOSTEL_NAME = "hostelName";

    /** hostelId == null shows every room in every hostel. */
    public static RoomListDialog newInstance(String hostelId, String hostelName) {
        RoomListDialog d = new RoomListDialog();
        Bundle b = new Bundle();
        b.putString(ARG_HOSTEL_ID, hostelId);
        b.putString(ARG_HOSTEL_NAME, hostelName);
        d.setArguments(b);
        return d;
    }

    private String hostelId() { return requireArguments().getString(ARG_HOSTEL_ID); }

    @Override protected String title() {
        return hostelId() == null ? "Room Availability" : requireArguments().getString(ARG_HOSTEL_NAME) + " · Rooms";
    }

    @Override protected String searchHint() { return "Search room number or type"; }

    @Override protected String[][] filters() {
        return new String[][]{{"", "All"}, {"available", "Has free places"}, {"full", "Full"}};
    }

    @Override protected String addLabel() { return hostelId() == null ? null : "+ Add room"; }

    @Override protected void onAdd() { form(null); }

    @Override protected String emptyText() { return hostelId() == null ? "No rooms yet." : "No rooms yet. Tap + Add room."; }

    @Override protected void load(Loader loader) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        Query q = db.collection("rooms");
        if (hostelId() != null) q = q.whereEqualTo("hostelId", hostelId());
        q.limit(AdminRepo.MAX_DOCS).get().addOnSuccessListener(rooms ->
                db.collection("hostels").limit(AdminRepo.MAX_DOCS).get().addOnSuccessListener(hostels -> {
                    Map<String, String> names = new HashMap<>();
                    for (DocumentSnapshot h : hostels.getDocuments()) names.put(h.getId(), AdminRepo.str(h, "name"));
                    List<DocumentSnapshot> docs = new ArrayList<>(rooms.getDocuments());
                    java.util.Collections.sort(docs, (a, b) -> {
                        int c = AdminRepo.str(a, "hostelId").compareTo(AdminRepo.str(b, "hostelId"));
                        return c != 0 ? c : AdminRepo.str(a, "roomNumber").compareToIgnoreCase(AdminRepo.str(b, "roomNumber"));
                    });
                    List<Row> rows = new ArrayList<>();
                    for (DocumentSnapshot d : docs) {
                        long free = AdminRepo.num(d, "slotsAvailable");
                        long cap = AdminRepo.num(d, "capacity");
                        String num = AdminRepo.str(d, "roomNumber");
                        Row r = new Row();
                        r.id = d.getId();
                        r.data = d;
                        r.title = (num.isEmpty() ? "Room" : "Room " + num) + " · " + AdminUi.cap(AdminRepo.str(d, "roomType"));
                        String hostelName = names.containsKey(AdminRepo.str(d, "hostelId")) ? names.get(AdminRepo.str(d, "hostelId")) : "Unknown hostel";
                        r.subtitle = hostelName + "  ·  " + AdminRepo.str(d, "sharing") + "  ·  "
                                + AdminUi.ksh(AdminRepo.num(d, "rentPerMonth")) + " per month";
                        r.extra = "Capacity " + (cap > 0 ? cap : "?") + "  ·  Free " + free
                                + (cap > 0 ? "  ·  Occupied " + Math.max(0, cap - free) : "");
                        String state = free > 0 ? "available" : "full";
                        r.status = free > 0 ? free + " free" : "Full";
                        r.statusColor = AdminUi.colorOfStatus(state);
                        r.tags.add(state);
                        r.search = (num + " " + AdminRepo.str(d, "roomType") + " " + hostelName).toLowerCase(Locale.ROOT);
                        rows.add(r);
                    }
                    loader.done(rows);
                }).addOnFailureListener(loader::fail)).addOnFailureListener(loader::fail);
    }

    @Override protected void onRowClick(Row row) {
        DocumentSnapshot room = (DocumentSnapshot) row.data;
        if (hostelId() == null) {
            info(row.title, row.subtitle + "\n" + row.extra);
            return;
        }
        choose(row.title, new String[]{"Edit room, price & capacity", "Delete room"}, new Runnable[]{
                () -> form(room),
                () -> confirm("Delete room?", "Only possible when nobody is allocated to it.", "Delete",
                        () -> AdminRepo.deleteRoom(room, afterAction("Room deleted.")))});
    }

    private void form(DocumentSnapshot room) {
        boolean edit = room != null;
        new FormBuilder(requireContext(), edit ? "Edit room" : "Add room")
                .text("number", "Room number", edit ? AdminRepo.str(room, "roomNumber") : "", InputType.TYPE_CLASS_TEXT)
                .choice("type", "Room type", HostelData.ROOM_TYPE_VALUES,
                        HostelData.ROOM_TYPE_LABELS, edit ? AdminRepo.str(room, "roomType") : "single")
                .choice("sharing", "Sharing", HostelData.SHARING_VALUES,
                        HostelData.SHARING_LABELS, edit ? AdminRepo.str(room, "sharing") : "private")
                .text("rent", "Rent per month (Ksh, per student)", edit ? String.valueOf(AdminRepo.num(room, "rentPerMonth")) : "", InputType.TYPE_CLASS_NUMBER)
                .text("capacity", "Capacity (number of beds)", edit ? String.valueOf(Math.max(1, AdminRepo.num(room, "capacity"))) : "1", InputType.TYPE_CLASS_NUMBER)
                .show(edit ? "Save" : "Add", v -> {
                    if (v.get("number").isEmpty()) return "Enter the room number.";
                    long rent, cap;
                    try { rent = Long.parseLong(v.get("rent")); } catch (NumberFormatException e) { return "Enter the rent as a whole number."; }
                    try { cap = Long.parseLong(v.get("capacity")); } catch (NumberFormatException e) { return "Enter the capacity as a whole number."; }
                    if (rent <= 0) return "The rent must be more than 0.";
                    if (cap < 1 || cap > 50) return "Capacity must be between 1 and 50.";
                    AdminRepo.saveRoom(edit ? room.getId() : null, hostelId(), v.get("number"), v.get("type"),
                            v.get("sharing"), rent, cap, afterAction(edit ? "Room updated." : "Room added."));
                    return null;
                });
    }
}