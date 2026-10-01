package com.chatserver.sdk.internal.net.dto;

import java.time.Instant;

public record ConversationSummaryResponse(
        String conversationId, String type, String createdBy, Instant createdAt, boolean muted, boolean archived,
        boolean pinned, String otherUserId, String otherUserContactIdentifier, String lastMessageId,
        String lastMessageBody, String lastMessageType, String lastMessageSenderId, Instant lastMessageAt,
        String lastMessageStatus, boolean lastMessageDeletedForEveryone, int unreadCount) {
}
