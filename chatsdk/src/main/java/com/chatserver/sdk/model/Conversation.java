package com.chatserver.sdk.model;

/** One row of the conversation list, exactly as the server summarises it. */
public final class Conversation {

    public final String conversationId;
    /** DIRECT, GROUP or BROADCAST. */
    public final String type;
    public final long createdAtMs;
    public final boolean muted;
    public final boolean archived;
    public final boolean pinned;
    /** The other person, for a DIRECT conversation; null for GROUP. */
    public final String otherUserId;
    /** Their contact identifier, e.g. {@code guest-mcx5047@guest.local}; null for GROUP. */
    public final String otherUserContactIdentifier;
    public final String lastMessageId;
    public final String lastMessageBody;
    public final String lastMessageType;
    public final String lastMessageSenderId;
    public final long lastMessageAtMs;
    /** SENT/DELIVERED/READ when the last message is this user's own; null otherwise. */
    public final String lastMessageStatus;
    /** The last message was deleted for everyone - show a tombstone rather than {@link #lastMessageBody}. */
    public final boolean lastMessageDeletedForEveryone;
    public final int unreadCount;

    public Conversation(String conversationId, String type, long createdAtMs, boolean muted, boolean archived,
            boolean pinned, String otherUserId, String otherUserContactIdentifier, String lastMessageId,
            String lastMessageBody, String lastMessageType, String lastMessageSenderId, long lastMessageAtMs,
            String lastMessageStatus, boolean lastMessageDeletedForEveryone, int unreadCount) {
        this.conversationId = conversationId;
        this.type = type;
        this.createdAtMs = createdAtMs;
        this.muted = muted;
        this.archived = archived;
        this.pinned = pinned;
        this.otherUserId = otherUserId;
        this.otherUserContactIdentifier = otherUserContactIdentifier;
        this.lastMessageId = lastMessageId;
        this.lastMessageBody = lastMessageBody;
        this.lastMessageType = lastMessageType;
        this.lastMessageSenderId = lastMessageSenderId;
        this.lastMessageAtMs = lastMessageAtMs;
        this.lastMessageStatus = lastMessageStatus;
        this.lastMessageDeletedForEveryone = lastMessageDeletedForEveryone;
        this.unreadCount = unreadCount;
    }

    @Override
    public String toString() {
        return "Conversation{" + conversationId + " " + type + " unread=" + unreadCount + "}";
    }
}
