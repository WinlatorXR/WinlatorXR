package com.winlator.cmod.store;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.container.GameUninstaller;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.core.FileUtils;
import com.winlator.xr.utils.GoldbergEmu;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-game environment and DLL overrides that Proton's launcher script sets by Steam AppID, for the
 * shortcut's environment tab to offer. We never run that script, so none of them apply by
 * themselves; the list in assets/game_overrides.json is copied from it, plus fixes found here.
 * A newer copy of the list is downloaded from REMOTE_URL when there is one.
 *
 * A fix can also carry launch arguments, the memory mapping merge tick box and which kind of
 * Goldberg package to apply.
 *
 * Nothing is applied automatically: the user picks which ones go into the shortcut's settings.
 * Reads files and steam.db, so call it off the UI thread.
 */
public final class GameOverrides {
    private static final String TAG = "GameOverrides";
    private static final String ASSET_FILE = "game_overrides.json";
    /** Maintained outside the APK, so games can be added without an app update. */
    private static final String REMOTE_URL = "https://raw.githubusercontent.com/WinlatorXR/Winlator-Contents/refs/heads/main/game_overrides.json";
    private static final String PREF_LAST_REFRESH = "game_overrides_last_refresh";
    private static final long REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L;
    private static final long RETRY_INTERVAL_MS = 10 * 60 * 1000L;
    private static long lastAttemptMs;

    public static final String GOLDBERG_LOADER = "coldclient";
    public static final String GOLDBERG_STEAM_API = "steam_api";

    public static final class Fix {
        public final String label;
        public final String source;
        public final Map<String, String> envVars = new LinkedHashMap<>();
        /** dll name to load order, for WINEDLLOVERRIDES */
        public final Map<String, String> dllOverrides = new LinkedHashMap<>();
        /** launch arguments for the shortcut's exec args, empty when the fix has none */
        public final String execArgs;
        /** ticks the shortcut's "Merge small memory mappings" box */
        public final boolean mapMergeShim;
        /** the Goldberg package kind to apply: GOLDBERG_LOADER, GOLDBERG_STEAM_API, or empty for none */
        public final String goldberg;

        Fix(String label, String source, String execArgs, boolean mapMergeShim, String goldberg) {
            this.label = label;
            this.source = source;
            this.execArgs = execArgs;
            this.mapMergeShim = mapMergeShim;
            this.goldberg = goldberg;
        }

        /** The launch arguments one option at a time, a value staying with its option ("+vr_msaa 0"). */
        public String[] execArgOptions() {
            return execArgs.isEmpty() ? new String[0] : execArgs.split("\\s+(?=[-+])");
        }
    }

    public static final class Result {
        public final String appId;
        public final String gameName;
        public final List<Fix> fixes;

        Result(String appId, String gameName, List<Fix> fixes) {
            this.appId = appId;
            this.gameName = gameName;
            this.fixes = fixes;
        }
    }

    private GameOverrides() {}

    /** The fixes listed for this shortcut's game, or null when its AppID is unknown or nothing is listed. */
    public static Result forShortcut(Context context, Shortcut shortcut) {
        try {
            String appId = findSteamAppId(context, shortcut);
            if (appId == null) return null;

            JSONArray entries = loadList(context).getJSONArray("fixes");
            List<Fix> fixes = new ArrayList<>();
            String gameName = "";
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                JSONObject games = entry.getJSONObject("games");
                if (!games.has(appId)) continue;
                if (gameName.isEmpty()) gameName = games.optString(appId);

                Fix fix = new Fix(entry.getString("label"), entry.optString("source"),
                        entry.optString("args").trim(), entry.optBoolean("mapMergeShim"), entry.optString("goldberg").trim());
                putAll(entry.optJSONObject("env"), fix.envVars);
                putAll(entry.optJSONObject("dlls"), fix.dllOverrides);
                fixes.add(fix);
            }
            return fixes.isEmpty() ? null : new Result(appId, gameName, fixes);
        } catch (Exception e) {
            Log.w(TAG, "Finding overrides for " + shortcut.name + " failed", e);
            return null;
        }
    }

    /**
     * The newest list to hand: the copy last downloaded from REMOTE_URL, unless the one in the APK
     * has a higher "version" (an app update can carry a newer list than a stale download).
     */
    private static JSONObject loadList(Context context) throws Exception {
        File cacheFile = new File(context.getFilesDir(), ASSET_FILE);
        refreshFromRemote(context, cacheFile);

        JSONObject bundled = new JSONObject(FileUtils.readString(context, ASSET_FILE));
        if (cacheFile.isFile()) {
            try {
                JSONObject cached = new JSONObject(FileUtils.readString(cacheFile));
                if (cached.optInt("version") >= bundled.optInt("version")) return cached;
            } catch (Exception e) {
                Log.w(TAG, "The downloaded list is unreadable, using the bundled one", e);
            }
        }
        return bundled;
    }

    /** Downloads the list when the last good download is older than REFRESH_INTERVAL_MS; failures keep the old copy. */
    private static synchronized void refreshFromRemote(Context context, File cacheFile) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        long now = System.currentTimeMillis();
        if (cacheFile.isFile() && Math.abs(now - preferences.getLong(PREF_LAST_REFRESH, 0)) < REFRESH_INTERVAL_MS) return;
        // Offline, every shortcut opened would otherwise wait out the timeout again
        if (Math.abs(now - lastAttemptMs) < RETRY_INTERVAL_MS) return;
        lastAttemptMs = now;

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection)new URL(REMOTE_URL).openConnection();
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(6000);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return;

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) bytes.write(buffer, 0, read);
            }
            String json = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            // Only a list that parses replaces the copy in use
            new JSONObject(json).getJSONArray("fixes");

            File tempFile = new File(cacheFile.getPath() + ".tmp");
            if (FileUtils.writeString(tempFile, json) && tempFile.renameTo(cacheFile))
                preferences.edit().putLong(PREF_LAST_REFRESH, now).apply();
        } catch (Exception e) {
            Log.w(TAG, "Downloading the list failed", e);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void putAll(JSONObject object, Map<String, String> map) throws Exception {
        if (object == null) return;
        for (Iterator<String> keys = object.keys(); keys.hasNext(); ) {
            String key = keys.next();
            map.put(key, object.getString(key));
        }
    }

    /**
     * Goldberg's AppID if one was set, else the Steam library entry the game sits in, else the
     * appmanifest beside a copied steamapps folder, else a steam_appid.txt next to the executable.
     */
    private static String findSteamAppId(Context context, Shortcut shortcut) {
        String appId = shortcut.getExtra("goldbergAppId", "").trim();
        if (appId.matches("\\d+")) return appId;

        File exeFile = GameUninstaller.resolveExecutable(context, shortcut.container, shortcut);
        File exeDir = exeFile != null ? exeFile.getParentFile() : null;
        if (exeDir == null) return null;

        try {
            String exePath = exeDir.getAbsolutePath();
            for (SteamDatabase.GameRow row : SteamDatabase.getInstance(context.getApplicationContext()).getInstalledGames()) {
                if (row.installDir != null && !row.installDir.isEmpty() && exePath.startsWith(row.installDir))
                    return String.valueOf(row.appId);
            }
        } catch (Exception e) {
            Log.w(TAG, "Looking up " + shortcut.name + " in steam.db failed", e);
        }

        appId = GoldbergEmu.detectAppIdFromAcf(exeDir);
        if (appId != null) return appId;

        for (File file : new File[]{new File(exeDir, "steam_appid.txt"), new File(exeDir, "steam_settings/steam_appid.txt")}) {
            if (!file.isFile()) continue;
            appId = FileUtils.readString(file).trim();
            if (appId.matches("\\d+")) return appId;
        }
        return null;
    }
}
