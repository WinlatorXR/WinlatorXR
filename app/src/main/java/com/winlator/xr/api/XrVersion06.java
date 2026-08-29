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

    private final ArrayList<Pair<Integer, Integer>> spaces = new ArrayList<>();

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
        StringBuilder binary = new StringBuilder();
        for (boolean button : buttons) {
            binary.append(button ? "T" : "F");
        }

        XrActivity instance = XrActivity.getInstance();
        StringBuilder poses = new StringBuilder();
        synchronized (spaces) {
            poses.append(spaces.size()).append(" ");
            for (Pair<Integer, Integer> space : spaces) {
                poses.append(space.first).append(" ");
                poses.append(space.second).append(" ");
                float[] pose = instance.getPose(space.first, space.second);
                if (pose.length != 7) pose = new float[7];
                for (float f : pose) {
                    String str = String.format(Locale.US, "%.3f", f);
                    str = str.replaceAll("\\.000", "");
                    poses.append(str).append(" ");
                }
            }
        }
        return (MSG_CLIENT + clientIndex +
                " " + String.format(Locale.US, "%.1f", axes[ControllerAxis.L_THUMBSTICK_X.ordinal()]) +
                " " + String.format(Locale.US, "%.1f", axes[ControllerAxis.L_THUMBSTICK_Y.ordinal()]) +
                " " + String.format(Locale.US, "%.1f", axes[ControllerAxis.R_THUMBSTICK_X.ordinal()]) +
                " " + String.format(Locale.US, "%.1f", axes[ControllerAxis.R_THUMBSTICK_Y.ordinal()]) +
                " " + String.format(Locale.US, "%.4f", axes[ControllerAxis.HMD_IPD.ordinal()]) +
                " " + String.format(Locale.US, "%.2f", axes[ControllerAxis.HMD_FOVX.ordinal()]) +
                " " + String.format(Locale.US, "%.2f", axes[ControllerAxis.HMD_FOVY.ordinal()]) +
                " " + String.format(Locale.US, "%d", (int)axes[ControllerAxis.HMD_SYNC.ordinal()]) +
                " " + String.format(Locale.US, "%d", (int)axes[ControllerAxis.HMD_RECENTER.ordinal()]) +
                " " + binary + " " + poses);
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
