package com.winlator.cmod.container;

import android.content.Context;

import com.winlator.cmod.core.GuestScriptRunner;
import com.winlator.cmod.store.StoreGameInstall;

import java.io.File;

/**
 * Where the game behind a shortcut actually sits, in a few characters.
 *
 * The Games tab already says which container plays a shortcut, which is not the same thing as
 * where the game is: it can be in that container's own C:, on one of its mapped drives, or on Z:,
 * which every container shares. The same game reached from two of those makes two rows that read
 * identically, so each says which one it is.
 *
 * A game a store installed is named by the store rather than by the drive it landed on, since
 * "GOG" answers the question and "Z:" only half answers it.
 */
public abstract class ShortcutSource {
    /**
     * The label for a shortcut's source, or null when what it runs cannot be placed -- a shortcut
     * naming no program, or one whose program is outside every drive the container can see.
     *
     * Resolves a .lnk to the program behind it and reads what is on disk, so this belongs with
     * the pass that builds the list rather than in the middle of a scroll.
     */
    public static String labelFor(Context context, Container container, Shortcut shortcut) {
        File exeFile = GameUninstaller.resolveExecutable(context, container, shortcut);
        if (exeFile == null) return null;

        StoreGameInstall storeInstall = StoreGameInstall.find(context, exeFile);
        if (storeInstall != null) return storeInstall.storeName;

        // "D:\Games\Doom\doom.exe" -- the drive is the whole of the answer, and a program on no
        // drive at all has no answer to give.
        String winPath = GuestScriptRunner.toWinPath(context, container, exeFile);
        return winPath != null && winPath.length() >= 2 ? winPath.substring(0, 2) : null;
    }
}
