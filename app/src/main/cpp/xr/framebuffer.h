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

struct XrFramebuffer {
    int Width;
    int Height;
    bool Acquired;
    XrSwapchain Handle;

    uint32_t SwapchainIndex;
    uint32_t SwapchainLength;
    void* SwapchainImage;

    unsigned int* GLFrameBuffers;
};

bool XrFramebufferCreate(struct XrFramebuffer *framebuffer, XrSession session, int width, int height);
void XrFramebufferDestroy(struct XrFramebuffer *framebuffer);

void XrFramebufferAcquire(struct XrFramebuffer *framebuffer);
void XrFramebufferRelease(struct XrFramebuffer *framebuffer);
void XrFramebufferSetCurrent(struct XrFramebuffer *framebuffer);

#if XR_USE_GRAPHICS_API_OPENGL_ES
bool XrFramebufferCreateGL(struct XrFramebuffer *framebuffer, XrSession session, int width, int height);
#endif
