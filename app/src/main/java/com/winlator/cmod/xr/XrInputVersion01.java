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
package com.winlator.cmod.xr;

import androidx.annotation.NonNull;

import com.winlator.XrActivity;
import com.winlator.cmod.xserver.Keyboard;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.XKeycode;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class XrInputVersion01 implements XrInputInterface {

    private final XrActivity instance;
    private List<String> pendingInputs = new ArrayList<>();

    private final File dir;

    public XrInputVersion01(File dir) {
        this.dir = dir;
        instance = XrActivity.getInstance();
    }

    @Override
    public void dataReceived(@NonNull String message) {
        try {
              pendingInputs.add(message);
        } catch (Exception e) {
            System.err.println("Error receiving data: " + e.getMessage());
        }
    }

    @Override
    public void consumeInputs() {
        Pointer mouse = instance.getXServer().pointer;
        Keyboard keyboard = instance.getXServer().keyboard;

        for (String message : pendingInputs) {
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
                    List<XKeycode> sendKeys = new ArrayList<>();

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

        pendingInputs.clear();
    }

    @Override
    public XKeycode keyFromString(@NonNull String idString) {
        byte id = Byte.parseByte(idString);

        for (XKeycode key : XKeycode.values()) {
            if (key.id == id) {
                return key;
            }
        }

        return XKeycode.KEY_NONE;
    }

    public int getPortIn() {
        return 7728;
    }

    public int[] getPortsOut() {
        return new int[]{7287};
    }
}
