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
import android.content.SharedPreferences;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import com.winlator.xr.XrActivity;
import com.winlator.cmod.R;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.xr.io.XrController;
import com.winlator.xr.io.XrInput;
import com.winlator.xr.utils.XrEnvironment;

import java.util.ArrayList;
import java.util.List;

public class XrDialog extends ContentDialog {

    public XrDialog(Activity activity) {
        super(activity, R.layout.xr_dialog);
        setTitle(R.string.xr);

        CheckBox cbSBS = findViewById(R.id.CBEnableSBS);
        CheckBox cbImmersiveMode = findViewById(R.id.CBEnableImmersiveMode);
        CheckBox cbCurvedScreen = findViewById(R.id.CBEnableCurvedScreen);
        CheckBox cbPassthrough = findViewById(R.id.CBEnablePassthrough);
        TextView tvToApplyClose = findViewById(R.id.TVToApplyClose);
        CheckBox cbDisableEnvironment = findViewById(R.id.CBDisableEnvironment);
        // Passthrough already covers the space the environment would occupy, so the switch
        // would do nothing visible while it is on.
        hmdUI(activity, cbSBS, cbImmersiveMode, cbCurvedScreen, cbPassthrough, tvToApplyClose,
                () -> setViewEnabled(cbDisableEnvironment, !cbPassthrough.isChecked()));
        environmentToggleUI(activity, cbDisableEnvironment);
        // The environment picker is Settings-only. Importing needs a document picker, which
        // is unusable from inside a session, and the list is not something to manage mid-game.
        findViewById(R.id.TVEnvironment).setVisibility(View.GONE);
        findViewById(R.id.LLEnvironment).setVisibility(View.GONE);

        CheckBox cbMouseLeftHanded = findViewById(R.id.CBPlayerXRMouseLeftHanded);
        CheckBox cbMouseLightgun = findViewById(R.id.CBPlayerXRMouseLightgun);
        CheckBox cbRelativeMouse = findViewById(R.id.CBRelativeMouse);
        CheckBox cbMouse = findViewById(R.id.CBPlayerXRMouse);
        CheckBox cbGamepad = findViewById(R.id.CBPlayerXRGamepad);
        CheckBox cbKeys = findViewById(R.id.CBPlayerXRKeys);
        controllerUI(cbMouseLeftHanded, cbMouseLightgun, cbRelativeMouse, cbMouse, cbGamepad, cbKeys);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);

        ListView listView = findViewById(R.id.listView);
        listView.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_list_item_single_choice, getProfileNames(activity)));
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        listView.setItemChecked(prefs.getInt(XrControllerDialog.XR_CONTROLLER_PROFILE_INDEX, 0), true);
        listView.setOnItemClickListener((adapterView, view, index, l) -> {
            SharedPreferences.Editor e = prefs.edit();
            e.putInt(XrControllerDialog.XR_CONTROLLER_PROFILE_INDEX, index);
            e.commit();
            XrController.cleanMappingCache();
        });
        findViewById(R.id.LLSpinnerLayout).setVisibility(View.GONE);

        findViewById(R.id.BTCancel).setVisibility(View.GONE);
        findViewById(R.id.BTConfirm).setVisibility(View.VISIBLE);
        findViewById(R.id.BTConfirm).setOnClickListener(v -> dismiss());
        setOnConfirmCallback(this::dismiss);
    }

    public static void controllerUI(CheckBox cbMouseLeftHanded, CheckBox cbMouseLightgun, CheckBox cbRelativeMouse, CheckBox cbMouse, CheckBox cbGamepad, CheckBox cbKeys) {
        loadConfig(cbMouseLeftHanded, "use_xr_leftHanded", false, XrActivity.mouseLeftHanded);
        cbMouseLeftHanded.setOnCheckedChangeListener((compoundButton, checked) -> {
            saveConfig(cbMouseLeftHanded, "use_xr_leftHanded", checked);
            XrActivity.mouseLeftHanded = checked;
        });

        loadConfig(cbMouseLightgun, "use_xr_lightgun", false, XrActivity.mouseLightgun);
        cbMouseLightgun.setOnCheckedChangeListener((compoundButton, checked) -> {
            saveConfig(cbMouseLightgun, "use_xr_lightgun", checked);
            XrActivity.mouseLightgun = checked;
            if (checked) cbRelativeMouse.setChecked(false);
            cbRelativeMouse.setEnabled(!checked && cbMouse.isChecked());
        });

        loadConfig(cbRelativeMouse, "use_xr_relative_mouse", false, XrActivity.mouseRelative);
        cbRelativeMouse.setOnCheckedChangeListener((compoundButton, checked) -> {
            saveConfig(cbRelativeMouse, "use_xr_relative_mouse", checked);
            XrActivity.mouseRelative = checked;
            if (XrActivity.isActive()) {
                XrActivity.getInstance().setRelativeMouseMovement(checked);
            }
        });

        loadConfig(cbMouse, "use_xr_mouse", true, XrActivity.mouseEmulation);
        cbMouse.setOnCheckedChangeListener((compoundButton, checked) -> {
            saveConfig(cbMouse, "use_xr_mouse", checked);
            XrActivity.mouseEmulation = checked;
            cbMouseLeftHanded.setEnabled(checked);
            cbMouseLightgun.setEnabled(checked);
            cbRelativeMouse.setEnabled(checked && !cbMouseLightgun.isChecked());
        });
        cbMouseLeftHanded.setEnabled(cbMouse.isChecked());
        cbMouseLightgun.setEnabled(cbMouse.isChecked());
        cbRelativeMouse.setEnabled(cbMouse.isChecked() && !cbMouseLightgun.isChecked());

        loadConfig(cbGamepad, "use_xr_gamepad", false, XrActivity.gamepadEmulation);
        cbGamepad.setOnCheckedChangeListener((compoundButton, checked) -> {
            saveConfig(cbGamepad, "use_xr_gamepad", checked);
            XrActivity.gamepadEmulation = checked;
            if (checked && XrActivity.isActive()) {
                XrInput.ensureVirtualControllerAttached();
            }
        });

        loadConfig(cbKeys, "use_xr_keys", false, XrActivity.keysEmulation);
        cbKeys.setOnCheckedChangeListener((compoundButton, checked) -> {
            saveConfig(cbKeys, "use_xr_keys", checked);
            XrActivity.keysEmulation = checked;
        });
    }

    /**
     * Populates the 360 environment picker from the panoramas in the environments folder.
     * Hidden entirely when the runtime has no equirect2 support, since there is nothing the
     * setting could do there. Settings-only: XrDialog hides the whole row instead.
     */
    public static void environmentUI(Activity activity, Spinner sEnvironment,
                                     TextView tvEnvironment, View btImport, Runnable onImport) {
        boolean supported = !XrActivity.isActive() ||
                XrActivity.getInstance().nativeIsEnvironmentSupported();
        if (!supported) {
            sEnvironment.setVisibility(View.GONE);
            tvEnvironment.setVisibility(View.GONE);
            btImport.setVisibility(View.GONE);
            return;
        }

        btImport.setOnClickListener(v -> onImport.run());

        selectEnvironment(activity, sEnvironment, XrEnvironment.getSelected(activity));
    }

    /**
     * Rebuilds the picker's contents and selects the named panorama, applying it if that is
     * a change. Also used after an import, to show the file that was just added.
     */
    public static void selectEnvironment(Activity activity, Spinner sEnvironment, String name) {
        List<String> files = XrEnvironment.list(activity);
        List<String> labels = new ArrayList<>();
        labels.add(activity.getString(R.string.xr_environment_none));
        labels.addAll(files);

        sEnvironment.setOnItemSelectedListener(null);
        sEnvironment.setAdapter(new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_dropdown_item, labels));
        // Index 0 is "None", so a stored name maps to its position in the file list plus one.
        sEnvironment.setSelection(files.indexOf(name) + 1);

        sEnvironment.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selected = position <= 0 ? "" : files.get(position - 1);
                if (selected.equals(XrEnvironment.getSelected(activity))) return;
                applySelection(activity, selected);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Spinner posts its selection callback rather than firing it inline, so apply the
        // change here instead of depending on that timing. The guard above keeps the posted
        // callback from repeating the work.
        if (!name.equals(XrEnvironment.getSelected(activity))) {
            applySelection(activity, name);
        }
    }

    private static void applySelection(Activity activity, String name) {
        XrEnvironment.setSelected(activity, name);
        // Deliberately picking a panorama overrides a previous "Disable environment",
        // otherwise choosing one from Settings would appear to do nothing.
        if (!name.isEmpty()) XrEnvironment.setEnabled(activity, true);
        XrEnvironment.apply(activity, name);
    }

    public static List<String> getProfileNames(Activity activity) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < prefs.getInt(XrControllerDialog.XR_CONTROLLER_PROFILE_COUNT, 1); i++)
            names.add(prefs.getString(XrControllerDialog.XR_CONTROLLER_PROFILE_NAME + i, "Default profile"));
        return names;
    }

    /**
     * The in-session switch for the 360 environment. Only shown once a panorama has actually
     * been chosen in Settings, since with nothing selected there is nothing to switch off.
     * Unlike picking "None" it leaves the selection alone, so it can be switched back on.
     */
    public static void environmentToggleUI(Activity activity, CheckBox cbDisableEnvironment) {
        boolean supported = !XrActivity.isActive() ||
                XrActivity.getInstance().nativeIsEnvironmentSupported();
        boolean selected = !XrEnvironment.getSelected(activity).isEmpty();
        if (!supported || !selected) {
            cbDisableEnvironment.setVisibility(View.GONE);
            return;
        }

        cbDisableEnvironment.setVisibility(View.VISIBLE);
        cbDisableEnvironment.setChecked(!XrEnvironment.isEnabled(activity));
        cbDisableEnvironment.setOnCheckedChangeListener(
                (compoundButton, checked) -> XrEnvironment.setEnabled(activity, !checked));
    }

    /**
     * Greys out the environment picker. Used when passthrough already owns the space behind
     * the screen, so the setting would have no visible effect if it were changed.
     */
    public static void setEnvironmentEnabled(Spinner sEnvironment, TextView tvEnvironment,
                                             View btImport, boolean enabled) {
        setViewEnabled(sEnvironment, enabled);
        setViewEnabled(tvEnvironment, enabled);
        setViewEnabled(btImport, enabled);
    }

    /** Disables a control and dims it, since setEnabled alone is easy to miss. */
    public static void setViewEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1.0f : 0.4f);
    }

    public static void hmdUI(Activity activity, CheckBox cbSBS, CheckBox cbImmersiveMode, CheckBox cbCurvedScreen, CheckBox cbPassthrough, TextView tvToApplyClose, Runnable onChanged) {
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
            XrActivity.isPassthrough = cbPassthrough.isChecked();
            XrActivity.isImmersive = cbImmersiveMode.isChecked();
            XrActivity instance = XrActivity.getInstance();
            if (XrActivity.isActive()) {
                instance.nativeSetCurvedScreen(cbCurvedScreen.isChecked());
                instance.nativeSetUsePT(cbPassthrough.isChecked());
            }

            boolean warn = (XrActivity.isImmersive != isImmersive) && XrActivity.isActive();
            tvToApplyClose.setVisibility(warn ? View.VISIBLE : View.GONE);
            if (onChanged != null) onChanged.run();
        };

        // Apply changes immediately
        cbSBS.setOnCheckedChangeListener((compoundButton, b) -> applyAll.run());
        cbImmersiveMode.setOnCheckedChangeListener((compoundButton, b) -> applyAll.run());
        cbCurvedScreen.setOnCheckedChangeListener((compoundButton, b) -> applyAll.run());
        cbPassthrough.setOnCheckedChangeListener((compoundButton, b) -> applyAll.run());

        // Settle whatever depends on these before the user has touched anything.
        if (onChanged != null) onChanged.run();
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
