package com.winlator.xr.api;

import androidx.annotation.NonNull;

import com.winlator.xr.XrActivity;

import java.util.Locale;
import java.util.Scanner;

public class XrVersion06 extends XrVersion05 {

    @Override
    public void dataReceived(PortIntent intent, @NonNull String message) {
        super.dataReceived(intent, message);
        if (intent == PortIntent.HMD_STATE) {
            // Skip AppInput data
            XrActivity instance = XrActivity.getInstance();
            Scanner sc = new Scanner(message);
            for (int i = 0; i < input.length; i++) {
                if (!sc.hasNext()) return;
                sc.next();
            }

            // Process reference spaces
            if (sc.hasNext()) {
                int count = sc.nextInt();
                for (int i = 0; i < count; i++) {
                    int space = sc.nextInt();
                    int type = sc.nextInt();
                    Pose p = parsePose(sc);
                    instance.updateReferenceSpace(space, type, p.x, p.y, p.z, p.qx, p.qy, p.qz, p.qw);
                }
            }

            // Process action spaces
            if (sc.hasNext()) {
                int count = sc.nextInt();
                for (int i = 0; i < count; i++) {
                    int space = sc.nextInt();
                    int type = sc.nextInt();
                    int grip = sc.nextInt();
                    Pose p = parsePose(sc);
                    instance.updateActionSpace(space, type, grip, p.x, p.y, p.z, p.qx, p.qy, p.qz, p.qw);
                }
            }

            // Process locate spaces
            if (sc.hasNext()) {
                int count = sc.nextInt();
                instance.clearLocateSpaces();
                for (int i = 0; i < count; i++) {
                    int a = sc.nextInt();
                    int b = sc.nextInt();
                    instance.addLocateSpace(a, b);
                }
            }
            sc.close();
        }
    }

    @Override
    public String encode(@NonNull float[] axes, @NonNull boolean[] buttons, int clientIndex) {
        return super.encode(axes, buttons, clientIndex) +
                " " + String.format(Locale.US, "%.3f", axes[ControllerAxis.HMD_STAGE_QX.ordinal()]) +
                " " + String.format(Locale.US, "%.3f", axes[ControllerAxis.HMD_STAGE_QY.ordinal()]) +
                " " + String.format(Locale.US, "%.3f", axes[ControllerAxis.HMD_STAGE_QZ.ordinal()]) +
                " " + String.format(Locale.US, "%.3f", axes[ControllerAxis.HMD_STAGE_QW.ordinal()]) +
                " " + String.format(Locale.US, "%.3f", axes[ControllerAxis.HMD_STAGE_X.ordinal()]) +
                " " + String.format(Locale.US, "%.3f", axes[ControllerAxis.HMD_STAGE_Y.ordinal()]) +
                " " + String.format(Locale.US, "%.3f", axes[ControllerAxis.HMD_STAGE_Z.ordinal()]);
    }

    private Pose parsePose(Scanner sc) {
        Pose output = new Pose();
        output.x = sc.nextFloat();
        output.y = sc.nextFloat();
        output.z = sc.nextFloat();
        output.qx = sc.nextFloat();
        output.qy = sc.nextFloat();
        output.qz = sc.nextFloat();
        output.qw = sc.nextFloat();
        return output;
    }
}
