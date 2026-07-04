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
package com.winlator.xr.ui;

import android.app.Activity;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import com.winlator.xr.XrActivity;
import com.winlator.cmod.R;
import com.winlator.cmod.contentdialog.ContentDialog;

public class XrDialog extends ContentDialog {

    public XrDialog(Activity activity) {
        super(activity, R.layout.xr_dialog);
        setTitle(R.string.xr);

        CheckBox cbSBS = findViewById(R.id.CBEnableSBS);
        CheckBox cbImmersiveMode = findViewById(R.id.CBEnableImmersiveMode);
        CheckBox cbCurvedScreen = findViewById(R.id.CBEnableCurvedScreen);
        CheckBox cbPassthrough = findViewById(R.id.CBEnablePassthrough);
        TextView tvToApplyClose = findViewById(R.id.TVToApplyClose);
        hmdUI(activity, cbSBS, cbImmersiveMode, cbCurvedScreen, cbPassthrough, tvToApplyClose);

        CheckBox cbMouseLeftHanded = findViewById(R.id.CBPlayerXRMouseLeftHanded);
        CheckBox cbMouseLightgun = findViewById(R.id.CBPlayerXRMouseLightgun);
        CheckBox cbMouse = findViewById(R.id.CBPlayerXRMouse);
        Button btEditor = findViewById(R.id.BTControlsEditor);
        controllerUI(activity, cbMouseLeftHanded, cbMouseLightgun, cbMouse, btEditor);

        findViewById(R.id.BTCancel).setVisibility(View.GONE);
        findViewById(R.id.BTConfirm).setVisibility(View.VISIBLE);
        findViewById(R.id.BTConfirm).setOnClickListener(v -> dismiss());
        setOnConfirmCallback(this::dismiss);
    }

    public static void controllerUI(Activity activity, CheckBox cbMouseLeftHanded, CheckBox cbMouseLightgun, CheckBox cbMouse, Button btEditor) {
        loadConfig(cbMouseLeftHanded, "use_xr_leftHanded", false, XrActivity.mouseLeftHanded);
        cbMouseLeftHanded.setOnCheckedChangeListener((compoundButton, checked) -> {
            saveConfig(cbMouseLeftHanded, "use_xr_leftHanded", checked);
            XrActivity.mouseLeftHanded = checked;
        });

        loadConfig(cbMouseLightgun, "use_xr_lightgun", false, XrActivity.mouseLightgun);
        cbMouseLightgun.setOnCheckedChangeListener((compoundButton, checked) -> {
            saveConfig(cbMouseLightgun, "use_xr_lightgun", checked);
            XrActivity.mouseLightgun = checked;
        });

        loadConfig(cbMouse, "use_xr_mouse", true, XrActivity.mouseEmulation);
        cbMouse.setOnCheckedChangeListener((compoundButton, checked) -> {
            saveConfig(cbMouse, "use_xr_mouse", checked);
            XrActivity.mouseEmulation = checked;
            cbMouseLeftHanded.setEnabled(checked);
            cbMouseLightgun.setEnabled(checked);
        });
        cbMouseLeftHanded.setEnabled(cbMouse.isChecked());
        cbMouseLightgun.setEnabled(cbMouse.isChecked());

        btEditor.setOnClickListener(v -> new XrControllerDialog(activity).show());
        btEditor.setVisibility(XrActivity.isActive() ? View.GONE : View.VISIBLE);
    }

    public static void hmdUI(Activity activity, CheckBox cbSBS, CheckBox cbImmersiveMode, CheckBox cbCurvedScreen, CheckBox cbPassthrough, TextView tvToApplyClose) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        boolean isImmersive = XrActivity.isImmersive;

        if (XrActivity.isActive()) {
            cbSBS.setEnabled(XrActivity.getInstance().lastMode3D < 0);
            cbSBS.setChecked(XrActivity.isSBS);
            cbImmersiveMode.setEnabled(!XrActivity.isUDP);
            cbImmersiveMode.setChecked(isImmersive);
        } else {
            cbSBS.setVisibility(View.GONE);
            cbImmersiveMode.setVisibility(View.GONE);
        }
        cbCurvedScreen.setChecked(preferences.getBoolean("use_cs", false));
        cbPassthrough.setEnabled(!isImmersive);
        cbPassthrough.setChecked(preferences.getBoolean("use_pt", true));

        Runnable applyAll = () -> {
            SharedPreferences.Editor e = preferences.edit();
            e.putBoolean("use_cs", cbCurvedScreen.isChecked());
            e.putBoolean("use_pt", cbPassthrough.isChecked());
            e.commit();

            XrActivity.isSBS = cbSBS.isChecked();
            XrActivity.isImmersive = cbImmersiveMode.isChecked();
            XrActivity instance = XrActivity.getInstance();
            if (XrActivity.isActive()) {
                instance.nativeSetCurvedScreen(cbCurvedScreen.isChecked());
                instance.nativeSetUsePT(cbPassthrough.isChecked());
            }

            boolean warn = (XrActivity.isImmersive != isImmersive) && XrActivity.isActive();
            tvToApplyClose.setVisibility(warn ? View.VISIBLE : View.GONE);
            cbPassthrough.setEnabled(!XrActivity.isImmersive);
        };

        // Apply changes immediately
        cbSBS.setOnCheckedChangeListener((compoundButton, b) -> applyAll.run());
        cbImmersiveMode.setOnCheckedChangeListener((compoundButton, b) -> applyAll.run());
        cbCurvedScreen.setOnCheckedChangeListener((compoundButton, b) -> applyAll.run());
        cbPassthrough.setOnCheckedChangeListener((compoundButton, b) -> applyAll.run());
    }

    private static void loadConfig(CheckBox cb, String key, boolean defValue, boolean curValue) {
        if (XrActivity.isActive()) {
            cb.setChecked(curValue);
        } else {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(cb.getContext());
            cb.setChecked(prefs.getBoolean(key, defValue));
        }
    }

    private static void saveConfig(CheckBox cb, String key, boolean value) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(cb.getContext());
        SharedPreferences.Editor e = prefs.edit();
        e.putBoolean(key, value);
        e.apply();
    }
}
