package com.chatserver.sdk;

/** Result of a request to the server, delivered on the main thread. */
public interface ChatCallback<T> {

    void onSuccess(T result);

    void onError(ChatError error);
}
