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
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import com.winlator.cmod.R;
import com.winlator.cmod.contentdialog.ContentDialog;

public class XrStartupDialog extends ContentDialog {
    public XrStartupDialog(Activity activity) {
        super(activity, R.layout.preloader_dialog);
        setCanceledOnTouchOutside(false);
        setCancelable(false);

        findViewById(R.id.BTConfirm).setVisibility(TextView.GONE);
        findViewById(R.id.BTCancel).setVisibility(TextView.GONE);

        Window window = getWindow();
        if (window != null) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            window.setTitle("");
        }
    }

    public synchronized void show(int textResId) {
        ((TextView)findViewById(R.id.TextView)).setText(textResId);
        if (!isShowing()) show();
    }
}
