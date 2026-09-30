package com.chatserver.sdk.model;

/** This device's own identity on the server. */
public final class User {

    public final String userId;
    public final String deviceId;

    public User(String userId, String deviceId) {
        this.userId = userId;
        this.deviceId = deviceId;
    }
}
