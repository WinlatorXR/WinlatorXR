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
package com.winlator.xr.io;

import android.opengl.GLES20;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Pair;

import com.winlator.cmod.math.XForm;
import com.winlator.cmod.renderer.GLRenderer;
import com.winlator.cmod.renderer.RenderableWindow;
import com.winlator.cmod.renderer.Texture;
import com.winlator.cmod.renderer.material.BGRAMaterial;
import com.winlator.cmod.renderer.material.BGRMaterial;
import com.winlator.cmod.renderer.material.ShaderMaterial;
import com.winlator.cmod.widget.XServerView;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;
import com.winlator.xr.XrActivity;
import com.winlator.xr.api.XrFramesync;
import com.winlator.xr.ui.XrContentDialog;
import com.winlator.xr.ui.XrFpsOverlay;
import com.winlator.xr.ui.XrKeyboard;
import com.winlator.xr.utils.XrEnvironment;

import javax.microedition.khronos.opengles.GL10;

public class XrRenderer extends GLRenderer {
    /** How far the frame rate panel sits from the corner, as a fraction of the screen. */
    private static final float FPS_PANEL_MARGIN = 0.01f;

    private final BGRMaterial bgrMaterial = new BGRMaterial();
    private final BGRAMaterial dialogMaterial = new BGRAMaterial();

    private final Texture[] lastTexture = {new Texture(), new Texture()};
    private short lastTextureWidth = 0;
    private short lastTextureHeight = 0;

    private long timestampHadWindow = Long.MAX_VALUE;

    private boolean xrFrameReady = false;
    private boolean xrFrameStarted = false;
    private final XrFramesync xrFramesync;
    private final XrFpsOverlay fpsOverlay = new XrFpsOverlay();

    public static boolean autoclose = true;
    public static boolean vrWindowOnTop = false;

    private static XrRenderer instance = null;

    public XrRenderer(XServerView xServerView, XServer xServer) {
        super(xServerView, xServer);
        xrFramesync = new XrFramesync(xServerView.getContext());
        instance = this;
    }

    public static int getLastFPS() {
        return instance.xrFramesync.getLastFPS();
    }

    /** The rate the guest is redrawing the window the user is looking at. */
    public static int getGuestFPS() {
        return instance == null ? 0 : instance.fpsOverlay.getLastFPS();
    }

    @Override
    public void onUpdateWindowContent(Window window) {
        fpsOverlay.onContentUpdate(window.getContent());
        super.onUpdateWindowContent(window);
    }

    @Override
    public boolean isCursorVisible() {
        return (XrActivity.isVR && !vrWindowOnTop) || super.isCursorVisible();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        if (XrActivity.isEnabled(null)) {
            XrActivity activity = XrActivity.getInstance();
            String res = activity.getScreenSize();
            String[] parts = res.split("x");
            width = Short.parseShort(parts[0]);
            height = Short.parseShort(parts[1]);
            if (width < 1280) {
                height = 1280 * height / width;
                width = 1280;
            }

            int cpuLevel = activity.getContainer().getCpuLevel();
            int gpuLevel = activity.getContainer().getGpuLevel();
            int refresh = activity.getContainer().getRefreshRate();
            activity.init(width, height, refresh, cpuLevel, gpuLevel);
            XrEnvironment.apply(activity, XrEnvironment.getSelected(activity));
            height = width; ////Use square resolution
            GLES20.glViewport(0, 0, width, height);
            magnifierEnabled = false;
        }

        super.onSurfaceChanged(gl, width, height);
    }

    @Override
    protected boolean preDrawable(ShaderMaterial material, Drawable drawable) {
        if (XrActivity.isEnabled(null) && XrActivity.isVR && vrWindowOnTop && xrFrameReady) {
            xrFramesync.process(drawable, (r, g, b, a) -> XrActivity.getInstance().nativeSetFramesync(r, g, b, a));
            xrFrameReady = false;
            if (XrActivity.getAER()) {
                renderAER(drawable, material, xrFramesync.getAerShouldUpdate(), xrFramesync.getAerTargetFBO());
                return false;
            }
        }
        return super.preDrawable(material, drawable);
    }

    @Override
    protected void preFrame() {
        super.preFrame();

        if (XrActivity.isEnabled(null)) {
            fullscreen = XrActivity.getVR();
            xrFrameReady = xrFrameStarted = XrActivity.getInstance().initFrame(
                    XrActivity.getImmersive() || XrActivity.getVR(),
                    XrActivity.getSBS(), XrActivity.getAER(), XrActivity.getDistance());
            XrActivity.getInstance().updateFrame();
            if (!XrActivity.getAER()) {
                XrActivity.getInstance().bindFBO(0);
            }
        } else {
            fullscreen = false;
        }
    }

    @Override
    protected void postFrame() {
        super.postFrame();

        if (xrFrameStarted) {
            renderDialog();
            xrFrameReady = false;
            XrActivity.getInstance().endFrame();
            xServerView.requestRender();
        }
    }

    @Override
    protected Pair<Float, Float> preTransform() {
        if (!XrActivity.isEnabled(null)) {
            return super.preTransform();
        } else if (!fullscreen && XrActivity.getSBS() && !renderableWindows.isEmpty()) {
            RenderableWindow window = renderableWindows.get(renderableWindows.size() - 1);
            return new Pair<>((float)window.rootX, (float)window.rootY);
        } else {
            return new Pair<>(0.0f, 0.0f);
        }
    }

    @Override
    protected void preWindows() {
        super.preWindows();

        if (XrActivity.isEnabled(null)) {
            if (!fullscreen && XrActivity.getSBS() && !renderableWindows.isEmpty()) {
                RenderableWindow window = renderableWindows.get(renderableWindows.size() - 1);
                magnifierZoom = xServer.screenInfo.width / (float)window.content.width;
                magnifierEnabled = true;
            } else {
                magnifierEnabled = false;
                magnifierZoom = 1;
            }
        }
    }

    @Override
    protected void postWindows() {
        super.postWindows();
        if (!renderableWindows.isEmpty()) {
            timestampHadWindow = System.currentTimeMillis();
            if (!renderableWindows.isEmpty()) {
                RenderableWindow window = renderableWindows.get(renderableWindows.size() - 1);
                vrWindowOnTop = (window.rootX == 0) && (window.rootY == 0);
                // This is the window renderWindows draws, so its redraws are the frame rate
                // the user is watching - not those of a launcher still ticking away behind it.
                fpsOverlay.setTrackedContent(window.content);
            }
        }  else if ((System.currentTimeMillis() - timestampHadWindow > 1000)) {
            if (autoclose && XrActivity.isEnabled(null)) {
                XrActivity.getInstance().runOnUiThread(() -> XrActivity.getInstance().closeSession());
            }
        }
    }

    private void renderAER(Drawable drawable, ShaderMaterial material, boolean shouldUpdate, int targetFBO) {
        if ((lastTextureWidth != drawable.getStride()) || (lastTextureHeight != drawable.height)) {
            for (int i = 0; i < lastTexture.length; i++) {
                lastTexture[i].destroy();
                lastTexture[i] = new Texture();
            }
            lastTextureWidth = drawable.getStride();
            lastTextureHeight = drawable.height;
        }

        if (shouldUpdate) {
            lastTexture[targetFBO].setNeedsUpdate(true);
            lastTexture[targetFBO].updateFromBuffer(drawable.getData(), drawable.getStride(), drawable.height);
        }

        for (int i = 0; i < lastTexture.length; i++) {
            XrActivity.getInstance().bindFBO(i);
            if (lastTexture[i].isAllocated()) {
                renderTexture(lastTexture[i], material);
            }
        }
        XrActivity.getInstance().bindFBO(-1);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    /**
     * The frame rate reading goes in after the game and the cursor so that nothing is drawn
     * over it, and from here rather than beside the dialogs so that it shares the transform
     * the window was drawn with: that puts it in the corner of the game rather than the
     * corner of the square the compositor is handed.
     */
    @Override
    public void drawFrame() {
        super.drawFrame();
        renderFPS();
    }

    /**
     * Draws the game's frame rate over the top left of the window, where the DXVK HUD this
     * stands in for would have been.
     *
     * Left out of the two modes it cannot be drawn correctly in: AER renders the window into
     * its own pair of framebuffers and leaves the default one bound, so there is nothing here
     * for the panel to land in, and a side-by-side render splits the screen between the eyes,
     * where a panel drawn once would reach one eye only.
     */
    private void renderFPS() {
        if (!xrFrameStarted || !XrActivity.showFPS) return;
        if (XrActivity.getAER() || XrActivity.getSBS()) return;

        // A VR title is counted by its own frame sync rather than by its window redraws, which
        // getLastFPS already prefers; either way this is the number the main menu reports.
        Drawable reading = fpsOverlay.getDrawable(XrActivity.getInstance().getLastFPS(),
                xServer.screenInfo.height);

        // In VR the window fills the square the compositor is handed instead of being
        // letterboxed into it, which stretches it vertically; the panel has to be widened by
        // the same amount to come out square itself.
        float aspect = fullscreen ? xServer.screenInfo.width / (float)xServer.screenInfo.height : 1.0f;
        int margin = Math.round(xServer.screenInfo.height * FPS_PANEL_MARGIN);

        dialogMaterial.use();
        GLES20.glUniform2f(dialogMaterial.getUniformLocation("viewSize"), xServer.screenInfo.width, xServer.screenInfo.height);
        quadVertices.bind(dialogMaterial.programId);

        // The compositor reads this framebuffer's alpha to decide how much of the room or the
        // environment shows through, and the window under the panel had left it opaque. Blending
        // a translucent panel the ordinary way thinned it again, which put passthrough behind the
        // reading rather than the game. So blend the colour as usual but only ever add to the
        // alpha: the panel can darken what is behind it without opening a hole, and it still
        // shows up where the screen had nothing behind it to begin with. Put the shared state
        // back afterwards.
        GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA,
                GLES20.GL_ONE, GLES20.GL_ONE);
        renderDrawable(reading, Math.round(margin * aspect), margin, dialogMaterial, false, aspect, 1);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        quadVertices.disable();
    }

    /**
     * Dialogs go through the alpha keeping material rather than the one the windows use: a
     * dialog drawn on a rounded panel has transparent corners, and they have to show the game
     * behind rather than a square of whatever those pixels happen to hold.
     */
    private void renderDialog() {
        dialogMaterial.use();
        GLES20.glUniform2f(dialogMaterial.getUniformLocation("viewSize"), xServer.screenInfo.width, xServer.screenInfo.height);
        quadVertices.bind(dialogMaterial.programId);

        XForm.identity(tmpXForm2);
        float aspect = xServer.screenInfo.width / (float)xServer.screenInfo.height;
        try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
            float div = XrActivity.getSBS() ? 2 : 1;
            for (XrContentDialog dialog : XrContentDialog.getInstances()) {
                Drawable drawable = dialog.getDrawable();
                if (drawable != null) {
                    float scale = xServer.screenInfo.height / 1200.0f;
                    if (XrKeyboard.isShown()) {
                        scale = xServer.screenInfo.height / (float)drawable.width / aspect;
                    } else if (Build.MANUFACTURER.compareToIgnoreCase("PICO") == 0) {
                        scale = 0.75f;
                        DisplayMetrics displayMetrics = new DisplayMetrics();
                        XrActivity.getInstance().getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
                        scale *= (float)Math.min(xServer.screenInfo.width, xServer.screenInfo.height);
                        scale /= (float)Math.min(displayMetrics.widthPixels, displayMetrics.heightPixels);
                    }

                    int offsetX = (int) ((xServer.screenInfo.width - drawable.width * aspect * scale) / 2 / div);
                    int offsetY = (int) ((xServer.screenInfo.height - drawable.height * scale) / 2);
                    if (XrKeyboard.isShown()) {
                        offsetY = (int) (xServer.screenInfo.height - viewTransformation.sceneOffsetY - drawable.height * scale);
                    }
                    renderDrawable(drawable, offsetX, offsetY, dialogMaterial, false, scale * aspect / div, scale);
                    if (div > 1) {
                        offsetX += (int) (xServer.screenInfo.width / div);
                        renderDrawable(drawable, offsetX, offsetY, dialogMaterial, false, scale * aspect / div, scale);
                    }
                }
            }
        }
        quadVertices.disable();
    }

    @Override
    protected void renderCursor() {
        if (XrActivity.isVR && !vrWindowOnTop) {
            cursorMaterial.use();
            GLES20.glUniform2f(cursorMaterial.getUniformLocation("viewSize"), xServer.screenInfo.width, xServer.screenInfo.height);
            quadVertices.bind(cursorMaterial.programId);

            try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
                short x = xServer.pointer.getClampedX();
                short y = xServer.pointer.getClampedY();
                renderDrawable(rootCursorDrawable, x, y, cursorMaterial);
            }

            quadVertices.disable();
        } else {
            super.renderCursor();
        }
    }

    @Override
    protected void renderWindows(ShaderMaterial material, boolean forceFullscreen) {
        boolean fullscreen = (XrActivity.isVR && XrRenderer.vrWindowOnTop) || XrActivity.isImmersive;
        super.renderWindows(material, fullscreen);
    }
}
