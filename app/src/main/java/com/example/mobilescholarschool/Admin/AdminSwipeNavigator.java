package com.example.mobilescholarschool.Admin;

import android.app.Activity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.example.mobilescholarschool.Components.SkeletonView;
import com.example.mobilescholarschool.R;

/**
 * Horizontal swipe between the five admin tabs: the admin version of the student SwipeNavigator.
 *
 * What the person experiences is the same as on the student side:
 *   - the page FOLLOWS the finger (the bottom pill stays still);
 *   - a grey skeleton of the next page follows right behind it, like a preview;
 *   - let go past ~30% of the screen, or flick: the page springs away, the pill springs across,
 *     and the new tab's real content springs in over the skeleton a moment later;
 *   - let go early: the page springs back; dragging past the first or last tab feels "heavy".
 *
 * LESSON - WHAT IS DIFFERENT: the student screens are separate ACTIVITIES, so SwipeNavigator has to
 * start the next Activity and let it play the entrance. The admin tabs are FRAGMENTS inside ONE
 * Activity, so there is no hand-over between screens. This class slides one view (adminContainer),
 * asks the host to swap the Fragment while the page is off screen, then reveals the new content
 * itself. Fewer moving parts, same feel. The skeleton is your SkeletonView, unchanged.
 *
 * HOW IT IS WIRED: AdminMainActivity creates it, and its dispatchTouchEvent asks it first.
 */
public final class AdminSwipeNavigator {

    /** What this class needs from the Activity. */
    public interface Host {
        /** The id of the tab on screen right now (R.id.admin_nav_...). */
        int currentTab();

        /** Show this tab: move the pill and swap the Fragment. */
        void switchTo(int tabId);
    }

    // ---- Tuning knobs: same values as the student SwipeNavigator, so both sides FEEL the same ----
    private static final float HORIZONTAL_RATIO = 1.5f;      // sideways must be 1.5x vertical to be a swipe
    private static final float COMMIT_FRACTION = 0.30f;      // release past 30% of the width = change tab
    private static final int FLING_VELOCITY_DP = 500;        // ...or flick faster than this
    private static final float OVERSCROLL_RESISTANCE = 0.25f;
    private static final float HANDOFF_FRACTION = 0.98f;     // page this far across: swap the Fragment
    private static final long MIN_SKELETON_MS = 350;         // skeleton never just flickers
    private static final int REVEAL_LIFT_DP = 16;

    private static final float EXIT_STIFFNESS = 600f;
    private static final float EXIT_DAMPING = SpringForce.DAMPING_RATIO_NO_BOUNCY;
    private static final float RETURN_STIFFNESS = 500f;
    private static final float RETURN_DAMPING = SpringForce.DAMPING_RATIO_LOW_BOUNCY;
    private static final float ENTER_STIFFNESS = 350f;
    private static final float ENTER_DAMPING = 0.8f;

    private static final int NEXT = 1, PREVIOUS = -1;

    private final Activity activity;
    private final int[] order;          // tab ids, left to right
    private final Host host;
    private final View content;         // the ONE view that slides: R.id.adminContainer

    private final int screenWidth;
    private final int touchSlop;
    private final float flingVelocityPx;

    private VelocityTracker velocityTracker;
    private int activePointerId;
    private float downX, downY, dragStartX;
    private boolean tracking = false;   // watching this gesture to see if it becomes a swipe
    private boolean dragging = false;   // it IS a swipe: we own the touches now

    private float offset = 0f;          // how far the page is shifted right (+) or left (-)
    private SpringAnimation spring, reveal;
    private boolean animating = false;

    private boolean leaving = false;    // committed: the page is flying away, then the new tab is revealed
    private boolean handedOff = false;
    private int leavingTabId = View.NO_ID;
    private boolean arriving = false;   // the opening skeleton is on screen

    private SkeletonView skeleton;

    public AdminSwipeNavigator(@NonNull Activity activity, @NonNull int[] tabOrder, @NonNull Host host) {
        this.activity = activity;
        this.order = tabOrder;
        this.host = host;
        this.content = activity.findViewById(R.id.adminContainer);
        this.screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        this.touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        this.flingVelocityPx = FLING_VELOCITY_DP * activity.getResources().getDisplayMetrics().density;
    }

    /** Neighbour of the CURRENT tab in a direction, or View.NO_ID at either end. */
    private int neighbor(int direction) {
        int index = -1;
        for (int i = 0; i < order.length; i++) {
            if (order[i] == host.currentTab()) index = i;
        }
        int target = index + direction;
        return (index < 0 || target < 0 || target >= order.length) ? View.NO_ID : order[target];
    }

    // ------------------------------------------------------------------ touch handling

    /**
     * Call first from Activity.dispatchTouchEvent.
     * @return true if the swipe has taken over this gesture (do NOT pass it on)
     */
    public boolean onTouchEvent(@NonNull MotionEvent ev) {
        if (leaving) {
            return true;   // page is flying away / being revealed: swallow touches
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return onDown(ev);
            case MotionEvent.ACTION_MOVE:
                return onMove(ev);
            case MotionEvent.ACTION_POINTER_DOWN:
                if (!dragging) stopTracking();   // a second finger is not a one-finger swipe
                return dragging;
            case MotionEvent.ACTION_POINTER_UP:
                if (dragging && ev.getPointerId(ev.getActionIndex()) == activePointerId) return onUp(ev);
                return dragging;
            case MotionEvent.ACTION_UP:
                return onUp(ev);
            case MotionEvent.ACTION_CANCEL:
                return onCancel();
            default:
                return dragging;
        }
    }

    private boolean onDown(MotionEvent ev) {
        stopTracking();
        if (animating) return false;   // still settling: let touches through, no new drag
        tracking = true;
        activePointerId = ev.getPointerId(0);
        downX = ev.getX();
        downY = ev.getY();
        velocityTracker = VelocityTracker.obtain();
        velocityTracker.addMovement(ev);
        return false;   // never consume DOWN: taps and scrolls must still start normally
    }

    private boolean onMove(MotionEvent ev) {
        if (!tracking) return dragging;
        velocityTracker.addMovement(ev);
        int index = ev.findPointerIndex(activePointerId);
        if (index < 0) return dragging;
        float x = ev.getX(index), y = ev.getY(index);

        if (!dragging) {
            float dx = x - downX, dy = y - downY;
            if (Math.max(Math.abs(dx), Math.abs(dy)) <= touchSlop) return false;   // not sure yet
            if (Math.abs(dx) > Math.abs(dy) * HORIZONTAL_RATIO) {
                startDrag(ev, x);
            } else {
                stopTracking();   // mostly vertical: a scroll, leave it alone
                return false;
            }
        }
        setOffset(withResistance(x - dragStartX));
        return true;
    }

    private boolean onUp(MotionEvent ev) {
        if (!dragging) {
            stopTracking();
            return false;
        }
        velocityTracker.addMovement(ev);
        velocityTracker.computeCurrentVelocity(1000);
        float velocityX = velocityTracker.getXVelocity(activePointerId);
        int index = ev.findPointerIndex(activePointerId);
        float x = index >= 0 ? ev.getX(index) : dragStartX + offset;
        stopTracking();
        finishDrag(x - dragStartX, velocityX);
        return true;
    }

    private boolean onCancel() {
        boolean wasDragging = dragging;
        stopTracking();
        if (wasDragging) animateTo(0f, 0f, RETURN_STIFFNESS, RETURN_DAMPING);
        return wasDragging;
    }

    private void startDrag(MotionEvent ev, float x) {
        dragging = true;
        dragStartX = x;   // the page starts moving from here, so it does not "jump" by the slop

        // The ScrollView / buttons already got the DOWN: tell them it was cancelled so a button
        // cannot stay "pressed". Sent to the content area, NOT the window (the window would send it back to us).
        MotionEvent cancel = MotionEvent.obtain(ev);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        activity.findViewById(android.R.id.content).dispatchTouchEvent(cancel);
        cancel.recycle();

        ensureSkeleton();   // the preview of the next page, parked off screen until needed
    }

    private void stopTracking() {
        tracking = false;
        dragging = false;
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    // ------------------------------------------------------------------ letting go

    private void finishDrag(float dragDistance, float velocityX) {
        // Finger moved LEFT -> the tab on the RIGHT (next), like turning a page.
        int direction = dragDistance < 0 ? NEXT : PREVIOUS;
        int neighbor = neighbor(direction);

        boolean farEnough = Math.abs(dragDistance) > screenWidth * COMMIT_FRACTION;
        boolean fastEnough = Math.abs(velocityX) > flingVelocityPx
                && (velocityX < 0) == (dragDistance < 0);   // a flick must point the way of the drag

        if (neighbor != View.NO_ID && (farEnough || fastEnough)) {
            leaving = true;
            handedOff = false;
            leavingTabId = neighbor;
            animateTo(-direction * (float) screenWidth, velocityX, EXIT_STIFFNESS, EXIT_DAMPING);
        } else {
            float velocity = neighbor == View.NO_ID ? velocityX * OVERSCROLL_RESISTANCE : velocityX;
            animateTo(0f, velocity, RETURN_STIFFNESS, RETURN_DAMPING);
        }
    }

    // ------------------------------------------------------------------ springs

    /**
     * LESSON: a SpringAnimation is physics, not a timer. It has no duration; it simulates a weight on
     * a spring, so it can start with the speed of your finger and settle naturally.
     */
    private void animateTo(float target, float velocity, float stiffness, float dampingRatio) {
        if (spring != null) spring.cancel();   // never let two springs fight

        spring = new SpringAnimation(new FloatValueHolder(offset));
        spring.setSpring(new SpringForce(target).setStiffness(stiffness).setDampingRatio(dampingRatio));
        spring.setStartVelocity(velocity);
        spring.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_PIXELS);

        spring.addUpdateListener((animation, value, currentVelocity) -> {
            setOffset(value);
            // The old page is almost off screen (and the skeleton almost in place): swap the tab.
            if (leaving && !handedOff && Math.abs(value) >= screenWidth * HANDOFF_FRACTION) {
                handedOff = true;
                content.post(this::handOff);   // posted: never change the spring from inside its own callback
            }
        });

        spring.addEndListener((animation, canceled, value, currentVelocity) -> {
            if (!canceled && !leaving) {   // when leaving, handOff() takes over
                setOffset(target);
                animating = false;
            }
        });

        animating = true;
        spring.start();
    }

    private void setOffset(float x) {
        offset = x;
        content.setTranslationX(x);
        updateSkeletonPreview();
    }

    private float withResistance(float dragDistance) {
        int neighbor = neighbor(dragDistance < 0 ? NEXT : PREVIOUS);
        return neighbor == View.NO_ID ? dragDistance * OVERSCROLL_RESISTANCE : dragDistance;
    }

    // ------------------------------------------------------------------ the tab change

    /**
     * The old page is off screen and the skeleton is exactly where it will stay. In ONE step: bring
     * the (now invisible) container back to the middle, switch tab, and wait a moment before
     * revealing, so the new Fragment has time to build its views.
     */
    private void handOff() {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        if (spring != null) spring.cancel();
        arriving = true;

        offset = 0f;
        content.setAlpha(0f);          // hide FIRST, then move: the old page never flashes back
        content.setTranslationX(0f);

        SkeletonView s = ensureSkeleton();
        s.setTranslationX(0f);
        s.setAlpha(1f);
        s.setVisibility(View.VISIBLE);

        host.switchTo(leavingTabId);   // pill springs across and the Fragment is swapped
        content.postDelayed(this::revealContent, MIN_SKELETON_MS);
    }

    /** Springs the real content in over the skeleton: fades up while rising a few dp. */
    private void revealContent() {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        final float lift = REVEAL_LIFT_DP * activity.getResources().getDisplayMetrics().density;
        content.setTranslationY(lift);

        reveal = new SpringAnimation(new FloatValueHolder(0f));
        reveal.setSpring(new SpringForce(1f).setStiffness(ENTER_STIFFNESS).setDampingRatio(ENTER_DAMPING));
        reveal.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_ALPHA);

        reveal.addUpdateListener((animation, value, velocity) -> {
            float progress = Math.max(0f, Math.min(1f, value));   // the spring may overshoot; alpha may not
            content.setAlpha(progress);
            content.setTranslationY((1f - value) * lift);
            if (skeleton != null) skeleton.setAlpha(1f - progress);
        });

        reveal.addEndListener((animation, canceled, value, velocity) -> finishArrival());
        reveal.start();
    }

    private void finishArrival() {
        content.setAlpha(1f);
        content.setTranslationY(0f);
        content.setTranslationX(0f);
        removeSkeleton();
        arriving = false;
        animating = false;
        leaving = false;
        handedOff = false;
        leavingTabId = View.NO_ID;
        offset = 0f;
    }

    // ------------------------------------------------------------------ skeleton

    private SkeletonView ensureSkeleton() {
        if (skeleton == null) {
            skeleton = new SkeletonView(activity);
            skeleton.setVisibility(View.INVISIBLE);
            // android.R.id.content holds the whole screen layout; the skeleton goes ON TOP of it and
            // stops above the bottom bar by itself (insets code in SkeletonView), so the pill is never covered.
            ViewGroup frame = activity.findViewById(android.R.id.content);
            frame.addView(skeleton, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            ViewCompat.requestApplyInsets(skeleton);
        }
        return skeleton;
    }

    private void removeSkeleton() {
        if (skeleton != null) {
            ((ViewGroup) activity.findViewById(android.R.id.content)).removeView(skeleton);
            skeleton = null;
        }
    }

    /** While dragging: keep the skeleton glued to the page edge the next tab would arrive from. */
    private void updateSkeletonPreview() {
        if (skeleton == null || arriving) return;
        boolean movingLeft = offset < 0;
        int neighbor = neighbor(movingLeft ? NEXT : PREVIOUS);
        boolean show = offset != 0f && neighbor != View.NO_ID;
        skeleton.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        if (show) {
            skeleton.setTranslationX(offset + (movingLeft ? screenWidth : -screenWidth));
        }
    }

    /** Called from Activity.onDestroy so no animation keeps a reference to a dead screen. */
    public void release() {
        if (spring != null) spring.cancel();
        if (reveal != null) reveal.cancel();
        removeSkeleton();
    }
}
