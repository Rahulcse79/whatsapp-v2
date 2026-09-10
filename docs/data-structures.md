# Data structures and algorithms

**Master prompt §6.** Every entry states the structure, its operation complexities, its
**bound**, the **policy at that bound**, and the specific failure that policy prevents. A
structure with no bound is not in this document, because it is not finished.

Every claim carries a `path:line` citation, a measurement with its method, or an
`ASSUMPTION:` label. Entries marked **PROPOSED** do not exist yet and say what they will be.

---

## 1. The hot path — native events into Kotlin

The single most important design in the app. pjsua2 delivers callbacks on **its own worker
threads**, and blocking one stops SIP processing entirely — not this call, the stack
(`docs/architecture.md` §4.3).

### 1.1 The shape

```
pjsua2 worker thread                 pjsip executor (1 thread)         collector coroutines
─────────────────────                ─────────────────────────         ────────────────────
onRegState / onCallState  ──►  tryEmit(StackEvent)  ──►  MutableSharedFlow  ──►  PjsipSipEngine
   (SWIG director callback)      O(1), non-blocking        cap 64, DROP_OLDEST      maps to :domain
                                                                                        │
                                                                                        ▼
                                                                        MutableSharedFlow (cap 64)
                                                                                        │
                                                                                        ▼
                                                                              :feature / :app
```

The enqueue is `tryEmit` on a `MutableSharedFlow` — **O(1), allocation-light, and it never
blocks or suspends**. No lock is held across the JNI boundary and no `suspend` call appears
inside a callback. This is the invariant the rest of §1 exists to protect.

### 1.2 Buffer capacity and overflow policy, per stream

Master prompt §6.1: *"DECIDE and document the policy per stream in a table."* This is that
table. It records what the code does **today** and what it should do, because
`docs/reconciliation.md` A-5 found the two differ on three streams.

**Below the seam — `RealPjsipCoreGateway`.** The emitter is a pjsua2 worker thread, so
suspending is not an available choice: it would stop the stack.

| Stream | Capacity | Policy today | Correct? | Consequence of a drop |
|---|---|---|---|---|
| `events` (registration) | 64 (`RealPjsipCoreGateway.kt:1535`) | `DROP_OLDEST` (`:114-118`) | **Yes** | A superseded registration state. The newest state is the true one, so dropping the oldest is not merely safe — it is right |
| `callEventFlow` | 64 | `DROP_OLDEST` (`:121-125`) | **Yes** | Same reasoning; a stale call state is worthless |
| `transferEventFlow` | 64 | `DROP_OLDEST` (`:128-132`) | **Yes** | Same |
| `conferenceEventFlow` | 64 | `DROP_OLDEST` (`:144-148`) | **Yes** | Same |

**Above the seam — `PjsipSipEngine`.** The emitter is a coroutine, so suspending *is*
available, and the right answer differs per stream because the consequence of a drop
differs.

| Stream | Capacity | Policy today | Should be | Consequence of a drop |
|---|---|---|---|---|
| `incomingCalls` | 64 (`PjsipSipEngine.kt:334-338`) | **Suspend** — `incoming.emit` at `:772` | Suspend. Correct as is | A missed call that never rang and never reached the log. The single worst drop in the app |
| `endedCalls` | 64 (`:347-350`) | **Silent drop** — `ended::tryEmit` at `:322`, return discarded | **Suspend**, or a counted drop | One call-log row missing, invisibly. `docs/lld.md:114-116` says the log writes one row per emission |
| `transferEvents` | 64 (`:282-285`) | **Silent drop** — `:471`, `:484` | Counted drop is acceptable | A transfer whose outcome is never reported. The call state machine has already advanced, so the call is correct and only the notification is lost |
| `videoRequests` | 64 (`:276-279`) | **Silent drop** — `:719` | Counted drop is acceptable | A video offer the user is never asked about. `pendingVideoRequests` (`:274`) still holds it, so the request can be recovered; nothing is corrupted |

**Why 64 and not another number.** A phone in the worst realistic case — an MCU conference
being torn down while two other calls end — produces low tens of events. 64 is roughly two
orders of magnitude above steady state and costs 64 references per stream. It is not a
tuned number and does not need to be; what it needs is a **policy for the case it is
exceeded**, which is the column above. **SHOW YOUR WORKING:** eight streams × 64 slots × one
reference each ≈ 512 object references ≈ 4 KB of pointers on a 64-bit VM, plus the events
themselves. Negligible against any figure in §9 of the master prompt.

**The rule that generalises this:** *a drop is acceptable when a later event supersedes the
dropped one, and unacceptable when the dropped event is the only record of something that
happened.* Registration state supersedes; a missed call does not.

### 1.3 Thread confinement — an enforced invariant, not a convention **PROPOSED**

Every thread that calls into pjsua2 must be registered with the library first, and
`Endpoint::libRegisterThread` allocates a descriptor **freed only when the library is
destroyed** (`docs/pjsip-migration.md:44-52`). The adapter's answer is a single-threaded
executor that every call is posted to, and `libCreate` registers its own caller
(`docs/pjsip-migration.md:56-60`) — so no explicit registration is needed and no descriptor
leaks.

That is a correct design held in place by a convention. **DoD 4 requires an assertion.**

- **Structure:** the executor's own thread identity, captured once at `libCreate`, compared
  on every entry to a pjsua2 call.
- **Complexity:** O(1) — one reference comparison per call. Debug builds only.
- **Bound:** none needed; it holds one `Thread` reference.
- **Policy:** in a debug build, throw. In release, do nothing — the check must not be the
  thing that crashes a shipped call.
- **Failure it prevents:** a `SIGSEGV` in a stack trace with no Kotlin frames. This is the
  top source of native crashes in pjsua2 apps and it is silent until it is fatal.
- **Proof:** a test that calls in from a foreign thread and asserts the throw (DoD 4).

**Owning the build makes this more important, not less.** A stack you patched is a stack
whose crashes are yours to explain.

### 1.4 The allocation budget

Three paths must not allocate per iteration:

1. **The native callback** (`RealPjsipCoreGateway`'s director overrides). One event object
   per event is unavoidable and is the budget; nothing else.
2. **The per-frame video surface path.** Runs at the frame rate; an allocation here is a GC
   pause visible as a dropped frame.
3. **The level-metering tick.** Runs 50×/second — see §2, *Level metering*.

Everywhere else, allocate freely and readably. Premature pooling is its own defect.

---

## 2. Structures, with required complexity

| Concern | Structure | Bound / policy | Complexity | Failure it prevents | Status |
|---|---|---|---|---|---|
| **Call registry** | `ConcurrentHashMap<String, PjCall>` (`RealPjsipCoreGateway.kt:203`), keyed by call key | Bounded by concurrent calls (low tens); entries removed on call end | O(1) get/put | A linear scan on every native event; a lost call handle | **Exists** |
| **Account registry** | `ConcurrentHashMap<String, PjAccount>` (`:200`) | Bounded by configured accounts | O(1) | Same | **Exists** |
| **Transport registry** | `ConcurrentHashMap<String, Int>` (`:197`) | One per transport type per account | O(1) | Re-creating a transport that already exists | **Exists** |
| **Call state** | Transition table `(CallState, CallEvent) → TransitionResult` (`domain/…/call/CallStateMachine.kt:11-21`), total, returning `Rejected` (`:193`) rather than null | Table size O(\|S\|×\|E\|), fixed at compile time | O(1) | Undocumented transitions; "resume did nothing". Every pair absent from the table is **asserted rejected** by test | **Exists** |
| **Registration retry** | Exponential backoff, **full jitter**, capped, honouring `Retry-After` (`domain/…/registration/RegistrationBackoff.kt`) | base 2 s (`:101`), ceiling 1800 s (`:104`), floor 1 s (`:109`), server jitter 10 s (`:106`), exponent capped at 32 (`:118`) | O(1) per attempt | 5,000 clients stampeding a restarted registrar — see §3 for the arithmetic | **Exists** |
| **Retry bookkeeping** | `mutableMapOf<AccountId, Int>` attempts, `<AccountId, Job>` pending (`RegistrationRecoveryCoordinator.kt:112-117`) | One entry per configured account; the `Job` map is cancelled and cleared on success | O(1) | Two retries in flight for one account; an attempt counter that resets and holds the client at the base delay for ever | **Exists** |
| **Connectivity churn** | Coalesce + debounce on a derived network-identity key (`RegistrationRecoveryCoordinator.kt:115` `boundNetwork`) | One key per account | O(1) per callback | `ConnectivityManager` fires in bursts during handover; naive handling re-registers 6× per handover | **Exists** |
| **Codec preference match** | Iterate the **registered** codec list, `indexOfFirst { codecId.startsWith(pref, ignoreCase = true) }`, assign a descending priority (`RealPjsipCoreGateway.kt:889-901`) | Runs **once per account setup**, never in the call path. `n` ≤ ~30 | O(n·m), n codecs × m preferences, both ≤ ~30 | The real bug in P-9: `LYRA` prefix-matches `lyra/16000/1` **never**, and silently. The domain enum pins lowercase (`domain/…/model/Codecs.kt:38`) and a test holds it there | **Exists** |
| **Codec audit** | Set difference: *declared feature set* − *`codecEnum2()` registry*, computed once per endpoint start | ≤ ~30 codecs, once per start. Result is an immutable value in `:domain` | O(n) | A codec that compiled and did not register — the silent half of "Lyra works". See §4 | **Exists.** `CodecAudit`/`CodecAuditor` in `:domain`, published by `SipCoreGateway.codecAudit`, unit-tested per reason |
| **Native library inventory** | Expected `.so` set per ABI: `{libpjsua2.so, libc++_shared.so}`, asserted at packaging and re-checked at startup | Exactly 2 entries × 3 ABIs, fixed | O(1) per ABI | An ABI silently short a library; an APK that installs and dies on the first call (N-6) | **Half exists** — CI already asserts it at AAR assembly (`.github/workflows/build-pjsip.yml:607`) and at APK packaging (`:781`). The **startup** re-check is proposed |
| **Vendored-tree integrity** | Hash of upstream-plus-patches vs the tree on disk | One hash per vendored tree, computed once in CI | O(size), once | An unrecorded edit to vendored source; a fixed bug returning at the next bump (N-7) | **PROPOSED** |
| **Contact resolution** | `LinkedHashMap` with `accessOrder = true` and `removeEldestEntry` (`data/contacts/…/LookupCache.kt:21-28`) | **32 entries** (`ContactsContractRepository.kt:245`), LRU eviction. Holds `null` deliberately — "not a contact" is the answer worth caching | O(1) amortized get/put, bounded memory | A `ContactsContract` query per list row, and a query per frame of a ringing screen for a caller who is not in the address book | **Exists** |
| **Call log paging** | `LIMIT :limit OFFSET :offset` over `ORDER BY started_at_epoch_millis DESC, id DESC` (`data/calllog/…/CallLogDao.kt:44-53`) | Page size set by the caller | **O(offset + limit)** — the database must count and discard `offset` rows | — | **Exists, and is the wrong structure. See §5.1** |
| **Audio route** | Pure function: available devices + user override → route, over an explicit priority order | Deterministic; no state | O(1) | A boolean chain that resolves differently depending on callback arrival order | **Exists** |
| **Conference roster** | Ordered set keyed by participant SIP URI, diffed by stable key | Bounded by the MCU's participant limit | O(n) diff | An MCU reorders participants; index-keyed lists tear the UI | **Exists** |
| **Timers** | **One** scheduler for refresh, keepalive, session timer and ring timeout | One scheduler, N entries | O(log n) insert | N independent delays = N wakeups = battery. Principle 5 | **ASSUMPTION: partially.** Registration refresh and retry share the coordinator's scope; whether keepalive and session timers do was not verified in phase 1 |
| **Log redaction** | Single-pass, linear, **no backtracking** | Input is one SIP message | **O(n) strictly** | A catastrophically-backtracking regex on a large SIP message is an ANR. This is a hard requirement on the regex, not a preference | **Exists** (`docs/security.md` §Redaction) |
| **Level metering** | Fixed ring buffer, reused | Fixed length, zero allocation per tick | O(1) | Per-frame allocation in a path that runs 50×/second | **ASSUMPTION:** not verified in phase 1 |

---

## 3. The backoff, and the arithmetic that justifies it

Master prompt §6.2: *"**DECIDE** the backoff parameters… and **SHOW YOUR WORKING** for why
they are safe against the deployed server's concurrent-session ceiling."*

### 3.1 The ceiling, measured

`/usr/local/freeswitch/etc/freeswitch/autoload_configs/switch.conf.xml`, read 2026-09-09:

```
max-sessions          = 1000
sessions-per-second   = 30
```

**ASSUMPTION:** this is the ADR-005 deployment (`docs/architecture.md:181-186`) and not a
second instance. `docs/reconciliation.md` A-1b carries the same caveat. Confirm before
these numbers are quoted anywhere that matters.

### 3.2 Steady state

REGISTER is not a session, so `max-sessions` does not bound it directly; `sessions-per-second`
is the throughput figure that does. With N clients and a re-register interval T:

```
steady-state REGISTER rate = N / T
```

At the stated 5,000-user target and a 600 s (10 min) expiry:

```
5000 / 600 = 8.3 REGISTER/s
```

Against `sessions-per-second = 30`, that is **28% of the configured rate ceiling at steady
state**, before a single call is placed. Add calls at any realistic rate and the margin is
thin. **This is a server-capacity finding, not a client one**, and it is the first number
to put in front of whoever owns the deployment.

### 3.3 The restart burst — what the backoff is actually for

When the registrar restarts, all N clients fail within one round trip. With **no** backoff
they all retry at once: a 5,000-request instant, or **167× the per-second ceiling**. The
server that just came up is knocked over by the clients trying to come back to it.

Full jitter spreads them. Retry k is sampled uniformly from `[0, W_k]` where
`W_k = min(1800, 2 · 2^k)` seconds (`RegistrationBackoff.kt:94-98`):

| Retry | Window `W_k` | Expected peak rate if all 5,000 are in this round |
|---|---|---|
| 0 | 2 s | 5000 / 2 = **2500/s** |
| 1 | 4 s | 1250/s |
| 2 | 8 s | 625/s |
| 3 | 16 s | 312/s |
| 4 | 32 s | 156/s |
| 5 | 64 s | 78/s |
| 6 | 128 s | 39/s |
| **7** | **256 s** | **19.5/s** ← first round under the 30/s ceiling |
| 8 | 512 s | 9.8/s |
| 9 | 1024 s | 4.9/s |
| 10+ | 1800 s (capped) | 2.8/s |

**SHOW YOUR WORKING on the first row:** 5,000 clients sampling uniformly from a 2-second
window put an *expected* 2,500 into each 1-second bucket. That is 83× the ceiling. The
first retry round is therefore **guaranteed to fail for most clients**, and the design
depends on that being survivable rather than avoidable.

**The conclusion this arithmetic forces, and it is not comfortable:** with a 2-second base,
a 5,000-client fleet needs to climb to **retry 7** — roughly 8.5 minutes of cumulative
expected delay — before the aggregate rate drops under the server's ceiling. Every round
before that is 5,000 clients being refused, which is itself load.

**Three ways to fix it, and the trade:**

1. **Raise `sessions-per-second`.** Server-side, one config value, and the honest first
   answer: 30/s is low for a 5,000-user deployment regardless of what the client does.
2. **Raise the client's base delay.** A base of 30 s reaches 30/s at retry 2 rather than 7.
   Costs every client up to 30 s of extra downtime after a *transient* failure, which is
   the common case — and the common case is one client, not five thousand.
3. **Leave it.** The current parameters are correct for the failure that actually happens
   (one client, one flaky link) and merely slow for the failure that rarely does.

**DECIDE — unanswered.** Recommendation: **(1) first**, because it is the parameter that is
actually mis-set, and re-measure before touching the client. Do not change the base delay
to compensate for a server ceiling that should be raised.

`RegistrationBackoff` is stateless by design (`:38-40`): the attempt number is the caller's,
and a successful REGISTER resets it. Holding the count inside would mean one shared object
across accounts, each resetting the others.

### 3.4 `Retry-After` is honoured, and then spread

A 503 carrying `Retry-After` is never undercut (`RegistrationBackoff.kt:78-81`) — ignoring
it produces a second stampede immediately. But it is not obeyed *exactly* either: if 5,000
clients all wait precisely 120 s they return in one instant. A uniform sample from
`[0, 10 s]` is added on top, so the instruction is respected and the herd is still spread.

**SHOW YOUR WORKING:** 5,000 clients over a 10-second spread = **500/s**, still 16× the
ceiling. The server-requested jitter is sized for correctness (breaking simultaneity), not
for a fleet this size. If the fleet target is real, `DEFAULT_SERVER_JITTER` needs to scale
with it — 170 s of spread would be needed to reach 30/s. **This is the same DECIDE as §3.3
and resolves the same way.**

---

## 4. The codec audit **PROPOSED**

Master prompt §2.5 and §5.1. The full type design is in `docs/lld.md`; this is its
algorithmic half.

- **Input A — the declared feature set** (N-8): the codecs `config_site.h` was generated to
  contain. A compile-time constant, generated by the same build that writes the header, so
  the two cannot drift.
- **Input B — the registry**: `Endpoint.codecEnum2()` for audio and `videoCodecEnum2()` for
  video, each yielding `CodecInfo{codecId, priority, desc}`. Read once per endpoint start.
- **Algorithm**: set difference `A − B`, normalising both sides on the codec-name segment
  before the `/` — the same normalisation `applyPriorities` uses, so the audit and the
  priority application cannot disagree about what "the same codec" means.
- **Complexity**: O(n) over ≤ ~30 codecs, once per start. Never in the call path.
- **Output**: an immutable value in `:domain` — the registered set, plus a reason per
  declared-but-absent codec.

**The four reasons, and why the fourth is the one that earns the design:**

| Reason | Detected by | Example today |
|---|---|---|
| *Not compiled* | In the declared set, absent from the registry, and the build's flag is `0` | `LYRA` — `-DPJMEDIA_HAS_LYRA_CODEC=0` in the green run; `H264` — `PJMEDIA_HAS_OPENH264_CODEC 0` |
| *Compiled but registration failed* | Flag is `1`, absent from the registry | None known. This is the case a build log cannot show |
| *Registered but model files missing* | In the registry, manifest verification failed | Lyra on Exit A only |
| ***Usable but no peer accepts it*** | In the registry, and no negotiated call has ever selected it | **`OPUS` and `G722`, today.** The deployed FreeSWITCH offers neither (`docs/reconciliation.md` A-1b) |

The fourth reason is not a Lyra footnote. It is **the state the app's headline audio codec
is in right now**, and having it as a value is what lets the UI say "Opus: built, no peer
accepts it" instead of showing a preference that silently does nothing.

**Feed it into the existing priority application rather than beside it.** `applyPriorities`
already iterates only the codecs PJSIP registered (`RealPjsipCoreGateway.kt:889-901`), which
is why the H264 mismatch is skipped rather than raised. **That skip becomes reportable
evidence instead of a silent no-op** — the same loop, one more output.

---

## 5. Structures that are wrong today

Findings, with the fix and its cost. Each is a phase-9 delta.

### 5.1 Call log paging is `OFFSET`, and the index does not serve the sort

Two separate defects in one query (`data/calllog/…/CallLogDao.kt:44-53`).

**`OFFSET` degrades linearly.** SQLite fulfils `LIMIT n OFFSET k` by producing and
discarding `k` rows. Page 1 is O(n); page 100 of a 20-row page is O(2000 + 20). Master
prompt §6.2 names this exactly: *"`OFFSET` pagination degrades linearly as history grows."*

**The index is single-column; the sort is composite.** `CallLogEntity.kt:35` declares
`Index(value = ["started_at_epoch_millis"])`, and every query orders by
`started_at_epoch_millis DESC, id DESC` (`CallLogDao.kt:21, 35, 51`). The index resolves the
first key; the tiebreak on `id` is not covered. In practice `id` ties are rare, so this is
latent rather than active — but it is latent by luck, not by design.

**The fix — keyset pagination:**

```sql
SELECT * FROM call_log
WHERE (started_at_epoch_millis, id) < (:lastTimestamp, :lastId)
ORDER BY started_at_epoch_millis DESC, id DESC
LIMIT :limit
```

with `Index(value = ["started_at_epoch_millis", "id"])`. **O(log n) per page, independent of
depth.** The cost is that the caller carries the last row's key instead of a page number,
which Paging 3 supports natively.

**Cost of not fixing it:** invisible until a user has a long history, then a scroll that
gets slower the further it goes — the classic report nobody can reproduce on a fresh
install.

**Bound and policy, which the current query has neither of:** the call log is unbounded and
grows for the life of the install. **DECIDE:** a retention bound (rows, or age), with
deletion at that bound. Principle 4 — *unbounded is a crash with a delay*.

### 5.2 Three seam streams inherited an overflow policy nobody chose — **fixed**

Each of the four seam flows now declares `onBufferOverflow` explicitly, and the three that
publish with `tryEmit` from a non-suspend path route through `emitOrReport`, which logs a
refusal at WARN naming the stream. A drop is still possible on `endedCalls`,
`transferEvents` and `videoRequests` — `endCall` is not a suspend function and is reached
from three non-suspend paths — but it is no longer **silent**, which was the defect.
`incomingCalls`, the one stream where a loss is unacceptable, uses a suspending `emit` and
cannot drop at all.

### 5.3 Two structures were not verified in phase 1

Listed rather than asserted, because master prompt §10 (Honesty) makes an unverified claim
worse than an absent one:

- **The timer scheduler.** Master prompt §6.2 requires *one* scheduler for refresh,
  keepalive, session timer and ring timeout. Registration refresh and retry share
  `RegistrationRecoveryCoordinator`'s scope; whether keepalive and session timers are on
  the same scheduler or on independent delays was not established. **If they are
  independent, that is N wakeups per hour of idle registration and it is a battery finding**
  (Principle 5). **VERIFY before §9 budget 6 is measured.**
- **Level metering.** The zero-allocation ring buffer is required by §1.4 and was not
  located. **VERIFY.**
