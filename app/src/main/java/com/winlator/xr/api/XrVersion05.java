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

import com.winlator.cmod.xserver.Keyboard;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.XKeycode;
import com.winlator.cmod.xserver.XServer;

import java.util.ArrayList;
import java.util.Locale;

public class XrVersion05 extends XrVersion04 {

    private final ArrayList<String> pendingInputs = new ArrayList<>();

    @Override
    public void consumeInputs(XServer xServer) {
        Pointer mouse = xServer.pointer;
        Keyboard keyboard = xServer.keyboard;

        ArrayList<String> inputs;
        synchronized (pendingInputs) {
            inputs = new ArrayList<>(pendingInputs);
            pendingInputs.clear();
        }

        for (String message : inputs) {
            String[] parts = message.split(",");

            if (parts.length > 1) {
                if (parts[0].equalsIgnoreCase("M")) {
                    //Eg: M,1,0,0,0,0
                    boolean leftClick = Boolean.parseBoolean(parts[1]);
                    boolean rightClick = false;
                    boolean middleClick = false;
                    boolean scrollUp = false;
                    boolean scrollDown = false;

                    if (parts.length > 2) {
                        rightClick = Boolean.parseBoolean(parts[2]);
                    }

                    if (parts.length > 3) {
                        middleClick = Boolean.parseBoolean(parts[3]);
                    }

                    if (parts.length > 4) {
                        scrollUp = Boolean.parseBoolean(parts[4]);
                    }

                    if (parts.length > 5) {
                        scrollDown = Boolean.parseBoolean(parts[5]);
                    }

                    mouse.setButton(Pointer.Button.BUTTON_LEFT, leftClick);
                    mouse.setButton(Pointer.Button.BUTTON_RIGHT, rightClick);
                    mouse.setButton(Pointer.Button.BUTTON_MIDDLE, middleClick);
                    mouse.setButton(Pointer.Button.BUTTON_SCROLL_UP, scrollUp);
                    mouse.setButton(Pointer.Button.BUTTON_SCROLL_DOWN, scrollDown);
                }
                else if (parts[0].equalsIgnoreCase("K")) {
                    //Eg: K,50,38 to press SHIFT_L and A at the same time
                    ArrayList<XKeycode> sendKeys = new ArrayList<>();

                    for (int i = 1; i < parts.length; i++) {
                        sendKeys.add(keyFromString(parts[i]));
                    }

                    for (XKeycode key : sendKeys) {
                        keyboard.setKeyPress(key.id, 0);
                    }

                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                    for (XKeycode key : sendKeys) {
                        keyboard.setKeyRelease(key.id);
                    }
                }
            }
        }
    }

    @Override
    public void dataReceived(PortIntent intent, @NonNull String message) {
        if (intent == PortIntent.HMD_STATE) {
            super.dataReceived(intent, message);
        } else if (intent == PortIntent.XSERVER_INPUT) {
            synchronized (pendingInputs) {
                pendingInputs.add(message);
            }
        }
    }

    @Override
    public String encode(@NonNull float[] axes, @NonNull boolean[] buttons, int clientIndex) {
        return super.encode(axes, buttons, clientIndex) +
                " " + String.format(Locale.US, "%.3f", axes[ControllerAxis.HMD_ALTITUDE.ordinal()]) +
                " " + String.format(Locale.US, "%.3f", axes[ControllerAxis.LG_QX.ordinal()]) +
                " " + String.format(Locale.US, "%.3f", axes[ControllerAxis.LG_QY.ordinal()]) +
                " " + String.format(Locale.US, "%.3f", axes[ControllerAxis.LG_QZ.ordinal()]) +
                " " + String.format(Locale.US, "%.3f", axes[ControllerAxis.LG_QW.ordinal()]) +
                " " + String.format(Locale.US, "%.3f", axes[ControllerAxis.RG_QX.ordinal()]) +
                " " + String.format(Locale.US, "%.3f", axes[ControllerAxis.RG_QY.ordinal()]) +
                " " + String.format(Locale.US, "%.3f", axes[ControllerAxis.RG_QZ.ordinal()]) +
                " " + String.format(Locale.US, "%.3f", axes[ControllerAxis.RG_QW.ordinal()]);
    }

    @Override
    public int getPortIn(PortIntent intent) {
        return switch (intent) {
            case HMD_STATE -> super.getPortIn(intent);
            case XSERVER_INPUT -> 7728;
        };
    }

    private XKeycode keyFromString(@NonNull String idString) {
        byte id = Byte.parseByte(idString);
        for (XKeycode key : XKeycode.values()) {
            if (key.id == id) {
                return key;
            }
        }
        return XKeycode.KEY_NONE;
    }
}
