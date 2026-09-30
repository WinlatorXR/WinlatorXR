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

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;

import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.SessionSettings;
import com.winlator.xr.XrActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Loads equirectangular panoramas that are shown behind the screen as a 360 environment.
 *
 * Users drop images into Android/data/&lt;package&gt;/files/environments/. That directory is
 * app-specific so it needs no storage permission, and it avoids putting a document picker
 * in front of someone who is wearing a headset.
 */
public class XrEnvironment {
    public static final String PREF_KEY = "xr_environment";
    public static final String ENABLED_KEY = "xr_environment_enabled";
    private static final String DIR_NAME = "environments";
    private static final String TAG = "XrEnvironment";

    // 8192 across 360 degrees roughly matches headset pixel density; past that, with no
    // mipmaps, extra detail only shimmers. It is a single 128MB static swapchain image.
    private static final int MAX_WIDTH = 8192;
    private static final int MAX_HEIGHT = 4096;

    private static final List<String> EXTENSIONS = Arrays.asList(".jpg", ".jpeg", ".png", ".webp");

    private XrEnvironment() {}

    public static File getDirectory(Context context) {
        File dir = new File(context.getExternalFilesDir(null), DIR_NAME);
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    /** Names of the panoramas available on disk, in the order they should be listed. */
    public static List<String> list(Context context) {
        List<String> names = new ArrayList<>();
        File[] files = getDirectory(context).listFiles();
        if (files != null) {
            for (File file : files) {
                if (!file.isFile()) continue;
                String name = file.getName();
                if (isSupported(name)) names.add(name);
            }
        }
        names.sort(String::compareToIgnoreCase);
        return names;
    }

    private static boolean isSupported(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String extension : EXTENSIONS) {
            if (lower.endsWith(extension)) return true;
        }
        return false;
    }

    /**
     * The file name a picked image would be stored under, or null if it is not a format we
     * accept. Lets the caller check for a clash before anything is written to disk.
     */
    public static String nameFor(Context context, Uri uri) {
        String name = FileUtils.getUriFileName(context, uri);
        if (name == null || name.trim().isEmpty()) name = "panorama.jpg";
        // Whatever the picker reports is a display name, not a path, but it can still carry
        // characters that are not legal in a file name.
        name = name.trim().replaceAll("[\\/:*?\"<>|]", "_");
        return isSupported(name) ? name : null;
    }

    /**
     * The file name to store an import under when the user renames it: their name, with
     * the extension of the picked file so the image still decodes and lists.
     */
    public static String rename(String fileName, String name) {
        int dot = fileName.lastIndexOf('.');
        String extension = fileName.substring(dot);
        name = name.trim().replaceAll("[\\/:*?\"<>|]", "_");
        if (name.toLowerCase(Locale.ROOT).endsWith(extension.toLowerCase(Locale.ROOT))) {
            name = name.substring(0, name.length() - extension.length()).trim();
        }
        return name.isEmpty() ? fileName : name + extension;
    }

    /** Whether a panorama of that name is already installed. */
    public static boolean exists(Context context, String name) {
        return name != null && new File(getDirectory(context), name).isFile();
    }

    /**
     * Copies a picked image into the environments folder, so the user can add panoramas
     * without going near Android/data by hand. Returns the name it was stored under, or
     * null if it is not a format we can decode. When a panorama of the same name is already
     * installed, replace overwrites it in place; otherwise the import is stored alongside it
     * under a numbered name.
     */
    public static String importFrom(Context context, Uri uri, String name, boolean replace) {
        if (name == null) return null;

        File directory = getDirectory(context);
        File destination = new File(directory, name);
        if (destination.exists() && !replace) destination = uniqueFile(directory, name);

        // Copy to a temporary file first, so replacing a panorama with something undecodable
        // cannot destroy the one that was already there. The temporary name carries no image
        // extension, so a leftover never shows up in the picker.
        File temp = new File(directory, ".import-" + System.currentTimeMillis());
        if (!FileUtils.copy(context, uri, temp)) {
            temp.delete();
            return null;
        }

        // A supported extension does not make it a decodable image; check before letting it
        // show up in the picker as a panorama that would silently fail to load.
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(temp.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            temp.delete();
            return null;
        }

        destination.delete();
        if (!temp.renameTo(destination)) {
            temp.delete();
            return null;
        }
        return destination.getName();
    }

    /** Deletes an installed panorama. The name must be one that {@link #list} returned. */
    public static boolean delete(Context context, String name) {
        if (name == null || name.isEmpty() || !isSupported(name)) return false;
        // Names come from the folder listing, but never let one walk out of the folder.
        if (name.contains("/") || name.contains("\\")) return false;
        return new File(getDirectory(context), name).delete();
    }

    private static File uniqueFile(File directory, String name) {
        File file = new File(directory, name);
        if (!file.exists()) return file;

        int dot = name.lastIndexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot);
        String extension = dot < 0 ? "" : name.substring(dot);
        for (int i = 2; i < 1000; i++) {
            file = new File(directory, stem + " (" + i + ")" + extension);
            if (!file.exists()) return file;
        }
        return file;
    }

    /**
     * Whether the selected panorama is currently shown. Separate from the selection itself,
     * so switching the environment off from the in-session menu does not lose which
     * panorama the user picked.
     */
    public static boolean isEnabled(Context context) {
        return SessionSettings.getBoolean(context, ENABLED_KEY, true);
    }

    public static void setEnabled(Context context, boolean enabled) {
        SessionSettings.putBoolean(context, ENABLED_KEY, enabled);
        XrActivity activity = XrActivity.getInstance();
        if (activity != null) activity.nativeSetEnvironmentEnabled(enabled);
    }

    public static String getSelected(Context context) {
        return SessionSettings.getString(context, PREF_KEY, "");
    }

    public static void setSelected(Context context, String name) {
        SessionSettings.putString(context, PREF_KEY, name == null ? "" : name);
    }

    /**
     * Decodes the named panorama and hands it to the native layer. Decoding an 8K image takes
     * long enough to stutter the UI, so it happens on a worker thread; the native side only
     * copies the pixels and defers the actual upload to the render thread.
     */
    public static void apply(Context context, String name) {
        final XrActivity activity = XrActivity.getInstance();
        if (activity == null) return;
        if (!activity.nativeIsEnvironmentSupported()) return;

        if (name == null || name.isEmpty()) {
            activity.nativeSetEnvironment(null);
            return;
        }

        final File file = new File(getDirectory(context), name);
        new Thread(() -> {
            Bitmap bitmap = decode(file);
            if (bitmap == null) {
                Log.e(TAG, "Could not decode environment " + file);
                activity.nativeSetEnvironment(null);
                return;
            }

            // ARGB_8888 is laid out as R,G,B,A bytes in memory, which is what GL_RGBA wants.
            activity.nativeSetEnvironment(bitmap);
            bitmap.recycle();
        }, "XrEnvironmentLoader").start();
    }

    /**
     * A small, subsampled decode of the named panorama for the Settings preview, or null if
     * it cannot be read. Cheap enough for a picker, but still meant for a worker thread.
     */
    public static Bitmap decodeThumbnail(Context context, String name, int maxWidth) {
        File file = new File(getDirectory(context), name);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / (options.inSampleSize * 2) >= maxWidth) {
            options.inSampleSize *= 2;
        }
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private static Bitmap decode(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inSampleSize = 1;
        while ((bounds.outWidth / options.inSampleSize > MAX_WIDTH) ||
               (bounds.outHeight / options.inSampleSize > MAX_HEIGHT)) {
            options.inSampleSize *= 2;
        }

        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (bitmap == null) return null;

        // Subsampling only halves, so an odd source size can still land above the cap.
        if (bitmap.getWidth() > MAX_WIDTH || bitmap.getHeight() > MAX_HEIGHT) {
            int width = Math.min(bitmap.getWidth(), MAX_WIDTH);
            int height = Math.min(bitmap.getHeight(), MAX_HEIGHT);
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, width, height, true);
            if (scaled != bitmap) bitmap.recycle();
            bitmap = scaled;
        }

        if (bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
            Bitmap converted = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (converted != null) {
                bitmap.recycle();
                bitmap = converted;
            }
        }
        return bitmap;
    }
}
