package com.example.mobilescholarschool.Components;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.example.mobilescholarschool.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Horizontal swipe navigation between the bottom-bar tabs.
 *
 * What the user experiences:
 *   - The page FOLLOWS the finger while dragging (the bottom pill stays still).
 *   - A grey "skeleton" of the next page follows right behind it, like a preview.
 *   - Let go past ~30% of the screen, or flick quickly: the page springs off screen,
 *     leaving the skeleton in its place. The new screen opens showing the same skeleton,
 *     the pill springs across, and a moment later the real content springs in over it.
 *   - Let go early: the page springs back to where it was.
 *   - Dragging past the first or last tab feels "heavy" (rubber band) and springs back.
 *
 * HOW TO USE (in each tab screen):
 *   1. Field:     private SwipeNavigator swipeNavigator;
 *   2. onCreate:  AFTER BottomNavHelper.setup(...):
 *                     swipeNavigator = new SwipeNavigator(this, R.id.nav_home);
 *   3. Override dispatchTouchEvent and ask swipeNavigator first (see the activities).
 *
 * Needs:  implementation("androidx.dynamicanimation:dynamicanimation:1.1.0")
 */
public final class SwipeNavigator {

    // ------------------------------------------------------------------
    // Tuning knobs (change these to adjust the feel)
    // ------------------------------------------------------------------

    /** Sideways movement must be 1.5x the vertical movement to count as a swipe, not a scroll. */
    private static final float HORIZONTAL_RATIO = 1.5f;

    /** Release after dragging this fraction of the screen width and we change tab. */
    private static final float COMMIT_FRACTION = 0.30f;

    /** ...or release while moving faster than this (dp per second) in the drag direction. */
    private static final int FLING_VELOCITY_DP = 500;

    /** Dragging toward a tab that does not exist moves the page only this fraction as far. */
    private static final float OVERSCROLL_RESISTANCE = 0.25f;

    /**
     * When the leaving page is this far across the screen, open the next screen. It is almost
     * fully across because the skeleton (not a blank window) now fills the gap, so the new
     * screen can safely take its time to appear.
     */
    private static final float HANDOFF_FRACTION = 0.98f;

    /** The skeleton stays up at least this long on the new screen, so it never just flickers. */
    private static final long MIN_SKELETON_MS = 350;

    /** The real content rises this far (dp) as it springs in over the skeleton. */
    private static final int REVEAL_LIFT_DP = 16;

    // Spring settings. STIFFNESS = how hard it pulls toward the target (higher = snappier).
    // DAMPING RATIO = how quickly the wobble dies: 1 = no bounce, lower = more bounce.
    private static final float EXIT_STIFFNESS = 600f;
    private static final float EXIT_DAMPING = SpringForce.DAMPING_RATIO_NO_BOUNCY;   // 1.0

    private static final float RETURN_STIFFNESS = 500f;
    private static final float RETURN_DAMPING = SpringForce.DAMPING_RATIO_LOW_BOUNCY; // 0.75

    // The reveal spring: real content fading and rising in over the skeleton.
    private static final float ENTER_STIFFNESS = 350f;
    private static final float ENTER_DAMPING = 0.8f;

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private final Activity activity;
    private final int currentTabId;
    private final int nextTabId;       // tab to the right, or View.NO_ID if we are the last
    private final int previousTabId;   // tab to the left, or View.NO_ID if we are the first

    private final int screenWidth;
    private final int touchSlop;
    private final float flingVelocityPx;

    /** The views that slide: every direct child of R.id.main EXCEPT the bottom bar. */
    private final List<View> contentViews = new ArrayList<>();

    private VelocityTracker velocityTracker;
    private int activePointerId;
    private float downX, downY;       // where the finger first touched
    private float dragStartX;         // where the finger was when we recognised a swipe
    private boolean tracking = false; // watching this gesture to see if it becomes a swipe
    private boolean dragging = false; // it IS a swipe: we own the touches now

    private float offset = 0f;        // how far the content is shifted right (+) or left (-), in px
    private SpringAnimation spring;
    private boolean animating = false;

    private boolean leaving = false;  // committed to a tab change; the page is flying away
    private boolean handedOff = false;
    private int leavingTabId = View.NO_ID;
    private int leavingDirection = 0;

    // The skeleton placeholder (see SkeletonView). Created the first time it is needed.
    private SkeletonView skeleton;
    private boolean arriving = false;   // true while THIS screen shows its opening skeleton
    private float[] originalAlpha;      // each content view's own alpha, restored after the reveal

    public SwipeNavigator(@NonNull Activity activity, int currentTabId) {
        BottomNavHelper.logSinceHandoff(activity.getClass().getSimpleName()
                + ": SwipeNavigator constructor started");   // DIAGNOSTIC
        this.activity = activity;
        this.currentTabId = currentTabId;

        this.screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        this.touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        this.flingVelocityPx = FLING_VELOCITY_DP * activity.getResources().getDisplayMetrics().density;

        this.nextTabId = BottomNavHelper.neighborTabId(activity, currentTabId,
                BottomNavHelper.DIRECTION_NEXT);
        this.previousTabId = BottomNavHelper.neighborTabId(activity, currentTabId,
                BottomNavHelper.DIRECTION_PREVIOUS);

        BottomNavHelper.logSinceHandoff("tab order read");   // DIAGNOSTIC
        collectContentViews();
        playEntranceIfWeArrivedBySwipe();
    }

    // ------------------------------------------------------------------
    // Entrance: the NEW screen shows the skeleton, then the real content springs in
    // ------------------------------------------------------------------

    private void playEntranceIfWeArrivedBySwipe() {
        Intent intent = activity.getIntent();
        int direction = intent.getIntExtra(BottomNavHelper.EXTRA_ENTER_DIRECTION, 0);

        // Remove the extras so a rotation (which rebuilds this screen) does not replay the entrance.
        intent.removeExtra(BottomNavHelper.EXTRA_ENTER_DIRECTION);
        intent.removeExtra(BottomNavHelper.EXTRA_FROM_TAB);

        if (direction == 0) {
            return;   // opened by a tap or at app start: no entrance animation
        }

        // Show the skeleton in place and hide the real content underneath it. This runs before
        // the first frame is drawn, and it matches the skeleton the previous screen ended on,
        // so the user sees no change at the moment the two screens swap.
        arriving = true;
        animating = true;   // ignore new drags until the real content has appeared
        ensureSkeleton().setVisibility(View.VISIBLE);
        hideContent();

        View root = activity.findViewById(R.id.main);

        // DIAGNOSTIC: report when Android is about to draw this screen's very first frame.
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                BottomNavHelper.logSinceHandoff("first frame is about to be drawn");
                return true;   // true = go ahead and draw
            }
        });

        // Keep the skeleton up for a minimum time, then reveal the real content. A postDelayed
        // on a view that is not attached yet starts counting once the screen is on display.
        root.postDelayed(this::revealContent, MIN_SKELETON_MS);
    }

    // ------------------------------------------------------------------
    // Touch handling
    // ------------------------------------------------------------------

    /**
     * Call this first from Activity.dispatchTouchEvent.
     * @return true if the swipe has taken over this gesture (the activity must NOT pass it on);
     *         false if nothing special is happening (carry on as normal).
     */
    public boolean onTouchEvent(@NonNull MotionEvent ev) {
        if (leaving) {
            return true;   // page is flying away: swallow touches so nothing gets tapped by accident
        }

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return onDown(ev);
            case MotionEvent.ACTION_MOVE:
                return onMove(ev);
            case MotionEvent.ACTION_POINTER_DOWN:
                // A second finger: not a one-finger swipe. Stop watching unless already dragging.
                if (!dragging) {
                    stopTracking();
                }
                return dragging;
            case MotionEvent.ACTION_POINTER_UP:
                if (dragging && ev.getPointerId(ev.getActionIndex()) == activePointerId) {
                    return onUp(ev);   // the finger we were following lifted
                }
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
        if (animating) {
            return false;   // still settling from the last gesture: let touches through, no new drag
        }
        tracking = true;
        activePointerId = ev.getPointerId(0);
        downX = ev.getX();
        downY = ev.getY();
        velocityTracker = VelocityTracker.obtain();
        velocityTracker.addMovement(ev);
        return false;   // never consume DOWN: taps and scrolls must still start normally
    }

    private boolean onMove(MotionEvent ev) {
        if (!tracking) {
            return dragging;
        }
        velocityTracker.addMovement(ev);

        int index = ev.findPointerIndex(activePointerId);
        if (index < 0) {
            return dragging;
        }
        float x = ev.getX(index);
        float y = ev.getY(index);

        if (!dragging) {
            float dx = x - downX;
            float dy = y - downY;

            // Not moved enough to tell what the user is doing yet: keep watching.
            if (Math.max(Math.abs(dx), Math.abs(dy)) <= touchSlop) {
                return false;
            }
            // Decide ONCE, at the moment the finger leaves the "slop" circle.
            if (Math.abs(dx) > Math.abs(dy) * HORIZONTAL_RATIO) {
                startDrag(ev, x);
            } else {
                stopTracking();   // mostly vertical: it's a scroll. Leave it alone.
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
        velocityTracker.computeCurrentVelocity(1000);   // 1000 = pixels per second
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
        if (wasDragging) {
            animateTo(0f, 0f, RETURN_STIFFNESS, RETURN_DAMPING);
        }
        return wasDragging;
    }

    private void startDrag(MotionEvent ev, float x) {
        dragging = true;
        dragStartX = x;   // the page starts moving from here, so it does not "jump" by the slop

        // The ScrollView / buttons already received the DOWN. Tell them the gesture was
        // cancelled so they release it (otherwise a button could stay "pressed").
        // We send it to the content area, NOT the window: the window would send it back to us.
        MotionEvent cancel = MotionEvent.obtain(ev);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        activity.findViewById(android.R.id.content).dispatchTouchEvent(cancel);
        cancel.recycle();

        collectContentViews();
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

    // ------------------------------------------------------------------
    // Letting go: commit or spring back
    // ------------------------------------------------------------------

    /**
     * @param dragDistance how far the finger travelled sideways (negative = leftwards)
     * @param velocityX    finger speed at release in px/second (negative = leftwards)
     */
    private void finishDrag(float dragDistance, float velocityX) {
        // Finger moved LEFT -> the user wants the tab on the RIGHT (next), like turning a page.
        int direction = dragDistance < 0 ? BottomNavHelper.DIRECTION_NEXT
                : BottomNavHelper.DIRECTION_PREVIOUS;
        int neighbor = direction == BottomNavHelper.DIRECTION_NEXT ? nextTabId : previousTabId;

        boolean farEnough = Math.abs(dragDistance) > screenWidth * COMMIT_FRACTION;
        boolean fastEnough = Math.abs(velocityX) > flingVelocityPx
                && (velocityX < 0) == (dragDistance < 0);   // flick must point the same way as the drag

        if (neighbor != View.NO_ID && (farEnough || fastEnough)) {
            // COMMIT: fly the page off the opposite side, carrying the finger's speed into the spring.
            leaving = true;
            leavingTabId = neighbor;
            leavingDirection = direction;
            animateTo(-direction * (float) screenWidth, velocityX, EXIT_STIFFNESS, EXIT_DAMPING);
        } else {
            // CANCEL: spring back home. With no neighbour, the drag was damped, so damp the speed too.
            float velocity = neighbor == View.NO_ID ? velocityX * OVERSCROLL_RESISTANCE : velocityX;
            animateTo(0f, velocity, RETURN_STIFFNESS, RETURN_DAMPING);
        }
    }

    /** Opens the next tab's screen. It starts off-screen and springs in (see its constructor). */
    private void openNextTab() {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        BottomNavHelper.openTab(activity, leavingTabId, currentTabId, leavingDirection);
    }

    // ------------------------------------------------------------------
    // Spring animation + moving the content
    // ------------------------------------------------------------------

    /**
     * Springs the content from wherever it is now to {@code target}.
     * A SpringAnimation is physics, not a timed animation: it has no duration. It simulates
     * a weight on a spring, so it can start with the speed of your finger and feels natural.
     */
    private void animateTo(float target, float velocity, float stiffness, float dampingRatio) {
        if (spring != null) {
            spring.cancel();   // stop any spring still running, so two never fight
        }

        // FloatValueHolder = "just a number to animate"; we apply it to the views ourselves.
        spring = new SpringAnimation(new FloatValueHolder(offset));
        spring.setSpring(new SpringForce(target)
                .setStiffness(stiffness)
                .setDampingRatio(dampingRatio));
        spring.setStartVelocity(velocity);
        spring.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_PIXELS);

        spring.addUpdateListener((animation, value, currentVelocity) -> {
            setOffset(value);

            // Once the leaving page is almost off screen (and the skeleton almost in place),
            // start the next screen. This screen keeps springing meanwhile, so nothing freezes.
            if (leaving && !handedOff && Math.abs(value) >= screenWidth * HANDOFF_FRACTION) {
                handedOff = true;
                Log.d(BottomNavHelper.TIMING_TAG, "old page is almost fully across: opening the next screen");   // DIAGNOSTIC
                openNextTab();
            }
        });

        spring.addEndListener((animation, canceled, value, currentVelocity) -> {
            if (!canceled) {
                setOffset(target);   // land exactly on the target
                animating = false;
            }
        });

        animating = true;
        spring.start();
    }

    private void setOffset(float x) {
        offset = x;
        for (View view : contentViews) {
            view.setTranslationX(x);
        }
        updateSkeletonPreview();
    }

    /** Dragging toward a tab that does not exist feels heavy: the page moves only a fraction. */
    private float withResistance(float dragDistance) {
        int neighbor = dragDistance < 0 ? nextTabId : previousTabId;
        return neighbor == View.NO_ID ? dragDistance * OVERSCROLL_RESISTANCE : dragDistance;
    }

    // ------------------------------------------------------------------
    // Skeleton placeholder
    // ------------------------------------------------------------------

    /** Creates the skeleton the first time it is needed and lays it over the content area. */
    private SkeletonView ensureSkeleton() {
        if (skeleton == null) {
            skeleton = new SkeletonView(activity);
            skeleton.setVisibility(View.INVISIBLE);

            // android.R.id.content is the frame that holds the whole screen layout. Adding the
            // skeleton here puts it ON TOP of the content. It stops above the bottom bar by
            // itself (see the insets code in SkeletonView), so the pill is never covered.
            ViewGroup frame = activity.findViewById(android.R.id.content);
            frame.addView(skeleton, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            ViewCompat.requestApplyInsets(skeleton);
        }
        return skeleton;
    }

    private void removeSkeleton() {
        if (skeleton != null) {
            ViewGroup frame = activity.findViewById(android.R.id.content);
            frame.removeView(skeleton);
            skeleton = null;
        }
    }

    /**
     * While dragging: keep the skeleton glued to the edge of the page, on the side the next tab
     * would come from. Finger moving LEFT means the next tab arrives from the RIGHT, so the
     * skeleton sits one screen-width to the right of the page, and so on. At the first/last tab
     * there is nothing to preview, so it stays hidden.
     */
    private void updateSkeletonPreview() {
        if (skeleton == null || arriving) {
            return;   // nothing to move, or this is the new screen's fixed opening skeleton
        }
        boolean movingLeft = offset < 0;
        int neighbor = movingLeft ? nextTabId : previousTabId;
        boolean show = offset != 0f && neighbor != View.NO_ID;

        skeleton.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        if (show) {
            skeleton.setTranslationX(offset + (movingLeft ? screenWidth : -screenWidth));
        }
    }

    /** Makes the real content invisible (remembering each view's own alpha) until the reveal. */
    private void hideContent() {
        originalAlpha = new float[contentViews.size()];
        for (int i = 0; i < contentViews.size(); i++) {
            originalAlpha[i] = contentViews.get(i).getAlpha();
            contentViews.get(i).setAlpha(0f);
        }
    }

    /**
     * Springs the real content in over the skeleton: it fades up while rising a few dp, and
     * the skeleton fades out at the same rate. A spring (not a timer) so it settles naturally.
     */
    private void revealContent() {
        BottomNavHelper.logSinceHandoff("revealing the real content");   // DIAGNOSTIC
        BottomNavHelper.handoffUptimeMs = 0;                              // DIAGNOSTIC: stop the stopwatch

        if (activity.isFinishing() || activity.isDestroyed() || originalAlpha == null) {
            return;
        }
        final float lift = REVEAL_LIFT_DP * activity.getResources().getDisplayMetrics().density;

        SpringAnimation reveal = new SpringAnimation(new FloatValueHolder(0f));
        reveal.setSpring(new SpringForce(1f)
                .setStiffness(ENTER_STIFFNESS)
                .setDampingRatio(ENTER_DAMPING));
        reveal.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_ALPHA);

        reveal.addUpdateListener((animation, value, velocity) -> {
            // The spring can overshoot past 1, but alpha must stay between 0 and 1.
            float progress = Math.max(0f, Math.min(1f, value));
            for (int i = 0; i < contentViews.size(); i++) {
                View view = contentViews.get(i);
                view.setAlpha(originalAlpha[i] * progress);
                view.setTranslationY((1f - value) * lift);
            }
            if (skeleton != null) {
                skeleton.setAlpha(1f - progress);
            }
        });

        reveal.addEndListener((animation, canceled, value, velocity) -> {
            for (int i = 0; i < contentViews.size(); i++) {
                contentViews.get(i).setAlpha(originalAlpha[i]);
                contentViews.get(i).setTranslationY(0f);
            }
            removeSkeleton();
            arriving = false;
            animating = false;
        });

        reveal.start();
    }

    /** Everything inside R.id.main moves, except the bottom bar, so the pill stays put. */
    private void collectContentViews() {
        contentViews.clear();
        ViewGroup root = activity.findViewById(R.id.main);
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child.getId() != R.id.bottomNav) {
                contentViews.add(child);
            }
        }
    }
}