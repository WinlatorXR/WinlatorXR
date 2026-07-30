package com.winlator.xr.api;

import android.util.Pair;

import androidx.annotation.NonNull;

import com.winlator.xr.XrActivity;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Scanner;

public class XrVersion06 extends XrVersion05 {

    private final ArrayList<Pair<Integer, Integer>> spaces = new ArrayList<>();

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
                synchronized (spaces) {
                    spaces.clear();
                    int count = sc.nextInt();
                    instance.clearLocateSpaces();
                    for (int i = 0; i < count; i++) {
                        int a = sc.nextInt();
                        int b = sc.nextInt();
                        spaces.add(new Pair<>(a, b));
                        instance.addLocateSpace(a, b);
                    }
                }
            }
            sc.close();
        }
    }

    @Override
    public String encode(@NonNull float[] axes, @NonNull boolean[] buttons, int clientIndex) {
        StringBuilder binary = new StringBuilder();
        for (boolean button : buttons) {
            binary.append(button ? "T" : "F");
        }

        XrActivity instance = XrActivity.getInstance();
        StringBuilder poses = new StringBuilder();
        synchronized (spaces) {
            poses.append(spaces.size()).append(" ");
            for (Pair<Integer, Integer> space : spaces) {
                poses.append(space.first).append(" ");
                poses.append(space.second).append(" ");
                float[] pose = instance.getPose(space.first, space.second);
                if (pose.length != 7) pose = new float[7];
                for (float f : pose) {
                    String str = String.format(Locale.US, "%.3f", f);
                    str = str.replaceAll("\\.000", "");
                    poses.append(str).append(" ");
                }
            }
        }
        return (MSG_CLIENT + clientIndex +
                " " + String.format(Locale.US, "%.1f", axes[ControllerAxis.L_THUMBSTICK_X.ordinal()]) +
                " " + String.format(Locale.US, "%.1f", axes[ControllerAxis.L_THUMBSTICK_Y.ordinal()]) +
                " " + String.format(Locale.US, "%.1f", axes[ControllerAxis.R_THUMBSTICK_X.ordinal()]) +
                " " + String.format(Locale.US, "%.1f", axes[ControllerAxis.R_THUMBSTICK_Y.ordinal()]) +
                " " + String.format(Locale.US, "%.4f", axes[ControllerAxis.HMD_IPD.ordinal()]) +
                " " + String.format(Locale.US, "%.2f", axes[ControllerAxis.HMD_FOVX.ordinal()]) +
                " " + String.format(Locale.US, "%.2f", axes[ControllerAxis.HMD_FOVY.ordinal()]) +
                " " + String.format(Locale.US, "%d", (int)axes[ControllerAxis.HMD_SYNC.ordinal()]) +
                " " + String.format(Locale.US, "%d", (int)axes[ControllerAxis.HMD_RECENTER.ordinal()]) +
                " " + binary + " " + poses);
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
