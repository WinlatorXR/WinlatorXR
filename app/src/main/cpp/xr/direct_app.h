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

/*
 * App side of the direct transport (direct.c): the receiver, and the
 * projection layer it builds from the frames the bridge sends.
 */

#pragma once

#include "direct.h"
#include "engine.h"

/* Start listening for the bridge. Safe to call more than once. */
void XrDirectStart(void);

/* How a frame's space (the game's XrSpace id) becomes this session's XrSpace. */
void XrDirectSetSpaceResolver(XrSpace (*resolve)(uint64_t id));

/*
 * Render thread, between xrBeginFrame and xrEndFrame: while the bridge is
 * delivering frames, copies any new one into direct.c's own swapchains and
 * fills layer and views (2) with the eyes as the game rendered them, and quads
 * (WXR_DIRECT_MAX_QUADS) with its quad layers, to go over the projection.
 * layer->viewCount is 0 when the game sent quads only. Returns false when there
 * is nothing to show, so the caller keeps its own layer.
 */
bool XrDirectBuildLayer(XrSession session, XrCompositionLayerProjection* layer,
                        XrCompositionLayerProjectionView* views,
                        XrCompositionLayerQuad* quads, int* quad_count);

/* Render thread: true while the last layer built came from the bridge, so the
 * app need not draw the game window into its own screen swapchain. */
bool XrDirectIsActive(void);

/* Render thread: forces the eye copies' alpha to 1, for blending over passthrough. */
extern bool XrDirectOpaqueEyes;

/* Colour key for the eye copies (0 off, 1 green, 2 blue, 3 pink, 4 black), applied over passthrough. */
extern int XrDirectKeyMode;
extern float XrDirectKeyThreshold;

/* Frames per second the bridge is delivering, while direct frames are active. */
float XrDirectFps(void);
