package com.winlator.cmod.contents;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Points Goldberg's ColdClientLoader.ini at the game it was unpacked beside.
 *
 * The loader stands in for a running Steam client, which is what a SteamStub-wrapped exe checks
 * for before any of the game runs -- replacing steam_api64.dll alone never gets that far. It reads
 * which program to start and the game's AppId from the ini next to it, and a mod packed once for
 * everyone cannot know either, so the blanks are filled here. Anything the mod's author did fill
 * in is left as they wrote it.
 */
public final class ColdClientLoaderIni {
    public static final String FILE_NAME = "ColdClientLoader.ini";

    private static final String SECTION = "[steamclient]";

    /** The file name in the placeholder path Goldberg's own sample ini ships with. */
    private static final String SAMPLE_EXE = "game.exe";

    private ColdClientLoaderIni() {}

    /**
     * Fills in Exe, ExeRunDir and AppId where they are blank. appId may be null when it is not
     * known. Returns the AppId the ini ends up with, or null when it still has none.
     */
    public static String fill(File ini, String exeWinPath, String exeDirWinPath, String appId) throws IOException {
        byte[] bytes = Files.readAllBytes(ini.toPath());
        boolean utf16 = bytes.length >= 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE;
        List<String> lines = new ArrayList<>(Arrays.asList(RegFileImport.readText(ini).split("\r?\n", -1)));

        int section = -1;
        int sectionEnd = lines.size();
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim().toLowerCase(Locale.ENGLISH);
            if (section == -1 && trimmed.equals(SECTION)) section = i;
            else if (section != -1 && trimmed.startsWith("[")) {
                sectionEnd = i;
                break;
            }
        }
        if (section == -1) {
            lines.add("[SteamClient]");
            section = lines.size() - 1;
            sectionEnd = lines.size();
        }

        String exe = valueOf(lines, section, sectionEnd, "Exe");
        if (exe == null || exe.isEmpty() || new File(exe.replace('\\', '/')).getName().equalsIgnoreCase(SAMPLE_EXE)) {
            sectionEnd = put(lines, section, sectionEnd, "Exe", exeWinPath);
            String runDir = valueOf(lines, section, sectionEnd, "ExeRunDir");
            if (runDir == null || runDir.isEmpty() || runDir.equals("."))
                sectionEnd = put(lines, section, sectionEnd, "ExeRunDir", exeDirWinPath);
        }

        String finalAppId = valueOf(lines, section, sectionEnd, "AppId");
        if (finalAppId == null || finalAppId.isEmpty()) {
            finalAppId = appId;
            if (appId != null) put(lines, section, sectionEnd, "AppId", appId);
        }

        String text = String.join("\r\n", lines);
        if (utf16) {
            byte[] body = text.getBytes(StandardCharsets.UTF_16LE);
            byte[] out = new byte[body.length + 2];
            out[0] = (byte) 0xFF;
            out[1] = (byte) 0xFE;
            System.arraycopy(body, 0, out, 2, body.length);
            Files.write(ini.toPath(), out);
        }
        else Files.write(ini.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return finalAppId;
    }

    private static int indexOf(List<String> lines, int section, int sectionEnd, String key) {
        for (int i = section + 1; i < sectionEnd; i++) {
            String line = lines.get(i);
            int eq = line.indexOf('=');
            if (eq != -1 && line.substring(0, eq).trim().equalsIgnoreCase(key)) return i;
        }
        return -1;
    }

    private static String valueOf(List<String> lines, int section, int sectionEnd, String key) {
        int i = indexOf(lines, section, sectionEnd, key);
        if (i == -1) return null;
        String line = lines.get(i);
        return line.substring(line.indexOf('=') + 1).trim();
    }

    /** Sets key in the section, adding it after the header when missing; returns the new end. */
    private static int put(List<String> lines, int section, int sectionEnd, String key, String value) {
        int i = indexOf(lines, section, sectionEnd, key);
        if (i != -1) {
            lines.set(i, key + "=" + value);
            return sectionEnd;
        }
        lines.add(section + 1, key + "=" + value);
        return sectionEnd + 1;
    }
}
