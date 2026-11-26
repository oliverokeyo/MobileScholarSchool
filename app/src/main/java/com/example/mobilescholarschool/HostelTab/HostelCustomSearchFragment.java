package com.example.mobilescholarschool.HostelTab;

import android.graphics.Rect;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * "Custom Search": the ONE screen that combines choices. The student picks, with radio buttons,
 * a location, a sharing preference and a room type, optionally types a maximum rent and ticks the
 * facilities they need, then taps "Find hostels".
 *
 * It shows hostels ONLY if they match everything chosen. If none do, it says so (it does not
 * quietly show something else). Every room found has a Book button.
 *
 * LESSON: the form and the results live on the same scrolling screen. The form is drawn once
 * (onViewCreated). The results go in their own box (`results`) that is cleared and redrawn,
 * because we override out() to point the parent class at that box instead of the whole list.
 */
public class HostelCustomSearchFragment extends HostelBaseFragment {

    public static final String TAG = "HostelCustomSearchFragment";

    // Fixed IDs so ticks and typed text survive a rotation. Each radio group gets a block of IDs:
    // base = "Any", base+1 = the first real choice, and so on.
    private static final int ID_LOCATION = 4501;
    private static final int ID_SHARING = 4511;
    private static final int ID_ROOM = 4521;
    private static final int ID_RENT = 4530;
    private static final int ID_FACILITY_BASE = 4540;

    private static final String STATE_SEARCHED = "state_searched";
    private static final String STATE_LOCATION = "state_location";
    private static final String STATE_SHARING = "state_sharing";
    private static final String STATE_ROOM = "state_room";
    private static final String STATE_RENT = "state_rent";
    private static final String STATE_FACILITIES = "state_facilities";

    private RadioGroup groupLocation, groupSharing, groupRoom;
    private TextInputLayout tilRent;
    private EditText edtRent;
    private final List<MaterialCheckBox> facilityBoxes = new ArrayList<>();
    private LinearLayout results;

    // The choices AT THE MOMENT "Find hostels" was tapped. Results are drawn from these, so
    // fiddling with the radio buttons afterwards changes nothing until the student taps again.
    private boolean searched = false;
    private String foundLocation = "";
    private String foundSharing = "";
    private String foundRoomType = "";
    private long foundMaxRent = 0;
    private final List<String> foundFacilities = new ArrayList<>();

    public static HostelCustomSearchFragment newInstance() {
        return new HostelCustomSearchFragment();
    }

    @Override
    protected String screenTitle() {
        return "Custom search";
    }

    @Nullable
    @Override
    protected LinearLayout out() {
        return results;   // draw into the results box, not over the form
    }

    // ================================================================ the form

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        groupLocation = addRadioGroup("Hostel location", "Any location",
                HostelData.LOCATION_LABELS, ID_LOCATION);
        groupSharing = addRadioGroup("Sharing preference", "No preference",
                HostelData.SHARING_LABELS, ID_SHARING);
        groupRoom = addRadioGroup("Room type", "Any room type",
                HostelData.ROOM_TYPE_LABELS, ID_ROOM);

        // ---- the amount box ----
        addSectionTitle(list, "Maximum rent");
        tilRent = new TextInputLayout(requireContext(), null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        tilRent.setHint("Maximum rent per month (KES)");
        tilRent.setHelperText("Per student. Leave empty for no limit.");
        TextInputEditText rentEdit = new TextInputEditText(tilRent.getContext());
        rentEdit.setId(ID_RENT);
        rentEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        rentEdit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(9)});
        tilRent.addView(rentEdit, new LinearLayout.LayoutParams(MATCH, WRAP));
        addLine(list, tilRent, 8);
        edtRent = rentEdit;

        // ---- the facilities ----
        addSectionTitle(list, "The hostel must have");
        facilityBoxes.clear();
        for (int i = 0; i < HostelData.FACILITY_VALUES.length; i++) {
            MaterialCheckBox box = new MaterialCheckBox(requireContext());
            box.setId(ID_FACILITY_BASE + i);   // fixed ID, so a tick survives rotation
            box.setText(HostelData.FACILITY_LABELS[i]);
            facilityBoxes.add(box);
            list.addView(box, new LinearLayout.LayoutParams(MATCH, WRAP));
        }

        // ---- the buttons ----
        LinearLayout buttons = new LinearLayout(requireContext());
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        MaterialButton find = new MaterialButton(requireContext());
        find.setText("Find hostels");
        find.setOnClickListener(v -> search());
        buttons.addView(find, new LinearLayout.LayoutParams(0, WRAP, 1f));

        MaterialButton reset = new MaterialButton(requireContext(), null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        reset.setText("Reset");
        reset.setOnClickListener(v -> reset());
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        resetParams.setMarginStart(dp(8));
        buttons.addView(reset, resetParams);
        addLine(list, buttons, 20);

        // ---- the results box, under the form ----
        results = new LinearLayout(requireContext());
        results.setOrientation(LinearLayout.VERTICAL);
        list.addView(results, new LinearLayout.LayoutParams(MATCH, WRAP));

        // After a rotation, bring back the answer that was on screen
        if (savedInstanceState != null) {
            searched = savedInstanceState.getBoolean(STATE_SEARCHED, false);
            foundLocation = savedInstanceState.getString(STATE_LOCATION, "");
            foundSharing = savedInstanceState.getString(STATE_SHARING, "");
            foundRoomType = savedInstanceState.getString(STATE_ROOM, "");
            foundMaxRent = savedInstanceState.getLong(STATE_RENT, 0);
            foundFacilities.clear();
            ArrayList<String> saved = savedInstanceState.getStringArrayList(STATE_FACILITIES);
            if (saved != null) {
                foundFacilities.addAll(saved);
            }
        }
        // (HostelBaseFragment.onStart() draws the results once the data is ready.)
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_SEARCHED, searched);
        outState.putString(STATE_LOCATION, foundLocation);
        outState.putString(STATE_SHARING, foundSharing);
        outState.putString(STATE_ROOM, foundRoomType);
        outState.putLong(STATE_RENT, foundMaxRent);
        outState.putStringArrayList(STATE_FACILITIES, new ArrayList<>(foundFacilities));
    }

    @Override
    public void onDestroyView() {
        results = null;
        facilityBoxes.clear();
        super.onDestroyView();
    }

    /**
     * One set of radio buttons: a title, "Any" first, then one button per real choice.
     * LESSON: a RadioGroup lets only ONE of its buttons be ticked at a time.
     */
    private RadioGroup addRadioGroup(String title, String anyLabel, String[] labels, int baseId) {
        addSectionTitle(list, title);
        RadioGroup group = new RadioGroup(requireContext());
        group.setOrientation(RadioGroup.VERTICAL);
        group.addView(newRadio(anyLabel, baseId), new RadioGroup.LayoutParams(MATCH, WRAP));
        for (int i = 0; i < labels.length; i++) {
            group.addView(newRadio(labels[i], baseId + 1 + i), new RadioGroup.LayoutParams(MATCH, WRAP));
        }
        list.addView(group, new LinearLayout.LayoutParams(MATCH, WRAP));
        group.check(baseId);   // "Any" is ticked to begin with
        return group;
    }

    private MaterialRadioButton newRadio(String label, int id) {
        MaterialRadioButton button = new MaterialRadioButton(requireContext());
        button.setId(id);
        button.setText(label);
        return button;
    }

    // ================================================================ search and reset

    private void search() {
        long rent = HostelData.parseRent(edtRent.getText() == null ? "" : edtRent.getText().toString());
        if (rent < 0) {
            tilRent.setError("Enter a whole amount from 1 to " + HostelData.formatKes(HostelData.MAX_RENT_LIMIT));
            return;   // stay here so the student can fix the amount
        }
        tilRent.setError(null);

        // Take a snapshot of what is ticked right now
        foundLocation = selectedValue(groupLocation, ID_LOCATION, HostelData.LOCATION_VALUES);
        foundSharing = selectedValue(groupSharing, ID_SHARING, HostelData.SHARING_VALUES);
        foundRoomType = selectedValue(groupRoom, ID_ROOM, HostelData.ROOM_TYPE_VALUES);
        foundMaxRent = rent;
        foundFacilities.clear();
        for (int i = 0; i < facilityBoxes.size(); i++) {
            if (facilityBoxes.get(i).isChecked()) {
                foundFacilities.add(HostelData.FACILITY_VALUES[i]);
            }
        }
        searched = true;

        hideKeyboard();
        render();

        // The results are below the form, so scroll them into view.
        if (results != null) {
            results.post(() -> {
                if (results != null) {
                    results.requestRectangleOnScreen(new Rect(0, 0, results.getWidth(),
                            Math.max(1, Math.min(results.getHeight(), dp(160)))), false);
                }
            });
        }
    }

    private void reset() {
        groupLocation.check(ID_LOCATION);
        groupSharing.check(ID_SHARING);
        groupRoom.check(ID_ROOM);
        edtRent.setText("");
        tilRent.setError(null);
        for (MaterialCheckBox box : facilityBoxes) {
            box.setChecked(false);
        }
        searched = false;
        render();
    }

    /** The stored value of the ticked radio button, or "" for "Any". */
    private static String selectedValue(RadioGroup group, int baseId, String[] values) {
        int index = group.getCheckedRadioButtonId() - baseId - 1;   // "Any" gives -1
        return (index >= 0 && index < values.length) ? values[index] : "";
    }

    // ================================================================ the results

    @Override
    protected void renderLoaded(@NonNull LinearLayout out) {
        if (!searched) {
            addMessage(out, "Choose what you need above, then tap \"Find hostels\".");
            return;
        }

        addLine(out, makeText("Your choices: " + describeChoices(), 14, false, 0), 16);

        // requireRooms = true: only hostels with a free room that matches EVERYTHING are returned.
        List<HostelData.Entry> found = HostelData.entries(hostels, rooms, foundLocation, foundSharing,
                foundRoomType, 0, foundMaxRent, foundFacilities, true);

        if (found.isEmpty()) {
            addBanner(out, "No hostel with a free room matches all your choices. "
                    + "Try changing or removing one of them.", COLOR_NOTE);
            return;
        }
        addBanner(out, found.size() + (found.size() == 1 ? " hostel matches" : " hostels match")
                + " your choices.", COLOR_GOOD);
        for (HostelData.Entry entry : found) {
            addHostelCard(out, entry);
        }
    }

    /** "Nearby (walking distance)  ·  Private (just me)  ·  Up to KES 12,000  ·  Needs Wi-Fi". */
    private String describeChoices() {
        List<String> parts = new ArrayList<>();
        String location = HostelData.locationLabel(foundLocation);
        String sharing = HostelData.sharingLabel(foundSharing);
        String room = HostelData.roomTypeLabel(foundRoomType);
        if (location != null) parts.add(location);
        if (sharing != null) parts.add(sharing);
        if (room != null) parts.add(room);
        if (foundMaxRent > 0) parts.add("Up to KES " + HostelData.formatKes(foundMaxRent));
        if (!foundFacilities.isEmpty()) {
            StringBuilder needs = new StringBuilder("Needs ");
            for (int i = 0; i < foundFacilities.size(); i++) {
                if (i > 0) needs.append(", ");
                needs.append(HostelData.facilityLabel(foundFacilities.get(i)));
            }
            parts.add(needs.toString());
        }
        if (parts.isEmpty()) {
            return "none (showing every free room)";
        }
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (out.length() > 0) out.append("  ·  ");
            out.append(part);
        }
        return out.toString();
    }
}
