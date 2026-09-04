# Architecture — Native Android SIP Client

**Status:** Task 1 complete (decision record open). HLD sections — module graph, layer
diagram, sequence diagrams, threading model — are authored in **Task 67** and are
deliberately absent here rather than stubbed with placeholder content.

**Source of requirements:** [`../android-sip-app-prompt.md`](../android-sip-app-prompt.md)
**Task plan:** [`../tasks.md`](../tasks.md)

---

## 1. Decisions

Five decisions, four settled and one carried as an open cost item. Each records what was
decided, why, what it costs, and how to reverse it.

### ADR-001 — SIP stack: **liblinphone (linphone-sdk)**

**Status:** Accepted · **Decided:** 2026-09-04 · **Decider:** delegated to engineering

**Context.** `android.net.sip` was deprecated in API 31 and removed; a third-party stack
with native libraries is mandatory (§2.4). The realistic options are liblinphone
(Belledonne) and PJSIP/pjsua2 (Teluu). The target infrastructure is FreeSWITCH (ADR-003),
which interoperates well with both, so infrastructure does not decide it.

**Decision.** Embed **liblinphone / linphone-sdk**, consumed as a published AAR.

**Why, given this project's scope:**

1. **The scope is wide, not deep.** This project needs SIP + SRTP/ZRTP + video codecs
   (VP8/H.264) + Opus + ICE/STUN/TURN + adaptive bitrate, all working together.
   liblinphone ships that as one integrated, tested unit. PJSIP gives finer control over
   a narrower core and leaves more of the media pipeline to assemble.
2. **No NDK build pipeline to own.** liblinphone publishes an AAR with prebuilt `.so`
   files. PJSIP means building and maintaining your own cross-compilation for every ABI,
   plus re-doing it for each NDK and 16 KB page-size requirement (§3). That is a standing
   maintenance cost paid by every engineer who touches the build.
3. **Push is first-class in the SDK.** liblinphone models RFC 8599 push parameters
   directly, which is exactly the mechanism ADR-004 depends on. With PJSIP the `pn-*`
   Contact parameters must be assembled and maintained by hand.
4. **Video is the differentiator.** Phase 6 needs bidirectional video, camera switching,
   orientation handling, and mid-call escalation. liblinphone's video stack is the more
   complete of the two out of the box.

**What we give up.** Less control over the media path, a larger APK, and a heavier
dependency. Neither is decisive at this scope.

**Cost.** APK size increase — measure and record in Task 25. Licence: see ADR-002.

**Reversibility.** High, and deliberately so. The stack lives entirely behind the
`SipEngine` interface (§4.3) in `:data:sip`, enforced by an architecture test (Task 12,
DoD 3). Swapping to PJSIP is a rewrite of one module, not of the application.

**Distribution finding (2026-09-04).** liblinphone is **not published to Maven Central or
Google Maven** — a version query against both returns nothing for
`org.linphone:linphone-sdk-android`. It is hosted on Belledonne's own Maven repository.

That has two consequences worth deciding on before Task 25:

1. **A third-party Maven repository must be added** to `dependencyResolutionManagement`.
   The build currently allows only `google()` and `mavenCentral()`, with
   `FAIL_ON_PROJECT_REPOS` so no module can add its own. Adding a repository widens the
   supply chain, and the artifacts should be pinned by version and ideally verified by
   checksum.
2. **The OSV vulnerability gate will not see it.** OSV indexes the Maven ecosystem;
   an artifact served from a private repository has no advisories to match. The gate
   stays useful for everything else, but liblinphone's own security notices have to be
   tracked by subscribing to Belledonne's releases — a manual process, and it should be
   named as such rather than assumed covered.

**Verify in Task 25 before writing code against it** (§13 — ground every claim):
- Pin an exact linphone-sdk version and record the artifact coordinates **and the
  repository URL** here.
- Confirm the version's push-configuration API surface against its own release notes,
  rather than against this document.
- Confirm every bundled `.so` is 16 KB page-size aligned; if not, that is a blocker to
  raise immediately, not a workaround to invent.

---

### ADR-002 — Licence position: **GPLv3 working assumption, commercial licence UNRESOLVED**

**Status:** ⚠️ **Open — blocks release, not development** · **Owner:** needs a business decision

**Context.** The distribution model is not yet decided. liblinphone is dual-licensed:
**GPLv3 or a commercial licence from Belledonne Communications**. PJSIP is GPLv2 or
commercial from Teluu. There is no free closed-source path with either.

**Decision (provisional).** Develop against the **GPLv3 terms**. This is the assumption
that is safe to build under, because it constrains nothing during development and
converting *later* costs money but not code.

**The consequence, stated plainly:**

| Distribution model | Obligation |
|---|---|
| App source published under GPLv3 | No fee. Your entire app becomes GPLv3. |
| Shipped to customers, source closed | **Commercial licence required.** Typically a recurring annual fee. |
| Internal / single-client enterprise | Still distribution. Offer source to recipients, or licence commercially. |

**Why this is flagged rather than assumed away.** If the app ships closed-source, the
licence is a **purchase**, and discovering that after Phase 9 is an expensive surprise.
Nothing in Phases 1–8 depends on the answer, so development proceeds — but the answer is
needed before any external release.

**Action required (not by engineering):**
1. Decide the distribution model.
2. If closed-source: obtain a quote from Belledonne and budget it.
3. Have counsel review the GPLv3 path if it is chosen — note that GPLv3's installation-
   information and anti-tivoization terms interact with app-store distribution in ways
   worth checking. **This document is not legal advice.**

**Review date:** before Task 64 (release build). Escalate if unresolved by Phase 8.

---

### ADR-003 — Conference: **FreeSWITCH `mod_conference`, dial-in MCU**

**Status:** Accepted · **Decided:** 2026-09-04 · **Decider:** stakeholder (existing infrastructure)

**Context.** SIP is point-to-point; multi-party calling requires a conference focus
server-side (§2.2). A FreeSWITCH instance is already deployed and will be used.

**Decision.** **Dial-in MCU** (§2.2 option a) using FreeSWITCH `mod_conference`. The
client places one ordinary call to a conference URI; the server mixes and returns a
single stream.

**Why.** It follows from the infrastructure, and it is the recommended default anyway:
it works over baseline SIP with no signalling extension, needs one decode path on the
client, and keeps device CPU and battery cost flat as participants grow — which matters
on the mid-range Android hardware most users will have.

**What we give up.** No per-participant video layout control, and no client-side
selective forwarding. Active-speaker or grid layout is whatever the bridge composes.

**Reversibility.** Preserved by design. `ConferenceSession` (Task 59) models **N**
participant streams with no dial-in-specific assumption baked in, so moving to an SFU is
an implementation swap in `:data:sip`, not a domain rewrite.

**Verify before Phase 8 (Task 60):**
- Confirm the FreeSWITCH conference profile, the dial-in extension pattern, and the PIN
  policy on the deployed instance.
- Confirm whether the deployment publishes a **participant roster** the client can
  subscribe to. If it does not, Task 60's participant list shows what is actually known
  and says so — it does **not** render a fabricated list (§13).

---

### ADR-004 — Push wake path: **RFC 8599 client parameters + an ESL-driven push gateway**

**Status:** Accepted, with one item to verify · **Decided:** 2026-09-04 · **Decider:** delegated to engineering

**Context.** Android will not let a backgrounded app hold a SIP registration
indefinitely. A registration-only design misses incoming calls in Doze or after process
death; on Android 12+ push is the primary delivery path, not a fallback (§2.5).

**Decision — a two-part design, so the client is correct regardless of what the server
turns out to support:**

**Client side (build unconditionally).** Always send RFC 8599 push parameters on the
`Contact` header at `REGISTER`:

```
pn-provider = fcm
pn-param    = <FCM sender / project identifier>
pn-prid     = <FCM registration token>
```

This is the standard mechanism, it costs nothing if the server ignores it, and it means
the token reaches the server without a bespoke side channel. Token rotation triggers
re-registration (Task 38).

**Server side.** FreeSWITCH core **does not ship an FCM or APNs sender** — a push gateway
is required either way. Build a small service that:

1. Subscribes to the FreeSWITCH **Event Socket (ESL)**.
2. Detects an inbound call to an endpoint that is push-registered but not currently
   reachable on an open transport.
3. Sends an **FCM high-priority data message** to that endpoint's `pn-prid`.
4. Lets the INVITE proceed once the client re-registers.

**Server → app payload contract (normative — Task 38 implements exactly this):**

| Field | Type | Meaning |
|---|---|---|
| `call_id` | string | SIP `Call-ID` of the pending INVITE, for correlation |
| `account_id` | string | Which registered identity the call is for |
| `sent_at` | epoch ms | For staleness detection — drop if older than the ring timeout |
| `type` | enum | `incoming_call` (extensible: `missed_call`, `message_waiting`) |

**The payload carries no credentials and no call content.** Caller identity arrives in
the INVITE over the secured signalling channel, not in the push (DoD 12). The push says
only "wake up and re-register".

**To verify (Task 38, before implementing):** whether the deployed FreeSWITCH version
stores `pn-*` parameters in its sofia registrations — inspect a live registration
(`sofia status profile <profile> reg`) rather than assuming from version numbers. If it
does not retain them, the gateway reads tokens from its own store instead, and the client
side is unchanged. This is why the client half is built unconditionally.

**Consequence.** A backend component must be built and operated. It is small, but it is
real work outside this repo, and it is **out of scope for this app** (§11) — the app
consumes the contract above; it does not implement the gateway.

---

### ADR-005 — Test target: **the existing FreeSWITCH deployment; no local Docker server**

**Status:** Accepted, with a recorded risk · **Decided:** 2026-09-04 · **Decider:** stakeholder

**Decision.** Integration tests run against the already-deployed FreeSWITCH server.
Task 32 no longer builds a `docker/compose.yaml`; it configures and documents access to
the existing instance instead.

**What this buys.** Tests exercise the real production configuration — real dialplan,
real codec negotiation, real TLS certificates, real NAT topology. That is genuinely
higher-fidelity than a clean-room container, and it removes a maintenance burden.

**Risk accepted — stated so it is a choice, not an accident.** A shared remote server
means integration tests are **not** hermetic:

- Tests need network reachability to that host, so CI cannot run them in isolation.
- State is shared. Concurrent CI runs, or a colleague testing at the same time, can
  collide on the same extensions or conference room.
- The server cannot be reset to a known state between runs.
- A registrar-restart test (Task 33, DoD 6) means restarting a **shared** service.

**Mitigations, to settle in Task 32:**
1. Reserve extensions used **only** by automated tests, distinct from manual-testing ones.
2. Reserve a dedicated conference room number for Task 60.
3. Serialize integration-test runs (a CI concurrency group of 1) to avoid collisions.
4. For the registrar-restart case, prefer a client-side transport drop over restarting a
   shared service — and if a real restart is needed, run it as a scheduled manual test,
   not on every CI push.
5. Keep unit tests and `FakeSipEngine` journeys (DoD 4) as the CI gate. Integration tests
   run on a schedule or on demand. **This keeps CI fast and deterministic** and is the
   part of the Docker plan actually worth preserving.

**Reversibility.** High. Standing up a local FreeSWITCH container later is additive and
changes no application code. Revisit if CI flakiness from shared state becomes a drag.

---

## 2. Settled inputs to the rest of the plan

| Question | Answer | Affects |
|---|---|---|
| SIP stack | liblinphone (linphone-sdk), version pinned in Task 25 | Tasks 25, 27 |
| Licence | GPLv3 assumed; commercial licence **unresolved** | Task 64, release |
| Conference server | FreeSWITCH `mod_conference` | Tasks 59, 60, 61 |
| Conference model | Dial-in MCU; domain shaped for SFU | Tasks 59, 60 |
| Push | RFC 8599 params + ESL push gateway (backend, out of scope) | Task 38 |
| Test target | Existing FreeSWITCH; no Docker | Tasks 32, 33, 46, 55–57, 60 |
| Concurrency framing | 5,000 is a server metric — client delivers backoff, jitter, keepalive economy (§2.1) | Tasks 26, 30 |

## 3. Open questions

Tracked, not guessed (§13). Each names an owner and a deadline.

| # | Question | Owner | Needed by |
|---|---|---|---|
| Q1 | Distribution model — closed-source, internal, or open? Drives ADR-002. | Business | Before Task 64 |
| Q2 | If closed-source: is the Belledonne commercial licence budgeted? | Business | Before release |
| Q3 | FreeSWITCH hostname, SIP domain, transport, and TLS CA for the test target | Infra | Task 32 |
| Q4 | Test extensions to reserve for automation, and a dedicated conference room | Infra | Task 32 |
| Q5 | Does the deployed FreeSWITCH retain `pn-*` params in sofia registrations? | Infra | Task 38 |
| Q6 | Who builds and operates the ESL push gateway? It is outside this repo. | Eng lead | Before Task 38 |
| Q7 | Does the conference profile publish a participant roster? | Infra | Task 60 |
| Q8 | Is call recording actually required, and in which jurisdictions? Drives the consent model in §2.6. | Legal / Product | Before Task 58 |

## 4. HLD

Authored in **Task 67**: module graph, layer diagram, sequence diagrams (register,
outgoing, incoming-via-push, hold, transfer), and the threading model. Left empty here
on purpose — a stub that looks like content is worse than an honest gap (§1).
