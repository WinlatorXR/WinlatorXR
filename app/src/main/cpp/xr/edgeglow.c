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

#include "edgeglow.h"

#if XR_USE_GRAPHICS_API_OPENGL_ES
#include <GLES3/gl3.h>
#endif

#include <string.h>

#if XR_USE_GRAPHICS_API_OPENGL_ES

// A fullscreen triangle generated from gl_VertexID, so there is no vertex buffer and no
// attribute state to disturb. vUV runs 0..1 across the visible part of the target.
static const char* kVertexShader =
    "#version 300 es\n"
    "out vec2 vUV;\n"
    "void main() {\n"
    "    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));\n"
    "    vUV = p;\n"
    "    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n"
    "}\n";

static const char* kFragmentShader =
    "#version 300 es\n"
    "precision mediump float;\n"
    "uniform sampler2D uSource;\n"
    "uniform vec2 uSourceOffset;\n"
    "uniform vec2 uSourceScale;\n"
    "uniform float uSpread;\n"
    "uniform float uIntensity;\n"
    "uniform float uGain;\n"
    "uniform float uSaturation;\n"
    "uniform float uEncodeSrgb;\n"
    "in vec2 vUV;\n"
    "out vec4 fragColor;\n"
    "const int TAPS = 3;\n"
    "void main() {\n"
    // vUV covers the enlarged glow layer. Map it back onto the screen and clamp, so
    // everything outside the screen keeps bleeding the nearest edge colour outwards.
    "    vec2 screenUV = clamp((vUV - 0.5) * uSpread + 0.5, 0.0, 1.0);\n"
    // One bilinear tap of a large source into a 64x64 target aliases badly and makes the
    // glow crawl on fine detail, so average a patch instead. Sampling an sRGB texture
    // decodes to linear, so this average is in linear light, which is where it belongs.
    "    vec2 tapStep = uSourceScale * 0.035;\n"
    "    vec3 sum = vec3(0.0);\n"
    "    for (int y = -TAPS; y <= TAPS; y++) {\n"
    "        for (int x = -TAPS; x <= TAPS; x++) {\n"
    "            vec2 uv = clamp(screenUV + vec2(float(x), float(y)) * tapStep, 0.0, 1.0);\n"
    "            sum += texture(uSource, uSourceOffset + uv * uSourceScale).rgb;\n"
    "        }\n"
    "    }\n"
    "    vec3 colour = sum / float((2 * TAPS + 1) * (2 * TAPS + 1));\n"
    // Averaging a whole patch pulls everything towards mid grey, so lift it back out.
    "    float luma = dot(colour, vec3(0.2126, 0.7152, 0.0722));\n"
    "    colour = clamp(mix(vec3(luma), colour, uSaturation) * uGain, 0.0, 1.0);\n"
    // Hold full strength across the screen itself and fade only over the border the layer
    // adds around it, otherwise the glow is already half faded where it first appears.
    "    float inner = 1.0 / uSpread;\n"
    "    vec2 d = abs(vUV - 0.5) * 2.0;\n"
    "    float alpha = (1.0 - smoothstep(inner, 1.0, max(d.x, d.y))) * uIntensity;\n"
    // Composition layers take premultiplied alpha, and the compositor blends in linear, so
    // premultiply before encoding rather than after.
    "    vec3 premultiplied = colour * alpha;\n"
    // The swapchain is sRGB but framebuffer.c turns off sRGB-on-write where the driver lets
    // it, in which case nothing would encode this and the compositor's own decode would
    // darken the glow a second time. Encode here when that is the case.
    "    vec3 stored = (uEncodeSrgb > 0.5) ? pow(premultiplied, vec3(1.0 / 2.2)) : premultiplied;\n"
    "    fragColor = vec4(stored, alpha);\n"
    "}\n";


static GLuint CompileShader(GLenum type, const char* source)
{
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, NULL);
    glCompileShader(shader);

    GLint compiled = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled)
    {
        char log[1024] = {0};
        glGetShaderInfoLog(shader, sizeof(log) - 1, NULL, log);
        ALOGE("Edge glow shader failed to compile: %s", log);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

static GLuint LinkProgram(void)
{
    GLuint vertex = CompileShader(GL_VERTEX_SHADER, kVertexShader);
    if (vertex == 0) return 0;

    GLuint fragment = CompileShader(GL_FRAGMENT_SHADER, kFragmentShader);
    if (fragment == 0)
    {
        glDeleteShader(vertex);
        return 0;
    }

    GLuint program = glCreateProgram();
    glAttachShader(program, vertex);
    glAttachShader(program, fragment);
    glLinkProgram(program);

    GLint linked = GL_FALSE;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked)
    {
        char log[1024] = {0};
        glGetProgramInfoLog(program, sizeof(log) - 1, NULL, log);
        ALOGE("Edge glow program failed to link: %s", log);
        glDeleteProgram(program);
        program = 0;
    }

    // The program keeps them alive until it is deleted.
    glDetachShader(program, vertex);
    glDetachShader(program, fragment);
    glDeleteShader(vertex);
    glDeleteShader(fragment);
    return program;
}
#endif

bool XrEdgeGlowCreate(struct XrEdgeGlow* edge_glow, XrSession session)
{
    memset(edge_glow, 0, sizeof(struct XrEdgeGlow));

#if XR_USE_GRAPHICS_API_OPENGL_ES
    if (!XrFramebufferCreate(&edge_glow->Framebuffer, session, XrEdgeGlowSize, XrEdgeGlowSize))
    {
        ALOGE("Failed to create the edge glow swapchain");
        return false;
    }

    edge_glow->Program = LinkProgram();
    if (edge_glow->Program == 0)
    {
        XrFramebufferDestroy(&edge_glow->Framebuffer);
        return false;
    }

    edge_glow->LocSource = glGetUniformLocation(edge_glow->Program, "uSource");
    edge_glow->LocSourceOffset = glGetUniformLocation(edge_glow->Program, "uSourceOffset");
    edge_glow->LocSourceScale = glGetUniformLocation(edge_glow->Program, "uSourceScale");
    edge_glow->LocSpread = glGetUniformLocation(edge_glow->Program, "uSpread");
    edge_glow->LocIntensity = glGetUniformLocation(edge_glow->Program, "uIntensity");
    edge_glow->LocGain = glGetUniformLocation(edge_glow->Program, "uGain");
    edge_glow->LocSaturation = glGetUniformLocation(edge_glow->Program, "uSaturation");
    edge_glow->LocEncodeSrgb = glGetUniformLocation(edge_glow->Program, "uEncodeSrgb");

    // framebuffer.c disables sRGB-on-write, so where the driver honours that the shader has
    // to encode the glow itself; without it the compositor decodes a value that was never
    // encoded and everything but the brightest colours goes dark.
    const char* extensions = (const char*)glGetString(GL_EXTENSIONS);
    edge_glow->EncodeSrgb = (extensions != NULL) &&
                            (strstr(extensions, "GL_EXT_sRGB_write_control") != NULL);
    ALOGV("Edge glow encodes sRGB itself: %s", edge_glow->EncodeSrgb ? "yes" : "no");

    // An empty vertex array, purely so binding ours parks whatever the caller had bound.
    GL(glGenVertexArrays(1, &edge_glow->VertexArray));

    // A sampler object, so the filtering we need never has to be written onto the screen
    // texture itself - that one belongs to the runtime and the compositor also samples it.
    GL(glGenSamplers(1, &edge_glow->Sampler));
    GL(glSamplerParameteri(edge_glow->Sampler, GL_TEXTURE_MIN_FILTER, GL_LINEAR));
    GL(glSamplerParameteri(edge_glow->Sampler, GL_TEXTURE_MAG_FILTER, GL_LINEAR));
    GL(glSamplerParameteri(edge_glow->Sampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE));
    GL(glSamplerParameteri(edge_glow->Sampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE));

    edge_glow->Initialized = true;
    return true;
#else
    return false;
#endif
}

void XrEdgeGlowDestroy(struct XrEdgeGlow* edge_glow)
{
    if (!edge_glow->Initialized) return;

#if XR_USE_GRAPHICS_API_OPENGL_ES
    GL(glDeleteVertexArrays(1, &edge_glow->VertexArray));
    GL(glDeleteSamplers(1, &edge_glow->Sampler));
    GL(glDeleteProgram(edge_glow->Program));
    XrFramebufferDestroy(&edge_glow->Framebuffer);
#endif
    edge_glow->Initialized = false;
}

void XrEdgeGlowRender(struct XrEdgeGlow* edge_glow, unsigned int source_texture,
                       float offset_u, float offset_v, float scale_u, float scale_v,
                       float intensity)
{
    if (!edge_glow->Initialized) return;

#if XR_USE_GRAPHICS_API_OPENGL_ES
    // This runs inside a frame the Java renderer owns, so everything touched is put back.
    GLint prev_framebuffer = 0, prev_program = 0, prev_vertex_array = 0;
    GLint prev_active_texture = 0, prev_texture = 0;
    GLint prev_sampler = 0;
    GLint prev_viewport[4] = {}, prev_scissor[4] = {};
    GLfloat prev_clear_colour[4] = {};
    GLboolean prev_blend = glIsEnabled(GL_BLEND);
    GLboolean prev_depth = glIsEnabled(GL_DEPTH_TEST);
    GLboolean prev_scissor_test = glIsEnabled(GL_SCISSOR_TEST);
    GLboolean prev_cull = glIsEnabled(GL_CULL_FACE);
    GLboolean prev_stencil = glIsEnabled(GL_STENCIL_TEST);
    GL(glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prev_framebuffer));
    GL(glGetIntegerv(GL_CURRENT_PROGRAM, &prev_program));
    GL(glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &prev_vertex_array));
    GL(glGetIntegerv(GL_ACTIVE_TEXTURE, &prev_active_texture));
    // Texture and sampler bindings belong to a unit, so read the ones for unit 0 - the unit
    // this pass uses - rather than whichever unit happened to be active on entry.
    GL(glActiveTexture(GL_TEXTURE0));
    GL(glGetIntegerv(GL_TEXTURE_BINDING_2D, &prev_texture));
    GL(glGetIntegerv(GL_SAMPLER_BINDING, &prev_sampler));
    GL(glGetIntegerv(GL_VIEWPORT, prev_viewport));
    GL(glGetIntegerv(GL_SCISSOR_BOX, prev_scissor));
    GL(glGetFloatv(GL_COLOR_CLEAR_VALUE, prev_clear_colour));

    XrFramebufferAcquire(&edge_glow->Framebuffer);

    GL(glDisable(GL_BLEND));
    GL(glDisable(GL_DEPTH_TEST));
    GL(glDisable(GL_SCISSOR_TEST));
    GL(glDisable(GL_CULL_FACE));
    GL(glDisable(GL_STENCIL_TEST));

    GL(glUseProgram(edge_glow->Program));
    GL(glBindVertexArray(edge_glow->VertexArray));
    GL(glBindTexture(GL_TEXTURE_2D, source_texture));
    GL(glBindSampler(0, edge_glow->Sampler));

    GL(glUniform1i(edge_glow->LocSource, 0));
    GL(glUniform2f(edge_glow->LocSourceOffset, offset_u, offset_v));
    GL(glUniform2f(edge_glow->LocSourceScale, scale_u, scale_v));
    GL(glUniform1f(edge_glow->LocSpread, XrEdgeGlowSpread));
    GL(glUniform1f(edge_glow->LocIntensity, intensity));
    GL(glUniform1f(edge_glow->LocGain, XrEdgeGlowGain));
    GL(glUniform1f(edge_glow->LocSaturation, XrEdgeGlowSaturation));
    GL(glUniform1f(edge_glow->LocEncodeSrgb, edge_glow->EncodeSrgb ? 1.0f : 0.0f));

    GL(glDrawArrays(GL_TRIANGLES, 0, 3));

    XrFramebufferRelease(&edge_glow->Framebuffer);

    GL(glBindSampler(0, (GLuint)prev_sampler));
    GL(glBindTexture(GL_TEXTURE_2D, (GLuint)prev_texture));
    GL(glActiveTexture((GLenum)prev_active_texture));
    GL(glBindVertexArray((GLuint)prev_vertex_array));
    GL(glUseProgram((GLuint)prev_program));
    GL(glBindFramebuffer(GL_FRAMEBUFFER, (GLuint)prev_framebuffer));
    GL(glViewport(prev_viewport[0], prev_viewport[1], prev_viewport[2], prev_viewport[3]));
    GL(glScissor(prev_scissor[0], prev_scissor[1], prev_scissor[2], prev_scissor[3]));
    GL(glClearColor(prev_clear_colour[0], prev_clear_colour[1], prev_clear_colour[2],
                    prev_clear_colour[3]));
    if (prev_blend) GL(glEnable(GL_BLEND));
    if (prev_depth) GL(glEnable(GL_DEPTH_TEST));
    if (prev_scissor_test) GL(glEnable(GL_SCISSOR_TEST));
    if (prev_cull) GL(glEnable(GL_CULL_FACE));
    if (prev_stencil) GL(glEnable(GL_STENCIL_TEST));
#endif
}
