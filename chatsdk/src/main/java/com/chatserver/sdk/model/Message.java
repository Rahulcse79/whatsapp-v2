package com.chatserver.sdk.model;

/**
 * One message, as the host sees it. Immutable - the SDK hands out fresh instances
 * as state changes (sent) rather than mutating ones it has already given away.
 */
public final class Message {

    /** Server-assigned id. Null while a message this device sent is still {@link #pending}. */
    public final String messageId;
    /** Set by the sender's device; the only stable handle across a send and its acknowledgement. */
    public final String clientMessageId;
    public final String conversationId;
    public final String senderId;
    /** TEXT, IMAGE, VIDEO, AUDIO, DOCUMENT or SYSTEM - chat-node's message types. */
    public final String type;
    public final String body;
    /** Ordering within the conversation. 0 while pending. */
    public final long sequenceNumber;
    /** Epoch millis of the server's timestamp, or the local send time while pending. */
    public final long createdAtMs;
    /** Sent by this device but not yet acknowledged by the server. */
    public final boolean pending;

    public Message(String messageId, String clientMessageId, String conversationId, String senderId, String type,
            String body, long sequenceNumber, long createdAtMs, boolean pending) {
        this.messageId = messageId;
        this.clientMessageId = clientMessageId;
        this.conversationId = conversationId;
        this.senderId = senderId;
        this.type = type;
        this.body = body;
        this.sequenceNumber = sequenceNumber;
        this.createdAtMs = createdAtMs;
        this.pending = pending;
    }

    public boolean isMine(String myUserId) {
        return myUserId != null && myUserId.equals(senderId);
    }

    @Override
    public String toString() {
        return "Message{" + (messageId != null ? messageId : "pending:" + clientMessageId)
                + " seq=" + sequenceNumber + " " + type + (body != null ? " '" + body + "'" : "") + "}";
    }
}
