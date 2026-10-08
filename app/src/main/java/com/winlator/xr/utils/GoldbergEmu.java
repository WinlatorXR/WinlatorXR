/*
 * Copyright (C) 2024-2026 WinlatorXR
 *
 * This file is part of WinlatorXR.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.winlator.xr.utils;

import static com.winlator.cmod.contents.ContentsManager.getInstallDir;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contents.ColdClientLoaderIni;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.contents.Downloader;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.GuestScriptRunner;
import com.winlator.cmod.core.WineRegistryEditor;
import com.winlator.cmod.container.GameUninstaller;
import com.winlator.cmod.store.SteamDatabase;
import com.winlator.cmod.store.StoreGameInstall;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

public class GoldbergEmu {

    private static final int GOLDBERG_HINT_MAX_SHOWS = 3;
    private static final int GOLDBERG_SCAN_MAX_DEPTH = 6; // UE4 keeps it in Engine/Binaries/ThirdParty/Steamworks/SteamvNNN/Win64
    private static final int GOLDBERG_SCAN_MAX_VISITED_DIRS = 4000;
    private static final String GOLDBERG_DIR_DELIM = ";;";

    /**
     * Walks a shortcut's game folder looking for every directory containing
     * steam_api.dll/steam_api64.dll — not just the shortcut's own target exe folder —
     * to cover games with a launcher/subprocess split (the exe the shortcut points at
     * spawns the real Steam-API-calling binary from a different subfolder). Bounded in
     * depth and total directories visited so an odd/huge game tree can't hang on it.
     */
    public static void scanForSteamApiDirs(File dir, int depth, List<File> found, int[] visitedCounter) {
        if (dir == null || !dir.isDirectory() || depth > GOLDBERG_SCAN_MAX_DEPTH) return;
        if (visitedCounter[0]++ > GOLDBERG_SCAN_MAX_VISITED_DIRS) return;

        File[] children = dir.listFiles();
        if (children == null) return;

        boolean hasApi = false;
        List<File> subDirs = new ArrayList<>();
        for (File f : children) {
            if (f.isDirectory()) {
                subDirs.add(f);
            } else if (f.getName().equalsIgnoreCase("steam_api.dll") || f.getName().equalsIgnoreCase("steam_api64.dll")) {
                hasApi = true;
            }
        }
        if (hasApi) found.add(dir);
        for (File sub : subDirs) scanForSteamApiDirs(sub, depth + 1, found, visitedCounter);
    }

    private static List<File> readCachedGoldbergDirs(Shortcut shortcut) {
        List<File> dirs = new ArrayList<>();
        String cached = shortcut.getExtra("goldbergDllDirs", "");
        if (!cached.isEmpty()) {
            for (String path : cached.split(java.util.regex.Pattern.quote(GOLDBERG_DIR_DELIM))) {
                File f = new File(path);
                if (f.isDirectory()) dirs.add(f);
            }
        }
        return dirs;
    }

    public static void saveGoldbergScanResult(Shortcut shortcut, List<File> dirs) {
        StringBuilder joined = new StringBuilder();
        for (File d : dirs) {
            if (joined.length() > 0) joined.append(GOLDBERG_DIR_DELIM);
            joined.append(d.getAbsolutePath());
        }
        shortcut.putExtra("goldbergScanned", "1");
        shortcut.putExtra("goldbergDllDirs", joined.toString());
        shortcut.saveData();
    }

    /**
     * Directories to apply/revert the Goldberg fix in. Reuses the one-time launch scan's
     * cached result if it's already run; otherwise runs it now (e.g. applying manually
     * from the shortcut menu before ever launching it) and caches it for next time. Falls
     * back to just the shortcut's own target-exe folder if the scan finds no steam_api.dll
     * anywhere — manual apply is never blocked by detection, only guided by it.
     */
    private static List<File> resolveGoldbergTargetDirs(Context context, Shortcut shortcut) {
        List<File> dirs;
        if (!shortcut.getExtra("goldbergScanned", "").isEmpty()) {
            dirs = readCachedGoldbergDirs(shortcut);
        } else {
            dirs = new ArrayList<>();
            File root = resolveShortcutInstallDir(context, shortcut);
            if (root != null && root.isDirectory()) scanForSteamApiDirs(root, 0, dirs, new int[1]);
            saveGoldbergScanResult(shortcut, dirs);
        }

        if (dirs.isEmpty()) {
            File root = resolveShortcutInstallDir(context, shortcut);
            if (root != null && root.isDirectory()) dirs.add(root);
        }
        return dirs;
    }

    /**
     * Resolves the real Android filesystem folder containing a shortcut's target .exe,
     * by mapping its Wine drive letter back to where that drive actually lives:
     * Z: -> the shared imagefs root (this is where Steam/GOG-store installs land, e.g.
     * imagefs/steam_games/<Game>), C: -> this shortcut's own container drive_c, and any
     * other letter -> whatever path that container's Drives config maps it to.
     */
    public static File resolveShortcutInstallDir(Context context, Shortcut shortcut) {
        String winPath = shortcut.path;
        if (winPath == null || winPath.length() < 3 || winPath.charAt(1) != ':') return null;

        char driveLetter = Character.toUpperCase(winPath.charAt(0));
        String rest = winPath.substring(2).replace("\\", "/");

        File driveRoot;
        if (driveLetter == 'Z') {
            driveRoot = new File(context.getFilesDir(), "imagefs");
        } else if (driveLetter == 'C') {
            driveRoot = new File(shortcut.container.getRootDir(), ".wine/drive_c");
        } else {
            driveRoot = null;
            for (String[] drive : shortcut.container.drivesIterator()) {
                if (drive[0].equalsIgnoreCase(String.valueOf(driveLetter))) {
                    driveRoot = new File(drive[1]);
                    break;
                }
            }
        }
        if (driveRoot == null) return null;

        return new File(driveRoot, rest).getParentFile();
    }

    /**
     * Whether the Goldberg fix is worth offering for a shortcut at all.
     *
     * Goldberg stands in for the Steam client, so it only means anything to a game built against
     * Steam. A game one of the other stores downloaded is that store's own build, sold without
     * Steam in it, and there is nothing in it for Goldberg to stand in for. Everything else --
     * a game the app's Steam screen downloaded, or anything installed or copied in by hand --
     * could be a Steam build, so it keeps the offer.
     *
     * Resolves a .lnk to the program behind it and reads what is on disk, so it belongs with
     * opening the menu rather than with drawing a row of it.
     */
    public static boolean appliesTo(Context context, Shortcut shortcut) {
        StoreGameInstall install = StoreGameInstall.find(context,
                GameUninstaller.resolveExecutable(context, shortcut.container, shortcut));
        return install == null || install.installsSteamBuilds();
    }

    /** Whether the fix is on the game, and so is there to be taken back off it. */
    public static boolean isApplied(Shortcut shortcut) {
        return !shortcut.getExtra("goldbergApplied", "").isEmpty();
    }

    /**
     * Writes the shortcut's friend IPs into custom_broadcasts.txt, so Goldberg's LAN discovery
     * reaches a friend over a VPN like Tailscale that carries no broadcasts; an empty field removes it.
     */
    public static void writeFriendIps(Context context, Shortcut shortcut) {
        if (!isApplied(shortcut)) return;
        String[] ips = shortcut.getExtra("goldbergFriendIps", "").trim().split("[\\s,;]+");
        boolean hasIps = !ips[0].isEmpty();

        List<File> dirs = new ArrayList<>();
        // The loader's steamclient reads steam_settings beside the game's exe, not beside steam_api
        boolean loader = !shortcut.getExtra("goldbergLoader", "").isEmpty();
        File exeFile = loader ? GameUninstaller.resolveExecutable(context, shortcut.container, shortcut) : null;
        if (exeFile != null && exeFile.getParentFile() != null) dirs.add(exeFile.getParentFile());
        else if (!loader) dirs.addAll(resolveGoldbergTargetDirs(context, shortcut));

        for (File dir : dirs) {
            File settingsDir = new File(dir, "steam_settings");
            File file = new File(settingsDir, "custom_broadcasts.txt");
            if (!hasIps) {
                if (file.isFile()) file.delete();
                continue;
            }
            if (!settingsDir.isDirectory() && !(loader && settingsDir.mkdirs())) continue;
            try (FileWriter fw = new FileWriter(file)) {
                for (String ip : ips) fw.write(ip + "\n");
            } catch (IOException ignored) {}
        }
    }

    /**
     * Lightweight, dismiss-by-ignoring hint shown on the first GOLDBERG_HINT_MAX_SHOWS
     * launches of a shortcut (not a blocking gate — we can't know in advance whether a
     * game actually needs the fix to start, only that it plausibly could, so it's a
     * pointer to the fix rather than a forced choice), then stays quiet for good.
     */
    public static boolean maybeShowGoldbergHint(Context context, Shortcut shortcut) {
        if (readCachedGoldbergDirs(shortcut).isEmpty()) return false;
        if (isApplied(shortcut)) return false;
        // Steam files left lying in another store's build are leftovers rather than something the
        // game runs on, and the menu the hint points at does not offer the fix for one of those.
        if (!appliesTo(context, shortcut)) return false;

        int shownCount;
        try {
            shownCount = Integer.parseInt(shortcut.getExtra("goldbergHintShownCount", "0"));
        } catch (NumberFormatException e) {
            shownCount = 0;
        }
        if (shownCount >= GOLDBERG_HINT_MAX_SHOWS) return false;

        Toast.makeText(context,
                "Steam files detected for \"" + shortcut.name + "\" — if it doesn't start, try \"Apply Goldberg Steam Fix\" from the shortcut menu.",
                Toast.LENGTH_LONG).show();

        shortcut.putExtra("goldbergHintShownCount", String.valueOf(shownCount + 1));
        shortcut.saveData();
        return true;
    }

    public static void showApplyGoldbergDialog(Activity activity, final Shortcut shortcut) {
        showApplyGoldbergDialog(activity, shortcut, null, null, null);
    }

    /**
     * The same dialog for a fix recommended for the game: only the installed packages of that kind
     * (ColdClientLoader or not; null offers every kind), with the AppID filled in when known.
     * onApplied runs on the UI thread once the fix is on the game.
     */
    public static void showApplyGoldbergDialog(Activity activity, final Shortcut shortcut, Boolean coldClientLoader, String appId, Runnable onApplied) {
        final Context context = activity;

        // A second fix on top of the first backs up the first one's files as the originals, so
        // revert would put them back rather than restore the game's own.
        if (isApplied(shortcut)) {
            Toast.makeText(context, "A Goldberg fix is already applied. Use \"Revert Goldberg Steam Fix\" first.", Toast.LENGTH_LONG).show();
            return;
        }

        ContentsManager contentsManager = new ContentsManager(context);
        contentsManager.syncContents();

        List<ContentProfile> allProfiles = contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_GOLDBERG);
        final List<ContentProfile> installed = new ArrayList<>();
        if (allProfiles != null) {
            for (ContentProfile profile : allProfiles) {
                if (!getInstallDir(context, profile).isDirectory()) continue;
                if (coldClientLoader != null && isColdClientLoader(profile) != coldClientLoader) continue;
                installed.add(profile);
            }
        }

        if (installed.isEmpty()) {
            Toast.makeText(context, coldClientLoader == null ? "No Goldberg Steam Emulator files installed. Install one from Downloader → Goldberg first."
                    : coldClientLoader ? "No Goldberg ColdClientLoader package installed. Install one from Downloader → Goldberg first."
                    : "No Goldberg steam_api package installed. Install one from Downloader → Goldberg first.", Toast.LENGTH_LONG).show();
            return;
        }

        final List<File> targetDirs = resolveGoldbergTargetDirs(activity, shortcut);
        if (targetDirs.isEmpty()) {
            Toast.makeText(context, "Couldn't locate this shortcut's game folder.", Toast.LENGTH_LONG).show();
            return;
        }

        if (installed.size() == 1) {
            promptGoldbergAppId(activity, shortcut, installed.get(0), targetDirs, appId, onApplied);
        } else {
            String[] names = new String[installed.size()];
            for (int i = 0; i < installed.size(); i++) names[i] = installed.get(i).verName;
            new AlertDialog.Builder(context)
                    .setTitle("Select Goldberg version")
                    .setItems(names, (d, which) -> promptGoldbergAppId(activity, shortcut, installed.get(which), targetDirs, appId, onApplied))
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }

    /**
     * Reads the AppID straight out of Steam's own library metadata, for games that were
     * never installed through WinlatorXR's built-in Steam downloader (so aren't in
     * SteamDatabase) — e.g. a "steamapps" folder copied wholesale from a PC onto a D:
     * drive or external storage and pointed at with a manually created shortcut. Steam
     * always keeps steamapps/appmanifest_<appid>.acf next to steamapps/common/<installDir>/,
     * and that pairing survives a raw folder copy, so it's the most reliable source here.
     */
    public static String detectAppIdFromAcf(File targetDir) {
        File dir = targetDir;
        while (dir != null) {
            File parent = dir.getParentFile();
            if (parent != null && parent.getName().equalsIgnoreCase("common")) {
                File steamapps = parent.getParentFile();
                if (steamapps != null && steamapps.getName().equalsIgnoreCase("steamapps") && steamapps.isDirectory()) {
                    String installDirName = dir.getName();
                    File[] acfFiles = steamapps.listFiles((d, name) -> name.toLowerCase().matches("appmanifest_\\d+\\.acf"));
                    if (acfFiles != null) {
                        for (File acf : acfFiles) {
                            String content = readAcfFile(acf);
                            if (content == null) continue;
                            String acfInstallDir = extractAcfField(content, "installdir");
                            if (acfInstallDir != null && acfInstallDir.equalsIgnoreCase(installDirName)) {
                                String appId = extractAcfField(content, "appid");
                                if (appId != null && !appId.isEmpty()) return appId;
                            }
                        }
                    }
                }
                break;
            }
            dir = parent;
        }
        return null;
    }

    private static String extractAcfField(String content, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s+\"([^\"]*)\"", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(content);
        return m.find() ? m.group(1) : null;
    }

    private static String readAcfFile(File f) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Prompts for a Steam search term (prefilled with defaultQuery) and runs it. Used both
     * for a manual re-search from the button, and automatically when an automatic search
     * (by shortcut/exe name) comes back empty — the exe name often doesn't match the
     * game's Steam store title closely enough for the search API to find it.
     */
    private static void promptSteamSearchQuery(Activity activity, String defaultQuery, final EditText appIdInput, final Button searchBtn) {
        final Context context = activity;

        final EditText queryInput = new EditText(context);
        queryInput.setHint("Game name");
        queryInput.setText(defaultQuery);
        queryInput.setSelection(queryInput.getText().length());
        queryInput.setSingleLine(true);
        queryInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);

        LinearLayout layout = new LinearLayout(context);
        int pad = (int) (16 * context.getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        layout.addView(queryInput);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Search Steam by name")
                .setView(layout)
                .setPositiveButton("Search", (d, which) -> {
                    String q = queryInput.getText().toString().trim();
                    if (!q.isEmpty()) searchSteamAppId(activity, q, appIdInput, searchBtn);
                })
                .setNegativeButton("Cancel", null)
                .create();

        // Enter/the keyboard's search action runs the search directly instead of inserting
        // a line break — this is a single-line search field, not a text box.
        queryInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                String q = queryInput.getText().toString().trim();
                if (!q.isEmpty()) searchSteamAppId(activity, q, appIdInput, searchBtn);
                dialog.dismiss();
                return true;
            }
            return false;
        });

        dialog.show();
    }

    /**
     * Looks up candidate Steam AppIDs by game name via Steam's public storefront search
     * (no login required — the same endpoint the store's own search box uses). This is
     * the fallback for shortcuts with no local Steam metadata at all: e.g. only the bare
     * game folder was copied over, with no steamapps/appmanifest_<id>.acf alongside it.
     */
    private static void searchSteamAppId(Activity activity, String query, EditText input, Button searchBtn) {
        final Context context = activity;
        final String originalLabel = "Search Steam";
        searchBtn.setEnabled(false);
        searchBtn.setText("Searching Steam…");

        Executors.newSingleThreadExecutor().execute(() -> {
            List<String[]> results = searchSteamStore(query);

            if (activity == null) return;
            activity.runOnUiThread(() -> {
                searchBtn.setEnabled(true);
                searchBtn.setText(originalLabel);

                if (results.isEmpty()) {
                    // Steam's search only lists games still on sale, so a delisted game never shows up
                    String message = "No Steam results for \"" + query + "\". Games no longer sold on Steam don't show up here; look up the AppID on steamdb.info and type it in.";
                    Toast toast = Toast.makeText(context, message, Toast.LENGTH_LONG);
                    toast.show();
                    // A toast can't be made longer, so show it again 2 seconds in to keep it up 2 seconds more
                    searchBtn.postDelayed(() -> { toast.cancel(); Toast.makeText(context, message, Toast.LENGTH_LONG).show(); }, 2000);
                    promptSteamSearchQuery(activity, query, input, searchBtn);
                    return;
                }
                if (results.size() == 1) {
                    input.setText(results.get(0)[0]);
                    return;
                }

                String[] labels = new String[results.size()];
                for (int i = 0; i < results.size(); i++) labels[i] = results.get(i)[1] + "  (" + results.get(i)[0] + ")";
                new AlertDialog.Builder(context)
                        .setTitle("Select the matching game")
                        .setItems(labels, (d, which) -> input.setText(results.get(which)[0]))
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        });
    }

    /** Steam storefront search by game or exe name, as {appid, name} pairs. Not for the UI thread. */
    public static List<String[]> searchSteamStore(String query) {
        List<String[]> results = new ArrayList<>(); // {appid, name}
        // Unreal exes end in -Win64-Shipping, which is never part of the title
        String base = query.replaceAll("(?i)(\\.exe)?$", "").replaceAll("(?i)(-(Win64|Win32|WinGDK))?-Shipping$|-(Win64|Win32|WinGDK)$", "").trim();
        // Exe and folder names often run the words together, which Steam's search can miss, so retry with them split
        String spaced = base.replaceAll("[_.]+", " ").replaceAll("(?<=[a-z])(?=[A-Z])|(?<=[A-Za-z])(?=[0-9])|(?<=[0-9])(?=[A-Za-z])", " ").trim();
        for (String term : spaced.equals(base) ? new String[]{base} : new String[]{base, spaced}) {
            if (!results.isEmpty()) break;
            try {
                String url = "https://store.steampowered.com/api/storesearch/?term="
                        + URLEncoder.encode(term, StandardCharsets.UTF_8.name())
                        + "&l=english&cc=US";
                String json = Downloader.downloadString(url);
                if (json != null) {
                    JSONArray items = new JSONObject(json).optJSONArray("items");
                    if (items != null) {
                        for (int i = 0; i < items.length(); i++) {
                            JSONObject item = items.getJSONObject(i);
                            results.add(new String[]{String.valueOf(item.getInt("id")), item.optString("name", "?")});
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return results;
    }

    private static void promptGoldbergAppId(Activity activity, final Shortcut shortcut, final ContentProfile profile, final List<File> targetDirs, String knownAppId, final Runnable onApplied) {
        final Context context = activity;
        final File targetDir = targetDirs.get(0); // representative folder — AppID/name detection is per-game, not per-binary

        String detectedAppId = knownAppId != null ? knownAppId : "";
        if (detectedAppId.isEmpty()) try {
            SteamDatabase db = SteamDatabase.getInstance(context.getApplicationContext());
            String targetPath = targetDir.getAbsolutePath();
            for (SteamDatabase.GameRow row : db.getInstalledGames()) {
                if (row.installDir != null && !row.installDir.isEmpty() && targetPath.startsWith(row.installDir)) {
                    detectedAppId = String.valueOf(row.appId);
                    break;
                }
            }
        } catch (Exception ignored) {}

        if (detectedAppId.isEmpty()) {
            String acfAppId = detectAppIdFromAcf(targetDir);
            if (acfAppId != null) detectedAppId = acfAppId;
        }

        final EditText input = new EditText(context);
        input.setHint("Steam AppID");
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        if (!detectedAppId.isEmpty()) input.setText(detectedAppId);

        final Button searchBtn = new Button(context);
        searchBtn.setText("Search Steam");
        // Manual clicks always let you edit the search term first — the shortcut/exe name
        // often doesn't match the game's actual Steam store title.
        searchBtn.setOnClickListener(v -> promptSteamSearchQuery(activity, shortcut.name, input, searchBtn));

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * context.getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        layout.addView(input);
        layout.addView(searchBtn);

        // No local AppID detected — kick off the Steam lookup automatically (using the
        // shortcut name as-is) so the dialog usually opens with the field already filled
        // in; if that finds nothing, searchSteamAppId() itself opens the manual-term prompt.
        if (detectedAppId.isEmpty()) searchSteamAppId(activity, shortcut.name, input, searchBtn);

        String locationsMsg = targetDirs.size() == 1
                ? "\"" + targetDir.getName() + "\""
                : targetDirs.size() + " locations within this game's folder (it has more than one copy of the Steam files)";

        String message = isColdClientLoader(profile)
                ? "This puts Goldberg's ColdClientLoader beside the game's exe, and this shortcut then starts the game through it. It is for games whose exe will not start without Steam running (SteamStub DRM), which replacing steam_api(64).dll does not fix. \"Revert Goldberg Steam Fix\" removes it.\n\nEnter this game's Steam AppID:"
                : "This replaces steam_api(64).dll in " + locationsMsg + " with Goldberg's emulated version and adds a steam_settings folder there. Originals are backed up and can be restored with \"Revert Goldberg Steam Fix\".\n\nEnter this game's Steam AppID:";

        new AlertDialog.Builder(context)
                .setTitle("Apply Goldberg Steam Fix")
                .setMessage(message)
                .setView(layout)
                .setPositiveButton("Apply", (d, which) -> {
                    String appId = input.getText().toString().trim();
                    if (appId.isEmpty()) {
                        Toast.makeText(context, "AppID is required.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    applyGoldberg(activity, shortcut, profile, targetDirs, appId, onApplied);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void applyGoldberg(Activity activity, final Shortcut shortcut, final ContentProfile profile, final List<File> targetDirs, final String appId, final Runnable onApplied) {
        final Context context = activity;
        if (isColdClientLoader(profile)) {
            Executors.newSingleThreadExecutor().execute(() -> applyColdClientLoader(activity, shortcut, profile, appId, onApplied));
            return;
        }
        Executors.newSingleThreadExecutor().execute(() -> {
            int succeeded = 0;
            for (File targetDir : targetDirs) {
                if (!applyContentToDir(context, profile, targetDir)) continue;

                File settingsDir = new File(targetDir, "steam_settings");
                settingsDir.mkdirs();
                try (FileWriter fw = new FileWriter(new File(settingsDir, "steam_appid.txt"))) {
                    fw.write(appId);
                } catch (IOException ignored) {}

                succeeded++;
            }

            if (succeeded > 0) {
                shortcut.putExtra("goldbergApplied", ContentsManager.getEntryName(profile));
                shortcut.putExtra("goldbergAppId", appId);
                shortcut.saveData();
            }

            final int finalSucceeded = succeeded;
            final int total = targetDirs.size();
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    Toast.makeText(context,
                        finalSucceeded == 0 ? "Failed to apply Goldberg fix."
                                : total == 1 ? "Goldberg Steam fix applied."
                                  : "Goldberg Steam fix applied to " + finalSucceeded + " of " + total + " locations.",
                        Toast.LENGTH_LONG).show();
                    if (finalSucceeded > 0 && onApplied != null) onApplied.run();
                });
            }
        });
    }

    /**
     * Whether a Goldberg package is the ColdClientLoader one. It goes beside the game's exe
     * rather than over its steam_api, and the game is started through it, which is what gets a
     * SteamStub-wrapped exe past its check for a running Steam client.
     */
    public static boolean isColdClientLoader(ContentProfile profile) {
        for (ContentProfile.ContentFile file : profile.fileList)
            if (file.target.equalsIgnoreCase(ColdClientLoaderIni.FILE_NAME)) return true;
        return false;
    }

    /**
     * ColdClientLoader points Steam's registry keys at itself and only puts them back when it
     * exits cleanly, so a killed session sends every later game's steam:// launch to that loader.
     * A loader launch writes them again, so leftovers are dropped before Wine starts.
     */
    public static void clearStaleLoaderRegistry(File wineDir) {
        String steamKey = "Software\\Valve\\Steam";
        String loaderDir = "";
        try (WineRegistryEditor editor = new WineRegistryEditor(new File(wineDir, "user.reg"))) {
            String steamExe = editor.getStringValue(steamKey, "SteamExe", "");
            if (steamExe.toLowerCase(Locale.ROOT).contains("steamclient_loader")) {
                loaderDir = steamExe.substring(0, Math.max(steamExe.lastIndexOf('\\') - 1, 0));
                editor.removeValue(steamKey, "SteamExe");
                for (String name : new String[]{"SteamPath", "SourceModInstallPath"})
                    if (loaderDir.equalsIgnoreCase(editor.getStringValue(steamKey, name, ""))) editor.removeValue(steamKey, name);
                for (String name : new String[]{"SteamClientDll", "SteamClientDll64", "PID"})
                    editor.removeValue(steamKey + "\\ActiveProcess", name);
            }
        }
        try (WineRegistryEditor editor = new WineRegistryEditor(new File(wineDir, "system.reg"))) {
            String commandKey = "Software\\Classes\\steam\\shell\\open\\command";
            if (editor.getStringValue(commandKey, null, "").toLowerCase(Locale.ROOT).contains("steamclient_loader"))
                editor.removeValue(commandKey, null);
            String wowKey = "Software\\Wow6432Node\\Valve\\Steam";
            if (!loaderDir.isEmpty() && loaderDir.equalsIgnoreCase(editor.getStringValue(wowKey, "InstallPath", ""))) {
                editor.removeValue(wowKey, "InstallPath");
                editor.removeValue(wowKey, "SteamPID");
            }
        }
    }

    /**
     * The shortcut keeps pointing at the game's exe, so everything that reads its path still finds
     * the game; goldbergLoader only swaps the program started at launch for the loader beside it.
     */
    private static void applyColdClientLoader(Activity activity, Shortcut shortcut, ContentProfile profile, String appId, Runnable onApplied) {
        File exeFile = GameUninstaller.resolveExecutable(activity, shortcut.container, shortcut);
        File exeDir = exeFile == null ? null : exeFile.getParentFile();
        String exePath = exeFile == null ? null : GuestScriptRunner.toWinPath(activity, shortcut.container, exeFile);
        String exeDirPath = exeDir == null ? null : GuestScriptRunner.toWinPath(activity, shortcut.container, exeDir);

        String failure = null;
        if (exePath == null || exeDirPath == null || exeFile.getName().toLowerCase(Locale.ENGLISH).endsWith(".lnk")) {
            failure = "Couldn't locate this shortcut's game folder.";
        }
        else if (!applyContentToDir(activity, profile, exeDir)) {
            failure = "Failed to apply Goldberg fix.";
        }
        else {
            try {
                // The loader only injects into the process it starts, so it has to start the exe SteamStub guards
                File gameExe = unrealShippingExe(exeFile);
                ColdClientLoaderIni.fill(new File(exeDir, ColdClientLoaderIni.FILE_NAME),
                        GuestScriptRunner.toWinPath(activity, shortcut.container, gameExe),
                        GuestScriptRunner.toWinPath(activity, shortcut.container, gameExe.getParentFile()), appId);
            } catch (IOException e) {
                failure = "Couldn't set up ColdClientLoader.ini: " + e.getMessage();
            }

            shortcut.putExtra("goldbergApplied", ContentsManager.getEntryName(profile));
            shortcut.putExtra("goldbergAppId", appId);
            // The loader has to match the game's bitness to inject into it.
            if (failure == null)
                shortcut.putExtra("goldbergLoader", isPe32(exeFile) ? "steamclient_loader_x86.exe" : "steamclient_loader_x64.exe");
            shortcut.saveData();
        }

        final String error = failure;
        activity.runOnUiThread(() -> {
            Toast.makeText(activity, error != null ? error
                : "ColdClientLoader set up. The game's shortcut now starts it through the loader.",
                Toast.LENGTH_LONG).show();
            if (error == null && onApplied != null) onApplied.run();
        });
    }

    /** An Unreal root exe is a bootstrap that starts <project>/Binaries/Win64/*-Shipping.exe; any other exe is returned as is. */
    private static File unrealShippingExe(File exeFile) {
        File[] projects = exeFile.getParentFile().listFiles(File::isDirectory);
        if (projects == null) return exeFile;
        for (File project : projects) {
            File[] exes = new File(project, "Binaries/Win64").listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith("-shipping.exe"));
            if (exes != null && exes.length == 1) return exes[0];
        }
        return exeFile;
    }

    /** Whether a Windows exe is 32-bit, from the machine field of its PE header. */
    public static boolean isPe32(File exe) {
        try (RandomAccessFile file = new RandomAccessFile(exe, "r")) {
            file.seek(0x3C);
            int peOffset = Integer.reverseBytes(file.readInt());
            file.seek(peOffset + 4);
            return Short.reverseBytes(file.readShort()) == 0x014c;
        } catch (IOException e) {
            return false;
        }
    }

    public static void showRevertGoldbergDialog(Activity activity, final Shortcut shortcut) {
        final Context context = activity;
        String entryName = shortcut.getExtra("goldbergApplied", "");
        if (entryName.isEmpty()) {
            Toast.makeText(context, "Goldberg fix isn't applied to this shortcut.", Toast.LENGTH_SHORT).show();
            return;
        }

        final List<File> targetDirs = resolveGoldbergTargetDirs(activity, shortcut);
        if (targetDirs.isEmpty()) {
            Toast.makeText(context, "Couldn't locate this shortcut's game folder.", Toast.LENGTH_LONG).show();
            return;
        }

        ContentDialog.confirm(context, "Restore the original Steam files for this shortcut?", () -> {
            Executors.newSingleThreadExecutor().execute(() -> {
                ContentsManager contentsManager = new ContentsManager(context);
                contentsManager.syncContents();
                ContentProfile profile = contentsManager.getProfileByEntryName(entryName);

                List<File> dirs = targetDirs;
                if (profile != null && isColdClientLoader(profile)) {
                    File exeFile = GameUninstaller.resolveExecutable(context, shortcut.container, shortcut);
                    dirs = new ArrayList<>();
                    if (exeFile != null && exeFile.getParentFile() != null) dirs.add(exeFile.getParentFile());
                }

                int reverted = 0;
                for (File targetDir : dirs) {
                    if (profile != null && revertGoldberg(profile, targetDir)) reverted++;

                    FileUtils.delete(new File(targetDir, "steam_settings/steam_appid.txt"));
                    File settingsDir = new File(targetDir, "steam_settings");
                    if (settingsDir.isDirectory() && FileUtils.isEmpty(settingsDir)) settingsDir.delete();
                }

                shortcut.putExtra("goldbergApplied", "");
                shortcut.putExtra("goldbergAppId", "");
                shortcut.putExtra("goldbergLoader", null);
                shortcut.saveData();

                final int finalReverted = reverted;
                if (activity != null) {
                    activity.runOnUiThread(() -> Toast.makeText(context,
                            finalReverted > 0 ? "Original Steam files restored." : "Nothing to restore.",
                            Toast.LENGTH_LONG).show());
                }
            });
        });
    }

    /**
     * Applies a Goldberg-type content profile into an arbitrary game folder (a specific
     * shortcut's install directory), rather than the active container's imagefs. Existing
     * files that Goldberg would overwrite are preserved as "&lt;name&gt;.goldberg_orig" the
     * first time, so revertGoldberg() can restore them later.
     */
    public static boolean applyContentToDir(Context context, ContentProfile profile, File targetDir) {
        if (profile.type != ContentProfile.ContentType.CONTENT_TYPE_GOLDBERG) return false;
        if (targetDir == null || !targetDir.isDirectory()) return false;

        String targetRoot = targetDir.getAbsolutePath();
        for (ContentProfile.ContentFile contentFile : profile.fileList) {
            File targetFile = new File(targetDir, contentFile.target);
            if (!isSubPath(targetRoot, targetFile.getAbsolutePath())) continue;

            File sourceFile = new File(getInstallDir(context, profile), contentFile.source);
            if (!sourceFile.isFile()) continue;

            backupOriginalFile(targetFile);
            targetFile.delete();
            FileUtils.copy(sourceFile, targetFile);
        }
        return true;
    }

    private static void backupOriginalFile(File targetFile) {
        if (!targetFile.isFile()) return;
        File backup = new File(targetFile.getParentFile(), targetFile.getName() + ".goldberg_orig");
        if (backup.exists()) return;
        FileUtils.copy(targetFile, backup);
    }

    private static boolean isSubPath(String parent, String child) {
        return Paths.get(child).toAbsolutePath().normalize().startsWith(Paths.get(parent).toAbsolutePath().normalize());
    }

    /**
     * Reverts a previous applyContentToDir() call: restores any "*.goldberg_orig" backups
     * and removes files Goldberg introduced that had no original counterpart.
     */
    private static boolean revertGoldberg(ContentProfile profile, File targetDir) {
        if (profile == null || targetDir == null || !targetDir.isDirectory()) return false;

        boolean revertedAny = false;
        for (ContentProfile.ContentFile contentFile : profile.fileList) {
            File targetFile = new File(targetDir, contentFile.target);
            File backup = new File(targetFile.getParentFile(), targetFile.getName() + ".goldberg_orig");

            if (backup.isFile()) {
                targetFile.delete();
                revertedAny = backup.renameTo(targetFile) || revertedAny;
            } else if (targetFile.exists()) {
                revertedAny = FileUtils.delete(targetFile) || revertedAny;
            }
        }
        return revertedAny;
    }
}
