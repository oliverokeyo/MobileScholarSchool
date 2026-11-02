package com.example.mobilescholarschool.Components;

import android.annotation.SuppressLint;
import android.graphics.drawable.Drawable;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;

import com.example.mobilescholarschool.R;

/**
 * Makes the "eye" drawable at the end of a password EditText clickable.
 * Usage:  PasswordToggle.attach(editPassword);
 */
public final class PasswordToggle {

    private PasswordToggle() { }   // static helper, never instantiated

    @SuppressLint("ClickableViewAccessibility")
    public static void attach(EditText editText) {

        // Remember the current state (false = hidden, which is how the XML starts)
        final boolean[] visible = {false};

        editText.setOnTouchListener((v, event) -> {

            // Only react when the finger LIFTS (a "click"), not on every touch event
            if (event.getAction() != MotionEvent.ACTION_UP) {
                return false;
            }

            // Index 0=start, 1=top, 2=end, 3=bottom. The eye is the "end" drawable.
            Drawable eye = editText.getCompoundDrawablesRelative()[2];
            if (eye == null) {
                return false;
            }

            // Did the finger land on the eye? (handles both left-to-right and right-to-left)
            boolean rtl = editText.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            float x = event.getX();
            int eyeWidth = eye.getBounds().width();
            boolean hitEye;
            if (rtl) {
                hitEye = x <= editText.getPaddingStart() + eyeWidth;
            } else {
                hitEye = x >= editText.getWidth() - editText.getPaddingEnd() - eyeWidth;
            }
            if (!hitEye) {
                return false;   // let the EditText handle the touch normally (typing, cursor)
            }

            visible[0] = !visible[0];   // flip the state
            apply(editText, visible[0]);

            v.performClick();           // good practice for accessibility services
            return true;                // we handled it
        });
    }

    private static void apply(EditText editText, boolean show) {
        // 1. Swap how the text is displayed (dots <-> real characters)
        editText.setTransformationMethod(show
                ? HideReturnsTransformationMethod.getInstance()
                : PasswordTransformationMethod.getInstance());

        // 2. Swap the icon, keeping the lock icon at the start as it was
        Drawable start = editText.getCompoundDrawablesRelative()[0];
        editText.setCompoundDrawablesRelativeWithIntrinsicBounds(
                start, null,
                editText.getContext().getDrawable(show ? R.drawable.ic_see_off : R.drawable.ic_see),
                null);

        // 3. Changing the transformation moves the cursor to the start; put it back at the end
        editText.setSelection(editText.getText().length());
    }
}
