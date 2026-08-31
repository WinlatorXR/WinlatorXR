package com.winlator.cmod;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.tabs.TabLayout;
import com.winlator.cmod.contents.Downloader;
import com.winlator.xr.XrActivity;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contentdialog.StorageInfoDialog;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class ContainersFragment extends Fragment {
    private static final String REMOTE_CONTAINERS = "https://raw.githubusercontent.com/WinlatorXR/Winlator-Contents/refs/heads/main/containers.lst";
    private static final int REQUEST_CODE_IMPORT_CONTAINER = 1070;
    private static final int REQUEST_CODE_IMPORT_CONTAINER_ARCHIVE = 1071;
    private RecyclerView recyclerView;
    private TextView emptyTextView;
    private ContainerManager manager;
    private PreloaderDialog preloaderDialog;
    private TabLayout tabLayout;

    private int currentTab = 0;


    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        preloaderDialog = new PreloaderDialog(getActivity());
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        manager = new ContainerManager(getContext());
        loadContainersList();
        ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(R.string.containers);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        FrameLayout frameLayout = (FrameLayout) inflater.inflate(R.layout.containers_fragment, container, false);
        recyclerView = frameLayout.findViewById(R.id.RecyclerView);
        emptyTextView = frameLayout.findViewById(R.id.TVEmptyText);
        recyclerView.setLayoutManager(new LinearLayoutManager(recyclerView.getContext()));
        recyclerView.addItemDecoration(new DividerItemDecoration(recyclerView.getContext(), DividerItemDecoration.VERTICAL));

        // Tab switcher
        tabLayout = frameLayout.findViewById(R.id.TabLayout);
        tabLayout.setTabTextColors(Color.LTGRAY, Color.WHITE);
        tabLayout.setSelectedTabIndicatorColor(Color.WHITE);
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                currentTab = tab.getPosition();
                loadContainersList();
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
        currentTab = 0;
        tabLayout.selectTab(tabLayout.getTabAt(currentTab));
        return frameLayout;
    }

    public void loadContainersList() {
        if (manager != null) {
            ArrayList<Container> containers;
            if (currentTab == 0) {
                manager.loadContainers();
                containers = manager.getContainers();
            } else if (currentTab == 1) {
                int index = 0;
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Winlator/Backups/Containers");
                containers = new ArrayList<>();
                if (dir != null && dir.exists()) {
                    for (File file : dir.listFiles()) {
                        if (!file.isDirectory() && file.getAbsolutePath().endsWith(".tzst")) {
                            Container c = new Container(index++);
                            c.setName(file.getName());
                            containers.add(c);
                        }
                    }
                }
            } else {
                containers = new ArrayList<>();
                new Thread(() -> {
                    int index = 0;
                    Scanner sc = new Scanner(Downloader.downloadString(REMOTE_CONTAINERS));
                    while (sc.hasNext()) {
                        String line = sc.nextLine();
                        String[] parts = line.split(",");
                        Container c = new Container(index++);
                        c.setName(parts[0]);
                        c.setEmulator(parts[1]);
                        containers.add(c);
                    }
                    sc.close();
                    Activity activity = getActivity();
                    if (activity == null)
                        return;
                    activity.runOnUiThread(() -> {
                        recyclerView.setAdapter(new ContainersAdapter(containers));
                        emptyTextView.setVisibility(containers.isEmpty() ? View.VISIBLE : View.GONE);
                    });
                }).start();
            }
            recyclerView.setAdapter(new ContainersAdapter(containers));
            emptyTextView.setVisibility(containers.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.clear();
        inflater.inflate(R.menu.containers_menu, menu);

        menu.findItem(R.id.containers_menu_add).setVisible(currentTab == 0);
        menu.findItem(R.id.containers_menu_import).setVisible(currentTab == 1);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        switch (menuItem.getItemId()) {
            case R.id.containers_menu_add:
                if (!ImageFs.find(getContext()).isValid()) return false;
                openCreateContainer();
                return true;

            case R.id.containers_menu_import:
                openImportContainerArchive();
                return true;

            default:
                return super.onOptionsItemSelected(menuItem);
        }
    }

    private void openCreateContainer() {
        getParentFragmentManager().beginTransaction()
                .setCustomAnimations(R.anim.slide_in_up, R.anim.slide_out_down, R.anim.slide_in_down, R.anim.slide_out_up)
                .addToBackStack(null)
                .replace(R.id.FLFragmentContainer, new ContainerDetailFragment())
                .commit();
    }

    // Pick a single exported container image (.tzst/.txz) to import.
    private void openImportContainerArchive() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_CODE_IMPORT_CONTAINER_ARCHIVE);
    }

    private void importContainerArchive(Uri uri, Runnable onFinish) {
        if (uri == null) return;
        preloaderDialog.show(R.string.importing_container);

        new Thread(() -> {
            // Resolves to the real file when possible, otherwise a temp copy in the cache dir.
            File archive = FileUtils.getFileFromUri(getContext(), uri);
            if (archive == null || !archive.isFile()) {
                runOnUiThreadSafe(() -> {
                    preloaderDialog.close();
                    AppUtils.showToast(getContext(), getString(R.string.import_container_invalid));
                });
                return;
            }

            manager.importContainerFromArchive(archive, (Boolean success) -> {
                // Delivered on the UI thread by the manager.
                preloaderDialog.close();
                if (success != null && success) {
                    loadContainersList();
                    AppUtils.showToast(getContext(), getString(R.string.import_container_success));
                } else {
                    AppUtils.showToast(getContext(), getString(R.string.import_container_failed));
                }
                // Remove the temp copy if getFileFromUri created one in the cache dir.
                if (getContext() != null
                        && archive.getAbsolutePath().startsWith(getContext().getCacheDir().getAbsolutePath())) {
                    FileUtils.delete(archive);
                }

                if (onFinish != null) {
                    onFinish.run();
                }
            });
        }).start();
    }

    private void runOnUiThreadSafe(Runnable action) {
        if (getActivity() != null) getActivity().runOnUiThread(action);
    }

    // Show confirmation dialog before importing the selected container
    private void showImportConfirmationDialog(Uri uri, File importDir) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("Confirm Import");
        builder.setMessage("You selected: " + importDir.getPath() + ". Proceed to import the container?");
        builder.setPositiveButton("Import", (dialog, which) -> {
            importContainer(uri); // Proceed with the import
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());
        builder.show();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_IMPORT_CONTAINER_ARCHIVE && resultCode == Activity.RESULT_OK) {
            currentTab = 0;
            tabLayout.selectTab(tabLayout.getTabAt(currentTab));
            if (data != null && data.getData() != null) importContainerArchive(data.getData(), null);
            return;
        }
        if (requestCode == REQUEST_CODE_IMPORT_CONTAINER && resultCode == Activity.RESULT_OK) {
            if (data != null) {
                Uri uri = data.getData();
                if (uri != null) {
                    // Get the directory path directly from the Uri using FileUtils
                    File importDir = FileUtils.getFileFromUri(getContext(), uri);
                    if (importDir == null || !importDir.isDirectory()) {
                        AppUtils.showToast(getContext(), "Invalid container directory.");
                        return;
                    }
                    // Show confirmation dialog before importing
                    showImportConfirmationDialog(uri, importDir);
                }
            }
        }
    }


    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_CODE_IMPORT_CONTAINER);
    }


    private void importContainer(Uri uri) {
        if (uri == null) return;

        // Get the directory path directly from the Uri using FileUtils
        File importDir = FileUtils.getFileFromUri(getContext(), uri);
        if (importDir == null || !importDir.isDirectory()) {
            AppUtils.showToast(getContext(), "Invalid container directory.");
            return;
        }

        preloaderDialog.show(R.string.importing_container);

        // Run the import operation on a background thread
        new Thread(() -> {
            try {
                // Now use the import directory directly for importing the container
                manager.importContainer(importDir, () -> {
                    // This callback runs when the import operation completes
                    getActivity().runOnUiThread(() -> {
                        // Load containers and close preloader dialog on the UI thread
                        loadContainersList();
                        AppUtils.showToast(getContext(), "Container imported successfully.");
                        preloaderDialog.close(); // Move this inside the callback
                    });
                });
            } catch (Exception e) {
                getActivity().runOnUiThread(() -> {
                    preloaderDialog.close(); // Ensure dialog closes on error
                    AppUtils.showToast(getContext(), "Error importing container: " + e.getMessage());
                });
            }
        }).start();
    }

    private class ContainersAdapter extends RecyclerView.Adapter<ContainersAdapter.ViewHolder> {
        private final List<Container> data;

        private class ViewHolder extends RecyclerView.ViewHolder {
            private final ImageView runButton; // Changed to ImageButton
            private final ImageView menuButton; // Changed to ImageButton
            private final ImageView imageView;
            private final TextView title;

            private ViewHolder(View view) {
                super(view);
                this.runButton = view.findViewById(R.id.BTRun); // Find by correct ID
                this.imageView = view.findViewById(R.id.ImageView);
                this.title = view.findViewById(R.id.TVTitle);
                this.menuButton = view.findViewById(R.id.BTMenu);
            }
        }

        public ContainersAdapter(List<Container> data) {
            this.data = data;
        }

        @Override
        public final ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.container_list_item, parent, false));
        }

        @Override
        public void onViewRecycled(@NonNull ViewHolder holder) {
            holder.runButton.setOnClickListener(null); // Remove listeners
            holder.menuButton.setOnClickListener(null); // Remove listeners
            super.onViewRecycled(holder);
        }

        @Override
        public void onBindViewHolder(final ViewHolder holder, int position) {
            final Container item = data.get(position); // Use 'item' instead of undefined 'container'
            holder.imageView.setImageResource(R.drawable.icon_container);
            holder.title.setText(item.getName());

            holder.runButton.setOnClickListener(view -> proceedWithLaunch(item)); // Correct item reference

            holder.menuButton.setOnClickListener(view -> showListItemMenu(view, item));

            holder.runButton.setVisibility(currentTab == 0 ? View.VISIBLE : View.GONE);
        }

        @Override
        public final int getItemCount() {
            return data.size();
        }

        private void proceedWithLaunch(Container container) {
            final Context context = getContext();

            File box64File = new File(context.getFilesDir(), "imagefs/usr/bin/box64");
            if (box64File.exists()) {
                box64File.delete();
                Log.i("ContainersFragment", "Deleted existing box64 to ensure a clean launch.");
            }

            if (!XrActivity.isEnabled(getContext())) {
                Intent intent = new Intent(context, XServerDisplayActivity.class);
                intent.putExtra("container_id", container.id);
                requireActivity().startActivity(intent);
            } else {
                XrActivity.openIntent(getActivity(), container.id, null);
            }
        }

        private void showListItemMenu(View anchorView, Container container) {
            final Context context = getContext();
            PopupMenu listItemMenu = new PopupMenu(context, anchorView);
            switch (currentTab) {
                case 0:
                    listItemMenu.inflate(R.menu.container_popup_menu);
                    break;
                case 1:
                    listItemMenu.inflate(R.menu.container_backup_menu);
                    break;
                case 2:
                    listItemMenu.inflate(R.menu.container_download_menu);
                    break;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listItemMenu.setForceShowIcon(true);

            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Winlator/Backups/Containers");
            listItemMenu.setOnMenuItemClickListener((menuItem) -> {
                switch (menuItem.getItemId()) {
                    case R.id.backup_import:
                        currentTab = 0;
                        tabLayout.selectTab(tabLayout.getTabAt(currentTab));
                        File file = new File(dir, container.getName());
                        importContainerArchive(Uri.fromFile(file), null);
                        break;
                    case R.id.backup_remove:
                        new File(dir, container.getName()).delete();
                        loadContainersList();
                        break;
                    case R.id.download_import:
                        File temp = new File(context.getCacheDir(), "container.tzst");
                        String url = container.getEmulator();
                        new Thread(() -> {
                            Downloader.downloadFileWithProgress(url, temp, preloaderDialog);
                            runOnUiThreadSafe(() -> {
                                currentTab = 0;
                                tabLayout.selectTab(tabLayout.getTabAt(currentTab));
                                importContainerArchive(Uri.fromFile(temp), () -> temp.delete());
                            });
                        }).start();
                        break;
                    case R.id.container_edit:
                        FragmentManager fragmentManager = getParentFragmentManager();
                        fragmentManager.beginTransaction()
                                .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left)
                                .addToBackStack(null)
                                .replace(R.id.FLFragmentContainer, new ContainerDetailFragment(container.id))
                                .commit();
                        break;
                    case R.id.container_duplicate:
                        ContentDialog.confirm(getContext(), R.string.do_you_want_to_duplicate_this_container, () -> {
                            preloaderDialog.show(R.string.duplicating_container);
                            manager.duplicateContainerAsync(container, () -> {
                                preloaderDialog.close();
                                loadContainersList();
                            });
                        });
                        break;
                    case R.id.container_remove:
                        ContentDialog.confirm(getContext(), R.string.do_you_want_to_remove_this_container, () -> {
                            preloaderDialog.show(R.string.removing_container);
                            for (Shortcut shortcut : manager.loadShortcuts()) {
                                if (shortcut.container == container)
                                    ShortcutsFragment.disableShortcutOnScreen(context, shortcut);
                            }
                            manager.removeContainerAsync(container, () -> {
                                preloaderDialog.close();
                                loadContainersList();
                            });
                        });
                        break;
                    case R.id.container_info:
                        (new StorageInfoDialog(getActivity(), container)).show();
                        break;
                    case R.id.container_reconfigure:
                        ContentDialog.confirm(getContext(), R.string.do_you_want_to_reconfigure_wine, () -> {
                            new File(container.getRootDir(), ".wine/.update-timestamp").delete();
                        });
                        break;
                    case R.id.container_export_image:
                        container.clearCache();
                        exportContainerImage(container);
                        break;
                }
                return true;
            });
            listItemMenu.show();
        }

        // Export a container as a single compressed .tzst image (the golden-image artifact).
        private void exportContainerImage(Container container) {
            preloaderDialog.show(R.string.exporting_container_image);
            manager.exportContainerAsImage(container, imageFile -> {
                preloaderDialog.close();
                if (imageFile != null) {
                    showToast(getString(R.string.export_container_image_success, imageFile.getPath()));
                } else {
                    showToast(getString(R.string.export_container_image_failed));
                }
            });
        }

        private void showToast(String message) {
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    Toast.makeText(getActivity(), message, Toast.LENGTH_SHORT).show();
                });
            }
        }
    }
}