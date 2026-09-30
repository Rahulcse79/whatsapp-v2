package com.whatsappv2.data.chat.net.dto

import com.google.gson.annotations.SerializedName

/**
 * `POST {origin}/services/app/v2/auth/login`.
 *
 * [username] and [password] are Base64 of `iv || AES/CFB/PKCS5Padding(...)`, not
 * plaintext — see [com.whatsappv2.data.chat.crypto.CoralCredentialCipher]. They are
 * `String` here because by this point they are ciphertext: wrapping them in `Secret` would
 * imply a `reveal()` somewhere between here and the socket, and there is none.
 *
 * The server 500s on a field it cannot decrypt, so a wrong key looks like an outage rather
 * than a wrong password. That is the whole reason `ChatAuthError.CryptoUnavailable` exists.
 */
internal data class LoginRequest(
    val username: String,
    val password: String,
    val deviceId: String,
)

/**
 * The platform's envelope, which every `/services/` endpoint returns.
 *
 * `RequestResponse` in the server: `{status, message, messageDetail, data}` where `status`
 * is `OK` or `ERROR`. **It is not the HTTP status.** A rejected login is `200 OK` carrying
 * `status: ERROR` as often as it is a `400`, so a client that branched only on the HTTP
 * code would report a wrong password as a success.
 */
internal data class CoralEnvelope<T>(
    val status: String?,
    val message: String?,
    val messageDetail: String?,
    val data: T?,
) {
    /** True when the platform itself says the call succeeded. */
    val isOk: Boolean get() = status.equals(STATUS_OK, ignoreCase = true)

    /** The most specific wording the server gave, for a banner. */
    val detail: String? get() = messageDetail?.takeIf { it.isNotBlank() } ?: message?.takeIf { it.isNotBlank() }

    private companion object {
        const val STATUS_OK = "OK"
    }
}

/**
 * The `data` of a successful login — `AuthenticationRequestResponseModel`, of which this
 * takes the fields this app has a use for.
 *
 * Gson leaves anything it is not told about alone, and the server sends about seventy
 * fields: menus, map centres, TURN credentials, branding. Naming only these seven is not
 * an oversight — a DTO that mirrored the whole model would be seventy fields to keep in
 * step with a server this app does not own, to read the four it actually needs.
 *
 * **There is no expiry field.** The model has none, so `ChatSession.expiresAtMs` is null
 * and the app assumes the token is good until a 401 says otherwise. Guessing a lifetime
 * would sign people out while their token still worked.
 */
internal data class LoginData(
    val token: String?,
    val refreshToken: String?,
    val userId: Int?,
    val userName: String?,
    val fullName: String?,
    val extension: String?,
    /**
     * The departments this user can see — and the answer to "where does `departmentList`
     * come from". It arrives here, at sign-in, not from an endpoint of its own.
     */
    val departmentList: List<DepartmentData>?,
)

/** One row of [LoginData.departmentList] — `ExtensionDepartmentModel`. */
internal data class DepartmentData(
    val id: Int?,
    val department: String?,
    val departmentDescription: String?,
)

/**
 * `POST {origin}/services/api/v2/uc/phoneBook/listByDepartment`, with `Authorization: Bearer`.
 *
 * ## [searchRequest] is sent and ignored
 *
 * `PhoneBookService.getAllByDepartment` reads **only** `departmentList`; it never looks at
 * the search request. It is still sent because the endpoint's body type requires it
 * (`SearchFilterRequest`) and omitting it would be a 400 — but no caller should expect
 * filtering or paging from it. Narrowing the directory is this app's job until the server
 * grows a search that works.
 */
internal data class PhoneBookRequest(
    val searchRequest: SearchRequest,
    val departmentList: List<String>,
)

/** `SearchRequest` on the server, with its own defaults. Sent whole because the type requires it. */
internal data class SearchRequest(
    val action: String? = null,
    val currentPage: Int = 0,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
    val sortDirection: String = "asc",
    val sortBy: String = "name",
    val search: String = "",
    val sortDataType: String = "string",
) {
    companion object {
        /** The server's own default. Matched rather than chosen, so behaviour does not change silently. */
        const val DEFAULT_PAGE_SIZE = 50
    }
}

/**
 * One directory row — `PhoneBookModel`.
 *
 * [offExtension] is the server's `offEXtn`, spelled as it spells it. `@SerializedName`
 * rather than a matching Kotlin name, because `offEXtn` is not a name anything else in
 * this codebase should have to read twice.
 */
internal data class PhoneBookRow(
    val id: Long?,
    val name: String?,
    val type: String?,
    val designation: String?,
    @SerializedName("offEXtn") val offExtension: String?,
    val userType: String?,
    val department: String?,
)
