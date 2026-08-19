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
package com.winlator.xr.io;

import android.content.Intent;
import android.util.Pair;

import com.drbeef.externalhapticsservice.HapticServiceClient;
import com.drbeef.externalhapticsservice.HapticsConstants;
import com.winlator.xr.XrActivity;
import com.winlator.xr.api.XrAPI;
import com.winlator.xr.api.XrInterface;

import java.util.Vector;

public class XrHaptics {
    private boolean isExternalHapticsRunning = false;
    private final float[] lastVibration = new float[2];
    private final Vector<HapticServiceClient> externalHapticsServiceClients = new Vector<>();
    private final XrInterface.AppInput[] haptics = {XrInterface.AppInput.L_HAPTICS, XrInterface.AppInput.R_HAPTICS};

    public XrHaptics() {
        Vector<Pair<String, String>> externalHapticsServiceDetails = new Vector<>();
        externalHapticsServiceDetails.add(Pair.create(HapticsConstants.BHAPTICS_PACKAGE, HapticsConstants.BHAPTICS_ACTION_FILTER));
        externalHapticsServiceDetails.add(Pair.create(HapticsConstants.FORCETUBE_PACKAGE, HapticsConstants.FORCETUBE_ACTION_FILTER));
        for (Pair<String, String> serviceDetail : externalHapticsServiceDetails) {
            Intent intent = new Intent(serviceDetail.second).setPackage(serviceDetail.first);
            HapticServiceClient client = new HapticServiceClient(XrActivity.getInstance(), (state, desc) -> {}, intent);
            client.bindService();
            externalHapticsServiceClients.add(client);
        }
    }

    public void unload() {
        try {
            for (HapticServiceClient externalHapticsServiceClient : externalHapticsServiceClients) {
                externalHapticsServiceClient.stopBinding();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void update(XrAPI xrAPI) {
        String[] sendEvent = defineHaptics(xrAPI);
        updateExternalHaptics(sendEvent);
    }

    private String[] defineHaptics(XrAPI xrAPI) {
        String[] sendEvent = {null, null};
        for (int i = 0; i < haptics.length; i++) {
            XrInterface.AppInput haptic = haptics[i];
            float value = xrAPI.getValue(haptic);
            if (value > 0.0f) {
                // External haptics (scheme from Doom3Quest)
                if (lastVibration[i] < value) {
                    sendEvent[i] = value > 1 ? "shotgun_fire" : "pistol_fire";
                }
                // Controller haptics
                XrActivity.getInstance().vibrateController(1, i, value);
                xrAPI.setValue(haptic, value - 0.1f);
                lastVibration[i] = value;
            } else {
                xrAPI.setValue(haptic, 0.0f);
                lastVibration[i] = 0.0f;
            }
        }
        return sendEvent;
    }

    private void updateExternalHaptics(String[] sendEvent) {
        for (HapticServiceClient externalHapticsServiceClient : externalHapticsServiceClients) {
            if (externalHapticsServiceClient.hasService()) {
                try {
                    if (isExternalHapticsRunning != XrActivity.isUDP) {
                        if (XrActivity.isUDP) {
                            externalHapticsServiceClient.getHapticsService().hapticEnable();
                        } else {
                            externalHapticsServiceClient.getHapticsService().hapticDisable();
                        }
                    } else if (isExternalHapticsRunning) {
                        for (int i = 0; i < haptics.length; i++) {
                            if (sendEvent[i] != null) {
                                externalHapticsServiceClient.getHapticsService().hapticEvent("Doom3Quest", sendEvent[i], i + 1, 0, 100, 0, 0);
                            }
                        }
                        externalHapticsServiceClient.getHapticsService().hapticFrameTick();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        isExternalHapticsRunning = XrActivity.isUDP;
    }
}
