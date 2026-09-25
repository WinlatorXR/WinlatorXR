package com.winlator.cmod.contents;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.WineRegistryEditor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Merges a Windows .reg file into a container's registry, the way regedit would, without starting
 * Wine to do it.
 *
 * A mod carries one when a game needs keys the real installer would have written -- Bethesda's
 * games read their own folder from HKLM\SOFTWARE\Wow6432Node\Bethesda Softworks\&lt;game&gt;, and a
 * game copied in from a PC has none of that. Wine keeps HKEY_LOCAL_MACHINE in system.reg and
 * HKEY_CURRENT_USER in user.reg, so each key is written to whichever of the two holds it.
 *
 * Where a game sits is only known once it is on the device, so {@link #GAME_DIR_TOKEN} in a string
 * value is replaced with the game's folder, written the way Windows sees it.
 */
public final class RegFileImport {
    /** Stands for the game's folder, with no backslash after it, inside a string value. */
    public static final String GAME_DIR_TOKEN = "%WXR_GAME_DIR%";

    public static class Result {
        /** Null when the file was read; otherwise why nothing in it was applied. */
        public String error;
        public boolean notRegFile;
        public int applied;
        public int skipped;
    }

    private RegFileImport() {}

    public static Result importFile(Container container, File regFile, String gameDir) {
        Result result = new Result();
        String text;
        try {
            text = readText(regFile);
        }
        catch (IOException e) {
            result.error = e.getMessage() != null ? e.getMessage() : e.toString();
            return result;
        }

        File wineDir = new File(container.getRootDir(), ".wine");
        Map<String, WineRegistryEditor> editors = new HashMap<>();
        try {
            apply(text, wineDir, editors, gameDir, result);
        }
        finally {
            for (WineRegistryEditor editor : editors.values()) editor.close();
        }
        return result;
    }

    private static void apply(String text, File wineDir, Map<String, WineRegistryEditor> editors,
                              String gameDir, Result result) {
        String[] lines = text.split("\r?\n");
        boolean headerSeen = false;
        WineRegistryEditor editor = null;
        String key = null;
        StringBuilder pending = null;

        for (String raw : lines) {
            String line = raw.trim();
            if (pending == null) {
                if (line.isEmpty() || line.startsWith(";")) continue;
                pending = new StringBuilder(line);
            }
            else pending.append(line);

            // Only hex data is ever continued onto the next line; a string ending in a backslash
            // is a folder, and joining the next line onto it would eat a whole entry.
            if (pending.charAt(pending.length() - 1) == '\\' && isHexValue(pending.toString())) {
                pending.setLength(pending.length() - 1);
                continue;
            }
            String statement = pending.toString();
            pending = null;

            if (!headerSeen) {
                if (!statement.equals("Windows Registry Editor Version 5.00") && !statement.equals("REGEDIT4")) {
                    result.notRegFile = true;
                    return;
                }
                headerSeen = true;
                continue;
            }

            if (statement.startsWith("[") && statement.endsWith("]")) {
                boolean delete = statement.startsWith("[-");
                String[] target = mapKey(statement.substring(delete ? 2 : 1, statement.length() - 1));
                editor = null;
                key = null;
                if (target == null) {
                    result.skipped++;
                    continue;
                }
                editor = editors.get(target[0]);
                if (editor == null) {
                    editor = new WineRegistryEditor(new File(wineDir, target[0]));
                    editors.put(target[0], editor);
                }
                if (delete) {
                    editor.removeKey(target[1]);
                    editor.removeKey(target[1] + "\\", true);
                    result.applied++;
                    editor = null;
                }
                else key = target[1];
                continue;
            }

            if (editor == null || !applyValue(editor, key, statement, gameDir)) result.skipped++;
            else result.applied++;
        }

        if (!headerSeen) result.notRegFile = true;
    }

    /** Writes one name=data line, or returns false for a line it cannot make sense of. */
    private static boolean applyValue(WineRegistryEditor editor, String key, String statement, String gameDir) {
        String name;
        int pos;
        if (statement.startsWith("@")) {
            name = null;
            pos = 1;
        }
        else if (statement.startsWith("\"")) {
            int[] end = new int[1];
            name = readQuoted(statement, 0, end);
            if (name == null) return false;
            pos = end[0];
        }
        else return false;

        String rest = statement.substring(pos).trim();
        if (!rest.startsWith("=")) return false;
        String data = rest.substring(1).trim();

        if (data.equals("-")) {
            editor.removeValue(key, name);
            return true;
        }

        if (data.startsWith("\"")) {
            int[] end = new int[1];
            String value = readQuoted(data, 0, end);
            if (value == null) return false;
            editor.setStringValue(key, name, value.replace(GAME_DIR_TOKEN, gameDir));
            return true;
        }

        String lower = data.toLowerCase(Locale.ENGLISH);
        if (lower.startsWith("dword:")) {
            try {
                editor.setDwordValue(key, name, Integer.parseUnsignedInt(lower.substring(6).trim(), 16));
                return true;
            }
            catch (NumberFormatException e) {
                return false;
            }
        }

        // system.reg and user.reg store binary data exactly as a .reg file writes it, so it goes
        // in untouched apart from the line breaks.
        String hex = lower.replaceAll("[\\s\\\\]", "");
        if (hex.matches("hex(\\([0-9a-f]+\\))?:[0-9a-f,]*")) {
            editor.setRawValue(key, name, hex);
            return true;
        }
        return false;
    }

    private static boolean isHexValue(String statement) {
        int eq = statement.indexOf("=hex");
        if (eq == -1) eq = statement.indexOf("= hex");
        return eq != -1;
    }

    /** A quoted .reg string starting at start, unescaped, with end[0] set just past it. */
    private static String readQuoted(String s, int start, int[] end) {
        StringBuilder out = new StringBuilder();
        for (int i = start + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                out.append(s.charAt(++i));
            }
            else if (c == '"') {
                end[0] = i + 1;
                return out.toString();
            }
            else out.append(c);
        }
        return null;
    }

    /** {file, key} for a full .reg key path, or null for a hive Wine keeps elsewhere. */
    private static String[] mapKey(String path) {
        int slash = path.indexOf('\\');
        if (slash == -1) return null;
        String hive = path.substring(0, slash).toUpperCase(Locale.ENGLISH);
        String subKey = path.substring(slash + 1);
        if (subKey.isEmpty()) return null;

        switch (hive) {
            case "HKEY_LOCAL_MACHINE":
            case "HKLM":
                return new String[]{"system.reg", subKey};
            case "HKEY_CLASSES_ROOT":
            case "HKCR":
                return new String[]{"system.reg", "Software\\Classes\\" + subKey};
            case "HKEY_CURRENT_USER":
            case "HKCU":
                return new String[]{"user.reg", subKey};
            default:
                return null;
        }
    }

    /** A text file as regedit and the ini functions write it: UTF-16 with a BOM, or 8-bit. */
    static String readText(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        if (bytes.length >= 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE)
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF)
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
