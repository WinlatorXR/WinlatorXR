package com.winlator.cmod.contents;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.util.Log;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.GuestScriptRunner;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Scans a game's own install folder for the redistributable installers it ships
 * (the _CommonRedist convention) and runs the ones the user picks inside the
 * container the shortcut belongs to.
 *
 * Nothing is bundled with the app -- only installers already sitting in the user's
 * game directory are ever executed. The selected installers are written to a batch
 * file inside the container and run through a single cmd session, which keeps
 * chaining out of the shortcut command line entirely.
 */
public abstract class RedistInstaller {
    private static final String TAG = "RedistInstaller";

    /** Subdirectories of the game folder worth walking, matched case-insensitively. */
    private static final String[] SEARCH_DIRS = {
        "_commonredist", "commonredist", "_redist", "redist", "directx", "support", "extras"
    };

    private static final int MAX_DEPTH = 5;

    /**
     * Proton 9 packages already ship OpenAL Soft, so a game's own OpenAL installer would only
     * displace it. Matches both the builtin identifier (proton-9.0-x86_64) and content package
     * entry names (Proton-9.0-1-arm64ec-0). Other Wine versions are not assumed to bundle it.
     */
    private static final Pattern PROTON_9 = Pattern.compile(
        "^proton-9(\\.\\d+)*(-\\d+)*-(x86_64|arm64ec|x86)(-\\d+)?$", Pattern.CASE_INSENSITIVE);

    public static class Redist {
        public final File file;
        /** Guest-visible path of {@link #file}, e.g. {@code D:\Games\Foo\vcredist_x64.exe}. */
        public final String winPath;
        /** Command line to run, with %PATH% standing in for {@link #winPath}. */
        public final String command;
        /**
         * False for installers that are known to behave badly under Wine, so they start
         * out unticked rather than being hidden entirely.
         */
        public final boolean recommended;

        public String label;

        private Redist(String label, File file, String winPath, String command, boolean recommended) {
            this.label = label;
            this.file = file;
            this.winPath = winPath;
            this.command = command;
            this.recommended = recommended;
        }
    }

    /**
     * Finds every redistributable installer shipped alongside the shortcut's executable
     * that is reachable from inside the container. Empty if the game directory cannot be
     * resolved.
     */
    public static List<Redist> scan(Context context, Shortcut shortcut) {
        ArrayList<Redist> found = new ArrayList<>();

        File exeFile = GuestScriptRunner.toHostPath(context, shortcut.container, shortcut.path);
        if (exeFile == null) {
            Log.w(TAG, "Could not map shortcut path to a host path: " + shortcut.path);
            return found;
        }

        File gameDir = exeFile.getParentFile();
        if (gameDir == null || !gameDir.isDirectory()) {
            Log.w(TAG, "Game directory does not exist: " + exeFile);
            return found;
        }

        HashSet<String> seen = new HashSet<>();
        boolean hasOpenAL = bundlesOpenAL(shortcut.container);

        collectFrom(context, shortcut.container, gameDir, 0, found, seen, false, hasOpenAL);
        for (File child : listDirs(gameDir)) {
            if (isSearchDir(child.getName())) {
                collectFrom(context, shortcut.container, child, 1, found, seen, true, hasOpenAL);
            }
        }

        disambiguateLabels(found);
        found.sort(Comparator.comparing(redist -> redist.label.toLowerCase(Locale.ENGLISH)));
        return found;
    }

    /** Shows the pick-and-run dialog for the redistributables shipped with this shortcut. */
    public static void showDialog(Activity activity, Shortcut shortcut) {
        List<Redist> found = scan(activity, shortcut);

        if (found.isEmpty()) {
            ContentDialog.alert(activity,
                "No redistributable installers were found next to this game.\n\n"
                    + "Games that ship their own runtimes normally keep them in a "
                    + "_CommonRedist folder inside the install directory.", null);
            return;
        }

        String[] labels = new String[found.size()];
        boolean[] checked = new boolean[found.size()];
        for (int i = 0; i < found.size(); i++) {
            labels[i] = found.get(i).label;
            checked[i] = found.get(i).recommended;
        }

        new AlertDialog.Builder(activity)
            .setTitle("Install redistributables")
            .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
            .setPositiveButton("Install", (dialog, which) -> {
                ArrayList<Redist> selected = new ArrayList<>();
                for (int i = 0; i < found.size(); i++) if (checked[i]) selected.add(found.get(i));

                if (!selected.isEmpty()) run(activity, shortcut, selected);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /**
     * Each installer runs from its own folder, since some of them (DXSETUP in particular) look
     * for their payload relative to the working directory.
     */
    private static void run(Activity activity, Shortcut shortcut, List<Redist> selected) {
        ArrayList<String> body = new ArrayList<>();

        int step = 1;
        for (Redist redist : selected) {
            // A game in a folder named with anything outside ASCII cannot have its path written
            // into a batch file, so it runs through a stand-in path instead. One link per step,
            // since a script installs several of these in a row.
            String winPath = GuestScriptRunner.asciiPath(activity, shortcut.container, redist.winPath,
                "winlator-redist-target-" + step);

            body.add("echo [" + step++ + "/" + selected.size() + "] " + redist.label);
            body.add("pushd \"" + FileUtils.getDirname(winPath) + "\"");
            body.add(redist.command.replace("%PATH%", winPath));
            body.add("popd");
        }

        GuestScriptRunner.run(activity, shortcut.container, "Installing redistributables", "redist", body);
    }

    private static void collectFrom(Context context, Container container, File dir, int depth,
                                    List<Redist> found, HashSet<String> seen, boolean recurse,
                                    boolean containerHasOpenAL) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                if (recurse && depth < MAX_DEPTH) {
                    collectFrom(context, container, file, depth + 1, found, seen, true, containerHasOpenAL);
                }
                continue;
            }

            if (!seen.add(file.getAbsolutePath())) continue;

            // An installer the container cannot see through any drive mapping is not runnable.
            String winPath = GuestScriptRunner.toWinPath(context, container, file);
            if (winPath == null) continue;

            Redist redist = classify(file, winPath, containerHasOpenAL);
            if (redist != null) found.add(redist);
        }
    }

    /** True when the container's Wine version is a Proton 9 build, which already ships OpenAL Soft. */
    private static boolean bundlesOpenAL(Container container) {
        String version = container.getWineVersion();
        return version != null && PROTON_9.matcher(version.trim()).matches();
    }

    /**
     * Recognises the installers games commonly ship and pairs each with the silent switches
     * that installer actually accepts.
     */
    private static Redist classify(File file, String winPath, boolean containerHasOpenAL) {
        String name = file.getName().toLowerCase(Locale.ENGLISH);
        File parent = file.getParentFile();
        String path = parent != null ? parent.getAbsolutePath().toLowerCase(Locale.ENGLISH) : "";

        if (name.equals("vcredist_x86.exe") || name.equals("vcredist_x64.exe")
            || name.equals("vc_redist.x86.exe") || name.equals("vc_redist.x64.exe")) {
            String year = vcYear(path);
            String arch = name.contains("x64") ? "x64" : "x86";
            String label = "Visual C++ " + (year.isEmpty() ? "Redistributable" : year) + " (" + arch + ")";
            return new Redist(label, file, winPath, exe() + " " + vcFlags(year, name), true);
        }

        if (name.equals("dxsetup.exe")) {
            // Core D3D comes from DXVK or wined3d, and the container already ships builtin
            // d3dx9/d3dcompiler/xaudio2/xact. Native versions occasionally help an older
            // game, so this is offered but not ticked by default.
            return new Redist("DirectX (rarely needed, DXVK provides D3D)", file, winPath,
                exe() + " /silent", false);
        }

        if (name.equals("oalinst.exe") || (name.startsWith("openal") && name.endsWith(".exe"))) {
            // Only demoted where the container's Proton already provides OpenAL Soft; on any
            // other Wine version this is still the thing that gets a game's audio working.
            String label = containerHasOpenAL ? "OpenAL (already provided by Proton 9)" : "OpenAL";
            return new Redist(label, file, winPath, exe() + " /s", !containerHasOpenAL);
        }

        if (name.startsWith("physx") && name.endsWith(".exe")) {
            return new Redist("PhysX", file, winPath, exe() + " /quiet /norestart", true);
        }

        if (name.startsWith("physx") && name.endsWith(".msi")) {
            return new Redist("PhysX", file, winPath, msi() + " /quiet /norestart", true);
        }

        if (name.startsWith("xna") && name.endsWith(".msi")) {
            return new Redist("XNA Framework", file, winPath, msi() + " /quiet /norestart", true);
        }

        if ((name.startsWith("dotnetfx") || name.startsWith("ndp")) && name.endsWith(".exe")) {
            // The real .NET Framework rarely installs cleanly under Wine; Mono normally
            // covers these games. Offered, but not ticked by default.
            return new Redist(".NET Framework (not recommended under Wine)", file, winPath,
                exe() + " /q /norestart", false);
        }

        return null;
    }

    private static String exe() {
        return "\"%PATH%\"";
    }

    private static String msi() {
        return "msiexec /i \"%PATH%\"";
    }

    private static String vcYear(String lowerPath) {
        for (String year : new String[]{"2005", "2008", "2010", "2012", "2013", "2015", "2017", "2019", "2022"}) {
            if (lowerPath.contains(year)) return year;
        }
        return "";
    }

    private static String vcFlags(String year, String name) {
        // 2015 and later ship as Burn bundles under the vc_redist.* name.
        if (name.startsWith("vc_redist.")) return "/install /passive /norestart";

        switch (year) {
            case "2005":
            case "2008": return "/q";
            case "2010": return "/q /norestart";
            case "2012":
            case "2013": return "/install /passive /norestart";
            default: return "/q /norestart";
        }
    }

    /** Appends the containing folder to any label that would otherwise appear more than once. */
    private static void disambiguateLabels(List<Redist> found) {
        HashSet<String> duplicated = new HashSet<>();
        HashSet<String> seen = new HashSet<>();
        for (Redist redist : found) if (!seen.add(redist.label)) duplicated.add(redist.label);

        for (Redist redist : found) {
            if (duplicated.contains(redist.label)) {
                redist.label += " - " + FileUtils.getName(FileUtils.getDirname(redist.winPath));
            }
        }
    }

    private static boolean isSearchDir(String name) {
        String lower = name.toLowerCase(Locale.ENGLISH);
        for (String candidate : SEARCH_DIRS) if (lower.equals(candidate)) return true;
        return false;
    }

    private static List<File> listDirs(File dir) {
        File[] files = dir.listFiles(File::isDirectory);
        return files != null ? Arrays.asList(files) : new ArrayList<>();
    }

}
