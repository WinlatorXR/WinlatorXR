package com.winlator.cmod.core;

import com.winlator.xr.utils.Device;

public abstract class DefaultVersion {
    public static final String BOX86 = "0.4.2";
    public static final String BOX64 = "0.4.2";
    public static final String FEXCORE = "2605";
    public static final String WRAPPER = switch(Device.getDevice()) {
        case PICO_4_ULTRA, QUEST_2, PICO_NEO_3_LINK, PICO_4 -> "adrenotools-Turnip_v26.3.0_r9";
        case QUEST_3 -> "adrenotools-v819.2_Quest3_Pico4Ultra";
        default -> "System";
    };

    /**
     * DXVK builds are architecture-specific, so the caller has to say which kind of container it
     * is asking for: the headset is a process-wide fact and can be resolved here, the container's
     * Wine architecture cannot. arm64ec versions come from R.array.dxvk_version_entries_arm64.
     */
    public static String dxvk(boolean arm64ec) {
        if (arm64ec) return switch(Device.getDevice()) {
            case PICO_NEO_3_LINK, PICO_4 -> "sarek-dyasync-arm64ec-1.12.0";
            default -> "1.10.1";
        };

        return switch(Device.getDevice()) {
            case PICO_NEO_3_LINK, PICO_4 -> "1.12.-sarek-async-0";
            default -> "1.10.1";
        };
    }

    public static String dxvk(WineInfo wineInfo) {
        return dxvk(wineInfo != null && wineInfo.isArm64EC());
    }

    /** x86_64 default, for callers with no container context. Prefer {@link #dxvk(boolean)}. */
    public static final String DXVK = dxvk(false);
    public static final String D8VK = "1.0";
    public static final String VKD3D = "2.12-0";

    public static final String ENV_VARS = switch(Device.getDevice()) {
        case PICO_4_ULTRA -> "ZINK_DESCRIPTORS=lazy ZINK_DEBUG=compact MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=1024MB mesa_glthread=true WINEESYNC=1 MESA_VK_WSI_PRESENT_MODE=mailbox DXVK_DISABLE_TIMELINE_SEMAPHORES=1 WINE_FAST_YIELD=0 TU_DEBUG=noconform DXVK_HUD=fps MANGOHUD=0 MANGOHUD_CONFIG=horizontal,ram,procmem,gpu_temp,frame_timing,engine_version,gpu_stats=0";
        default -> "ZINK_DESCRIPTORS=lazy ZINK_DEBUG=compact MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=512MB mesa_glthread=true WINEESYNC=1 MESA_VK_WSI_PRESENT_MODE=mailbox DXVK_DISABLE_TIMELINE_SEMAPHORES=1 WINE_FAST_YIELD=0 TU_DEBUG=noconform,sysmem DXVK_HUD=fps MANGOHUD=0 MANGOHUD_CONFIG=horizontal,ram,procmem,gpu_temp,frame_timing,engine_version,gpu_stats=0";
    };
}