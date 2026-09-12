package com.winlator.cmod.renderer.material;

/**
 * The BGR material with the texture's alpha kept rather than forced opaque.
 *
 * An X11 window's pixels carry whatever alpha the client left in them, which is why the
 * material this extends throws it away. A dialog is drawn by Android into a bitmap of its
 * own, so its alpha means what it says, and a panel with rounded corners needs the corners
 * to show what is behind them.
 */
public class BGRAMaterial extends BGRMaterial {
    @Override
    protected String getFragmentShader() {
        return
            "precision mediump float;\n" +

            "uniform sampler2D texture;\n" +
            "varying vec2 vUV;\n" +

            "void main() {\n" +
                "vec4 color = texture2D(texture, vUV);\n" +
                "gl_FragColor = vec4(color.bgr, color.a);\n" +
            "}"
        ;
    }
}
