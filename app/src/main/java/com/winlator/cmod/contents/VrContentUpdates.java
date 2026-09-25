package com.winlator.cmod.contents;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finds contents.json entries newer than the installed Proton WXR, OXRWXR and OpenComposite,
 * which otherwise sit in the Downloader list with nothing saying they are updates.
 */
public final class VrContentUpdates {
    private VrContentUpdates() {}

    /** The newest remote entry of each family that beats what is installed. The manager must have its remote profiles set. */
    public static List<ContentProfile> find(Context context, ContentsManager manager) {
        List<ContentProfile> updates = new ArrayList<>();
        ContentProfile proton = newest(context, manager, ContentProfile.ContentType.CONTENT_TYPE_PROTON, true);
        ContentProfile protonUpdate = newest(context, manager, ContentProfile.ContentType.CONTENT_TYPE_PROTON, false);
        if (proton != null && isNewer(protonUpdate, proton)) updates.add(protonUpdate);

        // Without Proton WXR there is no PC VR, so the runtimes only matter once it is installed.
        // With no runtime WCP installed the bundled one is used, which any listed WCP replaces.
        if (proton == null) return updates;
        for (ContentProfile.ContentType type : new ContentProfile.ContentType[]{ContentProfile.ContentType.CONTENT_TYPE_OXRWXR, ContentProfile.ContentType.CONTENT_TYPE_OPENCOMPOSITE}) {
            ContentProfile installed = newest(context, manager, type, true);
            ContentProfile remote = newest(context, manager, type, false);
            if (remote != null && (installed == null || isNewer(remote, installed))) updates.add(remote);
        }
        return updates;
    }

    // Newest installed (or newest listed but not installed) entry of a family
    private static ContentProfile newest(Context context, ContentsManager manager, ContentProfile.ContentType type, boolean installed) {
        ContentProfile newest = null;
        List<ContentProfile> profiles = manager.getProfiles(type);
        if (profiles == null) return null;
        for (ContentProfile profile : profiles) {
            // An installed WCP keeps its own profile.json name, which may not say wxr, so the bridge it carries counts too
            if (type == ContentProfile.ContentType.CONTENT_TYPE_PROTON && !profile.verName.toLowerCase(Locale.ROOT).contains("wxr")
                    && !(installed && new File(ContentsManager.getInstallDir(context, profile), "lib/wine/aarch64-unix/wxr_bridge.so").isFile())) continue;
            boolean matches = installed
                    ? profile.remoteUrl == null && ContentsManager.getInstallDir(context, profile).isDirectory()
                    : profile.remoteUrl != null;
            if (!matches) continue;
            if (newest == null || isNewer(profile, newest)) newest = profile;
        }
        return newest;
    }

    // Version code first, then the numbers in the version name (11.0-3 beats 11.0-2)
    private static boolean isNewer(ContentProfile a, ContentProfile b) {
        if (a == null) return false;
        if (a.verCode != b.verCode) return a.verCode > b.verCode;
        return compareNames(a.verName, b.verName) > 0;
    }

    private static int compareNames(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                long na = Long.parseLong(a.substring(si, Math.min(i, si + 18)));
                long nb = Long.parseLong(b.substring(sj, Math.min(j, sj + 18)));
                if (na != nb) return Long.compare(na, nb);
            } else {
                if (ca != cb) return 0;
                i++;
                j++;
            }
        }
        return 0;
    }
}
