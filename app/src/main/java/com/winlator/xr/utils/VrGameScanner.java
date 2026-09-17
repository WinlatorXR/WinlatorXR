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
package com.winlator.xr.utils;

import android.content.Context;

import com.winlator.cmod.store.SteamDatabase;
import com.winlator.cmod.store.StoreGameInstall;

import java.io.File;

/**
 * Whether a game looks like it has a VR mode, as a SteamDatabase.VR_* value.
 *
 * Steam games use the store's own categories. Every other game is judged by the VR libraries in
 * its folder, which cannot tell a VR only game from one where VR is optional, so a match is
 * always VR_OPTIONAL and the user is asked. Not for the UI thread.
 */
public abstract class VrGameScanner {
    /** Passed instead of a VR_* value when the game's folder still needs scanning. */
    public static final int UNKNOWN = -1;

    private static final String[] VR_LIBRARIES = {"openvr_api.dll", "openxr_loader.dll"};
    // Unreal keeps its OpenVR and OpenXR plugins six folders below the game's root
    private static final int MAX_DEPTH = 8;
    private static final int MAX_VISITED_DIRS = 3000;

    /** For the program a shortcut will run. */
    public static int detect(Context context, File exeFile) {
        StoreGameInstall install = StoreGameInstall.find(context, exeFile);
        if (install != null) return detectDir(context, install.installDir);

        File root = exeFile.getParentFile();
        // Unreal's real executable sits in <root>/<project>/Binaries/Win64
        for (File dir = root; dir != null; dir = dir.getParentFile()) {
            if (dir.getName().equalsIgnoreCase("Binaries") && dir.getParentFile() != null
                    && dir.getParentFile().getParentFile() != null) {
                root = dir.getParentFile().getParentFile();
                break;
            }
        }
        return root != null && hasVrLibrary(root, 0, new int[1]) ? SteamDatabase.VR_OPTIONAL : SteamDatabase.VR_NONE;
    }

    /** For a game's own folder. */
    public static int detectDir(Context context, File gameDir) {
        StoreGameInstall install = StoreGameInstall.forInstallDir(context, gameDir);
        int vrSupport = install != null ? install.vrSupport(context) : UNKNOWN;
        if (vrSupport != UNKNOWN) return vrSupport;
        return hasVrLibrary(gameDir, 0, new int[1]) ? SteamDatabase.VR_OPTIONAL : SteamDatabase.VR_NONE;
    }

    private static boolean hasVrLibrary(File dir, int depth, int[] visited) {
        if (depth > MAX_DEPTH || ++visited[0] > MAX_VISITED_DIRS) return false;

        File[] children = dir.listFiles();
        if (children == null) return false;

        for (File f : children) {
            if (f.isDirectory()) continue;
            for (String name : VR_LIBRARIES)
                if (f.getName().equalsIgnoreCase(name)) return true;
        }
        for (File f : children)
            if (f.isDirectory() && hasVrLibrary(f, depth + 1, visited)) return true;
        return false;
    }
}
