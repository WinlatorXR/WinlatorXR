/*
 * Copyright (C) 2024-2026 WinlatorXR
 *
 * This file is part of WinlatorXR.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.winlator.xr;

import android.os.Build;

import com.winlator.xr.runtime.MetaQuest;
import com.winlator.xr.runtime.Pico;
import com.winlator.xr.runtime.PlayForDream;

public class Device {

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
        return Device.getRuntime() != null;
    }

    private static Device.HmdModel getPicoDevice() {
        return switch (Build.PRODUCT) {
            case "Pico Neo 3", "Pico_Neo_3", "A7P10" -> HmdModel.PICO_NEO_3_LINK;
            case "Pico 4", "Pico_4", "PICO 4", "PICO_4", "Pico A8110", "PICO A8110", "Pico_A8110", "PICOA8110", "A8110", "pheonix" -> HmdModel.PICO_4;
            case "PICO 4 Ultra", "Pico_A9210", "A9210", "sparrow" -> Device.HmdModel.PICO_4_ULTRA;
            default -> Device.HmdModel.PICO_UNKNOWN;
        };
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