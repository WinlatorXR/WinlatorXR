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
package com.winlator;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Pair;
import android.view.Display;
import android.view.View;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.xr.XrAPI;
import com.winlator.xr.RuntimeMeta;
import com.winlator.xr.RuntimePFD;
import com.winlator.xr.RuntimePico;
import com.winlator.xr.XrContentDialog;
import com.winlator.xr.XrController;
import com.winlator.xr.XrKeyboard;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.XKeycode;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;

import static com.winlator.xr.XrInterface.AppInput;
import static com.winlator.xr.XrInterface.ControllerButton;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;

public class XrActivity extends XServerDisplayActivity {
    private static XrActivity instance;

    // Configuration flags
    private static boolean isEnabled = false;
    private static boolean isHeadTrackingAllowed = false;
    public static boolean isImmersive = false;
    private static boolean isAER = false;
    public static boolean isSBS = false;
    public static boolean isUDP = false;
    public static boolean isVR = false;
    public static boolean mouseEmulation;
    public static boolean mouseLightgun;
    public static boolean wheelEmulation;

    // Rendering status
    private static long lastActive = 0;
    private static float lastDistance = 5;
    private final ArrayList<Integer> framesyncMapping = new ArrayList<>();
    private int lastFrameSync = 0;
    public int lastMode3D = -1;

    // XR input/output
    private XrAPI xrAPI = null;
    private XrController xrController = null;
    private XrKeyboard xrKeyboard = null;

    static {
        System.loadLibrary("xr");
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean usePassthrough = prefs.getBoolean("use_pt", true);
        nativeSetUsePT(usePassthrough);
        boolean curvedScreen = prefs.getBoolean("use_cs", false);
        nativeSetCurvedScreen(curvedScreen);
        mouseEmulation = prefs.getBoolean("use_xr_mouse", true);
        mouseLightgun = prefs.getBoolean("use_xr_lightgun", false);
        wheelEmulation = prefs.getBoolean("use_xr_wheel", false);
    }

    @Override
    public synchronized void onPause() {
        xrController.unload();
        xrKeyboard.unload();
        super.onPause();
    }

    @Override
    public synchronized void onResume() {
        super.onResume();
        instance = this;
        xrController = new XrController();
        xrKeyboard = new XrKeyboard();
        sendManufacturer(Build.MANUFACTURER.toUpperCase());
    }

    @Override
    public synchronized void onDestroy() {
        super.onDestroy();
        closeSession();
    }

    public synchronized void closeSession() {
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

    public static boolean getAER() {
        return isAER && XrContentDialog.getFrontInstance() == null;
    }
    public static boolean getSBS() {
        return isSBS;
    }

    public static boolean getVR() {
        return isVR && XrContentDialog.getFrontInstance() == null;
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
        return isEnabled && isSupported();
    }

    public static boolean isSupported() {
        return getRuntime() != null;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        instance.findViewById(R.id.XRTextInput).setVisibility(View.GONE);
    }

    public void callMenuAction(int item) {
        switch (item) {
            case R.id.main_menu_keyboard:
                new Thread(() -> {
                    xrKeyboard.sleep(250); //ensure onWindowFocusChanged was called
                    runOnUiThread(() -> {
                        isVR = false;
                        isAER = false;
                        isImmersive = false;
                        xrKeyboard.show();
                    });
                }).start();
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
                xrKeyboard.sendKey(XKeycode.KEY_HOME);
                break;
        }
    }

    public Pair<Boolean, Integer> processFramesync(Drawable drawable) {
        // get sync pixel
        ByteBuffer buffer = drawable.getImage((short)0, (short)0, (short)1, (short)1);
        int b = buffer.get(0) & 0xFF;
        int g = buffer.get(1) & 0xFF;
        int r = buffer.get(2) & 0xFF;
        int a = buffer.get(3) & 0xFF;

        //define framesync behavior (the same as in xr/engine.h)
        int step = 12;
        int limit = 256;
        int expectedLength = (limit / step) + 1;

        //automatically find mapping for current color space
        if (framesyncMapping.size() < expectedLength) {
            if (!framesyncMapping.contains(r)) {
                framesyncMapping.add(r);
                framesyncMapping.sort(Comparator.comparingInt(i -> i));
            }
            return new Pair<>(false, b);
        } else if (framesyncMapping.size() == expectedLength) {
            if (!framesyncMapping.contains(r)) {
                framesyncMapping.clear();
                return new Pair<>(false, b);
            }
            r = framesyncMapping.indexOf(r) * step;
        }

        // apply the values
        nativeSetFramesync(r, g, b, a);
        Pair<Boolean, Integer> output = new Pair<>(lastFrameSync != r, b > 0 ? 1 : 0);
        lastFrameSync = r;
        return output;
    }

    public static void openIntent(Activity context, int containerId, String path) {
        // Create the launch intent
        Intent intent = new Intent(context, getRuntime());
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

    public void updateFrame() {
        // Get OpenXR data
        float[] axes = instance.getAxes();
        boolean[] buttons = instance.getButtons();

        // Communication between XR and Windows apps
        updateXrAPI(axes, buttons);
        xrController.updateHaptics(xrAPI);

        // Android UI input
        lastActive = System.currentTimeMillis();
        if (!xrController.updateAndroidInput(buttons))
            return;

        // Switch immersive/SBS mode
        updateShortcuts(buttons);

        // XServer input
        try (XLock lock = instance.getXServer().lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
            xrAPI.consumeInputs(instance.getXServer());
            if (mouseEmulation) {
                xrController.updateMouseAxes(axes, isImmersive && isHeadTrackingAllowed);
                xrController.updateMouseSnapturn(buttons, isImmersive ? 125 : 25);
                if (mouseLightgun && !isImmersive && !isVR)
                    xrController.updateMouseLightgun(axes, lastDistance);
            }
            if (wheelEmulation && !isImmersive && !isVR) {
                xrController.updateWheelEmulation(axes, buttons);
            }
            xrController.updateMouseState(buttons);
            xrController.updateKeyboardButtons(buttons);
        }
    }

    private static Class getRuntime() {
        if (Build.MANUFACTURER.compareToIgnoreCase("PICO") == 0) {
            return RuntimePico.class;
        } else if (Build.MANUFACTURER.compareToIgnoreCase("PLAY FOR DREAM") == 0) {
            return RuntimePFD.class;
        } else if (Build.MANUFACTURER.compareToIgnoreCase("OCULUS") == 0) {
            return RuntimeMeta.class;
        } else if (Build.MANUFACTURER.compareToIgnoreCase("META") == 0) {
            return RuntimeMeta.class;
        } else {
            return null;
        }
    }

    private void updateShortcuts(boolean[] buttons) {
        int primaryController = instance.container.getPrimaryController();
        ControllerButton primaryGrip = primaryController == 0 ? ControllerButton.L_GRIP : ControllerButton.R_GRIP;
        ControllerButton secondaryPress = primaryController == 1 ? ControllerButton.L_THUMBSTICK_PRESS : ControllerButton.R_THUMBSTICK_PRESS;
        if (xrController.getButtonClicked(buttons, secondaryPress)) {
            if (buttons[primaryGrip.ordinal()]) {
                isSBS = !isSBS;
            } else {
                isImmersive = !isImmersive;
            }
        }
    }

    private void updateXrAPI(float[] axes, boolean[] buttons) {
        try {
            if (xrAPI == null) {
                // Set the param to true and put a udp_debug folder in your Winlator D:\ drive
                // with a file named the IP on LAN to send XR data via UDP traffic to that IP.
                xrAPI = new XrAPI(false);
            }

            // VR mode update
            int vrMode = xrAPI.getIntValue(AppInput.MODE_VR);
            isHeadTrackingAllowed = (vrMode == 0) || (vrMode == 3);
            isUDP = vrMode > 0;
            isVR = vrMode == 1;
            getInstance().nativeSetUseVR(isVR);

            if (isUDP) {
                // Field of view adjustment
                float fovx = xrAPI.getValue(AppInput.HMD_FOVX);
                float fovy = xrAPI.getValue(AppInput.HMD_FOVY);
                getInstance().nativeSetFoV(fovx, fovy);

                // 3D mode update
                lastMode3D = xrAPI.getIntValue(AppInput.MODE_3D);
                if (lastMode3D >= 0) {
                    isAER = lastMode3D == 2;
                    isSBS = lastMode3D == 1;
                }

                // Send data into the Windows app
                String data = xrAPI.encode(axes, buttons, 0) + xrAPI.getFlags();
                xrAPI.send(data.getBytes(StandardCharsets.US_ASCII));
            } else {
                xrAPI.updateImplementation();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
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
    public native void nativeSetUseVR(boolean enabled);
    public native void nativeSetFramesync(int r, int g, int b, int a);
    public native void sendManufacturer(String manufacturer);
}
