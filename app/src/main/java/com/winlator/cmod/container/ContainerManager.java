package com.winlator.cmod.container;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.winlator.cmod.MainActivity;
import com.winlator.cmod.R;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.contents.Downloader;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.core.OnExtractFileListener;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.FilenameFilter;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;

public class ContainerManager {
    private final ArrayList<Container> containers = new ArrayList<>();
    private int maxContainerId = 0;
    private final File homeDir;
    private final Context context;

    private boolean isInitialized = false; // New flag to track initialization

    public ContainerManager(Context context) {
        this.context = context;
        File rootDir = ImageFs.find(context).getRootDir();
        homeDir = new File(rootDir, "home");
        loadContainers();
        isInitialized = true;
    }

    // Check if the ContainerManager is fully initialized
    public boolean isInitialized() {
        return isInitialized;
    }

    public ArrayList<Container> getContainers() {
        return containers;
    }

    // Load containers from the home directory
    private void loadContainers() {
        containers.clear();
        maxContainerId = 0;

        File[] files = homeDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    try {
                        if (file.getName().startsWith(ImageFs.USER + "-")) {
                            Container container = new Container(
                                    Integer.parseInt(file.getName().replace(ImageFs.USER + "-", "")), this
                            );

                            container.setRootDir(new File(homeDir, ImageFs.USER + "-" + container.id));
                            JSONObject data = new JSONObject(FileUtils.readString(container.getConfigFile()));
                            container.loadData(data);
                            containers.add(container);
                            maxContainerId = Math.max(maxContainerId, container.id);
                        }
                    } catch (Exception e) {
                        Log.e("ContainerManager", "Error loading containers", e);
                    }
                }
            }
        }
    }


    public Context getContext() {
        return context;
    }


    public void activateContainer(Container container) {
        container.setRootDir(new File(homeDir, ImageFs.USER+"-"+container.id));
        File file = new File(homeDir, ImageFs.USER);
        file.delete();
        FileUtils.symlink("./"+ImageFs.USER+"-"+container.id, file.getPath());
    }

    public void createContainerAsync(final JSONObject data, ContentsManager contentsManager, Callback<Container> callback) {
        final Handler handler = new Handler();
        Executors.newSingleThreadExecutor().execute(() -> {
            final Container container = createContainer(data, contentsManager);
            handler.post(() -> callback.call(container));
        });
    }

    public void duplicateContainerAsync(Container container, Runnable callback) {
        final Handler handler = new Handler();
        Executors.newSingleThreadExecutor().execute(() -> {
            duplicateContainer(container);
            handler.post(callback);
        });
    }

    public void removeContainerAsync(Container container, Runnable callback) {
        final Handler handler = new Handler();
        Executors.newSingleThreadExecutor().execute(() -> {
            removeContainer(container);
            handler.post(callback);
        });
    }

    private Container createContainer(JSONObject data, ContentsManager contentsManager) {
        try {
            int id = maxContainerId + 1;
            data.put("id", id);

            File containerDir = new File(homeDir, ImageFs.USER+"-"+id);
            if (!containerDir.mkdirs()) return null;

            Container container = new Container(id, this);
            container.setRootDir(containerDir);
            container.loadData(data);

            container.setWineVersion(data.getString("wineVersion"));

            if (!extractContainerPatternFile(container, container.getWineVersion(), contentsManager, containerDir, null)) {
                FileUtils.delete(containerDir);
                return null;
            }

//            // Extract the selected graphics driver files
//            String driverVersion = container.getGraphicsDriverVersion();
//            if (!extractGraphicsDriverFiles(driverVersion, containerDir, null)) {
//                FileUtils.delete(containerDir);
//                return null;
//            }

            container.saveData();
            maxContainerId++;
            containers.add(container);
            return container;
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return null;
    }


    private void duplicateContainer(Container srcContainer) {
        int id = maxContainerId + 1;

        File dstDir = new File(homeDir, ImageFs.USER + "-" + id);
        if (!dstDir.mkdirs()) return;

        // Use the refactored copy method that doesn't require a Context for File operations
        if (!FileUtils.copy(srcContainer.getRootDir(), dstDir, file -> FileUtils.chmod(file, 0771))) {
            FileUtils.delete(dstDir);
            return;
        }

        Container dstContainer = new Container(id, this);
        dstContainer.setRootDir(dstDir);
        dstContainer.setName(srcContainer.getName() + " (" + context.getString(R.string._copy) + ")");
        dstContainer.setScreenSize(srcContainer.getScreenSize());
        dstContainer.setEnvVars(srcContainer.getEnvVars());
        dstContainer.setCPUList(srcContainer.getCPUList());
        dstContainer.setCPUListWoW64(srcContainer.getCPUListWoW64());
        dstContainer.setGraphicsDriver(srcContainer.getGraphicsDriver());
        dstContainer.setDXWrapper(srcContainer.getDXWrapper());
        dstContainer.setDXWrapperConfig(srcContainer.getDXWrapperConfig());
        dstContainer.setAudioDriver(srcContainer.getAudioDriver());
        dstContainer.setWinComponents(srcContainer.getWinComponents());
        dstContainer.setDrives(srcContainer.getDrives());
        dstContainer.setShowFPS(srcContainer.isShowFPS());
        dstContainer.setWoW64Mode(srcContainer.isWoW64Mode());
        dstContainer.setStartupSelection(srcContainer.getStartupSelection());
        dstContainer.setBox64Preset(srcContainer.getBox64Preset());
        dstContainer.setDesktopTheme(srcContainer.getDesktopTheme());
        dstContainer.setRcfileId(srcContainer.getRCFileId());
        dstContainer.setWineVersion(srcContainer.getWineVersion());
        dstContainer.saveData();

        maxContainerId++;
        containers.add(dstContainer);
    }


    private void removeContainer(Container container) {
        if (FileUtils.delete(container.getRootDir())) containers.remove(container);
    }

    public ArrayList<Shortcut> loadShortcuts() {
        ArrayList<Shortcut> shortcuts = new ArrayList<>();

        for (Container container : containers) {
            File desktopDir = container.getDesktopDir();
            File[] list = (desktopDir.exists() ? desktopDir.listFiles() : null);
            if (list == null) continue;

            for (File file : list) {
                if (!file.getName().toLowerCase().endsWith(".desktop")) continue;

                try {
                    shortcuts.add(new Shortcut(container, file));
                } catch (Exception ex) {
                    Log.w("ContainerManager",
                            "Skipping malformed shortcut: " + file.getAbsolutePath(), ex);
                    // TODO: move the bad file to a “quarantine” folder or delete it
                }
            }
        }

        shortcuts.sort(Comparator.comparing(a -> a.name, String::compareToIgnoreCase));
        return shortcuts;
    }


    public int getNextContainerId() {
        return maxContainerId + 1;
    }

    public Container getContainerById(int id) {
        for (Container container : containers) if (container.id == id) return container;
        return null;
    }

    public Container getContainerByName(String name) {
        if (name == null) return null;
        for (Container container : containers) if (name.equals(container.getName())) return container;
        return null;
    }

    private void extractCommonDlls(WineInfo wineInfo, String srcName, String dstName, File containerDir, OnExtractFileListener onExtractFileListener) throws JSONException {
        File srcDir = new File(wineInfo.path + "/lib/wine/" + srcName);

        File[] srcfiles = srcDir.listFiles(file -> file.isFile());

        for (File file : srcfiles) {
            String dllName = file.getName();
            if (dllName.equals("iexplore.exe") && wineInfo.isArm64EC() && srcName.equals("aarch64-windows"))
                file = new File(wineInfo.path + "/lib/wine/" + "i386-windows/iexplore.exe");
            File dstFile = new File(containerDir, ".wine/drive_c/windows/" + dstName + "/" + dllName);
            if (dstFile.exists()) continue;
            if (onExtractFileListener != null ) {
                dstFile = onExtractFileListener.onExtractFile(dstFile, 0);
                if (dstFile == null) continue;
            }
            FileUtils.copy(file, dstFile);
        }
    }

    public boolean extractContainerPatternFile(Container container, String wineVersion, ContentsManager contentsManager, File containerDir, OnExtractFileListener onExtractFileListener) {
        WineInfo wineInfo = WineInfo.fromIdentifier(context, contentsManager, wineVersion);
        String containerPattern = wineVersion + "_container_pattern.tzst";
        boolean result = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, containerPattern, containerDir, onExtractFileListener);

        if (!result) {
            File containerPatternFile = new File(wineInfo.path + "/prefixPack.txz");
            result = TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, containerPatternFile, containerDir);
        }

        if (result) {
            try {
                if (wineInfo.isArm64EC())
                    extractCommonDlls(wineInfo, "aarch64-windows", "system32", containerDir, onExtractFileListener); // arm64ec only
                else
                    extractCommonDlls(wineInfo, "x86_64-windows", "system32", containerDir, onExtractFileListener);

                extractCommonDlls(wineInfo, "i386-windows", "syswow64", containerDir, onExtractFileListener);
            }
            catch (JSONException e) {
                return false;
            }
        }
   
        return result;
    }

    public Container getContainerForShortcut(Shortcut shortcut) {
        // Search for the container by its ID
        for (Container container : containers) {
            if (container.id == shortcut.getContainerId()) {
                return container;
            }
        }
        return null;  // Return null if no matching container is found
    }

    public void importContainer(File importDir, Runnable callback) {
        Executors.newSingleThreadExecutor().execute(() -> {
            doImportContainerDir(importDir, false);
            if (callback != null) callback.run();
        });
    }

    /**
     * Synchronously imports a container from an exported container directory, preserving its saved
     * configuration. Returns true on success. Runs on the calling thread (callers handle threading).
     *
     * @param retuneToDevice when true, rewrites the graphics-driver and DXVK version fields to this
     *                       headset's defaults (used for shared "recommended" images captured on a
     *                       different device). Normal user imports pass false to keep settings as-is.
     */
    private boolean doImportContainerDir(File importDir, boolean retuneToDevice) {
        try {
            if (importDir == null || !importDir.exists() || !importDir.isDirectory()) {
                Log.e("ContainerManager", "Invalid container directory for import: " + (importDir != null ? importDir.getPath() : "null"));
                return false;
            }

            // Get the next container ID and target directory.
            int newContainerId = getNextContainerId();
            String newContainerName = ImageFs.USER + "-" + newContainerId;
            File newContainerDir = new File(homeDir, newContainerName);

            if (newContainerDir.exists()) {
                Log.e("ContainerManager", "Container directory already exists: " + newContainerDir.getPath());
                return false;
            }

            if (!newContainerDir.mkdirs()) {
                Log.e("ContainerManager", "Failed to create directory: " + newContainerDir.getPath());
                return false;
            }

            // Copy the files from the import directory to the new container directory.
            // Note: this drops symlinks (FileUtils.copy skips them); fine for plain folder backups,
            // since per-launch setup rebuilds dosdevices. Archive imports use move() to keep symlinks.
            if (!FileUtils.copy(importDir, newContainerDir, file -> FileUtils.chmod(file, 0771))) {
                FileUtils.delete(newContainerDir);
                Log.e("ContainerManager", "Failed to copy container files to: " + newContainerDir.getPath());
                return false;
            }

            boolean ok = registerImportedContainer(newContainerId, newContainerDir, importDir.getName(), retuneToDevice);
            if (ok) Log.d("ContainerManager", "Container imported successfully to: " + newContainerDir.getPath());
            return ok;
        } catch (Exception e) {
            Log.e("ContainerManager", "Failed to import container from: " + (importDir != null ? importDir.getPath() : "null"), e);
            return false;
        }
    }

    /**
     * Registers an already-placed container directory (home/xuser-&lt;newId&gt;) as a Container,
     * restoring its saved configuration and optionally retuning device-specific graphics.
     */
    private boolean registerImportedContainer(int newId, File containerDir, String fallbackName, boolean retuneToDevice) {
        Container newContainer = new Container(newId, this);
        newContainer.setRootDir(containerDir);

        // The import chmods every file to 0771, which makes the captured wineserver runtime dir
        // group/other-accessible. wineserver then refuses to start ("must not be accessible by other
        // users") and the container hangs. It's a throwaway runtime dir, so delete it — wineserver
        // recreates it with the correct 0700 perms on launch.
        FileUtils.delete(new File(containerDir, ".wine/.wineserver"));

        // Preserve the imported container's configuration (name, wine version, drivers, env vars,
        // etc.) from its .container file. The container id is final and stays fixed at newId, so
        // loadData only restores the settings.
        boolean configRestored = false;
        File configFile = newContainer.getConfigFile();
        // DEBUG - TO BE REMOVED
        Log.d("ImportDebug", "register id=" + newId + " dir=" + containerDir.getPath()
                + " .container exists=" + configFile.isFile()
                + " dirListing=" + java.util.Arrays.toString(containerDir.list()));
        if (configFile.isFile()) {
            try {
                String configStr = FileUtils.readString(configFile);
                // DEBUG - TO BE REMOVED
                Log.d("ImportDebug", "config content=" + configStr);
                if (configStr != null && !configStr.isEmpty()) {
                    newContainer.loadData(new JSONObject(configStr));
                    configRestored = true;
                }
            } catch (JSONException e) {
                Log.w("ContainerManager", "Imported container has an invalid .container config; using defaults", e);
            }
        }
        if (!configRestored) newContainer.setName(fallbackName);

        // Shared recommended images are captured on one headset; align device-specific graphics
        // settings to this device so a single image works everywhere.
        if (retuneToDevice) retuneGraphicsForDevice(newContainer);

        // An imported prefix was set up on another install/device. Clear the "already applied"
        // environment markers so the first launch re-runs first-boot setup (graphics wrapper +
        // extra libs, DXVK, ddraw, wincomponents) for THIS device — exactly like a freshly created
        // container. This is additive: it does not touch the installed Windows-side content (the
        // registry, Program Files, ajay prefix, etc.), only the Winlator-managed environment.
        newContainer.putExtra("appVersion", null);            // -> firstTimeBoot == true on next launch
        newContainer.putExtra("dxwrapper", null);             // -> re-extract DXVK DLLs
        newContainer.putExtra("ddrawrapper", null);           // -> re-extract ddraw wrapper
        newContainer.putExtra("lastInstalledMainWrapper", null); // -> re-extract graphics wrapper
        newContainer.putExtra("wincomponents", null);         // -> reinstall win components

        newContainer.saveData();
        // DEBUG - TO BE REMOVED
        Log.d("ImportDebug", "registered configRestored=" + configRestored
                + " name=" + newContainer.getName() + " wineVersion=" + newContainer.getWineVersion()
                + " (cleared env markers to force first-boot setup)");
        containers.add(newContainer);
        maxContainerId++;
        return true;
    }

    /**
     * Imports a container from a single compressed archive (a .tzst/zstd or .txz/xz tarball of an
     * exported container directory). The archive is extracted to a temporary directory and then
     * imported like a regular directory, preserving the container's saved configuration.
     *
     * Intended for prebuilt "recommended" container images that are downloaded and provisioned in
     * one tap, but works for any archive produced by exporting + compressing a container directory.
     */
    public void importContainerFromArchive(File archiveFile, Runnable callback) {
        Executors.newSingleThreadExecutor().execute(() -> {
            doImportContainerArchive(archiveFile, false);
            if (callback != null) runOnUiThread(callback);
        });
    }

    /** Archive import that reports success/failure on the UI thread. */
    public void importContainerFromArchive(File archiveFile, Callback<Boolean> callback) {
        Executors.newSingleThreadExecutor().execute(() -> {
            boolean ok = doImportContainerArchive(archiveFile, false);
            if (callback != null) runOnUiThread(() -> callback.call(ok));
        });
    }

    /**
     * Synchronously extracts a container archive to a scratch directory and imports it. Returns true
     * on success. Runs on the calling thread (callers handle threading). See
     * {@link #doImportContainerDir(File, boolean)} for {@code retuneToDevice}.
     */
    private boolean doImportContainerArchive(File archiveFile, boolean retuneToDevice) {
        File extractDir = null;
        try {
            if (archiveFile == null || !archiveFile.isFile()) {
                Log.e("ContainerManager", "Invalid container archive: " + (archiveFile != null ? archiveFile.getPath() : "null"));
                return false;
            }

            // Extract to a scratch directory in the cache. Try zstd first, then xz.
            extractDir = new File(context.getCacheDir(), "container_import_" + System.currentTimeMillis());
            if (!extractDir.mkdirs()) {
                Log.e("ContainerManager", "Failed to create temp extract dir: " + extractDir.getPath());
                return false;
            }

            boolean extracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, archiveFile, extractDir);
            if (!extracted) extracted = TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, archiveFile, extractDir);
            if (!extracted) {
                Log.e("ContainerManager", "Failed to extract container archive: " + archiveFile.getPath());
                return false;
            }

            // The archive may wrap the container in a single top-level folder (e.g. xuser-N/).
            File sourceDir = resolveImportedContainerDir(extractDir);
            if (!new File(sourceDir, ".container").isFile())
                Log.w("ContainerManager", "Imported archive has no .container at " + sourceDir.getPath() + " — image may be incomplete");

            int newContainerId = getNextContainerId();
            File newContainerDir = new File(homeDir, ImageFs.USER + "-" + newContainerId);
            if (newContainerDir.exists()) {
                Log.e("ContainerManager", "Container directory already exists: " + newContainerDir.getPath());
                return false;
            }

            // Move the extracted tree into place. A rename preserves symlinks (FileUtils.copy drops
            // them) and avoids a second full copy of the prefix. Cache and files share /data, so the
            // rename stays on one filesystem; fall back to copy only if that ever fails.
            if (!sourceDir.renameTo(newContainerDir)) {
                Log.w("ContainerManager", "Move failed; falling back to copy for: " + newContainerDir.getPath());
                if (!newContainerDir.mkdirs()
                        || !FileUtils.copy(sourceDir, newContainerDir, file -> FileUtils.chmod(file, 0771))) {
                    FileUtils.delete(newContainerDir);
                    Log.e("ContainerManager", "Failed to place imported container at: " + newContainerDir.getPath());
                    return false;
                }
            }

            boolean ok = registerImportedContainer(newContainerId, newContainerDir, sourceDir.getName(), retuneToDevice);
            if (ok) Log.d("ContainerManager", "Container imported from archive to: " + newContainerDir.getPath());
            return ok;
        } catch (Exception e) {
            Log.e("ContainerManager", "Failed to import container from archive: " + (archiveFile != null ? archiveFile.getPath() : "null"), e);
            return false;
        } finally {
            if (extractDir != null) FileUtils.delete(extractDir);
        }
    }

    /**
     * Resolves the actual container directory inside a freshly extracted archive. Handles archives
     * that wrap everything in a single top-level folder as well as archives extracted flat.
     */
    private File resolveImportedContainerDir(File extractDir) {
        // The extraction root is itself a container.
        if (new File(extractDir, ".container").isFile()) return extractDir;

        // Otherwise look for a single wrapping directory (the common case for compressed exports).
        File[] entries = extractDir.listFiles();
        if (entries != null) {
            File onlyDir = null;
            int dirCount = 0;
            for (File entry : entries) {
                if (entry.isDirectory()) {
                    onlyDir = entry;
                    dirCount++;
                }
            }
            if (dirCount == 1 && onlyDir != null) return onlyDir;
        }

        // Fall back to the extraction root.
        return extractDir;
    }

    /**
     * Rewrites the device-specific graphics fields of a container to the current headset's defaults.
     * Only the graphics-driver wrapper version and the DXVK version differ per device; the actual
     * driver is selected at launch from this config, and DXVK DLLs are re-extracted when the version
     * changes, so a shared image just needs these version strings corrected for the target device.
     */
    private void retuneGraphicsForDevice(Container container) {
        container.setGraphicsDriverConfig(setSemicolonVersion(container.getGraphicsDriverConfig(), DefaultVersion.WRAPPER));

        if ("dxvk".equals(container.getDXWrapper())) {
            String dxConfig = container.getDXWrapperConfig();
            if (dxConfig == null || dxConfig.isEmpty()) dxConfig = Container.DEFAULT_DXWRAPPERCONFIG;
            KeyValueSet kv = new KeyValueSet(dxConfig);
            kv.put("version", DefaultVersion.DXVK);
            container.setDXWrapperConfig(kv.toString());
        }
    }

    /** Replaces (or inserts) the {@code version=...} entry in a ';'-separated config string. */
    private static String setSemicolonVersion(String config, String version) {
        if (config == null || config.isEmpty()) return "version=" + version;
        String[] parts = config.split(";");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].startsWith("version=")) {
                parts[i] = "version=" + version;
                return String.join(";", parts);
            }
        }
        return "version=" + version + ";" + config;
    }

    /** Reports progress and the final result of a recommended-setup run. Called on the UI thread. */
    public interface RecommendedSetupCallback {
        /** Invoked before each image is downloaded/installed, with its display name. */
        void onProgress(String imageName);

        /**
         * @param imported      number of recommended containers newly added
         * @param skipped       number already present (matched by name) and left untouched
         * @param failed        number that failed to download/extract/import
         * @param manifestError true if the manifest itself couldn't be fetched/parsed (nothing ran)
         */
        void onFinished(int imported, int skipped, int failed, boolean manifestError);
    }

    /**
     * Downloads and provisions the recommended prebuilt containers from the remote manifest, one tap.
     * Already-present containers (matched by name) are skipped, so this is safe to run repeatedly.
     * Work happens on a background thread; the callback is delivered on the UI thread.
     */
    public void setupRecommendedContainersAsync(RecommendedSetupCallback callback) {
        Executors.newSingleThreadExecutor().execute(() -> {
            List<RecommendedContainers.Image> images = RecommendedContainers.fetchManifest();
            if (images == null) {
                if (callback != null) runOnUiThread(() -> callback.onFinished(0, 0, 0, true));
                return;
            }

            String deviceKey = RecommendedContainers.deviceKey();
            int imported = 0, skipped = 0, failed = 0;
            for (RecommendedContainers.Image image : images) {
                // Only provision images meant for this headset (device-less images apply to all).
                if (!image.appliesToDevice(deviceKey)) continue;
                if (getContainerByName(image.name) != null) {
                    skipped++;
                    continue;
                }
                if (callback != null) runOnUiThread(() -> callback.onProgress(image.name));

                File archive = new File(context.getCacheDir(), "recommended_" + System.currentTimeMillis());
                try {
                    if (!Downloader.downloadFile(image.url, archive)) {
                        Log.e("ContainerManager", "Failed to download recommended image: " + image.url);
                        failed++;
                        continue;
                    }
                    if (!image.matchesDownload(archive)) {
                        Log.e("ContainerManager", "Downloaded recommended image failed verification: " + image.name);
                        failed++;
                        continue;
                    }
                    // Recommended images are device-agnostic; retune graphics to this headset.
                    if (doImportContainerArchive(archive, true)) imported++;
                    else failed++;
                } finally {
                    FileUtils.delete(archive);
                }
            }

            final int fi = imported, fs = skipped, ff = failed;
            if (callback != null) runOnUiThread(() -> callback.onFinished(fi, fs, ff, false));
        });
    }



    public void exportContainer(Container container, Runnable callback) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                // Create the export directory path
                File exportDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Winlator/Backups/Containers");

                if (!exportDir.exists() && !exportDir.mkdirs()) {
                    Log.e("ContainerManager", "Failed to create export directory: " + exportDir.getPath());
                    runOnUiThread(() -> callback.run()); // Close the preloader dialog
                    return;
                }

                File containerDir = container.getRootDir();
                File destinationDir = new File(exportDir, containerDir.getName());

                if (destinationDir.exists()) {
                    Log.e("ContainerManager", "Export directory already exists: " + destinationDir.getPath());
                    runOnUiThread(() -> callback.run()); // Close the preloader dialog
                    return;
                }

                if (!destinationDir.mkdirs()) {
                    Log.e("ContainerManager", "Failed to create directory: " + destinationDir.getPath());
                    runOnUiThread(() -> callback.run()); // Close the preloader dialog
                    return;
                }

                if (!FileUtils.copy(containerDir, destinationDir, file -> FileUtils.chmod(file, 0771))) {
                    Log.e("ContainerManager", "Failed to export some container files to: " + destinationDir.getPath());
                    FileUtils.delete(destinationDir); // Optional: Delete partially copied directory
                }

                Log.d("ContainerManager", "Container exported successfully to: " + destinationDir.getPath());
            } catch (Exception e) {
                Log.e("ContainerManager", "Failed to export container: " + container.getName(), e);
            } finally {
                runOnUiThread(callback); // Ensure the callback runs and preloader dialog closes
            }
        });
    }

    /**
     * Exports a container as a single compressed .tzst image (the same format consumed by
     * {@link #importContainerFromArchive}). This is the artifact used to produce the prebuilt
     * "golden" images: set a container up, export it here, then host the resulting file.
     *
     * The image is written to Downloads/Winlator/Backups/Containers/&lt;name&gt;.tzst. The callback is
     * delivered on the UI thread with the produced file, or null on failure.
     */
    public void exportContainerAsImage(Container container, Callback<File> callback) {
        Executors.newSingleThreadExecutor().execute(() -> {
            File result = null;
            try {
                File exportDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Winlator/Backups/Containers");
                if (!exportDir.exists() && !exportDir.mkdirs()) {
                    Log.e("ContainerManager", "Failed to create export directory: " + exportDir.getPath());
                } else {
                    String safeName = container.getName().replaceAll("[^a-zA-Z0-9._-]", "_");
                    File imageFile = new File(exportDir, safeName + ".tzst");
                    if (imageFile.exists()) FileUtils.delete(imageFile);

                    // Compresses the whole container directory (config + prefix), so the .container
                    // settings travel with the image and are restored on import.
                    TarCompressorUtils.compress(TarCompressorUtils.Type.ZSTD, container.getRootDir(), imageFile,
                            MainActivity.CONTAINER_PATTERN_COMPRESSION_LEVEL);

                    if (imageFile.isFile() && imageFile.length() > 0) {
                        result = imageFile;
                        Log.d("ContainerManager", "Container image exported to: " + imageFile.getPath());
                    } else {
                        Log.e("ContainerManager", "Container image export produced no file: " + imageFile.getPath());
                    }
                }
            } catch (Exception e) {
                Log.e("ContainerManager", "Failed to export container image: " + container.getName(), e);
            }
            final File finalResult = result;
            runOnUiThread(() -> callback.call(finalResult));
        });
    }

    // Utility method to run on UI thread
    private void runOnUiThread(Runnable action) {
        new Handler(Looper.getMainLooper()).post(action);
    }



}
