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

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <vector>

#include "openxr.h"

std::vector<std::pair<int, int> > xr_locate_spaces;
std::map<std::pair<int, int>, XrPosef> xr_poses;
std::map<int, XrReferenceSpaceCreateInfo> xr_info;
std::map<int, XrSpace> xr_spaces;
bool xr_initialized = false;
bool xr_curvedScreen = false;
bool xr_usePassthrough = false;
int xr_sharpening = 0;
bool xr_vr = false;
float xr_aspect = 0;
float xr_fovx = 0;
float xr_fovy = 0;

char gManufacturer[128] = {0};

extern "C" {

#include "engine.h"
#include "input.h"
#include "math.h"
#include "renderer.h"

struct XrEngine xr_module_engine;
struct XrInput xr_module_input;
struct XrRenderer xr_module_renderer;

#if defined(_DEBUG)
#include <GLES2/gl2.h>
void GLCheckErrors(const char* file, int line) {
    for (int i = 0; i < 10; i++) {
        const GLenum error = glGetError();
        if (error == GL_NO_ERROR) {
            break;
        }
        ALOGE("OpenGL error on line %s:%d %d", file, line, error);
    }
}

void OXRCheckErrors(XrResult result, const char* file, int line) {
    if (XR_FAILED(result)) {
        char errorBuffer[XR_MAX_RESULT_STRING_SIZE];
        xrResultToString(xr_module_engine.Instance, result, errorBuffer);
        ALOGE("OpenXR error on line %s:%d %s", file, line, errorBuffer);
    }
}
#endif

void updatePoses() {
    if (xr_locate_spaces.empty()) {
        return;
    }

    xr_poses.clear();
    for (auto& space : xr_locate_spaces) {
        bool hasFirst = xr_spaces.find(space.first) != xr_spaces.end();
        bool hasSecond = xr_spaces.find(space.second) != xr_spaces.end();

        if ((space.first == 0) && hasSecond) {
            XrViewLocateInfo projection_info = {};
            projection_info.type = XR_TYPE_VIEW_LOCATE_INFO;
            projection_info.next = NULL;
            projection_info.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
            projection_info.displayTime = xr_module_engine.PredictedDisplayTime;
            projection_info.space = xr_spaces[space.second];

            XrView projections[XrMaxNumEyes] = {};
            for (auto & projection : projections) {
                projection.type = XR_TYPE_VIEW;
            }
            uint32_t projection_capacity = XrMaxNumEyes;
            uint32_t projection_count = projection_capacity;
            XrViewState view_state = {XR_TYPE_VIEW_STATE, NULL};
            OXR(xrLocateViews(xr_module_engine.Session, &projection_info, &view_state,
                              projection_capacity, &projection_count, projections));

            XrPosef pose;
            pose.orientation = projections[0].pose.orientation;
            pose.position.x = (projections[0].pose.position.x + projections[1].pose.position.x) * 0.5f;
            pose.position.y = (projections[0].pose.position.y + projections[1].pose.position.y) * 0.5f;
            pose.position.z = (projections[0].pose.position.z + projections[1].pose.position.z) * 0.5f;
            xr_poses[space] = pose;
        } else if (hasFirst && hasSecond) {
            XrSpaceLocation loc = {};
            loc.type = XR_TYPE_SPACE_LOCATION;
            OXR(xrLocateSpace(xr_spaces[space.first], xr_spaces[space.second],
                              xr_module_engine.PredictedDisplayTime, &loc));
            xr_poses[space] = loc.pose;
        }
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_xr_XrActivity_sendManufacturer(JNIEnv *env, jobject thiz, jstring manufacturer) {
    const char *nativeStr = env->GetStringUTFChars(manufacturer, 0);
    strncpy(gManufacturer, nativeStr, sizeof(gManufacturer) - 1);
    gManufacturer[sizeof(gManufacturer) - 1] = '\0';
    env->ReleaseStringUTFChars(manufacturer, nativeStr);
}

JNIEXPORT void JNICALL
Java_com_winlator_xr_XrActivity_init(JNIEnv *env, jobject obj, jint width, jint height,
                                     jint refresh, jint cpu, jint gpu) {

    // Do not allow second initialization
    if (xr_initialized) {
        return;
    }
    if (strcmp(gManufacturer, "PICO") == 0) {
        memset(&xr_module_engine, 0, sizeof(xr_module_engine));
        xr_module_engine.PlatformFlag[PLATFORM_CONTROLLER_PICO] = true;
        xr_module_engine.PlatformFlag[PLATFORM_EXTENSION_INSTANCE] = true;
        xr_module_engine.PlatformFlag[PLATFORM_EXTENSION_PASSTHROUGH] = true;
        xr_module_engine.PlatformFlag[PLATFORM_EXTENSION_PERFORMANCE] = true;
        xr_module_engine.PlatformFlag[PLATFORM_EXTENSION_REFRESHRATE] = true;
    } else if (strcmp(gManufacturer, "PLAY FOR DREAM") == 0) {
        memset(&xr_module_engine, 0, sizeof(xr_module_engine));
        xr_module_engine.PlatformFlag[PLATFORM_CONTROLLER_QUEST] = true;
        xr_module_engine.PlatformFlag[PLATFORM_EXTENSION_INSTANCE] = true;
        xr_module_engine.PlatformFlag[PLATFORM_EXTENSION_PASSTHROUGH] = true;
        xr_module_engine.PlatformFlag[PLATFORM_EXTENSION_PERFORMANCE] = true;
    } else {
        memset(&xr_module_engine, 0, sizeof(xr_module_engine));
        xr_module_engine.PlatformFlag[PLATFORM_CONTROLLER_QUEST] = true;
        xr_module_engine.PlatformFlag[PLATFORM_EXTENSION_PASSTHROUGH] = true;
        xr_module_engine.PlatformFlag[PLATFORM_EXTENSION_PERFORMANCE] = true;
        xr_module_engine.PlatformFlag[PLATFORM_EXTENSION_REFRESHRATE] = true;
    }
    xr_module_renderer.ConfigInt[CONFIG_LEVEL_CPU] = cpu;
    xr_module_renderer.ConfigInt[CONFIG_LEVEL_GPU] = gpu;
    xr_module_renderer.ConfigInt[CONFIG_FRAMERATE] = refresh;
    xr_module_renderer.ConfigInt[CONFIG_VIEWPORT_WIDTH] = width;
    xr_module_renderer.ConfigInt[CONFIG_VIEWPORT_HEIGHT] = width; //Use square resolution
    xr_aspect = (float) width / (float) height;

    // Get Java VM
    JavaVM *vm;
    env->GetJavaVM(&vm);

    // Init XR
    xrJava java;
    java.vm = vm;
    java.activity = env->NewGlobalRef(obj);
    XrEngineInit(&xr_module_engine, &java, "Winlator", 1);

    // Enter XR
    XrEngineEnter(&xr_module_engine);
    XrInputInit(&xr_module_engine, &xr_module_input);
    XrRendererInit(&xr_module_engine, &xr_module_renderer);
    xr_initialized = true;
    ALOGV("Init called");
}

JNIEXPORT void JNICALL Java_com_winlator_xr_XrActivity_bindFramebuffer(JNIEnv *env, jobject obj) {
    if (xr_initialized) {
        XrRendererBindFramebuffer(&xr_module_renderer);
    }
}

JNIEXPORT jint JNICALL Java_com_winlator_xr_XrActivity_getWidth(JNIEnv *env, jobject obj) {
    return xr_module_renderer.ConfigInt[CONFIG_VIEWPORT_WIDTH];
}
JNIEXPORT jint JNICALL Java_com_winlator_xr_XrActivity_getHeight(JNIEnv *env, jobject obj) {
    return xr_module_renderer.ConfigInt[CONFIG_VIEWPORT_HEIGHT];
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_xr_XrActivity_initFrame(JNIEnv *env, jobject obj, jboolean immersive,
                                          jboolean sbs, jboolean aer, jfloat distance) {
    if (XrRendererInitFrame(&xr_module_engine, &xr_module_renderer)) {
        // Update controllers state
        XrInputUpdate(&xr_module_engine, &xr_module_input);

        // Get poses for XrAPI
        updatePoses();

        // All spaces are located, we can lock the frame
        XrRendererLockFrame(&xr_module_engine, &xr_module_renderer);

        // Set render canvas
        xr_module_renderer.ConfigInt[CONFIG_VIEWPORT_CURVED] = !immersive && xr_curvedScreen;
        xr_module_renderer.ConfigInt[CONFIG_SHARPENING] = xr_sharpening;
        xr_module_renderer.ConfigFloat[CONFIG_CANVAS_DISTANCE] = distance;
        xr_module_renderer.ConfigFloat[CONFIG_CANVAS_SIZE] = xr_aspect;
        xr_module_renderer.ConfigFloat[CONFIG_VIEWPORT_FOV_SCALE] = 1.1f;
        if (xr_fovx > 1) xr_module_renderer.ConfigFloat[CONFIG_VIEWPORT_FOVX] = xr_fovx;
        if (xr_fovy > 1) xr_module_renderer.ConfigFloat[CONFIG_VIEWPORT_FOVY] = xr_fovy;
        xr_module_renderer.ConfigInt[CONFIG_PASSTHROUGH] =
                !immersive && !xr_vr && xr_usePassthrough;
        xr_module_renderer.ConfigInt[CONFIG_IMMERSIVE] = immersive && !xr_vr;
        xr_module_renderer.ConfigInt[CONFIG_FRAMESYNC] = xr_vr;
        xr_module_renderer.ConfigInt[CONFIG_AER] = aer;
        xr_module_renderer.ConfigInt[CONFIG_SBS] = sbs;
        xr_module_renderer.ConfigInt[CONFIG_VR] = xr_vr;

        // Recenter on the first frame
        static bool first_frame = true;
        if (first_frame) {
            XrRendererRecenter(&xr_module_engine, &xr_module_renderer);
            first_frame = false;
        }

        // Reset framebuffer
        XrRendererBeginFrame(&xr_module_renderer, -1);

        return true;
    }
    return false;
}

JNIEXPORT void JNICALL
Java_com_winlator_xr_XrActivity_bindFBO(JNIEnv *env, jobject obj, jint fboIndex) {
    XrRendererEndFrame(&xr_module_renderer);
    XrRendererBeginFrame(&xr_module_renderer, fboIndex);
}

JNIEXPORT void JNICALL Java_com_winlator_xr_XrActivity_endFrame(JNIEnv *env, jobject obj) {
    XrRendererEndFrame(&xr_module_renderer);
    XrRendererFinishFrame(&xr_module_engine, &xr_module_renderer);
}

JNIEXPORT jfloatArray JNICALL Java_com_winlator_xr_XrActivity_getAxes(JNIEnv *env, jobject obj) {
    XrPosef lPose = XrInputGetPose(&xr_module_input, 0);
    XrPosef rPose = XrInputGetPose(&xr_module_input, 1);
    XrPosef lgPose = XrInputGetPose(&xr_module_input, 2);
    XrPosef rgPose = XrInputGetPose(&xr_module_input, 3);
    XrVector2f lThumbstick = XrInputGetJoystickState(&xr_module_input, 0);
    XrVector2f rThumbstick = XrInputGetJoystickState(&xr_module_input, 1);
    XrQuaternionf quat = xr_module_renderer.Projections[0].pose.orientation;
    XrVector3f lPosition = xr_module_renderer.Projections[0].pose.position;
    XrVector3f rPosition = xr_module_renderer.Projections[1].pose.position;
    XrVector3f angles = xr_module_renderer.HmdOrientation;
    float yaw = xr_module_renderer.ConfigFloat[CONFIG_MENU_YAW];

    int count = 0;
    float data[64];
    data[count++] = XrQuaternionfEulerAngles(lPose.orientation).x; //L_PITCH
    data[count++] = XrQuaternionfEulerAngles(lPose.orientation).y; //L_YAW
    data[count++] = XrQuaternionfEulerAngles(lPose.orientation).z; //L_ROLL
    data[count++] = lPose.orientation.x; //L_QX
    data[count++] = lPose.orientation.y; //L_QY
    data[count++] = lPose.orientation.z; //L_QZ
    data[count++] = lPose.orientation.w; //L_QW
    data[count++] = lThumbstick.x; //L_THUMBSTICK_X
    data[count++] = lThumbstick.y; //L_THUMBSTICK_Y
    data[count++] = lPose.position.x; //L_X
    data[count++] = lPose.position.y; //L_Y
    data[count++] = lPose.position.z; //L_Z
    data[count++] = XrQuaternionfEulerAngles(rPose.orientation).x; //R_PITCH
    data[count++] = XrQuaternionfEulerAngles(rPose.orientation).y; //R_YAW
    data[count++] = XrQuaternionfEulerAngles(rPose.orientation).z; //R_ROLL
    data[count++] = rPose.orientation.x; //R_QX
    data[count++] = rPose.orientation.y; //R_QY
    data[count++] = rPose.orientation.z; //R_QZ
    data[count++] = rPose.orientation.w; //R_QW
    data[count++] = rThumbstick.x; //R_THUMBSTICK_X
    data[count++] = rThumbstick.y; //R_THUMBSTICK_Y
    data[count++] = rPose.position.x; //R_X
    data[count++] = rPose.position.y; //R_Y
    data[count++] = rPose.position.z; //R_Z
    data[count++] = angles.x; //HMD_PITCH
    data[count++] = angles.y; //HMD_YAW
    data[count++] = angles.z; //HMD_ROLL
    data[count++] = quat.x; //HMD_QX
    data[count++] = quat.y; //HMD_QY
    data[count++] = quat.z; //HMD_QZ
    data[count++] = quat.w; //HMD_QW
    data[count++] = (lPosition.x + rPosition.x) * 0.5f; //HMD_X
    data[count++] = (lPosition.y + rPosition.y) * 0.5f; //HMD_Y
    data[count++] = (lPosition.z + rPosition.z) * 0.5f; //HMD_Z
    data[count++] = XrVector3fDistance(lPosition, rPosition); //HMD_IPD
    data[count++] = xr_module_renderer.ConfigFloat[CONFIG_VIEWPORT_FOVX]; //HMD_FOVX
    data[count++] = xr_module_renderer.ConfigFloat[CONFIG_VIEWPORT_FOVY]; //HMD_FOVY
    data[count++] = xr_module_renderer.FrameSync; //HMD_SYNC
    data[count++] = xr_module_renderer.RecenterCount; //HMD_RECENTER
    data[count++] = xr_module_renderer.HmdStage.position.y; //HMD_ALTITUDE
    data[count++] = lgPose.orientation.x; //LG_QX
    data[count++] = lgPose.orientation.y; //LG_QY
    data[count++] = lgPose.orientation.z; //LG_QZ
    data[count++] = lgPose.orientation.w; //LG_QW
    data[count++] = rgPose.orientation.x; //RG_QX
    data[count++] = rgPose.orientation.y; //RG_QY
    data[count++] = rgPose.orientation.z; //RG_QZ
    data[count++] = rgPose.orientation.w; //RG_QW
    data[count++] = xr_module_input.TriggerLeft; //L_TRIGGER
    data[count++] = xr_module_input.TriggerRight; //R_TRIGGER
    data[count++] = yaw; //MENU_YAW

    jfloat values[count];
    memcpy(values, data, count * sizeof(float));
    jfloatArray output = env->NewFloatArray(count);
    env->SetFloatArrayRegion(output, (jsize) 0, (jsize) count, values);
    return output;
}

JNIEXPORT jbooleanArray JNICALL
Java_com_winlator_xr_XrActivity_getButtons(JNIEnv *env, jobject obj) {
    uint32_t l = XrInputGetButtonState(&xr_module_input, 0);
    uint32_t r = XrInputGetButtonState(&xr_module_input, 1);

    int count = 0;
    bool data[32];
    data[count++] = l & (int) Grip; //L_GRIP
    data[count++] = l & (int) Enter; //L_MENU
    data[count++] = l & (int) LThumb; //L_THUMBSTICK_PRESS
    data[count++] = l & (int) Left; //L_THUMBSTICK_LEFT
    data[count++] = l & (int) Right; //L_THUMBSTICK_RIGHT
    data[count++] = l & (int) Up; //L_THUMBSTICK_UP
    data[count++] = l & (int) Down; //L_THUMBSTICK_DOWN
    data[count++] = l & (int) Trigger; //L_TRIGGER
    data[count++] = l & (int) X; //L_X
    data[count++] = l & (int) Y; //L_Y
    data[count++] = r & (int) A; //R_A
    data[count++] = r & (int) B; //R_B
    data[count++] = r & (int) Grip; //R_GRIP
    data[count++] = r & (int) RThumb; //R_THUMBSTICK_PRESS
    data[count++] = r & (int) Left; //R_THUMBSTICK_LEFT
    data[count++] = r & (int) Right; //R_THUMBSTICK_RIGHT
    data[count++] = r & (int) Up; //R_THUMBSTICK_UP
    data[count++] = r & (int) Down; //R_THUMBSTICK_DOWN
    data[count++] = r & (int) Trigger; //R_TRIGGER

    jboolean values[count];
    memcpy(values, data, count * sizeof(jboolean));
    jbooleanArray output = env->NewBooleanArray(count);
    env->SetBooleanArrayRegion(output, (jsize) 0, (jsize) count, values);
    return output;
}

JNIEXPORT void JNICALL
Java_com_winlator_xr_XrActivity_nativeSetFoV(JNIEnv *env, jobject obj, jfloat x, jfloat y) {
    xr_fovx = x;
    xr_fovy = y;
}

JNIEXPORT void JNICALL
Java_com_winlator_xr_XrActivity_nativeSetCurvedScreen(JNIEnv *env, jobject obj, jboolean enabled) {
    xr_curvedScreen = enabled;
}

JNIEXPORT void JNICALL
Java_com_winlator_xr_XrActivity_nativeSetUsePT(JNIEnv *env, jobject obj, jboolean enabled) {
    xr_usePassthrough = enabled;
}

JNIEXPORT void JNICALL
Java_com_winlator_xr_XrActivity_nativeSetSharpening(JNIEnv *env, jobject obj, jint level) {
    xr_sharpening = level;
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_xr_XrActivity_nativeIsSharpeningSupported(JNIEnv *env, jobject obj) {
    return xr_module_engine.PlatformFlag[PLATFORM_EXTENSION_LAYER_SETTINGS];
}

JNIEXPORT void JNICALL
Java_com_winlator_xr_XrActivity_nativeSetUseVR(JNIEnv *env, jobject obj, jboolean enabled) {
    xr_vr = enabled;
}

JNIEXPORT void JNICALL
Java_com_winlator_xr_XrActivity_nativeSetFramesync(JNIEnv *env, jobject obj, jint r, jint g, jint b,
                                                   jint a) {
    xr_module_renderer.ConfigInt[CONFIG_FRAMESYNC_R] = r;
    xr_module_renderer.ConfigInt[CONFIG_FRAMESYNC_G] = g;
    xr_module_renderer.ConfigInt[CONFIG_FRAMESYNC_B] = b;
    xr_module_renderer.ConfigInt[CONFIG_FRAMESYNC_A] = a;
}

JNIEXPORT void JNICALL
Java_com_winlator_xr_XrActivity_vibrateController(JNIEnv *env, jobject obj, int duration, int chan,
                                                  float intensity) {
    XrInputVibrate(&xr_module_input, duration, chan, intensity);
}

JNIEXPORT void JNICALL
Java_com_winlator_xr_XrActivity_addLocateSpace(JNIEnv *env, jobject thiz, jint a, jint b) {
    std::pair<int, int> value;
    value.first = a;
    value.second = b;
    xr_locate_spaces.push_back(value);
}

JNIEXPORT void JNICALL
Java_com_winlator_xr_XrActivity_clearLocateSpaces(JNIEnv *env, jobject thiz) {
    xr_locate_spaces.clear();
}

JNIEXPORT jfloatArray JNICALL
Java_com_winlator_xr_XrActivity_getPose(JNIEnv *env, jobject thiz, jint a, jint b) {
    int count = 0;
    float data[7];
    std::pair<int, int> key;
    key.first = a;
    key.second = b;

    if (xr_poses.find(key) != xr_poses.end()) {
        XrPosef pose = xr_poses[key];
        data[count++] = pose.position.x;
        data[count++] = pose.position.y;
        data[count++] = pose.position.z;
        data[count++] = pose.orientation.x;
        data[count++] = pose.orientation.y;
        data[count++] = pose.orientation.z;
        data[count++] = pose.orientation.w;
    }

    jfloat values[count];
    memcpy(values, data, count * sizeof(float));
    jfloatArray output = env->NewFloatArray(count);
    env->SetFloatArrayRegion(output, (jsize) 0, (jsize) count, values);
    return output;
}

JNIEXPORT void JNICALL
Java_com_winlator_xr_XrActivity_increaseReferenceSpacesOffset(JNIEnv *env, jobject thiz, jfloat x,
                                                              jfloat y, jfloat z) {
    double yaw = -ToRadians(xr_module_renderer.ConfigFloat[CONFIG_MENU_YAW]);
    auto c = (float)cos(yaw);
    auto s = (float)sin(yaw);

    for (auto it = xr_info.begin(); it != xr_info.end(); ++it) {
        int space = it->first;
        XrSpace output = {};
        XrReferenceSpaceCreateInfo space_info = it->second;
        if (space_info.referenceSpaceType == XR_REFERENCE_SPACE_TYPE_VIEW) {
            continue;
        }

        XrPosef offset = {};
        offset.position.x = -(x * c - z * s);
        offset.position.y = -y;
        offset.position.z = -(x * s + z * c);
        offset.orientation.w = 1;
        space_info.poseInReferenceSpace = XrPosefMultiply(space_info.poseInReferenceSpace, offset);

        xrCreateReferenceSpace(xr_module_engine.Session, &space_info, &output);
        xrDestroySpace(xr_spaces[space]);
        xr_info[space] = space_info;
        xr_spaces[space] = output;
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_xr_XrActivity_updateActionSpace(JNIEnv *env, jobject thiz, jint space, jint type,
                                                  jint grip, jfloat x, jfloat y, jfloat z,
                                                  jfloat qx, jfloat qy, jfloat qz, jfloat qw) {
    if (xr_spaces.find(space) == xr_spaces.end()) {
        ALOGV("Creating action space %d", space);
        XrSpace output = {};
        XrActionSpaceCreateInfo space_info = {};
        space_info.type = XR_TYPE_ACTION_SPACE_CREATE_INFO;
        space_info.action = XrInputGetControllerAction(&xr_module_input, type, grip);
        space_info.subactionPath = XrInputGetControllerPath(&xr_module_input, type);
        space_info.poseInActionSpace.orientation.x = qx;
        space_info.poseInActionSpace.orientation.y = qy;
        space_info.poseInActionSpace.orientation.z = qz;
        space_info.poseInActionSpace.orientation.w = qw;
        space_info.poseInActionSpace.position.x = x;
        space_info.poseInActionSpace.position.y = y;
        space_info.poseInActionSpace.position.z = z;
        if (xrCreateActionSpace(xr_module_engine.Session, &space_info, &output) != XR_SUCCESS) {
            ALOGE("Failed to create action space %d", space);
            std::exit(-1);
        }
        xr_spaces[space] = output;
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_xr_XrActivity_updateReferenceSpace(JNIEnv *env, jobject thiz, jint space,
                                                     jint type, jfloat x, jfloat y, jfloat z,
                                                     jfloat qx, jfloat qy, jfloat qz, jfloat qw) {
    if (xr_spaces.find(space) == xr_spaces.end()) {
        ALOGV("Creating reference space %d", space);
        XrSpace output = {};
        XrReferenceSpaceCreateInfo space_info = {};
        space_info.type = XR_TYPE_REFERENCE_SPACE_CREATE_INFO;
        space_info.referenceSpaceType = (XrReferenceSpaceType)type;
        space_info.poseInReferenceSpace.orientation.x = qx;
        space_info.poseInReferenceSpace.orientation.y = qy;
        space_info.poseInReferenceSpace.orientation.z = qz;
        space_info.poseInReferenceSpace.orientation.w = qw;
        space_info.poseInReferenceSpace.position.x = x;
        space_info.poseInReferenceSpace.position.y = y;
        space_info.poseInReferenceSpace.position.z = z;
        if (xrCreateReferenceSpace(xr_module_engine.Session, &space_info, &output) != XR_SUCCESS) {
            ALOGE("Failed to create reference space %d", space);
            std::exit(-1);
        }
        xr_info[space] = space_info;
        xr_spaces[space] = output;
    }
}

}