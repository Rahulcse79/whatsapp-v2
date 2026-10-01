# Integrating `chatsdk` into whatsapp-v2

**Status:** plan, not yet implemented. Branch `feat/chat-sdk-integration-plan`.
**Scope:** bring `com.chatserver.sdk` (the chat-node Android SDK) in behind the existing
`AppDestination.CHATS` tab, without changing one line of the SDK's Java.
**Date:** 2026-09-30.

---

## 0. Decisions already taken

| Question | Decision |
|---|---|
| How the SDK enters the build | Gradle **source module** `:chatsdk`. Only `chatsdk/build.gradle` is rewritten as `build.gradle.kts` against the version catalog. **No Java source is touched.** |
| The SDK's own git history | Nested `chatsdk/.git` (remote `sameergupta12281998/chatSDK`) is **removed**; the sources are vendored as ordinary tracked files in this repository. |
| UI scope | Full chat behind the tab — conversation list **and** message thread — delivered in three reviewable phases (§8). |
| Server URL | ~~A user-configurable Settings field~~ — **superseded 2026-09-30**. The URL is asked on the **login screen**, saved on a successful sign-in, and never shown in Settings. See `docs/chat-login-and-contacts-brief.md`; §7 below is kept only for the host facts it verifies. |

One question is still open and is a hard gate on Phase 3 — see §10.1 (auth).

---

## 1. What the SDK actually is

26 Java files, 1 163 lines, namespace `com.chatserver.sdk`, `minSdk 26`, Java 17.
No Kotlin, no coroutines, no Android components of its own — the manifest declares
`INTERNET` and `ACCESS_NETWORK_STATE` and nothing else. It starts no service and posts no
notification; what happens when a message arrives is the host's decision.

### 1.1 Public surface — the entire contract

```
ChatSdk        static singleton: init(Context, ChatConfig) / get()
               connect() disconnect() isConnected() myUserId()
               addListener(ChatListener) removeListener(ChatListener)
               me(ChatCallback<User>)
               getConversations(ChatCallback<List<Conversation>>)
               openDirectConversation(String otherUserId, ChatCallback<String>)
               getMessages(String conversationId, ChatCallback<List<Message>>)
               cachedMessages(String conversationId) : List<Message>
               sendText(String conversationId, String text) : String   // client message id

ChatConfig     builder: restBaseUrl, wsUrl, deviceKey, displayName
ChatListener   onConnected / onDisconnected(code, reason) / onMessage / onMessageSent
ChatCallback<T>  onSuccess(T) / onError(ChatError)
ChatError      httpStatus (0 == network-level), message
model.Message        messageId, clientMessageId, conversationId, senderId, type,
                     body, sequenceNumber, createdAtMs, pending, isMine(myUserId)
model.Conversation   conversationId, type, createdAtMs, muted, archived, pinned,
                     otherUserId, otherUserContactIdentifier, lastMessage*, unreadCount
model.User           userId, deviceId
```

That is all of it. Everything else is `internal`.

### 1.2 How it works inside

| Concern | Mechanism |
|---|---|
| REST | Retrofit 2.11 + Gson 2.11, `IdentityInterceptor` adds `X-Device-Key`, `X-Device-Id`, `X-Display-Name`. Paths are relative (`api/auth/me`) — a leading slash would drop the base URL's `/chat/` context path. |
| WebSocket | OkHttp, identity carried as **query parameters** (`?deviceKey=&deviceId=&displayName=`), not headers. |
| Envelope | `Frame{frameId, type, version "1.0", correlationId, sentAt, payload}`. `correlationId` **must** be a real ULID — chat-node force-parses it and silently drops the frame otherwise. |
| Frame types | `session.welcome`, `heartbeat.ping/pong`, `message.send/ack/push`. |
| Liveness | Three guards: OkHttp `pingInterval` 20 s; an app heartbeat at the interval `session.welcome` announces; and a watchdog that cancels a socket silent for 3× that interval. `connect()` also force-cancels a socket quiet for > 60 s. |
| Reconnect | Exponential backoff 1 s → 30 s, reset on open. Suppressed after an explicit `disconnect()`. |
| Threading | Every `ChatListener` and `ChatCallback` call is posted to `Handler(Looper.getMainLooper())`. Sends and the heartbeat run on OkHttp / a single-thread scheduled executor. |
| Persistence | Exactly one thing: a per-install UUID in `SharedPreferences("chatsdk_device")`, deliberately surviving logout. |
| Identity | `deviceKey` is who the user *is* (in guest mode, the PPDR username); `installId` distinguishes two installs of the same user so the server delivers to both. |

### 1.3 Findings that change the integration design

These are properties of the SDK as shipped. None is a reason not to integrate; each is a
reason the host must be built a particular way. **Every one is load-bearing below.**

1. **`getMessages` advances exactly one page per call.** It sends
   `afterSequenceNumber = <high-water mark>, limit = 200`, oldest-first. The KDoc says
   "calling it on every screen open is cheap", which is true, but a conversation with
   1 000 messages needs **five** calls to catch up, and the first call returns the *oldest*
   200, not the newest. → The host must loop until a call yields no new messages, and must
   not assume one call means "synced".

2. **No durable store.** `messagesByConversation`, `lastSequence` and `pending` are
   `ConcurrentHashMap`s in the singleton. Process death loses all three:
   `cachedMessages()` returns empty and the cursor resets to 0, so the whole history is
   re-fetched a page at a time. → If chat must open instantly or work offline, **the host
   owns persistence.**

3. **A send with no socket is silently dropped.** `WsClient.send` is
   `if (current != null) current.send(...)`; there is no outbox and no retry. The message
   stays in `pending` for ever and renders as "sending" until the process dies. → The host
   needs its own outbox if send reliability matters, and must at minimum surface
   connectivity.

4. **`pending` is never cleaned up on failure.** Only a matching `message.ack` removes an
   entry. Unbounded growth over a long session, plus permanently-pending bubbles.

5. **A message sent before `myUserId` is known carries `senderId = null`.** `me()` is
   fetched lazily on WS connect. `Message.isMine(myUserId)` is then false, so the user's
   own message renders on the wrong side until the ack arrives. → Resolve identity
   *before* enabling the composer.

6. **`ChatSdk.init()` leaks a thread per call.** It disconnects the old instance but
   never shuts down `WsClient.scheduler` (a `newSingleThreadScheduledExecutor`).
   `disconnect()` cancels the heartbeat task, not the executor. → **Directly constrains
   the Settings URL requirement**: re-init must be debounced and must happen only on a
   *distinct, valid* URL change, never per keystroke (§7.4).

7. **Listeners live on a process-lifetime singleton.** A ViewModel that registers and does
   not unregister is leaked for the life of the app. LeakCanary (debug) will catch it. →
   One owner registers, ViewModels observe a Flow (§4).

8. **The host cannot supply an `OkHttpClient`.** No interceptor, no trust anchor, no
   proxy, no `Authorization` header. This is why §10.1 is a blocker rather than a
   workaround.

9. **Only `TEXT` can be sent** — `WsClient.sendMessage` hardcodes the type. `Message.type`
   can still *arrive* as IMAGE/VIDEO/AUDIO/DOCUMENT/SYSTEM, so the renderer must handle
   inbound types it can never produce.

10. **The conversation summary is read-only.** `muted`, `archived`, `pinned`,
    `unreadCount`, `lastMessageStatus` all arrive from the server and there is **no setter
    for any of them**, no read receipts, no typing indicators, no delete, no edit, no
    group creation. → The first release must not promise these in the UI.

11. **`android.util.Log` is called directly**, including `Log.w(TAG, "WS failure: " + …)`,
    in release builds. No message bodies are logged, so this is not a §7 data leak, but it
    is unredacted output the repo's `Logger` facade otherwise forbids. Detekt's
    `ForbiddenImport` rule only scans Kotlin, so **no existing gate catches it** (§9.4).

12. **DTOs are Java `record`s** consumed by Gson. Below API 34 D8 desugars them and Gson
    falls back to reflective field access, which works only while the field names survive.
    `consumer-rules.pro` keeps them — so that file **must** be carried over in the
    conversion (§5.1), or release builds break in a way debug builds never show.

---

## 2. What is already waiting for it

This app was built with the seam in place. From `AppDestination`:

> `CHATS` restores the premise… A chat module is coming from another team, and it is a
> destination of the same weight as Calls.

- `AppDestination.CHATS` — route `chats`, `isTopLevel`, and `START` (the app opens here).
- `ui/chats/ChatsPlaceholderScreen.kt` — the screen to replace. Its KDoc states the
  contract: the replacing module inherits the route, the settings gear and the
  registration indicator, and "replaces a composable, not an app shell".
- `AppNavHost.callRoutes` already registers `composable(AppDestination.CHATS.route)`.
- `AppRoot` already draws the two-tab bottom bar.

**Nothing about the navigation, the bar, the insets or the back stack needs to change.**

---

## 3. Target module layout

```
:chatsdk            (new, vendored)  the SDK, Java untouched, build file converted
:domain             (edit)           chat models, ports, use cases — pure Kotlin
:data:chat          (new)            the ONLY module that may name com.chatserver.sdk
:feature:chat       (new)            Compose UI + ViewModels, sees :domain only
:app                (edit)           DI, lifecycle, nav wiring, Settings URL
:data:settings      (edit)           persist the server URL
:feature:settings   (edit)           the URL field
:test:arch          (edit)           new rule confining the SDK
```

This mirrors `:data:sip` exactly, and for the same stated reason — *"replacing liblinphone
with PJSIP was a rewrite of one file behind four interfaces, not of the application"*. The
chat SDK is a third-party dependency of the same character, from a repository this team
does not control, so it gets the same containment.

### 3.1 Layer rules this must satisfy

| Rule | Consequence here |
|---|---|
| 1 — `:domain` has no Android import | Chat models are plain Kotlin; timestamps are `Long` epoch millis (the SDK already gives them that way), never `Instant` from an Android-desugared type. |
| 2 — SDK types confined to one module | `com.chatserver.sdk.*` may be imported **only** under `data/chat/`. Proposed as a new rule, §9.5. |
| 3 — features never import `:data:*` | `:feature:chat` depends on `:domain` and `:core:designsystem` only. |
| 4 — repository interfaces in `:domain`, `*RepositoryImpl` in `:data:*` | `ChatRepository` in domain, `ChatRepositoryImpl` in `:data:chat`. The regex is on the *name*, so the naming is not optional. |
| 5 — no LiveData/Rx/AsyncTask/raw `Thread` | The SDK's own executor is inside Java and out of the rule's Kotlin scope. Do not add one in Kotlin. |
| 6 — ViewModels expose immutable state | `StateFlow`, private `MutableStateFlow`, no public `var`. |
| 8 — no hardcoded colour/dimension/text style outside the design system | Bubbles, avatars and the composer use `AppTheme.spacing` and `MaterialTheme.colorScheme`. |
| 9 — contact data does not leave the device | See §9.6 — this rule needs extending, because the chat SDK is a new way off the device that its `EGRESS` list does not know about. |

---

## 4. Data flow

```
                    ┌──────────────────────────────────────────┐
  Application  ──►  │ ChatEngineLifecycle        (:data:chat)   │
  onCreate          │  observes AppSettings.chatServerUrl       │
                    │  ChatSdk.init(...) on a distinct change   │
                    │  registers ONE ChatListener               │
                    └───────────────┬──────────────────────────┘
                                    │ callbacks (main thread)
                                    ▼
                    ┌──────────────────────────────────────────┐
                    │ ChatEventBus  MutableSharedFlow<ChatEvent>│
                    └───────────────┬──────────────────────────┘
                                    │
   ┌────────────────────────────────▼───────────────────────────┐
   │ ChatRepositoryImpl                            (:data:chat) │
   │  suspendCancellableCoroutine over ChatCallback             │
   │  maps sdk.model.* → domain model                           │
   │  hops to DispatcherProvider.io                             │
   └────────────────────────────────┬───────────────────────────┘
                                    │ Flow / suspend, Outcome<T, ChatError>
   ┌────────────────────────────────▼───────────────────────────┐
   │ ChatRepository (interface)                       (:domain) │
   └────────────────────────────────┬───────────────────────────┘
                                    │
   ┌────────────────────────────────▼───────────────────────────┐
   │ ConversationListViewModel / ChatThreadViewModel (:feature) │
   │  StateFlow<UiState>                                        │
   └────────────────────────────────┬───────────────────────────┘
                                    ▼
                         ConversationListScreen / ChatThreadScreen
```

Two rules make this work:

- **Exactly one `ChatListener` exists in the process**, owned by `ChatEngineLifecycle`,
  registered at `Application.onCreate` and never removed. Finding 1.3-7 (listener leaks)
  disappears by construction — no ViewModel ever touches `addListener`.
- **Callbacks arrive on the main thread and are immediately handed to a Flow.** Nothing
  blocks the main thread; the repository hops to `dispatchers.io` for the REST bridge.

---

## 5. Code changes, file by file

### 5.1 `chatsdk/build.gradle` → `chatsdk/build.gradle.kts` (the only SDK-directory change)

```kotlin
plugins {
    id("com.android.library")
}

android {
    namespace = "com.chatserver.sdk"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        // MUST be preserved. The wire DTOs are Java records populated by Gson through
        // reflection; R8 runs in :app, so without these the release build renames their
        // fields and every frame silently fails to parse. Debug builds never show it.
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Not in the original. java.time is API 26+ so it is not strictly required at
        // minSdk 26, but every other module in this repo desugars and a mismatch is a
        // trap for whoever raises a language level later.
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    coreLibraryDesugaring(libs.android.desugarJdkLibs)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.gson)
    implementation(libs.ulid.creator)
    implementation(libs.androidx.annotation)
}
```

Deliberately **not** applying `whatsappv2.android.library`: that plugin pulls in detekt and
kover, which analyse Kotlin and would report nothing on a Java-only module while adding
tasks to every build. `targetSdk` is dropped — it is meaningless in a library and AGP 9
warns on it.

`consumer-rules.pro` is copied across unchanged. Consumer ProGuard files propagate from a
**project** dependency, not only from an AAR, so `:app`'s R8 run picks them up.

### 5.2 `gradle/libs.versions.toml` — six new pins

```toml
[versions]
okhttp       = "4.12.0"
retrofit     = "2.11.0"
gson         = "2.11.0"
ulidCreator  = "5.2.3"
androidxAnnotation = "1.8.2"

[libraries]
okhttp                   = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
retrofit                 = { group = "com.squareup.retrofit2", name = "retrofit", version.ref = "retrofit" }
retrofit-converter-gson  = { group = "com.squareup.retrofit2", name = "converter-gson", version.ref = "retrofit" }
gson                     = { group = "com.google.code.gson", name = "gson", version.ref = "gson" }
ulid-creator             = { group = "com.github.f4b6a3", name = "ulid-creator", version.ref = "ulidCreator" }
androidx-annotation      = { group = "androidx.annotation", name = "annotation", version.ref = "androidxAnnotation" }
```

**These are the SDK's own pins, carried over deliberately, not versions written from
memory.** The catalog's header says every version comes from the *Report dependency
versions* workflow; run it against these five coordinates before merge and bump in the same
commit if it reports newer. Do not guess — the catalog comment records that guessing
produced pins several major versions stale. All five resolve from Maven Central, which
`settings.gradle.kts` already allows, so no new repository is needed.

### 5.3 `settings.gradle.kts`

```kotlin
// The chat SDK (com.chatserver.sdk), vendored as source rather than consumed as an AAR:
// architecture rule 11 forbids a committed .aar, and source is what lets this build
// prove what it ships. Its Java is upstream's and is not modified — only its build file
// was converted to the Kotlin DSL and the version catalog, which CI requires.
include(":chatsdk")
include(":data:chat")
include(":feature:chat")
```

### 5.4 `:domain` — new files

`domain/src/main/kotlin/com/whatsappv2/domain/chat/`

| File | Contents |
|---|---|
| `ChatMessage.kt` | `data class ChatMessage(id: ChatMessageId?, clientId: String?, conversationId: ConversationId, senderId: String?, type: ChatMessageType, body: String?, sequenceNumber: Long, createdAtMs: Long, delivery: Delivery)` where `Delivery = Pending \| Sent \| Failed`. **`Failed` is ours** — the SDK has no such state (finding 3), and without it a dropped send is invisible. |
| `ChatMessageType.kt` | `TEXT, IMAGE, VIDEO, AUDIO, DOCUMENT, SYSTEM, UNKNOWN`. `UNKNOWN` because the server may add a type this build has never heard of, and a `valueOf` crash on an inbound push is not an acceptable failure mode. |
| `ChatConversation.kt` | The summary, `otherUserContactIdentifier` included. |
| `ConversationId.kt`, `ChatMessageId.kt` | `@JvmInline value class`, matching `AccountId`/`CallId`. |
| `ChatIdentity.kt` | `data class ChatIdentity(userId: String, deviceId: String)`. |
| `ChatConnectionState.kt` | `Connected \| Disconnected(code, reason) \| Connecting \| NotConfigured`. **`NotConfigured` is required**: `ChatSdk.get()` throws before `init`, and "no server URL saved yet" is a real, reachable state once the URL is user-editable. |
| `ChatServerUrl.kt` | Value class + validation. See §7.1. |
| `ChatFailure.kt` | `Network \| Unauthorized \| Server(status, message) \| NotConfigured \| Unknown`. Typed, not a string — the same reasoning as `AccountRepositoryError`: the UI owns the wording, and `Unauthorized` must drive a different screen from `Network`. |

`domain/src/main/kotlin/com/whatsappv2/domain/repository/ChatRepository.kt`

```kotlin
interface ChatRepository {
    fun observeConnection(): Flow<ChatConnectionState>
    fun observeConversations(): Flow<List<ChatConversation>>
    fun observeMessages(conversationId: ConversationId): Flow<List<ChatMessage>>

    suspend fun identity(): Outcome<ChatIdentity, ChatFailure>
    suspend fun refreshConversations(): Outcome<Unit, ChatFailure>

    /**
     * Fetches until the server has nothing newer, not one page.
     * The SDK advances its cursor by one 200-message page per call (finding 1.3-1);
     * a single call is not "synced" and callers must not have to know that.
     */
    suspend fun syncMessages(conversationId: ConversationId): Outcome<Unit, ChatFailure>

    suspend fun openDirectConversation(otherUserId: String): Outcome<ConversationId, ChatFailure>
    suspend fun sendText(conversationId: ConversationId, text: String): Outcome<ChatMessageId?, ChatFailure>
    fun connect()
}
```

`domain/src/testFixtures/.../FakeChatRepository.kt` — beside the seven existing fakes, so
`:feature:chat` and `:app` can be driven with no chat server at all, exactly as
`FakeSipEngine` lets the app run with no SIP server.

Use cases (`domain/src/main/kotlin/com/whatsappv2/domain/usecase/`):
`OpenConversationUseCase`, `SendChatMessageUseCase`, `SyncConversationUseCase`.

### 5.5 `:data:chat` — new module

`data/chat/build.gradle.kts`

```kotlin
plugins {
    id("whatsappv2.android.library")
    id("whatsappv2.hilt")
}

android { namespace = "com.whatsappv2.data.chat" }

dependencies {
    implementation(project(":core:common"))
    implementation(project(":domain"))
    implementation(project(":chatsdk"))   // the ONLY module that may depend on it
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(testFixtures(project(":domain")))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
}
```

| File | Responsibility |
|---|---|
| `ChatEngineLifecycle.kt` | Public handle over an internal engine — the shape of `SipEngineLifecycle`, and for the identical reason: nothing above `:data:chat` should be able to name what owns the socket. `start()` observes `AppSettings.chatServerUrl`, calls `ChatSdk.init` + `connect()` on a **distinct valid** change (§7.4), registers the one listener, and is failure-tolerant — a chat server that will not come up must not stop the app launching, because this is a SIP client first. |
| `ChatEventBus.kt` | `MutableSharedFlow<ChatEvent>(extraBufferCapacity = 64, onBufferOverflow = DROP_OLDEST)`. The bridge from main-thread callbacks to Flow. |
| `SdkChatListener.kt` | The single `ChatListener`. Pure translation, no logic. |
| `ChatRepositoryImpl.kt` | Implements the port. `suspendCancellableCoroutine` per `ChatCallback`; `withContext(dispatchers.io)`; the `syncMessages` loop; `Outcome` mapping. **Naming is mandatory** — arch rule 4's regex looks for `…RepositoryImpl`. |
| `ChatModelMapper.kt` | `sdk.model.Message/Conversation/User` → domain. Unknown `type` → `ChatMessageType.UNKNOWN`. |
| `ChatSendOutbox.kt` | Closes finding 1.3-3. A send issued while `isConnected()` is false is marked `Failed` rather than left pending for ever, and is re-offered on the next `onConnected`. |
| `di/ChatModule.kt` | `@Binds ChatRepositoryImpl → ChatRepository`, `@Singleton`. |

**No Room in phase 1.** Persistence (finding 1.3-2) is a follow-up, and adding a database
in the same change as the transport makes both harder to review. The port is already
shaped so a cache can slide in behind it without a signature changing.

### 5.6 `:feature:chat` — new module

`build.gradle.kts` copies `feature/history`'s exactly (library + compose + hilt;
`:domain` and `:core:designsystem` only — **never** `:data:chat`).

| File | Notes |
|---|---|
| `ConversationListRoute.kt` | Replaces `ChatsPlaceholderScreen` at the same route. Takes `onOpenSettings` and `registrationIndicator` **with the same signatures the placeholder has**, so `AppNavHost` changes by one import and one call. |
| `ConversationListScreen.kt` / `…UiState.kt` / `…ViewModel.kt` | Rows, unread badge, empty state via `:core:designsystem`'s `StateViews`, `Avatar` for the other party. |
| `ChatThreadRoute.kt` / `Screen` / `UiState` / `ViewModel` | Bubbles, day separators, composer, `Delivery.Pending` spinner, `Delivery.Failed` retry affordance. |
| `ChatConnectionBanner.kt` | Renders `ChatConnectionState`. `NotConfigured` says "Set a chat server in Settings" and links there — the one state a user can actually fix. |

The thread route needs a new destination: `AppDestination.CHAT_THREAD("chat/{conversationId}", …)`,
**not** `isTopLevel`, so the existing push transition applies with no change to `AppNavHost`'s
animation logic.

### 5.7 `:app` — edits

- `SipApplication` — one field and one line, placed after `sipEngine.start()`:
  ```kotlin
  @Inject lateinit var chatEngine: ChatEngineLifecycle
  // …
  chatEngine.start()
  ```
  Consistent with the class's stated rule: what is started here is what has nowhere
  earlier to live and no screen to belong to. The chat socket qualifies.
- `AppNavHost` — swap `ChatsPlaceholderScreen(...)` for `ConversationListRoute(...)`;
  add the `chat/{conversationId}` composable.
- `di/ChatModule.kt` if any `:app`-level binding is needed (likely none).
- **Delete `ui/chats/ChatsPlaceholderScreen.kt`** in phase 3, not before.
- `app/build.gradle.kts` — add `implementation(project(":data:chat"))` and
  `implementation(project(":feature:chat"))`.

---

## 6. SDK → domain mapping

| SDK | Domain port | Note |
|---|---|---|
| `ChatSdk.connect()` | `ChatRepository.connect()` | Idempotent; also repairs a stale socket. |
| `ChatListener.onConnected/onDisconnected` | `observeConnection()` | Plus `NotConfigured` before `init`. |
| `ChatSdk.me` | `identity()` | Called once at start; gates the composer (finding 5). |
| `getConversations` | `refreshConversations()` + `observeConversations()` | Cached in a `StateFlow` so the list survives rotation. |
| `openDirectConversation` | `openDirectConversation()` | Takes a user id **or** a device key in guest mode. |
| `getMessages` | `syncMessages()` | **Loops.** See finding 1. |
| `cachedMessages` | seeds `observeMessages()` | Empty after process death (finding 2). |
| `sendText` | `sendText()` | Returns the client id; `Pending` until ack. |
| `ChatListener.onMessage` | `observeMessages()` emission | |
| `ChatListener.onMessageSent` | `Pending → Sent`, matched on `clientMessageId` | The only stable handle across a send. |
| `ChatError(0, …)` | `ChatFailure.Network` | `httpStatus == 0` means no response at all. |
| `ChatError(401, …)` | `ChatFailure.Unauthorized` | Drives §10.1. |
| — | `Delivery.Failed` | **Ours.** The SDK has no failure state for a send. |

---

## 7. Server URL — **partly superseded**

> **Superseded 2026-09-30.** The URL is **not** a Settings field. It is one of three fields
> on the login screen (URL, username, password), saved on a successful sign-in. See
> `docs/chat-login-and-contacts-brief.md` for the current requirement.
>
> **What survives unchanged:** §7.1 (the verified facts about the host — TLS, the `/chat/`
> prefix, the 401), §7.2 (one stored value, two derived URLs), §7.3 (validation, including
> why `http://` needs its own message) and §7.4 (the re-init guard that stops the SDK's
> thread leak). Only the *placement* moved: the field, its storage key and its owning
> ViewModel belong to login, not to Settings.
>
> **What is void:** §7.5's edits to `AppSettings`, `AppSettingsRepository`,
> `DataStoreAppSettingsRepository`, `SettingsViewModel` and `SettingsScreen`. The URL
> belongs to the session, not to app preferences.

Original text follows, for the host facts and the validation design it records.

### 7.1 What was verified about that host

Probed from this machine on 2026-09-30:

```
gujlogin.coraltele.com           → 192.168.100.16  (reachable, 2–9 ms)
https://…/                       → 200 under STRICT TLS (no -k)
certificate                      → CN=*.coraltele.com, SAN *.coraltele.com + coraltele.com
issuer                           → Sectigo Public Server Authentication CA DV R36
valid                            → 2026-02-25 … 2027-03-27
GET /chat/api/auth/me            → 401  ERR_INVALID_ACCESS_TOKEN
GET /chat/api/conversations      → 401
GET /chat/ws                     → 401
GET /api/auth/me                 → 200  (a different app at the root — not chat-node)
```

Two conclusions that matter:

- **The certificate is publicly trusted and the hostname matches.** The app's
  `network_security_config.xml` trusts the system store and nothing else, and refuses
  cleartext in every build with no debug override — and a CI step
  (*"Assert cleartext traffic is refused"*) fails the build if that is ever relaxed.
  Because this host presents a real Sectigo certificate, **`https://` and `wss://` work as
  shipped, with no change to the network security config and no new trust anchor.**
  That is a significant piece of luck; do not spend it by defaulting to `http://`.
- **The chat API is under `/chat/`**, matching the SDK's KDoc example.

So the two URLs the SDK needs are:

```
restBaseUrl = https://gujlogin.coraltele.com/chat/
wsUrl       = wss://gujlogin.coraltele.com/chat/ws
```

### 7.2 One stored value, two derived

The user types **one** URL. Storing two would let them disagree, and nobody can be expected
to keep a `wss://` string in step with an `https://` one.

`domain/src/main/kotlin/com/whatsappv2/domain/chat/ChatServerUrl.kt`

```kotlin
@JvmInline
value class ChatServerUrl private constructor(val base: String) {

    /** Retrofit refuses a base URL without a trailing slash. */
    val restBaseUrl: String get() = base

    /** https → wss, http → ws, plus the SDK's `ws` endpoint. */
    val wsUrl: String get() = base.replaceFirst("https://", "wss://")
                                  .replaceFirst("http://", "ws://") + "ws"

    companion object {
        val DEFAULT = ChatServerUrl("https://gujlogin.coraltele.com/chat/")

        fun parse(raw: String): Outcome<ChatServerUrl, ChatUrlViolation> { /* §7.3 */ }
    }
}
```

### 7.3 Validation — the failures a user will actually hit

`ChatUrlViolation` is a sealed interface, validated in `:domain` and unit-tested there,
matching `AccountValidator`:

| Violation | Why it exists |
|---|---|
| `Blank` | Empty field. |
| `NotAbsolute` | `gujlogin.coraltele.com` with no scheme — the likeliest thing a user types. Offer to prepend `https://` rather than just refusing. |
| `Cleartext` | `http://`. **Must be its own message.** This app refuses cleartext in every build; left generic, the user sets `http://`, gets an opaque `ChatError(0, …)` at runtime, and has no way to learn why. The message names the reason. |
| `UnsupportedScheme` | Anything but http/https. |
| `Malformed` | Unparseable authority. |

`parse` normalises: trims, lowercases the scheme and host, and appends the trailing slash
the SDK's own `ChatConfig` would otherwise add silently — better to show the user the
string that will actually be used.

### 7.4 Applying a change without leaking

This is where finding 1.3-6 bites. `ChatSdk.init()` disconnects the old instance but never
shuts down its `ScheduledExecutorService`, so **every re-init leaks one thread for the life
of the process**. Typing a 40-character URL into a field wired naively to the repository
would leak ~40 threads.

`ChatEngineLifecycle` therefore:

1. observes `AppSettings.chatServerUrl`,
2. `.distinctUntilChanged()`,
3. re-inits **only on a value that parses**, and
4. re-inits only when the parsed value differs from the one currently running.

The Settings screen commits on **done/blur**, not per keystroke — the field holds local
text state and writes through only on an explicit commit. Both halves are needed: the
screen must not spam writes, and the engine must not trust that it won't.

### 7.5 The rest of the change

- `AppSettings` gains `val chatServerUrl: String = ChatServerUrl.DEFAULT.base`.
  Stored as the raw string, like `videoFrameRate` is stored as a number: a value written by
  a build whose default differs is still readable, and `parse` decides what to do with it.
- `AppSettingsRepository` gains `suspend fun setChatServerUrl(url: String)` — an individual
  setter, per the interface's own stated rule against whole-object writes.
- `DataStoreAppSettingsRepository` gains `stringPreferencesKey("chat_server_url")` and
  reads it in `toAppSettings()` with `ChatServerUrl.DEFAULT.base` as the fallback.
- `FakeAppSettingsRepository` gains the same setter.
- `SettingsViewModel` gains `setChatServerUrl` plus a validated `chatServerUrlError`.
- `SettingsScreen` gains a "Chat server" card with an `OutlinedTextField`
  (`KeyboardType.Uri`, `singleLine`, supporting text carrying the violation). It follows
  `AccountEditorScreen.kt:271`'s private `Field` composable — each feature rolls its own
  wrapper here, so this needs **no design-system change** and therefore no new
  `@ThemePreviews` obligation under rule 7. Rule 8 still applies: `AppTheme.spacing`, no
  literal dp/sp.
- A "Reset to default" affordance, because a user who pastes a bad URL has otherwise
  bricked their own chat tab with no way back.

---

## 8. Sequencing

Four phases. Each is independently mergeable and leaves the app shippable — the Chats tab
keeps its placeholder until phase 3.

| Phase | Contents | Proves |
|---|---|---|
| **0 — Vendor** | Remove `chatsdk/.git`; track the sources; convert the build file; catalog pins; `include(":chatsdk")`; `:chatsdk:assembleDebug`. | The SDK compiles inside this build and CI's gates pass. |
| **1 — Login** *(revised)* | `ChatServerUrl` + validation + tests; session storage; the login screen (URL, username, password); logout. Was "Settings field" — superseded, see §7. | The user can sign in and out, and the URL is captured where it belongs. |
| **2 — Transport** | `:data:chat`, domain models and ports, `ChatEngineLifecycle`, event bus, mapper, outbox, DI, `FakeChatRepository`, arch rule. Wired into `SipApplication`. **UI unchanged.** | The socket connects, `me()` resolves and pushes arrive — observable in logs and tests, with no UI to review at the same time. Also where §10.1 is settled. |
| **3 — UI** | `:feature:chat`, both screens, nav wiring, delete the placeholder. | The tab. |

Phases 0–2 are strictly additive: no existing file changes behaviour until phase 3's
one-line swap in `AppNavHost`.

---

## 9. Conflicts, and what each costs

### 9.1 CI gate — *"Assert no Groovy build files"* (`ci.yml:153`) — **fails today**
`find . -type f -name "*.gradle"` excluding only `.git`, `third_party`, `.gradle` and
`build`. `chatsdk/build.gradle` is found the moment it is tracked. **Resolved by phase 0's
conversion.**

### 9.2 CI gate — *"Assert no inline dependency versions"* (`ci.yml:181`) — **fails after conversion**
Greps `*.gradle.kts` for `implementation("…:…:<digit>")`. The SDK's six inline versions
would fire. **Resolved by moving them to the catalog** (§5.2). Note the ordering trap:
conversion alone turns a passing gate into a failing one, so §5.2 is not optional and not
separable from §5.1.

### 9.3 Architecture rule 11 — no committed `.aar`
`chatsdk/build/outputs/aar/chatsdk-release.aar` exists on disk. It is under `build/`, which
git ignores and which `trackedFiles()` therefore never sees — so it is harmless **as long as
nobody force-adds it**. Do not add `chatsdk/build/` to the repository.

### 9.4 Detekt `ForbiddenImport` does not cover Java
`android.util.Log` is banned repo-wide and a CI step proves the ban fires — but detekt
analyses Kotlin, so the SDK's `Log.i/w/d` calls pass unexamined. This is not a new leak
(no bodies or credentials are logged) but it should be stated in the module's KDoc rather
than discovered. If the noise matters, the honest fix is a `-assumenosideeffects` rule for
`android.util.Log` in `:app`'s release ProGuard configuration — not an SDK edit.

### 9.5 Proposed new architecture rule — confine the SDK
`ArchitectureRules.kt` should gain, beside rule 2:

```kotlin
/** Rule 13 — no chat SDK type outside :data:chat. */
private const val CHAT_SDK = "com.chatserver.sdk"

fun chatSdkStaysInDataChat(files: List<SourceFile>): List<Violation> =
    files.flatMap { file ->
        file.imports
            .filter { it.startsWith(CHAT_SDK) && !file.isUnder("data/chat") }
            .map { Violation(file.relativePath, "imports $it outside :data:chat") }
    }
```

plus a fixture under `test/arch/src/test/resources/violations/` and a `RulesActuallyFireTest`
case — the suite's own standard is that *"a rule that never fires is worse than no rule"*.
This is the single highest-value change in the whole plan: it is what keeps a second
SDK swap a one-module rewrite.

### 9.6 Proposed extension to rule 9 — contacts and the chat server
Rule 9 lists `okhttp3.`, `retrofit2.`, `java.net.` … as ways off the device, and forbids any
file holding contact data from importing one. The chat SDK is a new way off the device that
the list does not know about, and chat is exactly where someone will one day want to match
the address book against chat users. **Add `com.chatserver.sdk` to `EGRESS`.** It costs
nothing today (no file imports both) and it is the cheapest moment to add it — the rule's
own KDoc says the mistake is "one import in one file, months later, in a class that already
had a good reason to talk to the network".

### 9.7 Coverage thresholds (`ci.yml:490`)
The threshold map is keyed by package, so new packages are ungated by default. Add, in the
spirit of the existing entries:
`com/whatsappv2/domain/chat: 90`, `com/whatsappv2/data/chat: 70`,
`com/whatsappv2/feature/chat: 40` (the feature floor the other feature packages use, for
the stated reason that line coverage of declarative UI is a weak signal).

### 9.8 R8 and the wire DTOs
Covered by carrying `consumer-rules.pro` across (§5.1). Worth an explicit release-build
smoke test, because the failure mode — Gson parsing nothing, no exception, an empty chat —
is invisible in debug.

### 9.9 LeakCanary
`:app`'s debug build will watch the chat objects. Expect noise if any ViewModel ever
registers a `ChatListener` directly; the single-owner design (§4) is what prevents it.
Per the existing note in `docs`, the `ConnectionService` binder-stub leak is pre-existing
and unrelated.

### 9.10 Working-tree state
This branch carries uncommitted changes from another line of work (`Spacing.kt`,
`AppSettings.kt`, `AppSettingsRepository.kt`, `DataStoreAppSettingsRepository.kt`,
`AdaptiveVideoPolicy.kt`, `VideoQualityProfile.kt`, `ConferenceVideoGrid.kt`,
`FakeAppSettingsRepository.kt`, and a new `VideoFrameRate.kt`). **Three of those are files
phase 1 also edits.** Land or rebase that work before starting phase 1, or the settings
change will conflict.

---

## 10. Open questions

### 10.1 Authentication — the one real blocker

`GET https://gujlogin.coraltele.com/chat/api/auth/me` returns:

```json
{"code":"ERR_INVALID_ACCESS_TOKEN","retryable":false,
 "message":"Access token is invalid or has expired"}
```

The SDK sends only `X-Device-Key` / `X-Device-Id` / `X-Display-Name` — chat-node's guest
mode, for a server running `auth-required=false`. It has **no** token field in `ChatConfig`,
no auth call, and no way for a host to add an `Authorization` header, because the
`OkHttpClient` is built privately inside `ChatSdk`'s constructor (finding 8).

I could not settle this myself: the probe that would answer it — one request carrying the
identity headers — was blocked by this session's command classifier. **Run it yourself:**

```bash
curl -si -H "X-Device-Key: 1001" -H "X-Device-Id: $(uuidgen)" -H "X-Display-Name: 1001" https://gujlogin.coraltele.com/chat/api/auth/me
```

- **200 with a `userId`** → guest mode is on. The plan proceeds exactly as written, and
  `deviceKey` binds to the default SIP account's username (§10.2).
- **401 again** → the deployment requires a bearer token and **the SDK cannot talk to it at
  all**. Three ways out, in order of preference: (a) the SDK gains a token in `ChatConfig` —
  an upstream change, small, and the only clean fix; (b) chat-node exposes guest mode on a
  second path this app points at; (c) a reverse proxy injects the header — works, but hides
  an auth decision in infrastructure. Phases 0, 1 and the whole of `:domain` are unaffected
  either way, which is why they are sequenced first.

Note the caveat the SDK's own KDoc gives: in guest mode the server *provisions* an identity
on first use, so that probe may create a guest user.

### 10.2 What is `deviceKey`?
`ChatConfig.deviceKey` is "the identity chat-node knows this user by (in guest mode, the
PPDR username)". The obvious binding is the **default SIP account's `username`**, with
`displayName` from `SipAccount.displayName ?: username`. That makes chat identity follow
SIP identity, which is almost certainly right for this product — but it means
`ChatEngineLifecycle` must also observe `SipAccountRepository.observeDefaultAccount()` and
re-init when the default account changes, with the same distinct-value guard as the URL.
**Confirm before phase 2**, because it changes `ChatEngineLifecycle`'s inputs.

### 10.3 Notifications
The SDK posts none, by design. A message arriving while the app is backgrounded currently
does nothing. Out of scope here; note that the push path (`SipMessagingService`, ADR-004)
already exists and is the natural home if chat-node can send FCM.

### 10.4 Message persistence
Deferred (finding 2). Revisit once the volume is known; the port is shaped for it.

---

## 11. Testing

| Level | What |
|---|---|
| `:domain` unit | `ChatServerUrl.parse` — every violation, normalisation, the http→ws derivation, the trailing slash. `ChatMessageType` unknown-value fallback. Use cases against `FakeChatRepository`. **No Android, no network.** |
| `:data:chat` unit (Robolectric) | Callback→`Outcome` bridge for success, HTTP error and network error; the `syncMessages` loop stopping when a page adds nothing; `Pending → Sent` on ack matched by `clientMessageId`; `Pending → Failed` when disconnected; mapper round-trips. `ChatSdk` is static, so it is reached through a thin `ChatSdkHandle` seam that tests substitute — the one piece of indirection this design adds, and it is what makes the module testable at all. |
| `:data:chat` — re-init | Turbine over a settings flow: N emissions of the same URL ⇒ **one** `init`; an invalid URL ⇒ **no** `init`. This is the regression test for the thread leak (finding 6). |
| `:feature:chat` (Robolectric + Compose) | List renders, empty state, unread badge; thread renders, composer disabled until identity is known (finding 5), pending spinner, failed-retry affordance; `NotConfigured` banner links to Settings. |
| `:feature:settings` | The URL field shows each violation; commit-on-blur writes once; reset restores the default. |
| `:test:arch` | Rule 13 finds nothing in the project and **does** find the planted fixture. |
| Instrumented / manual | Against the real server: connect, list, open, send, receive, background 60 s and confirm the watchdog reconnects, aeroplane-mode send ⇒ `Failed`, change the URL in Settings and confirm reconnection without a restart. |
| Release-build smoke | `assembleRelease`, install, send and receive one message. The only test that proves the R8 keep rules (§9.8). |

---

## 12. Rollback

Cheap by construction, phase by phase.

- **Phase 3** — revert one commit. `AppNavHost` points at `ChatsPlaceholderScreen` again
  (keep the file until phase 3 is soaked, which is why §5.7 deletes it last) and the tab
  reads "Messages are coming". `:feature:chat` can stay in the tree, unreferenced.
- **Phase 2** — remove `chatEngine.start()` from `SipApplication`. The socket is never
  opened; nothing else in the app touches it. One line.
- **Phase 1** — the settings key becomes unread. Harmless: DataStore keeps it, and a later
  re-land reads it back.
- **Phase 0** — drop `include(":chatsdk")` and the catalog entries.
- **Runtime kill switch, no deploy** — set the Settings URL to blank. `ChatEngineLifecycle`
  never calls `init`, the repository reports `NotConfigured`, and the tab shows the banner.
  Worth having: it is the only lever available when the chat server misbehaves in the field
  and the alternative is an app update.

Nothing in phases 0–2 alters an existing code path, so none of them can regress calling —
which matters more than chat, and is the property this sequencing is built to preserve.
