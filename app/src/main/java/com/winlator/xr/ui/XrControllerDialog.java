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

import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import com.winlator.cmod.R;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.xserver.XKeycode;
import com.winlator.xr.XrController;

import java.util.ArrayList;

public class XrControllerDialog extends ContentDialog {
    public XrControllerDialog(Context context) {
        super(context, R.layout.xr_controller_dialog);
        setIcon(R.drawable.icon_gamepad);
        setTitle(context.getString(R.string.primary_controller));

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

        setOnConfirmCallback(this::saveMapping);
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

    private void saveMapping() {
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
        XrController.setMapping(getContext(), new String(output));
    }
}
