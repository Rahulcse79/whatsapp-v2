package com.chatserver.sdk.internal.ws.payload;

public final class MessagePushPayload {
    public String messageId;
    public String conversationId;
    public String senderId;
    public String type;
    public String body;
    public long sequenceNumber;
    public String serverTimestamp;
}
