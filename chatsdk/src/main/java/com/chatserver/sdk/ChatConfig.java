package com.chatserver.sdk;

import androidx.annotation.NonNull;

/**
 * Everything the SDK needs to know about the server and who this device is. Built
 * once by the host and handed to {@link ChatSdk#init}.
 *
 * <p>{@code deviceKey} is the identity chat-node knows this user by (in guest mode,
 * the PPDR username). Two installs signed in with the same key are the same user on
 * two devices - the SDK adds its own per-install id so the server can tell them
 * apart and deliver to both.
 */
public final class ChatConfig {

    final String restBaseUrl;
    final String wsUrl;
    final String deviceKey;
    final String displayName;

    private ChatConfig(Builder b) {
        this.restBaseUrl = b.restBaseUrl.endsWith("/") ? b.restBaseUrl : b.restBaseUrl + "/";
        this.wsUrl = b.wsUrl;
        this.deviceKey = b.deviceKey;
        this.displayName = b.displayName;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String restBaseUrl;
        private String wsUrl;
        private String deviceKey;
        private String displayName;

        /** e.g. {@code https://host/chat/} - the trailing slash is added if missing; Retrofit refuses a base URL without one. */
        public Builder restBaseUrl(@NonNull String url) {
            this.restBaseUrl = url;
            return this;
        }

        /** e.g. {@code wss://host/chat/ws}. */
        public Builder wsUrl(@NonNull String url) {
            this.wsUrl = url;
            return this;
        }

        public Builder deviceKey(@NonNull String deviceKey) {
            this.deviceKey = deviceKey;
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public ChatConfig build() {
            if (restBaseUrl == null || wsUrl == null || deviceKey == null) {
                throw new IllegalArgumentException("restBaseUrl, wsUrl and deviceKey are required");
            }
            return new ChatConfig(this);
        }
    }
}
