package com.example.mobilescholarschool.HostelTab;

import android.app.Dialog;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


public class HostelBudgetDialogFragment extends DialogFragment {

    public static final String TAG = "HostelBudgetDialog";

    // ---- the key the screen listens for, and what comes back -------------------------------
    public static final String REQUEST_KEY = "hostel_budget";
    public static final String RESULT_MAX_RENT = "result_max_rent";         // long; 0 = no limit
    public static final String RESULT_FACILITIES = "result_facilities";     // String[]; empty = none needed

    private static final String ARG_MAX_RENT = "arg_max_rent";
    private static final String ARG_FACILITIES = "arg_facilities";
    private static final int ID_RENT = 4301;
    private static final int ID_FACILITY_BASE = 4310;

    private TextInputLayout tilRent;
    private EditText edtRent;
    private final List<MaterialCheckBox> facilityBoxes = new ArrayList<>();

    // >>> PURE-HELPERS-BEGIN  (plain Java: no Android code, so they can be unit-tested)

    /** The most a student can type (KES per month). Stops a slip like an extra row of zeros. */
    public static final long MAX_RENT_LIMIT = 1_000_000L;

    // The VALUES are exactly what is stored in each hostel's "facilities" array in Firestore
    // (lowercase). The LABELS are what the student reads. Keep the two lists in the same order.
    public static final String[] FACILITY_VALUES = {"wifi", "water", "security", "laundry", "parking", "kitchen"};
    public static final String[] FACILITY_LABELS = {"Wi-Fi", "Water", "Security", "Laundry", "Parking", "Kitchen"};


    public static long parseRent(String raw) {
        String clean = raw == null ? "" : raw.replaceAll("[\\s,]", "");   // forgive "12,000" and spaces
        if (clean.isEmpty()) {
            return 0;
        }
        if (!clean.matches("[0-9]{1,9}")) {
            return -1;
        }
        long amount = Long.parseLong(clean);
        if (amount < 1 || amount > MAX_RENT_LIMIT) {
            return -1;
        }
        return amount;
    }

    /** 12000 becomes "12,000". Always commas, whatever the phone's language. */
    public static String formatKes(long amount) {
        return String.format(Locale.US, "%,d", amount);
    }

    /** The readable label for a stored facility value, or the value itself if it is unknown. */
    public static String facilityLabel(String value) {
        for (int i = 0; i < FACILITY_VALUES.length; i++) {
            if (FACILITY_VALUES[i].equals(value)) {
                return FACILITY_LABELS[i];
            }
        }
        return value;
    }

    /**
     * One line describing the choice, for the screen: "Up to KES 12,000  ·  Needs Wi-Fi, Water".
     * Returns "" when there is no limit and no facility, so the caller can show its normal text.
     */
    public static String summaryOf(long maxRent, List<String> facilities) {
        StringBuilder out = new StringBuilder();
        if (maxRent > 0) {
            out.append("Up to KES ").append(formatKes(maxRent));
        }
        if (facilities != null && !facilities.isEmpty()) {
            if (out.length() > 0) {
                out.append("  ·  ");
            }
            out.append("Needs ");
            for (int i = 0; i < facilities.size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append(facilityLabel(facilities.get(i)));
            }
        }
        return out.toString();
    }

    // <<< PURE-HELPERS-END

    public static HostelBudgetDialogFragment newInstance(long currentMaxRent,
                                                         @Nullable List<String> currentFacilities) {
        Bundle args = new Bundle();
        args.putLong(ARG_MAX_RENT, currentMaxRent);
        args.putStringArray(ARG_FACILITIES, currentFacilities == null
                ? new String[0] : currentFacilities.toArray(new String[0]));

        HostelBudgetDialogFragment fragment = new HostelBudgetDialogFragment();
        fragment.setArguments(args);
        return fragment;
    }


    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        LinearLayout form = new LinearLayout(requireContext());
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), dp(8));

        // ---- the amount box (outlined, with room for a red error message) ----
        tilRent = new TextInputLayout(requireContext(), null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        tilRent.setHint("Maximum rent per month (KES)");
        tilRent.setHelperText("Per student. Leave empty for no limit.");

        // Built from the layout's OWN context so it picks up the right theme overlay
        TextInputEditText rentEdit = new TextInputEditText(tilRent.getContext());
        rentEdit.setId(ID_RENT);
        rentEdit.setInputType(InputType.TYPE_CLASS_NUMBER);   // digits only
        rentEdit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(9)});
        tilRent.addView(rentEdit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        form.addView(tilRent, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        edtRent = rentEdit;

        // ---- the facilities ----
        TextView heading = new TextView(requireContext());
        heading.setText("The hostel must have:");
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headingParams.topMargin = dp(16);
        form.addView(heading, headingParams);

        facilityBoxes.clear();
        for (int i = 0; i < FACILITY_VALUES.length; i++) {
            MaterialCheckBox box = new MaterialCheckBox(requireContext());
            box.setId(ID_FACILITY_BASE + i);   // fixed ID, so a tick survives rotation
            box.setText(FACILITY_LABELS[i]);
            facilityBoxes.add(box);
            form.addView(box, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // First time only: fill in the current choice. After a rotation the typed amount and the
        // ticked boxes restore themselves (they have fixed IDs), so we must not overwrite them.
        if (savedInstanceState == null) {
            Bundle args = requireArguments();
            long currentRent = args.getLong(ARG_MAX_RENT, 0);
            if (currentRent > 0) {
                edtRent.setText(String.valueOf(currentRent));
            }
            String[] current = args.getStringArray(ARG_FACILITIES);
            if (current != null) {
                for (String value : current) {
                    for (int i = 0; i < FACILITY_VALUES.length; i++) {
                        if (FACILITY_VALUES[i].equals(value)) {
                            facilityBoxes.get(i).setChecked(true);
                        }
                    }
                }
            }
        }

        // The ScrollView keeps the buttons reachable on small screens with the keyboard open.
        ScrollView scroll = new ScrollView(requireContext());
        scroll.addView(form);

        return new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Budget")
                .setView(scroll)
                .setNegativeButton("Cancel", null)   // closes the dialog and changes nothing
                // Clear needs no checking: it means "no limit, no facilities required"
                .setNeutralButton("Clear", (dialog, which) -> sendResult(0, new ArrayList<>()))
                // null on purpose: a listener passed here would close the dialog on EVERY tap,
                // even when the amount is invalid. We attach our own in onStart().
                .setPositiveButton("Apply", null)
                .create();
    }

    /** The dialog's buttons only exist once the dialog is showing, so we attach here. */
    @Override
    public void onStart() {
        super.onStart();
        AlertDialog dialog = (AlertDialog) getDialog();
        if (dialog != null) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> apply());
        }
    }

    // ================================================================ applying

    private void apply() {
        long rent = parseRent(edtRent.getText() == null ? "" : edtRent.getText().toString());
        if (rent < 0) {
            tilRent.setError("Enter a whole amount from 1 to " + formatKes(MAX_RENT_LIMIT));
            return;   // the dialog stays open so the student can fix the amount
        }
        tilRent.setError(null);

        List<String> chosen = new ArrayList<>();
        for (int i = 0; i < facilityBoxes.size(); i++) {
            if (facilityBoxes.get(i).isChecked()) {
                chosen.add(FACILITY_VALUES[i]);
            }
        }

        sendResult(rent, chosen);
        dismiss();
    }

    private void sendResult(long maxRent, List<String> facilities) {
        Bundle result = new Bundle();
        result.putLong(RESULT_MAX_RENT, maxRent);
        result.putStringArray(RESULT_FACILITIES, facilities.toArray(new String[0]));
        getParentFragmentManager().setFragmentResult(REQUEST_KEY, result);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
