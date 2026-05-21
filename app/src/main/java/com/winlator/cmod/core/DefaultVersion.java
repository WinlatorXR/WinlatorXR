package com.winlator.cmod.core;

import static com.winlator.xr.Device.HmdModel.PICO_UNKNOWN;
import static com.winlator.xr.Device.HmdModel.QUEST_2;

import com.winlator.xr.Device;

public abstract class DefaultVersion {
    public static final String BOX86 = "0.4.2-0";
    public static final String BOX64 = "0.4.2-0";
    public static final String FEXCORE = "2605-0";
    public static final String WRAPPER = switch(Device.getDevice()) {
        case QUEST_3 -> "Qualcomm_v849_Quest3_Pico4Ultra";
        default -> "System";
    };
    public static final String DXVK = switch(Device.getDevice()) {
        case QUEST_3 -> "2.6.2-1-gplasync-1";
        default -> "1.10.1";
    };
    public static final String D8VK = "1.0";
    public static final String VKD3D = "2.12-0";
}