package com.chatserver.sdk.internal.ws.payload;

public final class MessageAckPayload {
    public String clientMessageId;
    public String messageId;
    public long sequenceNumber;
    public String serverTimestamp;
}
