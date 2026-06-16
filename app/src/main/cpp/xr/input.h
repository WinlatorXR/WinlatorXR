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

enum XrButton
{
    A = 0x00000001,  // Set for trigger pulled on the Gear VR and Go Controllers
    B = 0x00000002,
    RThumb = 0x00000004,

    X = 0x00000100,
    Y = 0x00000200,
    LThumb = 0x00000400,

    Up = 0x00010000,
    Down = 0x00020000,
    Left = 0x00040000,
    Right = 0x00080000,
    Enter = 0x00100000,  //< Set for touchpad click on the Go Controller, menu
    // button on Left Quest Controller
    Back = 0x00200000,  //< Back button on the Go Controller (only set when
    // a short press comes up)
    Grip = 0x04000000,    //< grip trigger engaged
    Trigger = 0x20000000  //< Index Trigger engaged
};

struct XrInput {

    bool Initialized;

    // OpenXR controller mapping
    XrActionSet ActionSet;
    XrPath LeftHandPath;
    XrPath RightHandPath;
    XrAction HandGripLeft;
    XrAction HandGripRight;
    XrAction HandPoseLeft;
    XrAction HandPoseRight;
    XrAction IndexLeft;
    XrAction IndexRight;
    XrAction ButtonMenu;
    XrAction ButtonA;
    XrAction ButtonB;
    XrAction ButtonX;
    XrAction ButtonY;
    XrAction GripLeft;
    XrAction GripRight;
    XrAction JoystickLeft;
    XrAction JoystickRight;
    XrAction ThumbLeft;
    XrAction ThumbRight;
    XrAction VibrateLeftFeedback;
    XrAction VibrateRightFeedback;
    XrSpace LeftControllerAimSpace;
    XrSpace RightControllerAimSpace;
    XrSpace LeftControllerGripSpace;
    XrSpace RightControllerGripSpace;

    // Controller state
    uint32_t ButtonsLeft;
    uint32_t ButtonsRight;
    XrSpaceLocation ControllerPose[4];
    XrActionStateVector2f JoystickState[2];
    float VibrationChannelDuration[2];
    float VibrationChannelIntensity[2];

    // Timer
    unsigned long SysTimeBase;
};

void XrInputInit(struct XrEngine* engine, struct XrInput* input);
uint32_t XrInputGetButtonState(struct XrInput* input, int controller);
XrVector2f XrInputGetJoystickState(struct XrInput* input, int controller);
XrPosef XrInputGetPose(struct XrInput* input, int controller);
void XrInputUpdate(struct XrEngine* engine, struct XrInput* input);
void XrInputVibrate(struct XrInput* input, int duration, int chan, float intensity);

XrAction XrInputCreateAction(XrActionSet output_set, XrActionType type, const char* name,
                             const char* desc, int count_subaction_path, XrPath* subaction_path);
XrActionSet XrInputCreateActionSet(XrInstance instance, const char* name, const char* desc);
XrSpace XrInputCreateActionSpace(XrSession session, XrAction action, XrPath subaction_path);
XrActionStateBoolean XrInputGetActionStateBoolean(XrSession session, XrAction action);
XrActionStateFloat XrInputGetActionStateFloat(XrSession session, XrAction action);
XrActionStateVector2f XrInputGetActionStateVector2(XrSession session, XrAction action);
XrActionSuggestedBinding XrInputGetBinding(XrInstance instance, XrAction action, const char* name);
int XrInputGetMilliseconds(struct XrInput* input);
void XrInputProcessHaptics(struct XrInput* input, XrSession session);
