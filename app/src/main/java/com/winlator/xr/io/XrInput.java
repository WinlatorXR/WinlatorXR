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

import com.winlator.cmod.inputcontrols.ControllerManager;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;
import com.winlator.xr.XrActivity;
import com.winlator.xr.api.XrAPI;
import com.winlator.xr.api.XrInterface;
import com.winlator.xr.ui.XrKeyboard;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class XrInput {
    private final XrController xrController;
    private final XrHaptics xrHaptics;

    private XrAPI xrAPI = null;

    // Reused for updateXServer() instead of spawning a new OS thread every VR frame.
    private final ExecutorService xServerExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "XrInput-XServer");
        t.setDaemon(true);
        return t;
    });

    public XrInput() {
        xrController = new XrController();
        xrHaptics = new XrHaptics();

        if (XrActivity.gamepadEmulation || XrActivity.wheelEmulation || XrActivity.rumblePassthrough) {
            ensureVirtualControllerAttached();
        }
    }

    public static void ensureVirtualControllerAttached() {
        ControllerManager controllerManager = ControllerManager.getInstance();
        controllerManager.scanForDevices();
        if (!controllerManager.isSlotEnabled(0)) {
            controllerManager.setSlotEnabled(0, true);
        }
    }

    public void unload() {
        xServerExecutor.shutdownNow();
        xrHaptics.unload();
    }

    public void update() {
        // Get OpenXR data
        XrActivity instance = XrActivity.getInstance();
        float[] axes = instance.getAxes();
        boolean[] buttons = instance.getButtons();

        // Communication between XR and Windows apps
        updateXrAPI(instance);
        xrHaptics.update(xrAPI);

        // Android UI input
        boolean blocking = false;
        XrActivity.lastActive = System.currentTimeMillis();
        if (XrKeyboard.isShown()) {
            XrKeyboard.update(axes, buttons, XrActivity.lastDistance);
            blocking = true;
        } else if (!xrController.updateAndroidInput(buttons))
            blocking = true;

        // XR input
        if (blocking) {
            if (XrActivity.isUDP) xrController.updateXrCamera(buttons);
            updateXrApp(axes, new boolean[buttons.length]);
            xrController.updateFinished(axes, buttons);
        } else {
            updateShortcuts(buttons);
            updateXrApp(axes, buttons);
            updateXServer(instance.getXServer(), axes, buttons);
        }
    }

    private void updateShortcuts(boolean[] buttons) {
        XrInterface.ControllerButton primaryGrip = XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_GRIP : XrInterface.ControllerButton.R_GRIP;
        XrInterface.ControllerButton secondaryPress = !XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_THUMBSTICK_PRESS : XrInterface.ControllerButton.R_THUMBSTICK_PRESS;
        if (!XrActivity.gamepadEmulation && !XrActivity.getVR() && xrController.getButtonClicked(buttons, secondaryPress)) {
            if (buttons[primaryGrip.ordinal()]) {
                XrActivity.isSBS = !XrActivity.isSBS;
            } else {
                XrActivity.isImmersive = !XrActivity.isImmersive;
            }
        }
    }

    private void updateXrApp(float[] axes, boolean[] buttons) {
        if (XrActivity.isUDP) {
            String data = xrAPI.encode(axes, buttons, 0) + xrAPI.getFlags();
            xrAPI.sendAsync(data.getBytes(StandardCharsets.US_ASCII));
        }
    }

    private void updateXrAPI(XrActivity instance) {
        try {
            if (xrAPI == null) {
                // Set the param to true and put a udp_debug folder in your Winlator D:\ drive
                // with a file named the IP on LAN to send XR data via UDP traffic to that IP.
                xrAPI = new XrAPI(false);
            }

            // VR mode update
            int vrMode = xrAPI.getIntValue(XrInterface.AppInput.MODE_VR);
            XrActivity.isHeadTrackingAllowed = (vrMode == 0) || (vrMode == 3);
            XrActivity.isUDP = vrMode > 0;
            XrActivity.isVR = vrMode == 1;
            instance.nativeSetUseVR(XrActivity.getVR());
            // Unlike getVR(), this stays true across menus and window focus changes, so the
            // environment and edge glow do not come back mid-game and cost frames a native
            // VR title has none to spare.
            instance.nativeSetVRApp(XrActivity.isVR);

            if (XrActivity.isUDP) {
                // Field of view adjustment
                float fovx = xrAPI.getValue(XrInterface.AppInput.HMD_FOVX);
                float fovy = xrAPI.getValue(XrInterface.AppInput.HMD_FOVY);
                instance.nativeSetFoV(fovx, fovy);

                // 3D mode update
                XrActivity.lastMode3D = xrAPI.getIntValue(XrInterface.AppInput.MODE_3D);
                if (XrActivity.lastMode3D >= 0) {
                    XrActivity.isAER = XrActivity.lastMode3D == 2;
                    XrActivity.isSBS = XrActivity.lastMode3D == 1;
                }
            } else {
                xrAPI.updateImplementation();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updateXServer(XServer xServer, float[] axes, boolean[] buttons) {
        xServerExecutor.execute(() -> {
            try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                xrAPI.consumeInputs(xServer);
                if (XrActivity.gamepadEmulation) {
                    xrController.updateGamepad(axes, buttons);
                } else if (XrActivity.rumblePassthrough) {
                    // Keep the virtual XInput device "connected" so the guest sends rumble,
                    // but with a neutral state so it never drives buttons/axes (those stay on mouse/keys).
                    xrController.updateGamepad(new float[axes.length], new boolean[buttons.length]);
                }
                if (XrActivity.keysEmulation) {
                    xrController.updateKeyboardButtons(buttons);
                }
                if (!XrActivity.getVR()) {
                    if (XrActivity.mouseEmulation) {
                        xrController.updateMouseAxes(axes, XrActivity.isImmersive && XrActivity.isHeadTrackingAllowed);
                        xrController.updateMouseSnapturn(buttons, XrActivity.isImmersive ? 250 : 50);
                        if (XrActivity.mouseLightgun && !XrActivity.isImmersive)
                            xrController.updateMouseLightgun(axes, XrActivity.lastDistance);
                        xrController.updateMouseState(buttons);
                    } else if (XrActivity.isImmersive && XrActivity.isHeadTrackingAllowed) {
                        xrController.updateMouseAxes(axes, true);
                        xrController.updateMouseState(new boolean[buttons.length]);
                    }
                    if (XrActivity.wheelEmulation) {
                        xrController.updateWheelEmulation(axes);
                    }
                }
                xrController.updateFinished(axes, buttons);
            }
        });
    }
}
