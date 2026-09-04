# Prompt: Build a Production-Grade Native Android SIP Client

> **How to use this file.** Paste the whole document as the opening message to a coding
> agent (Claude Code, Cursor, etc.) working in an empty repository. It is written to be
> self-sufficient: it fixes the ambiguities that make "build me a SIP app" prompts fail,
> states the platform constraints that are non-negotiable on modern Android, and defines
> *falsifiable* acceptance criteria so "done" is checkable rather than arguable.
> Sections marked **DECIDE** must be answered before code is written.

---

## 1. Role and mandate

You are a senior Android engineer. Build a native Android SIP softphone that a telecom
operator could ship to paying customers and maintain for five years.

Optimise, in this order, for:

1. **Correctness** under real network conditions (NAT, packet loss, carrier handover, Doze).
2. **Maintainability** — a new engineer can add a call feature without touching the SIP stack.
3. **Testability** — business logic runs on the JVM with no device and no SIP server.
4. **Performance and battery** — this app holds a long-lived registration; it must not be
   the reason a user's phone dies at 4pm.

Do not write prototype code, `TODO: implement later` stubs presented as finished work, or
placeholder classes with empty bodies. If something is genuinely out of scope for a phase,
say so explicitly in the phase report rather than shipping a hollow class.

---

## 2. Corrections to common framing — read before planning

These points override any contrary instruction elsewhere. They exist because the naive
version of this request is technically impossible as literally stated.

### 2.1 "Support 5,000 concurrent users" is a server metric, not a client metric

An Android app instance serves **one** user on **one** device. Concurrency at 5,000 users
is a property of the SIP infrastructure (registrar, proxy, SBC, media server), not of the
APK. Translate the requirement into the things the *client* actually controls:

- **Registration hygiene** — exponential backoff with full jitter on `REGISTER` failure, so
  5,000 clients recovering from a registrar restart do not stampede it. No fixed retry interval.
- **Keepalive economy** — a single adaptive NAT keepalive (SIP OPTIONS or CRLF, per
  RFC 5626) whose interval is server-configurable, not a hardcoded 30 s ping per client.
- **Bounded resource use per device** — at most one active SIP transport, one registration
  per configured account, no leaked sockets or wake locks.
- **Graceful degradation** — on `503 Service Unavailable` or `Retry-After`, honour the
  server's backoff instead of retrying immediately.

State this reframing in your design document. Do **not** invent client-side "scalability"
theatre (thread pools sized for 5,000, connection pools, load balancers in the app).

### 2.2 Group calling is not a SIP client feature on its own

SIP is point-to-point. Multi-party audio/video requires a **conference focus** — an MCU or
SFU on the server side (RFC 4579 / RFC 4353 semantics; in practice FreeSWITCH `mod_conference`,
Asterisk ConfBridge, Janus, Jitsi Videobridge, or a vendor SBC). The client's job is to
dial a conference URI, render N remote streams, and drive floor control — not to mix media.

**DECIDE (blocking):** which conference server and which model?
- (a) **Dial-in MCU** — client places one ordinary call to a conference URI; server mixes.
  Simplest, works with a plain SIP stack, single decode path. **Recommended default.**
- (b) **SFU / multi-stream** — client receives N streams. Needs a signalling extension
  beyond baseline SIP and a stack that supports multiple simultaneous media sessions.

Implement (a) end-to-end. Design the domain layer so a `ConferenceSession` can hold *N*
participant streams, so (b) is an implementation swap, not a rewrite.

### 2.3 "Java + Kotlin" — pick Kotlin, justify every Java file

Mixing two languages by preference is a maintenance tax, not an architecture. Rule:

- **Kotlin** for all application code — UI, ViewModels, use cases, repositories, DI, tests.
- **Java** only where a dependency's JNI bindings or annotation processors force it, or
  where a generated SDK binding is Java. Every `.java` file must carry a one-line header
  comment stating why it cannot be Kotlin.

### 2.4 The platform's built-in SIP API does not exist any more

`android.net.sip` (SipManager) was deprecated in API 31 and removed. You **must** embed a
third-party SIP stack with native libraries.

**DECIDE (blocking):** which stack?
- **liblinphone / linphone-sdk** (GPLv3 or commercial) — maintained, batteries-included
  (SIP + SRTP/ZRTP + video + Opus/VP8/H.264), Kotlin-friendly Java bindings, published AAR.
  **Recommended default** — fastest path to a correct, complete client.
- **PJSIP / pjsua2** (GPLv2 or commercial) — smaller, more control, more manual work,
  you build the `.so` files yourself.

**Licensing is a real constraint, not a footnote.** liblinphone and PJSIP are both
copyleft-or-commercial. If this app will be distributed without publishing source, a
commercial licence is required. Flag this in the design document and ask before assuming.

Whichever you choose, it lives behind a `SipEngine` interface in the domain layer
(§4.3). No `org.linphone.*` or `org.pjsip.*` import may appear outside the `:data:sip` module.

### 2.5 A long-lived socket cannot survive Doze — you need push

Android will not let a background app hold a TCP/TLS registration indefinitely. A
registration-only design misses incoming calls when the app is backgrounded, Doze-idle,
or process-killed. Production design:

- **FCM high-priority data message** ("you have an incoming call") from the server →
  app wakes, re-registers if needed, starts the call foreground service, shows the
  incoming-call UI, and the server delivers the `INVITE`.
- Prefer the server-side **SIP `PUBLISH`/push-notification interworking** approach
  (`pn-provider`/`pn-param`/`pn-prid` Contact parameters, RFC 8599) if the SBC supports it.
- Keep the socket registration for the foreground/recent-use case; push is the fallback
  that makes it reliable, and on Android 12+ it is the *primary* path.

**DECIDE:** does the target SIP infrastructure support RFC 8599 push, or does a separate
service need to send the FCM message? The answer changes the server contract, not the app
architecture — but it must be stated.

### 2.6 Call recording is legally and technically constrained

Android restricts capture of the remote (downlink) audio stream; on a VoIP app you can only
record what passes through your own media pipeline, and doing so is regulated (two-party
consent jurisdictions, GDPR, PCI). Deliver: the **architecture** (a `CallRecorder` port,
an encrypted storage location, a consent gate, a retention policy hook) plus an explicit
in-call recording indicator. Do not enable recording by default, and do not ship a
recorder that silently captures without user-visible state.

---

## 3. Target platform and non-negotiable constraints

| Item | Value |
|---|---|
| Language | Kotlin (see §2.3) |
| Min SDK | 26 (Android 8.0) |
| Target / compile SDK | Latest stable |
| UI | Jetpack Compose + Material 3, single-Activity |
| Async | Coroutines + Flow. No RxJava, no `AsyncTask`, no raw `Thread` |
| DI | Hilt |
| Local storage | Room (call history, contacts cache) + DataStore (prefs) |
| Secrets | Android Keystore–backed encryption for every SIP credential |
| Build | Gradle Kotlin DSL, version catalog (`libs.versions.toml`), convention plugins |

Platform requirements that will fail review if missed:

- **Telecom integration.** Register a **self-managed `ConnectionService`**. This is how the
  system arbitrates with cellular calls, Bluetooth, Android Auto, and the lock screen.
  Hand-rolling call notifications instead of using Telecom is a rejected design.
- **Foreground services with correct types.** `phoneCall`, `microphone`, and `camera` types
  declared in the manifest with matching runtime permissions (Android 14+ enforces this).
- **`CallStyle` notifications** for incoming/ongoing calls (Android 12+ requires them for
  full-screen intents in many cases). Request `USE_FULL_SCREEN_INTENT` correctly.
- **Runtime permissions**, requested in-context, each with a rationale and a denial path:
  `RECORD_AUDIO`, `CAMERA`, `POST_NOTIFICATIONS`, `BLUETOOTH_CONNECT`, `READ_CONTACTS`,
  `MANAGE_OWN_CALLS`.
- **16 KB page-size alignment** for all bundled `.so` files (required by recent Android
  releases). Verify at build time, not at crash time.
- **Edge-to-edge** layout, predictive back, and per-app language support.
- **Audio focus and routing** through `AudioManager`/`AudioDeviceInfo` + Telecom:
  earpiece / speaker / wired / Bluetooth SCO, with automatic route change on headset events.

---

## 4. Architecture

### 4.1 Layering

Strict Clean Architecture with the dependency rule enforced by the build, not by convention:

```
:app                 Compose UI, navigation, DI wiring, ConnectionService
:core:designsystem   Theme, reusable components
:core:common         Result types, dispatchers, logging facade
:domain              Entities, use cases, repository *interfaces*, SipEngine *interface*
                     — pure Kotlin module, zero Android dependencies
:data:account        SIP account repository impl, Room, Keystore crypto
:data:sip            The ONLY module that knows the SIP SDK exists
:data:calllog        Call history repository impl
:data:contacts       Contacts provider integration
:feature:*           dialer, calls, accounts, history, settings (UI + ViewModels)
```

- `:domain` is a **pure Kotlin/JVM module**. If it compiles against `android.jar`, the
  layering is wrong. This single rule prevents most Clean Architecture theatre.
- Dependencies point inward only. Enforce with a Gradle check or Konsist/ArchUnit test
  that **fails the build** on violation. An architecture that is only documented is
  an architecture that will be violated in week three.

### 4.2 Patterns

- **MVVM** at the UI edge: `ViewModel` exposes a single immutable `UiState` via
  `StateFlow`, plus a `Channel`/`SharedFlow` for one-shot events (navigation, toasts).
  No `LiveData`, no mutable state exposed to Compose.
- **UDF** — events flow up, state flows down. Compose screens are stateless and previewable.
- **Use cases** for anything with a business rule (`PlaceCallUseCase`,
  `RegisterAccountUseCase`, `TransferCallUseCase`). Pass-through use cases that only
  forward to a repository are noise — call the repository directly and say so.
- **Repository pattern** with interfaces in `:domain`, implementations in `:data:*`.
- **`Result`-style sealed error type** at every layer boundary. No exceptions as control
  flow across layers; map SIP response codes to domain errors explicitly.

### 4.3 The SIP abstraction (the most important interface in the codebase)

```kotlin
interface SipEngine {
    val registrationState: StateFlow<Map<AccountId, RegistrationState>>
    val activeCalls: StateFlow<List<CallSnapshot>>
    val incomingCalls: Flow<IncomingCall>

    suspend fun register(account: SipAccount): Result<Unit, SipError>
    suspend fun unregister(accountId: AccountId): Result<Unit, SipError>
    suspend fun placeCall(accountId: AccountId, target: SipUri, media: MediaProfile): Result<CallId, SipError>
    suspend fun answer(callId: CallId, media: MediaProfile): Result<Unit, SipError>
    suspend fun hangup(callId: CallId, reason: HangupReason): Result<Unit, SipError>
    suspend fun setHold(callId: CallId, held: Boolean): Result<Unit, SipError>
    suspend fun sendDtmf(callId: CallId, digit: DtmfDigit): Result<Unit, SipError>
    suspend fun transfer(callId: CallId, target: SipUri, type: TransferType): Result<Unit, SipError>
    // ...
}
```

Requirements:
- Declared in `:domain`, implemented once in `:data:sip`.
- A `FakeSipEngine` in `testFixtures` drives the entire app with **no SIP server** —
  scriptable to produce ringing, answer, 486 Busy, 408 Timeout, network loss, and re-INVITE.
  This fake is what makes the rest of the codebase testable, so build it early.
- Every SDK callback is converted to a Flow emission at the boundary. No SDK listener
  types cross into `:domain`.

### 4.4 Call state machine

Model call lifecycle as an **explicit finite state machine**, not a bag of booleans:

```
Idle → Outgoing(Calling → Ringing → EarlyMedia) → Connected
Idle → Incoming(Ringing) → Connected
Connected ⇄ Held ⇄ Resuming
Connected → Transferring → Terminated
Any → Terminated(reason)
```

Illegal transitions must be impossible to express (sealed classes), and the FSM must be
unit-tested exhaustively on the JVM. Mute / speaker / camera-on are **orthogonal attributes**
of `Connected`, not states — do not fold them into the state enum.

---

## 5. Functional scope

### 5.1 SIP account management

CRUD for **multiple** accounts (one active registration each, one designated default).

Fields — validate each, and reject silently-wrong input at entry:

| Field | Notes |
|---|---|
| Label | User-facing display name for the account |
| Username | SIP user part |
| Extension | Optional; may equal username |
| Auth username | Defaults to username when blank |
| Password | Keystore-encrypted at rest; never logged; never in a crash report |
| Display name | `From` header display name |
| SIP domain | Also the default registrar unless overridden |
| Registrar / registration server | Optional override |
| Outbound proxy | Optional; supports `;lr` |
| Port | Default 5060 (UDP/TCP), 5061 (TLS) |
| Transport | UDP / TCP / TLS |
| Registration expiry | Seconds; server value wins if lower |
| STUN server | Optional |
| TURN server + credentials | Optional; credentials encrypted |
| ICE / NAT policy | Enable ICE, enable STUN, keepalive interval |
| SRTP policy | Disabled / Optional / Mandatory (see §7) |
| Codec preferences | Ordered audio and video codec lists |
| Registration status | Derived, read-only: Registered / Registering / Failed(reason) / Unregistered |

Behaviour:
- **Login** = save account + register. **Logout** = unregister cleanly (`Expires: 0`),
  stop the service, and **wipe credentials from memory** — but keep the account row unless
  the user deletes it.
- **Editing an account that is registered** must unregister with the old identity before
  registering with the new one. Silent partial re-registration is a bug.
- **Deleting an account** with an active call is refused with a clear message.
- **Auto re-registration** on: expiry (refresh at ~50–90% of the granted expiry), network
  change (`ConnectivityManager` callback), app foreground, and transport failure —
  each with exponential backoff + full jitter, and a hard ceiling.

### 5.2 Calling

Audio and video, 1-to-1 and conference (via §2.2 dial-in model):

Outgoing • Incoming (with Telecom + `CallStyle` full-screen UI) • Hangup / reject •
Hold / resume (`sendonly`/`recvonly` re-INVITE, correct SDP direction handling) •
Mute • Speaker • Bluetooth SCO with automatic route switching • Wired headset •
DTMF (RFC 4733 telephone-event, with SIP INFO fallback — configurable) •
Blind transfer and attended transfer (`REFER` + `Replaces`) •
Camera switch, video mute, orientation handling •
Call recording hook (§2.6) • Call waiting / second call • Call history • Contacts integration.

**Call history:** Room-backed, records direction, remote identity, resolved contact name,
start/answer/end timestamps, duration, termination reason, account used, and media type.
Paged with Paging 3. Supports delete-one and clear-all.

**Contacts:** read via `ContactsContract` with permission handling; resolve inbound SIP
URIs to contact names; offer "call via SIP" from the app's own contact list. Cache
minimally and respect that contacts are personal data — no bulk upload anywhere.

---

## 6. Reliability and lifecycle

- **Foreground service** for the registration + active call, with correct service types,
  a persistent notification, and a hard rule: it stops when there is no registered
  account and no active call. A service that runs forever is a battery bug.
- **Network resilience.** Handle Wi-Fi↔cellular handover: detect via `NetworkCallback`,
  re-bind the transport, re-register, and — if a call is active — re-INVITE with the new
  ICE candidates rather than dropping the call.
- **Offline behaviour.** Show an honest registration state. Queue nothing that cannot be
  meaningfully retried. Call history and account settings remain fully readable offline.
- **Process death.** Restore active-call UI from Telecom + service state, not from
  in-memory ViewModel state.
- **WorkManager** for deferrable work only (log upload, history cleanup). Never for
  call signalling.

---

## 7. Security

- **Credentials:** encrypt with a Keystore-backed key (`MasterKey`/`EncryptedFile`, or
  equivalent AES-GCM wrapping if you avoid the deprecated `EncryptedSharedPreferences`).
  Choose one, state the choice, do not hand-roll crypto.
- **Signalling:** support and default to **TLS 1.2+** with proper certificate validation.
  Never ship a permissive `TrustManager`. Offer an explicit, clearly-labelled,
  non-default setting for a custom CA if enterprise deployment requires it.
- **Media:** SRTP with an explicit policy (Disabled / Optional / Mandatory); ZRTP or
  DTLS-SRTP where the stack supports it. Mandatory means the call **fails** rather than
  going cleartext.
- **Logging:** a `Logger` facade with levels. Release builds must not log SIP messages,
  headers, credentials, phone numbers, or contact data. Add a debug-only, user-initiated
  SIP trace that is off by default and redacts `Authorization`/`Proxy-Authorization`.
- **Hardening:** `android:allowBackup="false"`, `FLAG_SECURE` on credential screens,
  no exported components without a permission, no cleartext traffic
  (`usesCleartextTraffic="false"` + network security config), R8 with a checked-in
  mapping and keep rules for the SIP SDK.
- **Dependencies:** pin versions, run a vulnerability check in CI.

---

## 8. Testing (part of the deliverable, not a follow-up)

| Layer | What | Tooling |
|---|---|---|
| Domain | Every use case, the call FSM exhaustively, backoff/jitter maths, SIP URI parsing and validation | JUnit 5, kotlin.test, Turbine |
| Data | Repository impls against in-memory Room; credential encryption round-trip; `SipEngine` mapping of SDK callbacks → domain events | Robolectric where needed |
| ViewModel | State transitions against `FakeSipEngine`, including error and race paths | Turbine, `runTest` |
| UI | Critical journeys: add account → register → place call → in-call controls → hangup → history entry | Compose UI test with `FakeSipEngine` |
| Integration | Registration and a full call against a **local SIP server in Docker** (Kamailio or Asterisk/FreeSWITCH) — include the compose file in the repo | Instrumented |
| Architecture | Layer-dependency rules; no SDK imports outside `:data:sip` | Konsist or ArchUnit |

Target ≥ 80% line coverage in `:domain` and `:data:*`. Coverage in `:app`/UI is not a
goal — journey coverage is. Report the actual numbers; do not claim a target as a result.

---

## 9. Delivery plan — build in phases, stop and report between each

Do **not** attempt the whole app in one pass. After each phase: build, run the tests,
and report what works, what does not, and what you assumed.

1. **Foundations** — Gradle setup (version catalog, convention plugins), module skeleton,
   Hilt, `:domain` entities + `SipEngine` interface + `FakeSipEngine`, architecture tests,
   CI (build + unit tests + lint + detekt).
2. **Accounts** — Room + Keystore-encrypted storage, validation, CRUD UI, account list.
3. **Registration** — real `SipEngine` impl, foreground service, backoff/jitter, network
   change handling, live registration status UI.
4. **1-to-1 audio calling** — Telecom `ConnectionService`, outgoing + incoming, `CallStyle`
   notification, in-call screen, hold/mute/speaker/Bluetooth, DTMF, call FSM.
5. **Call history + contacts** — Room + Paging, `ContactsContract` resolution.
6. **Video** — camera lifecycle, rendering, orientation, video mute, camera switch.
7. **Transfer, call waiting, second call, recording hook.**
8. **Conference (dial-in)** — conference session model, participant list, per-participant UI.
9. **Hardening** — R8, security review against §7, battery/memory profiling, docs.

---

## 10. Deliverables

- Compiling, installable, runnable app — `./gradlew assembleDebug` and
  `./gradlew test` both green from a clean clone.
- `docs/architecture.md` — HLD: module graph, layer diagram, call-flow sequence diagrams
  (register, outgoing, incoming-via-push, hold, transfer), threading model, and the
  **explicit answers to every DECIDE in §2**.
- `docs/lld.md` — key class responsibilities, the call FSM diagram, the `SipEngine`
  contract and its error taxonomy, the data model (Room schema).
- `docs/security.md` — credential handling, transport/media security, logging policy,
  the SIP-stack licence position and its distribution implications.
- `docs/testing.md` — how to run unit, instrumented, and Dockerised integration tests.
- `README.md` — setup, required config (FCM key, test SIP account), build variants.
- KDoc on every public type in `:domain` and on the `SipEngine` contract. Comments explain
  **why**, never restate what the code says.

---

## 11. Explicitly out of scope

- **No** SIP server, SBC, media server, or conference bridge implementation. The app is a
  client; the infrastructure is assumed to exist and its contract must be stated, not built.
- **No** custom SIP stack written from scratch.
- **No** messaging, presence/BLF, or chat unless separately requested.
- **No** iOS, web, or desktop client.
- **No** analytics or telemetry SDK, and no third-party crash reporter, without explicit
  approval — this app handles call metadata and credentials.
- **No** E2E media encryption beyond what the SIP stack provides (SRTP/ZRTP/DTLS-SRTP).

---

## 12. Definition of done — each item is binary and checkable

1. `./gradlew clean build` passes from a fresh clone with no local state.
2. `:domain` has zero Android dependencies, proven by a failing build if one is added.
3. No `org.linphone.*` / `org.pjsip.*` import exists outside `:data:sip`, proven by an
   architecture test.
4. The whole app runs end-to-end against `FakeSipEngine` with no network and no SIP server.
5. Two SIP accounts can be saved, registered simultaneously, and edited/deleted; deleting
   a registered account unregisters it first.
6. Registration recovers automatically after: airplane-mode toggle, Wi-Fi→cellular
   handover, and a registrar restart — with backoff, verified by log evidence.
7. An incoming call rings on the lock screen via `CallStyle` + Telecom, and is answerable
   from there.
8. A 1-to-1 audio call completes with working hold/resume, mute, speaker, Bluetooth route
   switch, and DTMF, verified against a local Asterisk/Kamailio in Docker.
9. A 1-to-1 video call establishes bidirectional video; camera switch and video mute work.
10. Blind and attended transfer each complete against the local server.
11. A dial-in conference call connects and shows the participant list.
12. Credentials are unreadable in a filesystem dump of app data; no credential, SIP header,
    or phone number appears in a release-build logcat capture.
13. TLS + SRTP-mandatory calls succeed; SRTP-mandatory against a cleartext-only peer
    **fails the call** rather than downgrading.
14. Unit-test coverage in `:domain` and `:data:*` is ≥ 80%, with the real number reported.
15. Every `DECIDE` in §2 is answered in `docs/architecture.md`.
16. A one-hour idle registered session shows no leaked wake locks and no unbounded memory
    growth (LeakCanary clean, heap dump reviewed).

---

## 13. How to work

- **Ask before assuming.** If a DECIDE is unanswered, or the SIP infrastructure contract
  (push model, conference server, licence) is unknown, ask — do not invent a plausible
  answer. An invented config key or header name is a production outage.
- **Ground every claim.** When you name a SIP header, config key, API, or file path,
  it must be one that actually exists in the SDK or platform you chose.
- **Report honestly.** If a phase's tests fail, say so and show the output. If you skipped
  something, name it. Never describe unverified code as working.
