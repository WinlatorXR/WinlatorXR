package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.hardware.BatteryState;
import android.os.BatteryManager;
import android.os.Build;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.navigation.NavigationView;
import com.winlator.cmod.R;
import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.cmod.core.SessionSettings;
import com.winlator.xr.XrActivity;
import com.winlator.xr.io.XrInput;
import com.winlator.xr.ui.XrDialog;
import com.winlator.xr.utils.XrEnvironment;

import java.util.Date;

public class NavigationDialog extends ContentDialog {
    private static final int STATUS_REFRESH_INTERVAL_MS = 1000;

    /** Long enough that it cannot be a stray knock, short enough not to feel stuck. */
    private static final long RESET_HOLD_MILLIS = 3000;

    // Shorter than the reset, which throws work away: a toggle is undone by doing it again,
    // so it does not have to be as hard to trigger.
    private static final long TOGGLE_HOLD_MILLIS = 1000;

    // The edge glow slider runs 0 to 100 in tens, so stepping by two of its stages gives
    // 20, 40, 60, 80, 100 - five stops, which is as many as is worth tapping through.
    private static final int EDGE_GLOW_STEP = 20;
    private static final int EDGE_GLOW_MAX = 100;

    public NavigationDialog(@NonNull XServerDisplayActivity context) {
        super(context, R.layout.navigation_dialog);
        setIcon(R.drawable.icon_container);
        setTitle(context.getString(R.string.app_name));
        findViewById(R.id.BTCancel).setVisibility(View.GONE);

        GridLayout grid = findViewById(R.id.main_menu_grid);
        grid.setColumnCount(4);

        NavigationView navigation = context.findViewById(R.id.NavigationView);
        Menu menu = navigation.getMenu();
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            if (!item.isVisible()) {
                continue;
            }

            int padding = dpToPx(5, context);
            LinearLayout layout = new LinearLayout(context);
            layout.setPadding(padding, padding, padding, padding);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setOnClickListener(view -> {
                if (context.onNavigationItemSelected(item)) {
                    dismiss();
                }
            });

            layout.setOnFocusChangeListener((view, focused) -> {
                if (focused) {
                    layout.setBackgroundColor(Color.GRAY);
                } else {
                    layout.setBackgroundColor(Color.TRANSPARENT);
                }
            });

            int size = dpToPx(40, context);
            View icon = new View(context);
            item.getIcon().setTint(context.getColor(R.color.colorAccent));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            icon.setLayoutParams(lp);
            icon.setBackground(item.getIcon());
            layout.addView(icon);

            int width = dpToPx(96, context);
            TextView text = new TextView(context);
            text.setLayoutParams(new ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));
            text.setText(item.getTitle());
            text.setGravity(Gravity.CENTER);
            text.setLines(2);
            layout.addView(text);

            grid.addView(layout);
        }

        final TextView statusInfo = findViewById(R.id.TVStatusInfo);
        final Runnable updateStatus = new Runnable() {
            @Override
            public void run() {
                statusInfo.setText(getStatusText(context));
                statusInfo.postDelayed(this, STATUS_REFRESH_INTERVAL_MS);
            }
        };
        updateStatus.run();
        setOnDismissListener((dialog) -> statusInfo.removeCallbacks(updateStatus));

        actionLinesUI(context);
    }

    /**
     * Lists what the controller does while this menu is up: "Reset to default" on Y, the
     * three face buttons that are still free, and the grip and stick gesture that moves the
     * screen. This is the menu the face buttons answer to, rather than any of the dialogs it
     * opens, so what they do does not change as the user moves between them.
     *
     * Reset is on a hold rather than a press: it throws away every XR, motion control and
     * screen effect setting the game has been given, and a face button is easy to catch by
     * accident while a dialog is up.
     *
     * Nothing here applies without a headset, and this menu is also what the back gesture
     * and a gamepad's Home button open in a flat session.
     */
    private void actionLinesUI(XServerDisplayActivity context) {
        if (!XrActivity.isEnabled(context)) return;

        passthroughUI(context);
        edgeGlowUI(context);
        gamepadModeUI(context);
        setFaceButtonAction(FaceButton.Y, context.getString(R.string.xr_reset_to_default),
                RESET_HOLD_MILLIS, () -> {
            boolean hadSettings = context.resetSessionSettingsToDefaults();
            // Left open behind the alert: nothing on this menu is showing the settings that
            // were just thrown away, so there is nothing stale to close.
            ContentDialog.alert(context, hadSettings
                    ? R.string.xr_reset_to_default_done
                    : R.string.xr_reset_to_default_none, null);
        });
        setControlHint(context.getString(R.string.xr_adjust_window_distance));
    }

    /**
     * A turns passthrough on and off, which is the one XR setting worth reaching for without
     * going into a submenu: it is what the user changes to look at the room and back.
     *
     * Listed the same way as gamepad mode, saying which state is on rather than what the
     * button would do.
     */
    private void passthroughUI(XServerDisplayActivity context) {
        CharSequence holdLabel = context.getString(XrActivity.isPassthrough
                ? R.string.xr_passthrough_enabled
                : R.string.xr_passthrough_disabled);
        Runnable hold = () -> {
            togglePassthrough(context);
            // Switching passthrough is what makes the environment visible or not, so the
            // press below has to be offered or withdrawn along with it.
            passthroughUI(context);
        };

        if (!environmentExposed(context)) {
            setFaceButtonAction(FaceButton.A, holdLabel, TOGGLE_HOLD_MILLIS, hold);
            return;
        }

        setFaceButtonAction(FaceButton.A, holdLabel, TOGGLE_HOLD_MILLIS, hold,
                context.getString(XrEnvironment.isEnabled(context)
                        ? R.string.xr_environment_shown
                        : R.string.xr_environment_hidden),
                () -> {
                    XrEnvironment.setEnabled(context, !XrEnvironment.isEnabled(context));
                    passthroughUI(context);
                });
    }

    /**
     * Whether the 360 environment is there to be switched. It needs one to have been picked
     * and the runtime to support it, and it needs passthrough off: passthrough fills the
     * space the environment would occupy, so switching it there would do nothing visible. A
     * native VR title draws its own layer over the top for the same reason.
     */
    private static boolean environmentExposed(XServerDisplayActivity context) {
        return !XrActivity.isPassthrough
                && !XrDialog.isVRGameRunning()
                && !XrEnvironment.getSelected(context).isEmpty()
                && (!XrActivity.isActive() || XrActivity.getInstance().nativeIsEnvironmentSupported());
    }

    /**
     * Immersive mode and a native VR title both cover the room anyway, and the native side
     * already ignores passthrough while either is on, so this does not have to guard against
     * them: the setting takes effect whenever they stop.
     */
    private static void togglePassthrough(XServerDisplayActivity context) {
        boolean passthrough = !XrActivity.isPassthrough;

        XrActivity.isPassthrough = passthrough;
        SessionSettings.putBoolean(context, "use_pt", passthrough);
        if (XrActivity.isActive()) XrActivity.getInstance().nativeSetUsePT(passthrough);
    }

    /**
     * B works the edge glow: a hold turns it on at the first stop or off again, and once it
     * is on a plain press walks up the stops and round to the start.
     *
     * Stepping is on the press and switching off is on the hold, rather than the other way
     * round, because stepping is what gets done repeatedly and dropping back to nothing is
     * what should not happen by accident halfway up.
     */
    private void edgeGlowUI(XServerDisplayActivity context) {
        int level = SessionSettings.getInt(context, "edge_glow_level", 0);
        if (level <= 0) {
            setFaceButtonAction(FaceButton.B, context.getString(R.string.xr_edge_glow_disabled),
                    TOGGLE_HOLD_MILLIS, () -> {
                setEdgeGlow(context, EDGE_GLOW_STEP);
                edgeGlowUI(context);
            });
            return;
        }

        setFaceButtonAction(FaceButton.B,
                context.getString(R.string.xr_edge_glow_enabled, level),
                TOGGLE_HOLD_MILLIS,
                () -> {
                    setEdgeGlow(context, 0);
                    edgeGlowUI(context);
                },
                () -> {
                    // Read again rather than trusting what was captured when the line was
                    // listed, so two quick presses are two steps.
                    int next = SessionSettings.getInt(context, "edge_glow_level", 0) + EDGE_GLOW_STEP;
                    setEdgeGlow(context, next > EDGE_GLOW_MAX ? EDGE_GLOW_STEP : next);
                    edgeGlowUI(context);
                });
    }

    /**
     * A native VR title covers the glow with its own projection layer and the native side
     * already forces it off there, so this does not have to guard against it.
     */
    private static void setEdgeGlow(XServerDisplayActivity context, int level) {
        SessionSettings.putInt(context, "edge_glow_level", level);
        if (XrActivity.isActive()) XrActivity.getInstance().nativeSetEdgeGlow(level);
    }

    /**
     * X switches the controllers between driving a gamepad and driving the mouse and
     * keyboard, which is the swap most games want and is otherwise three checkboxes deep in
     * the XR menu.
     *
     * The line says which mode is on rather than what the button would do, because that is
     * what the user has come to the menu to find out; relisting it after the toggle is what
     * reports the change.
     */
    private void gamepadModeUI(XServerDisplayActivity context) {
        setFaceButtonAction(FaceButton.X, context.getString(XrActivity.gamepadEmulation
                        ? R.string.xr_gamepad_mode_enabled
                        : R.string.xr_gamepad_mode_disabled),
                TOGGLE_HOLD_MILLIS, () -> {
            toggleGamepadMode(context);
            gamepadModeUI(context);
        });
    }

    /**
     * Gamepad on means mouse and keys off and the other way round: the three are alternative
     * readings of the same sticks and buttons, so leaving the others on would have a game
     * seeing one press twice.
     */
    private static void toggleGamepadMode(XServerDisplayActivity context) {
        boolean gamepad = !XrActivity.gamepadEmulation;

        XrActivity.gamepadEmulation = gamepad;
        XrActivity.keysEmulation = !gamepad;
        XrActivity.mouseEmulation = !gamepad;

        SessionSettings.edit(context)
                .putBoolean("use_xr_gamepad", gamepad)
                .putBoolean("use_xr_keys", !gamepad)
                .putBoolean("use_xr_mouse", !gamepad)
                .apply();

        if (gamepad) XrInput.ensureVirtualControllerAttached();
    }

    private static String getStatusText(Context context) {
        StringBuilder status = new StringBuilder();
        status.append(DateFormat.getTimeFormat(context).format(new Date()));

        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int state = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            if (level >= 0 && scale > 0) {
                status.append("   ").append(context.getString(R.string.battery)).append(" ").append(level * 100 / scale).append("%");
                if (state == BatteryManager.BATTERY_STATUS_CHARGING || state == BatteryManager.BATTERY_STATUS_FULL) {
                    status.append(" (").append(context.getString(R.string.charging)).append(")");
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (int deviceId : InputDevice.getDeviceIds()) {
                InputDevice device = InputDevice.getDevice(deviceId);
                if (device == null || device.isVirtual() || !isGamepad(device)) continue;
                BatteryState batteryState = device.getBatteryState();
                if (batteryState != null && batteryState.isPresent() && !Float.isNaN(batteryState.getCapacity())) {
                    status.append("   ").append(context.getString(R.string.gamepad)).append(" ").append(Math.round(batteryState.getCapacity() * 100)).append("%");
                    break;
                }
            }
        }

        return status.toString();
    }

    private static boolean isGamepad(InputDevice device) {
        int sources = device.getSources();
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            || ((sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK && (sources & InputDevice.SOURCE_MOUSE) == 0);
    }

    public int dpToPx(float dp, Context context){
        return (int) (dp * context.getResources().getDisplayMetrics().densityDpi / DisplayMetrics.DENSITY_DEFAULT);
    }
}
