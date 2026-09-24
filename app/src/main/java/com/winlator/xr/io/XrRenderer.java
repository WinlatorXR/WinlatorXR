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
import android.util.Log;
import android.util.Pair;

import com.winlator.cmod.math.XForm;
import com.winlator.cmod.renderer.GLRenderer;
import com.winlator.cmod.renderer.RenderableWindow;
import com.winlator.cmod.renderer.Texture;
import com.winlator.cmod.renderer.material.BGRAMaterial;
import com.winlator.cmod.renderer.material.ShaderMaterial;
import com.winlator.cmod.widget.XServerView;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;
import com.winlator.xr.XrActivity;
import com.winlator.xr.api.XrAPI;
import com.winlator.xr.api.XrFramesync;
import com.winlator.xr.ui.XrContentDialog;
import com.winlator.xr.ui.XrFpsOverlay;
import com.winlator.xr.ui.XrKeyboard;
import com.winlator.xr.utils.XrEnvironment;

import javax.microedition.khronos.opengles.GL10;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class XrRenderer extends GLRenderer {
    /** How far the frame rate panel sits from the corner, as a fraction of the screen. */
    private static final float FPS_PANEL_MARGIN = 0.01f;
    /** In VR the screen is a projection layer wider than the lenses show, so its corner is out of sight. */
    private static final float FPS_PANEL_MARGIN_VR = 0.35f;
    /** Lower still, so the panel sits where the eye can read it. */
    private static final float FPS_PANEL_MARGIN_VR_TOP = 0.4f;

    private final BGRAMaterial dialogMaterial = new BGRAMaterial();
    private final Texture[] lastTexture = {new Texture(), new Texture()};
    private short lastTextureWidth = 0;
    private short lastTextureHeight = 0;

    private long timestampHadWindow = Long.MAX_VALUE;

    private boolean xrFrameReady = false;
    private boolean xrFrameStarted = false;
    private boolean directActive = false;
    private boolean screenBound = true;
    private boolean sbs = false;
    private boolean sbsStretch = false;
    private float sbsTrim = 0;
    private final Set<Integer> taskManagerWindows = ConcurrentHashMap.newKeySet();
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

    public static boolean isDirectActive() {
        return instance != null && instance.directActive;
    }

    /** The rate the guest is redrawing the window the user is looking at. */
    public static int getGuestFPS() {
        return instance == null ? 0 : instance.fpsOverlay.getLastFPS();
    }

    @Override
    public void onMapWindow(Window window) {
        if (window.getClassName().toLowerCase(Locale.ENGLISH).contains("taskmgr")) taskManagerWindows.add(window.id);
        super.onMapWindow(window);
    }

    @Override
    public void onUnmapWindow(Window window) {
        taskManagerWindows.remove(window.id);
        super.onUnmapWindow(window);
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
            // The headset's eye size is only known now; the runtime reads it at the game's xrCreateInstance
            activity.updateRecommendedEyeSize();
            try {
                XrAPI.writeSystemInfo();
            } catch (Exception e) {
                Log.e("XrRenderer", "Failed to write XR system info", e);
            }
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
            sbs = XrActivity.getSBS() && taskManagerWindows.isEmpty();
            sbsStretch = XrActivity.sbsStretch && !XrActivity.isVR;
            sbsTrim = sbs && XrActivity.sbsTrim ? XrActivity.SBS_TRIM_PERCENT / 100.0f : 0;
            xrFrameReady = xrFrameStarted = XrActivity.getInstance().initFrame(
                    XrActivity.getImmersive() || XrActivity.getVR(),
                    sbs, sbsStretch, XrActivity.getAER(), XrActivity.getDistance());
            XrActivity.getInstance().updateFrame();
            // PC VR frames arriving directly replace the game window, so the screen swapchain
            // is only needed for what is drawn over them: the FPS panel and XR dialogs
            // Native only shows them in VR mode, so another window on top (the task manager) is drawn instead
            directActive = xrFrameStarted && fullscreen && XrActivity.getInstance().nativeIsDirectActive();
            screenBound = !directActive || XrActivity.showFPS || !XrContentDialog.getInstances().isEmpty();
            if (!screenBound) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            } else if (!XrActivity.getAER()) {
                XrActivity.getInstance().bindFBO(0);
                // Acquiring the swapchain resets the viewport to the full square; SBS needs the screen's letterbox back unless set to original.
                viewportNeedsUpdate = !sbsStretch;
            }
        } else {
            fullscreen = false;
        }
    }

    @Override
    protected void postFrame() {
        super.postFrame();

        if (xrFrameStarted) {
            if (screenBound) {
                // Overlays are laid out for the full square, not the SBS letterbox, and the frame SBS turns on can leave its scissor set.
                GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
                renderFPS();
                // Under SBS, menus get their own full resolution layer instead of half of each eye
                boolean overlay = sbs && !XrContentDialog.getInstances().isEmpty() && XrActivity.getInstance().beginOverlay();
                if (overlay) {
                    sbs = false;
                    sbsTrim = 0;
                    // The overlay starts transparent, so alpha must build up as coverage, not coverage squared
                    GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA, GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                }
                renderDialog();
                if (overlay) GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            }
            xrFrameReady = false;
            XrActivity.getInstance().endFrame();
            xServerView.requestRender();
        }
    }

    @Override
    protected Pair<Float, Float> preTransform() {
        if (!XrActivity.isEnabled(null)) {
            return super.preTransform();
        } else if (!fullscreen && sbs && !renderableWindows.isEmpty()) {
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
            if (!fullscreen && sbs && !renderableWindows.isEmpty()) {
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
            int top = renderableWindows.size() - 1;
            RenderableWindow window = renderableWindows.get(top);
            vrWindowOnTop = (window.rootX == 0) && (window.rootY == 0);
            timestampHadWindow = System.currentTimeMillis();

            // Skip 1x1 helper windows mapped over the game (Aperture Hand Lab), they are not what is on screen
            while (top > 0 && renderableWindows.get(top).content.width <= 1 && renderableWindows.get(top).content.height <= 1) top--;
            fpsOverlay.setTrackedContent(renderableWindows.get(top).content);
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
     * The original SBS screen is half as wide and shows the whole square in the 16:9 height, so
     * overlays are shrunk vertically to keep their shape; 16:9 and plain modes leave them be.
     */
    private float getSbsFitY() {
        return sbs && sbsStretch ? xServer.screenInfo.width / (float)xServer.screenInfo.height / 2 : 1;
    }

    private void renderFPS() {
        if (!XrActivity.showFPS) return;
        int fps = XrActivity.getInstance().getLastFPS();
        Drawable drawable = fpsOverlay.getDrawable(fps, xServer.screenInfo.height);

        dialogMaterial.use();
        GLES20.glUniform2f(dialogMaterial.getUniformLocation("viewSize"), xServer.screenInfo.width, xServer.screenInfo.height);
        quadVertices.bind(dialogMaterial.programId);

        float div = sbs ? 2 : 1;
        float fitY = getSbsFitY();
        float aspect = fullscreen ? xServer.screenInfo.width / (float)xServer.screenInfo.height : 1.0f;
        float margin = directActive ? FPS_PANEL_MARGIN_VR : FPS_PANEL_MARGIN;
        int offsetX = Math.round(xServer.screenInfo.height * margin * aspect);
        offsetX += Math.round(xServer.screenInfo.width / div * sbsTrim);
        int offsetY = Math.round(xServer.screenInfo.height * (directActive ? FPS_PANEL_MARGIN_VR_TOP : FPS_PANEL_MARGIN));
        GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA, GLES20.GL_ONE, GLES20.GL_ONE);
        renderDrawable(drawable, offsetX, offsetY, dialogMaterial, false, aspect / div, fitY);
        if (div > 1) {
            offsetX += (int) (xServer.screenInfo.width / div);
            renderDrawable(drawable, offsetX, offsetY, dialogMaterial, false, aspect / div, fitY);
        }
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
            float div = sbs ? 2 : 1;
            float fitY = getSbsFitY();
            for (XrContentDialog dialog : XrContentDialog.getInstances()) {
                Drawable drawable = dialog.getDrawable();
                if (drawable != null) {
                    float scale = xServer.screenInfo.height / 1200.0f;
                    if (XrKeyboard.isShown()) {
                        scale = xServer.screenInfo.height / (float)drawable.width / aspect;
                        // Keep the keyboard inside the trimmed SBS eye
                        scale *= 1 - 2 * sbsTrim;
                    } else if (Build.MANUFACTURER.compareToIgnoreCase("PICO") == 0) {
                        scale = 0.75f;
                        DisplayMetrics displayMetrics = new DisplayMetrics();
                        XrActivity.getInstance().getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
                        scale *= (float)Math.min(xServer.screenInfo.width, xServer.screenInfo.height);
                        scale /= (float)Math.min(displayMetrics.widthPixels, displayMetrics.heightPixels);
                    }
                    scale *= dialog.getXrScale();

                    int offsetX = (int) ((xServer.screenInfo.width - drawable.width * aspect * scale) / 2 / div);
                    int offsetY = (int) ((xServer.screenInfo.height - drawable.height * scale * fitY) / 2);
                    if (XrKeyboard.isShown()) {
                        // The original SBS screen shows the whole square, so there is no letterbox to sit above
                        float sceneOffsetY = sbs && sbsStretch ? 0 : viewTransformation.sceneOffsetY;
                        offsetY = (int) (xServer.screenInfo.height - sceneOffsetY - drawable.height * scale * fitY);
                    }
                    renderDrawable(drawable, offsetX, offsetY, dialogMaterial, false, scale * aspect / div, scale * fitY);
                    if (div > 1) {
                        offsetX += (int) (xServer.screenInfo.width / div);
                        renderDrawable(drawable, offsetX, offsetY, dialogMaterial, false, scale * aspect / div, scale * fitY);
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
        if (directActive) {
            // Nothing drawn, but the window stack is still tracked: vrWindowOnTop keeps VR mode on
            try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
                preWindows();
                postWindows();
            }
            return;
        }
        boolean fullscreen = (XrActivity.isVR && XrRenderer.vrWindowOnTop) || XrActivity.isImmersive;
        super.renderWindows(material, fullscreen);
    }
}
