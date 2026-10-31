package com.example.mobilescholarschool.HostelTab;

import android.app.Dialog;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;


public class HostelChoiceDialogFragment extends DialogFragment {

    public static final String TAG = "HostelChoiceDialog";

    // ---- the keys the screen listens for (one per dialog) -----------------------------
    public static final String KEY_LOCATION = "hostel_location";
    public static final String KEY_SHARING = "hostel_sharing";
    public static final String KEY_ROOM_TYPE = "hostel_room_type";

    // ---- what comes back ---------------------------------------------------------------
    public static final String RESULT_VALUE = "result_value";   // "" means "Any"

    private static final String ARG_KEY = "arg_key";
    private static final String ARG_TITLE = "arg_title";
    private static final String ARG_ANY_LABEL = "arg_any_label";
    private static final String ARG_VALUES = "arg_values";
    private static final String ARG_LABELS = "arg_labels";
    private static final String ARG_SELECTED = "arg_selected";


    public static final String[] LOCATION_VALUES = {"campus", "nearby", "off-campus"};
    public static final String[] LOCATION_LABELS = {"On campus", "Nearby (walking distance)", "Off-campus"};

    public static final String[] SHARING_VALUES = {"private", "shared"};
    public static final String[] SHARING_LABELS = {"Private (just me)", "Shared (with other students)"};

    public static final String[] ROOM_TYPE_VALUES = {"bedsitter", "single", "one-bedroom", "shared"};
    public static final String[] ROOM_TYPE_LABELS = {"Bedsitter", "Single room", "One-bedroom", "Shared room"};

    /** Position of a value in the list, or -1 when it is blank or unknown. */
    public static int indexOf(String[] values, String value) {
        if (value == null) {
            return -1;
        }
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
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

    // <<< PURE-HELPERS-END

    // ================================================================ the three dialogs

    /** @param selected the current choice ("campus"...), or "" / null for Any */
    public static HostelChoiceDialogFragment forLocation(@Nullable String selected) {
        return create(KEY_LOCATION, "Hostel location", "Any location",
                LOCATION_VALUES, LOCATION_LABELS, selected);
    }

    public static HostelChoiceDialogFragment forSharing(@Nullable String selected) {
        return create(KEY_SHARING, "Sharing preference", "No preference",
                SHARING_VALUES, SHARING_LABELS, selected);
    }

    public static HostelChoiceDialogFragment forRoomType(@Nullable String selected) {
        return create(KEY_ROOM_TYPE, "Room type", "Any room type",
                ROOM_TYPE_VALUES, ROOM_TYPE_LABELS, selected);
    }

    
    private static HostelChoiceDialogFragment create(String requestKey, String title, String anyLabel,
                                                     String[] values, String[] labels,
                                                     @Nullable String selected) {
        Bundle args = new Bundle();
        args.putString(ARG_KEY, requestKey);
        args.putString(ARG_TITLE, title);
        args.putString(ARG_ANY_LABEL, anyLabel);
        args.putStringArray(ARG_VALUES, values);
        args.putStringArray(ARG_LABELS, labels);
        args.putString(ARG_SELECTED, selected == null ? "" : selected);

        HostelChoiceDialogFragment fragment = new HostelChoiceDialogFragment();
        fragment.setArguments(args);
        return fragment;
    }

    // ================================================================ building the dialog

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Bundle args = requireArguments();
        String requestKey = args.getString(ARG_KEY, "");
        String[] values = args.getStringArray(ARG_VALUES);
        String[] labels = args.getStringArray(ARG_LABELS);
        if (values == null || labels == null) {
            values = new String[0];
            labels = new String[0];
        }
        final String[] finalValues = values;   // the click listener below needs a final copy

        // The list shown: "Any" first, then each real choice
        String[] items = new String[labels.length + 1];
        items[0] = args.getString(ARG_ANY_LABEL, "Any");
        System.arraycopy(labels, 0, items, 1, labels.length);

        // Which row starts selected: the current choice, or "Any" (row 0) if there is none
        int checked = indexOf(values, args.getString(ARG_SELECTED, "")) + 1;

        return new MaterialAlertDialogBuilder(requireContext())
                .setTitle(args.getString(ARG_TITLE, ""))
                .setSingleChoiceItems(items, checked, (dialog, which) -> {
                    Bundle result = new Bundle();
                    // Row 0 is "Any", which means "don't filter": an empty string
                    result.putString(RESULT_VALUE, which == 0 ? "" : finalValues[which - 1]);
                    getParentFragmentManager().setFragmentResult(requestKey, result);
                    dismiss();
                })
                .setNegativeButton("Cancel", null)   // closes the dialog and changes nothing
                .create();
    }
}
