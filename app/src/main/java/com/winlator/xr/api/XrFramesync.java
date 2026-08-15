/*
 * Copyright (C) 2024-2026 WinlatorXR
 *
 * This file is part of WinlatorXR.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.winlator.xr.api;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.xserver.Drawable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;

public class XrFramesync {
    public interface XrFramesyncCallback {
        void setFramesync(int r, int g, int b, int a);
    }

    private static final String KEY_FRAMESYNC_MAPPING = "KEY_FRAMESYNC_MAPPING";

    private final Context context;

    private boolean aerShouldUpdate = false;
    private int aerTargetFBO = 0;

    private final ArrayList<Integer> framesyncMapping = new ArrayList<>();
    private boolean framesyncMappingHigh = false;
    private boolean framesyncMappingLow = false;
    private int lastFrameSync = 0;

    public XrFramesync(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int size = prefs.getInt(KEY_FRAMESYNC_MAPPING, 0);
        for (int i = 0; i < size; i++) {
            framesyncMapping.add(prefs.getInt(KEY_FRAMESYNC_MAPPING + i, 0));
        }
        this.context = context;
    }

    public boolean getAerShouldUpdate() {
        return aerShouldUpdate;
    }

    public int getAerTargetFBO() {
        return aerTargetFBO;
    }

    public void process(Drawable drawable, XrFramesyncCallback callback) {
        // get sync pixel
        ByteBuffer buffer = drawable.getImage((short)0, (short)0, (short)1, (short)1);
        int b = buffer.get(0) & 0xFF;
        int g = buffer.get(1) & 0xFF;
        int r = buffer.get(2) & 0xFF;
        int a = buffer.get(3) & 0xFF;

        //define framesync behavior (the same as in xr/engine.h)
        int step = 12;
        int limit = 256;
        int expectedLength = (limit / step) + 1;

        //automatically find mapping for current color space
        if (framesyncMapping.size() < expectedLength) {
            if (!framesyncMapping.contains(r)) {
                framesyncMapping.add(r);
                framesyncMapping.sort(Comparator.comparingInt(i -> i));
            }
            if (framesyncMapping.size() == expectedLength) {
                SharedPreferences.Editor e = PreferenceManager.getDefaultSharedPreferences(context).edit();
                e.putInt(KEY_FRAMESYNC_MAPPING, framesyncMapping.size());
                for (int i = 0; i < framesyncMapping.size(); i++) {
                    e.putInt(KEY_FRAMESYNC_MAPPING + i, framesyncMapping.get(i));
                }
                e.commit();
            }
            aerShouldUpdate = false;
            return;
        } else if (framesyncMapping.size() == expectedLength) {
            if (g == 0) {
                if (r < 128) framesyncMappingLow = true;
                if (r > 128) framesyncMappingHigh = true;
                if (framesyncMappingLow && framesyncMappingHigh) {
                    if (!framesyncMapping.contains(r)) {
                        framesyncMappingHigh = false;
                        framesyncMappingLow = false;
                        framesyncMapping.clear();
                        aerShouldUpdate = false;
                        return;
                    }
                    r = framesyncMapping.indexOf(r) * step;
                }
            }
        }

        // apply the values
        callback.setFramesync(r, g, b, a);
        aerShouldUpdate = lastFrameSync != r;
        aerTargetFBO = b > 0 ? 1 : 0;
        lastFrameSync = r;
    }
}
