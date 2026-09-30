package com.chatserver.sdk;

/** Why a request failed. {@code httpStatus} is 0 for a network-level failure (no response at all). */
public final class ChatError {

    public final int httpStatus;
    public final String message;

    ChatError(int httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    @Override
    public String toString() {
        return httpStatus == 0 ? "ChatError(" + message + ")" : "ChatError(HTTP " + httpStatus + ": " + message + ")";
    }
}
