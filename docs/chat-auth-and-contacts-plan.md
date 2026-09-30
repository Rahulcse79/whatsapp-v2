# Sign-in and contacts — implementation plan

**Supersedes** §6 of `docs/chat-login-and-contacts-brief.md` (what the API spec must contain),
which is now partly answered. The brief's requirements and decisions D1–D5 still stand.
**Status:** designed; seven items still block the wire layer (§2). Everything in §9's
"can start today" column is unblocked.
**Date:** 2026-09-30.

---

## 1. What the two endpoints tell us — verified, not assumed

Probed from this machine on 2026-09-30.

```
GET  https://gujlogin.coraltele.com/services/app/v2/auth/login
     → 405, Allow: POST                      (path is real; POST only)
POST https://gujlogin.coraltele.com/services/api/v2/uc/phoneBook/listByDepartment
     → 401 {"error":"Full authentication is required to access this resource"}
```

Four conclusions.

### 1.1 The `http://` in the spec is not a problem — use `https://`

Both paths answer **identically over strict TLS**, on the publicly-trusted Sectigo
certificate already verified for this host (`CN=*.coraltele.com`, valid to 2027-03-27).

This matters more than it looks. The app sets `usesCleartextTraffic="false"`, its
`network_security_config.xml` refuses cleartext in every build with no debug override, and
a CI step (*"Assert cleartext traffic is refused"*) fails the build if either is relaxed.
Had these endpoints been HTTPS-less, the integration would have needed either a policy
exception this repo explicitly refuses on principle or a server change.

**It needs neither.** Swap the scheme and everything else stands: no manifest change, no
network-security-config change, no CI gate touched, no new trust anchor. Any spec, ticket or
code review that carries `http://` should be corrected to `https://` at the point it is read.

### 1.2 This is a *second* backend, not the chat server

| | Base path | Error envelope |
|---|---|---|
| Coral UC platform | `/services/` | `{"error":"…"}` — Spring Security |
| chat-node | `/chat/` | `{"correlationId","code","retryable","message"}` |

Two services, one host, two different error models. The app must map both, and must not
assume a failure from one looks like a failure from the other.

### 1.3 The UC platform is bearer-token based

`Authorization: Bearer {token}` on the phonebook call, and a 401 without it. Confirmed.

### 1.4 The chat SDK still cannot use that token

`ChatSdk` builds its `OkHttpClient` privately; a host cannot add a header. So a successful
sign-in gives the app a token it **cannot hand to the chat SDK**. Whether chat works at all
therefore still turns on `/chat/`'s own auth model, which is a separate question from this
one and is unchanged: `docs/chat-sdk-integration.md` §10.1.

**This does not block sign-in or contacts.** Both are plain REST calls this app makes
itself. Build them; they stand on their own, and they are what the Chats tab needs before
it can show anything at all.

---

## 2. What is still missing — seven items, each a guess otherwise

The request shapes arrived; the rest did not. Every item below is one that will otherwise be
invented, and an invented field name is a shipped outage.

### Blocking sign-in

1. **The AES-128-CFB key material.** Not supplied, and nothing can be encrypted without it.
   Five sub-answers are all needed, because four of them are silent-failure modes — wrong
   and the server returns "bad credentials", never "wrong crypto":
   - the **key** (16 bytes) and how it reaches the app — literal hex, Base64, or a passphrase put through a KDF (and if a KDF: which, what salt, how many iterations);
   - the **IV** (16 bytes): fixed and shared, or random-per-message and prepended to the ciphertext before Base64?
   - the **CFB segment size**: `AES/CFB/NoPadding` in Java is **CFB128**; OpenSSL's `aes-128-cfb` is also CFB128 but `aes-128-cfb8` is not, and PHP/Node defaults differ. Which one does the server use?
   - the **Base64 variant**: standard or URL-safe, padded or not. (Android's `Base64.encodeToString` inserts newlines unless you pass `NO_WRAP` — a classic source of a 401 that looks like a wrong password.)
   - the **plaintext encoding** — UTF-8 assumed; confirm.
2. **The login response body.** Exact field names and types for: the token, its expiry, a
   refresh token if one exists, the user's id, and their display name.
3. **`deviceId` semantics.** The sample is `BF6625949EAA4D5F94CAA18641BE8E74` — 32 uppercase
   hex characters, 128 bits, no dashes. Is it generated once per install, or is it a fixed
   application identifier? Note it is **not** the format the chat SDK's own `DeviceStore`
   produces (`UUID.randomUUID().toString()` — lowercase, dashed), so the two are separate
   values and the app must not try to share one.
4. **401 mid-session.** Does the token expire, is there a refresh endpoint, and what should
   the app do — silently refresh, or drop to the sign-in screen?

### Blocking contacts

5. **Where `departmentList` comes from.** The sample hardcodes `["coral-test","another-dept"]`.
   Is there an endpoint that lists a user's departments, does the login response carry them,
   or is it configuration? An empty list — does it mean "all" or "none"?
6. **The `searchRequest` schema.** It is `{}` in the sample. What fields does it accept —
   a query string, paging, sorting? Is the result paged at all, and if so how?
7. **The phonebook response shape**, and specifically **which field is the id to pass to
   `ChatSdk.openDirectConversation`**. In this deployment's vocabulary `userId`,
   `contactIdentifier`, `deviceKey` and an extension number are four different things;
   choosing wrong fails when a conversation is opened, not when contacts are listed, so the
   bug surfaces a screen away from its cause.

**The most useful thing to send is one real request and one real response per endpoint,**
values redacted rather than invented. That answers 2, 5, 6 and 7 at once.

---

## 3. The URL model, revised

The brief said the user types one Server URL. With two backends on one host, the value to
store is the **origin**, and everything else derives:

```kotlin
@JvmInline
value class CoralServerUrl private constructor(val origin: String) {   // "https://gujlogin.coraltele.com"
    val servicesBaseUrl: String get() = "$origin/services/"            // Retrofit base — trailing slash required
    val chatRestBaseUrl: String get() = "$origin/chat/"
    val chatWsUrl:       String get() = origin.replaceFirst("https://", "wss://")
                                              .replaceFirst("http://",  "ws://") + "/chat/ws"

    companion object {
        val DEFAULT = CoralServerUrl("https://gujlogin.coraltele.com")
        fun parse(raw: String): Outcome<CoralServerUrl, ChatUrlViolation>
    }
}
```

This replaces `ChatServerUrl` from the integration plan §7.2, which stored `…/chat/`. Storing
the origin is better for three reasons: the user types a host rather than a path they would
have to get exactly right; one field cannot drift out of step with the other two; and the
`/services/` base can be derived at all, which the old shape could not express.

Validation is unchanged from integration plan §7.3 — `Blank`, `NotAbsolute`, `Cleartext`,
`UnsupportedScheme`, `Malformed` — with one addition: **normalise away a trailing slash and
any path the user pastes**, so someone who pastes the full login URL from this document still
gets a working origin rather than `…/services/app/v2/auth/login/services/`.

`Cleartext` keeps its own dedicated message and, given §1.1, should offer to switch a typed
`http://` to `https://` rather than only refusing — for this deployment that is always the
right correction.

---

## 4. Module and file plan

`:data:chat` owns both backends. One module, not two, because they are one feature from the
app's point of view — a chat identity, its directory, and its messages — and a second module
would add a boundary with no test seam behind it. The two HTTP stacks stay separate classes,
so if the phonebook is later wanted by the dialler it lifts out into `:data:directory`
cleanly: it is already behind its own port.

### 4.1 `:domain` — additions

`domain/src/main/kotlin/com/whatsappv2/domain/chat/`

| File | Contents |
|---|---|
| `CoralServerUrl.kt` | §3. Replaces `ChatServerUrl`. |
| `ChatSession.kt` | `data class ChatSession(userId: String, displayName: String?, token: Secret, expiresAtMs: Long?, deviceId: String)`. The token is a `Secret` — `core:common` already has the type, and a CI gate fails any build that interpolates a credential into a log line. |
| `ChatCredentials.kt` | `data class ChatCredentials(username: String, password: Secret)`. Transient; never persisted (decision D3). |
| `ChatContact.kt` | The directory row. **Not** `Contact` and **not** in `domain.contacts` — see §4.5. |
| `ChatDepartment.kt` | Whatever item 5 turns out to be. |
| `ChatAuthError.kt` | `InvalidCredentials \| SessionExpired \| Network \| Server(status, message) \| CryptoUnavailable \| NotConfigured`. `CryptoUnavailable` is its own case because a key that is missing or malformed must not be reported to the user as a wrong password — that is a bug report, not a retry. |

`domain/src/main/kotlin/com/whatsappv2/domain/repository/`

```kotlin
interface ChatSessionRepository {
    fun observeSession(): Flow<ChatSession?>            // null == signed out
    suspend fun currentSession(): ChatSession?
    suspend fun signIn(url: CoralServerUrl, credentials: ChatCredentials): Outcome<ChatSession, ChatAuthError>
    suspend fun signOut()
    /** The origin, whether or not anyone is signed in. Survives sign-out — decision D2. */
    fun observeServerUrl(): Flow<CoralServerUrl>
}

interface ChatContactRepository {
    fun observeContacts(): Flow<List<ChatContact>>
    suspend fun refresh(query: String? = null): Outcome<Unit, ChatAuthError>
}
```

Use cases: `ChatSignInUseCase`, `ChatSignOutUseCase`, `OpenContactConversationUseCase`.
Fixtures: `FakeChatSessionRepository`, `FakeChatContactRepository`, beside the existing seven.

### 4.2 `:data:chat` — the wire

| File | Responsibility |
|---|---|
| `net/CoralApi.kt` | Retrofit interface. `@POST("app/v2/auth/login")`, `@POST("api/v2/uc/phoneBook/listByDepartment")` — **relative paths**, against base `…/services/`. A leading slash would drop `/services/` exactly as the chat SDK's own `ApiService` warns. |
| `net/CoralClientFactory.kt` | Builds the `OkHttpClient` + Retrofit for a given origin. Rebuilt when the origin changes; nothing static. |
| `net/BearerInterceptor.kt` | Adds `Authorization: Bearer …` from the session holder. Skips the login call itself. |
| `net/CoralErrorMapper.kt` | `{"error":"…"}` + status → `ChatAuthError`. Kept apart from chat-node's mapper (§1.2). |
| `net/dto/` | `LoginRequest(username, password, deviceId)`, `LoginResponse(…)`, `PhoneBookRequest(searchRequest, departmentList)`, `PhoneBookResponse(…)`. Kotlin data classes with Gson; field names exactly as the server writes them. |
| `crypto/CoralCredentialCipher.kt` | §5. |
| `store/ChatSessionStore.kt` | Persists session + origin. |
| `ChatSessionRepositoryImpl.kt`, `ChatContactRepositoryImpl.kt` | The ports. Names end in `Impl` — architecture rule 4 matches on the name, so this is not stylistic. |
| `di/ChatDataModule.kt` | `@Binds`, `@Singleton`. |

`data/chat/build.gradle.kts` adds `libs.retrofit`, `libs.retrofit.converter.gson`,
`libs.gson`, `libs.okhttp` — all already in the catalog from integration-plan phase 0, since
the SDK needs the same four. Note they are **not** inherited from `:chatsdk`, whose
dependencies are `implementation`; they must be declared here too.

### 4.3 Where the session is stored

| Value | Store | Why |
|---|---|---|
| Server origin | DataStore, `coral_server_origin` | Not sensitive; must survive sign-out (D2). |
| `userId`, `displayName`, `deviceId`, expiry | DataStore | Not sensitive. |
| **Token** | **Encrypted**, through the same Keystore-backed cipher `:data:account` uses for SIP passwords | A bearer token is a credential. The settings store's own KDoc says nothing sensitive goes in it, and that rule is not bent for this. |
| **Password** | **Not stored** (D3) | If item 4's answer forces re-sending it, it goes in the encrypted store too — never DataStore. |

### 4.4 `:feature:chat` — screens

`ChatSignInScreen` (three fields, §2.1 of the brief), `ChatSignInViewModel`,
`ChatContactsScreen` + `ChatContactsViewModel`, and the FAB added to the Chats screen at the
bottom right. New destinations `chat-signin` and `chat-contacts`; neither `isTopLevel`, so
`AppNavHost`'s existing push transition applies with no change to its animation logic.

`SettingsScreen` gains a "Chat account" row: signed-in identity and a confirmed **Sign out**.

### 4.5 Keep the two contact models apart

Architecture rule 9 forbids any file holding a `com.whatsappv2.domain.contacts` type from
importing `okhttp3.`, `retrofit2.` or `java.net.` — because the device address book must not
leave the device. `ChatContact` lives in `com.whatsappv2.domain.chat` and is server data, so
nothing fires. **If anyone puts it in `domain.contacts` instead, the build breaks** — and
that is the rule doing its job, not a false positive to work around.

Integration plan §9.6 separately proposes adding `com.chatserver.sdk` to that rule's egress
list. With this change the same argument now applies to `:data:chat`'s own Retrofit — worth
doing in the same commit.

---

## 5. The credential cipher

`crypto/CoralCredentialCipher.kt` — one class, one job, and deliberately **not** in
`:core:common` or `:data:account/crypto`.

```kotlin
internal class CoralCredentialCipher(private val key: ByteArray, private val iv: ByteArray) {
    fun encrypt(plaintext: String): String =
        Cipher.getInstance(TRANSFORMATION)                 // pending item 1: CFB vs CFB8
            .apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
            .doFinal(plaintext.toByteArray(Charsets.UTF_8))
            .let { Base64.encodeToString(it, Base64.NO_WRAP) }   // NO_WRAP: the default inserts newlines
}
```

Three things to be explicit about.

**It is not the account cipher.** `:data:account/crypto` is Keystore-backed and protects data
**at rest** on this device; keys never leave the Keystore. This one is a shared key agreed
with a server, protecting a field **in transit**. Same primitive, unrelated purposes.
Putting this in that package would invite someone to use the wrong one.

**The key ships inside the APK.** That is inherent to the design, not a flaw introduced here,
and R8 does not change it — a determined reader of the APK can recover it. What that means
practically: this is obfuscation of the credential, not a second layer of transport security,
and the real protection is the TLS from §1.1. Worth one line in the KDoc so nobody later
reasons that "the password is encrypted" means it is safe over a plain socket. Nothing in
this plan depends on the key being secret; it is implemented as specified because it is the
server's contract.

**It never appears in a log.** Plaintext, key, IV and ciphertext are all out. CI already
fails any build that interpolates a credential into a log line, and the `Secret` type exists
to make it awkward to do by accident.

Key delivery, pending item 1: a `BuildConfig` field fed by a Gradle property, read from
`~/.gradle/gradle.properties` locally and from a secret in CI — exactly the pattern
`data/sip/build.gradle.kts` already uses for its integration-test credentials, and for the
same stated reason: *"a password is a credential; either one in git is a leak that outlives
the commit that removed it."* Never a literal in a committed source file.

---

## 6. Flows

**Sign-in**

```
ChatSignInScreen  (url, username, password)
   → CoralServerUrl.parse            → violation on the URL field, no request sent
   → ChatSignInUseCase
   → ChatSessionRepositoryImpl
        CoralCredentialCipher.encrypt(username), .encrypt(password)
        POST {origin}/services/app/v2/auth/login
             {"username": <b64>, "password": <b64>, "deviceId": <installId>}
   → 200  → persist session (token encrypted) + origin → Chats
   → 401  → ChatAuthError.InvalidCredentials     → onto the password field
   → 0    → ChatAuthError.Network                → banner + retry
```

**Contacts**

```
Chats screen, FAB bottom-right
   → ChatContactsScreen
   → ChatContactRepositoryImpl
        POST {origin}/services/api/v2/uc/phoneBook/listByDepartment
        Authorization: Bearer <token>
        {"searchRequest": {…}, "departmentList": [… item 5 …]}
   → rows → tap
   → OpenContactConversationUseCase
        ChatSdk.openDirectConversation(<… item 7 …>)   ← gated on chat-node's own auth
   → navigate to the thread
```

The last step is the one that depends on integration plan §10.1. Everything above it works
regardless, which is why contacts can be built and shipped before chat messaging does.

---

## 7. Error mapping

| From | To | Surface |
|---|---|---|
| HTTP 401 on login | `InvalidCredentials` | Password field |
| HTTP 401 on any other call | `SessionExpired` | Refresh if item 4 allows; otherwise sign-in screen |
| HTTP 4xx other | `Server(status, message)` | Banner, message from `{"error":…}` |
| HTTP 5xx | `Server(status, message)` | Banner + retry |
| `IOException` / timeout | `Network` | Banner + retry |
| Cipher init or key absent | `CryptoUnavailable` | "Sign-in is not configured in this build" — **never** "wrong password" |

---

## 8. Tests

| Level | What |
|---|---|
| `:domain` | `CoralServerUrl.parse` — every violation, the trailing-slash and pasted-path normalisation, and all three derived URLs. Use cases against the fakes. No Android, no network. |
| `:data:chat` — cipher | **Known-answer tests**, once item 1 lands: a fixed key, IV and plaintext against a ciphertext the server team produced. This is the only test that proves CFB mode, Base64 variant and charset simultaneously, and without it the first real failure is an unexplained 401. |
| `:data:chat` — auth | MockWebServer: 200 → session persisted, token encrypted; 401 → `InvalidCredentials`; malformed JSON → `Server`; no response → `Network`. Assert the request body carries **encrypted** fields, and that `Authorization` is absent on login and present on the phonebook call. |
| `:data:chat` — session | Turbine: sign-out clears the session and the token but **keeps** the origin (D2). |
| `:feature:chat` (Robolectric + Compose) | Submit disabled until all three fields parse; one in-flight request; each error lands on the right field; contacts loading/empty/error/retry; FAB present and navigating. |
| `:test:arch` | Rule 13 finds nothing; the planted fixture still fails. |
| Manual | Sign in against the real server, list contacts, sign out, re-sign-in shows two fields with the URL kept, aeroplane mode gives `Network` not `InvalidCredentials`. |

`MockWebServer` is a new test dependency (`com.squareup.okhttp3:mockwebserver`) and needs a
catalog entry at the okhttp version already pinned.

---

## 9. What can start today

| Unblocked now | Waiting |
|---|---|
| `CoralServerUrl` + validation + tests | Cipher implementation (item 1) |
| `ChatSession`, `ChatCredentials`, `ChatAuthError`, ports, fakes | Login DTOs (item 2) |
| `ChatSignInScreen` + ViewModel against `FakeChatSessionRepository` | `deviceId` generation (item 3) |
| Session store, encrypted-token path, DataStore origin | Session expiry handling (item 4) |
| Settings "Chat account" row and sign-out | `departmentList` source (item 5) |
| Navigation, the FAB, contacts screen scaffolding + states | `searchRequest` schema (item 6) |
| `:data:chat` module skeleton, DI, client factory | `ChatContact` fields and the conversation id (item 7) |

That is most of two phases. The whole of `:domain`, the whole of `:feature:chat`'s sign-in
surface, and the storage layer can be written and fully tested against fakes before a single
byte goes over the wire — which is the point of the port/adapter split, and the reason the
missing items are an inconvenience rather than a stoppage.
