package com.winlator.cmod;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.contents.ContentInstaller;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class ContentsFragment extends Fragment {
    private RecyclerView recyclerView;
    private View emptyText;
    private AdrenotoolsManager adrenotoolsManager;
    private ContentsManager manager;
    private PreloaderDialog preloaderDialog;
    private ArrayList<ContentProfile.ContentType> currentContentType = new ArrayList<>();
    private Button btInstallContent;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);
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

        btInstallContent = layout.findViewById(R.id.BTInstallContent);
        btInstallContent.setOnClickListener(v -> {
            if (currentContentType.contains(ContentProfile.ContentType.CONTENT_TYPE_ADRENO_GPU_DRIVERS)) {
                ContentDialog.confirm(getContext(), getString(R.string.install_drivers_message) + " " + getString(R.string.install_drivers_warning), () -> {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    getActivity().startActivityFromFragment(this, intent, MainActivity.OPEN_FILE_REQUEST_CODE);
                });
                return;
            }

            final boolean installers = currentContentType.contains(ContentProfile.ContentType.CONTENT_TYPE_INSTALLER);

            String message = getString(R.string.do_you_want_to_install_content) + " " + getString(R.string.pls_make_sure_content_trustworthy) + " ";
            if (installers) message += getString(R.string.select_installer);
            else message += currentContentType.contains(ContentProfile.ContentType.CONTENT_TYPE_RUNTIME)
                    ? getString(R.string.content_suffix_is_wcp_or_runtime_installer)
                    : getString(R.string.content_suffix_is_wcp_packed_xz_zst);

            ContentDialog.confirm(getContext(), message, () -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                // An installer is taken from the Download folder, which is the D: drive a
                // container gets by default, so open the picker there.
                if (installers) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, ContentInstaller.downloadsDocumentUri());
                getActivity().startActivityFromFragment(this, intent, MainActivity.OPEN_FILE_REQUEST_CODE);
            });
        });

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
                        break;
                    case 2: currentContentType.add(ContentProfile.ContentType.CONTENT_TYPE_DXVK); break;
                    case 3: currentContentType.add(ContentProfile.ContentType.CONTENT_TYPE_VKD3D); break;
                    case 4: currentContentType.add(ContentProfile.ContentType.CONTENT_TYPE_BOX64); break;
                    case 5: currentContentType.add(ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64); break;
                    case 6: currentContentType.add(ContentProfile.ContentType.CONTENT_TYPE_FEXCORE); break;
                    case 7: currentContentType.add(ContentProfile.ContentType.CONTENT_TYPE_ADRENO_GPU_DRIVERS); break;
                    case 8: currentContentType.add(ContentProfile.ContentType.CONTENT_TYPE_INSTALLER); break;
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
        tabLayout.getTabAt(0).select();

        return layout;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (currentContentType.contains(ContentProfile.ContentType.CONTENT_TYPE_ADRENO_GPU_DRIVERS)) {
            if (requestCode == MainActivity.OPEN_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
                Uri uri = data.getData();
                String driver = adrenotoolsManager.installDriver(uri);
                if (!driver.isEmpty())
                    ((DriversAdapter)recyclerView.getAdapter()).addItem(driver);
            }
            return;
        }

        // A demo or offline installer is not packed content and is not copied in either: the
        // entry is a reference to the file where the user keeps it.
        if (requestCode == MainActivity.OPEN_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK
                && currentContentType.contains(ContentProfile.ContentType.CONTENT_TYPE_INSTALLER)
                && data.getData() != null) {
            ContentInstaller.addLocalInstaller(getContext(), data.getData(), () -> {
                manager.syncContents();
                loadContentList();
            });
            return;
        }

        // A runtime installer is a plain .exe/.msi, not a packed .wcp, so it is copied into the
        // runtimes directory rather than being handed to the content extractor.
        if (requestCode == MainActivity.OPEN_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK
                && currentContentType.contains(ContentProfile.ContentType.CONTENT_TYPE_RUNTIME)) {
            String name = ContentInstaller.queryDisplayName(getContext(), data.getData());
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

    private void loadContentList() {
        if (currentContentType.contains(ContentProfile.ContentType.CONTENT_TYPE_ADRENO_GPU_DRIVERS)) {
            emptyText.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            recyclerView.setAdapter(new DriversAdapter(adrenotoolsManager.enumarateInstalledDrivers()));
            btInstallContent.setText(R.string.install_drivers);
            return;
        } else if (currentContentType.contains(ContentProfile.ContentType.CONTENT_TYPE_INSTALLER)) {
            btInstallContent.setText(R.string.add_installer);
            ((TextView) emptyText).setText(R.string.no_installers_to_display);
        } else {
            btInstallContent.setText(R.string.install_content);
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
            final File localFile = isInstaller
                    ? ContentsManager.getInstallerFile(getContext(), profile)
                    : ContentsManager.getRuntimeFile(getContext(), profile);

            if (isInstaller) {
                // An installer has no version behind it, so the file is the identity -- and a
                // listed one that has not been fetched yet has no size to report.
                holder.tvVersionName.setText(profile.localFileName != null ? profile.localFileName : profile.verName);
                String subtitle = localFile.exists()
                        ? StringUtils.formatBytes(localFile.length())
                        : getString(R.string.not_downloaded_yet);
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
            holder.ibMenu.setOnClickListener(v -> {
                PopupMenu selectionMenu = new PopupMenu(getContext(), holder.ibMenu);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    selectionMenu.setForceShowIcon(true);

                if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_RUNTIME || isInstaller) {
                    selectionMenu.inflate(R.menu.content_popup_runtime_menu);
                    selectionMenu.setOnMenuItemClickListener(item -> {
                        int itemId = item.getItemId();
                        if (itemId == R.id.content_install) {
                            ContentInstaller.runInstaller(getActivity(), localFile);
                        } else if (itemId == R.id.remove_content) {
                            // An entry that only references a file of the user's takes nothing
                            // with it but the reference; the installer stays in their Download
                            // folder, as do the data files beside it.
                            final boolean referenced = profile.localFilePath != null;
                            List<File> companions = isInstaller && !referenced ? ContentInstaller.companionFiles(localFile) : new ArrayList<>();
                            String message = referenced
                                    ? getString(R.string.remove_installer_reference, localFile.getName())
                                    : getString(R.string.do_you_want_to_remove_this_content);
                            if (!companions.isEmpty())
                                message += "\n\n" + companions.size() + " data file(s) belonging to it will go too.";

                            ContentDialog.confirm(getContext(), message, () -> {
                                if (referenced) ContentsManager.getInstallerReferenceFile(getContext(), profile).delete();
                                else localFile.delete();
                                for (File companion : companions) companion.delete();
                                // Locally added entries only exist as a file, so the profile list
                                // has to be rebuilt for them to disappear.
                                manager.syncContents();
                                loadContentList();
                            });
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

            // Both kinds are a single file that is either here or still to be fetched, so the row
            // offers whichever of the two actions applies.
            if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_RUNTIME || isInstaller) {
                if (localFile.exists()) {
                    holder.ibDownload.setVisibility(View.GONE);
                    holder.ibMenu.setVisibility(View.VISIBLE);
                } else {
                    holder.ibDownload.setVisibility(View.VISIBLE);
                    holder.ibMenu.setVisibility(View.GONE);
                }
            }

            holder.ibDownload.setOnClickListener(v -> {
                Intent intent = new Intent();
                intent.setData(Uri.parse(profile.remoteUrl));
                new Thread(() -> {
                    long timestamp = System.currentTimeMillis();
                    File output = new File(getContext().getCacheDir(), "temp_" + timestamp);

                    try {
                        if (Downloader.downloadFileWithProgress(profile.remoteUrl, output, preloaderDialog)) {
                            // Neither is packed content: the download is the file itself, so it
                            // goes straight to where the install action will look for it.
                            if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_RUNTIME || isInstaller) {
                                FileUtils.copy(output, localFile);
                                output.delete();

                                getActivity().runOnUiThread(() -> {
                                    preloaderDialog.close();
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
