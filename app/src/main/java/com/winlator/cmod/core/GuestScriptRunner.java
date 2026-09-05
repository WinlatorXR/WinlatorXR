package com.winlator.cmod.core;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.winlator.cmod.ShortcutsFragment;
import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.xenvironment.ImageFs;
import com.winlator.xr.XrActivity;

import java.io.File;
import java.io.FileWriter;
import java.util.List;

/**
 * Runs a batch script inside a container through a visible cmd console.
 *
 * Installers started as a bare guest executable give no sign of progress -- a package with no
 * UI sequence of its own leaves the container showing an empty desktop -- and the session stays
 * open afterwards because nothing ends the wine desktop. Running through cmd fixes both: the
 * console shows what is happening, and the script closes explorer at the end, which ends the
 * guest process and lets XServerDisplayActivity's termination callback finish the activity.
 */
public abstract class GuestScriptRunner {
    private static final String TAG = "GuestScriptRunner";

    /** Long enough to read the job's exit code, short enough not to be in the way. */
    public static final int DEFAULT_CLOSE_DELAY_SECONDS = 15;

    /**
     * Turns off FEX's reduced-precision x87 for one session.
     *
     * Reduced precision ("Fast", the container default) keeps the x87 stack in 64-bit doubles,
     * which breaks programs built with 32-bit Delphi -- an Inno Setup installer and the
     * uninstaller it leaves behind are both such a program, and both fail with a corrupted /
     * path-not-found error until x87 is accurate. FEX reads FEX_-prefixed environment variables
     * as its highest-priority config layer, so this holds for the session that runs the job and
     * nothing else: the container keeps whatever x87 mode it is set to, and there is nothing to
     * put back afterwards, even if the job crashes.
     */
    public static final String ACCURATE_X87_ENV = "FEX_X87REDUCEDPRECISION=0";

    public interface OnContainerSelected {
        void onSelected(Container container);
    }

    /** Asks which container to run in, skipping the prompt when only one exists. */
    public static void pickContainer(Activity activity, String title, OnContainerSelected callback) {
        ContainerManager manager = new ContainerManager(activity);
        List<Container> containers = manager.getContainers();

        if (containers == null || containers.isEmpty()) {
            ContentDialog.alert(activity, "No container found. Create one first.", null);
            return;
        }

        if (containers.size() == 1) {
            callback.onSelected(containers.get(0));
            return;
        }

        String[] names = new String[containers.size()];
        for (int i = 0; i < containers.size(); i++) names[i] = containers.get(i).getName();

        new AlertDialog.Builder(activity)
            .setTitle(title)
            .setItems(names, (dialog, which) -> callback.onSelected(containers.get(which)))
            .setNegativeButton("Cancel", null)
            .show();
    }

    /**
     * Writes {@code body} into a batch file inside the container and launches it.
     *
     * @param jobName  shown as the console title and in the closing message
     * @param scriptId short slug used for the batch and shortcut file names, so unrelated jobs
     *                 do not overwrite each other
     */
    public static void run(Activity activity, Container container, String jobName,
                           String scriptId, List<String> body) {
        run(activity, container, jobName, scriptId, body, null, DEFAULT_CLOSE_DELAY_SECONDS);
    }

    /**
     * @param envVars environment variables for this session only, in "NAME=value NAME=value"
     *                form, or null. They are merged over the container's own, and go no further
     *                than the launch: nothing about the container is changed.
     */
    public static void run(Activity activity, Container container, String jobName,
                           String scriptId, List<String> body, String envVars) {
        run(activity, container, jobName, scriptId, body, envVars, DEFAULT_CLOSE_DELAY_SECONDS);
    }

    /**
     * @param closeDelaySeconds how long the finished console waits before closing the container.
     *                          A job whose command returns before the work is really over -- an
     *                          uninstaller that hands off to a copy of itself, say -- needs long
     *                          enough for that to finish, since closing takes the container down
     *                          with it.
     */
    public static void run(Activity activity, Container container, String jobName,
                           String scriptId, List<String> body, String envVars, int closeDelaySeconds) {
        new Thread(() -> {
            Handler handler = new Handler(Looper.getMainLooper());
            try {
                File driveC = new File(container.getRootDir(), ".wine/drive_c");
                if (!driveC.isDirectory()) {
                    handler.post(() -> ContentDialog.alert(activity,
                        "The container has not been set up yet. Launch it once, then try again.", null));
                    return;
                }

                String batName = "winlator-" + scriptId + ".bat";
                writeBatchFile(new File(driveC, batName), jobName, body, closeDelaySeconds);

                File desktopDir = container.getDesktopDir();
                if (!desktopDir.isDirectory() && !desktopDir.mkdirs()) {
                    handler.post(() -> ContentDialog.alert(activity,
                        "Could not open the container's desktop directory.", null));
                    return;
                }

                // The hidden-shortcut prefix keeps the generated entry out of the shortcut list.
                File shortcutFile = new File(desktopDir,
                    ShortcutsFragment.HIDDEN_SHORTCUT + "-" + scriptId + ".desktop");
                writeRunnerShortcut(shortcutFile, batName, envVars);

                Shortcut runner = new Shortcut(container, shortcutFile);
                handler.post(() -> launch(activity, runner));
            }
            catch (Exception e) {
                Log.e(TAG, "Failed to start " + jobName, e);
                handler.post(() -> ContentDialog.alert(activity,
                    "Failed to start " + jobName + ": " + e.getMessage(), null));
            }
        }).start();
    }

    /**
     * A guest path a batch file can carry, standing in for one it cannot.
     *
     * cmd reads a batch file in the guest's codepage rather than as UTF-8, so a path holding
     * anything outside ASCII -- a game in a folder with a (R) in its name -- stops naming a real
     * file once written into one. Such a path gets a plain-ASCII stand-in: a link in the drive's
     * root pointing at the folder the file is in, so the file keeps its own name and everything
     * beside it, which is what an installer or uninstaller reads its data from.
     *
     * @param linkName the link's name, unique per job so two of them cannot collide
     * @return a path safe to write into a script, or the original where it already was
     */
    public static String asciiPath(Context context, Container container, String winPath, String linkName) {
        if (winPath == null || isAscii(winPath)) return winPath;

        File file = toHostPath(context, container, winPath);
        File folder = file != null ? file.getParentFile() : null;
        if (folder == null || !isAscii(file.getName())) {
            Log.w(TAG, "No ASCII stand-in for " + winPath);
            return winPath;
        }

        File link = new File(new File(container.getRootDir(), ".wine/drive_c"), linkName);
        // Deleting a link to a directory removes the link, not what it points at.
        link.delete();
        FileUtils.symlink(folder.getAbsolutePath(), link.getAbsolutePath());

        return "C:\\" + linkName + "\\" + file.getName();
    }

    /** Whether a guest path can be written into a batch file and still name the same file. */
    public static boolean isAscii(String path) {
        for (int i = 0; i < path.length(); i++) if (path.charAt(i) > 127) return false;
        return true;
    }

    /**
     * The [Extra Data] line XServerDisplayActivity reads environment variables from, which it
     * merges over the container's own for that launch alone.
     */
    private static String extraEnvVars(String envVars) {
        return envVars == null || envVars.isEmpty() ? "" : "envVars=" + envVars + "\n";
    }

    /**
     * The script keeps a log beside itself on C:, since the console it prints to is gone as soon
     * as the container closes -- which is exactly when something has gone wrong and there is
     * nothing left to read. It records what the job was handed and what came back.
     */
    private static void writeBatchFile(File batFile, String jobName, List<String> body, int closeDelaySeconds) throws Exception {
        String logPath = "C:\\" + FileUtils.getBasename(batFile.getName()) + ".log";

        StringBuilder sb = new StringBuilder();
        sb.append("@echo off\r\n");
        sb.append("title ").append(jobName).append("\r\n");
        sb.append("set LOG=").append(logPath).append("\r\n");
        sb.append("echo === ").append(jobName).append(" started >\"%LOG%\"\r\n");

        for (String line : body) sb.append(line).append("\r\n");

        sb.append("echo === body finished, errorlevel %errorlevel% >>\"%LOG%\"\r\n");
        sb.append("echo.\r\n");
        sb.append("echo ").append(jobName).append(" finished.\r\n");
        sb.append("echo Closing the container in ").append(closeDelaySeconds)
          .append(" seconds. Press a key to close it now.\r\n");
        // A keypress is awkward to send in VR, so the console closes itself. Where wine has no
        // timeout program the || falls back to waiting for the key.
        sb.append("timeout /t ").append(closeDelaySeconds).append(" >nul 2>&1 || pause\r\n");
        sb.append("echo === closing the container >>\"%LOG%\"\r\n");
        // Ending the wine desktop ends the guest process, which closes the container session.
        sb.append("taskkill /f /im explorer.exe\r\n");

        try (FileWriter fw = new FileWriter(batFile)) {
            fw.write(sb.toString());
        }
    }

    /**
     * The command lives in [Extra Data] execArgs, which XServerDisplayActivity appends verbatim,
     * so no chaining characters ever reach the shortcut's Exec line.
     */
    private static void writeRunnerShortcut(File shortcutFile, String batName, String envVars) throws Exception {
        // Shortcut's unescape() collapses the quadrupled backslashes when the file is read back.
        String cmdPath = "Z:\\\\\\\\windows\\\\\\\\system32\\\\\\\\cmd.exe";

        String content = "[Desktop Entry]\n"
            + "Name=" + FileUtils.getBasename(shortcutFile.getName()) + "\n"
            + "Exec=wine " + cmdPath + "\n"
            + "Icon=\n"
            + "Type=Application\n"
            + "StartupWMClass=explorer\n"
            + "\n"
            + "[Extra Data]\n"
            + "execArgs=/c \"C:\\" + batName + "\"\n"
            + extraEnvVars(envVars);

        try (FileWriter fw = new FileWriter(shortcutFile)) {
            fw.write(content);
        }
    }

    private static void launch(Activity activity, Shortcut shortcut) {
        if (!XrActivity.isEnabled(activity)) {
            Intent intent = new Intent(activity, XServerDisplayActivity.class);
            intent.putExtra("container_id", shortcut.container.id);
            intent.putExtra("shortcut_path", shortcut.file.getPath());
            intent.putExtra("shortcut_name", shortcut.name);
            activity.startActivity(intent);
        }
        else XrActivity.openIntent(activity, shortcut.container.id, shortcut.file.getPath());
    }

    /** Maps a guest path such as {@code D:\Games\Foo\game.exe} onto the Android filesystem. */
    public static File toHostPath(Context context, Container container, String winPath) {
        if (winPath == null || winPath.length() < 2 || winPath.charAt(1) != ':') return null;

        char letter = Character.toUpperCase(winPath.charAt(0));
        String rel = winPath.substring(2).replace('\\', '/');

        if (letter == 'C') return new File(new File(container.getRootDir(), ".wine/drive_c"), rel);
        if (letter == 'Z') return new File(ImageFs.find(context).getRootDir(), rel);

        for (String[] drive : container.drivesIterator()) {
            if (!drive[0].isEmpty() && Character.toUpperCase(drive[0].charAt(0)) == letter) {
                return new File(drive[1], rel);
            }
        }
        return null;
    }

    /**
     * Maps an Android path back onto a guest path. C: and the container's own drive mappings
     * are checked before Z:, since the container tree itself lives under the image root.
     */
    public static String toWinPath(Context context, Container container, File hostFile) {
        String abs = hostFile.getAbsolutePath();

        String driveC = new File(container.getRootDir(), ".wine/drive_c").getAbsolutePath();
        if (abs.startsWith(driveC)) return "C:" + abs.substring(driveC.length()).replace('/', '\\');

        for (String[] drive : container.drivesIterator()) {
            String root = new File(drive[1]).getAbsolutePath();
            if (abs.startsWith(root)) return drive[0] + ":" + abs.substring(root.length()).replace('/', '\\');
        }

        String imageFs = ImageFs.find(context).getRootDir().getAbsolutePath();
        if (abs.startsWith(imageFs)) return "Z:" + abs.substring(imageFs.length()).replace('/', '\\');

        return null;
    }
}
