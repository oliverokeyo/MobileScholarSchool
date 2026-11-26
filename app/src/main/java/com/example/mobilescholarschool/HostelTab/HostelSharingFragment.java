package com.example.mobilescholarschool.HostelTab;

import androidx.annotation.NonNull;

import android.widget.LinearLayout;

import java.util.List;

/**
 * "Sharing Preference": the rooms that can be booked, split into PRIVATE (just me) and
 * SHARED (with other students). Every room has a Book button.
 */
public class HostelSharingFragment extends HostelBaseFragment {

    public static final String TAG = "HostelSharingFragment";

    public static HostelSharingFragment newInstance() {
        return new HostelSharingFragment();
    }

    @Override
    protected String screenTitle() {
        return "Sharing preference";
    }

    @Override
    protected void renderLoaded(@NonNull LinearLayout out) {
        for (int i = 0; i < HostelData.SHARING_VALUES.length; i++) {
            // requireRooms = true: here we are looking for something to BOOK, so a hostel with
            // no free room of this kind is left out. Each card shows only the matching rooms.
            List<HostelData.Entry> group = HostelData.entries(hostels, rooms,
                    "", HostelData.SHARING_VALUES[i], "", 0, 0, null, true);
            addSection(out, HostelData.SHARING_LABELS[i], group,
                    "No free " + HostelData.SHARING_VALUES[i] + " rooms right now.");
        }
    }
}
