# Task Breakdown — Native Android SIP Client

Derived from [`android-sip-app-prompt.md`](android-sip-app-prompt.md). Every task traces
back to a section of that document (`§`) and, where applicable, to a Definition-of-Done
item (`DoD n`, from §12).

## How to use this file

- Work **top to bottom**. The order is a valid topological sort of the dependency graph.
- Each task is sized to be finishable in one focused sitting and to leave the repo
  **green** (`./gradlew build` passes) when it is done.
- **`Done when`** boxes are binary. If you cannot tick one, the task is not finished —
  do not move on and do not tick it optimistically.
- If a task turns out to need a decision that Task 1 did not settle, stop and ask.
  Do not invent a config key, header name, or SDK API. (§13)

**Task format**

```
### Task N — Title
Depends on · Prompt refs · Modules
Build:      what to produce
Done when:  binary checks
```

## Phase map

| Phase | Tasks | Outcome |
|---|---|---|
| 0 — Decisions | 1 | The three blocking DECIDEs are answered and recorded |
| 1 — Foundations | 2–15 | Modules, DI, domain, FakeSipEngine, arch tests, CI |
| 2 — Accounts | 16–24 | Encrypted multi-account CRUD, fully offline |
| 3 — Registration | 25–33 | Real SIP stack registers and self-heals |
| 4 — Audio calling | 34–46 | 1-to-1 audio calls via Telecom, end to end |
| 5 — History & contacts | 47–50 | Call log and contact resolution |
| 6 — Video | 51–54 | 1-to-1 video calling |
| 7 — Transfer & recording | 55–58 | REFER, call waiting, recording architecture |
| 8 — Conference | 59–61 | Dial-in conference |
| 9 — Hardening | 62–68 | Security, perf, docs, DoD sweep |

---

# Phase 0 — Blocking decisions

### Task 1 — Answer the three blocking DECIDEs and open the decision record
**Depends on:** nothing · **Prompt refs:** §2.2, §2.4, §2.5, §10, DoD 15 · **Modules:** `docs/`

No code until this is done — each answer changes what gets built.

Build:
- `docs/architecture.md` with a **Decisions** section answering:
  1. **SIP stack** — liblinphone or PJSIP. Record the version, the artifact coordinates,
     and the **licence position**: GPL-compliant open distribution, or a commercial licence.
     If the app ships closed-source, this is a purchase, not a preference.
  2. **Conference model** — which conference server (FreeSWITCH `mod_conference`,
     Asterisk ConfBridge, Janus, vendor SBC), and dial-in MCU (default) vs SFU.
  3. **Push model** — does the SBC support RFC 8599 (`pn-provider`/`pn-param`/`pn-prid`),
     or must a separate service send the FCM high-priority data message? State the
     server contract either way.
- Record the SIP infrastructure you will test against (domain, transport, test extensions).

Done when:
- [x] All three decisions are written down with a one-paragraph rationale each
      → `docs/architecture.md` ADR-001 (stack), ADR-003 (conference), ADR-004 (push),
      plus ADR-005 (test target) which the infrastructure answer forced
- [x] The licence answer names a specific licence and says whether money is required
      → ADR-002: GPLv3 working assumption; **commercial licence required if closed-source**,
      carried as an unresolved cost item, not silently assumed away
- [x] The push answer states the exact server→app contract (message shape, fields)
      → ADR-004: RFC 8599 `pn-*` Contact params client-side + a normative four-field
      FCM payload contract (`call_id`, `account_id`, `sent_at`, `type`)
- [x] Anything still unknown is listed as an open question addressed to the stakeholder,
      not filled in with a guess
      → `docs/architecture.md` §3, Q1–Q8, each with an owner and a deadline

**Status: COMPLETE.** Q1/Q2 (licence) block release, not development — Phases 1–8 proceed.
Q3/Q4 must be answered before Task 32.

---

# Phase 1 — Foundations

### Task 2 — Repository and Gradle skeleton
**Depends on:** 1 · **Prompt refs:** §3 · **Modules:** root

Build:
- Git repo, `.gitignore`, `.editorconfig`, JDK/toolchain pin.
- Gradle **Kotlin DSL** only, `gradle/libs.versions.toml` version catalog,
  `settings.gradle.kts` with an empty module list, `gradle.properties`
  (AndroidX, non-transitive R classes, configuration cache on).
- `minSdk 26`, latest stable `compileSdk`/`targetSdk`.

Done when:
- [x] `./gradlew build` passes on a clean clone
      → verified in CI (run 33845555839), which checks out fresh every time
- [x] No `.gradle` (Groovy) build files exist anywhere
      → asserted by a CI step, not by inspection
- [x] Every dependency version lives in the version catalog; none is inline
      → asserted by a CI step that greps for inline `group:name:version` literals

**Status: COMPLETE.** Builds run on GitHub Actions, never locally (ADR-006).
Toolchain pinned **from the bootstrap workflow's version report**, not from memory:
Gradle 9.7.1 · AGP 9.4.0 · Kotlin 2.4.10 · JDK 21 runs Gradle · minSdk 26 ·
compileSdk/targetSdk 36 (runner confirms android-34/35/36 installed).
The Gradle wrapper jar is generated by the bootstrap workflow, since a binary
cannot be authored by hand.

### Task 3 — Gradle convention plugins
**Depends on:** 2 · **Prompt refs:** §3, §4.1 · **Modules:** `build-logic`

Build:
- `build-logic` included build with convention plugins:
  `android.application`, `android.library`, `android.compose`, `jvm.library`
  (for `:domain`), `hilt`, `test`.
- Shared Kotlin compiler options, Java toolchain, lint baseline, detekt + ktlint config.

Done when:
- [ ] A new module needs ≤ 5 lines of `build.gradle.kts` beyond a convention plugin id
- [ ] `./gradlew detekt lint` passes with zero warnings suppressed by baseline abuse

### Task 4 — Module skeleton
**Depends on:** 3 · **Prompt refs:** §4.1 · **Modules:** all

Build:
- Create every module from §4.1 as an empty, compiling shell:
  `:app`, `:core:common`, `:core:designsystem`, `:domain`,
  `:data:account`, `:data:sip`, `:data:calllog`, `:data:contacts`,
  `:feature:dialer`, `:feature:calls`, `:feature:accounts`, `:feature:history`, `:feature:settings`.
- `:domain` uses the **JVM library** plugin, not the Android library plugin.

Done when:
- [ ] `./gradlew build` passes with all modules present
- [ ] `:domain/build.gradle.kts` has no Android plugin and no `android {}` block
- [ ] Adding `implementation(libs.androidx.core)` to `:domain` **fails** the build

### Task 5 — `:core:common` primitives
**Depends on:** 4 · **Prompt refs:** §4.2, §7 · **Modules:** `:core:common`

Build:
- A sealed `Result<T, E>` (or `Outcome`) type with `map`/`flatMap`/`fold`, plus tests.
- `DispatcherProvider` interface + production and test implementations.
- `Logger` facade with levels and a `redact()` helper. **No** direct `android.util.Log`
  calls anywhere else in the codebase from here on.

Done when:
- [ ] `Result` has ≥ 95% test coverage
- [ ] A lint/detekt rule fails the build on any direct `android.util.Log` usage outside `:core:common`
- [ ] `Logger` drops `VERBOSE`/`DEBUG` in release by construction, not by an `if`

### Task 6 — Hilt wiring and Application class
**Depends on:** 5 · **Prompt refs:** §3, §4.2 · **Modules:** `:app`

Build:
- `@HiltAndroidApp` Application, `AndroidManifest.xml`, base modules binding
  `DispatcherProvider` and `Logger`.

Done when:
- [ ] The app installs and launches to an empty screen
- [ ] A trivial `@Inject` into the single Activity resolves at runtime

### Task 7 — Domain value types
**Depends on:** 5 · **Prompt refs:** §4.1, §5.1 · **Modules:** `:domain`

Build:
- `SipUri` (parse + validate + render, RFC 3261 user/host/port/params subset),
  `AccountId`, `CallId`, `DtmfDigit`, `Transport`, `SrtpPolicy`, `MediaProfile`,
  `HangupReason`, `TransferType`, `RegistrationState`.
- Value classes / sealed types — no `String` typing for identifiers.

Done when:
- [ ] `SipUri.parse` has table-driven tests covering valid, invalid, IPv6-literal,
      port-bearing, and parameter-bearing inputs
- [ ] Constructing an invalid `SipUri` is impossible (parse returns `Result`, no public ctor)

### Task 8 — `SipAccount` entity and validation rules
**Depends on:** 7 · **Prompt refs:** §5.1 · **Modules:** `:domain`

Build:
- `SipAccount` covering **every** field in the §5.1 table.
- A pure `AccountValidator` returning per-field errors: required fields, port range,
  transport/port coherence (5061 ⇄ TLS default), auth-username fallback to username,
  expiry bounds, STUN/TURN URL shape, non-empty codec list.

Done when:
- [ ] Every field in the §5.1 table exists on the entity
- [ ] `AccountValidator` is tested per rule, including the auth-username fallback
- [ ] `SipAccount.toString()` cannot leak the password (test asserts this)

### Task 9 — Call finite state machine
**Depends on:** 7 · **Prompt refs:** §4.4, DoD 4 · **Modules:** `:domain`

Build:
- Sealed `CallState`: `Idle`, `Outgoing(Calling|Ringing|EarlyMedia)`, `Incoming(Ringing)`,
  `Connected`, `Held`, `Resuming`, `Transferring`, `Terminated(reason)`.
- A `CallStateMachine` where illegal transitions are **unrepresentable**.
- Mute / speaker / camera-on / recording are **attributes of `Connected`**, not states.

Done when:
- [ ] Every legal transition has a test; an exhaustive test asserts all other
      (state, event) pairs are rejected
- [ ] No boolean flags model call phase anywhere in `:domain`
- [ ] The FSM compiles in `:domain` with zero Android imports

### Task 10 — `SipEngine` interface and error taxonomy
**Depends on:** 8, 9 · **Prompt refs:** §4.3, DoD 3 · **Modules:** `:domain`

Build:
- The `SipEngine` interface exactly as sketched in §4.3, plus `CallSnapshot`,
  `IncomingCall`, `ConferenceSession` (N participants — shaped for §2.2 option b).
- `SipError` sealed hierarchy mapping SIP responses to domain meaning:
  `AuthFailed(401/407)`, `NotFound(404)`, `Busy(486)`, `Timeout(408)`,
  `TemporarilyUnavailable(480)`, `ServiceUnavailable(503, retryAfter)`,
  `TransportFailure`, `MediaNegotiationFailed`, `Cancelled`.
- Full KDoc on every member: threading, cancellation, and emission guarantees.

Done when:
- [ ] The interface exposes only domain types — no SDK types in any signature
- [ ] Every `SipError` case documents which SIP responses map to it
- [ ] The interface compiles in `:domain` with zero Android imports

### Task 11 — `FakeSipEngine`
**Depends on:** 10 · **Prompt refs:** §4.3, §8, DoD 4 · **Modules:** `:domain` (`testFixtures`)

This is what makes the rest of the project testable. Build it properly.

Build:
- A scriptable `FakeSipEngine` driving: successful registration, auth failure,
  registrar timeout, incoming call, remote answer, remote hangup, 486 Busy,
  408 Timeout, network loss mid-call, hold/re-INVITE, and transfer.
- Deterministic virtual time (`TestScope`), no real delays.

Done when:
- [ ] A test can script a full incoming-call-answered-then-remote-hangup sequence in < 20 lines
- [ ] It is published via `testFixtures` and consumable from `:feature:*` and `:app`
- [ ] No `Thread.sleep` and no wall-clock dependency anywhere in it

### Task 12 — Architecture tests
**Depends on:** 4, 10 · **Prompt refs:** §4.1, §8, DoD 2, DoD 3 · **Modules:** `:app` or a `:test:arch` module

Build:
- Konsist (or ArchUnit) rules that **fail the build**:
  1. `:domain` has no Android dependency.
  2. No `org.linphone.*` / `org.pjsip.*` import outside `:data:sip`.
  3. `:feature:*` does not depend on `:data:*` — only on `:domain`.
  4. Repository *interfaces* live in `:domain`; *implementations* only in `:data:*`.
  5. No `LiveData`, no RxJava, no `AsyncTask`, no raw `Thread`.
  6. ViewModels expose `StateFlow`, never mutable state.

Done when:
- [ ] Each rule has a deliberately-violating fixture proving the rule actually fires
- [ ] The rules run in CI as part of `./gradlew check`

### Task 13 — CI pipeline
**Depends on:** 12 · **Prompt refs:** §9.1, §8 · **Modules:** `.github/workflows` (or equivalent)

Build:
- CI running: assemble, unit tests, detekt, ktlint, Android lint, architecture tests,
  and a dependency-vulnerability check (§7).
- Coverage report published as a build artifact.

Done when:
- [ ] CI is green on the current `main`
- [ ] CI fails if any architecture rule from Task 12 is violated
- [ ] Coverage numbers for `:domain` and `:data:*` are visible in the CI output

### Task 14 — `:core:designsystem`
**Depends on:** 6 · **Prompt refs:** §3 · **Modules:** `:core:designsystem`

Build:
- Material 3 theme (light/dark/dynamic), typography, spacing tokens.
- Edge-to-edge setup, predictive back, per-app language support.
- Reusable components: loading/empty/error states, confirm dialog, permission-rationale
  sheet, avatar, `CallActionButton`.

Done when:
- [ ] Every component has a `@Preview` in both light and dark
- [ ] The app draws edge-to-edge with no hardcoded system-bar insets
- [ ] No colour, dimension, or text style is hardcoded outside this module

### Task 15 — App shell, navigation, permission scaffolding
**Depends on:** 14 · **Prompt refs:** §3, §4.2 · **Modules:** `:app`, `:feature:*`

Build:
- Single Activity + Compose navigation, top-level destinations
  (Dialer, Calls/History, Accounts, Settings) as empty screens.
- A reusable permission coordinator: in-context request, rationale, and an explicit
  **denial path** (what the app does when the user says no) for `RECORD_AUDIO`,
  `CAMERA`, `POST_NOTIFICATIONS`, `BLUETOOTH_CONNECT`, `READ_CONTACTS`, `MANAGE_OWN_CALLS`.

Done when:
- [ ] All top-level destinations are reachable and survive rotation
- [ ] Each permission has a rationale string and a defined denied-state behaviour
- [ ] Permanently-denied permissions route the user to app settings with an explanation

---

# Phase 2 — Accounts (fully functional offline)

### Task 16 — Keystore-backed credential encryption
**Depends on:** 5 · **Prompt refs:** §7, DoD 12 · **Modules:** `:data:account`

Build:
- A `CredentialCipher` using an Android Keystore–backed AES-GCM key (`MasterKey` +
  `EncryptedFile`, or explicit AES-GCM wrapping). Pick one, record the choice in
  `docs/security.md`. **Do not hand-roll crypto primitives.**
- Handle key invalidation (device credential changed) with a defined recovery path.

Done when:
- [ ] Encrypt → decrypt round-trip test passes, including empty and 256-char passwords
- [ ] Ciphertext differs across two encryptions of the same plaintext (unique IV)
- [ ] Key invalidation is handled with a user-facing "re-enter your password" path,
      not a crash

### Task 17 — Room schema for accounts
**Depends on:** 8, 16 · **Prompt refs:** §5.1, §3 · **Modules:** `:data:account`

Build:
- `SipAccountEntity` covering every §5.1 field; password and TURN credentials stored
  as ciphertext columns. Exported schema checked into the repo. DAO with Flow queries.
- A documented migration policy (no destructive migrations in release).

Done when:
- [ ] The exported schema JSON is committed
- [ ] A DAO test against in-memory Room covers insert/update/delete/query
- [ ] A raw SQLite dump of the DB shows no plaintext password (asserted by a test)

### Task 18 — Account repository
**Depends on:** 17 · **Prompt refs:** §4.2, §5.1 · **Modules:** `:domain`, `:data:account`

Build:
- `SipAccountRepository` interface in `:domain`; implementation in `:data:account`
  handling entity↔domain mapping and encryption at the boundary.
- Default-account selection persisted in DataStore.

Done when:
- [ ] The interface is in `:domain` and the impl is in `:data:account` (arch test enforces)
- [ ] Decrypted passwords exist only inside the impl and are never held in a cached
      domain object longer than the call that needs them
- [ ] Repository tests run against in-memory Room with ≥ 80% coverage

### Task 19 — Account CRUD use cases
**Depends on:** 18 · **Prompt refs:** §4.2, §5.1 · **Modules:** `:domain`

Build:
- `SaveAccountUseCase` (validate → persist), `DeleteAccountUseCase`,
  `SetDefaultAccountUseCase`, `ObserveAccountsUseCase`.
- Encode the business rules now, before any UI: **delete is refused while a call is
  active on that account**; editing a registered account must unregister first
  (the hook exists here; wiring lands in Task 32).
- Do **not** create pass-through use cases that only forward to the repository (§4.2).

Done when:
- [ ] Each use case has tests for success, validation failure, and the refusal path
- [ ] Deleting an account with an active call returns a typed error, not an exception

### Task 20 — Account list screen
**Depends on:** 19, 15 · **Prompt refs:** §4.2, §5.1 · **Modules:** `:feature:accounts`

Build:
- List of accounts with label, SIP identity, default marker, and a registration-status
  placeholder (real status arrives in Task 32). Add / edit / delete affordances.
- ViewModel exposing one immutable `UiState` via `StateFlow` + a one-shot event channel.

Done when:
- [ ] The screen is fully previewable with fake state, no DI required
- [ ] ViewModel tests cover empty, populated, and delete-refused states
- [ ] No mutable state is exposed to Compose

### Task 21 — Account editor screen
**Depends on:** 20 · **Prompt refs:** §5.1 · **Modules:** `:feature:accounts`

Build:
- A form covering **every** §5.1 field, grouped: Identity, Server, Transport & NAT,
  Media & Security, Advanced. Advanced fields collapsed by default.
- Per-field inline validation from `AccountValidator`. Password field masked, with
  `FLAG_SECURE` on the screen (§7).

Done when:
- [ ] Every §5.1 field is editable and round-trips through save/reload
- [ ] Invalid input is blocked at entry with a field-level message, not a toast on save
- [ ] The screen sets `FLAG_SECURE`; a screenshot attempt is blocked
- [ ] A Compose UI test covers create → save → reopen → edit → save

### Task 22 — Multiple accounts and default selection
**Depends on:** 21 · **Prompt refs:** §5.1, DoD 5 · **Modules:** `:feature:accounts`, `:domain`

Build:
- Support N accounts with one default used for outgoing calls; per-call account override
  in the dialer (wired in Task 36).

Done when:
- [ ] Two accounts can be created, listed, and independently edited and deleted
- [ ] Exactly one account is default at all times; deleting the default promotes another
- [ ] Account identity collisions (same user@domain) are rejected with a clear message

### Task 23 — Settings screen
**Depends on:** 15 · **Prompt refs:** §5.1, §7 · **Modules:** `:feature:settings`

Build:
- App-level settings in DataStore: default account, DTMF mode (RFC 4733 / INFO),
  SRTP policy default, audio route preference, and a **debug SIP trace toggle that is
  off by default** and redacts `Authorization`/`Proxy-Authorization` (§7).

Done when:
- [ ] Settings persist across process death
- [ ] The SIP trace toggle is off on a fresh install and unavailable in release builds
- [ ] A test asserts trace output redacts auth headers

### Task 24 — Phase 2 checkpoint
**Depends on:** 22, 23 · **Prompt refs:** §9.2 · **Modules:** —

Done when:
- [ ] The app builds, installs, and manages accounts end-to-end with **no network**
- [ ] `./gradlew check` is green, including architecture tests
- [ ] Phase report written: what works, what was assumed, what is deferred

---

# Phase 3 — Registration

### Task 25 — Integrate the SIP SDK into `:data:sip`
**Depends on:** 1, 12 · **Prompt refs:** §2.4, §3, DoD 3 · **Modules:** `:data:sip`

Build:
- Add the stack chosen in Task 1 (AAR/Maven coords or a built `.so` set).
- R8/ProGuard keep rules for the SDK and its JNI entry points.
- A build-time check that every bundled `.so` is **16 KB page-size aligned** (§3) —
  fail the build, not the device.
- ABI filters and an APK-size note in `docs/architecture.md`.

Done when:
- [ ] A debug build initialises the stack and logs its version at startup
- [ ] The 16 KB alignment check runs in CI and fails on a misaligned `.so`
- [ ] The architecture test from Task 12 still passes: no SDK import escapes `:data:sip`
- [ ] A minified release build starts without a `ClassNotFound`/`UnsatisfiedLinkError`

### Task 26 — Registration backoff policy (pure domain)
**Depends on:** 10 · **Prompt refs:** §2.1, §5.1, DoD 6 · **Modules:** `:domain`

Written before the SDK work needs it, and tested without a network.

Build:
- `RegistrationBackoff`: exponential with **full jitter**, a hard ceiling, a reset on
  success, and explicit `Retry-After` honouring on 503.
- `ExpiryRefreshPolicy`: refresh at 50–90% of the **server-granted** expiry (server value
  wins if lower than requested).

Done when:
- [ ] Backoff is deterministic under an injected RNG and tested across ≥ 100 iterations
- [ ] Two "clients" with different seeds produce different delays (no stampede) — asserted
- [ ] `Retry-After: 120` produces a ≥ 120 s delay regardless of attempt count
- [ ] Refresh fires inside the 50–90% window for granted expiries of 60, 300, and 3600 s

### Task 27 — `SipEngine` implementation: registration only
**Depends on:** 25, 26 · **Prompt refs:** §4.3, §5.1 · **Modules:** `:data:sip`

Build:
- Implement `register` / `unregister` / `registrationState`.
- Convert every SDK callback into a Flow emission **at the boundary**; map SDK error
  codes to the `SipError` taxonomy from Task 10.
- Single transport instance; one registration per configured account.

Done when:
- [ ] `registrationState` emits `Registering → Registered` on success and
      `Failed(AuthFailed)` on a wrong password
- [ ] `unregister` sends `Expires: 0` and completes before the service stops
- [ ] No SDK listener type is visible outside this module
- [ ] Callback→Flow mapping is unit-tested with a stubbed SDK seam

### Task 28 — Registration foreground service
**Depends on:** 27 · **Prompt refs:** §3, §6 · **Modules:** `:app`

Build:
- A foreground service holding registration, with `phoneCall` service type declared and
  the matching runtime permission checked (Android 14+ enforces the pairing).
- A persistent notification showing real registration state.
- **Hard stop rule:** the service stops when there is no registered account and no
  active call.

Done when:
- [ ] The service starts on first successful registration and stops on logout of the
      last account — verified with `adb shell dumpsys activity services`
- [ ] The notification reflects `Registering / Registered / Failed(reason)` accurately
- [ ] Starting the service without the matching permission is handled, not crashed
- [ ] No wake lock is held while unregistered

### Task 29 — Login / logout semantics
**Depends on:** 28, 19 · **Prompt refs:** §5.1, DoD 5 · **Modules:** `:domain`, `:feature:accounts`

Build:
- **Login** = save + register. **Logout** = clean unregister (`Expires: 0`), stop the
  service, and **wipe credentials from memory** while keeping the account row.
- **Edit a registered account** = unregister the old identity, then register the new one.
  No silent partial re-registration.

Done when:
- [ ] Logout leaves the account row present and the registration gone
- [ ] Editing a registered account produces unregister-then-register, asserted by an
      ordered test against `FakeSipEngine`
- [ ] After logout, no decrypted password remains reachable from any live object

### Task 30 — Network-change resilience
**Depends on:** 28 · **Prompt refs:** §6, DoD 6 · **Modules:** `:data:sip`, `:app`

Build:
- `ConnectivityManager.NetworkCallback` → rebind transport → re-register, with the
  Task 26 backoff. Debounce flapping networks.
- Distinguish "no network" (do not retry, wait) from "network but registrar down"
  (retry with backoff).

Done when:
- [ ] Airplane-mode on→off recovers registration automatically
- [ ] Wi-Fi → cellular handover re-registers without user action
- [ ] With no network, retries stop (no battery-burning loop) and resume on reconnect
- [ ] Log evidence of the backoff sequence is captured in the phase report

### Task 31 — Registration status UI
**Depends on:** 29, 20 · **Prompt refs:** §5.1, §6 · **Modules:** `:feature:accounts`

Build:
- Real per-account status in the account list and a detail view showing state, last
  error (`Failed(AuthFailed)`, `Failed(Timeout)`, …), next retry time, and a manual
  "register now" action.
- **Honest offline state** (§6) — never show "Registered" when the transport is down.

Done when:
- [ ] Status updates live, driven by `registrationState`, with no polling
- [ ] A wrong password surfaces "Authentication failed", not a generic error
- [ ] Airplane mode shows a distinct offline state, not a failure

### Task 32 — Wire up the FreeSWITCH test target
**Depends on:** 1 · **Prompt refs:** §8, DoD 8 · **Modules:** `docs/`, build config

Per **ADR-005**: integration tests run against the **already-deployed FreeSWITCH**
instance. No local Docker server. Unblocks every integration test from here on.

Build:
- Resolve open questions **Q3** and **Q4** in `docs/architecture.md`: hostname, SIP
  domain, transport, TLS CA, reserved test extensions, reserved conference room.
- Build config for the test target — host and credentials injected from Gradle
  properties / CI secrets, **never committed**.
- `docs/testing.md`: how to point the app at the server, which extensions are reserved
  for automation vs manual use, and how to run the integration suite.
- Apply the ADR-005 mitigations: extensions reserved for automation only, a dedicated
  conference room, and a CI concurrency group of 1 so runs cannot collide.
- Split the CI gate: **unit tests + `FakeSipEngine` journeys run on every push**;
  integration tests run on a schedule or on demand. This keeps CI fast and deterministic
  despite the shared, non-hermetic server.

Done when:
- [ ] The app registers against the deployed FreeSWITCH from a developer machine
- [ ] Reserved test extensions, TLS CA, and conference room are documented in `docs/testing.md`
- [ ] Two reserved extensions can call each other through it
- [ ] No hostname, credential, or certificate is committed to the repo
- [ ] CI runs unit tests on every push and does **not** require server reachability to be green

### Task 33 — Integration test: registration
**Depends on:** 30, 32 · **Prompt refs:** §8, DoD 6 · **Modules:** `:data:sip` (androidTest)

Build:
- Instrumented tests against the FreeSWITCH test target: register over UDP, TCP, and TLS;
  wrong-password failure; recovery after a transport drop.

Done when:
- [ ] All three transports register successfully
- [ ] Recovery after a transport drop happens automatically with a jittered delay.
      Per ADR-005, prefer a client-side transport drop over restarting a **shared** server;
      if a real registrar restart is tested, run it as a scheduled manual test
- [ ] The test suite is documented and runnable from a clean checkout

---

# Phase 4 — 1-to-1 audio calling

### Task 34 — Telecom self-managed `ConnectionService`
**Depends on:** 28 · **Prompt refs:** §3, DoD 7 · **Modules:** `:app`

Hand-rolled call notifications instead of Telecom is a **rejected design** (§3).

Build:
- `PhoneAccount` registration with `CAPABILITY_SELF_MANAGED`, `MANAGE_OWN_CALLS`
  permission, and a self-managed `ConnectionService`.
- `Connection` subclass bridging Telecom callbacks (`onAnswer`, `onReject`, `onDisconnect`,
  `onHold`, `onUnhold`, `onCallAudioStateChanged`) to the Task 9 FSM.
- Correct interaction with cellular calls: a native call in progress must be honoured.

Done when:
- [ ] The `PhoneAccount` is registered and visible in `adb shell dumpsys telecom`
- [ ] Telecom hold/unhold and mute callbacks drive the FSM
- [ ] An incoming SIP call during an active cellular call is handled per Telecom policy,
      not force-shown

### Task 35 — Outgoing audio call
**Depends on:** 34, 27 · **Prompt refs:** §5.2, §4.4 · **Modules:** `:data:sip`, `:app`

Build:
- `placeCall` in the real `SipEngine`; `PlaceCallUseCase` selecting the account
  (default or per-call override); FSM `Outgoing(Calling → Ringing → EarlyMedia)`;
  Telecom `Connection` created before the INVITE.

Done when:
- [ ] A call to a test extension rings the far end and connects on answer
- [ ] Early media (183 with SDP) is audible when the server sends it
- [ ] 486 Busy, 404 Not Found, and 408 Timeout each surface a distinct message
- [ ] Cancelling before answer sends `CANCEL` and terminates cleanly

### Task 36 — Dialer screen
**Depends on:** 35, 22 · **Prompt refs:** §5.2 · **Modules:** `:feature:dialer`

Build:
- Keypad + SIP URI/extension entry, per-call account override, recent-call shortcuts.
- Input accepts both `1234` (resolved against the account domain) and a full `sip:` URI.

Done when:
- [ ] Both bare-extension and full-URI dialling place a call
- [ ] The account override is honoured and shown in the in-call UI
- [ ] The screen is fully driveable by `FakeSipEngine` in a Compose UI test

### Task 37 — Incoming call: notification and full-screen UI
**Depends on:** 34 · **Prompt refs:** §3, §5.2, DoD 7 · **Modules:** `:app`, `:feature:calls`

Build:
- `CallStyle` notification (Android 12+), `USE_FULL_SCREEN_INTENT` requested correctly,
  lock-screen answer/reject, ringtone + vibration honouring the ringer mode.

Done when:
- [ ] An incoming call rings on the lock screen and is answerable from there
- [ ] Rejecting sends `486` and terminates cleanly
- [ ] Silent/DND modes are respected
- [ ] The notification is a `CallStyle` notification, not a custom layout

### Task 38 — Push wake path (FCM / RFC 8599)
**Depends on:** 37, 1 · **Prompt refs:** §2.5 · **Modules:** `:app`, `:data:sip`

Registration alone misses calls in Doze — this is the primary path on Android 12+.

Build:
- Per the Task 1 decision: either RFC 8599 Contact parameters
  (`pn-provider`/`pn-param`/`pn-prid`) on `REGISTER`, or the separate-service FCM contract.
- High-priority data message → wake → re-register if stale → start the call foreground
  service → show incoming UI → accept the INVITE.
- Token refresh re-registers with the new `pn-prid`.

Done when:
- [ ] A call placed while the app is force-stopped rings the device
- [ ] A call placed after 30+ minutes idle (Doze, verified with
      `adb shell dumpsys deviceidle force-idle`) rings the device
- [ ] FCM token rotation updates the registration
- [ ] The push payload carries **no** credentials and no call content

### Task 39 — In-call screen
**Depends on:** 35, 37 · **Prompt refs:** §4.2, §5.2 · **Modules:** `:feature:calls`

Build:
- In-call UI driven entirely by the FSM: remote identity, state, duration timer,
  and action buttons. Buttons are enabled/disabled **by state**, not by ad-hoc booleans.

Done when:
- [ ] Every FSM state renders a correct, previewable screen
- [ ] Hold is unavailable before `Connected` (enforced by state, not by a disabled flag)
- [ ] The duration timer survives rotation and is driven by call start, not by a counter

### Task 40 — Audio routing and focus
**Depends on:** 34 · **Prompt refs:** §3, §5.2, DoD 8 · **Modules:** `:app`

Build:
- Routing via Telecom `CallAudioState` + `AudioManager`/`AudioDeviceInfo`:
  earpiece / speaker / wired / Bluetooth SCO.
- Audio focus request and loss handling; automatic route change on headset connect
  and disconnect; proximity sensor for earpiece mode.

Done when:
- [ ] All four routes are selectable and audible
- [ ] Plugging in a wired headset mid-call switches route automatically
- [ ] Connecting a Bluetooth headset mid-call switches to SCO automatically
- [ ] Focus loss (an incoming cellular call) is handled without leaving the mic hot

### Task 41 — Hold and resume
**Depends on:** 39 · **Prompt refs:** §5.2, DoD 8 · **Modules:** `:data:sip`, `:feature:calls`

Build:
- Re-INVITE with correct SDP direction (`sendonly`/`recvonly`/`inactive`), remote-hold
  detection, and the `Held ⇄ Resuming` FSM transitions. Telecom hold stays in sync.

Done when:
- [ ] Local hold produces `a=sendonly` and media stops in the correct direction
- [ ] Remote hold is detected and shown in the UI
- [ ] Both-sides hold resolves correctly on resume
- [ ] Verified against the FreeSWITCH test target

### Task 42 — Mute and speaker
**Depends on:** 40 · **Prompt refs:** §4.4, §5.2 · **Modules:** `:feature:calls`

Build:
- Mute and speaker as **attributes of `Connected`** (§4.4), synced with Telecom so the
  system UI and Bluetooth headset button agree with the app.

Done when:
- [ ] Mute stops uplink audio, verified at the far end
- [ ] Mute toggled from a Bluetooth headset updates the app UI
- [ ] Neither mute nor speaker appears in the `CallState` enum

### Task 43 — DTMF
**Depends on:** 39, 23 · **Prompt refs:** §5.2, DoD 8 · **Modules:** `:data:sip`, `:feature:calls`

Build:
- RFC 4733 telephone-event by default, SIP INFO fallback, mode configurable per §5.1.
- In-call keypad with local tone feedback.

Done when:
- [ ] RFC 4733 digits are received correctly by an IVR on the FreeSWITCH test target
- [ ] The INFO fallback works when configured
- [ ] All 16 digits (0-9, \*, #, A-D) transmit correctly

### Task 44 — Termination reasons and error mapping
**Depends on:** 35 · **Prompt refs:** §4.2, §4.3 · **Modules:** `:domain`, `:feature:calls`

Build:
- Complete `HangupReason` coverage: local hangup, remote hangup, busy, declined,
  no answer, network failure, media failure, cancelled. Each maps to a user-facing
  message and a call-log entry (Task 47).

Done when:
- [ ] Every `SipError` and `HangupReason` maps to exactly one user-facing string
- [ ] No user-visible error reads "Unknown error" for a case the server actually sent
- [ ] Mapping is table-tested

### Task 45 — Process-death and call restoration
**Depends on:** 39 · **Prompt refs:** §6 · **Modules:** `:app`

Build:
- Restore in-call UI from Telecom + service state, **not** from ViewModel memory.

Done when:
- [ ] Killing the process mid-call (`adb shell am kill`) and reopening restores the
      in-call screen with correct state and duration
- [ ] The call audio never drops during the restore
- [ ] No call state is read from `SavedStateHandle` as the source of truth

### Task 46 — Integration test: full audio call
**Depends on:** 41, 42, 43, 32 · **Prompt refs:** §8, DoD 8 · **Modules:** androidTest

Done when:
- [ ] Outgoing and incoming audio calls complete against the FreeSWITCH test target
- [ ] Hold/resume, mute, speaker, Bluetooth route switch, and DTMF each pass in the suite
- [ ] The phase report records the actual pass/fail output, not a claim

---

# Phase 5 — Call history and contacts

### Task 47 — Call log storage
**Depends on:** 44 · **Prompt refs:** §5.2 · **Modules:** `:data:calllog`, `:domain`

Build:
- Room schema recording direction, remote identity, resolved contact name, start/answer/end
  timestamps, duration, termination reason, account used, and media type.
- `CallLogRepository` interface in `:domain`, impl in `:data:calllog`.
- Entries written from FSM terminal transitions, including **missed and failed** calls.

Done when:
- [ ] Every call outcome — answered, missed, rejected, failed — produces exactly one entry
- [ ] A call that never connects still records the correct reason
- [ ] Repository tests hit ≥ 80% coverage

### Task 48 — Call history screen
**Depends on:** 47 · **Prompt refs:** §5.2 · **Modules:** `:feature:history`

Build:
- Paging 3 list grouped by day, filterable (all / missed), with detail view,
  delete-one, clear-all, and call-back from an entry.

Done when:
- [ ] 10,000 seeded entries scroll without jank (verified on a mid-range device)
- [ ] Delete-one and clear-all both work and survive process death
- [ ] The list updates live when a new call ends

### Task 49 — Contacts integration
**Depends on:** 15 · **Prompt refs:** §5.2, §7 · **Modules:** `:data:contacts`

Build:
- `ContactsContract` read with permission handling and a graceful denied path.
- Resolve inbound SIP URIs to contact names for the incoming-call UI and history.
- Minimal cache. Contacts are personal data — **no bulk upload anywhere** (§7, §11).

Done when:
- [ ] An incoming call from a known contact shows the contact name and photo
- [ ] Denying the permission leaves the app fully functional, showing raw URIs
- [ ] A test asserts no contact data leaves the device

### Task 50 — Contact list and SIP dialling
**Depends on:** 49, 36 · **Prompt refs:** §5.2 · **Modules:** `:feature:dialer`

Done when:
- [ ] Contacts with SIP addresses are listed and searchable
- [ ] "Call via SIP" from a contact places a call on the chosen account

---

# Phase 6 — Video

### Task 51 — Video media profile and camera lifecycle
**Depends on:** 46 · **Prompt refs:** §5.2, §3 · **Modules:** `:data:sip`, `:app`

Build:
- Extend `MediaProfile` for video; camera acquisition tied to call lifecycle and
  released on every terminal path.
- `camera` and `microphone` foreground-service types declared with matching permissions.

Done when:
- [ ] The camera is released on hangup, on error, and on process death — no
      "camera in use" on the next call
- [ ] Denying the camera permission downgrades to audio-only rather than failing the call
- [ ] The FGS types are declared and the app starts the service without a
      `ForegroundServiceTypeException` on Android 14+

### Task 52 — Video rendering and orientation
**Depends on:** 51 · **Prompt refs:** §5.2, DoD 9 · **Modules:** `:feature:calls`

Build:
- Local preview + remote view with correct aspect ratio and rotation handling on device
  rotation and on remote resolution change.

Done when:
- [ ] Bidirectional video establishes against the FreeSWITCH test target
- [ ] Rotating the device keeps both streams correctly oriented at both ends
- [ ] A remote resolution change does not stretch or crash the view

### Task 53 — Camera switch and video mute
**Depends on:** 52 · **Prompt refs:** §5.2, DoD 9 · **Modules:** `:feature:calls`

Done when:
- [ ] Front/back switch works mid-call without dropping the stream
- [ ] Video mute stops the outbound stream while audio continues
- [ ] Both are attributes of `Connected`, not FSM states

### Task 54 — Audio ⇄ video escalation
**Depends on:** 53 · **Prompt refs:** §5.2 · **Modules:** `:data:sip`, `:feature:calls`

Build:
- Re-INVITE to add or remove video mid-call, with an accept/decline prompt for an
  incoming escalation.

Done when:
- [ ] Escalating an audio call to video works against the FreeSWITCH test target
- [ ] Declining an escalation keeps the audio call alive
- [ ] De-escalation to audio-only releases the camera

---

# Phase 7 — Transfer, call waiting, recording

### Task 55 — Blind transfer
**Depends on:** 46 · **Prompt refs:** §5.2, DoD 10 · **Modules:** `:data:sip`, `:feature:calls`

Build:
- `REFER` with `Refer-To`; `NOTIFY`/sipfrag progress surfaced in the UI;
  the `Transferring` FSM state.

Done when:
- [ ] A blind transfer to a third extension completes against the FreeSWITCH test target
- [ ] Transfer progress and failure are both shown to the user
- [ ] A failed transfer returns the call to `Connected`, not to a dead state

### Task 56 — Call waiting and second call
**Depends on:** 46 · **Prompt refs:** §5.2 · **Modules:** `:feature:calls`, `:app`

Build:
- A second incoming call during an active call: accept-and-hold, accept-and-end,
  or reject. Swap between calls. Telecom must agree with app state throughout.
- This is also the consultation-call machinery attended transfer needs (Task 57).

Done when:
- [ ] All three responses to a second call behave correctly
- [ ] Swapping calls puts exactly one on hold and one active — never two active
- [ ] The system call UI and the app UI never disagree

### Task 57 — Attended transfer
**Depends on:** 55, 56 · **Prompt refs:** §5.2, DoD 10 · **Modules:** `:data:sip`, `:feature:calls`

Build:
- Hold call A → consult call B → `REFER` with `Replaces` → both legs terminate locally.

Done when:
- [ ] An attended transfer completes with all three parties behaving correctly
- [ ] Cancelling the consultation returns cleanly to call A
- [ ] Verified against the FreeSWITCH test target

### Task 58 — Call recording architecture
**Depends on:** 46 · **Prompt refs:** §2.6, §7 · **Modules:** `:domain`, `:data:sip`, `:feature:calls`

Architecture + consent + indicator. Do **not** ship a silent recorder (§2.6).

Build:
- A `CallRecorder` port in `:domain`, encrypted storage location, a **consent gate**
  before recording starts, a persistent in-call recording indicator, and a retention
  policy hook.
- Document the platform limits on capturing remote audio, and the two-party-consent /
  GDPR exposure, in `docs/security.md`.

Done when:
- [ ] Recording is **off by default** and cannot start without explicit consent
- [ ] A visible indicator is present for the entire duration of any recording
- [ ] Recordings are encrypted at rest and excluded from backup
- [ ] `docs/security.md` states the legal and platform constraints plainly

---

# Phase 8 — Conference

### Task 59 — Conference domain model
**Depends on:** 10, 46 · **Prompt refs:** §2.2 · **Modules:** `:domain`

Build:
- `ConferenceSession` holding **N** participant streams, participant state
  (joined/left/muted/speaking), and floor-control hooks — shaped so an SFU (§2.2 option b)
  is an implementation swap, not a rewrite.

Done when:
- [ ] The model supports N > 2 participants with no dial-in-specific assumption baked in
- [ ] Participant join/leave/mute transitions are unit-tested against `FakeSipEngine`

### Task 60 — Dial-in conference
**Depends on:** 59, 32 · **Prompt refs:** §2.2, DoD 11 · **Modules:** `:data:sip`, `:feature:calls`

Build:
- Dial a conference URI as an ordinary call; the server mixes (§2.2 option a).
- Participant list UI from the server's roster (`NOTIFY` / conference event package
  where available; otherwise document the limitation honestly).

Done when:
- [ ] Three clients join one conference on the FreeSWITCH bridge and hear each other
- [ ] The participant list reflects joins and leaves
- [ ] If the server provides no roster, the UI says so rather than showing a fake list

### Task 61 — Video conference
**Depends on:** 60, 52 · **Prompt refs:** §2.2 · **Modules:** `:feature:calls`

Done when:
- [ ] A video conference renders the active-speaker or mixed stream correctly
- [ ] Layout adapts to participant count and to rotation
- [ ] Any limitation of the mixed-stream model is documented, not hidden

---

# Phase 9 — Hardening and release readiness

### Task 62 — Transport and media security pass
**Depends on:** 33, 46 · **Prompt refs:** §7, DoD 13 · **Modules:** `:data:sip`, `:app`

Build:
- TLS 1.2+ with real certificate validation. **No permissive `TrustManager` anywhere.**
- Optional, clearly-labelled, non-default custom-CA setting for enterprise deployment.
- SRTP policy enforcement: Disabled / Optional / **Mandatory**, where Mandatory means
  the call **fails** rather than going cleartext.
- Network security config; `usesCleartextTraffic="false"`.

Done when:
- [ ] A TLS + SRTP call succeeds against the FreeSWITCH test target
- [ ] SRTP-Mandatory against a cleartext-only peer **fails the call** — asserted by a test
- [ ] An invalid/self-signed cert is rejected unless the custom CA is explicitly configured
- [ ] A code search finds no `checkServerTrusted` override that returns unconditionally

### Task 63 — Logging and data-leak audit
**Depends on:** 62 · **Prompt refs:** §7, DoD 12 · **Modules:** all

Build:
- Audit every log call. Release builds must emit no SIP messages, headers, credentials,
  phone numbers, or contact data.
- Verify the Task 23 debug trace redacts `Authorization` / `Proxy-Authorization`.

Done when:
- [ ] A full release-build logcat capture across register → call → hangup contains no
      credential, SIP header, or phone number — checked by grep and recorded
- [ ] A filesystem dump of app data shows no plaintext credential
- [ ] `android:allowBackup="false"` is set and credential screens use `FLAG_SECURE`

### Task 64 — R8, minification, and release build
**Depends on:** 63 · **Prompt refs:** §7, DoD 1 · **Modules:** `:app`

Build:
- R8 full mode, keep rules for the SIP SDK's JNI surface, mapping file archived in CI,
  no exported components without a permission.

Done when:
- [ ] A minified release build registers and completes an audio and a video call
- [ ] The mapping file is produced and archived by CI
- [ ] `./gradlew assembleRelease` passes from a clean clone

### Task 65 — Battery, memory, and leak profiling
**Depends on:** 64 · **Prompt refs:** §1, §6, DoD 16 · **Modules:** —

Build:
- LeakCanary in debug; a one-hour idle registered session and a 30-minute call profiled.
- Fix what the profiles find; record before/after numbers.

Done when:
- [ ] One hour idle registered: no leaked wake locks (`dumpsys power`), no unbounded
      heap growth, LeakCanary clean
- [ ] A 30-minute call shows stable memory and no growing thread count
- [ ] Battery drain over the idle hour is measured and recorded in the phase report

### Task 66 — Offline and honest-state audit
**Depends on:** 31 · **Prompt refs:** §6 · **Modules:** all

Done when:
- [ ] Account settings and call history are fully readable with no network
- [ ] No screen ever claims "Registered" while the transport is down
- [ ] Nothing is queued for retry that cannot meaningfully be retried

### Task 67 — Documentation
**Depends on:** 65 · **Prompt refs:** §10, DoD 15 · **Modules:** `docs/`

Build:
- `docs/architecture.md` — HLD: module graph, layer diagram, sequence diagrams for
  register / outgoing / incoming-via-push / hold / transfer, threading model, and the
  **answers to every DECIDE in §2** (carried forward from Task 1).
- `docs/lld.md` — class responsibilities, the call FSM diagram, the `SipEngine` contract
  and its error taxonomy, the Room schema.
- `docs/security.md` — credential handling, transport/media security, logging policy,
  SIP-stack licence position and its distribution implications, recording constraints.
- `docs/testing.md` — running unit, instrumented, and server-backed integration tests.
- `README.md` — setup, required config (FCM key, test SIP account), build variants.
- KDoc on every public type in `:domain` and on `SipEngine`. Comments explain **why**.

Done when:
- [ ] All five documents exist and match the code as built
- [ ] Every §2 DECIDE is answered in `docs/architecture.md`
- [ ] A new engineer can go from clone to a registered call using only `README.md`
      and `docs/testing.md`

### Task 68 — Definition-of-Done sweep
**Depends on:** all · **Prompt refs:** §12 · **Modules:** —

Build:
- Walk §12 items 1–16 one by one. For each: run the check, record the **actual** result.
- Report real coverage numbers — do not restate the 80% target as an outcome (§8).
- List anything not met, with the reason. An honest gap beats a false tick (§13).

Done when:
- [ ] All 16 DoD items have a recorded pass/fail with evidence
- [ ] Coverage for `:domain` and `:data:*` is reported as a real measured number
- [ ] Every failure is documented with a cause and a proposed follow-up task

---

## Traceability — Definition of Done → tasks

| DoD (§12) | Tasks |
|---|---|
| 1 — Clean build passes | 2, 13, 64 |
| 2 — `:domain` has zero Android deps | 4, 12 |
| 3 — No SDK import outside `:data:sip` | 12, 25 |
| 4 — Whole app runs on `FakeSipEngine` | 11, 20, 36 |
| 5 — Two accounts register; delete unregisters first | 22, 29 |
| 6 — Registration self-heals with backoff | 26, 30, 33 |
| 7 — Lock-screen incoming call via Telecom | 34, 37 |
| 8 — Audio call with hold/mute/speaker/BT/DTMF | 40–43, 46 |
| 9 — Bidirectional video, switch, mute | 52, 53 |
| 10 — Blind and attended transfer | 55, 57 |
| 11 — Dial-in conference with participant list | 60 |
| 12 — No credential or PII in logs or on disk | 16, 17, 63 |
| 13 — TLS + SRTP; Mandatory fails rather than downgrades | 62 |
| 14 — ≥ 80% coverage in `:domain` and `:data:*` | 13, 68 |
| 15 — Every §2 DECIDE answered in docs | 1, 67 |
| 16 — No leaked wake locks or unbounded memory | 65 |

## Checkpoints

Stop, build, run tests, and write a phase report at Tasks **15, 24, 33, 46, 50, 54, 58, 61, 68**.
Each report states what works, what was assumed, and what is deferred (§9, §13).
