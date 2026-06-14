/*
 * Copyright (C) 2024-2026 WinlatorXR
 *
 * This file is part of WinlatorXR.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#pragma once

#include "engine.h"
#include "framebuffer.h"

enum XrConfigFloat
{
    // 2D canvas positioning
    CONFIG_CANVAS_DISTANCE,
    CONFIG_CANVAS_SIZE,
    CONFIG_MENU_PITCH,
    CONFIG_MENU_YAW,
    CONFIG_RECENTER_YAW,
    CONFIG_VIEWPORT_FOVX,
    CONFIG_VIEWPORT_FOVY,
    CONFIG_VIEWPORT_FOV_SCALE,

    CONFIG_FLOAT_MAX
};

enum XrConfigInt
{
    // switching between modes
    CONFIG_FRAMESYNC,
    CONFIG_PASSTHROUGH,
    CONFIG_IMMERSIVE,
    CONFIG_AER,
    CONFIG_SBS,
    CONFIG_VR,
    // config levels
    CONFIG_FRAMERATE,
    CONFIG_LEVEL_CPU,
    CONFIG_LEVEL_GPU,
    // viewport setup
    CONFIG_VIEWPORT_CURVED,
    CONFIG_VIEWPORT_WIDTH,
    CONFIG_VIEWPORT_HEIGHT,
    // render status
    CONFIG_CURRENT_FBO,
    CONFIG_FRAMESYNC_R,
    CONFIG_FRAMESYNC_G,
    CONFIG_FRAMESYNC_B,
    CONFIG_FRAMESYNC_A,

    // end
    CONFIG_INT_MAX
};

struct XrRenderer {
    bool SessionActive;
    bool SessionFocused;
    bool Initialized;
    bool StageSupported;
    float ConfigFloat[CONFIG_FLOAT_MAX];
    int ConfigInt[CONFIG_INT_MAX];

    struct XrFramebuffer Framebuffer[XrMaxNumEyes];

    float FovScale;
    int FrameSync;
    int LayerCount;
    XrCompositorLayer Layers[XrMaxLayerCount];
    XrPassthroughFB Passthrough;
    XrPassthroughLayerFB PassthroughLayer;
    bool PassthroughRunning;

    XrView* Projections;
    XrPosef InvertedViewPose[2][XrMaxFrameSync + 1];
    XrVector3f HmdOrientation;
    float HmdAltitude;
};

void XrRendererInit(struct XrEngine* engine, struct XrRenderer* renderer);
void XrRendererDestroy(struct XrEngine* engine, struct XrRenderer* renderer);

bool XrRendererInitFrame(struct XrEngine* engine, struct XrRenderer* renderer);
void XrRendererBeginFrame(struct XrRenderer* renderer, int fbo_index);
void XrRendererEndFrame(struct XrRenderer* renderer);
void XrRendererFinishFrame(struct XrEngine* engine, struct XrRenderer* renderer);

void XrRendererBindFramebuffer(struct XrRenderer* renderer);
void XrRendererRecenter(struct XrEngine* engine, struct XrRenderer* renderer);

void XrRendererHandleSessionStateChanges(struct XrEngine* engine, struct XrRenderer* renderer, XrSessionState state);
void XrRendererHandleXrEvents(struct XrEngine* engine, struct XrRenderer* renderer);