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
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.SessionSettings;
import com.winlator.xr.XrActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * What the user looks at while a game boots in a headset. A boot is the only moment the XR
 * controls can be taught without interrupting anything, so the dialog carries one hint about
 * them underneath the spinner.
 *
 * One, not a rotation: by the time a second would be due the game is usually already up, and
 * holding it back to keep reading is worse than the hint is worth.
 */
public class XrStartupDialog extends ContentDialog {
    /**
     * App wide, off by default, and only settable from the Settings screen: a user who knows
     * the controls has nothing to gain from being held at the hint, and it is not something
     * to be changing from inside a session, where the hints are already behind them.
     */
    public static final String PREF_DISABLE_HINTS = "xr_disable_startup_hints";

    /** Long enough to read a two line hint. */
    private static final long HINT_MILLIS = 9000;

    /** The first boot's hint stays up longer; it is the one that has to land. */
    private static final long FIRST_BOOT_HINT_MILLIS = 12000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hintDone = this::dismiss;
    private final List<String> hints;
    private final TextView hintView;
    /** When the hint has had its time. Zero while there is no hint up. */
    private long hintUntil;
    private boolean hasMenuHint;
    private boolean gameReady;

    public XrStartupDialog(Activity activity) {
        super(activity, R.layout.xr_startup_dialog);
        setCanceledOnTouchOutside(false);
        setCancelable(false);

        // Nothing here is dismissable by hand, so the whole bar goes rather than the buttons
        // alone: what is left of it is a divider above an empty row.
        findViewById(R.id.LLBottomBar).setVisibility(View.GONE);

        boolean xr = XrActivity.isEnabled(activity);
        // In a headset the dialog never reaches the screen through its window, so the card the
        // window background would have drawn is not there: XrContentDialog renders the view
        // onto a bitmap of its own, on bare white. Giving the view a panel of its own puts the
        // corners back, and leaves the flat path alone, where the window still has its card.
        if (xr) getContentView().setBackgroundResource(R.drawable.xr_startup_panel);

        hintView = findViewById(R.id.TVHint);
        // Every hint is about the XR controllers, so a session without them gets the bare
        // preloader it had before, as does one where they have been turned off. Shuffled
        // otherwise, because only one of them is going to be read and it should not be the
        // same one every boot.
        boolean wanted = xr && !PreferenceManager
                .getDefaultSharedPreferences(activity).getBoolean(PREF_DISABLE_HINTS, false);
        hints = wanted
                ? new ArrayList<>(Arrays.asList(
                        activity.getResources().getStringArray(R.array.xr_startup_hints)))
                : new ArrayList<>();
        Collections.shuffle(hints);

        // The panel and its label say "there is a tip here", so an empty one would be worse
        // than none: the whole block goes, leaving the status line on its own.
        if (hints.isEmpty()) findViewById(R.id.LLHint).setVisibility(View.GONE);

        Window window = getWindow();
        if (window != null) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            window.setTitle("");
        }
    }

    /**
     * @param firstTimeBoot the container has never finished a boot, so this is the long wait:
     *                      everything it needs is being built before the game even starts.
     */
    public synchronized void show(int textResId, boolean firstTimeBoot) {
        ((TextView)findViewById(R.id.TextView)).setText(textResId);
        if (isShowing()) return;

        if (!hints.isEmpty()) {
            // The menu hint lives here rather than in the array because it names a stick, and
            // which stick follows the primary hand. Read from the settings rather than from
            // XrActivity.mouseLeftHanded: that is loaded after this runs, so on the second game
            // of a session it would still be holding the first game's answer.
            boolean leftHanded = SessionSettings.getBoolean(getContext(), "use_xr_leftHanded",
                    XrActivity.DEFAULT_MOUSE_LEFT_HANDED);
            String menuHint = getContext().getString(leftHanded
                    ? R.string.xr_startup_hint_menu_left_handed
                    : R.string.xr_startup_hint_menu);

            // On a first boot the way into the menu is the one thing worth saying: the user has
            // been told it nowhere else, and without it none of the others can be acted on. On
            // any boot after that it takes its chances with the rest, because a user who has
            // forgotten it has nowhere else to be reminded of it either.
            String hint;
            if (firstTimeBoot) {
                hint = menuHint;
            } else {
                if (!hasMenuHint) {
                    hints.add(menuHint);
                    hasMenuHint = true;
                }
                Collections.shuffle(hints);
                hint = hints.get(0);
            }

            hintView.setText(hint);
            hintUntil = SystemClock.uptimeMillis()
                    + (firstTimeBoot ? FIRST_BOOT_HINT_MILLIS : HINT_MILLIS);
        }

        show();
    }

    /**
     * The game has put its first frame on the screen behind this dialog. That is not always
     * the moment to go: a container that boots in a couple of seconds would pull the hint away
     * half read, and the frame underneath at that point is usually a splash or an empty window
     * with nothing to do in it yet. So the hint is left to finish over the drawn window, and
     * the dialog goes when it has had its time - immediately, on any boot that already took
     * longer than that.
     */
    public synchronized void onGameWindowReady() {
        if (!isShowing() || gameReady) return;
        gameReady = true;

        long remaining = hintUntil - SystemClock.uptimeMillis();
        if (remaining > 0) {
            handler.postDelayed(hintDone, remaining);
        } else {
            dismiss();
        }
    }

    @Override
    public void dismiss() {
        handler.removeCallbacks(hintDone);
        super.dismiss();
    }
}
