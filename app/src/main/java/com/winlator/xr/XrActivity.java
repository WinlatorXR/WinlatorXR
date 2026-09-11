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
package com.winlator.xr;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.SessionSettings;
import com.winlator.cmod.xserver.XKeycode;
import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.xr.io.XrInput;
import com.winlator.xr.io.XrRenderer;
import com.winlator.xr.ui.XrContentDialog;
import com.winlator.xr.ui.XrControllerDialog;
import com.winlator.xr.ui.XrKeyboard;
import com.winlator.xr.utils.Device;
import com.winlator.xr.utils.XrEnvironment;

public class XrActivity extends XServerDisplayActivity {
    private static XrActivity instance;
    private static XrInput xrInput;

    // Configuration flags
    private static boolean isEnabled = false;
    public static boolean isHeadTrackingAllowed = false;
    public static boolean isImmersive = false;
    public static boolean isPassthrough = false;
    public static boolean isAER = false;
    public static boolean isSBS = false;
    public static boolean isUDP = false;
    public static boolean isVR = false;
    public static boolean adjustCamera = false;
    public static boolean gamepadEmulation;
    public static boolean gamepadRadialToSquare;
    public static boolean rumblePassthrough;
    public static boolean keysEmulation;
    public static boolean mouseEmulation;
    public static boolean mouseLeftHanded;
    public static boolean mouseLightgun;
    public static boolean mouseRelative;
    public static boolean pointerSmoothing;
    public static boolean wheelEmulation;

    // How far from the eye the screen sits until the user moves it.
    public static final float DEFAULT_DISTANCE = 5.0f;

    // Rendering status
    public static long lastActive = 0;
    public static float lastDistance = DEFAULT_DISTANCE;
    public static int lastMode3D = -1;

    // How near and far the screen is allowed to sit. The magnifier steps through this range a
    // metre at a time and the thumbstick sweeps it continuously; they share the bounds so the
    // two controls cannot take the screen anywhere the other cannot bring it back from.
    public static final float MIN_DISTANCE = 0.5f;
    public static final float MAX_DISTANCE = 7.0f;

    private static final String PREF_SCREEN_DISTANCE = "xr_screen_distance";

    // Defaults for everything the XR and motion control menus can change. They live here
    // because both the menus and this activity have to agree on what an untouched setting
    // means; when they did not, the menu showed key emulation off while the session ran it
    // on. A shortcut that has been given its own answer overrides these.
    public static final boolean DEFAULT_CURVED_SCREEN = false;
    public static final boolean DEFAULT_PASSTHROUGH = true;
    public static final boolean DEFAULT_GAMEPAD = false;
    public static final boolean DEFAULT_RADIAL_TO_SQUARE = false;
    public static final boolean DEFAULT_RUMBLE_PASSTHROUGH = false;
    public static final boolean DEFAULT_KEYS = true;
    public static final boolean DEFAULT_MOUSE = true;
    public static final boolean DEFAULT_MOUSE_LEFT_HANDED = false;
    public static final boolean DEFAULT_MOUSE_LIGHTGUN = false;
    public static final boolean DEFAULT_MOUSE_RELATIVE = false;
    public static final boolean DEFAULT_POINTER_SMOOTHING = false;
    public static final boolean DEFAULT_WHEEL = false;

    /**
     * Every setting the XR menu can pin to a game. Listed so "Reset to default" can drop the
     * lot and let the game inherit the app-wide values again; anything added to the menu
     * belongs here too, or it will survive a reset.
     */
    public static final String[] SESSION_KEYS = {
            "use_cs", "use_pt", "use_xr_gamepad", "xr_gamepad_radial_to_square",
            "use_xr_rumble_passthrough", "use_xr_keys", "use_xr_mouse", "use_xr_leftHanded",
            "use_xr_lightgun", "use_xr_relative_mouse", "use_xr_smoothing", "use_xr_wheel",
            PREF_SCREEN_DISTANCE, XrEnvironment.PREF_KEY, XrEnvironment.ENABLED_KEY,
            XrControllerDialog.XR_CONTROLLER_PROFILE_INDEX};

    static {
        System.loadLibrary("xr");
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadSessionSettings();
        sendManufacturer(Build.MANUFACTURER.toUpperCase());

        instance = this;
        if (xrInput == null) {
            xrInput = new XrInput();
        }
    }

    /**
     * Reads every XR setting for the game being played and pushes it to the native side.
     * Called once at startup, and again after a reset to defaults so the change shows up
     * without waiting for a relaunch.
     */
    private void loadSessionSettings() {
        // Read through SessionSettings, not the preferences directly: a game that has been
        // given its own answer for one of these overrides the app-wide default.
        boolean curvedScreen = SessionSettings.getBoolean(this, "use_cs", DEFAULT_CURVED_SCREEN);
        int sharpening = SessionSettings.getInt(this, "sharpening_level", 0);
        int edgeGlow = SessionSettings.getInt(this, "edge_glow_level", 0);
        isPassthrough = SessionSettings.getBoolean(this, "use_pt", DEFAULT_PASSTHROUGH);
        gamepadEmulation = SessionSettings.getBoolean(this, "use_xr_gamepad", DEFAULT_GAMEPAD);
        gamepadRadialToSquare = SessionSettings.getBoolean(this, "xr_gamepad_radial_to_square", DEFAULT_RADIAL_TO_SQUARE);
        rumblePassthrough = SessionSettings.getBoolean(this, "use_xr_rumble_passthrough", DEFAULT_RUMBLE_PASSTHROUGH);
        keysEmulation = SessionSettings.getBoolean(this, "use_xr_keys", DEFAULT_KEYS);
        mouseEmulation = SessionSettings.getBoolean(this, "use_xr_mouse", DEFAULT_MOUSE);
        mouseLeftHanded = SessionSettings.getBoolean(this, "use_xr_leftHanded", DEFAULT_MOUSE_LEFT_HANDED);
        mouseLightgun = SessionSettings.getBoolean(this, "use_xr_lightgun", DEFAULT_MOUSE_LIGHTGUN);
        mouseRelative = SessionSettings.getBoolean(this, "use_xr_relative_mouse", DEFAULT_MOUSE_RELATIVE);
        pointerSmoothing = SessionSettings.getBoolean(this, "use_xr_smoothing", DEFAULT_POINTER_SMOOTHING);
        wheelEmulation = SessionSettings.getBoolean(this, "use_xr_wheel", DEFAULT_WHEEL);
        lastDistance = SessionSettings.getFloat(this, PREF_SCREEN_DISTANCE, DEFAULT_DISTANCE);

        if (mouseLightgun) mouseRelative = false;
        setRelativeMouseMovement(mouseRelative);

        nativeSetUsePT(isPassthrough);
        nativeSetCurvedScreen(curvedScreen);
        nativeSetSharpening(sharpening);
        nativeSetEdgeGlow(edgeGlow);
        nativeSetPointerSmoothing(pointerSmoothing);
        nativeSetEnvironmentEnabled(XrEnvironment.isEnabled(this));
    }

    /**
     * After a reset, the XR settings have to be read again and the panorama reapplied: the
     * selection is one of the things that goes back to the default, and nothing else reloads
     * it until the next launch.
     */
    @Override
    protected void reloadSessionSettings() {
        super.reloadSessionSettings();
        loadSessionSettings();
        XrEnvironment.apply(this, XrEnvironment.getSelected(this));
    }

    @Override
    public synchronized void onDestroy() {
        super.onDestroy();
        closeSession();
    }

    public synchronized void closeSession() {
        xrInput.unload();

        Intent intent = getBaseContext().getPackageManager()
                .getLaunchIntentForPackage(getBaseContext().getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }

        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(0);
    }

    public static XrActivity getInstance() {
        return instance;
    }

    public static boolean getImmersive() {
        return isImmersive && XrContentDialog.getFrontInstance() == null;
    }

    public int getLastFPS() {
        return isVR && XrRenderer.vrWindowOnTop ? XrRenderer.getLastFPS() : (int) frameRating.getLastFPS();
    }

    public static boolean getAER() {
        return isAER && XrContentDialog.getFrontInstance() == null;
    }
    public static boolean getSBS() {
        if (isVR && !XrRenderer.vrWindowOnTop) {
            return false;
        }
        return isSBS;
    }

    public static boolean getVR() {
        return isVR && XrRenderer.vrWindowOnTop && XrContentDialog.getFrontInstance() == null;
    }

    public static float getDistance() {
        return lastDistance;
    }

    /** Remembers where the user left the screen, for the magnifier and the thumbstick alike. */
    public void saveScreenDistance() {
        SessionSettings.putFloat(this, PREF_SCREEN_DISTANCE, lastDistance);
    }

    public static boolean isActive() {
        return Math.abs(System.currentTimeMillis() - lastActive) < 5000;
    }

    public static boolean isEnabled(Context context) {
        if (context != null) {
            isEnabled = PreferenceManager.getDefaultSharedPreferences(context).getBoolean("use_xr", true);
        }
        return isEnabled && Device.isSupported();
    }

    public void callMenuAction(int item) {
        switch (item) {
            case R.id.main_menu_keyboard:
                new XrKeyboard(instance).show();
                break;
            case R.id.main_menu_magnifier:
                lastDistance -= 1.0f;
                if (lastDistance < MIN_DISTANCE) {
                    lastDistance = MAX_DISTANCE;
                }
                saveScreenDistance();
                break;
            case R.id.main_menu_task_manager:
                getWinHandler().exec("taskmgr.exe");
                break;
            case R.id.main_menu_camera:
                adjustCamera = true;
                ContentDialog.info(this, R.string.hint_camera_adjust, dialogInterface -> adjustCamera = false);
                break;
            case R.id.main_menu_reshade:
                isImmersive = false;
                isSBS = false;
                XrKeyboard.sendKey(XKeycode.KEY_HOME);
                break;
        }
    }

    public static void openIntent(Activity context, int containerId, String path) {
        // Create the launch intent
        Intent intent = new Intent(context, Device.getRuntime());
        intent.putExtra("container_id", containerId);
        if (path != null) {
            intent.putExtra("shortcut_path", path);
        }

        // Set the flags
        final int mainDisplayId = Display.DEFAULT_DISPLAY;
        ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(mainDisplayId);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK |
                Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        // Launch the activity
        context.getBaseContext().startActivity(intent, options.toBundle());
        context.finish();
    }

    public synchronized void updateFrame() {
        xrInput.update();
    }

    // Rendering
    public native void init(int width, int height, int refresh, int cpu, int gpu);
    public native void bindFramebuffer();
    public native int getWidth();
    public native int getHeight();
    public native boolean initFrame(boolean immersive, boolean sbs, boolean aer, float distance);
    public native void bindFBO(int index);
    public native void endFrame();

    // Controllers
    public native float[] getAxes();
    public native boolean[] getButtons();
    public native void vibrateController(int duration, int chan, float intensity);

    // Settings
    public native void nativeSetFoV(float x, float y);
    public native void nativeSetCurvedScreen(boolean enabled);
    public native void nativeSetUsePT(boolean enabled);
    public native void nativeSetSharpening(int level);
    public native void nativeSetEdgeGlow(int intensity);
    public native void nativeSetPointerSmoothing(boolean enabled);
    public native boolean nativeIsEnvironmentSupported();
    public native void nativeSetEnvironment(byte[] rgba, int width, int height);
    public native void nativeSetEnvironmentEnabled(boolean enabled);
    public native boolean nativeIsSharpeningSupported();
    public native void nativeSetUseVR(boolean enabled);
    public native void nativeSetVRApp(boolean enabled);
    public native void nativeSetFramesync(int r, int g, int b, int a);
    public native void sendManufacturer(String manufacturer);

    // XrAPI
    public native void addLocateSpace(int a, int b);
    public native void clearLocateSpaces();
    public native float[] getPose(int a, int b);
    public native void increaseReferenceSpacesOffset(float x, float y, float z);
    public native void updateActionSpace(int space, int type, int grip, float x, float y, float z,
                                         float qx, float qy, float qz, float qw);
    public native void updateReferenceSpace(int space, int type, float x, float y, float z,
                                            float qx, float qy, float qz, float qw);
}
