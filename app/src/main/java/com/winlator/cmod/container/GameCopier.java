package com.winlator.cmod.container;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.widget.TextView;

import com.winlator.cmod.R;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.GuestScriptRunner;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.core.ZipExtractor;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.Executors;

/**
 * Copying a game off a mapped drive and onto Z:, so it is read from internal storage.
 *
 * A shortcut added for a game the user copied in themselves points at it where they left it,
 * which is their Download folder, an SD card, or a USB stick, reached through one of the
 * container's mapped drives. Those are all slower than the internal storage the image root sits
 * on, and a game reading its assets over one of them loads slowly and can stutter mid-level, so
 * copying the game's folder across and pointing the shortcut at the copy is worth offering.
 *
 * The copy lands under the image root -- Z:\local_games -- rather than in a container, for the
 * same reason an unpacked archive does: every container sees it at the same path, so a shortcut
 * cloned into another container still finds it.
 *
 * Only the copy is made. The user's own files are left exactly where they are: they are not this
 * app's to remove, and a game that turns out not to run any better from Z: is then still there.
 */
public abstract class GameCopier {
    private static final String TAG = "GameCopier";

    /** The folder under the image root -- Z:\local_games -- that copied games go into. */
    public static final String COPIED_DIR_NAME = "local_games";

    /** Guards against a runaway tree, which on shared storage can mean a link loop. */
    private static final int MAX_COPY_DEPTH = 32;

    public interface OnProgressListener {
        void onProgress(long bytesDone, long bytesTotal);
    }

    /* ------------------------------------------------------------------ *
     *  What the user is asked, in the order they are asked it             *
     * ------------------------------------------------------------------ */

    /**
     * The whole interaction, from the menu item to a shortcut pointing at the copy.
     *
     * @param onCopied run on the UI thread once the shortcut points somewhere new, or null
     */
    public static void start(Activity activity, Shortcut shortcut, Runnable onCopied) {
        File sourceDir = findSourceFolder(activity, shortcut);
        if (sourceDir == null) {
            ContentDialog.alert(activity, R.string.copy_to_internal_nothing_to_copy, null);
            return;
        }

        File destDir = destinationFor(activity, sourceDir);
        String destWinPath = "Z:\\" + COPIED_DIR_NAME + "\\" + destDir.getName();

        // Copying over a folder that is already there would leave the two mixed together, and a
        // game copied once does not want copying again, so which of those it is stays the user's
        // call.
        if (!FileUtils.isEmpty(destDir)) {
            ContentDialog dialog = new ContentDialog(activity);
            dialog.setTitle(shortcut.name);
            dialog.setMessage(activity.getString(R.string.copy_to_internal_already_there, destWinPath));
            ((TextView) dialog.findViewById(R.id.BTConfirm)).setText(R.string.copy_to_internal_copy_again);
            ((TextView) dialog.findViewById(R.id.BTCancel)).setText(R.string.copy_to_internal_use_existing);
            dialog.setOnConfirmCallback(() -> confirmCopy(activity, shortcut, sourceDir, destDir, destWinPath, onCopied));
            dialog.setOnCancelCallback(() -> repoint(activity, shortcut, sourceDir, destDir, onCopied));
            dialog.show();
            return;
        }

        confirmCopy(activity, shortcut, sourceDir, destDir, destWinPath, onCopied);
    }

    /**
     * States what the copy will take before it is made, since a game folder runs to gigabytes,
     * and refuses outright when the room is not there rather than filling the device and failing
     * part way through.
     */
    private static void confirmCopy(Activity activity, Shortcut shortcut, File sourceDir, File destDir,
                                    String destWinPath, Runnable onCopied) {
        PreloaderDialog sizingDialog = new PreloaderDialog(activity);
        sizingDialog.showOnUiThread(R.string.copy_to_internal_measuring);

        // Walking a game folder is slow enough to keep off the UI thread even before the drive it
        // is on is taken into account.
        Executors.newSingleThreadExecutor().execute(() -> {
            long size = GameUninstaller.folderSize(sourceDir);
            long free = ZipExtractor.usableSpaceFor(copiedRoot(activity));
            String sourceWinPath = GuestScriptRunner.toWinPath(activity, shortcut.container, sourceDir);

            activity.runOnUiThread(() -> {
                sizingDialog.close();

                if (free >= 0 && size > free) {
                    ContentDialog.alert(activity, activity.getString(R.string.copy_to_internal_not_enough_space,
                            StringUtils.formatBytes(size), StringUtils.formatBytes(free)), null);
                    return;
                }

                ContentDialog dialog = new ContentDialog(activity);
                dialog.setTitle(shortcut.name);
                dialog.setMessage(activity.getString(R.string.copy_to_internal_message,
                        sourceWinPath != null ? sourceWinPath : sourceDir.getAbsolutePath(),
                        destWinPath, StringUtils.formatBytes(size)));
                ((TextView) dialog.findViewById(R.id.BTConfirm)).setText(R.string.copy_to_internal_confirm);
                dialog.setOnConfirmCallback(() -> runCopy(activity, shortcut, sourceDir, destDir, size, onCopied));
                dialog.show();
            });
        });
    }

    private static void runCopy(Activity activity, Shortcut shortcut, File sourceDir, File destDir,
                                long size, Runnable onCopied) {
        PreloaderDialog preloaderDialog = new PreloaderDialog(activity);
        preloaderDialog.showOnUiThread(R.string.copy_to_internal_copying);
        final String copying = activity.getString(R.string.copy_to_internal_copying);

        Executors.newSingleThreadExecutor().execute(() -> {
            // Anything already here is from an earlier copy of the same game, and was replaced by
            // the user's own choice.
            FileUtils.delete(destDir);

            String failure = null;
            try {
                copy(sourceDir, destDir, size, new OnProgressListener() {
                    private int lastPercent = -1;

                    @Override
                    public void onProgress(long bytesDone, long bytesTotal) {
                        int percent = bytesTotal > 0 ? (int) (bytesDone * 100 / bytesTotal) : 0;
                        if (percent == lastPercent) return;
                        lastPercent = percent;
                        preloaderDialog.updateText(copying + "\n" + percent + "%");
                    }
                });
            }
            catch (IOException e) {
                Log.e(TAG, "Could not copy " + sourceDir.getAbsolutePath(), e);
                failure = e.getMessage() != null ? e.getMessage() : e.toString();
                // Half a game is worse than none: it would be offered as already copied next time
                // round, and a shortcut pointed at it would run files that are not all there.
                FileUtils.delete(destDir);
            }

            final String error = failure;
            activity.runOnUiThread(() -> {
                preloaderDialog.close();
                if (error != null) {
                    ContentDialog.alert(activity, activity.getString(R.string.copy_to_internal_failed,
                            sourceDir.getName(), error), null);
                    return;
                }
                repoint(activity, shortcut, sourceDir, destDir, onCopied);
            });
        });
    }

    /** Points the shortcut at the copy, and says where the game is played from now. */
    private static void repoint(Activity activity, Shortcut shortcut, File sourceDir, File destDir,
                                Runnable onCopied) {
        String destWinPath = "Z:\\" + COPIED_DIR_NAME + "\\" + destDir.getName();

        if (!repointShortcut(activity, shortcut, sourceDir, destDir)) {
            ContentDialog.alert(activity, activity.getString(R.string.copy_to_internal_repoint_failed,
                    destWinPath), null);
            return;
        }

        if (onCopied != null) onCopied.run();
        ContentDialog.alert(activity, activity.getString(R.string.copy_to_internal_done,
                shortcut.name, destWinPath), null);
    }

    /* ------------------------------------------------------------------ *
     *  Which games this is worth offering for, and what gets copied       *
     * ------------------------------------------------------------------ */

    /** Whether the menu item is worth showing for a shortcut, which is cheap enough to ask often. */
    public static boolean canCopy(Context context, Shortcut shortcut) {
        return findSourceFolder(context, shortcut) != null;
    }

    /**
     * The folder that would be copied, or null when there is nothing worth copying.
     *
     * The game's own folder is rarely the one its executable sits in -- a launcher lives beside
     * the game while the game itself is under bin\x64 or similar -- so this climbs to the
     * outermost folder that still belongs to the game, stopping below the drive root and below a
     * folder that holds games in general, such as GOG Games. That is the same shape as the folder
     * {@link GameUninstaller#findInstallDir} would delete, and for the same reason: it is the
     * whole of what the game is made of.
     *
     * Nothing comes back unless the executable is on one of the container's mapped drives and
     * that drive leads outside the app's own storage. C: is inside the container and Z: is the
     * image root, both already on internal storage, and so is the drive mapped to the app's
     * private folder: copying from any of those would take the room up twice over and gain
     * nothing. An executable sitting loose in a drive root has no folder of its own, and the
     * drive root itself -- the user's whole Download folder, say -- is not it.
     */
    public static File findSourceFolder(Context context, Shortcut shortcut) {
        Container container = shortcut.container;
        // The shortcut's own path, rather than what a .lnk it names points at: a shortcut made
        // through a .lnk would still be read out of that .lnk after the copy, and rewriting one
        // is not something this does.
        File exeFile = GuestScriptRunner.toHostPath(context, container, executablePath(shortcut.path));
        if (exeFile == null || !exeFile.isFile()) return null;

        File driveRoot = slowDriveRoot(context, container, exeFile);
        if (driveRoot == null) return null;

        String rootPath = driveRoot.getAbsolutePath();
        File folder = null;
        for (File dir = exeFile.getParentFile(); dir != null; dir = dir.getParentFile()) {
            if (dir.getAbsolutePath().equals(rootPath)) return folder;
            if (GameUninstaller.isProgramFolder(dir.getName())) return folder;
            folder = dir;
        }
        // The climb left the drive without ever meeting its root, so the path stopped matching
        // part way up and nothing found on the way can be trusted to be the game's folder.
        return null;
    }

    /**
     * The root of the mapped drive an executable is on, when that drive is somewhere copying off
     * would actually help, and null otherwise.
     */
    private static File slowDriveRoot(Context context, Container container, File exeFile) {
        String abs = exeFile.getAbsolutePath();
        String appStorage = canonicalPath(context.getFilesDir().getParentFile());

        for (String[] drive : container.drivesIterator()) {
            File root = new File(drive[1]);
            if (!abs.startsWith(root.getAbsolutePath() + "/")) continue;
            // The drive mapped to the app's own private folder is on the same internal storage
            // the image root is, so there is no speed to be had by copying between them.
            return isInside(canonicalPath(root), appStorage) ? null : root;
        }
        return null;
    }

    private static String canonicalPath(File file) {
        if (file == null) return null;
        try {
            return file.getCanonicalPath();
        }
        catch (IOException e) {
            Log.w(TAG, "Could not resolve " + file.getAbsolutePath(), e);
            return file.getAbsolutePath();
        }
    }

    private static boolean isInside(String path, String parent) {
        return path != null && parent != null && (path.equals(parent) || path.startsWith(parent + "/"));
    }

    /**
     * The Exec line carries whatever arguments the game is run with after the program itself, and
     * those are not part of the path. The program is what ends at the first .exe that the end of
     * the line, or a space, follows.
     */
    private static String executablePath(String winPath) {
        if (winPath == null) return "";

        String lower = winPath.toLowerCase(Locale.ENGLISH);
        for (int index = lower.indexOf(".exe"); index != -1; index = lower.indexOf(".exe", index + 1)) {
            int end = index + 4;
            if (end == winPath.length() || winPath.charAt(end) == ' ') return winPath.substring(0, end);
        }
        return winPath;
    }

    /** Z:\local_games, created on demand. */
    public static File copiedRoot(Context context) {
        File dir = copiedRootPath(context);
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    /** Where Z:\local_games is, for the questions that only ask about a path. */
    private static File copiedRootPath(Context context) {
        return new File(ImageFs.find(context).getRootDir(), COPIED_DIR_NAME);
    }

    /** Where a game folder would be copied to: Z:\local_games\&lt;the folder's own name&gt;. */
    public static File destinationFor(Context context, File sourceDir) {
        String name = sourceDir.getName().replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (name.isEmpty()) name = "game";
        return new File(copiedRootPath(context), name);
    }

    /**
     * The folder directly under Z:\local_games that a file belongs to, or null when it is not
     * part of a copied game at all. That folder holds the one game and nothing else, so it is
     * what removing that game takes.
     */
    public static File containingCopy(Context context, File file) {
        if (file == null) return null;

        String root = copiedRootPath(context).getAbsolutePath();
        File candidate = null;
        for (File dir = file.getAbsoluteFile().getParentFile(); dir != null; dir = dir.getParentFile()) {
            if (dir.getAbsolutePath().equals(root)) return candidate;
            candidate = dir;
        }
        return null;
    }

    /* ------------------------------------------------------------------ *
     *  The copy itself                                                    *
     * ------------------------------------------------------------------ */

    /**
     * Copies the whole folder across.
     *
     * A file that cannot be read or written fails the whole copy rather than being skipped: a
     * game missing one of its files is not one to point a shortcut at, and the caller deletes
     * the half that did copy.
     */
    public static void copy(File sourceDir, File destDir, long total, OnProgressListener listener) throws IOException {
        if (!destDir.isDirectory() && !destDir.mkdirs())
            throw new IOException("Could not create " + destDir.getAbsolutePath());

        copyInto(sourceDir, destDir, new long[] {0}, total, listener, new byte[65536], 0);
    }

    private static void copyInto(File sourceDir, File destDir, long[] done, long total,
                                 OnProgressListener listener, byte[] buffer, int depth) throws IOException {
        if (depth > MAX_COPY_DEPTH)
            throw new IOException("Folders nested too deeply at " + sourceDir.getAbsolutePath());

        File[] files = sourceDir.listFiles();
        if (files == null) throw new IOException("Could not read " + sourceDir.getAbsolutePath());

        for (File file : files) {
            // A link is not the file it names, and following one is how copying a game folder
            // turns into copying something else entirely.
            if (FileUtils.isSymlink(file)) continue;

            File target = new File(destDir, file.getName());
            if (file.isDirectory()) {
                if (!target.isDirectory() && !target.mkdirs())
                    throw new IOException("Could not create " + target.getAbsolutePath());
                copyInto(file, target, done, total, listener, buffer, depth + 1);
                continue;
            }

            try (InputStream input = new FileInputStream(file);
                 OutputStream output = new FileOutputStream(target)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    done[0] += count;
                    if (listener != null) listener.onProgress(done[0], total);
                }
            }
        }
    }

    /* ------------------------------------------------------------------ *
     *  Pointing the shortcut at the copy                                  *
     * ------------------------------------------------------------------ */

    /**
     * Rewrites the shortcut to run the game from its new folder, leaving everything else about it
     * -- its arguments, its settings, its icon -- as it was.
     *
     * Only the part of each path that names the old folder is replaced, so a shortcut that runs
     * the game with arguments, or from a subfolder several levels down, still says the same thing
     * about that game afterwards.
     */
    public static boolean repointShortcut(Context context, Shortcut shortcut, File sourceDir, File destDir) {
        Container container = shortcut.container;
        String oldWinDir = GuestScriptRunner.toWinPath(context, container, sourceDir);
        String newWinDir = GuestScriptRunner.toWinPath(context, container, destDir);
        if (oldWinDir == null || newWinDir == null) {
            Log.e(TAG, "No guest path for " + sourceDir + " or for " + destDir);
            return false;
        }

        ArrayList<String> lines = FileUtils.readLines(shortcut.file);
        StringBuilder content = new StringBuilder();
        boolean repointed = false;

        for (String line : lines) {
            if (line.startsWith("Exec=")) {
                String exec = replaceGuestPath(line.substring(5), oldWinDir, newWinDir);
                if (exec != null) {
                    line = "Exec=" + exec;
                    repointed = true;
                }
            }
            else if (line.startsWith("Path=")) {
                // A working directory is a host path that reaches the game through the prefix's
                // symlink for its drive, so it is that leg of it which changes.
                String path = replaceFirst(line.substring(5), dosDevicesPath(oldWinDir), dosDevicesPath(newWinDir));
                if (path != null) line = "Path=" + path;
                else Log.w(TAG, "Leaving a working directory that does not name the game's folder: " + line);
            }
            content.append(line).append("\n");
        }

        if (!repointed) {
            Log.e(TAG, "No Exec line naming " + oldWinDir + " in " + shortcut.file.getAbsolutePath());
            return false;
        }
        if (!FileUtils.writeString(shortcut.file, content.toString())) return false;

        boolean extrasChanged = false;

        // A second executable is another program in the same folder -- a launcher, or a config
        // tool -- so it moved with the rest of it.
        String secondaryExec = shortcut.getExtra("secondaryExec");
        if (!secondaryExec.isEmpty()) {
            String moved = replaceGuestPath(secondaryExec, oldWinDir, newWinDir);
            if (moved != null) {
                shortcut.putExtra("secondaryExec", moved);
                extrasChanged = true;
            }
        }

        // Where the game's steam_api.dll files are was worked out by walking the old folder, and
        // those paths lead nowhere now. Dropping the answer has it worked out again, against the
        // copy, the next time the fix is applied.
        if (shortcut.hasExtra("goldbergScanned") || shortcut.hasExtra("goldbergDllDirs")) {
            shortcut.putExtra("goldbergScanned", null);
            shortcut.putExtra("goldbergDllDirs", null);
            extrasChanged = true;
        }

        // Only when there is something to save: saving rewrites the whole entry from what was
        // read out of it, and the rewritten Exec line above is already on disk.
        if (extrasChanged) shortcut.saveData();
        return true;
    }

    /**
     * The same path with a different folder at the front of it, or null when it does not start
     * with that folder at all.
     *
     * A guest path goes into the Exec line with its backslashes escaped, and how many times over
     * depends on which of the ways of making a shortcut wrote it, so the folder is looked for in
     * each of those forms, the most escaped first.
     */
    private static String replaceGuestPath(String value, String oldWinDir, String newWinDir) {
        for (String separator : new String[] {"\\\\\\\\", "\\\\", "\\"}) {
            String result = replaceFirst(value, oldWinDir.replace("\\", separator),
                    newWinDir.replace("\\", separator));
            if (result != null) return result;
        }
        return null;
    }

    /**
     * Replaces the first occurrence, matched without regard to case: Windows paths are not case
     * sensitive, and a drive letter in particular is written both ways by different parts of this.
     */
    private static String replaceFirst(String value, String target, String replacement) {
        int index = value.toLowerCase(Locale.ENGLISH).indexOf(target.toLowerCase(Locale.ENGLISH));
        return index == -1 ? null
                : value.substring(0, index) + replacement + value.substring(index + target.length());
    }

    /** A guest folder as the prefix's own symlinks reach it: {@code /dosdevices/d:/Games/Foo}. */
    private static String dosDevicesPath(String winDir) {
        return "/dosdevices/" + Character.toLowerCase(winDir.charAt(0)) + ":"
                + winDir.substring(2).replace('\\', '/');
    }
}
