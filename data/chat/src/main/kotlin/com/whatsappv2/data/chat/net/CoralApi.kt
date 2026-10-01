package com.whatsappv2.data.chat.net

import com.whatsappv2.data.chat.net.dto.CoralEnvelope
import com.whatsappv2.data.chat.net.dto.LoginData
import com.whatsappv2.data.chat.net.dto.LoginRequest
import com.whatsappv2.data.chat.net.dto.PhoneBookRequest
import com.whatsappv2.data.chat.net.dto.PhoneBookRow
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * The Coral UC platform, at base `{origin}/services/`.
 *
 * ## Every path is relative, and that is not a style choice
 *
 * A leading slash on a Retrofit path **replaces** the base URL's path, so `"/app/v2/…"`
 * would silently drop `/services/` and produce `{origin}/app/v2/…` — a 404 at runtime with
 * nothing in the code that looks wrong. The chat SDK's own `ApiService` carries the same
 * warning for the same reason.
 *
 * ## `Response<…>` rather than a bare body, everywhere
 *
 * Both answers matter and they are different questions. The HTTP status says whether the
 * request was allowed — a 401 on the phonebook means the token aged out. The envelope's
 * own `status` says whether the operation worked — a rejected login arrives as `200 OK`
 * with `status: ERROR` in the body. A client reading only one of the two reports the other
 * kind of failure as a success.
 */
internal interface CoralApi {

    @POST("app/v2/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<CoralEnvelope<LoginData>>

    @POST("api/v2/uc/phoneBook/listByDepartment")
    suspend fun listByDepartment(
        @Body request: PhoneBookRequest,
    ): Response<CoralEnvelope<List<PhoneBookRow>>>
}
