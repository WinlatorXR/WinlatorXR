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

import androidx.annotation.NonNull;

public class XrVersion02 extends XrVersion01 {

    public XrVersion02() {
        super(null);
    }

    @Override
    public void dataReceived(PortIntent intent, @NonNull String message) {
        if (intent == PortIntent.HMD_STATE) {
            parseAppInputValues(message);
        }
    }

    /**
     * Tokenizes an HMD_STATE message once and fills {@link #input} from its leading
     * AppInput values. Never throws (a malformed value is skipped, not fatal) so the
     * returned token array is always safe to use. Subclasses that need the remainder
     * of the message (e.g. trailing reference/action/locate space data) can continue
     * parsing from {@link #input}.length onward without re-tokenizing the message
     * themselves.
     */
    protected String[] parseAppInputValues(String message) {
        String[] parts = message.split("\\s+");
        int limit = Math.min(input.length, parts.length);
        for (int i = 0; i < limit; i++) {
            try {
                float value = Float.parseFloat(parts[i]);
                if ((value > 0) || (i >= 2)) {
                    input[i] = value;
                }
            } catch (NumberFormatException ignored) {}
        }
        return parts;
    }

    @Override
    public float getValue(@NonNull AppInput index) {
        return input[index.ordinal()];
    }
}
