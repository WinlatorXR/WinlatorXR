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
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.WineRegistryEditor;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Installs and registers the bundled OXRWXR (OpenXR) and OpenComposite (OpenVR) runtimes for
 * shortcuts with the PC VR option. Launching another shortcut in the container undoes the
 * registration, but only when it was made here.
 */
public final class PcvrRuntime {

    public static final String EXTRA_KEY = "pcvrRuntime";
    /** Direct PC VR renders at the headset's recommended eye size, scaled by this percentage. */
    public static final String RENDER_SCALE_KEY = "pcvrRenderScale";
    public static final int DEFAULT_RENDER_SCALE = 50;
    /** Percentage of the default VR field of view, which already overscans the headset by 10%. */
    public static final String FOV_SCALE_KEY = "pcvrFovScale";
    public static final int DEFAULT_FOV_SCALE = 100;
    /** Vertical field of view for letterboxing; shortcuts without it follow the horizontal one. */
    public static final String FOV_SCALE_Y_KEY = "pcvrFovScaleY";
    /** The runtime hands its eye images to this app instead of drawing them in the preview window. */
    public static final String DIRECT_KEY = "pcvrDirectTransport";
    /** OpenXR profile the runtime reports; OpenComposite presents the matching headset. Empty keeps the runtime's ranking. */
    public static final String CONTROLLER_KEY = "pcvrController";
    /** Pushing a thumbstick counts as pressing the touchpad, for games written for the Vive wand. */
    public static final String STICK_TOUCHPAD_KEY = "pcvrStickTouchpad";
    /** OpenComposite reads this at start; builds older than the option ignore it. */
    public static final String STICK_TOUCHPAD_ENV = "OPENCOMPOSITE_STICK_PRESSES_TOUCHPAD";
    /** In the order of the pcvr_controller_entries array. */
    public static final String[] CONTROLLER_PROFILES = {"", "/valve/index_controller", "/oculus/touch_controller", "/htc/vive_controller", "/microsoft/motion_controller"};

    private static final String TAG = "PcvrRuntime";
    private static final String ASSET_DIR = "pcvr";
    private static final String OPENXR_KEY = "Software\\Khronos\\OpenXR\\1";
    private static final String ACTIVE_RUNTIME = "ActiveRuntime";
    private static final String RUNTIME_JSON = "C:\\oxrwxr\\openxr_simulator.json";
    private static final String OPENXR_KEY_32 = "Software\\Wow6432Node\\Khronos\\OpenXR\\1";
    private static final String RUNTIME_JSON_32 = "C:\\oxrwxr\\openxr_simulator32.json";
    private static final String VRPATH = "{\n  \"runtime\": [\n    \"C:\\\\OpenComposite\\\\Runtime\"\n  ]\n}\n";
    private static final String STATE_FILE = ".winlatorxr_pcvr_state";
    private static final String VERSION_FILE = ".winlatorxr_payload_version";
    private static final String BACKUP_SUFFIX = ".winlatorxr-backup";
    private static final String CONF_FILE = "Winlator/oxrwxr/conf.txt";
    private static final String CONF_DIRECT = "direct_transport";
    private static final String CONF_CONTROLLER = "controller_profile";

    /** Whether this launch set the PC VR runtime up; the game then reads every controller button through it. */
    public static boolean active;

    private PcvrRuntime() {}

    public static boolean isEnabled(Shortcut shortcut) {
        return shortcut != null && shortcut.getExtra(EXTRA_KEY, "0").equals("1");
    }

    public static boolean isDirectTransport(Shortcut shortcut) {
        return shortcut == null || !shortcut.getExtra(DIRECT_KEY, "1").equals("0");
    }

    public static boolean isStickTouchpad(Shortcut shortcut) {
        return shortcut != null && shortcut.getExtra(STICK_TOUCHPAD_KEY, "0").equals("1");
    }

    public static int getRenderScale(Shortcut shortcut) {
        return parseRenderScale(shortcut != null ? shortcut.getExtra(RENDER_SCALE_KEY, "") : "");
    }

    public static boolean isEnabled(Container container) {
        return container != null && container.getExtra(EXTRA_KEY, "0").equals("1");
    }

    public static boolean isDirectTransport(Container container) {
        return container == null || !container.getExtra(DIRECT_KEY, "1").equals("0");
    }

    public static int getRenderScale(Container container) {
        return parseRenderScale(container != null ? container.getExtra(RENDER_SCALE_KEY, "") : "");
    }

    public static String getControllerProfile(Container container) {
        return container != null ? container.getExtra(CONTROLLER_KEY, "") : "";
    }

    // The container's own VR options are only for a Proton 11 WXR container
    public static boolean isWxrContainer(Context context, Container container) {
        String wineVersion = container.getWineVersion();
        if (!DefaultVersion.isProton11(wineVersion)) return false;
        if (wineVersion.toLowerCase(Locale.ROOT).contains("wxr")) return true;
        // An installed WCP keeps its own profile.json name, which may not say wxr, so the bridge it carries counts too
        ContentsManager manager = new ContentsManager(context);
        manager.syncContents();
        ContentProfile profile = manager.getProfileByEntryName(wineVersion);
        return profile != null && new File(ContentsManager.getInstallDir(context, profile), "lib/wine/aarch64-unix/wxr_bridge.so").isFile();
    }

    private static int parseRenderScale(String value) {
        try {
            int scale = Integer.parseInt(value);
            return Math.max(10, Math.min(200, scale));
        } catch (NumberFormatException e) {
            return DEFAULT_RENDER_SCALE;
        }
    }

    public static int getFovScale(Shortcut shortcut) {
        try {
            int scale = Integer.parseInt(shortcut != null ? shortcut.getExtra(FOV_SCALE_KEY, "") : "");
            return Math.max(30, Math.min(125, scale));
        } catch (NumberFormatException e) {
            return DEFAULT_FOV_SCALE;
        }
    }

    public static int getFovScaleY(Shortcut shortcut) {
        try {
            int scale = Integer.parseInt(shortcut != null ? shortcut.getExtra(FOV_SCALE_Y_KEY, "") : "");
            return Math.max(30, Math.min(125, scale));
        } catch (NumberFormatException e) {
            return getFovScale(shortcut);
        }
    }

    public static int getFovScale(Container container) {
        try {
            int scale = Integer.parseInt(container != null ? container.getExtra(FOV_SCALE_KEY, "") : "");
            return Math.max(30, Math.min(125, scale));
        } catch (NumberFormatException e) {
            return DEFAULT_FOV_SCALE;
        }
    }

    public static int getFovScaleY(Container container) {
        try {
            int scale = Integer.parseInt(container != null ? container.getExtra(FOV_SCALE_Y_KEY, "") : "");
            return Math.max(30, Math.min(125, scale));
        } catch (NumberFormatException e) {
            return getFovScale(container);
        }
    }

    public static String getControllerProfile(Shortcut shortcut) {
        return shortcut != null ? shortcut.getExtra(CONTROLLER_KEY, "") : "";
    }

    public static void apply(Context context, Container container, boolean enabled, boolean directTransport, String controllerProfile) {
        active = enabled;
        try {
            File driveC = new File(container.getRootDir(), ".wine/drive_c");
            if (enabled) {
                enable(context, container, driveC);
                writeConf(container, directTransport, controllerProfile);
            } else if (new File(driveC, "oxrwxr/" + STATE_FILE).isFile()) {
                disable(container, driveC);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to update the PC VR runtime setup", e);
        }
    }

    private static void enable(Context context, Container container, File driveC) {
        installPayload(context, driveC);

        File state = new File(driveC, "oxrwxr/" + STATE_FILE);
        File vrpath = vrpathFile(driveC);
        String previous, previous32;
        try (WineRegistryEditor editor = new WineRegistryEditor(systemReg(container))) {
            previous = editor.getStringValue(OPENXR_KEY, ACTIVE_RUNTIME, "");
            if (!RUNTIME_JSON.equals(previous)) editor.setStringValue(OPENXR_KEY, ACTIVE_RUNTIME, RUNTIME_JSON);
            previous32 = editor.getStringValue(OPENXR_KEY_32, ACTIVE_RUNTIME, "");
            if (!RUNTIME_JSON_32.equals(previous32)) editor.setStringValue(OPENXR_KEY_32, ACTIVE_RUNTIME, RUNTIME_JSON_32);
        }

        if (!state.isFile()) {
            if (vrpath.isFile() && !VRPATH.equals(FileUtils.readString(vrpath))) {
                FileUtils.copy(vrpath, new File(vrpath.getPath() + BACKUP_SUFFIX));
            }
            FileUtils.writeString(state, previous + "\n" + previous32 + "\n");
        }

        if (!vrpath.isFile() || !VRPATH.equals(FileUtils.readString(vrpath))) {
            vrpath.getParentFile().mkdirs();
            FileUtils.writeString(vrpath, VRPATH);
        }
    }

    private static void disable(Container container, File driveC) {
        File state = new File(driveC, "oxrwxr/" + STATE_FILE);
        String[] lines = FileUtils.readString(state).split("\n");
        String previous = lines[0].trim();
        String previous32 = lines.length > 1 ? lines[1].trim() : "";

        try (WineRegistryEditor editor = new WineRegistryEditor(systemReg(container))) {
            if (RUNTIME_JSON.equals(editor.getStringValue(OPENXR_KEY, ACTIVE_RUNTIME, ""))) {
                if (previous.isEmpty()) editor.removeValue(OPENXR_KEY, ACTIVE_RUNTIME);
                else editor.setStringValue(OPENXR_KEY, ACTIVE_RUNTIME, previous);
            }
            if (RUNTIME_JSON_32.equals(editor.getStringValue(OPENXR_KEY_32, ACTIVE_RUNTIME, ""))) {
                if (previous32.isEmpty()) editor.removeValue(OPENXR_KEY_32, ACTIVE_RUNTIME);
                else editor.setStringValue(OPENXR_KEY_32, ACTIVE_RUNTIME, previous32);
            }
        }

        File vrpath = vrpathFile(driveC);
        File backup = new File(vrpath.getPath() + BACKUP_SUFFIX);
        if (backup.isFile()) {
            FileUtils.copy(backup, vrpath);
            backup.delete();
        } else if (vrpath.isFile() && VRPATH.equals(FileUtils.readString(vrpath))) {
            vrpath.delete();
        }
        state.delete();
    }

    /**
     * The runtime reads its options from D:\Winlator\oxrwxr\conf.txt, so the shortcut's setting is
     * written over that file's direct_transport and controller_profile lines; every other line is kept as it was.
     */
    private static void writeConf(Container container, boolean directTransport, String controllerProfile) {
        File driveD = null;
        for (String[] drive : container.drivesIterator()) {
            if (drive[0].equalsIgnoreCase("D")) {
                driveD = new File(drive[1]);
                break;
            }
        }
        // Without a D: drive the runtime has nowhere to read the file from
        if (driveD == null) return;

        File conf = new File(driveD, CONF_FILE);
        // The runtime matches both the key and the value without trimming, so no spaces around the =
        String line = CONF_DIRECT + "=" + (directTransport ? "1" : "0");
        // Written even when empty, so an earlier shortcut's choice does not carry over
        String controllerLine = CONF_CONTROLLER + "=" + controllerProfile;
        StringBuilder text = new StringBuilder();
        boolean written = false, controllerWritten = false;
        if (conf.isFile()) {
            for (String existing : FileUtils.readString(conf).split("\n")) {
                String key = existing.split("=", 2)[0].trim();
                if (key.equalsIgnoreCase(CONF_DIRECT)) {
                    if (written) continue;
                    text.append(line).append("\n");
                    written = true;
                } else if (key.equalsIgnoreCase(CONF_CONTROLLER)) {
                    if (controllerWritten) continue;
                    text.append(controllerLine).append("\n");
                    controllerWritten = true;
                } else if (!existing.trim().isEmpty()) {
                    text.append(existing.replace("\r", "")).append("\n");
                }
            }
        }
        if (!written) text.append(line).append("\n");
        if (!controllerWritten) text.append(controllerLine).append("\n");
        conf.getParentFile().mkdirs();
        FileUtils.writeString(conf, text.toString());
    }

    private static void installPayload(Context context, File driveC) {
        File oxrwxrDir = new File(driveC, "oxrwxr");
        File runtimeBin = new File(driveC, "OpenComposite/Runtime/bin");
        File version = new File(oxrwxrDir, VERSION_FILE);
        ContentProfile oxrwxr = newestInstalled(context, ContentProfile.ContentType.CONTENT_TYPE_OXRWXR);
        ContentProfile openComposite = newestInstalled(context, ContentProfile.ContentType.CONTENT_TYPE_OPENCOMPOSITE);
        String bundled = FileUtils.readString(context, ASSET_DIR + "/version.txt").trim();
        if (oxrwxr != null) bundled += " " + ContentsManager.getEntryName(oxrwxr);
        if (openComposite != null) bundled += " " + ContentsManager.getEntryName(openComposite);

        if (!version.isFile() || !bundled.equals(FileUtils.readString(version).trim())) {
            oxrwxrDir.mkdirs();
            runtimeBin.mkdirs();
            // A WCP that ships openxr_wxr32.dll replaces the bundled one
            FileUtils.copy(context, ASSET_DIR + "/oxrwxr/openxr_wxr32.dll", new File(oxrwxrDir, "openxr_wxr32.dll"));
            if (oxrwxr != null) {
                copyContent(context, oxrwxr, oxrwxrDir);
            } else {
                FileUtils.copy(context, ASSET_DIR + "/oxrwxr/openxr_wxr.dll", new File(oxrwxrDir, "openxr_wxr.dll"));
            }
            if (openComposite != null) {
                copyContent(context, openComposite, runtimeBin);
            } else {
                FileUtils.copy(context, ASSET_DIR + "/opencomposite/vrclient_x64.dll", new File(runtimeBin, "vrclient_x64.dll"));
                FileUtils.copy(context, ASSET_DIR + "/opencomposite/vrclient.dll", new File(runtimeBin, "vrclient.dll"));
            }
            FileUtils.writeString(version, bundled + "\n");
        }

        File json = new File(oxrwxrDir, "openxr_simulator.json");
        if (!json.isFile()) {
            FileUtils.writeString(json, "{\n    \"file_format_version\": \"1.0.0\",\n    \"runtime\": {\n        \"library_path\": \"C:/oxrwxr/openxr_wxr.dll\"\n    }\n}\n");
        }
        File json32 = new File(oxrwxrDir, "openxr_simulator32.json");
        if (!json32.isFile()) {
            FileUtils.writeString(json32, "{\n    \"file_format_version\": \"1.0.0\",\n    \"runtime\": {\n        \"library_path\": \"C:/oxrwxr/openxr_wxr32.dll\"\n    }\n}\n");
        }
        File ini = new File(runtimeBin, "opencomposite.ini");
        if (!ini.isFile()) FileUtils.copy(context, ASSET_DIR + "/opencomposite/opencomposite.ini", ini);
    }

    // Highest version of a runtime installed from a WCP, or null to use the bundled one
    private static ContentProfile newestInstalled(Context context, ContentProfile.ContentType type) {
        ContentsManager contentsManager = new ContentsManager(context);
        contentsManager.syncContents();
        ContentProfile newest = null;
        List<ContentProfile> profiles = contentsManager.getProfiles(type);
        if (profiles != null) {
            for (ContentProfile profile : profiles) {
                if (!ContentsManager.getInstallDir(context, profile).isDirectory()) continue;
                if (newest == null || profile.verCode > newest.verCode) newest = profile;
            }
        }
        return newest;
    }

    private static void copyContent(Context context, ContentProfile profile, File targetDir) {
        for (ContentProfile.ContentFile contentFile : profile.fileList) {
            File target = new File(targetDir, contentFile.target);
            File source = new File(ContentsManager.getInstallDir(context, profile), contentFile.source);
            if (!source.isFile() || !target.toPath().normalize().startsWith(targetDir.toPath().normalize())) continue;
            target.getParentFile().mkdirs();
            FileUtils.copy(source, target);
        }
    }

    private static File vrpathFile(File driveC) {
        return new File(driveC, "users/" + ImageFs.USER + "/AppData/Local/openvr/openvrpaths.vrpath");
    }

    private static File systemReg(Container container) {
        return new File(container.getRootDir(), ".wine/system.reg");
    }
}
