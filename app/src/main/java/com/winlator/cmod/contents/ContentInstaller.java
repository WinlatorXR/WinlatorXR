package com.winlator.cmod.contents;

import android.app.Activity;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;

import com.winlator.cmod.R;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.GuestScriptRunner;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.core.ShortcutCreator;
import com.winlator.cmod.core.ZipExtractor;
import com.winlator.cmod.core.ZipImport;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public class ContentInstaller {
    /** The link name an installer's folder gets when its own path cannot go into the script. */
    private static final String INSTALLER_LINK = "winlator-installer-target";

    public static String queryDisplayName(Context context, Uri uri) {
        if (uri == null) return null;

        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index != -1) return cursor.getString(index);
            }
        } catch (Exception e) {
            Log.w("ContentsFragment", "Could not read the display name of " + uri, e);
        }
        return uri.getLastPathSegment();
    }

    /** Copies a locally picked installer into the runtimes directory so it can be run in a container. */
    public static void installLocalRuntime(Activity activity, Uri uri, String name, Runnable onSuccess) {
        Context context = activity.getApplicationContext();
        PreloaderDialog preloaderDialog = new PreloaderDialog(activity);
        preloaderDialog.showOnUiThread(R.string.installing_content);

        Executors.newSingleThreadExecutor().execute(() -> {
            File dest = new File(ContentsManager.getRuntimesDir(context), name);
            boolean copied = FileUtils.copy(context, uri, dest);
            if (!copied) dest.delete();

            activity.runOnUiThread(() -> {
                preloaderDialog.close();
                if (copied) {
                    onSuccess.run();
                    ContentDialog.alert(activity, R.string.content_installed_success, null);
                } else {
                    ContentDialog.alert(activity, R.string.install_failed, null);
                }
            });
        });
    }

    /** The Download folder as the picker's starting point. */
    public static Uri downloadsDocumentUri() {
        // EXTRA_INITIAL_URI needs a real SAF document Uri, not a file:// Uri (which most
        // document picker implementations silently ignore, falling back to their default root).
        return DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:" + Environment.DIRECTORY_DOWNLOADS);
    }



    /**
     * Lists a demo or offline installer the user keeps in their Download folder, without copying
     * anything: the file is recorded where it sits, so the data files an offline installer needs
     * beside it -- the .bin parts of a GOG one, say -- stay where the installer expects them and
     * are not duplicated into the app's storage.
     *
     * Where it may sit is limited to the folders a container reaches without being reconfigured,
     * which is what lets it be run from there at all: the Download folder, which is D: by default,
     * and the image root, which is Z: in every container and is where a .zip is unpacked to.
     */
    public static void addLocalInstaller(Activity activity, Uri uri, Runnable onSuccess) {
        String path = FileUtils.getFilePathFromDocumentUri(activity, uri);
        if (path == null) {
            ContentDialog.alert(activity, R.string.installer_not_local_file, null);
            return;
        }

        File picked = new File(path);
        if (!picked.isFile()) {
            ContentDialog.alert(activity, R.string.installer_file_not_found, null);
            return;
        }

        // A .zip cannot be run from the archive either way, so it is unpacked onto Z: first. What
        // it turns out to hold decides the rest: an installer is listed here to be run in a
        // container, while game files are already installed by virtue of being unpacked and only
        // need a shortcut to the game.
        if (ZipImport.isZip(picked)) {
            ZipImport.startAsking(activity, picked, (role, executable, extractedDir) -> {
                if (role == ZipExtractor.Role.GAME) ShortcutCreator.createForExecutable(activity, executable, null);
                else addInstallerReference(activity, executable, onSuccess);
            });
            return;
        }

        if (!ContentsManager.isInstaller(picked.getName())) {
            ContentDialog.alert(activity, R.string.installer_must_be_exe_or_msi, null);
            return;
        }

        addInstallerReference(activity, picked, onSuccess);
    }

    /** Lists an installer where it sits, once it is somewhere a container can actually get at it. */
    private static void addInstallerReference(Context context, File installer, Runnable onSuccess) {
        if (!isReachableByContainers(context, installer)) {
            String downloadsPath = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();
            ContentDialog.alert(context, context.getString(R.string.installer_must_be_in_downloads, downloadsPath), null);
            return;
        }

        if (!ContentsManager.addInstallerReference(context, installer)) {
            ContentDialog.alert(context, R.string.install_failed, null);
            return;
        }

        onSuccess.run();
        ContentDialog.alert(context, context.getString(R.string.installer_added, installer.getName()), null);
    }

    /**
     * Whether a file of the user's is somewhere every container reaches without being reconfigured:
     * the Download folder, which is D: by default, or the image root, which is Z: in all of them
     * and is where an archive is unpacked to.
     */
    private static boolean isReachableByContainers(Context context, File file) {
        String path = file.getAbsolutePath();
        String downloads = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();
        if (path.startsWith(downloads + "/")) return true;

        String imageFs = ImageFs.find(context).getRootDir().getAbsolutePath();
        return path.startsWith(imageFs + "/");
    }

    /**
     * The data files an offline installer keeps beside itself, named the way GOG's are: the
     * installer setup_game.exe is paired with setup_game-1.bin and so on. Matching only that
     * exact shape keeps the remove action from taking anything it was not asked to.
     */
    public static List<File> companionFiles(File installer) {
        List<File> companions = new ArrayList<>();

        File dir = installer.getParentFile();
        File[] files = dir != null ? dir.listFiles(File::isFile) : null;
        if (files == null) return companions;

        String pattern = "(?i)" + Pattern.quote(FileUtils.getBasename(installer.getName())) + "-\\d+\\.bin";
        for (File file : files) if (file.getName().matches(pattern)) companions.add(file);
        return companions;
    }

    /**
     * Runs an installer held on the host inside a container, through a cmd console either way:
     * an .msi has no entry point of its own so it goes through msiexec, and an .exe is run as a
     * command so that the console outlives it. Nothing else ends a session -- the wine desktop
     * keeps running after the installer exits -- so the script is what closes the container when
     * the job is done.
     */
    public static void runInstaller(Activity activity, File file) {
        if (file.getName().toLowerCase(Locale.ENGLISH).endsWith(".msi")) {
            installMsiPackage(activity, file);
            return;
        }

        String name = FileUtils.getBasename(file.getName());
        GuestScriptRunner.pickContainer(activity, "Run " + name + " in which container?", container -> {
            // An installer kept in the Download folder is reached through the container's own
            // drive mapping for it, so the guest path has to come from the container.
            String winPath = GuestScriptRunner.toWinPath(activity, container, file);
            if (winPath == null) {
                ContentDialog.alert(activity, activity.getString(R.string.installer_out_of_reach,
                        file.getName(), container.getName(), container.getDrives()), null);
                return;
            }

            List<String> body = new ArrayList<>();
            body.add("echo Running " + name + " ...");
            body.add("echo.");
            // cmd waits for a windowed program run as a command, so the script resumes when the
            // installer is actually finished rather than when its window first appears. A path
            // the script cannot carry is passed as an argument instead.
            body.add("\"" + GuestScriptRunner.asciiPath(activity, container, winPath, INSTALLER_LINK) + "\"");
            body.add("echo.");
            body.add("echo Installer exit code: %errorlevel%");

            GuestScriptRunner.run(activity, container, "Installing " + name, "installer", body,
                    GuestScriptRunner.ACCURATE_X87_ENV);
        });
    }

    /**
     * Runs an .msi through msiexec in a container. A package with no UI sequence of its own would
     * otherwise install against a blank desktop with no sign of progress, so this goes through
     * the script runner: the console reports what is happening, a verbose log is left in the
     * container for diagnosing a failed install, and the session closes when it is done.
     */
    public static void installMsiPackage(Activity activity, File packageFile) {
        String name = FileUtils.getBasename(packageFile.getName());

        GuestScriptRunner.pickContainer(activity, "Install " + name + " into which container?", container -> {
            String winPath = GuestScriptRunner.toWinPath(activity, container, packageFile);
            if (winPath == null) {
                ContentDialog.alert(activity, "The container cannot reach " + packageFile.getName() + ".", null);
                return;
            }

            // Both paths stay out of the script itself: the package's because it can hold
            // anything, the log's because it was named after the package.
            String logPath = "C:\\winlator-install.log";
            List<String> body = new ArrayList<>();
            body.add("echo Installing " + name + " ...");
            body.add("echo A verbose log will be written to " + logPath);
            body.add("echo.");
            body.add("msiexec /i \"" + GuestScriptRunner.asciiPath(activity, container, winPath, INSTALLER_LINK)
                    + "\" /L*V \"" + logPath + "\"");
            body.add("echo.");
            body.add("echo msiexec exit code: %errorlevel%");

            GuestScriptRunner.run(activity, container, "Installing " + name, "runtime", body,
                    GuestScriptRunner.ACCURATE_X87_ENV);
        });
    }
}
