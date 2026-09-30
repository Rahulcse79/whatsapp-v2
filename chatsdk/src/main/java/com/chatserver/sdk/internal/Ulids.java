package com.chatserver.sdk.internal;

import com.github.f4b6a3.ulid.UlidCreator;

public final class Ulids {

    private Ulids() {
    }

    public static String next() {
        return UlidCreator.getUlid().toString();
    }
}
