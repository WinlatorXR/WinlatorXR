package com.winlator.cmod.renderer.material;

import android.opengl.GLES20;

public class WindowMaterial extends ShaderMaterial {
    public WindowMaterial() {
        setUniformNames("xform", "viewSize", "texture", "keyMode", "keyThreshold");
    }

    /** Colour key (0 off, 1 green, 2 blue, 3 pink, 4 black): matching pixels get alpha 0. */
    public void setColourKey(int mode, float threshold) {
        use();
        GLES20.glUniform1i(getUniformLocation("keyMode"), mode);
        GLES20.glUniform1f(getUniformLocation("keyThreshold"), threshold);
    }

    @Override
    protected String getVertexShader() {
        return
            "uniform float xform[6];\n" +
            "uniform vec2 viewSize;\n" +
            "attribute vec2 position;\n" +
            "varying vec2 vUV;\n" +

            "void main() {\n" +
                "vUV = position;\n" +
                "vec2 transformedPos = applyXForm(position, xform);\n" +
                "gl_Position = vec4(2.0 * transformedPos.x / viewSize.x - 1.0, 1.0 - 2.0 * transformedPos.y / viewSize.y, 0.0, 1.0);\n" +
            "}"
        ;
    }

    @Override
    protected String getFragmentShader() {
        return
            "precision mediump float;\n" +

            "uniform sampler2D texture;\n" +
            "uniform int keyMode;\n" +
            "uniform float keyThreshold;\n" +
            "varying vec2 vUV;\n" +

            // Kept in step with KEY_ALPHA_GLSL in xr/direct.c
            "float keyAlpha(vec3 c) {\n" +
                "if (keyMode == 0) return 1.0;\n" +
                "float v = max(c.r, max(c.g, c.b));\n" +
                "if (keyMode == 4) return smoothstep(keyThreshold, keyThreshold + 0.04, v);\n" +
                "float d = v - min(c.r, min(c.g, c.b));\n" +
                "float s = v > 0.0 ? d / v : 0.0;\n" +
                "float h = 0.0;\n" +
                "if (d > 0.0) {\n" +
                    "if (v == c.r) h = mod((c.g - c.b) / d, 6.0);\n" +
                    "else if (v == c.g) h = (c.b - c.r) / d + 2.0;\n" +
                    "else h = (c.r - c.g) / d + 4.0;\n" +
                "}\n" +
                "float key = keyMode == 1 ? 2.0 : (keyMode == 2 ? 4.0 : 5.0);\n" +
                "float dh = abs(h - key) / 6.0;\n" +
                "dh = min(dh, 1.0 - dh);\n" +
                "float match = 1.0 - smoothstep(keyThreshold, keyThreshold + 0.03, dh);\n" +
                "return 1.0 - match * smoothstep(0.2, 0.3, s) * smoothstep(0.1, 0.2, v);\n" +
            "}\n" +

            "void main() {\n" +
                "vec3 color = texture2D(texture, vUV).rgb;\n" +
                "gl_FragColor = vec4(color, keyAlpha(color));\n" +
            "}"
        ;
    }
}
