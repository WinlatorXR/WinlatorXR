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
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.xserver.XKeycode;
import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.xr.io.XrInput;
import com.winlator.xr.io.XrRenderer;
import com.winlator.xr.ui.XrContentDialog;
import com.winlator.xr.ui.XrKeyboard;
import com.winlator.xr.utils.Device;

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
    public static boolean adjustCameraHeight = true;
    public static boolean gamepadEmulation;
    public static boolean gamepadRadialToSquare;
    public static boolean keysEmulation;
    public static boolean mouseEmulation;
    public static boolean mouseLeftHanded;
    public static boolean mouseLightgun;
    public static boolean mouseRelative;
    public static boolean wheelEmulation;

    // Rendering status
    public static long lastActive = 0;
    public static float lastDistance = 5;
    public static int lastMode3D = -1;

    static {
        System.loadLibrary("xr");
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean curvedScreen = prefs.getBoolean("use_cs", false);
        int sharpening = prefs.getInt("sharpening_level", 0);
        isPassthrough = prefs.getBoolean("use_pt", true);
        gamepadEmulation = prefs.getBoolean("use_xr_gamepad", false);
        gamepadRadialToSquare = prefs.getBoolean("xr_gamepad_radial_to_square", true);
        keysEmulation = prefs.getBoolean("use_xr_keys", true);
        mouseEmulation = prefs.getBoolean("use_xr_mouse", true);
        mouseLeftHanded = prefs.getBoolean("use_xr_leftHanded", false);
        mouseLightgun = prefs.getBoolean("use_xr_lightgun", false);
        mouseRelative = prefs.getBoolean("use_xr_relative_mouse", false);
        wheelEmulation = prefs.getBoolean("use_xr_wheel", false);

        if (mouseLightgun) mouseRelative = false;
        setRelativeMouseMovement(mouseRelative);

        nativeSetUsePT(isPassthrough);
        nativeSetCurvedScreen(curvedScreen);
        nativeSetSharpening(sharpening);
        sendManufacturer(Build.MANUFACTURER.toUpperCase());

        instance = this;
        if (xrInput == null) {
            xrInput = new XrInput();
        }
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
                if (lastDistance < 0.5f) {
                    lastDistance = 7.0f;
                }
                break;
            case R.id.main_menu_task_manager:
                getWinHandler().exec("taskmgr.exe");
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
    public native boolean nativeIsSharpeningSupported();
    public native void nativeSetUseVR(boolean enabled);
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
