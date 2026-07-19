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

import java.util.Date;

public class NavigationDialog extends ContentDialog {
    private static final int STATUS_REFRESH_INTERVAL_MS = 10000;

    public NavigationDialog(@NonNull XServerDisplayActivity context) {
        super(context, R.layout.navigation_dialog);
        setIcon(R.drawable.icon_container);
        setTitle(context.getString(R.string.app_name));
        findViewById(R.id.BTCancel).setVisibility(View.GONE);

        GridLayout grid = findViewById(R.id.main_menu_grid);
        grid.setColumnCount(3);

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
