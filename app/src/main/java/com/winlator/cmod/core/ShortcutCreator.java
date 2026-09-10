package com.winlator.cmod.core;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import com.winlator.cmod.R;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.contentdialog.ContentDialog;

import java.io.File;
import java.util.List;

/**
 * Making a Games tab shortcut for a program already sitting on disk.
 *
 * This is what a game that was never installed needs: one copied in by hand, or one that came out
 * of an archive. There is nothing to run and nothing to register -- the files are already where
 * they belong -- so all that is left is a desktop entry pointing at the executable, in whichever
 * container the user wants to play it from.
 *
 * It lives out here rather than on the Games tab because the Downloader reaches the same place: an
 * archive added there can turn out to hold game files rather than an installer, and when it does,
 * this is what it needs.
 */
public abstract class ShortcutCreator {
    private static final String TAG = "ShortcutCreator";

    /**
     * Asks which container the shortcut should go in, then writes it, saying what happened either
     * way. The title states the shortcut is what is being made, since this is also reached from
     * screens that are otherwise about installing things.
     *
     * @param onCreated run on the UI thread once a shortcut has actually been written, or null
     */
    public static void createForExecutable(Activity activity, File exeFile, Runnable onCreated) {
        String gameName = FileUtils.getBasename(exeFile.getName());

        List<Container> containers = new ContainerManager(activity).getContainers();
        if (containers == null || containers.isEmpty()) {
            ContentDialog.alert(activity, R.string.shortcut_needs_a_container, null);
            return;
        }

        if (containers.size() == 1) {
            final Container only = containers.get(0);
            ContentDialog.confirm(activity, activity.getString(R.string.shortcut_will_be_created,
                    gameName, only.getName()), () -> create(activity, only, exeFile, onCreated));
            return;
        }

        String[] names = new String[containers.size()];
        for (int i = 0; i < containers.size(); i++) names[i] = containers.get(i).getName();

        ContentDialog.showSingleChoiceList(activity,
                activity.getString(R.string.shortcut_choose_container, gameName), names,
                which -> create(activity, containers.get(which), exeFile, onCreated));
    }

    /** Writes the desktop entry, and says what happened. */
    public static void create(Activity activity, Container container, File exeFile, Runnable onCreated) {
        // The container's own C:, its mapped drives, and Z: -- the image root, which is where an
        // extracted archive lands and is the same folder in every container.
        String winePath = GuestScriptRunner.toWinPath(activity, container, exeFile);
        if (winePath == null) {
            ContentDialog.alert(activity, activity.getString(R.string.installer_out_of_reach,
                    exeFile.getName(), container.getName(), container.getDrives()), null);
            return;
        }

        File desktopFile = write(activity, container, exeFile, winePath);
        if (desktopFile == null) {
            ContentDialog.alert(activity, R.string.shortcut_create_failed, null);
            return;
        }

        if (onCreated != null) onCreated.run();
        ContentDialog.alert(activity, activity.getString(R.string.shortcut_created,
                FileUtils.getBasename(exeFile.getName()), container.getName()), null);
    }

    /**
     * The desktop entry itself, or null if it could not be written.
     *
     * A name already taken is numbered rather than overwritten: the same game can reasonably be
     * added to more than one container, and a second copy of one is not a mistake to correct.
     */
    private static File write(Context context, Container container, File exeFile, String winePath) {
        File desktopDir = container.getDesktopDir();
        if (!desktopDir.exists() && !desktopDir.mkdirs()) {
            Log.e(TAG, "Could not create the desktop directory at " + desktopDir.getAbsolutePath());
            return null;
        }

        String gameName = FileUtils.getBasename(exeFile.getName());
        String safeName = gameName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (safeName.isEmpty()) safeName = "game";

        File desktopFile = new File(desktopDir, safeName + ".desktop");
        int suffix = 1;
        while (desktopFile.exists()) {
            desktopFile = new File(desktopDir, safeName + "_" + (suffix++) + ".desktop");
        }

        // Shortcut's own reader unescapes each backslash from four characters, so the path goes
        // in the way it expects to read it back.
        String escapedWinePath = winePath.replace("\\", "\\\\\\\\");
        String content = "[Desktop Entry]\n" +
                "Name=" + gameName + "\n" +
                "Exec=wine " + escapedWinePath + "\n" +
                "Type=Application\n" +
                "StartupNotify=true\n" +
                "Icon=\n" +
                "StartupWMClass=" + exeFile.getName() + "\n\n" +
                "[Extra Data]\n" +
                "container_id:" + container.id + "\n";

        if (FileUtils.writeString(desktopFile, content)) return desktopFile;

        Log.e(TAG, "Failed to write shortcut " + desktopFile.getAbsolutePath());
        return null;
    }
}
