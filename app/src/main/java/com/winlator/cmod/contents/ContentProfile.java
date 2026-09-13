package com.winlator.cmod.contents;

import androidx.annotation.NonNull;

import java.util.List;

public class ContentProfile {
    public static final String MARK_TYPE = "type";
    public static final String MARK_VERSION_NAME = "versionName";
    public static final String MARK_VERSION_CODE = "versionCode";
    public static final String MARK_DESC = "description";
    /** The game a Mod entry is for, which nothing else about the entry says. */
    public static final String MARK_GAME = "game";
    public static final String MARK_FILE_LIST = "files";
    public static final String MARK_FILE_SOURCE = "source";
    public static final String MARK_FILE_TARGET = "target";
    public static final String MARK_WINE = "wine";
    public static final String MARK_WINE_BINPATH = "binPath";
    public static final String MARK_WINE_LIBPATH = "libPath";
    public static final String MARK_WINE_PREFIX_PACK = "prefixPack";
    public static final String MARK_PROTON = "proton";
    public static final String MARK_PROTON_BINPATH = "binPath";
    public static final String MARK_PROTON_LIBPATH = "libPath";
    public static final String MARK_PROTON_PREFIX_PACK = "prefixPack";

    public enum ContentType {
        CONTENT_TYPE_WINE("Wine"),
        CONTENT_TYPE_PROTON("Proton"),
        CONTENT_TYPE_RUNTIME("Runtime"),
        CONTENT_TYPE_DXVK("DXVK"),
        CONTENT_TYPE_VKD3D("VKD3D"),
        CONTENT_TYPE_BOX64("Box64"),
        CONTENT_TYPE_WOWBOX64("WOWBox64"),
        CONTENT_TYPE_FEXCORE("FEXCore"),
        CONTENT_TYPE_ADRENO_GPU_DRIVERS("Adreno GPU drivers"),
        CONTENT_TYPE_GOLDBERG("Goldberg"),
        CONTENT_TYPE_INSTALLER("Installer"),
        CONTENT_TYPE_MOD("Mod");

        final String typeName;

        ContentType(String typeName) {
            this.typeName = typeName;
        }

        @NonNull
        @Override
        public String toString() {
            return typeName;
        }

        public static ContentType getTypeByName(String name) {
            for (ContentType type : ContentType.values())
                if (type.typeName.toLowerCase().equals(name.toLowerCase()))
                    return type;
            return null;
        }
    }

    public static class ContentFile {
        public String source;
        public String target;
    }

    public ContentType type;
    public String verName;
    public int verCode;
    public String desc;
    /**
     * The game a mod is for, as the name a person would recognise it by -- "Crysis". Free text
     * rather than an id, because this app has no id for a game: one is a folder a store made, a
     * folder the user copied in, or a shortcut they named themselves, and nothing runs through
     * all three. So it is matched loosely and used to sort the likely answer to the top of the
     * list rather than to decide anything on the user's behalf.
     *
     * Null for every entry that does not name one, which is every entry the user added
     * themselves and any mod listed without it.
     */
    public String game;
    public List<ContentFile> fileList;
    public String wineLibPath;
    public String wineBinPath;
    public String winePrefixPack;
    public String protonLibPath;

    public String protonBinPath;
    public String protonPrefixPack;
    public String remoteUrl;
    /**
     * File name of an installer sitting in the runtimes or installers directory. Set for the
     * entries the user added from local storage, which have no contents.json profile behind
     * them and so no remoteUrl to derive an extension from.
     */
    public String localFileName;
    /**
     * Absolute host path of a demo or offline installer that lives outside the app, in the
     * user's Download folder. Such an entry is a reference: the file runs where it sits, beside
     * the data files it needs, and is never copied into the installers directory.
     */
    public String localFilePath;
}
