package com.winlator.cmod;

import static androidx.core.content.ContextCompat.getSystemService;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileObserver;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.tabs.TabLayout;
import com.winlator.xr.XrActivity;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contentdialog.ShortcutSettingsDialog;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.container.GameCopier;
import com.winlator.cmod.container.GameUninstaller;
import com.winlator.cmod.core.GuestScriptRunner;
import com.winlator.cmod.core.LaunchReport;
import com.winlator.cmod.core.MSLink;
import com.winlator.cmod.contents.ModInstaller;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.contents.RedistInstaller;
import com.winlator.cmod.container.ShortcutCreator;
import com.winlator.cmod.container.ShortcutProfile;
import com.winlator.cmod.container.ShortcutSource;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.core.ZipExtractor;
import com.winlator.cmod.core.ZipImport;
import com.winlator.cmod.store.StoreGameInstall;
import com.winlator.xr.utils.XrDevice;
import com.winlator.xr.utils.GoldbergEmu;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

public class ShortcutsFragment extends Fragment {
    public static final String HIDDEN_SHORTCUT = "runtime-installer";

    private RecyclerView recyclerView;
    private TextView emptyTextView;
    private ContainerManager manager;
    private Shortcut currentShortcut;
    /**
     * The shortcut whose game is being uninstalled in a container session we are waiting on.
     *
     * It is written down rather than held in a field: starting a session in VR finishes this
     * activity, so by the time the user is back this fragment is a new one and a field would
     * have gone with the old.
     */
    private static final String PENDING_UNINSTALL_PREFS = "pending_uninstall";
    private static final String PENDING_UNINSTALL_PATH = "shortcut_path";
    private static final String PENDING_UNINSTALL_CONTAINER = "container_id";

    private ArrayList<FileObserver> fileObservers = new ArrayList<>();
    private PreloaderDialog preloaderDialog;

    public static int currentTab = 0;

    private final ActivityResultLauncher<Intent> iconPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null && currentShortcut != null) {
                        // This is where you will handle the selected .ico file
                        handleSelectedIcon(uri);
                    }
                }
            });

    private void openIconPicker(Shortcut shortcut) {
        this.currentShortcut = shortcut;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/x-icon"); // Set the primary MIME type for .ico files

        // Provide an array of possible MIME types to be more compatible
        String[] mimeTypes = {"image/x-icon", "image/vnd.microsoft.icon"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);

        iconPickerLauncher.launch(intent);
    }

    private final ActivityResultLauncher<Intent> localGamePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) handlePickedExeUri(uri);
                }
            });

    /**
     * Toast windows render broken/invisible under the Quest Navigator's panel compositor, so
     * feedback for this flow is shown as a dialog over the Shortcuts screen instead.
     */
    private void showLocalGameMessage(String message) {
        if (getContext() == null) return;
        new AlertDialog.Builder(getContext())
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void openAddLocalGamePicker() {
        // Android's own picker hides the whole Download folder when its restricted-path check
        // fails, showing "No items" although the files are there. This browses Download directly.
        if (PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getBoolean("browse_download_with_winlator", false)) {
            browseDownloadFolder(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
            return;
        }

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");

        // EXTRA_INITIAL_URI needs a real SAF document Uri, not a file:// Uri (which most
        // document picker implementations silently ignore, falling back to their default root).
        Uri initialUri = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:" + Environment.DIRECTORY_DOWNLOADS);
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri);

        try {
            localGamePickerLauncher.launch(intent);
        } catch (ActivityNotFoundException e) {
            showLocalGameMessage("No file picker app is available on this device.");
        }
    }

    /** Browses Download and its subfolders only, listing what a shortcut can be made from. */
    private void browseDownloadFolder(File dir) {
        if (getContext() == null) return;

        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File[] files = dir.listFiles((file) -> {
            if (file.isDirectory()) return true;
            String name = file.getName().toLowerCase(Locale.ROOT);
            return name.endsWith(".exe") || name.endsWith(".zip");
        });
        if (files == null) files = new File[0];

        ArrayList<File> entries = new ArrayList<>();
        for (File file : files) if (file.isDirectory()) entries.add(file);
        for (File file : files) if (!file.isDirectory()) entries.add(file);

        // The root gets no ".." entry, so nothing above Download can be reached from here.
        boolean atRoot = dir.getAbsolutePath().equals(downloadDir.getAbsolutePath());
        ArrayList<String> labels = new ArrayList<>();
        if (!atRoot) labels.add("..");
        for (File file : entries) labels.add(file.isDirectory() ? file.getName() + "/" : file.getName());

        new AlertDialog.Builder(getContext())
                .setTitle(atRoot ? Environment.DIRECTORY_DOWNLOADS : dir.getName())
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (!atRoot && which == 0) {
                        browseDownloadFolder(dir.getParentFile());
                        return;
                    }
                    File picked = entries.get(atRoot ? which : which - 1);
                    if (picked.isDirectory()) browseDownloadFolder(picked);
                    else handlePickedExeFile(picked);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The game a picked mod archive is being installed into, held across the picker. */
    private Shortcut modTargetShortcut;

    private final ActivityResultLauncher<Intent> modZipPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) handlePickedModZipUri(uri);
                }
            });

    private void openModZipPicker(Shortcut shortcut) {
        this.modTargetShortcut = shortcut;

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // Not filtered to application/zip: a .zip handed over by a browser or a file manager
        // arrives under half a dozen different MIME types, and filtering on them hides the file
        // the user is looking straight at. The extension is checked instead once it is picked.
        intent.setType("*/*");

        Uri initialUri = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:" + Environment.DIRECTORY_DOWNLOADS);
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri);

        try {
            modZipPickerLauncher.launch(intent);
        } catch (ActivityNotFoundException e) {
            showLocalGameMessage("No file picker app is available on this device.");
        }
    }

    private void handlePickedModZipUri(Uri uri) {
        Shortcut shortcut = modTargetShortcut;
        modTargetShortcut = null;
        if (shortcut == null || getActivity() == null) return;

        String path = FileUtils.getFilePathFromDocumentUri(getContext(), uri);
        if (path == null) {
            showLocalGameMessage("Could not resolve the selected file's location. Please pick a file from local storage.");
            return;
        }

        File pickedFile = new File(path);
        if (!pickedFile.isFile()) {
            showLocalGameMessage("Selected file could not be found.");
            return;
        }

        if (!ZipImport.isZip(pickedFile)) {
            showLocalGameMessage(getString(R.string.mod_zip_only));
            return;
        }

        ModInstaller.start(getActivity(), shortcut, pickedFile, this::loadShortcutsList);
    }

    private void handlePickedExeUri(Uri uri) {
        String path = FileUtils.getFilePathFromDocumentUri(getContext(), uri);
        if (path == null) {
            showLocalGameMessage("Could not resolve the selected file's location. Please pick a file from local storage.");
            return;
        }

        handlePickedExeFile(new File(path));
    }

    private void handlePickedExeFile(File pickedFile) {
        if (!pickedFile.isFile()) {
            showLocalGameMessage("Selected file could not be found.");
            return;
        }

        // A game that arrives zipped has no .exe to point a shortcut at until it is unpacked, so
        // that is done first and the shortcut is made for whichever program comes out of it.
        if (ZipImport.isZip(pickedFile)) {
            ZipImport.start(getActivity(), pickedFile, ZipExtractor.Role.GAME,
                    (role, executable, extractedDir) -> chooseContainerForExe(executable));
            return;
        }

        if (!pickedFile.getName().toLowerCase(Locale.ROOT).endsWith(".exe")) {
            showLocalGameMessage("Please select a .exe file, or a .zip holding one.");
            return;
        }

        chooseContainerForExe(pickedFile);
    }

    /* ------------------------------------------------------------------ */
    /*                       settings profiles                            */
    /* ------------------------------------------------------------------ */

    /**
     * The shortcut a profile is being imported onto.
     *
     * Held separately from {@link #currentShortcut}, which the icon picker owns: both flows leave
     * the app for a file picker and would otherwise be writing over each other's answer.
     */
    private Shortcut profileShortcut;

    private final ActivityResultLauncher<Intent> profileImportLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
                Uri uri = result.getData().getData();
                if (uri != null && profileShortcut != null) readProfileFrom(profileShortcut, uri);
            });

    /** Toasts render broken under the Quest panel compositor, so this screen says things in dialogs. */
    private void showProfileMessage(String message) {
        if (getContext() == null) return;
        new AlertDialog.Builder(getContext())
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /**
     * Profiles go to one known folder rather than wherever a picker was last pointed, so a user
     * told where their profiles are is told something that is true of all of them.
     */
    public void exportSettingsProfile(Shortcut shortcut) {
        File destination = ShortcutProfile.exportFileFor(shortcut);

        // The only export that can destroy something is the second one for the same game, or one
        // landing on a profile that arrived under the same name. Both are worth a question.
        if (destination.isFile()) {
            new AlertDialog.Builder(getContext())
                    .setTitle(R.string.export_profile)
                    .setMessage(getString(R.string.settings_profile_export_replace,
                            destination.getName(), destination.getParent()))
                    .setPositiveButton(R.string.settings_profile_export_replace_confirm,
                            (dialog, which) -> writeProfile(shortcut))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }

        writeProfile(shortcut);
    }

    private void writeProfile(Shortcut shortcut) {
        String error = ShortcutProfile.export(shortcut);
        showProfileMessage(error == null
                ? getString(R.string.settings_profile_exported, shortcut.name,
                        ShortcutProfile.exportFileFor(shortcut).getAbsolutePath())
                : getString(R.string.settings_profile_export_failed, error));
    }

    public void importSettingsProfile(Shortcut shortcut) {
        profileShortcut = shortcut;

        // Picked as any file rather than as application/json: a profile copied off a PC or out of
        // a chat app often arrives typed as something else, and a picker that hides it is worse
        // than one that lets a wrong file through -- reading it says so plainly either way.
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        // Opens on the folder exports go to, which is where a profile that came from somewhere
        // else was most likely dropped -- but still a picker, since a received one can be
        // anywhere and the folder is a convenience rather than a rule for reading.
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:" + Environment.DIRECTORY_DOWNLOADS + "/Winlator/WxrProfiles"));

        try {
            profileImportLauncher.launch(intent);
        }
        catch (ActivityNotFoundException e) {
            showProfileMessage(getString(R.string.settings_profile_no_picker));
        }
    }

    private void readProfileFrom(Shortcut shortcut, Uri source) {
        ShortcutProfile.ReadResult result = ShortcutProfile.read(getContext(), source);
        if (result.profile == null) {
            showProfileMessage(getString(R.string.settings_profile_import_failed, result.error));
            return;
        }

        final ShortcutProfile.Parsed profile = result.profile;
        // A profile from another game is the interesting case, not the error case: one that works
        // for a game usually works for the next one built on the same engine. It is worth saying
        // out loud whose settings these are, and then applying them anyway if that is wanted.
        String message = profile.matches(shortcut)
                ? getString(R.string.settings_profile_import_same_game,
                        profile.settingCount(), profile.gameName, shortcut.name)
                : getString(R.string.settings_profile_import_other_game,
                        profile.gameName, profile.settingCount(), shortcut.name);

        // Which headset tuned these settings is context rather than a warning: the same values
        // do not land the same way on every headset, so a profile that crossed over is a
        // starting point, not an answer.
        if (profile.isDifferentDevice()) {
            message += getString(R.string.settings_profile_import_other_device,
                    profile.deviceName, XrDevice.getDisplayName());
        }

        // Crossing between the x86 and ARM64EC sides is the one difference that reliably changes
        // what the settings mean, so it is said before the import rather than left to be worked
        // out from a game that will not start.
        if (profile.isArchMismatch(shortcut)) {
            message += getString(R.string.settings_profile_import_arch_mismatch,
                    WineInfo.archLabel(profile.arch()), profile.wineVersion,
                    WineInfo.archLabel(WineInfo.archFromIdentifier(
                            shortcut.container.getWineVersion())),
                    shortcut.container.getWineVersion());
        }

        new AlertDialog.Builder(getContext())
                .setTitle(R.string.settings_profile_import_title)
                .setMessage(message)
                .setPositiveButton(R.string.settings_profile_import_confirm, (dialog, which) -> {
                    ShortcutProfile.apply(shortcut, profile, profile.gameName);
                    loadShortcutsList();
                    showProfileMessage(getString(R.string.settings_profile_imported,
                            shortcut.name, profile.gameName));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    public void removeSettingsProfile(Shortcut shortcut) {
        String profileName = ShortcutProfile.appliedName(shortcut);
        if (profileName == null) return;

        new AlertDialog.Builder(getContext())
                .setTitle(R.string.settings_profile_remove_title)
                .setMessage(getString(R.string.settings_profile_remove_message, shortcut.name, profileName))
                .setPositiveButton(R.string.remove_profile, (dialog, which) -> {
                    ShortcutProfile.remove(shortcut);
                    loadShortcutsList();
                    showProfileMessage(getString(R.string.settings_profile_removed, shortcut.name));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** A game can be played from any container that reaches it, so which one is the user's call. */
    private void chooseContainerForExe(File exeFile) {
        // Offering to make the container is worth doing from here, where creating one is a screen
        // away, rather than leaving the user to find their own way to it.
        if (manager.getContainers().isEmpty()) {
            new AlertDialog.Builder(getContext())
                    .setTitle("No containers found")
                    .setMessage("You need at least one container before adding a game shortcut. Create one now?")
                    .setPositiveButton("Create Container", (dialog, which) -> openCreateContainer())
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        ShortcutCreator.createForExecutable(getActivity(), exeFile, this::loadShortcutsList);
    }

    /**
     * Removing a shortcut leaves the game itself installed, which is right when the shortcut was
     * the mistake and wrong when the game was. The choice is put to the user rather than guessed
     * at, with the harmless option first.
     */
    private void showRemoveShortcutDialog(final Shortcut shortcut) {
        String[] options = {
            getString(R.string.remove_shortcut_only),
            getString(R.string.remove_shortcut_and_game)
        };

        new AlertDialog.Builder(getContext())
                .setTitle(getString(R.string.remove_shortcut_title, shortcut.name))
                .setItems(options, (dialog, which) -> {
                    if (which == 0) removeShortcut(shortcut);
                    else confirmUninstallGame(shortcut);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void removeShortcut(Shortcut shortcut) {
        boolean desktopDeleted = safeDelete(shortcut.file);
        boolean lnkDeleted = deletePairedLnkForShortcut(shortcut);
        safeDelete(shortcut.iconFile);

        if (desktopDeleted) {
            disableShortcutOnScreen(requireContext(), shortcut);
            loadShortcutsList();
        }

        String msg;
        if (desktopDeleted) {
            msg = lnkDeleted
                    ? "Shortcut and paired .lnk removed."
                    : "Shortcut removed. (No paired .lnk found or could not delete.)";
        } else {
            msg = "Failed to remove the shortcut. Please try again.";
        }
        Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
    }

    /**
     * Uninstalls through whatever the game left behind to be uninstalled with, and falls back to
     * deleting its folder for one that was copied in and so has no uninstaller.
     */
    private void confirmUninstallGame(final Shortcut shortcut) {
        GameUninstaller.UninstallEntry entry =
                GameUninstaller.findUninstallEntry(getContext(), shortcut.container, shortcut);
        // A game whose entry is missing, or recorded against a path that does not read back the
        // same, still has its uninstaller sitting in its folder.
        if (entry == null) entry = GameUninstaller.findUninstallerInFolder(getContext(), shortcut.container, shortcut);

        if (entry != null) {
            final GameUninstaller.UninstallEntry uninstallEntry = entry;
            String name = uninstallEntry.displayName != null ? uninstallEntry.displayName : shortcut.name;
            new AlertDialog.Builder(getContext())
                    .setTitle(R.string.uninstall_game_title)
                    .setMessage(getString(R.string.uninstall_game_message, name))
                    .setPositiveButton(R.string.uninstall, (dialog, which) -> {
                        // The uninstaller runs in its own container session, so whether it
                        // succeeded is only known once the user is back: the shortcut is left
                        // alone until they say so.
                        getContext().getSharedPreferences(PENDING_UNINSTALL_PREFS, Context.MODE_PRIVATE)
                                .edit()
                                .putString(PENDING_UNINSTALL_PATH, shortcut.file.getAbsolutePath())
                                .putInt(PENDING_UNINSTALL_CONTAINER, shortcut.container.id)
                                .apply();
                        GameUninstaller.runUninstaller(getActivity(), shortcut.container, uninstallEntry);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }

        // A game one of the stores downloaded has no uninstaller either, but where its files are
        // is known rather than guessed at, so it is removed the way the store removes it.
        StoreGameInstall storeInstall = StoreGameInstall.find(getContext(),
                GameUninstaller.resolveExecutable(getContext(), shortcut.container, shortcut));
        if (storeInstall != null) {
            confirmDeleteStoreInstall(shortcut, storeInstall);
            return;
        }

        confirmDeleteInstallDir(shortcut);
    }

    /**
     * The stores install onto Z:, outside every container, so one download serves each container
     * that has a shortcut to it. Removing one is put to the user as what it really is -- taking
     * the game away from all of them -- and the shortcuts left over in the others go with it.
     */
    private void confirmDeleteStoreInstall(final Shortcut shortcut, final StoreGameInstall install) {
        final List<Shortcut> others = GameUninstaller.shortcutsInside(
                getContext(), manager, install.installDir, shortcut);

        // Walking a game folder takes long enough to be worth keeping off the UI thread.
        Executors.newSingleThreadExecutor().execute(() -> {
            long size = GameUninstaller.folderSize(install.installDir);

            Activity activity = getActivity();
            if (activity == null) return;

            String winPath = GuestScriptRunner.toWinPath(getContext(), shortcut.container, install.installDir);
            String message = getString(R.string.uninstall_store_game_message,
                    shortcut.name, install.storeName,
                    winPath != null ? winPath : install.installDir.getAbsolutePath(),
                    StringUtils.formatBytes(size));
            if (!others.isEmpty()) {
                StringBuilder names = new StringBuilder();
                for (Shortcut other : others)
                    names.append("\n• ").append(other.name)
                         .append(" (").append(other.container.getName()).append(")");
                message += "\n\n" + getString(R.string.uninstall_store_game_other_shortcuts, names.toString());
            }

            final String text = message;
            activity.runOnUiThread(() -> new AlertDialog.Builder(getContext())
                    .setTitle(R.string.uninstall_store_game_title)
                    .setMessage(text)
                    .setPositiveButton(R.string.uninstall, (dialog, which) -> deleteStoreInstall(shortcut, install, others))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show());
        });
    }

    /** Deletes the game the store installed, then every shortcut that pointed into it. */
    private void deleteStoreInstall(final Shortcut shortcut, final StoreGameInstall install,
                                    final List<Shortcut> others) {
        preloaderDialog.showOnUiThread(R.string.deleting_game_folder);
        // The work outlives this fragment being torn down, and the store's records are the
        // application's rather than this screen's.
        final Context context = requireContext().getApplicationContext();

        Executors.newSingleThreadExecutor().execute(() -> {
            boolean deleted = FileUtils.delete(install.installDir);
            // A store page goes by its own record of what is installed rather than by the disk,
            // so it keeps offering to launch a game whose files are gone until that record goes.
            if (deleted) install.forget(context);

            Activity activity = getActivity();
            if (activity == null) return;

            activity.runOnUiThread(() -> {
                preloaderDialog.close();
                if (!deleted) {
                    showLocalGameMessage(getString(R.string.delete_install_dir_failed, install.installDir.getName()));
                    return;
                }
                for (Shortcut other : others) deleteShortcutFiles(other);
                removeShortcut(shortcut);
            });
        });
    }

    /** The files a shortcut is made of, for the ones being removed alongside a game. */
    void deleteShortcutFiles(Shortcut shortcut) {
        boolean deleted = safeDelete(shortcut.file);
        deletePairedLnkForShortcut(shortcut);
        safeDelete(shortcut.iconFile);
        if (deleted) disableShortcutOnScreen(requireContext(), shortcut);
    }

    /**
     * The last resort for a game with no uninstaller: deleting its folder. It is spelled out in
     * full -- the path, its size, and anything else that would be left pointing at it -- because
     * nothing here can be undone.
     */
    private void confirmDeleteInstallDir(final Shortcut shortcut) {
        File installDir = GameUninstaller.findInstallDir(getContext(), shortcut.container, shortcut);
        if (installDir == null) {
            showLocalGameMessage(getString(R.string.no_uninstaller_and_unsafe_to_delete));
            return;
        }

        List<Shortcut> others = GameUninstaller.shortcutsInside(
                getContext(), shortcut.container, installDir, shortcut);

        // Walking a game's folder takes long enough to be worth keeping off the UI thread.
        Executors.newSingleThreadExecutor().execute(() -> {
            long size = GameUninstaller.folderSize(installDir);

            Activity activity = getActivity();
            if (activity == null) return;

            // Named by the path the game knows itself by, so it can be checked at a glance.
            String winPath = GuestScriptRunner.toWinPath(getContext(), shortcut.container, installDir);
            String message = getString(R.string.delete_install_dir_message,
                    winPath != null ? winPath : installDir.getAbsolutePath(), StringUtils.formatBytes(size));
            if (!others.isEmpty()) {
                StringBuilder names = new StringBuilder();
                for (Shortcut other : others) names.append("\n• ").append(other.name);
                message += "\n\n" + getString(R.string.delete_install_dir_other_shortcuts, names.toString());
            }

            final String text = message;
            activity.runOnUiThread(() -> new AlertDialog.Builder(getContext())
                    .setTitle(R.string.delete_install_dir_title)
                    .setMessage(text)
                    .setPositiveButton(R.string.delete_folder, (dialog, which) -> deleteInstallDir(shortcut, installDir))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show());
        });
    }

    /** Deleting a game's folder can run to gigabytes, so it does not happen on the UI thread. */
    private void deleteInstallDir(final Shortcut shortcut, final File installDir) {
        preloaderDialog.showOnUiThread(R.string.deleting_game_folder);

        Executors.newSingleThreadExecutor().execute(() -> {
            boolean deleted = FileUtils.delete(installDir);

            Activity activity = getActivity();
            if (activity == null) return;

            activity.runOnUiThread(() -> {
                preloaderDialog.close();
                if (deleted) removeShortcut(shortcut);
                else showLocalGameMessage(getString(R.string.delete_install_dir_failed, installDir.getName()));
            });
        });
    }

    private void openCreateContainer() {
        getParentFragmentManager().beginTransaction()
                .setCustomAnimations(R.anim.slide_in_up, R.anim.slide_out_down, R.anim.slide_in_down, R.anim.slide_out_up)
                .addToBackStack(null)
                .replace(R.id.FLFragmentContainer, new ContainerDetailFragment())
                .commit();
    }

    private void showIconPickerConfirmation(final Shortcut shortcut) {
        new AlertDialog.Builder(getContext())
                .setTitle("Custom Icon")
                .setMessage("You will be prompted to select an icon file. Please choose a valid .ico file.")
                .setPositiveButton("Continue", (dialog, which) -> {
                    // This will launch the file picker
                    openIconPicker(shortcut);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void handleSelectedIcon(Uri icoFileUri) {
        try {
            File iconsDir = new File(currentShortcut.container.getIconsDir(0).getParentFile(), "custom_icons");
            if (!iconsDir.exists()) {
                iconsDir.mkdirs();
            }

            // Use the shortcut's unique name to create a unique icon filename
            String newIconFileName = currentShortcut.name + "_" + System.currentTimeMillis() + ".ico";
            File newIconFile = new File(iconsDir, newIconFileName);

            // Use the correct copy method from your FileUtils class
            if (FileUtils.copy(getContext(), icoFileUri, newIconFile)) {
                // Update the shortcut to point to this new icon
                currentShortcut.setCustomIconPath(newIconFile.getAbsolutePath());

                // Reload the list to show the new icon
                loadShortcutsList();
                Toast.makeText(getContext(), "Icon updated successfully.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(getContext(), "Failed to copy icon file.", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(getContext(), "Failed to update icon.", Toast.LENGTH_SHORT).show();
            Log.e("ShortcutsFragment", "Error handling selected icon", e);
        }
    }


    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onResume() {
        super.onResume();

        // Back from an uninstaller session. Nothing out here can tell whether it did the job, so
        // the shortcut goes on the user's word rather than on a guess.
        SharedPreferences pending = getContext().getSharedPreferences(PENDING_UNINSTALL_PREFS, Context.MODE_PRIVATE);
        String path = pending.getString(PENDING_UNINSTALL_PATH, null);
        int containerId = pending.getInt(PENDING_UNINSTALL_CONTAINER, 0);
        if (path == null) return;
        pending.edit().clear().apply();

        File shortcutFile = new File(path);
        Container container = manager != null ? manager.getContainerById(containerId) : null;
        if (container == null || !shortcutFile.isFile()) return;

        final Shortcut shortcut = new Shortcut(container, shortcutFile);
        new AlertDialog.Builder(getContext())
                .setTitle(R.string.uninstall_finished_title)
                .setMessage(getString(R.string.uninstall_finished_message, shortcut.name))
                .setPositiveButton(R.string.remove_shortcut, (dialog, which) -> removeShortcut(shortcut))
                .setNegativeButton(R.string.keep_shortcut, null)
                .show();
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        menu.clear();
        inflater.inflate(R.menu.shortcuts_menu, menu);
        menu.findItem(R.id.shortcuts_menu_add_local_game).setVisible(currentTab == 0);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem menuItem) {
        if (menuItem.getItemId() == R.id.shortcuts_menu_add_local_game) {
            showAddGameDialog();
            return true;
        }
        return super.onOptionsItemSelected(menuItem);
    }

    /**
     * A game arrives either as files that are already installed, or as an installer that still
     * has to be run in a container, and only the user knows which one they have.
     */
    private void showAddGameDialog() {
        String[] options = {
            getString(R.string.add_local_game_files),
            getString(R.string.run_game_installer)
        };

        new AlertDialog.Builder(getContext())
                .setTitle(R.string.add_game)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) openAddLocalGamePicker();
                    else if (getActivity() instanceof MainActivity) ((MainActivity) getActivity()).showAddInstaller();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopFileObservers(); // Stop watching to prevent memory leaks
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        manager = new ContainerManager(getContext());
        preloaderDialog = new PreloaderDialog(getActivity());
        loadShortcutsList();
        startFileObservers(); // Start watching for new file
        ((AppCompatActivity)getActivity()).getSupportActionBar().setTitle(R.string.games);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        FrameLayout frameLayout = (FrameLayout)inflater.inflate(R.layout.shortcuts_fragment, container, false);
        recyclerView = frameLayout.findViewById(R.id.RecyclerView);
        emptyTextView = frameLayout.findViewById(R.id.TVEmptyText);
        recyclerView.setLayoutManager(new LinearLayoutManager(recyclerView.getContext()));
        recyclerView.addItemDecoration(new DividerItemDecoration(recyclerView.getContext(), DividerItemDecoration.VERTICAL));

        // Tab switcher
        TabLayout tabLayout = frameLayout.findViewById(R.id.TabLayout);
        tabLayout.setTabTextColors(Color.LTGRAY, Color.WHITE);
        tabLayout.setSelectedTabIndicatorColor(Color.WHITE);
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                View[] tabs = {
                        frameLayout.findViewById(R.id.LLTabShortcuts),
                        frameLayout.findViewById(R.id.LLTabSaves),
                        frameLayout.findViewById(R.id.LLTabZDrive)
                };

                currentTab = tab.getPosition();
                for (int i = 0; i < tabs.length; i++) {
                    if (i == currentTab) {
                        tabs[i].setVisibility(View.VISIBLE);
                    } else {
                        tabs[i].setVisibility(View.GONE);
                    }
                }
                // The Z: tab reads the disk, so it is filled in when it is opened rather than kept
                // up to date behind the user.
                if (currentTab == 2) refreshZDriveTab();
                requireActivity().invalidateOptionsMenu();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                onTabSelected(tab);
            }
        });
        tabLayout.selectTab(tabLayout.getTabAt(0));
        return frameLayout;
    }

    /**
     * Fills in the Z: Drive tab, which lists the games installed outside every container.
     *
     * It is a child fragment of this screen rather than a screen of its own, and it is created
     * whether or not its tab is the one showing, so the scan waits until the user asks for it.
     */
    private void refreshZDriveTab() {
        Fragment fragment = getChildFragmentManager().findFragmentById(R.id.LLTabZDrive);
        if (fragment instanceof ZDriveFragment) ((ZDriveFragment)fragment).refresh();
    }

    private Container findContainerForFile(File file) {
        for (Container container : manager.getContainers()) {
            if (file.getAbsolutePath().startsWith(container.getDesktopDir().getAbsolutePath())) {
                return container;
            }
        }
        return null;
    }
    private void startFileObservers() {
        stopFileObservers();
        ArrayList<Container> containers = manager.getContainers();
        ArrayList<File> orphanedLinks = new ArrayList<>();
        Log.d("ShortcutObserver", "Starting observers for " + containers.size() + " containers.");

        for (Container container : containers) {
            File desktopDir = container.getDesktopDir();
            Log.d("ShortcutObserver", "Checking container " + container.id + " at path: " + desktopDir.getAbsolutePath());

            if (desktopDir.exists() && desktopDir.isDirectory()) {
                File[] files = desktopDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.getName().toLowerCase().endsWith(".lnk")) {
                            String desktopFileName = FileUtils.getBasename(file.getName()) + ".desktop";
                            File desktopFile = new File(desktopDir, desktopFileName);
                            if (!desktopFile.exists()) {
                                Log.d("ShortcutObserver", "Found orphaned .lnk file: " + file.getName());
                                orphanedLinks.add(file);
                            }
                        }
                    }
                }

                FileObserver observer = new FileObserver(desktopDir, FileObserver.CREATE) {
                    @Override
                    public void onEvent(int event, @Nullable String path) {
                        if (path != null && path.toLowerCase().endsWith(".lnk")) {
                            Log.d("ShortcutObserver", "New .lnk file created: " + path);
                            final File newLnkFile = new File(desktopDir, path);
                            if (getActivity() != null) {
                                getActivity().runOnUiThread(() -> {
                                    preloaderDialog.show(R.string.creating_shortcut);
                                    processNewLinkFile(newLnkFile, container);
                                });
                            }
                        }
                    }
                };
                observer.startWatching();
                fileObservers.add(observer);
            } else {
                Log.w("ShortcutObserver", "Desktop directory does not exist for container " + container.id + ": " + desktopDir.getAbsolutePath());
            }
        }

        if (!orphanedLinks.isEmpty()) {
            Log.d("ShortcutObserver", "Processing " + orphanedLinks.size() + " orphaned links.");
            preloaderDialog.show(R.string.creating_shortcut);
            processOrphanedLinkFiles(orphanedLinks);
        } else {
            Log.d("ShortcutObserver", "No orphaned links found.");
        }
    }

    private void processOrphanedLinkFiles(ArrayList<File> lnkFiles) {
        Executors.newSingleThreadExecutor().execute(() -> {
            boolean shortcutsChanged = false;
            try {
                for (File lnkFile : lnkFiles) {
                    Container owner = findContainerForFile(lnkFile);
                    if (owner != null) {
                        if (createDesktopFileFromLnk(lnkFile, owner)) {
                            shortcutsChanged = true;
                        }
                    }
                }
            } finally {
                if (getActivity() != null) {
                    final boolean finalShortcutsChanged = shortcutsChanged;
                    getActivity().runOnUiThread(() -> {
                        if (preloaderDialog != null) preloaderDialog.close();
                        if (finalShortcutsChanged) {
                            loadShortcutsList();
                            Toast.makeText(getContext(), "Shortcuts updated.", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        });
    }

    private void processNewLinkFile(File lnkFile, Container container) {
        Executors.newSingleThreadExecutor().execute(() -> {
            boolean shortcutCreated = false;
            try {
                shortcutCreated = createDesktopFileFromLnk(lnkFile, container);
            } finally {
                if (getActivity() != null) {
                    final boolean finalShortcutCreated = shortcutCreated;
                    getActivity().runOnUiThread(() -> {
                        if (preloaderDialog != null) preloaderDialog.close();
                        if (finalShortcutCreated) {
                            loadShortcutsList();
                            Toast.makeText(getContext(), "New shortcut created!", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        });
    }




    private boolean createDesktopFileFromLnk(File lnkFile, Container container) {
        try {
            Log.d("ShortcutCreation", "Processing .lnk: " + lnkFile.getAbsolutePath());
            String targetPath = MSLink.parse(lnkFile); // e.g., "D:\\Games\\Example Folder\\Example.exe"

            if (targetPath == null || targetPath.isEmpty() || !targetPath.contains(":")) {
                Log.e("ShortcutCreation", "Failed to parse a valid target path from " + lnkFile.getName());
                return false;
            }
            Log.d("ShortcutCreation", "Parsed target path: " + targetPath);

            String lnkName = FileUtils.getBasename(lnkFile.getName());
            File desktopFile = new File(container.getDesktopDir(), lnkName + ".desktop");

            if (desktopFile.exists()) {
                Log.d("ShortcutCreation", "Skipping existing .desktop file: " + desktopFile.getName());
                return false;
            }

            // --- FINAL LOGIC TO MATCH YOUR EXACT EXAMPLE ---

            // 1. Get the app's root files directory to construct the generic path
            String filesDir = getContext().getFilesDir().getAbsolutePath(); // e.g., /data/user/0/com.winlator.cmod/files
            String genericHomePath = filesDir + "/imagefs/home/xuser";

            // 2. Construct the specific, complex WINEPREFIX path
            String winePrefix = genericHomePath + "/.wine/dosdevices/z:" + genericHomePath + "/.wine";

            // 3. To get "\\\\", we need to escape twice. Once for Java, once for the file.
            String escapedTargetPath = targetPath.replace("\\", "\\\\\\\\");

            // 4. Construct the full Exec command
            String execCommand = "env WINEPREFIX=\"" + winePrefix + "\" wine " + escapedTargetPath;

            // 5. Construct the working directory Path using the generic dosdevices path
            File genericDosdevicesDir = new File(genericHomePath, ".wine/dosdevices");
            String driveLetter = targetPath.substring(0, 1).toLowerCase();
            File driveSymlink = new File(genericDosdevicesDir, driveLetter + ":");
            String pathAfterDrive = targetPath.substring(targetPath.indexOf('\\') + 1);
            String windowsWorkingDir = FileUtils.getDirname(pathAfterDrive);
            String finalWorkingPath = new File(driveSymlink, windowsWorkingDir).getAbsolutePath();

            // 6. Get the executable name for StartupWMClass
            String wmClass = FileUtils.getName(targetPath);

            // 7. Construct the final .desktop file content
            String content =
                    "[Desktop Entry]\n" +
                            "Name=" + lnkName + "\n" +
                            "Exec=" + execCommand + "\n" +
                            "Type=Application\n" +
                            "StartupNotify=true\n" +
                            "Path=" + finalWorkingPath + "\n" +
                            "Icon=\n" +
                            "StartupWMClass=" + wmClass + "\n\n" +
                            "[Extra Data]\n" +
                            "container_id:" + container.id + "\n";

            FileUtils.writeString(desktopFile, content);
            Log.d("ShortcutCreation", "SUCCESS: Created .desktop file at " + desktopFile.getAbsolutePath());
            Log.d("ShortcutCreation", "Content:\n" + content);

            return true;
        } catch (IOException e) {
            Log.e("ShortcutCreation", "IOException creating .desktop file from .lnk", e);
            return false;
        }
    }



    private void stopFileObservers() {
        for (FileObserver observer : fileObservers) {
            observer.stopWatching();
        }
        fileObservers.clear();
    }
    public void loadShortcutsList() {

        ArrayList<Shortcut> shortcuts = new ArrayList<>();
        ArrayList<File> quarantined   = new ArrayList<>();

        // ContainerManager can still throw (e.g. I/O permission issues).
        // Keep the whole call in one try/catch so the UI never dies.
        try {
            for (Container c : manager.getContainers()) {
                for (File f : c.getDesktopDir().listFiles((dir, n) -> n.endsWith(".desktop"))) {
                    if (f.getAbsolutePath().contains(HIDDEN_SHORTCUT)) {
                        continue;
                    }

                    try {
                        Shortcut s = new Shortcut(c, f);   // may throw
                        // very cheap logical sanity check
                        if (s.name == null || s.name.trim().isEmpty()) {
                            throw new IllegalStateException("empty name");
                        }
                        shortcuts.add(s);

                    } catch (Throwable t) {               // <-- swallow & quarantine
                        Log.e("ShortcutsFragment", "Bad shortcut: " + f.getAbsolutePath(), t);
                        quarantined.add(f);
                    }
                }
            }

        } catch (Throwable fatal) {
            Log.e("ShortcutsFragment", "Fatal error while scanning shortcuts!", fatal);
            Toast.makeText(getContext(),
                    "Couldn’t load shortcuts (see log).", Toast.LENGTH_LONG).show();
        }

        // ---- UI update ----
        Collections.sort(shortcuts, (a, b) -> {
            if (a == null || b == null) return 0;
            String an = a.name == null ? "" : a.name;
            String bn = b.name == null ? "" : b.name;
            return an.compareToIgnoreCase(bn);
        });

        // Where each game sits -- a store, a mapped drive, or Z: -- is read once here rather than
        // row by row, since resolving a shortcut reads what is on disk and rows are bound while
        // the list is being scrolled.
        HashMap<Shortcut, String> sources = new HashMap<>();
        for (Shortcut shortcut : shortcuts) {
            String source = ShortcutSource.labelFor(getContext(), shortcut.container, shortcut);
            if (source != null) sources.put(shortcut, source);
        }

        recyclerView.setAdapter(new ShortcutsAdapter(shortcuts, sources));
        emptyTextView.setVisibility(shortcuts.isEmpty() ? View.VISIBLE : View.GONE);

        // ---- quarantine report ----
        if (!quarantined.isEmpty()) {
            Toast.makeText(getContext(),
                    quarantined.size() + " shortcut(s) ignored (corrupted):",
                    Toast.LENGTH_LONG).show();

            // Move them out of the way so the crash never happens again.
            for (File bad : quarantined) {
                File dst = new File(bad.getParent(), bad.getName() + ".bad");
                Toast.makeText(getContext(), bad.getName() + " renamed to " + dst.getName(), Toast.LENGTH_LONG).show();
                // ignore return value – it’s a best-effort quarantine
                //noinspection ResultOfMethodCallIgnored
                bad.renameTo(dst);
            }
        }
    }


    private class ShortcutsAdapter extends RecyclerView.Adapter<ShortcutsAdapter.ViewHolder> {
        private final List<Shortcut> data;

        /** Where each shortcut's game is installed, for the ones that could be placed. */
        private final HashMap<Shortcut, String> sources;

        private class ViewHolder extends RecyclerView.ViewHolder {
            private final ImageButton menuButton;
            private final ImageButton imageView;
            private final TextView title;
            private final TextView subtitle;
            private final View innerArea;

            private ViewHolder(View view) {
                super(view);
                this.imageView = view.findViewById(R.id.ImageView);
                this.title = view.findViewById(R.id.TVTitle);
                this.subtitle = view.findViewById(R.id.TVSubtitle);
                this.menuButton = view.findViewById(R.id.BTMenu);
                this.innerArea = view.findViewById(R.id.LLInnerArea);
            }
        }

        public ShortcutsAdapter(List<Shortcut> data, HashMap<Shortcut, String> sources) {
            this.data = data;
            this.sources = sources;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.shortcut_list_item, parent, false));
        }

        @Override
        public void onViewRecycled(@NonNull ViewHolder holder) {
            holder.menuButton.setOnClickListener(null);
            holder.innerArea.setOnClickListener(null);
            super.onViewRecycled(holder);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            final Shortcut item = data.get(position);
            if (item.icon != null) {
                holder.imageView.setImageBitmap(item.icon);
            } else {
                // Set a default icon if none exists
                holder.imageView.setImageResource(R.mipmap.ic_launcher_foreground); // Create a default icon drawable
            }
            holder.imageView.setOnClickListener(v -> showIconPickerConfirmation(item));            holder.title.setText(item.name);
            // The container plays the game; the source says where the game itself is, which is not
            // the same thing and is the only difference between two rows for the same title.
            String source = sources.get(item);
            String subtitle = source == null ? item.container.getName()
                    : getString(R.string.shortcut_subtitle_with_source, item.container.getName(), source);
            // Settings that came in from a file are the third thing about a row that is not
            // visible anywhere else, and the one that explains a game behaving unlike its
            // neighbours. The separator is the same one the source already uses.
            if (ShortcutProfile.appliedName(item) != null) {
                subtitle = getString(R.string.shortcut_subtitle_with_source, subtitle,
                        getString(R.string.settings_profile_badge));
            }
            holder.subtitle.setText(subtitle);
            holder.menuButton.setOnClickListener((v) -> showListItemMenu(v, item));
            holder.innerArea.setOnClickListener((v) -> runFromShortcut(item));

            // Get the context from the item view
            Context context = holder.itemView.getContext();

            // Check if dark mode is enabled
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
            boolean isDarkMode = sharedPreferences.getBoolean("dark_mode", false);

            if (isDarkMode) {
                // Set the text color to something light for dark backgrounds
                holder.title.setTextColor(android.graphics.Color.WHITE);
            } else {
                // Set the text color to something dark for light backgrounds
                holder.title.setTextColor(android.graphics.Color.BLACK);
            }
        }

        @Override
        public final int getItemCount() {
            return data.size();
        }

        private void showListItemMenu(View anchorView, final Shortcut shortcut) {
            final Context context = getContext();
            PopupMenu listItemMenu = new PopupMenu(context, anchorView);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listItemMenu.setForceShowIcon(true);

            listItemMenu.inflate(R.menu.shortcut_popup_menu);
            // Only a game the shortcut plays off a mapped drive has anywhere faster to be moved
            // to, so the rest are not offered a copy they would gain nothing from.
            listItemMenu.getMenu().findItem(R.id.shortcut_copy_to_internal)
                    .setVisible(GameCopier.canCopy(context, shortcut));
            // Goldberg stands in for Steam, which the build a store other than Steam sold has no
            // use for. Taking the fix back off stays where it is put, so a game it was somehow
            // applied to is never left holding it with no way to undo that.
            // There is nothing to remove until a profile has been imported, and the shortcut's
            // own settings are what it is already running.
            boolean goldbergApplies = GoldbergEmu.appliesTo(context, shortcut);
            listItemMenu.getMenu().findItem(R.id.shortcut_apply_goldberg).setVisible(goldbergApplies);
            listItemMenu.getMenu().findItem(R.id.shortcut_revert_goldberg)
                    .setVisible(goldbergApplies || GoldbergEmu.isApplied(shortcut));
            listItemMenu.getMenu().findItem(R.id.shortcut_launch_report)
                    .setVisible(LaunchReport.exists(context, shortcut));

            listItemMenu.setOnMenuItemClickListener((menuItem) -> {
                int itemId = menuItem.getItemId();
                if (itemId == R.id.shortcut_settings) {
                    (new ShortcutSettingsDialog(ShortcutsFragment.this, shortcut)).show();
                }
                else if (itemId == R.id.shortcut_remove) {
                    showRemoveShortcutDialog(shortcut);
                }
                else if (itemId == R.id.shortcut_clone_to_container) {
                    // Use the ContainerManager to get the list of containers
                    ContainerManager containerManager = new ContainerManager(context);
                    ArrayList<Container> containers = containerManager.getContainers();

                    // Show a container selection dialog
                    showContainerSelectionDialog(containers, new OnContainerSelectedListener() {
                        @Override
                        public void onContainerSelected(Container selectedContainer) {
                            // Use the selected container to clone the shortcut
                            if (shortcut.cloneToContainer(selectedContainer)) {
                                Toast.makeText(context, "Shortcut cloned successfully.", Toast.LENGTH_SHORT).show();
                                loadShortcutsList(); // Reload the shortcuts to show the cloned one
                            } else {
                                Toast.makeText(context, "Failed to clone shortcut.", Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                }
                else if (itemId == R.id.shortcut_copy_to_internal) {
                    GameCopier.start(getActivity(), shortcut, () -> loadShortcutsList());
                }
                else if (itemId == R.id.shortcut_apply_goldberg) {
                    GoldbergEmu.showApplyGoldbergDialog(getActivity(), shortcut);
                }
                else if (itemId == R.id.shortcut_revert_goldberg) {
                    GoldbergEmu.showRevertGoldbergDialog(getActivity(), shortcut);
                }
                else if (itemId == R.id.shortcut_add_to_home_screen) {
                    if (shortcut.getExtra("uuid").equals(""))
                        shortcut.genUUID();
                    addShortcutToScreen(shortcut);
                }
                else if (itemId == R.id.shortcut_export_to_frontend) {
                    exportShortcutToFrontend(shortcut);
                }
                else if (itemId == R.id.shortcut_install_redist) {
                    RedistInstaller.showDialog(getActivity(), shortcut);
                }
                else if (itemId == R.id.shortcut_install_mod) {
                    openModZipPicker(shortcut);
                }
                else if (itemId == R.id.shortcut_launch_report) {
                    showLaunchReport(shortcut);
                }
                else if (itemId == R.id.shortcut_properties) {
                    showShortcutProperties(shortcut);
                }
                return true;
            });
            listItemMenu.show();
        }

        // Define the listener interface for selecting a container
        public interface OnContainerSelectedListener {
            void onContainerSelected(Container container);
        }

        private void showContainerSelectionDialog(ArrayList<Container> containers, OnContainerSelectedListener listener) {
            // Create an AlertDialog to show the list of containers
            AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
            builder.setTitle("Select a container");

            // Create an array of container names to display
            String[] containerNames = new String[containers.size()];
            for (int i = 0; i < containers.size(); i++) {
                containerNames[i] = containers.get(i).getName();
            }

            // Set up the list in the dialog
            builder.setItems(containerNames, (dialog, which) -> {
                // Call the listener when a container is selected
                listener.onContainerSelected(containers.get(which));
            });

            // Show the dialog
            builder.show();
        }






        private static final long GOLDBERG_HINT_DELAY_MS = 4500;

        private void runFromShortcut(Shortcut shortcut) {
            // First launch of this shortcut: scan its game folder once for steam_api.dll (in
            // case a fix might help) and cache the result, then launch. Every later launch just
            // reuses that cached result instead of re-scanning.
            if (shortcut.getExtra("goldbergScanned", "").isEmpty()) {
                Executors.newSingleThreadExecutor().execute(() -> {
                    List<File> dirs = new ArrayList<>();
                    Activity activity = getActivity();
                    File root = GoldbergEmu.resolveShortcutInstallDir(activity, shortcut);
                    if (root != null && root.isDirectory()) GoldbergEmu.scanForSteamApiDirs(root, 0, dirs, new int[1]);
                    GoldbergEmu.saveGoldbergScanResult(shortcut, dirs);
                    if (activity != null) {
                        activity.runOnUiThread(() -> launchAfterGoldbergHint(shortcut));
                    }
                });
            } else {
                launchAfterGoldbergHint(shortcut);
            }
        }

        /**
         * Shows the Goldberg hint (if applicable) and only then launches — with a short delay
         * when a hint was actually shown. The container/XR launch that follows switches away
         * from this screen immediately, which was swallowing the toast before it could be seen
         * when both happened back-to-back with no gap.
         */
        private void launchAfterGoldbergHint(Shortcut shortcut) {
            // Held here: the fragment can detach during the delay, leaving getActivity() null
            Activity activity = getActivity();
            if (activity == null) return;
            if (GoldbergEmu.maybeShowGoldbergHint(activity, shortcut)) {
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(() -> launchShortcut(activity, shortcut), GOLDBERG_HINT_DELAY_MS);
            } else {
                launchShortcut(activity, shortcut);
            }
        }

        private void launchShortcut(Activity activity, Shortcut shortcut) {
            if (!XrActivity.isEnabled(activity)) {
                Intent intent = new Intent(activity, XServerDisplayActivity.class);
                intent.putExtra("container_id", shortcut.container.id);
                intent.putExtra("shortcut_path", shortcut.file.getPath());
                intent.putExtra("shortcut_name", shortcut.name); // Add this line to pass the shortcut name
                // Check if the shortcut has the disableXinput value; if not, default to false.
                String disableXinputValue = shortcut.getExtra("disableXinput", "0"); // Get value from shortcut or use "0" (false) by default
                intent.putExtra("disableXinput", disableXinputValue); // Use the actual value from the shortcut
                activity.startActivity(intent);
            }
            else XrActivity.openIntent(activity, shortcut.container.id, shortcut.file.getPath());
        }

        private void exportShortcutToFrontend(Shortcut shortcut) {
            // Check for a custom frontend export path in shared preferences
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getContext());
            String uriString = sharedPreferences.getString("frontend_export_uri", null);

            File frontendDir;

            if (uriString != null) {
                // If custom URI is set, use it
                Uri folderUri = Uri.parse(uriString);
                DocumentFile pickedDir = DocumentFile.fromTreeUri(getContext(), folderUri);

                if (pickedDir == null || !pickedDir.canWrite()) {
                    Toast.makeText(getContext(), "Cannot write to the selected folder", Toast.LENGTH_SHORT).show();
                    return;
                }

                // Convert DocumentFile to a File object for further processing
                frontendDir = new File(FileUtils.getFilePathFromUri(getContext(), folderUri));
            } else {
                // Default to Downloads\Winlator\Frontend if no custom URI is set
                frontendDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Winlator/Frontend");
                if (!frontendDir.exists() && !frontendDir.mkdirs()) {
                    Toast.makeText(getContext(), "Failed to create default directory", Toast.LENGTH_SHORT).show();
                    return;
                }
            }


            // Check for FRONTEND_INSTRUCTIONS.txt
            File instructionsFile = new File(frontendDir, "FRONTEND_INSTRUCTIONS.txt");
            if (true) {
                try (FileWriter writer = new FileWriter(instructionsFile, false)) {
                    writer.write("Instructions for adding Winlator shortcuts to Frontends:\n\n");
                    writer.write("Daijisho:\n\n");
                    writer.write("1. Open Daijisho\n");
                    writer.write("2. Navigate to the Settings tab.\n");
                    writer.write("3. Navigate to Settings\\Library\n");
                    writer.write("4. Select, Import from Pegasus\n");
                    writer.write("5. Add the metadata.pegasus.txt file located in this directory (Downloads\\Winlator\\Frontend)\n");
                    writer.write("6. Set the Sync path to Downloads\\Winlator\\Frontend\n");
                    writer.write("7. Start your game!\n\n");
                    writer.write("Beacon:\n\n");
                    writer.write("1. Navigate to Settings\n");
                    writer.write("2. Click the + Icon\n");
                    writer.write("3. Set the following values:\n\n");
                    writer.write("Platform Type: Custom\n");
                    writer.write("Name: Windows (or Winlator, whatever you prefer)\n");
                    writer.write("Short name: windows\n");
                    writer.write("Player app: Select Winlator Cmod (or whichever fork you are using that has adopted this code)\n");
                    writer.write("ROMs folder: Use Android FilePicker to select the Downloads\\Winlator\\Frontend directory\n");
                    writer.write("Expand Advanced:\n");
                    writer.write("File handling: Default\n");
                    writer.write("Use custom launch: True\n");
                    writer.write("am start command: am start -n " + "com.winlator.cmod/com.winlator.cmod.XServerDisplayActivity -e shortcut_path {file_path}\n\n");
                    writer.write("4. Click Save\n");
                    writer.write("5. Scan the folder for your game\n");
                    writer.write("6. Launch your game!\n");
                    writer.flush();
                    Log.d("ShortcutsFragment", "FRONTEND_INSTRUCTIONS.txt created successfully.");
                } catch (IOException e) {
                    Log.e("ShortcutsFragment", "Failed to create FRONTEND_INSTRUCTIONS.txt", e);
                }
            }

            // Check for metadata.pegasus.txt
            File metadataFile = new File(frontendDir, "metadata.pegasus.txt");
            try (FileWriter writer = new FileWriter(metadataFile, false)) {
                writer.write("collection: Windows\n");
                writer.write("shortname: windows\n");
                writer.write("extensions: desktop\n");
                writer.write("launch: am start\n");
                writer.write("  -n " + "com.winlator.cmod/com.winlator.cmod.XServerDisplayActivity\n");
                writer.write("  -e shortcut_path {file.path}\n");
                writer.write("  --activity-clear-task\n");
                writer.write("  --activity-clear-top\n");
                writer.write("  --activity-no-history\n");
                writer.flush();
                Log.d("ShortcutsFragment", "metadata.pegasus.txt created or updated successfully.");
            } catch (IOException e) {
                Log.e("ShortcutsFragment", "Failed to create or update metadata.pegasus.txt", e);
            }

            // Create the export file in the Frontend directory
            File exportFile = new File(frontendDir, shortcut.file.getName());

            boolean fileExists = exportFile.exists();
            boolean containerIdFound = false;

            try {
                List<String> lines = new ArrayList<>();

                // Read the original file or existing file if it exists
                try (BufferedReader reader = new BufferedReader(new FileReader(shortcut.file))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("container_id=")) {
                            // Replace the existing container_id line
                            lines.add("container_id=" + shortcut.container.id);
                            containerIdFound = true;
                        } else {
                            lines.add(line);
                        }
                    }
                }

                // If no container_id was found, add it
                if (!containerIdFound) {
                    lines.add("container_id=" + shortcut.container.id);
                }

                // Write the contents to the export file
                try (FileWriter writer = new FileWriter(exportFile, false)) {
                    for (String line : lines) {
                        writer.write(line + "\n");
                    }
                    writer.flush();
                }

                Log.d("ShortcutsFragment", "Shortcut exported successfully to " + exportFile.getPath());

                // Determine the toast message
                String message;
                if (fileExists) {
                    message = "Frontend Shortcut Updated at " + exportFile.getPath();
                } else {
                    message = "Frontend Shortcut Exported to " + exportFile.getPath();
                }

                // Show a toast message to the user
                Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();

            } catch (IOException e) {
                Log.e("ShortcutsFragment", "Failed to export shortcut", e);
                Toast.makeText(getContext(), "Failed to export shortcut", Toast.LENGTH_LONG).show();
            }
        }

        private void showShortcutProperties(Shortcut shortcut) {
            SharedPreferences playtimePrefs = getContext().getSharedPreferences("playtime_stats", Context.MODE_PRIVATE);

            String playtimeKey = shortcut.name + "_playtime";
            String playCountKey = shortcut.name + "_play_count";

            long totalPlaytime = playtimePrefs.getLong(playtimeKey, 0);
            int playCount = playtimePrefs.getInt(playCountKey, 0);

            // Convert playtime to human-readable format
            long seconds = (totalPlaytime / 1000) % 60;
            long minutes = (totalPlaytime / (1000 * 60)) % 60;
            long hours = (totalPlaytime / (1000 * 60 * 60)) % 24;
            long days = (totalPlaytime / (1000 * 60 * 60 * 24));

            String playtimeFormatted = String.format("%dd %02dh %02dm %02ds", days, hours, minutes, seconds);

            // Create the properties dialog
            ContentDialog dialog = new ContentDialog(getContext(), R.layout.shortcut_properties_dialog);
            dialog.setTitle("Properties");

            TextView playCountTextView = dialog.findViewById(R.id.play_count);
            TextView playtimeTextView = dialog.findViewById(R.id.playtime);
            // The dialog wraps its content, so without a set width the paths wrap into a narrow column.
            ((View) playCountTextView.getParent()).getLayoutParams().width = AppUtils.getPreferredDialogWidth(getContext());

            playCountTextView.setText("Number of times played: " + playCount);
            playtimeTextView.setText("Playtime: " + playtimeFormatted);

            showTargetPath(dialog, shortcut);

            Button resetPropertiesButton = dialog.findViewById(R.id.reset_properties);

            resetPropertiesButton.setOnClickListener(v -> {
                playtimePrefs.edit().remove(playtimeKey).remove(playCountKey).apply();
                Toast.makeText(getContext(), "Properties reset successfully.", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });

            dialog.show();
        }

        /**
         * The Steam suspicion below rests on the launch-time steam_api scan, which a shortcut
         * made before that scan existed has never had run on it. Run it once here so the report
         * is not reading an absence of evidence as evidence of absence.
         */
        private void showLaunchReport(Shortcut shortcut) {
            if (!shortcut.getExtra("goldbergScanned", "").isEmpty()) {
                buildLaunchReport(shortcut);
                return;
            }
            final Context context = getContext();
            Executors.newSingleThreadExecutor().execute(() -> {
                ArrayList<File> dirs = new ArrayList<>();
                File root = GoldbergEmu.resolveShortcutInstallDir(context, shortcut);
                if (root != null && root.isDirectory()) GoldbergEmu.scanForSteamApiDirs(root, 0, dirs, new int[1]);
                GoldbergEmu.saveGoldbergScanResult(shortcut, dirs);
                Activity activity = getActivity();
                if (activity != null) activity.runOnUiThread(() -> buildLaunchReport(shortcut));
            });
        }

        private void buildLaunchReport(Shortcut shortcut) {
            LaunchReport.Summary report = LaunchReport.read(getContext(), shortcut);
            if (report == null) return;
            boolean hasSteamFiles = !shortcut.getExtra("goldbergDllDirs", "").isEmpty();

            StringBuilder msg = new StringBuilder();
            msg.append("Launched: ").append(DateFormat.getDateTimeInstance().format(new Date(report.startTime))).append('\n');
            msg.append("Game window appeared: ").append(report.windowShown ? "yes" : "no").append('\n');
            msg.append("Session length: ").append(report.duration >= 0 ? report.formatDuration()
                    : "not recorded (closed by Android, or the app crashed)").append("\n\n");

            // A Steam build that finds no Steam client usually quits before drawing anything, and
            // often takes the session down with it - so how it ended says nothing either way, and
            // only the missing window counts here.
            boolean steamSuspected = !report.steam && !report.windowShown
                    && hasSteamFiles && !GoldbergEmu.isApplied(shortcut);

            if (report.steam) msg.append("• The game could not reach Steam. Apply the Goldberg Steam Fix.\n\n");
            else if (steamSuspected) msg.append("• The game closed without drawing a window and ships steam_api. It probably needs Steam; try the Goldberg Steam Fix.\n\n");
            else if (!report.windowShown) msg.append("• The game closed before drawing anything.\n\n");
            if (report.missingExe) msg.append("• Wine could not find or open the game's executable. Check the shortcut's target path.\n\n");
            if (report.redist) msg.append("• Missing runtime files: ").append(TextUtils.join(", ", report.missingDlls))
                    .append(". Install Game Redistributables (VC++ / DirectX / PhysX).\n\n");
            if (report.dotnet) msg.append("• The game needs .NET, which this Wine could not load.\n\n");
            if (report.render) msg.append("• DXVK reported graphics errors. Try another graphics driver or DXVK version in the shortcut settings.\n\n");
            if (report.emulator) msg.append("• The CPU emulator hit an instruction it does not support. Try another Box64/FEX preset.\n\n");
            if (report.crash) msg.append("• The game crashed (unhandled exception).\n\n");
            if (!report.steam && !steamSuspected && !report.missingExe && !report.redist && !report.dotnet
                    && !report.render && !report.emulator && !report.crash) {
                msg.append("No known cause was found in the game's output.\n\n");
            }

            // Whatever else a failed launch printed, a Steam build that cannot reach Steam is
            // still the first thing to rule out: the errors such a game leaves behind on its way
            // out are as likely to be side effects as the reason it never started. So the
            // reminder goes on every failed launch, not only the ones that point at Steam.
            boolean failed = report.steam || report.missingExe || report.redist || report.dotnet
                    || report.render || report.emulator || report.crash
                    || !report.windowShown || report.duration < 0;
            boolean goldberg = failed && !GoldbergEmu.isApplied(shortcut)
                    && GoldbergEmu.appliesTo(getContext(), shortcut);
            // The Steam bullets above have said this already in their own words.
            if (goldberg && !report.steam && !steamSuspected) {
                msg.append(hasSteamFiles
                        ? "This game ships steam_api. If it is a Steam build, try the Goldberg Steam Fix — the errors above may be side effects rather than the reason it did not start.\n\n"
                        : "No steam_api file was found next to this game. If it is a Steam build, the Goldberg Steam Fix is still worth a try.\n\n");
            }

            if (!report.lines.isEmpty()) {
                msg.append("Details:\n");
                for (String line : report.lines) msg.append(line).append('\n');
            }
            else msg.append("The game printed nothing before it closed.");

            AlertDialog.Builder builder = new AlertDialog.Builder(getContext())
                    .setTitle("Last Launch: " + shortcut.name)
                    .setMessage(msg.toString().trim())
                    .setNegativeButton(android.R.string.ok, null);
            if (goldberg) {
                builder.setPositiveButton("Apply Goldberg", (d, w) -> GoldbergEmu.showApplyGoldbergDialog(getActivity(), shortcut));
            }
            else if (report.redist) {
                builder.setPositiveButton("Install Redists", (d, w) -> RedistInstaller.showDialog(getActivity(), shortcut));
            }
            builder.setNeutralButton(R.string.settings, (d, w) -> (new ShortcutSettingsDialog(ShortcutsFragment.this, shortcut)).show());
            builder.show();
        }

        /**
         * What the shortcut actually runs, which is not otherwise shown anywhere.
         *
         * A shortcut a game's own installer made runs it through a .lnk on the desktop, so the
         * path it carries names that .lnk and says nothing about where the game is. The .lnk
         * names the game, so it is read and shown underneath.
         */
        private void showTargetPath(ContentDialog dialog, Shortcut shortcut) {
            TextView targetTextView = dialog.findViewById(R.id.target_path);
            TextView linkTextView = dialog.findViewById(R.id.target_link_path);

            String path = shortcut.path;
            targetTextView.setText(getString(R.string.properties_target,
                    path.isEmpty() ? getString(R.string.properties_target_unknown) : path));

            if (!path.toLowerCase(Locale.ENGLISH).endsWith(".lnk")) return;

            File target = GameUninstaller.resolveExecutable(getContext(), shortcut.container, shortcut);
            // The path the game knows itself by where there is one, since that is what the rest
            // of the app talks in, and where it sits on the device otherwise.
            String winPath = target != null
                    ? GuestScriptRunner.toWinPath(getContext(), shortcut.container, target) : null;
            String shown = winPath != null ? winPath
                    : target != null ? target.getAbsolutePath()
                    : getString(R.string.properties_target_link_unreadable);

            linkTextView.setText(getString(R.string.properties_target_link, shown));
            linkTextView.setVisibility(View.VISIBLE);
        }
    }

    private ShortcutInfo buildScreenShortCut(String shortLabel, String longLabel, int containerId, String shortcutPath, Icon icon, String uuid) {
        Intent intent = new Intent(getActivity(), XServerDisplayActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra("container_id", containerId);
        intent.putExtra("shortcut_path", shortcutPath);

        ShortcutInfo.Builder builder = new ShortcutInfo.Builder(getActivity(), uuid)
                .setShortLabel(shortLabel)
                .setLongLabel(longLabel)
                .setIntent(intent);
        // An update leaves out what it does not set, so a shortcut with no icon keeps the one
        // the launcher already drew for it.
        if (icon != null) builder.setIcon(icon);
        return builder.build();
    }

    private void addShortcutToScreen(Shortcut shortcut) {
        ShortcutManager shortcutManager = getSystemService(requireContext(), ShortcutManager.class);
        if (shortcutManager != null && shortcutManager.isRequestPinShortcutSupported())
            shortcutManager.requestPinShortcut(buildScreenShortCut(shortcut.name, shortcut.name, shortcut.container.id,
                    shortcut.file.getPath(), shortcut.icon != null ? Icon.createWithBitmap(shortcut.icon) : null,
                    shortcut.getExtra("uuid")), null);
    }

    public static void disableShortcutOnScreen(Context context, Shortcut shortcut) {
        ShortcutManager shortcutManager = getSystemService(context, ShortcutManager.class);
        try {
            shortcutManager.disableShortcuts(Collections.singletonList(shortcut.getExtra("uuid")),
                    context.getString(R.string.shortcut_not_available));
        } catch (Exception e) {}
    }

    public void updateShortcutOnScreen(String shortLabel,
                                       String longLabel,
                                       int    containerId,
                                       String shortcutPath,
                                       Icon   icon,
                                       String uuid) {

        ShortcutManager sm =
                androidx.core.content.ContextCompat.getSystemService(
                        requireContext(), ShortcutManager.class);

        if (sm == null) {                    // ⇦ grace-fully bail out on devices
            Log.w("ShortcutsFragment",       //    that don’t expose ShortcutManager
                    "ShortcutManager not available; cannot update pinned shortcut");
            return;
        }

        for (ShortcutInfo info : sm.getPinnedShortcuts()) {
            if (uuid.equals(info.getId())) {
                sm.updateShortcuts(Collections.singletonList(
                        buildScreenShortCut(shortLabel, longLabel,
                                containerId, shortcutPath, icon, uuid)));
                break;
            }
        }
    }

    private static boolean safeDelete(@Nullable File f) {
        try {
            return f != null && f.exists() && f.delete();
        } catch (Exception e) {
            Log.e("ShortcutsFragment", "Delete failed for: " + (f != null ? f.getAbsolutePath() : "null"), e);
            return false;
        }
    }

    /** Delete a sibling .lnk that matches the .desktop basename. */
    private boolean deletePairedLnkForShortcut(Shortcut shortcut) {
        if (shortcut == null || shortcut.file == null) return false;
        File dir = shortcut.file.getParentFile();
        if (dir == null) return false;

        String base = FileUtils.getBasename(shortcut.file.getName()); // strips extension
        File lnk = new File(dir, base + ".lnk");
        boolean deleted = safeDelete(lnk);
        if (deleted) {
            Log.d("ShortcutsFragment", "Paired .lnk removed: " + lnk.getAbsolutePath());
        } else if (lnk.exists()) {
            Log.w("ShortcutsFragment", "Paired .lnk exists but could not be removed: " + lnk.getAbsolutePath());
        } else {
            Log.d("ShortcutsFragment", "No paired .lnk found for " + base);
        }
        return deleted;
    }
}
