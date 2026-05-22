package com.winlator.xr;

import android.os.Build;
import android.util.Log;

import com.winlator.xr.runtime.MetaQuest;
import com.winlator.xr.runtime.Pico;
import com.winlator.xr.runtime.PlayForDream;

public class Device {

    public enum HmdModel {
        PICO_UNKNOWN, PICO_4_Ultra, PICO_NEO_3_LINK, PICO_4,
        PLAY_FOR_DREAM_UNKNOWN,
        QUEST_1, QUEST_2, QUEST_3, QUEST_PRO, QUEST_UNKNOWN,
        UNKNOWN_DEVICE
    }

    public static HmdModel getDevice() {
        Log.e("ABCDE", "MANUFACTURER: " + Build.MANUFACTURER);
        Log.e("ABCDE", "PRODUCT: " + Build.PRODUCT);
        if (Build.MANUFACTURER.compareToIgnoreCase("PICO") == 0) {
            return getPicoDevice();
        } else if (Build.MANUFACTURER.compareToIgnoreCase("PLAY FOR DREAM") == 0) {
            return HmdModel.PLAY_FOR_DREAM_UNKNOWN;
        } else if (Build.MANUFACTURER.compareToIgnoreCase("OCULUS") == 0) {
            return getQuestDevice();
        } else if (Build.MANUFACTURER.compareToIgnoreCase("META") == 0) {
            return getQuestDevice();
        } else {
            return HmdModel.UNKNOWN_DEVICE;
        }
    }

    public static Class getRuntime() {
        if (Build.MANUFACTURER.compareToIgnoreCase("PICO") == 0) {
            return Pico.class;
        } else if (Build.MANUFACTURER.compareToIgnoreCase("PLAY FOR DREAM") == 0) {
            return PlayForDream.class;
        } else if (Build.MANUFACTURER.compareToIgnoreCase("OCULUS") == 0) {
            return MetaQuest.class;
        } else if (Build.MANUFACTURER.compareToIgnoreCase("META") == 0) {
            return MetaQuest.class;
        } else {
            return null;
        }
    }

    private static Device.HmdModel getQuestDevice() {
        return switch (Build.PRODUCT) {
            case "monterey", "vr_monterey" -> Device.HmdModel.QUEST_1;
            case "hollywood" -> Device.HmdModel.QUEST_2;
            case "eureka", "stinson", "panther" -> Device.HmdModel.QUEST_3;
            case "seacliff" -> Device.HmdModel.QUEST_PRO;
            default -> Device.HmdModel.QUEST_UNKNOWN;
        };
    }
    private static Device.HmdModel getPicoDevice() {
        return switch (Build.PRODUCT) {
            case "PICO 4 Ultra", "Pico_A9210", "A9210", "sparrow" -> Device.HmdModel.PICO_4_Ultra;
            case "Pico Neo 3", "Pico_Neo_3", "A7P10" -> HmdModel.PICO_NEO_3_LINK;
            case "Pico 4", "Pico_4", "PICO 4", "PICO_4", "Pico A8110", "PICO A8110", "Pico_A8110", "PICOA8110", "A8110", "pheonix" -> HmdModel.PICO_4;
            default -> Device.HmdModel.PICO_UNKNOWN;
        };
    }
}