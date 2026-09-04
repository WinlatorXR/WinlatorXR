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

/*
 * One Euro filter (Casiez, Roussel, Vogel 2012).
 *
 * A low-pass whose cutoff rises with the speed of the signal, so a still hand is smoothed
 * hard while a moving one is barely touched. Controller poses arrive with enough jitter to
 * make a laser pointer wobble over small desktop targets, which matters far more when the
 * ray is driving a Windows UI than when it is picking from a list of big buttons.
 *
 * Tuning, per the paper's own procedure: raise MinCutoff until a still pointer stops
 * wobbling, then raise Beta until a moving pointer stops lagging. Lower MinCutoff means
 * more smoothing; higher Beta means the filter gets out of the way sooner during motion.
 */
#define XrOneEuroPositionMinCutoff 1.2f
#define XrOneEuroPositionBeta 1.0f
#define XrOneEuroOrientationMinCutoff 1.5f
#define XrOneEuroOrientationBeta 1.0f
#define XrOneEuroDerivativeCutoff 1.0f

struct XrOneEuroScalar {
    float Value;       // last filtered value
    float Raw;         // last raw input, for the derivative
    float Derivative;  // last filtered derivative
};

struct XrOneEuroPose {
    bool Primed;
    XrTime PreviousTime;
    struct XrOneEuroScalar Position[3];
    struct XrOneEuroScalar Orientation[4];
};

/* Drops the filter's history, so the next pose is passed through untouched. */
void XrOneEuroPoseReset(struct XrOneEuroPose* filter);

/*
 * Returns the smoothed pose. time is the OpenXR predicted display time in nanoseconds.
 * Call XrOneEuroPoseReset whenever tracking has been lost, otherwise the filter drags the
 * pose back from wherever the controller was before it disappeared.
 */
XrPosef XrOneEuroPoseFilter(struct XrOneEuroPose* filter, XrPosef pose, XrTime time);
