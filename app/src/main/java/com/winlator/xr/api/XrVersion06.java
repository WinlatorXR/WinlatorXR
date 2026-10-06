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

import java.util.ArrayList;
import java.util.Locale;

public class XrVersion06 extends XrVersion05 {

    protected final ArrayList<Pair<Integer, Integer>> spaces = new ArrayList<>();

    private static final long[] POW10 = {1, 10, 100, 1000, 10000, 100000};

    @Override
    public void dataReceived(PortIntent intent, @NonNull String message) {
        if (intent != PortIntent.HMD_STATE) {
            super.dataReceived(intent, message);
            return;
        }

        // Single tokenization pass: parseAppInputValues() reads the leading AppInput
        // values into `input`, and we continue reading the remaining tokens (reference/
        // action/locate space data) from the same array instead of re-scanning the
        // message from scratch.
        String[] parts = parseAppInputValues(message);
        XrActivity instance = XrActivity.getInstance();
        int idx = input.length;
        if (idx > parts.length) return;

        // Process reference spaces
        if (idx < parts.length) {
            int count = Integer.parseInt(parts[idx++]);
            for (int i = 0; i < count; i++) {
                int space = Integer.parseInt(parts[idx++]);
                int type = Integer.parseInt(parts[idx++]);
                Pose p = parsePose(parts, idx);
                idx += 7;
                instance.updateReferenceSpace(space, type, p.x, p.y, p.z, p.qx, p.qy, p.qz, p.qw);
            }
        }

        // Process action spaces
        if (idx < parts.length) {
            int count = Integer.parseInt(parts[idx++]);
            for (int i = 0; i < count; i++) {
                int space = Integer.parseInt(parts[idx++]);
                int type = Integer.parseInt(parts[idx++]);
                int grip = Integer.parseInt(parts[idx++]);
                Pose p = parsePose(parts, idx);
                idx += 7;
                instance.updateActionSpace(space, type, grip, p.x, p.y, p.z, p.qx, p.qy, p.qz, p.qw);
            }
        }

        // Process locate spaces
        if (idx < parts.length) {
            synchronized (spaces) {
                spaces.clear();
                int count = Integer.parseInt(parts[idx++]);
                instance.clearLocateSpaces();
                for (int i = 0; i < count; i++) {
                    int a = Integer.parseInt(parts[idx++]);
                    int b = Integer.parseInt(parts[idx++]);
                    spaces.add(new Pair<>(a, b));
                    instance.addLocateSpace(a, b);
                }
            }
        }
    }

    @Override
    public String encode(@NonNull float[] axes, @NonNull boolean[] buttons, int clientIndex) {
        // One builder and no String.format: this runs every VR frame, and format made a Formatter
        // and several strings for each of the forty-odd numbers in the packet
        XrActivity instance = XrActivity.getInstance();
        StringBuilder out = new StringBuilder(512);
        out.append(MSG_CLIENT).append(clientIndex);
        appendFixed(out.append(' '), axes[ControllerAxis.L_THUMBSTICK_X.ordinal()], 2, false);
        appendFixed(out.append(' '), axes[ControllerAxis.L_THUMBSTICK_Y.ordinal()], 2, false);
        appendFixed(out.append(' '), axes[ControllerAxis.R_THUMBSTICK_X.ordinal()], 2, false);
        appendFixed(out.append(' '), axes[ControllerAxis.R_THUMBSTICK_Y.ordinal()], 2, false);
        appendFixed(out.append(' '), axes[ControllerAxis.L_TRIGGER.ordinal()], 2, false);
        appendFixed(out.append(' '), axes[ControllerAxis.L_SQUEEZE.ordinal()], 2, false);
        appendFixed(out.append(' '), axes[ControllerAxis.R_TRIGGER.ordinal()], 2, false);
        appendFixed(out.append(' '), axes[ControllerAxis.R_SQUEEZE.ordinal()], 2, false);
        appendFixed(out.append(' '), axes[ControllerAxis.HMD_IPD.ordinal()], 4, false);
        appendFixed(out.append(' '), axes[ControllerAxis.HMD_FOVX.ordinal()], 2, false);
        appendFixed(out.append(' '), axes[ControllerAxis.HMD_FOVY.ordinal()], 2, false);
        appendFixed(out.append(' '), instance.getDisplayRefreshRate(), 2, false);
        out.append(' ').append((int)axes[ControllerAxis.HMD_SYNC.ordinal()]);
        out.append(' ').append((int)axes[ControllerAxis.HMD_RECENTER.ordinal()]);

        out.append(' ');
        for (int i = 0; i < GUEST_BUTTON_COUNT; i++) {
            out.append(buttons[i] ? 'T' : 'F');
        }

        out.append(' ');
        synchronized (spaces) {
            out.append(spaces.size()).append(' ');
            for (Pair<Integer, Integer> space : spaces) {
                out.append(space.first).append(' ');
                out.append(space.second).append(' ');
                float[] pose = instance.getPose(space.first, space.second);
                if (pose.length != 7) pose = new float[7];
                for (float f : pose) {
                    // Five decimals: at three, a quaternion was rounded to about a twentieth of a degree
                    appendFixed(out, f, 5, true);
                    out.append(' ');
                }
                float[] velocity = instance.getPoseVelocity(space.first, space.second);
                if (velocity.length != 7) velocity = new float[7];
                for (float f : velocity) {
                    appendFixed(out, f, 3, true);
                    out.append(' ');
                }
            }
        }
        return out.toString();
    }

    /**
     * Appends value with a fixed number of decimals, as "%.Nf" would. With trimWhole a value that
     * rounds to a whole number is written without its decimals, which keeps the pose list short:
     * a pair that could not be located goes out as plain zeros.
     */
    static void appendFixed(StringBuilder out, float value, int decimals, boolean trimWhole) {
        if (Float.isNaN(value) || Float.isInfinite(value) || Math.abs(value) >= 1e9f) {
            out.append(String.format(Locale.US, "%." + decimals + "f", value));
            return;
        }
        long scale = POW10[decimals];
        long scaled = Math.round(Math.abs((double)value) * scale);
        if (value < 0) out.append('-');
        out.append(scaled / scale);
        long fraction = scaled % scale;
        if (trimWhole && fraction == 0) return;
        out.append('.');
        for (long digit = scale / 10; digit > 0; digit /= 10) {
            out.append((char)('0' + (fraction / digit) % 10));
        }
    }

    private Pose parsePose(String[] parts, int idx) {
        Pose output = new Pose();
        output.x = Float.parseFloat(parts[idx]);
        output.y = Float.parseFloat(parts[idx + 1]);
        output.z = Float.parseFloat(parts[idx + 2]);
        output.qx = Float.parseFloat(parts[idx + 3]);
        output.qy = Float.parseFloat(parts[idx + 4]);
        output.qz = Float.parseFloat(parts[idx + 5]);
        output.qw = Float.parseFloat(parts[idx + 6]);
        return output;
    }
}
