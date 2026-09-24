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
import android.os.Build;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.UnitUtils;
import com.winlator.cmod.widget.SeekBar;
import com.winlator.xr.XrActivity;
import com.winlator.cmod.R;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.SessionSettings;
import com.winlator.xr.io.XrController;
import com.winlator.xr.io.XrInput;
import com.winlator.xr.io.XrRenderer;
import com.winlator.xr.utils.XrEnvironment;

import java.util.ArrayList;
import java.util.List;

public class XrDialog extends ContentDialog {

    public XrDialog(Activity activity) {
        super(activity, R.layout.xr_dialog);
        setTitle(R.string.xr);

        View parent = findViewById(R.id.LLDialog);
        if (Build.MANUFACTURER.compareToIgnoreCase("PICO") == 0) {
            parent.getLayoutParams().width = (int) UnitUtils.dpToPx(UnitUtils.pxToDp(AppUtils.getScreenWidth()) * 0.5f);
        } else {
            parent.getLayoutParams().width = 1024;
        }

        CheckBox cbSBS = findViewById(R.id.CBEnableSBS);
        CheckBox cbImmersiveMode = findViewById(R.id.CBEnableImmersiveMode);
        CheckBox cbCurvedScreen = findViewById(R.id.CBEnableCurvedScreen);
        CheckBox cbPassthrough = findViewById(R.id.CBEnablePassthrough);
        TextView tvToApplyClose = findViewById(R.id.TVToApplyClose);
        CheckBox cbDisableEnvironment = findViewById(R.id.CBDisableEnvironment);
        CheckBox cbSBSStretch = findViewById(R.id.CBSBSStretch);
        CheckBox cbSBSTrim = findViewById(R.id.CBSBSTrim);
        // Passthrough already covers the space the environment would occupy, so the switch
        // would do nothing visible while it is on.
        hmdUI(activity, cbSBS, cbImmersiveMode, cbCurvedScreen, cbPassthrough, tvToApplyClose,
                () -> {
                    setViewEnabled(cbDisableEnvironment,
                            !cbPassthrough.isChecked() && !isVRGameRunning());
                    // Only offered while SBS is ticked
                    int sbsVisibility = XrActivity.isActive() && !XrActivity.isVR && cbSBS.isChecked() ? View.VISIBLE : View.GONE;
                    cbSBSStretch.setVisibility(sbsVisibility);
                    cbSBSTrim.setVisibility(sbsVisibility);
                    updateImmersiveLabel(cbImmersiveMode, XrActivity.gamepadEmulation);
                });
        SeekBar sbHeadTurn = findViewById(R.id.SBHeadTurnSensitivity);
        sbHeadTurn.setSuffix("%");
        sbHeadTurn.setValue(XrActivity.headTurnSensitivity);
        sbHeadTurn.setOnValueChangeListener((seekBar, value) -> {
            XrActivity.headTurnSensitivity = Math.round(value);
            SessionSettings.putInt(activity, XrActivity.PREF_HEAD_TURN_SENSITIVITY, XrActivity.headTurnSensitivity);
        });        loadConfig(cbSBSStretch, XrActivity.PREF_SBS_STRETCH, XrActivity.DEFAULT_SBS_STRETCH, XrActivity.sbsStretch);
        cbSBSStretch.setOnCheckedChangeListener((compoundButton, checked) -> {
            saveConfig(cbSBSStretch, XrActivity.PREF_SBS_STRETCH, checked);
            XrActivity.sbsStretch = checked;
        });
        loadConfig(cbSBSTrim, XrActivity.PREF_SBS_TRIM, XrActivity.DEFAULT_SBS_TRIM, XrActivity.sbsTrim);
        cbSBSTrim.setOnCheckedChangeListener((compoundButton, checked) -> {
            saveConfig(cbSBSTrim, XrActivity.PREF_SBS_TRIM, checked);
            XrActivity.sbsTrim = checked;
            if (XrActivity.isActive()) XrActivity.getInstance().nativeSetSbsTrim(checked ? XrActivity.SBS_TRIM_PERCENT : 0);
        });
        environmentToggleUI(activity, cbDisableEnvironment);
        frameRateUI(findViewById(R.id.CBShowFPS));
        // The environment picker is Settings-only. Importing needs a document picker, which
        // is unusable from inside a session, and the list is not something to manage mid-game.
        findViewById(R.id.TVEnvironment).setVisibility(View.GONE);
        findViewById(R.id.LLEnvironment).setVisibility(View.GONE);
        // So is the startup hint switch: by the time this menu can be opened the hint it
        // governs has already been and gone.
        findViewById(R.id.CBDisableStartupHints).setVisibility(View.GONE);

        CheckBox cbMouseLeftHanded = findViewById(R.id.CBPlayerXRMouseLeftHanded);
        CheckBox cbMouseLightgun = findViewById(R.id.CBPlayerXRMouseLightgun);
        CheckBox cbLightgunHaptic = findViewById(R.id.CBPlayerXRLightgunHaptic);
        CheckBox cbRelativeMouse = findViewById(R.id.CBRelativeMouse);
        CheckBox cbMouse = findViewById(R.id.CBPlayerXRMouse);
        CheckBox cbGamepad = findViewById(R.id.CBPlayerXRGamepad);
        CheckBox cbKeys = findViewById(R.id.CBPlayerXRKeys);
        controllerUI(cbMouseLeftHanded, cbMouseLightgun, cbLightgunHaptic, cbRelativeMouse, cbMouse, cbGamepad, cbKeys);

        // Which controller profile is in use belongs to the game; the profiles themselves
        // are a shared library and stay in the app-wide preferences.
        ListView listView = findViewById(R.id.listView);
        listView.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_list_item_single_choice, getProfileNames(activity)));
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        listView.setItemChecked(SessionSettings.getInt(activity,
                XrControllerDialog.XR_CONTROLLER_PROFILE_INDEX, 0), true);
        listView.setOnItemClickListener((adapterView, view, index, l) -> {
            SessionSettings.putInt(activity, XrControllerDialog.XR_CONTROLLER_PROFILE_INDEX, index);
            XrController.cleanMappingCache();
        });
        findViewById(R.id.LLSpinnerLayout).setVisibility(View.GONE);

        findViewById(R.id.BTCancel).setVisibility(View.GONE);
        findViewById(R.id.BTConfirm).setVisibility(View.VISIBLE);
        findViewById(R.id.BTConfirm).setOnClickListener(v -> dismiss());
        setOnConfirmCallback(this::dismiss);
    }

    /**
     * The frame rate reading drawn over the game, which is what a user would otherwise have to
     * run the DXVK HUD for. Offered here and in Settings alike: in a session it is pinned to
     * the game, from Settings it is the default every game inherits.
     *
     * Only drawn in a headset; a flat session has the HUD in the corner of the screen already.
     */
    public static void frameRateUI(CheckBox cbShowFPS) {
        loadConfig(cbShowFPS, XrActivity.PREF_SHOW_FPS, XrActivity.DEFAULT_SHOW_FPS, XrActivity.showFPS);
        cbShowFPS.setOnCheckedChangeListener((compoundButton, checked) -> {
            saveConfig(cbShowFPS, XrActivity.PREF_SHOW_FPS, checked);
            XrActivity.showFPS = checked;
        });
    }

    public static void controllerUI(CheckBox cbMouseLeftHanded, CheckBox cbMouseLightgun, CheckBox cbLightgunHaptic, CheckBox cbRelativeMouse, CheckBox cbMouse, CheckBox cbGamepad, CheckBox cbKeys) {
        loadConfig(cbMouseLeftHanded, "use_xr_leftHanded", XrActivity.DEFAULT_MOUSE_LEFT_HANDED, XrActivity.mouseLeftHanded);
        cbMouseLeftHanded.setOnCheckedChangeListener((compoundButton, checked) -> {
            saveConfig(cbMouseLeftHanded, "use_xr_leftHanded", checked);
            XrActivity.mouseLeftHanded = checked;
        });

        loadConfig(cbMouseLightgun, "use_xr_lightgun", XrActivity.DEFAULT_MOUSE_LIGHTGUN, XrActivity.mouseLightgun);
        cbMouseLightgun.setOnCheckedChangeListener((compoundButton, checked) -> {
            saveConfig(cbMouseLightgun, "use_xr_lightgun", checked);
            XrActivity.mouseLightgun = checked;
            if (checked) cbRelativeMouse.setChecked(false);
            cbRelativeMouse.setEnabled(!checked && cbMouse.isChecked());
            cbLightgunHaptic.setVisibility(checked ? View.VISIBLE : View.GONE);
        });

        loadConfig(cbLightgunHaptic, "use_xr_lightgun_haptic", XrActivity.DEFAULT_LIGHTGUN_HAPTIC, XrActivity.lightgunHaptic);
        cbLightgunHaptic.setOnCheckedChangeListener((compoundButton, checked) -> {
            saveConfig(cbLightgunHaptic, "use_xr_lightgun_haptic", checked);
            XrActivity.lightgunHaptic = checked;
        });
        cbLightgunHaptic.setVisibility(cbMouseLightgun.isChecked() ? View.VISIBLE : View.GONE);

        loadConfig(cbRelativeMouse, "use_xr_relative_mouse", XrActivity.DEFAULT_MOUSE_RELATIVE, XrActivity.mouseRelative);
        cbRelativeMouse.setOnCheckedChangeListener((compoundButton, checked) -> {
            saveConfig(cbRelativeMouse, "use_xr_relative_mouse", checked);
            XrActivity.mouseRelative = checked;
            if (XrActivity.isActive()) {
                XrActivity.getInstance().setRelativeMouseMovement(checked);
            }
        });

        loadConfig(cbMouse, "use_xr_mouse", XrActivity.DEFAULT_MOUSE, XrActivity.mouseEmulation);
        cbMouse.setOnCheckedChangeListener((compoundButton, checked) -> {
            saveConfig(cbMouse, "use_xr_mouse", checked);
            XrActivity.mouseEmulation = checked;
            cbMouseLeftHanded.setEnabled(checked);
            cbMouseLightgun.setEnabled(checked);
            cbLightgunHaptic.setEnabled(checked);
            cbRelativeMouse.setEnabled(checked && !cbMouseLightgun.isChecked());
        });
        cbMouseLeftHanded.setEnabled(cbMouse.isChecked());
        cbMouseLightgun.setEnabled(cbMouse.isChecked());
        cbLightgunHaptic.setEnabled(cbMouse.isChecked());
        cbRelativeMouse.setEnabled(cbMouse.isChecked() && !cbMouseLightgun.isChecked());

        loadConfig(cbGamepad, "use_xr_gamepad", XrActivity.DEFAULT_GAMEPAD, XrActivity.gamepadEmulation);
        updateImmersiveLabel(cbGamepad, cbGamepad.isChecked());
        cbGamepad.setOnCheckedChangeListener((compoundButton, checked) -> {
            saveConfig(cbGamepad, "use_xr_gamepad", checked);
            XrActivity.gamepadEmulation = checked;
            updateImmersiveLabel(cbGamepad, checked);
            if (checked && XrActivity.isActive()) {
                XrInput.ensureVirtualControllerAttached();
            }
        });

        loadConfig(cbKeys, "use_xr_keys", XrActivity.DEFAULT_KEYS, XrActivity.keysEmulation);
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
                                     TextView tvEnvironment, View btImport, View btRemove,
                                     Runnable onImport) {
        boolean supported = !XrActivity.isActive() ||
                XrActivity.getInstance().nativeIsEnvironmentSupported();
        if (!supported) {
            sEnvironment.setVisibility(View.GONE);
            tvEnvironment.setVisibility(View.GONE);
            btImport.setVisibility(View.GONE);
            btRemove.setVisibility(View.GONE);
            return;
        }

        btImport.setOnClickListener(v -> onImport.run());
        btRemove.setOnClickListener(v -> {
            String selected = XrEnvironment.getSelected(activity);
            if (selected.isEmpty()) return;
            ContentDialog.confirm(activity,
                    activity.getString(R.string.xr_environment_remove_message, selected), () -> {
                XrEnvironment.delete(activity, selected);
                // The removed panorama cannot stay selected, so fall back to None, which also
                // clears it from a running session.
                selectEnvironment(activity, sEnvironment, btRemove, "");
            });
        });

        selectEnvironment(activity, sEnvironment, btRemove, XrEnvironment.getSelected(activity));
    }

    /**
     * Rebuilds the picker's contents and selects the named panorama, applying it if that is
     * a change. Also used after an import, to show the file that was just added.
     */
    public static void selectEnvironment(Activity activity, Spinner sEnvironment, View btRemove,
                                         String name) {
        List<String> files = XrEnvironment.list(activity);
        List<String> labels = new ArrayList<>();
        labels.add(activity.getString(R.string.xr_environment_none));
        labels.addAll(files);

        sEnvironment.setOnItemSelectedListener(null);
        sEnvironment.setAdapter(new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_dropdown_item, labels));
        // Index 0 is "None", so a stored name maps to its position in the file list plus one.
        sEnvironment.setSelection(files.indexOf(name) + 1);
        updateRemoveEnabled(sEnvironment, btRemove);

        sEnvironment.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateRemoveEnabled(sEnvironment, btRemove);
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

    /** There is nothing to remove while "None" is picked, or while the row is greyed out. */
    private static void updateRemoveEnabled(Spinner sEnvironment, View btRemove) {
        if (btRemove == null) return;
        setViewEnabled(btRemove, sEnvironment.isEnabled() && sEnvironment.getSelectedItemPosition() > 0);
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

        // A native VR title renders a full projection layer over the panorama and has no
        // frame time to spare compositing one, so it is forced off for the whole session.
        // Say so rather than leaving a switch that would appear to do nothing.
        if (isVRGameRunning()) {
            cbDisableEnvironment.setText(activity.getString(R.string.xr_not_available_in_vr,
                    activity.getString(R.string.disable_environment)));
            setUnavailableInVR(cbDisableEnvironment);
        }
    }

    /**
     * Greys out the environment picker. Used when passthrough already owns the space behind
     * the screen, so the setting would have no visible effect if it were changed.
     */
    public static void setEnvironmentEnabled(Spinner sEnvironment, TextView tvEnvironment,
                                             View btImport, View btRemove, boolean enabled) {
        setViewEnabled(sEnvironment, enabled);
        setViewEnabled(tvEnvironment, enabled);
        setViewEnabled(btImport, enabled);
        // Remove also depends on something being selected, so let that decide once the row
        // itself is enabled again.
        updateRemoveEnabled(sEnvironment, btRemove);
    }

    /**
     * Whether an XrAPI title is currently in VR mode. Unlike {@link XrActivity#getVR()} this
     * stays true while a dialog is up, which is exactly when the settings below are read.
     */
    public static boolean isVRGameRunning() {
        return XrActivity.isActive() && XrActivity.isVR;
    }

    /**
     * Disables a control that a running VR game has taken over. Dims it less than
     * {@link #setViewEnabled}: the label has to explain itself, and at 0.4 the text is hard
     * to read on a panel a couple of metres away.
     */
    public static void setUnavailableInVR(View view) {
        view.setEnabled(false);
        view.setAlpha(0.8f);
    }

    /** Disables a control and dims it, since setEnabled alone is easy to miss. */
    public static void setViewEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1.0f : 0.4f);
    }

    // Gamepad head-look adds walking to the head tracking, so immersive mode becomes 5DoF
    private static void updateImmersiveLabel(View anyView, boolean gamepad) {
        View root = anyView.getRootView();
        CheckBox cbImmersiveMode = root.findViewById(R.id.CBEnableImmersiveMode);
        if (cbImmersiveMode == null) return;
        cbImmersiveMode.setText(gamepad ? R.string.use_immersive_mode_5dof : R.string.use_immersive_mode);
        // The head-turn slider only does anything in 5DoF
        int visibility = XrActivity.isActive() && gamepad && cbImmersiveMode.isChecked() ? View.VISIBLE : View.GONE;
        View headTurnSensitivity = root.findViewById(R.id.LLHeadTurnSensitivity);
        if (headTurnSensitivity != null) headTurnSensitivity.setVisibility(visibility);
    }

    public static void hmdUI(Activity activity, CheckBox cbSBS, CheckBox cbImmersiveMode, CheckBox cbCurvedScreen, CheckBox cbPassthrough, TextView tvToApplyClose, Runnable onChanged) {
        boolean isImmersive = XrActivity.isImmersive;

        if (XrActivity.isActive()) {
            setViewEnabled(cbSBS, XrActivity.lastMode3D < 0 && !XrRenderer.isDirectActive());
            cbSBS.setChecked(XrActivity.isSBS);
            cbImmersiveMode.setEnabled(!XrActivity.isUDP);
            cbImmersiveMode.setChecked(isImmersive);
        } else {
            cbSBS.setVisibility(View.GONE);
            cbImmersiveMode.setVisibility(View.GONE);
        }
        cbCurvedScreen.setChecked(SessionSettings.getBoolean(activity, "use_cs", XrActivity.DEFAULT_CURVED_SCREEN));
        cbPassthrough.setEnabled(!isImmersive);
        cbPassthrough.setChecked(SessionSettings.getBoolean(activity, "use_pt", XrActivity.DEFAULT_PASSTHROUGH));

        Runnable applyAll = () -> {
            SessionSettings.edit(activity)
                    .putBoolean("use_cs", cbCurvedScreen.isChecked())
                    .putBoolean("use_pt", cbPassthrough.isChecked())
                    .apply();

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
            cb.setChecked(SessionSettings.getBoolean(cb.getContext(), key, defValue));
        }
    }

    /**
     * In a session this pins the value to the game that is running; from Settings, where
     * there is no session, it sets the default every game inherits. Either way the write is
     * on disk before this returns, because a session ends by killing its own process.
     */
    private static void saveConfig(CheckBox cb, String key, boolean value) {
        SessionSettings.putBoolean(cb.getContext(), key, value);
    }
}
