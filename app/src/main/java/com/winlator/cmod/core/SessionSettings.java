package com.winlator.cmod.core;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.container.Shortcut;

/**
 * Settings that belong to the game being played rather than to the app.
 *
 * The XR menu, motion controls and screen effects used to write straight into the app-wide
 * preferences, so turning passthrough off for one game turned it off for every game, and the
 * next launch of anything picked the change up. These settings now live in the launching
 * shortcut's Extra Data instead, and the app-wide preference is what a shortcut inherits
 * until it has an answer of its own: a read falls through to the global value, and only a
 * write made during a session pins the value to that one game.
 *
 * A container launched with no shortcut has nowhere to pin anything, so it reads and writes
 * the global preferences exactly as before. The same is true of the Settings screen, which
 * runs with no session and therefore edits the defaults every shortcut inherits.
 *
 * Writes are durable by the time they return. That matters here: a session ends by killing
 * its own process (XrRenderer autoclose, XrActivity.closeSession), which gives an
 * asynchronous SharedPreferences.apply() no chance to reach disk.
 */
public final class SessionSettings {
    /**
     * The shortcut that launched the running session, or null when there is none. Set once in
     * XServerDisplayActivity.onCreate; a session lives in its own process, so this cannot
     * outlive the game it belongs to.
     */
    private static volatile Shortcut shortcut;

    private SessionSettings() {}

    public static void setShortcut(Shortcut value) {
        shortcut = value;
    }

    public static Shortcut getShortcut() {
        return shortcut;
    }

    /** Whether settings changed right now would be pinned to a game rather than the app. */
    public static boolean isPerShortcut() {
        return shortcut != null;
    }

    /** Whether the game being played has an answer of its own for this key. */
    public static boolean isOverridden(String key) {
        Shortcut current = shortcut;
        if (current == null) return false;
        synchronized (current) {
            return current.hasExtra(key);
        }
    }

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    /**
     * The shortcut's answer for a key, or null when it has none and the global one applies.
     *
     * Locked on the shortcut because a session touches these from more than one thread: the
     * menus from the UI thread, the screen distance from the XR render thread when a grip is
     * released, and WinHandler's gyro load from its own thread. The JSONObject behind the
     * extras is not thread safe, and a write rewrites the .desktop file.
     */
    private static String override(String key) {
        Shortcut current = shortcut;
        if (current == null) return null;
        synchronized (current) {
            return current.hasExtra(key) ? current.getExtra(key) : null;
        }
    }

    public static boolean getBoolean(Context context, String key, boolean fallback) {
        String value = override(key);
        if (value == null) return prefs(context).getBoolean(key, fallback);
        return value.equals("1") || value.equalsIgnoreCase("true");
    }

    public static int getInt(Context context, String key, int fallback) {
        String value = override(key);
        if (value == null) return prefs(context).getInt(key, fallback);
        try {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static float getFloat(Context context, String key, float fallback) {
        String value = override(key);
        if (value == null) return prefs(context).getFloat(key, fallback);
        try {
            return Float.parseFloat(value);
        }
        catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static String getString(Context context, String key, String fallback) {
        String value = override(key);
        return value != null ? value : prefs(context).getString(key, fallback);
    }

    public static void putBoolean(Context context, String key, boolean value) {
        edit(context).putBoolean(key, value).apply();
    }

    public static void putInt(Context context, String key, int value) {
        edit(context).putInt(key, value).apply();
    }

    public static void putFloat(Context context, String key, float value) {
        edit(context).putFloat(key, value).apply();
    }

    public static void putString(Context context, String key, String value) {
        edit(context).putString(key, value).apply();
    }

    public static Editor edit(Context context) {
        return new Editor(context);
    }

    /**
     * Batches a group of changes into one write. Writing a shortcut rewrites its .desktop
     * file, so a dialog that saves a dozen values on OK should collect them here rather than
     * rewrite the file a dozen times.
     */
    public static final class Editor {
        private final Context context;
        // Pinned when the editor is created, so a batch cannot land half in a shortcut and
        // half in the global preferences if the session ends underneath it.
        private final Shortcut target = shortcut;
        private SharedPreferences.Editor prefsEditor;
        private boolean shortcutChanged;

        private Editor(Context context) {
            this.context = context;
        }

        private SharedPreferences.Editor prefsEditor() {
            if (prefsEditor == null) prefsEditor = prefs(context).edit();
            return prefsEditor;
        }

        public Editor putBoolean(String key, boolean value) {
            if (target != null) putExtra(key, value ? "1" : "0");
            else prefsEditor().putBoolean(key, value);
            return this;
        }

        public Editor putInt(String key, int value) {
            if (target != null) putExtra(key, String.valueOf(value));
            else prefsEditor().putInt(key, value);
            return this;
        }

        public Editor putFloat(String key, float value) {
            if (target != null) putExtra(key, String.valueOf(value));
            else prefsEditor().putFloat(key, value);
            return this;
        }

        public Editor putString(String key, String value) {
            if (target != null) putExtra(key, value != null ? value : "");
            else prefsEditor().putString(key, value);
            return this;
        }

        /** Drops a shortcut's own answer, so the key inherits the global value again. */
        public Editor remove(String key) {
            if (target != null) {
                synchronized (target) {
                    if (!target.hasExtra(key)) return this;
                    target.putExtra(key, null);
                }
                shortcutChanged = true;
            }
            else prefsEditor().remove(key);
            return this;
        }

        // Skipping an unchanged value keeps a no-op from rewriting the file, which matters
        // for the screen distance: it is saved on every grip release, most of which move
        // nothing, and that call arrives on the render thread.
        private void putExtra(String key, String value) {
            synchronized (target) {
                if (target.hasExtra(key) && target.getExtra(key).equals(value)) return;
                target.putExtra(key, value);
            }
            shortcutChanged = true;
        }

        /** Writes everything collected so far. Synchronous, so a hard exit cannot lose it. */
        public void apply() {
            if (shortcutChanged) {
                synchronized (target) {
                    target.saveData();
                }
                shortcutChanged = false;
            }
            if (prefsEditor != null) {
                prefsEditor.commit();
                prefsEditor = null;
            }
        }
    }
}
