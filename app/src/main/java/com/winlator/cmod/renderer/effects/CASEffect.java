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
                    "varying vec2 vUV;",

                    "void main() {",
                    "    vec2 uv = vUV;",
                    "    vec2 offset = 1.0 / resolution;",

                    "    vec4 e = texture2D(screenTexture, uv);",
                    "    vec3 b = texture2D(screenTexture, uv + vec2(0.0, -offset.y)).rgb;",
                    "    vec3 d = texture2D(screenTexture, uv + vec2(-offset.x, 0.0)).rgb;",
                    "    vec3 f = texture2D(screenTexture, uv + vec2(offset.x, 0.0)).rgb;",
                    "    vec3 h = texture2D(screenTexture, uv + vec2(0.0, offset.y)).rgb;",

                    "    float peak = 8.0 - 3.0 * sharpness;",
                    "    float w = -1.0 / peak;",

                    "    vec3 color = (d + f + b + h) * w + e.rgb;",
                    "    vec3 sharpened = clamp(color / (1.0 + 4.0 * w), 0.0, 1.0);",
                    "    gl_FragColor = vec4(mix(e.rgb, sharpened, 1.0 - denoise), e.a);",
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
