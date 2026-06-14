package com.winlator.cmod.core;

import com.winlator.xr.Device;

public abstract class DefaultVersion {
    public static final String BOX86 = "0.4.2";
    public static final String BOX64 = "0.4.2";
    public static final String FEXCORE = "2605-0";
    public static final String WRAPPER = switch(Device.getDevice()) {
        case PICO_4_ULTRA -> "adrenotools-Turnip_v26.2.0_R6";
        case QUEST_3 -> "adrenotools-v819.2_Quest3_Pico4Ultra";
        case QUEST_2 -> "adrenotools-Turnip_v26.2.0_R6";
        default -> "System";
    };
    public static final String DXVK = switch(Device.getDevice()) {
        case PICO_NEO_3_LINK, PICO_4 -> "1.12.-sarek-async-0";
        default -> "1.10.1";
    };
    public static final String D8VK = "1.0";
    public static final String VKD3D = "2.12-0";
}