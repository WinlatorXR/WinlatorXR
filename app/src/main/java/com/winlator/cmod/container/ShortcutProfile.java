package com.winlator.cmod.container;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import com.winlator.cmod.core.WineInfo;
import com.winlator.xr.utils.Device;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * A shortcut's settings, carried between installs as a file.
 *
 * Everything a shortcut has been told about how to run its game -- its wrapper, its driver, the
 * screen size, the environment variables, and every XR, motion and screen-effect value a session
 * pinned to it -- lives in that one shortcut's Extra Data. Someone who has spent an evening
 * finding the combination that makes a game run has no way to hand it to anybody else, or to get
 * it back after reinstalling. A profile is that Extra Data, written out and readable back in.
 *
 * A profile remembers which game it came from, but only so an import can say so: the game it is
 * applied to is whichever shortcut the user picked, the same combination is often right for a
 * whole engine or publisher, and a reinstall from another store is a different name for the same
 * game. So the name is a question asked, never a gate -- and it is the profile's own name, never
 * the shortcut's, that gives way.
 *
 * What a profile does not carry is anything naming one install rather than describing how a game
 * should run, listed in {@link #NOT_SETTINGS}. Everything else a shortcut holds is a setting --
 * so a new one added anywhere in the app is in profiles from the day it exists, which is the way
 * round that fails safely.
 */
public abstract class ShortcutProfile {
    private static final String TAG = "ShortcutProfile";

    /** Marks a shortcut as running imported settings, and names where they came from. */
    public static final String MARKER = "customProfile";

    /** What the shortcut's own settings were before the first import replaced them. */
    private static final String BACKUP = "customProfileBackup";

    private static final String FORMAT = "winlatorxr-shortcut-profile";
    private static final int VERSION = 1;

    private static final String KEY_FORMAT = "format";
    private static final String KEY_VERSION = "version";
    private static final String KEY_GAME = "game";
    private static final String KEY_CONTAINER = "container";
    private static final String KEY_WINE_VERSION = "wineVersion";
    private static final String KEY_DEVICE = "device";
    private static final String KEY_SETTINGS = "settings";

    /** A profile is a few kilobytes of text; past this it is not one, whatever it is. */
    private static final int MAX_BYTES = 1024 * 1024;

    /**
     * Keys that say where this copy of a game is rather than how it should run.
     *
     * Icon and cover art are paths into this device; the uuid is how the home screen finds this
     * one shortcut; the Goldberg keys record what was scanned and patched in this install's own
     * files, and would be a lie about anybody else's.
     */
    private static final Set<String> NOT_SETTINGS = new HashSet<>(Arrays.asList(
            "uuid", "customIconPath", "customCoverArtPath",
            "goldbergScanned", "goldbergDllDirs", "goldbergApplied", "goldbergAppId",
            "goldbergHintShownCount",
            MARKER, BACKUP));

    private ShortcutProfile() {}

    /** Whether a key travels with a profile rather than belonging to one install. */
    private static boolean isSetting(String key) {
        return !NOT_SETTINGS.contains(key);
    }

    /** A shortcut's own settings -- what it has an answer for, and nothing it inherits. */
    private static JSONObject settingsOf(Shortcut shortcut) {
        JSONObject settings = new JSONObject();
        for (String key : shortcut.getExtraKeys()) {
            if (!isSetting(key)) continue;
            try {
                settings.put(key, shortcut.getExtra(key));
            }
            catch (JSONException ignored) {}
        }
        return settings;
    }

    /**
     * A shortcut's settings as they actually take effect, inherited ones included.
     *
     * The settings dialog stores nothing for a value left at its container's, so a shortcut that
     * runs beautifully can have almost no settings written on it. Exporting only those would send
     * a profile whose behaviour depends on whichever container received it -- the same file
     * playing differently on two devices, which is the one thing a profile exists to prevent. So
     * an export answers every inherited question from the container the shortcut ran in, and the
     * profile describes a way of running a game rather than a set of differences from somewhere.
     */
    private static JSONObject effectiveSettingsOf(Shortcut shortcut) {
        JSONObject settings = settingsOf(shortcut);
        Container container = shortcut.container;

        putInherited(settings, "screenSize", container.getScreenSize());
        putInherited(settings, "graphicsDriver", container.getGraphicsDriver());
        putInherited(settings, "graphicsDriverConfig", container.getGraphicsDriverConfig());
        putInherited(settings, "dxwrapper", container.getDXWrapper());
        putInherited(settings, "ddrawrapper", container.getDDrawWrapper());
        putInherited(settings, "dxwrapperConfig", container.getDXWrapperConfig());
        putInherited(settings, "audioDriver", container.getAudioDriver());
        putInherited(settings, "emulator", container.getEmulator());
        putInherited(settings, "midiSoundFont", container.getMIDISoundFont());
        putInherited(settings, "box64Preset", container.getBox64Preset());
        putInherited(settings, "rcfileId", String.valueOf(container.getRCFileId()));
        putInherited(settings, "fexcoreVersion", container.getFEXCoreVersion());
        putInherited(settings, "startupSelection", String.valueOf(container.getStartupSelection()));
        putInherited(settings, "cpuList", container.getCPUList(true));

        return settings;
    }

    private static void putInherited(JSONObject settings, String key, String value) {
        if (settings.has(key) || value == null || value.isEmpty()) return;
        try {
            settings.put(key, value);
        }
        catch (JSONException ignored) {}
    }

    /** The name of the profile a shortcut is running, or null when it is running its own. */
    public static String appliedName(Shortcut shortcut) {
        String name = shortcut.getExtra(MARKER);
        return name.isEmpty() ? null : name;
    }

    /**
     * What a profile exported from this shortcut should be called on disk.
     *
     * The wine version and the headset are in the name because the file is meant to be looked at
     * in a folder, or in a chat, by someone deciding whether it is worth importing -- and a
     * profile tuned under Proton on x86_64 is the wrong starting point for a game running under
     * ARM64EC, while one tuned on another headset is a starting point rather than an answer.
     * Opening the file to find either out is a step too many when the name can just say it.
     */
    public static String suggestedFileName(Shortcut shortcut) {
        String name = sanitize(shortcut.name);
        if (name.isEmpty()) name = "shortcut";

        String wineVersion = sanitize(shortcut.container.getWineVersion());
        if (!wineVersion.isEmpty()) name += " - " + wineVersion;

        String device = sanitize(Device.getDisplayName());
        if (!device.isEmpty()) name += " - " + device;

        return name + EXTENSION;
    }

    /** Strips what a filename cannot hold, so a game's punctuation cannot break the export. */
    private static String sanitize(String value) {
        return value == null ? "" : value.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    /* ------------------------------------------------------------------ */
    /*                              export                                */
    /* ------------------------------------------------------------------ */

    /**
     * The one folder profiles are written to and expected to be found in.
     *
     * Sits beside Winlator/Backups, Winlator/Saves and Winlator/Frontend, which is where a user
     * already goes looking for anything this app has written out, and is somewhere a file sent
     * from a PC or another headset can simply be dropped: a profile is meant to be passed around,
     * so both ends knowing the address is worth more than each export choosing its own.
     */
    public static File profilesDir() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "Winlator/WxrProfiles");
    }

    /**
     * Makes the profiles folder if it is not there, so it can be found and filled before anything
     * has ever been exported. Called at startup, once storage access is granted.
     *
     * @return whether the folder exists now.
     */
    public static boolean ensureProfilesDir() {
        File dir = profilesDir();
        if (dir.isDirectory()) return true;
        if (dir.mkdirs()) return true;
        Log.w(TAG, "Could not create " + dir.getAbsolutePath());
        return false;
    }

    /** Where exporting this shortcut would write, whether or not anything is there yet. */
    public static File exportFileFor(Shortcut shortcut) {
        return new File(profilesDir(), suggestedFileName(shortcut));
    }

    /**
     * Writes a shortcut's settings into the profiles folder.
     *
     * @return null on success, or why it failed, in words a dialog can show.
     */
    public static String export(Shortcut shortcut) {
        JSONObject root = new JSONObject();
        try {
            root.put(KEY_FORMAT, FORMAT);
            root.put(KEY_VERSION, VERSION);
            root.put(KEY_GAME, shortcut.name);
            root.put(KEY_CONTAINER, shortcut.container.getName());
            root.put(KEY_WINE_VERSION, shortcut.container.getWineVersion());
            root.put(KEY_DEVICE, Device.getDisplayName());
            root.put(KEY_SETTINGS, effectiveSettingsOf(shortcut));
        }
        catch (JSONException e) {
            return "Could not read this shortcut's settings.";
        }

        if (!ensureProfilesDir()) {
            return "Could not create " + profilesDir().getAbsolutePath()
                    + ".\n\nCheck that Winlator has permission to manage all files.";
        }

        File destination = exportFileFor(shortcut);
        // Truncating rather than appending: exporting the same shortcut twice must replace the
        // profile, not leave the tail of a longer old one after the new JSON.
        try (OutputStream out = new FileOutputStream(destination, false)) {
            out.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        catch (IOException | JSONException e) {
            Log.e(TAG, "Failed to export profile for " + shortcut.name, e);
            return "Could not write " + destination.getName() + ": " + e.getMessage();
        }
        return null;
    }

    /* ------------------------------------------------------------------ */
    /*                              import                                */
    /* ------------------------------------------------------------------ */

    /** A profile read off disk, before anything has been done with it. */
    public static final class Parsed {
        /** The shortcut the profile was exported from. */
        public final String gameName;
        /** The container that shortcut ran in, for the confirmation to mention. */
        public final String containerName;
        /** That container's wine version identifier, or "" in a profile written before this. */
        public final String wineVersion;
        /** The headset the profile was exported on, or "" in a profile written before this. */
        public final String deviceName;
        public final JSONObject settings;

        private Parsed(String gameName, String containerName, String wineVersion,
                       String deviceName, JSONObject settings) {
            this.gameName = gameName;
            this.containerName = containerName;
            this.wineVersion = wineVersion;
            this.deviceName = deviceName;
            this.settings = settings;
        }

        public int settingCount() {
            return settings.length();
        }

        /** Whether the profile came from the shortcut it is about to be applied to. */
        public boolean matches(Shortcut shortcut) {
            return normalize(gameName).equals(normalize(shortcut.name));
        }

        /** The architecture the profile was tuned under, or null if it does not say. */
        public String arch() {
            return WineInfo.archFromIdentifier(wineVersion);
        }

        /**
         * Whether this profile came from a container built for a different architecture.
         *
         * Worth saying out loud, because the settings that matter most -- the graphics driver,
         * the DX wrapper, the emulator and its preset -- are the ones least likely to mean the
         * same thing across the x86 and ARM64EC sides. Not a reason to refuse the import, for
         * the same reason the game's name is not: the person doing it may know exactly why.
         *
         * False when either side does not name an architecture, since a guess in either
         * direction would be a warning nobody can act on.
         */
        public boolean isArchMismatch(Shortcut shortcut) {
            String from = arch();
            String to = WineInfo.archFromIdentifier(shortcut.container.getWineVersion());
            return from != null && to != null && !from.equals(to);
        }

        /**
         * Whether the profile was exported on a different headset than this one.
         *
         * Worth mentioning rather than warning about: the same settings do not land the same way
         * on every headset, so a profile that crossed between two of them is a starting point
         * rather than an answer. False when the profile does not say, which is what one written
         * before the device was recorded gets.
         */
        public boolean isDifferentDevice() {
            return deviceName != null && !deviceName.isEmpty()
                    && !deviceName.equalsIgnoreCase(Device.getDisplayName());
        }

        // A game reinstalled from another store, or re-added from a .lnk, keeps its title but
        // rarely its punctuation or capitalisation, and none of that changes what it needs.
        private static String normalize(String name) {
            return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        }
    }

    /** What reading a profile produced: exactly one of these two is set. */
    public static final class ReadResult {
        public final Parsed profile;
        public final String error;

        private ReadResult(Parsed profile, String error) {
            this.profile = profile;
            this.error = error;
        }
    }

    /** The name a profile file carries, wherever it is found. */
    public static final String EXTENSION = ".wxrprofile.json";

    /** Whether a filename is a settings profile, by its name alone. */
    public static boolean isProfileFile(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(EXTENSION);
    }

    /**
     * What to call a profile that arrived as a file, with the whole of its extension taken off.
     *
     * Not FileUtils.getBasename, which drops one extension and would leave ".wxrprofile" on the
     * end of every name it was given.
     */
    public static String nameOf(String fileName) {
        if (fileName == null) return "";
        return isProfileFile(fileName)
                ? fileName.substring(0, fileName.length() - EXTENSION.length()) : fileName;
    }

    public static ReadResult read(Context context, Uri source) {
        try (InputStream in = context.getContentResolver().openInputStream(source)) {
            if (in == null) return new ReadResult(null, "Could not open the selected file.");
            return readFrom(in);
        }
        catch (IOException e) {
            Log.e(TAG, "Failed to read profile", e);
            return new ReadResult(null, "Could not read the selected file: " + e.getMessage());
        }
    }

    /** The same, for a profile already on disk -- one that came inside a mod archive, say. */
    public static ReadResult read(File source) {
        try (InputStream in = new FileInputStream(source)) {
            return readFrom(in);
        }
        catch (IOException e) {
            Log.e(TAG, "Failed to read profile " + source.getAbsolutePath(), e);
            return new ReadResult(null, "Could not read " + source.getName() + ": " + e.getMessage());
        }
    }

    private static ReadResult readFrom(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
            if (buffer.size() > MAX_BYTES) {
                return new ReadResult(null, "That file is too large to be a settings profile.");
            }
        }
        String text = new String(buffer.toByteArray(), StandardCharsets.UTF_8);

        try {
            JSONObject root = new JSONObject(text);
            if (!FORMAT.equals(root.optString(KEY_FORMAT))) {
                return new ReadResult(null, "That file is not a game settings profile.");
            }
            if (root.optInt(KEY_VERSION, VERSION) > VERSION) {
                return new ReadResult(null, "That profile was saved by a newer version of the app.");
            }
            JSONObject settings = root.optJSONObject(KEY_SETTINGS);
            if (settings == null || settings.length() == 0) {
                return new ReadResult(null, "That profile has no settings in it.");
            }
            return new ReadResult(new Parsed(root.optString(KEY_GAME),
                    root.optString(KEY_CONTAINER), root.optString(KEY_WINE_VERSION),
                    root.optString(KEY_DEVICE), settings), null);
        }
        catch (JSONException e) {
            return new ReadResult(null, "That file is not a game settings profile.");
        }
    }

    /**
     * Puts a profile's settings on a shortcut, leaving its name, icon and game alone.
     *
     * The settings the shortcut had are kept the first time, so removing the profile later can
     * put them back; a second import replaces the first profile, not that original.
     */
    public static void apply(Shortcut shortcut, Parsed profile, String label) {
        if (!shortcut.hasExtra(BACKUP)) {
            shortcut.putExtra(BACKUP, settingsOf(shortcut).toString());
        }

        for (String key : shortcut.getExtraKeys()) {
            if (isSetting(key)) shortcut.putExtra(key, null);
        }

        Iterator<String> keys = profile.settings.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            // A profile edited by hand, or written by a later version, can name a key that this
            // app does not treat as a setting. What one install owns is decided here, on the way
            // in, not by whoever wrote the file.
            if (!isSetting(key)) continue;
            shortcut.putExtra(key, profile.settings.optString(key));
        }

        shortcut.putExtra(MARKER, label);
        shortcut.saveData();
    }

    /** Drops an imported profile and puts back the settings the shortcut had before it. */
    public static void remove(Shortcut shortcut) {
        String backup = shortcut.getExtra(BACKUP);

        for (String key : shortcut.getExtraKeys()) {
            if (isSetting(key)) shortcut.putExtra(key, null);
        }

        if (!backup.isEmpty()) {
            try {
                JSONObject original = new JSONObject(backup);
                Iterator<String> keys = original.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (isSetting(key)) shortcut.putExtra(key, original.optString(key));
                }
            }
            catch (JSONException e) {
                // Nothing to put back lands the shortcut where one that never had settings of
                // its own already is: inheriting everything from its container.
                Log.e(TAG, "Unreadable settings backup on " + shortcut.name, e);
            }
        }

        shortcut.putExtra(MARKER, null);
        shortcut.putExtra(BACKUP, null);
        shortcut.saveData();
    }
}
