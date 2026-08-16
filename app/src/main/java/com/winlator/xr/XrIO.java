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

import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;
import com.winlator.xr.api.XrAPI;
import com.winlator.xr.api.XrInterface;
import com.winlator.xr.ui.XrKeyboard;

import java.nio.charset.StandardCharsets;

public class XrIO {
    private final XrActivity instance;
    private final XrController xrController;
    private XrAPI xrAPI = null;

    public XrIO(XrActivity activity) {
        instance = activity;
        xrController = new XrController();

        if (XrActivity.gamepadEmulation || XrActivity.wheelEmulation) {
            XrController.ensureVirtualControllerAttached();
        }
    }

    public void unload() {
        xrController.unload();
    }

    public void update() {
        // Get OpenXR data
        float[] axes = instance.getAxes();
        boolean[] buttons = instance.getButtons();

        // Communication between XR and Windows apps
        updateXrAPI();
        xrController.updateHaptics(xrAPI);

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
        } else {
            updateShortcuts(buttons);
            updateXrApp(axes, buttons);
            updateXServer(axes, buttons);
        }
    }

    private void updateShortcuts(boolean[] buttons) {
        XrInterface.ControllerButton primaryGrip = XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_GRIP : XrInterface.ControllerButton.R_GRIP;
        XrInterface.ControllerButton secondaryPress = !XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_THUMBSTICK_PRESS : XrInterface.ControllerButton.R_THUMBSTICK_PRESS;
        if (!XrActivity.gamepadEmulation && xrController.getButtonClicked(buttons, secondaryPress)) {
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

    private void updateXrAPI() {
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

    private void updateXServer(float[] axes, boolean[] buttons) {
        new Thread(() -> {
            try (XLock lock = instance.getXServer().lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                xrAPI.consumeInputs(instance.getXServer());
                if (XrActivity.gamepadEmulation) {
                    xrController.updateGamepad(axes, buttons);
                }
                if (!XrActivity.getVR()) {
                    if (XrActivity.keysEmulation) {
                        xrController.updateKeyboardButtons(buttons);
                    }
                    if (XrActivity.mouseEmulation) {
                        xrController.updateMouseAxes(axes, XrActivity.isImmersive && XrActivity.isHeadTrackingAllowed);
                        xrController.updateMouseState(buttons);
                        xrController.updateMouseSnapturn(buttons, XrActivity.isImmersive ? 250 : 50);
                        if (XrActivity.mouseLightgun && !XrActivity.isImmersive)
                            xrController.updateMouseLightgun(axes, XrActivity.lastDistance);
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
        }).start();
    }
}
