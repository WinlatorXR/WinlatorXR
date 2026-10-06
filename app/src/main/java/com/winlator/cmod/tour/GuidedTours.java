package com.winlator.cmod.tour;

import android.app.AlertDialog;
import android.view.View;
import android.view.ViewGroup;

import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.tabs.TabLayout;
import com.winlator.cmod.ContainersFragment;
import com.winlator.cmod.ContentsFragment;
import com.winlator.cmod.MainActivity;
import com.winlator.cmod.R;
import com.winlator.cmod.ShortcutsFragment;
import com.winlator.cmod.settings.SettingsFragment;
import com.winlator.cmod.store.StoreFragment;

import java.util.Arrays;
import java.util.function.Supplier;

/** The tours offered from the Guided Tour button at the right of the bottom bar. */
public final class GuidedTours {
    private GuidedTours() {}

    public static void showPicker(MainActivity activity) {
        String[] tours = {
                activity.getString(R.string.tour_add_game),
                activity.getString(R.string.tour_game_settings),
                activity.getString(R.string.tour_store),
                activity.getString(R.string.tour_installed),
                activity.getString(R.string.tour_saves),
                activity.getString(R.string.tour_controllers),
                activity.getString(R.string.tour_xr_settings),
                activity.getString(R.string.tour_gamepad),
                activity.getString(R.string.tour_mouse),
                activity.getString(R.string.tour_vr),
                activity.getString(R.string.tour_containers),
                activity.getString(R.string.tour_downloader),
                activity.getString(R.string.tour_mods),
                activity.getString(R.string.tour_backup)
        };
        new AlertDialog.Builder(activity)
                .setTitle(R.string.guided_tour)
                .setItems(tours, (dialog, which) -> {
                    switch (which) {
                        case 0 -> addGame(activity);
                        case 1 -> gameSettings(activity);
                        case 2 -> store(activity);
                        case 3 -> installedGames(activity);
                        case 4 -> saves(activity);
                        case 5 -> externalControllers(activity);
                        case 6 -> xrSettings(activity);
                        case 7 -> gamepad(activity);
                        case 8 -> mouse(activity);
                        case 9 -> vr(activity);
                        case 10 -> containers(activity);
                        case 11 -> downloader(activity);
                        case 12 -> mods(activity);
                        case 13 -> backup(activity);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Bottom bar Games button, then the Shortcuts tab, then the + that adds a game. */
    public static void addGame(MainActivity activity) {
        Runnable openGames = () -> open(activity, R.id.main_menu_shortcuts, ShortcutsFragment.class);

        GuidedTour.start(activity, Arrays.asList(
                new GuidedTour.Step(R.string.tour_add_game_games, () -> navButton(activity, R.id.main_menu_shortcuts), null),
                new GuidedTour.Step(R.string.tour_add_game_tab, () -> tab(activity, ShortcutsFragment.class, 0), openGames),
                new GuidedTour.Step(R.string.tour_add_game_plus, () -> {
                    // The + only shows on the Shortcuts tab.
                    return tab(activity, ShortcutsFragment.class, 0) != null
                            ? activity.findViewById(R.id.shortcuts_menu_add_local_game) : null;
                }, openGames)));
    }

    /**
     * Starting a game from its row, then the row's ⋮ menu: its settings, then the other tools in
     * it. The menu opens as a window of its own, which the spotlight cannot reach into, so those
     * steps point at the ⋮ and name its items; only the last one opens it.
     */
    public static void gameSettings(MainActivity activity) {
        Runnable openGames = () -> open(activity, R.id.main_menu_shortcuts, ShortcutsFragment.class);
        Supplier<View> menu = () -> firstRowView(activity, ShortcutsFragment.class, 0, R.id.LLTabShortcuts, R.id.BTMenu);

        GuidedTour.start(activity, Arrays.asList(
                new GuidedTour.Step(R.string.tour_game_settings_games, () -> navButton(activity, R.id.main_menu_shortcuts), null),
                new GuidedTour.Step(R.string.tour_game_settings_play,
                        () -> firstRowView(activity, ShortcutsFragment.class, 0, R.id.LLTabShortcuts, R.id.BTPlay), openGames).noPress(),
                new GuidedTour.Step(R.string.tour_game_settings_menu, menu, openGames).noPress(),
                new GuidedTour.Step(R.string.tour_game_menu_mod, menu, openGames).noPress(),
                new GuidedTour.Step(R.string.tour_game_menu_steam, menu, openGames).noPress(),
                new GuidedTour.Step(R.string.tour_game_menu_copy, menu, openGames).noPress(),
                new GuidedTour.Step(R.string.tour_game_menu_open, menu, openGames)));
    }

    /**
     * The stores open screens of their own, which the tour cannot follow into, so it stops at
     * them: Downloads is pointed out first and the store tiles come last.
     */
    public static void store(MainActivity activity) {
        Runnable openStore = () -> open(activity, R.id.main_menu_store, StoreFragment.class);

        GuidedTour.start(activity, Arrays.asList(
                new GuidedTour.Step(R.string.tour_store_nav, () -> navButton(activity, R.id.main_menu_store), null),
                new GuidedTour.Step(R.string.tour_store_downloads, () -> showing(activity, StoreFragment.class)
                        ? activity.findViewById(R.id.store_menu_downloader) : null, openStore).noPress(),
                new GuidedTour.Step(R.string.tour_store_tiles, () -> {
                    if (!showing(activity, StoreFragment.class)) return null;
                    View tile = activity.findViewById(R.id.StoreSteam);
                    // The grid holding all four tiles: the tile's row, then the rows' column.
                    return tile != null ? (View)tile.getParent().getParent() : null;
                }, openStore)));
    }

    /**
     * What a container is, where its settings are, backups, downloading a ready-made one, and
     * the + that makes a new one.
     */
    public static void containers(MainActivity activity) {
        Runnable openContainers = () -> open(activity, R.id.main_menu_containers, ContainersFragment.class);

        GuidedTour.start(activity, Arrays.asList(
                new GuidedTour.Step(R.string.tour_containers_nav, () -> navButton(activity, R.id.main_menu_containers), null),
                new GuidedTour.Step(R.string.tour_containers_tab,
                        () -> tab(activity, ContainersFragment.class, 0), openContainers),
                new GuidedTour.Step(R.string.tour_containers_menu,
                        () -> firstRowView(activity, ContainersFragment.class, 0, R.id.BTMenu), openContainers).noPress(),
                new GuidedTour.Step(R.string.tour_containers_backups,
                        () -> tab(activity, ContainersFragment.class, 1), openContainers),
                new GuidedTour.Step(R.string.tour_container_downloads_tab,
                        () -> tab(activity, ContainersFragment.class, 2), openContainers),
                new GuidedTour.Step(R.string.tour_container_downloads_menu,
                        () -> firstRowView(activity, ContainersFragment.class, 2, R.id.BTMenu), openContainers).noPress(),
                new GuidedTour.Step(R.string.tour_containers_plus, () -> {
                    // The + only shows on the Container list tab.
                    return tab(activity, ContainersFragment.class, 0) != null
                            ? activity.findViewById(R.id.containers_menu_add) : null;
                }, openContainers)));
    }

    /** The XR tab of Settings: screen, surroundings and how the controllers act. */
    public static void xrSettings(MainActivity activity) {
        Runnable openSettings = () -> open(activity, R.id.main_menu_settings, SettingsFragment.class);

        GuidedTour.start(activity, Arrays.asList(
                new GuidedTour.Step(R.string.tour_xr_nav, () -> navButton(activity, R.id.main_menu_settings), null),
                new GuidedTour.Step(R.string.tour_xr_tab, () -> tab(activity, SettingsFragment.class, 3), openSettings),
                xrSetting(activity, R.string.tour_xr_curved_passthrough, R.id.CBEnableCurvedScreen, openSettings)
                        .alsoSpotlight(() -> activity.findViewById(R.id.CBEnablePassthrough)),
                xrSetting(activity, R.string.tour_xr_environment, R.id.LLEnvironment, openSettings),
                // Gamepad, keys and mouse sit one under another; spanning to mouse takes in keys.
                xrSetting(activity, R.string.tour_xr_controllers, R.id.CBPlayerXRGamepad, openSettings)
                        .alsoSpotlight(() -> activity.findViewById(R.id.CBPlayerXRMouse)),
                xrSetting(activity, R.string.tour_xr_left_handed, R.id.CBPlayerXRMouseLeftHanded, openSettings),
                xrSetting(activity, R.string.tour_xr_profile, R.id.LLSpinnerLayout, openSettings)));
    }

    /**
     * The XR controllers as a gamepad: which button is which, and the ways to a d-pad. There is
     * no screen of the bindings to point at, so the steps that list them stay on the gamepad
     * tick box.
     */
    public static void gamepad(MainActivity activity) {
        Runnable openSettings = () -> open(activity, R.id.main_menu_settings, SettingsFragment.class);

        GuidedTour.start(activity, Arrays.asList(
                new GuidedTour.Step(R.string.tour_xr_nav, () -> navButton(activity, R.id.main_menu_settings), null),
                new GuidedTour.Step(R.string.tour_gamepad_tab, () -> tab(activity, SettingsFragment.class, 3), openSettings),
                xrSetting(activity, R.string.tour_gamepad_enable, R.id.CBPlayerXRGamepad, openSettings),
                xrSetting(activity, R.string.tour_gamepad_buttons, R.id.CBPlayerXRGamepad, openSettings),
                xrSetting(activity, R.string.tour_gamepad_menu, R.id.CBPlayerXRGamepad, openSettings),
                xrSetting(activity, R.string.tour_gamepad_dpad, R.id.CBPlayerXRGamepad, openSettings),
                xrSetting(activity, R.string.tour_gamepad_thumbrest, R.id.CBPlayerXRThumbrestDpad, openSettings)));
    }

    /**
     * An XR controller as a mouse: what its controls do, centring a lost cursor, and the light
     * gun and relative modes. The steps with nothing of their own to point at stay on the mouse
     * tick box.
     */
    public static void mouse(MainActivity activity) {
        Runnable openSettings = () -> open(activity, R.id.main_menu_settings, SettingsFragment.class);

        GuidedTour.start(activity, Arrays.asList(
                new GuidedTour.Step(R.string.tour_xr_nav, () -> navButton(activity, R.id.main_menu_settings), null),
                new GuidedTour.Step(R.string.tour_gamepad_tab, () -> tab(activity, SettingsFragment.class, 3), openSettings),
                xrSetting(activity, R.string.tour_mouse_enable, R.id.CBPlayerXRMouse, openSettings),
                xrSetting(activity, R.string.tour_mouse_buttons, R.id.CBPlayerXRMouse, openSettings),
                xrSetting(activity, R.string.tour_mouse_centre, R.id.CBPlayerXRThumbrestMouseCentre,openSettings),
                xrSetting(activity, R.string.tour_xr_left_handed, R.id.CBPlayerXRMouseLeftHanded, openSettings),
                xrSetting(activity, R.string.tour_mouse_lightgun, R.id.CBPlayerXRMouseLightgun, openSettings),
                xrSetting(activity, R.string.tour_mouse_relative, R.id.CBRelativeMouse, openSettings)));
    }

    /**
     * PC VR: the tick boxes in a game's settings, the same options kept on a container, and
     * where the runtime's updates are. The settings open as windows of their own, so those steps
     * point at the ⋮ that leads to them.
     */
    public static void vr(MainActivity activity) {
        Runnable openGames = () -> open(activity, R.id.main_menu_shortcuts, ShortcutsFragment.class);
        Runnable openContainers = () -> open(activity, R.id.main_menu_containers, ContainersFragment.class);
        Runnable openDownloader = () -> open(activity, R.id.main_menu_contents, ContentsFragment.class);
        Supplier<View> menu = () -> firstRowView(activity, ShortcutsFragment.class, 0, R.id.LLTabShortcuts, R.id.BTMenu);

        GuidedTour.start(activity, Arrays.asList(
                new GuidedTour.Step(R.string.tour_vr_games, () -> navButton(activity, R.id.main_menu_shortcuts), null),
                new GuidedTour.Step(R.string.tour_vr_enable, menu, openGames).noPress(),
                new GuidedTour.Step(R.string.tour_vr_direct, menu, openGames).noPress(),
                new GuidedTour.Step(R.string.tour_vr_direct_off, menu, openGames).noPress(),
                new GuidedTour.Step(R.string.tour_vr_options, menu, openGames).noPress(),
                new GuidedTour.Step(R.string.tour_vr_xrapi, menu, openGames).noPress(),
                new GuidedTour.Step(R.string.tour_vr_containers, () -> navButton(activity, R.id.main_menu_containers), null),
                new GuidedTour.Step(R.string.tour_vr_container_menu,
                        () -> firstRowView(activity, ContainersFragment.class, 0, R.id.BTMenu), openContainers).noPress(),
                new GuidedTour.Step(R.string.tour_vr_downloader, () -> navButton(activity, R.id.main_menu_contents), null),
                new GuidedTour.Step(R.string.tour_vr_wine, () -> tab(activity, ContentsFragment.class, 0), openDownloader),
                new GuidedTour.Step(R.string.tour_vr_runtimes, () -> tab(activity, ContentsFragment.class, 1), openDownloader)));
    }

    /** The Downloader's + for importing, then its main tabs: Wine, Runtimes, FEXCore and Mods. */
    public static void downloader(MainActivity activity) {
        Runnable openDownloader = () -> open(activity, R.id.main_menu_contents, ContentsFragment.class);

        GuidedTour.start(activity, Arrays.asList(
                new GuidedTour.Step(R.string.tour_downloader_nav, () -> navButton(activity, R.id.main_menu_contents), null),
                new GuidedTour.Step(R.string.tour_downloader_plus, () -> showing(activity, ContentsFragment.class)
                        ? activity.findViewById(R.id.contents_menu_add) : null, openDownloader).noPress(),
                new GuidedTour.Step(R.string.tour_downloader_wine, () -> tab(activity, ContentsFragment.class, 0), openDownloader),
                new GuidedTour.Step(R.string.tour_downloader_runtimes, () -> tab(activity, ContentsFragment.class, 1), openDownloader),
                new GuidedTour.Step(R.string.tour_downloader_fexcore, () -> tab(activity, ContentsFragment.class, 6), openDownloader),
                new GuidedTour.Step(R.string.tour_mods_tab, () -> tab(activity, ContentsFragment.class, 8), openDownloader)));
    }

    /** The Downloader's Mods tab, which holds installers and mod zips, and its +. */
    public static void mods(MainActivity activity) {
        Runnable openDownloader = () -> open(activity, R.id.main_menu_contents, ContentsFragment.class);

        GuidedTour.start(activity, Arrays.asList(
                new GuidedTour.Step(R.string.tour_mods_nav, () -> navButton(activity, R.id.main_menu_contents), null),
                new GuidedTour.Step(R.string.tour_mods_tab, () -> tab(activity, ContentsFragment.class, 8), openDownloader),
                new GuidedTour.Step(R.string.tour_mods_plus, () -> tab(activity, ContentsFragment.class, 8) != null
                        ? activity.findViewById(R.id.contents_menu_add) : null, openDownloader)));
    }

    /** Dark mode and the app data backup, both on the System tab of Settings. */
    public static void backup(MainActivity activity) {
        Runnable openSettings = () -> open(activity, R.id.main_menu_settings, SettingsFragment.class);

        GuidedTour.start(activity, Arrays.asList(
                new GuidedTour.Step(R.string.tour_backup_nav, () -> navButton(activity, R.id.main_menu_settings), null),
                new GuidedTour.Step(R.string.tour_backup_tab, () -> tab(activity, SettingsFragment.class, 0), openSettings),
                systemSetting(activity, R.string.tour_backup_dark_mode, R.id.CBDarkMode, openSettings),
                systemSetting(activity, R.string.tour_backup_backup, R.id.BTBackupData, openSettings),
                systemSetting(activity, R.string.tour_backup_restore, R.id.BTRestoreData, openSettings)));
    }

    /** The Saves tab of Games: what registering a save is for, importing one, and the + that registers one. */
    public static void saves(MainActivity activity) {
        Runnable openGames = () -> open(activity, R.id.main_menu_shortcuts, ShortcutsFragment.class);

        GuidedTour.start(activity, Arrays.asList(
                new GuidedTour.Step(R.string.tour_saves_nav, () -> navButton(activity, R.id.main_menu_shortcuts), null),
                new GuidedTour.Step(R.string.tour_saves_tab, () -> tab(activity, ShortcutsFragment.class, 1), openGames),
                new GuidedTour.Step(R.string.tour_saves_import, () -> tab(activity, ShortcutsFragment.class, 1) != null
                        ? activity.findViewById(R.id.saves_menu_import) : null, openGames).noPress(),
                new GuidedTour.Step(R.string.tour_saves_add, () -> tab(activity, ShortcutsFragment.class, 1) != null
                        ? activity.findViewById(R.id.saves_menu_add) : null, openGames)));
    }

    /** The Store tab of Games, which lists the games installed outside every container. */
    public static void installedGames(MainActivity activity) {
        Runnable openGames = () -> open(activity, R.id.main_menu_shortcuts, ShortcutsFragment.class);

        GuidedTour.start(activity, Arrays.asList(
                new GuidedTour.Step(R.string.tour_installed_nav, () -> navButton(activity, R.id.main_menu_shortcuts), null),
                new GuidedTour.Step(R.string.tour_installed_tab, () -> tab(activity, ShortcutsFragment.class, 2), openGames),
                new GuidedTour.Step(R.string.tour_installed_menu,
                        () -> firstRowView(activity, ShortcutsFragment.class, 2, R.id.LLTabZDrive, R.id.BTMenu), openGames)));
    }

    /** The External Controllers tab of Settings, shown on Player 1's row. */
    public static void externalControllers(MainActivity activity) {
        Runnable openSettings = () -> open(activity, R.id.main_menu_settings, SettingsFragment.class);

        GuidedTour.start(activity, Arrays.asList(
                new GuidedTour.Step(R.string.tour_controllers_nav, () -> navButton(activity, R.id.main_menu_settings), null),
                new GuidedTour.Step(R.string.tour_controllers_tab, () -> tab(activity, SettingsFragment.class, 2), openSettings),
                controllerSetting(activity, R.string.tour_controllers_assign, R.id.BTNAssignP1, openSettings),
                controllerSetting(activity, R.string.tour_controllers_vibrate, R.id.CBVibrateP1, openSettings),
                controllerSetting(activity, R.string.tour_controllers_macros, R.id.BTNMacrosP1, openSettings),
                controllerSetting(activity, R.string.tour_controllers_sticks, R.id.BTConfigureAnalogSticks, openSettings)));
    }

    /** A control on the External Controllers tab, pointed at but left as it is. */
    private static GuidedTour.Step controllerSetting(MainActivity activity, int textResId, int viewId, Runnable openSettings) {
        return new GuidedTour.Step(textResId, () -> {
            if (tab(activity, SettingsFragment.class, 2) == null) return null;
            View tab = activity.findViewById(R.id.LLTabController);
            return tab != null ? tab.findViewById(viewId) : null;
        }, openSettings).noPress();
    }

    /** A setting on the System tab, pointed at but left as it is. */
    private static GuidedTour.Step systemSetting(MainActivity activity, int textResId, int viewId, Runnable openSettings) {
        return new GuidedTour.Step(textResId, () -> {
            if (tab(activity, SettingsFragment.class, 0) == null) return null;
            View tab = activity.findViewById(R.id.LLTabSystem);
            return tab != null ? tab.findViewById(viewId) : null;
        }, openSettings).noPress();
    }

    /** A setting on the XR tab, pointed at but left as it is. */
    private static GuidedTour.Step xrSetting(MainActivity activity, int textResId, int viewId, Runnable openSettings) {
        return new GuidedTour.Step(textResId, () -> {
            if (tab(activity, SettingsFragment.class, 3) == null) return null;
            View tab = activity.findViewById(R.id.LLTabXR);
            return tab != null ? tab.findViewById(viewId) : null;
        }, openSettings).noPress();
    }

    private static View navButton(MainActivity activity, int menuItemId) {
        return activity.findViewById(R.id.NavigationGrid).findViewWithTag(menuItemId);
    }

    private static boolean showing(MainActivity activity, Class<? extends Fragment> page) {
        return page.isInstance(activity.getSupportFragmentManager().findFragmentById(R.id.FLFragmentContainer));
    }

    /** Switches to a page from the bottom bar, unless it is already showing. */
    private static void open(MainActivity activity, int menuItemId, Class<? extends Fragment> page) {
        if (showing(activity, page)) return;
        View button = navButton(activity, menuItemId);
        if (button != null) button.performClick();
    }

    /** A page's tab, selected first if another tab is showing. Null until the page is open. */
    private static View tab(MainActivity activity, Class<? extends Fragment> page, int index) {
        // Other pages have a TabLayout of the same id; wait for this page to replace them.
        if (!showing(activity, page)) return null;
        TabLayout tabLayout = activity.findViewById(R.id.TabLayout);
        TabLayout.Tab tab = tabLayout != null ? tabLayout.getTabAt(index) : null;
        if (tab == null) return null;
        if (!tab.isSelected()) tab.select();
        return tab.view;
    }

    /** A view in the first row of a page's list, or null while the list is empty. */
    private static View firstRowView(MainActivity activity, Class<? extends Fragment> page, int tabIndex, int viewId) {
        return firstRowView(activity, page, tabIndex, 0, viewId);
    }

    /**
     * As above, for a page whose tabs each have a list of their own: the list is looked for
     * inside the view with scopeId, or anywhere on the page when that is 0.
     */
    private static View firstRowView(MainActivity activity, Class<? extends Fragment> page, int tabIndex, int scopeId, int viewId) {
        if (tab(activity, page, tabIndex) == null) return null;
        View scope = scopeId != 0 ? activity.findViewById(scopeId) : null;
        RecyclerView list = scope != null ? scope.findViewById(R.id.RecyclerView) : activity.findViewById(R.id.RecyclerView);
        if (list == null || list.getChildCount() == 0) return null;
        View row = list.getChildAt(0);
        return row instanceof ViewGroup ? row.findViewById(viewId) : null;
    }
}
