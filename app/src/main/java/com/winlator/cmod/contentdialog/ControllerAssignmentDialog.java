package com.winlator.cmod.contentdialog;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.view.ContextThemeWrapper;
import android.view.InputDevice;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import com.winlator.xr.XrActivity;
import com.winlator.cmod.R;
import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.cmod.inputcontrols.ControllerManager;
import com.winlator.cmod.inputcontrols.PreferenceKeys;
import com.winlator.cmod.winhandler.WinHandler;

public class ControllerAssignmentDialog {
    private final ContentDialog dialog;
    private final ControllerManager controllerManager;
    private final WinHandler winHandler;     // may be null
    private final Activity hostActivity;

    private final CheckBox[] checkBoxes = new CheckBox[4];
    private final TextView[] deviceNameTextViews = new TextView[4];
    private final Button[] assignButtons = new Button[4];
    private final Button[] btnMacros = new Button[4];   // <-- Button array
    private final CheckBox[] vibrateBoxes = new CheckBox[4];
    private final Button[] resetButtons = new Button[4];

    private final TextView restartRequiredView;
    private final int initialPlayerCount;
    private static ControllerAssignmentDialog instance = null;

    // ---------- Public entry points -----------------------------------------

    /** Legacy call (e.g., from MainActivity). Will try to grab WinHandler if context is XServerDisplayActivity. */
    public static void show(Context context) {
        show(context, extractWinHandler(context));
    }

    /** Preferred call when you already have the handler. */
    public static void show(Context context, WinHandler winHandler) {
        int initialPlayerCount = ControllerManager.getInstance().getEnabledPlayerCount();
        Activity act = (Activity) context; // all current callers pass an Activity
        instance = new ControllerAssignmentDialog(act, initialPlayerCount, winHandler);
        instance.showContentDialog();
    }

    public static void dismiss() {
        try {
            instance.dialog.dismiss();
        } catch (Exception e) {
        }
    }

    private static WinHandler extractWinHandler(Context ctx) {
        if (ctx instanceof XServerDisplayActivity) {
            return ((XServerDisplayActivity) ctx).getWinHandler();
        }
        return null;
    }

    // ---------- Impl ---------------------------------------------------------

    private ControllerAssignmentDialog(Activity activity, int initialPlayerCount, WinHandler winHandler) {
        boolean dark = PreferenceManager.getDefaultSharedPreferences(activity)
                .getBoolean("dark_mode", false);

        ContextThemeWrapper themed =
                new ContextThemeWrapper(activity, dark ? R.style.ContentDialog : R.style.AppTheme);

        this.dialog = new ContentDialog(themed, R.layout.controller_assignment_dialog);
        this.dialog.setTitle(R.string.controller_manager);

        this.controllerManager = ControllerManager.getInstance();
        this.initialPlayerCount = initialPlayerCount;
        this.winHandler = winHandler;     // can be null
        this.hostActivity = activity;

        initializeViews();

        restartRequiredView = dialog.getContentView().findViewById(R.id.TVRestartRequired);

        if (dark) {
            View root = dialog.getContentView();
            if (root instanceof ViewGroup) setTextColorForDialog((ViewGroup) root, 0xFFFFFFFF);
        }

        populateView();
        setupListeners();
    }

    private static int dp(Context c, int v){
        return Math.round(c.getResources().getDisplayMetrics().density * v);
    }

    @SuppressWarnings("deprecation")
    public void showContentDialog() {
        dialog.show();
        Window w = dialog.getWindow();
        if (w == null) return;

        int widthPx;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.view.WindowMetrics metrics = w.getWindowManager().getCurrentWindowMetrics();
            android.graphics.Rect b = metrics.getBounds();
            widthPx = b.width();
        } else {
            Point p = new Point();
            w.getWindowManager().getDefaultDisplay().getSize(p);
            widthPx = p.x;
        }

        int capPx = dp(dialog.getContext(), 540);
        int target = Math.min((int) (widthPx * 0.90f), capPx);
        w.setLayout(target, WindowManager.LayoutParams.WRAP_CONTENT);

        // Initialize the "Configure Analog Sticks" button
        View view = dialog.getContentView();
        Button btConfigureAnalogSticks = view.findViewById(R.id.BTConfigureAnalogSticks);
        btConfigureAnalogSticks.setOnClickListener(v -> showAnalogStickConfigDialog(view.getContext()));
        btConfigureAnalogSticks.setVisibility(XrActivity.isActive() ? View.GONE : View.VISIBLE);

        // Player XR
        LinearLayout xr = view.findViewById(R.id.PlayerXR);
        if (XrActivity.isEnabled(view.getContext())) {
            xr.setVisibility(View.VISIBLE);

            CheckBox cbMouseLeftHanded = view.findViewById(R.id.CBPlayerXRMouseLeftHanded);
            loadConfig(cbMouseLeftHanded, "use_xr_leftHanded", false, XrActivity.mouseLeftHanded);
            cbMouseLeftHanded.setOnCheckedChangeListener((compoundButton, checked) -> {
                saveConfig(view, "use_xr_leftHanded", checked);
                XrActivity.mouseLeftHanded = checked;
            });

            CheckBox cbMouseLightgun = view.findViewById(R.id.CBPlayerXRMouseLightgun);
            loadConfig(cbMouseLightgun, "use_xr_lightgun", false, XrActivity.mouseLightgun);
            cbMouseLightgun.setOnCheckedChangeListener((compoundButton, checked) -> {
                saveConfig(view, "use_xr_lightgun", checked);
                XrActivity.mouseLightgun = checked;
            });

            CheckBox cbMouse = view.findViewById(R.id.CBPlayerXRMouse);
            loadConfig(cbMouse, "use_xr_mouse", true, XrActivity.mouseEmulation);
            cbMouse.setOnCheckedChangeListener((compoundButton, checked) -> {
                saveConfig(view, "use_xr_mouse", checked);
                XrActivity.mouseEmulation = checked;
                cbMouseLeftHanded.setEnabled(checked);
                cbMouseLightgun.setEnabled(checked);
            });
            cbMouseLeftHanded.setEnabled(cbMouse.isChecked());
            cbMouseLightgun.setEnabled(cbMouse.isChecked());
        } else {
            xr.setVisibility(View.GONE);
        }
    }

    private void loadConfig(CheckBox cb, String key, boolean defValue, boolean curValue) {
        if (XrActivity.isActive()) {
            cb.setChecked(curValue);
        } else {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(cb.getContext());
            cb.setChecked(prefs.getBoolean(key, defValue));
        }
    }

    private void saveConfig(View view, String key, boolean value) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(view.getContext());
        SharedPreferences.Editor e = prefs.edit();
        e.putBoolean(key, value);
        e.apply();
    }

    private void initializeViews() {
        View view = dialog.getContentView();

        // Player 1
        checkBoxes[0] = view.findViewById(R.id.CBPlayer1);
        deviceNameTextViews[0] = view.findViewById(R.id.TVPlayer1DeviceName);
        assignButtons[0] = view.findViewById(R.id.BTNAssignP1);
        vibrateBoxes[0] = view.findViewById(R.id.CBVibrateP1);
        resetButtons[0] = view.findViewById(R.id.BTNResetP1);
        btnMacros[0] = view.findViewById(R.id.BTNMacrosP1);
        btnMacros[0].setOnClickListener(v ->
                com.winlator.cmod.contentdialog.MacrosDialog.show(hostActivity, 0, winHandler));

        // Player 2
        checkBoxes[1] = view.findViewById(R.id.CBPlayer2);
        deviceNameTextViews[1] = view.findViewById(R.id.TVPlayer2DeviceName);
        assignButtons[1] = view.findViewById(R.id.BTNAssignP2);
        vibrateBoxes[1] = view.findViewById(R.id.CBVibrateP2);
        resetButtons[1] = view.findViewById(R.id.BTNResetP2);
        btnMacros[1] = view.findViewById(R.id.BTNMacrosP2);
        btnMacros[1].setOnClickListener(v ->
                com.winlator.cmod.contentdialog.MacrosDialog.show(hostActivity, 1, winHandler));

        // Player 3
        checkBoxes[2] = view.findViewById(R.id.CBPlayer3);
        deviceNameTextViews[2] = view.findViewById(R.id.TVPlayer3DeviceName);
        assignButtons[2] = view.findViewById(R.id.BTNAssignP3);
        vibrateBoxes[2] = view.findViewById(R.id.CBVibrateP3);
        resetButtons[2] = view.findViewById(R.id.BTNResetP3);
        btnMacros[2] = view.findViewById(R.id.BTNMacrosP3);
        btnMacros[2].setOnClickListener(v ->
                com.winlator.cmod.contentdialog.MacrosDialog.show(hostActivity, 2, winHandler));

        // Player 4
        checkBoxes[3] = view.findViewById(R.id.CBPlayer4);
        deviceNameTextViews[3] = view.findViewById(R.id.TVPlayer4DeviceName);
        assignButtons[3] = view.findViewById(R.id.BTNAssignP4);
        vibrateBoxes[3] = view.findViewById(R.id.CBVibrateP4);
        resetButtons[3] = view.findViewById(R.id.BTNResetP4);
        btnMacros[3] = view.findViewById(R.id.BTNMacrosP4);
        btnMacros[3].setOnClickListener(v ->
                com.winlator.cmod.contentdialog.MacrosDialog.show(hostActivity, 3, winHandler));
    }

    private void populateView() {
        controllerManager.scanForDevices();

        for (int i = 0; i < 4; i++) {
            checkBoxes[i].setChecked(controllerManager.isSlotEnabled(i));
            if (vibrateBoxes[i] != null) {
                vibrateBoxes[i].setChecked(controllerManager.isVibrationEnabled(i));
            }
            InputDevice device = controllerManager.getAssignedDeviceForSlot(i);
            deviceNameTextViews[i].setText(
                    device != null ? device.getName() : dialog.getContext().getString(R.string.not_assigned)
            );
            deviceNameTextViews[i].setSelected(true);
        }
    }

    private void setupListeners() {
        for (int i = 0; i < 4; i++) {
            final int slotIndex = i;

            checkBoxes[i].setOnCheckedChangeListener((buttonView, isChecked) -> {
                controllerManager.setSlotEnabled(slotIndex, isChecked);
                if (!isChecked) {
                    for (int j = slotIndex + 1; j < 4; j++) {
                        if (controllerManager.isSlotEnabled(j)) controllerManager.setSlotEnabled(j, false);
                    }
                } else {
                    for (int j = 0; j < slotIndex; j++) {
                        if (!controllerManager.isSlotEnabled(j)) controllerManager.setSlotEnabled(j, true);
                    }
                }
                populateView();

                if (controllerManager.getEnabledPlayerCount() != initialPlayerCount) {
                    restartRequiredView.setVisibility(View.VISIBLE);
                } else {
                    restartRequiredView.setVisibility(View.GONE);
                }
            });

            vibrateBoxes[i].setOnCheckedChangeListener((b, checked) ->
                    controllerManager.setVibrationEnabled(slotIndex, checked));

            resetButtons[i].setOnClickListener(v -> {
                controllerManager.unassignSlot(slotIndex);
                populateView();
            });

            assignButtons[i].setOnClickListener(v -> {
                String message = dialog.getContext().getString(R.string.press_any_button_for_player) + " " + (slotIndex + 1);
                dialog.setMessage(message);

                dialog.setOnControllerInputListener(device -> {
                    if (!ControllerManager.isGameController(device)) return;
                    controllerManager.assignDeviceToSlot(slotIndex, device);
                    dialog.setMessage(null);
                    dialog.setOnControllerInputListener(null);
                    populateView();
                });
            });
        }

        dialog.setOnConfirmCallback(() -> controllerManager.saveAssignments());
    }

    private void setTextColorForDialog(ViewGroup viewGroup, int color) {
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            if (child instanceof ViewGroup) {
                setTextColorForDialog((ViewGroup) child, color);
            } else if (child instanceof TextView) {
                ((TextView) child).setTextColor(color);
            }
        }
    }

    private void showAnalogStickConfigDialog(Context context) {
        // Inflate the dialog layout
        LayoutInflater inflater = LayoutInflater.from(context);
        View dialogView = inflater.inflate(R.layout.analog_stick_config_dialog, null);

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setView(dialogView);
        builder.setTitle("Configure Analog Sticks");
        builder.setCancelable(false);

        // Initialize UI elements
        SeekBar sbLeftDeadzone = dialogView.findViewById(R.id.SBLeftDeadzone);
        TextView tvLeftDeadzone = dialogView.findViewById(R.id.TVLeftDeadzone);

        SeekBar sbLeftSensitivity = dialogView.findViewById(R.id.SBLeftSensitivity);
        TextView tvLeftSensitivity = dialogView.findViewById(R.id.TVLeftSensitivity);

        SeekBar sbRightDeadzone = dialogView.findViewById(R.id.SBRightDeadzone);
        TextView tvRightDeadzone = dialogView.findViewById(R.id.TVRightDeadzone);

        SeekBar sbRightSensitivity = dialogView.findViewById(R.id.SBRightSensitivity);
        TextView tvRightSensitivity = dialogView.findViewById(R.id.TVRightSensitivity);

        CheckBox cbInvertLeftX = dialogView.findViewById(R.id.CBInvertLeftStickX);
        CheckBox cbInvertLeftY = dialogView.findViewById(R.id.CBInvertLeftStickY);
        CheckBox cbInvertRightX = dialogView.findViewById(R.id.CBInvertRightStickX);
        CheckBox cbInvertRightY = dialogView.findViewById(R.id.CBInvertRightStickY);

        // New checkbox for square deadzone
        CheckBox cbLeftStickSquareDeadzone = dialogView.findViewById(R.id.CBLeftStickSquareDeadzone);

        // Load current preferences
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        float currentDeadzoneLeft = preferences.getFloat(PreferenceKeys.DEADZONE_LEFT, 0.1f) * 100; // Convert to percentage
        float currentDeadzoneRight = preferences.getFloat(PreferenceKeys.DEADZONE_RIGHT, 0.1f) * 100;
        float currentSensitivityLeft = preferences.getFloat(PreferenceKeys.SENSITIVITY_LEFT, 1.0f) * 100; // Convert to percentage
        float currentSensitivityRight = preferences.getFloat(PreferenceKeys.SENSITIVITY_RIGHT, 1.0f) * 100;
        boolean squareDeadzoneLeft = preferences.getBoolean(PreferenceKeys.SQUARE_DEADZONE_LEFT, false);

        boolean invertLeftX = preferences.getBoolean(PreferenceKeys.INVERT_LEFT_X, false);
        boolean invertLeftY = preferences.getBoolean(PreferenceKeys.INVERT_LEFT_Y, false);
        boolean invertRightX = preferences.getBoolean(PreferenceKeys.INVERT_RIGHT_X, false);
        boolean invertRightY = preferences.getBoolean(PreferenceKeys.INVERT_RIGHT_Y, false);

        // Set initial values
        sbLeftDeadzone.setProgress((int) currentDeadzoneLeft);
        tvLeftDeadzone.setText("Deadzone: " + sbLeftDeadzone.getProgress() + "%");

        sbLeftSensitivity.setProgress((int) currentSensitivityLeft);
        tvLeftSensitivity.setText("Sensitivity: " + sbLeftSensitivity.getProgress() + "%");

        sbRightDeadzone.setProgress((int) currentDeadzoneRight);
        tvRightDeadzone.setText("Deadzone: " + sbRightDeadzone.getProgress() + "%");

        sbRightSensitivity.setProgress((int) currentSensitivityRight);
        tvRightSensitivity.setText("Sensitivity: " + sbRightSensitivity.getProgress() + "%");

        cbInvertLeftX.setChecked(invertLeftX);
        cbInvertLeftY.setChecked(invertLeftY);
        cbInvertRightX.setChecked(invertRightX);
        cbInvertRightY.setChecked(invertRightY);

        cbLeftStickSquareDeadzone.setChecked(squareDeadzoneLeft);

        // Set listeners to update TextViews as SeekBars change
        sbLeftDeadzone.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvLeftDeadzone.setText("Deadzone: " + progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        sbLeftSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvLeftSensitivity.setText("Sensitivity: " + progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        sbRightDeadzone.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvRightDeadzone.setText("Deadzone: " + progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        sbRightSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvRightSensitivity.setText("Sensitivity: " + progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Set up the dialog buttons
        builder.setPositiveButton("Save", (dialog, which) -> {
            // Retrieve and save the updated settings
            float newDeadzoneLeft = sbLeftDeadzone.getProgress() / 100.0f;
            float newDeadzoneRight = sbRightDeadzone.getProgress() / 100.0f;
            float newSensitivityLeft = sbLeftSensitivity.getProgress() / 100.0f;
            float newSensitivityRight = sbRightSensitivity.getProgress() / 100.0f;

            boolean newInvertLeftX = cbInvertLeftX.isChecked();
            boolean newInvertLeftY = cbInvertLeftY.isChecked();
            boolean newInvertRightX = cbInvertRightX.isChecked();
            boolean newInvertRightY = cbInvertRightY.isChecked();

            // Save to SharedPreferences
            SharedPreferences.Editor editor = preferences.edit();
            editor.putFloat(PreferenceKeys.DEADZONE_LEFT, newDeadzoneLeft);
            editor.putFloat(PreferenceKeys.DEADZONE_RIGHT, newDeadzoneRight);
            editor.putFloat(PreferenceKeys.SENSITIVITY_LEFT, newSensitivityLeft);
            editor.putFloat(PreferenceKeys.SENSITIVITY_RIGHT, newSensitivityRight);
            editor.putBoolean(PreferenceKeys.INVERT_LEFT_X, newInvertLeftX);
            editor.putBoolean(PreferenceKeys.INVERT_LEFT_Y, newInvertLeftY);
            editor.putBoolean(PreferenceKeys.INVERT_RIGHT_X, newInvertRightX);
            editor.putBoolean(PreferenceKeys.INVERT_RIGHT_Y, newInvertRightY);
            editor.putBoolean(PreferenceKeys.SQUARE_DEADZONE_LEFT, cbLeftStickSquareDeadzone.isChecked());
            editor.apply();

            // Optionally, notify ExternalController instances to reload preferences
            // If you have a central manager or singleton, you can call a method here
            // For example:
            // ExternalControllerManager.getInstance().reloadPreferences();

            // We'll assume ExternalController instances listen to preference changes
        });

        builder.setNegativeButton("Cancel", null);

        // Create and show the dialog
        AlertDialog dialog = builder.create();
        dialog.show();
    }
}
