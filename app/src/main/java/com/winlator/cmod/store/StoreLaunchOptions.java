package com.winlator.cmod.store;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.container.GameUninstaller;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.core.FileUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The launch arguments a game's own store gives it, for the shortcut's exec-arguments menu.
 *
 * Each store says this somewhere different: Steam in the app info the library sync stores, GOG in
 * the goggame-*.info files shipped in the game's folder, Amazon in the folder's fuel.json, and Epic
 * in the install manifest, recorded when the game was downloaded. Only entries that pass arguments
 * are listed; one that differs only by which executable it starts has nothing to add to the field.
 *
 * Reads files and steam.db, so it belongs with opening the menu rather than drawing the dialog.
 */
public final class StoreLaunchOptions {
    private static final String TAG = "StoreLaunchOptions";

    private StoreLaunchOptions() {}

    /** {label, arguments} pairs, with no two carrying the same arguments; empty when the store names none. */
    public static List<String[]> forShortcut(Context context, Shortcut shortcut) {
        List<String[]> options = new ArrayList<>();
        File exeFile = GameUninstaller.resolveExecutable(context, shortcut.container, shortcut);
        if (exeFile == null) return options;

        // A game the stores did not install can still carry its GOG or Amazon files beside it
        StoreGameInstall install = StoreGameInstall.find(context, exeFile);
        File gameDir = install != null ? install.installDir : exeFile.getParentFile();
        if (gameDir == null) return options;

        try {
            if (install != null && install.installsSteamBuilds()) addSteam(context, gameDir, options);
            addGog(gameDir, options);
            addAmazon(gameDir, options);
            addEpic(context, gameDir, options);
        } catch (Exception e) {
            Log.w(TAG, "Reading launch options for " + shortcut.name + " failed", e);
        }

        Set<String> seen = new LinkedHashSet<>();
        List<String[]> unique = new ArrayList<>();
        for (String[] option : options) if (seen.add(option[1])) unique.add(option);
        return unique;
    }

    private static void addSteam(Context context, File gameDir, List<String[]> options) {
        SteamDatabase db = SteamDatabase.getInstance(context);
        for (SteamDatabase.GameRow row : db.getInstalledGames()) {
            if (row.installDir == null || row.installDir.isEmpty()) continue;
            if (!new File(row.installDir).getAbsolutePath().equals(gameDir.getAbsolutePath())) continue;
            options.addAll(db.getLaunchOptions(row.appId));
        }
    }

    /** playTasks entries in each goggame-*.info file directly inside the game's folder. */
    private static void addGog(File gameDir, List<String[]> options) throws Exception {
        File[] infoFiles = gameDir.listFiles((dir, name) ->
                name.toLowerCase().startsWith("goggame-") && name.toLowerCase().endsWith(".info"));
        if (infoFiles == null) return;

        for (File infoFile : infoFiles) {
            JSONArray tasks = new JSONObject(FileUtils.readString(infoFile)).optJSONArray("playTasks");
            if (tasks == null) continue;
            for (int i = 0; i < tasks.length(); i++) {
                JSONObject task = tasks.optJSONObject(i);
                if (task == null || !"FileTask".equals(task.optString("type", "FileTask"))) continue;
                String arguments = task.optString("arguments").trim();
                if (!arguments.isEmpty()) options.add(new String[]{task.optString("name").trim(), arguments});
            }
        }
    }

    /** Main.Args in the fuel.json at the top of the game's folder. */
    private static void addAmazon(File gameDir, List<String[]> options) throws Exception {
        File fuelFile = new File(gameDir, "fuel.json");
        if (!fuelFile.isFile()) return;

        JSONObject main = new JSONObject(FileUtils.readString(fuelFile)).optJSONObject("Main");
        JSONArray args = main != null ? main.optJSONArray("Args") : null;
        if (args == null || args.length() == 0) return;

        StringBuilder arguments = new StringBuilder();
        for (int i = 0; i < args.length(); i++) {
            String arg = args.optString(i);
            if (arg.isEmpty()) continue;
            if (arguments.length() > 0) arguments.append(' ');
            arguments.append(arg.contains(" ") ? "\"" + arg + "\"" : arg);
        }
        if (arguments.length() > 0) options.add(new String[]{"", arguments.toString()});
    }

    /** The manifest's launch command, which is only known for games downloaded after it was recorded. */
    private static void addEpic(Context context, File gameDir, List<String[]> options) {
        String arguments = context.getSharedPreferences("bh_epic_prefs", Context.MODE_PRIVATE)
                .getString(EpicDownloadManager.LAUNCH_COMMAND_KEY + gameDir.getAbsolutePath(), "");
        if (!arguments.isEmpty()) options.add(new String[]{"", arguments});
    }
}
