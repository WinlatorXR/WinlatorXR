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
package com.winlator.xr.ui;

import android.app.Activity;
import android.graphics.Color;
import android.util.Pair;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import com.winlator.cmod.R;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Keyboard;
import com.winlator.cmod.xserver.XKeycode;
import com.winlator.cmod.xserver.XServer;
import com.winlator.xr.XrActivity;
import com.winlator.xr.api.XrInterface;

import java.util.ArrayList;

public class XrKeyboard extends ContentDialog {

    private static final KeyCharacterMap chars = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
    private static final boolean[] lastButtons = new boolean[XrInterface.ControllerButton.values().length];

    private static int width, height;
    private static int x1, y1, x2, y2;
    private static boolean isShown = false;
    private static XrKeyboard keyboard;

    private boolean isCaps = true;
    private boolean isSymbols = false;

    public XrKeyboard(Activity activity) {
        super(activity, R.layout.xr_keyboard);
        findViewById(R.id.LLTitleBar).setVisibility(View.GONE);
        findViewById(R.id.BTCancel).setVisibility(View.GONE);
        findViewById(R.id.BTConfirm).setVisibility(View.GONE);
        bindKeyboard(findViewById(R.id.keyboardRoot));
        toggleCaps(findViewById(R.id.keyboardRoot));
        keyboard = this;
    }

    @Override
    public void show() {
        super.show();
        isShown = true;
    }

    @Override
    public void dismiss() {
        super.dismiss();
        isShown = false;
    }

    @Override
    public Drawable getDrawable() {
        Drawable drawable = super.getDrawable();
        width = drawable.width;
        height = drawable.height;
        int radius = 5;
        drawable.drawLine(x1 - radius, y1, x1 + radius, y1, Color.BLUE, radius);
        drawable.drawLine(x1, y1 - radius, x1, y1 + radius, Color.BLUE, radius);
        drawable.drawLine(x2 - radius, y2, x2 + radius, y2, Color.RED, radius);
        drawable.drawLine(x2, y2 - radius, x2, y2 + radius, Color.RED, radius);
        return drawable;
    }

    @Override
    public void redraw() {
        View root = findViewById(R.id.keyboardRoot);
        ArrayList<Button> keys = new ArrayList<>();
        collectButtons(root, keys);
        for (Button key : keys) {
            if (isInside(key, x1, y1) && isInside(key, x2, y2)) {
                key.setBackgroundColor(Color.rgb(128, 0, 128));
            } else if (isInside(key, x1, y1)) {
                key.setBackgroundColor(Color.rgb(128, 0, 0));
            } else if (isInside(key, x2, y2)) {
                key.setBackgroundColor(Color.rgb(0, 0, 128));
            } else {
                key.setBackgroundColor(Color.BLACK);
            }
        }
        super.redraw();
    }

    public static boolean isShown() {
        return isShown;
    }

    public static void sendKey(XKeycode key) {
        Keyboard keyboard = XrActivity.getInstance().getXServer().keyboard;
        keyboard.setKeyPress(key.id, 0);
        sleep(50);
        keyboard.setKeyRelease(key.id);
    }

    public static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void update(float[] axes, boolean[] buttons, float distance) {
        // laser raycasting
        Pair<Integer, Integer> values;
        values = calculateRaycast(0, axes, distance);
        x1 = values.first;
        y1 = values.second;
        values = calculateRaycast(1, axes, distance);
        x2 = values.first;
        y2 = values.second;

        // handle buttons
        XrActivity instance = XrActivity.getInstance();
        if (getButtonClicked(buttons, XrInterface.ControllerButton.L_MENU)) instance.runOnUiThread(() -> keyboard.dismiss());
        if (getButtonClicked(buttons, XrInterface.ControllerButton.L_THUMBSTICK_PRESS)) instance.runOnUiThread(() -> keyboard.dismiss());
        if (getButtonClicked(buttons, XrInterface.ControllerButton.R_THUMBSTICK_PRESS)) instance.runOnUiThread(() -> keyboard.dismiss());
        if (getButtonClicked(buttons, XrInterface.ControllerButton.L_TRIGGER)) instance.runOnUiThread(() -> keyboard.processClick(x1, y1));
        if (getButtonClicked(buttons, XrInterface.ControllerButton.R_TRIGGER)) instance.runOnUiThread(() -> keyboard.processClick(x2, y2));
        System.arraycopy(buttons, 0, lastButtons, 0, buttons.length);
    }

    private void applyLetters(ArrayList<Button> keys) {
        String[] letters = {
                "q","w","e","r","t","y","u","i","o","p",
                "a","s","d","f","g","h","j","k","l",
                "z","x","c","v","b","n","m",",","."
        };

        int index = 0;

        for (Button key : keys) {
            String text = key.getText().toString();

            // Skip special keys
            if (isSpecialKey(text)) continue;

            if (index < letters.length) {
                key.setText(letters[index]);
                index++;
            }
        }
    }

    private void applySymbols(ArrayList<Button> keys) {
        String[] symbols = {
                "1","2","3","4","5","6","7","8","9","0",
                "!","@","#","%","&","*","-","+","/","(",
                ")","?","\"","'",";",":","<",">"
        };

        int index = 0;

        for (Button key : keys) {
            String text = key.getText().toString();

            // Skip special keys
            if (isSpecialKey(text)) continue;

            if (index < symbols.length) {
                key.setText(symbols[index]);
                index++;
            }
        }
    }

    private void bindKeyboard(View keyboardRoot) {
        ArrayList<Button> keys = new ArrayList<>();
        collectButtons(keyboardRoot, keys);

        for (Button key : keys) {
            key.setOnClickListener(v -> {
                String text = key.getText().toString();
                switch (text) {
                    case "⌫":
                        sendKey(XKeycode.KEY_BKSP);
                        break;
                    case "Space":
                        sendKey(XKeycode.KEY_SPACE);
                        break;
                    case "Enter":
                        sendKey(XKeycode.KEY_ENTER);
                        break;
                    case "⇧":
                        toggleCaps(keyboardRoot);
                        break;
                    case "?123":
                    case "ABC":
                        toggleKeyboard(keyboardRoot);
                        break;
                    default:
                        sendChar(text.charAt(0));
                }
            });
        }
    }


    private static Pair<Integer, Integer> calculateRaycast(int controller, float[] axes, float distance) {
        // Get values
        float x = axes[controller == 0 ? XrInterface.ControllerAxis.L_X.ordinal() : XrInterface.ControllerAxis.R_X.ordinal()] - axes[XrInterface.ControllerAxis.HMD_X.ordinal()];;
        float y = axes[controller == 0 ? XrInterface.ControllerAxis.L_Y.ordinal() : XrInterface.ControllerAxis.R_Y.ordinal()] - axes[XrInterface.ControllerAxis.HMD_Y.ordinal()];;
        float yaw = axes[controller == 0 ? XrInterface.ControllerAxis.L_YAW.ordinal() : XrInterface.ControllerAxis.R_YAW.ordinal()];
        float pitch = axes[controller == 0 ? XrInterface.ControllerAxis.L_PITCH.ordinal() : XrInterface.ControllerAxis.R_PITCH.ordinal()];
        float cx = (float) width / 2;
        float cy = (float) height / 2;
        float aspect = (float) Math.pow(cx / cy, 0.15);

        // Adjust input
        pitch *= 3.0f;
        pitch = Math.max(-60, Math.min(60, pitch));
        yaw *= 3.0f / aspect;
        yaw = Math.max(-60, Math.min(60, yaw));

        //Positional mapping
        float amount = (cx + cy) / 2.0f;
        float mx = cx + x * amount / aspect;
        float my = cy - y * amount;

        //Angular mapping
        amount = distance / 4.0f * (cx + cy) / 2;
        mx -= (float) (Math.tan(Math.toRadians(yaw)) * amount);
        my += (float) (Math.tan(Math.toRadians(pitch)) * amount);

        return new Pair<>((int)mx, (int)my);
    }

    private void collectButtons(View view, ArrayList<Button> result) {
        if (view instanceof Button) {
            result.add((Button) view);
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectButtons(group.getChildAt(i), result);
            }
        }
    }

    private static boolean getButtonClicked(boolean[] buttons, XrInterface.ControllerButton button) {
        return buttons[button.ordinal()] && !lastButtons[button.ordinal()];
    }

    private boolean isInside(View view, float x, float y) {
        float left = view.getLeft() + ((View)view.getParent()).getLeft();
        float top = view.getTop() + ((View)view.getParent()).getTop();
        float right = left + view.getWidth();
        float bottom = top + view.getHeight();

        return (x >= left) && (x <= right) && (y >= top) && (y <= bottom);
    }

    private boolean isLetter(String text) {
        return text.length() == 1 && Character.isLetter(text.charAt(0));
    }

    private boolean isSpecialKey(String text) {
        return text.equals("⇧") ||
                text.equals("⌫") ||
                text.equals("Space") ||
                text.equals("Enter") ||
                text.equals("?123") ||
                text.equals("ABC");
    }

    private void processClick(int x, int y) {
        View root = findViewById(R.id.keyboardRoot);
        ArrayList<Button> keys = new ArrayList<>();
        collectButtons(root, keys);
        for (Button key : keys) {
            if (isInside(key, x, y)) {
                key.callOnClick();
            }
        }
    }

    private void sendChar(char c) {
        XServer server = XrActivity.getInstance().getXServer();
        KeyEvent[] events = chars.getEvents(new char[]{c});
        if (events != null) {
            boolean first = true;
            for (KeyEvent keyEvent : events) {
                if (!first) sleep(50);
                server.keyboard.onKeyEvent(keyEvent);
                first = false;
            }
        }
    }

    private void toggleCaps(View keyboardRoot) {
        if (isSymbols) toggleKeyboard(keyboardRoot);
        isCaps = !isCaps;

        ArrayList<Button> keys = new ArrayList<>();
        collectButtons(keyboardRoot, keys);

        for (Button key : keys) {
            String text = key.getText().toString();

            if (isLetter(text)) {
                key.setText(isCaps ? text.toUpperCase() : text.toLowerCase());
            }
        }
        updateKeyboard(keys);
    }

    private void toggleKeyboard(View keyboardRoot) {
        isSymbols = !isSymbols;
        isCaps = false;

        ArrayList<Button> keys = new ArrayList<>();
        collectButtons(keyboardRoot, keys);

        if (isSymbols) {
            applySymbols(keys);
        } else {
            applyLetters(keys);
        }
        updateKeyboard(keys);
    }

    private void updateKeyboard(ArrayList<Button> keys) {
        for (Button key : keys) {
            if (key.getText().toString().equals("?123") || key.getText().toString().equals("ABC")) {
                key.setText(isSymbols ? "ABC" : "?123");
                break;
            }
        }
    }
}
