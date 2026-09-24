/*
 * Copyright (C) 2024-2026 WinlatorXR
 *
 * This file is part of WinlatorXR.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.winlator.xr.utils;

import android.os.Build;

import com.winlator.xr.runtime.MetaQuest;
import com.winlator.xr.runtime.Pico;
import com.winlator.xr.runtime.PlayForDream;

public class XrDevice {

    public enum HmdModel {
        PICO_NEO_3_LINK, PICO_4, PICO_4_ULTRA, PICO_UNKNOWN,
        PLAY_FOR_DREAM_UNKNOWN,
        QUEST_1, QUEST_2, QUEST_3, QUEST_PRO, QUEST_UNKNOWN,
        UNKNOWN_DEVICE
    }

    public static HmdModel getDevice() {
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

    /**
     * The headset's name as someone would say it -- "Quest 3", "PICO 4 Ultra".
     *
     * For anything the enum does not name, and for a phone or tablet, it falls back to what the
     * build says about itself rather than to "unknown": a headset this code has not been taught
     * about still identifies itself usefully that way, and the point of asking is to tell one
     * device from another, not to prove which one it is.
     */
    public static String getDisplayName() {
        return switch (getDevice()) {
            case PICO_NEO_3_LINK -> "Pico Neo 3";
            case PICO_4 -> "PICO 4";
            case PICO_4_ULTRA -> "PICO 4 Ultra";
            case QUEST_1 -> "Quest 1";
            case QUEST_2 -> "Quest 2";
            case QUEST_3 -> "Quest 3";
            case QUEST_PRO -> "Quest Pro";
            default -> buildName();
        };
    }

    /** What the build calls itself, with the maker left off when the model already says it. */
    private static String buildName() {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();

        if (model.isEmpty()) return manufacturer.isEmpty() ? "Unknown device" : manufacturer;
        if (manufacturer.isEmpty()) return model;
        if (model.toLowerCase().startsWith(manufacturer.toLowerCase())) return model;
        return manufacturer + " " + model;
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

    public static boolean isSupported() {
        return XrDevice.getRuntime() != null;
    }

    private static XrDevice.HmdModel getPicoDevice() {
        return switch (Build.PRODUCT) {
            case "Pico Neo 3", "Pico_Neo_3", "A7P10", "A7H10" -> HmdModel.PICO_NEO_3_LINK;
            case "Pico 4", "Pico_4", "PICO 4", "PICO_4", "Pico A8110", "PICO A8110", "Pico_A8110", "PICOA8110", "A8110", "pheonix" -> HmdModel.PICO_4;
            case "PICO 4 Ultra", "Pico_A9210", "A9210", "sparrow" -> XrDevice.HmdModel.PICO_4_ULTRA;
            default -> XrDevice.HmdModel.PICO_UNKNOWN;
        };
    }

    private static XrDevice.HmdModel getQuestDevice() {
        return switch (Build.PRODUCT) {
            case "monterey", "vr_monterey" -> XrDevice.HmdModel.QUEST_1;
            case "hollywood" -> XrDevice.HmdModel.QUEST_2;
            case "eureka", "stinson", "panther" -> XrDevice.HmdModel.QUEST_3;
            case "seacliff" -> XrDevice.HmdModel.QUEST_PRO;
            default -> XrDevice.HmdModel.QUEST_UNKNOWN;
        };
    }
}