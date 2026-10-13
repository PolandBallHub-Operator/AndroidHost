/*
 * Copyright (C) 2017 Genymobile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.genymobile.gnirehtet.relay;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Set;

public class Relay {
    private static final String TAG = Relay.class.getSimpleName();
    private static final int CLEANING_INTERVAL = 60 * 1000;
    private final int port;
    private final Runnable onStarted;
    private volatile boolean running;
    private volatile boolean stopRequested;
    private volatile Selector activeSelector;
    public Relay(int port) {
        this(port, null);
    }
    public Relay(int port, Runnable onStarted) {
        this.port = port;
        this.onStarted = onStarted;
    }
    public void run() throws IOException {
        // A Relay instance is created for each Host start.  Reset this state here as well as in
        // the constructor so a stop request racing with the previous service instance cannot
        // make the newly bound server leave its selector loop immediately.
        stopRequested = false;
        Selector selector = Selector.open();
        activeSelector = selector;
        running = true;
        TunnelServer tunnelServer = null;
        try {
            // The upstream relay binds loopback; adb reverse exposes this socket to the client.
            tunnelServer = new TunnelServer(port, selector);
            Log.i(TAG, "Relay server started");
            if (!stopRequested && onStarted != null) {
                onStarted.run();
            }
            long nextCleaningDeadline = System.currentTimeMillis() + UDPConnection.IDLE_TIMEOUT;
            while (running && !stopRequested) {
                long timeout = Math.max(0, nextCleaningDeadline - System.currentTimeMillis());
                selector.select(timeout);
                if (!running) {
                    break;
                }
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                long now = System.currentTimeMillis();
                if (now >= nextCleaningDeadline || selectedKeys.isEmpty()) {
                    tunnelServer.cleanUp();
                    nextCleaningDeadline = now + CLEANING_INTERVAL;
                }
                for (SelectionKey selectedKey : selectedKeys) {
                    SelectionHandler selectionHandler = (SelectionHandler) selectedKey.attachment();
                    selectionHandler.onReady(selectedKey);
                }
                selectedKeys.clear();
            }
            Log.w(TAG, "Relay loop exited running=" + running + " stopRequested=" + stopRequested);
        } finally {
            running = false;
            if (tunnelServer != null) {
                tunnelServer.close();
            }
            activeSelector = null;
            selector.close();
        }
    }
    public void stop() {
        Log.w(TAG, "Relay stop() called");
        stopRequested = true;
        running = false;
        Selector selector = activeSelector;
        if (selector != null) {
            selector.wakeup();
        }
    }
}
