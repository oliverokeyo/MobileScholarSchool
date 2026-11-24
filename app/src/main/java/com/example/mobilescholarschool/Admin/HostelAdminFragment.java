package com.example.mobilescholarschool.Admin;

import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.mobilescholarschool.R;
import com.google.firebase.firestore.AggregateSource;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

/** Tab 2: Hostel Management. */
public class HostelAdminFragment extends AdminScreenFragment {

    private TextView[] stats;   // Hostels, Free places, Occupied, Pending

    @Override protected String screenTitle() { return getString(R.string.admin_title_hostel); }

    @Override protected String screenSubtitle() { return "Buildings, rooms, applications and availability"; }

    @Override protected void build(LinearLayout content) {
        AdminUi.sectionTitle(requireContext(), content, "Accommodation overview");
        stats = AdminUi.statRow(requireContext(), content, "Hostels", "Free places", "Occupied", "Pending");
        AdminUi.sectionTitle(requireContext(), content, "Hostel services");
        AdminUi.serviceRow(requireContext(), content, R.drawable.ic_hostel, "Hostels & Rooms",
                "Add buildings and rooms, set types, prices and capacity.", v -> open(new HostelListDialog(), "hostels"));
        AdminUi.serviceRow(requireContext(), content, R.drawable.ic_requestedbooks, "Applications",
                "View, approve or reject, and allocate students to rooms.", v -> open(new BookingListDialog(), "applications"));
        AdminUi.serviceRow(requireContext(), content, R.drawable.ic_units, "Room Availability",
                "Track free places in every room.", v -> open(RoomListDialog.newInstance(null, null), "availability"));
    }

    private void open(androidx.fragment.app.DialogFragment d, String tag) {
        if (getChildFragmentManager().findFragmentByTag(tag) == null) d.show(getChildFragmentManager(), tag);
    }

    @Override protected void refresh() {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.collection("hostels").count().get(AggregateSource.SERVER)
                .addOnSuccessListener(s -> { if (isAdded()) stats[0].setText(String.valueOf(s.getCount())); });
        db.collection("hostelBookings").whereEqualTo("status", "pending").count().get(AggregateSource.SERVER)
                .addOnSuccessListener(s -> { if (isAdded()) stats[3].setText(String.valueOf(s.getCount())); });
        db.collection("rooms").limit(AdminRepo.MAX_DOCS).get().addOnSuccessListener(s -> {
            if (!isAdded()) return;
            long free = 0, occupied = 0;
            for (DocumentSnapshot d : s.getDocuments()) {
                long f = AdminRepo.num(d, "slotsAvailable"), cap = AdminRepo.num(d, "capacity");
                free += f;
                if (cap > 0) occupied += Math.max(0, cap - f);
            }
            stats[1].setText(String.valueOf(free));
            stats[2].setText(String.valueOf(occupied));
        });
    }
}
