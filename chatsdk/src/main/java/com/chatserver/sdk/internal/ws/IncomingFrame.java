package com.chatserver.sdk.internal.ws;

import com.google.gson.JsonElement;

/** Inbound envelope; {@code payload} stays raw until {@code type} says what it is. */
public final class IncomingFrame {

    public String frameId;
    public String type;
    public String version;
    public String correlationId;
    public String sentAt;
    public JsonElement payload;
}
