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

import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.TarCompressorUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class ReshadeInstaller {
    private static final String TAG = "ReshadeInstaller";
    private static final TarCompressorUtils.Type PKG_TYPE = TarCompressorUtils.Type.ZSTD;
    private static final String DIRECTX_PKG = "reshade-directx.tzst";
    private static final String PLUGINS_PKG = "reshade-plugins.tzst";
    // ReShade built with its OpenGL extension hooks handed out by pointer, since the stock build crashes patching Wine's ARM64EC opengl32.
    private static final String OPENGL_PKG = "reshade-opengl.tzst";
    private static final String[] PACKAGES = {PLUGINS_PKG, DIRECTX_PKG};
    private static final String[] CLONES = {"d3d10.dll", "d3d11.dll", "d3d12.dll"};
    private static final String DXGI_DLL = "dxgi.dll";
    private static final String D3D9_DLL = "d3d9.dll";
    private static final String OPENGL_DLL = "opengl32.dll";
    private static final String[] LEGACY_DLLS = {DXGI_DLL, D3D9_DLL};
    private static final String MANIFEST = "reshade-winlatorxr.json";
    private static final String BACKUP_SUFFIX = ".wxrbak";
    // Sizes of LEGACY_DLLS in the ReShade 6.7 package that installs without a manifest were made from.
    private static final long[] LEGACY_SIZES = {5486592, 4272128};

    private static Map<String, Long> packaged;

    private static class Manifest {
        boolean dxgi;
        boolean opengl;
        long size = -1;
        final List<String> files = new ArrayList<>();
        final List<String> dirs = new ArrayList<>();
        final List<String> backups = new ArrayList<>();
    }

    static void update(Context context, File dir, boolean enable, boolean dxgi, boolean opengl) {
        Manifest manifest = readManifest(dir);
        if (manifest == null) {
            manifest = legacyManifest(context, dir);
            if (manifest != null) writeManifest(dir, manifest);
        }

        if (!enable) {
            if (manifest != null) uninstall(dir, manifest);
            return;
        }
        // OpenGL takes the build matching the game.
        File exe = opengl ? largestExe(dir) : null;
        String source = !opengl ? DXGI_DLL : exe != null && GoldbergEmu.isPe32(exe) ? "ReShade32.dll" : "ReShade64.dll";
        if (manifest != null) {
            File dll = new File(dir, manifest.opengl ? OPENGL_DLL : DXGI_DLL);
            boolean intact = manifest.size < 0 ? dll.exists() : dll.length() == manifest.size;
            if (manifest.dxgi == dxgi && manifest.opengl == opengl && intact) {
                long size = packagedSize(context, source);
                if (size <= 0 || manifest.size == size || upgrade(context, dir, manifest, source, size)) return;
            }
            uninstall(dir, manifest);
        }
        install(context, dir, dxgi, opengl, source);
    }

    private static long packagedSize(Context context, String name) {
        if (packaged == null) {
            packaged = TarCompressorUtils.list(PKG_TYPE, context, DIRECTX_PKG);
            packaged.putAll(TarCompressorUtils.list(PKG_TYPE, context, OPENGL_PKG));
        }
        Long size = packaged.get(name);
        return size != null ? size : 0;
    }

    private static File largestExe(File dir) {
        File[] exes = dir.listFiles((parent, name) -> name.endsWith(".exe"));
        File largest = null;
        if (exes != null) {
            for (File exe : exes) {
                if (largest == null || exe.length() > largest.length()) largest = exe;
            }
        }
        return largest;
    }

    private static boolean extractDlls(Context context, File dir, Manifest manifest, String source) {
        if (manifest.opengl) {
            File target = new File(dir, OPENGL_DLL);
            return TarCompressorUtils.extract(PKG_TYPE, context, OPENGL_PKG, dir, (file, size) -> file.getName().equals(source) ? target : null) && target.isFile();
        }
        boolean success = TarCompressorUtils.extract(PKG_TYPE, context, DIRECTX_PKG, dir);
        if (success && !manifest.dxgi) {
            File dxgi = new File(dir, DXGI_DLL);
            for (String name : CLONES) success = success && FileUtils.copy(dxgi, new File(dir, name));
        }
        return success;
    }

    // Replaces only the DLLs, so the preset and settings tuned for the game are kept.
    private static boolean upgrade(Context context, File dir, Manifest manifest, String source, long size) {
        Log.i(TAG, "Updating ReShade in " + dir.getAbsolutePath());
        manifest.size = size;
        return extractDlls(context, dir, manifest, source) && writeManifest(dir, manifest);
    }

    private static void install(Context context, File dir, boolean dxgi, boolean opengl, String source) {
        Log.i(TAG, "Installing ReShade to " + dir.getAbsolutePath());
        Manifest manifest = new Manifest();
        manifest.dxgi = dxgi;
        manifest.opengl = opengl;
        for (String pkg : PACKAGES) {
            if (opengl && pkg.equals(DIRECTX_PKG)) continue;
            for (Map.Entry<String, Long> entry : TarCompressorUtils.list(PKG_TYPE, context, pkg).entrySet()) {
                String name = entry.getKey();
                if (name.endsWith("/")) {
                    if (!new File(dir, name).isDirectory()) manifest.dirs.add(name);
                    continue;
                }
                if (pkg.equals(DIRECTX_PKG) && name.equals(DXGI_DLL)) manifest.size = entry.getValue();
                if (!claim(dir, name, manifest)) {
                    restoreBackups(dir, manifest);
                    return;
                }
            }
        }
        if (opengl) manifest.size = packagedSize(context, source);
        String[] extras = opengl ? new String[]{OPENGL_DLL} : dxgi ? new String[0] : CLONES;
        for (String name : extras) {
            if (!claim(dir, name, manifest)) {
                restoreBackups(dir, manifest);
                return;
            }
        }
        if (!writeManifest(dir, manifest)) {
            Log.e(TAG, "Failed to write manifest in " + dir.getAbsolutePath());
            restoreBackups(dir, manifest);
            return;
        }

        boolean success = TarCompressorUtils.extract(PKG_TYPE, context, PLUGINS_PKG, dir) && extractDlls(context, dir, manifest, source);
        if (!success) {
            Log.e(TAG, "Failed to install ReShade to " + dir.getAbsolutePath());
            uninstall(dir, manifest);
        }
    }

    private static void uninstall(File dir, Manifest manifest) {
        Log.i(TAG, "Removing ReShade from " + dir.getAbsolutePath());
        restoreBackups(dir, manifest);
        for (String name : manifest.files) {
            if (!manifest.backups.contains(name)) new File(dir, name).delete();
        }
        for (int i = manifest.dirs.size() - 1; i >= 0; i--) {
            File subdir = new File(dir, manifest.dirs.get(i));
            if (subdir.isDirectory() && FileUtils.isEmpty(subdir)) subdir.delete();
        }
        new File(dir, MANIFEST).delete();
    }

    private static void restoreBackups(File dir, Manifest manifest) {
        for (String name : manifest.backups) {
            File backup = new File(dir, name + BACKUP_SUFFIX);
            if (backup.exists()) backup.renameTo(new File(dir, name));
        }
    }

    // Moves any existing file aside so it can be restored on uninstall.
    private static boolean claim(File dir, String name, Manifest manifest) {
        if (manifest.files.contains(name)) return true;
        File file = new File(dir, name);
        File backup = new File(dir, name + BACKUP_SUFFIX);
        if (backup.exists()) {
            manifest.backups.add(name);
        } else if (file.exists()) {
            if (!file.renameTo(backup)) {
                Log.e(TAG, "Failed to back up " + file.getAbsolutePath());
                return false;
            }
            manifest.backups.add(name);
        }
        manifest.files.add(name);
        return true;
    }

    // Installs made before the manifest existed are recognised by their file set.
    private static Manifest legacyManifest(Context context, File dir) {
        for (String name : LEGACY_DLLS) {
            if (!new File(dir, name).isFile()) return null;
        }
        Map<String, Long> directx = TarCompressorUtils.list(PKG_TYPE, context, DIRECTX_PKG);
        for (int i = 0; i < LEGACY_DLLS.length; i++) {
            Long size = directx.get(LEGACY_DLLS[i]);
            long length = new File(dir, LEGACY_DLLS[i]).length();
            if (size == null || (length != size && length != LEGACY_SIZES[i])) return null;
        }
        Manifest manifest = new Manifest();
        for (String pkg : PACKAGES) {
            Map<String, Long> entries = pkg.equals(DIRECTX_PKG) ? directx : TarCompressorUtils.list(PKG_TYPE, context, pkg);
            for (String name : entries.keySet()) {
                if (name.endsWith("/")) {
                    manifest.dirs.add(name);
                } else if (new File(dir, name).exists()) {
                    manifest.files.add(name);
                }
            }
        }
        manifest.dxgi = true;
        manifest.size = new File(dir, DXGI_DLL).length();
        for (String name : CLONES) {
            File clone = new File(dir, name);
            if (clone.isFile() && clone.length() == manifest.size) {
                manifest.files.add(name);
                manifest.dxgi = false;
            }
        }
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                String name = child.getName();
                if (name.endsWith(BACKUP_SUFFIX)) manifest.backups.add(name.substring(0, name.length() - BACKUP_SUFFIX.length()));
            }
        }
        Log.i(TAG, "Adopted legacy ReShade install in " + dir.getAbsolutePath());
        return manifest;
    }

    private static Manifest readManifest(File dir) {
        File file = new File(dir, MANIFEST);
        if (!file.isFile()) return null;
        try {
            JSONObject json = new JSONObject(FileUtils.readString(file));
            Manifest manifest = new Manifest();
            manifest.dxgi = json.optBoolean("dxgi");
            manifest.opengl = json.optBoolean("opengl");
            manifest.size = json.optLong("size", -1);
            readList(json.optJSONArray("files"), manifest.files);
            readList(json.optJSONArray("dirs"), manifest.dirs);
            readList(json.optJSONArray("backups"), manifest.backups);
            return manifest;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static void readList(JSONArray array, List<String> out) {
        if (array == null) return;
        for (int i = 0; i < array.length(); i++) out.add(array.optString(i));
    }

    private static boolean writeManifest(File dir, Manifest manifest) {
        try {
            JSONObject json = new JSONObject();
            json.put("dxgi", manifest.dxgi);
            if (manifest.opengl) json.put("opengl", true);
            if (manifest.size >= 0) json.put("size", manifest.size);
            json.put("files", new JSONArray(manifest.files));
            json.put("dirs", new JSONArray(manifest.dirs));
            json.put("backups", new JSONArray(manifest.backups));
            File tmp = new File(dir, MANIFEST + ".tmp");
            return FileUtils.writeString(tmp, json.toString()) && tmp.renameTo(new File(dir, MANIFEST));
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}
