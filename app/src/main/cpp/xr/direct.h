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

/*
 * Direct transport: PC VR eye images from the Wine process (tools/wxr_bridge)
 * to this app, without the X11 preview window.
 *
 * The bridge connects to an abstract SOCK_SEQPACKET socket this app listens on.
 * Each eye buffer is sent once: a BUFFER message, then the AHardwareBuffer
 * itself via AHardwareBuffer_sendHandleToUnixSocket. Each frame is one FRAME
 * message carrying, per view and per quad, a sync fd (SCM_RIGHTS) that
 * signals when the bridge's copy into that buffer has finished.
 *
 * Shared with tools/wxr_bridge; both sides must agree on WXR_DIRECT_VERSION.
 */

#ifndef WXR_DIRECT_H
#define WXR_DIRECT_H

#include <stdint.h>

#define WXR_DIRECT_SOCKET  "winlatorxr.direct"   /* abstract namespace */
#define WXR_DIRECT_VERSION 2
#define WXR_DIRECT_MAX_VIEWS 2
#define WXR_DIRECT_MAX_QUADS 4
#define WXR_DIRECT_MAX_FDS (WXR_DIRECT_MAX_VIEWS + WXR_DIRECT_MAX_QUADS)

enum wxr_direct_type {
    WXR_DIRECT_HELLO  = 1,
    WXR_DIRECT_BUFFER = 2,
    WXR_DIRECT_FRAME  = 3,
};

struct wxr_direct_header {
    uint32_t type;
    uint32_t size;              /* whole message, header included */
};

struct wxr_direct_hello {
    struct wxr_direct_header header;
    uint32_t version;
    uint32_t pid;
};

/* Followed by one AHardwareBuffer_sendHandleToUnixSocket message. */
struct wxr_direct_buffer {
    struct wxr_direct_header header;
    uint32_t slot, layer;       /* the bridge's names for this buffer */
    uint32_t width, height;     /* RGBA8, sRGB-encoded when the game rendered sRGB */
};

struct wxr_direct_view {
    uint32_t slot, layer;
    int32_t  rect[4];           /* imageRect: x, y, width, height */
    float    orientation[4];    /* x, y, z, w -- the pose the game rendered with */
    float    position[3];
    float    fov[4];            /* angleLeft, angleRight, angleUp, angleDown */
};

struct wxr_direct_quad {
    uint64_t space;             /* the game's XrSpace for this quad */
    uint32_t slot, layer;
    int32_t  rect[4];           /* imageRect: x, y, width, height */
    float    orientation[4];    /* x, y, z, w */
    float    position[3];
    float    size[2];           /* width, height in meters */
    uint32_t eye_visibility;    /* XrEyeVisibility */
    uint32_t flags;             /* XrCompositionLayerFlags */
    uint32_t padding;
};

/* The sync fds come in view order, then quad order. */
struct wxr_direct_frame {
    struct wxr_direct_header header;
    uint64_t frame;
    int64_t  display_time;      /* the game's XrFrameEndInfo::displayTime */
    uint64_t space;             /* the game's XrSpace for the projection layer */
    uint32_t view_count;        /* 0 when the game submitted quads only */
    uint32_t quad_count;
    struct wxr_direct_view views[WXR_DIRECT_MAX_VIEWS];
    struct wxr_direct_quad quads[WXR_DIRECT_MAX_QUADS];  /* drawn over the views, in order */
};

#endif
