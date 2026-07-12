package com.winlator.cmod.store;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.core.FileUtils;
import com.winlator.xr.XrActivity;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Launch bridge for Ludashi-plus store integrations.
 *
 * Uses reflection to access ContainerManager so this class compiles against
 * android.jar alone — no Ludashi stubs needed.
 *
 * Call addToLauncher() when a store game is ready to add. It shows a dialog
 * listing all Wine containers, then writes a .desktop shortcut file into the
 * selected container's desktop directory. The shortcut then appears in
 * Ludashi's Shortcuts list where the user can launch and configure it.
 */
public final class LudashiLaunchBridge {

    private static final String TAG = "LudashiLaunchBridge";

    private LudashiLaunchBridge() {}

    /**
     * Show a container picker dialog, then write a .desktop shortcut file
     * into the chosen container's Wine desktop directory.
     */
    public static void addToLauncher(Activity activity, String gameName, String exePath) {
        new Thread(() -> {
            Handler h = new Handler(Looper.getMainLooper());
            try {
                ContainerManager manager = new ContainerManager(activity);
                List<Container> containers = manager.getContainers();

                if (containers == null || containers.isEmpty()) {
                    h.post(() -> Toast.makeText(activity,
                            "No Wine container found. Create one first.",
                            Toast.LENGTH_LONG).show());
                    return;
                }

                // Build display names for the picker
                String[] names = new String[containers.size()];
                for (int i = 0; i < containers.size(); i++) {
                    Object c = containers.get(i);
                    try {
                        Method getName = c.getClass().getMethod("getName");
                        names[i] = (String) getName.invoke(c);
                    } catch (Exception ignored) {}
                    if (names[i] == null || names[i].isEmpty()) names[i] = "Container " + i;
                }

                h.post(() -> new AlertDialog.Builder(activity)
                        .setTitle("Select container for \"" + gameName + "\"")
                        .setItems(names, (dialog, which) ->
                                writeShortcut(activity, containers.get(which), gameName, exePath, h))
                        .setNegativeButton("Cancel", null)
                        .show());

            } catch (Exception e) {
                h.post(() -> Toast.makeText(activity,
                        "Error loading containers: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    public static void deleteShortcut(Context context, String name) {
        name = safeString(name);
        for (Shortcut shortcut : getShortcutsList(context)) {
            if (name.compareTo(safeString(shortcut.name)) == 0) {
                safeDelete(shortcut.file);
                deletePairedLnkForShortcut(shortcut);
            }
        }
    }

    private static void deletePairedLnkForShortcut(Shortcut shortcut) {
        if (shortcut == null || shortcut.file == null) return;
        File dir = shortcut.file.getParentFile();
        if (dir == null) return;

        String base = FileUtils.getBasename(shortcut.file.getName()); // strips extension
        File lnk = new File(dir, base + ".lnk");
        boolean deleted = safeDelete(lnk);
        if (deleted) {
            Log.d(TAG, "Paired .lnk removed: " + lnk.getAbsolutePath());
        } else if (lnk.exists()) {
            Log.w(TAG, "Paired .lnk exists but could not be removed: " + lnk.getAbsolutePath());
        } else {
            Log.d(TAG, "No paired .lnk found for " + base);
        }
    }

    private static ArrayList<Shortcut> getShortcutsList(Context context) {

        ArrayList<Shortcut> shortcuts = new ArrayList<>();

        // ContainerManager can still throw (e.g. I/O permission issues).
        // Keep the whole call in one try/catch so the UI never dies.
        try {
            ContainerManager manager = new ContainerManager(context);
            for (Container c : manager.getContainers()) {
                for (File f : c.getDesktopDir().listFiles((dir, n) -> n.endsWith(".desktop"))) {
                    try {
                        Shortcut s = new Shortcut(c, f);   // may throw
                        // very cheap logical sanity check
                        if (s.name == null || s.name.trim().isEmpty()) {
                            throw new IllegalStateException("empty name");
                        }
                        shortcuts.add(s);

                    } catch (Throwable t) {
                        Log.e(TAG, "Bad shortcut: " + f.getAbsolutePath(), t);
                    }
                }
            }
        } catch (Throwable fatal) {
            Log.e(TAG, "Fatal error while scanning shortcuts!", fatal);
        }
        return shortcuts;
    }

    private static void runFromShortcut(Activity activity, Shortcut shortcut) {
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

    private static boolean safeDelete(@Nullable File f) {
        try {
            return f != null && f.exists() && f.delete();
        } catch (Exception e) {
            Log.e(TAG, "Delete failed for: " + (f != null ? f.getAbsolutePath() : "null"), e);
            return false;
        }
    }

    private static String safeString(String str) {
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == ' ') output.append(c);
            else if ((c >= '0') && (c <= '9')) output.append(c);
            else if ((c >= 'a') && (c <= 'z')) output.append(c);
            else if ((c >= 'A') && (c <= 'Z')) output.append(c);
        }
        return output.toString();
    }

    private static void writeShortcut(Activity activity, Container container,
                                      String gameName, String exePath, Handler h) {
        new Thread(() -> {
            try {
                Method getDesktopDir = container.getClass().getMethod("getDesktopDir");
                File desktopDir = (File) getDesktopDir.invoke(container);

                if (desktopDir == null) {
                    h.post(() -> Toast.makeText(activity,
                            "Container desktop directory not found.",
                            Toast.LENGTH_LONG).show());
                    return;
                }

                if (!desktopDir.exists() && !desktopDir.mkdirs()) {
                    h.post(() -> Toast.makeText(activity,
                            "Could not create desktop directory.",
                            Toast.LENGTH_LONG).show());
                    return;
                }

                // Sanitize game name for use as a filename
                String safeName = gameName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
                if (safeName.isEmpty()) safeName = "game";

                File shortcutFile = new File(desktopDir, safeName + ".desktop");

                // Z: = imagefs root. Convert Android path → Z:\gog_games\...\game.exe
                // Shortcut.java unescape() expects each \ encoded as \\\\ (4 chars) in file.
                String winPath = GogInstallPath.toWinePath(activity, exePath);
                String escapedWinPath = winPath.replace("\\", "\\\\\\\\");

                String content = "[Desktop Entry]\n"
                        + "Name=" + gameName + "\n"
                        + "Exec=wine " + escapedWinPath + "\n"
                        + "Icon=\n"
                        + "Type=Application\n"
                        + "StartupWMClass=explorer\n"
                        + "\n"
                        + "[Extra Data]\n";

                try (FileWriter fw = new FileWriter(shortcutFile)) {
                    fw.write(content);
                }

                h.post(() -> Toast.makeText(activity,
                        "\"" + gameName + "\" added to Shortcuts.\n"
                                + "Open the side menu → Shortcuts to launch and configure it.",
                        Toast.LENGTH_LONG).show());

                runFromShortcut(activity, new Shortcut(container, shortcutFile));

            } catch (Exception e) {
                h.post(() -> Toast.makeText(activity,
                        "Failed to add shortcut: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        }).start();
    }
}
