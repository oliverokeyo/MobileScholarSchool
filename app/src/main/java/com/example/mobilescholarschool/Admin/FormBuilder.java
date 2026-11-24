package com.example.mobilescholarschool.Admin;

import android.app.DatePickerDialog;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.example.mobilescholarschool.R;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LESSON: instead of writing a new XML layout for every "Add / Edit" form, this builds the form from
 * a description: .text(...).choice(...).date(...).show(...). The Submit callback receives every
 * answer as text and returns an error message (the dialog stays open and shows it) or null (the
 * dialog closes). Dates arrive as milliseconds in text form.
 */
public final class FormBuilder {

    public interface Submit {
        /** @return an error message to show, or null when the answers are accepted */
        String onSubmit(Map<String, String> values);
    }

    private final Context ctx;
    private final String title;
    private final LinearLayout body;
    private final TextView error;
    private final Map<String, View> inputs = new LinkedHashMap<>();
    private final Map<String, long[]> dates = new HashMap<>();
    private final Map<String, String[]> choiceValues = new HashMap<>();   // labelled choices: key -> stored values

    public FormBuilder(Context ctx, String title) {
        this.ctx = ctx;
        this.title = title;
        body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = AdminUi.dp(ctx, 24);
        body.setPadding(pad, AdminUi.dp(ctx, 8), pad, 0);
        error = new TextView(ctx);
        error.setTextColor(AdminUi.RED);
        error.setVisibility(View.GONE);
        body.addView(error);
    }

    private void label(String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextAppearance(R.style.TextAppearance_App_Caption);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = AdminUi.dp(ctx, 10);
        body.addView(tv, lp);
    }

    public FormBuilder note(String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextAppearance(R.style.TextAppearance_App_Body);
        body.addView(tv);
        return this;
    }

    public FormBuilder text(String key, String label, String initial, int inputType) {
        label(label);
        EditText e = new EditText(ctx);
        e.setInputType(inputType);
        e.setMaxLines(1);
        e.setText(initial == null ? "" : initial);
        body.addView(e, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        inputs.put(key, e);
        return this;
    }

    public FormBuilder choice(String key, String label, String[] options, String initial) {
        label(label);
        Spinner sp = new Spinner(ctx);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(adapter);
        for (int i = 0; i < options.length; i++) {
            if (options[i].equalsIgnoreCase(initial)) sp.setSelection(i);
        }
        body.addView(sp, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        inputs.put(key, sp);
        return this;
    }

    /**
     * LESSON: a choice where the person READS a friendly label ("Nearby (walking distance)") but the
     * form hands back the STORED value ("nearby"). Using the student app's own value lists means the
     * admin can never save a spelling the student's filters would not recognise.
     */
    public FormBuilder choice(String key, String label, String[] values, String[] labels, String initialValue) {
        label(label);
        Spinner sp = new Spinner(ctx);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(adapter);
        for (int i = 0; i < values.length; i++) {
            if (values[i].equalsIgnoreCase(initialValue)) sp.setSelection(i);
        }
        body.addView(sp, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        inputs.put(key, sp);
        choiceValues.put(key, values);
        return this;
    }

    /** A group of tick boxes. The answer is the ticked VALUES joined by commas ("wifi,water"). */
    public FormBuilder multi(String key, String label, String[] values, String[] labels, List<String> initial) {
        label(label);
        LinearLayout group = new LinearLayout(ctx);
        group.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < values.length; i++) {
            MaterialCheckBox box = new MaterialCheckBox(ctx);
            box.setText(labels[i]);
            box.setTag(values[i]);
            box.setChecked(initial != null && initial.contains(values[i]));
            group.addView(box);
        }
        body.addView(group, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        inputs.put(key, group);
        return this;
    }

    public FormBuilder date(String key, String label, long initialMillis) {
        label(label);
        long[] holder = {initialMillis};
        dates.put(key, holder);
        EditText e = new EditText(ctx);
        e.setFocusable(false);
        e.setClickable(true);
        e.setText(AdminUi.date(initialMillis));
        e.setOnClickListener(v -> {
            Calendar c = Calendar.getInstance();
            if (holder[0] > 0) c.setTimeInMillis(holder[0]);
            new DatePickerDialog(ctx, (picker, y, m, d) -> {
                Calendar picked = Calendar.getInstance();
                picked.set(y, m, d, 12, 0, 0);   // noon: daylight-saving shifts can never change the day
                holder[0] = picked.getTimeInMillis();
                e.setText(AdminUi.date(holder[0]));
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
        });
        body.addView(e, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        inputs.put(key, e);
        return this;
    }

    private Map<String, String> collect() {
        Map<String, String> values = new HashMap<>();
        for (Map.Entry<String, View> entry : inputs.entrySet()) {
            View v = entry.getValue();
            String value;
            if (dates.containsKey(entry.getKey())) {
                value = String.valueOf(dates.get(entry.getKey())[0]);
            } else if (v instanceof Spinner && choiceValues.containsKey(entry.getKey())) {
                int pos = ((Spinner) v).getSelectedItemPosition();
                String[] stored = choiceValues.get(entry.getKey());
                value = pos >= 0 && pos < stored.length ? stored[pos] : "";
            } else if (v instanceof Spinner) {
                value = String.valueOf(((Spinner) v).getSelectedItem());
            } else if (v instanceof LinearLayout) {
                StringBuilder ticked = new StringBuilder();
                LinearLayout group = (LinearLayout) v;
                for (int i = 0; i < group.getChildCount(); i++) {
                    MaterialCheckBox box = (MaterialCheckBox) group.getChildAt(i);
                    if (box.isChecked()) ticked.append(ticked.length() > 0 ? "," : "").append(box.getTag());
                }
                value = ticked.toString();
            } else {
                value = ((EditText) v).getText().toString().trim();
            }
            values.put(entry.getKey(), value);
        }
        return values;
    }

    public void show(String positive, Submit submit) {
        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(body);
        AlertDialog dialog = new MaterialAlertDialogBuilder(ctx)
                .setTitle(title)
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(positive, null)   // null here: we attach the click below so errors keep the dialog open
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String problem = submit.onSubmit(collect());
            if (problem == null) {
                dialog.dismiss();
            } else {
                error.setText(problem);
                error.setVisibility(View.VISIBLE);
            }
        }));
        dialog.show();
    }
}
