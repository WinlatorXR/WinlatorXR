package com.winlator.cmod;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.preference.PreferenceManager;

import com.google.android.material.navigation.NavigationView;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contentdialog.ControllerAssignmentDialog;
import com.winlator.cmod.contentdialog.SaveEditDialog;
import com.winlator.cmod.contentdialog.SaveSettingsDialog;
import com.winlator.cmod.contents.ApkUpdate;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.contents.Downloader;
import com.winlator.cmod.contents.VrContentUpdates;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.container.ShortcutProfile;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.inputcontrols.ControllerManager;
import com.winlator.cmod.saves.Save;
import com.winlator.cmod.saves.SaveManager;
import com.winlator.cmod.settings.SettingsFragment;
import com.winlator.cmod.store.StoreFragment;
import com.winlator.cmod.xenvironment.ImageFsInstaller;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {
    public static final @IntRange(from = 1, to = 19) byte CONTAINER_PATTERN_COMPRESSION_LEVEL = 9;
    public static final byte PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE = 1;
    public static final byte OPEN_FILE_REQUEST_CODE = 2;
    public static final byte EDIT_INPUT_CONTROLS_REQUEST_CODE = 3;
    public static final byte OPEN_DIRECTORY_REQUEST_CODE = 4;
    private static final String PREF_AUTO_DEFAULT_CONTAINER_CREATED = "auto_default_container_created";
    private static final String PREF_UPGRADE_CONTAINERS_OFFERED = "upgrade_containers_offered";
    private static final String PREF_XR_MENU_LONG_PRESS_NOTICE_SHOWN = "xr_menu_long_press_notice_shown";
    private DrawerLayout drawerLayout;
    private GridLayout gridLayout;
    public final PreloaderDialog preloaderDialog = new PreloaderDialog(this);
    private boolean editInputControls = false;
    public static int selectedProfileId;
    private Callback<Uri> openFileCallback;
    private SharedPreferences sharedPreferences;

    // Add SaveSettingsDialog and SaveEditDialog instances
    private SaveSettingsDialog saveSettingsDialog;
    private SaveEditDialog saveEditDialog;
    private SaveManager saveManager;
    private ContainerManager containerManager;

    private SaveEditDialog currentSaveEditDialog;

    private boolean isDarkMode;

    private boolean allAccessFilesDialogDismissed = false;
    private static boolean vrContentUpdatesChecked = false;
    // True while the default containers are being created, so "+" can't offer them a second time
    private boolean creatingDefaultContainers = false;
    // The update check is held back while the one-time upgrade prompts are on screen
    private boolean upgradePromptsShowing = false;
    private boolean vrContentUpdatesPending = false;
    private boolean vrUpdateCheckOnResume = false;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize the controller management system
        ControllerManager.getInstance().init(getApplicationContext());


        // Get shared preferences
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        // Load the user's preferred theme
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        isDarkMode = sharedPreferences.getBoolean("dark_mode", false);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                        getWindow(), getWindow().getDecorView());

        if (controller != null) {
            controller.hide(WindowInsetsCompat.Type.systemBars());
            controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }

        setContentView(R.layout.main_activity);

        drawerLayout = findViewById(R.id.DrawerLayout);
        NavigationView navigationView = findViewById(R.id.NavigationView);
        navigationView.setNavigationItemSelectedListener(this);

        gridLayout = findViewById(R.id.NavigationGrid);
        setNavigationGrid();

        setSupportActionBar(findViewById(R.id.Toolbar));
        ActionBar actionBar = getSupportActionBar();
        /*if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeAsUpIndicator(R.drawable.icon_action_bar_menu);
        }*/

        // Determine text color based on dark mode
        int textColor = isDarkMode ? Color.WHITE : Color.BLACK;
        setNavigationViewItemTextColor(navigationView, textColor);
        

        // Initialize SaveManager and ContainerManager
        saveManager = new SaveManager(this);
        containerManager = new ContainerManager(this);

        Intent intent = getIntent();
        editInputControls = intent.getBooleanExtra("edit_input_controls", false);
        if (editInputControls) {
            selectedProfileId = intent.getIntExtra("selected_profile_id", 0);
        } else {
            int selectedMenuItemId = intent.getIntExtra("selected_menu_item_id", 0);
            int menuItemId = selectedMenuItemId > 0 ? selectedMenuItemId : R.id.main_menu_containers;

            actionBar.setHomeAsUpIndicator(R.drawable.icon_action_bar_menu);
            onNavigationItemSelected(navigationView.getMenu().findItem(menuItemId));
            navigationView.setCheckedItem(menuItemId);

            // onCreate(), replace the two blocks with this single block
            boolean waitingForPerms = requestAppPermissions();
            if (!waitingForPerms) {
                ImageFsInstaller.installIfNeeded(this, () ->
                        checkForAndInstallAssetContents(this::onStorageAndAssetsReady));
            }

        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        ControllerAssignmentDialog.dismiss();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Apply the theme based on the preference
        if (isDarkMode) {
            setTheme(R.style.AppThemeFullscreen_Dark);
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        } else {
            setTheme(R.style.AppThemeFullscreen);
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.WHITE));
        }

        restoreTab();

        // Back from the system All Files Access page
        if (vrUpdateCheckOnResume) {
            vrUpdateCheckOnResume = false;
            checkVrContentUpdatesDelayed();
        }
    }

    private void restoreTab() {
        MenuItem output = null;
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        String tab = sharedPreferences.getString("tab_last", "");
        NavigationView navigation = findViewById(R.id.NavigationView);
        Menu menu = navigation.getMenu();
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            if (!item.isVisible()) {
                continue;
            }

            if (item.getTitle().toString().compareTo(tab) == 0) {
                output = item;
            }
        }
        if (output != null) {
            onNavigationItemSelected(output);
        }
    }

    private void setNavigationGrid() {
        Context context = getBaseContext();
        NavigationView navigation = findViewById(R.id.NavigationView);
        Menu menu = navigation.getMenu();
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            if (!item.isVisible()) {
                continue;
            }

            int padding = dpToPx(5, context);
            LinearLayout layout = new LinearLayout(context);
            layout.setPadding(padding, padding, padding, padding);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setOnClickListener(view -> {
                onNavigationItemSelected(item);
                SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
                SharedPreferences.Editor e = sharedPreferences.edit();
                e.putString("tab_last", item.getTitle().toString());
                e.commit();
            });

            layout.setOnFocusChangeListener((view, focused) -> {
                if (focused) {
                    layout.setBackgroundColor(Color.GRAY);
                } else {
                    layout.setBackgroundColor(Color.TRANSPARENT);
                }
            });

            int size = dpToPx(32, context);
            View icon = new View(context);
            item.getIcon().setTint(Color.WHITE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            icon.setLayoutParams(lp);
            icon.setBackground(item.getIcon());
            layout.addView(icon);

            int width = dpToPx(80, context);
            TextView text = new TextView(context);
            text.setLayoutParams(new ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));
            text.setText(item.getTitle());
            text.setTextColor(Color.WHITE);
            text.setGravity(Gravity.CENTER);
            text.setLines(2);
            layout.addView(text);

            gridLayout.addView(layout);
        }
    }

    public int dpToPx(float dp, Context context){
        return (int) (dp * context.getResources().getDisplayMetrics().densityDpi / DisplayMetrics.DENSITY_DEFAULT);
    }

    /**
     * Install *.wcp bundles from assets/contents/ that are not already
     * unpacked in /files/contents/<Type>/<verDir>/.
     *
     * Handles case differences and the fact that install dirs end with
     * “-<verCode>” while the .wcp filename may omit that suffix.
     */
    /**
     * Scan assets/contents/ for *.wcp bundles and install the ones that do
     * not already exist in /files/contents/<Type>/<verDir>/.
     *
     * Handles the following real-world quirks:
     *   • Folder name may be "type-verDir-<code>" (duplicated type prefix)
     *   • .wcp may have a leading 'v' in the version that the folder omits
     *   • '-' and '_' are treated as equivalent separators
     *   • Case insensitive everywhere
     *
     * The PreloaderDialog appears only when an install actually starts.
     */
    private void checkForAndInstallAssetContents(@Nullable Runnable onCompletion) {
        String key = "content_update_version";
        try {
            String last = sharedPreferences.getString(key, "");
            PackageInfo pkg = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (last.compareTo(pkg.versionName) == 0) {
                runOnUiThread(() -> {
                    if (onCompletion != null) onCompletion.run();
                });
                return;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        Executors.newSingleThreadExecutor().execute(() -> {
            PreloaderDialog spinner = new PreloaderDialog(this);
            boolean spinnerShown = false;

            try {
                /* ----------------------------------------------------------
                 * 1.  Build a map<type, set<normalisedVersionDir>>
                 *     where normalisedVersionDir strips:
                 *       - leading duplicate "type-" prefix
                 *       - converts to lower-case
                 *       - replaces '_' with '-'
                 * ---------------------------------------------------------- */
                Map<String, Set<String>> installed = new HashMap<>();
                File root = ContentsManager.getContentDir(this);

                File[] typeDirs = root.listFiles();
                if (typeDirs != null) {
                    for (File typeDir : typeDirs) {
                        if (!typeDir.isDirectory()) continue;

                        String typeKey = typeDir.getName().toLowerCase();
                        Set<String> vers = new HashSet<>();

                        File[] verDirs = typeDir.listFiles();
                        if (verDirs != null) {
                            for (File verDir : verDirs) {
                                if (!verDir.isDirectory()) continue;
                                String dirName = verDir.getName();

                                // strip duplicated "type-" prefix if present
                                String cleaned = dirName.toLowerCase()
                                        .replace('_', '-');
                                String dupPrefix = typeKey + "-";
                                if (cleaned.startsWith(dupPrefix))
                                    cleaned = cleaned.substring(dupPrefix.length());

                                vers.add(cleaned);
                            }
                        }
                        installed.put(typeKey, vers);
                    }
                }

                /* ----------------------------------------------------------
                 * 2.  Decide which asset bundles still need installing
                 * ---------------------------------------------------------- */
                String[] assetNames = getAssets().list("contents");
                if (assetNames == null) assetNames = new String[0];

                List<String> toInstall = new ArrayList<>();

                for (String asset : assetNames) {
                    if (!asset.endsWith(".wcp")) continue;

                    String base = asset.substring(0, asset.length() - 4);   // strip ".wcp"
                    int sep = Math.min(
                            base.indexOf('-') == -1 ? Integer.MAX_VALUE : base.indexOf('-'),
                            base.indexOf('_') == -1 ? Integer.MAX_VALUE : base.indexOf('_'));
                    if (sep == Integer.MAX_VALUE) continue;                 // malformed

                    String typeKey = base.substring(0, sep).toLowerCase();
                    String verRaw  = base.substring(sep + 1);

                    // normalise version string:
                    //  - lower-case
                    //  - '_' → '-'
                    //  - optional leading 'v' removed for matching
                    String verNorm = verRaw.toLowerCase().replace('_', '-');
                    String verNoV  = verNorm.startsWith("v") ? verNorm.substring(1) : verNorm;

                    Set<String> vers = installed.get(typeKey);
                    boolean exists = false;
                    if (vers != null) {
                        for (String dir : vers) {
                            // dir already lower-cased & '-' normalised
                            if (dir.equals(verNorm)      ||
                                    dir.equals(verNoV)        ||
                                    dir.startsWith(verNorm + "-") ||
                                    dir.startsWith(verNoV  + "-")) {
                                exists = true;
                                break;
                            }
                        }
                    }

                    if (!exists) toInstall.add(asset);
                }

                if (toInstall.isEmpty()) {
                    markContentInstalled(key);
                    if (onCompletion != null) runOnUiThread(onCompletion);
                    return;
                }

                /* ----------------------------------------------------------
                 * 3.  Install missing bundles
                 * ---------------------------------------------------------- */
                ContentsManager cm = new ContentsManager(this);
                File tmpDir = new File(getCacheDir(), "wcp_asset_tmp");
                if (!tmpDir.exists()) tmpDir.mkdirs();

                for (String asset : toInstall) {

                    if (!spinnerShown) {
                        spinnerShown = true;
                        runOnUiThread(() -> spinner.show(R.string.installing_contents));
                    }

                    File tmp = new File(tmpDir, asset);

                    // 3a copy asset → tmp
                    try (InputStream in  = getAssets().open("contents/" + asset);
                         OutputStream out = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    }

                    // 3b two-stage install
                    ContentsManager.OnInstallFinishedCallback cb =
                            new ContentsManager.OnInstallFinishedCallback() {
                                private boolean first = true;
                                @Override public void onSucceed(ContentProfile p) {
                                    if (first) { first = false; cm.finishInstallContent(p, this); }
                                }
                                @Override public void onFailed(ContentsManager.InstallFailedReason r,
                                                               Exception e) {
                                    Log.e("MainActivity","Install failed for "+asset+" : "+r,e);
                                }
                            };
                    cm.extraContentFile(Uri.fromFile(tmp), cb);
                    tmp.delete();
                }

            } catch (Exception e) {
                Log.e("MainActivity", "Asset-content install error", e);
            } finally {
                markContentInstalled(key);
                final boolean shown = spinnerShown;        // effectively-final snapshot
                runOnUiThread(() -> {
                    if (shown) spinner.close();
                    if (onCompletion != null) onCompletion.run();
                });
            }
        });
    }

    private void markContentInstalled(String key) {
        try {
            PackageInfo pkg = getPackageManager().getPackageInfo(getPackageName(), 0);
            SharedPreferences.Editor e = sharedPreferences.edit();
            e.putString(key, pkg.versionName);
            e.commit();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void showAllFilesAccessDialog() {
        ContentDialog dialog = new ContentDialog(this);
        dialog.setTitle("USB Storage Access");
        dialog.setMessage("In order to grant access to additional storage devices such as USB storage device, the All Files Access permission must be granted. You can leave this disabled, or you can enable it for USB storage support.");
        ((TextView) dialog.findViewById(R.id.BTConfirm)).setText("Okay");
        boolean[] openedSettings = {false};
        dialog.setOnConfirmCallback(() -> {
            openedSettings[0] = true;
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.parse("package:" + getPackageName()));
            vrUpdateCheckOnResume = true;
            startActivity(intent);
            allAccessFilesDialogDismissed = true;
        });
        // Cancel, back or tapping outside; Okay runs the check on return from the settings page instead
        dialog.setOnDismissListener(d -> {
            if (!openedSettings[0]) checkVrContentUpdatesDelayed();
        });
        dialog.show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                ImageFsInstaller.installIfNeeded(this, () ->
                        checkForAndInstallAssetContents(this::onStorageAndAssetsReady));
            } else {
                finish();
            }
        }
    }

    /**
     * Called once storage permissions are granted, the imagefs is installed, and bundled
     * asset content has been unpacked. This is the earliest point at which a container can
     * actually be created (extracting a container pattern file requires the imagefs/contents
     * to be in place).
     */
    private void onStorageAndAssetsReady() {
        // A folder someone is meant to copy profiles into has to be there before the first export
        // creates it, or there is nowhere to put the profile a friend just sent.
        ShortcutProfile.ensureProfilesDir();
        // Posted so the upgrade prompts open after onResume applies the light/dark theme; built
        // from onCreate they take the manifest's dark text on a light dialog and read blank.
        // The storage prompt waits for them rather than opening underneath.
        getWindow().getDecorView().post(() -> {
            autoCreateDefaultContainersIfNeeded();
            if (!upgradePromptsShowing) showStoragePromptOrCheckUpdates();
        });
    }

    private void showStoragePromptOrCheckUpdates() {
        if (isFinishing() || isDestroyed()) return;
        if (!allAccessFilesDialogDismissed
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && !Environment.isExternalStorageManager()) {
            // The update check waits until this dialog (and the system page it opens) is dealt with
            showAllFilesAccessDialog();
        } else {
            checkVrContentUpdatesDelayed();
        }
    }

    private void checkVrContentUpdatesDelayed() {
        getWindow().getDecorView().postDelayed(this::checkVrContentUpdates, 1500);
    }

    /** Tells the user when the Downloader lists a newer Proton WXR, OXRWXR or OpenComposite. */
    private void checkVrContentUpdates() {
        // Waits for the upgrade prompts to finish, which call this again when they do
        if (upgradePromptsShowing) {
            vrContentUpdatesPending = true;
            return;
        }
        // Once per app start, so "Not now" reminds again on the next start
        if (vrContentUpdatesChecked) return;
        vrContentUpdatesChecked = true;
        new Thread(() -> {
            String json = Downloader.downloadString(ContentsManager.REMOTE_PROFILES);
            if (json == null) return;
            ApkUpdate apkUpdate = null;
            try {
                apkUpdate = ApkUpdate.find(json, getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }
            ContentsManager contentsManager = new ContentsManager(this);
            contentsManager.setRemoteProfiles(json);
            List<ContentProfile> updates = VrContentUpdates.find(this, contentsManager);
            if (apkUpdate == null && updates.isEmpty()) return;
            final ApkUpdate apk = apkUpdate;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                // The VR prompt waits until the app update prompt is closed
                if (apk != null) {
                    ContentDialog apkDialog = new ContentDialog(this);
                    apkDialog.setTitle(R.string.apk_update_title);
                    apkDialog.setMessage(getString(R.string.apk_update_message, apk.verName));
                    ((TextView) apkDialog.findViewById(R.id.BTConfirm)).setText(R.string.apk_update_open);
                    ((TextView) apkDialog.findViewById(R.id.BTCancel)).setText(R.string.vr_content_updates_later);
                    apkDialog.setOnConfirmCallback(() -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(apk.url))));
                    apkDialog.setOnDismissListener(d -> showVrContentUpdates(updates));
                    apkDialog.show();
                } else {
                    showVrContentUpdates(updates);
                }
            });
        }).start();
    }

    private void showVrContentUpdates(List<ContentProfile> updates) {
        if (updates.isEmpty() || isFinishing() || isDestroyed()) return;
        StringBuilder names = new StringBuilder();
        for (ContentProfile profile : updates)
            names.append("\n• ").append(profile.type).append(": ").append(profile.verName).append(" (").append(profile.verCode).append(")");
        ContentDialog dialog = new ContentDialog(this);
        dialog.setTitle(R.string.vr_content_updates_title);
        dialog.setMessage(getString(R.string.vr_content_updates_message) + "\n" + names);
        ((TextView) dialog.findViewById(R.id.BTConfirm)).setText(R.string.vr_content_updates_open);
        ((TextView) dialog.findViewById(R.id.BTCancel)).setText(R.string.vr_content_updates_later);
        dialog.setOnConfirmCallback(() -> showDownloader(updates.get(0).type != ContentProfile.ContentType.CONTENT_TYPE_PROTON));
        dialog.show();
    }

    /**
     * Mirrors clicking "+" in the containers list and immediately hitting save with no
     * changes, done once for each bundled Wine/Proton version (x86_64 and arm64ec), naming
     * each container after the Wine version it was created with. Runs once ever, guarded by a
     * SharedPreferences flag, and only when the user has no containers yet.
     *
     * If a container image was bundled into the APK under assets/containers/, that "golden"
     * image is imported instead of building the blank per-version ones — it ships
     * pre-configured (wine components, DXVK, drivers, etc. already installed). The blank ones
     * are still created if that import fails.
     */
    private void autoCreateDefaultContainersIfNeeded() {
        if (containerManager == null) return;
        if (sharedPreferences.getBoolean(PREF_AUTO_DEFAULT_CONTAINER_CREATED, false)) {
            offerDefaultContainersToUpgradersIfNeeded();
            return;
        }

        sharedPreferences.edit().putBoolean(PREF_AUTO_DEFAULT_CONTAINER_CREATED, true).apply();

        containerManager.loadContainers();
        if (!containerManager.getContainers().isEmpty()) {
            offerDefaultContainersToUpgradersIfNeeded();
            return;
        }
        // A fresh install gets them now, so it never needs the upgrade offer or the menu notice
        sharedPreferences.edit()
                .putBoolean(PREF_UPGRADE_CONTAINERS_OFFERED, true)
                .putBoolean(PREF_XR_MENU_LONG_PRESS_NOTICE_SHOWN, true)
                .apply();
        createDefaultContainers();
    }

    /**
     * Someone upgrading from an older build already has containers, so the first-boot creation
     * above skips them. Once, offer to create this version's default containers alongside theirs,
     * unless they already have a container for every default version.
     */
    private void offerDefaultContainersToUpgradersIfNeeded() {
        if (sharedPreferences.getBoolean(PREF_UPGRADE_CONTAINERS_OFFERED, false)) return;
        sharedPreferences.edit().putBoolean(PREF_UPGRADE_CONTAINERS_OFFERED, true).apply();
        upgradePromptsShowing = true;

        containerManager.loadContainers();
        ContentsManager contentsManager = new ContentsManager(this);
        contentsManager.syncContents();
        boolean missingAny = false;
        for (String wineVersion : defaultContainerVersions(contentsManager, new HashMap<>())) {
            boolean found = false;
            for (Container container : containerManager.getContainers()) {
                if (wineVersion.equalsIgnoreCase(container.getWineVersion())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                missingAny = true;
                break;
            }
        }
        if (!missingAny) {
            showXrMenuLongPressNoticeIfNeeded();
            return;
        }

        ContentDialog dialog = new ContentDialog(this);
        dialog.setTitle(R.string.upgrade_containers_title);
        dialog.setMessage(R.string.upgrade_containers_message);
        ((TextView) dialog.findViewById(R.id.BTConfirm)).setText(R.string.upgrade_containers_create);
        ((TextView) dialog.findViewById(R.id.BTCancel)).setText(R.string.vr_content_updates_later);
        boolean[] created = {false};
        dialog.setOnConfirmCallback(() -> {
            created[0] = true;
            createDefaultContainers();
        });
        // "Not now", back or tapping outside all get one last chance
        dialog.setOnDismissListener(d -> {
            if (isFinishing() || isDestroyed()) return;
            if (created[0]) {
                showXrMenuLongPressNoticeIfNeeded();
                return;
            }
            ContentDialog finalDialog = new ContentDialog(this);
            finalDialog.setTitle(R.string.upgrade_containers_title);
            finalDialog.setMessage(R.string.upgrade_containers_final_message);
            ((TextView) finalDialog.findViewById(R.id.BTConfirm)).setText(R.string.upgrade_containers_create);
            ((TextView) finalDialog.findViewById(R.id.BTCancel)).setText(R.string.upgrade_containers_im_sure);
            finalDialog.setOnConfirmCallback(this::createDefaultContainers);
            finalDialog.setOnDismissListener(fd -> showXrMenuLongPressNoticeIfNeeded());
            finalDialog.show();
        });
        dialog.show();
    }

    /** Once, tells someone upgrading from an older build that the XR menu moved to a long press. */
    private void showXrMenuLongPressNoticeIfNeeded() {
        if (sharedPreferences.getBoolean(PREF_XR_MENU_LONG_PRESS_NOTICE_SHOWN, false) || isFinishing() || isDestroyed()) {
            finishUpgradePrompts();
            return;
        }
        sharedPreferences.edit().putBoolean(PREF_XR_MENU_LONG_PRESS_NOTICE_SHOWN, true).apply();
        ContentDialog dialog = new ContentDialog(this, R.layout.xr_menu_hold_guide);
        // The content frame wraps its child, so widen it for the picture to centre in
        dialog.findViewById(R.id.FrameLayout).getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
        dialog.setTitle(R.string.xr_menu_long_press_notice_title);
        dialog.setMessage(R.string.xr_menu_long_press_notice_message);
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.setOnDismissListener(d -> finishUpgradePrompts());
        dialog.show();
    }

    /** The last upgrade prompt closed, so the storage prompt and update check held back behind it run now. */
    private void finishUpgradePrompts() {
        upgradePromptsShowing = false;
        vrContentUpdatesPending = false;
        showStoragePromptOrCheckUpdates();
    }

    /** Bundled Wine versions plus bundled contents/ packages that installed, in creation order. */
    private String[] defaultContainerVersions(ContentsManager contentsManager, Map<String, String> names) {
        List<String> versionList = new ArrayList<>(Arrays.asList(getResources().getStringArray(R.array.wine_entries)));
        String[] contentEntries = getResources().getStringArray(R.array.default_container_content_entries);
        String[] contentNames = getResources().getStringArray(R.array.default_container_content_names);
        for (int i = 0; i < contentEntries.length; i++) {
            if (contentsManager.getProfileByEntryName(contentEntries[i]) == null) continue;
            versionList.add(contentEntries[i]);
            if (i < contentNames.length) names.put(contentEntries[i], contentNames[i]);
        }
        return versionList.toArray(new String[0]);
    }

    /**
     * The first-boot default containers (version → name) that no longer have a container of that
     * name, for the "+" button's offer to recreate them. Empty when a container image is bundled,
     * since first boot imported that instead.
     */
    public Map<String, String> missingDefaultContainers() {
        Map<String, String> missing = new LinkedHashMap<>();
        if (containerManager == null || !containerManager.listBundledContainerAssets().isEmpty()) return missing;
        containerManager.loadContainers();
        ContentsManager contentsManager = new ContentsManager(this);
        contentsManager.syncContents();
        Map<String, String> names = new HashMap<>();
        for (String wineVersion : defaultContainerVersions(contentsManager, names)) {
            String name = names.getOrDefault(wineVersion, wineVersion);
            boolean found = false;
            for (Container container : containerManager.getContainers()) {
                if (name.equalsIgnoreCase(container.getName())) {
                    found = true;
                    break;
                }
            }
            if (!found) missing.put(wineVersion, name);
        }
        return missing;
    }

    /** Recreates the given default containers (version → name) the same way first boot made them. */
    public void recreateDefaultContainers(Map<String, String> missing) {
        // Container ids come from the manager's counter, which must see containers made since startup
        containerManager.loadContainers();
        ContentsManager contentsManager = new ContentsManager(this);
        contentsManager.syncContents();
        createDefaultContainer(missing.keySet().toArray(new String[0]), missing, 0, contentsManager);
    }

    private void createDefaultContainers() {
        ContentsManager contentsManager = new ContentsManager(this);
        contentsManager.syncContents();
        // Bundled contents/ packages listed here get a default container too, if they installed.
        Map<String, String> names = new HashMap<>();
        String[] wineVersions = defaultContainerVersions(contentsManager, names);

        List<String> bundledContainers = containerManager.listBundledContainerAssets();
        if (!bundledContainers.isEmpty()) {
            containerManager.importContainerFromAsset(bundledContainers.get(0), (Boolean success) -> {
                if (success != null && success) {
                    Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.FLFragmentContainer);
                    if (currentFragment instanceof ContainersFragment) {
                        ((ContainersFragment) currentFragment).loadContainersList();
                    }
                } else {
                    Log.e("MainActivity", "Failed to auto-import bundled container asset; falling back to default containers");
                    createDefaultContainer(wineVersions, names, 0, contentsManager);
                }
            });
            return;
        }

        createDefaultContainer(wineVersions, names, 0, contentsManager);
    }

    /**
     * Creates the default container for wineVersions[index], then chains to the next one.
     * Creation is sequential because container ids come from ContainerManager's in-memory
     * counter, which overlapping creations would hand out twice.
     */
    private void createDefaultContainer(String[] wineVersions, Map<String, String> names, int index, ContentsManager contentsManager) {
        if (index >= wineVersions.length) {
            creatingDefaultContainers = false;
            return;
        }
        creatingDefaultContainers = true;
        final String wineVersion = wineVersions[index];

        try {
            JSONObject data = new JSONObject();
            data.put("name", names.getOrDefault(wineVersion, wineVersion));
            data.put("wineVersion", wineVersion);
            data.put("emulator", Container.DEFAULT_EMULATOR);
            data.put("dxwrapperConfig", Container.defaultDXWrapperConfig(WineInfo.fromIdentifier(this, contentsManager, wineVersion).isArm64EC()));
            if (DefaultVersion.isProton11(wineVersion)) {
                data.put("dxwrapperConfig", Container.defaultDXWrapperConfig(DefaultVersion.PROTON11_DXVK, DefaultVersion.PROTON11_VKD3D));
                data.put("graphicsDriver", DefaultVersion.PROTON11_GRAPHICS_DRIVER);
                data.put("graphicsDriverConfig", Container.defaultGraphicsDriverConfig(DefaultVersion.PROTON11_WRAPPER));
                data.put("fexcoreVersion", DefaultVersion.PROTON11_FEXCORE);
                data.put("envVars", DefaultVersion.PROTON11_ENV_VARS);
            }

            containerManager.createContainerAsync(data, contentsManager, (container) -> {
                if (container == null) Log.e("MainActivity", "Failed to auto-create default container for " + wineVersion);

                Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.FLFragmentContainer);
                if (currentFragment instanceof ContainersFragment) {
                    ((ContainersFragment) currentFragment).loadContainersList();
                }

                createDefaultContainer(wineVersions, names, index + 1, contentsManager);
            });
        } catch (JSONException e) {
            e.printStackTrace();
            creatingDefaultContainers = false;
        }
    }

    public boolean isCreatingDefaultContainers() {
        return creatingDefaultContainers;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        Log.d("WinActivity", "onActivityResult called with requestCode: " + requestCode + " and resultCode: " + resultCode);

        if (saveSettingsDialog != null && saveSettingsDialog.isShowing()) {
            Log.d("WinActivity", "Forwarding result to SaveSettingsDialog");
            saveSettingsDialog.onActivityResult(requestCode, resultCode, data);
        } else if (saveEditDialog != null && saveEditDialog.isShowing()) {
            Log.d("WinActivity", "Forwarding result to SaveEditDialog");
            saveEditDialog.onActivityResult(requestCode, resultCode, data);
        } else {
            Log.d("WinActivity", "No dialog found for request code: " + requestCode);
        }
    }

    // Method to show SaveEditDialog
    public void showSaveEditDialog(Save saveToEdit) {
        saveEditDialog = new SaveEditDialog(this, saveManager, containerManager, saveToEdit);

        // Check for dark mode and set the background accordingly
        if (isDarkMode) {
            saveEditDialog.getWindow().setBackgroundDrawableResource(R.drawable.content_dialog_background_dark);
        } else {
            saveEditDialog.getWindow().setBackgroundDrawableResource(R.drawable.content_dialog_background);
        }

        saveEditDialog.show();
    }

    public void onSaveAdded() {
        Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.FLFragmentContainer);
        if (currentFragment instanceof SavesFragment) {
            ((SavesFragment) currentFragment).refreshSavesList();
        }
    }

    @Override
    public void onBackPressed() {
        FragmentManager fragmentManager = getSupportFragmentManager();
        List<Fragment> fragments = fragmentManager.getFragments();
        for (Fragment fragment : fragments) {
            if (fragment instanceof ContainerDetailFragment && fragment.isVisible()) {
                show(new ContainersFragment(), true);  // Pass `true` to trigger the reverse animation
            }
        }
    }

    public void setOpenFileCallback(Callback<Uri> openFileCallback) {
        this.openFileCallback = openFileCallback;
    }

    private boolean requestAppPermissions() {
        boolean hasWritePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        boolean hasReadPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        boolean hasManageStoragePermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager();

        if (hasWritePermission && hasReadPermission && hasManageStoragePermission) {
            return false; // All permissions are granted
        }

        if (!hasWritePermission || !hasReadPermission) {
            String[] permissions = new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE};
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE);
        }

        return true; // Permissions are still being requested
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        if (menuItem.getItemId() == android.R.id.home) {
            // Toggle the drawer
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START);
            } else {
                drawerLayout.openDrawer(GravityCompat.START);
            }
            return true;
        } else if (menuItem.getItemId() == R.id.saves_menu_add) {
            // Check if we are editing a save
            Intent intent = getIntent();
            int editSaveId = intent.getIntExtra("edit_save_id", -1);
            Save saveToEdit = editSaveId >= 0 ? saveManager.getSaveById(editSaveId) : null;

            // Create and show SaveEditDialog or SaveSettingsDialog as appropriate
            if (saveToEdit != null) {
                // Ensure previous dialog is dismissed before showing a new one
                if (saveEditDialog != null && saveEditDialog.isShowing()) {
                    saveEditDialog.dismiss();
                }
                showSaveEditDialog(saveToEdit); // Use the correct method to show SaveEditDialog
            } else {
                saveSettingsDialog = new SaveSettingsDialog(this, saveManager, containerManager);

                // Check for dark mode and set the background accordingly
                if (isDarkMode) {
                    saveSettingsDialog.getWindow().setBackgroundDrawableResource(R.drawable.content_dialog_background_dark);
                } else {
                    saveSettingsDialog.getWindow().setBackgroundDrawableResource(R.drawable.content_dialog_background);
                }

                saveSettingsDialog.show();
            }
            return true;
        } else {
            return super.onOptionsItemSelected(menuItem);
        }
    }

    private int getTabIndex(String name) {
        NavigationView navigation = findViewById(R.id.NavigationView);
        Menu menu = navigation.getMenu();
        for (int i = 0; i < menu.size(); i++) {
            if (name.compareTo(menu.getItem(i).toString()) == 0) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        if (fragmentManager.getBackStackEntryCount() > 0) {
            fragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }

        int oldTab = getTabIndex(sharedPreferences.getString("tab_last", ""));
        int newTab = getTabIndex(item.getTitle().toString());
        boolean reverse = oldTab > newTab;

        return switch (item.getItemId()) {
            case R.id.main_menu_shortcuts -> show(new ShortcutsFragment(), reverse);
            case R.id.main_menu_containers -> show(new ContainersFragment(), reverse);
            case R.id.main_menu_contents -> show(new ContentsFragment(), reverse);
            case R.id.main_menu_store -> show(new StoreFragment(), reverse);
            case R.id.main_menu_settings -> show(new SettingsFragment(), reverse);
            case R.id.main_menu_about -> show(new WebFragment(), reverse);
            default -> true;
        };
    }


    /** Opens the Downloader on the Wine/Proton tab, or the one listing the PC VR runtimes. */
    private void showDownloader(boolean runtimesTab) {
        NavigationView navigation = findViewById(R.id.NavigationView);
        MenuItem item = navigation.getMenu().findItem(R.id.main_menu_contents);
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putString("tab_last", item.getTitle().toString())
                .commit();
        navigation.setCheckedItem(R.id.main_menu_contents);

        ContentsFragment fragment = new ContentsFragment();
        Bundle args = new Bundle();
        args.putInt(ContentsFragment.ARG_TAB, runtimesTab ? 1 : 0);
        fragment.setArguments(args);
        getSupportFragmentManager().beginTransaction().replace(R.id.FLFragmentContainer, fragment).commit();
    }

    /** Opens the Downloader on its Installers tab and goes straight to adding an installer. */
    public void showAddInstaller() {
        NavigationView navigation = findViewById(R.id.NavigationView);
        MenuItem item = navigation.getMenu().findItem(R.id.main_menu_contents);

        // Remembered as the last tab because onResume restores that one, and the file picker
        // returning resumes this activity: left as Games, it would be switched back mid-flow.
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putString("tab_last", item.getTitle().toString())
                .commit();
        navigation.setCheckedItem(R.id.main_menu_contents);

        ContentsFragment fragment = new ContentsFragment();
        Bundle args = new Bundle();
        args.putBoolean(ContentsFragment.ARG_ADD_INSTALLER, true);
        fragment.setArguments(args);
        show(fragment, false);
    }

    private boolean show(Fragment fragment, boolean reverse) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        Fragment currentFragment = fragmentManager.findFragmentById(R.id.FLFragmentContainer);

        // Do nothing if the target fragment is already displayed
        if (currentFragment != null && currentFragment.getClass().equals(fragment.getClass())) {
            drawerLayout.closeDrawer(GravityCompat.START);
            return false;
        }

        FragmentTransaction transaction = fragmentManager.beginTransaction();
        if (reverse) transaction.setCustomAnimations(R.anim.slide_in_left, R.anim.slide_out_right);
        else transaction.setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left);
        transaction.replace(R.id.FLFragmentContainer, fragment).commit();

        drawerLayout.closeDrawer(GravityCompat.START);
        return true;
    }

    private void setNavigationViewItemTextColor(NavigationView navigationView, int color) {
        for (int i = 0; i < navigationView.getMenu().size(); i++) {
            MenuItem menuItem = navigationView.getMenu().getItem(i);
            setMenuItemTextColor(menuItem, color);

            // If the menu item has sub-items, iterate through them
            if (menuItem.hasSubMenu()) {
                for (int j = 0; j < menuItem.getSubMenu().size(); j++) {
                    MenuItem subMenuItem = menuItem.getSubMenu().getItem(j);
                    setMenuItemTextColor(subMenuItem, color);
                }
            }
        }
    }

    private void setMenuItemTextColor(MenuItem menuItem, int color) {
        SpannableString spanString = new SpannableString(menuItem.getTitle());
        spanString.setSpan(new ForegroundColorSpan(color), 0, spanString.length(), 0);
        menuItem.setTitle(spanString);
    }
}
