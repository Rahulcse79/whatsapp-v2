# Task Breakdown v3 — device defects, and the PJSIP migration

Raised on **2026-09-07** from the first run on a real handset. Five items: four defects,
and a stack migration.

The four defects turned out to share a property worth stating up front — **not one of them
was in the SIP stack.** Every one lived in `:app` or `:domain`, above the `SipEngine`
abstraction, and every one would reproduce identically on PJSIP. That is the finding that
decides how item 5 is sequenced.

| # | Reported | Root cause | Task | State |
|---|---|---|---|---|
| 1 | LeakCanary: 5 × `ConnectionService$1` | The service was its own connection listener, held from a static map | 79 | **Fixed** |
| 2 | Mute does not work | Telecom's stale `isMuted` echoed back and un-muted the call | 80 | **Fixed** |
| 3 | Hold does not work | Telecom's state moved optimistically, before the re-INVITE was answered | 81 | **Fixed** |
| 4 | Video calling does not work | `isVideoEnabled` never adopted the negotiated media; camera claimed too late | 82 | **Fixed** |
| 5 | Move to latest stable PJSIP | — | 83–88 | **Blocked**, see ADR-006 |

---

# Phase 14 — What the handset found

### Task 79 — The retained `ConnectionService`
**Modules:** `:app`

`SipConnectionService` implemented `SipConnection.Listener` and handed **itself** to every
connection it made. Connections live in a static map on its companion — deliberately,
because Telecom creates and destroys the service on its own schedule while the app still
needs to end a call — so a destroyed service stayed reachable from a GC root for as long
as one of its connections did, and a new instance was retained on every rebind. LeakCanary
walks the framework's own `IConnectionService.Stub`, which holds `this$0`, and reports it
as `ConnectionService$1`; five of them meant five rebinds.

Done when:
- [x] The connection listener is a `@Singleton` (`TelecomCallBridge`), not the service
- [x] Its scope is `@ApplicationScope` — answering a call is not finished because Telecom
      unbound the service that received the button press
- [x] No `Service` instance is reachable from static state
- [ ] LeakCanary reports no retained `SipConnectionService` after a call — **needs the device**

### Task 80 — Mute
**Modules:** `:app`

Telecom reports its own `CallAudioState.isMuted` on every audio event, and a self-managed
connection has **no public API** to tell Telecom it muted itself — `Connection.setMuteState`
is package private. So Telecom's value sat at `false` however many times the user pressed
mute, and `SipConnection` forwarded it unconditionally: the next route change un-muted the
call the user had just muted.

Also removed: the registry was setting `AudioManager.isMicrophoneMute`, which is
**device-wide** — it muted the microphone for every app on the phone — and is not what
mutes a SIP call. The stack's own per-call mute already did that.

Done when:
- [x] The platform's mute is forwarded only when it actually changed
- [x] An app-side mute seeds the connection, so the platform's stale value cannot undo it
- [x] No device-wide microphone flag is written
- [ ] Mute holds across a route change on the handset — **needs the device**

### Task 81 — Hold
**Modules:** `:app`

`SipConnection.onHold` moved Telecom's own state immediately. That is the optimism
`LinphoneSipEngine.setHold` already refuses for the app's own UI: the re-INVITE may be
rejected, and a platform that believes a running call is held offers a resume that resumes
nothing.

Done when:
- [x] Telecom's hold state moves when the stack reports it, for a hold from any source
- [ ] Hold and resume round-trip against FreeSWITCH — **needs the device**

### Task 82 — Video calling
**Modules:** `:domain`, `:data:sip`

Two compounding bugs, neither in the SIP layer:

1. `CallControls.isVideoEnabled` defaults to `false` and the only thing that ever set it
   was the in-call video button. A call placed **as** a video call connected with video
   negotiated and its own controls saying video was off — and both `CameraPolicy` and the
   call screen read those controls, so the camera was never claimed and no local preview
   was ever drawn.
2. `CameraPolicy` required `isEstablished`, so capture was off at INVITE time. liblinphone
   writes the SDP offer then; with no capture device it can only offer `recvonly`, and
   nothing re-negotiates it afterwards.

Done when:
- [x] A connected call adopts the negotiated video into its controls, once, at connect
- [x] An outgoing video call holds the camera from the moment it is placed
- [x] An **incoming** call still waits to be answered — the §5.2 privacy rule is kept
- [x] Regression tests that fail on the parent commit
- [ ] Bidirectional video against FreeSWITCH — **needs the device**

---

# Phase 15 — PJSIP (ADR-006)

**Blocked, and on one question: where the binaries come from.** PJSIP publishes no Android
artifact — there is no `org.pjsip` group on Maven Central. Latest stable is **pjproject
2.17** (22 April 2026), built with `./configure-android` plus SWIG bindings, needing
**NDK r27+** for 16 KB page alignment. ADR-006 has the evidence and the three options.

Nothing below starts until that is answered. Sizing it honestly: `:data:sip` is **4,821
lines of production code and 3,663 lines of tests**, and nearly all of it is rewritten.
`:domain`, `:feature:*` and `:app` are untouched — that is the `SipEngine` seam paying off
exactly as ADR-001 said it would.

### Task 83 — Answer ADR-006 and prove the build
Build pjproject 2.17 for all three ABIs and confirm the two things a naive build silently
loses: **TLS** (DoD 13) and **Opus** (§5.2). `.github/workflows/build-pjsip.yml` is a first
cut and has never completed a run.

Done when:
- [ ] The sourcing option is chosen and recorded in ADR-006
- [ ] A build produces `libpjsua2.so` for `arm64-v8a`, `armeabi-v7a`, `x86_64`
- [ ] `configure` reports SSL **and** Opus **and** video as enabled, quoted in the ADR
- [ ] Every `.so` is 16 KB aligned

### Task 84 — Publish the artifact and pin it
Done when:
- [ ] The AAR is consumable by Gradle and pinned in `gradle/libs.versions.toml`
- [ ] The pin comes from the build's own output, never from memory
- [ ] The OSV job still passes

### Task 85 — `PjsipSipEngine` behind the existing seam
Done when:
- [ ] `SipEngine` is implemented against pjsua2 with no change to its contract
- [ ] Architecture Rule 2 is widened to keep `org.pjsip` inside `:data:sip`, as it does
      `org.linphone` today — and a fixture proves the rule fires
- [ ] The whole app still runs on `FakeSipEngine` (DoD 4)

### Task 86 — Registration, calling, hold, mute, DTMF
Done when:
- [ ] Every `LinphoneSipEngineTest` assertion passes against the PJSIP engine
- [ ] The call FSM and its mappers are unchanged — a stack swap must not touch `:domain`

### Task 87 — Video, transfer, conference
Done when:
- [ ] 1:1 video, camera switch, and mid-call escalation work (Tasks 51–54)
- [ ] Blind and attended transfer work (Tasks 55, 57)
- [ ] Dial-in conference works (Task 60)

### Task 88 — Retire liblinphone
Done when:
- [ ] The linphone dependency and its gateway are removed
- [ ] ADR-001 is marked superseded by ADR-006 with the date
- [ ] `docs/security.md` is corrected — the core-wide media-encryption limitation it
      records is liblinphone's, and PJSIP's constraints are not the same

---

## Checkpoints

Stop and read CI at Tasks **82** and **83**. Task 83 is the one that decides whether Phase
15 is a week or a month, and it is worth finding out before Task 85 is started.
