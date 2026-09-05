package com.winlator.cmod.core;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Removes a game from a container, rather than only removing the shortcut that points at it.
 *
 * There are two ways to do that and they are not equivalent. Anything installed by a real
 * installer registers an uninstaller in the prefix, which knows what it put where; running that
 * is always the better answer. A game that was copied in has nothing to run, so the only option
 * left is deleting its folder, which this will only do within narrow limits -- see
 * {@link #findInstallDir}.
 */
public abstract class GameUninstaller {
    private static final String TAG = "GameUninstaller";

    /** Where Windows records what an installer left behind, per registry hive file. */
    private static final String[] UNINSTALL_KEYS = {
        "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\",
        "Software\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\"
    };

    /** Windows' own trees, which hold nothing that is a game's folder at any depth. */
    private static final String[] SYSTEM_FOLDERS = {"windows", "users", "programdata", "temp"};

    /**
     * Folders that hold programs in general, so deleting one whole is never what the user asked
     * for. A game's own folder is the one inside them.
     */
    private static final String[] PROGRAM_FOLDERS = {
        "program files", "program files (x86)", "common files",
        "gog games", "games", "steam", "steamapps"
    };

    /** An entry from the prefix's list of installed programs. */
    public static class UninstallEntry {
        public final String displayName;
        public final String uninstallString;
        public final String installLocation;

        UninstallEntry(String displayName, String uninstallString, String installLocation) {
            this.displayName = displayName;
            this.uninstallString = uninstallString;
            this.installLocation = installLocation;
        }
    }

    /**
     * The registered uninstaller for a shortcut's program, or null when it has none -- which is
     * the normal case for a game that was copied into the container rather than installed.
     *
     * An entry claims the shortcut if the executable sits inside the folder the entry says it
     * installed to, or, for the installers that record no location, inside the folder their
     * uninstaller sits in. The longest match wins, so a game inside another game's folder
     * resolves to its own entry rather than its neighbour's.
     */
    public static UninstallEntry findUninstallEntry(Context context, Container container, Shortcut shortcut) {
        File exeFile = resolveExecutable(context, container, shortcut);
        String winPath = exeFile != null ? GuestScriptRunner.toWinPath(context, container, exeFile) : null;
        return findUninstallEntry(container, winPath != null ? winPath : shortcut.path);
    }

    private static UninstallEntry findUninstallEntry(Container container, String winExePath) {
        if (winExePath == null || winExePath.isEmpty()) return null;

        String exePath = winExePath.toLowerCase(Locale.ENGLISH);
        UninstallEntry best = null;
        int bestLength = 0;

        for (UninstallEntry entry : readUninstallEntries(container)) {
            // Either of the two folders an entry names can be the one the game is in: what it
            // recorded as its install location, and where it left its uninstaller. They usually
            // agree, and where they do not, whichever matches is as good as the other.
            for (String location : new String[] {entry.installLocation, parentOf(executableOf(entry.uninstallString))}) {
                if (location == null || location.isEmpty()) continue;

                String prefix = location.toLowerCase(Locale.ENGLISH);
                while (prefix.endsWith("\\")) prefix = prefix.substring(0, prefix.length() - 1);
                if (prefix.length() <= bestLength || !exePath.startsWith(prefix + "\\")) continue;

                best = entry;
                bestLength = prefix.length();
            }
        }
        return best;
    }

    /**
     * The uninstaller sitting in a game's own folder, for when the registry does not lead to it.
     *
     * An installer writes both the uninstaller and the entry that points at it, so the entry is
     * normally the way in -- but it can be missing, or recorded against a path that no longer
     * reads back the same, and the file is still there either way. Running it is a great deal
     * better than deleting the folder around it.
     */
    public static UninstallEntry findUninstallerInFolder(Context context, Container container, Shortcut shortcut) {
        File installDir = findInstallDir(context, container, shortcut);
        if (installDir == null) return null;

        File[] files = installDir.listFiles(File::isFile);
        if (files == null) return null;

        File uninstaller = null;
        for (File file : files) {
            String name = file.getName().toLowerCase(Locale.ENGLISH);
            if (!name.endsWith(".exe")) continue;
            // Inno Setup's unins000.exe is the common one; the others cover what else turns up.
            if (!name.startsWith("unins") && !name.equals("uninstall.exe") && !name.equals("uninstaller.exe")) continue;
            // Numbered lowest first, so the original wins over a later install's spare.
            if (uninstaller == null || name.compareTo(uninstaller.getName().toLowerCase(Locale.ENGLISH)) < 0)
                uninstaller = file;
        }
        if (uninstaller == null) return null;

        String winPath = GuestScriptRunner.toWinPath(context, container, uninstaller);
        if (winPath == null) return null;

        return new UninstallEntry(shortcut.name, "\"" + winPath + "\"",
            GuestScriptRunner.toWinPath(context, container, installDir));
    }

    /**
     * Runs a registered uninstaller in a console session, the way an installer is run.
     *
     * The wait before the container closes is generous because an uninstaller commonly hands off
     * to a copy of itself in the temp folder -- it has to, since it cannot delete the file it is
     * running from -- so the command returns while the work is still going on.
     */
    public static void runUninstaller(Activity activity, Container container, UninstallEntry entry) {
        String name = entry.displayName != null ? entry.displayName : "the game";

        // A game installed under a name with anything outside ASCII in it -- LEGO(R) Bricktales,
        // say -- gets a stand-in path the script can carry; whatever switches the recorded
        // command carries are ASCII and can be written as they are.
        String executable = executableOf(entry.uninstallString);
        String switches = executable != null && entry.uninstallString.length() > executable.length()
            ? entry.uninstallString.substring(entry.uninstallString.indexOf(executable) + executable.length()).trim()
            : "";
        if (executable != null && executable.startsWith("\"")) executable = executable.substring(1);
        if (switches.startsWith("\"")) switches = switches.substring(1).trim();

        String command = "\"" + GuestScriptRunner.asciiPath(activity, container, executable, UNINSTALL_LINK) + "\"";

        List<String> body = new ArrayList<>();
        body.add("echo Uninstalling ...");
        body.add("echo.");
        body.add(switches.isEmpty() ? command : command + " " + switches);
        body.add("echo.");
        body.add("echo Uninstaller exit code: %errorlevel%");

        // An uninstaller is the same 32-bit Delphi program its installer was, and needs the same
        // accurate x87 to find its own files.
        GuestScriptRunner.run(activity, container, "Uninstalling " + name, "uninstall", body,
            GuestScriptRunner.ACCURATE_X87_ENV, UNINSTALLER_CLOSE_DELAY_SECONDS);
    }

    /**
     * Longer than a job that runs to completion needs, since an uninstaller commonly hands off to
     * a copy of itself in the temp folder -- it has to, being unable to delete the file it is
     * running from -- and the command returns while that copy is still working.
     */
    private static final int UNINSTALLER_CLOSE_DELAY_SECONDS = 30;

    /** The link name a game folder gets when its own path cannot go into the script. */
    private static final String UNINSTALL_LINK = "winlator-uninstall-target";

    /**
     * The folder that can be deleted to remove a game with no uninstaller, or null when deleting
     * anything would be unsafe.
     *
     * The game's own folder is rarely the one its executable sits in -- a launcher lives beside
     * the game while the game itself is under bin\x64 or similar -- so this climbs to the
     * outermost folder that still belongs to the game: the last one before the drive root or
     * before a folder that holds programs in general, such as Program Files or GOG Games.
     *
     * The rules are deliberately narrow, since this deletes without anything to undo it. The
     * executable has to live on the container's own C: drive -- a shortcut added from local
     * storage points at the user's Download folder through a mapped drive, and their files are
     * not ours to remove -- and what it arrives at can be neither the drive root nor one of those
     * shared folders.
     */
    public static File findInstallDir(Context context, Container container, Shortcut shortcut) {
        File exeFile = resolveExecutable(context, container, shortcut);
        if (exeFile == null) return null;

        File driveC = new File(container.getRootDir(), ".wine/drive_c");
        String drivePath = driveC.getAbsolutePath();
        if (!exeFile.getAbsolutePath().startsWith(drivePath + "/")) return null;

        File installDir = null;
        for (File dir = exeFile.getParentFile(); dir != null; dir = dir.getParentFile()) {
            if (dir.getAbsolutePath().equals(drivePath)) break;
            // Nothing under Windows' own trees is a game's folder, whatever it is called, so a
            // shortcut that leads into one -- to a .lnk on the public desktop, say -- gets no
            // folder at all rather than the folder above it.
            if (isSystemFolder(dir.getName())) return null;
            if (isProgramFolder(dir.getName())) break;
            installDir = dir;
        }
        return installDir;
    }

    /**
     * The executable a shortcut really points at.
     *
     * A game's own installer writes a shortcut through a .lnk on the desktop rather than to the
     * executable, so the path on the shortcut leads to Windows' desktop folder and says nothing
     * about where the game is. The .lnk names the executable, so it is read.
     */
    public static File resolveExecutable(Context context, Container container, Shortcut shortcut) {
        File file = GuestScriptRunner.toHostPath(context, container, shortcut.path);
        if (file == null || !shortcut.path.toLowerCase(Locale.ENGLISH).endsWith(".lnk")) return file;

        File target = MSLink.getLocalFile(container.getRootDir(), ".wine", container.drivesIterator(), file);
        if (target == null) Log.w(TAG, "Could not read the target of " + shortcut.path);
        return target != null ? target : file;
    }

    /** The other shortcuts that would be left pointing at nothing if a folder went. */
    public static List<Shortcut> shortcutsInside(Context context, Container container, File installDir, Shortcut except) {
        List<Shortcut> shortcuts = new ArrayList<>();

        File[] files = container.getDesktopDir().listFiles((dir, name) -> name.endsWith(".desktop"));
        if (files == null) return shortcuts;

        String installPath = installDir.getAbsolutePath() + "/";
        for (File file : files) {
            if (except != null && file.equals(except.file)) continue;
            try {
                Shortcut shortcut = new Shortcut(container, file);
                File exeFile = GuestScriptRunner.toHostPath(context, container, shortcut.path);
                if (exeFile != null && exeFile.getAbsolutePath().startsWith(installPath)) shortcuts.add(shortcut);
            }
            catch (Exception e) {
                Log.w(TAG, "Skipping unreadable shortcut " + file, e);
            }
        }
        return shortcuts;
    }

    /**
     * What a folder holds, for telling the user what they are about to lose. FileUtils has an
     * async size walk, but it reports each file separately rather than a total.
     */
    public static long folderSize(File dir) {
        long size = 0;
        Deque<File> pending = new ArrayDeque<>();
        pending.push(dir);

        while (!pending.isEmpty()) {
            File[] files = pending.pop().listFiles();
            if (files == null) continue;

            for (File file : files) {
                if (file.isDirectory()) pending.push(file);
                else size += file.length();
            }
        }
        return size;
    }

    private static boolean isSystemFolder(String name) {
        return Arrays.asList(SYSTEM_FOLDERS).contains(name.toLowerCase(Locale.ENGLISH));
    }

    private static boolean isProgramFolder(String name) {
        return Arrays.asList(PROGRAM_FOLDERS).contains(name.toLowerCase(Locale.ENGLISH));
    }

    /**
     * An UninstallString is a command line, so it goes into the script as it is. The exception is
     * the one written by installers that never quoted it: a bare path with a space in it would be
     * read as a command plus arguments, so it is quoted here once it is known to be a real file.
     */
    private static String uninstallCommand(Context context, Container container, String uninstallString) {
        String command = uninstallString.trim();
        if (command.startsWith("\"") || !command.contains(" ")) return command;

        File file = GuestScriptRunner.toHostPath(context, container, command);
        return file != null && file.isFile() ? "\"" + command + "\"" : command;
    }

    /** The program an UninstallString runs, which is either quoted or ends at the first space. */
    private static String executableOf(String uninstallString) {
        if (uninstallString == null) return null;

        String command = uninstallString.trim();
        if (command.startsWith("\"")) {
            int end = command.indexOf('"', 1);
            return end > 1 ? command.substring(1, end) : null;
        }

        // An unquoted path with spaces cannot be told apart from a path plus arguments, so it is
        // taken whole when what follows the space is not a switch.
        int space = command.indexOf(' ');
        if (space == -1) return command;
        return command.indexOf('/', space) != -1 || command.indexOf('-', space) != -1
            ? command.substring(0, space) : command;
    }

    private static String parentOf(String winPath) {
        if (winPath == null) return null;
        int index = winPath.lastIndexOf('\\');
        return index > 2 ? winPath.substring(0, index) : null;
    }

    /**
     * Every installed program the prefix knows about. The hive files are plain text, and the
     * registry editor reads a value at a key it is given rather than listing what is under one,
     * so the sections are walked here.
     */
    private static List<UninstallEntry> readUninstallEntries(Container container) {
        List<UninstallEntry> entries = new ArrayList<>();
        File prefixDir = new File(container.getRootDir(), ".wine");

        for (String hive : new String[] {"system.reg", "user.reg"}) {
            File hiveFile = new File(prefixDir, hive);
            if (!hiveFile.isFile()) continue;

            boolean inUninstallKey = false;
            String displayName = null, uninstallString = null, installLocation = null;

            for (String line : FileUtils.readLines(hiveFile)) {
                if (line.startsWith("[")) {
                    if (inUninstallKey && uninstallString != null)
                        entries.add(new UninstallEntry(displayName, uninstallString, installLocation));

                    inUninstallKey = isUninstallKey(line);
                    displayName = uninstallString = installLocation = null;
                    continue;
                }
                if (!inUninstallKey || !line.startsWith("\"")) continue;

                String name = valueName(line);
                if ("DisplayName".equalsIgnoreCase(name)) displayName = valueData(line);
                else if ("UninstallString".equalsIgnoreCase(name)) uninstallString = valueData(line);
                else if ("InstallLocation".equalsIgnoreCase(name)) installLocation = valueData(line);
            }

            if (inUninstallKey && uninstallString != null)
                entries.add(new UninstallEntry(displayName, uninstallString, installLocation));
        }

        Log.d(TAG, "Found " + entries.size() + " uninstall entries in " + prefixDir);
        return entries;
    }

    /** A key line is the key path in brackets, with its backslashes doubled, then a timestamp. */
    private static boolean isUninstallKey(String line) {
        int end = line.indexOf(']');
        if (end == -1) return false;

        String key = line.substring(1, end).replace("\\\\", "\\");
        for (String prefix : UNINSTALL_KEYS) if (key.regionMatches(true, 0, prefix, 0, prefix.length())) return true;
        return false;
    }

    private static String valueName(String line) {
        int end = line.indexOf('"', 1);
        return end > 0 ? line.substring(1, end) : null;
    }

    /** The string a value line holds, or null when the value is not a string. */
    private static String valueData(String line) {
        int separator = line.indexOf("=\"");
        if (separator == -1) return null;

        String data = line.substring(separator + 2);
        int end = data.length();
        while (end > 0 && data.charAt(end - 1) != '"') end--;
        if (end == 0) return null;

        return unescape(data.substring(0, end - 1));
    }

    /**
     * Wine escapes a backslash or a quote with a backslash, and anything outside plain ASCII as
     * \xNNNN. A path such as C:\GOG Games\LEGO\x00ae Bricktales comes back as the name the game
     * is actually installed under only if those are decoded, and a path that does not read back
     * exactly is a path that matches nothing.
     */
    private static String unescape(String value) {
        StringBuilder sb = new StringBuilder(value.length());

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                sb.append(c);
                continue;
            }

            char escaped = value.charAt(++i);
            if (escaped != 'x') {
                sb.append(escaped);
                continue;
            }

            int end = i + 1;
            while (end < value.length() && end - i <= 4 && isHex(value.charAt(end))) end++;
            if (end == i + 1) {
                sb.append(escaped);
                continue;
            }

            sb.append((char) Integer.parseInt(value.substring(i + 1, end), 16));
            i = end - 1;
        }
        return sb.toString();
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
