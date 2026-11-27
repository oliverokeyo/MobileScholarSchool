package com.example.mobilescholarschool.Onboarding;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.example.mobilescholarschool.R;

/**
 * Page indicator for the onboarding ViewPager2: small circles for the other pages and a
 * longer pill for the current one.
 *
 * It owns NO page state of its own. Every frame it reads the pager's position
 * (page + scroll offset), so it can never disagree with what is on screen. Because the
 * pager reports each frame of a scroll, the pill grows and shrinks smoothly, following
 * the finger on a swipe and animating by itself on a programmatic page change.
 *
 * Usage: call attachToViewPager(viewPager) AFTER the adapter has been set.
 */
public class OnboardingPageIndicator extends View {

    // Sizes in dp (converted to pixels once, so they scale with screen density).
    private static final float DOT_SIZE_DP = 8f;
    private static final float ACTIVE_WIDTH_DP = 24f;
    private static final float SPACING_DP = 8f;

    // Reused every frame: onDraw must not create objects (it runs up to 60 times a second).
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final float dotSize;
    private final float activeWidth;
    private final float spacing;
    private final int colorInactive;
    private final int colorActive;

    private int pageCount;
    // Where the pager is right now, e.g. 1.5 = halfway between page 2 and page 3.
    private float progress;

    private ViewPager2 pager;
    private RecyclerView.Adapter<?> adapter;
    private ViewPager2.OnPageChangeCallback pageCallback;
    private RecyclerView.AdapterDataObserver dataObserver;

    public OnboardingPageIndicator(@NonNull Context context) {
        this(context, null);
    }

    public OnboardingPageIndicator(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public OnboardingPageIndicator(@NonNull Context context, @Nullable AttributeSet attrs,
                                   int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        float density = getResources().getDisplayMetrics().density;
        dotSize = DOT_SIZE_DP * density;
        activeWidth = ACTIVE_WIDTH_DP * density;
        spacing = SPACING_DP * density;

        // Same colors your old tab_indicator_selector used.
        colorInactive = ContextCompat.getColor(context, R.color.onboarding_dot_default);
        colorActive = ContextCompat.getColor(context, R.color.onboarding_dot_selected);

        // Show 4 dots in Android Studio's layout preview (there is no pager there).
        if (isInEditMode()) {
            pageCount = 4;
        }
    }

    /** Links the indicator to the pager. The pager stays the single source of truth. */
    public void attachToViewPager(@NonNull ViewPager2 viewPager) {
        detach();

        RecyclerView.Adapter<?> pagerAdapter = viewPager.getAdapter();
        if (pagerAdapter == null) {
            throw new IllegalStateException(
                    "Set the adapter on the ViewPager2 before attaching the indicator.");
        }

        pager = viewPager;
        adapter = pagerAdapter;
        pageCount = pagerAdapter.getItemCount();
        progress = viewPager.getCurrentItem();
        updateDescription(viewPager.getCurrentItem());

        pageCallback = new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageScrolled(int position, float positionOffset,
                                       int positionOffsetPixels) {
                progress = position + positionOffset;
                invalidate(); // redraw with the new progress
            }

            @Override
            public void onPageSelected(int position) {
                updateDescription(position);
            }
        };
        viewPager.registerOnPageChangeCallback(pageCallback);

        // If pages are ever added or removed, the number of dots follows.
        dataObserver = new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                refreshPageCount();
            }

            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                refreshPageCount();
            }

            @Override
            public void onItemRangeRemoved(int positionStart, int itemCount) {
                refreshPageCount();
            }
        };
        pagerAdapter.registerAdapterDataObserver(dataObserver);

        requestLayout();
        invalidate();
    }

    private void refreshPageCount() {
        if (adapter == null || pager == null) {
            return;
        }
        pageCount = adapter.getItemCount();
        progress = Math.min(progress, Math.max(0, pageCount - 1));
        updateDescription(pager.getCurrentItem());
        requestLayout();
        invalidate();
    }

    /** Spoken by TalkBack, e.g. "Page 2 of 4". */
    private void updateDescription(int position) {
        setContentDescription(getContext().getString(
                R.string.onboarding_page_indicator, position + 1, pageCount));
    }

    private void detach() {
        if (pager != null && pageCallback != null) {
            pager.unregisterOnPageChangeCallback(pageCallback);
        }
        if (adapter != null && dataObserver != null) {
            adapter.unregisterAdapterDataObserver(dataObserver);
        }
        pager = null;
        adapter = null;
        pageCallback = null;
        dataObserver = null;
    }

    @Override
    protected void onDetachedFromWindow() {
        detach(); // don't leak the pager/adapter
        super.onDetachedFromWindow();
    }

    /** Width of the whole row at rest: (n - 1) circles + 1 pill + the gaps between them. */
    private float contentWidth() {
        if (pageCount <= 0) {
            return 0f;
        }
        return (pageCount - 1) * dotSize + activeWidth + (pageCount - 1) * spacing;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredWidth = (int) Math.ceil(contentWidth()) + getPaddingLeft() + getPaddingRight();
        int desiredHeight = (int) Math.ceil(dotSize) + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(
                resolveSize(desiredWidth, widthMeasureSpec),
                resolveSize(desiredHeight, heightMeasureSpec));
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (pageCount <= 0) {
            return;
        }

        // Centre the row inside whatever space we were given.
        float availableWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        float x = getPaddingLeft() + (availableWidth - contentWidth()) / 2f;
        float top = getPaddingTop()
                + (getHeight() - getPaddingTop() - getPaddingBottom() - dotSize) / 2f;
        float radius = dotSize / 2f;

        // In right-to-left languages the pager runs right-to-left, so draw the dots the same way.
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;

        for (int visualIndex = 0; visualIndex < pageCount; visualIndex++) {
            int page = rtl ? pageCount - 1 - visualIndex : visualIndex;

            // 1.0 when the pager is exactly on this page, fading to 0.0 one page away.
            float weight = Math.max(0f, 1f - Math.abs(progress - page));

            float width = dotSize + (activeWidth - dotSize) * weight;
            paint.setColor(ColorUtils.blendARGB(colorInactive, colorActive, weight));

            rect.set(x, top, x + width, top + dotSize);
            canvas.drawRoundRect(rect, radius, radius, paint);

            x += width + spacing;
        }
    }
}