package com.example.mobilescholarschool.HostelTab;

import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.List;

/**
 * "Budget": the student types the MOST they can pay per month, and the screen suggests rooms.
 *
 *   - "Within your budget": every free room at or under that rent, cheapest first.
 *   - "A little over budget": rooms up to 20% above it, in case a slightly dearer room is worth it.
 *   - If nothing fits, it says what the cheapest free room costs.
 *
 * The suggestions update on every keystroke. Every room has a Book button.
 */
public class HostelBudgetFragment extends HostelBaseFragment {

    public static final String TAG = "HostelBudgetFragment";

    // A fixed ID, so the typed amount survives a rotation.
    private static final int ID_RENT = 4601;

    // Tap-to-fill amounts, so a student does not even have to type.
    private static final long[] QUICK_AMOUNTS = {5000, 8000, 12000, 20000};

    private TextInputLayout tilRent;
    private EditText edtRent;

    public static HostelBudgetFragment newInstance() {
        return new HostelBudgetFragment();
    }

    @Override
    protected String screenTitle() {
        return "Budget";
    }

    @Override
    protected void onBuildHeader(@NonNull LinearLayout header, @Nullable Bundle savedInstanceState) {
        // ---- the amount box (outlined, with room for a red error message) ----
        tilRent = new TextInputLayout(requireContext(), null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        tilRent.setHint("Maximum rent per month (KES)");
        tilRent.setHelperText("Per student. Suggestions appear as you type.");

        // Built from the layout's OWN context so it picks up the right theme overlay
        TextInputEditText rentEdit = new TextInputEditText(tilRent.getContext());
        rentEdit.setId(ID_RENT);
        rentEdit.setInputType(InputType.TYPE_CLASS_NUMBER);   // digits only
        rentEdit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(9)});
        tilRent.addView(rentEdit, new LinearLayout.LayoutParams(MATCH, WRAP));
        header.addView(tilRent, new LinearLayout.LayoutParams(MATCH, WRAP));
        edtRent = rentEdit;

        // ---- quick amounts ----
        HorizontalScrollView scroller = new HorizontalScrollView(requireContext());
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(requireContext());
        chips.setOrientation(LinearLayout.HORIZONTAL);
        for (long amount : QUICK_AMOUNTS) {
            MaterialButton chip = new MaterialButton(requireContext(), null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            chip.setText("KES " + HostelData.formatKes(amount));
            chip.setOnClickListener(v -> edtRent.setText(String.valueOf(amount)));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(WRAP, WRAP);
            params.setMarginEnd(dp(8));
            chips.addView(chip, params);
        }
        scroller.addView(chips);
        header.addView(scroller, new LinearLayout.LayoutParams(MATCH, WRAP));

        // Every change to the amount redraws the suggestions.
        edtRent.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable s) {
                long rent = HostelData.parseRent(s == null ? "" : s.toString());
                tilRent.setError(rent < 0
                        ? "Enter a whole amount from 1 to " + HostelData.formatKes(HostelData.MAX_RENT_LIMIT)
                        : null);
                render();
            }
        });
    }

    @Override
    protected void renderLoaded(@NonNull LinearLayout out) {
        long rent = HostelData.parseRent(edtRent.getText() == null ? "" : edtRent.getText().toString());
        long cheapest = HostelData.cheapestRent(hostels, rooms);

        if (rent < 0) {
            addMessage(out, "Enter a whole amount from 1 to " + HostelData.formatKes(HostelData.MAX_RENT_LIMIT) + ".");
            return;
        }
        if (rent == 0) {
            addMessage(out, "Type the most you can pay per month, or tap an amount above, "
                    + "and suggestions will appear here.");
            if (cheapest > 0) {
                addBanner(out, "The cheapest free room right now is KES "
                        + HostelData.formatKes(cheapest) + " per month.", COLOR_GOOD);
            }
            return;
        }

        // Rooms at or under the budget...
        List<HostelData.Entry> within = HostelData.entries(hostels, rooms, "", "", "", 0, rent, null, true);
        // ...and rooms from just above the budget up to 20% more.
        long ceiling = HostelData.suggestionCeiling(rent);
        List<HostelData.Entry> above = HostelData.entries(hostels, rooms, "", "", "", rent, ceiling, null, true);

        if (within.isEmpty() && above.isEmpty()) {
            addBanner(out, "No free room costs KES " + HostelData.formatKes(ceiling)
                    + " or less.", COLOR_NOTE);
            if (cheapest > 0) {
                addMessage(out, "The cheapest free room right now is KES "
                        + HostelData.formatKes(cheapest) + " per month.");
            }
            return;
        }

        if (within.isEmpty()) {
            addBanner(out, "Nothing is free at KES " + HostelData.formatKes(rent)
                    + " or less, but these are close:", COLOR_NOTE);
        } else {
            addSection(out, "Within your budget (up to KES " + HostelData.formatKes(rent) + ")",
                    within, "");
        }
        if (!above.isEmpty()) {
            addSection(out, "A little over budget (KES " + HostelData.formatKes(rent + 1)
                    + " to " + HostelData.formatKes(ceiling) + ")", above, "");
        }
    }
}
