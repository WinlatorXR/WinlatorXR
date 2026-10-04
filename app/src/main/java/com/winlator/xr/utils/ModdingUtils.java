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
import android.util.Log;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.MSLink;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;

public class ModdingUtils {

    private static final String PATH_CHARS = "qwertyuiopasdfghjklzxcvbnmQWERTYUIOPASDFGHJKLZXCVBNM01234567890.";
    private static final String PATH_ZDRIVE = "/data/data/com.winlator.cmod/files/imagefs/";
    private static final TarCompressorUtils.Type PKG_TYPE = TarCompressorUtils.Type.ZSTD;
    private static final String TRACKIR_DESTIONATION = "/sdcard/Download/Winlator";
    private static final String TRACKIR_PATH = "D:\\Winlator\\opentrack_wxr\\opentrack.exe";
    private static final String TRACKIR_TRAY_PATH = "D:\\Winlator\\opentrack_wxr\\opentrack_tray.exe";
    private static final String TRACKIR_PKG = "opentrack_wxr.tzst";
    private static final String TAG = "ModdingUtils";

    public static File getLocalExeFile(ImageFs imageFs, String executable, Container container) {
        int linkFollow = 0;
        File exe = getLocalFile(imageFs, executable, container);
        while (exe.getAbsolutePath().endsWith(".lnk")) {
            Log.i(TAG, "Shortcut lead to shortcut " + exe.getAbsolutePath());
            try {
                Iterable<String[]> drives = Container.drivesIterator(container.getDrives());
                exe = MSLink.getLocalFile(imageFs.getRootDir(), ImageFs.WINEPREFIX, drives, exe);
            } catch (Exception e) {
                e.printStackTrace();
            }
            linkFollow++;
            if (linkFollow > 5) {
                break;
            }
        }
        return exe;
    }

    public static String getRuntimeForTrackIR() {
        return TRACKIR_PATH;
    }

    public static String getTrayToggleForTrackIR() {
        return TRACKIR_TRAY_PATH;
    }

    public static void unpackTrackIR(Context context) {
        File dst = new File(TRACKIR_DESTIONATION);
        if (TarCompressorUtils.isExtracted(PKG_TYPE, context, TRACKIR_PKG, dst) != TarCompressorUtils.Status.FULL) {
            Log.i(TAG, "Extracting TrackIR to " + dst.getAbsolutePath());
            TarCompressorUtils.extract(PKG_TYPE, context, TRACKIR_PKG, dst);
        }
    }

    public static void updateReshade(Context context, File dst, boolean useReshade, boolean forceDXGI, boolean opengl) {
        // A folder that cannot be listed is one there is nothing to do in: a shortcut generated
        // to run a job points at cmd.exe, whose folder resolves to nothing here, and the recursion
        // below walks into whatever the game's own folder holds.
        File[] files = dst != null ? dst.listFiles() : null;
        if (files == null) return;

        boolean hasExe = false;
        for (File file : files) {
            if (file.getAbsolutePath().endsWith(".exe"))
                hasExe = true;
            if (!file.isDirectory())
                continue;
            updateReshade(context, file, useReshade, forceDXGI, opengl);
        }

        if (hasExe) {
            boolean dxgi = useReshade && !opengl && (forceDXGI || isUsingDXGI(dst));
            ReshadeInstaller.update(context, dst, useReshade, dxgi, useReshade && opengl);
        }
    }

    private static File getLocalFile(ImageFs imageFs, String executable, Container container) {
        String output = executable.substring(executable.indexOf("wine ") + 5);
        output = output.replace(":", "");
        char drive = output.charAt(0);
        if ((drive >= 'A') && (drive <= 'Z')) {
            output = (char)(drive - 'A' + 'a') + output.substring(1);
        }
        output = output.replaceAll("\\\\", "/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < output.length(); i++) {
            if (output.charAt(i) == '/' && i + 1 < output.length()) {
                if (PATH_CHARS.indexOf(output.charAt(i + 1)) < 0) {
                    continue;
                }
            }
            sb.append(output.charAt(i));
        }

        for (String[] it : Container.drivesIterator(container.getDrives())) {
            if (it[0].compareToIgnoreCase(drive + "") == 0) {
                return new File(it[1], sb.substring(2));
            }
        }
        if (sb.charAt(0) == 'z') {
            return new File(PATH_ZDRIVE, sb.substring(2));
        }
        return new File(imageFs.getRootDir(), ImageFs.WINEPREFIX + "/drive_" + sb);
    }

    private static boolean isUsingDXGI(File dst) {
        return locateUE(dst) || locateUnity(dst);
    }

    private static boolean locateUE(File dst) {
        File[] files = dst.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    if (locateUE(file)) {
                        return true;
                    }
                }
            }
        }
        return dst.getAbsolutePath().endsWith("Binaries/Win64");
    }

    private static boolean locateUnity(File dst) {
        File[] files = dst.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    boolean result = locateUnity(file);
                    if (result) {
                        return true;
                    }
                } else if (file.getAbsolutePath().endsWith("UnityEngine.dll")) {
                    return true;
                }
            }
        }
        return false;
    }
}
