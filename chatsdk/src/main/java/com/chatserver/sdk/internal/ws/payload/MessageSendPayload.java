package com.chatserver.sdk.internal.ws.payload;

public final class MessageSendPayload {
    public String clientMessageId;
    public String conversationId;
    public String type;
    public String body;

    public MessageSendPayload(String clientMessageId, String conversationId, String type, String body) {
        this.clientMessageId = clientMessageId;
        this.conversationId = conversationId;
        this.type = type;
        this.body = body;
    }
}
