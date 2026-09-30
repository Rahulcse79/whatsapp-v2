package com.chatserver.sdk;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.chatserver.sdk.internal.Ulids;
import com.chatserver.sdk.internal.net.ApiService;
import com.chatserver.sdk.internal.net.IdentityInterceptor;
import com.chatserver.sdk.internal.net.InstantTypeAdapter;
import com.chatserver.sdk.internal.net.dto.ConversationIdResponse;
import com.chatserver.sdk.internal.net.dto.ConversationSummaryResponse;
import com.chatserver.sdk.internal.net.dto.CreateDirectConversationRequest;
import com.chatserver.sdk.internal.net.dto.MessageResponse;
import com.chatserver.sdk.internal.store.DeviceStore;
import com.chatserver.sdk.internal.ws.WsClient;
import com.chatserver.sdk.internal.ws.payload.MessageAckPayload;
import com.chatserver.sdk.internal.ws.payload.MessagePushPayload;
import com.chatserver.sdk.model.Conversation;
import com.chatserver.sdk.model.Message;
import com.chatserver.sdk.model.User;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;

import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * The whole SDK behind one object.
 *
 * <pre>
 * ChatSdk.init(context, ChatConfig.builder()
 *         .restBaseUrl("https://host/chat/")
 *         .wsUrl("wss://host/chat/ws")
 *         .deviceKey("mcx5047")
 *         .displayName("mcx5047")
 *         .build());
 * ChatSdk.get().addListener(myListener);
 * ChatSdk.get().connect();
 * ChatSdk.get().sendText(conversationId, "hello");
 * </pre>
 *
 * <p>Live events arrive through {@link ChatListener}; requests answer through
 * {@link ChatCallback}. Both are always delivered on the main thread.
 *
 * <p>What the SDK does on its own, so the host does not have to: keeps the socket
 * alive and reconnects it; and remembers, per conversation, how far history has
 * been read so {@link #getMessages} only fetches what is new.
 */
public final class ChatSdk {

    private static volatile ChatSdk instance;

    private final ChatConfig config;
    private final ApiService api;
    private final WsClient ws;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<ChatListener> listeners = new CopyOnWriteArraySet<>();
    private volatile boolean connected;
    private volatile String myUserId;

    /** Sent-but-unacknowledged messages, by client id, so an ack can be turned back into a full Message. */
    private final Map<String, Message> pending = new ConcurrentHashMap<>();
    /** Highest sequence number seen per conversation - the cursor {@link #getMessages} resumes from. */
    private final Map<String, Long> lastSequence = new ConcurrentHashMap<>();
    /** Messages held per conversation, merged from history and live pushes, ordered by sequence. */
    private final Map<String, Map<String, Message>> messagesByConversation = new ConcurrentHashMap<>();

    /** Must be called once, before anything else - typically from the host's {@code Application.onCreate}. */
    public static synchronized void init(@NonNull Context context, @NonNull ChatConfig config) {
        if (instance != null) {
            instance.ws.disconnect();
        }
        instance = new ChatSdk(context.getApplicationContext(), config);
    }

    public static ChatSdk get() {
        ChatSdk sdk = instance;
        if (sdk == null) {
            throw new IllegalStateException("ChatSdk.init(context, config) has not been called");
        }
        return sdk;
    }

    private ChatSdk(Context context, ChatConfig config) {
        this.config = config;
        String installId = new DeviceStore(context).installId();

        // Retrofit and the WebSocket use separate clients: the REST one carries the
        // identity headers, which do not belong on the socket, and the socket needs
        // OkHttp's own ping to detect a dead link.
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequestsPerHost(15);
        OkHttpClient restClient = new OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .addInterceptor(new IdentityInterceptor(config.deviceKey, installId, config.displayName))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
        Gson gson = new GsonBuilder().registerTypeAdapter(Instant.class, new InstantTypeAdapter()).create();
        this.api = new Retrofit.Builder()
                .baseUrl(config.restBaseUrl)
                .client(restClient)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
                .create(ApiService.class);

        OkHttpClient wsHttp = new OkHttpClient.Builder()
                // OkHttp's ping is what turns a silently dead connection into a
                // reconnect; the app-level heartbeat alone cannot, its writes just sit
                // in the send buffer of a socket that will never answer.
                .pingInterval(20, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .build();
        this.ws = new WsClient(wsHttp, config.wsUrl, config.deviceKey, installId, config.displayName, new WsEvents());
    }

    // ---- connection ----

    /** Opens the socket, or repairs one that has gone quiet. Safe to call as often as you like. */
    public void connect() {
        ws.connect();
    }

    public void disconnect() {
        ws.disconnect();
    }

    public boolean isConnected() {
        return connected;
    }

    public void addListener(@NonNull ChatListener listener) {
        listeners.add(listener);
    }

    public void removeListener(@NonNull ChatListener listener) {
        listeners.remove(listener);
    }

    /** This user's server id, once known - null until {@link #me} or the first connect has told us. */
    @Nullable
    public String myUserId() {
        return myUserId;
    }

    // ---- identity ----

    public void me(@NonNull ChatCallback<User> callback) {
        api.me().enqueue(adapt(callback, r -> {
            myUserId = r.userId();
            return new User(r.userId(), r.deviceId());
        }));
    }

    // ---- conversations ----

    public void getConversations(@NonNull ChatCallback<List<Conversation>> callback) {
        api.listConversations().enqueue(adapt(callback, list -> {
            List<Conversation> out = new ArrayList<>(list.size());
            for (ConversationSummaryResponse c : list) {
                out.add(toConversation(c));
            }
            return out;
        }));
    }

    /**
     * Finds or creates the 1:1 conversation with another user; answers with its id.
     *
     * @param otherUserId that user's server id, or - when the server runs in guest
     *                    mode - their device key, which the server resolves (and
     *                    provisions on first use) into a real identity.
     */
    public void openDirectConversation(@NonNull String otherUserId, @NonNull ChatCallback<String> callback) {
        api.createOrGetDirectConversation(new CreateDirectConversationRequest(otherUserId))
                .enqueue(adapt(callback, ConversationIdResponse::conversationId));
    }

    // ---- messages ----

    /**
     * Everything the SDK holds for this conversation - history fetched so far plus
     * live messages - after fetching whatever is newer than the last fetch, so
     * calling it on every screen open is cheap.
     */
    public void getMessages(@NonNull String conversationId, @NonNull ChatCallback<List<Message>> callback) {
        long after = lastSequence.getOrDefault(conversationId, 0L);
        api.getMessageHistory(conversationId, after, 200).enqueue(adapt(callback, list -> {
            for (MessageResponse m : list) {
                remember(toMessage(m));
            }
            return snapshot(conversationId);
        }));
    }

    /** What the SDK already holds for a conversation, without going to the server. */
    @NonNull
    public List<Message> cachedMessages(@NonNull String conversationId) {
        return snapshot(conversationId);
    }

    /**
     * Sends a text message. Returns the client message id straight away; the message
     * is held as {@link Message#pending} until the server's acknowledgement arrives
     * as {@link ChatListener#onMessageSent}.
     */
    @NonNull
    public String sendText(@NonNull String conversationId, @NonNull String text) {
        String clientMessageId = Ulids.next();
        Message optimistic = new Message(null, clientMessageId, conversationId, myUserId, "TEXT", text,
                0L, System.currentTimeMillis(), true);
        pending.put(clientMessageId, optimistic);
        ws.sendMessage(clientMessageId, conversationId, text);
        return clientMessageId;
    }

    // ---- live events ----

    private final class WsEvents implements WsClient.Listener {
        @Override
        public void onConnected() {
            connected = true;
            if (myUserId == null) {
                api.me().enqueue(adapt(ignored -> { }, r -> { myUserId = r.userId(); return null; }));
            }
            post(() -> { for (ChatListener l : listeners) l.onConnected(); });
        }

        @Override
        public void onDisconnected(int code, String reason) {
            connected = false;
            post(() -> { for (ChatListener l : listeners) l.onDisconnected(code, reason); });
        }

        @Override
        public void onMessageAck(MessageAckPayload ack) {
            Message sent = pending.remove(ack.clientMessageId);
            if (sent == null) {
                return;
            }
            Message confirmed = new Message(ack.messageId, ack.clientMessageId, sent.conversationId, sent.senderId,
                    sent.type, sent.body, ack.sequenceNumber, epochMs(ack.serverTimestamp, sent.createdAtMs), false);
            remember(confirmed);
            post(() -> { for (ChatListener l : listeners) l.onMessageSent(confirmed); });
        }

        @Override
        public void onMessagePush(MessagePushPayload push) {
            Message message = new Message(push.messageId, null, push.conversationId, push.senderId, push.type,
                    push.body, push.sequenceNumber, epochMs(push.serverTimestamp, System.currentTimeMillis()), false);
            remember(message);
            post(() -> { for (ChatListener l : listeners) l.onMessage(message); });
        }
    }

    // ---- local message store ----

    private void remember(Message m) {
        messagesByConversation.computeIfAbsent(m.conversationId, k -> new ConcurrentHashMap<>()).put(m.messageId, m);
        lastSequence.merge(m.conversationId, m.sequenceNumber, Math::max);
    }

    private List<Message> snapshot(String conversationId) {
        Map<String, Message> byId = messagesByConversation.get(conversationId);
        List<Message> out = byId != null ? new ArrayList<>(byId.values()) : new ArrayList<>();
        for (Message p : pending.values()) {
            if (conversationId.equals(p.conversationId)) {
                out.add(p);
            }
        }
        // Server-ordered first, then anything still pending (sequence 0) by send time.
        Collections.sort(out, (a, b) -> {
            if (a.sequenceNumber != b.sequenceNumber) {
                if (a.sequenceNumber == 0) return 1;
                if (b.sequenceNumber == 0) return -1;
                return Long.compare(a.sequenceNumber, b.sequenceNumber);
            }
            return Long.compare(a.createdAtMs, b.createdAtMs);
        });
        return out;
    }

    // ---- plumbing ----

    private static Message toMessage(MessageResponse m) {
        return new Message(m.messageId(), null, m.conversationId(), m.senderId(), m.type(), m.body(),
                m.sequenceNumber(), m.createdAt() != null ? m.createdAt().toEpochMilli() : 0L, false);
    }

    private static Conversation toConversation(ConversationSummaryResponse c) {
        return new Conversation(c.conversationId(), c.type(),
                c.createdAt() != null ? c.createdAt().toEpochMilli() : 0L, c.muted(), c.archived(), c.pinned(),
                c.otherUserId(), c.otherUserContactIdentifier(), c.lastMessageId(), c.lastMessageBody(),
                c.lastMessageType(), c.lastMessageSenderId(),
                c.lastMessageAt() != null ? c.lastMessageAt().toEpochMilli() : 0L, c.lastMessageStatus(),
                c.lastMessageDeletedForEveryone(), c.unreadCount());
    }

    private static long epochMs(String iso, long fallback) {
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String errorBody(Response<?> response) {
        try {
            return response.errorBody() != null ? response.errorBody().string() : response.message();
        } catch (Exception e) {
            return response.message();
        }
    }

    private void post(Runnable r) {
        main.post(r);
    }

    /** Wraps a Retrofit callback so the host only ever sees {@link ChatCallback} on the main thread. */
    private <R, T> Callback<R> adapt(ChatCallback<T> callback, java.util.function.Function<R, T> map) {
        return new Callback<R>() {
            @Override
            public void onResponse(Call<R> call, Response<R> response) {
                if (!response.isSuccessful() || response.body() == null) {
                    post(() -> callback.onError(new ChatError(response.code(), errorBody(response))));
                    return;
                }
                T mapped = map.apply(response.body());
                post(() -> callback.onSuccess(mapped));
            }

            @Override
            public void onFailure(Call<R> call, Throwable t) {
                post(() -> callback.onError(new ChatError(0, t.getMessage())));
            }
        };
    }

    private <R, T> Callback<R> adapt(java.util.function.Consumer<T> onSuccess, java.util.function.Function<R, T> map) {
        return adapt(new ChatCallback<T>() {
            @Override
            public void onSuccess(T result) {
                onSuccess.accept(result);
            }

            @Override
            public void onError(ChatError error) {
            }
        }, map);
    }
}
