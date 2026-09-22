package com.winlator.cmod.core;

import android.content.Context;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.container.Shortcut;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Keeps the few guest output lines that explain why a game did not start or crashed, and
 * what the session looked like, so the shortcut menu can summarise its last launch.
 *
 * A session ends by killing its own process on most paths, so the report is rewritten on
 * disk whenever something in it changes rather than once at the end.
 */
public class LaunchReport implements Callback<String> {
    private static final String TAG = "LaunchReport";
    private static final int MAX_LINES = 20;
    private static final int MAX_LINE_LENGTH = 300;

    private static LaunchReport current;

    private final File file;
    private final long startTime = System.currentTimeMillis();
    private final List<String> lines = new ArrayList<>();
    private boolean windowShown;
    private long duration = -1;

    private LaunchReport(File file) {
        this.file = file;
    }

    /** Off by default: reading the guest's output costs something on every launch. */
    public static boolean isEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean("enable_launch_report", false);
    }

    public static File getFile(Context context, Shortcut shortcut) {
        String key = Integer.toHexString(shortcut.file.getAbsolutePath().hashCode());
        return new File(context.getFilesDir(), "launch_reports/" + key + ".json");
    }

    public static boolean exists(Context context, Shortcut shortcut) {
        return getFile(context, shortcut).isFile();
    }

    /** Starts a fresh report for this session, replacing the shortcut's previous one. */
    public static LaunchReport start(Context context, Shortcut shortcut) {
        File file = getFile(context, shortcut);
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        current = new LaunchReport(file);
        current.save();
        return current;
    }

    public static void onWindowShown() {
        LaunchReport report = current;
        if (report == null) return;
        synchronized (report) {
            if (report.windowShown) return;
            report.windowShown = true;
            report.save();
        }
    }

    public static void onSessionEnd() {
        LaunchReport report = current;
        if (report == null) return;
        synchronized (report) {
            if (report.duration >= 0) return;
            report.duration = System.currentTimeMillis() - report.startTime;
            report.save();
        }
    }

    private static boolean isRelevant(String line) {
        return line.contains("err:module:")
                || line.contains("err:mscoree:")
                || line.startsWith("wine: ")
                || isDxvkError(line)
                || line.contains("[S_API FAIL]")
                || line.contains("Unimplemented Opcode");
    }

    // DXVK pads its level, "err:   message"; Wine names a channel, "err:module:...".
    private static boolean isDxvkError(String line) {
        return line.startsWith("err:  ");
    }

    @Override
    public void call(String line) {
        if (line == null || !isRelevant(line)) return;
        // Wine prefixes its own lines with the thread id, which would defeat the dedup below.
        line = line.replaceFirst("^[0-9a-f.]{4,}:", "");
        if (line.length() > MAX_LINE_LENGTH) line = line.substring(0, MAX_LINE_LENGTH);
        synchronized (this) {
            if (lines.size() >= MAX_LINES || lines.contains(line)) return;
            lines.add(line);
            save();
        }
    }

    private void save() {
        try {
            JSONObject json = new JSONObject();
            json.put("startTime", startTime);
            json.put("windowShown", windowShown);
            json.put("duration", duration);
            json.put("lines", new JSONArray(lines));
            File tmp = new File(file.getPath() + ".tmp");
            FileUtils.writeString(tmp, json.toString());
            tmp.renameTo(file);
        }
        catch (Exception e) {
            Log.e(TAG, "Failed to save launch report", e);
        }
    }

    /** What the shortcut menu shows: the session's facts, then each cause found in its output. */
    public static class Summary {
        public long startTime;
        public boolean windowShown;
        public long duration;
        public final List<String> lines = new ArrayList<>();
        public boolean steam, redist, dotnet, render, crash, emulator, missingExe;
        public final List<String> missingDlls = new ArrayList<>();

        public String formatDuration() {
            long seconds = duration / 1000;
            return seconds >= 60
                    ? String.format(Locale.ENGLISH, "%dm %02ds", seconds / 60, seconds % 60)
                    : seconds + "s";
        }
    }

    public static Summary read(Context context, Shortcut shortcut) {
        try {
            JSONObject json = new JSONObject(FileUtils.readString(getFile(context, shortcut)));
            Summary summary = new Summary();
            summary.startTime = json.optLong("startTime");
            summary.windowShown = json.optBoolean("windowShown");
            summary.duration = json.optLong("duration", -1);
            JSONArray lines = json.optJSONArray("lines");
            if (lines != null) for (int i = 0; i < lines.length(); i++) summary.lines.add(lines.getString(i));
            for (String line : summary.lines) classify(summary, line);
            return summary;
        }
        catch (Exception e) {
            Log.e(TAG, "Failed to read launch report", e);
            return null;
        }
    }

    private static void classify(Summary summary, String line) {
        if (line.contains("[S_API FAIL]")) summary.steam = true;
        if (line.contains("err:mscoree:")) summary.dotnet = true;
        if (isDxvkError(line)) summary.render = true;
        if (line.startsWith("wine: Unhandled")) summary.crash = true;
        if (line.contains("Unimplemented Opcode")) summary.emulator = true;
        if (line.startsWith("wine: failed to open") || line.startsWith("wine: cannot find")) summary.missingExe = true;

        // err:module:import_dll Library MSVCP140.dll (which is needed by L"...") not found
        int at = line.indexOf("Library ");
        if (line.contains("err:module:") && at >= 0 && line.contains("not found")) {
            int end = line.indexOf(' ', at + 8);
            String dll = end > 0 ? line.substring(at + 8, end) : line.substring(at + 8);
            if (!summary.missingDlls.contains(dll)) summary.missingDlls.add(dll);
            String name = dll.toLowerCase(Locale.ENGLISH);
            if (name.startsWith("steam_api")) summary.steam = true;
            else if (name.startsWith("mscoree")) summary.dotnet = true;
            else summary.redist = true;
        }
    }
}
