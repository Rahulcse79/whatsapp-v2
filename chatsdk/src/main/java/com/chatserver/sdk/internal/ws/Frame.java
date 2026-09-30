package com.chatserver.sdk.internal.ws;

import com.chatserver.sdk.internal.Ulids;

import java.time.Instant;

/**
 * Outgoing envelope - mirrors chat-node's {@code platform.websocket.Frame}. {@code
 * correlationId} must be a real ULID: the server force-parses it as one and
 * silently drops the frame otherwise, with no error back.
 */
public final class Frame {

    public static final String PROTOCOL_VERSION = "1.0";

    public final String frameId;
    public final String type;
    public final String version;
    public final String correlationId;
    public final String sentAt;
    public final Object payload;

    private Frame(String type, Object payload) {
        this.frameId = Ulids.next();
        this.type = type;
        this.version = PROTOCOL_VERSION;
        this.correlationId = Ulids.next();
        this.sentAt = Instant.now().toString();
        this.payload = payload;
    }

    public static Frame of(String type, Object payload) {
        return new Frame(type, payload);
    }
}
