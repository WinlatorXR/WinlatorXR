package com.winlator.cmod.container;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.widget.TextView;

import com.winlator.cmod.R;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.GuestScriptRunner;
import com.winlator.cmod.store.LudashiLaunchBridge;
import com.winlator.cmod.store.SteamDatabase;
import com.winlator.xr.utils.PcvrRuntime;
import com.winlator.xr.utils.VrGameScanner;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

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
        createForExecutable(activity, exeFile, null, onCreated);
    }

    /** @param iconFile a square PNG to copy in as the shortcut's icon, or null for the generic one */
    public static void createForExecutable(Activity activity, File exeFile, File iconFile, Runnable onCreated) {
        createForExecutable(activity, exeFile, iconFile, VrGameScanner.UNKNOWN, onCreated);
    }

    /** @param vrSupport a SteamDatabase.VR_* value, or VrGameScanner.UNKNOWN to scan the game; VR only turns on PC VR, optional VR asks */
    public static void createForExecutable(Activity activity, File exeFile, File iconFile, int vrSupport, Runnable onCreated) {
        createForExecutable(activity, exeFile, FileUtils.getBasename(exeFile.getName()), iconFile, vrSupport, onCreated);
    }

    /** @param gameName what the game is called where the exe's own name is not it, offered as the shortcut's name */
    public static void createForExecutable(Activity activity, File exeFile, String gameName, File iconFile, int vrSupport, Runnable onCreated) {

        List<Container> containers = new ContainerManager(activity).getContainers();
        if (containers == null || containers.isEmpty()) {
            ContentDialog.alert(activity, R.string.shortcut_needs_a_container, null);
            return;
        }

        if (containers.size() == 1) {
            final Container only = containers.get(0);
            // A container that already has this game says so instead, which asks the same
            // question and more besides, so the two are never both shown.
            if (!existingShortcutsFor(activity, only, exeFile).isEmpty()) {
                create(activity, only, exeFile, gameName, iconFile, vrSupport, onCreated);
                return;
            }
            ContentDialog.confirm(activity, activity.getString(R.string.shortcut_will_be_created,
                    gameName, only.getName()), () -> create(activity, only, exeFile, gameName, iconFile, vrSupport, onCreated));
            return;
        }

        String[] names = new String[containers.size()];
        for (int i = 0; i < containers.size(); i++) names[i] = containers.get(i).getName();

        ContentDialog.showSingleChoiceList(activity,
                activity.getString(R.string.shortcut_choose_container, gameName), names,
                which -> create(activity, containers.get(which), exeFile, gameName, iconFile, vrSupport, onCreated));
    }

    /**
     * Writes the desktop entry, and says what happened.
     *
     * A container that already has a shortcut for this program is asked about first. The same
     * game in two containers is a normal thing to want, and so is a second shortcut in one of
     * them -- one set up differently for a different way of playing -- so the answer is the
     * user's rather than something to refuse or to do silently.
     */
    public static void create(Activity activity, Container container, File exeFile, String gameName, File iconFile, int vrSupport, Runnable onCreated) {
        List<Shortcut> existing = existingShortcutsFor(activity, container, exeFile);
        if (!existing.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (Shortcut shortcut : existing) names.append("\n• ").append(shortcut.name);

            ContentDialog dialog = new ContentDialog(activity);
            dialog.setTitle(gameName);
            dialog.setMessage(activity.getString(R.string.shortcut_already_exists,
                    container.getName(), names.toString()));
            ((TextView)dialog.findViewById(R.id.BTConfirm)).setText(R.string.shortcut_create_another);
            dialog.setOnConfirmCallback(() -> writeAndReport(activity, container, exeFile, gameName, iconFile, vrSupport, onCreated));
            dialog.show();
            return;
        }

        writeAndReport(activity, container, exeFile, gameName, iconFile, vrSupport, onCreated);
    }

    /**
     * The shortcuts in a container that already run this exact program.
     *
     * Matched on what a shortcut runs rather than on its name: a shortcut can be renamed to
     * anything, and two shortcuts for one game may well have been given different names on
     * purpose. A .lnk names its program only indirectly, so it is followed first.
     */
    private static List<Shortcut> existingShortcutsFor(Context context, Container container, File exeFile) {
        List<Shortcut> existing = new ArrayList<>();

        File[] files = container.getDesktopDir().listFiles((dir, name) -> name.endsWith(".desktop"));
        if (files == null) return existing;

        String exePath = exeFile.getAbsolutePath();
        for (File file : files) {
            try {
                Shortcut shortcut = new Shortcut(container, file);
                File target = GameUninstaller.resolveExecutable(context, container, shortcut);
                if (target != null && target.getAbsolutePath().equals(exePath)) existing.add(shortcut);
            }
            catch (Exception e) {
                Log.w(TAG, "Skipping unreadable shortcut " + file, e);
            }
        }
        return existing;
    }

    /** Puts the shortcut on disk, having settled that it is wanted. */
    private static void writeAndReport(Activity activity, Container container, File exeFile, String gameName, File iconFile, int vrSupport, Runnable onCreated) {
        // A game folder can hold thousands of files, so the scan stays off the UI thread
        if (vrSupport == VrGameScanner.UNKNOWN) {
            Executors.newSingleThreadExecutor().execute(() -> {
                int found = VrGameScanner.detect(activity, exeFile);
                activity.runOnUiThread(() -> writeAndReport(activity, container, exeFile, gameName, iconFile, found, onCreated));
            });
            return;
        }

        // Optional VR is asked here, and the answer carries on as VR only or none
        if (vrSupport == SteamDatabase.VR_OPTIONAL) {
            ContentDialog dialog = new ContentDialog(activity);
            dialog.setTitle(gameName);
            dialog.setMessage(activity.getString(R.string.shortcut_vr_optional, gameName));
            ((TextView)dialog.findViewById(R.id.BTConfirm)).setText(R.string.shortcut_vr_enable);
            ((TextView)dialog.findViewById(R.id.BTCancel)).setText(R.string.shortcut_vr_skip);
            dialog.setOnConfirmCallback(() -> writeAndReport(activity, container, exeFile, gameName, iconFile, SteamDatabase.VR_ONLY, onCreated));
            dialog.setOnCancelCallback(() -> writeAndReport(activity, container, exeFile, gameName, iconFile, SteamDatabase.VR_NONE, onCreated));
            dialog.show();
            return;
        }

        // The container's own C:, its mapped drives, and Z: -- the image root, which is where an
        // extracted archive lands and is the same folder in every container.
        String winePath = GuestScriptRunner.toWinPath(activity, container, exeFile);
        if (winePath == null) {
            ContentDialog.alert(activity, activity.getString(R.string.installer_out_of_reach,
                    exeFile.getName(), container.getName(), container.getDrives()), null);
            return;
        }

        // The exe's own name is often not the game's (Game-Win64-Shipping, launcher), so the name
        // is offered as the default and the user gets the last word before anything is written.
        ContentDialog.prompt(activity, R.string.shortcut_name_prompt, gameName, name -> {
            File desktopFile = write(activity, container, exeFile, winePath, iconFile, name, vrSupport == SteamDatabase.VR_ONLY);
            if (desktopFile == null) {
                ContentDialog.alert(activity, R.string.shortcut_create_failed, null);
                return;
            }

            if (onCreated != null) onCreated.run();
            ContentDialog.alert(activity, activity.getString(R.string.shortcut_created,
                    name, container.getName()), null);
        });
    }

    /** Where the uncropped artwork for an icon is kept: beside it, same name, .img. */
    public static File coverFor(File iconFile) {
        return new File(iconFile.getParentFile(), FileUtils.getBasename(iconFile.getName()) + ".img");
    }

    /**
     * The desktop entry itself, or null if it could not be written.
     *
     * A name already taken is numbered rather than overwritten: the same game can reasonably be
     * added to more than one container, and a second copy of one is not a mistake to correct.
     */
    private static File write(Context context, Container container, File exeFile, String winePath, File iconFile, String gameName, boolean pcvr) {
        File desktopDir = container.getDesktopDir();
        if (!desktopDir.exists() && !desktopDir.mkdirs()) {
            Log.e(TAG, "Could not create the desktop directory at " + desktopDir.getAbsolutePath());
            return null;
        }

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

        // Icon= names a PNG in the container's own icons folder, so the icon is copied there.
        String iconName = "";
        File iconDir = container.getIconsDir(64);
        if (iconFile != null && (iconDir.isDirectory() || iconDir.mkdirs())
                && FileUtils.copy(iconFile, new File(iconDir, iconFile.getName())))
            iconName = FileUtils.getBasename(iconFile.getName());
        // The card view shows the whole artwork when the icon came with it
        String coverPath = null;
        File cover = iconFile != null ? coverFor(iconFile) : null;
        if (cover != null && cover.isFile()) {
            File coverDir = LudashiLaunchBridge.coversDir(container);
            File target = new File(coverDir, cover.getName());
            if ((coverDir.isDirectory() || coverDir.mkdirs()) && FileUtils.copy(cover, target))
                coverPath = target.getPath();
        }
        String content = "[Desktop Entry]\n" +
                "Name=" + gameName + "\n" +
                "Exec=wine " + escapedWinePath + "\n" +
                "Type=Application\n" +
                "StartupNotify=true\n" +
                "Icon=" + iconName + "\n" +
                "StartupWMClass=" + exeFile.getName() + "\n\n" +
                "[Extra Data]\n" +
                "container_id:" + container.id + "\n" +
                (pcvr ? PcvrRuntime.EXTRA_KEY + "=1\n" : "") +
                (coverPath != null ? "customCoverArtPath=" + coverPath + "\n" : "");

        if (FileUtils.writeString(desktopFile, content)) return desktopFile;

        Log.e(TAG, "Failed to write shortcut " + desktopFile.getAbsolutePath());
        return null;
    }
}
