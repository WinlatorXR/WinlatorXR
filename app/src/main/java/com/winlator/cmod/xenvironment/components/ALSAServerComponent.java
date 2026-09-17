package com.winlator.cmod.xenvironment.components;

import android.util.SparseArray;

import com.winlator.cmod.audio.ALSAClient;
import com.winlator.cmod.audio.ALSAClientConnectionHandler;
import com.winlator.cmod.audio.ALSARequestHandler;
import com.winlator.cmod.xconnector.Client;
import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xconnector.XConnectorEpoll;
import com.winlator.cmod.xenvironment.EnvironmentComponent;

public class ALSAServerComponent extends EnvironmentComponent {
    private XConnectorEpoll connector;
    private final UnixSocketConfig socketConfig;

    private final boolean reflectorMode;

    public ALSAServerComponent(UnixSocketConfig socketConfig, boolean reflectorMode) {
        this.socketConfig = socketConfig;
        this.reflectorMode = reflectorMode; // Store it
    }

    @Override
    public void start() {
        if (connector != null) return;
        ALSAClientConnectionHandler connectionHandler = new ALSAClientConnectionHandler(reflectorMode);
        connector = new XConnectorEpoll(socketConfig, connectionHandler, new ALSARequestHandler());
        connector.setMultithreadedClients(true);
        connector.start();
    }

    @Override
    public void stop() {
        if (connector != null) {
            connector.stop();
            connector = null;
        }
    }

    /**
     * This method is called from the Activity when an audio device change is detected.
     * It iterates through all active ALSA connections and notifies them.
     */
    public void notifyAudioDeviceChanged() {
        if (connector == null) {
            return;
        }

        // Use the new getter to access the list of clients
        SparseArray<Client> clients = connector.getConnectedClients();
        for (int i = 0; i < clients.size(); i++) {
            Client client = clients.valueAt(i);
            Object tag = client.getTag();
            // Check if the client's tag is an ALSAClient instance
            if (tag instanceof ALSAClient) {
                ((ALSAClient) tag).onAudioDeviceChanged();
            }
        }
    }
}

