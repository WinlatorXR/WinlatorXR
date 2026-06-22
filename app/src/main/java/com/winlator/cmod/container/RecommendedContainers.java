package com.winlator.cmod.container;

import android.util.Log;

import com.winlator.cmod.contents.Downloader;
import com.winlator.xr.Device;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Describes the prebuilt "recommended" container images that new users can provision in one tap
 * (see {@link ContainerManager#setupRecommendedContainersAsync}). The list is fetched from a small
 * JSON manifest hosted alongside the content profiles, so images can be re-versioned without an app
 * update.
 *
 * Expected manifest format (JSON array):
 * <pre>
 * [
 *   {
 *     "name": "Quest3-x86",               // becomes the container name; also the dedup key
 *     "device": "quest3",                 // optional; see deviceKey(). Empty/absent = any device
 *     "wineVersion": "proton-9.0-x86_64", // informational
 *     "url": "https://.../Quest3-x86.tzst",
 *     "verCode": 1,
 *     "sizeBytes": 1234567890,             // optional; verifies the download isn't truncated
 *     "sha256": "abc123..."                // optional; verifies download integrity
 *   }
 * ]
 * </pre>
 *
 * Images are device-specific because each headset bakes a different graphics driver wrapper and
 * DXVK version into a container's config (see DefaultVersion). The current headset maps to a
 * {@code device} key via {@link #deviceKey()}; only images whose {@code device} is empty or matches
 * that key are provisioned.
 */
public class RecommendedContainers {
    private static final String TAG = "RecommendedContainers";

    public static final String REMOTE_RECOMMENDED =
            "https://raw.githubusercontent.com/WinlatorXR/Winlator-Contents/refs/heads/main/recommended.json";

    public static class Image {
        public String name;
        public String device;
        public String wineVersion;
        public String url;
        public String sha256;
        public int verCode;
        public long sizeBytes;

        /** True if this image should be installed on the device identified by {@code currentKey}. */
        public boolean appliesToDevice(String currentKey) {
            if (device == null || device.isEmpty()) return true; // universal image
            return device.equalsIgnoreCase(currentKey);
        }

        /**
         * Verifies a downloaded archive against the manifest's size/checksum, when provided.
         * If neither is specified the download is accepted as-is.
         */
        public boolean matchesDownload(File file) {
            if (file == null || !file.isFile()) return false;
            if (sizeBytes > 0 && file.length() != sizeBytes) {
                Log.e(TAG, "Size mismatch for " + name + ": expected " + sizeBytes + ", got " + file.length());
                return false;
            }
            if (sha256 != null && !sha256.isEmpty()) {
                String actual = sha256Of(file);
                if (actual == null || !actual.equalsIgnoreCase(sha256)) {
                    Log.e(TAG, "Checksum mismatch for " + name + ": expected " + sha256 + ", got " + actual);
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Maps the current headset to a manifest {@code device} key. Quest Pro shares Quest 2's images;
     * Quest 3 and 3S share the QUEST_3 mapping in {@link Device}. Returns "" for unrecognised
     * devices, which then only match universal (device-less) images.
     */
    public static String deviceKey() {
        switch (Device.getDevice()) {
            case QUEST_2:
            case QUEST_PRO:        return "quest2";
            case QUEST_3:          return "quest3";
            case PICO_NEO_3_LINK:  return "pico_neo3";
            case PICO_4:           return "pico4";
            case PICO_4_ULTRA:     return "pico4_ultra";
            default:               return "";
        }
    }

    /** Fetches and parses the remote manifest. Returns null on network/parse failure. */
    public static List<Image> fetchManifest() {
        String json = Downloader.downloadString(REMOTE_RECOMMENDED);
        if (json == null) {
            Log.e(TAG, "Failed to download recommended manifest");
            return null;
        }
        try {
            List<Image> images = new ArrayList<>();
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Image img = new Image();
                img.name = o.getString("name");
                img.url = o.getString("url");
                img.device = o.optString("device", "");
                img.wineVersion = o.optString("wineVersion", "");
                img.sha256 = o.optString("sha256", "");
                img.verCode = o.optInt("verCode", 0);
                img.sizeBytes = o.optLong("sizeBytes", 0);
                images.add(img);
            }
            return images;
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse recommended manifest", e);
            return null;
        }
    }

    private static String sha256Of(File file) {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Failed to compute sha256", e);
            return null;
        }
    }
}
