package com.example.mobilescholarschool.Admin;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.example.mobilescholarschool.R;
import com.google.android.material.button.MaterialButton;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Date;
import java.util.Locale;

/** Small view-building and formatting helpers shared by every admin screen. */
public final class AdminUi {

    public static final int GREEN = 0xFF2E7D32;
    public static final int ORANGE = 0xFFF26B2C;
    public static final int RED = 0xFFC62828;
    public static final int GREY = 0xFF70645A;
    public static final int BLUE = 0xFF1565C0;

    private AdminUi() {}

    public static int dp(Context c, int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    public static String ksh(long amount) {
        return "Ksh. " + String.format(Locale.US, "%,d", amount);
    }

    public static String date(long millis) {
        return millis <= 0 ? "-" : new SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(new Date(millis));
    }

    public static String dateTime(long millis) {
        return millis <= 0 ? "-" : new SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(new Date(millis));
    }

    /**
     * The items of a string-array (like department_list), WITHOUT its first "Select ..." placeholder.
     * If the student's saved value is not in the list any more, it is added at the top so editing
     * never silently changes it.
     */
    public static String[] listFromArray(Context c, int arrayRes, String current) {
        String[] raw = c.getResources().getStringArray(arrayRes);
        List<String> out = new ArrayList<>();
        for (int i = 1; i < raw.length; i++) out.add(raw[i]);
        if (current != null && !current.isEmpty() && !out.contains(current)) out.add(0, current);
        return out.toArray(new String[0]);
    }

    public static String cap(String s) {
        return (s == null || s.isEmpty()) ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public static void toast(Context c, String message) {
        if (c != null) Toast.makeText(c.getApplicationContext(), message, Toast.LENGTH_LONG).show();
    }

    /** Paints a rounded "chip" background: a pale tint of the colour, with the text in the full colour. */
    public static void chip(TextView view, int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(view.getContext(), 20));
        bg.setColor((color & 0x00FFFFFF) | 0x26000000);   // ~15% opacity
        view.setBackground(bg);
        view.setTextColor(color);
    }

    public static TextView sectionTitle(Context c, ViewGroup parent, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextAppearance(R.style.TextAppearance_App_Section);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 14);
        lp.bottomMargin = dp(c, 8);
        parent.addView(tv, lp);
        return tv;
    }

    public static TextView body(Context c, ViewGroup parent, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextAppearance(R.style.TextAppearance_App_Body);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 4);
        parent.addView(tv, lp);
        return tv;
    }

    /** A tappable service row (icon, title, description, arrow). */
    public static void serviceRow(Context c, ViewGroup parent, int iconRes, String title, String desc,
                                  View.OnClickListener click) {
        View row = LayoutInflater.from(c).inflate(R.layout.admin_item_service, parent, false);
        ((ImageView) row.findViewById(R.id.admIcon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.admTitle)).setText(title);
        ((TextView) row.findViewById(R.id.admDesc)).setText(desc);
        row.setOnClickListener(click);
        parent.addView(row);
    }

    /** A row of number cards. Returns the value TextViews so the caller can fill them in later. */
    public static TextView[] statRow(Context c, ViewGroup parent, String... labels) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(c, 8);
        parent.addView(row, rowLp);
        TextView[] values = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            View card = LayoutInflater.from(c).inflate(R.layout.admin_item_stat, row, false);
            ((TextView) card.findViewById(R.id.admStatLabel)).setText(labels[i]);
            values[i] = card.findViewById(R.id.admStatValue);
            row.addView(card);
        }
        return values;
    }

    /** primary = filled dark button, otherwise an outlined one. */
    public static MaterialButton button(Context c, String text, boolean primary, View.OnClickListener click) {
        MaterialButton b = primary
                ? new MaterialButton(c)
                : new MaterialButton(c, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(click);
        return b;
    }

    public static int colorOfStatus(String status) {
        switch (status == null ? "" : status) {
            case "approved": case "success": case "active": case "registered": case "returned":
            case "available": case "cleared": return GREEN;
            case "pending": case "issued": case "unverified": case "owing": return ORANGE;
            case "rejected": case "failed": case "overdue": case "suspended": case "full":
            case "deactivated": return RED;
            default: return GREY;
        }
    }
}
