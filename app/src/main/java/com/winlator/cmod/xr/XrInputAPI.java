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

import com.winlator.cmod.xserver.XKeycode;

import java.io.File;
import java.io.FileInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;

public class XrInputAPI implements XrInputInterface, Runnable {

    private static final int BUFFER_SIZE = 1024;
    @SuppressLint("SdCardPath")
    private static final String PATH_API = "/data/data/com.winlator.cmod/files/imagefs/tmp/xr";
    @SuppressLint("SdCardPath")
    private static final String PATH_DEBUG = "/sdcard/Download/udp_debug";
    private static final String INPUT_VERSION_FILE = "input_version";

    private XrInputInterface impl = null;
    private boolean running = false;
    private final DatagramSocket socket = new DatagramSocket();

    private String debugIp = null;
    private final boolean debugMode;

    public XrInputAPI(boolean debugMode) throws Exception {
        //Ensure directory exists
        File dir = new File(PATH_API);
        if (!dir.exists()) {
            if (!dir.mkdirs()) {
                throw new Exception("Filesystem issue");
            }
        }

        //Set debug mode
        this.debugMode = debugMode;
    }

    public void dataReceived(@NonNull String message) {
        if (impl != null) {
            impl.dataReceived(message);
        }
    }

    public void consumeInputs() {
        if (impl != null) {
            impl.consumeInputs();
        }
    }

    public XKeycode keyFromString(@NonNull String idString) {
        if (impl != null) {
            return impl.keyFromString(idString);
        }

        return XKeycode.KEY_NONE;
    }

    public int getPortIn() {
        return impl != null ? impl.getPortIn() : 0;
    }

    public int[] getPortsOut() {
        return impl != null ? impl.getPortsOut() : new int[] {0};
    }

    @Override
    public void run() {
        byte[] buffer = new byte[BUFFER_SIZE];
        try (DatagramSocket socket = new DatagramSocket(getPortIn())) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            running = true;

            while (running) {
                socket.receive(packet);
                dataReceived(new String(buffer, 0, packet.getLength()));
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
            impl.consumeInputs();
            return;
        }

        //Check if the host requested XrAPI
        File file = new File(PATH_API, INPUT_VERSION_FILE);
        if (file.exists()) {
            try {
                //Get requested API version
                FileInputStream fis = new FileInputStream(file);
                Scanner sc = new Scanner(fis);
                String version = sc.nextLine();
                sc.close();
                fis.close();

                //Decide which implementation to use
                if (version.startsWith("0.1")) impl = new XrInputVersion01(new File(PATH_API));
            } catch (Exception e) {
                System.err.println("Error reading input_version file: " + e.getMessage());
            }
        }

        // Create UDP listener background thread
        Thread udpThread = new Thread(this);
        udpThread.setDaemon(true);
        udpThread.start();
    }
}
