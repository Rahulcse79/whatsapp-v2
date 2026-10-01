package com.chatserver.sdk.internal.ws;

/**
 * Wire names of the frames this SDK sends and receives - the subset of chat-node's
 * catalogue that concerns messaging. Do not add a name here without a real handler
 * for it on the server.
 */
public final class FrameType {

    public static final String SESSION_WELCOME = "session.welcome";
    public static final String HEARTBEAT_PING = "heartbeat.ping";
    public static final String HEARTBEAT_PONG = "heartbeat.pong";
    public static final String MESSAGE_SEND = "message.send";
    public static final String MESSAGE_ACK = "message.ack";
    public static final String MESSAGE_PUSH = "message.push";

    private FrameType() {
    }
}
