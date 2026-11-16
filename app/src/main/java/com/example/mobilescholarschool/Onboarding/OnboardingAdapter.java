package com.example.mobilescholarschool.Onboarding;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.mobilescholarschool.R;

import java.util.List;

/**
 * Feeds the ViewPager2 its pages. ViewPager2 is built on RecyclerView,
 * so this is a normal RecyclerView.Adapter where each "row" is a full screen.
 */
public class OnboardingAdapter extends RecyclerView.Adapter<OnboardingAdapter.PageViewHolder> {

    private final List<OnboardingItem> items;

    public OnboardingAdapter(List<OnboardingItem> items) {
        this.items = items;
    }

    // Called when a NEW page view must be created: inflate the XML once.
    @NonNull
    @Override
    public PageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_onboarding_page, parent, false);
        return new PageViewHolder(view);
    }

    // Called every time a page is shown: fill it with that page's data.
    @Override
    public void onBindViewHolder(@NonNull PageViewHolder holder, int position) {
        OnboardingItem item = items.get(position);
        holder.image.setImageResource(item.getImageRes());
        holder.title.setText(item.getTitleRes());
        holder.description.setText(item.getDescriptionRes());
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    // Holds references to the views so findViewById runs once, not on every swipe.
    static class PageViewHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView title;
        final TextView description;

        PageViewHolder(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.imgOnboarding);
            title = itemView.findViewById(R.id.txtTitle);
            description = itemView.findViewById(R.id.txtDescription);
        }
    }
}
