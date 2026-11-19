package com.example.mobilescholarschool.Components;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;

/**
 * A "skeleton" placeholder: grey blocks shaped like a typical screen (a title, a subtitle and
 * a stack of cards) with a soft highlight sweeping across them.
 *
 * It is drawn with Canvas, so it needs no XML layout. SwipeNavigator adds it on top of the
 * screen's content (but ABOVE the bottom bar, never covering it) in two situations:
 *   1. while you drag, as a preview of the page you are about to open, and
 *   2. on the new screen, for a moment, until the real content is ready.
 *
 * Colours come from the app's theme, so it looks right in light and dark mode.
 */
public class SkeletonView extends View {

    private static final long SHIMMER_MS = 1300;      // one sweep across the screen
    private static final int BASE_ALPHA = 20;          // 0-255: how visible the blocks are (~8%)
    private static final int HIGHLIGHT_ALPHA = 44;     // 0-255: the brighter band that sweeps (~17%)

    private static final int MARGIN_DP = 16;
    private static final int CARD_HEIGHT_DP = 96;
    private static final int CARD_GAP_DP = 16;

    /** One grey rounded rectangle. */
    private static final class Block {
        final RectF rect;
        final float radius;
        Block(float left, float top, float right, float bottom, float radius) {
            this.rect = new RectF(left, top, right, bottom);
            this.radius = radius;
        }
    }

    private final List<Block> blocks = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix shaderMatrix = new Matrix();
    private final float density;
    private final int baseColor;
    private final int highlightColor;

    private LinearGradient shader;
    private float gradientWidth = 1f;
    private ValueAnimator shimmer;

    public SkeletonView(@NonNull Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;

        // Draw the blocks in the theme's "text on a surface" colour, but very faint.
        int onSurface = MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorOnSurface, Color.BLACK);
        baseColor = ColorUtils.setAlphaComponent(onSurface, BASE_ALPHA);
        highlightColor = ColorUtils.setAlphaComponent(onSurface, HIGHLIGHT_ALPHA);

        applyWindowBackground(context);

        setClickable(true);   // swallow touches so nothing underneath can be tapped by accident
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);   // screen readers skip it

        // Fit between the status bar and the bottom bar. Insets are the sizes of the system
        // bars; the bottom bar itself is 80dp, so we stop short of it and leave it uncovered.
        ViewCompat.setOnApplyWindowInsetsListener(this, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            setPadding(bars.left, bars.top, bars.right, 0);

            if (getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) getLayoutParams();
                int bottomMargin = bars.bottom + BottomNavHelper.barHeightPx(getContext());
                if (params.bottomMargin != bottomMargin) {
                    params.bottomMargin = bottomMargin;
                    setLayoutParams(params);
                }
            }
            return insets;   // not consumed: other views still get their turn
        });
    }

    /** Paint over the content with the same colour the window has behind it. */
    private void applyWindowBackground(Context context) {
        TypedValue value = new TypedValue();
        boolean found = context.getTheme()
                .resolveAttribute(android.R.attr.windowBackground, value, true);

        if (found && value.resourceId != 0) {
            setBackground(ContextCompat.getDrawable(context, value.resourceId));
        } else if (found && value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            setBackgroundColor(value.data);
        } else {
            setBackgroundColor(MaterialColors.getColor(context,
                    com.google.android.material.R.attr.colorSurface, Color.WHITE));
        }
    }

    // ------------------------------------------------------------------
    // Shape layout: runs whenever the size changes
    // ------------------------------------------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        blocks.clear();
        float margin = dp(MARGIN_DP);
        float left = getPaddingLeft() + margin;
        float right = w - getPaddingRight() - margin;
        float y = getPaddingTop() + margin;
        float contentWidth = right - left;

        // Title
        blocks.add(new Block(left, y, left + contentWidth * 0.55f, y + dp(28), dp(8)));
        y += dp(28) + dp(10);

        // Subtitle
        blocks.add(new Block(left, y, left + contentWidth * 0.35f, y + dp(16), dp(6)));
        y += dp(16) + dp(24);

        // As many cards as fit in the space that is left
        float cardHeight = dp(CARD_HEIGHT_DP);
        while (y + cardHeight <= h - dp(8)) {
            blocks.add(new Block(left, y, right, y + cardHeight, dp(16)));
            y += cardHeight + dp(CARD_GAP_DP);
        }

        // The highlight band is 60% of the width: transparent-ish, bright in the middle, then back.
        gradientWidth = Math.max(1f, w * 0.6f);
        shader = new LinearGradient(0, 0, gradientWidth, 0,
                new int[]{baseColor, highlightColor, baseColor},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP);   // CLAMP: outside the band, blocks stay the base colour
        paint.setShader(shader);
        moveShimmer(0f);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        for (Block block : blocks) {
            canvas.drawRoundRect(block.rect, block.radius, block.radius, paint);
        }
    }

    // ------------------------------------------------------------------
    // Shimmer: only runs while the view is actually visible (saves battery)
    // ------------------------------------------------------------------

    private void moveShimmer(float progress) {
        if (shader == null) {
            return;
        }
        // Slide the band from just off the left edge to just off the right edge.
        float x = -gradientWidth + progress * (getWidth() + gradientWidth);
        shaderMatrix.setTranslate(x, 0f);
        shader.setLocalMatrix(shaderMatrix);
        invalidate();
    }

    private void updateShimmerState() {
        boolean shouldRun = isAttachedToWindow() && getVisibility() == VISIBLE;
        if (shouldRun && shimmer == null) {
            shimmer = ValueAnimator.ofFloat(0f, 1f);
            shimmer.setDuration(SHIMMER_MS);
            shimmer.setInterpolator(new LinearInterpolator());
            shimmer.setRepeatCount(ValueAnimator.INFINITE);
            shimmer.addUpdateListener(a -> moveShimmer((float) a.getAnimatedValue()));
            shimmer.start();
        } else if (!shouldRun && shimmer != null) {
            shimmer.cancel();
            shimmer = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateShimmerState();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        updateShimmerState();
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateShimmerState();
    }

    private float dp(int value) {
        return value * density;
    }
}
