/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.keyguard;

import android.view.View;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.android.internal.util.LatencyTracker;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.systemui.R;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.Dependency;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.statusbar.policy.DevicePostureController;
import com.android.systemui.tuner.TunerService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class KeyguardPinViewController
        extends KeyguardPinBasedInputViewController<KeyguardPINView> implements TunerService.Tunable {
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final DevicePostureController mPostureController;
    private final DevicePostureController.Callback mPostureCallback = posture ->
            mView.onDevicePostureChanged(posture);
    private LockPatternUtils mLockPatternUtils;
    private final FeatureFlags mFeatureFlags;
    private static final int DEFAULT_PIN_LENGTH = 6;
    private static final int MIN_FAILED_PIN_ATTEMPTS = 5;
    private NumPadButton mBackspaceKey;
    private View mOkButton = mView.findViewById(R.id.key_enter);

    private long mPinLength;

    private boolean mDisabledAutoConfirmation;

    private final String LOCKSCREEN_PIN_SCRAMBLE_LAYOUT = "system:lockscreen_pin_scramble_layout";

    private boolean mScramblePin;
    private List<Integer> mNumbers = new ArrayList<>(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 0));
    private final List<Integer> mDefaultNumbers = new ArrayList<>(mNumbers);

    protected KeyguardPinViewController(KeyguardPINView view,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            SecurityMode securityMode, LockPatternUtils lockPatternUtils,
            KeyguardSecurityCallback keyguardSecurityCallback,
            KeyguardMessageAreaController.Factory messageAreaControllerFactory,
            LatencyTracker latencyTracker, LiftToActivateListener liftToActivateListener,
            EmergencyButtonController emergencyButtonController,
            FalsingCollector falsingCollector,
            DevicePostureController postureController,
            FeatureFlags featureFlags) {
        super(view, keyguardUpdateMonitor, securityMode, lockPatternUtils, keyguardSecurityCallback,
                messageAreaControllerFactory, latencyTracker, liftToActivateListener,
                emergencyButtonController, falsingCollector, featureFlags);
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mPostureController = postureController;
        mLockPatternUtils = lockPatternUtils;
        mFeatureFlags = featureFlags;
        mBackspaceKey = view.findViewById(R.id.delete_button);
        mPinLength = mLockPatternUtils.getPinLength(KeyguardUpdateMonitor.getCurrentUser());
    }

    @Override
    protected void onViewAttached() {
        super.onViewAttached();

        View cancelBtn = mView.findViewById(R.id.cancel_button);
        if (cancelBtn != null) {
            cancelBtn.setOnClickListener(view -> {
                getKeyguardSecurityCallback().reset();
                getKeyguardSecurityCallback().onCancelClicked();
            });
        }
        mPasswordEntry.setUserActivityListener(this::onUserInput);
        mView.onDevicePostureChanged(mPostureController.getDevicePosture());
        mPostureController.addCallback(mPostureCallback);
        if (mFeatureFlags.isEnabled(Flags.AUTO_PIN_CONFIRMATION)) {
            mPasswordEntry.setUsePinShapes(true);
            updateAutoConfirmationState();
        }
        Dependency.get(TunerService.class).addTunable(this, LOCKSCREEN_PIN_SCRAMBLE_LAYOUT);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case LOCKSCREEN_PIN_SCRAMBLE_LAYOUT:
                mScramblePin = TunerService.parseIntegerSwitch(newValue, false);
                break;
            default:
                break;
        }
    }

    private void updatePinScrambling() {
        if (mScramblePin) {
            Collections.shuffle(mNumbers);
        } else {
            mNumbers.clear();
            mNumbers.addAll(mDefaultNumbers);
        }

        // get all children who are NumPadKey's
        ConstraintLayout container = mView.findViewById(R.id.pin_container);

        List<NumPadKey> views = new ArrayList<>();
        for (int i = 0; i < container.getChildCount(); i++) {
            View view = container.getChildAt(i);
            if (view instanceof NumPadKey) {
                views.add((NumPadKey) view);
            }
        }

        // reset the digits in the views
        int i = 0;
        for (NumPadKey view : views) {
            view.setDigit(mNumbers.get(i++));
        }
    }

    protected void onUserInput() {
        super.onUserInput();
        if (isAutoPinConfirmEnabledInSettings()) {
            updateAutoConfirmationState();
            if (mPasswordEntry.getText().length() == mPinLength
                    && mOkButton.getVisibility() == View.INVISIBLE) {
                verifyPasswordAndUnlock();
            }
        }
    }

    @Override
    protected void onViewDetached() {
        super.onViewDetached();
        mPostureController.removeCallback(mPostureCallback);
    }

    @Override
    public void startAppearAnimation() {
        updatePinScrambling();
        super.startAppearAnimation();
    }

    @Override
    public boolean startDisappearAnimation(Runnable finishRunnable) {
        return mView.startDisappearAnimation(
                mKeyguardUpdateMonitor.needsSlowUnlockTransition(), finishRunnable);
    }

    @Override
    protected void handleAttemptLockout(long elapsedRealtimeDeadline) {
        super.handleAttemptLockout(elapsedRealtimeDeadline);
        updateAutoConfirmationState();
    }

    private void updateAutoConfirmationState() {
        mDisabledAutoConfirmation = mLockPatternUtils.getCurrentFailedPasswordAttempts(
                KeyguardUpdateMonitor.getCurrentUser()) >= MIN_FAILED_PIN_ATTEMPTS;
        updateOKButtonVisibility();
        updateBackSpaceVisibility();
        updatePinHinting();
    }

    /**
     * Updates the visibility of the OK button for auto confirm feature
     */
    private void updateOKButtonVisibility() {
        if (isAutoPinConfirmEnabledInSettings() && !mDisabledAutoConfirmation) {
            mOkButton.setVisibility(View.INVISIBLE);
        } else {
            mOkButton.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Updates the visibility and the enabled state of the backspace.
     * Visibility changes are only for auto confirmation configuration.
     */
    private void updateBackSpaceVisibility() {
        boolean isAutoConfirmation = isAutoPinConfirmEnabledInSettings();
        mBackspaceKey.setTransparentMode(/* isTransparentMode= */
                isAutoConfirmation && !mDisabledAutoConfirmation);
        if (isAutoConfirmation) {
            if (mPasswordEntry.getText().length() > 0
                    || mDisabledAutoConfirmation) {
                mBackspaceKey.setVisibility(View.VISIBLE);
            } else {
                mBackspaceKey.setVisibility(View.INVISIBLE);
            }
        }
    }
    /** Updates whether to use pin hinting or not. */
    void updatePinHinting() {
        mPasswordEntry.setIsPinHinting(isAutoPinConfirmEnabledInSettings() && isPinHinting()
                && !mDisabledAutoConfirmation);
    }

    /**
     * Responsible for identifying if PIN hinting is to be enabled or not
     */
    private boolean isPinHinting() {
        return mPinLength == DEFAULT_PIN_LENGTH;
    }

    /**
     * Responsible for identifying if auto confirm is enabled or not in Settings and
     * a valid PIN_LENGTH is stored on the device (though the latter check is only to make it more
     * robust since we only allow enabling PIN confirmation if the user has a valid PIN length
     * saved on device)
     */
    private boolean isAutoPinConfirmEnabledInSettings() {
        //Checks if user has enabled the auto confirm in Settings
        return mLockPatternUtils.isAutoPinConfirmEnabled(KeyguardUpdateMonitor.getCurrentUser())
                && mPinLength != LockPatternUtils.PIN_LENGTH_UNAVAILABLE;
    }
}
