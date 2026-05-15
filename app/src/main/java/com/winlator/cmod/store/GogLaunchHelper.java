package com.winlator.cmod.store;

import android.app.Activity;

/**
 * Launch bridge for GOG games in Ludashi-plus.
 * Delegates to LudashiLaunchBridge.
 */
public final class GogLaunchHelper {

    private GogLaunchHelper() {}

    public static void addToLauncher(Activity activity, String gameName, String exePath) {
        LudashiLaunchBridge.addToLauncher(activity, gameName, exePath);
    }
}
