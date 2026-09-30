package com.chatserver.sdk;

import com.chatserver.sdk.model.Message;

/**
 * Everything the server pushes, delivered on the main thread. Every method has an
 * empty default so a host overrides only what it cares about.
 */
public interface ChatListener {

    default void onConnected() {
    }

    default void onDisconnected(int code, String reason) {
    }

    /** A message from someone else arrived. */
    default void onMessage(Message message) {
    }

    /**
     * A message this device sent was accepted by the server. {@code message} now
     * carries the real id and sequence number; match it to the send by {@link
     * Message#clientMessageId}.
     */
    default void onMessageSent(Message message) {
    }
}
