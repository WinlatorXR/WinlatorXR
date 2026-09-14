package com.winlator.cmod.core;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import com.winlator.cmod.R;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.store.LudashiLaunchBridge;
import com.winlator.cmod.store.StoreGameInstall;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * The games sitting on Z:, whether or not anything still points at them.
 *
 * A shortcut and the game it runs are separate things, and removing the shortcut leaves the game
 * exactly where it was. For a game inside a container that is at least findable -- it is in the
 * container's own C: drive -- but the games on Z: belong to no container at all, so once the
 * shortcut is gone nothing on any screen says they are still there, and the space they take can
 * only be found by opening a file manager inside a container. This lists them instead, so the
 * Games tab can show what is on Z: and offer to point a new shortcut at any of it.
 *
 * Only the folders this app itself puts games into are looked at. Z: is the image root, and the
 * rest of it is the Linux filesystem the container runs on, which holds no games.
 */
public abstract class ZDriveGames {
    private static final String TAG = "ZDriveGames";

    /** One game's folder on Z:, and what is known about it. */
    public static class Game {
        /** The game's own folder, which is the whole of it: nothing else shares it. */
        public final File dir;

        /** Where it came from -- a store's name, or how it got there -- for the user to read. */
        public final String source;

        /** The path the game knows itself by, so it can be checked against what a shortcut runs. */
        public final String winPath;

        /** Every shortcut pointing into it, across every container. Often empty: that is the point. */
        public final List<Shortcut> shortcuts;

        /** What the folder holds, measured during the scan. */
        public final long size;

        Game(File dir, String source, String winPath, List<Shortcut> shortcuts, long size) {
            this.dir = dir;
            this.source = source;
            this.winPath = winPath;
            this.shortcuts = shortcuts;
            this.size = size;
        }

        public String getName() {
            return dir.getName();
        }
    }

    /**
     * Everything on Z:, in name order.
     *
     * Measures each folder and reads every container's shortcuts, so this is not for the UI
     * thread.
     */
    public static List<Game> scan(Context context, ContainerManager manager) {
        List<Game> games = new ArrayList<>();
        List<Target> targets = readShortcutTargets(context, manager);

        for (Map.Entry<File, String> root : gameRoots(context).entrySet()) {
            File[] folders = root.getKey().listFiles(File::isDirectory);
            if (folders == null) continue;

            for (File folder : folders) {
                // An empty folder is a game that has been removed rather than one that is there,
                // and listing it would only offer a shortcut to nothing.
                if (FileUtils.isEmpty(folder)) continue;

                games.add(new Game(folder, root.getValue(),
                        "Z:\\" + root.getKey().getName() + "\\" + folder.getName(),
                        shortcutsInside(targets, folder), GameUninstaller.folderSize(folder)));
            }
        }

        Collections.sort(games, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return games;
    }

    /** The folders on Z: that hold one game each, against what put them there. */
    private static Map<File, String> gameRoots(Context context) {
        File imageFs = ImageFs.find(context).getRootDir();

        Map<File, String> roots = new LinkedHashMap<>();
        roots.put(new File(imageFs, GameCopier.COPIED_DIR_NAME),
                context.getString(R.string.z_drive_source_copied));
        roots.put(new File(imageFs, ZipExtractor.EXTRACTED_DIR_NAME),
                context.getString(R.string.z_drive_source_extracted));
        roots.putAll(StoreGameInstall.installRoots(context));
        return roots;
    }

    /* ------------------------------------------------------------------ *
     *  Which of them already have a shortcut                              *
     * ------------------------------------------------------------------ */

    /** A shortcut and the program it runs, resolved once so every folder can be asked about it. */
    private static class Target {
        final Shortcut shortcut;
        final String path;

        Target(Shortcut shortcut, String path) {
            this.shortcut = shortcut;
            this.path = path;
        }
    }

    /**
     * Every shortcut in every container, against the program it runs.
     *
     * Read once for the whole scan rather than once per folder: reading a shortcut means parsing
     * its desktop entry and loading its icon, and asking that of each container again for every
     * game on Z: would repeat all of it dozens of times over.
     */
    private static List<Target> readShortcutTargets(Context context, ContainerManager manager) {
        List<Target> targets = new ArrayList<>();

        for (Container container : manager.getContainers()) {
            File[] files = container.getDesktopDir().listFiles((dir, name) -> name.endsWith(".desktop"));
            if (files == null) continue;

            for (File file : files) {
                try {
                    Shortcut shortcut = new Shortcut(container, file);
                    // A .lnk names the program only indirectly, and resolveExecutable follows it.
                    File exeFile = GameUninstaller.resolveExecutable(context, container, shortcut);
                    if (exeFile != null) targets.add(new Target(shortcut, exeFile.getAbsolutePath()));
                }
                catch (Exception e) {
                    Log.w(TAG, "Skipping unreadable shortcut " + file, e);
                }
            }
        }
        return targets;
    }

    private static List<Shortcut> shortcutsInside(List<Target> targets, File folder) {
        List<Shortcut> shortcuts = new ArrayList<>();
        String path = folder.getAbsolutePath() + "/";
        for (Target target : targets) if (target.path.startsWith(path)) shortcuts.add(target.shortcut);
        return shortcuts;
    }

    /**
     * The store's artwork for a game a store installed, cropped square like a store shortcut's
     * icon, or null for any other game or when none could be downloaded.
     *
     * Kept in the app's cache, since a game on Z: belongs to no container's icons; only the first
     * call downloads it. Not for the UI thread.
     */
    public static File storeIcon(Context context, Game game) {
        StoreGameInstall install = StoreGameInstall.forInstallDir(context, game.dir);
        if (install == null) return null;

        File iconFile = new File(new File(context.getCacheDir(), "z_drive_icons"), LudashiLaunchBridge.ICON_PREFIX
                + game.dir.getParentFile().getName() + "_" + game.getName() + ".png");
        if (iconFile.isFile()) return iconFile;
        return LudashiLaunchBridge.saveIcon(iconFile, install.artUserAgent(), install.artUrls(context)) ? iconFile : null;
    }

    /* ------------------------------------------------------------------ *
     *  Pointing a new shortcut at one                                    *
     * ------------------------------------------------------------------ */

    /**
     * Adds a shortcut for a game that is already on Z:.
     *
     * The files are there and there is nothing to install, so all this settles is which of the
     * programs in the folder the user meant -- one is taken as the answer, several are put to
     * them in the order they are most likely to want. Which container it runs in is asked after
     * that, by {@link ShortcutCreator}, since a game on Z: can be played from any of them.
     *
     * @param onCreated run on the UI thread once a shortcut has been written, or null
     */
    public static void createShortcut(Activity activity, Game game, Runnable onCreated) {
        PreloaderDialog preloaderDialog = new PreloaderDialog(activity);
        preloaderDialog.showOnUiThread(R.string.zip_looking_for_programs);

        // A game folder can hold thousands of files, so the walk stays off the UI thread.
        Executors.newSingleThreadExecutor().execute(() -> {
            List<File> executables = ZipExtractor.findExecutables(game.dir, ZipExtractor.Role.GAME);
            File iconFile = storeIcon(activity, game);

            activity.runOnUiThread(() -> {
                preloaderDialog.close();

                if (executables.isEmpty()) {
                    ContentDialog.alert(activity, activity.getString(R.string.z_drive_no_executable,
                            game.getName(), game.winPath), null);
                    return;
                }

                if (executables.size() == 1) {
                    ShortcutCreator.createForExecutable(activity, executables.get(0), iconFile, onCreated);
                    return;
                }

                String[] names = new String[executables.size()];
                for (int i = 0; i < executables.size(); i++)
                    names[i] = ZipExtractor.relativeName(game.dir, executables.get(i));

                ContentDialog.showSingleChoiceList(activity,
                        activity.getString(R.string.zip_choose_game_title, game.getName()), names,
                        which -> ShortcutCreator.createForExecutable(activity, executables.get(which), iconFile, onCreated));
            });
        });
    }
}
