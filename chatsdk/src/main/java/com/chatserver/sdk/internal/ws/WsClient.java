package com.chatserver.sdk.internal.ws;

import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.chatserver.sdk.internal.ws.payload.MessageAckPayload;
import com.chatserver.sdk.internal.ws.payload.MessagePushPayload;
import com.chatserver.sdk.internal.ws.payload.MessageSendPayload;
import com.chatserver.sdk.internal.ws.payload.SessionWelcomePayload;
import com.google.gson.Gson;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * One WebSocket to chat-node's guest {@code /ws} endpoint. Owns its own heartbeat
 * (at the interval the server announces in {@code session.welcome}) and reconnects
 * with exponential backoff on any unexpected close.
 *
 * <p>A dropped socket is not always a closed one: lose the network without a FIN
 * (Wi-Fi gone, NAT expired, network switched) and the socket stays "open" while
 * nothing arrives. Two guards cover that - OkHttp's own ping/pong, configured on
 * the client this is built with, and the inbound watchdog below, which cancels a
 * socket quiet for longer than a healthy one could be.
 */
public final class WsClient {

    private static final String TAG = "ChatSdk.Ws";
    private static final long INITIAL_BACKOFF_MS = 1000;
    private static final long MAX_BACKOFF_MS = 30_000;
    /** Past this many silent heartbeat intervals the socket is dead even if it claims otherwise. */
    private static final int SILENCE_TOLERANCE_INTERVALS = 3;
    /** How long a socket may be silent before {@link #connect()} treats it as dead rather than idle. */
    private static final long STALE_SOCKET_MS = 60_000;

    public interface Listener {
        void onConnected();

        void onDisconnected(int code, String reason);

        void onMessageAck(MessageAckPayload payload);

        void onMessagePush(MessagePushPayload payload);
    }

    private final OkHttpClient httpClient;
    private final String wsUrl;
    private final String deviceKey;
    private final String installId;
    private final String displayName;
    private final Listener listener;
    private final Gson gson = new Gson();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private volatile WebSocket webSocket;
    private ScheduledFuture<?> heartbeatTask;
    private final AtomicBoolean intentionallyClosed = new AtomicBoolean(false);
    private long currentBackoffMs = INITIAL_BACKOFF_MS;
    /** Last time anything arrived on the current socket - the liveness signal the watchdog reads. */
    private volatile long lastInboundAtMs;

    public WsClient(OkHttpClient httpClient, String wsUrl, String deviceKey, String installId,
            @Nullable String displayName, Listener listener) {
        this.httpClient = httpClient;
        this.wsUrl = wsUrl;
        this.deviceKey = deviceKey;
        this.installId = installId;
        this.displayName = displayName;
        this.listener = listener;
    }

    /**
     * Idempotent, and doubles as a recovery: a socket quiet past {@link
     * #STALE_SOCKET_MS} is cancelled so the failure path reconnects it. Safe to call
     * from every screen open.
     */
    public void connect() {
        intentionallyClosed.set(false);
        WebSocket current = webSocket;
        if (current == null) {
            openSocket();
            return;
        }
        long silentFor = SystemClock.elapsedRealtime() - lastInboundAtMs;
        if (lastInboundAtMs > 0 && silentFor < STALE_SOCKET_MS) {
            return;
        }
        // cancel(), not close(): a half-open socket never completes a closing
        // handshake. onFailure is where the reconnect gets scheduled.
        Log.i(TAG, "Socket quiet for " + silentFor + "ms - forcing a reconnect");
        current.cancel();
    }

    public void disconnect() {
        intentionallyClosed.set(true);
        cancelHeartbeat();
        WebSocket current = webSocket;
        if (current != null) {
            current.close(1000, "client disconnect");
        }
    }

    public boolean isOpen() {
        return webSocket != null && lastInboundAtMs > 0
                && SystemClock.elapsedRealtime() - lastInboundAtMs < STALE_SOCKET_MS;
    }

    // ---- outgoing ----

    public void sendMessage(String clientMessageId, String conversationId, String body) {
        send(Frame.of(FrameType.MESSAGE_SEND, new MessageSendPayload(clientMessageId, conversationId, "TEXT", body)));
    }

    private void send(Frame frame) {
        WebSocket current = webSocket;
        if (current != null) {
            current.send(gson.toJson(frame));
        }
    }

    // ---- lifecycle ----

    private void openSocket() {
        StringBuilder url = new StringBuilder(wsUrl)
                .append("?deviceKey=").append(urlEncode(deviceKey))
                .append("&deviceId=").append(urlEncode(installId));
        if (displayName != null) {
            url.append("&displayName=").append(urlEncode(displayName));
        }
        webSocket = httpClient.newWebSocket(new Request.Builder().url(url.toString()).build(), new SocketListener());
    }

    private void scheduleHeartbeat(long intervalMs) {
        cancelHeartbeat();
        lastInboundAtMs = SystemClock.elapsedRealtime();
        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            send(Frame.of(FrameType.HEARTBEAT_PING, null));
            long silentFor = SystemClock.elapsedRealtime() - lastInboundAtMs;
            if (silentFor > intervalMs * SILENCE_TOLERANCE_INTERVALS) {
                Log.w(TAG, "No traffic for " + silentFor + "ms - treating the socket as dead");
                WebSocket dead = webSocket;
                if (dead != null) {
                    dead.cancel();
                }
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void cancelHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
    }

    private void scheduleReconnect() {
        if (intentionallyClosed.get()) {
            return;
        }
        Log.i(TAG, "Reconnecting in " + currentBackoffMs + "ms");
        scheduler.schedule(this::openSocket, currentBackoffMs, TimeUnit.MILLISECONDS);
        currentBackoffMs = Math.min(currentBackoffMs * 2, MAX_BACKOFF_MS);
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    private final class SocketListener extends WebSocketListener {

        @Override
        public void onOpen(@NonNull WebSocket socket, @NonNull Response response) {
            Log.i(TAG, "WS open");
            currentBackoffMs = INITIAL_BACKOFF_MS;
            lastInboundAtMs = SystemClock.elapsedRealtime();
        }

        /** A socket already replaced must not cancel its successor's heartbeat or schedule a reconnect on top of it. */
        private boolean isStale(WebSocket source) {
            return webSocket != null && webSocket != source;
        }

        @Override
        public void onMessage(@NonNull WebSocket socket, @NonNull String text) {
            lastInboundAtMs = SystemClock.elapsedRealtime();
            IncomingFrame frame;
            try {
                frame = gson.fromJson(text, IncomingFrame.class);
            } catch (Exception e) {
                Log.w(TAG, "Malformed frame: " + e.getMessage());
                return;
            }
            if (frame == null || frame.type == null) {
                return;
            }
            switch (frame.type) {
                case FrameType.SESSION_WELCOME:
                    SessionWelcomePayload welcome = gson.fromJson(frame.payload, SessionWelcomePayload.class);
                    scheduleHeartbeat(welcome.heartbeatIntervalMs > 0 ? welcome.heartbeatIntervalMs : 30_000L);
                    listener.onConnected();
                    break;
                case FrameType.MESSAGE_ACK:
                    listener.onMessageAck(gson.fromJson(frame.payload, MessageAckPayload.class));
                    break;
                case FrameType.MESSAGE_PUSH:
                    listener.onMessagePush(gson.fromJson(frame.payload, MessagePushPayload.class));
                    break;
                case FrameType.HEARTBEAT_PONG:
                    break;
                default:
                    Log.d(TAG, "Unhandled frame type: " + frame.type);
            }
        }

        @Override
        public void onClosing(@NonNull WebSocket socket, int code, @NonNull String reason) {
            socket.close(code, reason);
        }

        @Override
        public void onClosed(@NonNull WebSocket socket, int code, @NonNull String reason) {
            Log.i(TAG, "WS closed code=" + code + " reason=" + reason);
            if (isStale(socket)) {
                return;
            }
            cancelHeartbeat();
            listener.onDisconnected(code, reason);
            scheduleReconnect();
        }

        @Override
        public void onFailure(@NonNull WebSocket socket, @NonNull Throwable t, @Nullable Response response) {
            Log.w(TAG, "WS failure: " + t.getMessage());
            if (isStale(socket)) {
                return;
            }
            cancelHeartbeat();
            listener.onDisconnected(-1, t.getMessage());
            scheduleReconnect();
        }
    }
}
