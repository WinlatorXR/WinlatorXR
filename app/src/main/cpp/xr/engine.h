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

#include "openxr.h"

#if defined(_DEBUG)
void GLCheckErrors(const char* file, int line);
void OXRCheckErrors(XrResult result, const char* file, int line);

#define GL(func) func; GLCheckErrors(__FILE__ , __LINE__);
#define OXR(func) OXRCheckErrors(func, __FILE__ , __LINE__);
#else
#define GL(func) func;
#define OXR(func) func;
#endif

enum
{
    XrFrameSyncStep = 12
};
enum
{
    XrMaxFrameSync = 256
};
enum
{
  XrMaxLayerCount = 5
};
enum
{
  XrMaxNumEyes = 2
};

enum XrPlatformFlag
{
  PLATFORM_CONTROLLER_PICO,
  PLATFORM_CONTROLLER_QUEST,
  PLATFORM_EXTENSION_INSTANCE,
  PLATFORM_EXTENSION_PASSTHROUGH,
  PLATFORM_EXTENSION_PERFORMANCE,
  PLATFORM_EXTENSION_REFRESHRATE,
  PLATFORM_EXTENSION_LAYER_SETTINGS,
  PLATFORM_EXTENSION_EQUIRECT,
  PLATFORM_MAX
};

typedef union
{
  XrCompositionLayerProjection projection;
  XrCompositionLayerQuad quad;
  XrCompositionLayerCylinderKHR cylinder;
  XrCompositionLayerEquirect2KHR equirect;
  XrCompositionLayerPassthroughFB passthrough;
} XrCompositorLayer;

#ifdef ANDROID
typedef struct
{
  jobject activity;
  JNIEnv* env;
  JavaVM* vm;
} xrJava;
#endif

struct XrEngine {
    XrInstance Instance;
    XrSession Session;
    XrSystemId SystemId;

    XrSpace CurrentSpace;
    XrSpace FakeSpace;
    XrSpace HeadSpace;
    XrSpace StageSpace;

    XrTime PredictedDisplayTime;

    int MainThreadId;
    int RenderThreadId;

    bool PlatformFlag[PLATFORM_MAX];
    bool Initialized;
};

void XrEngineInit(struct XrEngine* engine, void* system, const char* name, int version);
void XrEngineDestroy(struct XrEngine* engine);
void XrEngineEnter(struct XrEngine* engine);
void XrEngineLeave(struct XrEngine* engine);
void XrEngineWaitForFrame(struct XrEngine* engine);
