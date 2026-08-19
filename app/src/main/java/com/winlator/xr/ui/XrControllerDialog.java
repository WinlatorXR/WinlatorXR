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
package com.winlator.xr.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.xserver.XKeycode;
import com.winlator.xr.io.XrController;

import java.util.ArrayList;
import java.util.List;

public class XrControllerDialog extends ContentDialog {
    public static final String XR_CONTROLLER_PROFILE_COUNT = "XR_CONTROLLER_PROFILE_COUNT";
    public static final String XR_CONTROLLER_PROFILE_INDEX = "XR_CONTROLLER_PROFILE_INDEX";
    public static final String XR_CONTROLLER_PROFILE_NAME = "XR_CONTROLLER_PROFILE_NAME";
    public static final String XR_CONTROLLER_PROFILE_VALUE = "XR_CONTROLLER_PROFILE_VALUE";

    public XrControllerDialog(Context context, Runnable onSave) {
        super(context, R.layout.xr_controller_dialog);
        setIcon(R.drawable.icon_gamepad);
        setTitle(context.getString(R.string.controller_profile));

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int index = prefs.getInt(XR_CONTROLLER_PROFILE_INDEX, 0);
        EditText etName = findViewById(R.id.ETName);
        etName.setText(prefs.getString(XR_CONTROLLER_PROFILE_NAME + index, "Unknown name"));

        bindMapping(findViewById(R.id.SButtonA), XrController.Mapping.BUTTON_A);
        bindMapping(findViewById(R.id.SButtonB), XrController.Mapping.BUTTON_B);
        bindMapping(findViewById(R.id.SButtonX), XrController.Mapping.BUTTON_X);
        bindMapping(findViewById(R.id.SButtonY), XrController.Mapping.BUTTON_Y);
        bindMapping(findViewById(R.id.SButtonGrip), XrController.Mapping.BUTTON_GRIP);
        bindMapping(findViewById(R.id.SButtonTrigger), XrController.Mapping.BUTTON_TRIGGER);
        bindMapping(findViewById(R.id.SThumbstickLeft), XrController.Mapping.THUMBSTICK_LEFT);
        bindMapping(findViewById(R.id.SThumbstickRight), XrController.Mapping.THUMBSTICK_RIGHT);
        bindMapping(findViewById(R.id.SThumbstickUp), XrController.Mapping.THUMBSTICK_UP);
        bindMapping(findViewById(R.id.SThumbstickDown), XrController.Mapping.THUMBSTICK_DOWN);

        setOnConfirmCallback(() -> {
            saveMapping(etName.getText().toString());
            onSave.run();
        });
    }

    public static void profileUI(Activity activity, Spinner sControllerPreset,
                                 View btAddControllerPreset, View btEditControllerPreset,
                                 View btDuplicateControllerPreset, View btRemoveControllerPreset) {

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        updateUISpinner(activity, sControllerPreset);

        btEditControllerPreset.setOnClickListener(v -> new XrControllerDialog(activity, () -> profileUI(activity, sControllerPreset,
                btAddControllerPreset, btEditControllerPreset,
                btDuplicateControllerPreset, btRemoveControllerPreset)).show());
        btAddControllerPreset.setOnClickListener(view -> {
            int count = prefs.getInt(XR_CONTROLLER_PROFILE_COUNT, 1);
            SharedPreferences.Editor e = prefs.edit();
            e.putString(XR_CONTROLLER_PROFILE_NAME + count, "New profile");
            e.putInt(XR_CONTROLLER_PROFILE_COUNT, count + 1);
            e.commit();
            updateUISpinner(activity, sControllerPreset);
        });
        btDuplicateControllerPreset.setOnClickListener(view -> {
            int index = prefs.getInt(XR_CONTROLLER_PROFILE_INDEX, 0);
            String name = prefs.getString(XR_CONTROLLER_PROFILE_NAME + index, "Cloned profile");
            String value = prefs.getString(XR_CONTROLLER_PROFILE_VALUE + index, XrController.getDefaultMapping());

            int count = prefs.getInt(XR_CONTROLLER_PROFILE_COUNT, 1);
            SharedPreferences.Editor e = prefs.edit();
            e.putString(XR_CONTROLLER_PROFILE_NAME + count, name + " (copy)");
            e.putString(XR_CONTROLLER_PROFILE_VALUE + count, value);
            e.putInt(XR_CONTROLLER_PROFILE_COUNT, count + 1);
            e.commit();
            updateUISpinner(activity, sControllerPreset);
        });
        btRemoveControllerPreset.setOnClickListener(view -> {
            int index = prefs.getInt(XR_CONTROLLER_PROFILE_INDEX, 0);
            int count = prefs.getInt(XR_CONTROLLER_PROFILE_COUNT, 1);
            SharedPreferences.Editor e = prefs.edit();
            for (int i = index + 1; i < count; i++) {
                String name = prefs.getString(XR_CONTROLLER_PROFILE_NAME + i, "Failed profile");
                String value = prefs.getString(XR_CONTROLLER_PROFILE_VALUE + i, XrController.getDefaultMapping());
                e.putString(XR_CONTROLLER_PROFILE_NAME + (i - 1), name);
                e.putString(XR_CONTROLLER_PROFILE_VALUE + (i - 1), value);
            }
            e.putInt(XR_CONTROLLER_PROFILE_COUNT, count - 1);
            e.putInt(XR_CONTROLLER_PROFILE_INDEX, 0);
            e.commit();
            updateUISpinner(activity, sControllerPreset);
        });
    }

    private void bindMapping(Spinner spinner, XrController.Mapping mapping) {
        XKeycode[] values = XKeycode.values();
        ArrayList<String> array = new ArrayList<>();
        for (XKeycode value : values) {
            array.add(value.name());
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(spinner.getContext(), android.R.layout.simple_spinner_dropdown_item, array);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        byte keycode = XrController.getMapping(spinner.getContext(), mapping);
        int index = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].id == keycode) {
                index = i;
                break;
            }
        }
        spinner.setSelection(index);
    }

    private void saveMapping(String name) {
        //The order has to be the same as in Mapping enum
        int[] ids = {
                R.id.SButtonA, R.id.SButtonB, R.id.SButtonX, R.id.SButtonY, R.id.SButtonGrip, R.id.SButtonTrigger,
                R.id.SThumbstickUp, R.id.SThumbstickDown, R.id.SThumbstickLeft, R.id.SThumbstickRight
        };
        byte[] output = new byte[ids.length];
        for (int i = 0; i < ids.length; i++) {
            int index =  ((Spinner)findViewById(ids[i])).getSelectedItemPosition();
            byte value = XKeycode.values()[index].id;
            output[i] = value;
        }
        XrController.setMapping(getContext(), name, new String(output));
    }

    private static void updateUISpinner(Activity activity, Spinner sControllerProfile) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        List<String> names = XrDialog.getProfileNames(activity);
        sControllerProfile.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, names));
        sControllerProfile.setSelection(prefs.getInt(XrControllerDialog.XR_CONTROLLER_PROFILE_INDEX, 0));
        sControllerProfile.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int index, long l) {
                SharedPreferences.Editor e = prefs.edit();
                e.putInt(XrControllerDialog.XR_CONTROLLER_PROFILE_INDEX, index);
                e.commit();
                XrController.cleanMappingCache();
                updateUISpinner(activity, sControllerProfile);
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {
            }
        });
    }
}
