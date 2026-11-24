package com.example.mobilescholarschool.Admin;

import android.app.Activity;
import android.content.Context;
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
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.transition.ChangeBounds;
import androidx.transition.Fade;
import androidx.transition.TransitionManager;
import androidx.transition.TransitionSet;

import com.example.mobilescholarschool.R;

import java.util.ArrayList;
import java.util.List;

/**
 * The admin version of BottomNavHelper. It re-uses the SAME bar layout (layout_bottom_nav.xml), the
 * same colours and the same spring animation, but reads its tabs from menu_admin_bottom_nav.xml
 * and, instead of opening other Activities, just tells the host which tab was chosen (the admin
 * screens are Fragments inside ONE Activity).
 *
 * LESSON: copying was chosen over editing BottomNavHelper so the student side stays untouched.
 * A later clean-up could make BottomNavHelper accept a menu and a callback, and delete this copy.
 */
public final class AdminNavHelper {

    public interface OnTabSelected {
        void onSelected(int itemId);
    }

    private static final int BAR_HEIGHT_DP = 80, BAR_SIDE_PADDING_DP = 8, PILL_HEIGHT_DP = 60,
            MAX_PILL_WIDTH_DP = 420, TAB_HEIGHT_DP = 48;
    private static final float WEIGHT_INACTIVE = 1f, WEIGHT_ACTIVE = 2.6f;
    private static final long ANIMATION_MS = 550;

    private static final class Tab {
        final MenuItem item;
        final FrameLayout root;
        final View highlight;
        final ImageView icon;
        final TextView label;

        Tab(MenuItem item, FrameLayout root, View highlight, ImageView icon, TextView label) {
            this.item = item; this.root = root; this.highlight = highlight; this.icon = icon; this.label = label;
        }
    }

    private static final class SpringInterpolator implements Interpolator {
        @Override
        public float getInterpolation(float t) {
            if (t >= 1f) return 1f;
            double decay = Math.exp(-7f * t);
            double wave = Math.cos(8.5f * t) + (7f / 8.5f) * Math.sin(8.5f * t);
            return (float) (1.0 - decay * wave);
        }
    }

    private final Context context;
    private final ViewGroup sceneRoot;
    private final LinearLayout container;
    private final List<Tab> tabs = new ArrayList<>();
    private final int activeColor, inactiveColor, highlightColor;
    private final OnTabSelected listener;

    public AdminNavHelper(Activity activity, OnTabSelected listener) {
        this.context = activity;
        this.listener = listener;
        sceneRoot = activity.findViewById(R.id.bottomNav);
        View card = activity.findViewById(R.id.pillCard);
        container = activity.findViewById(R.id.pillContainer);
        activeColor = ContextCompat.getColor(activity, R.color.floating_nav_active_content);
        inactiveColor = ContextCompat.getColor(activity, R.color.floating_nav_inactive);
        highlightColor = ContextCompat.getColor(activity, R.color.floating_nav_active_bg);

        lockBarSize(sceneRoot, card, container);
        container.removeAllViews();
        Menu menu = new PopupMenu(activity, container).getMenu();
        new MenuInflater(activity).inflate(R.menu.menu_admin_bottom_nav, menu);
        for (int i = 0; i < menu.size(); i++) {
            if (menu.getItem(i).isVisible()) addTab(menu.getItem(i));
        }
        select(View.NO_ID, false);
    }

    public int firstTabId() {
        return tabs.get(0).item.getItemId();
    }

    /** The tab ids from left to right, as listed in menu_admin_bottom_nav.xml (used by the swipe). */
    public int[] tabIds() {
        int[] ids = new int[tabs.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = tabs.get(i).item.getItemId();
        return ids;
    }

    private void lockBarSize(ViewGroup bar, View card, View row) {
        ViewGroup.LayoutParams bp = bar.getLayoutParams();
        if (bp != null) {
            bp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            bp.height = dp(BAR_HEIGHT_DP);
            if (bp instanceof FrameLayout.LayoutParams) ((FrameLayout.LayoutParams) bp).gravity = Gravity.BOTTOM;
            bar.setLayoutParams(bp);
        }
        int screenDp = context.getResources().getConfiguration().screenWidthDp;
        int pillDp = Math.min(screenDp - 2 * BAR_SIDE_PADDING_DP, MAX_PILL_WIDTH_DP);
        card.setLayoutParams(new FrameLayout.LayoutParams(dp(pillDp), dp(PILL_HEIGHT_DP), Gravity.CENTER_HORIZONTAL));
        row.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void addTab(MenuItem item) {
        FrameLayout root = new FrameLayout(context);
        root.setClickable(true);
        root.setFocusable(true);
        root.setContentDescription(item.getTitle());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, dp(TAB_HEIGHT_DP), WEIGHT_INACTIVE);
        rp.setMargins(dp(1), 0, dp(1), 0);
        root.setLayoutParams(rp);

        View highlight = new View(context);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(highlightColor);
        bg.setCornerRadius(dp(100));
        highlight.setBackground(bg);
        highlight.setVisibility(View.GONE);
        root.addView(highlight, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(dp(10), 0, dp(10), 0);

        ImageView icon = new ImageView(context);
        icon.setImageDrawable(item.getIcon());
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));

        TextView label = new TextView(context);
        label.setText(item.getTitle());
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setPadding(dp(8), 0, 0, 0);
        label.setVisibility(View.GONE);

        content.addView(icon);
        content.addView(label);
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        root.setOnClickListener(v -> {
            select(item.getItemId(), true);
            listener.onSelected(item.getItemId());
        });
        container.addView(root);
        tabs.add(new Tab(item, root, highlight, icon, label));
    }

    /** Highlights one tab and collapses the rest (animated with the spring when animate is true). */
    public void select(int itemId, boolean animate) {
        if (animate) {
            TransitionSet spring = new TransitionSet();
            spring.setOrdering(TransitionSet.ORDERING_TOGETHER);
            spring.addTransition(new ChangeBounds());
            spring.addTransition(new Fade());
            spring.setDuration(ANIMATION_MS);
            spring.setInterpolator(new SpringInterpolator());
            TransitionManager.beginDelayedTransition(sceneRoot, spring);
        }
        for (Tab tab : tabs) {
            boolean active = tab.item.getItemId() == itemId;
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) tab.root.getLayoutParams();
            lp.weight = active ? WEIGHT_ACTIVE : WEIGHT_INACTIVE;
            tab.root.setLayoutParams(lp);
            tab.label.setVisibility(active ? View.VISIBLE : View.GONE);
            tab.highlight.setVisibility(active ? View.VISIBLE : View.GONE);
            int color = active ? activeColor : inactiveColor;
            tab.icon.setColorFilter(color);
            tab.label.setTextColor(color);
        }
    }

    private int dp(int v) {
        return AdminUi.dp(context, v);
    }
}