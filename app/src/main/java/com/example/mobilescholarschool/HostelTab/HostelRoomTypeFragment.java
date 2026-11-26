package com.example.mobilescholarschool.HostelTab;

import androidx.annotation.NonNull;

import android.widget.LinearLayout;

import java.util.List;

/**
 * "Room Type": the rooms that can be booked, grouped as Bedsitters, Single rooms,
 * One-bedrooms and Shared room spaces. Every room has a Book button.
 */
public class HostelRoomTypeFragment extends HostelBaseFragment {

    public static final String TAG = "HostelRoomTypeFragment";

    public static HostelRoomTypeFragment newInstance() {
        return new HostelRoomTypeFragment();
    }

    @Override
    protected String screenTitle() {
        return "Room type";
    }

    @Override
    protected void renderLoaded(@NonNull LinearLayout out) {
        // The SAME loop as the Sharing screen, only the filter slot changes. The matching rules
        // live in HostelData.entries(), so a new kind of grouping is just a few lines.
        for (int i = 0; i < HostelData.ROOM_TYPE_VALUES.length; i++) {
            List<HostelData.Entry> group = HostelData.entries(hostels, rooms,
                    "", "", HostelData.ROOM_TYPE_VALUES[i], 0, 0, null, true);
            addSection(out, HostelData.ROOM_TYPE_HEADINGS[i], group,
                    "No free " + HostelData.ROOM_TYPE_HEADINGS[i].toLowerCase(java.util.Locale.ROOT)
                            + " right now.");
        }
    }
}
