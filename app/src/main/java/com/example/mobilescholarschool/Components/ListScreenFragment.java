package com.example.mobilescholarschool.Components;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.core.widget.TextViewCompat;
import androidx.fragment.app.Fragment;

import com.example.mobilescholarschool.R;
import com.google.android.material.card.MaterialCardView;

/**
 * The ONE base class for every full screen (Fragment) in the Library and Hostel tabs.
 *
 * LESSON: A DialogFragment floats over the screen in its own window. A plain Fragment is
 * different: it is a piece of screen that the Activity places INSIDE a container view
 * (R.id.fragmentContainer). Because it lives in the Activity's own window it can be a whole
 * page, it takes part in the back stack (the Back button closes it), and it can scroll freely.
 *
 * This base class builds the part every screen shares, so the screens below it only describe
 * what makes THEM different:
 *
 *      +---------------------------------+
 *      |  <-  Title                      |   the top bar (back button + title)
 *      +---------------------------------+
 *      |  header                         |   fixed area: a search box, tabs, a budget field...
 *      +---------------------------------+
 *      |  list                           |   the part that scrolls: cards, forms, results
 *      |  ...                            |
 *      +---------------------------------+
 *
 * LESSON: this is INHERITANCE. A subclass gets everything here for free and only fills in the
 * two "slots": screenTitle() (required) and onBuildHeader() (optional).
 */
public abstract class ListScreenFragment extends Fragment {

    // Shared sizes and colours, so every screen looks the same.
    protected static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    protected static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    // The WORD on a banner always explains the situation too, so colour is never the only clue.
    protected static final int COLOR_GOOD = 0xFF43A047;
    protected static final int COLOR_NOTE = 0xFFF9A825;
    protected static final int COLOR_BAD = 0xFFE53935;

    /** The fixed area under the title bar. Null while the screen's view does not exist. */
    protected LinearLayout header;

    /** The scrolling area. Null while the screen's view does not exist. */
    protected LinearLayout list;

    /** SLOT 1 (required): the words shown in the top bar. */
    protected abstract String screenTitle();

    /** SLOT 2 (optional): put a search box, tabs, an amount field... into the fixed header. */
    protected void onBuildHeader(@NonNull LinearLayout header, @Nullable Bundle savedInstanceState) {
    }

    // ================================================================ building the screen

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Context context = requireContext();
        int margin = getResources().getDimensionPixelSize(R.dimen.screen_margin);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(MATCH, MATCH));
        // The Activity's own screen is still underneath us, so we paint an opaque background
        // and swallow touches (setClickable) so nothing underneath can be tapped by accident.
        root.setBackgroundColor(ContextCompat.getColor(context, R.color.bg_main));
        root.setClickable(true);

        // ---- the top bar: back button + title ----
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(12), margin, dp(4));

        ImageButton back = new ImageButton(context, null, 0, R.style.Widget_App_IconButton_Light);
        back.setImageResource(R.drawable.ic_forward);   // the "forward" arrow, turned round
        back.setRotation(180f);
        ImageViewCompat.setImageTintList(back,
                ColorStateList.valueOf(ContextCompat.getColor(context, R.color.text_muted)));
        back.setContentDescription("Back");
        // popBackStack() removes this screen and reveals whatever was under it.
        back.setOnClickListener(v -> getParentFragmentManager().popBackStack());
        bar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));

        TextView title = new TextView(context);
        title.setText(screenTitle());
        TextViewCompat.setTextAppearance(title, R.style.TextAppearance_App_Headline);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, WRAP, 1f);
        titleParams.setMarginStart(dp(8));
        bar.addView(title, titleParams);
        root.addView(bar, new LinearLayout.LayoutParams(MATCH, WRAP));

        // ---- the fixed header (empty unless a subclass fills it) ----
        header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(margin, 0, margin, dp(4));
        root.addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));

        // ---- the scrolling list ----
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(margin, dp(4), margin, dp(24));

        ScrollView scroll = new ScrollView(context);
        scroll.setClipToPadding(false);
        scroll.addView(list, new FrameLayout.LayoutParams(MATCH, WRAP));
        // Height 0 + weight 1 means "take all the space the bar and header did not use".
        root.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        // `list` and `header` exist by now, so the subclass can safely use them.
        onBuildHeader(header, savedInstanceState);
        return root;
    }

    @Override
    public void onDestroyView() {
        hideKeyboard();
        // LESSON: a Fragment can outlive its VIEW (for example while another screen is on top
        // of it in the back stack). Clearing these stops us touching views that are gone.
        header = null;
        list = null;
        super.onDestroyView();
    }

    // ================================================================ small shared helpers

    /** Converts dp to pixels, so sizes look the same on every phone. */
    protected int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    protected void hideKeyboard() {
        View view = getView();
        if (view == null) {
            return;
        }
        InputMethodManager imm = (InputMethodManager)
                view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    /** Text with a size in sp. color 0 means "use the theme's normal text colour". */
    protected TextView makeText(String text, int sp, boolean bold, int color) {
        TextView view = new TextView(requireContext());
        view.setText(text);
        view.setTextSize(sp);
        if (bold) {
            view.setTypeface(null, Typeface.BOLD);
        }
        if (color != 0) {
            view.setTextColor(color);
        }
        return view;
    }

    /** Adds a view to a vertical layout, with a gap above it. */
    protected void addLine(LinearLayout parent, View view, int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH, WRAP);
        params.topMargin = dp(topMarginDp);
        parent.addView(view, params);
    }

    /** A plain message (for example "Loading…" or "Nothing here yet"). */
    protected void addMessage(LinearLayout target, String message) {
        TextView view = makeText(message, 15, false, 0);
        view.setPadding(0, dp(24), 0, dp(24));
        target.addView(view, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    /** A bold coloured sentence that explains the result. */
    protected void addBanner(LinearLayout target, String message, int color) {
        TextView banner = makeText(message, 14, true, color);
        banner.setPadding(0, dp(12), 0, dp(4));
        target.addView(banner, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    /** A heading that splits a long list into groups. */
    protected void addSectionTitle(LinearLayout target, String title) {
        TextView view = new TextView(requireContext());
        view.setText(title);
        TextViewCompat.setTextAppearance(view, R.style.TextAppearance_App_Section);
        addLine(target, view, 20);
    }

    /**
     * Adds an empty card to `target` and returns the vertical box INSIDE it, ready to be filled.
     * Every book, hostel and request on these screens is drawn with this.
     */
    protected LinearLayout addCard(LinearLayout target) {
        MaterialCardView card = new MaterialCardView(requireContext());
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(MATCH, WRAP);
        cardParams.topMargin = dp(12);
        target.addView(card, cardParams);

        LinearLayout inner = new LinearLayout(requireContext());
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(16), dp(12), dp(16), dp(12));
        card.addView(inner, new FrameLayout.LayoutParams(MATCH, WRAP));
        return inner;
    }
}
