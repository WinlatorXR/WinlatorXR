package com.winlator.cmod.store;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A game one of the stores downloaded, found from a path that points into it.
 *
 * The stores install under the image root rather than into a container, so a game sits on Z: and
 * every container sees it at the same path. That is what lets one download be played from several
 * containers, and it is also why removing one is not any single container's business: deleting the
 * folder takes the game away from all of them at once.
 *
 * Nothing the stores install registers an uninstaller in a prefix, so their own uninstall is
 * deleting the game's folder and forgetting the install was ever made. This is that same pair of
 * steps, reached from a shortcut rather than from the store's page.
 */
public final class StoreGameInstall {
    private static final String TAG = "StoreGameInstall";

    /**
     * Where each store installs to, and what it writes down about what it installed.
     *
     * The keys are the prefixes a game's id is appended to. The first two hold paths and are what
     * a folder is matched against; the rest are cleared along with whatever they matched.
     */
    private enum Store {
        GOG("gog_games", "GOG", "bh_gog_prefs", "gog_dir_", "gog_exe_", "gog_cover_"),
        EPIC("epic_games", "Epic Games", "bh_epic_prefs", "epic_dir_", "epic_exe_"),
        AMAZON("Amazon", "Amazon Games", "bh_amazon_prefs", "amazon_dir_", "amazon_exe_"),
        /** Steam records what is installed in steam.db instead of in preferences. */
        STEAM("steam_games", "Steam", null);

        final String folder;
        final String label;
        final String prefsName;
        final String[] keys;

        Store(String folder, String label, String prefsName, String... keys) {
            this.folder = folder;
            this.label = label;
            this.prefsName = prefsName;
            this.keys = keys;
        }
    }

    /** The store that installed the game, for telling the user where it came from. */
    public final String storeName;

    /** The game's own folder, which is what removing it deletes. */
    public final File installDir;

    private final Store store;

    private StoreGameInstall(Store store, File installDir) {
        this.store = store;
        this.storeName = store.label;
        this.installDir = installDir;
    }

    /**
     * The store game the given file belongs to, or null when it belongs to none.
     *
     * What comes back is the game's own folder directly under the store's root. The root itself
     * holds every game that store installed, so a file naming nothing below it -- the root, or a
     * file sitting loose in it -- belongs to no one game and is left alone.
     */
    public static StoreGameInstall find(Context context, File file) {
        if (file == null) return null;

        String path = file.getAbsolutePath();
        File imageFs = ImageFs.find(context).getRootDir();

        for (Store store : Store.values()) {
            String root = new File(imageFs, store.folder).getAbsolutePath() + "/";
            if (!path.startsWith(root)) continue;

            int end = path.indexOf('/', root.length());
            return end == -1 ? null : new StoreGameInstall(store, new File(path.substring(0, end)));
        }
        return null;
    }

    /**
     * Clears what the store wrote down about the game, so its page shows it as installable again
     * rather than offering to launch files that are gone.
     *
     * Reads and writes the store's records on disk, so it is not for the UI thread.
     */
    public void forget(Context context) {
        if (store == Store.STEAM) forgetSteamInstall(context);
        else forgetRecordedKeys(context);
    }

    /**
     * Removes every key group whose recorded path is inside the folder.
     *
     * A game is identified by whatever the store appended to its keys, and DLC installed into the
     * same folder carries an id of its own, so this collects every id that points inside rather
     * than only the one the shortcut was for.
     */
    private void forgetRecordedKeys(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(store.prefsName, Context.MODE_PRIVATE);
        File storeRoot = installDir.getParentFile();

        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!(entry.getValue() instanceof String)) continue;

            // The first two keys are the ones holding paths: where the game was installed, and
            // the executable picked out of it.
            for (int i = 0; i < 2; i++) {
                if (!entry.getKey().startsWith(store.keys[i])) continue;

                String id = entry.getKey().substring(store.keys[i].length());
                if (!ids.contains(id) && isInside(resolve(storeRoot, (String) entry.getValue()))) ids.add(id);
            }
        }
        if (ids.isEmpty()) return;

        SharedPreferences.Editor editor = prefs.edit();
        for (String id : ids) for (String key : store.keys) editor.remove(key + id);
        editor.apply();

        Log.d(TAG, "Forgot " + ids.size() + " " + store.label + " install(s) under " + installDir);
    }

    private void forgetSteamInstall(Context context) {
        SteamDatabase database = SteamDatabase.getInstance(context);

        for (SteamDatabase.GameRow game : database.getInstalledGames()) {
            if (game.installDir == null || game.installDir.isEmpty()) continue;
            if (isInside(new File(game.installDir))) database.markUninstalled(game.appId);
        }
    }

    /**
     * The file a recorded value names. Most of them are full paths, but GOG has recorded the
     * folder name alone since before the download queue started writing the path out, and
     * installs of both kinds are still around.
     */
    private static File resolve(File storeRoot, String value) {
        if (value == null || value.isEmpty()) return null;
        return value.startsWith("/") ? new File(value) : new File(storeRoot, value);
    }

    private boolean isInside(File file) {
        if (file == null) return false;

        String dir = installDir.getAbsolutePath();
        String path = file.getAbsolutePath();
        return path.equals(dir) || path.startsWith(dir + "/");
    }
}
