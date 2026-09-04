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

#pragma once

#include "engine.h"
#include "framebuffer.h"

// How much larger than the screen the glow layer is, per axis. The screen occupies the
// middle 1/XrEdgeGlowSpread of it and the rest is bled-out edge colour.
#define XrEdgeGlowSpread 1.6f

// The glow is a heavily blurred copy of the screen edges, so it needs almost no resolution;
// the compositor's bilinear upscale onto a large layer supplies the softness for free.
#define XrEdgeGlowSize 64

// The reduction runs in linear light, so lift it before writing: a straight copy of the
// screen edge reads as a dim smear rather than as spill. Gain brightens, saturation keeps
// the glow recognisably the colour of what is on screen instead of washing towards grey.
#define XrEdgeGlowGain 1.6f
#define XrEdgeGlowSaturation 1.25f

struct XrEdgeGlow {
    bool Initialized;
    struct XrFramebuffer Framebuffer;

    unsigned int Program;
    unsigned int VertexArray;
    unsigned int Sampler;
    bool EncodeSrgb;
    int LocSource;
    int LocSourceOffset;
    int LocSourceScale;
    int LocSpread;
    int LocIntensity;
    int LocGain;
    int LocSaturation;
    int LocEncodeSrgb;
};

bool XrEdgeGlowCreate(struct XrEdgeGlow* edge_glow, XrSession session);
void XrEdgeGlowDestroy(struct XrEdgeGlow* edge_glow);

/*
 * Reduces the region of source_texture described by the offset/scale UV rectangle into the
 * glow swapchain. Saves and restores every piece of GL state it touches, because it runs in
 * the middle of a frame the Java renderer owns.
 */
void XrEdgeGlowRender(struct XrEdgeGlow* edge_glow, unsigned int source_texture,
                       float offset_u, float offset_v, float scale_u, float scale_v,
                       float intensity);
