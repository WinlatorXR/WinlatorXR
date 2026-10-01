package com.winlator.cmod.store;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.container.GameUninstaller;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.core.FileUtils;
import com.winlator.xr.utils.GoldbergEmu;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-game environment and DLL overrides that Proton's launcher script sets by Steam AppID, for the
 * shortcut's environment tab to offer. We never run that script, so none of them apply by
 * themselves; the list in assets/game_overrides.json is copied from it, plus fixes found here.
 *
 * Nothing is applied automatically: the user picks which ones go into the shortcut's envVars.
 * Reads files and steam.db, so call it off the UI thread.
 */
public final class GameOverrides {
    private static final String TAG = "GameOverrides";
    private static final String ASSET_FILE = "game_overrides.json";

    public static final class Fix {
        public final String label;
        public final String source;
        public final Map<String, String> envVars = new LinkedHashMap<>();
        /** dll name to load order, for WINEDLLOVERRIDES */
        public final Map<String, String> dllOverrides = new LinkedHashMap<>();

        Fix(String label, String source) {
            this.label = label;
            this.source = source;
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

            JSONArray entries = new JSONObject(FileUtils.readString(context, ASSET_FILE)).getJSONArray("fixes");
            List<Fix> fixes = new ArrayList<>();
            String gameName = "";
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                JSONObject games = entry.getJSONObject("games");
                if (!games.has(appId)) continue;
                if (gameName.isEmpty()) gameName = games.optString(appId);

                Fix fix = new Fix(entry.getString("label"), entry.optString("source"));
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
