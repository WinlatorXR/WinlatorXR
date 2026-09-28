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
package com.winlator.xr.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
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
import java.util.HashMap;
import java.util.Map;

public class XrKeyboard extends ContentDialog {

    private static final int HAPTICS_CLICK = 50;
    private static final int HAPTICS_HOVER = 5;
    private static final int HAPTICS_INTENSITY = 5;
    private static final int POINTER_RADIUS = 16;
    // The keyboard is magnified to fill the view, so it is drawn at twice its size to stay sharp
    private static final float RENDER_SCALE = 2;
    private static final int MAX_RENDER_WIDTH = 2048;
    private static final float KEY_CORNER_RADIUS_DP = 6;
    // Hover colours match the pointer dots: left blue, right red
    private static final int KEY_COLOR = Color.rgb(52, 52, 58);
    private static final int SPECIAL_KEY_COLOR = Color.rgb(32, 32, 38);
    private static final int LEFT_HOVER_COLOR = Color.rgb(30, 80, 190);
    private static final int RIGHT_HOVER_COLOR = Color.rgb(190, 40, 40);
    private static final int BOTH_HOVER_COLOR = Color.rgb(130, 50, 150);
    private static final int LATCHED_MODIFIER_COLOR = Color.rgb(200, 130, 20);

    private static final int PAGE_LETTERS = 0;
    private static final int PAGE_SYMBOLS = 1;
    private static final int PAGE_PC = 2;
    // Label of the page toggle key on each page, naming the page it goes to next
    private static final String[] PAGE_TOGGLE_LABELS = {"?123", "PC", "ABC"};

    // Keys sent by keycode rather than as a typed character
    private static final Map<String, XKeycode> NAMED_KEYS = new HashMap<>();
    // One-shot modifiers: held down for the next key pressed, then let go
    private static final Map<String, XKeycode> MODIFIER_KEYS = new HashMap<>();
    static {
        NAMED_KEYS.put("⌫", XKeycode.KEY_BKSP);
        NAMED_KEYS.put("Space", XKeycode.KEY_SPACE);
        NAMED_KEYS.put("Enter", XKeycode.KEY_ENTER);
        NAMED_KEYS.put("F1", XKeycode.KEY_F1);
        NAMED_KEYS.put("F2", XKeycode.KEY_F2);
        NAMED_KEYS.put("F3", XKeycode.KEY_F3);
        NAMED_KEYS.put("F4", XKeycode.KEY_F4);
        NAMED_KEYS.put("F5", XKeycode.KEY_F5);
        NAMED_KEYS.put("F6", XKeycode.KEY_F6);
        NAMED_KEYS.put("F7", XKeycode.KEY_F7);
        NAMED_KEYS.put("F8", XKeycode.KEY_F8);
        NAMED_KEYS.put("F9", XKeycode.KEY_F9);
        NAMED_KEYS.put("F10", XKeycode.KEY_F10);
        NAMED_KEYS.put("F11", XKeycode.KEY_F11);
        NAMED_KEYS.put("F12", XKeycode.KEY_F12);
        NAMED_KEYS.put("Esc", XKeycode.KEY_ESC);
        NAMED_KEYS.put("Tab", XKeycode.KEY_TAB);
        NAMED_KEYS.put("PrtSc", XKeycode.KEY_PRTSCN);
        NAMED_KEYS.put("Ins", XKeycode.KEY_INSERT);
        NAMED_KEYS.put("Del", XKeycode.KEY_DEL);
        NAMED_KEYS.put("Home", XKeycode.KEY_HOME);
        NAMED_KEYS.put("End", XKeycode.KEY_END);
        NAMED_KEYS.put("PgUp", XKeycode.KEY_PRIOR);
        NAMED_KEYS.put("PgDn", XKeycode.KEY_NEXT);
        NAMED_KEYS.put("CapsLk", XKeycode.KEY_CAPS_LOCK);
        NAMED_KEYS.put("NumLk", XKeycode.KEY_NUM_LOCK);
        NAMED_KEYS.put("↑", XKeycode.KEY_UP);
        NAMED_KEYS.put("↓", XKeycode.KEY_DOWN);
        NAMED_KEYS.put("←", XKeycode.KEY_LEFT);
        NAMED_KEYS.put("→", XKeycode.KEY_RIGHT);
        MODIFIER_KEYS.put("Ctrl", XKeycode.KEY_CTRL_L);
        MODIFIER_KEYS.put("Alt", XKeycode.KEY_ALT_L);
        MODIFIER_KEYS.put("Shift", XKeycode.KEY_SHIFT_L);
    }

    private static final KeyCharacterMap chars = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
    private static final boolean[] lastButtons = new boolean[XrInterface.ControllerButton.values().length];

    private static int width, height;
    private static int x1, y1, x2, y2;
    private static boolean isClosing = false;
    private static boolean isShown = false;
    private static XrKeyboard keyboard;
    private static Drawable leftPointer, rightPointer;

    private boolean isCaps = true;
    private int page = PAGE_LETTERS;
    private final ArrayList<XKeycode> latchedModifiers = new ArrayList<>();
    private final ArrayList<Button> keys = new ArrayList<>();
    private int lastLeftKey = -1;
    private int lastRightKey = -1;
    // Redraw only when the hover highlight or the key labels change, not on a timer
    private volatile boolean needsRedraw = true;
    private volatile boolean redrawPending = false;
    // Bitmap pixels per view pixel; the pointer positions are in bitmap pixels
    private volatile float renderScale = 1;

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
        needsRedraw = true;
        latchedModifiers.clear();
    }

    @Override
    public void dismiss() {
        super.dismiss();
        isClosing = true;
    }

    @Override
    public Drawable getDrawable() {
        Drawable drawable = super.getDrawable();
        if (drawable != null) {
            width = drawable.width;
            height = drawable.height;
        }
        return drawable;
    }

    @Override
    protected boolean shouldRedraw() {
        if (!needsRedraw || redrawPending) {
            return false;
        }
        redrawPending = true;
        return true;
    }

    @Override
    public void redraw() {
        redrawPending = false;
        if (isClosing) {
            return;
        }
        View root = getContentView();
        if (root == null || root.getMeasuredWidth() == 0) {
            return;
        }
        needsRedraw = false;
        for (Button key : keys) {
            if (isInside(key, x1, y1) && isInside(key, x2, y2)) {
                setKeyColor(key, BOTH_HOVER_COLOR);
            } else if (isInside(key, x1, y1)) {
                setKeyColor(key, LEFT_HOVER_COLOR);
            } else if (isInside(key, x2, y2)) {
                setKeyColor(key, RIGHT_HOVER_COLOR);
            } else if (latchedModifiers.contains(MODIFIER_KEYS.get(key.getText().toString()))) {
                setKeyColor(key, LATCHED_MODIFIER_COLOR);
            } else {
                setKeyColor(key, isModifierKey(key.getText().toString()) ? SPECIAL_KEY_COLOR : KEY_COLOR);
            }
        }
        super.redraw();
    }

    @Override
    protected float getRenderScale() {
        int w = Math.max(1, getContentView().getMeasuredWidth());
        renderScale = Math.min(RENDER_SCALE, MAX_RENDER_WIDTH / (float) w);
        return renderScale;
    }

    private static void setKeyColor(Button key, int color) {
        GradientDrawable background = (GradientDrawable) key.getBackground();
        if (!Integer.valueOf(color).equals(key.getTag())) {
            background.setColor(color);
            key.setTag(color);
        }
    }

    public static boolean isShown() {
        return isShown;
    }

    /** The laser dot for a controller; the renderer draws it over the keyboard rather than into its texture. */
    public static Drawable getPointer(int controller) {
        if (leftPointer == null) {
            leftPointer = createPointer(Color.BLUE);
            rightPointer = createPointer(Color.RED);
        }
        return controller == 0 ? leftPointer : rightPointer;
    }

    public static int getPointerX(int controller) {
        return controller == 0 ? x1 : x2;
    }

    public static int getPointerY(int controller) {
        return controller == 0 ? y1 : y2;
    }

    private static Drawable createPointer(int color) {
        int size = POINTER_RADIUS * 2;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(POINTER_RADIUS, POINTER_RADIUS, POINTER_RADIUS, paint);
        paint.setColor(color);
        canvas.drawCircle(POINTER_RADIUS, POINTER_RADIUS, POINTER_RADIUS - 2, paint);
        return Drawable.fromBitmap(bitmap);
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
        if (isClosing) {
            if (buttons[XrInterface.ControllerButton.L_MENU.ordinal()]) return;
            if (buttons[XrInterface.ControllerButton.L_THUMBSTICK_PRESS.ordinal()]) return;
            if (buttons[XrInterface.ControllerButton.R_THUMBSTICK_PRESS.ordinal()]) return;
            isClosing = false;
            isShown = false;
            return;
        }

        // laser raycasting
        Pair<Integer, Integer> values;
        values = calculateRaycast(0, axes, distance);
        x1 = values.first;
        y1 = values.second;
        values = calculateRaycast(1, axes, distance);
        x2 = values.first;
        y2 = values.second;
        keyboard.processHaptics();

        // handle buttons
        XrActivity instance = XrActivity.getInstance();
        if (getButtonClicked(buttons, XrInterface.ControllerButton.L_MENU)) instance.runOnUiThread(() -> keyboard.dismiss());
        if (getButtonClicked(buttons, XrInterface.ControllerButton.L_THUMBSTICK_PRESS)) instance.runOnUiThread(() -> keyboard.dismiss());
        if (getButtonClicked(buttons, XrInterface.ControllerButton.R_THUMBSTICK_PRESS)) instance.runOnUiThread(() -> keyboard.dismiss());
        // XrInput leaves the face buttons to the keyboard, so the XR menu's back line is run here
        if (getButtonClicked(buttons, XrInterface.ControllerButton.R_B)) keyboard.runFaceButtonPress(ContentDialog.FaceButton.B);
        if (getButtonClicked(buttons, XrInterface.ControllerButton.L_TRIGGER)) instance.runOnUiThread(() -> keyboard.processClick(x1, y1, 0));
        if (getButtonClicked(buttons, XrInterface.ControllerButton.R_TRIGGER)) instance.runOnUiThread(() -> keyboard.processClick(x2, y2, 1));
        System.arraycopy(buttons, 0, lastButtons, 0, buttons.length);
    }

    private void applyLetters(ArrayList<Button> keys) {
        String[] letters = {
                "1","2","3","4","5","6","7","8","9","0",
                "q","w","e","r","t","y","u","i","o","p",
                "a","s","d","f","g","h","j","k","l",
                "⇧","z","x","c","v","b","n","m",",","."
        };
        applyLabels(keys, letters);
    }

    private void applySymbols(ArrayList<Button> keys) {
        String[] symbols = {
                "F1","F2","F3","F4","F5","F6","F7","F8","F9","F10",
                "`","!","@","#","$","%","&","(",")","=",
                "+","-","*","/","_","[","]","{","}",
                "|","?","\"","'",";",":","<",">",
                ",","."
        };
        applyLabels(keys, symbols);
    }

    // Laid out like a desktop keyboard: modifiers bottom left, arrows bottom right; "" hides a key
    private void applyPcKeys(ArrayList<Button> keys) {
        String[] pcKeys = {
                "Esc","F11","F12","PrtSc","Ins","Del","Home","End","PgUp","PgDn",
                "Tab","~","\\","^","`","","","","CapsLk","NumLk",
                "","","","","","","↑","","",
                "Shift","Ctrl","Alt","","","←","↓","→",
                "",""
        };
        applyLabels(keys, pcKeys);
    }

    private void applyLabels(ArrayList<Button> keys, String[] labels) {
        int index = 0;

        for (Button key : keys) {
            String text = key.getText().toString();

            // Skip special keys
            if (isSpecialKey(text)) continue;

            if (index < labels.length) {
                key.setText(labels[index]);
                key.setVisibility(labels[index].isEmpty() ? View.INVISIBLE : View.VISIBLE);
                index++;
            }
        }
    }

    private void bindKeyboard(View keyboardRoot) {
        collectButtons(keyboardRoot);

        float cornerRadius = KEY_CORNER_RADIUS_DP * keyboardRoot.getResources().getDisplayMetrics().density;
        for (Button key : keys) {
            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(cornerRadius);
            key.setBackground(background);
            setKeyColor(key, isModifierKey(key.getText().toString()) ? SPECIAL_KEY_COLOR : KEY_COLOR);
        }

        for (Button key : keys) {
            key.setOnClickListener(v -> {
                String text = key.getText().toString();
                switch (text) {
                    case "⇧":
                        toggleCaps(keyboardRoot);
                        return;
                    case "?123":
                    case "PC":
                    case "ABC":
                        toggleKeyboard(keyboardRoot);
                        return;
                }

                XKeycode modifier = MODIFIER_KEYS.get(text);
                if (modifier != null) {
                    if (!latchedModifiers.remove(modifier)) latchedModifiers.add(modifier);
                    return;
                }

                Keyboard xKeyboard = XrActivity.getInstance().getXServer().keyboard;
                for (XKeycode latched : latchedModifiers) xKeyboard.setKeyPress(latched.id, 0);
                XKeycode named = NAMED_KEYS.get(text);
                if (named != null) {
                    sendKey(named);
                } else if (!text.isEmpty()) {
                    sendChar(text.charAt(0));
                }
                for (XKeycode latched : latchedModifiers) xKeyboard.setKeyRelease(latched.id);
                latchedModifiers.clear();
            });
        }
    }


    private static Pair<Integer, Integer> calculateRaycast(int controller, float[] axes, float distance) {
        // Get values
        float x = axes[controller == 0 ? XrInterface.ControllerAxis.L_X.ordinal() : XrInterface.ControllerAxis.R_X.ordinal()] - axes[XrInterface.ControllerAxis.HMD_X.ordinal()];;
        float y = axes[controller == 0 ? XrInterface.ControllerAxis.L_Y.ordinal() : XrInterface.ControllerAxis.R_Y.ordinal()] - axes[XrInterface.ControllerAxis.HMD_Y.ordinal()];;
        float yaw = axes[controller == 0 ? XrInterface.ControllerAxis.L_YAW.ordinal() : XrInterface.ControllerAxis.R_YAW.ordinal()];
        float pitch = axes[controller == 0 ? XrInterface.ControllerAxis.L_PITCH.ordinal() : XrInterface.ControllerAxis.R_PITCH.ordinal()];
        yaw -= axes[XrInterface.ControllerAxis.MENU_YAW.ordinal()];
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

    private void collectButtons(View view) {
        if (view instanceof Button) {
            keys.add((Button) view);
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectButtons(group.getChildAt(i));
            }
        }
    }

    private static boolean getButtonClicked(boolean[] buttons, XrInterface.ControllerButton button) {
        return buttons[button.ordinal()] && !lastButtons[button.ordinal()];
    }

    private int getKeyPointed(int x, int y) {
        for (int i = 0; i < keys.size(); i++) {
            if (isInside(keys.get(i), x, y)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isInside(View view, float x, float y) {
        if (view.getVisibility() != View.VISIBLE) return false;
        x /= renderScale;
        y /= renderScale;
        float left = view.getLeft() + ((View)view.getParent()).getLeft();
        float top = view.getTop() + ((View)view.getParent()).getTop();
        float right = left + view.getWidth();
        float bottom = top + view.getHeight();

        return (x >= left) && (x <= right) && (y >= top) && (y <= bottom);
    }

    private boolean isLetter(String text) {
        return text.length() == 1 && Character.isLetter(text.charAt(0));
    }

    private boolean isModifierKey(String text) {
        return isSpecialKey(text) || text.equals("⇧");
    }

    private boolean isSpecialKey(String text) {
        return  text.equals("⌫") ||
                text.equals("Space") ||
                text.equals("Enter") ||
                text.equals("?123") ||
                text.equals("PC") ||
                text.equals("ABC");
    }

    private void processClick(int x, int y, int chan) {
        for (Button key : keys) {
            if (isInside(key, x, y)) {
                XrActivity.getInstance().vibrateController(HAPTICS_CLICK, chan, HAPTICS_INTENSITY);
                key.callOnClick();
                needsRedraw = true;
            }
        }
    }

    private void processHaptics() {
        int leftKey = getKeyPointed(x1, y1);
        if (lastLeftKey != leftKey) {
            if (leftKey >= 0) XrActivity.getInstance().vibrateController(HAPTICS_HOVER, 0, HAPTICS_INTENSITY);
            needsRedraw = true;
        }
        lastLeftKey = leftKey;

        int rightKey = getKeyPointed(x2, y2);
        if (lastRightKey != rightKey) {
            if (rightKey >= 0) XrActivity.getInstance().vibrateController(HAPTICS_HOVER, 1, HAPTICS_INTENSITY);
            needsRedraw = true;
        }
        lastRightKey = rightKey;
    }

    private void sendChar(char c) {
        XServer server = XrActivity.getInstance().getXServer();
        KeyEvent[] events = chars.getEvents(new char[]{c});
        if (events != null) {
            boolean first = true;
            for (KeyEvent keyEvent : events) {
                if (!first) sleep(50);
                server.keyboard.onKeyEvent(keyEvent, true);
                first = false;
            }
        }
    }

    private void toggleCaps(View keyboardRoot) {
        if (page != PAGE_LETTERS) setPage(PAGE_LETTERS);
        isCaps = !isCaps;

        for (Button key : keys) {
            String text = key.getText().toString();

            if (isLetter(text)) {
                key.setText(isCaps ? text.toUpperCase() : text.toLowerCase());
            }
        }
        updateKeyboard(keys);
    }

    private void toggleKeyboard(View keyboardRoot) {
        setPage((page + 1) % PAGE_TOGGLE_LABELS.length);
    }

    private void setPage(int newPage) {
        page = newPage;
        isCaps = false;

        if (page == PAGE_SYMBOLS) {
            applySymbols(keys);
        } else if (page == PAGE_PC) {
            applyPcKeys(keys);
        } else {
            applyLetters(keys);
        }
        updateKeyboard(keys);
    }

    private void updateKeyboard(ArrayList<Button> keys) {
        for (Button key : keys) {
            String text = key.getText().toString();
            if (text.equals("?123") || text.equals("PC") || text.equals("ABC")) {
                key.setText(PAGE_TOGGLE_LABELS[page]);
                break;
            }
        }
    }
}
