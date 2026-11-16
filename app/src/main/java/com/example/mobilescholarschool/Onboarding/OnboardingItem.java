package com.example.mobilescholarschool.Onboarding;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

/**
 * Plain data holder for ONE onboarding page.
 * It stores resource IDs (not the text/image itself) so Android can still
 * pick the right language and screen density automatically.
 */
public class OnboardingItem {

    @DrawableRes
    private final int imageRes;
    @StringRes
    private final int titleRes;
    @StringRes
    private final int descriptionRes;

    public OnboardingItem(@DrawableRes int imageRes,
                          @StringRes int titleRes,
                          @StringRes int descriptionRes) {
        this.imageRes = imageRes;
        this.titleRes = titleRes;
        this.descriptionRes = descriptionRes;
    }

    @DrawableRes
    public int getImageRes() {
        return imageRes;
    }

    @StringRes
    public int getTitleRes() {
        return titleRes;
    }

    @StringRes
    public int getDescriptionRes() {
        return descriptionRes;
    }
}
