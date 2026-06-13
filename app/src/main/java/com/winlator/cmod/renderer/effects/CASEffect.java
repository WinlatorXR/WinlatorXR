package com.winlator.cmod.renderer.effects;

import com.winlator.cmod.renderer.material.ScreenMaterial;
import com.winlator.cmod.renderer.material.ShaderMaterial;

public class CASEffect extends Effect {
    private float sharpness = 0.0f;
    private float denoise = 0.0f;

    public CASEffect() {
        super();
    }

    @Override
    protected ShaderMaterial createMaterial() {
        return new CASMaterial();
    }

    public float getSharpness() {
        return sharpness;
    }

    public void setSharpness(float sharpness) {
        this.sharpness = sharpness;
    }

    public float getDenoise() {
        return denoise;
    }

    public void setDenoise(float denoise) {
        this.denoise = denoise;
    }

    private class CASMaterial extends ScreenMaterial {
        public CASMaterial() {
            super();
            setUniformNames("screenTexture", "resolution", "sharpness", "denoise");
        }

        @Override
        protected String getFragmentShader() {
            return String.join("\n",
                    "precision highp float;",
                    "uniform sampler2D screenTexture;",
                    "uniform vec2 resolution;",
                    "uniform float sharpness;",
                    "uniform float denoise;",

                    "void main() {",
                    "    vec2 uv = gl_FragCoord.xy / resolution;",
                    "    vec2 offset = 1.0 / resolution;",

                    "    vec3 a = texture2D(screenTexture, uv + vec2(-offset.x, -offset.y)).rgb;",
                    "    vec3 b = texture2D(screenTexture, uv + vec2(0.0, -offset.y)).rgb;",
                    "    vec3 c = texture2D(screenTexture, uv + vec2(offset.x, -offset.y)).rgb;",
                    "    vec3 d = texture2D(screenTexture, uv + vec2(-offset.x, 0.0)).rgb;",
                    "    vec3 e = texture2D(screenTexture, uv).rgb;",
                    "    vec3 f = texture2D(screenTexture, uv + vec2(offset.x, 0.0)).rgb;",
                    "    vec3 g = texture2D(screenTexture, uv + vec2(-offset.x, offset.y)).rgb;",
                    "    vec3 h = texture2D(screenTexture, uv + vec2(0.0, offset.y)).rgb;",
                    "    vec3 i = texture2D(screenTexture, uv + vec2(offset.x, offset.y)).rgb;",

                    "    float min_g = min(min(min(d.g, f.g), min(b.g, h.g)), e.g);",
                    "    float max_g = max(max(max(d.g, f.g), max(b.g, h.g)), e.g);",

                    "    float peak = 8.0 - 3.0 * sharpness;",
                    "    float w = -1.0 / peak;",

                    "    vec3 color = (d + f + b + h) * w + e;",
                    "    gl_FragColor = vec4(clamp(color / (1.0 + 4.0 * w), 0.0, 1.0), 1.0);",
                    "}"
            );
        }

        @Override
        public void use() {
            super.use();
            setUniformFloat("sharpness", sharpness);
            setUniformFloat("denoise", denoise);
        }
    }
}
