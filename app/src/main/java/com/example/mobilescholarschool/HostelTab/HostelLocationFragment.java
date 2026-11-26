package com.example.mobilescholarschool.HostelTab;

import androidx.annotation.NonNull;

import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * "Hostel Location": every hostel, grouped by WHERE it is (on campus, nearby, off-campus).
 * Each hostel card shows its area, its facilities and its free rooms with a Book button.
 *
 * LESSON: no filter is chosen here and nothing is remembered. The screen is a ready-made
 * answer to the question "where are the hostels?". Compare this with the old design, where
 * a choice made on one dialog was silently combined with three others.
 */
public class HostelLocationFragment extends HostelBaseFragment {

    public static final String TAG = "HostelLocationFragment";

    public static HostelLocationFragment newInstance() {
        return new HostelLocationFragment();
    }

    @Override
    protected String screenTitle() {
        return "Hostel location";
    }

    @Override
    protected void renderLoaded(@NonNull LinearLayout out) {
        // One section per known location. requireRooms = false: we are LISTING hostels here,
        // so a hostel with no free room still appears (and says so).
        for (int i = 0; i < HostelData.LOCATION_VALUES.length; i++) {
            List<HostelData.Entry> group = HostelData.entries(hostels, rooms,
                    HostelData.LOCATION_VALUES[i], "", "", 0, 0, null, false);
            HostelData.sortByName(group);
            addSection(out, HostelData.LOCATION_LABELS[i], group, "No hostels listed here yet.");
        }

        // A hostel saved with a mistyped location (say "Campuss") would match none of the
        // sections above and vanish. This catches it, so nothing is ever silently hidden.
        List<HostelData.Entry> unplaced = new ArrayList<>();
        for (HostelData.Entry entry : HostelData.entries(hostels, rooms, "", "", "", 0, 0, null, false)) {
            if (!HostelData.isKnownLocation(entry.hostel.locationType)) {
                unplaced.add(entry);
            }
        }
        if (!unplaced.isEmpty()) {
            HostelData.sortByName(unplaced);
            addSection(out, "Other locations", unplaced, "");
        }
    }
}
