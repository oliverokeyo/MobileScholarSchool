package com.example.mobilescholarschool.Admin;

import android.text.InputType;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Accommodation applications. Approving ALLOCATES the student to a room (the one they asked for, or
 * another free room in the same hostel) with a check-in date; the student's Home card then shows
 * "Booked", the room number, the check-in date and the warden.
 */
public class BookingListDialog extends BaseListDialog {

    @Override protected String title() { return "Accommodation Applications"; }

    @Override protected String searchHint() { return "Search registration no. or hostel"; }

    @Override protected String[][] filters() {
        return new String[][]{{"", "All"}, {"pending", "Pending"}, {"approved", "Approved"},
                {"rejected", "Rejected"}, {"vacated", "Vacated"}};
    }

    @Override protected String initialFilter() { return "pending"; }

    @Override protected String emptyText() { return "No accommodation applications."; }

    @Override protected void load(Loader loader) {
        FirebaseFirestore.getInstance().collection("hostelBookings").limit(AdminRepo.MAX_DOCS).get()
                .addOnSuccessListener(snap -> {
                    List<DocumentSnapshot> docs = new ArrayList<>(snap.getDocuments());
                    AdminRepo.sortNewest(docs, "createdAt");
                    List<Row> rows = new ArrayList<>();
                    for (DocumentSnapshot d : docs) {
                        String status = AdminRepo.status(d);
                        Row r = new Row();
                        r.id = d.getId();
                        r.data = d;
                        r.title = "Student " + AdminRepo.str(d, "studentIdNumber");
                        r.subtitle = AdminRepo.str(d, "hostelName") + "  ·  " + AdminUi.cap(AdminRepo.str(d, "roomType"))
                                + "  ·  " + AdminRepo.str(d, "sharing") + "  ·  " + AdminUi.ksh(AdminRepo.num(d, "rentPerMonth")) + "/month";
                        r.extra = "Applied " + AdminUi.date(AdminRepo.millis(d, "createdAt"))
                                + (AdminRepo.str(d, "roomNumber").isEmpty() ? "" : "  ·  Room " + AdminRepo.str(d, "roomNumber"))
                                + (AdminRepo.millis(d, "checkInDate") > 0 ? "  ·  Check-in " + AdminUi.date(AdminRepo.millis(d, "checkInDate")) : "");
                        r.status = AdminUi.cap(status);
                        r.statusColor = status.equals("vacated") ? AdminUi.GREY : AdminUi.colorOfStatus(status);
                        r.tags.add(status);
                        r.search = (AdminRepo.str(d, "studentIdNumber") + " " + AdminRepo.str(d, "hostelName")).toLowerCase(Locale.ROOT);
                        rows.add(r);
                    }
                    loader.done(rows);
                })
                .addOnFailureListener(loader::fail);
    }

    @Override protected void onRowClick(Row row) {
        DocumentSnapshot b = (DocumentSnapshot) row.data;
        String status = AdminRepo.status(b);
        if (status.equals("pending")) {
            choose(row.title, new String[]{"Approve & allocate a room", "Reject"}, new Runnable[]{
                    () -> pickRoom(b, "Approve"),
                    () -> confirm("Reject application?", "The student will see it as rejected and can apply again.", "Reject",
                            () -> AdminRepo.rejectBooking(b, afterAction("Application rejected.")))});
        } else if (status.equals("approved")) {
            choose(row.title, new String[]{"Move to another room / change check-in", "Vacate (student leaves)"}, new Runnable[]{
                    () -> pickRoom(b, "Move"),
                    () -> confirm("Vacate room?", "The place becomes free and the student can book again.", "Vacate",
                            () -> AdminRepo.releaseBooking(b, afterAction("Room vacated.")))});
        } else {
            info(row.title, row.subtitle + "\n" + row.extra + "\nStatus: " + row.status);
        }
    }

    /** Loads the free rooms of the SAME hostel, then asks which one and the check-in date. */
    private void pickRoom(DocumentSnapshot booking, String verb) {
        FirebaseFirestore.getInstance().collection("rooms")
                .whereEqualTo("hostelId", AdminRepo.str(booking, "hostelId")).limit(AdminRepo.MAX_DOCS).get()
                .addOnSuccessListener(snap -> {
                    if (!isAdded()) return;
                    String current = AdminRepo.str(booking, "roomId");
                    List<DocumentSnapshot> options = new ArrayList<>();
                    List<String> labels = new ArrayList<>();
                    String requested = null;
                    for (DocumentSnapshot room : snap.getDocuments()) {
                        boolean isCurrent = room.getId().equals(current);
                        if (AdminRepo.num(room, "slotsAvailable") <= 0 && !isCurrent) continue;
                        String label = "Room " + AdminRepo.str(room, "roomNumber") + " · "
                                + AdminUi.cap(AdminRepo.str(room, "roomType")) + " · " + AdminRepo.str(room, "sharing")
                                + " · " + AdminUi.ksh(AdminRepo.num(room, "rentPerMonth")) + " · "
                                + AdminRepo.num(room, "slotsAvailable") + " free" + (isCurrent ? " (current/requested)" : "");
                        options.add(room);
                        labels.add(label);
                        if (isCurrent) requested = label;
                    }
                    if (options.isEmpty()) {
                        AdminUi.toast(getContext(), "No free rooms in this hostel. Add a room or free a place first.");
                        return;
                    }
                    long checkIn = AdminRepo.millis(booking, "checkInDate") > 0 ? AdminRepo.millis(booking, "checkInDate")
                            : System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000;
                    new FormBuilder(requireContext(), verb + " · allocate room")
                            .choice("room", "Room", labels.toArray(new String[0]), requested)
                            .date("checkIn", "Check-in date", checkIn)
                            .show("Allocate", v -> {
                                int index = labels.indexOf(v.get("room"));
                                long when = Long.parseLong(v.get("checkIn"));
                                if (index < 0) return "Choose a room.";
                                if (when < AdminRepo.startOfToday()) return "The check-in date cannot be in the past.";
                                AdminRepo.allocate(booking, options.get(index).getReference(), when,
                                        afterAction("Room allocated. The student sees it on their Home card."));
                                return null;
                            });
                })
                .addOnFailureListener(e -> AdminUi.toast(getContext(), AdminRepo.message(e)));
    }
}
