package com.example.mobilescholarschool.Components;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.transition.ChangeBounds;
import androidx.transition.Fade;
import androidx.transition.TransitionManager;
import androidx.transition.TransitionSet;

import com.example.mobilescholarschool.HomeTab.HomeActivity;
import com.example.mobilescholarschool.HostelTab.HostelActivity;
import com.example.mobilescholarschool.LibraryTab.LibraryTabActivity;
import com.example.mobilescholarschool.ProfileTab.ProfileActivity;
import com.example.mobilescholarschool.PaymentsTab.PaymentActivity;
import com.example.mobilescholarschool.R;

import java.util.ArrayList;
import java.util.List;


public final class BottomNavHelper {

    // Exact sizes (dp). Exact sizes can't be stretched by a parent layout.
    private static final int BAR_HEIGHT_DP = 80;        // pill + 8dp top + 12dp bottom breathing room
    private static final int BAR_SIDE_PADDING_DP = 8;   // must match paddingStart/End in the layout
    private static final int PILL_HEIGHT_DP = 60;       // the pill itself
    private static final int MAX_PILL_WIDTH_DP = 420;   // stops the pill getting huge on tablets
    private static final int TAB_HEIGHT_DP = 48;        // each tab (60 - 6dp padding on both sides)

    private BottomNavHelper() {}   // utility class: nobody should create one

    /**
     * Call this from every screen that shows the bottom bar.
     * @param selectedItemId the tab that matches the current screen, e.g. R.id.nav_home
     */
    public static void setup(Activity activity, int selectedItemId) {
        // sceneRoot = the outermost view the animation should cover (wrapper + card + tabs).
        ViewGroup sceneRoot = activity.findViewById(R.id.bottomNav);
        View card = activity.findViewById(R.id.pillCard);
        LinearLayout container = activity.findViewById(R.id.pillContainer);

        lockBarSize(activity, sceneRoot, card, container);

        PillNav nav = new PillNav(activity, sceneRoot, container, R.menu.menu_bottom_nav);

        // Highlight the current tab (no animation, no listener call).
        nav.select(selectedItemId, false);

        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();

            if (id == selectedItemId) {
                return true;
            }

            Class<?> target = destinationFor(id);
            if (target == null) {
                Toast.makeText(activity,
                        activity.getString(R.string.nav_coming_soon, item.getTitle()),
                        Toast.LENGTH_SHORT).show();
                return false;  // false = don't move the highlight
            }

            activity.startActivity(new Intent(activity, target));
            // Skip the slide animation so the pill looks like it stays in place
            // while only the content changes.
            activity.overridePendingTransition(0, 0);
            activity.finish();
            return true;
        });
    }

    /**
     * An <include> tag (or the screen's parent layout) can override the size written in
     * layout_bottom_nav.xml. Re-applying EXACT sizes here guarantees the bar only ever
     * takes its own 80dp, and that the pill has a fixed width for the tabs to share.
     */
    private static void lockBarSize(Context context, ViewGroup bar, View card, View container) {
        // 1. The outer wrapper: full width, exactly 80dp tall, stuck to the bottom.
        ViewGroup.LayoutParams barParams = bar.getLayoutParams();
        if (barParams != null) {
            barParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            barParams.height = dp(context, BAR_HEIGHT_DP);

            if (barParams instanceof LinearLayout.LayoutParams) {
                // weight > 0 means "take all leftover space": cancel it.
                ((LinearLayout.LayoutParams) barParams).weight = 0f;
            } else if (barParams instanceof FrameLayout.LayoutParams) {
                ((FrameLayout.LayoutParams) barParams).gravity = Gravity.BOTTOM;
            } else if (barParams instanceof RelativeLayout.LayoutParams) {
                ((RelativeLayout.LayoutParams) barParams)
                        .addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
            }
            bar.setLayoutParams(barParams);
        }

        // 2. The pill card: a FIXED width (screen minus side padding, capped) and 60dp tall.
        //    The tabs inside will split this width by ratio, so none can be pushed out.
        int screenWidthDp = context.getResources().getConfiguration().screenWidthDp;
        int pillWidthDp = Math.min(screenWidthDp - 2 * BAR_SIDE_PADDING_DP, MAX_PILL_WIDTH_DP);
        card.setLayoutParams(new FrameLayout.LayoutParams(
                dp(context, pillWidthDp),
                dp(context, PILL_HEIGHT_DP),
                Gravity.CENTER_HORIZONTAL));

        // 3. The row of tabs: fills the card.
        container.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** Which screen each tab opens. */
    private static Class<?> destinationFor(int itemId) {
        if (itemId == R.id.nav_home) return HomeActivity.class;
        if (itemId == R.id.nav_library) return LibraryTabActivity.class;
        if (itemId == R.id.nav_hostel) return HostelActivity.class;
        if (itemId == R.id.nav_payments) return PaymentActivity.class;
        if (itemId == R.id.nav_profile) return ProfileActivity.class;
        return null;   // screen not built yet
    }

    /** Converts dp to pixels so sizes look the same on every screen density. */
    private static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    // =====================================================================
    //  Pill drawing + animation (private: only this helper uses it)
    // =====================================================================

    /** Same shape as Material's listener: return true to accept the selection. */
    private interface OnItemSelectedListener {
        boolean onItemSelected(MenuItem item);
    }

    /**
     * A spring-like timing curve. An Interpolator maps time (0..1) to progress (0..1).
     * A spring overshoots the target slightly, then settles back onto it.
     */
    private static final class SpringInterpolator implements Interpolator {
        private static final float DAMPING = 7f;      // higher = settles faster, less bounce
        private static final float FREQUENCY = 8.5f;  // higher = wobbles faster

        @Override
        public float getInterpolation(float t) {
            if (t >= 1f) {
                return 1f;   // land exactly on the target
            }
            // Damped oscillation that starts at rest, overshoots ~7%, then decays to 1.
            double decay = Math.exp(-DAMPING * t);
            double wave = Math.cos(FREQUENCY * t)
                    + (DAMPING / FREQUENCY) * Math.sin(FREQUENCY * t);
            return (float) (1.0 - decay * wave);
        }
    }

    /** Builds the tabs inside a LinearLayout and animates the active one. */
    private static final class PillNav {

        // The active tab gets this many "shares" of the row; every other tab gets 1.
        private static final float WEIGHT_INACTIVE = 1f;
        private static final float WEIGHT_ACTIVE = 2.6f;
        private static final long ANIMATION_MS = 550;

        /** Bundles the views of ONE tab so we can update them together. */
        private static final class Tab {
            final MenuItem item;
            final FrameLayout root;
            final View highlight;   // soft colored background, faded in/out
            final ImageView icon;
            final TextView label;

            Tab(MenuItem item, FrameLayout root, View highlight, ImageView icon, TextView label) {
                this.item = item;
                this.root = root;
                this.highlight = highlight;
                this.icon = icon;
                this.label = label;
            }
        }

        private final Context context;
        private final ViewGroup sceneRoot;
        private final LinearLayout container;
        private final List<Tab> tabs = new ArrayList<>();
        private final int activeColor;
        private final int inactiveColor;
        private final int highlightColor;
        private OnItemSelectedListener listener;

        PillNav(Context context, ViewGroup sceneRoot, LinearLayout container, int menuRes) {
            this.context = context;
            this.sceneRoot = sceneRoot;
            this.container = container;
            this.activeColor = ContextCompat.getColor(context, R.color.floating_nav_active_content);
            this.inactiveColor = ContextCompat.getColor(context, R.color.floating_nav_inactive);
            this.highlightColor = ContextCompat.getColor(context, R.color.floating_nav_active_bg);

            container.removeAllViews();

            // Trick: a PopupMenu hands us an empty Menu we can inflate into,
            // so we can read icons/titles/ids from the XML without showing anything.
            Menu menu = new PopupMenu(context, container).getMenu();
            new MenuInflater(context).inflate(menuRes, menu);

            for (int i = 0; i < menu.size(); i++) {
                MenuItem item = menu.getItem(i);
                if (item.isVisible()) {
                    addTab(item);
                }
            }
            select(View.NO_ID, false);   // start with every tab collapsed
        }

        void setOnItemSelectedListener(OnItemSelectedListener listener) {
            this.listener = listener;
        }

        private void addTab(MenuItem item) {
            // Tab = a FrameLayout stacking two layers:
            //   bottom layer: the highlight (colored pill, hidden unless active)
            //   top layer:    [ icon ][ label ]
            FrameLayout root = new FrameLayout(context);
            root.setClickable(true);
            root.setFocusable(true);
            // Icon-only buttons are invisible to screen readers without this.
            root.setContentDescription(item.getTitle());

            // width 0 + weight = "take a share of the row by ratio". Because the row has a
            // fixed width, every tab always gets its share and none can be pushed out.
            LinearLayout.LayoutParams rootParams = new LinearLayout.LayoutParams(
                    0, dp(TAB_HEIGHT_DP), WEIGHT_INACTIVE);
            rootParams.setMargins(dp(1), 0, dp(1), 0);
            root.setLayoutParams(rootParams);

            View highlight = new View(context);
            highlight.setBackground(createHighlightBackground());
            highlight.setVisibility(View.GONE);
            root.addView(highlight, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.HORIZONTAL);
            content.setGravity(Gravity.CENTER);
            content.setPadding(dp(10), 0, dp(10), 0);   // sides only: height comes from the tab

            ImageView icon = new ImageView(context);
            icon.setImageDrawable(item.getIcon());
            icon.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));

            TextView label = new TextView(context);
            label.setText(item.getTitle());
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            label.setTypeface(label.getTypeface(), Typeface.BOLD);
            label.setMaxLines(1);
            label.setEllipsize(TextUtils.TruncateAt.END);   // long titles get "..." instead of overflowing
            label.setPadding(dp(8), 0, 0, 0);
            label.setVisibility(View.GONE);           // hidden until this tab is active
            label.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            content.addView(icon);
            content.addView(label);
            root.addView(content, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER));

            root.setOnClickListener(v -> onTabClicked(item));

            container.addView(root);
            tabs.add(new Tab(item, root, highlight, icon, label));
        }

        private void onTabClicked(MenuItem item) {
            boolean accepted = listener == null || listener.onItemSelected(item);
            if (accepted) {
                select(item.getItemId(), true);       // animate the user's tap
            }
            // Not accepted: leave the highlight where it was.
        }

        /** Highlights one tab and collapses the rest. */
        void select(int itemId, boolean animate) {
            if (animate) {
                // Step 1: tell Android "animate whatever changes inside sceneRoot next".
                // ChangeBounds = slide/resize views, Fade = fade labels and highlights in/out.
                // ORDERING_TOGETHER = run them at the same time, not one after another.
                TransitionSet spring = new TransitionSet();
                spring.setOrdering(TransitionSet.ORDERING_TOGETHER);
                spring.addTransition(new ChangeBounds());
                spring.addTransition(new Fade());
                spring.setDuration(ANIMATION_MS);
                spring.setInterpolator(new SpringInterpolator());
                TransitionManager.beginDelayedTransition(sceneRoot, spring);
            }

            // Step 2: just make the changes. Android animates the difference.
            for (Tab tab : tabs) {
                boolean active = tab.item.getItemId() == itemId;

                // Active tab gets a bigger share of the row; the others share the rest.
                LinearLayout.LayoutParams params =
                        (LinearLayout.LayoutParams) tab.root.getLayoutParams();
                params.weight = active ? WEIGHT_ACTIVE : WEIGHT_INACTIVE;
                tab.root.setLayoutParams(params);

                tab.label.setVisibility(active ? View.VISIBLE : View.GONE);
                tab.highlight.setVisibility(active ? View.VISIBLE : View.GONE);

                int color = active ? activeColor : inactiveColor;
                tab.icon.setColorFilter(color);
                tab.label.setTextColor(color);
            }
        }

        /** The soft, fully rounded highlight behind the active tab. */
        private GradientDrawable createHighlightBackground() {
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setColor(highlightColor);
            bg.setCornerRadius(dp(100));   // clamped to half the height = rounded-full ends
            return bg;
        }

        /** Converts dp to pixels so sizes look the same on every screen density. */
        private int dp(int value) {
            return BottomNavHelper.dp(context, value);
        }
    }
}