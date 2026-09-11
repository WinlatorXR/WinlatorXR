package com.winlator.cmod;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.core.ZDriveGames;
import com.winlator.cmod.store.StoreGameInstall;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * The Z: Drive tab of the Games screen: what is installed outside every container.
 *
 * The Shortcuts tab lists what can be played, which is not the same as what is taking up room.
 * Removing a shortcut leaves the game behind, and a game on Z: belongs to no container, so from
 * that point on nothing names it anywhere -- the only way to find it again is a file manager
 * inside a container. This is the list that says it is still there, and the place to give it a
 * shortcut again.
 */
public class ZDriveFragment extends Fragment {
    private RecyclerView recyclerView;
    private TextView emptyTextView;
    private ContainerManager manager;
    private PreloaderDialog preloaderDialog;

    /** Scanning is not free, so it happens when the tab is opened rather than on every redraw. */
    private volatile boolean scanning;

    /** A store page can uninstall or update the game, so the list is read again on the way back. */
    private boolean returningFromStorePage;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        FrameLayout frameLayout = (FrameLayout)inflater.inflate(R.layout.z_drive_fragment, container, false);
        recyclerView = frameLayout.findViewById(R.id.RecyclerView);
        emptyTextView = frameLayout.findViewById(R.id.TVEmptyText);
        recyclerView.setLayoutManager(new LinearLayoutManager(recyclerView.getContext()));
        recyclerView.addItemDecoration(new DividerItemDecoration(recyclerView.getContext(), DividerItemDecoration.VERTICAL));
        return frameLayout;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        manager = new ContainerManager(getContext());
        preloaderDialog = new PreloaderDialog(getActivity());
    }

    /**
     * Reads Z: again and shows what is there.
     *
     * Called by the Games screen when this tab is opened: the list is only ever as old as the
     * last time the user asked to see it, and this fragment is created with the screen whether
     * or not its tab is the one showing.
     */
    public void refresh() {
        if (scanning || manager == null || getContext() == null) return;
        scanning = true;

        final Context context = requireContext().getApplicationContext();

        // Measuring every game folder and reading every container's shortcuts is a disk walk, so
        // it does not happen on the UI thread.
        Executors.newSingleThreadExecutor().execute(() -> {
            final List<ZDriveGames.Game> games = ZDriveGames.scan(context, manager);

            Activity activity = getActivity();
            if (activity == null) {
                scanning = false;
                return;
            }

            activity.runOnUiThread(() -> {
                scanning = false;
                // The screen can be gone by the time the walk finishes.
                if (recyclerView == null) return;

                recyclerView.setAdapter(new ZDriveAdapter(games));
                emptyTextView.setVisibility(games.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (returningFromStorePage) {
            returningFromStorePage = false;
            refresh();
        }
    }

    /**
     * Opens the page the store that installed the game keeps for it.
     *
     * The way there from the store is its library, filtered down to what is installed, and then
     * the game; this is the same page, reached from the folder instead. It is only offered while
     * the store is signed in, since signed out it has no library to show the game in.
     */
    private void openStorePage(ZDriveGames.Game game) {
        if (getContext() == null) return;

        final Context context = requireContext().getApplicationContext();
        final StoreGameInstall install = StoreGameInstall.forInstallDir(context, game.dir);
        if (install == null) return;

        // Finding the game means reading the store's records, and for Steam its database.
        Executors.newSingleThreadExecutor().execute(() -> {
            final boolean signedIn = install.isSignedIn(context);
            final Intent page = signedIn ? install.storePage(context) : null;

            Activity activity = getActivity();
            if (activity == null) return;

            activity.runOnUiThread(() -> {
                if (getContext() == null) return;

                if (!signedIn) {
                    ContentDialog.alert(getContext(), getString(R.string.z_drive_store_signed_out,
                            install.storeName, game.getName()), null);
                }
                else if (page == null) {
                    ContentDialog.alert(getContext(), getString(R.string.z_drive_store_no_record,
                            install.storeName, game.getName()), null);
                }
                else {
                    returningFromStorePage = true;
                    startActivity(page);
                }
            });
        });
    }

    /** Adds a shortcut, then shows both lists as they now are. */
    private void createShortcut(ZDriveGames.Game game) {
        if (getActivity() == null) return;

        ZDriveGames.createShortcut(getActivity(), game, () -> {
            refresh();
            // The new shortcut belongs on the Shortcuts tab, which is not redrawn on its own.
            if (getParentFragment() instanceof ShortcutsFragment)
                ((ShortcutsFragment)getParentFragment()).loadShortcutsList();
        });
    }

    /**
     * Puts what deleting a game takes in front of the user before anything goes.
     *
     * Nothing on Z: belongs to a single container, so this is never one container's game being
     * removed: it goes for all of them at once, which is why the shortcuts that point at it are
     * named here rather than left to be discovered as entries that no longer start anything.
     */
    private void confirmDelete(ZDriveGames.Game game) {
        if (getContext() == null) return;

        String message = getString(R.string.z_drive_delete_message, game.getName(),
                StringUtils.formatBytes(game.size), game.winPath);

        if (!game.shortcuts.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (Shortcut shortcut : game.shortcuts)
                names.append("\n• ").append(shortcut.name)
                     .append(" (").append(shortcut.container.getName()).append(")");
            message += "\n\n" + getString(R.string.uninstall_store_game_other_shortcuts, names.toString());
        }

        new AlertDialog.Builder(getContext())
                .setTitle(R.string.z_drive_delete_title)
                .setMessage(message)
                .setPositiveButton(R.string.delete_folder, (dialog, which) -> deleteGame(game))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Deleting a game folder can run to gigabytes, so it does not happen on the UI thread. */
    private void deleteGame(ZDriveGames.Game game) {
        if (getContext() == null) return;

        preloaderDialog.showOnUiThread(R.string.deleting_game_folder);
        // The work outlives this tab being torn down, and a store's records are the application's
        // rather than this screen's.
        final Context context = requireContext().getApplicationContext();
        // A store page goes by its own record of what is installed rather than by the disk, so it
        // keeps offering to launch a game whose files are gone until that record goes too.
        final StoreGameInstall storeInstall = StoreGameInstall.forInstallDir(context, game.dir);

        Executors.newSingleThreadExecutor().execute(() -> {
            boolean deleted = FileUtils.delete(game.dir);
            if (deleted && storeInstall != null) storeInstall.forget(context);

            Activity activity = getActivity();
            if (activity == null) return;

            activity.runOnUiThread(() -> {
                preloaderDialog.close();

                if (!deleted) {
                    ContentDialog.alert(getContext(),
                            getString(R.string.delete_install_dir_failed, game.getName()), null);
                    return;
                }

                // What the shortcuts pointed at is gone, so all they could do now is fail to
                // start. They were listed as going with it, and this is that.
                if (getParentFragment() instanceof ShortcutsFragment) {
                    ShortcutsFragment shortcutsFragment = (ShortcutsFragment)getParentFragment();
                    for (Shortcut shortcut : game.shortcuts) shortcutsFragment.deleteShortcutFiles(shortcut);
                    shortcutsFragment.loadShortcutsList();
                }
                refresh();
            });
        });
    }

    /** Everything known about one game, for when the row's two lines are not enough. */
    private void showProperties(ZDriveGames.Game game) {
        StringBuilder message = new StringBuilder();
        message.append(getString(R.string.z_drive_properties_location, game.winPath));
        message.append("\n").append(getString(R.string.z_drive_properties_source, game.source));
        message.append("\n").append(getString(R.string.z_drive_properties_size, StringUtils.formatBytes(game.size)));

        if (game.shortcuts.isEmpty()) {
            message.append("\n\n").append(getString(R.string.z_drive_properties_no_shortcuts));
        }
        else {
            message.append("\n\n").append(getString(R.string.z_drive_properties_shortcuts));
            for (Shortcut shortcut : game.shortcuts)
                message.append("\n• ").append(shortcut.name)
                       .append(" (").append(shortcut.container.getName()).append(")");
        }

        ContentDialog dialog = new ContentDialog(getContext());
        dialog.setTitle(game.getName());
        dialog.setMessage(message.toString());
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }

    private class ZDriveAdapter extends RecyclerView.Adapter<ZDriveAdapter.ViewHolder> {
        private final List<ZDriveGames.Game> data;

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

        private ZDriveAdapter(List<ZDriveGames.Game> data) {
            this.data = new ArrayList<>(data);
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.shortcut_list_item, parent, false));
        }

        @Override
        public void onViewRecycled(@NonNull ViewHolder holder) {
            holder.menuButton.setOnClickListener(null);
            super.onViewRecycled(holder);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            final ZDriveGames.Game game = data.get(position);

            holder.imageView.setImageResource(R.mipmap.ic_launcher_foreground);
            holder.imageView.setClickable(false);
            holder.imageView.setFocusable(false);
            holder.title.setText(game.getName());
            holder.subtitle.setText(getString(R.string.z_drive_subtitle,
                    game.source, StringUtils.formatBytes(game.size), shortcutState(game)));

            // A row says what is on Z: and nothing more: the actions are all on its menu, so a
            // stray tap cannot start one. setOnClickListener would make it clickable again even
            // with a null listener, so the row is switched off rather than left unlistened.
            holder.innerArea.setClickable(false);
            holder.innerArea.setFocusable(false);
            holder.menuButton.setOnClickListener((v) -> showListItemMenu(v, game));

            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(holder.itemView.getContext());
            holder.title.setTextColor(preferences.getBoolean("dark_mode", false) ? Color.WHITE : Color.BLACK);
        }

        /** Whether anything can still play this game, which is what the tab exists to answer. */
        private String shortcutState(ZDriveGames.Game game) {
            if (game.shortcuts.isEmpty()) return getString(R.string.z_drive_no_shortcut);
            if (game.shortcuts.size() == 1)
                return getString(R.string.z_drive_one_shortcut, game.shortcuts.get(0).container.getName());
            return getString(R.string.z_drive_many_shortcuts, game.shortcuts.size());
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        private void showListItemMenu(View anchorView, final ZDriveGames.Game game) {
            PopupMenu listItemMenu = new PopupMenu(getContext(), anchorView);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listItemMenu.setForceShowIcon(true);

            listItemMenu.inflate(R.menu.z_drive_popup_menu);
            // Only what a store installed has a store page; copied and unpacked games do not.
            listItemMenu.getMenu().findItem(R.id.z_drive_store_page)
                    .setVisible(StoreGameInstall.forInstallDir(getContext(), game.dir) != null);
            listItemMenu.setOnMenuItemClickListener((menuItem) -> {
                int itemId = menuItem.getItemId();
                if (itemId == R.id.z_drive_create_shortcut) createShortcut(game);
                else if (itemId == R.id.z_drive_store_page) openStorePage(game);
                else if (itemId == R.id.z_drive_delete) confirmDelete(game);
                else if (itemId == R.id.z_drive_properties) showProperties(game);
                return true;
            });
            listItemMenu.show();
        }
    }
}
