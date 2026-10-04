package com.whatsappv2.data.chat.net

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * chat-node's group endpoints, which its own SDK does not expose.
 *
 * Every path is **relative** — a leading slash makes Retrofit treat it as absolute and drop
 * the base URL's `/chat/` context path, which is the same trap the SDK's own `ApiService`
 * documents.
 *
 * Established by probing the live server on 2 Oct 2026 rather than from any documentation;
 * see `ChatGroup` for the session of requests and what each one answered. Authentication is
 * the same guest-mode identity header the SDK sends — see [ChatIdentityInterceptor].
 */
internal interface ChatNodeApi {

    @GET("api/groups")
    suspend fun groups(): Response<List<GroupResponse>>

    @GET("api/groups/{id}/members")
    suspend fun members(@Path("id") id: String): Response<List<GroupMemberResponse>>

    @POST("api/groups")
    suspend fun createGroup(@Body request: CreateGroupRequest): Response<GroupResponse>

    @POST("api/groups/{id}/members")
    suspend fun addMember(
        @Path("id") id: String,
        @Body request: AddMemberRequest,
    ): Response<GroupMemberResponse>

    @DELETE("api/groups/{id}")
    suspend fun deleteGroup(@Path("id") id: String): Response<Unit>
}

/** `{"name": "..."}` — the only field chat-node requires, and an empty body is a 400. */
internal data class CreateGroupRequest(val name: String)

/**
 * `{"userId": "<ULID>"}`.
 *
 * The ULID, not the designation: `{"userId":"mcx8101"}` is accepted as a shape and then
 * fails with a 500 because there is nothing to resolve it to.
 */
internal data class AddMemberRequest(val userId: String)

internal data class GroupResponse(
    @SerializedName("conversationId") val conversationId: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("createdBy") val createdBy: String?,
)

internal data class GroupMemberResponse(
    @SerializedName("conversationId") val conversationId: String?,
    @SerializedName("userId") val userId: String?,
    /** `OWNER` or `MEMBER`. Anything else is read as a plain member. */
    @SerializedName("role") val role: String?,
    @SerializedName("assignedAt") val assignedAt: String?,
)
