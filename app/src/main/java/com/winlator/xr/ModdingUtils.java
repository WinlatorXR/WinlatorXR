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

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.MSLink;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;

public class ModdingUtils {

    private static final String PATH_CHARS = "qwertyuiopasdfghjklzxcvbnmQWERTYUIOPASDFGHJKLZXCVBNM01234567890.";
    private static final TarCompressorUtils.Type PKG_TYPE = TarCompressorUtils.Type.ZSTD;
    private static final String[] RESHADE_DIRECTX_CLONES = {"d3d10.dll", "d3d11.dll", "d3d12.dll"};
    private static final String RESHADE_DIRECTX_DLL = "dxgi.dll";
    private static final String RESHADE_DIRECTX_PKG = "reshade-directx.tzst";
    private static final String RESHADE_PLUGINS_PKG = "reshade-plugins.tzst";
    private static final String TRACKIR_DESTIONATION = "/sdcard/Download/Winlator";
    private static final String TRACKIR_PATH = "D:\\Winlator\\opentrack_wxr\\opentrack.exe";
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

    public static void unpackTrackIR(Context context) {
        File dst = new File(TRACKIR_DESTIONATION);
        if (TarCompressorUtils.isExtracted(PKG_TYPE, context, TRACKIR_PKG, dst) != TarCompressorUtils.Status.FULL) {
            Log.i(TAG, "Extracting TrackIR to " + dst.getAbsolutePath());
            TarCompressorUtils.extract(PKG_TYPE, context, TRACKIR_PKG, dst);
        }
    }

    public static void updateReshade(Context context, File dst, boolean useReshade, boolean forceDXGI) {
        // Update packages
        updateReshadePlugins(context, useReshade, dst);
        updateReshadeDirectX(context, useReshade, forceDXGI, dst);

        // Workaround for launchers
        File ue = locateUE(dst);
        if (ue != null) {
            updateReshadePlugins(context, useReshade, ue);
            updateReshadeDirectX(context, useReshade, forceDXGI, ue);
        }
    }

    private static void updateReshadeDirectX(Context context, boolean useReshade, boolean forceDXGI, File dst) {
        // Add or remove Reshade files
        TarCompressorUtils.Status extracted;
        extracted = TarCompressorUtils.isExtracted(PKG_TYPE, context, RESHADE_DIRECTX_PKG, dst);
        if (extracted != TarCompressorUtils.Status.PARTIAL) {
            if (useReshade) {
                Log.i(TAG, "Extracting reshade to " + dst.getAbsolutePath());
                TarCompressorUtils.extract(PKG_TYPE, context, RESHADE_DIRECTX_PKG, dst);
                if (forceDXGI || isUsingDXGI(dst)) {
                    deleteClones(dst, RESHADE_DIRECTX_CLONES);
                } else {
                    cloneFile(new File(dst, RESHADE_DIRECTX_DLL), RESHADE_DIRECTX_CLONES);
                }
            } else {
                Log.i(TAG, "Removing reshade from " + dst.getAbsolutePath());
                TarCompressorUtils.remove(PKG_TYPE, context, RESHADE_DIRECTX_PKG, dst);
                deleteClones(dst, RESHADE_DIRECTX_CLONES);
            }
        }

        // Log current status
        extracted = TarCompressorUtils.isExtracted(PKG_TYPE, context, RESHADE_DIRECTX_PKG, dst);
        Log.i(TAG, "Reshade isExtracted=" + extracted);
    }

    private static void updateReshadePlugins(Context context, boolean useReshade, File dst) {
        if (useReshade) {
            Log.i(TAG, "Extracting reshade to " + dst.getAbsolutePath());
            TarCompressorUtils.extract(PKG_TYPE, context, RESHADE_PLUGINS_PKG, dst);
        } else {
            Log.i(TAG, "Removing reshade from " + dst.getAbsolutePath());
            TarCompressorUtils.remove(PKG_TYPE, context, RESHADE_PLUGINS_PKG, dst);
        }
    }

    private static void cloneFile(File file, String[] names) {
        File dir = file.getParentFile();
        for (String name : names) {
            FileUtils.copy(file, new File(dir, name));
        }
    }

    private static void deleteClones(File dir, String[] names) {
        for (String name : names) {
            new File(dir, name).delete();
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
        return new File(imageFs.getRootDir(), ImageFs.WINEPREFIX + "/drive_" + sb);
    }

    private static boolean isUsingDXGI(File dst) {
        if (locateUE(dst) != null) {
            return true;
        } else if (locateUnity(dst)) {
            return true;
        } else {
            return false;
        }
    }

    private static File locateUE(File dst) {
        File[] files = dst.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    File result = locateUE(file);
                    if (result != null) {
                        return result;
                    }
                }
            }
        }
        return dst.getAbsolutePath().endsWith("Binaries/Win64") ? dst : null;
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
