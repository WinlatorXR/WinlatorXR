package com.winlator.cmod.contents;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Reads the "APK" entry in contents.json. Older app versions drop it because they don't know the type.
 * Releases are named by letter first (cats before dawn), then by number (dawn-33 before dawn-34).
 */
public final class ApkUpdate {
    public final String verName;
    public final String url;

    private ApkUpdate(String verName, String url) {
        this.verName = verName;
        this.url = url;
    }

    /** The listed release if it is newer than the installed version name, otherwise null. */
    public static ApkUpdate find(String json, String installedVersion) {
        if (json == null || installedVersion == null) return null;
        try {
            JSONArray content = new JSONArray(json);
            for (int i = 0; i < content.length(); i++) {
                JSONObject object = content.optJSONObject(i);
                if (object == null || !"APK".equalsIgnoreCase(object.optString("type"))) continue;
                String verName = object.getString("verName");
                if (compare(verName, installedVersion) > 0) return new ApkUpdate(verName, object.getString("remoteUrl"));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private static int compare(String a, String b) {
        a = a.trim().toLowerCase(Locale.ROOT);
        b = b.trim().toLowerCase(Locale.ROOT);
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.charAt(0) != b.charAt(0)) return Character.compare(a.charAt(0), b.charAt(0));
        return Long.compare(lastNumber(a), lastNumber(b));
    }

    private static long lastNumber(String s) {
        int end = s.length();
        while (end > 0 && !Character.isDigit(s.charAt(end - 1))) end--;
        int start = end;
        while (start > 0 && Character.isDigit(s.charAt(start - 1))) start--;
        if (start == end) return 0;
        return Long.parseLong(s.substring(Math.max(start, end - 18), end));
    }
}
