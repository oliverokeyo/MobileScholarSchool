package com.example.mobilescholarschool.Admin;

import android.text.InputType;

import com.example.mobilescholarschool.HostelTab.HostelData;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Add, edit and remove hostel buildings, and open the rooms inside each one. */
public class HostelListDialog extends BaseListDialog {

    @Override protected String title() { return "Hostel Buildings"; }

    @Override protected String searchHint() { return "Search hostel name or area"; }

    @Override protected String addLabel() { return "+ Add hostel"; }

    @Override protected String emptyText() { return "No hostels yet. Tap + Add hostel."; }

    @Override protected void onAdd() { form(null); }

    @Override protected void load(Loader loader) {
        FirebaseFirestore.getInstance().collection("hostels").limit(AdminRepo.MAX_DOCS).get()
                .addOnSuccessListener(snap -> {
                    List<DocumentSnapshot> docs = new ArrayList<>(snap.getDocuments());
                    java.util.Collections.sort(docs, (a, b) -> AdminRepo.str(a, "name").compareToIgnoreCase(AdminRepo.str(b, "name")));
                    List<Row> rows = new ArrayList<>();
                    for (DocumentSnapshot d : docs) {
                        boolean active = !Boolean.FALSE.equals(d.getBoolean("active"));
                        Object fac = d.get("facilities");
                        StringBuilder fb = new StringBuilder();
                        if (fac instanceof List) {
                            for (Object o : (List<?>) fac) {
                                fb.append(fb.length() > 0 ? ", " : "").append(HostelData.facilityLabel(String.valueOf(o)));
                            }
                        }
                        String facilities = fb.toString();
                        Row r = new Row();
                        r.id = d.getId();
                        r.data = d;
                        r.title = AdminRepo.str(d, "name");
                        String loc = HostelData.locationLabel(AdminRepo.str(d, "locationType"));
                        r.subtitle = (loc == null ? AdminUi.cap(AdminRepo.str(d, "locationType")) : loc) + "  ·  " + AdminRepo.str(d, "area");
                        r.extra = "Warden: " + (AdminRepo.str(d, "wardenName").isEmpty() ? "-" : AdminRepo.str(d, "wardenName"))
                                + "  ·  " + (facilities.isEmpty() ? "no facilities listed" : facilities);
                        r.status = active ? "Active" : "Inactive";
                        r.statusColor = active ? AdminUi.GREEN : AdminUi.GREY;
                        r.search = (r.title + " " + AdminRepo.str(d, "area")).toLowerCase(Locale.ROOT);
                        rows.add(r);
                    }
                    loader.done(rows);
                })
                .addOnFailureListener(loader::fail);
    }

    @Override protected void onRowClick(Row row) {
        DocumentSnapshot hostel = (DocumentSnapshot) row.data;
        boolean active = !Boolean.FALSE.equals(hostel.getBoolean("active"));
        choose(row.title, new String[]{"Rooms, prices & capacity", "Edit hostel",
                active ? "Mark inactive (hide from booking)" : "Mark active", "Delete hostel"}, new Runnable[]{
                () -> showChild(RoomListDialog.newInstance(hostel.getId(), row.title), "rooms"),
                () -> form(hostel),
                () -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("name", row.title);
                    m.put("active", !active);
                    AdminRepo.saveHostel(hostel.getId(), m, afterAction(active ? "Hostel hidden from students." : "Hostel is active."));
                },
                () -> confirm("Delete hostel?", "Only possible when it has no rooms.", "Delete",
                        () -> AdminRepo.deleteHostel(hostel, afterAction("Hostel deleted.")))});
    }

    private void form(DocumentSnapshot h) {
        boolean edit = h != null;
        Object fac = edit ? h.get("facilities") : null;
        List<String> facList = new ArrayList<>();
        if (fac instanceof List) for (Object o : (List<?>) fac) facList.add(String.valueOf(o));
        new FormBuilder(requireContext(), edit ? "Edit hostel" : "Add hostel")
                .text("name", "Hostel name", edit ? AdminRepo.str(h, "name") : "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS)
                .choice("location", "Location", HostelData.LOCATION_VALUES,
                        HostelData.LOCATION_LABELS, edit ? AdminRepo.str(h, "locationType") : "campus")
                .text("area", "Area / estate", edit ? AdminRepo.str(h, "area") : "", InputType.TYPE_CLASS_TEXT)
                .text("warden", "Warden's name", edit ? AdminRepo.str(h, "wardenName") : "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS)
                .multi("facilities", "Facilities", HostelData.FACILITY_VALUES,
                        HostelData.FACILITY_LABELS, facList)
                .show(edit ? "Save" : "Add", v -> {
                    String name = v.get("name");
                    if (name.isEmpty() || name.length() > 100) return "Enter the hostel name (up to 100 characters).";
                    List<String> facilities = new ArrayList<>();
                    for (String f : v.get("facilities").split(",")) {
                        if (!f.trim().isEmpty()) facilities.add(f.trim());   // already the stored values
                    }
                    Map<String, Object> m = new HashMap<>();
                    m.put("name", name);
                    m.put("locationType", v.get("location"));
                    m.put("area", v.get("area"));
                    m.put("wardenName", v.get("warden"));
                    m.put("facilities", facilities);
                    if (!edit) m.put("active", true);
                    AdminRepo.saveHostel(edit ? h.getId() : null, m, afterAction(edit ? "Hostel updated." : "Hostel added."));
                    return null;
                });
    }
}