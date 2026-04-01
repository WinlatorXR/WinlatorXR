/*
 * Copyright (C) 2024-2026 WinlatorXR
 *
 * This file is part of WinlatorXR.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.winlator.cmod.xr;

import androidx.annotation.NonNull;
import android.annotation.SuppressLint;
import android.os.Build;

import com.winlator.cmod.xserver.XServer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Scanner;

public class XrAPI implements XrInterface, Runnable {

    private static final int BUFFER_SIZE = 1024;
    @SuppressLint("SdCardPath")
    private static final String PATH_API = "/data/data/com.winlator.cmod/files/imagefs/tmp/xr";
    @SuppressLint("SdCardPath")
    private static final String PATH_DEBUG = "/sdcard/Download/udp_debug";
    private static final String SYSTEM_FILE = "system";
    private static final String VERSION_FILE = "version";

    private XrInterface impl = null;
    private boolean running = false;
    private final DatagramSocket socket = new DatagramSocket();

    private String debugIp = null;
    private final boolean debugMode;

    public XrAPI(boolean debugMode) throws Exception {
        //Ensure directory exists
        File dir = new File(PATH_API);
        if (!dir.exists()) {
            if (!dir.mkdirs()) {
                throw new Exception("Filesystem issue");
            }
        }

        //Ensure there are no previous data
        for (File file : Objects.requireNonNull(dir.listFiles())) {
            if (!file.delete()) {
                throw new Exception("Filesystem issue");
            }
        }

        //Write system info
        String info = "";
        info += Build.MANUFACTURER.toUpperCase() + "\n";
        info += Build.PRODUCT.toUpperCase() + "\n";
        info += Build.VERSION.RELEASE.toUpperCase() + "\n";
        info += Build.VERSION.SECURITY_PATCH.toUpperCase() + "\n";
        FileOutputStream fos = new FileOutputStream(new File(dir, SYSTEM_FILE));
        fos.write(info.getBytes(StandardCharsets.US_ASCII));
        fos.close();

        //Set debug mode
        this.debugMode = debugMode;
    }

    public void consumeInputs(XServer xServer) {
        if (impl != null) {
            impl.consumeInputs(xServer);
        }
    }

    public void dataReceived(PortIntent intent, @NonNull String message) {
        if (impl != null) {
            impl.dataReceived(intent, message);
        }
    }

    public String encode(@NonNull float[] axes, @NonNull boolean[] buttons, int clientIndex) {
        return impl != null ? impl.encode(axes, buttons, clientIndex) : "";
    }

    public String getFlags() {
        return impl != null ? impl.getFlags() : "";
    }

    public int getPortIn(PortIntent intent) {
        return impl != null ? impl.getPortIn(intent) : 0;
    }

    public int[] getPortsOut() {
        return impl != null ? impl.getPortsOut() : new int[] {0};
    }

    public float getValue(@NonNull AppInput index) {
        return impl != null ? impl.getValue(index) : 0.0f;
    }

    public int getIntValue(@NonNull AppInput index) {
        return (int)getValue(index);
    }

    public void setValue(@NonNull AppInput index, float value) {
        if (impl != null) {
            impl.setValue(index, value);
        }
    }

    @Override
    public void run() {
        int size = PortIntent.values().length;
        ArrayList<byte[]> buffer = new ArrayList<>();
        DatagramSocket[] socket = new DatagramSocket[size];
        DatagramPacket[] packet = new DatagramPacket[size];
        for (PortIntent intent : PortIntent.values()) {
            int port = getPortIn(intent);
            int index = intent.ordinal();
            try {
                buffer.add(new byte[BUFFER_SIZE]);
                if (port > 0) {
                    socket[index] = new DatagramSocket(port);
                    packet[index] = new DatagramPacket(buffer.get(index), buffer.get(index).length);
                }
            } catch (Exception e) {
                System.err.println("Error listening for UDP packets: " + e.getMessage());
                socket[index] = null;
                packet[index] = null;
            }
            running = true;
        }

        try {
            while (running) {
                for (PortIntent intent : PortIntent.values()) {
                    int index = intent.ordinal();
                    if ((socket[index] != null) && (packet[index] != null)) {
                        socket[index].receive(packet[index]);
                        dataReceived(intent, new String(buffer.get(index), 0, packet[index].getLength()));
                    }
                }
                Thread.sleep(10);
            }
        } catch (Exception e) {
            System.err.println("Error listening for UDP packets: " + e.getMessage());
        }
    }

    public void send(@NonNull byte[] bytes) throws Exception {
        //Send data to localhost
        InetAddress address = InetAddress.getLocalHost();
        for (int port : getPortsOut()) {
            socket.send(new DatagramPacket(bytes, bytes.length, address, port));
        }

        if (debugMode) {
            //Get requested IP from the filesystem
            if (debugIp == null) {
                debugIp = "";
                File debugDir = new File(PATH_DEBUG);
                if (debugDir.exists()) {
                    for (File file : Objects.requireNonNull(debugDir.listFiles())) {
                        debugIp = file.getName();
                        break;
                    }
                }
            }

            //Send the data over the network
            if (!debugIp.isEmpty()) {
                InetAddress debugIPAdd = InetAddress.getByName(debugIp);
                for (int port : getPortsOut()) {
                    socket.send(new DatagramPacket(bytes, bytes.length, debugIPAdd, port));
                }
            }
        }
    }

    public void stop() {
        running = false;
    }

    public void updateImplementation() {
        if (impl != null) {
            return;
        }

        //Check if the host requested XrAPI
        File file = new File(PATH_API, VERSION_FILE);
        if (file.exists()) {
            try {
                //Get requested API version
                FileInputStream fis = new FileInputStream(file);
                Scanner sc = new Scanner(fis);
                String version = sc.nextLine();
                sc.close();
                fis.close();

                //Decide which implementation to use
                if (version.startsWith("0.1")) impl = new XrVersion01(new File(PATH_API));
                if (version.startsWith("0.2")) impl = new XrVersion02();
                if (version.startsWith("0.3")) impl = new XrVersion03();
                if (version.startsWith("0.4")) impl = new XrVersion04();
                if (version.startsWith("0.5")) impl = new XrVersion05();
            } catch (Exception e) {
                System.err.println("Error reading version file: " + e.getMessage());
            }
        }

        // Create UDP listener background thread
        if (impl != null) {
            Thread udpThread = new Thread(this);
            udpThread.setDaemon(true);
            udpThread.start();
        }
    }
}
