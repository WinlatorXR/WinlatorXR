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

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.os.SystemClock;

import com.winlator.cmod.xserver.Drawable;

/**
 * The rate the guest is producing frames at, counted and drawn as a small panel over the
 * game so a user does not have to run the DXVK HUD to see it.
 *
 * What it counts is how often the game's own window content is redrawn, which is the number
 * the HUD reports and is not the rate the headset composites at: the compositor keeps
 * running at the display's rate however slowly the game feeds it. Only the window the
 * renderer is actually showing counts, so a launcher or a splash screen still ticking away
 * behind the game cannot inflate the reading.
 *
 * Counting happens on whichever thread served the guest's request and drawing on the GL
 * thread, hence the lock around the sample and the volatile handover of the reading.
 */
public class XrFpsOverlay {
    // Long enough to average out an uneven run of frames, short enough that the number still
    // answers to what the game is doing.
    private static final long SAMPLE_MILLIS = 500;

    // A game that has stopped presenting - loading, or sitting behind an installer - leaves
    // the last sample standing for as long as it stalls. Past this the reading is reported as
    // no reading rather than as the frame rate from before the stall.
    private static final long STALE_MILLIS = 2000;

    // Text height as a fraction of the guest screen height, with a floor for the small screen
    // sizes. Kept near what the DXVK HUD draws itself at, so the reading takes up about as
    // little of the game as the thing it stands in for did.
    private static final float TEXT_SCALE = 0.015f;
    private static final int MIN_TEXT_SIZE = 12;

    /** Widest reading the panel is sized for, so a change of number never reallocates it. */
    private static final String WIDEST_READING = "9999 FPS";

    private volatile Drawable trackedContent;
    private volatile int lastFPS;
    private volatile long lastSample;
    private int frames;
    private long sampleStart;

    // Drawing state, touched only on the GL thread.
    private Bitmap bitmap;
    private Canvas canvas;
    private Paint textPaint;
    private Paint panelPaint;
    private Drawable drawable;
    private int drawnFPS = -1;
    private int textSize;
    private int padding;

    /**
     * Points the count at the window the renderer is showing. Whatever was counted for the
     * window before it belongs to a different game frame rate, so the sample starts over.
     */
    public void setTrackedContent(Drawable content) {
        if (content == trackedContent) return;
        trackedContent = content;
        synchronized (this) {
            frames = 0;
            sampleStart = 0;
            lastSample = 0;
            lastFPS = 0;
        }
    }

    /** Called for every window redraw, from whichever thread served the guest's request. */
    public void onContentUpdate(Drawable content) {
        if (content != trackedContent) return;

        long now = SystemClock.elapsedRealtime();
        synchronized (this) {
            if (sampleStart == 0) sampleStart = now;
            frames++;

            long elapsed = now - sampleStart;
            if (elapsed >= SAMPLE_MILLIS) {
                lastFPS = Math.round(frames * 1000.0f / elapsed);
                frames = 0;
                sampleStart = now;
                lastSample = now;
            }
        }
    }

    /** The last reading, or zero when the game has not presented recently enough for one. */
    public int getLastFPS() {
        long sample = lastSample;
        if (sample == 0 || SystemClock.elapsedRealtime() - sample > STALE_MILLIS) return 0;
        return lastFPS;
    }

    /**
     * The panel holding the given reading, as something the renderer can draw. Sized from the
     * guest screen so it stays the same size on the window whatever resolution the game runs
     * at, and only redrawn when the number it shows changes.
     */
    public Drawable getDrawable(int fps, int screenHeight) {
        int size = Math.max(MIN_TEXT_SIZE, Math.round(screenHeight * TEXT_SCALE));
        if (bitmap == null || textSize != size) {
            allocate(size);
        }
        if (drawnFPS != fps) {
            redraw(fps);
            drawnFPS = fps;
        }
        return drawable;
    }

    private void allocate(int size) {
        textSize = size;
        padding = Math.max(2, size / 4);

        // Monospaced so the panel does not twitch as the digits change.
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        textPaint.setTextSize(size);
        textPaint.setColor(Color.WHITE);

        panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        // Dark enough to stay readable over a bright game, sheer enough not to hide it.
        panelPaint.setColor(0xCC000000);

        int width = Math.round(textPaint.measureText(WIDEST_READING)) + padding * 2;
        int height = Math.round(textPaint.descent() - textPaint.ascent()) + padding * 2;
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        canvas = new Canvas(bitmap);
        drawable = Drawable.fromBitmap(bitmap);
        drawnFPS = -1;
    }

    private void redraw(int fps) {
        String reading = fps + " FPS";

        // The bitmap is sized for the widest reading and reused between redraws, so the part
        // the panel does not cover has to be cleared rather than left holding the last number.
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        float width = textPaint.measureText(reading) + padding * 2;
        float radius = padding;
        canvas.drawRoundRect(0, 0, width, bitmap.getHeight(), radius, radius, panelPaint);
        canvas.drawText(reading, padding, padding - textPaint.ascent(), textPaint);

        drawable.drawBitmap(bitmap);
    }
}
