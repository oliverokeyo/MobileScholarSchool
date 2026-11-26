package com.example.mobilescholarschool.HostelTab;

import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.mobilescholarschool.R;

import java.util.ArrayList;
import java.util.List;

/**
 * "Find a hostel": the search bar AND every hostel on one screen.
 *
 *   - With nothing typed, it lists ALL hostels, A to Z.
 *   - As the student types, the list narrows to hostels whose name or location matches.
 *   - Every room shown has a Book button (inherited from HostelBaseFragment).
 *
 * LESSON: look how short this file is. Loading the data, drawing a hostel card and booking
 * all live in the parent class. This screen only decides WHICH hostels to show.
 */
public class HostelSearchFragment extends HostelBaseFragment {

    public static final String TAG = "HostelSearchFragment";

    // A fixed ID, so the typed text survives a rotation (Android saves text by view ID).
    private static final int ID_SEARCH = 4401;

    private EditText editSearch;

    public static HostelSearchFragment newInstance() {
        return new HostelSearchFragment();
    }

    @Override
    protected String screenTitle() {
        return "Find a hostel";
    }

    @Override
    protected void onBuildHeader(@NonNull LinearLayout header, @Nullable Bundle savedInstanceState) {
        // A plain EditText: its look comes from the theme, like the one that used to sit on the
        // Hostel screen.
        editSearch = new EditText(requireContext());
        editSearch.setId(ID_SEARCH);
        editSearch.setHint("Search hostel by name or location");
        editSearch.setInputType(InputType.TYPE_CLASS_TEXT);
        editSearch.setSingleLine(true);
        editSearch.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        editSearch.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0);
        editSearch.setCompoundDrawablePadding(dp(8));
        header.addView(editSearch, new LinearLayout.LayoutParams(MATCH, WRAP));

        // The list updates on EVERY keystroke; the data is already on the phone.
        editSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable s) {
                render();
            }
        });
        editSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard();
                return true;
            }
            return false;
        });
    }

    @Override
    protected void renderLoaded(@NonNull LinearLayout out) {
        String query = editSearch.getText() == null ? "" : editSearch.getText().toString().trim();

        // Every active hostel, with all of its free rooms (requireRooms = false keeps full hostels too)
        List<HostelData.Entry> all = HostelData.entries(hostels, rooms, "", "", "", 0, 0, null, false);
        List<HostelData.Entry> shown = new ArrayList<>();
        for (HostelData.Entry entry : all) {
            if (HostelData.matchesQuery(entry.hostel, query)) {
                shown.add(entry);
            }
        }
        HostelData.sortByName(shown);

        if (all.isEmpty()) {
            addMessage(out, "No hostels are listed yet. Please check again later.");
            return;
        }
        String word = shown.size() == 1 ? "hostel" : "hostels";
        addBanner(out, query.isEmpty()
                ? "All " + shown.size() + " " + word + ", A to Z"
                : shown.size() + " " + word + " match \"" + query + "\"", 0);

        if (shown.isEmpty()) {
            addMessage(out, "No hostel matches that. Try part of a name or an area.");
            return;
        }
        for (HostelData.Entry entry : shown) {
            addHostelCard(out, entry);
        }
    }
}
