package com.winlator.xr;

import android.os.Build;

import com.winlator.xr.runtime.MetaQuest;
import com.winlator.xr.runtime.Pico;
import com.winlator.xr.runtime.PlayForDream;

public class Device {

    public enum HmdModel {
        PICO_UNKNOWN,
        PLAY_FOR_DREAM_UNKNOWN,
        QUEST_1, QUEST_2, QUEST_3, QUEST_PRO, QUEST_UNKNOWN,
        UNKNOWN_DEVICE
    }

    public static HmdModel getDevice() {
        if (Build.MANUFACTURER.compareToIgnoreCase("PICO") == 0) {
            return HmdModel.PICO_UNKNOWN;
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
}
