package com.winlator.cmod.store;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import com.winlator.cmod.xenvironment.ImageFs;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
        GOG("gog_games", "GOG", "bh_gog_prefs", "gog_library_cache", "gameId",
                "gog_dir_", "gog_exe_", "gog_cover_"),
        EPIC("epic_games", "Epic Games", "bh_epic_prefs", "epic_cache", "appName",
                "epic_dir_", "epic_exe_"),
        AMAZON("Amazon", "Amazon Games", "bh_amazon_prefs", "amazon_library_cache", "productId",
                "amazon_dir_", "amazon_exe_"),
        /** Steam records what is installed, and its library, in steam.db instead of in preferences. */
        STEAM("steam_games", "Steam", null, null, null);

        final String folder;
        final String label;
        final String prefsName;
        /** The store's saved copy of its library, a JSON array holding what its pages are opened with. */
        final String libraryKey;
        /** The field of a library entry holding the same id the install keys are appended to. */
        final String libraryIdField;
        final String[] keys;

        Store(String folder, String label, String prefsName, String libraryKey, String libraryIdField, String... keys) {
            this.folder = folder;
            this.label = label;
            this.prefsName = prefsName;
            this.libraryKey = libraryKey;
            this.libraryIdField = libraryIdField;
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
     * The store game a folder <i>is</i>, rather than the one a file sits inside.
     *
     * {@link #find} answers for a path within a game -- the executable a shortcut runs -- which
     * is how a shortcut reaches its files. A folder listed straight off Z: is the game itself,
     * one level below the store's root, and has no such path to be found from.
     */
    public static StoreGameInstall forInstallDir(Context context, File dir) {
        if (dir == null || dir.getParentFile() == null) return null;

        File imageFs = ImageFs.find(context).getRootDir();
        for (Store store : Store.values())
            if (dir.getParentFile().equals(new File(imageFs, store.folder))) return new StoreGameInstall(store, dir);
        return null;
    }

    /**
     * Every store's folder on Z:, against the name of the store that owns it.
     *
     * {@link #find} answers the question the other way round -- which store a path belongs to --
     * which is all that removing a game needs. Listing what is on Z: starts from nothing at all,
     * so it needs the folders themselves, and which folder is whose is the stores' own business
     * rather than something for another class to keep a second copy of.
     */
    public static Map<File, String> installRoots(Context context) {
        File imageFs = ImageFs.find(context).getRootDir();
        Map<File, String> roots = new LinkedHashMap<>();
        for (Store store : Store.values()) roots.put(new File(imageFs, store.folder), store.label);
        return roots;
    }

    /**
     * Whether the store's downloads are Steam builds -- games that reach Steam through
     * steam_api.dll and expect the client to be running behind it.
     *
     * Only Steam's are. The other stores sell their own build of a game, made without Steam in
     * it, so nothing they install has any use for a stand-in for Steam.
     */
    public boolean installsSteamBuilds() {
        return store == Store.STEAM;
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
     * same folder carries an id of its own, so this clears every id that points inside rather
     * than only the one the shortcut was for.
     */
    private void forgetRecordedKeys(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(store.prefsName, Context.MODE_PRIVATE);
        List<String> ids = recordedIds(prefs);
        if (ids.isEmpty()) return;

        SharedPreferences.Editor editor = prefs.edit();
        for (String id : ids) for (String key : store.keys) editor.remove(key + id);
        editor.apply();

        Log.d(TAG, "Forgot " + ids.size() + " " + store.label + " install(s) under " + installDir);
    }

    /** Every id the store recorded a path inside the folder for: the game, and any DLC with it. */
    private List<String> recordedIds(SharedPreferences prefs) {
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
        return ids;
    }

    private void forgetSteamInstall(Context context) {
        SteamDatabase database = SteamDatabase.getInstance(context);

        for (SteamDatabase.GameRow game : database.getInstalledGames()) {
            if (game.installDir == null || game.installDir.isEmpty()) continue;
            if (isInside(new File(game.installDir))) database.markUninstalled(game.appId);
        }
    }

    /* ------------------------------------------------------------------ *
     *  The store's own page for it                                        *
     * ------------------------------------------------------------------ */

    /**
     * Whether the store that installed the game still has an account signed in.
     *
     * Its page is only any use while it does: signed out, the store has no library to show the
     * game in, and nothing to update it or sync its saves with.
     */
    public boolean isSignedIn(Context context) {
        switch (store) {
            case GOG:
                return context.getSharedPreferences(store.prefsName, Context.MODE_PRIVATE)
                        .getString("access_token", null) != null;
            case EPIC:
                return EpicCredentialStore.isLoggedIn(context);
            case AMAZON:
                return AmazonCredentialStore.isLoggedIn(context);
            default:
                SteamPrefs.INSTANCE.init(context);
                return SteamPrefs.INSTANCE.isLoggedIn();
        }
    }

    /**
     * The store's page for the game, or null when the store no longer has a record of it.
     *
     * A store opens its pages from its library, which hands each one everything it shows. This
     * starts from the folder instead, so the game is found by the path the store recorded when it
     * installed it, and the rest comes from the store's saved copy of that library.
     *
     * Reads the store's records on disk, so it is not for the UI thread.
     */
    public Intent storePage(Context context) {
        if (store == Store.STEAM) return steamPage(context);

        SharedPreferences prefs = context.getSharedPreferences(store.prefsName, Context.MODE_PRIVATE);
        JSONObject entry = libraryEntry(prefs, recordedIds(prefs));
        if (entry == null) return null;

        switch (store) {
            case GOG:
                return new Intent(context, GogGameDetailActivity.class)
                        .putExtra("game_id", entry.optString("gameId"))
                        .putExtra("title", entry.optString("title"))
                        .putExtra("image_url", entry.optString("imageUrl"))
                        .putExtra("description", entry.optString("description"))
                        .putExtra("developer", entry.optString("developer"))
                        .putExtra("category", entry.optString("category"))
                        .putExtra("generation", entry.optInt("generation", 1));
            case EPIC:
                return new Intent(context, EpicGameDetailActivity.class)
                        .putExtra("app_name", entry.optString("appName"))
                        .putExtra("title", entry.optString("title"))
                        .putExtra("description", entry.optString("description"))
                        .putExtra("developer", entry.optString("developer"))
                        .putExtra("art_cover", entry.optString("artCover"))
                        .putExtra("namespace", entry.optString("namespace"))
                        .putExtra("catalog_item_id", entry.optString("catalogItemId"));
            default:
                return new Intent(context, AmazonGameDetailActivity.class)
                        .putExtra("product_id", entry.optString("productId"))
                        .putExtra("entitlement_id", entry.optString("entitlementId"))
                        .putExtra("title", entry.optString("title"))
                        .putExtra("developer", entry.optString("developer"))
                        .putExtra("publisher", entry.optString("publisher"))
                        .putExtra("art_url", entry.optString("artUrl"))
                        .putExtra("product_sku", entry.optString("productSku"));
        }
    }

    /**
     * The store's artwork for the game, best first, found the same way as its page. Empty when
     * the store has no record of it.
     *
     * Reads the store's records on disk, so it is not for the UI thread.
     */
    public String[] artUrls(Context context) {
        if (store == Store.STEAM) {
            for (SteamDatabase.GameRow game : SteamDatabase.getInstance(context).getInstalledGames()) {
                if (game.installDir == null || game.installDir.isEmpty()) continue;
                if (isInside(new File(game.installDir))) return SteamGame.Companion.fromGameRow(game).getArtworkUrls();
            }
            return new String[0];
        }

        SharedPreferences prefs = context.getSharedPreferences(store.prefsName, Context.MODE_PRIVATE);
        JSONObject entry = libraryEntry(prefs, recordedIds(prefs));
        if (entry == null) return new String[0];

        switch (store) {
            case GOG: return new String[] { entry.optString("imageUrl") };
            case EPIC: return new String[] { entry.optString("artCover"), entry.optString("artSquare") };
            default: return new String[] { entry.optString("artUrl"), entry.optString("heroUrl") };
        }
    }

    /** GOG's image CDN expects a user agent when its artwork is fetched; the others do not. */
    public String artUserAgent() {
        return store == Store.GOG ? "GOG Galaxy" : null;
    }

    /**
     * The library entry for one of the ids recorded against the folder.
     *
     * DLC installed into the same folder has an id of its own, but the library holds only what
     * has a page to open, so whichever id is found there is the game's.
     */
    private JSONObject libraryEntry(SharedPreferences prefs, List<String> ids) {
        if (ids.isEmpty()) return null;

        String json = prefs.getString(store.libraryKey, null);
        if (json == null) return null;

        try {
            JSONArray library = new JSONArray(json);
            for (int i = 0; i < library.length(); i++) {
                JSONObject entry = library.getJSONObject(i);
                if (ids.contains(entry.optString(store.libraryIdField))) return entry;
            }
        }
        catch (JSONException e) {
            Log.w(TAG, "Unreadable " + store.label + " library", e);
        }
        return null;
    }

    private Intent steamPage(Context context) {
        // Steam's pages read the store's database through its repository, and the Steam screen
        // sets that up -- and starts the connection its pages download and update over -- on the
        // way in. Coming from Z: skips that screen, so the same is done here.
        SteamRepository repository = SteamRepository.getInstance();
        repository.initialize(context);
        SteamForegroundService.Companion.start(context);

        for (SteamDatabase.GameRow game : repository.getDatabase().getInstalledGames()) {
            if (game.installDir == null || game.installDir.isEmpty()) continue;
            if (isInside(new File(game.installDir)))
                return new Intent(context, SteamGameDetailActivity.class)
                        .putExtra(SteamGameDetailActivity.EXTRA_APP_ID, game.appId);
        }
        return null;
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
