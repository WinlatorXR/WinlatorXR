package com.winlator.cmod.inputcontrols;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.xr.XrActivity;

import java.util.List;

public class MotionControls implements SensorEventListener {
    private static MotionControls INSTANCE;
    public static MotionControls getInstance(Context ctx) {
        if (INSTANCE == null) INSTANCE = new MotionControls(ctx.getApplicationContext());
        return INSTANCE;
    }

    private final Context appCtx;
    private final SensorManager sensorManager;
    private final Sensor gyro;
    private final SharedPreferences prefs;

    private WinHandler winHandler;
    private boolean registered = false;

    private final WindowManager windowManager;

    private final android.hardware.display.DisplayManager displayManager;

    private MotionControls(Context appCtx) {
        this.appCtx = appCtx;
        this.sensorManager = (SensorManager) appCtx.getSystemService(Context.SENSOR_SERVICE);
        this.gyro = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) : null;
        this.prefs = PreferenceManager.getDefaultSharedPreferences(appCtx);
        this.windowManager = (WindowManager) appCtx.getSystemService(Context.WINDOW_SERVICE);
        this.displayManager  = (android.hardware.display.DisplayManager)
                appCtx.getSystemService(Context.DISPLAY_SERVICE);
    }

    /** Wire the active WinHandler and push current prefs immediately. */
    public MotionControls attach(WinHandler handler) {
        this.winHandler = handler;
        if (handler != null) {
            handler.setGyroEnabled(prefs.getBoolean("gyro_enabled", false));
            applyPrefsToHandler(handler);
            handler.setGyroTriggerButton(prefs.getInt("gyro_trigger_button", KeyEvent.KEYCODE_BUTTON_L1));
            handler.setGyroToggleMode(prefs.getInt("gyro_mode", 0) == 1);
        }
        refreshRegistration();
        return this;
    }

    // --- Sensor registration -------------------------------------------------

    private void refreshRegistration() {
        boolean enabled = prefs.getBoolean("gyro_enabled", false);
        if (gyro == null || sensorManager == null || winHandler == null) {
            unregister();
            return;
        }
        if (enabled) register(); else unregister();
    }

    private void register() {
        if (registered) return;
        sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME);
        registered = true;
    }

    private void unregister() {
        if (!registered) return;
        sensorManager.unregisterListener(this);
        registered = false;
        if (winHandler != null) winHandler.updateGyroData(0f, 0f); // clear
    }

    // --- SensorEventListener -------------------------------------------------

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (winHandler == null || event.sensor.getType() != Sensor.TYPE_GYROSCOPE) return;

        // Raw device axes in device coordinates (rad/s)
        float gx = event.values[0]; // pitch
        float gy = event.values[1]; // roll
        float gz = event.values[2]; // yaw

        int rotation = getScreenRotationSafe();

        float screenYaw, screenPitch;
        switch (rotation) {
            case android.view.Surface.ROTATION_0:        // portrait
                screenYaw   = gz;
                screenPitch = gx;     // tilt forward/back
                break;
            case android.view.Surface.ROTATION_90:       // landscape, buttons on left
                screenYaw   = gz;
                screenPitch =  gy;    // forward/back tilt
                break;
            case android.view.Surface.ROTATION_180:      // upside-down portrait
                screenYaw   = -gz;
                screenPitch = -gx;
                break;
            case android.view.Surface.ROTATION_270:      // landscape, buttons on right
            default:
                screenYaw   = gz;
                screenPitch = -gy;
                break;
        }

        winHandler.updateGyroData(screenYaw, screenPitch);
    }


    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}


    private int getScreenRotationSafe() {
        // Try WindowManager (works pre-R and usually on R+ too)
        if (windowManager != null) {
            try {
                android.view.Display d = windowManager.getDefaultDisplay(); // deprecated but works
                if (d != null) return d.getRotation();
            } catch (Throwable ignored) {}
        }

        // Try DisplayManager (visual-context not required)
        if (displayManager != null) {
            try {
                android.view.Display d = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY);
                if (d != null) return d.getRotation();
            } catch (Throwable ignored) {}
        }

        // Last resort
        return android.view.Surface.ROTATION_0;
    }

    // --- Dialog --------------------------------------------------------------

    public void showContentDialog(Context ctx, @Nullable ContentDialog toClose) {
        if (toClose != null) toClose.dismiss();

        ContentDialog cd = new ContentDialog(ctx, 0);
        cd.setTitle(R.string.gyro_settings);

        boolean dark = PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean("dark_mode", false);
        ContextThemeWrapper wrap = new ContextThemeWrapper(cd.getContext(), dark ? R.style.ContentDialog : R.style.AppTheme);

        if (dark) {
            View root = cd.getContentView();
            if (root instanceof ViewGroup) {
                setTextColorForDialog((ViewGroup) root, 0xFFFFFFFF); // white
            }
        }

        FrameLayout frame = cd.getContentView().findViewById(R.id.FrameLayout);
        frame.setVisibility(View.VISIBLE);
        View v = LayoutInflater.from(wrap).inflate(R.layout.motion_controls_dialog, frame, false);
        frame.addView(v);



        // Bind
        CheckBox cbEnabled = v.findViewById(R.id.cbGyroEnabled);

        RadioGroup rgTarget = v.findViewById(R.id.rgGyroTarget);

        SeekBar sbXSens = v.findViewById(R.id.sbGyroXSensitivity);
        SeekBar sbYSens = v.findViewById(R.id.sbGyroYSensitivity);
        SeekBar sbSmooth = v.findViewById(R.id.sbGyroSmoothing);
        SeekBar sbDead   = v.findViewById(R.id.sbGyroDeadzone);

        TextView tvXSens = v.findViewById(R.id.tvGyroXSensitivity);
        TextView tvYSens = v.findViewById(R.id.tvGyroYSensitivity);
        TextView tvSmooth= v.findViewById(R.id.tvGyroSmoothing);
        TextView tvDead  = v.findViewById(R.id.tvGyroDeadzone);

        CheckBox cbInvX = v.findViewById(R.id.cbInvertGyroX);
        CheckBox cbInvY = v.findViewById(R.id.cbInvertGyroY);

        Spinner spActivator = v.findViewById(R.id.spGyroTriggerButton);

        RadioGroup rgMode   = v.findViewById(R.id.rgGyroMode);

        if (XrActivity.isEnabled(v.getContext())) {
            v.findViewById(R.id.TVGyroTriggerButton).setVisibility(View.GONE);
            v.findViewById(R.id.TVGyroMode).setVisibility(View.GONE);
            spActivator.setVisibility(View.GONE);
            rgMode.setVisibility(View.GONE);
        }

        // Load prefs
        boolean enabled = prefs.getBoolean("gyro_enabled", false);
        boolean toLeft = prefs.getBoolean("gyro_to_left_stick", false);

        float xSens = prefs.getFloat("gyro_x_sensitivity", 1.0f);
        float ySens = prefs.getFloat("gyro_y_sensitivity", 1.0f);
        float smooth = prefs.getFloat("gyro_smoothing", 0.9f);
        float dead = prefs.getFloat("gyro_deadzone", 0.05f);
        boolean invX = prefs.getBoolean("invert_gyro_x", false);
        boolean invY = prefs.getBoolean("invert_gyro_y", false);
        int savedKey = prefs.getInt("gyro_trigger_button", KeyEvent.KEYCODE_BUTTON_L1);
        int mode = prefs.getInt("gyro_mode", 0);

        cbEnabled.setChecked(enabled);
        rgTarget.check(toLeft ? R.id.rbTargetLeft : R.id.rbTargetRight);
        sbXSens.setProgress(Math.round(xSens * 100f));
        sbYSens.setProgress(Math.round(ySens * 100f));
        sbSmooth.setProgress(Math.round(smooth * 100f));
        sbDead.setProgress(Math.round(dead * 100f));
        tvXSens.setText(ctx.getString(R.string.percent_fmt, sbXSens.getProgress()));
        tvYSens.setText(ctx.getString(R.string.percent_fmt, sbYSens.getProgress()));
        tvSmooth.setText(ctx.getString(R.string.percent_fmt, sbSmooth.getProgress()));
        tvDead.setText(ctx.getString(R.string.percent_fmt, sbDead.getProgress()));
        cbInvX.setChecked(invX);
        cbInvY.setChecked(invY);
        MotionControlsUiUtils.selectKeycodeInSpinner(ctx, spActivator, savedKey);
        rgMode.check(mode == 0 ? R.id.rbHoldMode : R.id.rbToggleMode);



        ArrayAdapter<MotionControlsUiUtils.KeyEntry> adapter =
                MotionControlsUiUtils.buildKeycodeAdapter(ctx, wrap, dark);

        spActivator.setAdapter(adapter);

        // Selection still works because the order matches keycodes[]
        MotionControlsUiUtils.selectKeycodeInSpinner(ctx, spActivator, savedKey);




        // Live push
        Runnable pushAll = () -> {
            if (winHandler == null) return;
            boolean en = cbEnabled.isChecked();
            winHandler.setGyroToLeftStick(rgTarget.getCheckedRadioButtonId() == R.id.rbTargetLeft);
            winHandler.setGyroEnabled(en);
            winHandler.setGyroSensitivityX(sbXSens.getProgress() / 100f);
            winHandler.setGyroSensitivityY(sbYSens.getProgress() / 100f);
            winHandler.setSmoothingFactor(sbSmooth.getProgress() / 100f);
            winHandler.setGyroDeadzone(sbDead.getProgress() / 100f);
            winHandler.setInvertGyroX(cbInvX.isChecked());
            winHandler.setInvertGyroY(cbInvY.isChecked());
            winHandler.setGyroTriggerButton(MotionControlsUiUtils.getSelectedKeycodeFromSpinner(ctx, spActivator));
            winHandler.setGyroToggleMode(rgMode.getCheckedRadioButtonId() == R.id.rbToggleMode);

            if (en) register(); else unregister();
            // No need to call sendGamepadState() here — WinHandler.updateGyroData() pushes on sensor ticks.
        };

        cbEnabled.setOnCheckedChangeListener((b, c) -> pushAll.run());
        rgTarget.setOnCheckedChangeListener((g, id) -> pushAll.run());
        sbXSens.setOnSeekBarChangeListener(simple(p -> { tvXSens.setText(ctx.getString(R.string.percent_fmt, p)); pushAll.run(); }));
        sbYSens.setOnSeekBarChangeListener(simple(p -> { tvYSens.setText(ctx.getString(R.string.percent_fmt, p)); pushAll.run(); }));
        sbSmooth.setOnSeekBarChangeListener(simple(p -> { tvSmooth.setText(ctx.getString(R.string.percent_fmt, p)); pushAll.run(); }));
        sbDead.setOnSeekBarChangeListener(simple(p -> { tvDead.setText(ctx.getString(R.string.percent_fmt, p)); pushAll.run(); }));
        cbInvX.setOnCheckedChangeListener((b, c) -> pushAll.run());
        cbInvY.setOnCheckedChangeListener((b, c) -> pushAll.run());
        spActivator.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { pushAll.run(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        rgMode.setOnCheckedChangeListener((g, id) -> pushAll.run());

        // Persist on OK
        cd.setOnConfirmCallback(() -> {
            SharedPreferences.Editor e = prefs.edit();
            e.putBoolean("gyro_enabled", cbEnabled.isChecked());
            e.putBoolean("gyro_to_left_stick", rgTarget.getCheckedRadioButtonId() == R.id.rbTargetLeft);
            e.putFloat("gyro_x_sensitivity", sbXSens.getProgress() / 100f);
            e.putFloat("gyro_y_sensitivity", sbYSens.getProgress() / 100f);
            e.putFloat("gyro_smoothing", sbSmooth.getProgress() / 100f);
            e.putFloat("gyro_deadzone", sbDead.getProgress() / 100f);
            e.putBoolean("invert_gyro_x", cbInvX.isChecked());
            e.putBoolean("invert_gyro_y", cbInvY.isChecked());
            e.putInt("gyro_trigger_button", MotionControlsUiUtils.getSelectedKeycodeFromSpinner(ctx, spActivator));
            e.putInt("gyro_mode", rgMode.getCheckedRadioButtonId() == R.id.rbHoldMode ? 0 : 1);
            e.apply();
        });

        cd.show();
    }

    private void setTextColorForDialog(ViewGroup viewGroup, int color) {
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            if (child instanceof ViewGroup) {
                // If the child is a ViewGroup, recursively apply the color
                setTextColorForDialog((ViewGroup) child, color);
            } else if (child instanceof TextView) {
                // If the child is a TextView, set its text color
                ((TextView) child).setTextColor(color);
            }
        }
    }

    // --- helpers -------------------------------------------------------------

    private void applyPrefsToHandler(WinHandler h) {
        h.setGyroSensitivityX(prefs.getFloat("gyro_x_sensitivity", 1.0f));
        h.setGyroSensitivityY(prefs.getFloat("gyro_y_sensitivity", 1.0f));
        h.setSmoothingFactor  (prefs.getFloat("gyro_smoothing", 0.9f));
        h.setInvertGyroX      (prefs.getBoolean("invert_gyro_x", false));
        h.setInvertGyroY      (prefs.getBoolean("invert_gyro_y", false));
        h.setGyroDeadzone     (prefs.getFloat("gyro_deadzone", 0.05f));
    }

    private static SeekBar.OnSeekBarChangeListener simple(java.util.function.IntConsumer onChange) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { onChange.accept(progress); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
    }

    /** Spinner <-> keycode helpers. */
    public static class MotionControlsUiUtils {

        /** Simple item model used by the spinner. */
        public static final class KeyEntry {
            public final int code;
            public final String label;
            public KeyEntry(int code, String label) { this.code = code; this.label = label; }
            @Override public String toString() { return label; } // used by default ArrayAdapter
        }

        /** Create an adapter with forced text colors that respects dark mode. */
        public static ArrayAdapter<KeyEntry> buildKeycodeAdapter(
                Context appCtx, Context themedCtx, boolean dark) {

            int[] codes = KeycodeArrays.loadButtonKeycodes(appCtx);
            List<KeyEntry> items = new java.util.ArrayList<>(codes.length);
            for (int c : codes) items.add(new KeyEntry(c, prettyLabelFor(c)));

            // Real ARGB colors, resolved from resources
            final int collapsedColor = ContextCompat.getColor(appCtx,
                    dark ? android.R.color.white : android.R.color.black);
            final int dropdownColor  = ContextCompat.getColor(appCtx, android.R.color.black);

            ArrayAdapter<KeyEntry> ad = new ArrayAdapter<KeyEntry>(
                    themedCtx,
                    android.R.layout.simple_spinner_item,
                    items
            ) {
                @NonNull @Override
                public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                    View v = super.getView(position, convertView, parent);
                    if (v instanceof TextView) {
                        ((TextView) v).setTextColor(collapsedColor);  // collapsed/button text
                    } else {
                        TextView tv = v.findViewById(android.R.id.text1);
                        if (tv != null) tv.setTextColor(collapsedColor);
                    }
                    return v;
                }

                @Override
                public View getDropDownView(int position, View convertView, @NonNull ViewGroup parent) {
                    View v = super.getDropDownView(position, convertView, parent);
                    if (v instanceof TextView) {
                        ((TextView) v).setTextColor(dropdownColor);   // dropdown rows
                    } else {
                        TextView tv = v.findViewById(android.R.id.text1);
                        if (tv != null) tv.setTextColor(dropdownColor);
                    }
                    return v;
                }
            };

            ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            return ad;
        }

        /** Keep using position-based selection; order matches loadButtonKeycodes(appCtx). */
        public static void selectKeycodeInSpinner(Context ctx, Spinner spinner, int keycode) {
            int[] keycodes = KeycodeArrays.loadButtonKeycodes(ctx);
            int idx = 0;
            for (int i = 0; i < keycodes.length; i++) {
                if (keycodes[i] == keycode) { idx = i; break; }
            }
            spinner.setSelection(idx);
        }

        public static int getSelectedKeycodeFromSpinner(Context ctx, Spinner spinner) {
            int[] keycodes = KeycodeArrays.loadButtonKeycodes(ctx);
            int pos = spinner.getSelectedItemPosition();
            return (pos >= 0 && pos < keycodes.length) ? keycodes[pos] : keycodes[0];
        }

        // --- Helpers ------------------------------------------------------------

        private static String prettyLabelFor(int keycode) {
            // If you already have labels in KeycodeArrays, prefer that.
            // Otherwise derive something readable from the Android name.
            String raw = android.view.KeyEvent.keyCodeToString(keycode); // e.g. "KEYCODE_BUTTON_L1"
            if (raw == null) return "Key " + keycode;

            raw = raw.replace("KEYCODE_BUTTON_", "")
                    .replace("KEYCODE_", "");

            // Common nicer aliases
            switch (keycode) {
                case KeyEvent.KEYCODE_BUTTON_L1: return "L1";
                case KeyEvent.KEYCODE_BUTTON_R1: return "R1";
                case KeyEvent.KEYCODE_BUTTON_L2: return "L2";
                case KeyEvent.KEYCODE_BUTTON_R2: return "R2";
                case KeyEvent.KEYCODE_BUTTON_START: return "Start";
                case KeyEvent.KEYCODE_BUTTON_SELECT: return "Select";
                case KeyEvent.KEYCODE_BUTTON_THUMBL: return "L3";
                case KeyEvent.KEYCODE_BUTTON_THUMBR: return "R3";
            }
            // Fallback: make it human-ish
            return raw.replace('_', ' ');
        }
    }


    }


