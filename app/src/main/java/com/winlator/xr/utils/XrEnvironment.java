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
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.core.FileUtils;
import com.winlator.xr.XrActivity;

import java.io.File;
import java.nio.ByteBuffer;
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

    // A 4096x2048 panorama costs 32MB of swapchain, which is as much as is worth spending
    // on something the user is not looking directly at.
    private static final int MAX_WIDTH = 4096;
    private static final int MAX_HEIGHT = 2048;

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
     * Copies a picked image into the environments folder, so the user can add panoramas
     * without going near Android/data by hand. Returns the name it was stored under, or
     * null if it is not a format we can decode.
     */
    public static String importFrom(Context context, Uri uri) {
        String name = FileUtils.getUriFileName(context, uri);
        if (name == null || name.trim().isEmpty()) name = "panorama.jpg";
        // Whatever the picker reports is a display name, not a path, but it can still carry
        // characters that are not legal in a file name.
        name = name.trim().replaceAll("[\\/:*?\"<>|]", "_");
        if (!isSupported(name)) return null;

        File destination = uniqueFile(getDirectory(context), name);
        if (!FileUtils.copy(context, uri, destination)) {
            destination.delete();
            return null;
        }

        // A supported extension does not make it a decodable image; check before letting it
        // show up in the picker as a panorama that would silently fail to load.
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(destination.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            destination.delete();
            return null;
        }

        return destination.getName();
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
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(ENABLED_KEY, true);
    }

    public static void setEnabled(Context context, boolean enabled) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(ENABLED_KEY, enabled).apply();
        XrActivity activity = XrActivity.getInstance();
        if (activity != null) activity.nativeSetEnvironmentEnabled(enabled);
    }

    public static String getSelected(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        return preferences.getString(PREF_KEY, "");
    }

    public static void setSelected(Context context, String name) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(PREF_KEY, name == null ? "" : name).apply();
    }

    /**
     * Decodes the named panorama and hands it to the native layer. Decoding a 4K image takes
     * long enough to stutter the UI, so it happens on a worker thread; the native side only
     * copies the pixels and defers the actual upload to the render thread.
     */
    public static void apply(Context context, String name) {
        final XrActivity activity = XrActivity.getInstance();
        if (activity == null) return;
        if (!activity.nativeIsEnvironmentSupported()) return;

        if (name == null || name.isEmpty()) {
            activity.nativeSetEnvironment(null, 0, 0);
            return;
        }

        final File file = new File(getDirectory(context), name);
        new Thread(() -> {
            Bitmap bitmap = decode(file);
            if (bitmap == null) {
                Log.e(TAG, "Could not decode environment " + file);
                activity.nativeSetEnvironment(null, 0, 0);
                return;
            }

            // ARGB_8888 is laid out as R,G,B,A bytes in memory, which is what GL_RGBA wants.
            ByteBuffer buffer = ByteBuffer.allocate(bitmap.getWidth() * bitmap.getHeight() * 4);
            bitmap.copyPixelsToBuffer(buffer);
            activity.nativeSetEnvironment(buffer.array(), bitmap.getWidth(), bitmap.getHeight());
            bitmap.recycle();
        }, "XrEnvironmentLoader").start();
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
