package com.winlator.cmod;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.tabs.TabLayout;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contentdialog.ContentInfoDialog;
import com.winlator.cmod.contentdialog.ContentUntrustedDialog;
import com.winlator.cmod.contents.AdrenotoolsManager;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.contents.Downloader;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.container.GameUninstaller;
import com.winlator.cmod.contents.ModInstaller;
import com.winlator.cmod.container.ShortcutCreator;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.core.ZipExtractor;
import com.winlator.cmod.core.ZipImport;
import com.winlator.cmod.contents.ContentInstaller;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

public class ContentsFragment extends Fragment {
    /** Argument that opens the fragment on the Installers &amp; Mods tab, ready to add an installer. */
    public static final String ARG_ADD_INSTALLER = "add_installer";

    private RecyclerView recyclerView;
    private View emptyText;
    private AdrenotoolsManager adrenotoolsManager;
    private ContentsManager manager;
    private PreloaderDialog preloaderDialog;
    private ArrayList<ContentProfile.ContentType> currentContentType = new ArrayList<>();

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        adrenotoolsManager = new AdrenotoolsManager(getActivity());
        preloaderDialog = new PreloaderDialog(getActivity());
        manager = new ContentsManager(getContext());
        manager.syncContents();

        if (currentContentType.isEmpty()) {
            currentContentType.add(ContentProfile.ContentType.CONTENT_TYPE_WINE);
            currentContentType.add(ContentProfile.ContentType.CONTENT_TYPE_PROTON);
        }

        // Initialize isDarkMode based on shared preferences or theme
        boolean isDarkMode = PreferenceManager.getDefaultSharedPreferences(getContext())
                .getBoolean("dark_mode", false);
    }

    @Override
    public void onDestroy() {
        FileUtils.clear(getContext().getCacheDir());
        super.onDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(R.string.downloader);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        ViewGroup layout = (ViewGroup) inflater.inflate(R.layout.contents_fragment, container, false);

        emptyText = layout.findViewById(R.id.TVEmptyText);

        recyclerView = layout.findViewById(R.id.RecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(recyclerView.getContext()));
        recyclerView.addItemDecoration(new DividerItemDecoration(recyclerView.getContext(), DividerItemDecoration.VERTICAL));

        TabLayout tabLayout = layout.findViewById(R.id.TabLayout);
        tabLayout.setTabTextColors(Color.LTGRAY, Color.WHITE);
        tabLayout.setSelectedTabIndicatorColor(Color.WHITE);

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                currentContentType.clear();
                switch (tab.getPosition()) {
                    case 0:
                        currentContentType.add(ContentProfile.ContentType.CONTENT_TYPE_WINE);
                        currentContentType.add(ContentProfile.ContentType.CONTENT_TYPE_PROTON);
                        break;
                    case 1:
                        currentContentType.add(ContentProfile.ContentType.CONTENT_TYPE_RUNTIME);
                        currentContentType.add(ContentProfile.ContentType.CONTENT_TYPE_GOLDBERG);
                        currentContentType.add(ContentProfile.ContentType.CONTENT_TYPE_OPENCOMPOSITE);
                        currentContentType.add(ContentProfile.ContentType.CONTENT_TYPE_OXRWXR);
                        break;
                    case 2: currentContentType.add(ContentProfile.ContentType.CONTENT_TYPE_DXVK); break;
                    case 3: currentContentType.add(ContentProfile.ContentType.CONTENT_TYPE_VKD3D); break;
                    case 4: currentContentType.add(ContentProfile.ContentType.CONTENT_TYPE_BOX64); break;
                    case 5: currentContentType.add(ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64); break;
                    case 6: currentContentType.add(ContentProfile.ContentType.CONTENT_TYPE_FEXCORE); break;
                    case 7: currentContentType.add(ContentProfile.ContentType.CONTENT_TYPE_ADRENO_GPU_DRIVERS); break;
                    case 8:
                        currentContentType.add(ContentProfile.ContentType.CONTENT_TYPE_INSTALLER);
                        currentContentType.add(ContentProfile.ContentType.CONTENT_TYPE_MOD);
                        break;
                }
                loadContentList();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                onTabSelected(tab);
            }
        });
        // Sent here from the Games tab to add an installer, so it opens on that tab and goes
        // straight to picking one. The flag is used up, so a recreated view does not ask again.
        Bundle args = getArguments();
        if (args != null && args.getBoolean(ARG_ADD_INSTALLER)) {
            args.remove(ARG_ADD_INSTALLER);
            tabLayout.getTabAt(8).select();
            // Sent here to add an installer specifically, so the kind is already answered and
            // asking again would be asking a question the caller brought the answer to.
            promptAddInstaller();
        }
        else tabLayout.getTabAt(0).select();

        return layout;
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.clear();
        inflater.inflate(R.menu.contents_menu, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        switch (menuItem.getItemId()) {
            case R.id.contents_menu_add:
                promptInstallContent();
                return true;

            default:
                return super.onOptionsItemSelected(menuItem);
        }
    }

    /** What the install button does, which depends on the tab it is pressed on. */
    private void promptInstallContent() {
        if (currentContentType.contains(ContentProfile.ContentType.CONTENT_TYPE_ADRENO_GPU_DRIVERS)) {
            ContentDialog.confirm(getContext(), getString(R.string.install_drivers_message) + " " + getString(R.string.install_drivers_warning), () -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                getActivity().startActivityFromFragment(this, intent, MainActivity.OPEN_FILE_REQUEST_CODE);
            });
            return;
        }

        // Installers and mods sit in one list, so which of the two is being added cannot be read
        // off the tab any more. It is asked once, here, and every prompt after it is the one that
        // kind always had.
        if (currentContentType.contains(ContentProfile.ContentType.CONTENT_TYPE_INSTALLER)
                && currentContentType.contains(ContentProfile.ContentType.CONTENT_TYPE_MOD)) {
            String[] kinds = {getString(R.string.add_kind_installer), getString(R.string.add_kind_mod)};
            ContentDialog.showSingleChoiceList(getContext(), getString(R.string.add_kind_title),
                    kinds, which -> {
                        if (which == 0) promptAddInstaller();
                        else promptAddMod();
                    });
            return;
        }

        String message = getString(R.string.do_you_want_to_install_content) + " " + getString(R.string.pls_make_sure_content_trustworthy) + " ";
        message += currentContentType.contains(ContentProfile.ContentType.CONTENT_TYPE_RUNTIME)
                ? getString(R.string.content_suffix_is_wcp_or_runtime_installer)
                : getString(R.string.content_suffix_is_wcp_packed_xz_zst);

        ContentDialog.confirm(getContext(), message, () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            getActivity().startActivityFromFragment(this, intent, MainActivity.OPEN_FILE_REQUEST_CODE);
        });
    }

    /**
     * Which of the two kinds the add button is part-way through adding.
     *
     * The file picker leaves this fragment and comes back to onActivityResult, which used to read
     * the kind off the tab. One tab holding both means it has to be remembered instead.
     */
    private enum AddKind { INSTALLER, MOD }
    private AddKind pendingAdd;

    /** A demo or offline installer: listed where the user keeps it, run from its own row later. */
    private void promptAddInstaller() {
        pendingAdd = AddKind.INSTALLER;
        String message = getString(R.string.do_you_want_to_install_content) + " "
                + getString(R.string.pls_make_sure_content_trustworthy) + " "
                + getString(R.string.select_installer);
        ContentDialog.confirm(getContext(), message, () -> pickFileFromDownloads());
    }

    /**
     * A mod is not installed from here at all -- it is added to the list, and unpacked into a game
     * later from its own row, once there is a game to name.
     */
    private void promptAddMod() {
        pendingAdd = AddKind.MOD;
        ContentDialog.confirm(getContext(), getString(R.string.add_mod_message), () -> pickFileFromDownloads());
    }

    /**
     * Both kinds are the user's own files kept in the Download folder -- which is also the D:
     * drive a container gets by default -- so the picker opens there for both.
     */
    private void pickFileFromDownloads() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, ContentInstaller.downloadsDocumentUri());
        getActivity().startActivityFromFragment(this, intent, MainActivity.OPEN_FILE_REQUEST_CODE);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        // A picker backed out of leaves nothing to add, and an answer left lying around would be
        // read by whatever opened the picker next.
        if (requestCode == MainActivity.OPEN_FILE_REQUEST_CODE
                && (resultCode != Activity.RESULT_OK || data == null || data.getData() == null)) {
            pendingAdd = null;
        }

        if (currentContentType.contains(ContentProfile.ContentType.CONTENT_TYPE_ADRENO_GPU_DRIVERS)) {
            if (requestCode == MainActivity.OPEN_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
                Uri uri = data.getData();
                String driver = adrenotoolsManager.installDriver(uri);
                if (!driver.isEmpty())
                    ((DriversAdapter)recyclerView.getAdapter()).addItem(driver);
            }
            return;
        }

        // Neither a mod nor an offline installer is copied in: the entry is a reference to the
        // file where the user keeps it. Which of the two was asked for before the picker opened,
        // since one tab holds both and the list alone no longer says.
        if (requestCode == MainActivity.OPEN_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK
                && pendingAdd != null && data.getData() != null) {
            AddKind kind = pendingAdd;
            pendingAdd = null;

            if (kind == AddKind.MOD) {
                // A mod is only ever read once, to be unpacked into a game.
                addPickedMod(data.getData());
            }
            else {
                ContentInstaller.addLocalInstaller(getActivity(), data.getData(), () -> {
                    manager.syncContents();
                    loadContentList();
                });
            }
            return;
        }

        // A runtime installer is a plain .exe/.msi, not a packed .wcp, so it is copied into the
        // runtimes directory rather than being handed to the content extractor.
        if (requestCode == MainActivity.OPEN_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK
                && currentContentType.contains(ContentProfile.ContentType.CONTENT_TYPE_RUNTIME)) {
            String name = ContentInstaller.queryDisplayName(getContext(), data.getData());

            // A zipped one is unpacked onto Z: and listed from there instead: what comes out of
            // an archive usually needs the rest of what came out of it sitting beside it.
            if (ZipImport.isZip(name)) {
                addRuntimeFromZip(data.getData());
                return;
            }

            if (name != null && ContentsManager.isInstaller(name)) {
                ContentInstaller.installLocalRuntime(getActivity(), data.getData(), name, () -> {
                    manager.syncContents();
                    loadContentList();
                });
                return;
            }
        }

        if (requestCode == MainActivity.OPEN_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            PreloaderDialog preloaderDialog = new PreloaderDialog(getActivity());
            preloaderDialog.showOnUiThread(R.string.installing_content);
            try {
                ContentsManager.OnInstallFinishedCallback callback = new ContentsManager.OnInstallFinishedCallback() {
                    private boolean isExtracting = true;

                    @Override
                    public void onFailed(ContentsManager.InstallFailedReason reason, Exception e) {
                        int msgId = switch (reason) {
                            case ERROR_BADTAR -> R.string.file_cannot_be_recognied;
                            case ERROR_NOPROFILE -> R.string.profile_not_found_in_content;
                            case ERROR_BADPROFILE -> R.string.profile_cannot_be_recognized;
                            case ERROR_EXIST -> R.string.content_already_exist;
                            case ERROR_MISSINGFILES -> R.string.content_is_incomplete;
                            case ERROR_UNTRUSTPROFILE -> R.string.content_cannot_be_trusted;
                            default -> R.string.unable_to_install_content;
                        };
                        requireActivity().runOnUiThread(() -> ContentDialog.alert(getContext(), getString(R.string.install_failed) + ": " + getString(msgId), preloaderDialog::closeOnUiThread));
                    }

                    @Override
                    public void onSucceed(ContentProfile profile) {
                        if (isExtracting) {
                            ContentsManager.OnInstallFinishedCallback callback1 = this;
                            requireActivity().runOnUiThread(() -> {
                                ContentInfoDialog dialog = new ContentInfoDialog(getContext(), profile);
                                ((TextView) dialog.findViewById(R.id.BTConfirm)).setText(R.string._continue);
                                dialog.setOnConfirmCallback(() -> {
                                    isExtracting = false;
                                    List<ContentProfile.ContentFile> untrustedFiles = manager.getUnTrustedContentFiles(profile);
                                    if (!untrustedFiles.isEmpty()) {
                                        ContentUntrustedDialog untrustedDialog = new ContentUntrustedDialog(getContext(), untrustedFiles);
                                        untrustedDialog.setOnCancelCallback(preloaderDialog::closeOnUiThread);
                                        untrustedDialog.setOnConfirmCallback(() -> manager.finishInstallContent(profile, callback1));
                                        untrustedDialog.show();
                                    } else manager.finishInstallContent(profile, callback1);
                                });
                                dialog.setOnCancelCallback(preloaderDialog::closeOnUiThread);
                                dialog.show();
                            });

                        } else {
                            preloaderDialog.closeOnUiThread();
                            requireActivity().runOnUiThread(() -> {
                                ContentDialog.alert(getContext(), R.string.content_installed_success, null);
                                manager.syncContents();
                                reload();
                            });
                        }
                    }
                };
                Executors.newSingleThreadExecutor().execute(() -> {
                    manager.extraContentFile(data.getData(), callback);
                });
            } catch (Exception e) {
                preloaderDialog.closeOnUiThread();
                AppUtils.showToast(getContext(), R.string.unable_to_import_profile);
            }
        }
    }

    /**
     * Unpacks a zipped runtime or installer, and does whatever the archive turns out to want.
     *
     * On the Installers tab that is not known in advance -- an archive there is as likely to hold
     * a game as an installer -- so the user is asked, and game files get a Games tab shortcut
     * rather than an entry here. The Runtime tab holds nothing but runtime installers, so it does
     * not ask.
     */
    private void addFromZip(File zip, boolean asInstaller) {
        if (asInstaller) {
            ZipImport.startAsking(getActivity(), zip, (role, executable, extractedDir) -> {
                if (role == ZipExtractor.Role.GAME) ShortcutCreator.createForExecutable(getActivity(), executable, null);
                else addExtractedEntry(executable, true);
            });
            return;
        }

        ZipImport.start(getActivity(), zip, ZipExtractor.Role.INSTALLER,
                (role, executable, extractedDir) -> addExtractedEntry(executable, false));
    }

    /**
     * Lists the program that came out of an archive, referenced where it was unpacked rather than
     * copied into the runtimes or installers folder: an installer lifted out of an archive on its
     * own would leave behind the data files it was packed with, which is exactly what it needs
     * beside it to run.
     */
    private void addExtractedEntry(File executable, boolean asInstaller) {
        boolean added = asInstaller
                ? ContentsManager.addInstallerReference(getContext(), executable)
                : ContentsManager.addRuntimeReference(getContext(), executable);

        if (!added) {
            ContentDialog.alert(getContext(), R.string.install_failed, null);
            return;
        }

        manager.syncContents();
        loadContentList();
        ContentDialog.alert(getContext(), getString(R.string.installer_added, executable.getName()), null);
    }

    /**
     * Lists a mod archive the user picked, referenced where they keep it.
     *
     * Only a .zip is taken, since that is the whole of what the mod installer can unpack, and an
     * entry offering an install that cannot be carried out is worse than no entry at all.
     */
    private void addPickedMod(Uri uri) {
        String path = FileUtils.getFilePathFromDocumentUri(getContext(), uri);
        if (path == null) {
            ContentDialog.alert(getContext(), R.string.mod_not_local_file, null);
            return;
        }

        File picked = new File(path);
        if (!picked.isFile()) {
            ContentDialog.alert(getContext(), R.string.mod_not_local_file, null);
            return;
        }

        if (!ContentsManager.isMod(picked.getName())) {
            ContentDialog.alert(getContext(), R.string.mod_zip_only, null);
            return;
        }

        if (!ContentsManager.addModReference(getContext(), picked)) {
            ContentDialog.alert(getContext(), getString(R.string.mod_add_failed, picked.getName()), null);
            return;
        }

        manager.syncContents();
        loadContentList();
        ContentDialog.alert(getContext(), getString(R.string.mod_added, picked.getName()), null);
    }

    /**
     * Installs a listed mod into a game, which means settling which game first.
     *
     * A mod belongs to one game and nothing on the entry says which, so every shortcut is offered
     * and the answer is the user's. What happens after that is the same as installing a mod from
     * the game's own menu -- the same dialogs, the same folder, the same warning -- since it is
     * the same code reached from the other end.
     */
    private void installModIntoGame(ContentProfile profile, File modFile) {
        List<Shortcut> shortcuts = allShortcuts();
        if (shortcuts.isEmpty()) {
            ContentDialog.alert(getContext(), R.string.mod_no_games, null);
            return;
        }

        // An entry that names its game puts the games it could mean at the top, rather than
        // hiding the rest: the name is free text and the match is a guess, so a wrong guess
        // should cost a scroll and not the entry disappearing.
        final String gameKey = matchKey(profile.game);
        if (gameKey != null) shortcuts.sort((a, b) -> {
            boolean matchesA = matchesGame(gameKey, a);
            if (matchesA != matchesGame(gameKey, b)) return matchesA ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });

        String[] names = new String[shortcuts.size()];
        for (int i = 0; i < shortcuts.size(); i++) {
            Shortcut shortcut = shortcuts.get(i);
            names[i] = shortcut.name + " · " + shortcut.container.getName();
        }

        String title = profile.game != null
                ? getString(R.string.mod_choose_game_title_for, modFile.getName(), profile.game)
                : getString(R.string.mod_choose_game_title, modFile.getName());

        ContentDialog.showSingleChoiceList(getActivity(), title, names,
                which -> ModInstaller.start(getActivity(), shortcuts.get(which), modFile, null));
    }

    /**
     * Whether a shortcut looks like the game a mod names.
     *
     * Both the shortcut's name and the path it runs are tested, because either can be the one
     * carrying the game's name: a shortcut can be renamed to anything, while the path still
     * leads through the folder the game was installed into, and a folder the user made is the
     * other way round.
     */
    private static boolean matchesGame(String gameKey, Shortcut shortcut) {
        String name = matchKey(shortcut.name);
        String path = matchKey(shortcut.path);
        return (name != null && (name.contains(gameKey) || gameKey.contains(name)))
                || (path != null && path.contains(gameKey));
    }

    /**
     * A name reduced to what two spellings of it have in common, or null when what is left is too
     * short to match on -- "GTA" would otherwise be found inside half the library.
     */
    private static String matchKey(String name) {
        if (name == null) return null;
        String key = name.toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9]", "");
        return key.length() >= 4 ? key : null;
    }

    /**
     * Every game shortcut, in every container, for asking which one a mod is for.
     *
     * The Games tab is one screen away and its list is the one the user knows, so this is put in
     * the same order and says which container each belongs to -- the same game in two containers
     * is two entries there and has to be two entries here.
     */
    private List<Shortcut> allShortcuts() {
        List<Shortcut> shortcuts = new ArrayList<>();

        for (Container container : new ContainerManager(getContext()).getContainers()) {
            File[] files = container.getDesktopDir().listFiles((dir, name) -> name.endsWith(".desktop"));
            if (files == null) continue;

            for (File file : files) {
                // The shortcut a container runs its own installers through is not a game.
                if (file.getAbsolutePath().contains(ShortcutsFragment.HIDDEN_SHORTCUT)) continue;
                try {
                    shortcuts.add(new Shortcut(container, file));
                }
                catch (Exception e) {
                    Log.w("ContentsFragment", "Skipping unreadable shortcut " + file, e);
                }
            }
        }

        shortcuts.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return shortcuts;
    }

    /** The .zip a runtime was picked as, which has to be a file on the device to be unpacked. */
    private void addRuntimeFromZip(Uri uri) {
        String path = FileUtils.getFilePathFromDocumentUri(getContext(), uri);
        if (path == null) {
            ContentDialog.alert(getContext(), R.string.installer_not_local_file, null);
            return;
        }
        addFromZip(new File(path), false);
    }

    /**
     * Whether an entry is one downloadable file rather than packed content.
     *
     * Runtimes, installers and mods are each a single file that is either on the device or still
     * to be fetched, which is what decides both how the row is drawn and what downloading one
     * does with what comes back.
     */
    private static boolean isSingleFileEntry(ContentProfile profile) {
        return profile.type == ContentProfile.ContentType.CONTENT_TYPE_RUNTIME
                || profile.type == ContentProfile.ContentType.CONTENT_TYPE_INSTALLER
                || profile.type == ContentProfile.ContentType.CONTENT_TYPE_MOD;
    }

    /**
     * Removing a mod entry, which never takes the archive with it when the archive is the user's
     * own: it sits in their Download folder and is theirs to keep. A downloaded one is this app's
     * doing, so that one goes.
     */
    private void confirmRemoveMod(ContentProfile profile, File modFile) {
        final boolean referenced = profile.localFilePath != null;

        String message = referenced
                ? getString(R.string.remove_mod_reference, modFile.getName())
                : getString(R.string.do_you_want_to_remove_this_content);

        ContentDialog.confirm(getContext(), message, () -> {
            if (referenced) ContentsManager.getReferenceFile(getContext(), profile).delete();
            else modFile.delete();
            // A mod entry only exists as a file, so the profile list has to be rebuilt for it to
            // disappear.
            manager.syncContents();
            loadContentList();
        });
    }

    /**
     * Removing a runtime or installer entry, which takes a different amount with it depending on
     * what the entry is: a downloaded file is the app's to delete, one of the user's own files is
     * only unlisted, and one that came out of an archive takes the unpacked folder with it, since
     * that folder exists for nothing else.
     */
    private void confirmRemoveLocalEntry(ContentProfile profile, File localFile, boolean isInstaller) {
        final boolean referenced = profile.localFilePath != null;
        final File extraction = referenced ? ZipExtractor.containingExtraction(getContext(), localFile) : null;
        final List<File> companions = isInstaller && !referenced
                ? ContentInstaller.companionFiles(localFile) : new ArrayList<>();

        if (extraction != null) {
            // An unpacked archive can run to gigabytes, so how much goes is worth saying, and
            // walking it for that is worth keeping off the UI thread.
            Executors.newSingleThreadExecutor().execute(() -> {
                long size = GameUninstaller.folderSize(extraction);

                Activity activity = getActivity();
                if (activity == null) return;

                String message = getString(R.string.remove_extracted_installer, localFile.getName(),
                        "Z:\\" + ZipExtractor.EXTRACTED_DIR_NAME + "\\" + extraction.getName(),
                        StringUtils.formatBytes(size));
                activity.runOnUiThread(() -> ContentDialog.confirm(getContext(), message, () -> {
                    ContentsManager.getReferenceFile(getContext(), profile).delete();
                    FileUtils.delete(extraction);
                    manager.syncContents();
                    loadContentList();
                }));
            });
            return;
        }

        String message = referenced
                ? getString(R.string.remove_installer_reference, localFile.getName())
                : getString(R.string.do_you_want_to_remove_this_content);
        if (!companions.isEmpty())
            message += "\n\n" + companions.size() + " data file(s) belonging to it will go too.";

        ContentDialog.confirm(getContext(), message, () -> {
            if (referenced) ContentsManager.getReferenceFile(getContext(), profile).delete();
            else localFile.delete();
            for (File companion : companions) companion.delete();
            // Locally added entries only exist as a file, so the profile list has to be rebuilt
            // for them to disappear.
            manager.syncContents();
            loadContentList();
        });
    }

    private void loadContentList() {
        if (currentContentType.contains(ContentProfile.ContentType.CONTENT_TYPE_ADRENO_GPU_DRIVERS)) {
            emptyText.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            recyclerView.setAdapter(new DriversAdapter(adrenotoolsManager.enumarateInstalledDrivers()));
            return;
        } else if (currentContentType.contains(ContentProfile.ContentType.CONTENT_TYPE_INSTALLER)
                || currentContentType.contains(ContentProfile.ContentType.CONTENT_TYPE_MOD)) {
            ((TextView) emptyText).setText(R.string.no_installers_or_mods_to_display);
        } else {
            ((TextView) emptyText).setText(R.string.no_items_to_display_contents);
        }

        List<ContentProfile> profiles = manager.getProfiles(currentContentType);
        if (profiles.isEmpty()) {
            emptyText.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            emptyText.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            recyclerView.setAdapter(new ContentItemAdapter(profiles));
        }
    }

    private void reload() {
        new Thread(() -> {
            ArrayList<String> zips = Downloader.getGithubZipLinks(AdrenotoolsManager.REMOTE_PROFILES);
            Activity activity = getActivity();
            if (activity == null)
                return;
            activity.runOnUiThread(() -> {
                adrenotoolsManager.setRemoteProfiles(zips);
                loadContentList();
            });
        }).start();

        new Thread(() -> {
            String json = Downloader.downloadString(ContentsManager.REMOTE_PROFILES);
            if (json == null)
                return;
            Activity activity = getActivity();
            if (activity == null)
                return;
            activity.runOnUiThread(() -> {
                manager.setRemoteProfiles(json);
                loadContentList();
            });
        }).start();
    }

    private class ContentItemAdapter extends RecyclerView.Adapter<ContentItemAdapter.ViewHolder> {
        private final List<ContentProfile> data;

        private static class ViewHolder extends RecyclerView.ViewHolder {
            private final ImageView ivIcon;
            private final TextView tvVersionName;
            private final TextView tvVersionCode;
            private final ImageButton ibMenu;
            private final ImageButton ibDownload;

            public ViewHolder(@NonNull View view) {
                super(view);

                ivIcon = view.findViewById(R.id.IVIcon);
                tvVersionName = view.findViewById(R.id.TVVersionName);
                tvVersionCode = view.findViewById(R.id.TVVersionCode);
                ibMenu = view.findViewById(R.id.BTMenu);
                ibDownload = view.findViewById(R.id.BTDownload);
            }
        }

        public ContentItemAdapter(List<ContentProfile> data) {
            this.data = data;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ContentItemAdapter.ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.content_list_item, parent, false));
        }

        @Override
        public void onViewRecycled(@NonNull ViewHolder holder) {
            holder.ibMenu.setOnClickListener(null);
            super.onViewRecycled(holder);
        }

        @SuppressLint("StringFormatInvalid")
        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            final ContentProfile profile = data.get(position);

            int iconId = switch (profile.type) {
                case CONTENT_TYPE_WINE -> R.drawable.icon_wine;
                case CONTENT_TYPE_PROTON -> R.drawable.icon_wine;
                default -> R.drawable.icon_settings;
            };
            holder.ivIcon.setBackground(getContext().getDrawable(iconId));

            final boolean isInstaller = profile.type == ContentProfile.ContentType.CONTENT_TYPE_INSTALLER;
            final boolean isMod = profile.type == ContentProfile.ContentType.CONTENT_TYPE_MOD;
            final File localFile = isMod
                    ? ContentsManager.getModFile(getContext(), profile)
                    : isInstaller
                        ? ContentsManager.getInstallerFile(getContext(), profile)
                        : ContentsManager.getRuntimeFile(getContext(), profile);

            if (isInstaller || isMod) {
                // Neither an installer nor a mod has a version behind it, so the file is the
                // identity -- and a listed one not fetched yet has no size to report.
                holder.tvVersionName.setText(profile.localFileName != null ? profile.localFileName : profile.verName);
                String subtitle = localFile.exists()
                        ? StringUtils.formatBytes(localFile.length())
                        : getString(R.string.not_downloaded_yet);
                // Which game a mod is for is the thing a list of mods is read for, so it leads.
                if (profile.game != null) subtitle = profile.game + " · " + subtitle;
                // One of the user's own files is worth locating, since it is listed from wherever
                // they keep it rather than from a folder of ours.
                if (profile.localFilePath != null && localFile.getParentFile() != null)
                    subtitle += " · " + localFile.getParentFile().getName();
                holder.tvVersionCode.setText(subtitle);
            }
            else {
                holder.tvVersionName.setText(getContext().getString(R.string.version) + ": " + profile.verName);
                holder.tvVersionCode.setText(getContext().getString(R.string.version_code) + ": " + profile.verCode);
            }
            holder.ibMenu.setVisibility(profile.remoteUrl == null ? View.VISIBLE : View.GONE);
            // Content already here has no address behind it, so the row offers its menu rather
            // than a download button whose remote URL is null.
            holder.ibDownload.setVisibility(profile.remoteUrl == null ? View.GONE : View.VISIBLE);
            holder.ibMenu.setOnClickListener(v -> {
                PopupMenu selectionMenu = new PopupMenu(getContext(), holder.ibMenu);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    selectionMenu.setForceShowIcon(true);

                // A mod is not installed into a container like everything else here: it goes into
                // one game, which has to be named before anything can happen.
                if (isMod) {
                    selectionMenu.inflate(R.menu.content_popup_mod_menu);
                    selectionMenu.setOnMenuItemClickListener(item -> {
                        int itemId = item.getItemId();
                        if (itemId == R.id.mod_install_into_game) installModIntoGame(profile, localFile);
                        else if (itemId == R.id.remove_content) confirmRemoveMod(profile, localFile);
                        return true;
                    });
                    selectionMenu.show();
                    return;
                }

                if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_RUNTIME || isInstaller) {
                    selectionMenu.inflate(R.menu.content_popup_runtime_menu);
                    selectionMenu.setOnMenuItemClickListener(item -> {
                        int itemId = item.getItemId();
                        if (itemId == R.id.content_install) {
                            // An entry whose file is still an archive -- one downloaded as a .zip,
                            // or one the user chose to keep -- is unpacked first, and what comes
                            // out of it is listed as an entry of its own.
                            if (ZipImport.isZip(localFile)) addFromZip(localFile, isInstaller);
                            else ContentInstaller.runInstaller(getActivity(), localFile);
                        } else if (itemId == R.id.remove_content) {
                            confirmRemoveLocalEntry(profile, localFile, isInstaller);
                        }
                        return true;
                    });
                    selectionMenu.show();
                    return;
                }

                selectionMenu.inflate(R.menu.content_popup_menu);
                selectionMenu.setOnMenuItemClickListener(item -> {
                    int itemId = item.getItemId();
                    if (itemId == R.id.content_info) {
                        new ContentInfoDialog(getContext(), profile).show();
                    } else if (itemId == R.id.remove_content) {
                        ContentDialog.confirm(getContext(), R.string.do_you_want_to_remove_this_content, () -> {
                            if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE || profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
                                ContainerManager containerManager = new ContainerManager(getContext());
                                for (Container container : containerManager.getContainers()) {
                                    if (container.getWineVersion().equals(ContentsManager.getEntryName(profile))) {
                                        ContentDialog.alert(getContext(), String.format(getString(R.string.unable_to_remove_content_since_container_using), container.getName()), null);
                                        return;
                                    }
                                }
                            }
                            manager.removeContent(profile);
                            loadContentList();
                        });
                    }
                    return true;
                });
                selectionMenu.show();
            });

            // These are a single file that is either here or still to be fetched, so the row
            // offers whichever of the two actions applies.
            if (isSingleFileEntry(profile) && localFile.exists()) {
                holder.ibDownload.setVisibility(View.GONE);
                holder.ibMenu.setVisibility(View.VISIBLE);
            }

            holder.ibDownload.setOnClickListener(v -> {
                Intent intent = new Intent();
                intent.setData(Uri.parse(profile.remoteUrl));
                new Thread(() -> {
                    long timestamp = System.currentTimeMillis();
                    File output = new File(getContext().getCacheDir(), "temp_" + timestamp);

                    try {
                        if (Downloader.downloadFileWithProgress(profile.remoteUrl, output, preloaderDialog)) {
                            // None of these is packed content: the download is the file itself, so
                            // it goes straight to where the install action will look for it.
                            if (isSingleFileEntry(profile)) {
                                FileUtils.copy(output, localFile);
                                output.delete();

                                getActivity().runOnUiThread(() -> {
                                    preloaderDialog.close();
                                    // A mod stays an archive: it is unpacked into a game rather
                                    // than into a folder of its own, and which game that is has
                                    // not been asked yet.
                                    if (isMod) {
                                        ContentDialog.alert(getContext(),
                                                getString(R.string.mod_downloaded, localFile.getName()), null);
                                        loadContentList();
                                        return;
                                    }
                                    // A download that turns out to be an archive holds the
                                    // program rather than being it, so it is unpacked before it
                                    // can be listed as something to run.
                                    if (ZipImport.isZip(localFile)) {
                                        addFromZip(localFile, isInstaller);
                                        return;
                                    }
                                    Toast.makeText(getContext(),
                                        isInstaller ? R.string.installer_toast : R.string.runtime_toast,
                                        Toast.LENGTH_LONG).show();
                                    loadContentList();
                                });
                                return;
                            }
                            intent.setData(Uri.parse(output.getAbsolutePath()));
                        }
                        getActivity().runOnUiThread(() -> {
                            preloaderDialog.close();
                            onActivityResult(MainActivity.OPEN_FILE_REQUEST_CODE, Activity.RESULT_OK, intent);
                        });
                    } catch (Exception e) {
                        //Expected to fail when the fragment is no longer visible
                    }
                }).start();
            });
        }

        @Override
        public int getItemCount() {
            return data.size();
        }
    }


    private class DriversAdapter extends RecyclerView.Adapter<DriversAdapter.ViewHolder> {
        private ArrayList<String> driversList;

        public class ViewHolder extends RecyclerView.ViewHolder {
            private TextView tvName;
            private TextView tvVersion;
            private ImageButton btMenu;

            public ViewHolder(View v) {
                super(v);
                tvName = v.findViewById(R.id.TVName);
                tvVersion = v.findViewById(R.id.TVVersion);
                btMenu = v.findViewById(R.id.BTMenu);
            }
        }

        public DriversAdapter(ArrayList<String> driversList) {
            this.driversList = driversList;
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup viewGroup, int viewType) {
            View view = LayoutInflater.from(viewGroup.getContext()).inflate(R.layout.adrenotools_list_item, viewGroup, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(ViewHolder viewHolder, final int position) {
            String driver = driversList.get(position);
            viewHolder.tvName.setText(adrenotoolsManager.getDriverName(driver));
            viewHolder.tvVersion.setText(adrenotoolsManager.getDriverVersion(driver));
            if (adrenotoolsManager.isRemote(driver)) {
                viewHolder.btMenu.setImageResource(R.drawable.icon_popup_menu_download);
            } else {
                viewHolder.btMenu.setImageResource(R.drawable.icon_remove);
            }
            viewHolder.btMenu.setOnClickListener((v) -> {
                if (adrenotoolsManager.isRemote(driver)) {
                    new Thread(() -> {
                        long timestamp = System.currentTimeMillis();
                        File output = new File(getContext().getCacheDir(), "temp_" + timestamp);
                        try {
                            if (Downloader.downloadFileWithProgress(adrenotoolsManager.getDriverUrl(driver), output, preloaderDialog)) {
                                adrenotoolsManager.installDriver(Uri.fromFile(output));
                            }
                            getActivity().runOnUiThread(() -> reload());
                        } catch (Exception e) {
                            //Expected to fail when the fragment is no longer visible
                        }
                    }).start();
                } else {
                    removeAtIndex(position);
                    reload();
                }
            });
        }

        public void addItem(String item) {
            driversList.add(item);
            notifyItemInserted(getItemCount() - 1);
        }

        public void removeAtIndex(int index) {
            String deletedDriver = driversList.remove(index);
            adrenotoolsManager.removeDriver(deletedDriver);
            notifyItemRemoved(index);
            notifyItemRangeChanged(index, getItemCount());
        }

        @Override
        public int getItemCount() {
            return driversList.size();
        }
    }
}
