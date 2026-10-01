package com.chatserver.sdk.internal.net;

import androidx.annotation.NonNull;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/** The guest-auth headers chat-node reads when it runs with {@code auth-required=false}. */
public final class IdentityInterceptor implements Interceptor {

    private final String deviceKey;
    private final String installId;
    private final String displayName;

    public IdentityInterceptor(String deviceKey, String installId, String displayName) {
        this.deviceKey = deviceKey;
        this.installId = installId;
        this.displayName = displayName;
    }

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request.Builder builder = chain.request().newBuilder()
                .header("X-Device-Key", deviceKey)
                // Identifies this install, not this person - see DeviceStore.
                .header("X-Device-Id", installId);
        if (displayName != null) {
            builder.header("X-Display-Name", displayName);
        }
        return chain.proceed(builder.build());
    }
}
