package com.winlator.cmod.audio;

import com.winlator.cmod.xconnector.Client;
import com.winlator.cmod.xconnector.ConnectionHandler;


public class ALSAClientConnectionHandler implements ConnectionHandler {

    private final boolean reflectorMode;
    public ALSAClientConnectionHandler(boolean reflectorMode) {
        this.reflectorMode = reflectorMode;
    }

    @Override
    public void handleNewConnection(Client client) {
        client.createIOStreams();

        ALSAClient alsaClient = new ALSAClient();
        alsaClient.setReflectorMode(this.reflectorMode);
        client.setTag(alsaClient);
    }

    @Override
    public void handleConnectionShutdown(Client client) {
        ((ALSAClient)client.getTag()).release();
    }
}