package com.chatserver.sdk.internal.net.dto;

import java.time.Instant;

public record MessageResponse(
        String messageId, String conversationId, String senderId, String type, String body, long sequenceNumber,
        Instant createdAt) {
}
