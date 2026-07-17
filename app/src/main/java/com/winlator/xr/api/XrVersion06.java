package com.winlator.xr.api;

import androidx.annotation.NonNull;

import java.util.Locale;

public class XrVersion06 extends XrVersion05 {

    @Override
    public String encode(@NonNull float[] axes, @NonNull boolean[] buttons, int clientIndex) {
        return super.encode(axes, buttons, clientIndex) +
                " " + String.format(Locale.US, "%.3f", axes[ControllerAxis.HMD_STAGE_QX.ordinal()]) +
                " " + String.format(Locale.US, "%.3f", axes[ControllerAxis.HMD_STAGE_QY.ordinal()]) +
                " " + String.format(Locale.US, "%.3f", axes[ControllerAxis.HMD_STAGE_QZ.ordinal()]) +
                " " + String.format(Locale.US, "%.3f", axes[ControllerAxis.HMD_STAGE_QW.ordinal()]) +
                " " + String.format(Locale.US, "%.3f", axes[ControllerAxis.HMD_STAGE_X.ordinal()]) +
                " " + String.format(Locale.US, "%.3f", axes[ControllerAxis.HMD_STAGE_Y.ordinal()]) +
                " " + String.format(Locale.US, "%.3f", axes[ControllerAxis.HMD_STAGE_Z.ordinal()]);
    }
}
