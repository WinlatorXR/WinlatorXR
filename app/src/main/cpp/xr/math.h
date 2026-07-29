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

#include <openxr/openxr.h>

#ifndef EPSILON
#define EPSILON 0.001f
#endif

double FromXrTime(const XrTime time);
XrTime ToXrTime(const double time_in_seconds);
float ToDegrees(float rad);
float ToRadians(float deg);

// XrQuaternionf
XrQuaternionf XrQuaternionfCreateFromVectorAngle(const XrVector3f axis, const float angle);
XrQuaternionf XrQuaternionfInvert(const XrQuaternionf q);
XrQuaternionf XrQuaternionfMultiply(const XrQuaternionf a, const XrQuaternionf b);
XrVector3f XrQuaternionfEulerAngles(const XrQuaternionf q);
XrVector3f XrQuaternionfRotateVector3f(const XrQuaternionf a, const XrVector3f v);
void XrQuaternionfToMatrix4f(const XrQuaternionf* q, float* m);

// XrVector3f, XrVector4f
float XrVector3fDistance(const XrVector3f a, const XrVector3f b);
float XrVector3fLengthSquared(const XrVector3f v);
XrVector3f XrVector3fAdd(const XrVector3f a, const XrVector3f b);
XrVector3f XrVector3fGetAnglesFromVectors(XrVector3f forward, XrVector3f right, XrVector3f up);
XrVector3f XrVector3fNormalized(const XrVector3f v);
XrVector3f XrVector3fScalarMultiply(const XrVector3f v, float scale);
XrVector4f XrVector4fMultiplyMatrix4f(const float* m, const XrVector4f* v);

// XrPosef
XrPosef XrPosefMultiply(const XrPosef a, const XrPosef b);
