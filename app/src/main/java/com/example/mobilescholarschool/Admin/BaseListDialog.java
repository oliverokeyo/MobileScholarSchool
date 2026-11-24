package com.example.mobilescholarschool.Admin;

import android.content.DialogInterface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.mobilescholarschool.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * LESSON - ONE PATTERN, MANY SCREENS: nearly every admin screen is "load documents, show them as
 * cards, let me search / filter, tap one for actions". That is written ONCE here. A screen (for
 * example BookListDialog) only answers a few questions: what is the title, how do I load the rows,
 * what happens when a row is tapped. Adding a new list later takes about 60 lines, not 400.
 *
 * Data is read ONCE per load (get) instead of with live listeners: admin lists can be large, and
 * after every action we simply call reload(). That keeps the number of billed reads predictable.
 */
public abstract class BaseListDialog extends DialogFragment {

    /** One card in the list. Subclasses fill the fields; data can carry the original document. */
    public static final class Row {
        public String id = "", title = "", subtitle = "", extra = "", status = "";
        public int statusColor = AdminUi.GREY;
        public String search = "";                        // lower-case text the search box looks in
        public final List<String> tags = new ArrayList<>();   // what the filter chips match against
        public Object data;
    }

    public interface Loader {
        void done(List<Row> rows);
        void fail(Exception e);
    }

    // ---- subclasses answer these ----
    protected abstract String title();

    protected abstract void load(Loader loader);

    protected abstract void onRowClick(Row row);

    /** null = no search box. */
    protected String searchHint() { return null; }

    /** {{tag, label}, ...}. The first one should be {"", "All"}. null = no chips. */
    protected String[][] filters() { return null; }

    protected String initialFilter() { return ""; }

    protected String addLabel() { return null; }   // null = no top-right button

    protected void onAdd() {}

    protected String emptyText() { return "Nothing to show yet."; }

    // ---- state ----
    private final List<Row> allRows = new ArrayList<>();
    private final List<Row> shown = new ArrayList<>();
    private String activeFilter = "";
    private String query = "";
    private String failure;   // text of the last load error, or null
    private RowAdapter adapter;
    private ProgressBar progress;
    private TextView empty, summary;
    private final List<TextView> chips = new ArrayList<>();

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(DialogFragment.STYLE_NORMAL, R.style.Theme_MobileScholarSchool);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.admin_dialog_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        activeFilter = initialFilter();
        view.findViewById(R.id.admListBack).setOnClickListener(v -> dismiss());
        ((TextView) view.findViewById(R.id.admListTitle)).setText(title());
        progress = view.findViewById(R.id.admListProgress);
        empty = view.findViewById(R.id.admListEmpty);
        summary = view.findViewById(R.id.admListSummary);

        String add = addLabel();
        if (add != null) {
            MaterialButton b = view.findViewById(R.id.admListAdd);
            b.setText(add);
            b.setAllCaps(false);
            b.setVisibility(View.VISIBLE);
            b.setOnClickListener(v -> onAdd());
        }

        if (searchHint() != null) {
            EditText search = view.findViewById(R.id.admListSearch);
            search.setHint(searchHint());
            search.setVisibility(View.VISIBLE);
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    query = s.toString().trim().toLowerCase(Locale.ROOT);
                    applyFilter();
                }
            });
        }

        String[][] f = filters();
        if (f != null) {
            view.findViewById(R.id.admListFiltersScroll).setVisibility(View.VISIBLE);
            LinearLayout bar = view.findViewById(R.id.admListFilters);
            for (String[] pair : f) {
                TextView chip = new TextView(requireContext());
                chip.setText(pair[1]);
                chip.setTag(pair[0]);
                chip.setTextSize(13);
                int h = AdminUi.dp(requireContext(), 14), vv = AdminUi.dp(requireContext(), 7);
                chip.setPadding(h, vv, h, vv);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(AdminUi.dp(requireContext(), 4), 0, AdminUi.dp(requireContext(), 4), 0);
                chip.setOnClickListener(v -> {
                    activeFilter = (String) v.getTag();
                    styleChips();
                    applyFilter();
                });
                bar.addView(chip, lp);
                chips.add(chip);
            }
            styleChips();
        }

        RecyclerView recycler = view.findViewById(R.id.admListRecycler);
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new RowAdapter();
        recycler.setAdapter(adapter);
        reload();
    }

    private void styleChips() {
        for (TextView chip : chips) {
            boolean on = chip.getTag().equals(activeFilter);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(AdminUi.dp(requireContext(), 20));
            bg.setColor(on ? 0xFF0E0C0A : 0xFFE9E7E7);   // dark when chosen, soft grey otherwise
            chip.setBackground(bg);
            chip.setTextColor(on ? 0xFFFFFFFF : 0xFF0E0C0A);
        }
    }

    /** Loads the rows again (called at the start and after every action). */
    protected final void reload() {
        if (progress == null) return;
        progress.setVisibility(View.VISIBLE);
        empty.setVisibility(View.GONE);
        failure = null;
        load(new Loader() {
            @Override public void done(List<Row> rows) {
                if (!isAdded()) return;
                allRows.clear();
                allRows.addAll(rows);
                progress.setVisibility(View.GONE);
                applyFilter();
            }
            @Override public void fail(Exception e) {
                if (!isAdded()) return;
                progress.setVisibility(View.GONE);
                allRows.clear();
                failure = "Could not load: " + AdminRepo.message(e);
                applyFilter();
            }
        });
    }

    private void applyFilter() {
        shown.clear();
        for (Row r : allRows) {
            boolean tagOk = activeFilter.isEmpty() || r.tags.contains(activeFilter);
            boolean textOk = query.isEmpty() || r.search.contains(query);
            if (tagOk && textOk) shown.add(r);
        }
        adapter.notifyDataSetChanged();
        summary.setText(shown.size() + " of " + allRows.size());
        if (shown.isEmpty() && progress.getVisibility() != View.VISIBLE) {
            empty.setText(failure != null ? failure : allRows.isEmpty() ? emptyText() : "No matches.");
            empty.setVisibility(View.VISIBLE);
        } else {
            empty.setVisibility(View.GONE);
        }
    }

    /** When a dialog opened FROM a list closes, the list underneath refreshes itself. */
    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        Fragment parent = getParentFragment();
        if (parent instanceof BaseListDialog && parent.isAdded()) {
            ((BaseListDialog) parent).reload();
        }
    }

    // ---- helpers for subclasses ----

    protected final void choose(String title, String[] labels, Runnable[] actions) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setItems(labels, (d, which) -> actions[which].run())
                .setNegativeButton("Close", null)
                .show();
    }

    protected final void confirm(String title, String message, String yes, Runnable onYes) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(yes, (d, w) -> onYes.run())
                .show();
    }

    protected final void info(String title, String message) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title).setMessage(message).setPositiveButton("OK", null).show();
    }

    /** A Result that shows a message and reloads the list. */
    protected final AdminRepo.Result afterAction(String okMessage) {
        return new AdminRepo.Result() {
            @Override public void ok() {
                AdminUi.toast(getContext(), okMessage);
                if (isAdded()) reload();
            }
            @Override public void fail(String message) {
                AdminUi.toast(getContext(), message);
            }
        };
    }

    protected final void showChild(DialogFragment dialog, String tag) {
        if (getChildFragmentManager().findFragmentByTag(tag) == null) {
            dialog.show(getChildFragmentManager(), tag);
        }
    }

    // ---- adapter ----
    private final class RowAdapter extends RecyclerView.Adapter<RowAdapter.Holder> {
        final class Holder extends RecyclerView.ViewHolder {
            final TextView title, status, subtitle, extra;
            Holder(View v) {
                super(v);
                title = v.findViewById(R.id.rowTitle);
                status = v.findViewById(R.id.rowStatus);
                subtitle = v.findViewById(R.id.rowSubtitle);
                extra = v.findViewById(R.id.rowExtra);
            }
        }

        @NonNull @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.admin_item_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Row r = shown.get(position);
            h.title.setText(r.title);
            h.subtitle.setText(r.subtitle);
            h.subtitle.setVisibility(r.subtitle.isEmpty() ? View.GONE : View.VISIBLE);
            h.extra.setText(r.extra);
            h.extra.setVisibility(r.extra.isEmpty() ? View.GONE : View.VISIBLE);
            h.status.setVisibility(r.status.isEmpty() ? View.GONE : View.VISIBLE);
            h.status.setText(r.status);
            AdminUi.chip(h.status, r.statusColor);
            h.itemView.setOnClickListener(v -> onRowClick(r));
        }

        @Override public int getItemCount() { return shown.size(); }
    }
}
