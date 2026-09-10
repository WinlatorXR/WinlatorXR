package com.winlator.cmod.core;

import android.app.Activity;
import android.util.Log;
import android.widget.TextView;

import com.winlator.cmod.R;
import com.winlator.cmod.contentdialog.ContentDialog;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * The whole of what happens when the file a user picked, or downloaded, turns out to be a .zip.
 *
 * The three places a program can be brought in from -- a game on the Games tab, and a runtime or
 * an installer on the Downloader -- all want the same thing from an archive: unpack it somewhere
 * a container can reach, settle which of the executables inside it is the one that was meant, and
 * offer to drop the archive now that its contents are on disk. That sequence lives here once, and
 * hands each caller back a single file to carry on with, so none of them has to know it was ever
 * an archive.
 *
 * What the caller then does with that file is not always the same, though, and is not always known
 * in advance -- see {@link #startAsking}.
 *
 * Every message is a dialog rather than a toast: toasts render broken under the Quest Navigator's
 * panel compositor, which is where most of this is used.
 */
public abstract class ZipImport {
    private static final String TAG = "ZipImport";

    public interface OnExecutableChosen {
        /**
         * @param role          what the archive turned out to hold, for the callers that had to ask
         * @param executable    the program the user settled on, unpacked and ready to run
         * @param extractedDir  the folder the whole archive was unpacked into
         */
        void onChosen(ZipExtractor.Role role, File executable, File extractedDir);
    }

    public static boolean isZip(File file) {
        return ZipExtractor.isZip(file);
    }

    public static boolean isZip(String name) {
        return ZipExtractor.isZip(name);
    }

    /**
     * Takes an archive whose contents nobody has said anything about, which is the Downloader's
     * case: a .zip added there is as likely to be a game that only needs unpacking as an installer
     * to run, and nothing about the file says which.
     *
     * So it is put to the user before anything is unpacked, and their answer decides both which of
     * the programs inside is picked out as the likely one and what the caller does with it
     * afterwards -- run it in a container, or make a Games tab shortcut to it.
     */
    public static void startAsking(Activity activity, File zip, OnExecutableChosen callback) {
        String[] kinds = {
            activity.getString(R.string.zip_kind_installer),
            activity.getString(R.string.zip_kind_game)
        };

        ContentDialog.showSingleChoiceList(activity, activity.getString(R.string.zip_kind_title, zip.getName()),
                kinds, which -> start(activity, zip, which == 0
                        ? ZipExtractor.Role.INSTALLER : ZipExtractor.Role.GAME, callback));
    }

    /**
     * Takes an archive from the file the user picked to the program inside it, asking about each
     * step that cannot be decided for them. The callback runs on the UI thread, and only when
     * there is something to carry on with: every way of stopping short ends in a dialog here.
     */
    public static void start(Activity activity, File zip, ZipExtractor.Role role, OnExecutableChosen callback) {
        if (!zip.isFile()) {
            ContentDialog.alert(activity, activity.getString(R.string.zip_file_not_found, zip.getName()), null);
            return;
        }

        File destDir = ZipExtractor.destinationFor(activity, zip);
        String winPath = "Z:\\" + ZipExtractor.EXTRACTED_DIR_NAME + "\\" + destDir.getName();

        // Unpacking over an archive that is already there would leave the two mixed together, so
        // what to do about it is the user's call -- and carrying on with what is already unpacked
        // saves doing a multi-gigabyte extraction twice.
        if (!FileUtils.isEmpty(destDir)) {
            ContentDialog dialog = new ContentDialog(activity);
            dialog.setTitle(zip.getName());
            dialog.setMessage(activity.getString(R.string.zip_already_extracted, winPath));
            ((TextView) dialog.findViewById(R.id.BTConfirm)).setText(R.string.zip_extract_again);
            ((TextView) dialog.findViewById(R.id.BTCancel)).setText(R.string.zip_use_existing);
            dialog.setOnConfirmCallback(() -> confirmExtract(activity, zip, destDir, winPath, role, callback));
            dialog.setOnCancelCallback(() -> chooseExecutable(activity, zip, destDir, role, callback));
            dialog.show();
            return;
        }

        confirmExtract(activity, zip, destDir, winPath, role, callback);
    }

    /**
     * States what unpacking will do before it is done, since it can run to gigabytes, and refuses
     * outright when there is not the room for it rather than filling the device and failing part
     * way through.
     */
    private static void confirmExtract(Activity activity, File zip, File destDir, String winPath,
                                       ZipExtractor.Role role, OnExecutableChosen callback) {
        PreloaderDialog sizingDialog = new PreloaderDialog(activity);
        sizingDialog.showOnUiThread(R.string.zip_reading_archive);

        Executors.newSingleThreadExecutor().execute(() -> {
            long size = ZipExtractor.uncompressedSize(zip);
            long free = ZipExtractor.usableSpaceFor(ZipExtractor.extractedRoot(activity));

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

                ContentDialog dialog = new ContentDialog(activity);
                dialog.setTitle(zip.getName());
                dialog.setMessage(activity.getString(R.string.zip_needs_extraction, winPath,
                        StringUtils.formatBytes(size)));
                ((TextView) dialog.findViewById(R.id.BTConfirm)).setText(R.string.zip_extract);
                dialog.setOnConfirmCallback(() -> extract(activity, zip, destDir, role, callback));
                dialog.show();
            });
        });
    }

    private static void extract(Activity activity, File zip, File destDir, ZipExtractor.Role role,
                                OnExecutableChosen callback) {
        PreloaderDialog preloaderDialog = new PreloaderDialog(activity);
        preloaderDialog.showOnUiThread(R.string.zip_extracting);
        final String extracting = activity.getString(R.string.zip_extracting);

        Executors.newSingleThreadExecutor().execute(() -> {
            // Anything already unpacked here is from an earlier run of the same archive, and was
            // replaced by the user's own choice.
            FileUtils.delete(destDir);

            String failure = null;
            try {
                ZipExtractor.extract(zip, destDir, new ZipExtractor.OnProgressListener() {
                    private int lastPercent = -1;

                    @Override
                    public void onProgress(String entryName, long bytesDone, long bytesTotal) {
                        int percent = bytesTotal > 0 ? (int) (bytesDone * 100 / bytesTotal) : 0;
                        if (percent == lastPercent) return;
                        lastPercent = percent;
                        preloaderDialog.updateText(extracting + "\n" + percent + "%");
                    }
                });
            }
            catch (IOException e) {
                Log.e(TAG, "Could not extract " + zip.getAbsolutePath(), e);
                failure = e.getMessage() != null ? e.getMessage() : e.toString();
                // A half-unpacked folder is worse than none: it would be offered as "already
                // extracted" next time round.
                FileUtils.delete(destDir);
            }

            final String error = failure;
            activity.runOnUiThread(() -> {
                preloaderDialog.close();
                if (error != null) {
                    ContentDialog.alert(activity, activity.getString(R.string.zip_extract_failed,
                            zip.getName(), error), null);
                    return;
                }
                chooseExecutable(activity, zip, destDir, role, callback);
            });
        });
    }

    /**
     * Settles which program in the unpacked folder the user meant.
     *
     * One is taken as the answer; several are put to the user in the order they are most likely
     * to want, since nothing about a folder of files says which of them is the game. Walking the
     * tree happens off the UI thread, as an unpacked archive can hold thousands of files.
     */
    private static void chooseExecutable(Activity activity, File zip, File destDir,
                                         ZipExtractor.Role role, OnExecutableChosen callback) {
        PreloaderDialog preloaderDialog = new PreloaderDialog(activity);
        preloaderDialog.showOnUiThread(R.string.zip_looking_for_programs);

        Executors.newSingleThreadExecutor().execute(() -> {
            List<File> executables = ZipExtractor.findExecutables(destDir, role);

            activity.runOnUiThread(() -> {
                preloaderDialog.close();

                if (executables.isEmpty()) {
                    int msgResId = role == ZipExtractor.Role.INSTALLER
                            ? R.string.zip_no_installer_inside
                            : R.string.zip_no_executable_inside;
                    ContentDialog.alert(activity, activity.getString(msgResId, zip.getName(),
                            "Z:\\" + ZipExtractor.EXTRACTED_DIR_NAME + "\\" + destDir.getName()), null);
                    return;
                }

                if (executables.size() == 1) {
                    offerToDeleteArchive(activity, zip, role, executables.get(0), destDir, callback);
                    return;
                }

                String[] names = new String[executables.size()];
                for (int i = 0; i < executables.size(); i++)
                    names[i] = ZipExtractor.relativeName(destDir, executables.get(i));

                int titleResId = role == ZipExtractor.Role.INSTALLER
                        ? R.string.zip_choose_installer_title
                        : R.string.zip_choose_game_title;

                ContentDialog.showSingleChoiceList(activity, activity.getString(titleResId, zip.getName()), names,
                        which -> offerToDeleteArchive(activity, zip, role, executables.get(which), destDir, callback));
            });
        });
    }

    /**
     * The archive has served its purpose once its contents are on disk, and it is normally the
     * larger of the two, so removing it is offered -- but not assumed: it is the user's download,
     * and they may want to keep it.
     */
    private static void offerToDeleteArchive(Activity activity, File zip, ZipExtractor.Role role,
                                             File executable, File destDir, OnExecutableChosen callback) {
        if (!zip.isFile()) {
            callback.onChosen(role, executable, destDir);
            return;
        }

        ContentDialog dialog = new ContentDialog(activity);
        dialog.setTitle(activity.getString(R.string.zip_delete_archive_title));
        dialog.setMessage(activity.getString(R.string.zip_delete_archive,
                zip.getName(), StringUtils.formatBytes(zip.length()),
                ZipExtractor.relativeName(destDir, executable)));
        ((TextView) dialog.findViewById(R.id.BTConfirm)).setText(R.string.zip_delete_archive_confirm);
        ((TextView) dialog.findViewById(R.id.BTCancel)).setText(R.string.zip_keep_archive);
        dialog.setOnConfirmCallback(() -> {
            if (!zip.delete()) Log.w(TAG, "Could not delete " + zip.getAbsolutePath());
            callback.onChosen(role, executable, destDir);
        });
        dialog.setOnCancelCallback(() -> callback.onChosen(role, executable, destDir));
        dialog.show();
    }
}
