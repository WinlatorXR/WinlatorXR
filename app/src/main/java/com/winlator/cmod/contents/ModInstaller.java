package com.winlator.cmod.contents;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.widget.TextView;

import com.winlator.cmod.R;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.container.ShortcutProfile;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.container.GameUninstaller;
import com.winlator.cmod.core.GuestScriptRunner;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.core.ZipExtractor;
import com.winlator.cmod.core.ZipImport;
import com.winlator.cmod.store.StoreGameInstall;
import com.winlator.xr.utils.GoldbergEmu;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

/**
 * Unpacking a mod into a game that is already installed.
 *
 * A mod is not a game and not an installer: its files belong inside a game that is already on
 * disk, mixed in with what is there rather than put somewhere of their own, and the whole point
 * of it is to replace some of the files it lands on. That is the opposite of what
 * {@link ZipImport} does -- which unpacks into a folder of its own and clears it first -- so it
 * is a separate flow rather than another role for that one.
 *
 * Replacing files cannot be undone from here: nothing is backed up, and a mod that breaks a game
 * means reinstalling the game. So the point where that becomes true is a dialog that says so.
 *
 * Most mods add no program at all -- they swap a .dll or some assets -- so the shortcut at the
 * end is offered only when the archive actually brought an .exe with it.
 *
 * Everything is unpacked into the game's install folder -- Z:\gog_games\&lt;game&gt;, not the
 * Bin64 inside it that the game is launched from -- and the archive's own folders are kept
 * exactly as packed. Between them those two rules are what puts a mod's files where they belong:
 * an archive holding Bin32 and Bin64 lands on the Bin32 and Bin64 the game already has. It does
 * mean an archive has to be packed the way the game's folder is laid out, with no folder of its
 * own wrapped around it, and {@link #confirmInstall} says so before anything is written.
 */
public abstract class ModInstaller {
    private static final String TAG = "ModInstaller";

    /** How much of the archive's layout is shown before it is written. */
    private static final int PREVIEW_ENTRIES = 5;

    /** How far out of a game's inner folders the install folder is looked for. */
    private static final int MAX_CLIMB = 3;

    /**
     * Folder names that are part of a game's layout rather than a download's wrapper.
     *
     * A mod packed as a single folder is the common case and that folder is nearly always where
     * its files belong, so it is kept. These are the names that say so outright, and they are
     * never dropped even when the archive happens to be named after one of them.
     */
    private static final String[] GAME_LAYOUT_FOLDERS = {
        "bin", "bin32", "bin64", "binaries", "content", "data", "game", "mods", "plugins",
        "scripts", "engine", "assets", "config", "saves", "shaders", "textures", "sound",
        "sounds", "music", "movies", "system", "win64", "win32", "x64", "x86", "paks", "steamapps"
    };

    /**
     * Takes a picked archive from the shortcut it was picked for to the files unpacked inside
     * that game, asking about every step that cannot be decided for the user.
     *
     * @param onShortcutCreated run on the UI thread when a second shortcut was written, or null
     */
    public static void start(Activity activity, Shortcut shortcut, File zip, Runnable onShortcutCreated) {
        if (!zip.isFile()) {
            ContentDialog.alert(activity, activity.getString(R.string.zip_file_not_found, zip.getName()), null);
            return;
        }

        // Where the game is, which is not what the shortcut says when the shortcut runs it
        // through a .lnk -- that path leads to Windows' desktop folder, and a mod unpacked
        // there would land nowhere near the game.
        File exeFile = GameUninstaller.resolveExecutable(activity, shortcut.container, shortcut);
        if (exeFile == null || exeFile.getName().toLowerCase(Locale.ENGLISH).endsWith(".lnk")) {
            ContentDialog.alert(activity, activity.getString(R.string.mod_no_install_dir, shortcut.name), null);
            return;
        }

        File installDir = installRoot(activity, shortcut, exeFile);
        // A folder the container cannot name is one no mod can be unpacked into, and "C:\" and
        // shorter is the drive itself rather than a game.
        String winPath = installDir == null ? null : winPathOf(activity, shortcut.container, installDir);
        if (installDir == null || !installDir.isDirectory() || winPath == null || winPath.length() <= 3) {
            ContentDialog.alert(activity, activity.getString(R.string.mod_no_install_dir, shortcut.name), null);
            return;
        }

        inspect(activity, shortcut, zip, exeFile, installDir, onShortcutCreated);
    }

    /**
     * The game's install folder -- Z:\gog_games\&lt;game&gt; -- which is not the folder its
     * executable sits in: a game is launched from a Bin64 or a Binaries\Win64 inside its own
     * folder, and a mod packed against the game's layout belongs at the top of it rather than
     * in there.
     *
     * The stores install a folder per game and the uninstaller already works out the rest, so
     * both are asked before anything is guessed. What is left is a game somewhere of the user's
     * own choosing, where nothing marks where it starts, so the folders that are plainly part of
     * a layout rather than the top of one are climbed out of.
     */
    private static File installRoot(Activity activity, Shortcut shortcut, File exeFile) {
        StoreGameInstall storeInstall = StoreGameInstall.find(activity, exeFile);
        if (storeInstall != null) return storeInstall.installDir;

        // Covers a game unpacked onto Z:, one copied there, and one installed into a container's
        // own C:, and refuses the cases where no folder can be called the game's.
        File installDir = GameUninstaller.findInstallDir(activity, shortcut.container, shortcut);
        if (installDir != null) return installDir;

        File dir = exeFile.getParentFile();
        for (int climbed = 0; climbed < MAX_CLIMB; climbed++) {
            File parent = dir == null ? null : dir.getParentFile();
            if (parent == null || !isGameLayoutFolder(dir.getName())) break;
            dir = parent;
        }
        return dir;
    }

    private static boolean isGameLayoutFolder(String name) {
        String folderKey = key(name);
        for (String layoutFolder : GAME_LAYOUT_FOLDERS) if (folderKey.equals(layoutFolder)) return true;
        return false;
    }

    private static String winPathOf(Context context, Container container, File dir) {
        return GuestScriptRunner.toWinPath(context, container, dir);
    }

    /**
     * Reads the archive before anything is written: how big it is, whether there is room for it,
     * and whether everything in it is wrapped in a folder that should be unpacked rather than
     * kept. Reading a large archive's index is slow enough to belong off the UI thread.
     */
    private static void inspect(Activity activity, Shortcut shortcut, File zip, File exeFile,
                                File targetDir, Runnable onShortcutCreated) {
        PreloaderDialog sizingDialog = new PreloaderDialog(activity);
        sizingDialog.showOnUiThread(R.string.zip_reading_archive);

        Executors.newSingleThreadExecutor().execute(() -> {
            long size = ZipExtractor.uncompressedSize(zip);
            // Measured where the game actually is: a game on a mapped drive is not on the same
            // storage as the image root.
            long free = ZipExtractor.usableSpaceFor(targetDir);
            String wrapper = archiveWrapper(zip, ZipExtractor.commonTopLevelFolder(zip));
            List<String> files = ZipExtractor.fileEntries(zip, wrapper);

            activity.runOnUiThread(() -> {
                sizingDialog.close();

                if (size < 0) {
                    ContentDialog.alert(activity, activity.getString(R.string.zip_cannot_be_read, zip.getName()), null);
                    return;
                }

                if (free >= 0 && size > free) {
                    ContentDialog.alert(activity, activity.getString(R.string.zip_not_enough_space,
                            StringUtils.formatBytes(size), StringUtils.formatBytes(free)), null);
                    return;
                }

                if (files.isEmpty()) {
                    ContentDialog.alert(activity, activity.getString(R.string.mod_archive_empty, zip.getName()), null);
                    return;
                }

                confirmInstall(activity, shortcut, zip, exeFile, targetDir, wrapper, size, files, onShortcutCreated);
            });
        });
    }

    /**
     * The folder an archive is wrapped in, which is the only folder that is dropped rather than
     * unpacked, or null when the archive is not wrapped in one.
     *
     * An archive with everything under one folder is packed that way for two different reasons,
     * and they look identical from the outside: the folder is the download's own -- named after
     * the mod, and no part of the game -- or it is the first folder of the layout the files
     * belong in, which is what puts them in the right place. Dropping the second kind is what
     * scatters a mod through the game, so the folder is only dropped when it is named after the
     * archive it came in, which is what a download's wrapper is named after and what a game's
     * folder is not.
     */
    private static String archiveWrapper(File zip, String folder) {
        if (folder == null) return null;

        String folderKey = key(folder);
        if (folderKey.isEmpty() || isGameLayoutFolder(folder)) return null;

        String archiveKey = key(FileUtils.getBasename(zip.getName()));
        if (folderKey.equals(archiveKey)) return folder;

        // Download sites append a version and an id to the file name -- "MyMod-1234-1-0.zip"
        // around a folder called "MyMod" -- so the archive only has to start with it.
        return folderKey.length() >= 3 && archiveKey.startsWith(folderKey) ? folder : null;
    }

    /** A name with everything that spelling and packaging vary in taken out of it. */
    private static String key(String name) {
        return name.toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9]", "");
    }

    /**
     * The last point at which nothing has been written. Says plainly that files in the game are
     * about to be replaced and that there is no way back, since that is the whole risk of the
     * feature and it is not visible from anywhere else.
     */
    private static void confirmInstall(Activity activity, Shortcut shortcut, File zip, File exeFile,
                                       File targetDir, String stripPrefix, long size,
                                       List<String> files, Runnable onShortcutCreated) {
        StringBuilder message = new StringBuilder(activity.getString(R.string.mod_confirm_install,
                winPathOf(activity, shortcut.container, targetDir), layoutPreview(activity, files),
                StringUtils.formatBytes(size)));

        // The one thing that is not visible in the preview is what the preview is missing, so
        // dropping a folder is said outright rather than left to be noticed.
        if (stripPrefix != null)
            message.append(activity.getString(R.string.mod_confirm_wrapper_note, stripPrefix));

        // Registry settings are written outside the game's folder, so they are said up front too.
        List<String> regFiles = new ArrayList<>();
        for (String entry : files)
            if (!entry.contains("/") && entry.toLowerCase(Locale.ENGLISH).endsWith(".reg")) regFiles.add(entry);
        if (!regFiles.isEmpty())
            message.append(activity.getString(R.string.mod_confirm_registry_note, String.join(", ", regFiles)));

        message.append(activity.getString(R.string.mod_confirm_no_undo));

        ContentDialog dialog = new ContentDialog(activity);
        dialog.setTitle(zip.getName());
        dialog.setMessage(message.toString());
        ((TextView) dialog.findViewById(R.id.BTConfirm)).setText(R.string.mod_install);
        dialog.setOnConfirmCallback(() -> extract(activity, shortcut, zip, exeFile, targetDir,
                stripPrefix, onShortcutCreated));
        dialog.show();
    }

    /**
     * The folders the archive is packed in, written out as the paths they will become, so that
     * where a mod is about to land is something the user can see rather than something they find
     * out afterwards.
     */
    private static String layoutPreview(Activity activity, List<String> files) {
        StringBuilder preview = new StringBuilder();

        int shown = Math.min(PREVIEW_ENTRIES, files.size());
        for (int i = 0; i < shown; i++)
            preview.append("• ").append(files.get(i).replace('/', '\\')).append("\n");

        if (files.size() > shown)
            preview.append(activity.getString(R.string.mod_preview_more, files.size() - shown));

        return preview.toString().trim();
    }

    private static void extract(Activity activity, Shortcut shortcut, File zip, File exeFile,
                                File targetDir, String stripPrefix, Runnable onShortcutCreated) {
        PreloaderDialog preloaderDialog = new PreloaderDialog(activity);
        preloaderDialog.showOnUiThread(R.string.mod_installing);
        final String installing = activity.getString(R.string.mod_installing);

        Executors.newSingleThreadExecutor().execute(() -> {
            String failure = null;
            try {
                ZipExtractor.extract(zip, targetDir, stripPrefix, new ZipExtractor.OnProgressListener() {
                    private int lastPercent = -1;

                    @Override
                    public void onProgress(String entryName, long bytesDone, long bytesTotal) {
                        int percent = bytesTotal > 0 ? (int) (bytesDone * 100 / bytesTotal) : 0;
                        if (percent == lastPercent) return;
                        lastPercent = percent;
                        preloaderDialog.updateText(installing + "\n" + percent + "%");
                    }
                });
            }
            catch (IOException e) {
                Log.e(TAG, "Could not install " + zip.getAbsolutePath() + " into " + targetDir.getAbsolutePath(), e);
                failure = e.getMessage() != null ? e.getMessage() : e.toString();
                // Nothing is cleaned up after a failure: what has been written is mixed in with
                // the game, and deleting it would take the game with it.
            }

            // Which programs came out of the archive, rather than which programs are in the
            // folder now -- the game's own are in there too and are not what was just added.
            final List<String> exeEntries = failure == null
                    ? ZipExtractor.exeEntries(zip, stripPrefix) : new ArrayList<>();
            final File bundled = failure == null ? bundledProfile(zip, stripPrefix, targetDir) : null;
            final String setupNote = failure == null
                    ? gameSetup(activity, shortcut, zip, stripPrefix, exeFile, targetDir) : "";
            final String error = failure;

            activity.runOnUiThread(() -> {
                preloaderDialog.close();

                if (error != null) {
                    ContentDialog.alert(activity, activity.getString(R.string.mod_install_failed,
                            zip.getName(), error), null);
                    return;
                }

                offerShortcut(activity, shortcut, zip, exeFile, targetDir, exeEntries, bundled,
                        setupNote, onShortcutCreated);
            });
        });
    }

    /**
     * The settings profile an archive brought with it, or null if it brought none.
     *
     * A mod that needs the game run differently -- a loader that wants a particular DX wrapper, a
     * texture pack that wants a bigger screen size -- can pack a .wxrprofile.json beside its
     * files, and it is applied to the shortcut the mod's own program gets. The file is read from
     * where it was unpacked rather than out of the archive, and is left there: this flow's whole
     * contract is that an archive's layout is kept exactly as packed, so deleting one of its
     * files afterwards would be the one place that stopped being true.
     *
     * Only the shallowest is taken. An archive holding several is either mistaken or trying to be
     * clever, and picking the top one is the answer that can be explained.
     */
    private static File bundledProfile(File zip, String stripPrefix, File targetDir) {
        for (String entry : ZipExtractor.fileEntries(zip, stripPrefix)) {
            if (!ShortcutProfile.isProfileFile(entry)) continue;
            File file = new File(targetDir, entry.replace('/', File.separatorChar));
            if (file.isFile()) return file;
        }
        return null;
    }

    /**
     * What a game copied in from a PC is missing that its installer would have set up: registry
     * keys, from any .reg at the top of the archive, and a Steam client for a SteamStub-wrapped
     * exe, from Goldberg's ColdClientLoader when the archive packs one. Returns what was done, to
     * go in the closing dialog.
     */
    private static String gameSetup(Activity activity, Shortcut shortcut, File zip, String stripPrefix,
                                    File exeFile, File targetDir) {
        StringBuilder note = new StringBuilder();
        String gameDir = winPathOf(activity, shortcut.container, targetDir);

        for (String entry : ZipExtractor.fileEntries(zip, stripPrefix)) {
            if (entry.contains("/")) continue;
            File file = new File(targetDir, entry);
            if (!file.isFile()) continue;

            if (entry.toLowerCase(Locale.ENGLISH).endsWith(".reg")) {
                RegFileImport.Result result = RegFileImport.importFile(shortcut.container, file, gameDir);
                if (result.error != null || result.notRegFile) {
                    note.append(activity.getString(R.string.mod_registry_failed, entry, result.error != null
                            ? result.error : activity.getString(R.string.mod_registry_not_reg_file)));
                    continue;
                }
                note.append(activity.getString(R.string.mod_registry_imported, entry, result.applied));
                if (result.skipped > 0) note.append(activity.getString(R.string.mod_registry_skipped, result.skipped));
            }
            else if (entry.equalsIgnoreCase(ColdClientLoaderIni.FILE_NAME)) {
                note.append(fillColdClientLoader(activity, shortcut, file, exeFile));
            }
        }
        return note.toString();
    }

    private static String fillColdClientLoader(Activity activity, Shortcut shortcut, File ini, File exeFile) {
        String exePath = winPathOf(activity, shortcut.container, exeFile);
        String exeDir = winPathOf(activity, shortcut.container, exeFile.getParentFile());
        if (exePath == null || exeDir == null) return "";

        String appId = shortcut.getExtra("goldbergAppId", "");
        if (appId.isEmpty()) appId = GoldbergEmu.detectAppIdFromAcf(exeFile.getParentFile());

        try {
            String finalAppId = ColdClientLoaderIni.fill(ini, exePath, exeDir,
                    appId == null || appId.isEmpty() ? null : appId);
            return finalAppId == null
                    ? activity.getString(R.string.mod_coldclient_no_appid)
                    : activity.getString(R.string.mod_coldclient_configured, finalAppId);
        }
        catch (IOException e) {
            Log.e(TAG, "Could not fill in " + ini.getAbsolutePath(), e);
            return activity.getString(R.string.mod_coldclient_failed,
                    e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    /**
     * Says what the mod did, and offers a second shortcut when it brought a program of its own --
     * a mod loader, a launcher, a configuration tool. Most mods bring none, and saying so is the
     * whole of what they need.
     */
    private static void offerShortcut(Activity activity, Shortcut shortcut, File zip, File exeFile,
                                      File targetDir, List<String> exeEntries, File bundledProfile,
                                      String setupNote, Runnable onShortcutCreated) {
        String winPath = winPathOf(activity, shortcut.container, targetDir);

        List<File> added = new ArrayList<>();
        for (String entry : exeEntries) {
            File file = new File(targetDir, entry.replace('/', File.separatorChar));
            if (!file.isFile()) continue;
            // A mod that patched the game's own .exe has not added anything to run.
            if (exeFile.getAbsolutePath().equals(file.getAbsolutePath())) continue;
            added.add(file);
        }

        // A profile is settings for a program, so with no program added there is nothing of the
        // mod's to apply them to. The game's own shortcut is not offered as a substitute: it is
        // working now, and quietly replacing the settings behind it is not what was asked for.
        String profileNote = setupNote + (bundledProfile == null ? ""
                : activity.getString(added.isEmpty()
                        ? R.string.mod_profile_unused : R.string.mod_profile_included,
                        bundledProfile.getName()));

        if (added.isEmpty()) {
            ContentDialog.alert(activity, activity.getString(R.string.mod_installed_no_programs,
                    zip.getName(), winPath) + profileNote, null);
            return;
        }

        if (added.size() == 1) {
            File executable = added.get(0);
            ContentDialog dialog = new ContentDialog(activity);
            dialog.setTitle(zip.getName());
            dialog.setMessage(activity.getString(R.string.mod_installed_one_program, zip.getName(),
                    winPath, ZipExtractor.relativeName(targetDir, executable), shortcut.name)
                    + profileNote);
            ((TextView) dialog.findViewById(R.id.BTConfirm)).setText(R.string.mod_add_shortcut);
            ((TextView) dialog.findViewById(R.id.BTCancel)).setText(R.string.mod_no_shortcut);
            dialog.setOnConfirmCallback(() -> createShortcut(activity, shortcut, executable,
                    bundledProfile, onShortcutCreated));
            dialog.show();
            return;
        }

        ContentDialog dialog = new ContentDialog(activity);
        dialog.setTitle(zip.getName());
        dialog.setMessage(activity.getString(R.string.mod_installed_many_programs, zip.getName(),
                winPath, added.size()) + profileNote);
        ((TextView) dialog.findViewById(R.id.BTConfirm)).setText(R.string.mod_choose_shortcut);
        ((TextView) dialog.findViewById(R.id.BTCancel)).setText(R.string.mod_no_shortcut);
        dialog.setOnConfirmCallback(() -> {
            String[] names = new String[added.size()];
            for (int i = 0; i < added.size(); i++) names[i] = ZipExtractor.relativeName(targetDir, added.get(i));

            ContentDialog.showSingleChoiceList(activity, activity.getString(R.string.mod_choose_program_title),
                    names, which -> createShortcut(activity, shortcut, added.get(which),
                            bundledProfile, onShortcutCreated));
        });
        dialog.show();
    }

    /**
     * Writes the mod's shortcut, and says what happened.
     *
     * It goes in the same container as the game, since a mod only works with the game it was
     * unpacked into, so there is nothing to ask about.
     */
    private static void createShortcut(Activity activity, Shortcut parent, File exeFile,
                                       File bundledProfile, Runnable onShortcutCreated) {
        String winePath = GuestScriptRunner.toWinPath(activity, parent.container, exeFile);
        if (winePath == null) {
            ContentDialog.alert(activity, activity.getString(R.string.installer_out_of_reach,
                    exeFile.getName(), parent.container.getName(), parent.container.getDrives()), null);
            return;
        }

        File desktopFile = write(parent, exeFile, winePath);
        if (desktopFile == null) {
            ContentDialog.alert(activity, R.string.shortcut_create_failed, null);
            return;
        }

        String profileNote = bundledProfile == null ? ""
                : applyProfile(activity, parent, desktopFile, bundledProfile);

        if (onShortcutCreated != null) onShortcutCreated.run();
        ContentDialog.alert(activity, activity.getString(R.string.shortcut_created,
                FileUtils.getBasename(exeFile.getName()), parent.container.getName())
                + profileNote, null);
    }

    /**
     * Puts the archive's profile on the shortcut just written, and says what came of it.
     *
     * The mod's shortcut starts as a copy of the game's, so applying a profile here replaces
     * settings that were inherited rather than chosen -- and because the first apply keeps what
     * it replaced, Remove Custom Profile on the mod's shortcut puts the game's settings back.
     * That is what makes this safe to do without a second question: it is reversible from the
     * same menu, on the mod's shortcut alone, and the game's own shortcut is never touched.
     *
     * A profile that cannot be read leaves the shortcut exactly as the game had it, which is the
     * outcome a mod with no profile gets, so a bad file costs nothing but the note saying so.
     */
    private static String applyProfile(Activity activity, Shortcut parent, File desktopFile,
                                       File profileFile) {
        ShortcutProfile.ReadResult result = ShortcutProfile.read(profileFile);
        if (result.profile == null) {
            Log.w(TAG, "Ignoring " + profileFile.getName() + ": " + result.error);
            return activity.getString(R.string.mod_profile_unreadable, profileFile.getName(),
                    result.error);
        }

        Shortcut created;
        try {
            created = new Shortcut(parent.container, desktopFile);
        }
        catch (IllegalArgumentException e) {
            Log.e(TAG, "Could not read back the shortcut just written at "
                    + desktopFile.getAbsolutePath(), e);
            return activity.getString(R.string.mod_profile_unreadable, profileFile.getName(),
                    activity.getString(R.string.mod_profile_shortcut_unreadable));
        }

        ShortcutProfile.apply(created, result.profile, ShortcutProfile.nameOf(profileFile.getName()));

        String note = activity.getString(R.string.mod_profile_applied, result.profile.settingCount());
        // The same caution the Games tab gives an imported profile. A mod packed against the x86
        // side of a game and unpacked into an ARM64EC container is the case where these settings
        // stop the mod launching rather than merely running it differently.
        if (result.profile.isArchMismatch(created)) {
            note += activity.getString(R.string.mod_profile_arch_mismatch,
                    WineInfo.archLabel(result.profile.arch()),
                    WineInfo.archLabel(WineInfo.archFromIdentifier(
                            parent.container.getWineVersion())));
        }
        return note;
    }

    /**
     * Extra Data the game's entry does not lend to a mod's, whichever way it is written.
     *
     * The uuid is how the home screen finds one entry. The rest are Goldberg's, and they come in
     * pairs -- the scan writes goldbergScanned with goldbergDllDirs, applying the fix writes
     * goldbergApplied with goldbergAppId -- so each pair is dropped whole. Leaving half of one
     * behind is a shortcut claiming a scan of files that were never looked at, or an appId for a
     * fix it has not had, which is worse than either having both or having neither.
     */
    private static final String[] NOT_INHERITED = {
            "uuid",
            "goldbergScanned", "goldbergDllDirs",
            "goldbergApplied", "goldbergAppId", "goldbergLoader"};

    /**
     * Whether a line of the game's entry names one of those keys.
     *
     * Matched with either separator on purpose. Shortcut.saveData() writes Extra Data back as
     * "key=value", but a freshly created entry has its container_id written as "key:value", so
     * the format really does carry both and a check for just one of them is a check that quietly
     * does nothing -- which is what this was, so none of these keys was being dropped at all.
     */
    private static boolean isNotInherited(String trimmed) {
        for (String key : NOT_INHERITED) {
            if (trimmed.startsWith(key + "=") || trimmed.startsWith(key + ":")) return true;
        }
        return false;
    }

    /**
     * The mod's desktop entry, copied from the game's own rather than written from scratch.
     *
     * A mod is the same game launched a different way, so it wants the settings the game was
     * given -- its driver, its environment variables, its per-shortcut session settings -- and a
     * fresh entry would have none of them. Only what names the program is rewritten, plus the
     * couple of keys that identify one shortcut rather than describe it.
     *
     * Returns null if it could not be written.
     */
    private static File write(Shortcut parent, File exeFile, String winePath) {
        File desktopDir = parent.container.getDesktopDir();
        if (!desktopDir.exists() && !desktopDir.mkdirs()) {
            Log.e(TAG, "Could not create the desktop directory at " + desktopDir.getAbsolutePath());
            return null;
        }

        String gameName = FileUtils.getBasename(exeFile.getName());
        String safeName = gameName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (safeName.isEmpty()) safeName = "mod";

        File desktopFile = new File(desktopDir, safeName + ".desktop");
        int suffix = 1;
        while (desktopFile.exists()) desktopFile = new File(desktopDir, safeName + "_" + (suffix++) + ".desktop");

        // Shortcut's own reader unescapes each backslash from four characters, so the path goes
        // in the way it expects to read it back.
        String escapedWinePath = winePath.replace("\\", "\\\\\\\\");

        StringBuilder content = new StringBuilder();
        boolean wroteExec = false;

        for (String line : FileUtils.readLines(parent.file)) {
            String trimmed = line.trim();

            if (trimmed.startsWith("Name=")) {
                content.append("Name=").append(gameName).append("\n");
            }
            else if (trimmed.startsWith("Exec=")) {
                // Whatever the game's entry put in front of "wine" is how it is launched, and
                // the mod is launched the same way -- only the program changes.
                String exec = trimmed.substring("Exec=".length());
                int winePos = exec.lastIndexOf("wine ");
                String prefix = winePos == -1 ? "wine " : exec.substring(0, winePos + "wine ".length());
                content.append("Exec=").append(prefix).append(escapedWinePath).append("\n");
                wroteExec = true;
            }
            else if (trimmed.startsWith("StartupWMClass=")) {
                content.append("StartupWMClass=").append(exeFile.getName()).append("\n");
            }
            // See NOT_INHERITED: the keys that identify one entry rather than describe it.
            else if (isNotInherited(trimmed)) {
                continue;
            }
            else content.append(line).append("\n");
        }

        if (!wroteExec) {
            Log.e(TAG, "The game's shortcut has no Exec line: " + parent.file.getAbsolutePath());
            return null;
        }

        if (FileUtils.writeString(desktopFile, content.toString())) return desktopFile;

        Log.e(TAG, "Failed to write shortcut " + desktopFile.getAbsolutePath());
        return null;
    }
}
