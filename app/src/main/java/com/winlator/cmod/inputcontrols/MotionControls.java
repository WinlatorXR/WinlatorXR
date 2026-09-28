package com.winlator.cmod.inputcontrols;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.SessionSettings;
import com.winlator.cmod.widget.SeekBar;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.xr.XrActivity;
import com.winlator.xr.io.XrInput;

import java.util.List;

public class MotionControls implements SensorEventListener {
    // Shared with WinHandler and XServerDisplayActivity, which read the same settings on
    // their own. They disagreed on the gyro_enabled default before this.
    public static final boolean DEFAULT_ENABLED = false;
    public static final boolean DEFAULT_TO_LEFT_STICK = false;
    public static final float DEFAULT_X_SENSITIVITY = 1.0f;
    public static final float DEFAULT_Y_SENSITIVITY = 1.0f;
    public static final float DEFAULT_SMOOTHING = 0.9f;
    public static final float DEFAULT_DEADZONE = 0.05f;
    public static final boolean DEFAULT_INVERT_X = false;
    public static final boolean DEFAULT_INVERT_Y = false;
    public static final int DEFAULT_TRIGGER_BUTTON = KeyEvent.KEYCODE_BUTTON_L1;
    public static final int DEFAULT_MODE = 0;

    /** Every setting this dialog can pin to a game. See XrActivity.SESSION_KEYS. */
    public static final String[] SESSION_KEYS = {
            "gyro_enabled", "gyro_to_left_stick", "gyro_x_sensitivity", "gyro_y_sensitivity",
            "gyro_smoothing", "gyro_deadzone", "invert_gyro_x", "invert_gyro_y",
            "gyro_trigger_button", "gyro_mode"};

    private static MotionControls INSTANCE;
    public static MotionControls getInstance(Context ctx) {
        if (INSTANCE == null) INSTANCE = new MotionControls(ctx.getApplicationContext());
        return INSTANCE;
    }

    private final Context appCtx;
    private final SensorManager sensorManager;
    private final Sensor gyro;

    private WinHandler winHandler;
    private boolean registered = false;

    private final WindowManager windowManager;

    private final android.hardware.display.DisplayManager displayManager;

    private MotionControls(Context appCtx) {
        this.appCtx = appCtx;
        this.sensorManager = (SensorManager) appCtx.getSystemService(Context.SENSOR_SERVICE);
        this.gyro = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) : null;
        this.windowManager = (WindowManager) appCtx.getSystemService(Context.WINDOW_SERVICE);
        this.displayManager  = (android.hardware.display.DisplayManager)
                appCtx.getSystemService(Context.DISPLAY_SERVICE);
    }

    /** Wire the active WinHandler and push the current settings immediately. */
    public MotionControls attach(WinHandler handler) {
        this.winHandler = handler;
        if (handler != null) {
            handler.setGyroEnabled(SessionSettings.getBoolean(appCtx, "gyro_enabled", DEFAULT_ENABLED));
            handler.setGyroToLeftStick(SessionSettings.getBoolean(appCtx, "gyro_to_left_stick", DEFAULT_TO_LEFT_STICK));
            applyPrefsToHandler(handler);
            handler.setGyroTriggerButton(SessionSettings.getInt(appCtx, "gyro_trigger_button", DEFAULT_TRIGGER_BUTTON));
            handler.setGyroToggleMode(SessionSettings.getInt(appCtx, "gyro_mode", DEFAULT_MODE) == 1);
        }
        refreshRegistration();
        return this;
    }

    // --- Sensor registration -------------------------------------------------

    private void refreshRegistration() {
        boolean enabled = SessionSettings.getBoolean(appCtx, "gyro_enabled", DEFAULT_ENABLED);
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
        sbXSens.setMaxValue(200);
        sbYSens.setMaxValue(200);

        TextView tvXSens = v.findViewById(R.id.tvGyroXSensitivity);
        TextView tvYSens = v.findViewById(R.id.tvGyroYSensitivity);
        TextView tvSmooth= v.findViewById(R.id.tvGyroSmoothing);
        TextView tvDead  = v.findViewById(R.id.tvGyroDeadzone);

        CheckBox cbInvX = v.findViewById(R.id.cbInvertGyroX);
        CheckBox cbInvY = v.findViewById(R.id.cbInvertGyroY);
        CheckBox cbWheel = v.findViewById(R.id.cbWheelEmulation);
        CheckBox cbRadialToSquare = v.findViewById(R.id.CBPlayerXRGamepadRadialToSquare);
        CheckBox cbRumblePassthrough = v.findViewById(R.id.CBPlayerXRRumblePassthrough);
        TextView tvRumblePassthroughHeader = v.findViewById(R.id.TVRumblePassthroughHeader);
        CheckBox cbPointerSmoothing = v.findViewById(R.id.CBPointerSmoothing);
        TextView tvThumbstickType = v.findViewById(R.id.tvGamepadThumbstickType);

        Spinner spActivator = v.findViewById(R.id.spGyroTriggerButton);

        RadioGroup rgMode   = v.findViewById(R.id.rgGyroMode);

        // Load the settings for the game being played, falling back to the app-wide defaults
        // for anything this game has not been given an answer of its own for.
        boolean enabled = SessionSettings.getBoolean(ctx, "gyro_enabled", DEFAULT_ENABLED);
        boolean toLeft = SessionSettings.getBoolean(ctx, "gyro_to_left_stick", DEFAULT_TO_LEFT_STICK);

        float xSens = SessionSettings.getFloat(ctx, "gyro_x_sensitivity", DEFAULT_X_SENSITIVITY);
        float ySens = SessionSettings.getFloat(ctx, "gyro_y_sensitivity", DEFAULT_Y_SENSITIVITY);
        float smooth = SessionSettings.getFloat(ctx, "gyro_smoothing", DEFAULT_SMOOTHING);
        float dead = SessionSettings.getFloat(ctx, "gyro_deadzone", DEFAULT_DEADZONE);
        boolean invX = SessionSettings.getBoolean(ctx, "invert_gyro_x", DEFAULT_INVERT_X);
        boolean invY = SessionSettings.getBoolean(ctx, "invert_gyro_y", DEFAULT_INVERT_Y);
        int savedKey = SessionSettings.getInt(ctx, "gyro_trigger_button", DEFAULT_TRIGGER_BUTTON);
        int mode = SessionSettings.getInt(ctx, "gyro_mode", DEFAULT_MODE);

        if (XrActivity.isEnabled(v.getContext())) {
            v.findViewById(R.id.TVGyroTriggerButton).setVisibility(View.GONE);
            v.findViewById(R.id.TVGyroMode).setVisibility(View.GONE);
            spActivator.setVisibility(View.GONE);
            rgMode.setVisibility(View.GONE);

            tvThumbstickType.setVisibility(View.VISIBLE);
            cbRadialToSquare.setVisibility(View.VISIBLE);
            tvRumblePassthroughHeader.setVisibility(View.VISIBLE);
            cbRumblePassthrough.setVisibility(View.VISIBLE);
            cbPointerSmoothing.setVisibility(View.VISIBLE);

            if (XrActivity.isActive()) {
                cbWheel.setChecked(XrActivity.wheelEmulation);
                cbWheel.setEnabled(enabled);
                cbWheel.setVisibility(View.VISIBLE);
                cbWheel.setOnCheckedChangeListener((compoundButton, checked) -> {
                    SessionSettings.putBoolean(ctx, "use_xr_wheel", checked);
                    XrActivity.wheelEmulation = checked;
                    if (checked) {
                        XrInput.ensureVirtualControllerAttached();
                        // Wheel steering drives the left stick, so point the target there too.
                        if (rgTarget.getCheckedRadioButtonId() != R.id.rbTargetLeft) {
                            rgTarget.check(R.id.rbTargetLeft);
                        }
                    } else if (rgTarget.getCheckedRadioButtonId() != R.id.rbTargetRight) {
                        rgTarget.check(R.id.rbTargetRight);
                    }
                });

                cbRadialToSquare.setChecked(XrActivity.gamepadRadialToSquare);
            }
        } else {
            tvThumbstickType.setVisibility(View.GONE);
            cbRadialToSquare.setVisibility(View.GONE);
            tvRumblePassthroughHeader.setVisibility(View.GONE);
            cbRumblePassthrough.setVisibility(View.GONE);
            cbPointerSmoothing.setVisibility(View.GONE);
        }

        cbRadialToSquare.setChecked(SessionSettings.getBoolean(ctx,
                "xr_gamepad_radial_to_square", XrActivity.DEFAULT_RADIAL_TO_SQUARE));
        cbRadialToSquare.setOnCheckedChangeListener((compoundButton, checked) -> {
            SessionSettings.putBoolean(ctx, "xr_gamepad_radial_to_square", checked);
            XrActivity.gamepadRadialToSquare = checked;
        });

        cbRumblePassthrough.setChecked(SessionSettings.getBoolean(ctx,
                "use_xr_rumble_passthrough", XrActivity.DEFAULT_RUMBLE_PASSTHROUGH));
        cbRumblePassthrough.setOnCheckedChangeListener((compoundButton, checked) -> {
            SessionSettings.putBoolean(ctx, "use_xr_rumble_passthrough", checked);
            XrActivity.rumblePassthrough = checked;
            if (checked && XrActivity.isActive()) {
                XrInput.ensureVirtualControllerAttached();
            }
        });

        // Off by default: the filter trades a little latency for steadiness and the right
        // balance depends on the headset, so it is opt-in until judged on device.
        cbPointerSmoothing.setChecked(SessionSettings.getBoolean(ctx,
                "use_xr_smoothing", XrActivity.DEFAULT_POINTER_SMOOTHING));
        cbPointerSmoothing.setOnCheckedChangeListener((compoundButton, checked) -> {
            SessionSettings.putBoolean(ctx, "use_xr_smoothing", checked);
            XrActivity.pointerSmoothing = checked;
            if (XrActivity.isActive()) {
                XrActivity.getInstance().nativeSetPointerSmoothing(checked);
            }
        });

        cbEnabled.setChecked(enabled);
        rgTarget.check(toLeft ? R.id.rbTargetLeft : R.id.rbTargetRight);
        sbXSens.setValue(Math.round(xSens * 100f));
        sbYSens.setValue(Math.round(ySens * 100f));
        sbSmooth.setValue(Math.round(smooth * 100f));
        sbDead.setValue(Math.round(dead * 100f));
        tvXSens.setText(ctx.getString(R.string.percent_fmt, (int)sbXSens.getValue()));
        tvYSens.setText(ctx.getString(R.string.percent_fmt, (int)sbYSens.getValue()));
        tvSmooth.setText(ctx.getString(R.string.percent_fmt, (int)sbSmooth.getValue()));
        tvDead.setText(ctx.getString(R.string.percent_fmt, (int)sbDead.getValue()));
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
            winHandler.setGyroSensitivityX(sbXSens.getValue() / 100f);
            winHandler.setGyroSensitivityY(sbYSens.getValue() / 100f);
            winHandler.setSmoothingFactor(sbSmooth.getValue() / 100f);
            winHandler.setGyroDeadzone(sbDead.getValue() / 100f);
            winHandler.setInvertGyroX(cbInvX.isChecked());
            winHandler.setInvertGyroY(cbInvY.isChecked());
            winHandler.setGyroTriggerButton(MotionControlsUiUtils.getSelectedKeycodeFromSpinner(ctx, spActivator));
            winHandler.setGyroToggleMode(rgMode.getCheckedRadioButtonId() == R.id.rbToggleMode);

            if (en) register(); else unregister();
            // No need to call sendGamepadState() here — WinHandler.updateGyroData() pushes on sensor ticks.
        };

        cbEnabled.setOnCheckedChangeListener((b, c) -> {
            pushAll.run();
            cbWheel.setEnabled(c);
        });
        rgTarget.setOnCheckedChangeListener((g, id) -> pushAll.run());
        sbXSens.setOnValueChangeListener((seekBar, value) -> {
            tvXSens.setText(ctx.getString(R.string.percent_fmt, (int)value)); pushAll.run();
        });
        sbYSens.setOnValueChangeListener((seekBar, value) -> {
            tvYSens.setText(ctx.getString(R.string.percent_fmt, (int)value)); pushAll.run();
        });
        sbSmooth.setOnValueChangeListener((seekBar, value) -> {
            tvSmooth.setText(ctx.getString(R.string.percent_fmt, (int)value)); pushAll.run();
        });
        sbDead.setOnValueChangeListener((seekBar, value) -> {
            tvDead.setText(ctx.getString(R.string.percent_fmt, (int)value)); pushAll.run();
        });
        cbInvX.setOnCheckedChangeListener((b, c) -> pushAll.run());
        cbInvY.setOnCheckedChangeListener((b, c) -> pushAll.run());
        spActivator.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { pushAll.run(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        rgMode.setOnCheckedChangeListener((g, id) -> pushAll.run());

        // Everything saves on close, so Cancel had nothing left to undo - reset instead
        cd.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        View btReset = cd.findViewById(R.id.BTReset);
        btReset.setVisibility(View.VISIBLE);
        btReset.setOnClickListener(view -> {
            cbEnabled.setChecked(DEFAULT_ENABLED);
            rgTarget.check(DEFAULT_TO_LEFT_STICK ? R.id.rbTargetLeft : R.id.rbTargetRight);
            sbXSens.setValue(Math.round(DEFAULT_X_SENSITIVITY * 100f));
            sbYSens.setValue(Math.round(DEFAULT_Y_SENSITIVITY * 100f));
            sbSmooth.setValue(Math.round(DEFAULT_SMOOTHING * 100f));
            sbDead.setValue(Math.round(DEFAULT_DEADZONE * 100f));
            tvXSens.setText(ctx.getString(R.string.percent_fmt, (int)sbXSens.getValue()));
            tvYSens.setText(ctx.getString(R.string.percent_fmt, (int)sbYSens.getValue()));
            tvSmooth.setText(ctx.getString(R.string.percent_fmt, (int)sbSmooth.getValue()));
            tvDead.setText(ctx.getString(R.string.percent_fmt, (int)sbDead.getValue()));
            cbInvX.setChecked(DEFAULT_INVERT_X);
            cbInvY.setChecked(DEFAULT_INVERT_Y);
            MotionControlsUiUtils.selectKeycodeInSpinner(ctx, spActivator, DEFAULT_TRIGGER_BUTTON);
            rgMode.check(DEFAULT_MODE == 0 ? R.id.rbHoldMode : R.id.rbToggleMode);
            // Only wired up, and so only saved, while XR is running
            if (XrActivity.isActive()) cbWheel.setChecked(XrActivity.DEFAULT_WHEEL);
            cbRadialToSquare.setChecked(XrActivity.DEFAULT_RADIAL_TO_SQUARE);
            cbRumblePassthrough.setChecked(XrActivity.DEFAULT_RUMBLE_PASSTHROUGH);
            cbPointerSmoothing.setChecked(XrActivity.DEFAULT_POINTER_SMOOTHING);
            pushAll.run();
        });

        // Persist however the dialog closes - the thumbstick close in XR is a back press, and
        // the changes are already live. One batched write: pinning these to a shortcut
        // rewrites its .desktop file, which is not worth doing a dozen times over.
        cd.setOnDismissListener(d -> SessionSettings.edit(ctx)
                .putBoolean("gyro_enabled", cbEnabled.isChecked())
                .putBoolean("gyro_to_left_stick", rgTarget.getCheckedRadioButtonId() == R.id.rbTargetLeft)
                .putFloat("gyro_x_sensitivity", sbXSens.getValue() / 100f)
                .putFloat("gyro_y_sensitivity", sbYSens.getValue() / 100f)
                .putFloat("gyro_smoothing", sbSmooth.getValue() / 100f)
                .putFloat("gyro_deadzone", sbDead.getValue() / 100f)
                .putBoolean("invert_gyro_x", cbInvX.isChecked())
                .putBoolean("invert_gyro_y", cbInvY.isChecked())
                .putInt("gyro_trigger_button", MotionControlsUiUtils.getSelectedKeycodeFromSpinner(ctx, spActivator))
                .putInt("gyro_mode", rgMode.getCheckedRadioButtonId() == R.id.rbHoldMode ? 0 : 1)
                .putBoolean("xr_gamepad_radial_to_square", cbRadialToSquare.isChecked())
                .apply());

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
        h.setGyroSensitivityX(SessionSettings.getFloat(appCtx, "gyro_x_sensitivity", DEFAULT_X_SENSITIVITY));
        h.setGyroSensitivityY(SessionSettings.getFloat(appCtx, "gyro_y_sensitivity", DEFAULT_Y_SENSITIVITY));
        h.setSmoothingFactor  (SessionSettings.getFloat(appCtx, "gyro_smoothing", DEFAULT_SMOOTHING));
        h.setInvertGyroX      (SessionSettings.getBoolean(appCtx, "invert_gyro_x", DEFAULT_INVERT_X));
        h.setInvertGyroY      (SessionSettings.getBoolean(appCtx, "invert_gyro_y", DEFAULT_INVERT_Y));
        h.setGyroDeadzone     (SessionSettings.getFloat(appCtx, "gyro_deadzone", DEFAULT_DEADZONE));
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


