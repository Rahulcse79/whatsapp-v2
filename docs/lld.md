# Low-level design

Written in Task 67, from the code as built. Where a name here does not exist in the tree,
that is a defect in this document — everything below is checkable with a grep.

Companion documents: [`architecture.md`](architecture.md) for the HLD, the module graph
and the sequence diagrams; [`security.md`](security.md) for credentials, transport and
recording; [`testing.md`](testing.md) for how to run any of it.

---

## 1. The call state machine

The single most important object in `:domain`. Pure, stateless, and exhaustively tested:
`CallStateMachineTest` enumerates every (state, event) pair and asserts that every pair
**absent** from its table is rejected, so adding a transition without documenting it fails
the build.

```mermaid
stateDiagram-v2
    [*] --> Idle

    Idle --> Calling: Dial
    Idle --> Incoming: IncomingInvite

    state "Outgoing.Calling" as Calling
    state "Outgoing.Ringing" as Ringing
    state "Outgoing.EarlyMedia" as Early

    Calling --> Ringing: RemoteRinging
    Calling --> Early: RemoteEarlyMedia
    Ringing --> Early: RemoteEarlyMedia
    Early --> Ringing: RemoteRinging
    Calling --> Connected: RemoteAnswered
    Ringing --> Connected: RemoteAnswered
    Early --> Connected: RemoteAnswered

    Incoming --> Connected: LocalAnswered

    Connected --> Held: LocalHold / RemoteHold
    Held --> Held: the other side also holds
    Held --> Resuming: LocalResume (nobody else holding)
    Held --> Connected: RemoteResume (nobody else holding)
    Resuming --> Connected: ResumeConfirmed
    Resuming --> Held: RemoteHold

    Connected --> Transferring: StartTransfer
    Held --> Transferring: StartTransfer (attended)
    Transferring --> Connected: TransferFailed (blind)
    Transferring --> Held: TransferFailed (attended — back to the hold it came from)
    Transferring --> Terminated: TransferSucceeded

    Connected --> Terminated: Terminate
    Held --> Terminated: Terminate
    Resuming --> Terminated: Terminate
    Incoming --> Terminated: Terminate
    Calling --> Terminated: Terminate
    Terminated --> [*]
```

### The four decisions inside it

**`HoldParty`, not a boolean.** Both ends can hold at once. With a boolean, resuming from
`Held(BOTH)` returns `Connected` while the far end is still holding, and the audio does not
come back — the "resume did nothing" bug, shipped.

**Controls are attributes, not states.** Mute, audio route, video and recording each vary
independently and none changes what the call may do next. Folding them into `CallState`
would multiply the state count by sixteen and make the table unreadable, for nothing.

**`Transferring` remembers where it came from.** An attended transfer sends its REFER from
a *held* call. Without `heldBy`, a failed one returns to `Connected` — a screen claiming
audio is flowing while the far end still holds.

**Rejection is a value, not a null.** `TransitionResult.Rejected` carries the state and the
event that was refused, so "the UI offered an action the call could not perform" surfaces
in a test rather than being swallowed at run time.

---

## 2. `SipEngine` — the contract

The seam between the application and PJSIP (§4.3, ADR-006). Everything above it is written
against `:domain` types and can be built, run and tested with `FakeSipEngine` — no server,
no network, no device. It splits into four role interfaces so a ViewModel that only toggles
the speaker does not have `transfer` in scope.

| Role | What it owns |
|---|---|
| `SipRegistrar` | `registrationState`, register / unregister / refresh, RFC 8599 push params |
| `SipCallController` | `activeCalls`, `incomingCalls`, `endedCalls`, `transferEvents`; place, answer, reject, hangup, hold, DTMF, transfer |
| `SipMediaController` | mute, audio route, video enable, camera switch, `videoRequests` + `respondToVideoRequest` |
| `SipConferenceController` | `conferences`, `joinConference` |

### The five promises, and why each exists

1. **Only `:domain` types cross it.** No `org.pjsip.*` anywhere above `:data:sip` — and
   and no import of the removed stack anywhere at all, since ADR-006 —
   enforced by architecture Rule 2 and a CI step. This is what makes `FakeSipEngine` a
   drop-in rather than an approximation.
2. **Every `suspend` function is main-safe.** Implementations move to their own dispatcher
   internally, so no caller needs `withContext`.
3. **Cancelling abandons the result, not the SIP transaction.** A cancelled `placeCall` may
   still have put an INVITE on the wire; the call appears in `activeCalls` regardless.
   Never assume cancelling the call to this interface cancelled the call.
4. **Errors are values.** `Outcome<T, SipError>`, not exceptions. A dropped registration is
   an expected outcome of a mobile network, and a caller that can forget a `catch` will.
5. **Idempotence.** Unregistering an unregistered account, or hanging up an ended call,
   succeeds quietly — the outcome the caller wanted is already true.

### The three streams that never replay

`incomingCalls`, `endedCalls` and `transferEvents` are `Flow`, not `StateFlow`, and are
buffered rather than replayed. Replay would re-ring a call answered minutes ago, write a
second call-log row on every re-collection, and raise a second alarm about a transfer that
already failed. The buffer is 64, far more than a phone will ever have at once.

> **Correction, 2026-09-09.** The paragraph above used to end "Dropping would lose the
> missed call nobody was collecting for — which is precisely the call that matters".
> **That states an intent the code does not implement.** `MutableSharedFlow` defaults
> `onBufferOverflow` to `SUSPEND`, and on such a flow `tryEmit` returns `false` without
> emitting when the buffer is full. `endedCalls` (`PjsipSipEngine.kt:322`),
> `transferEvents` (`:471`, `:484`) and `videoRequests` (`:719`) all emit with `tryEmit`
> and **discard the boolean** — so all three drop silently. Only `incomingCalls` uses a
> suspending `emit` (`:772`) and therefore actually holds the promise.
>
> The per-stream capacity **and policy** table is in `docs/data-structures.md` §1.2, which
> is now the single source of truth for this; the finding is `docs/reconciliation.md` A-5.
> Below the seam the four gateway streams declare `DROP_OLDEST` explicitly
> (`RealPjsipCoreGateway.kt:114-148`), which is correct there — the emitter is a pjsua2
> worker thread and suspending it stops the stack.

### Error taxonomy

`SipError` has one case per thing a user can be told, and `CallMessages` gives each exactly
one sentence. Both `when`s are exhaustive with **no `else`**, so the compiler refuses a new
case until somebody has decided what the user is told about it.

| Group | Cases | Becomes |
|---|---|---|
| Authentication | `AuthenticationFailed`, `Forbidden` | a message naming the credential or the permission |
| Addressing | `NotFound`, `BadRequest` | "that address does not exist" / "could not be dialled" |
| Callee state | `Busy`, `Declined`, `TemporarilyUnavailable`, `Timeout`, `Cancelled` | distinct sentences — a busy line is not a refusal |
| Server | `ServiceUnavailable`, `ServerError` | `Retry-After` is honoured by the backoff, never read out |
| Media | `MediaNegotiationFailed(encryptionRequired)` | an SRTP failure says so; reporting it as "no shared codec" would hide the part that matters |
| Transport | `TransportFailure`, `NetworkUnavailable` | "could not reach the server" / "no connection" |
| Local | `UnknownAccount`, `UnknownCall`, `InvalidState`, `EngineUnavailable`, `CallNotPermitted` | states rather than failures |

`HangupReason` is the parallel vocabulary for the **call log**, and they are deliberately
different: a 486 is `BUSY` in the log and "that line is busy" on screen, but a 404 is a
terminated call in the log and "that address does not exist" on screen.

---

## 3. Class responsibilities

Only the classes that hold a decision. Anything absent is plumbing.

### `:domain` — pure, no Android

| Class | Responsibility |
|---|---|
| `CallStateMachine` | (state, event) → state. The authority on what a call may do next |
| `CameraPolicy` | Given **every** call, who holds the camera. Not "should this call release it" |
| `CallWaitingPolicy` | The ordered steps for a second call and for a swap. Order is the requirement |
| `RecordingPolicy` | The one gate in front of a recording (§2.6) |
| `RegistrationBackoff` | Exponential with jitter. No clock, no I/O |
| `RegistrationRecoveryPolicy` | What a network change means for one account. Never retries what a user must fix |
| `ExpiryRefreshPolicy` | When to re-REGISTER before a binding lapses |
| `AccountValidator` | Every §5.1 field rule, as data |
| `DialledTarget` | `1002` and `sip:1002@domain` are the same destination |
| `SrtpPolicy.permits` | DoD 13, as a function of two values |
| `ConferenceSession` | N participants, SFU-ready; `rosterAvailable` separates "empty" from "unknown" |

### `:data:sip`

| Class | Responsibility |
|---|---|
| `PjsipSipEngine` | Bookkeeping and orchestration. Holds no rule that could live in `:domain` |
| `RealPjsipCoreGateway` | **The only class that names the SDK.** Callbacks → buffered flows; every call into PJSIP confined to one registered thread |
| `CallStateMapper` | Stack call states → FSM events. Pure, so it is testable off-device |
| `TransferEventMapper` | REFER progress → `TransferEvent`. The 202 is *accepted*, never *succeeded* |
| `ConferenceMapper` | Bridge roster → `ConferenceSession`, preserving "no roster" |
| `RegistrationStateMapper` | Stack registration states → `RegistrationState` |
| `RegistrationRecoveryCoordinator` | Owns the retry timers; asks `RegistrationRecoveryPolicy` what to do |
| `PjsipCallRecorder` | Gate, lifecycle, indicator. Never chooses where bytes go |
| `EncryptedRecordingStore` | AES-GCM under a Keystore key; deletes the plaintext before reporting success |

### `:app`

| Class | Responsibility |
|---|---|
| `TelecomCallRegistry` | The `PlatformCallRegistry` port, backed by `ConnectionService` |
| `SipConnectionService` / `SipConnection` | Telecom's side of a self-managed call |
| `RegistrationService` | Foreground service; renders the call **and** the registration summary in one notification |
| `ForegroundServiceTypes` | Which FGS types are true *and permitted* right now (Android 14) |
| `ServiceRunPolicy` | Whether the service should exist at all (§6) |
| `CallAudioCoordinator`, `AudioRoutePolicy`, `ProximityLock` | Routing, focus, and the screen-off lock |
| `PushWakePolicy`, `SipMessagingService` | The FCM wake path (ADR-004) |
| `AndroidCameraAvailability` | "No camera" and "declined" answered as one boolean |

---

## 4. Room schemas

Both databases export their schema to `schemas/` and both are committed — CI fails if they
drift. Without an exported schema Room cannot verify a migration, and a schema change ships
as silent data loss instead of a build error.

### `sip_accounts` (`:data:account`, version 1)

25 columns; one row per configured account. The ones that matter:

| Column | Note |
|---|---|
| `id` | Primary key; an app-generated UUID, never the SIP identity |
| `password_ciphertext` | **AES-GCM, never plaintext.** See `security.md` |
| `turn_password_ciphertext` | Same treatment; a TURN credential is a credential |
| `username` + `domain` | **Unique index.** Two bindings for one identity fight over one registrar record |
| `is_default` | Indexed; exactly one row is default, maintained on write |
| `srtp_policy`, `transport` | Per account, because one identity may mandate SRTP and another cannot do it |

### `call_log` (`:data:calllog`, version 1)

12 columns; one row per call, including the ones that never connected.

| Column | Note |
|---|---|
| `started_at_epoch_millis` | Indexed — the log is read newest-first, always |
| `direction` + `answered_at_epoch_millis` | Composite index; this pair *is* the "missed" filter |
| `answered_at_epoch_millis` | Null means never answered. That is the missed-call definition, not a flag |
| `contact_name` | Resolved at write time and **never leaves the device** (architecture Rule 9) |
| `reason` | The `HangupReason`, so a failed call says why rather than merely ending |

Settings live in DataStore rather than Room (`:data:settings`): they are a handful of
scalars with no relationships, and a database for them would be a migration surface for
nothing.

---

## 4b. The types the native mandate adds

Three of them. Each gets the full treatment: responsibility in one sentence, the invariant
it maintains, its concurrency contract, its error model, and its idempotence where that
applies. All three are **PROPOSED** — none exists yet.

### 4b.1 `CodecAudit` — the result of the startup codec audit

**Lives in `:domain`.** It is a value, not a service: the thing that computes it lives in
`:data:sip` because only that module may touch `Endpoint.codecEnum2()`.

**Responsibility.** Report, for one endpoint start, which declared codecs the running
library actually registered — and for each one that did not, why.

**The shape:**

```kotlin
data class CodecAudit(
    val registeredAudio: List<RegisteredCodec>,
    val registeredVideo: List<RegisteredCodec>,
    val absent: Map<DeclaredCodec, AbsenceReason>,
)

sealed interface AbsenceReason {
    /** The build was configured without it. `PJMEDIA_HAS_*_CODEC 0`. */
    data object NotCompiled : AbsenceReason

    /** The flag was 1 and the library did not register it. A build defect. */
    data object RegistrationFailed : AbsenceReason

    /** Registered, but its model files are missing or fail their manifest. */
    data class ModelFilesUnusable(val detail: String) : AbsenceReason

    /** Registered and selectable, and no peer has ever accepted it. */
    data object NoPeerAccepts : AbsenceReason
}
```

**The invariant.** `absent.keys` and the registered lists are **disjoint**, and their union
is exactly the declared feature set. A codec that appears in neither is a bug in the audit,
not in the build — and the test for this is a set-equality assertion, not a spot check.

**Why `NoPeerAccepts` is a first-class case and not a footnote.** It is the state **Opus is
in today**: compiled, registered, first in `CodecPreferences.DEFAULT`, and refused by every
call because the deployed FreeSWITCH does not offer it
(`docs/reconciliation.md` A-1b). Without this case the UI can only say "Opus: available",
which is true and useless. With it the UI can say **"Opus: built, no peer accepts it"**,
which is the sentence a user or a support engineer can act on.

**Concurrency contract.** Computed **once per endpoint start**, on the single `pjsip`
executor thread — the only thread that may call `codecEnum2()`. Published as an immutable
value through a `StateFlow`. Main-safe to read; never recomputed in the call path.

**Error model.** `Outcome<CodecAudit, SipError>`. A failure to enumerate is
`EngineUnavailable`, not an exception — the endpoint not being up is an ordinary state, not
a programming error.

**Idempotence.** Reading it twice returns the same value. There is no "refresh": a new
endpoint start produces a new audit, which is the only event that can change the answer.

**What it must report at ERROR, once, with the codec id:** every `RegistrationFailed`. That
is a build defect and master prompt §2.5 requires it be reported as one. `NotCompiled` is
INFO — it is a decision, not a defect.

### 4b.2 `LyraModelStore` — **Exit A of the §2.4 gate only**

Built only if ADR-008 resolves to Exit A. Specified here so the gate's cost is known.

**Responsibility.** Extract four asset files to a readable path, verify them against a
content-hash manifest, and hand back the path `CodecLyraConfig.modelPath` needs — or a typed
failure.

**The invariant, and it is the whole class.** **`modelPath` is never handed out
unverified.** Without the model files the codec **registers and then fails when a stream
opens**, which is worse than not having it: it advertises a capability it cannot deliver. A
truncated asset must be a loud startup failure, not a dead call.

**Concurrency contract.** Suspending, main-safe, on `Dispatchers.IO`. It performs file I/O
and must never be called from the `pjsip` executor thread — that thread must not block, and
extraction of ~3.6 MB is a block.

**Error model.** `Outcome<Path, ModelStoreError>` with cases for *asset missing*, *hash
mismatch* and *extraction failed*. Three cases because three different things went wrong and
a caller may reasonably retry one of them.

**Idempotence — and this is what makes it cheap.** A second call on an already-extracted,
still-valid set **does no I/O**: it hashes nothing and copies nothing, it checks the
recorded version marker and returns the same path. O(size) once per version, never per call.

### 4b.3 `NativeLibraryInventory` — the expected `.so` set per ABI

**Responsibility.** Assert that this APK carries every native library it needs, for the ABI
it is running on.

**The set, verified from the green run:** exactly two entries per ABI —
`libpjsua2.so` and `libc++_shared.so`. `libc++_shared.so` is not optional: `libpjsua2.so`
links against the NDK's shared C++ runtime, and without it the load fails with *"library
libc++\_shared.so not found"* — an APK that installs and cannot run
(`.github/workflows/build-pjsip.yml:512-519`).

**Half of this already exists, and it is the better half.** CI asserts the set at AAR
assembly (`:604-607`) and again at APK packaging (`:781-786`), and the packaging assertion
fails the build on a missing library. **That is N-6 satisfied at build time.**

**What is proposed is the second line of defence: the same check at startup.** Under
ADR-007 the *build* is what must fail — `pjsip/build.gradle.kts:46` currently catches this
at configuration time, and DoD 24 moves it to a hard build failure. The runtime check stops
being the first line and becomes the one that catches an ABI split, a repackaging, or a
device-side install that dropped a library.

**Concurrency contract.** Runs once, at endpoint creation, on the `pjsip` executor thread,
before `libCreate`. O(1) per ABI — it is a set comparison, not a filesystem walk.

**Error model.** Fails loudly. This is deliberately **not** an `Outcome`: there is no
recovery and no caller decision to make. A missing native library is not a state the app
can be in and still be an app.

### 4b.4 The one assertion that is not a type — thread confinement

**DoD 4**, and it is a check rather than a class.

Every thread that calls into pjsua2 must be registered with the library first, and
`Endpoint::libRegisterThread` allocates a descriptor **freed only when the library is
destroyed** (`docs/pjsip-migration.md:44-52`). The adapter's answer is a single-threaded
executor that every call is posted to, and `libCreate` registers its own caller
(`:56-60`) — so no explicit registration is needed and no descriptor leaks.

**That is a correct design held in place by a convention.** The assertion makes it an
enforced invariant: capture the executor's thread identity once at `libCreate`, compare on
every entry to a pjsua2 call, and **throw in a debug build**. Release builds do nothing —
the check must not be the thing that crashes a shipped call.

**What it prevents:** a `SIGSEGV` in a stack trace with no Kotlin frames. This is the top
source of native crashes in pjsua2 apps and it is silent until it is fatal.

**Proven by a test that trips it deliberately** — DoD 4 requires the assertion to be shown
firing, not merely to exist. A rule that never fires reads like protection while providing
none, which is the same argument architecture rule 4 makes about the layer rules.

**Owning the build makes this more important, not less.** A stack you patched is a stack
whose crashes are yours to explain.

---

## 5. What is deliberately not here

- **A DI diagram.** Hilt's graph is the sum of the `@Module`s, and a drawing of it is
  stale the day a binding moves. `HiltGraphTest` asserts the graph builds and resolves,
  which is the property a diagram would be trying to claim.
- **Per-class UML.** The KDoc on each class says why it exists; a diagram that repeats the
  class list adds a second thing to keep in step.
