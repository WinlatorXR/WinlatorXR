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

/* Calls shared by the PE half (wxr_bridge.dll) and the native half
 * (wxr_bridge.so). Every field is fixed-size so the structs lay out the same
 * for an arm64ec or x64 caller and the aarch64 unixlib.
 */

#ifndef WXR_BRIDGE_H
#define WXR_BRIDGE_H

#include <stdint.h>

#define WXR_BRIDGE_ABI 5

enum wxr_bridge_call {
    WXR_BRIDGE_INIT,            /* create the relay Vulkan device */
    WXR_BRIDGE_IMPORT,          /* shared NT handle -> fd -> image on the relay device, plus an AHardwareBuffer */
    WXR_BRIDGE_COPY_READBACK,   /* copy the image into the AHardwareBuffer and check a test pattern on the CPU */
    WXR_BRIDGE_SUBMIT,          /* queue one layer's copy into the AHardwareBuffer, without waiting */
    WXR_BRIDGE_DUMP,            /* debugging: wait for the last copy and write one layer to a file */
    WXR_BRIDGE_PRESENT,         /* copy a frame's views and send them to the app, with a sync fd each */
    WXR_BRIDGE_CALL_COUNT
};

#define WXR_BRIDGE_MAX_VIEWS 2
#define WXR_BRIDGE_MAX_QUADS 4

struct wxr_bridge_present_view {
    uint32_t id, layer;         /* in: slot and array slice holding this eye */
    int32_t  rect[4];           /* in: imageRect x, y, width, height */
    float    orientation[4];    /* in: the pose the game rendered this eye with */
    float    position[3];
    float    fov[4];            /* in: angleLeft, angleRight, angleUp, angleDown */
};

struct wxr_bridge_present_quad {
    uint64_t space;             /* in: the quad layer's XrSpace */
    uint32_t id, layer;         /* in: slot and array slice holding the quad */
    int32_t  rect[4];           /* in: imageRect x, y, width, height */
    float    orientation[4];    /* in: pose in space */
    float    position[3];
    float    size[2];           /* in: width, height in meters */
    uint32_t eye_visibility;    /* in: XrEyeVisibility */
    uint32_t flags;             /* in: XrCompositionLayerFlags */
    uint32_t padding;
};

struct wxr_bridge_present_args {
    uint64_t frame;             /* in */
    int64_t  display_time;      /* in: XrFrameEndInfo::displayTime */
    uint64_t space;             /* in: the projection layer's XrSpace */
    uint32_t view_count;        /* in: 0 for a frame of quads only */
    uint32_t quad_count;        /* in */
    struct wxr_bridge_present_view views[WXR_BRIDGE_MAX_VIEWS];
    struct wxr_bridge_present_quad quads[WXR_BRIDGE_MAX_QUADS];  /* in: drawn over the views, in order */
    int32_t  result;            /* out: first failing VkResult, or 0 */
    uint32_t sent;              /* out: 1 when the frame reached the app */
};

/* Import flags: must mirror how DXVK created the image, or the layouts disagree. */
#define WXR_BRIDGE_IMPORT_MUTABLE_FORMAT 0x1   /* typeless DXGI format */
#define WXR_BRIDGE_IMPORT_STORAGE        0x2   /* D3D11_BIND_UNORDERED_ACCESS */

struct wxr_bridge_init_args {
    uint32_t abi;               /* in: WXR_BRIDGE_ABI */
    int32_t  result;            /* out: 0 on success */
    char     message[256];      /* out: device name, or what failed */
};

struct wxr_bridge_import_args {
    uint64_t nt_handle;         /* in: from IDXGIResource1::CreateSharedHandle */
    uint32_t width, height;     /* in */
    uint32_t layers;            /* in: array size */
    uint32_t vk_format;         /* in: VkFormat of the D3D11 texture */
    uint32_t flags;             /* in: WXR_BRIDGE_IMPORT_* */
    uint32_t id;                /* out: slot for later calls */
    int32_t  result;            /* out: 0 on success */
    char     message[256];      /* out */
};

struct wxr_bridge_submit_args {
    uint32_t id;                /* in */
    uint32_t layer;             /* in: array slice to copy */
    int32_t  result;            /* out: VkResult of the submit */
};

struct wxr_bridge_dump_args {
    uint32_t id;                /* in */
    uint32_t layer;             /* in */
    char     path[256];         /* in: unix path to write raw RGBA8 rows to */
    uint32_t width, height;     /* out */
    int32_t  result;            /* out: 0 on success */
    char     message[256];      /* out */
};

struct wxr_bridge_readback_args {
    uint32_t id;                /* in */
    uint32_t mismatches;        /* out: test-pattern pixels that differ */
    uint32_t checked;           /* out: pixels compared */
    uint32_t first_bad;         /* out: RGBA of the first mismatch */
    int32_t  result;            /* out: 0 when the copy ran */
    char     message[256];      /* out */
};

/* Pixel (x, y) of the test pattern, as R8G8B8A8 in memory order. */
static inline uint32_t wxr_bridge_pattern(uint32_t x, uint32_t y)
{
    return (x & 0xff) | ((y & 0xff) << 8) | (0x5au << 16) | (0xffu << 24);
}

#endif
