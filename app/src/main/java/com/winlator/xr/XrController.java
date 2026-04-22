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
package com.winlator.xr;

import android.content.Intent;
import android.util.Pair;
import android.view.KeyEvent;

import androidx.preference.PreferenceManager;

import com.drbeef.externalhapticsservice.HapticsConstants;
import com.drbeef.externalhapticsservice.HapticServiceClient;

import com.winlator.XrActivity;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.contentdialog.NavigationDialog;
import com.winlator.cmod.xserver.Keyboard;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.XKeycode;
import com.winlator.xr.api.XrAPI;
import com.winlator.xr.api.XrInterface;
import com.winlator.xr.ui.XrContentDialog;

import java.util.Vector;

public class XrController {

    private final XrActivity instance;
    private boolean[] currentButtons = new boolean[XrInterface.ControllerButton.values().length];
    private final float[] lastAxes = new float[XrInterface.ControllerAxis.values().length];
    private final boolean[] lastButtons = new boolean[XrInterface.ControllerButton.values().length];
    private long lastDialogShown = 0;
    private long lastMouseUpdate = 0;
    private short lastMouseX = 0;
    private short lastMouseY = 0;
    private float mouseSpeed = 1;
    private final float[] smoothedMouse = new float[2];

    // External haptics
    private boolean isExternalHapticsRunning = false;
    private final float[] lastVibration = new float[2];
    private final Vector<HapticServiceClient> externalHapticsServiceClients = new Vector<>();
    private final Vector<Pair<String, String>> externalHapticsServiceDetails = new Vector<>();

    public XrController() {
        instance = XrActivity.getInstance();
        mouseSpeed = PreferenceManager.getDefaultSharedPreferences(instance).getFloat("cursor_speed", 1.0f);

        externalHapticsServiceDetails.add(Pair.create(HapticsConstants.BHAPTICS_PACKAGE, HapticsConstants.BHAPTICS_ACTION_FILTER));
        externalHapticsServiceDetails.add(Pair.create(HapticsConstants.FORCETUBE_PACKAGE, HapticsConstants.FORCETUBE_ACTION_FILTER));
        for (Pair<String, String> serviceDetail : externalHapticsServiceDetails) {
            Intent intent = new Intent(serviceDetail.second).setPackage(serviceDetail.first);
            HapticServiceClient client = new HapticServiceClient(instance, (state, desc) -> {}, intent);
            client.bindService();
            externalHapticsServiceClients.add(client);
        }
    }

    public void unload() {
        try {
            for (HapticServiceClient externalHapticsServiceClient : externalHapticsServiceClients) {
                externalHapticsServiceClient.stopBinding();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public boolean updateAndroidInput(boolean[] buttons) {
        // Get OpenXR input
        int primaryController = instance.container.getPrimaryController();
        XrInterface.ControllerButton primaryPress = primaryController == 0 ? XrInterface.ControllerButton.L_THUMBSTICK_PRESS : XrInterface.ControllerButton.R_THUMBSTICK_PRESS;        XrInterface.ControllerButton primaryTrigger = primaryController == 0 ? XrInterface.ControllerButton.L_TRIGGER : XrInterface.ControllerButton.R_TRIGGER;
        XrInterface.ControllerButton primaryUp = primaryController == 0 ? XrInterface.ControllerButton.L_THUMBSTICK_UP : XrInterface.ControllerButton.R_THUMBSTICK_UP;
        XrInterface.ControllerButton primaryDown = primaryController == 0 ? XrInterface.ControllerButton.L_THUMBSTICK_DOWN : XrInterface.ControllerButton.R_THUMBSTICK_DOWN;
        XrInterface.ControllerButton primaryLeft = primaryController == 0 ? XrInterface.ControllerButton.L_THUMBSTICK_LEFT : XrInterface.ControllerButton.R_THUMBSTICK_LEFT;
        XrInterface.ControllerButton primaryRight = primaryController == 0 ? XrInterface.ControllerButton.L_THUMBSTICK_RIGHT : XrInterface.ControllerButton.R_THUMBSTICK_RIGHT;

        // Pass the input to the Android UI
        XrContentDialog dialog = XrContentDialog.getFrontInstance();
        if (dialog != null) {
            if (getButtonClicked(buttons, primaryPress)) instance.runOnUiThread(dialog::onBackPressed);
            if (getButtonClicked(buttons, primaryUp)) instance.runOnUiThread(() -> dialog.onKeyAction(KeyEvent.KEYCODE_DPAD_UP));
            if (getButtonClicked(buttons, primaryDown)) instance.runOnUiThread(() -> dialog.onKeyAction(KeyEvent.KEYCODE_DPAD_DOWN));
            if (getButtonClicked(buttons, primaryTrigger)) instance.runOnUiThread(() -> dialog.onKeyAction(KeyEvent.KEYCODE_ENTER));
            if (getButtonClicked(buttons, primaryLeft)) instance.runOnUiThread(() -> dialog.onKeyAction(KeyEvent.KEYCODE_DPAD_LEFT));
            if (getButtonClicked(buttons, primaryRight)) instance.runOnUiThread(() -> dialog.onKeyAction(KeyEvent.KEYCODE_DPAD_RIGHT));
            System.arraycopy(buttons, 0, lastButtons, 0, buttons.length);
            lastDialogShown = System.currentTimeMillis();
            instance.nativeSetUseVR(false);
            XrActivity.isVR = false;
            return false;
        } else if (getButtonClicked(buttons, primaryPress)) {
            instance.runOnUiThread(() -> new NavigationDialog(instance).show());
        }

        // Block input shortly after dialog closed
        if (System.currentTimeMillis() - lastDialogShown < 500) {
            System.arraycopy(buttons, 0, lastButtons, 0, buttons.length);
            return false;
        }
        return true;
    }

    public void updateHaptics(XrAPI xrAPI) {
        // Define haptics
        String[] sendEvent = {null, null};
        XrInterface.AppInput[] haptics = {XrInterface.AppInput.L_HAPTICS, XrInterface.AppInput.R_HAPTICS};
        for (int i = 0; i < haptics.length; i++) {
            XrInterface.AppInput haptic = haptics[i];
            float value = xrAPI.getValue(haptic);
            if (value > 0.0f) {
                // External haptics (scheme from Doom3Quest)
                if (lastVibration[i] < value) {
                    sendEvent[i] = value > 1 ? "shotgun_fire" : "pistol_fire";
                }
                // Controller haptics
                instance.vibrateController(1, i, value);
                xrAPI.setValue(haptic, value - 0.1f);
                lastVibration[i] = value;
            } else {
                xrAPI.setValue(haptic, 0.0f);
                lastVibration[i] = 0.0f;
            }
        }

        // Update external haptics
        for (HapticServiceClient externalHapticsServiceClient : externalHapticsServiceClients) {
            if (externalHapticsServiceClient.hasService()) {
                try {
                    if (isExternalHapticsRunning != XrActivity.isUDP) {
                        if (XrActivity.isUDP) {
                            externalHapticsServiceClient.getHapticsService().hapticEnable();
                        } else {
                            externalHapticsServiceClient.getHapticsService().hapticDisable();
                        }
                    } else if (isExternalHapticsRunning) {
                        for (int i = 0; i < haptics.length; i++) {
                            if (sendEvent[i] != null) {
                                externalHapticsServiceClient.getHapticsService().hapticEvent("Doom3Quest", sendEvent[i], i + 1, 0, 100, 0, 0);
                            }
                        }
                        externalHapticsServiceClient.getHapticsService().hapticFrameTick();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        isExternalHapticsRunning = XrActivity.isUDP;
    }

    public void updateKeyboardButtons(boolean[] buttons) {
        // Get OpenXR input
        int primaryController = instance.container.getPrimaryController();
        XrInterface.ControllerButton secondaryGrip = primaryController == 1 ? XrInterface.ControllerButton.L_GRIP : XrInterface.ControllerButton.R_GRIP;
        XrInterface.ControllerButton secondaryTrigger = primaryController == 1 ? XrInterface.ControllerButton.L_TRIGGER : XrInterface.ControllerButton.R_TRIGGER;
        XrInterface.ControllerButton secondaryUp = primaryController == 1 ? XrInterface.ControllerButton.L_THUMBSTICK_UP : XrInterface.ControllerButton.R_THUMBSTICK_UP;
        XrInterface.ControllerButton secondaryDown = primaryController == 1 ? XrInterface.ControllerButton.L_THUMBSTICK_DOWN : XrInterface.ControllerButton.R_THUMBSTICK_DOWN;
        XrInterface.ControllerButton secondaryLeft = primaryController == 1 ? XrInterface.ControllerButton.L_THUMBSTICK_LEFT : XrInterface.ControllerButton.R_THUMBSTICK_LEFT;
        XrInterface.ControllerButton secondaryRight = primaryController == 1 ? XrInterface.ControllerButton.L_THUMBSTICK_RIGHT : XrInterface.ControllerButton.R_THUMBSTICK_RIGHT;

        // Pass the controller mapping into XServer
        currentButtons = buttons;
        mapKey(XrInterface.ControllerButton.L_MENU, XKeycode.KEY_ESC.id);
        mapKey(XrInterface.ControllerButton.R_A, instance.container.getControllerMapping(Container.XrControllerMapping.BUTTON_A));
        mapKey(XrInterface.ControllerButton.R_B, instance.container.getControllerMapping(Container.XrControllerMapping.BUTTON_B));
        mapKey(XrInterface.ControllerButton.L_X, instance.container.getControllerMapping(Container.XrControllerMapping.BUTTON_X));
        mapKey(XrInterface.ControllerButton.L_Y, instance.container.getControllerMapping(Container.XrControllerMapping.BUTTON_Y));
        mapKey(secondaryGrip, instance.container.getControllerMapping(Container.XrControllerMapping.BUTTON_GRIP));
        mapKey(secondaryTrigger, instance.container.getControllerMapping(Container.XrControllerMapping.BUTTON_TRIGGER));
        mapKey(secondaryUp, instance.container.getControllerMapping(Container.XrControllerMapping.THUMBSTICK_UP));
        mapKey(secondaryDown, instance.container.getControllerMapping(Container.XrControllerMapping.THUMBSTICK_DOWN));
        mapKey(secondaryLeft, instance.container.getControllerMapping(Container.XrControllerMapping.THUMBSTICK_LEFT));
        mapKey(secondaryRight, instance.container.getControllerMapping(Container.XrControllerMapping.THUMBSTICK_RIGHT));
        System.arraycopy(buttons, 0, lastButtons, 0, buttons.length);
    }

    public void updateMouseAxes(float[] axes, boolean headMapping) {
        // Get OpenXR input
        int primaryController = instance.container.getPrimaryController();
        XrInterface.ControllerAxis mouseAxisX = primaryController == 0 ? XrInterface.ControllerAxis.L_X : XrInterface.ControllerAxis.R_X;
        XrInterface.ControllerAxis mouseAxisY = primaryController == 0 ? XrInterface.ControllerAxis.L_Y : XrInterface.ControllerAxis.R_Y;

        // Mouse control with hand
        float f = 0.75f;
        float meter2px = instance.getXServer().screenInfo.width * 10.0f;
        float dx = (axes[mouseAxisX.ordinal()] - lastAxes[mouseAxisX.ordinal()]) * meter2px;
        float dy = (axes[mouseAxisY.ordinal()] - lastAxes[mouseAxisY.ordinal()]) * meter2px;
        if ((Math.abs(dx) > 300) || (Math.abs(dy) > 300)) {
            dx = 0;
            dy = 0;
        }

        // Mouse control with head
        Pointer mouse = instance.getXServer().pointer;
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

        System.arraycopy(axes, 0, lastAxes, 0, axes.length);
    }

    public void updateMouseLightgun(float[] axes, float distance) {
        // Get values
        int primaryController = instance.container.getPrimaryController();
        float x = axes[primaryController == 0 ? XrInterface.ControllerAxis.L_X.ordinal() : XrInterface.ControllerAxis.R_X.ordinal()] - axes[XrInterface.ControllerAxis.HMD_X.ordinal()];;
        float y = axes[primaryController == 0 ? XrInterface.ControllerAxis.L_Y.ordinal() : XrInterface.ControllerAxis.R_Y.ordinal()] - axes[XrInterface.ControllerAxis.HMD_Y.ordinal()];;
        float yaw = axes[primaryController == 0 ? XrInterface.ControllerAxis.L_YAW.ordinal() : XrInterface.ControllerAxis.R_YAW.ordinal()];
        float pitch = axes[primaryController == 0 ? XrInterface.ControllerAxis.L_PITCH.ordinal() : XrInterface.ControllerAxis.R_PITCH.ordinal()];
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
        Pointer mouse = instance.getXServer().pointer;
        int primaryController = instance.container.getPrimaryController();
        XrInterface.ControllerButton primaryLeft = primaryController == 0 ? XrInterface.ControllerButton.L_THUMBSTICK_LEFT : XrInterface.ControllerButton.R_THUMBSTICK_LEFT;
        XrInterface.ControllerButton primaryRight = primaryController == 0 ? XrInterface.ControllerButton.L_THUMBSTICK_RIGHT : XrInterface.ControllerButton.R_THUMBSTICK_RIGHT;

        // Apply snapturn to the input
        if (getButtonClicked(buttons, primaryLeft)) {
            smoothedMouse[0] = mouse.getClampedX() - step;
        }
        if (getButtonClicked(buttons, primaryRight)) {
            smoothedMouse[0] = mouse.getClampedX() + step;
        }
    }

    public void updateMouseState(boolean[] buttons) {
        // Get OpenXR input
        Pointer mouse = instance.getXServer().pointer;
        int primaryController = instance.container.getPrimaryController();
        XrInterface.ControllerButton primaryGrip = primaryController == 0 ? XrInterface.ControllerButton.L_GRIP : XrInterface.ControllerButton.R_GRIP;
        XrInterface.ControllerButton primaryTrigger = primaryController == 0 ? XrInterface.ControllerButton.L_TRIGGER : XrInterface.ControllerButton.R_TRIGGER;
        XrInterface.ControllerButton primaryUp = primaryController == 0 ? XrInterface.ControllerButton.L_THUMBSTICK_UP : XrInterface.ControllerButton.R_THUMBSTICK_UP;
        XrInterface.ControllerButton primaryDown = primaryController == 0 ? XrInterface.ControllerButton.L_THUMBSTICK_DOWN : XrInterface.ControllerButton.R_THUMBSTICK_DOWN;

        // Apply values
        currentButtons = buttons;
        mouse.setX((int) smoothedMouse[0]);
        mouse.setY((int) smoothedMouse[1]);
        mapButton(primaryTrigger, Pointer.Button.BUTTON_LEFT);
        mapButton(primaryGrip, Pointer.Button.BUTTON_RIGHT);
        mapButton(primaryUp, Pointer.Button.BUTTON_SCROLL_UP);
        mapButton(primaryDown, Pointer.Button.BUTTON_SCROLL_DOWN);

        // Limit cursor updates to the FPS (this prevents freezing)
        long timestamp = System.currentTimeMillis();
        if (timestamp - lastMouseUpdate > 1000 / Math.max(instance.getLastFPS(), 1)) {
            if ((lastMouseX != mouse.getX()) || (lastMouseY != mouse.getY())) {
                lastMouseUpdate = timestamp;
                lastMouseX = mouse.getX();
                lastMouseY = mouse.getY();
                mouse.triggerOnPointerMove(lastMouseX, lastMouseY);
            }
        }
    }

    public void updateWheelEmulation(float[] axes, boolean[] buttons) {
        // Detect the controllers are in a pose where wheel makes sense
        float dx = axes[XrInterface.ControllerAxis.R_X.ordinal()] - axes[XrInterface.ControllerAxis.L_X.ordinal()];
        float dy = axes[XrInterface.ControllerAxis.R_Y.ordinal()] - axes[XrInterface.ControllerAxis.L_Y.ordinal()];
        float dz = axes[XrInterface.ControllerAxis.R_Z.ordinal()] - axes[XrInterface.ControllerAxis.L_Z.ordinal()];
        float size = (float) Math.sqrt(dx * dx + dy * dy);
        if ((Math.abs(dz) > 0.1) || (size > 0.4f)) {
            return;
        }

        // Lock mouse pointer
        smoothedMouse[0] = 0;
        smoothedMouse[1] = 0;

        // Overwrite the left/right buttons based on the state
        float deadzone = 0.02f;
        float sensitivity = 500;
        XrInterface.ControllerButton target = dy > 0 ? XrInterface.ControllerButton.L_THUMBSTICK_LEFT : XrInterface.ControllerButton.L_THUMBSTICK_RIGHT;
        if ((Math.abs(dy) > deadzone) && (Math.abs(dy) * sensitivity > System.currentTimeMillis() % 100)) {
            buttons[target.ordinal()] = true;
        }
    }

    public boolean getButtonClicked(boolean[] buttons, XrInterface.ControllerButton button) {
        return buttons[button.ordinal()] && !lastButtons[button.ordinal()];
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
