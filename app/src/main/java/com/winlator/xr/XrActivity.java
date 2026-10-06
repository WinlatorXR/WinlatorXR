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
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.LaunchReport;
import com.winlator.cmod.core.SessionSettings;
import com.winlator.cmod.xserver.XKeycode;
import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.xr.io.XrInput;
import com.winlator.xr.io.XrRenderer;
import com.winlator.xr.ui.XrContentDialog;
import com.winlator.xr.ui.XrControllerDialog;
import com.winlator.xr.ui.XrKeyboard;
import com.winlator.xr.utils.ModdingUtils;
import com.winlator.xr.utils.XrDevice;
import com.winlator.xr.utils.XrEnvironment;

public class XrActivity extends XServerDisplayActivity {
    private static XrActivity instance;
    private static XrInput xrInput;
    private static volatile boolean xrShutDown = false;
    private boolean closing = false;
    private boolean exitRequestHandled = false;

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
    public static boolean thumbrestDpad;
    public static boolean thumbrestMouseCentre;
    public static boolean keysEmulation;
    public static boolean mouseEmulation;
    public static boolean mouseLeftHanded;
    public static boolean mouseLightgun;
    public static boolean lightgunHaptic;
    public static boolean mouseRelative;
    public static boolean pointerSmoothing;
    public static boolean showFPS;
    public static boolean sbsStretch;
    public static boolean sbsTrim;
    public static boolean fovPassthrough;
    public static int colourKey;
    public static int colourKeyTolerance;
    public static boolean wheelEmulation;
    public static int headTurnSensitivity;

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

    /** Whether the guest frame rate is drawn over the game, in place of the DXVK HUD. */
    public static final String PREF_SHOW_FPS = "use_xr_fps";

    /** Whether SBS shows each eye at its own shape in a half-width screen, instead of 16:9. */
    public static final String PREF_SBS_STRETCH = "use_xr_sbs_stretch";

    /** Whether SBS trims both edges of each eye, where 3D shaders leave black strips. */
    public static final String PREF_SBS_TRIM = "use_xr_sbs_edge_trim";
    /** How much of each eye's width is trimmed from each edge; enough for a 3D shader at default strength. */
    public static final int SBS_TRIM_PERCENT = 2;

    /** Whether a VR title's reduced field of view shows passthrough, not black, around it. */
    public static final String PREF_FOV_PASSTHROUGH = "use_xr_fov_passthrough";

    /** Colour a VR title's view shows passthrough through: 0 off, then green, blue, pink, black. */
    public static final String PREF_COLOUR_KEY = "xr_colour_key";
    public static final String PREF_COLOUR_KEY_TOLERANCE = "xr_colour_key_tolerance";
    public static final int COLOUR_KEY_BLACK = 4;

    // Defaults for everything the XR and motion control menus can change. They live here
    // because both the menus and this activity have to agree on what an untouched setting
    // means; when they did not, the menu showed key emulation off while the session ran it
    // on. A shortcut that has been given its own answer overrides these.
    public static final boolean DEFAULT_CURVED_SCREEN = false;
    public static final boolean DEFAULT_PASSTHROUGH = true;
    public static final boolean DEFAULT_GAMEPAD = false;
    public static final boolean DEFAULT_RADIAL_TO_SQUARE = false;
    public static final boolean DEFAULT_RUMBLE_PASSTHROUGH = false;
    public static final boolean DEFAULT_THUMBREST_DPAD = false;
    public static final boolean DEFAULT_THUMBREST_MOUSE_CENTRE = false;
    public static final boolean DEFAULT_KEYS = true;
    public static final boolean DEFAULT_MOUSE = true;
    public static final boolean DEFAULT_MOUSE_LEFT_HANDED = false;
    public static final boolean DEFAULT_MOUSE_LIGHTGUN = false;
    public static final boolean DEFAULT_LIGHTGUN_HAPTIC = false;
    public static final boolean DEFAULT_MOUSE_RELATIVE = false;
    public static final boolean DEFAULT_POINTER_SMOOTHING = false;
    public static final boolean DEFAULT_SHOW_FPS = false;
    public static final boolean DEFAULT_SBS_STRETCH = false;
    public static final boolean DEFAULT_SBS_TRIM = false;
    public static final boolean DEFAULT_FOV_PASSTHROUGH = false;
    public static final int DEFAULT_COLOUR_KEY = 0;
    public static final int DEFAULT_COLOUR_KEY_TOLERANCE = 50;
    public static final boolean DEFAULT_WHEEL = false;
    public static final int DEFAULT_HEAD_TURN_SENSITIVITY = 100;
    public static final String PREF_HEAD_TURN_SENSITIVITY = "xr_head_turn_sensitivity";

    /**
     * Every setting the XR menu can pin to a game. Listed so "Reset to default" can drop the
     * lot and let the game inherit the app-wide values again; anything added to the menu
     * belongs here too, or it will survive a reset.
     */
    public static final String[] SESSION_KEYS = {
            "use_cs", "use_pt", "use_xr_gamepad", "xr_gamepad_radial_to_square",
            "use_xr_rumble_passthrough", "use_xr_thumbrest_dpad", "use_xr_thumbrest_mouse_centre", "use_xr_keys", "use_xr_mouse", "use_xr_leftHanded",
            "use_xr_lightgun", "use_xr_lightgun_haptic", "use_xr_relative_mouse", "use_xr_smoothing", "use_xr_wheel",
            PREF_SHOW_FPS, PREF_SBS_STRETCH, PREF_SBS_TRIM, PREF_FOV_PASSTHROUGH, PREF_COLOUR_KEY, PREF_COLOUR_KEY_TOLERANCE, PREF_SCREEN_DISTANCE, PREF_HEAD_TURN_SENSITIVITY,XrEnvironment.PREF_KEY, XrEnvironment.ENABLED_KEY,
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
        thumbrestDpad = SessionSettings.getBoolean(this, "use_xr_thumbrest_dpad", DEFAULT_THUMBREST_DPAD);
        thumbrestMouseCentre = SessionSettings.getBoolean(this, "use_xr_thumbrest_mouse_centre", DEFAULT_THUMBREST_MOUSE_CENTRE);
        keysEmulation = SessionSettings.getBoolean(this, "use_xr_keys", DEFAULT_KEYS);
        mouseEmulation = SessionSettings.getBoolean(this, "use_xr_mouse", DEFAULT_MOUSE);
        mouseLeftHanded = SessionSettings.getBoolean(this, "use_xr_leftHanded", DEFAULT_MOUSE_LEFT_HANDED);
        mouseLightgun = SessionSettings.getBoolean(this, "use_xr_lightgun", DEFAULT_MOUSE_LIGHTGUN);
        lightgunHaptic = SessionSettings.getBoolean(this, "use_xr_lightgun_haptic", DEFAULT_LIGHTGUN_HAPTIC);
        mouseRelative = SessionSettings.getBoolean(this, "use_xr_relative_mouse", DEFAULT_MOUSE_RELATIVE);
        // Proton 10/11 need relative mouse, so it is on unless this game was given its own answer.
        if (getWineInfo() != null && getWineInfo().requiresRelativeMouse() && !SessionSettings.isOverridden("use_xr_relative_mouse")) mouseRelative = true;
        pointerSmoothing = SessionSettings.getBoolean(this, "use_xr_smoothing", DEFAULT_POINTER_SMOOTHING);
        wheelEmulation = SessionSettings.getBoolean(this, "use_xr_wheel", DEFAULT_WHEEL);
        showFPS = SessionSettings.getBoolean(this, PREF_SHOW_FPS, DEFAULT_SHOW_FPS);
        sbsStretch = SessionSettings.getBoolean(this, PREF_SBS_STRETCH, DEFAULT_SBS_STRETCH);
        sbsTrim = SessionSettings.getBoolean(this, PREF_SBS_TRIM, DEFAULT_SBS_TRIM);
        fovPassthrough = SessionSettings.getBoolean(this, PREF_FOV_PASSTHROUGH, DEFAULT_FOV_PASSTHROUGH);
        colourKey = Math.max(0, Math.min(SessionSettings.getInt(this, PREF_COLOUR_KEY, DEFAULT_COLOUR_KEY), COLOUR_KEY_BLACK));
        colourKeyTolerance = Math.max(10, Math.min(SessionSettings.getInt(this, PREF_COLOUR_KEY_TOLERANCE, DEFAULT_COLOUR_KEY_TOLERANCE), 100));
        lastDistance = SessionSettings.getFloat(this, PREF_SCREEN_DISTANCE, DEFAULT_DISTANCE);
        headTurnSensitivity = Math.max(50, Math.min(SessionSettings.getInt(this, PREF_HEAD_TURN_SENSITIVITY, DEFAULT_HEAD_TURN_SENSITIVITY), 150));
        if (mouseLightgun) mouseRelative = false;
        setRelativeMouseMovement(mouseRelative);

        nativeSetUsePT(isPassthrough);
        nativeSetCurvedScreen(curvedScreen);
        nativeSetSharpening(sharpening);
        nativeSetEdgeGlow(edgeGlow);
        nativeSetSbsTrim(sbsTrim ? SBS_TRIM_PERCENT : 0);
        nativeSetFovScale(getPcvrFovScale(), getPcvrFovScaleY());
        nativeSetFovPassthrough(fovPassthrough);
        nativeSetColourKey(colourKey, getColourKeyThreshold());
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
    public void onPause() {
        // Pausing stops the render thread, and the OpenXR session has to be ended on it
        if (isFinishing()) shutdownXr();
        super.onPause();
    }

    @Override
    public synchronized void onDestroy() {
        super.onDestroy();
        closeSession();
    }

    @Override
    public void exitApp() {
        shutdownXr();
        super.exitApp();
    }

    public synchronized void closeSession() {
        if (closing) return;
        closing = true;
        LaunchReport.onSessionEnd();
        xrInput.unload();
        shutdownXr();

        Intent intent = getBaseContext().getPackageManager()
                .getLaunchIntentForPackage(getBaseContext().getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }

        // The XServer and Wine environment in this process is not built to start a second time
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    /**
     * Ends the OpenXR session and instance on the render thread, whose GL context owns the
     * swapchains, so the runtime sees the app leave instead of its process vanishing mid-frame.
     * Waits a bounded time: a render thread that is already paused never runs the request.
     */
    private void shutdownXr() {
        if (xrShutDown || getXServerView() == null) return;
        CountDownLatch done = new CountDownLatch(1);
        getXServerView().queueEvent(() -> {
            nativeShutdown();
            xrShutDown = true;
            done.countDown();
        });
        try {
            done.await(1500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {}
    }

    public static boolean isShutDown() {
        return xrShutDown;
    }

    /** Called each frame: leaves the way the Exit menu item does when the runtime asks the app to quit. */
    public void checkRuntimeExit() {
        if (!exitRequestHandled && nativeIsExitRequested()) {
            exitRequestHandled = true;
            runOnUiThread(this::exitApp);
        }
    }

    public static XrActivity getInstance() {
        return instance;
    }

    public static boolean getImmersive() {
        return isImmersive && XrContentDialog.getFrontInstance() == null;
    }

    /**
     * The rate the game is producing frames at, which is not the rate the headset composites
     * at. A VR title is read from its own frame sync, anything else from how often it redraws
     * its window; either is counted whether or not the reading is being shown anywhere.
     */
    private static int[] recommendedEyeSize = {0, 0};

    /** Caches the headset's recommended eye size; only answers once XR is initialised. */
    public void updateRecommendedEyeSize() {
        int[] size = nativeGetRecommendedEyeSize();
        if (size != null && size.length == 2 && size[0] > 0 && size[1] > 0) recommendedEyeSize = size;
    }

    /** Per-eye size for direct PC VR: the headset recommendation at the shortcut's render scale, or "" if unknown. */
    public String getDirectEyeSize() {
        if (recommendedEyeSize[0] <= 0) return "";
        int scale = getPcvrRenderScale();
        int width = Math.round(recommendedEyeSize[0] * scale / 100.0f) & ~1;
        int height = Math.round(recommendedEyeSize[1] * scale / 100.0f) & ~1;
        return width + "x" + height;
    }

    public int getLastFPS() {
        // Under direct frames the game window is not drawn, so its redraws say nothing
        if (XrRenderer.isDirectActive()) return Math.round(nativeGetDirectFps());
        return isVR && XrRenderer.vrWindowOnTop ? XrRenderer.getLastFPS() : XrRenderer.getGuestFPS();
    }

    public static boolean getAER() {
        return isAER && XrContentDialog.getFrontInstance() == null;
    }
    public static boolean getSBS() {
        if (isVR && !XrRenderer.vrWindowOnTop) {
            return false;
        }
        // Direct PC VR frames are already per eye, so splitting the screen again would double them
        if (XrRenderer.isDirectActive()) {
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
        return isEnabled && XrDevice.isSupported();
    }

    public void callMenuAction(int item) {
        switch (item) {
            case R.id.main_menu_keyboard:
                new XrKeyboard(instance).show();
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
            case R.id.main_menu_opentrack:
                // OpenTrack lives in the tray, so the helper clicks its tray icon to show the window (restarting it if closed)
                isImmersive = false;
                isSBS = false;
                getWinHandler().exec(ModdingUtils.getTrayToggleForTrackIR());
                break;
        }
    }

    public static void openIntent(Activity context, int containerId, String path) {
        // Create the launch intent
        Intent intent = new Intent(context, XrDevice.getRuntime());
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
    public native void nativeShutdown();
    public native boolean nativeIsExitRequested();
    public native void bindFramebuffer();
    public native int getWidth();
    public native int getHeight();
    public native boolean initFrame(boolean immersive, boolean sbs, boolean sbsStretch, boolean aer, float distance);
    public native void bindFBO(int index);
    public native void endFrame();
    public native boolean beginOverlay();

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
    public native void nativeSetSbsTrim(int percent);
    public native void nativeSetFovScale(int percent, int percentY);
    public native void nativeSetFovPassthrough(boolean enabled);
    public native void nativeSetColourKey(int mode, float threshold);

    /** The tolerance as the shaders use it: hue distance for a colour, brightness for black. */
    public static float getColourKeyThreshold() {
        float t = colourKeyTolerance / 100.0f;
        return colourKey == COLOUR_KEY_BLACK ? 0.02f + t * 0.25f : 0.02f + t * 0.13f;
    }
    public native void nativeSetPointerSmoothing(boolean enabled);
    public native boolean nativeIsEnvironmentSupported();
    public native void nativeSetEnvironment(Bitmap bitmap);
    public native void nativeSetEnvironmentEnabled(boolean enabled);
    public native boolean nativeIsSharpeningSupported();
    public native void nativeSetUseVR(boolean enabled);
    public native void nativeSetVRApp(boolean enabled);
    public native boolean nativeIsDirectActive();
    public native float nativeGetDirectFps();
    public native int[] nativeGetRecommendedEyeSize();
    public native void nativeSetFramesync(int r, int g, int b, int a);
    public native void sendManufacturer(String manufacturer);

    // XrAPI
    public native void addLocateSpace(int a, int b);
    public native void clearLocateSpaces();
    public native float[] getPose(int a, int b);
    public native float[] getPoseVelocity(int a, int b);
    public native float getDisplayRefreshRate();
    public native void increaseReferenceSpacesOffset(float x, float y, float z);
    public native void updateActionSpace(int space, int type, int grip, float x, float y, float z,
                                         float qx, float qy, float qz, float qw);
    public native void updateReferenceSpace(int space, int type, float x, float y, float z,
                                            float qx, float qy, float qz, float qw);
}
