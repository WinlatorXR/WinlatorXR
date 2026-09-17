package com.winlator.cmod.core;

import android.content.Context;
import android.os.StatFs;
import android.util.Log;

import com.winlator.cmod.contents.ModInstaller;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Unpacking a .zip onto Z:, and working out which of the executables inside it the user meant.
 *
 * A game or an offline installer that arrives zipped cannot be run from the archive: Wine needs
 * real files, and an installer needs the data files packed beside it to sit beside it on disk as
 * well. Everything is unpacked under the image root, which is Z: in every container, so one copy
 * serves them all and nothing depends on how a particular container's drives happen to be mapped.
 */
public abstract class ZipExtractor {
    private static final String TAG = "ZipExtractor";

    /** The folder under the image root -- Z:\extracted -- that unpacked archives go into. */
    public static final String EXTRACTED_DIR_NAME = "extracted";

    /** Guards against a runaway tree while looking for what to run. */
    private static final int MAX_SCAN_DEPTH = 16;
    private static final int MAX_SCAN_FILES = 20000;

    /** What the archive is being unpacked for, which is what decides the pick order below. */
    public enum Role {
        /** A game: its setup and its redistributables are noise, the game's own .exe is wanted. */
        GAME,
        /** An installer: setup.exe is exactly the target, and an .msi counts as one too. */
        INSTALLER
    }

    public interface OnProgressListener {
        void onProgress(String entryName, long bytesDone, long bytesTotal);
    }

    public static boolean isZip(String name) {
        return name != null && name.toLowerCase(Locale.ENGLISH).endsWith(".zip");
    }

    public static boolean isZip(File file) {
        return file != null && isZip(file.getName());
    }

    /** Z:\extracted, created on demand. */
    public static File extractedRoot(Context context) {
        File dir = extractedRootPath(context);
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    /** Where Z:\extracted is, for the questions that only ask about a path. */
    private static File extractedRootPath(Context context) {
        return new File(ImageFs.find(context).getRootDir(), EXTRACTED_DIR_NAME);
    }

    /** The folder an archive unpacks into: Z:\extracted\&lt;archive name without .zip&gt;. */
    public static File destinationFor(Context context, File zip) {
        String name = FileUtils.getBasename(zip.getName()).replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (name.isEmpty()) name = "archive";
        return new File(extractedRootPath(context), name);
    }

    /**
     * The folder directly under Z:\extracted that a file belongs to, or null when it is not part
     * of an unpacked archive at all. That folder is the whole of what unpacking left behind, so
     * it is what removing the archive's entry deletes.
     */
    public static File containingExtraction(Context context, File file) {
        if (file == null) return null;

        String root = extractedRootPath(context).getAbsolutePath();
        File candidate = null;
        for (File dir = file.getAbsoluteFile().getParentFile(); dir != null; dir = dir.getParentFile()) {
            if (dir.getAbsolutePath().equals(root)) return candidate;
            candidate = dir;
        }
        return null;
    }

    /** What the archive holds unpacked, for checking there is room for it before starting. */
    public static long uncompressedSize(File zip) {
        try (ZipFile zipFile = open(zip)) {
            long total = 0;
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getSize() > 0) total += entry.getSize();
            }
            return total;
        }
        catch (IOException e) {
            Log.w(TAG, "Could not read the size of " + zip.getName(), e);
            return -1;
        }
    }

    /** How much room is left where the archive would go, or -1 when it cannot be read. */
    public static long usableSpaceFor(File dir) {
        try {
            return new StatFs(dir.getAbsolutePath()).getAvailableBytes();
        }
        catch (Exception e) {
            Log.w(TAG, "Could not read the free space at " + dir.getAbsolutePath(), e);
            return -1;
        }
    }

    /**
     * Unpacks the whole archive into destDir.
     *
     * An entry naming a path outside the destination -- the "zip slip" shape, ../ in an entry
     * name -- fails the whole extraction rather than being skipped: an archive doing that is not
     * one to unpack the rest of.
     */
    public static void extract(File zip, File destDir, OnProgressListener listener) throws IOException {
        extract(zip, destDir, null, listener);
    }

    /**
     * Unpacks the archive into destDir, optionally dropping one wrapping folder from the front of
     * every entry name.
     *
     * Nothing already in destDir is removed: entries land beside what is there and replace only
     * the files they share a name with. That is what unpacking over an existing folder has to
     * mean -- see {@link ModInstaller}, whose destination is a game the user still wants.
     *
     * @param stripPrefix the single top-level folder to unpack the contents of rather than the
     *                    folder itself, or null to unpack entry names as they are
     */
    public static void extract(File zip, File destDir, String stripPrefix, OnProgressListener listener) throws IOException {
        if (!destDir.isDirectory() && !destDir.mkdirs())
            throw new IOException("Could not create " + destDir.getAbsolutePath());

        String destPath = destDir.getCanonicalPath() + File.separator;
        byte[] buffer = new byte[65536];

        try (ZipFile zipFile = open(zip)) {
            long total = 0;
            Enumeration<? extends ZipEntry> sizes = zipFile.entries();
            while (sizes.hasMoreElements()) {
                ZipEntry entry = sizes.nextElement();
                if (!entry.isDirectory() && entry.getSize() > 0) total += entry.getSize();
            }

            long done = 0;
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = strip(entry.getName(), stripPrefix);
                if (name == null) continue;

                File target = new File(destDir, name);

                if (!target.getCanonicalPath().startsWith(destPath))
                    throw new IOException("Entry outside of the destination folder: " + entry.getName());

                if (entry.isDirectory()) {
                    if (!target.isDirectory() && !target.mkdirs())
                        throw new IOException("Could not create " + target.getAbsolutePath());
                    continue;
                }

                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs())
                    throw new IOException("Could not create " + parent.getAbsolutePath());

                try (InputStream input = zipFile.getInputStream(entry);
                     OutputStream output = new FileOutputStream(target)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                        done += count;
                        if (listener != null) listener.onProgress(entry.getName(), done, total);
                    }
                }
            }
        }
    }

    /**
     * Archives written on Windows do not all flag their entry names as UTF-8, and Android has no
     * CP437 to decode the older ones with, so a name that is not valid UTF-8 is read as Latin-1:
     * the bytes survive, which is what matters for finding the file again afterwards.
     */
    private static ZipFile open(File zip) throws IOException {
        try {
            return new ZipFile(zip, StandardCharsets.UTF_8);
        }
        catch (IllegalArgumentException e) {
            Log.w(TAG, "Falling back to Latin-1 entry names for " + zip.getName());
            return new ZipFile(zip, Charset.forName("ISO-8859-1"));
        }
    }

    /**
     * An entry name with its wrapping folder taken off, or null when the entry is that folder
     * itself and so has nothing left to unpack.
     */
    private static String strip(String name, String stripPrefix) {
        if (stripPrefix == null) return name;

        String prefix = stripPrefix + "/";
        if (!name.startsWith(prefix)) return null;

        String stripped = name.substring(prefix.length());
        return stripped.isEmpty() ? null : stripped;
    }

    /**
     * The one folder an archive holds everything inside, or null when it does not have one.
     *
     * This only reports the shape. It says nothing about whether that folder is the download's
     * own wrapper or a folder belonging to the layout the archive is packed in -- both look
     * identical from here, and telling them apart is the caller's business.
     */
    public static String commonTopLevelFolder(File zip) {
        try (ZipFile zipFile = open(zip)) {
            String folder = null;

            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                int slash = name.indexOf('/');
                // A file sitting at the top level means the archive is not wrapped at all.
                if (slash <= 0) return null;

                String top = name.substring(0, slash);
                if (folder == null) folder = top;
                else if (!folder.equals(top)) return null;
            }
            return folder;
        }
        catch (IOException e) {
            Log.w(TAG, "Could not read the layout of " + zip.getName(), e);
            return null;
        }
    }

    /**
     * The .exe entries an archive holds, as the paths they will have once unpacked.
     *
     * Read from the archive rather than by scanning afterwards so that what came out of it can be
     * told apart from what was already in the folder, which is the whole question when unpacking
     * over a game that has executables of its own.
     */
    public static List<String> exeEntries(File zip, String stripPrefix) {
        return entries(zip, stripPrefix, ".exe");
    }

    /**
     * Every file an archive holds, as the paths they will have once unpacked -- what says where
     * an archive's contents are about to land, before any of it is written.
     */
    public static List<String> fileEntries(File zip, String stripPrefix) {
        return entries(zip, stripPrefix, null);
    }

    /**
     * The archive's file entries, shallowest first, which is the order that reads as a layout
     * rather than as whatever order the archive happens to be packed in.
     */
    private static List<String> entries(File zip, String stripPrefix, String suffix) {
        List<String> found = new ArrayList<>();

        try (ZipFile zipFile = open(zip)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;

                String name = strip(entry.getName(), stripPrefix);
                if (name == null) continue;
                if (suffix == null || name.toLowerCase(Locale.ENGLISH).endsWith(suffix)) found.add(name);
            }
        }
        catch (IOException e) {
            Log.w(TAG, "Could not read the contents of " + zip.getName(), e);
        }

        Collections.sort(found, (a, b) -> {
            int depthA = a.split("/").length;
            int depthB = b.split("/").length;
            if (depthA != depthB) return Integer.compare(depthA, depthB);
            return a.compareToIgnoreCase(b);
        });
        return found;
    }

    /**
     * The programs an unpacked archive holds, best guess first.
     *
     * Which one the user meant cannot be known, so this only puts them in the order they are most
     * likely to want -- the shallowest, least support-looking executable first -- and the choice
     * is still theirs whenever there is more than one.
     */
    public static List<File> findExecutables(File root, Role role) {
        List<File> found = new ArrayList<>();
        collect(root, role, found, 0);

        final String hint = root.getName().toLowerCase(Locale.ENGLISH);
        Collections.sort(found, (a, b) -> {
            int scoreA = score(root, a, role, hint);
            int scoreB = score(root, b, role, hint);
            if (scoreA != scoreB) return Integer.compare(scoreA, scoreB);
            return a.getName().compareToIgnoreCase(b.getName());
        });
        return found;
    }

    private static void collect(File dir, Role role, List<File> found, int depth) {
        if (depth > MAX_SCAN_DEPTH || found.size() >= MAX_SCAN_FILES) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) collect(file, role, found, depth + 1);
            else if (isRunnable(file.getName(), role)) found.add(file);
        }
    }

    private static boolean isRunnable(String name, Role role) {
        String lower = name.toLowerCase(Locale.ENGLISH);
        return lower.endsWith(".exe") || (role == Role.INSTALLER && lower.endsWith(".msi"));
    }

    /** Names that are never the program the archive was picked for. */
    private static final String[] NEVER_THE_TARGET = {
        "unins", "uninstall", "vcredist", "vc_redist", "dxsetup", "dxwebsetup", "directx",
        "dotnetfx", "ndp4", "oalinst", "openal", "physx", "crashreport", "crashhandler",
        "unitycrashhandler", "ueprereqsetup", "dotnetsetup"
    };

    /** Folders that hold what a game ships beside itself rather than the game. */
    private static final String[] SUPPORT_FOLDERS = {
        "redist", "_commonredist", "commonredist", "vcredist", "directx", "dotnet",
        "prerequisites", "support", "_redist"
    };

    /** Names that are the target when an installer is what was asked for. */
    private static final String[] INSTALLER_NAMES = {"setup", "install", "start"};

    private static int score(File root, File file, Role role, String hint) {
        String stem = FileUtils.getBasename(file.getName().toLowerCase(Locale.ENGLISH));

        int score = depthBelow(root, file) * 10;

        for (String pattern : NEVER_THE_TARGET) if (stem.startsWith(pattern)) score += 1000;
        for (String folder : foldersAbove(root, file)) {
            for (String pattern : SUPPORT_FOLDERS) {
                if (folder.startsWith(pattern)) {
                    score += 1000;
                    break;
                }
            }
        }

        if (role == Role.INSTALLER) {
            for (String pattern : INSTALLER_NAMES) if (stem.startsWith(pattern)) score -= 100;
        }
        else {
            // A game's own setup.exe is what put it there, not what to run afterwards.
            if (stem.startsWith("setup") || stem.startsWith("install")) score += 500;
            // A launcher is what the user would double-click, so it beats the engine binary.
            if (stem.contains("launcher")) score -= 20;
        }

        // An archive is named after what is in it often enough to be worth leaning on.
        if (!hint.isEmpty() && (stem.equals(hint) || hint.startsWith(stem) || stem.startsWith(hint))) score -= 200;

        return score;
    }

    private static int depthBelow(File root, File file) {
        int depth = 0;
        for (File dir = file.getParentFile(); dir != null && !dir.equals(root); dir = dir.getParentFile()) depth++;
        return depth;
    }

    private static List<String> foldersAbove(File root, File file) {
        List<String> names = new ArrayList<>();
        for (File dir = file.getParentFile(); dir != null && !dir.equals(root); dir = dir.getParentFile())
            names.add(dir.getName().toLowerCase(Locale.ENGLISH));
        return names;
    }

    /** The path an executable is shown by, which is the part of it below the unpacked folder. */
    public static String relativeName(File root, File file) {
        String rootPath = root.getAbsolutePath() + File.separator;
        String path = file.getAbsolutePath();
        return path.startsWith(rootPath) ? path.substring(rootPath.length()) : file.getName();
    }
}
