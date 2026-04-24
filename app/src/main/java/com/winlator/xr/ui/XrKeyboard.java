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
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import com.winlator.cmod.R;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.xserver.Keyboard;
import com.winlator.cmod.xserver.XKeycode;
import com.winlator.cmod.xserver.XServer;
import com.winlator.xr.XrActivity;

import java.util.ArrayList;

public class XrKeyboard extends ContentDialog {

    private static final KeyCharacterMap chars = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);

    private boolean isCaps = true;
    private boolean isSymbols = false;

    public XrKeyboard(Activity activity) {
        super(activity, R.layout.xr_keyboard);
        findViewById(R.id.LLTitleBar).setVisibility(View.GONE);
        findViewById(R.id.BTCancel).setVisibility(View.GONE);
        findViewById(R.id.BTConfirm).setVisibility(View.GONE);
        bindKeyboard(findViewById(R.id.keyboardRoot));
        toggleCaps(findViewById(R.id.keyboardRoot));
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

    private void applyLetters(ArrayList<Button> keys) {
        String[] letters = {
                "q","w","e","r","t","y","u","i","o","p",
                "a","s","d","f","g","h","j","k","l",
                "z","x","c","v","b","n","m"
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
                "@","#","$","%","&","*","-","+","(",
                ")","!","\"","'",";",":","/"
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

    private boolean isLetter(String text) {
        return text.length() == 1 && Character.isLetter(text.charAt(0));
    }

    private boolean isSpecialKey(String text) {
        return text.equals("⇧") ||
                text.equals("⌫") ||
                text.equals("Space") ||
                text.equals("Enter") ||
                text.equals("?123") ||
                text.equals("ABC") ||
                text.equals(",") ||
                text.equals(".");
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
