package com.winlator.cmod.store;

import android.content.Context;

import java.io.File;

/** Static helper that resolves the install directory for a GOG game. */
public final class GogInstallPath {

    private GogInstallPath() {}

    /**
     * Returns the install directory for a game.
     * Path: {filesDir}/imagefs/gog_games/{dirName}
     *
     * Games must live under imagefs/ because Winlator maps Z: to imagefs/
     * (not to /). Games outside imagefs are unreachable by Wine.
     */
    public static File getInstallDir(Context ctx, String dirName) {
        return new File(new File(ctx.getFilesDir(), "imagefs/gog_games"), dirName);
    }

    /**
     * The folder a recorded gog_dir_ value names, or null when there is none.
     *
     * The value is not always the same shape: the installers record the folder's name alone while
     * the download queue records the full path of the folder the executable was found in, which
     * for a game whose executable sits under bin\ or similar is a folder inside the game's own.
     * Both are taken back to the folder the store made, since deleting anything narrower would
     * leave most of the game behind.
     */
    public static File resolveInstallDir(Context ctx, String value) {
        if (value == null || value.isEmpty()) return null;
        if (!value.startsWith("/")) return getInstallDir(ctx, value);

        StoreGameInstall install = StoreGameInstall.find(ctx, new File(value));
        return install != null ? install.installDir : new File(value);
    }

    /**
     * Returns the Wine Z: path for a game exe.
     * Z: = {filesDir}/imagefs, so strip that prefix and replace / with \.
     * e.g. .../imagefs/gog_games/Game/game.exe → Z:\gog_games\Game\game.exe
     */
    public static String toWinePath(Context ctx, String absExePath) {
        String imageFsRoot = new File(ctx.getFilesDir(), "imagefs").getAbsolutePath();
        String rel = absExePath.startsWith(imageFsRoot)
                ? absExePath.substring(imageFsRoot.length())
                : absExePath;
        return "Z:" + rel.replace("/", "\\");
    }
}
