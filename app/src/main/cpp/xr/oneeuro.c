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

#include "oneeuro.h"

#include <math.h>
#include <string.h>

// Anything outside this is a hitch or a clock oddity rather than a frame interval, and
// feeding it to the filter produces a visible jump.
#define XrOneEuroMinDelta 0.0005f
#define XrOneEuroMaxDelta 0.1f

static float SmoothingFactor(float cutoff, float delta)
{
    float tau = 1.0f / (2.0f * (float)M_PI * cutoff);
    return 1.0f / (1.0f + tau / delta);
}

static float FilterScalar(struct XrOneEuroScalar* scalar, float value, float delta,
                          float min_cutoff, float beta)
{
    float derivative = (value - scalar->Raw) / delta;
    scalar->Derivative += SmoothingFactor(XrOneEuroDerivativeCutoff, delta) *
                          (derivative - scalar->Derivative);

    // The whole point of the filter: the faster the signal moves, the less it is smoothed.
    float cutoff = min_cutoff + beta * fabsf(scalar->Derivative);
    scalar->Value += SmoothingFactor(cutoff, delta) * (value - scalar->Value);
    scalar->Raw = value;
    return scalar->Value;
}

void XrOneEuroPoseReset(struct XrOneEuroPose* filter)
{
    memset(filter, 0, sizeof(struct XrOneEuroPose));
}

XrPosef XrOneEuroPoseFilter(struct XrOneEuroPose* filter, XrPosef pose, XrTime time)
{
    const float position[3] = {pose.position.x, pose.position.y, pose.position.z};
    const float orientation[4] = {pose.orientation.x, pose.orientation.y,
                                 pose.orientation.z, pose.orientation.w};

    if (!filter->Primed)
    {
        for (int i = 0; i < 3; i++)
        {
            filter->Position[i].Value = position[i];
            filter->Position[i].Raw = position[i];
            filter->Position[i].Derivative = 0.0f;
        }
        for (int i = 0; i < 4; i++)
        {
            filter->Orientation[i].Value = orientation[i];
            filter->Orientation[i].Raw = orientation[i];
            filter->Orientation[i].Derivative = 0.0f;
        }
        filter->PreviousTime = time;
        filter->Primed = true;
        return pose;
    }

    float delta = (float)((double)(time - filter->PreviousTime) * 1e-9);
    filter->PreviousTime = time;
    if ((delta < XrOneEuroMinDelta) || (delta > XrOneEuroMaxDelta))
    {
        return pose;
    }

    XrPosef result = pose;
    float smoothed[3];
    for (int i = 0; i < 3; i++)
    {
        smoothed[i] = FilterScalar(&filter->Position[i], position[i], delta,
                                   XrOneEuroPositionMinCutoff, XrOneEuroPositionBeta);
    }
    result.position.x = smoothed[0];
    result.position.y = smoothed[1];
    result.position.z = smoothed[2];

    // q and -q are the same rotation, so a sign flip between frames would otherwise look
    // like the controller spinning through the whole arc. Flip the input to match instead.
    float dot = 0.0f;
    for (int i = 0; i < 4; i++) dot += filter->Orientation[i].Value * orientation[i];
    float sign = (dot < 0.0f) ? -1.0f : 1.0f;

    float filtered[4];
    float length = 0.0f;
    for (int i = 0; i < 4; i++)
    {
        filtered[i] = FilterScalar(&filter->Orientation[i], sign * orientation[i], delta,
                                   XrOneEuroOrientationMinCutoff, XrOneEuroOrientationBeta);
        length += filtered[i] * filtered[i];
    }

    // Filtering the components independently leaves the quaternion off the unit sphere.
    length = sqrtf(length);
    if (length > 1e-6f)
    {
        result.orientation.x = filtered[0] / length;
        result.orientation.y = filtered[1] / length;
        result.orientation.z = filtered[2] / length;
        result.orientation.w = filtered[3] / length;
    }

    return result;
}
