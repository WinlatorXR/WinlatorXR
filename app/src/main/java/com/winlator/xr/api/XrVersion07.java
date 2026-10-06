/*
 * Copyright (C) 2024-2026 WinlatorXR
 *
 * This file is part of WinlatorXR.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.winlator.xr.api;

import android.util.Pair;

import androidx.annotation.NonNull;

import com.winlator.xr.XrActivity;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * XrAPI 0.6 with the tracking packet sent as binary instead of text: the same values in the same
 * order, as little-endian floats and ints, so neither side formats or parses numbers every frame.
 * What the Windows app sends back is unchanged.
 */
public class XrVersion07 extends XrVersion06 {

    // Has to match kBinaryMagic in the runtime's WinXrApiUDP.cpp
    private static final byte[] MAGIC = {'W', 'X', 'R', 7};
    private static final int HEADER_BYTES = MAGIC.length + 12 * 4 + 4 * 4;
    private static final int POSE_BYTES = 2 * 4 + 14 * 4;

    @Override
    public byte[] encodeBinary(@NonNull float[] axes, @NonNull boolean[] buttons, int clientIndex) {
        XrActivity instance = XrActivity.getInstance();
        synchronized (spaces) {
            ByteBuffer out = ByteBuffer.allocate(HEADER_BYTES + spaces.size() * POSE_BYTES);
            out.order(ByteOrder.LITTLE_ENDIAN);
            out.put(MAGIC);
            out.putFloat(axes[ControllerAxis.L_THUMBSTICK_X.ordinal()]);
            out.putFloat(axes[ControllerAxis.L_THUMBSTICK_Y.ordinal()]);
            out.putFloat(axes[ControllerAxis.R_THUMBSTICK_X.ordinal()]);
            out.putFloat(axes[ControllerAxis.R_THUMBSTICK_Y.ordinal()]);
            out.putFloat(axes[ControllerAxis.L_TRIGGER.ordinal()]);
            out.putFloat(axes[ControllerAxis.L_SQUEEZE.ordinal()]);
            out.putFloat(axes[ControllerAxis.R_TRIGGER.ordinal()]);
            out.putFloat(axes[ControllerAxis.R_SQUEEZE.ordinal()]);
            out.putFloat(axes[ControllerAxis.HMD_IPD.ordinal()]);
            out.putFloat(axes[ControllerAxis.HMD_FOVX.ordinal()]);
            out.putFloat(axes[ControllerAxis.HMD_FOVY.ordinal()]);
            out.putFloat(instance.getDisplayRefreshRate());
            out.putInt((int)axes[ControllerAxis.HMD_SYNC.ordinal()]);
            out.putInt((int)axes[ControllerAxis.HMD_RECENTER.ordinal()]);

            int buttonBits = 0;
            for (int i = 0; i < GUEST_BUTTON_COUNT; i++) {
                if (buttons[i]) buttonBits |= 1 << i;
            }
            out.putInt(buttonBits);

            out.putInt(spaces.size());
            for (Pair<Integer, Integer> space : spaces) {
                out.putInt(space.first);
                out.putInt(space.second);
                float[] pose = instance.getPose(space.first, space.second);
                if (pose.length != 7) pose = new float[7];
                for (float f : pose) out.putFloat(sane(f));
                float[] velocity = instance.getPoseVelocity(space.first, space.second);
                if (velocity.length != 7) velocity = new float[7];
                for (float f : velocity) out.putFloat(sane(f));
            }
            return out.array();
        }
    }

    // The text packet could not carry these either: the runtime stopped reading at the first one
    private static float sane(float value) {
        return Float.isNaN(value) || Float.isInfinite(value) ? 0 : value;
    }
}
