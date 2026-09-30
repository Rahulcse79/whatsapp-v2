package com.chatserver.sdk.internal.net;

import com.chatserver.sdk.internal.net.dto.ConversationIdResponse;
import com.chatserver.sdk.internal.net.dto.ConversationSummaryResponse;
import com.chatserver.sdk.internal.net.dto.CreateDirectConversationRequest;
import com.chatserver.sdk.internal.net.dto.MeResponse;
import com.chatserver.sdk.internal.net.dto.MessageResponse;

import java.util.List;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;

/**
 * The REST endpoints messaging needs. Every path is RELATIVE (no leading slash):
 * a leading slash makes Retrofit treat it as absolute and drop the base URL's
 * context path, so {@code /api/...} would go to the wrong place entirely.
 */
public interface ApiService {

    @GET("api/auth/me")
    Call<MeResponse> me();

    @GET("api/conversations")
    Call<List<ConversationSummaryResponse>> listConversations();

    @POST("api/conversations")
    Call<ConversationIdResponse> createOrGetDirectConversation(@Body CreateDirectConversationRequest request);

    /** Oldest first, strictly after {@code afterSequenceNumber}; the resync/history query. */
    @GET("api/conversations/{conversationId}/messages")
    Call<List<MessageResponse>> getMessageHistory(
            @Path("conversationId") String conversationId,
            @Query("afterSequenceNumber") Long afterSequenceNumber,
            @Query("limit") Integer limit);
}
