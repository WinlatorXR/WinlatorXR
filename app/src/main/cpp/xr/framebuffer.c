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

#include "framebuffer.h"

#if XR_USE_GRAPHICS_API_OPENGL_ES
#include <GLES3/gl3.h>
#include <GLES3/gl3ext.h>
#endif

#include <stdlib.h>
#include <string.h>

bool XrFramebufferCreate(struct XrFramebuffer *framebuffer, XrSession session, int width, int height)
{
    memset(framebuffer, 0, sizeof(framebuffer));

#if XR_USE_GRAPHICS_API_OPENGL_ES
    return XrFramebufferCreateGL(framebuffer, session, width, height);
#else
    return false;
#endif
}

void XrFramebufferDestroy(struct XrFramebuffer *framebuffer)
{
#if XR_USE_GRAPHICS_API_OPENGL_ES
    GL(glDeleteFramebuffers(framebuffer->SwapchainLength, framebuffer->GLFrameBuffers));
    free(framebuffer->GLFrameBuffers);
#endif
    OXR(xrDestroySwapchain(framebuffer->Handle));
    free(framebuffer->SwapchainImage);
}

void XrFramebufferAcquire(struct XrFramebuffer *framebuffer)
{
    XrSwapchainImageAcquireInfo acquire_info = {XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO, NULL};
    OXR(xrAcquireSwapchainImage(framebuffer->Handle, &acquire_info, &framebuffer->SwapchainIndex));

    XrSwapchainImageWaitInfo wait_info;
    wait_info.type = XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO;
    wait_info.next = NULL;
    wait_info.timeout = 1000000; /* timeout in nanoseconds */
    XrResult res = xrWaitSwapchainImage(framebuffer->Handle, &wait_info);
    int i = 0;
    while ((res != XR_SUCCESS) && (i < 10))
    {
        res = xrWaitSwapchainImage(framebuffer->Handle, &wait_info);
        i++;
        ALOGV("Retry xrWaitSwapchainImage %d times due XR_TIMEOUT_EXPIRED (duration %lf ms",
              i, wait_info.timeout * (1E-9));
    }

    framebuffer->Acquired = res == XR_SUCCESS;
    XrFramebufferSetCurrent(framebuffer);

#if XR_USE_GRAPHICS_API_OPENGL_ES
    GL(glEnable(GL_SCISSOR_TEST));
    GL(glViewport(0, 0, framebuffer->Width, framebuffer->Height));
    GL(glClearColor(0.0f, 0.0f, 0.0f, 0.0f));
    GL(glScissor(0, 0, framebuffer->Width, framebuffer->Height));
    GL(glClear(GL_COLOR_BUFFER_BIT));
    GL(glScissor(0, 0, 0, 0));
    GL(glDisable(GL_SCISSOR_TEST));
#endif
}

void XrFramebufferRelease(struct XrFramebuffer *framebuffer)
{
    if (framebuffer->Acquired)
    {
        const GLenum depthAttachment[1] = {GL_DEPTH_ATTACHMENT};
        glInvalidateFramebuffer(GL_FRAMEBUFFER, 1, depthAttachment);
        XrSwapchainImageReleaseInfo release_info = {XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO, NULL};
        OXR(xrReleaseSwapchainImage(framebuffer->Handle, &release_info));
        framebuffer->Acquired = false;
    }
}

void XrFramebufferSetCurrent(struct XrFramebuffer *framebuffer)
{
#if XR_USE_GRAPHICS_API_OPENGL_ES
    GL(glBindFramebuffer(GL_FRAMEBUFFER, framebuffer->GLFrameBuffers[framebuffer->SwapchainIndex]));
#endif
}

#if XR_USE_GRAPHICS_API_OPENGL_ES
#define GL_FRAMEBUFFER_SRGB               0x8DB9
bool XrFramebufferCreateGL(struct XrFramebuffer *framebuffer, XrSession session, int width, int height)
{
    glDisable(GL_FRAMEBUFFER_SRGB);
    XrSwapchainCreateInfo swapchain_info;
    memset(&swapchain_info, 0, sizeof(swapchain_info));
    swapchain_info.type = XR_TYPE_SWAPCHAIN_CREATE_INFO;
    swapchain_info.sampleCount = 1;
    swapchain_info.width = width;
    swapchain_info.height = height;
    swapchain_info.faceCount = 1;
    swapchain_info.mipCount = 1;
    swapchain_info.arraySize = 1;

    framebuffer->Width = swapchain_info.width;
    framebuffer->Height = swapchain_info.height;

    // Create the color swapchain.
    swapchain_info.format = GL_SRGB8_ALPHA8;;
    swapchain_info.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;
    OXR(xrCreateSwapchain(session, &swapchain_info, &framebuffer->Handle));
    OXR(xrEnumerateSwapchainImages(framebuffer->Handle, 0, &framebuffer->SwapchainLength, NULL));
    framebuffer->SwapchainImage = malloc(framebuffer->SwapchainLength * sizeof(XrSwapchainImageOpenGLESKHR));

    // Populate the swapchain image array.
    for (uint32_t i = 0; i < framebuffer->SwapchainLength; i++)
    {
        XrSwapchainImageOpenGLESKHR* swapchain = (XrSwapchainImageOpenGLESKHR*)framebuffer->SwapchainImage;
        swapchain[i].type = XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR;
        swapchain[i].next = NULL;
    }
    OXR(xrEnumerateSwapchainImages(framebuffer->Handle, framebuffer->SwapchainLength,
                                   &framebuffer->SwapchainLength,
                                   (XrSwapchainImageBaseHeader*)framebuffer->SwapchainImage));

    framebuffer->GLFrameBuffers = (GLuint*)malloc(framebuffer->SwapchainLength * sizeof(GLuint));
    for (uint32_t i = 0; i < framebuffer->SwapchainLength; i++)
    {
        GLuint color_texture = ((XrSwapchainImageOpenGLESKHR*)framebuffer->SwapchainImage)[i].image;

        // Create the frame buffer.
        GL(glGenFramebuffers(1, &framebuffer->GLFrameBuffers[i]));
        GL(glBindFramebuffer(GL_FRAMEBUFFER, framebuffer->GLFrameBuffers[i]));
        GL(glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                                  color_texture, 0));
        GL(GLenum renderFramebufferStatus = glCheckFramebufferStatus(GL_FRAMEBUFFER));
        GL(glBindFramebuffer(GL_FRAMEBUFFER, 0));
        if (renderFramebufferStatus != GL_FRAMEBUFFER_COMPLETE)
        {
            ALOGE("Incomplete frame buffer object: %d", renderFramebufferStatus);
            return false;
        }
    }

    return true;
}
#endif
