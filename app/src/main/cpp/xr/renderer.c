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

#include <malloc.h>
#include <assert.h>
#include <string.h>
#include <math.h>
#include "engine.h"
#include "math.h"
#include "renderer.h"
#include "direct_app.h"
#include <jni.h>
#include <stdbool.h>

#if XR_USE_GRAPHICS_API_OPENGL_ES
#include <GLES3/gl3.h>
#endif

#define DECL_PFN(pfn) PFN_##pfn pfn = NULL
#define INIT_PFN(pfn) OXR(xrGetInstanceProcAddr(engine->Instance, #pfn, (PFN_xrVoidFunction*)(&pfn)))

DECL_PFN(xrCreatePassthroughFB);
DECL_PFN(xrDestroyPassthroughFB);
DECL_PFN(xrPassthroughStartFB);
DECL_PFN(xrPassthroughPauseFB);
DECL_PFN(xrCreatePassthroughLayerFB);
DECL_PFN(xrDestroyPassthroughLayerFB);
DECL_PFN(xrPassthroughLayerPauseFB);
DECL_PFN(xrPassthroughLayerResumeFB);

PFN_xrGetDisplayRefreshRateFB pfnGetDisplayRefreshRate = NULL;
PFN_xrRequestDisplayRefreshRateFB pfnRequestDisplayRefreshRate = NULL;

void XrRendererInit(struct XrEngine* engine, struct XrRenderer* renderer)
{
    if (renderer->Initialized)
    {
        XrRendererDestroy(engine, renderer);
    }
    memset(renderer, 0, sizeof(renderer));
    renderer->RecenterPending = true;
    // The memset above only covers a pointer, so state that must start clean is set here.
    renderer->EnvironmentCreated = false;
    renderer->EnvironmentReady = false;
    renderer->EdgeGlowRendered = false;
    renderer->OverlayCreated = false;
    renderer->OverlayFailed = false;
    renderer->OverlayRendered = false;

    if (engine->PlatformFlag[PLATFORM_EXTENSION_PASSTHROUGH])
    {
        INIT_PFN(xrCreatePassthroughFB);
        INIT_PFN(xrDestroyPassthroughFB);
        INIT_PFN(xrPassthroughStartFB);
        INIT_PFN(xrPassthroughPauseFB);
        INIT_PFN(xrCreatePassthroughLayerFB);
        INIT_PFN(xrDestroyPassthroughLayerFB);
        INIT_PFN(xrPassthroughLayerPauseFB);
        INIT_PFN(xrPassthroughLayerResumeFB);
    }

    uint32_t num_spaces = 0;
    OXR(xrEnumerateReferenceSpaces(engine->Session, 0, &num_spaces, NULL));
    XrReferenceSpaceType* spaces = (XrReferenceSpaceType*)malloc(num_spaces * sizeof(XrReferenceSpaceType));
    OXR(xrEnumerateReferenceSpaces(engine->Session, num_spaces, &num_spaces, spaces));

    for (uint32_t i = 0; i < num_spaces; i++)
    {
        if (spaces[i] == XR_REFERENCE_SPACE_TYPE_STAGE)
        {
            renderer->StageSupported = true;
            break;
        }
    }

    free(spaces);

    if (engine->CurrentSpace == XR_NULL_HANDLE)
    {
        XrRendererRecenter(engine, renderer);
    }

    renderer->Projections = (XrView*)(malloc(XrMaxNumEyes * sizeof(XrView)));
    for (int eye = 0; eye < XrMaxNumEyes; eye++) {
        memset(&renderer->Projections[eye], 0, sizeof(XrView));
        renderer->Projections[eye].type = XR_TYPE_VIEW;
    }

    // Create framebuffers.
    int width = renderer->ConfigInt[CONFIG_VIEWPORT_WIDTH];
    int height = renderer->ConfigInt[CONFIG_VIEWPORT_HEIGHT];
    for (int i = 0; i < XrMaxNumEyes; i++)
    {
        XrFramebufferCreate(&renderer->Framebuffer[i], engine->Session, width, height);
    }

    if (engine->PlatformFlag[PLATFORM_EXTENSION_PASSTHROUGH])
    {
        XrPassthroughCreateInfoFB ptci = {XR_TYPE_PASSTHROUGH_CREATE_INFO_FB};
        XrResult result;
        OXR(result = xrCreatePassthroughFB(engine->Session, &ptci, &renderer->Passthrough));

        if (XR_SUCCEEDED(result))
        {
            XrPassthroughLayerCreateInfoFB plci = {XR_TYPE_PASSTHROUGH_LAYER_CREATE_INFO_FB};
            plci.passthrough = renderer->Passthrough;
            plci.purpose = XR_PASSTHROUGH_LAYER_PURPOSE_RECONSTRUCTION_FB;
            OXR(xrCreatePassthroughLayerFB(engine->Session, &plci, &renderer->PassthroughLayer));
        }

        OXR(xrPassthroughStartFB(renderer->Passthrough));
    }
    for (int i = 0; i < XrMaxNumEyes; i++) {
        renderer->InvertedViewPose[i][XrMaxFrameSync].orientation.x = 0;
        renderer->InvertedViewPose[i][XrMaxFrameSync].orientation.y = 0;
        renderer->InvertedViewPose[i][XrMaxFrameSync].orientation.z = 0;
        renderer->InvertedViewPose[i][XrMaxFrameSync].orientation.w = 1;
        renderer->InvertedViewPose[i][XrMaxFrameSync].position.x = 0;
        renderer->InvertedViewPose[i][XrMaxFrameSync].position.y = 0;
        renderer->InvertedViewPose[i][XrMaxFrameSync].position.z = 0;
    }
    XrEdgeGlowCreate(&renderer->EdgeGlow, engine->Session);
    renderer->PassthroughRunning = false;
    renderer->Initialized = true;
    renderer->FrameSync = 0;
}

void XrRendererDestroy(struct XrEngine* engine, struct XrRenderer* renderer)
{
    if (engine->PlatformFlag[PLATFORM_EXTENSION_PASSTHROUGH])
    {
        if (renderer->PassthroughRunning)
        {
            OXR(xrPassthroughLayerPauseFB(renderer->PassthroughLayer));
        }
        OXR(xrPassthroughPauseFB(renderer->Passthrough));
        OXR(xrDestroyPassthroughFB(renderer->Passthrough));
        renderer->Passthrough = XR_NULL_HANDLE;
    }

    for (int i = 0; i < XrMaxNumEyes; i++)
    {
        XrFramebufferDestroy(&renderer->Framebuffer[i]);
    }
    XrRendererClearEnvironment(renderer);
    XrEdgeGlowDestroy(&renderer->EdgeGlow);
    if (renderer->OverlayCreated)
    {
        XrFramebufferDestroy(&renderer->Overlay);
        renderer->OverlayCreated = false;
    }
    free(renderer->Projections);
    renderer->Initialized = false;
}

bool XrRendererInitFrame(struct XrEngine* engine, struct XrRenderer* renderer)
{
    if (!renderer->Initialized)
    {
        return false;
    }
    XrRendererHandleXrEvents(engine, renderer);
    if (!renderer->SessionActive)
    {
        return false;
    }

    // Update passthrough
    if (renderer->PassthroughRunning != renderer->ConfigInt[CONFIG_PASSTHROUGH])
    {
        if (renderer->ConfigInt[CONFIG_PASSTHROUGH])
        {
            OXR(xrPassthroughLayerResumeFB(renderer->PassthroughLayer));
        }
        else
        {
            OXR(xrPassthroughLayerPauseFB(renderer->PassthroughLayer));
        }
        renderer->PassthroughRunning = renderer->ConfigInt[CONFIG_PASSTHROUGH];
    }

    XrEngineWaitForFrame(engine);

    XrViewLocateInfo projection_info = {};
    projection_info.type = XR_TYPE_VIEW_LOCATE_INFO;
    projection_info.next = NULL;
    projection_info.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
    projection_info.displayTime = engine->PredictedDisplayTime;
    projection_info.space = engine->CurrentSpace;

    XrViewState view_state = {XR_TYPE_VIEW_STATE, NULL};

    uint32_t projection_capacity = XrMaxNumEyes;
    uint32_t projection_count = projection_capacity;

    OXR(xrLocateViews(engine->Session, &projection_info, &view_state, projection_capacity,
                      &projection_count, renderer->Projections));
    return true;
}

void XrRendererLockFrame(struct XrEngine* engine, struct XrRenderer* renderer) {
    // Get the HMD pose, predicted for the middle of the time period during which
    // the new eye images will be displayed. The number of frames predicted ahead
    // depends on the pipeline depth of the engine and the synthesis rate.
    // The better the prediction, the less black will be pulled in at the edges.
    XrFrameBeginInfo begin_frame_info = {};
    begin_frame_info.type = XR_TYPE_FRAME_BEGIN_INFO;
    begin_frame_info.next = NULL;
    OXR(xrBeginFrame(engine->Session, &begin_frame_info));

    float fovx = 0;
    float fovy = 0;
    renderer->FrameSync = renderer->FrameSync + XrFrameSyncStep;
    if (renderer->FrameSync >= XrMaxFrameSync) renderer->FrameSync = 0;
    for (int eye = 0; eye < XrMaxNumEyes; eye++) {
        fovx += fabs(renderer->Projections[eye].fov.angleDown - renderer->Projections[eye].fov.angleUp) / 2.0f;
        fovy += fabs(renderer->Projections[eye].fov.angleRight - renderer->Projections[eye].fov.angleLeft) / 2.0f;
        memcpy(&renderer->InvertedViewPose[eye][renderer->FrameSync], &renderer->Projections[eye].pose, sizeof(XrPosef));
    }
    // Ensure there is enough overlap for late reprojection
    renderer->FovScale = renderer->ConfigFloat[CONFIG_VIEWPORT_FOV_SCALE];
    if (renderer->FovScale > 0.1f) {
        fovx *= renderer->FovScale;
        fovy *= renderer->FovScale;
    }

    XrSpaceLocation loc = {};
    loc.type = XR_TYPE_SPACE_LOCATION;
    OXR(xrLocateSpace(engine->HeadSpace, engine->StageSpace, engine->PredictedDisplayTime, &loc));
    renderer->HmdStage = loc.pose;

    // Tell the game only once the poses include the recenter, or it offsets from stale ones
    if (renderer->RecenterEventPending && engine->PredictedDisplayTime >= renderer->RecenterChangeTime)
    {
        renderer->RecenterCount++;
        renderer->RecenterEventPending = false;
    }

    renderer->ConfigFloat[CONFIG_VIEWPORT_FOVX] = ToDegrees(fovx);
    renderer->ConfigFloat[CONFIG_VIEWPORT_FOVY] = ToDegrees(fovy);
    renderer->HmdOrientation = XrQuaternionfEulerAngles(renderer->InvertedViewPose[0][renderer->FrameSync].orientation);
    renderer->EdgeGlowRendered = false;
    renderer->FramebufferDrawn = false;
    renderer->OverlayRendered = false;
    renderer->LayerCount = 0;
    memset(renderer->Layers, 0, sizeof(XrCompositorLayer) * XrMaxLayerCount);
}

// The overlay is addressed as one index past the eye framebuffers
#define XrOverlayFbo XrMaxNumEyes

static struct XrFramebuffer* XrRendererGetFramebuffer(struct XrRenderer* renderer, int fbo_index)
{
    return fbo_index == XrOverlayFbo ? &renderer->Overlay : &renderer->Framebuffer[fbo_index];
}

void XrRendererBeginFrame(struct XrRenderer* renderer, int fbo_index)
{
    if (fbo_index >= 0) {
        if (renderer->ConfigInt[CONFIG_CURRENT_FBO] != fbo_index) {
            XrFramebufferAcquire(XrRendererGetFramebuffer(renderer, fbo_index));
        } else {
            XrFramebufferSetCurrent(XrRendererGetFramebuffer(renderer, fbo_index));
        }
    }
    renderer->ConfigInt[CONFIG_CURRENT_FBO] = fbo_index;
    if (fbo_index >= 0) renderer->FramebufferDrawn = true;
}

/*
 * Reduces the screen into the glow layer. This has to happen while the screen swapchain
 * image is still acquired, so it runs from XrRendererEndFrame rather than from
 * XrRendererFinishFrame where the rest of the layer work lives.
 */
static void XrRendererUpdateEdgeGlow(struct XrRenderer* renderer, int fbo_index)
{
    // Only the left eye is sampled: in AER mode the two eyes differ too little to be worth
    // a second reduction, and a full projection layer (VR mode) leaves nothing to glow past.
    if (fbo_index != 0) return;
    if (renderer->ConfigInt[CONFIG_VR]) return;

    int intensity = renderer->ConfigInt[CONFIG_EDGE_GLOW];
    if (intensity <= 0) return;
    if (!renderer->EdgeGlow.Initialized) return;

#if XR_USE_GRAPHICS_API_OPENGL_ES
    struct XrFramebuffer* framebuffer = &renderer->Framebuffer[fbo_index];
    if (!framebuffer->Acquired) return;

    // Matches the sub-rectangle XrRendererFinishFrame hands to the screen layer: the left
    // half of the swapchain under SBS, all of it otherwise.
    float scale_u = renderer->ConfigInt[CONFIG_SBS] ? 0.5f : 1.0f;
    // Skip the trimmed SBS eye edges, as the screen layer does
    float offset_u = scale_u * renderer->ConfigFloat[CONFIG_SBS_TRIM];
    scale_u -= 2.0f * offset_u;

    GLuint source = ((XrSwapchainImageOpenGLESKHR*)framebuffer->SwapchainImage)
                            [framebuffer->SwapchainIndex].image;
    XrEdgeGlowRender(&renderer->EdgeGlow, source, offset_u, 0.0f, scale_u, 1.0f,
                      (float)intensity / 100.0f);
    XrFramebufferSetCurrent(framebuffer);
    renderer->EdgeGlowRendered = true;
#endif
}

void XrRendererEndFrame(struct XrRenderer* renderer)
{
    int fbo_index = renderer->ConfigInt[CONFIG_CURRENT_FBO];
    if (fbo_index >= 0) {
        XrRendererUpdateEdgeGlow(renderer, fbo_index);
        XrFramebufferRelease(XrRendererGetFramebuffer(renderer, fbo_index));
    }
}

/*
 * Finishes the screen and switches drawing to the overlay, a full resolution layer both eyes
 * see. Under SBS a menu drawn into the screen gets only half of each eye's width. Created on
 * first use, so sessions that never open a menu in SBS never pay for it.
 */
bool XrRendererBeginOverlay(struct XrEngine* engine, struct XrRenderer* renderer)
{
    if (!renderer->OverlayCreated && !renderer->OverlayFailed) {
        renderer->OverlayCreated = XrFramebufferCreate(&renderer->Overlay, engine->Session,
                                                       renderer->Framebuffer[0].Width,
                                                       renderer->Framebuffer[0].Height);
        renderer->OverlayFailed = !renderer->OverlayCreated;
    }
    if (!renderer->OverlayCreated) return false;

    XrRendererEndFrame(renderer);
    XrRendererBeginFrame(renderer, XrOverlayFbo);
    renderer->OverlayRendered = renderer->Overlay.Acquired;
    return renderer->OverlayRendered;
}

void XrRendererFinishFrame(struct XrEngine* engine, struct XrRenderer* renderer)
{
    // A 360 panorama sits behind everything else. It is opaque, so passthrough wins where
    // both are on: seeing the room is the more specific request, and an opaque sphere over
    // it would only hide what the user asked to see. It is also pointless under a full
    // projection layer (VR mode), which already covers the whole view.
    bool environment = renderer->EnvironmentReady &&
                       renderer->ConfigInt[CONFIG_ENVIRONMENT] &&
                       !renderer->ConfigInt[CONFIG_VR] &&
                       !renderer->ConfigInt[CONFIG_PASSTHROUGH];

    if (engine->PlatformFlag[PLATFORM_EXTENSION_PASSTHROUGH] && renderer->ConfigInt[CONFIG_PASSTHROUGH]) {
        if (renderer->PassthroughLayer != XR_NULL_HANDLE) {
            XrCompositionLayerPassthroughFB passthrough_layer = {XR_TYPE_COMPOSITION_LAYER_PASSTHROUGH_FB};
            passthrough_layer.layerHandle = renderer->PassthroughLayer;
            passthrough_layer.flags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
            passthrough_layer.space = XR_NULL_HANDLE;
            renderer->Layers[renderer->LayerCount++].passthrough = passthrough_layer;
        }
    }

    XrFovf fov;
    float fovx = ToRadians(renderer->ConfigFloat[CONFIG_VIEWPORT_FOVX]);
    float fovy = ToRadians(renderer->ConfigFloat[CONFIG_VIEWPORT_FOVY]);
    fov.angleLeft = -fovx / 2.0f;
    fov.angleRight = fovx / 2.0f;
    fov.angleDown = -fovy / 2.0f;
    fov.angleUp = fovy / 2.0f;

    // Log the fov change
    static float lastFovX = 0;
    static float lastFovY = 0;
    if ((fabs(lastFovX - fovx) > 0.001f) || (fabs(lastFovY - fovy) > 0.001f)) {
        ALOGE("FoV changed! It is now %.2fx%.2f (scaled by %.1f)", ToDegrees(fovx), ToDegrees(fovy), renderer->FovScale);
        lastFovX = fovx;
        lastFovY = fovy;
    }

    int x = 0;
    int y = 0;
    int w = renderer->ConfigInt[CONFIG_VIEWPORT_WIDTH];
    int h = renderer->ConfigInt[CONFIG_VIEWPORT_HEIGHT];
    if (renderer->ConfigInt[CONFIG_SBS]) {
        w /= 2;
    }
    // Trims both edges of each SBS eye, where 3D shaders leave black strips; the screen narrows to match
    int trim = (int)(w * renderer->ConfigFloat[CONFIG_SBS_TRIM]);
    float keep = w > 0 ? (float)(w - 2 * trim) / (float)w : 1.0f;

    if (renderer->RecenterPending) {
        // Guard against uninitialized pose (first frame before xrLocateViews)
        XrQuaternionf orientation = renderer->Projections[0].pose.orientation;
        float qLenSq = orientation.x * orientation.x + orientation.y * orientation.y +
                       orientation.z * orientation.z + orientation.w * orientation.w;
        if (qLenSq > 0.5f) {
            renderer->ConfigFloat[CONFIG_MENU_PITCH] = renderer->HmdOrientation.x;
            renderer->ConfigFloat[CONFIG_MENU_YAW] = XrQuaternionfEulerAngles(orientation).y;
            renderer->RecenterPending = false;
        }
    }

    // Screen pose definition
    float radius = 1.0f;
    float size = renderer->ConfigFloat[CONFIG_CANVAS_SIZE];
    float distance = renderer->ConfigFloat[CONFIG_CANVAS_DISTANCE];
    float menu_pitch = ToRadians(renderer->ConfigFloat[CONFIG_MENU_PITCH]);
    float menu_yaw = ToRadians(renderer->ConfigFloat[CONFIG_MENU_YAW]);
    if (renderer->ConfigInt[CONFIG_VIEWPORT_CURVED]) {
        radius *= size;
        //approximately the same look like the flat screen
        if (distance < 5) {
            distance = 4 + distance / 5.0f;
        }
        distance -= radius;
        distance -= 3.0f;
    }

    // Screen pose calculation
    int frame = renderer->FrameSync;
    XrVector3f pitch_axis = {1, 0, 0};
    XrVector3f yaw_axis = {0, 1, 0};
    XrQuaternionf pitch = XrQuaternionfCreateFromVectorAngle(pitch_axis, -menu_pitch);
    XrQuaternionf yaw = XrQuaternionfCreateFromVectorAngle(yaw_axis, menu_yaw);
    XrQuaternionf rot = XrQuaternionfMultiply(pitch, yaw);
    XrVector3f pos = {renderer->InvertedViewPose[0][frame].position.x - sinf(menu_yaw) * cosf(menu_pitch) * distance,
                      renderer->InvertedViewPose[0][frame].position.y - sinf(menu_pitch) * distance,
                      renderer->InvertedViewPose[0][frame].position.z - cosf(menu_yaw) * cosf(menu_pitch) * distance};

    if (renderer->ConfigInt[CONFIG_IMMERSIVE]) {
        renderer->ConfigFloat[CONFIG_MENU_PITCH] = 0;
        renderer->ConfigFloat[CONFIG_MENU_YAW] = renderer->HmdOrientation.y;

        // Get orientation without roll
        XrVector3f roll_axis = {0, 0, 1};
        rot = renderer->InvertedViewPose[0][frame].orientation;
        float roll = ToRadians(XrQuaternionfEulerAngles(rot).z);
        XrQuaternionf invRoll = XrQuaternionfCreateFromVectorAngle(roll_axis, roll);
        rot = XrQuaternionfMultiply(rot, invRoll);

        // Move screen position forward
        float mat[16];
        XrQuaternionfToMatrix4f(&rot, mat);
        XrVector4f fwd = {0, 0, -distance, 0};
        fwd = XrVector4fMultiplyMatrix4f(mat, &fwd);
        pos.x = renderer->InvertedViewPose[0][frame].position.x + fwd.x;
        pos.y = renderer->InvertedViewPose[0][frame].position.y + fwd.y - 0.5f;
        pos.z = renderer->InvertedViewPose[0][frame].position.z + fwd.z;
    }


    // Ask the compositor to sharpen the layer, greatly improves readability.
    // Level: 0 = off, 1 = normal, 2 = quality (more expensive).
    int sharpening_level = renderer->ConfigInt[CONFIG_SHARPENING];
    XrCompositionLayerSettingsFB layer_settings = {};
    layer_settings.type = XR_TYPE_COMPOSITION_LAYER_SETTINGS_FB;
    layer_settings.layerFlags = (sharpening_level >= 2) ? XR_COMPOSITION_LAYER_SETTINGS_QUALITY_SHARPENING_BIT_FB
                                                        : XR_COMPOSITION_LAYER_SETTINGS_NORMAL_SHARPENING_BIT_FB;
    const void* layer_settings_chain = (engine->PlatformFlag[PLATFORM_EXTENSION_LAYER_SETTINGS] && sharpening_level > 0) ? &layer_settings : NULL;

    if (environment)
    {
        // radius 0 means an infinite sphere, so only the centre matters: pin it to the
        // head so the panorama always surrounds the viewer, but leave the orientation
        // in CurrentSpace so a recenter rotates it along with the screen.
        XrCompositionLayerEquirect2KHR equirect_layer = {};
        equirect_layer.type = XR_TYPE_COMPOSITION_LAYER_EQUIRECT2_KHR;
        equirect_layer.layerFlags = 0;
        equirect_layer.space = engine->CurrentSpace;
        equirect_layer.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
        memset(&equirect_layer.subImage, 0, sizeof(XrSwapchainSubImage));
        equirect_layer.subImage.swapchain = renderer->Environment.Handle;
        equirect_layer.subImage.imageRect.offset.x = 0;
        equirect_layer.subImage.imageRect.offset.y = 0;
        equirect_layer.subImage.imageRect.extent.width = renderer->Environment.Width;
        equirect_layer.subImage.imageRect.extent.height = renderer->Environment.Height;
        equirect_layer.subImage.imageArrayIndex = 0;
        equirect_layer.pose.orientation.w = 1.0f;
        equirect_layer.pose.position = renderer->InvertedViewPose[0][frame].position;
        equirect_layer.radius = 0.0f;
        equirect_layer.centralHorizontalAngle = (float)(2.0 * M_PI);
        equirect_layer.upperVerticalAngle = (float)(M_PI * 0.5);
        equirect_layer.lowerVerticalAngle = (float)(-M_PI * 0.5);
        renderer->Layers[renderer->LayerCount++].equirect = equirect_layer;
    }

    if (renderer->EdgeGlowRendered)
    {
        // The glow is coplanar with the screen and submitted first, so the screen simply
        // paints over the middle of it. Compositor layers are ordered, not depth tested,
        // which is why no separation offset is needed here.
        if (renderer->ConfigInt[CONFIG_VIEWPORT_CURVED])
        {
            XrCompositionLayerCylinderKHR glow_layer = {};
            glow_layer.type = XR_TYPE_COMPOSITION_LAYER_CYLINDER_KHR;
            glow_layer.layerFlags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
            glow_layer.space = engine->CurrentSpace;
            glow_layer.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
            memset(&glow_layer.subImage, 0, sizeof(XrSwapchainSubImage));
            glow_layer.subImage.swapchain = renderer->EdgeGlow.Framebuffer.Handle;
            glow_layer.subImage.imageRect.extent.width = XrEdgeGlowSize;
            glow_layer.subImage.imageRect.extent.height = XrEdgeGlowSize;
            glow_layer.subImage.imageArrayIndex = 0;
            glow_layer.pose.orientation = rot;
            glow_layer.pose.position = pos;
            glow_layer.radius = radius;
            glow_layer.centralAngle = (float)(M_PI * 0.5) * XrEdgeGlowSpread;
            glow_layer.aspectRatio = 1;
            if (renderer->ConfigInt[CONFIG_SBS] && renderer->ConfigInt[CONFIG_SBS_STRETCH])
            {
                // Match the half-width original SBS screen
                glow_layer.centralAngle = (float)(M_PI * 0.25) * XrEdgeGlowSpread;
                glow_layer.aspectRatio = size / 2.0f;
            }
            glow_layer.centralAngle *= keep;
            glow_layer.aspectRatio *= keep;
            renderer->Layers[renderer->LayerCount++].cylinder = glow_layer;
        }
        else
        {
            XrCompositionLayerQuad glow_layer = {};
            glow_layer.type = XR_TYPE_COMPOSITION_LAYER_QUAD;
            glow_layer.layerFlags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
            glow_layer.space = engine->CurrentSpace;
            glow_layer.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
            memset(&glow_layer.subImage, 0, sizeof(XrSwapchainSubImage));
            glow_layer.subImage.swapchain = renderer->EdgeGlow.Framebuffer.Handle;
            glow_layer.subImage.imageRect.extent.width = XrEdgeGlowSize;
            glow_layer.subImage.imageRect.extent.height = XrEdgeGlowSize;
            glow_layer.subImage.imageArrayIndex = 0;
            glow_layer.pose.orientation = rot;
            glow_layer.pose.position = pos;
            glow_layer.size.width = 4 * size * XrEdgeGlowSpread;
            glow_layer.size.height = 4 * size * XrEdgeGlowSpread;
            if (renderer->ConfigInt[CONFIG_SBS] && renderer->ConfigInt[CONFIG_SBS_STRETCH])
            {
                // Match the half-width original SBS screen
                glow_layer.size.width = 2 * size * XrEdgeGlowSpread;
                glow_layer.size.height = 4 * XrEdgeGlowSpread;
            }
            glow_layer.size.width *= keep;
            renderer->Layers[renderer->LayerCount++].quad = glow_layer;
        }
    }

    XrCompositionLayerProjectionView projection_layer_elements[2] = {};
    XrCompositionLayerProjectionView direct_views[2] = {};
    XrCompositionLayerProjection direct_layer;
    XrCompositionLayerQuad direct_quads[WXR_DIRECT_MAX_QUADS];
    int direct_quad_count = 0;
    struct XrFramebuffer* framebuffer = &renderer->Framebuffer[0];
    bool direct = renderer->ConfigInt[CONFIG_VR] &&
                  XrDirectBuildLayer(engine->Session, &direct_layer, direct_views, direct_quads, &direct_quad_count);
    if (direct)
    {
        // PC VR frames from the direct transport replace the preview window's, the game's quads on top
        if (direct_layer.viewCount) renderer->Layers[renderer->LayerCount++].projection = direct_layer;
        for (int i = 0; i < direct_quad_count; i++) renderer->Layers[renderer->LayerCount++].quad = direct_quads[i];
    }
    if (direct && !renderer->FramebufferDrawn)
    {
        // Nothing was drawn over the direct frames, so the screen swapchain is left out
        renderer->ConfigFloat[CONFIG_MENU_YAW] = renderer->HmdOrientation.y;
    }
    else if (renderer->ConfigInt[CONFIG_VR])
    {
        // Under direct frames this only carries the XR menu and FPS panel, laid over them
        renderer->ConfigFloat[CONFIG_MENU_YAW] = renderer->HmdOrientation.y;

        for (int eye = 0; eye < XrMaxNumEyes; eye++)
        {
            if (renderer->ConfigInt[CONFIG_AER]) {
                framebuffer = &renderer->Framebuffer[eye];
            }
            // The sync pixel comes from the game window, which is not drawn under direct frames
            if (renderer->ConfigInt[CONFIG_FRAMESYNC] && !direct)
            {
                static int framesync[2] = {};
                int targetFBO = renderer->ConfigInt[CONFIG_FRAMESYNC_B] > 0 ? 1 : 0;
                if ((renderer->ConfigInt[CONFIG_FRAMESYNC_G] < 1) && (renderer->ConfigInt[CONFIG_FRAMESYNC_A] > 0))
                {
                    framesync[targetFBO] = renderer->ConfigInt[CONFIG_FRAMESYNC_R];
                }
                frame = renderer->ConfigInt[CONFIG_AER] ? framesync[eye] : framesync[0];
            }

            XrPosef pose = renderer->InvertedViewPose[0][frame];
            if (renderer->ConfigInt[CONFIG_SBS] && (eye == 1))
            {
                x += w;
            }

            memset(&projection_layer_elements[eye], 0, sizeof(XrCompositionLayerProjectionView));
            projection_layer_elements[eye].type = XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW;
            projection_layer_elements[eye].pose = pose;
            projection_layer_elements[eye].fov = fov;

            memset(&projection_layer_elements[eye].subImage, 0, sizeof(XrSwapchainSubImage));
            projection_layer_elements[eye].subImage.swapchain = framebuffer->Handle;
            projection_layer_elements[eye].subImage.imageRect.offset.x = x;
            projection_layer_elements[eye].subImage.imageRect.offset.y = y;
            projection_layer_elements[eye].subImage.imageRect.extent.width = w;
            projection_layer_elements[eye].subImage.imageRect.extent.height = h;
            projection_layer_elements[eye].subImage.imageArrayIndex = 0;
        }

        XrCompositionLayerProjection projection_layer = {};
        projection_layer.type = XR_TYPE_COMPOSITION_LAYER_PROJECTION;
        projection_layer.next = layer_settings_chain;
        projection_layer.layerFlags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
        projection_layer.layerFlags |= XR_COMPOSITION_LAYER_CORRECT_CHROMATIC_ABERRATION_BIT;
        projection_layer.space = engine->CurrentSpace;
        projection_layer.viewCount = XrMaxNumEyes;
        projection_layer.views = projection_layer_elements;

        renderer->Layers[renderer->LayerCount++].projection = projection_layer;
    } else if (renderer->ConfigInt[CONFIG_VIEWPORT_CURVED]) {
        // Setup the cylinder layer
        XrCompositionLayerCylinderKHR cylinder_layer = {};
        cylinder_layer.type = XR_TYPE_COMPOSITION_LAYER_CYLINDER_KHR;
        cylinder_layer.next = layer_settings_chain;
        cylinder_layer.layerFlags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
        cylinder_layer.space = engine->CurrentSpace;
        memset(&cylinder_layer.subImage, 0, sizeof(XrSwapchainSubImage));
        cylinder_layer.subImage.imageRect.offset.x = x + trim;
        cylinder_layer.subImage.imageRect.offset.y = y;
        cylinder_layer.subImage.imageRect.extent.width = w - 2 * trim;
        cylinder_layer.subImage.imageRect.extent.height = h;
        cylinder_layer.subImage.swapchain = framebuffer->Handle;
        cylinder_layer.subImage.imageArrayIndex = 0;
        cylinder_layer.pose.orientation = rot;
        cylinder_layer.pose.position = pos;
        cylinder_layer.radius = radius;
        cylinder_layer.centralAngle = (float)(M_PI * 0.5);
        cylinder_layer.aspectRatio = 1;
        if (renderer->ConfigInt[CONFIG_SBS] && renderer->ConfigInt[CONFIG_SBS_STRETCH])
        {
            // Each eye is half the screen: show it at its own shape, full height of the 16:9 screen
            cylinder_layer.centralAngle = (float)(M_PI * 0.25);
            cylinder_layer.aspectRatio = size / 2.0f;
        }
        cylinder_layer.centralAngle *= keep;
        cylinder_layer.aspectRatio *= keep;

        // Build the layer
        if (renderer->ConfigInt[CONFIG_SBS])
        {
            cylinder_layer.eyeVisibility = XR_EYE_VISIBILITY_LEFT;
            renderer->Layers[renderer->LayerCount++].cylinder = cylinder_layer;
            cylinder_layer.eyeVisibility = XR_EYE_VISIBILITY_RIGHT;
            cylinder_layer.subImage.imageRect.offset.x = w + trim;
            renderer->Layers[renderer->LayerCount++].cylinder = cylinder_layer;
        }
        else
        {
            cylinder_layer.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
            renderer->Layers[renderer->LayerCount++].cylinder = cylinder_layer;
        }
    } else {
        // Setup quad layer
        XrCompositionLayerQuad quad_layer = {};
        quad_layer.type = XR_TYPE_COMPOSITION_LAYER_QUAD;
        quad_layer.next = layer_settings_chain;
        quad_layer.layerFlags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
        quad_layer.space = engine->CurrentSpace;
        memset(&quad_layer.subImage, 0, sizeof(XrSwapchainSubImage));
        quad_layer.subImage.imageRect.offset.x = x + trim;
        quad_layer.subImage.imageRect.offset.y = y;
        quad_layer.subImage.imageRect.extent.width = w - 2 * trim;
        quad_layer.subImage.imageRect.extent.height = h;
        quad_layer.subImage.swapchain = framebuffer->Handle;
        quad_layer.subImage.imageArrayIndex = 0;
        quad_layer.pose.orientation = rot;
        quad_layer.pose.position = pos;
        quad_layer.size.width = 4 * size;
        quad_layer.size.height = 4 * size;
        if (renderer->ConfigInt[CONFIG_SBS] && renderer->ConfigInt[CONFIG_SBS_STRETCH])
        {
            // Each eye is half the screen: show it at its own shape, full height of the 16:9 screen
            quad_layer.size.width = 2 * size;
            quad_layer.size.height = 4;
        }
        quad_layer.size.width *= keep;

        // Build the layer
        if (renderer->ConfigInt[CONFIG_SBS])
        {
            quad_layer.eyeVisibility = XR_EYE_VISIBILITY_LEFT;
            renderer->Layers[renderer->LayerCount++].quad = quad_layer;
            quad_layer.eyeVisibility = XR_EYE_VISIBILITY_RIGHT;
            quad_layer.subImage.imageRect.offset.x = w + trim;
            renderer->Layers[renderer->LayerCount++].quad = quad_layer;
        }
        else
        {
            quad_layer.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
            renderer->Layers[renderer->LayerCount++].quad = quad_layer;
        }
    }

    if (renderer->OverlayRendered)
    {
        // Laid out like the non-SBS screen, so menus look the same with SBS on or off
        struct XrFramebuffer* overlay = &renderer->Overlay;
        if (renderer->ConfigInt[CONFIG_VIEWPORT_CURVED])
        {
            XrCompositionLayerCylinderKHR overlay_layer = {};
            overlay_layer.type = XR_TYPE_COMPOSITION_LAYER_CYLINDER_KHR;
            overlay_layer.next = layer_settings_chain;
            overlay_layer.layerFlags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
            overlay_layer.space = engine->CurrentSpace;
            overlay_layer.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
            memset(&overlay_layer.subImage, 0, sizeof(XrSwapchainSubImage));
            overlay_layer.subImage.swapchain = overlay->Handle;
            overlay_layer.subImage.imageRect.extent.width = overlay->Width;
            overlay_layer.subImage.imageRect.extent.height = overlay->Height;
            overlay_layer.subImage.imageArrayIndex = 0;
            overlay_layer.pose.orientation = rot;
            overlay_layer.pose.position = pos;
            overlay_layer.radius = radius;
            overlay_layer.centralAngle = (float)(M_PI * 0.5);
            overlay_layer.aspectRatio = 1;
            renderer->Layers[renderer->LayerCount++].cylinder = overlay_layer;
        }
        else
        {
            XrCompositionLayerQuad overlay_layer = {};
            overlay_layer.type = XR_TYPE_COMPOSITION_LAYER_QUAD;
            overlay_layer.next = layer_settings_chain;
            overlay_layer.layerFlags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
            overlay_layer.space = engine->CurrentSpace;
            overlay_layer.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
            memset(&overlay_layer.subImage, 0, sizeof(XrSwapchainSubImage));
            overlay_layer.subImage.swapchain = overlay->Handle;
            overlay_layer.subImage.imageRect.extent.width = overlay->Width;
            overlay_layer.subImage.imageRect.extent.height = overlay->Height;
            overlay_layer.subImage.imageArrayIndex = 0;
            overlay_layer.pose.orientation = rot;
            overlay_layer.pose.position = pos;
            overlay_layer.size.width = 4 * size;
            overlay_layer.size.height = 4 * size;
            renderer->Layers[renderer->LayerCount++].quad = overlay_layer;
        }
    }

    // Compose the layers for this frame.
    const XrCompositionLayerBaseHeader* layers[XrMaxLayerCount] = {};
    for (int i = 0; i < renderer->LayerCount; i++)
    {
        layers[i] = (const XrCompositionLayerBaseHeader*)&renderer->Layers[i];
    }

    XrFrameEndInfo end_frame_info = {};
    end_frame_info.type = XR_TYPE_FRAME_END_INFO;
    end_frame_info.displayTime = engine->PredictedDisplayTime;
    end_frame_info.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    end_frame_info.layerCount = renderer->LayerCount;
    end_frame_info.layers = layers;
    OXR(xrEndFrame(engine->Session, &end_frame_info));

    for (int eye = 0; eye < XrMaxNumEyes; eye++) {
        struct XrFramebuffer* frameBuffer = &renderer->Framebuffer[eye];
        frameBuffer->SwapchainIndex++;
        frameBuffer->SwapchainIndex %= frameBuffer->SwapchainLength;
    }
}

void XrRendererBindFramebuffer(struct XrRenderer* renderer)
{
    if (!renderer->Initialized)
        return;
    int fbo_index = renderer->ConfigInt[CONFIG_CURRENT_FBO];
    if (fbo_index >= 0) {
        XrFramebufferSetCurrent(XrRendererGetFramebuffer(renderer, fbo_index));
    }
}


bool XrRendererSetEnvironment(struct XrEngine* engine, struct XrRenderer* renderer,
                              const void* rgba, int width, int height)
{
    if (!engine->PlatformFlag[PLATFORM_EXTENSION_EQUIRECT] || (rgba == NULL))
    {
        return false;
    }

    // The panorama never changes once uploaded, so it lives in its own swapchain that
    // is acquired and released exactly once. The compositor keeps sampling the last
    // released image, which is what lets the layer cost us nothing per frame.
    if (renderer->EnvironmentCreated &&
        ((renderer->Environment.Width != width) || (renderer->Environment.Height != height)))
    {
        XrFramebufferDestroy(&renderer->Environment);
        renderer->EnvironmentCreated = false;
        renderer->EnvironmentReady = false;
    }

    if (!renderer->EnvironmentCreated)
    {
        if (!XrFramebufferCreate(&renderer->Environment, engine->Session, width, height))
        {
            ALOGE("Failed to create the %dx%d environment swapchain", width, height);
            return false;
        }
        renderer->EnvironmentCreated = true;
    }

#if XR_USE_GRAPHICS_API_OPENGL_ES
    // Acquiring rebinds the framebuffer and rewrites the viewport, so put back whatever the
    // caller had: this runs mid-frame, before the screen framebuffer has been acquired.
    GLint previous_framebuffer = 0;
    GLint previous_viewport[4] = {};
    GL(glGetIntegerv(GL_FRAMEBUFFER_BINDING, &previous_framebuffer));
    GL(glGetIntegerv(GL_VIEWPORT, previous_viewport));

    XrFramebufferAcquire(&renderer->Environment);
    GLuint texture = ((XrSwapchainImageOpenGLESKHR*)renderer->Environment.SwapchainImage)
                             [renderer->Environment.SwapchainIndex].image;
    GL(glBindTexture(GL_TEXTURE_2D, texture));
    GL(glPixelStorei(GL_UNPACK_ALIGNMENT, 4));
    GL(glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, rgba));
    GL(glBindTexture(GL_TEXTURE_2D, 0));
    XrFramebufferRelease(&renderer->Environment);

    GL(glBindFramebuffer(GL_FRAMEBUFFER, previous_framebuffer));
    GL(glViewport(previous_viewport[0], previous_viewport[1], previous_viewport[2], previous_viewport[3]));
#endif


    renderer->EnvironmentReady = true;
    ALOGV("Uploaded a %dx%d environment panorama", width, height);
    return true;
}

void XrRendererClearEnvironment(struct XrRenderer* renderer)
{
    renderer->EnvironmentReady = false;
    if (renderer->EnvironmentCreated)
    {
        XrFramebufferDestroy(&renderer->Environment);
        renderer->EnvironmentCreated = false;
    }
}

void XrRendererRecenter(struct XrEngine* engine, struct XrRenderer* renderer)
{
    // Calculate recenter reference
    XrReferenceSpaceCreateInfo space_info = {};
    space_info.type = XR_TYPE_REFERENCE_SPACE_CREATE_INFO;
    space_info.poseInReferenceSpace.orientation.w = 1.0f;
    if (engine->CurrentSpace != XR_NULL_HANDLE)
    {
        XrSpaceLocation loc = {};
        loc.type = XR_TYPE_SPACE_LOCATION;
        OXR(xrLocateSpace(engine->HeadSpace, engine->CurrentSpace,
                          engine->PredictedDisplayTime, &loc));
        renderer->HmdOrientation = XrQuaternionfEulerAngles(loc.pose.orientation);

        renderer->ConfigFloat[CONFIG_RECENTER_YAW] += renderer->HmdOrientation.y;
        float renceter_yaw = ToRadians(renderer->ConfigFloat[CONFIG_RECENTER_YAW]);
        space_info.poseInReferenceSpace.orientation.x = 0;
        space_info.poseInReferenceSpace.orientation.y = sinf(renceter_yaw / 2);
        space_info.poseInReferenceSpace.orientation.z = 0;
        space_info.poseInReferenceSpace.orientation.w = cosf(renceter_yaw / 2);
    }

    // Delete previous space instances
    if (engine->StageSpace != XR_NULL_HANDLE)
    {
        OXR(xrDestroySpace(engine->StageSpace));
    }
    if (engine->FakeSpace != XR_NULL_HANDLE)
    {
        OXR(xrDestroySpace(engine->FakeSpace));
    }

    // Create a default stage space to use if SPACE_TYPE_STAGE is not
    // supported, or calls to xrGetReferenceSpaceBoundsRect fail.
    space_info.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
    memset(&space_info.poseInReferenceSpace, 0, sizeof(XrPosef));
    space_info.poseInReferenceSpace.orientation.w = 1.0f;
    OXR(xrCreateReferenceSpace(engine->Session, &space_info, &engine->FakeSpace));
    ALOGV("Created fake stage space from local space with offset");
    engine->CurrentSpace = engine->FakeSpace;

    if (renderer->StageSupported)
    {
        space_info.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_STAGE;
        memset(&space_info.poseInReferenceSpace, 0, sizeof(XrPosef));
        space_info.poseInReferenceSpace.orientation.w = 1.0;
        OXR(xrCreateReferenceSpace(engine->Session, &space_info, &engine->StageSpace));
        ALOGV("Created stage space");
    }

    // Update menu orientation on next frame with valid head tracking
    renderer->RecenterPending = true;
}

void XrRendererHandleSessionStateChanges(struct XrEngine* engine, struct XrRenderer* renderer, XrSessionState state)
{
    if (state == XR_SESSION_STATE_READY)
    {
        assert(renderer->SessionActive == false);

        XrSessionBeginInfo session_begin_info;
        memset(&session_begin_info, 0, sizeof(session_begin_info));
        session_begin_info.type = XR_TYPE_SESSION_BEGIN_INFO;
        session_begin_info.next = NULL;
        session_begin_info.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;

        XrResult result;
        OXR(result = xrBeginSession(engine->Session, &session_begin_info));
        renderer->SessionActive = (result == XR_SUCCESS);
        ALOGV("Session active = %d", renderer->SessionActive);

        if (renderer->SessionActive && engine->PlatformFlag[PLATFORM_EXTENSION_REFRESHRATE])
        {
            int refresh = renderer->ConfigInt[CONFIG_FRAMERATE];
            if (!pfnRequestDisplayRefreshRate)
            {
                OXR(xrGetInstanceProcAddr(
                        engine->Instance,
                        "xrRequestDisplayRefreshRateFB",
                        (PFN_xrVoidFunction*)(&pfnRequestDisplayRefreshRate)));
            }
            OXR(pfnRequestDisplayRefreshRate(engine->Session, 72.0f));
            OXR(pfnRequestDisplayRefreshRate(engine->Session, (float)refresh));
        }
#ifdef ANDROID
        if (renderer->SessionActive && engine->PlatformFlag[PLATFORM_EXTENSION_PERFORMANCE])
        {
            PFN_xrPerfSettingsSetPerformanceLevelEXT pfnPerfSettingsSetPerformanceLevelEXT = NULL;
            OXR(xrGetInstanceProcAddr(engine->Instance, "xrPerfSettingsSetPerformanceLevelEXT",
                                      (PFN_xrVoidFunction*)(&pfnPerfSettingsSetPerformanceLevelEXT)));

            int cpuLevel = renderer->ConfigInt[CONFIG_LEVEL_CPU];
            int gpuLevel = renderer->ConfigInt[CONFIG_LEVEL_GPU];
            OXR(pfnPerfSettingsSetPerformanceLevelEXT(engine->Session, XR_PERF_SETTINGS_DOMAIN_CPU_EXT, cpuLevel));
            OXR(pfnPerfSettingsSetPerformanceLevelEXT(engine->Session, XR_PERF_SETTINGS_DOMAIN_GPU_EXT, gpuLevel));

            PFN_xrSetAndroidApplicationThreadKHR pfnSetAndroidApplicationThreadKHR = NULL;
            OXR(xrGetInstanceProcAddr(engine->Instance, "xrSetAndroidApplicationThreadKHR",
                                      (PFN_xrVoidFunction*)(&pfnSetAndroidApplicationThreadKHR)));

            OXR(pfnSetAndroidApplicationThreadKHR(engine->Session,
                                                  XR_ANDROID_THREAD_TYPE_APPLICATION_MAIN_KHR,
                                                  engine->MainThreadId));
            OXR(pfnSetAndroidApplicationThreadKHR(engine->Session,
                                                  XR_ANDROID_THREAD_TYPE_RENDERER_MAIN_KHR,
                                                  engine->RenderThreadId));
        }
#endif
    }
    else if (state == XR_SESSION_STATE_STOPPING)
    {
        assert(renderer->SessionActive);

        OXR(xrEndSession(engine->Session));
        renderer->SessionActive = false;
    }
}

void XrRendererHandleXrEvents(struct XrEngine* engine, struct XrRenderer* renderer)
{
    XrEventDataBuffer event_data_bufer = {};

    // Poll for events
    for (;;)
    {
        XrEventDataBaseHeader* base_event_handler = (XrEventDataBaseHeader*)(&event_data_bufer);
        base_event_handler->type = XR_TYPE_EVENT_DATA_BUFFER;
        base_event_handler->next = NULL;
        XrResult r;
        OXR(r = xrPollEvent(engine->Instance, &event_data_bufer));
        if (r != XR_SUCCESS)
        {
            break;
        }

        switch (base_event_handler->type)
        {
            case XR_TYPE_EVENT_DATA_EVENTS_LOST:
                ALOGV("xrPollEvent: received XR_TYPE_EVENT_DATA_EVENTS_LOST");
                break;
            case XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING:
            {
                const XrEventDataInstanceLossPending* instance_loss_pending_event =
                        (XrEventDataInstanceLossPending*)(base_event_handler);
                ALOGV("xrPollEvent: received XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING: time %lf",
                      FromXrTime(instance_loss_pending_event->lossTime));
            }
                break;
            case XR_TYPE_EVENT_DATA_INTERACTION_PROFILE_CHANGED:
                ALOGV("xrPollEvent: received XR_TYPE_EVENT_DATA_INTERACTION_PROFILE_CHANGED");
                break;
            case XR_TYPE_EVENT_DATA_PERF_SETTINGS_EXT:
                break;
            case XR_TYPE_EVENT_DATA_REFERENCE_SPACE_CHANGE_PENDING:
                XrRendererRecenter(engine, renderer);
                renderer->RecenterChangeTime = ((XrEventDataReferenceSpaceChangePending*)base_event_handler)->changeTime;
                renderer->RecenterEventPending = true;
                break;
            case XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED:
            {
                const XrEventDataSessionStateChanged* session_state_changed_event =
                        (XrEventDataSessionStateChanged*)(base_event_handler);
                switch (session_state_changed_event->state)
                {
                    case XR_SESSION_STATE_FOCUSED:
                        renderer->SessionFocused = true;
                        break;
                    case XR_SESSION_STATE_VISIBLE:
                        renderer->SessionFocused = false;
                        break;
                    case XR_SESSION_STATE_READY:
                    case XR_SESSION_STATE_STOPPING:
                        XrRendererHandleSessionStateChanges(engine, renderer, session_state_changed_event->state);
                        break;
                    default:
                        break;
                }
                break;
            }
            default:
                ALOGV("xrPollEvent: Unknown event");
                break;
        }
    }
}
