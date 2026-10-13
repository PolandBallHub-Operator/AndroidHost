/*
 * Copyright (C) 2017 Genymobile
 * Licensed under the Apache License, Version 2.0.
 */
package com.genymobile.gnirehtet.relay;

import java.util.concurrent.atomic.AtomicLong;

/** Counts IPv4 stream bytes crossing the gnirehtet relay client socket. */
public final class RelayTrafficStats {
    private static final AtomicLong FROM_CLIENT = new AtomicLong();
    private static final AtomicLong TO_CLIENT = new AtomicLong();

    private RelayTrafficStats() { }

    public static void reset() {
        FROM_CLIENT.set(0);
        TO_CLIENT.set(0);
    }

    public static void addFromClient(long bytes) {
        FROM_CLIENT.addAndGet(bytes);
    }

    public static void addToClient(long bytes) {
        TO_CLIENT.addAndGet(bytes);
    }

    public static long getFromClient() {
        return FROM_CLIENT.get();
    }

    public static long getToClient() {
        return TO_CLIENT.get();
    }
}
