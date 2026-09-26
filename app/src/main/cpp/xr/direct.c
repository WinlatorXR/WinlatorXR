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
 * Direct transport receiver: listens for tools/wxr_bridge in the Wine process,
 * keeps the eye buffers and the latest frame it sends (see direct.h), and turns
 * them into a projection layer on the render thread.
 */

#include "direct.h"
#include "direct_app.h"
#include "framebuffer.h"
#include "math.h"

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <poll.h>
#include <pthread.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "WxrDirect", __VA_ARGS__)

#define MAX_BUFFERS 128
#define FRAME_TIMEOUT_MS 5000.0  /* no frame for this long and the caller's own layer returns */

/* Filled by the socket thread; the GL half is created and destroyed only on the
 * render thread. Entries of an older generation are released there too. */
static struct buffer {
    uint32_t slot, layer, generation;
    AHardwareBuffer* buffer;
    EGLImageKHR image;
    GLuint texture, fbo;
} g_buffers[MAX_BUFFERS];
static int g_buffer_count;
static uint32_t g_generation;

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static struct wxr_direct_frame g_frame;
static int g_frame_fds[WXR_DIRECT_MAX_FDS];
static int g_frame_fd_count;
static bool g_has_frame;
static double g_frame_time;

static XrSpace (*g_resolve_space)(uint64_t);

static double now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1000000.0;
}

static void drop_frame_locked(void)
{
    for (int i = 0; i < g_frame_fd_count; i++) close(g_frame_fds[i]);
    g_frame_fd_count = 0;
    g_has_frame = false;
}

static void reset(void)
{
    pthread_mutex_lock(&g_lock);
    drop_frame_locked();
    g_generation++;  /* the render thread releases the old buffers */
    pthread_mutex_unlock(&g_lock);
}

static void add_buffer(const struct wxr_direct_buffer* msg, AHardwareBuffer* buffer)
{
    pthread_mutex_lock(&g_lock);
    if (g_buffer_count < MAX_BUFFERS) {
        struct buffer* b = &g_buffers[g_buffer_count++];
        memset(b, 0, sizeof(*b));
        b->slot = msg->slot;
        b->layer = msg->layer;
        b->generation = g_generation;
        b->buffer = buffer;
    } else {
        AHardwareBuffer_release(buffer);
    }
    pthread_mutex_unlock(&g_lock);
}

/* How long after arrival the copy's sync fd signals: the bridge sends the frame
 * before its copy has finished, so this is what the app would wait for. */
static double copy_wait_ms(int fd)
{
    struct pollfd p = { fd, POLLIN, 0 };
    double start = now_ms();
    return poll(&p, 1, 100) == 1 ? now_ms() - start : -1.0;
}

static float g_fps;  /* frames received per second, for the XR FPS panel */

static void on_frame(const struct wxr_direct_frame* msg, const int* fds, int fd_count)
{
    static uint64_t frames;
    static double window_start;
    static double rate_start;
    static int rate_frames;
    double now = now_ms();

    rate_frames++;
    if (now - rate_start >= 1000.0) {
        if (rate_start > 0) g_fps = (float)(rate_frames * 1000.0 / (now - rate_start));
        rate_frames = 0;
        rate_start = now;
    }

    if (++frames == 1) window_start = now_ms();
    if (frames % 300 == 0) {
        double elapsed = now_ms() - window_start;
        const struct wxr_direct_view* v = &msg->views[0];
        LOG("frame %llu: %.1f fps received, %u view(s), %u quad(s), copy done %.2f ms after arrival; "
            "view 0 slot %u layer %u rect %dx%d, pos %.3f %.3f %.3f, fov %.3f %.3f %.3f %.3f",
            (unsigned long long)msg->frame, 300000.0 / elapsed, msg->view_count, msg->quad_count,
            fd_count ? copy_wait_ms(fds[0]) : -1.0, v->slot, v->layer, v->rect[2], v->rect[3],
            v->position[0], v->position[1], v->position[2], v->fov[0], v->fov[1], v->fov[2], v->fov[3]);
        window_start = now_ms();
    }

    pthread_mutex_lock(&g_lock);
    drop_frame_locked();  /* a frame the render thread never took is simply replaced */
    g_frame = *msg;
    memcpy(g_frame_fds, fds, sizeof(int) * fd_count);
    g_frame_fd_count = fd_count;
    g_has_frame = true;
    g_frame_time = now_ms();
    pthread_mutex_unlock(&g_lock);
}

static void serve(int conn)
{
    union {
        struct wxr_direct_header header;
        struct wxr_direct_hello hello;
        struct wxr_direct_buffer buffer;
        struct wxr_direct_frame frame;
    } msg;
    char control[CMSG_SPACE(sizeof(int) * WXR_DIRECT_MAX_FDS)];

    for (;;) {
        struct iovec iov = { &msg, sizeof(msg) };
        struct msghdr hdr = { 0 };
        int fds[WXR_DIRECT_MAX_FDS], fd_count = 0;
        ssize_t size;

        hdr.msg_iov = &iov;
        hdr.msg_iovlen = 1;
        hdr.msg_control = control;
        hdr.msg_controllen = sizeof(control);
        size = recvmsg(conn, &hdr, MSG_CMSG_CLOEXEC);
        if (size <= 0) return;

        for (struct cmsghdr* c = CMSG_FIRSTHDR(&hdr); c; c = CMSG_NXTHDR(&hdr, c)) {
            if (c->cmsg_level != SOL_SOCKET || c->cmsg_type != SCM_RIGHTS) continue;
            int n = (int)((c->cmsg_len - CMSG_LEN(0)) / sizeof(int));
            for (int i = 0; i < n; i++) {
                int fd;
                memcpy(&fd, CMSG_DATA(c) + i * sizeof(int), sizeof(int));
                if (fd_count < WXR_DIRECT_MAX_FDS) fds[fd_count++] = fd;
                else close(fd);
            }
        }

        if (size < (ssize_t)sizeof(msg.header) || msg.header.size != (uint32_t)size) {
            LOG("malformed message (%zd bytes), dropping the connection", size);
            for (int i = 0; i < fd_count; i++) close(fds[i]);
            return;
        }

        switch (msg.header.type) {
        case WXR_DIRECT_HELLO:
            LOG("bridge connected: pid %u, protocol %u", msg.hello.pid, msg.hello.version);
            if (msg.hello.version != WXR_DIRECT_VERSION) {
                LOG("protocol mismatch, this app speaks %d", WXR_DIRECT_VERSION);
                return;
            }
            break;
        case WXR_DIRECT_BUFFER: {
            AHardwareBuffer* buffer = NULL;
            AHardwareBuffer_Desc desc;
            if (AHardwareBuffer_recvHandleFromUnixSocket(conn, &buffer) || !buffer) {
                LOG("slot %u layer %u: buffer handle did not arrive", msg.buffer.slot, msg.buffer.layer);
                return;
            }
            AHardwareBuffer_describe(buffer, &desc);
            LOG("slot %u layer %u: buffer %ux%u format %u usage 0x%llx",
                msg.buffer.slot, msg.buffer.layer, desc.width, desc.height, desc.format,
                (unsigned long long)desc.usage);
            add_buffer(&msg.buffer, buffer);
            break;
        }
        case WXR_DIRECT_FRAME:
            on_frame(&msg.frame, fds, fd_count);
            fd_count = 0;  /* on_frame keeps them */
            break;
        default:
            LOG("unknown message type %u", msg.header.type);
            break;
        }
        for (int i = 0; i < fd_count; i++) close(fds[i]);
    }
}

static void* listen_thread(void* unused)
{
    struct sockaddr_un addr = { 0 };
    socklen_t length = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(WXR_DIRECT_SOCKET);
    int server = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);

    (void)unused;
    addr.sun_family = AF_UNIX;
    memcpy(addr.sun_path + 1, WXR_DIRECT_SOCKET, strlen(WXR_DIRECT_SOCKET));  /* sun_path[0] = 0: abstract */
    if (server < 0 || bind(server, (struct sockaddr*)&addr, length) || listen(server, 1)) {
        LOG("cannot listen on @%s", WXR_DIRECT_SOCKET);
        if (server >= 0) close(server);
        return NULL;
    }
    LOG("listening on @%s", WXR_DIRECT_SOCKET);

    for (;;) {
        int conn = accept4(server, NULL, NULL, SOCK_CLOEXEC);
        if (conn < 0) continue;
        serve(conn);
        close(conn);
        reset();  /* a new game process sends its buffers again */
        LOG("bridge disconnected");
    }
    return NULL;
}

void XrDirectStart(void)
{
    static bool started;
    pthread_t thread;

    if (started) return;
    started = true;
    if (!pthread_create(&thread, NULL, listen_thread, NULL)) pthread_detach(thread);
}

void XrDirectSetSpaceResolver(XrSpace (*resolve)(uint64_t id))
{
    g_resolve_space = resolve;
}

/* ------------------------------------------------------------ render thread */

static PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC p_eglGetNativeClientBufferANDROID;
static PFNEGLCREATEIMAGEKHRPROC p_eglCreateImageKHR;
static PFNEGLDESTROYIMAGEKHRPROC p_eglDestroyImageKHR;
static PFNGLEGLIMAGETARGETTEXTURE2DOESPROC p_glEGLImageTargetTexture2DOES;
static PFNEGLCREATESYNCKHRPROC p_eglCreateSyncKHR;
static PFNEGLWAITSYNCKHRPROC p_eglWaitSyncKHR;
static PFNEGLDESTROYSYNCKHRPROC p_eglDestroySyncKHR;

/* The eyes as last copied: resubmitted every frame until the game sends the next. */
static struct XrFramebuffer g_eyes[WXR_DIRECT_MAX_VIEWS];
static int g_eye_width, g_eye_height;
static struct XrFramebuffer g_quads[WXR_DIRECT_MAX_QUADS];
static int g_quad_width[WXR_DIRECT_MAX_QUADS], g_quad_height[WXR_DIRECT_MAX_QUADS];
static struct wxr_direct_frame g_shown;
static bool g_shown_valid;
bool XrDirectOpaqueEyes;
int XrDirectKeyMode;
float XrDirectKeyThreshold;

/* Colour key, kept in step with WindowMaterial.java: alpha 0 where the pixel matches the key.
 * Modes 1-3 match hue (green, blue, pink) with saturation and brightness guards, 4 is black. */
#define KEY_ALPHA_GLSL \
    "float keyAlpha(vec3 c) {\n" \
    "    if (keyMode == 0) return 1.0;\n" \
    "    float v = max(c.r, max(c.g, c.b));\n" \
    "    if (keyMode == 4) return smoothstep(keyThreshold, keyThreshold + 0.04, v);\n" \
    "    float d = v - min(c.r, min(c.g, c.b));\n" \
    "    float s = v > 0.0 ? d / v : 0.0;\n" \
    "    float h = 0.0;\n" \
    "    if (d > 0.0) {\n" \
    "        if (v == c.r) h = mod((c.g - c.b) / d, 6.0);\n" \
    "        else if (v == c.g) h = (c.b - c.r) / d + 2.0;\n" \
    "        else h = (c.r - c.g) / d + 4.0;\n" \
    "    }\n" \
    "    float key = keyMode == 1 ? 2.0 : (keyMode == 2 ? 4.0 : 5.0);\n" \
    "    float dh = abs(h - key) / 6.0;\n" \
    "    dh = min(dh, 1.0 - dh);\n" \
    "    float match = 1.0 - smoothstep(keyThreshold, keyThreshold + 0.03, dh);\n" \
    "    return 1.0 - match * smoothstep(0.2, 0.3, s) * smoothstep(0.1, 0.2, v);\n" \
    "}\n"

static const char* KEY_VS =
    "#version 300 es\n"
    "void main() {\n"
    "    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));\n"
    "    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n"
    "}\n";

static const char* KEY_FS =
    "#version 300 es\n"
    "precision highp float;\n"
    "uniform highp sampler2D src;\n"
    "uniform ivec4 rect;\n"
    "uniform vec2 size;\n"
    "uniform int keyMode;\n"
    "uniform float keyThreshold;\n"
    "out vec4 color;\n"
    KEY_ALPHA_GLSL
    "void main() {\n"
    "    vec2 st = gl_FragCoord.xy / size;\n"
    /* Same mapping as the blit: the buffer's rows run top-down */
    "    vec2 p = vec2(rect.xy) + vec2(st.x, 1.0 - st.y) * vec2(rect.zw);\n"
    "    vec3 c = texelFetch(src, ivec2(floor(p)), 0).rgb;\n"
    "    color = vec4(c, keyAlpha(c));\n"
    "}\n";

static GLuint g_key_program, g_key_vao;
static GLint g_key_rect, g_key_size, g_key_mode, g_key_threshold;

static GLuint key_shader(GLenum type, const char* source)
{
    GLint ok = 0;
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, NULL);
    glCompileShader(shader);
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512];
        glGetShaderInfoLog(shader, sizeof(log), NULL, log);
        LOG("colour key shader: %s", log);
    }
    return shader;
}

static bool key_program(void)
{
    static bool failed;
    GLint ok = 0;
    if (g_key_program || failed) return g_key_program != 0;
    GLuint vs = key_shader(GL_VERTEX_SHADER, KEY_VS), fs = key_shader(GL_FRAGMENT_SHADER, KEY_FS);
    GLuint program = glCreateProgram();
    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glLinkProgram(program);
    glDeleteShader(vs);
    glDeleteShader(fs);
    glGetProgramiv(program, GL_LINK_STATUS, &ok);
    if (!ok) {
        LOG("colour key program failed to link");
        glDeleteProgram(program);
        failed = true;
        return false;
    }
    g_key_rect = glGetUniformLocation(program, "rect");
    g_key_size = glGetUniformLocation(program, "size");
    g_key_mode = glGetUniformLocation(program, "keyMode");
    g_key_threshold = glGetUniformLocation(program, "keyThreshold");
    glGenVertexArrays(1, &g_key_vao);
    g_key_program = program;
    return true;
}

/* Draws rect of src into the bound target with the colour key applied; leaves GL state as found. */
static void key_copy(int width, int height, struct buffer* src, const int32_t* r)
{
    GLint program, vao, active, texture, viewport[4];
    GLboolean blend = glIsEnabled(GL_BLEND), depth = glIsEnabled(GL_DEPTH_TEST);
    GLboolean cull = glIsEnabled(GL_CULL_FACE), scissor = glIsEnabled(GL_SCISSOR_TEST);
    glGetIntegerv(GL_CURRENT_PROGRAM, &program);
    glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &vao);
    glGetIntegerv(GL_ACTIVE_TEXTURE, &active);
    glActiveTexture(GL_TEXTURE0);
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &texture);
    glGetIntegerv(GL_VIEWPORT, viewport);

    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(GL_SCISSOR_TEST);
    glViewport(0, 0, width, height);
    glUseProgram(g_key_program);
    glBindVertexArray(g_key_vao);
    glBindTexture(GL_TEXTURE_2D, src->texture);
    /* Only level 0 exists: the default mipmap filter would leave the texture incomplete */
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glUniform4i(g_key_rect, r[0], r[1], r[2], r[3]);
    glUniform2f(g_key_size, (float)width, (float)height);
    glUniform1i(g_key_mode, XrDirectKeyMode);
    glUniform1f(g_key_threshold, XrDirectKeyThreshold);
    glDrawArrays(GL_TRIANGLES, 0, 3);

    glBindTexture(GL_TEXTURE_2D, texture);
    glActiveTexture(active);
    glBindVertexArray(vao);
    glUseProgram(program);
    glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
    if (blend) glEnable(GL_BLEND);
    if (depth) glEnable(GL_DEPTH_TEST);
    if (cull) glEnable(GL_CULL_FACE);
    if (scissor) glEnable(GL_SCISSOR_TEST);
}

static bool load_egl(void)
{
    static int loaded;  /* 0 not tried, 1 ok, -1 missing */
    if (!loaded) {
        p_eglGetNativeClientBufferANDROID = (PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC)eglGetProcAddress("eglGetNativeClientBufferANDROID");
        p_eglCreateImageKHR = (PFNEGLCREATEIMAGEKHRPROC)eglGetProcAddress("eglCreateImageKHR");
        p_eglDestroyImageKHR = (PFNEGLDESTROYIMAGEKHRPROC)eglGetProcAddress("eglDestroyImageKHR");
        p_glEGLImageTargetTexture2DOES = (PFNGLEGLIMAGETARGETTEXTURE2DOESPROC)eglGetProcAddress("glEGLImageTargetTexture2DOES");
        p_eglCreateSyncKHR = (PFNEGLCREATESYNCKHRPROC)eglGetProcAddress("eglCreateSyncKHR");
        p_eglWaitSyncKHR = (PFNEGLWAITSYNCKHRPROC)eglGetProcAddress("eglWaitSyncKHR");
        p_eglDestroySyncKHR = (PFNEGLDESTROYSYNCKHRPROC)eglGetProcAddress("eglDestroySyncKHR");
        loaded = p_eglGetNativeClientBufferANDROID && p_eglCreateImageKHR && p_eglDestroyImageKHR &&
                 p_glEGLImageTargetTexture2DOES && p_eglCreateSyncKHR && p_eglWaitSyncKHR &&
                 p_eglDestroySyncKHR ? 1 : -1;
        if (loaded < 0) LOG("EGL AHardwareBuffer / native fence entry points missing, direct transport off");
    }
    return loaded > 0;
}

static void release_buffer_gl(struct buffer* b)
{
    if (b->fbo) glDeleteFramebuffers(1, &b->fbo);
    if (b->texture) glDeleteTextures(1, &b->texture);
    if (b->image) p_eglDestroyImageKHR(eglGetCurrentDisplay(), b->image);
    AHardwareBuffer_release(b->buffer);
}

/* Drops buffers from connections that have ended. Called with g_lock held. */
static void collect_locked(void)
{
    int kept = 0;
    for (int i = 0; i < g_buffer_count; i++) {
        if (g_buffers[i].generation != g_generation) release_buffer_gl(&g_buffers[i]);
        else g_buffers[kept++] = g_buffers[i];
    }
    if (kept != g_buffer_count) g_shown_valid = false;
    g_buffer_count = kept;
}

/* The buffer for a view, with its texture and read framebuffer made on first use. */
static struct buffer* find_buffer(uint32_t slot, uint32_t layer)
{
    for (int i = g_buffer_count - 1; i >= 0; i--) {
        struct buffer* b = &g_buffers[i];
        if (b->slot != slot || b->layer != layer || b->generation != g_generation) continue;
        if (!b->fbo) {
            static const EGLint attrs[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
            EGLClientBuffer client = p_eglGetNativeClientBufferANDROID(b->buffer);
            b->image = p_eglCreateImageKHR(eglGetCurrentDisplay(), EGL_NO_CONTEXT,
                                           EGL_NATIVE_BUFFER_ANDROID, client, attrs);
            if (b->image == EGL_NO_IMAGE_KHR) {
                LOG("slot %u layer %u: eglCreateImageKHR failed 0x%x", slot, layer, eglGetError());
                return NULL;
            }
            glGenTextures(1, &b->texture);
            glBindTexture(GL_TEXTURE_2D, b->texture);
            p_glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, b->image);
            glBindTexture(GL_TEXTURE_2D, 0);
            glGenFramebuffers(1, &b->fbo);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, b->fbo);
            glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, b->texture, 0);
            if (glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                LOG("slot %u layer %u: framebuffer incomplete", slot, layer);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        }
        return b;
    }
    return NULL;
}

/* Makes the GPU, not this thread, wait for the bridge's copy. Takes the fd. */
static void gpu_wait(int fd)
{
    const EGLint attrs[] = { EGL_SYNC_NATIVE_FENCE_FD_ANDROID, fd, EGL_NONE };
    EGLDisplay display = eglGetCurrentDisplay();
    EGLSyncKHR sync = p_eglCreateSyncKHR(display, EGL_SYNC_NATIVE_FENCE_ANDROID, attrs);
    if (sync == EGL_NO_SYNC_KHR) {
        /* EGL only takes the fd on success: wait on the CPU instead, then drop it. */
        struct pollfd p = { fd, POLLIN, 0 };
        poll(&p, 1, 50);
        close(fd);
        return;
    }
    p_eglWaitSyncKHR(display, sync, 0);
    p_eglDestroySyncKHR(display, sync);
}

static bool ensure_eyes(XrSession session, int width, int height)
{
    /* An empty rect matches the unset size, so the swapchains would never be created and the
     * layer would then use them anyway - drop the frame instead */
    if (width <= 0 || height <= 0) return false;
    if (g_eye_width == width && g_eye_height == height) return true;
    for (int eye = 0; eye < WXR_DIRECT_MAX_VIEWS; eye++) {
        if (g_eye_width) XrFramebufferDestroy(&g_eyes[eye]);
        if (!XrFramebufferCreate(&g_eyes[eye], session, width, height)) {
            g_eye_width = g_eye_height = 0;
            return false;
        }
    }
    g_eye_width = width;
    g_eye_height = height;
    LOG("eye swapchains %dx%d", width, height);
    return true;
}

static bool ensure_quad(XrSession session, uint32_t q, int width, int height)
{
    if (width <= 0 || height <= 0) return false;
    if (g_quad_width[q] == width && g_quad_height[q] == height) return true;
    if (g_quad_width[q]) XrFramebufferDestroy(&g_quads[q]);
    g_quad_width[q] = g_quad_height[q] = 0;
    if (!XrFramebufferCreate(&g_quads[q], session, width, height)) return false;
    g_quad_width[q] = width;
    g_quad_height[q] = height;
    return true;
}

/* Waits for the bridge's copy (taking the fd), then copies rect of src into the whole of target. */
static void blit(struct XrFramebuffer* target, int width, int height, struct buffer* src, const int32_t* r, int fd, bool opaque, bool key)
{
    gpu_wait(fd);
    target->SwapchainIndex++;
    target->SwapchainIndex %= target->SwapchainLength;
    XrFramebufferAcquire(target);
    if (key && key_program()) {
        key_copy(width, height, src, r);
        XrFramebufferRelease(target);
        return;
    }
    glBindFramebuffer(GL_READ_FRAMEBUFFER, src->fbo);
    /* The buffer's rows run top-down, a GL swapchain image's bottom-up. */
    glBlitFramebuffer(r[0], r[1], r[0] + r[2], r[1] + r[3],
                      0, height, width, 0, GL_COLOR_BUFFER_BIT, GL_NEAREST);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
    if (opaque) {
        /* Games leave alpha 0 in their eyes (Beat Saber does), which would show passthrough through them */
        glColorMask(GL_FALSE, GL_FALSE, GL_FALSE, GL_TRUE);
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    }
    XrFramebufferRelease(target);
}

/* Copies a newly arrived frame into the eye and quad swapchains. Takes the fds. */
static void take_frame(XrSession session, struct wxr_direct_frame* frame, int* fds, int fd_count)
{
    struct buffer* src[WXR_DIRECT_MAX_FDS] = { 0 };
    uint32_t v, q, n = frame->view_count + frame->quad_count;
    bool ok = (int)n == fd_count && frame->view_count <= WXR_DIRECT_MAX_VIEWS &&
              frame->quad_count <= WXR_DIRECT_MAX_QUADS &&
              (frame->view_count == 0 || frame->view_count == WXR_DIRECT_MAX_VIEWS);

    for (v = 0; ok && v < frame->view_count; v++)
        ok = (src[v] = find_buffer(frame->views[v].slot, frame->views[v].layer)) != NULL;
    for (q = 0; ok && q < frame->quad_count; q++)
        ok = (src[frame->view_count + q] = find_buffer(frame->quads[q].slot, frame->quads[q].layer)) != NULL;
    /* A negative extent is a flipped image (inverted texture bounds): the blit flips it back */
    if (ok && frame->view_count)
        ok = ensure_eyes(session, abs(frame->views[0].rect[2]), abs(frame->views[0].rect[3]));
    for (q = 0; ok && q < frame->quad_count; q++)
        ok = ensure_quad(session, q, abs(frame->quads[q].rect[2]), abs(frame->quads[q].rect[3]));
    if (!ok) {
        for (int i = 0; i < fd_count; i++) close(fds[i]);
        return;
    }

    for (v = 0; v < frame->view_count; v++)
        blit(&g_eyes[v], g_eye_width, g_eye_height, src[v], frame->views[v].rect, fds[v], XrDirectOpaqueEyes,
             XrDirectOpaqueEyes && XrDirectKeyMode);
    for (q = 0; q < frame->quad_count; q++)
        blit(&g_quads[q], g_quad_width[q], g_quad_height[q], src[frame->view_count + q],
             frame->quads[q].rect, fds[frame->view_count + q], false, false);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);  /* the released images are not a place to draw */
    g_shown = *frame;
    g_shown_valid = true;
}

bool XrDirectBuildLayer(XrSession session, XrCompositionLayerProjection* layer,
                        XrCompositionLayerProjectionView* views,
                        XrCompositionLayerQuad* quads, int* quad_count)
{
    struct wxr_direct_frame frame;
    int fds[WXR_DIRECT_MAX_FDS];
    int fd_count = 0;
    bool fresh = false, recent;
    XrSpace space = XR_NULL_HANDLE;

    *quad_count = 0;
    if (!load_egl()) return false;

    /* Held through the copy: the socket thread appends to g_buffers under it. */
    pthread_mutex_lock(&g_lock);
    collect_locked();
    recent = g_frame_time > 0 && now_ms() - g_frame_time < FRAME_TIMEOUT_MS;
    if (g_has_frame) {
        frame = g_frame;
        fd_count = g_frame_fd_count;
        memcpy(fds, g_frame_fds, sizeof(int) * fd_count);
        g_frame_fd_count = 0;
        g_has_frame = false;
        fresh = true;
    }
    if (fresh) take_frame(session, &frame, fds, fd_count);
    pthread_mutex_unlock(&g_lock);

    if (!recent || !g_shown_valid) return false;

    for (uint32_t q = 0; q < g_shown.quad_count; q++) {
        const struct wxr_direct_quad* in = &g_shown.quads[q];
        XrCompositionLayerQuad* out = &quads[*quad_count];
        XrSpace quad_space = g_resolve_space ? g_resolve_space(in->space) : XR_NULL_HANDLE;
        if (quad_space == XR_NULL_HANDLE) {
            static bool logged;
            if (!logged) LOG("no session space for the game's quad space %llu", (unsigned long long)in->space);
            logged = true;
            continue;
        }
        memset(out, 0, sizeof(*out));
        out->type = XR_TYPE_COMPOSITION_LAYER_QUAD;
        out->layerFlags = in->flags;
        out->space = quad_space;
        out->eyeVisibility = (XrEyeVisibility)in->eye_visibility;
        out->subImage.swapchain = g_quads[q].Handle;
        out->subImage.imageRect.extent.width = g_quad_width[q];
        out->subImage.imageRect.extent.height = g_quad_height[q];
        memcpy(&out->pose.orientation, in->orientation, sizeof(in->orientation));
        memcpy(&out->pose.position, in->position, sizeof(in->position));
        out->size.width = in->size[0];
        out->size.height = in->size[1];
        (*quad_count)++;
    }

    memset(layer, 0, sizeof(*layer));
    if (!g_shown.view_count) return true;  /* quads only, as on a loading screen */

    space = g_resolve_space ? g_resolve_space(g_shown.space) : XR_NULL_HANDLE;
    if (space == XR_NULL_HANDLE) {
        static bool logged;
        if (!logged) LOG("no session space for the game's space %llu", (unsigned long long)g_shown.space);
        logged = true;
        return *quad_count > 0;
    }

    for (int v = 0; v < WXR_DIRECT_MAX_VIEWS; v++) {
        const struct wxr_direct_view* in = &g_shown.views[v];
        memset(&views[v], 0, sizeof(views[v]));
        views[v].type = XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW;
        memcpy(&views[v].pose.orientation, in->orientation, sizeof(in->orientation));
        memcpy(&views[v].pose.position, in->position, sizeof(in->position));
        memcpy(&views[v].fov, in->fov, sizeof(in->fov));
        views[v].subImage.swapchain = g_eyes[v].Handle;
        views[v].subImage.imageRect.extent.width = g_eye_width;
        views[v].subImage.imageRect.extent.height = g_eye_height;
        XrQuaternionfNormalize(&views[v].pose.orientation);
    }

    /* Opaque: the game's eye textures carry alpha 0 (Beat Saber's do). */
    memset(layer, 0, sizeof(*layer));
    layer->type = XR_TYPE_COMPOSITION_LAYER_PROJECTION;
    layer->space = space;
    layer->viewCount = WXR_DIRECT_MAX_VIEWS;
    layer->views = views;
    return true;
}

bool XrDirectIsActive(void)
{
    bool recent;
    pthread_mutex_lock(&g_lock);
    recent = g_frame_time > 0 && now_ms() - g_frame_time < FRAME_TIMEOUT_MS;
    pthread_mutex_unlock(&g_lock);
    return recent && g_shown_valid && g_resolve_space &&
           (!g_shown.view_count || g_resolve_space(g_shown.space) != XR_NULL_HANDLE);
}

float XrDirectFps(void)
{
    return XrDirectIsActive() ? g_fps : 0.0f;
}
