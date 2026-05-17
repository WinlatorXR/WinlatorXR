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

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        boolean isImmersive = XrActivity.isImmersive;

        CheckBox cbSBS = findViewById(R.id.CBEnableSBS);
        cbSBS.setEnabled(XrActivity.getInstance().lastMode3D < 0);
        cbSBS.setChecked(XrActivity.isSBS);
        CheckBox cbImmersiveMode = findViewById(R.id.CBEnableImmersiveMode);
        cbImmersiveMode.setEnabled(!XrActivity.isUDP);
        cbImmersiveMode.setChecked(isImmersive);
        CheckBox cbCurvedScreen = findViewById(R.id.CBEnableCurvedScreen);
        cbCurvedScreen.setChecked(preferences.getBoolean("use_cs", false));
        CheckBox cbPassthrough = findViewById(R.id.CBEnablePassthrough);
        cbPassthrough.setEnabled(!isImmersive);
        cbPassthrough.setChecked(preferences.getBoolean("use_pt", true));
        TextView tvToApplyClose = findViewById(R.id.TVToApplyClose);

        Runnable applyAll = () -> {
            SharedPreferences.Editor e = preferences.edit();
            e.putBoolean("use_cs", cbCurvedScreen.isChecked());
            e.putBoolean("use_pt", cbPassthrough.isChecked());
            e.commit();

            XrActivity.isSBS = cbSBS.isChecked();
            XrActivity.isImmersive = cbImmersiveMode.isChecked();
            XrActivity instance = XrActivity.getInstance();
            instance.nativeSetCurvedScreen(cbCurvedScreen.isChecked());
            instance.nativeSetUsePT(cbPassthrough.isChecked());

            boolean warn = (XrActivity.isImmersive != isImmersive);
            tvToApplyClose.setVisibility(warn ? View.VISIBLE : View.GONE);
            cbPassthrough.setEnabled(!XrActivity.isImmersive);
        };

        // Apply changes immediatelly
        cbSBS.setOnCheckedChangeListener((compoundButton, b) -> applyAll.run());
        cbImmersiveMode.setOnCheckedChangeListener((compoundButton, b) -> applyAll.run());
        cbCurvedScreen.setOnCheckedChangeListener((compoundButton, b) -> applyAll.run());
        cbPassthrough.setOnCheckedChangeListener((compoundButton, b) -> applyAll.run());

        findViewById(R.id.BTCancel).setVisibility(View.GONE);
        findViewById(R.id.BTConfirm).setVisibility(View.VISIBLE);
        findViewById(R.id.BTConfirm).setOnClickListener(v -> dismiss());
        setOnConfirmCallback(this::dismiss);
    }
}
