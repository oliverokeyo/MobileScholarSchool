package com.example.mobilescholarschool.Admin;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.mobilescholarschool.R;

/**
 * LESSON: all five tabs look the same (title, subtitle, a column of cards). This base class inflates
 * that shell ONCE; each tab only says what goes in the column (build) and what to refresh when it
 * comes back into view (refresh), for example after an admin changed something in a dialog.
 */
public abstract class AdminScreenFragment extends Fragment {

    protected abstract String screenTitle();

    protected abstract String screenSubtitle();

    /** Add the cards and rows. Called once, when the screen is created. */
    protected abstract void build(LinearLayout content);

    /** Reload the numbers. Called each time the tab is shown again. */
    protected void refresh() {}

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.admin_screen, container, false);
        ((TextView) root.findViewById(R.id.admScreenTitle)).setText(screenTitle());
        ((TextView) root.findViewById(R.id.admScreenSubtitle)).setText(screenSubtitle());
        build(root.findViewById(R.id.admContent));
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }
}
