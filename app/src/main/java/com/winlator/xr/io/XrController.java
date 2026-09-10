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
package com.winlator.xr.io;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.contentdialog.NavigationDialog;
import com.winlator.cmod.inputcontrols.ExternalController;
import com.winlator.cmod.inputcontrols.GamepadState;
import com.winlator.cmod.winhandler.MouseEventFlags;
import com.winlator.cmod.xserver.Keyboard;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.XKeycode;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;
import com.winlator.xr.XrActivity;
import com.winlator.xr.api.XrInterface;
import com.winlator.xr.ui.XrContentDialog;
import com.winlator.xr.ui.XrControllerDialog;

public class XrController {
    public enum Mapping {
        BUTTON_A, BUTTON_B, BUTTON_X, BUTTON_Y, BUTTON_GRIP, BUTTON_TRIGGER,
        THUMBSTICK_UP, THUMBSTICK_DOWN, THUMBSTICK_LEFT, THUMBSTICK_RIGHT
    }

    private static String mapping = null;

    private final XrActivity instance;
    private boolean[] currentButtons = new boolean[XrInterface.ControllerButton.values().length];
    private final float[] lastAxes = new float[XrInterface.ControllerAxis.values().length];
    private final boolean[] lastButtons = new boolean[XrInterface.ControllerButton.values().length];
    private long lastDialogShown = 0;
    private long menuButtonPressTime = 0;
    private long primaryButtonPressTime = 0;
    private long startPulseEndTime = 0;
    private long dpadComboStartTime = 0;
    private long lastMouseUpdate = 0;
    private short lastMouseX = 0;
    private short lastMouseY = 0;
    private final float mouseSpeed;
    private final float[] smoothedMouse = new float[2];
    private final float[] relativeMouseAccumulator = new float[2];
    private boolean wasMouseRelative;

    public XrController() {
        instance = XrActivity.getInstance();
        mouseSpeed = PreferenceManager.getDefaultSharedPreferences(instance).getFloat("cursor_speed", 1.0f);
    }

    public boolean updateAndroidInput(boolean[] buttons) {
        // Get OpenXR input
        XrInterface.ControllerButton primaryTrigger = XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_TRIGGER : XrInterface.ControllerButton.R_TRIGGER;
        XrInterface.ControllerButton primaryPress = XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_THUMBSTICK_PRESS : XrInterface.ControllerButton.R_THUMBSTICK_PRESS;
        XrInterface.ControllerButton primaryUp = XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_THUMBSTICK_UP : XrInterface.ControllerButton.R_THUMBSTICK_UP;
        XrInterface.ControllerButton primaryDown = XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_THUMBSTICK_DOWN : XrInterface.ControllerButton.R_THUMBSTICK_DOWN;
        XrInterface.ControllerButton primaryLeft = XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_THUMBSTICK_LEFT : XrInterface.ControllerButton.R_THUMBSTICK_LEFT;
        XrInterface.ControllerButton primaryRight = XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_THUMBSTICK_RIGHT : XrInterface.ControllerButton.R_THUMBSTICK_RIGHT;

        XrContentDialog dialog = XrContentDialog.getFrontInstance();
        if (dialog != null) {
            primaryButtonPressTime = 0;
            if (getButtonClicked(buttons, primaryPress)) instance.runOnUiThread(dialog::onBackPressed);
            if (getButtonClicked(buttons, primaryUp)) instance.runOnUiThread(() -> dialog.onKeyAction(KeyEvent.KEYCODE_DPAD_UP));
            if (getButtonClicked(buttons, primaryDown)) instance.runOnUiThread(() -> dialog.onKeyAction(KeyEvent.KEYCODE_DPAD_DOWN));
            if (getButtonClicked(buttons, primaryLeft)) instance.runOnUiThread(() -> dialog.onKeyAction(KeyEvent.KEYCODE_DPAD_LEFT));
            if (getButtonClicked(buttons, primaryRight)) instance.runOnUiThread(() -> dialog.onKeyAction(KeyEvent.KEYCODE_DPAD_RIGHT));
            if (getButtonClicked(buttons, primaryTrigger)) instance.runOnUiThread(() -> dialog.onKeyAction(KeyEvent.KEYCODE_ENTER));
            lastDialogShown = System.currentTimeMillis();
            instance.nativeSetUseVR(false);
            return false;
        } else {
            if (System.currentTimeMillis() - lastDialogShown < 500) {
                System.arraycopy(buttons, 0, lastButtons, 0, buttons.length);
                return false;
            }

            if (buttons[primaryPress.ordinal()]) {
                if (primaryButtonPressTime == 0) primaryButtonPressTime = System.currentTimeMillis();
                boolean trigger = XrActivity.gamepadEmulation || XrActivity.getVR() ? (System.currentTimeMillis() - primaryButtonPressTime > 1000) : getButtonClicked(buttons, primaryPress);
                if (trigger) {
                    primaryButtonPressTime = System.currentTimeMillis() + 5000;
                    instance.runOnUiThread(() -> new NavigationDialog(instance).show());
                    System.arraycopy(buttons, 0, lastButtons, 0, buttons.length);
                    lastDialogShown = System.currentTimeMillis();

                    if (XrActivity.gamepadEmulation) {
                        try (XLock lock = XrActivity.getInstance().getXServer().lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                            updateGamepad(new float[XrInterface.ControllerAxis.values().length], new boolean[buttons.length]);
                        }
                    }
                    return false;
                }
            } else primaryButtonPressTime = 0;
        }

        return true;
    }

    public void updateFinished(float[] axes, boolean[] buttons) {
        System.arraycopy(axes, 0, lastAxes, 0, axes.length);
        System.arraycopy(buttons, 0, lastButtons, 0, buttons.length);
    }

    public void updateGamepad(float[] axes, boolean[] buttons) {
        GamepadState state = new GamepadState();

        if (buttons[XrInterface.ControllerButton.L_MENU.ordinal()]) {
            if (menuButtonPressTime == 0) menuButtonPressTime = System.currentTimeMillis();
        } else {
            if (menuButtonPressTime > 0) {
                if (System.currentTimeMillis() - menuButtonPressTime < 600) startPulseEndTime = System.currentTimeMillis() + 100;
                menuButtonPressTime = 0;
            }
        }
        boolean menuLongPress = menuButtonPressTime > 0 && (System.currentTimeMillis() - menuButtonPressTime) > 600;

        boolean bothGripsPressed = buttons[XrInterface.ControllerButton.L_GRIP.ordinal()] && buttons[XrInterface.ControllerButton.R_GRIP.ordinal()];
        if (bothGripsPressed) {
            if (dpadComboStartTime == 0) dpadComboStartTime = System.currentTimeMillis();
        } else dpadComboStartTime = 0;
        boolean dpadActive = dpadComboStartTime > 0 && (System.currentTimeMillis() - dpadComboStartTime) > 600;

        state.setPressed(ExternalController.IDX_BUTTON_X, buttons[XrInterface.ControllerButton.L_X.ordinal()]);
        state.setPressed(ExternalController.IDX_BUTTON_Y, buttons[XrInterface.ControllerButton.L_Y.ordinal()]);
        state.setPressed(ExternalController.IDX_BUTTON_A, buttons[XrInterface.ControllerButton.R_A.ordinal()]);
        state.setPressed(ExternalController.IDX_BUTTON_B, buttons[XrInterface.ControllerButton.R_B.ordinal()]);
        state.setPressed(ExternalController.IDX_BUTTON_L1, buttons[XrInterface.ControllerButton.L_GRIP.ordinal()] && !dpadActive);
        state.triggerL = axes[XrInterface.ControllerAxis.L_TRIGGER.ordinal()];
        state.setPressed(ExternalController.IDX_BUTTON_L2, state.triggerL > 0.5f);
        state.setPressed(ExternalController.IDX_BUTTON_L3, buttons[XrInterface.ControllerButton.L_THUMBSTICK_PRESS.ordinal()]);
        state.setPressed(ExternalController.IDX_BUTTON_R1, buttons[XrInterface.ControllerButton.R_GRIP.ordinal()] && !dpadActive);
        state.triggerR = axes[XrInterface.ControllerAxis.R_TRIGGER.ordinal()];
        state.setPressed(ExternalController.IDX_BUTTON_R2, state.triggerR > 0.5f);
        state.setPressed(ExternalController.IDX_BUTTON_R3, buttons[XrInterface.ControllerButton.R_THUMBSTICK_PRESS.ordinal()]);
        state.setPressed(ExternalController.IDX_BUTTON_SELECT, menuLongPress);
        state.setPressed(ExternalController.IDX_BUTTON_START, System.currentTimeMillis() < startPulseEndTime);

        state.dpad[0] = dpadActive && buttons[XrInterface.ControllerButton.L_THUMBSTICK_UP.ordinal()];
        state.dpad[1] = dpadActive && buttons[XrInterface.ControllerButton.L_THUMBSTICK_RIGHT.ordinal()];
        state.dpad[2] = dpadActive && buttons[XrInterface.ControllerButton.L_THUMBSTICK_DOWN.ordinal()];
        state.dpad[3] = dpadActive && buttons[XrInterface.ControllerButton.L_THUMBSTICK_LEFT.ordinal()];

        state.thumbLX = dpadActive ? 0 : axes[XrInterface.ControllerAxis.L_THUMBSTICK_X.ordinal()];
        state.thumbLY = dpadActive ? 0 : -axes[XrInterface.ControllerAxis.L_THUMBSTICK_Y.ordinal()];
        state.thumbRX = axes[XrInterface.ControllerAxis.R_THUMBSTICK_X.ordinal()];
        state.thumbRY = -axes[XrInterface.ControllerAxis.R_THUMBSTICK_Y.ordinal()];

        if (XrActivity.gamepadRadialToSquare) {
            float lenL = (float) Math.sqrt(state.thumbLX * state.thumbLX + state.thumbLY * state.thumbLY);
            if (lenL > 0) {
                float maxL = Math.max(Math.abs(state.thumbLX), Math.abs(state.thumbLY));
                state.thumbLX = Math.max(-1.0f, Math.min(state.thumbLX * (lenL / maxL), 1.0f));
                state.thumbLY = Math.max(-1.0f, Math.min(state.thumbLY * (lenL / maxL), 1.0f));
            }

            float lenR = (float) Math.sqrt(state.thumbRX * state.thumbRX + state.thumbRY * state.thumbRY);
            if (lenR > 0) {
                float maxR = Math.max(Math.abs(state.thumbRX), Math.abs(state.thumbRY));
                state.thumbRX = Math.max(-1.0f, Math.min(state.thumbRX * (lenR / maxR), 1.0f));
                state.thumbRY = Math.max(-1.0f, Math.min(state.thumbRY * (lenR / maxR), 1.0f));
            }
        }

        XrActivity.getInstance().getWinHandler().sendVirtualGamepadState(state);
    }

    public void updateKeyboardButtons(boolean[] buttons) {
        // Get OpenXR input
        XrInterface.ControllerButton secondaryGrip = !XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_GRIP : XrInterface.ControllerButton.R_GRIP;
        XrInterface.ControllerButton secondaryTrigger = !XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_TRIGGER : XrInterface.ControllerButton.R_TRIGGER;
        XrInterface.ControllerButton secondaryUp = !XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_THUMBSTICK_UP : XrInterface.ControllerButton.R_THUMBSTICK_UP;
        XrInterface.ControllerButton secondaryDown = !XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_THUMBSTICK_DOWN : XrInterface.ControllerButton.R_THUMBSTICK_DOWN;
        XrInterface.ControllerButton secondaryLeft = !XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_THUMBSTICK_LEFT : XrInterface.ControllerButton.R_THUMBSTICK_LEFT;
        XrInterface.ControllerButton secondaryRight = !XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_THUMBSTICK_RIGHT : XrInterface.ControllerButton.R_THUMBSTICK_RIGHT;

        // Pass the controller mapping into XServer
        currentButtons = buttons;
        Context context = XrActivity.getInstance();
        mapKey(XrInterface.ControllerButton.L_MENU, XKeycode.KEY_ESC.id);
        mapKey(XrInterface.ControllerButton.R_A, getMapping(context, Mapping.BUTTON_A));
        mapKey(XrInterface.ControllerButton.R_B, getMapping(context, Mapping.BUTTON_B));
        mapKey(XrInterface.ControllerButton.L_X, getMapping(context, Mapping.BUTTON_X));
        mapKey(XrInterface.ControllerButton.L_Y, getMapping(context, Mapping.BUTTON_Y));
        mapKey(secondaryGrip, getMapping(context, Mapping.BUTTON_GRIP));
        mapKey(secondaryTrigger, getMapping(context, Mapping.BUTTON_TRIGGER));
        mapKey(secondaryUp, getMapping(context, Mapping.THUMBSTICK_UP));
        mapKey(secondaryDown, getMapping(context, Mapping.THUMBSTICK_DOWN));
        mapKey(secondaryLeft, getMapping(context, Mapping.THUMBSTICK_LEFT));
        mapKey(secondaryRight, getMapping(context, Mapping.THUMBSTICK_RIGHT));
    }

    /**
     * Releases every key the controller mapping is able to press.
     *
     * Key mapping is edge-triggered (see {@link #mapKey}), but while the XR menu is up the XServer
     * update is skipped entirely and lastButtons keeps following the physical controller, so the
     * release edge of anything held at summon time is consumed with nothing listening and the key
     * stays down in the container for good. Gamepad emulation has no such problem because it pushes
     * an absolute state every frame and cuts input by pushing a neutral one; keys need the releases
     * sent explicitly. Keyboard.setKeyRelease ignores keys that are not currently pressed, so this
     * is safe to call unconditionally.
     */
    public void releaseKeyboardButtons() {
        Context context = XrActivity.getInstance();
        Keyboard keyboard = instance.getXServer().keyboard;
        keyboard.setKeyRelease(XKeycode.KEY_ESC.id);
        for (Mapping input : Mapping.values()) {
            keyboard.setKeyRelease(getMapping(context, input));
        }
    }

    public void updateMouseAxes(float[] axes, boolean headMapping) {
        // Get OpenXR input
        XrInterface.ControllerAxis mouseAxisX = XrActivity.mouseLeftHanded ? XrInterface.ControllerAxis.L_X : XrInterface.ControllerAxis.R_X;
        XrInterface.ControllerAxis mouseAxisY = XrActivity.mouseLeftHanded ? XrInterface.ControllerAxis.L_Y : XrInterface.ControllerAxis.R_Y;

        // For relative mouse get actual position from the XServer
        Pointer mouse = instance.getXServer().pointer;
        if (XrActivity.mouseRelative && (mouse.getX() != lastMouseX || mouse.getY() != lastMouseY)) {
            smoothedMouse[0] = mouse.getX() + 0.5f;
            smoothedMouse[1] = mouse.getY() + 0.5f;
            lastMouseX = mouse.getX();
            lastMouseY = mouse.getY();
        }

        // Mouse control with hand
        float f = 0.75f;
        float startMouseX = smoothedMouse[0];
        float startMouseY = smoothedMouse[1];
        float meter2px = instance.getXServer().screenInfo.width * 10.0f;
        float dx = (axes[mouseAxisX.ordinal()] - lastAxes[mouseAxisX.ordinal()]) * meter2px;
        float dy = (axes[mouseAxisY.ordinal()] - lastAxes[mouseAxisY.ordinal()]) * meter2px;
        if ((Math.abs(dx) > 300) || (Math.abs(dy) > 300)) {
            dx = 0;
            dy = 0;
        }

        // Mouse control with head
        if (headMapping) {
            float angle2px = instance.getXServer().screenInfo.width * 0.05f / f;
            dx = getAngleDiff(lastAxes[XrInterface.ControllerAxis.HMD_YAW.ordinal()], axes[XrInterface.ControllerAxis.HMD_YAW.ordinal()]) * angle2px;
            dy = getAngleDiff(lastAxes[XrInterface.ControllerAxis.HMD_PITCH.ordinal()], axes[XrInterface.ControllerAxis.HMD_PITCH.ordinal()]) * angle2px;
            if (Float.isNaN(dy)) {
                dy = 0;
            }
            smoothedMouse[0] = mouse.getClampedX() + 0.5f;
            smoothedMouse[1] = mouse.getClampedY() + 0.5f;
        }

        // Mouse smoothing
        dx *= mouseSpeed;
        dy *= mouseSpeed;
        smoothedMouse[0] = smoothedMouse[0] * f + (mouse.getClampedX() + 0.5f + dx) * (1 - f);
        smoothedMouse[1] = smoothedMouse[1] * f + (mouse.getClampedY() + 0.5f - dy) * (1 - f);
        relativeMouseAccumulator[0] += (smoothedMouse[0] - startMouseX);
        relativeMouseAccumulator[1] += (smoothedMouse[1] - startMouseY);
    }

    public void updateMouseLightgun(float[] axes, float distance) {
        // Get values
        float x = axes[XrActivity.mouseLeftHanded ? XrInterface.ControllerAxis.L_X.ordinal() : XrInterface.ControllerAxis.R_X.ordinal()] - axes[XrInterface.ControllerAxis.HMD_X.ordinal()];;
        float y = axes[XrActivity.mouseLeftHanded ? XrInterface.ControllerAxis.L_Y.ordinal() : XrInterface.ControllerAxis.R_Y.ordinal()] - axes[XrInterface.ControllerAxis.HMD_Y.ordinal()];;
        float yaw = axes[XrActivity.mouseLeftHanded ? XrInterface.ControllerAxis.L_YAW.ordinal() : XrInterface.ControllerAxis.R_YAW.ordinal()];
        float pitch = axes[XrActivity.mouseLeftHanded ? XrInterface.ControllerAxis.L_PITCH.ordinal() : XrInterface.ControllerAxis.R_PITCH.ordinal()];
        yaw -= axes[XrInterface.ControllerAxis.MENU_YAW.ordinal()];
        float cx = (float) instance.getXServer().windowManager.rootWindow.getWidth() / 2;
        float cy = (float) instance.getXServer().windowManager.rootWindow.getHeight() / 2;
        float aspect = (float) Math.pow(cx / cy, 0.15);

        //Positional mapping
        float amount = (cx + cy) / 2.0f;
        smoothedMouse[0] = cx + x * amount / aspect;
        smoothedMouse[1] = cy - y * amount;

        //Angular mapping
        amount = distance / 4.0f * (cx + cy) / 2;
        smoothedMouse[0] -= (float) (Math.tan(Math.toRadians(yaw) / aspect) * amount);
        smoothedMouse[1] += (float) (Math.tan(Math.toRadians(pitch)) * amount);
    }

    public void updateMouseSnapturn(boolean[] buttons, int step) {
        // Get OpenXR input
        XrInterface.ControllerButton primaryLeft = XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_THUMBSTICK_LEFT : XrInterface.ControllerButton.R_THUMBSTICK_LEFT;
        XrInterface.ControllerButton primaryRight = XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_THUMBSTICK_RIGHT : XrInterface.ControllerButton.R_THUMBSTICK_RIGHT;

        // Apply snapturn to the input
        if (getButtonClicked(buttons, primaryLeft)) {
            relativeMouseAccumulator[0] -= step;
            smoothedMouse[0] -= step;
        }
        if (getButtonClicked(buttons, primaryRight)) {
            relativeMouseAccumulator[0] += step;
            smoothedMouse[0] += step;
        }
    }

    public void updateMouseState(boolean[] buttons) {
        // Get OpenXR input
        Pointer mouse = instance.getXServer().pointer;
        XrInterface.ControllerButton primaryGrip = XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_GRIP : XrInterface.ControllerButton.R_GRIP;
        XrInterface.ControllerButton primaryTrigger = XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_TRIGGER : XrInterface.ControllerButton.R_TRIGGER;
        XrInterface.ControllerButton primaryUp = XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_THUMBSTICK_UP : XrInterface.ControllerButton.R_THUMBSTICK_UP;
        XrInterface.ControllerButton primaryDown = XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_THUMBSTICK_DOWN : XrInterface.ControllerButton.R_THUMBSTICK_DOWN;

        // Apply buttons
        currentButtons = buttons;
        mapButton(primaryTrigger, Pointer.Button.BUTTON_LEFT);
        mapButton(primaryGrip, Pointer.Button.BUTTON_RIGHT);
        mapButton(primaryUp, Pointer.Button.BUTTON_SCROLL_UP);
        mapButton(primaryDown, Pointer.Button.BUTTON_SCROLL_DOWN);

        // Apply cursor position
        if (XrActivity.mouseRelative) {
            int dx = (int) relativeMouseAccumulator[0];
            int dy = (int) relativeMouseAccumulator[1];
            if (dx != 0 || dy != 0) {
                if (wasMouseRelative) {
                    instance.getWinHandler().mouseEvent(MouseEventFlags.MOVE, dx, dy, 0);
                }
                relativeMouseAccumulator[0] -= dx;
                relativeMouseAccumulator[1] -= dy;
            }
        } else {
            // Fast visual update
            mouse.setX((int) smoothedMouse[0]);
            mouse.setY((int) smoothedMouse[1]);

            // Limit cursor updates to the FPS (this prevents freezing)
            long timestamp = System.currentTimeMillis();
            if (timestamp - lastMouseUpdate > 1000 / Math.max(instance.getLastRedraws(), 1)) {
                if ((lastMouseX != mouse.getX()) || (lastMouseY != mouse.getY())) {
                    lastMouseUpdate = timestamp;
                    lastMouseX = mouse.getX();
                    lastMouseY = mouse.getY();
                    mouse.triggerOnPointerMove(lastMouseX, lastMouseY);
                }
            }
        }
        wasMouseRelative = XrActivity.mouseRelative;
    }

    public void updateWheelEmulation(float[] axes) {
        // Detect the controllers are in a pose where wheel makes sense
        float dx = axes[XrInterface.ControllerAxis.R_X.ordinal()] - axes[XrInterface.ControllerAxis.L_X.ordinal()];
        float dy = axes[XrInterface.ControllerAxis.R_Y.ordinal()] - axes[XrInterface.ControllerAxis.L_Y.ordinal()];
        float dz = axes[XrInterface.ControllerAxis.R_Z.ordinal()] - axes[XrInterface.ControllerAxis.L_Z.ordinal()];
        float size = (float) Math.sqrt(dx * dx + dy * dy);
        if ((Math.abs(dz) > 0.15) || (size > 0.5f)) {
            return;
        }

        // Get value from primary thumbstick
        XrInterface.ControllerAxis primaryThumbstick = XrActivity.mouseLeftHanded ? XrInterface.ControllerAxis.L_THUMBSTICK_Y : XrInterface.ControllerAxis.R_THUMBSTICK_Y;
        float thumbstick = axes[primaryThumbstick.ordinal()];

        // Send values through gyro system
        XrActivity.getInstance().getWinHandler().updateGyroData(-dy * 4.0f, thumbstick * 2.0f);
    }

    public void updateXrCamera(boolean[] buttons) {
        XrInterface.ControllerButton primaryUp = XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_THUMBSTICK_UP : XrInterface.ControllerButton.R_THUMBSTICK_UP;
        XrInterface.ControllerButton primaryDown = XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_THUMBSTICK_DOWN : XrInterface.ControllerButton.R_THUMBSTICK_DOWN;
        XrInterface.ControllerButton secondaryUp = !XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_THUMBSTICK_UP : XrInterface.ControllerButton.R_THUMBSTICK_UP;
        XrInterface.ControllerButton secondaryDown = !XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_THUMBSTICK_DOWN : XrInterface.ControllerButton.R_THUMBSTICK_DOWN;
        XrInterface.ControllerButton secondaryLeft = !XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_THUMBSTICK_LEFT : XrInterface.ControllerButton.R_THUMBSTICK_LEFT;
        XrInterface.ControllerButton secondaryRight = !XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_THUMBSTICK_RIGHT : XrInterface.ControllerButton.R_THUMBSTICK_RIGHT;

        float x = 0; float y = 0; float z = 0; float step = 0.025f;
        if (XrActivity.adjustCamera) {
            if (buttons[primaryUp.ordinal()]) y += step;
            if (buttons[primaryDown.ordinal()]) y -= step;
            if (buttons[secondaryUp.ordinal()]) z -= step;
            if (buttons[secondaryDown.ordinal()]) z += step;
            if (buttons[secondaryLeft.ordinal()]) x -= step;
            if (buttons[secondaryRight.ordinal()]) x += step;
        }
        if ((Math.abs(x) > 0) || (Math.abs(y) > 0) || (Math.abs(z) > 0)) {
            XrActivity.getInstance().increaseReferenceSpacesOffset(x, y, z);
        }
    }

    public boolean getButtonClicked(boolean[] buttons, XrInterface.ControllerButton button) {
        return buttons[button.ordinal()] && !lastButtons[button.ordinal()];
    }

    public static void cleanMappingCache() {
        mapping = null;
    }

    public static byte getMapping(Context context, Mapping input) {
        if (mapping == null) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            int index = prefs.getInt(XrControllerDialog.XR_CONTROLLER_PROFILE_INDEX, 0);
            String key = XrControllerDialog.XR_CONTROLLER_PROFILE_VALUE + index;
            mapping = prefs.getString(key, getDefaultMapping());
        }
        return (byte) mapping.charAt(input.ordinal());
    }

    public static void setMapping(Context context, String name, String value) {
        mapping = value;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int index = prefs.getInt(XrControllerDialog.XR_CONTROLLER_PROFILE_INDEX, 0);
        SharedPreferences.Editor e = prefs.edit();
        e.putString(XrControllerDialog.XR_CONTROLLER_PROFILE_NAME + index, name);
        e.putString(XrControllerDialog.XR_CONTROLLER_PROFILE_VALUE + index, value);
        e.apply();
    }

    public static String getDefaultMapping() {
        //The order has to be the same as in Mapping enum
        String output = "";
        output += (char)XKeycode.KEY_A.id;
        output += (char)XKeycode.KEY_B.id;
        output += (char)XKeycode.KEY_X.id;
        output += (char)XKeycode.KEY_Y.id;
        output += (char)XKeycode.KEY_SPACE.id;
        output += (char)XKeycode.KEY_ENTER.id;
        output += (char)XKeycode.KEY_UP.id;
        output += (char)XKeycode.KEY_DOWN.id;
        output += (char)XKeycode.KEY_LEFT.id;
        output += (char)XKeycode.KEY_RIGHT.id;
        return output;
    }

    private float getAngleDiff(float oldAngle, float newAngle) {
        float diff = oldAngle - newAngle;
        while (diff > 180) {
            diff -= 360;
        }
        while (diff < -180) {
            diff += 360;
        }
        return diff;
    }

    private void mapButton(XrInterface.ControllerButton xrButton, Pointer.Button button) {
        Pointer mouse = instance.getXServer().pointer;
        if (currentButtons[xrButton.ordinal()] != lastButtons[xrButton.ordinal()]) {
            mouse.setButton(button, currentButtons[xrButton.ordinal()]);
        }
    }

    private void mapKey(XrInterface.ControllerButton xrButton, byte xKeycode) {
        Keyboard keyboard = instance.getXServer().keyboard;
        if (currentButtons[xrButton.ordinal()] != lastButtons[xrButton.ordinal()]) {
            if (currentButtons[xrButton.ordinal()]) {
                keyboard.setKeyPress(xKeycode, 0);
            } else {
                keyboard.setKeyRelease(xKeycode);
            }
        }
    }
}
