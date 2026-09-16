package com.winlator.cmod.contents;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;

import com.winlator.cmod.core.EvshimPatcher;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.core.ZipExtractor;
import com.winlator.cmod.xenvironment.ImageFs;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class ContentsManager {
    public static final String PROFILE_NAME = "profile.json";
    /** Suffix of the file that records where a locally added installer lives. */
    public static final String INSTALLER_REFERENCE_SUFFIX = ".installerref";
    /** The same, for a runtime installer that has to stay in the folder it was unpacked into. */
    public static final String RUNTIME_REFERENCE_SUFFIX = ".runtimeref";
    /** The same, for a mod archive, which is left wherever the user downloaded it to. */
    public static final String MOD_REFERENCE_SUFFIX = ".modref";
    public static final String REMOTE_PROFILES = "https://raw.githubusercontent.com/WinlatorXR/Winlator-Contents/refs/heads/main/contents.json";
    public static final String[] DXVK_TRUST_FILES = {"${system32}/d3d8.dll", "${system32}/d3d9.dll", "${system32}/d3d10.dll", "${system32}/d3d10_1.dll",
            "${system32}/d3d10core.dll", "${system32}/d3d11.dll", "${system32}/dxgi.dll", "${syswow64}/d3d8.dll", "${syswow64}/d3d9.dll", "${syswow64}/d3d10.dll",
            "${syswow64}/d3d10_1.dll", "${syswow64}/d3d10core.dll", "${syswow64}/d3d11.dll", "${syswow64}/dxgi.dll"};
    public static final String[] VKD3D_TRUST_FILES = {"${system32}/d3d12core.dll", "${system32}/d3d12.dll",
            "${syswow64}/d3d12core.dll", "${syswow64}/d3d12.dll"};
    public static final String[] BOX64_TRUST_FILES = {"${bindir}/box64"};
    public static final String[] WOWBOX64_TRUST_FILES = {"${system32}/wowbox64.dll"};
    public static final String[] FEXCORE_TRUST_FILES = {"${system32}/libwow64fex.dll", "${system32}/libarm64ecfex.dll"};
    private Map<String, String> dirTemplateMap;
    private Map<ContentProfile.ContentType, List<String>> trustedFilesMap;

    private SharedPreferences preferences;

    public enum InstallFailedReason {
        ERROR_NOSPACE,
        ERROR_BADTAR,
        ERROR_NOPROFILE,
        ERROR_BADPROFILE,
        ERROR_MISSINGFILES,
        ERROR_EXIST,
        ERROR_UNTRUSTPROFILE,
        ERROR_UNKNOWN
    }

    public enum ContentDirName {
        CONTENT_MAIN_DIR_NAME("contents"),
        CONTENT_WINE_DIR_NAME("wine"),
        CONTENT_PROTON_DIR_NAME("proton"),
        CONTENT_DXVK_DIR_NAME("dxvk"),
        CONTENT_VKD3D_DIR_NAME("vkd3d"),
        CONTENT_BOX64_DIR_NAME("box64");

        private String name;

        ContentDirName(String name) {
            this.name = name;
        }

        @NonNull
        @Override
        public String toString() {
            return name;
        }
    }

    private final Context context;

    private HashMap<ContentProfile.ContentType, List<ContentProfile>> profilesMap;

    private ArrayList<ContentProfile> remoteProfiles;

    public ContentsManager(Context context) {
        this.context = context;
        this.preferences = context.getSharedPreferences("contents_manager_prefs", Context.MODE_PRIVATE);
    }

    // Method to mark the graphics driver as installed
    public void setGraphicsDriverInstalled(String driverVersion, boolean installed) {
        preferences.edit().putBoolean("graphics_driver_installed_" + driverVersion, installed).apply();
    }

    public interface OnInstallFinishedCallback {
        void onFailed(InstallFailedReason reason, Exception e);

        void onSucceed(ContentProfile profile);
    }

    /**
     * A field that an entry does not have to carry, as null rather than as an empty string.
     *
     * optString answers "" for a missing key and the literal "null" for a JSON null, and neither
     * is a value: an entry that says nothing about its game has to be told apart from one that
     * names a game, not given a blank one.
     */
    private static String optionalString(JSONObject object, String key) {
        if (object.isNull(key)) return null;
        String value = object.optString(key, "").trim();
        return value.isEmpty() ? null : value;
    }

    public void setRemoteProfiles(String json) {
        try {
            remoteProfiles = new ArrayList<>();
            JSONArray content = new JSONArray(json);
            for (int i = 0; i < content.length(); i++) {
                try {
                    JSONObject object = content.getJSONObject(i);
                    ContentProfile remoteProfile = new ContentProfile();
                    remoteProfile.remoteUrl = object.getString("remoteUrl");
                    remoteProfile.type = ContentProfile.ContentType.getTypeByName(object.getString("type"));
                    remoteProfile.verName = object.getString("verName");
                    remoteProfile.verCode = object.getInt("verCode");
                    remoteProfile.game = optionalString(object, ContentProfile.MARK_GAME);
                    remoteProfiles.add(remoteProfile);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        syncContents();
    }

    public void syncContents() {
        profilesMap = new HashMap<>();

        // Ensure all content types are initialized in the profilesMap
        for (ContentProfile.ContentType type : ContentProfile.ContentType.values()) {
            profilesMap.put(type, new LinkedList<>());
        }

        for (ContentProfile.ContentType type : ContentProfile.ContentType.values()) {
            List<ContentProfile> profiles = profilesMap.get(type);


            // Load local profiles
            File typeFile = getContentTypeDir(context, type);
            File[] fileList = typeFile.listFiles();
            if (fileList != null) {
                for (File file : fileList) {
                    File proFile = new File(file, PROFILE_NAME);
                    if (proFile.exists() && proFile.isFile()) {
                        ContentProfile profile = readProfile(proFile);
                        if (profile != null) {
                            profiles.add(profile);
                            Log.d("ContentsManager", "Local profile loaded: " + profile.verName);
                        } else {
                            Log.w("ContentsManager", "Invalid local profile at: " + proFile.getAbsolutePath());
                        }
                    }
                }
            }

            // Add remote profiles for this type
            if (remoteProfiles != null) {
                for (ContentProfile remote : remoteProfiles) {
                    if (remote.type == type) {
                        boolean exists = false;
                        for (ContentProfile profile : profiles) {
                            if (profile.verName.equals(remote.verName) && profile.verCode == remote.verCode) {
                                exists = true;
                                break;
                            }
                        }
                        if (!exists) {
                            profiles.add(remote);
                            Log.d("ContentsManager", "Remote profile added: " + remote.verName);
                        }
                    }
                }
            }
        }

        syncLocalRuntimes();
        syncLocalInstallers();
        syncLocalMods();
    }

    /**
     * Surfaces installers the user dropped into the runtimes directory themselves, so a runtime
     * does not have to be listed in contents.json to be installable into a container.
     *
     * As with the installers below, an entry is either the file itself or a reference file naming
     * where it sits: a runtime that came out of a .zip is left in the folder it was unpacked into,
     * since it may well need what was unpacked beside it.
     */
    private void syncLocalRuntimes() {
        List<ContentProfile> runtimes = profilesMap.get(ContentProfile.ContentType.CONTENT_TYPE_RUNTIME);
        if (runtimes == null) return;

        File[] files = getRuntimesDir(context).listFiles(File::isFile);
        if (files == null) return;

        for (File file : files) {
            File target = file;
            String referencePath = null;

            if (file.getName().endsWith(RUNTIME_REFERENCE_SUFFIX)) {
                byte[] content = FileUtils.read(file);
                if (content == null) continue;
                referencePath = new String(content, StandardCharsets.UTF_8).trim();

                // A reference whose file has since been moved or deleted is skipped rather than
                // cleaned up, so putting the file back brings its entry back with it.
                target = new File(referencePath);
                if (!target.isFile()) {
                    Log.d("ContentsManager", "Runtime reference points at a missing file: " + referencePath);
                    continue;
                }
            }

            // Anything else in the folder is not something a container can be asked to run: an
            // archive still waiting to be unpacked, or a reference file belonging to an entry.
            if (!isInstaller(target.getName())) continue;

            boolean known = false;
            for (ContentProfile profile : runtimes) {
                if (getRuntimeFile(context, profile).equals(target)) {
                    known = true;
                    break;
                }
            }
            if (known) continue;

            ContentProfile profile = new ContentProfile();
            profile.type = ContentProfile.ContentType.CONTENT_TYPE_RUNTIME;
            profile.verName = FileUtils.getBasename(target.getName());
            profile.verCode = 0;
            profile.localFileName = target.getName();
            profile.localFilePath = referencePath;
            runtimes.add(profile);
            Log.d("ContentsManager", "Local runtime found: " + target.getAbsolutePath());
        }
    }

    /**
     * Surfaces the demos and offline installers the installers directory knows about, so one the
     * user added from local storage does not have to be listed in contents.json to appear.
     *
     * Two kinds live there. A downloaded one is the file itself. One the user added from local
     * storage is only a reference file naming where it sits in the Download folder: an offline
     * installer that keeps its payload in separate data files, as GOG's do, has to run beside
     * them, and copying a multi-gigabyte set in to achieve that would only duplicate it.
     */
    private void syncLocalInstallers() {
        List<ContentProfile> installers = profilesMap.get(ContentProfile.ContentType.CONTENT_TYPE_INSTALLER);
        if (installers == null) return;

        File[] files = getInstallersDir(context).listFiles(File::isFile);
        if (files == null) return;
        // A reference file is named after the path it holds, so the listing is put in order by
        // what the user sees rather than by what the directory happens to hold.
        List<ContentProfile> found = new ArrayList<>();

        for (File file : files) {
            File target = file;
            String referencePath = null;

            if (file.getName().endsWith(INSTALLER_REFERENCE_SUFFIX)) {
                byte[] content = FileUtils.read(file);
                if (content == null) continue;
                referencePath = new String(content, StandardCharsets.UTF_8).trim();

                // A reference whose file has since been moved or deleted is skipped rather than
                // cleaned up, so putting the file back brings its entry back with it.
                target = new File(referencePath);
                if (!target.isFile()) {
                    Log.d("ContentsManager", "Installer reference points at a missing file: " + referencePath);
                    continue;
                }
            }
            if (!isInstaller(target.getName())) continue;

            // A downloaded entry already owns its file, so it must not be listed a second time.
            boolean known = false;
            for (ContentProfile profile : installers) {
                if (getInstallerFile(context, profile).equals(target)) {
                    known = true;
                    break;
                }
            }
            if (known) continue;

            ContentProfile profile = new ContentProfile();
            profile.type = ContentProfile.ContentType.CONTENT_TYPE_INSTALLER;
            profile.verName = FileUtils.getBasename(target.getName());
            profile.verCode = 0;
            profile.localFileName = target.getName();
            profile.localFilePath = referencePath;
            found.add(profile);
            Log.d("ContentsManager", "Local installer found: " + target.getAbsolutePath());
        }

        found.sort((a, b) -> a.localFileName.compareToIgnoreCase(b.localFileName));
        installers.addAll(found);
    }

    /**
     * Surfaces the mod archives the mods directory knows about, so one the user added from local
     * storage is listed beside the ones contents.json offers.
     *
     * A mod is never unpacked here -- it is unpacked into a game, once the user says which game --
     * so an entry is the archive itself, either downloaded into the mods directory or referenced
     * where the user keeps it. Referencing rather than copying matters more here than it does for
     * installers: a mod is downloaded for one game and used once, and copying it in would leave a
     * second multi-gigabyte copy behind for nothing.
     */
    private void syncLocalMods() {
        List<ContentProfile> mods = profilesMap.get(ContentProfile.ContentType.CONTENT_TYPE_MOD);
        if (mods == null) return;

        File[] files = getModsDir(context).listFiles(File::isFile);
        if (files == null) return;

        List<ContentProfile> found = new ArrayList<>();

        for (File file : files) {
            File target = file;
            String referencePath = null;

            if (file.getName().endsWith(MOD_REFERENCE_SUFFIX)) {
                byte[] content = FileUtils.read(file);
                if (content == null) continue;
                referencePath = new String(content, StandardCharsets.UTF_8).trim();

                // A reference whose file has since been moved or deleted is skipped rather than
                // cleaned up, so putting the file back brings its entry back with it.
                target = new File(referencePath);
                if (!target.isFile()) {
                    Log.d("ContentsManager", "Mod reference points at a missing file: " + referencePath);
                    continue;
                }
            }
            if (!isMod(target.getName())) continue;

            // A downloaded entry already owns its file, so it must not be listed a second time.
            boolean known = false;
            for (ContentProfile profile : mods) {
                if (getModFile(context, profile).equals(target)) {
                    known = true;
                    break;
                }
            }
            if (known) continue;

            ContentProfile profile = new ContentProfile();
            profile.type = ContentProfile.ContentType.CONTENT_TYPE_MOD;
            profile.verName = FileUtils.getBasename(target.getName());
            profile.verCode = 0;
            profile.localFileName = target.getName();
            profile.localFilePath = referencePath;
            found.add(profile);
            Log.d("ContentsManager", "Local mod found: " + target.getAbsolutePath());
        }

        found.sort((a, b) -> a.localFileName.compareToIgnoreCase(b.localFileName));
        mods.addAll(found);
    }

    /**
     * Records a demo or offline installer held elsewhere on the device as an entry, by writing a
     * reference file naming it into the installers directory. The file itself stays where it is.
     */
    public static boolean addInstallerReference(Context context, File installer) {
        String path = installer.getAbsolutePath();
        return FileUtils.writeString(referenceFile(context, ContentProfile.ContentType.CONTENT_TYPE_INSTALLER, path), path);
    }

    /**
     * The same for a runtime installer, which is what a .zip on the Runtime tab leaves behind: it
     * has to be run from the folder it was unpacked into, so it is referenced rather than copied.
     */
    public static boolean addRuntimeReference(Context context, File runtime) {
        String path = runtime.getAbsolutePath();
        return FileUtils.writeString(referenceFile(context, ContentProfile.ContentType.CONTENT_TYPE_RUNTIME, path), path);
    }

    /** The same for a mod archive, which stays wherever the user downloaded it to. */
    public static boolean addModReference(Context context, File mod) {
        String path = mod.getAbsolutePath();
        return FileUtils.writeString(referenceFile(context, ContentProfile.ContentType.CONTENT_TYPE_MOD, path), path);
    }

    /**
     * The reference file behind a locally added entry, which is all that removing it takes: the
     * installer belongs to wherever the user keeps it, not to this app.
     */
    public static File getReferenceFile(Context context, ContentProfile profile) {
        return referenceFile(context, profile.type, profile.localFilePath);
    }

    /**
     * A reference file is named after the path it holds rather than after the installer, so two
     * same-named installers in different folders each keep their own entry, and adding the same
     * one twice replaces its reference instead of listing it again.
     */
    private static File referenceFile(Context context, ContentProfile.ContentType type, String path) {
        StringBuilder name = new StringBuilder();
        try {
            for (byte b : MessageDigest.getInstance("MD5").digest(path.getBytes(StandardCharsets.UTF_8)))
                name.append(String.format("%02x", b));
        }
        catch (NoSuchAlgorithmException e) {
            name.append(Integer.toHexString(path.hashCode()));
        }

        switch (type) {
            case CONTENT_TYPE_RUNTIME: return new File(getRuntimesDir(context), name + RUNTIME_REFERENCE_SUFFIX);
            case CONTENT_TYPE_MOD: return new File(getModsDir(context), name + MOD_REFERENCE_SUFFIX);
            default: return new File(getInstallersDir(context), name + INSTALLER_REFERENCE_SUFFIX);
        }
    }

    /** Whether a file is something a container can be asked to run, rather than data beside it. */
    public static boolean isInstaller(String name) {
        String lower = name.toLowerCase(Locale.ENGLISH);
        return lower.endsWith(".exe") || lower.endsWith(".msi");
    }

    /**
     * Whether a file is something that can be unpacked into a game.
     *
     * Only .zip, which is the whole of what {@link com.winlator.cmod.core.ModInstaller} can read:
     * a .rar or a .7z listed here would offer an install that cannot be carried out.
     */
    public static boolean isMod(String name) {
        return ZipExtractor.isZip(name);
    }

    public static File getRuntimesDir(Context context) {
        File dir = new File(ImageFs.find(context).getRootDir(), "runtimes");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    public static File getInstallersDir(Context context) {
        File dir = new File(ImageFs.find(context).getRootDir(), "installers");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    public static File getModsDir(Context context) {
        File dir = new File(ImageFs.find(context).getRootDir(), "mods");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    /**
     * The on-disk file for a demo or offline installer: where it was downloaded to, or where the
     * user keeps it if the entry is a reference to a file of their own.
     */
    public static File getInstallerFile(Context context, ContentProfile profile) {
        if (profile.localFilePath != null) return new File(profile.localFilePath);
        return new File(getInstallersDir(context), localFileName(profile));
    }

    /**
     * The on-disk installer for a Runtime profile: where it was downloaded to, where the user
     * dropped it, or where it was unpacked to if the entry is a reference to a file of their own.
     */
    public static File getRuntimeFile(Context context, ContentProfile profile) {
        if (profile.localFilePath != null) return new File(profile.localFilePath);
        return new File(getRuntimesDir(context), localFileName(profile));
    }

    /**
     * The on-disk archive for a Mod profile: where it was downloaded to, or where the user keeps
     * it if the entry is a reference to a file of their own.
     */
    public static File getModFile(Context context, ContentProfile profile) {
        if (profile.localFilePath != null) return new File(profile.localFilePath);
        return new File(getModsDir(context), localFileName(profile));
    }

    /**
     * The name an entry has on disk. A file the user added carries its own; one that came from
     * contents.json is named after the profile, with the extension taken from its URL.
     */
    private static String localFileName(ContentProfile profile) {
        String name = profile.localFileName;
        if (name == null) name = profile.verName + extensionOf(profile.remoteUrl);
        return name;
    }

    /**
     * The extension a download keeps, taken from the last path segment of its URL.
     *
     * It has to be that segment rather than the whole URL, because a URL has dots that are not
     * extensions: a link ending in a bare name would otherwise be read as having one -- everything
     * from the dot in "github.com" onwards -- and a link carrying a query string would take that
     * with it. What a downloaded file ends up called decides how it is treated afterwards, .zip
     * above all, so a wrong answer here is not cosmetic.
     *
     * @return the extension including its dot, or "" when the URL names none worth having
     */
    private static String extensionOf(String url) {
        if (url == null) return "";

        // Anything from a ? or # on belongs to the request, not to the file.
        int end = url.length();
        int query = url.indexOf('?');
        int fragment = url.indexOf('#');
        if (query >= 0) end = Math.min(end, query);
        if (fragment >= 0) end = Math.min(end, fragment);

        // A URL with no path at all names no file, and the dots it does have are the host's.
        int schemeEnd = url.indexOf("://");
        if (schemeEnd >= 0) {
            int pathStart = url.indexOf('/', schemeEnd + 3);
            if (pathStart < 0 || pathStart >= end) return "";
        }

        String segment = url.substring(url.lastIndexOf('/', end - 1) + 1, end);
        int dot = segment.lastIndexOf('.');
        // A dot at the start is the whole of a hidden file's name rather than an extension.
        if (dot <= 0) return "";

        // A version in the last segment -- ".../wine-9.0" -- has a dot without having an
        // extension, so what follows one has to look like an extension to be taken as one.
        String extension = segment.substring(dot + 1);
        if (extension.isEmpty() || extension.length() > 6) return "";

        boolean hasLetter = false;
        for (int i = 0; i < extension.length(); i++) {
            char c = extension.charAt(i);
            if (!Character.isLetterOrDigit(c)) return "";
            hasLetter |= Character.isLetter(c);
        }
        return hasLetter ? "." + extension : "";
    }

    public void extraContentFile(Uri uri, OnInstallFinishedCallback callback) {
        cleanTmpDir(context);

        File file = getTmpDir(context);

        boolean ret;
        ret = TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, context, uri, file);
        if (!ret)
            ret = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, uri, file);
        if (!ret) {
            callback.onFailed(InstallFailedReason.ERROR_BADTAR, null);
            return;
        }

        File proFile = new File(file, PROFILE_NAME);
        if (!proFile.exists()) {
            callback.onFailed(InstallFailedReason.ERROR_NOPROFILE, null);
            return;
        }

        ContentProfile profile = readProfile(proFile);
        if (profile == null) {
            callback.onFailed(InstallFailedReason.ERROR_BADPROFILE, null);
            return;
        }

        String imagefsPath = context.getFilesDir().getAbsolutePath() + "/imagefs";
        for (ContentProfile.ContentFile contentFile : profile.fileList) {
            File tmpFile = new File(file, contentFile.source);
            if (!tmpFile.exists() || !tmpFile.isFile() || !isSubPath(file.getAbsolutePath(), tmpFile.getAbsolutePath())) {
                callback.onFailed(InstallFailedReason.ERROR_MISSINGFILES, null);
                return;
            }

            // Goldberg targets are relative filenames resolved later against a per-shortcut
            // game folder (which can be anywhere), not a fixed imagefs template path, so the
            // imagefs-subpath check doesn't apply here. applyContentToDir() enforces containment
            // against the chosen target folder instead, at apply time.
            if (profile.type != ContentProfile.ContentType.CONTENT_TYPE_GOLDBERG && profile.type != ContentProfile.ContentType.CONTENT_TYPE_OPENCOMPOSITE && profile.type != ContentProfile.ContentType.CONTENT_TYPE_OXRWXR) {
                String realPath = getPathFromTemplate(contentFile.target);
                if (!isSubPath(imagefsPath, realPath) || isSubPath(ContentsManager.getContentDir(context).getAbsolutePath(), realPath) || realPath.contains("dosdevices")) {
                    callback.onFailed(InstallFailedReason.ERROR_UNTRUSTPROFILE, null);
                    return;
                }
            } else if (contentFile.target.isEmpty() || contentFile.target.contains("..")) {
                callback.onFailed(InstallFailedReason.ERROR_UNTRUSTPROFILE, null);
                return;
            }
        }

        if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE) {
            if (!profile.wineBinPath.isEmpty()) {
                File bin = new File(file, profile.wineBinPath);
                if (!bin.exists() || !bin.isDirectory()) {
                    callback.onFailed(InstallFailedReason.ERROR_MISSINGFILES, null);
                    return;
                }
            }

            if (!profile.wineLibPath.isEmpty()) {
                File lib = new File(file, profile.wineLibPath);
                if (!lib.exists() || !lib.isDirectory()) {
                    callback.onFailed(InstallFailedReason.ERROR_MISSINGFILES, null);
                    return;
                }
            }

            if (!profile.winePrefixPack.isEmpty()) {
                File cp = new File(file, profile.winePrefixPack);
                if (!cp.exists() || !cp.isFile()) {
                    callback.onFailed(InstallFailedReason.ERROR_MISSINGFILES, null);
                    return;
                }
            }
        }

        if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
            if (!profile.protonBinPath.isEmpty()) {
                File bin = new File(file, profile.protonBinPath);
                if (!bin.exists() || !bin.isDirectory()) {
                    callback.onFailed(InstallFailedReason.ERROR_MISSINGFILES, null);
                    return;
                }
            }

            if (!profile.protonLibPath.isEmpty()) {
                File lib = new File(file, profile.protonLibPath);
                if (!lib.exists() || !lib.isDirectory()) {
                    callback.onFailed(InstallFailedReason.ERROR_MISSINGFILES, null);
                    return;
                }
            }

            if (!profile.protonPrefixPack.isEmpty()) {
                File cp = new File(file, profile.protonPrefixPack);
                if (!cp.exists() || !cp.isFile()) {
                    callback.onFailed(InstallFailedReason.ERROR_MISSINGFILES, null);
                    return;
                }
            }
        }

        callback.onSucceed(profile);
    }

    public void finishInstallContent(ContentProfile profile, OnInstallFinishedCallback callback) {
        File installPath = getInstallDir(context, profile);
        if (installPath.exists()) {
            callback.onFailed(InstallFailedReason.ERROR_EXIST, null);
            return;
        }

        if (!installPath.mkdirs()) {
            callback.onFailed(InstallFailedReason.ERROR_UNKNOWN, null);
            return;
        }

        if (!getTmpDir(context).renameTo(installPath)) {
            callback.onFailed(InstallFailedReason.ERROR_UNKNOWN, null);
        }

        callback.onSucceed(profile);
    }

    public ContentProfile readProfile(File file) {
        try {
            ContentProfile profile = new ContentProfile();
            JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
            String typeName = profileJSONObject.optString(ContentProfile.MARK_TYPE, "");
            profile.type = ContentProfile.ContentType.getTypeByName(typeName);
            if (profile.type == null) return null;

            profile.verName = profileJSONObject.optString(ContentProfile.MARK_VERSION_NAME, "");
            profile.verCode = profileJSONObject.optInt(ContentProfile.MARK_VERSION_CODE, 0);
            profile.desc = profileJSONObject.optString(ContentProfile.MARK_DESC, "");
            profile.game = optionalString(profileJSONObject, ContentProfile.MARK_GAME);

            JSONArray fileJSONArray = profileJSONObject.optJSONArray(ContentProfile.MARK_FILE_LIST);
            List<ContentProfile.ContentFile> fileList = new ArrayList<>();
            if (fileJSONArray != null) {
                for (int i = 0; i < fileJSONArray.length(); i++) {
                    JSONObject contentFileJSONObject = fileJSONArray.getJSONObject(i);
                    ContentProfile.ContentFile contentFile = new ContentProfile.ContentFile();
                    contentFile.source = contentFileJSONObject.optString(ContentProfile.MARK_FILE_SOURCE, "");
                    contentFile.target = contentFileJSONObject.optString(ContentProfile.MARK_FILE_TARGET, "");
                    fileList.add(contentFile);
                }
            }
            profile.fileList = fileList;

            if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE) {
                JSONObject wineJSONObject = profileJSONObject.optJSONObject(ContentProfile.MARK_WINE);
                if (wineJSONObject != null) {
                    profile.wineLibPath = wineJSONObject.optString(ContentProfile.MARK_WINE_LIBPATH, "");
                    profile.wineBinPath = wineJSONObject.optString(ContentProfile.MARK_WINE_BINPATH, "");
                    profile.winePrefixPack = wineJSONObject.optString(ContentProfile.MARK_WINE_PREFIX_PACK, "");
                } else {
                    profile.wineLibPath = profileJSONObject.optString(ContentProfile.MARK_WINE_LIBPATH, "");
                    profile.wineBinPath = profileJSONObject.optString(ContentProfile.MARK_WINE_BINPATH, "");
                    profile.winePrefixPack = profileJSONObject.optString(ContentProfile.MARK_WINE_PREFIX_PACK, "");
                }
            }

            if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
                JSONObject protonJSONObject = profileJSONObject.optJSONObject(ContentProfile.MARK_PROTON);
                if (protonJSONObject != null) {
                    profile.protonLibPath = protonJSONObject.optString(ContentProfile.MARK_PROTON_LIBPATH, "");
                    profile.protonBinPath = protonJSONObject.optString(ContentProfile.MARK_PROTON_BINPATH, "");
                    profile.protonPrefixPack = protonJSONObject.optString(ContentProfile.MARK_PROTON_PREFIX_PACK, "");
                } else {
                    profile.protonLibPath = profileJSONObject.optString(ContentProfile.MARK_PROTON_LIBPATH, "");
                    profile.protonBinPath = profileJSONObject.optString(ContentProfile.MARK_PROTON_BINPATH, "");
                    profile.protonPrefixPack = profileJSONObject.optString(ContentProfile.MARK_PROTON_PREFIX_PACK, "");
                }
            }

            return profile;
        } catch (Exception e) {
            return null;
        }
    }

    public List<ContentProfile> getProfiles(ContentProfile.ContentType type) {
        if (profilesMap != null)
            return profilesMap.get(type);
        return null;
    }

        public List<ContentProfile> getProfiles(ArrayList<ContentProfile.ContentType> types) {
        if (profilesMap != null) {
            ArrayList<ContentProfile> profiles = new ArrayList<>();
            for (ContentProfile.ContentType type : types) {
                profiles.addAll(Objects.requireNonNull(profilesMap.get(type)));
            }
            return profiles;
        }
        return null;
    }

    public static File getInstallDir(Context context, ContentProfile profile) {
        return new File(getContentTypeDir(context, profile.type), profile.verName + "-" + profile.verCode);
    }

    public static File getContentDir(Context context) {
        return new File(context.getFilesDir(), ContentDirName.CONTENT_MAIN_DIR_NAME.toString());
    }

    public static File getContentTypeDir(Context context, ContentProfile.ContentType type) {
        return new File(getContentDir(context), type.toString());
    }

    public static File getTmpDir(Context context) {
        return new File(context.getFilesDir(), "tmp/" + ContentDirName.CONTENT_MAIN_DIR_NAME);
    }

    public static File getSourceFile(Context context, ContentProfile profile, String path) {
        return new File(getInstallDir(context, profile), path);
    }

    public static void cleanTmpDir(Context context) {
        File file = getTmpDir(context);
        FileUtils.delete(file);
        file.mkdirs();
    }

    public List<ContentProfile.ContentFile> getUnTrustedContentFiles(ContentProfile profile) {
        createTrustedFilesMap();
        List<ContentProfile.ContentFile> files = new ArrayList<>();
        for (ContentProfile.ContentFile contentFile : profile.fileList) {
            if (!trustedFilesMap.get(profile.type).contains(
                    Paths.get(getPathFromTemplate(contentFile.target)).toAbsolutePath().normalize().toString()))
                files.add(contentFile);
        }
        return files;
    }

    private boolean isSubPath(String parent, String child) {
        return Paths.get(child).toAbsolutePath().normalize().startsWith(Paths.get(parent).toAbsolutePath().normalize());
    }

    private void createDirTemplateMap() {
        if (dirTemplateMap == null) {
            dirTemplateMap = new HashMap<>();
            String imagefsPath = context.getFilesDir().getAbsolutePath() + "/imagefs";
            String drivecPath = imagefsPath + "/home/xuser/.wine/drive_c";
            dirTemplateMap.put("${libdir}", imagefsPath + "/usr/lib");
            dirTemplateMap.put("${system32}", drivecPath + "/windows/system32");
            dirTemplateMap.put("${syswow64}", drivecPath + "/windows/syswow64");
            dirTemplateMap.put("${bindir}", imagefsPath + "/usr/bin");
            dirTemplateMap.put("${sharedir}", imagefsPath + "/usr/share");
        }
    }

    private void createTrustedFilesMap() {
        if (trustedFilesMap == null) {
            trustedFilesMap = new HashMap<>();
            for (ContentProfile.ContentType type : ContentProfile.ContentType.values()) {
                List<String> pathList = new ArrayList<>();
                trustedFilesMap.put(type, pathList);

                String[] paths = switch (type) {
                    case CONTENT_TYPE_DXVK -> DXVK_TRUST_FILES;
                    case CONTENT_TYPE_VKD3D -> VKD3D_TRUST_FILES;
                    case CONTENT_TYPE_BOX64 -> BOX64_TRUST_FILES;
                    case CONTENT_TYPE_WOWBOX64 -> WOWBOX64_TRUST_FILES;
                    case CONTENT_TYPE_FEXCORE -> FEXCORE_TRUST_FILES;
                    default -> new String[0];
                };
                for (String path : paths)
                    pathList.add(Paths.get(getPathFromTemplate(path)).toAbsolutePath().normalize().toString());
            }
        }
    }

    private String getPathFromTemplate(String path) {
        createDirTemplateMap();
        String realPath = path;
        for (String key : dirTemplateMap.keySet()) {
            realPath = realPath.replace(key, dirTemplateMap.get(key));
        }
        return realPath;
    }

    public void removeContent(ContentProfile profile) {
        if (profilesMap.get(profile.type).contains(profile)) {
            FileUtils.delete(getInstallDir(context, profile));
            profilesMap.get(profile.type).remove(profile);
            syncContents();
        }
    }

    public static String getEntryName(ContentProfile profile) {
        return profile.type.toString() + '-' + profile.verName + '-' + profile.verCode;
    }

    public ContentProfile getProfileByEntryName(String entryName) {
        int firstDashIndex = entryName.indexOf('-');
        int lastDashIndex = entryName.lastIndexOf('-');

        try {
            String typeName = entryName.substring(0, firstDashIndex);
            String versionName = entryName.substring(firstDashIndex + 1, lastDashIndex);
            String versionCode = entryName.substring(lastDashIndex + 1);

            for (ContentProfile profile : profilesMap.get(ContentProfile.ContentType.getTypeByName(typeName))) {
                if (versionName.equals(profile.verName) && Integer.parseInt(versionCode) == profile.verCode)
                    return profile;
            }
        } catch (Exception e) {
        }

        return null;
    }

    public boolean applyContent(ContentProfile profile) {
        if (profile.type != ContentProfile.ContentType.CONTENT_TYPE_WINE && profile.type != ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
            for (ContentProfile.ContentFile contentFile : profile.fileList) {
                File targetFile = new File(getPathFromTemplate(contentFile.target));
                File sourceFile = new File(getInstallDir(context, profile), contentFile.source);

                targetFile.delete();
                FileUtils.copy(sourceFile, targetFile);

                if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_BOX64) {
                    FileUtils.chmod(targetFile, 0771);
                }
            }
        }
        else {
            // If we end up needing to inject winebus.so into user-installed contents
//            File installDir = getInstallDir(context, profile);
//            boolean arm64ec = profile.verName.contains("arm64ec");
//            File wineRoot = new File(installDir,        // root of the .wcp
//                    profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON
//                            ? profile.protonLibPath     // “proton-…”
//                            : profile.wineLibPath       // “wine-…”
//            ).getParentFile().getParentFile().getParentFile(); // climb back to top
//
//            EvshimPatcher.patchWineTree(context, wineRoot, arm64ec);
        }
        return true;
    }
}
