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

import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.inputcontrols.ControllerManager;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;
import com.winlator.xr.XrActivity;
import com.winlator.xr.api.XrAPI;
import com.winlator.xr.api.XrInterface;
import com.winlator.xr.ui.XrContentDialog;
import com.winlator.xr.ui.XrKeyboard;
import com.winlator.xr.utils.PcvrRuntime;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class XrInput {
    // Metres per second at full deflection, and how far the thumb has to move before any of it
    // counts - the stick is shared with mouse and gamepad emulation, so a resting thumb must
    // not creep the screen.
    private static final float SCREEN_DISTANCE_RATE = 3.0f;
    private static final float SCREEN_DISTANCE_DEADZONE = 0.25f;

    // A tick on the hand that took the grip, once on press and once on release.
    private static final int SCREEN_DISTANCE_HAPTIC_MILLIS = 40;
    private static final float SCREEN_DISTANCE_HAPTIC_INTENSITY = 0.35f;

    // The whole stick is taken while the grip is held, both axes, so nothing leaks into the
    // menu behind the screen being moved.
    private static final XrInterface.ControllerButton[] LEFT_STICK_BUTTONS = {
            XrInterface.ControllerButton.L_THUMBSTICK_LEFT,
            XrInterface.ControllerButton.L_THUMBSTICK_RIGHT,
            XrInterface.ControllerButton.L_THUMBSTICK_UP,
            XrInterface.ControllerButton.L_THUMBSTICK_DOWN};
    private static final XrInterface.ControllerButton[] RIGHT_STICK_BUTTONS = {
            XrInterface.ControllerButton.R_THUMBSTICK_LEFT,
            XrInterface.ControllerButton.R_THUMBSTICK_RIGHT,
            XrInterface.ControllerButton.R_THUMBSTICK_UP,
            XrInterface.ControllerButton.R_THUMBSTICK_DOWN};

    // The face buttons, in the order ContentDialog.FaceButton lists them, and which hand
    // each one is on for the haptic tick.
    private static final XrInterface.ControllerButton[] FACE_BUTTONS = {
            XrInterface.ControllerButton.R_A,
            XrInterface.ControllerButton.R_B,
            XrInterface.ControllerButton.L_X,
            XrInterface.ControllerButton.L_Y};
    private static final int[] FACE_BUTTON_HANDS = {1, 1, 0, 0};
    // Held rather than calling values() per frame, which copies the array each time.
    private static final ContentDialog.FaceButton[] FACE_BUTTON_ACTIONS =
            ContentDialog.FaceButton.values();

    // A tick when a hold starts and another when it fires, so a three second wait is not
    // spent wondering whether the button registered.
    private static final int FACE_BUTTON_HAPTIC_MILLIS = 40;
    private static final float FACE_BUTTON_HAPTIC_INTENSITY = 0.35f;

    private final XrController xrController;
    private final XrHaptics xrHaptics;

    private boolean screenDistanceAdjusting = false;
    private long lastDistanceNanos = 0;

    // Edge detection for the face buttons is kept here rather than read from XrController:
    // the buttons are cleared out of the frame's array below, so by the time XrController
    // records them they always look released.
    private final boolean[] faceButtonWasDown = new boolean[FACE_BUTTONS.length];
    private final long[] faceButtonHeldSince = new long[FACE_BUTTONS.length];
    private final boolean[] faceButtonFired = new boolean[FACE_BUTTONS.length];
    // Which dialog the state above belongs to, so a hold cannot be carried across to another
    // one and count as time already served there. Only ever the dialog that is currently in
    // front, which XrContentDialog is holding anyway, and dropped as soon as it is not.
    private ContentDialog faceButtonDialog = null;

    private XrAPI xrAPI = null;
    private boolean wasBlocking = false;

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
        updateScreenDistance(instance, axes, buttons);
        updateMenuActions(instance, buttons);

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
            if (!wasBlocking && XrActivity.keysEmulation) releaseXServerKeys(instance.getXServer());
            if (XrActivity.isUDP) xrController.updateXrCamera(buttons);
            updateXrApp(filteredAxes(axes), new boolean[buttons.length]);
            xrController.updateFinished(axes, buttons);
        } else {
            updateShortcuts(buttons);
            updateXrApp(axes, buttons);
            updateXServer(instance.getXServer(), axes, buttons);
        }
        wasBlocking = blocking;
    }

    private void releaseXServerKeys(XServer xServer) {
        // Queued on the same executor as updateXServer so it lands after any key press still in
        // flight from the frame the menu was summoned on.
        xServerExecutor.execute(() -> {
            try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                xrController.releaseKeyboardButtons();
            }
        });
    }

    /**
     * Thumbstick control over the screen distance, which is the same value the magnifier menu
     * item steps through - this just sweeps it continuously instead of a metre at a time.
     *
     * Only the primary hand adjusts, but with the menu up neither grip reaches it: XrController
     * reads a grip as a menu left or right, and grip now means "the screen", so a hand that is
     * not adjusting must not be steering the selector either. The adjusting hand additionally
     * loses its thumbstick, which would otherwise scroll the menu the screen is moving behind;
     * the other hand keeps its stick, so the menu can still be navigated throughout.
     */
    private void updateScreenDistance(XrActivity instance, float[] axes, boolean[] buttons) {
        boolean menuShown = XrContentDialog.getFrontInstance() != null;
        boolean left = XrActivity.mouseLeftHanded;
        int grip = (left ? XrInterface.ControllerButton.L_GRIP
                         : XrInterface.ControllerButton.R_GRIP).ordinal();
        boolean adjusting = menuShown && buttons[grip];

        long now = System.nanoTime();
        float elapsed = (screenDistanceAdjusting && (lastDistanceNanos > 0))
                ? (now - lastDistanceNanos) / 1_000_000_000.0f : 0.0f;
        lastDistanceNanos = now;

        if (adjusting) {
            // Nothing moves until the stick is pushed, so without this there is no sign the
            // grip took it.
            if (!screenDistanceAdjusting) tickScreenDistance(instance, left);

            // A stall between frames must not turn one nudge into a full sweep.
            if (elapsed > 0.1f) elapsed = 0.1f;

            float push = axes[(left ? XrInterface.ControllerAxis.L_THUMBSTICK_Y
                                    : XrInterface.ControllerAxis.R_THUMBSTICK_Y).ordinal()];
            if (Math.abs(push) > SCREEN_DISTANCE_DEADZONE) {
                // Rescaled past the deadzone so it starts from a standstill rather than jumping
                // to a quarter speed the moment the thumb crosses the threshold.
                push -= Math.signum(push) * SCREEN_DISTANCE_DEADZONE;
                push /= (1.0f - SCREEN_DISTANCE_DEADZONE);
                XrActivity.lastDistance = Math.max(XrActivity.MIN_DISTANCE, Math.min(
                        XrActivity.MAX_DISTANCE,
                        XrActivity.lastDistance + push * SCREEN_DISTANCE_RATE * elapsed));
            }

            for (XrInterface.ControllerButton consumed : left ? LEFT_STICK_BUTTONS : RIGHT_STICK_BUTTONS) {
                buttons[consumed.ordinal()] = false;
            }
            axes[(left ? XrInterface.ControllerAxis.L_THUMBSTICK_X
                       : XrInterface.ControllerAxis.R_THUMBSTICK_X).ordinal()] = 0;
            axes[(left ? XrInterface.ControllerAxis.L_THUMBSTICK_Y
                       : XrInterface.ControllerAxis.R_THUMBSTICK_Y).ordinal()] = 0;
        } else if (screenDistanceAdjusting) {
            // Written out on release rather than every frame it is held, and ticked again to
            // say so.
            instance.saveScreenDistance();
            tickScreenDistance(instance, left);
        }
        screenDistanceAdjusting = adjusting;

        if (menuShown) {
            buttons[XrInterface.ControllerButton.L_GRIP.ordinal()] = false;
            buttons[XrInterface.ControllerButton.R_GRIP.ordinal()] = false;
        }
    }

    /**
     * The face buttons drive the action lines listed at the bottom of an open dialog.
     *
     * With a dialog up these four have nothing else to do - the thumbstick and trigger
     * already work the menu, and nothing reaches the game while it is blocking - so they are
     * free for actions the menu cannot sensibly offer as another checkbox. They are taken out
     * of the frame's button array whether or not a line claims them, so a press can never
     * arrive in the game behind, or count as a keyboard or gamepad button, while the menu is
     * up. The keyboard is left alone: it is a dialog of its own with its own input.
     */
    private void updateMenuActions(XrActivity instance, boolean[] buttons) {
        XrContentDialog front = XrKeyboard.isShown() ? null : XrContentDialog.getFrontInstance();
        ContentDialog dialog = (front instanceof ContentDialog) ? (ContentDialog) front : null;
        if (dialog != faceButtonDialog) {
            faceButtonDialog = dialog;
            // Seeded from what is held right now rather than zeroed, so a thumb already
            // resting on a button when the dialog opens is not read as a press against it,
            // and a hold has to be started again from a release.
            for (int i = 0; i < FACE_BUTTONS.length; i++) {
                boolean down = buttons[FACE_BUTTONS[i].ordinal()];
                faceButtonWasDown[i] = down;
                faceButtonHeldSince[i] = 0;
                faceButtonFired[i] = down;
            }
        }
        if (dialog == null) return;

        long now = System.currentTimeMillis();
        for (int i = 0; i < FACE_BUTTONS.length; i++) {
            int index = FACE_BUTTONS[i].ordinal();
            boolean down = buttons[index];
            buttons[index] = false;

            ContentDialog.FaceButton action = FACE_BUTTON_ACTIONS[i];
            if (!dialog.hasFaceButtonAction(action)) {
                faceButtonWasDown[i] = down;
                continue;
            }

            long holdMillis = dialog.getFaceButtonHoldMillis(action);
            boolean hasPress = dialog.hasFaceButtonPressAction(action);

            if (!down) {
                // A press acts here rather than on the way down, so that a button which
                // also has a hold is not read as a press on its way past the hold.
                if (faceButtonWasDown[i] && !faceButtonFired[i] && hasPress) {
                    tickFaceButton(instance, i);
                    dialog.runFaceButtonPress(action);
                }
                faceButtonHeldSince[i] = 0;
                faceButtonFired[i] = false;
            }
            else if (!faceButtonFired[i]) {
                if (faceButtonHeldSince[i] == 0) {
                    faceButtonHeldSince[i] = now;
                    // Say a hold has been noticed, otherwise the wait is indistinguishable
                    // from the press having missed. Nothing to say when a press is going to
                    // act on release anyway.
                    if (holdMillis > 0 && !hasPress) tickFaceButton(instance, i);
                }
                else if (holdMillis > 0 && (now - faceButtonHeldSince[i] >= holdMillis)) {
                    faceButtonFired[i] = true;
                    tickFaceButton(instance, i);
                    dialog.runFaceButtonHold(action);
                }
            }

            faceButtonWasDown[i] = down;
        }
    }

    private void tickFaceButton(XrActivity instance, int index) {
        instance.vibrateController(FACE_BUTTON_HAPTIC_MILLIS, FACE_BUTTON_HANDS[index],
                FACE_BUTTON_HAPTIC_INTENSITY);
    }

    /** Short enough to read as a click rather than a buzz, on the hand holding the grip. */
    private void tickScreenDistance(XrActivity instance, boolean left) {
        instance.vibrateController(SCREEN_DISTANCE_HAPTIC_MILLIS, left ? 0 : 1,
                SCREEN_DISTANCE_HAPTIC_INTENSITY);
    }

    private void updateShortcuts(boolean[] buttons) {
        XrInterface.ControllerButton primaryGrip = XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_GRIP : XrInterface.ControllerButton.R_GRIP;
        XrInterface.ControllerButton secondaryPress = !XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_THUMBSTICK_PRESS : XrInterface.ControllerButton.R_THUMBSTICK_PRESS;
        if (!XrActivity.gamepadEmulation && !XrActivity.getVR() && xrController.getButtonClicked(buttons, secondaryPress)) {
            if (buttons[primaryGrip.ordinal()]) {
                XrActivity.isSBS = !XrActivity.isSBS;
            } else if (!XrActivity.isUDP) {
                // The menu greys the tick box out while TrackIR or another XR app is connected
                XrActivity.isImmersive = !XrActivity.isImmersive;
            }
        }
    }

    private void updateXrApp(float[] axes, boolean[] buttons) {
        if (XrActivity.isUDP) {
            byte[] packet = xrAPI.encodeBinary(axes, buttons, 0);
            if (packet == null) {
                String data = xrAPI.encode(axes, buttons, 0) + xrAPI.getFlags();
                packet = data.getBytes(StandardCharsets.US_ASCII);
            }
            xrAPI.sendAsync(packet);
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
                boolean headMapping = XrActivity.isImmersive && XrActivity.isHeadTrackingAllowed;
                xrAPI.consumeInputs(xServer);
                if (XrActivity.gamepadEmulation) {
                    xrController.updateGamepad(axes, buttons, headMapping);
                } else if (XrActivity.rumblePassthrough) {
                    // Keep the virtual XInput device "connected" so the guest sends rumble,
                    // but with a neutral state so it never drives buttons/axes (those stay on mouse/keys).
                    xrController.updateGamepad(new float[axes.length], new boolean[buttons.length], false);
                }
                if (XrActivity.keysEmulation) {
                    // A PC VR game already gets these buttons through the runtime, so mapped keys would press twice
                    if (PcvrRuntime.active && XrActivity.getVR()) xrController.releaseKeyboardButtons();
                    else xrController.updateKeyboardButtons(buttons);
                }
                if (!XrActivity.getVR()) {
                    if (XrActivity.mouseEmulation) {
                        if (!headMapping || !XrActivity.gamepadEmulation) {
                            xrController.updateMouseAxes(axes, headMapping);
                            xrController.updateMouseSnapturn(buttons, XrActivity.isImmersive ? 250 : 50);
                        }
                        if (XrActivity.mouseLightgun && !XrActivity.isImmersive)
                            xrController.updateMouseLightgun(axes, XrActivity.lastDistance);
                        xrController.updateMouseCentre(buttons);
                        xrController.updateMouseState(buttons);
                    } else if (headMapping) {
                        xrController.updateMouseAxes(axes, true);
                        xrController.updateMouseState(new boolean[buttons.length]);
                    }
                }
                // Also in VR when gamepad mode is on, for PC VR games that have no VR hands
                if (XrActivity.wheelEmulation && (!XrActivity.getVR() || XrActivity.gamepadEmulation)) {
                    xrController.updateWheelEmulation(axes);
                }
                xrController.updateFinished(axes, buttons);
            }
        });
    }

    private float[] filteredAxes(float[] axes) {
        float[] output = new float[axes.length];
        System.arraycopy(axes, 0, output, 0, axes.length);
        output[XrInterface.ControllerAxis.L_THUMBSTICK_X.ordinal()] = 0;
        output[XrInterface.ControllerAxis.L_THUMBSTICK_Y.ordinal()] = 0;
        output[XrInterface.ControllerAxis.L_TRIGGER.ordinal()] = 0;
        output[XrInterface.ControllerAxis.R_THUMBSTICK_X.ordinal()] = 0;
        output[XrInterface.ControllerAxis.R_THUMBSTICK_Y.ordinal()] = 0;
        output[XrInterface.ControllerAxis.R_TRIGGER.ordinal()] = 0;
        return output;
    }
}
