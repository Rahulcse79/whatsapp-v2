# Prompt: make APK-to-APK calling work, and prove it

> **How to use this file.** Paste it whole as the opening message to a coding agent working
> in `whatsapp-v2`. It governs the seven reported defects in the calling path.
> `docs/master-engineering-prompt.md` still governs *how* the work is done — its five
> principles, its layer rules, its review rubric and its honesty rules are unchanged and
> apply here. This file adds *what* to fix and *what counts as proof*.
>
> **DECIDE** = must be answered before code is written. **VERIFY** = must be checked against
> source or a device before it is relied on. **SHOW YOUR WORKING** = a number with the
> arithmetic beside it.
>
> **Revised 2026-09-10**, after a session that read a full device trace off the handset and
> probed the reference server directly. Every line marked *measured* below was observed, not
> recalled; every unverified line says so. That distinction is the point of the document:
> **the previous revision of this file contained two confident diagnoses that the evidence
> disproves**, and both are corrected in §2.6.

---

## 1. The rule this file exists to enforce

**A calling defect is not fixed until a call has been placed, answered and heard.**

Every item below carries an acceptance criterion naming a device, a call and an observable
outcome. "It compiles", "the code now looks right" and "the log line is gone" are not among
them. §10 of the master prompt already distinguishes *compiled*, *registered*, *negotiated*
and *verified on hardware* as four different claims; this file is where the fourth one is
mandatory.

**The reference setup is part of the specification:**

| | |
|---|---|
| Server | `192.168.80.145`, reached through a NAT — the handset's `192.168.137.140` arrives as `192.168.100.150`. Presents itself as `iriscloud`; a FreeSWITCH derivative (`mod_sofia`, `X-FS-Support`, `X-FSUUID`) |
| Transports | **UDP 5060 only.** TCP 5060 refuses the connection and TLS 5061 is closed — both measured, §2.3 |
| Extensions | `7000`–`7005`. `7000` is the handset's registration |
| Handsets | Zebra TC15, Android 13 (API 33), `arm64-v8a`, attached over USB debugging |

**A third-party client is the control.** When a call fails, the first question is always
*does it also fail from Zoiper, on the same network, against the same extension?* If the
third party works and this app does not, the defect is here. If both fail, the defect is in
the server or the network, and the fix does not belong in this repository.

---

## 2. The verified baseline

Read this before proposing anything. Every row was observed on hardware, probed against the
live server, or read out of source in this tree.

### 2.1 What works

| Fact | Evidence |
|---|---|
| PJSIP builds from vendored source and starts on Android 13 | `Endpoint constructed` → `libCreate ok` → `libInit ok` → `transports created (UDP, TCP, TLS)` → `libStart ok` |
| The generated bindings match the binary | `Endpoint constructed` cannot happen otherwise — the JNI symbols are named after the generated Java |
| Registration over UDP works | `TX REGISTER` → `RX 401` → `TX REGISTER` (with digest) → `RX 200`, repeatedly, against `192.168.80.145` |
| Inbound INVITEs arrive and ring | Telecom reaches `SET_RINGING, successful incoming call`; the app's own screen follows |
| Answering wires the media correctly | On the one call that negotiated, `Conf connect: 0 --> 1` and `1 --> 0`, then six seconds of two-way RTP: `RX 263pkt 42.0KB`, `TX 339pkt 54.2KB`, loss 0.3 %, jitter 7 ms, RTT 20 ms |
| The SIP trace works | The `Settings → SIP trace` switch produces full `PjsipTrace` message dumps in logcat. Every finding below came from it |

### 2.2 What the library actually registers, on device

The audit prints this once per endpoint start:

```
registered audio = PCMU/8000/1, PCMA/8000/1, GSM/8000/1, iLBC/8000/1, AMR-WB/16000/1,
                   AMR/8000/1, speex/32000/1, speex/8000/1, speex/16000/1,
                   L16/44100/2, L16/44100/1
registered video = VP8/102, H264/99, VP8/103, VP9/106
```

**That list is filtered, and the filtering is a defect in the audit rather than a fact about
the build.** `CodecAuditor.audit` removes every codec named in
`DeclaredFeatureSet.unnegotiableOnThisDeployment` — today `opus` and `g722` — from
`registeredAudio` before anything logs it. The wire proves they registered: a real INVITE
off this handset carries

```
m=audio 4005 RTP/AVP 96 0 8 9 120 121
a=rtpmap:96 opus/48000/2
a=rtpmap:9 G722/8000
```

Opus and G.722 are registered, enabled and offered. See §2.6, correction 1.

### 2.3 What the server accepts — measured, not assumed

Probed from a host on the handset's network on 2026-09-10:

| Probe | Result |
|---|---|
| SIP `OPTIONS` over **UDP** 5060 | `SIP/2.0 200 OK` |
| TCP connect to 5060 | `ECONNREFUSED` |
| TCP connect to 5061 | closed |

**And the size limit, bisected:**

| SIP payload | Result |
|---|---|
| 600 bytes | response |
| 1200 bytes | response |
| 1400 bytes | response |
| 1440 / 1460 / 1470 / **1472** bytes | response |
| **1475** bytes | **no response** |
| 1480 / 1500 / 1742 / 1900 bytes | no response |

**SHOW YOUR WORKING:** 1472 + 8 (UDP header) + 20 (IPv4 header) = **1500**, exactly the
Ethernet MTU. The path drops every IP-fragmented datagram. Anything above 1472 bytes of SIP
payload is discarded in silence — no ICMP, no response, nothing to see.

### 2.4 The declared feature set

`pjsip/config/pj/config_site.h`:

```
PJMEDIA_HAS_VIDEO           1     (:40)
PJMEDIA_HAS_VPX_CODEC       1     (:45)
PJMEDIA_HAS_OPUS_CODEC      1     (:54)
PJMEDIA_HAS_OPENH264_CODEC  0     (:71)
PJMEDIA_HAS_LYRA_CODEC      0     (:77)
```

`H264/99` nevertheless appears in the video registry, because `PJMEDIA_HAS_ANDROID_MEDIACODEC`
registers it through the platform encoder rather than through OpenH264. The declared set in
`docs/architecture.md` §4.11 does not say so and must.

### 2.5 Three diagnostic signatures worth recognising

- **Seven retransmissions then silence** — `TX INVITE` at roughly 0.5 s, 1 s, 2 s, 4 s, 8 s,
  16 s, 32 s with no response of any kind — is SIP **Timer B**: the INVITE transaction
  expiring with zero responses. Not a 404, not a 486, not a 488. It means the request never
  arrived, or the response never came back. §2.3 says why it never arrived.
- **`488 Unable to create media session`** sent *by this app*, immediately after
  `Answering call N: code=200`, is not the peer rejecting anything. It is pjsua's own
  `on_media_update` failing and disconnecting the call it has just answered.
- **`Call N media 0: Disabled due to no active codec`** means the endpoint's own codec
  registry had nothing enabled when the SDP was built. It is a statement about this app, not
  about the far end.

### 2.6 Two corrections to the previous revision

Both were diagnosed by reading code and both are wrong.

**Correction 1 — "Opus and G.722 do not register."** They do. §2.2 shows them on the wire.
What is true is that the audit's log line is filtered and therefore cannot be used as
evidence about the registry. The audit is misleading; the build is not broken.

**Correction 2 — "Exactly 32 seconds then disconnect is Timer B, meaning the request never
reached anything."** The first half is right and the second half is now specific: the request
does not reach anything **because it is larger than the path MTU and there is no TCP listener
to fall back to.** PJSIP behaves correctly — the trace shows it obeying RFC 3261 §18.1.1 and
attempting TCP first — and the fallback fails on a network fact, not a code fact.

---

## 3. The seven items

Each carries the symptom as reported, what the trace establishes, what must still be
**VERIFIED**, and acceptance criteria that are binary and device-observable.

### 3.1 Item 1 — answering the call disconnects it

**Reported:** an APK-to-APK call reaches the destination, and answering it disconnects
immediately.

**Root cause — established from the trace, not inferred.** The disconnecting party is **this
app**. The sequence, verbatim:

```
Answering call 1: code=200
SDP negotiation done: Success
Call 1: updating media..
audio updated, stream #0:  (inactive)
video updated, stream #1:  (inactive)
Unable to create media session: No active media stream after negotiation
                               (PJMEDIA_SDPNEG_ENOMEDIA) [status=220048]
TX 553 bytes Response msg 488/INVITE ... SIP/2.0 488 Unable to create media session
```

Both streams came out inactive because the endpoint had **no enabled audio codec at all**.
Twenty-three minutes earlier the account had been saved with a single audio preference,
`lyra`, which this build does not contain:

```
Audio codecs preferred but not in this build: [lyra]
Codecs for acaea878-…: audio=[lyra] video=[VP8, H264]
```

`RealPjsipCoreGateway.applyPriorities` walks every **registered** codec, looks for a match in
the account's preference list, and assigns `CODEC_DISABLED` to everything unmatched. With
`[lyra]` as the whole list, nothing matched, so **every registered audio codec was set to
priority 0**. `pjmedia_endpt_create_audio_sdp` breaks out of its loop on the first disabled
codec (`pjmedia/src/pjmedia/endpoint.c:490`), yields `fmt_count == 0`, and pjsua deactivates
the media line.

Three properties make this worse than a bad setting:

1. **PJSIP codec priorities are endpoint-wide.** The gateway's own comment concedes that "the
   last account configured is the one whose codec list wins" — but not the consequence: one
   account's unmatchable preference list silently disables audio for **every** account.
2. **It persists.** The state survives re-registration and outlives the account edit that
   caused it. Four subsequent calls in the trace all logged `Disabled due to no active codec`.
3. **It breaks outgoing calls identically**, which is the corroborating evidence: the very
   next outgoing INVITE carried `m=audio 0 RTP/AVP 0` — a deactivated media line with a
   placeholder payload type.

**Acceptance criteria.**

1. An account whose audio preferences match nothing in the registry **cannot** leave the
   endpoint with zero enabled audio codecs. Proven by a JVM test over the decision, not by a
   device.
2. One account's preferences cannot disable a codec another configured account requires.
   By test.
3. The condition is reported once, at `ERROR`, naming the account and the preferences that
   matched nothing.
4. An APK-to-APK audio call is answered on the far handset and **stays up for 60 seconds**,
   measured.
5. **Audio is heard in both directions** by a person — stated as a person's observation, not
   inferred from RTP counters.

**Non-goal.** Do not "fix" this by hardcoding a codec list, disabling SRTP, or removing the
per-account preference. The setting is legitimate; silently disabling the endpoint is not.

### 3.2 Item 2 — video calling never reaches the destination

**Reported:** a video call from one APK to another never arrives.

**Root cause — measured.** The INVITE is larger than the path can carry, and there is no
transport to fall back to.

**SHOW YOUR WORKING**, from one real audio INVITE off the handset:

| | bytes |
|---|---|
| INVITE as sent (pjsua's own count) | **1742** |
| Path limit (§2.3) | **1472** |
| Excess | **270** |
| of which: 4 × `a=crypto:` lines | 384 |
| of which: `a=ice-ufrag` + `a=ice-pwd` + 2 × `a=candidate` | 198 |
| of which: two `telephone-event` clock rates | 105 |

The trace shows PJSIP doing exactly the right thing and losing anyway:

```
TX 1748 bytes Request msg INVITE ... to TCP 192.168.80.145:5060
TCP connect() error: [code=120111]: Connection refused
Temporary failure in sending ... will try next server: Connection refused
TX 1742 bytes Request msg INVITE ... to UDP 192.168.80.145:5060
   (then 0.5s, 1s, 2s, 4s, 8s, 16s, 32s — no response at any point)
```

RFC 3261 §18.1.1 requires a UAC to switch to a congestion-controlled transport when a request
approaches the path MTU. PJSIP switched. The server refuses TCP (§2.3). The UDP fallback is
fragmented and dropped.

**The natural control is in the same trace.** A malformed 781-byte INVITE — the one produced
by the codec bug in item 1 — **did** get an answer:

```
TX 781 bytes Request msg INVITE ...     →   RX 343 bytes Response msg 100/INVITE
```

Small INVITE: answered. Large INVITE: silence. Same handset, same server, minutes apart.

**Why this hits video hardest.** An audio-only offer is 1742 bytes. A video call adds a
second `m=video` line with its own crypto block, its own ICE candidates, `rtpmap` entries for
VP8 and H264, an H264 `fmtp`, and `rtcp-fb` attributes — of the order of another 900–1000
bytes. **A video INVITE is roughly 2700 bytes: nearly twice the limit.** Audio is marginal
and sometimes survives; video never does.

**DECIDE, and this is the honest part.** Trimming the offer can bring an *audio* INVITE
comfortably under 1472 bytes. **It cannot bring a full audio+video offer under it.** So state
which of these the project is doing, in `docs/architecture.md`, with the rejected alternative:

| Option | Consequence |
|---|---|
| **Get a TCP listener on the server** | The correct fix. Not a change in this repository, and it must be raised with whoever operates the server |
| **Trim the offer** | Makes audio work now. Not sufficient for video by itself |
| **Both** | What is actually required for video to work at all |

**VERIFY before claiming video is fixed:**

1. The byte length of the video INVITE, after every trim, measured off a handset.
2. Whether it crossed 1472. If it did, video is **not** fixed and the report must say so.
3. Which transport carried it, and whether that transport is reachable.

**Acceptance criteria.**

1. A trace showing the video INVITE, its byte length, the transport it used, and the
   negotiated `m=video` — or, failing that, an explicit statement that it exceeded the path
   MTU with the arithmetic beside it.
2. A JVM test asserting the SDP-size budget, so a future change that re-inflates the offer
   fails the build rather than a call.
3. When both sides negotiate video, a person confirms picture in both directions, and camera
   switch and video mute work during that call.

### 3.3 Item 3 — support all available PJSIP codecs

**Reported:** enable and support all available PJSIP codecs for APK-to-APK calls, with
proper negotiation and stable media.

**This item needs reframing, and the reframing is most of the work.** "All available" cannot
mean "every codec pjmedia can be built with", for reasons this tree now demonstrates rather
than asserts:

1. **The registry is already larger than the offer.** The library registers GSM, iLBC, AMR,
   AMR-WB, Speex at three rates and L16 at two, and the account offers none of them. Enabling
   all of them makes the SDP larger — which, given §2.3, makes calls *fail* rather than work.
   Every codec added is bytes on an INVITE that is already 270 bytes over the limit.
2. **A codec with no peer is not support.** It is a claim in a settings screen that the wire
   does not honour.
3. **The audit currently misreports the two most interesting cases**, §2.2, so nobody can
   tell which state any codec is actually in.

**So the deliverable is:**

1. **Fix the audit's two defects.**
   - Its log line claims to print what the library registered and prints a **filtered** list.
     Master prompt §11 phase 4 requires the registry verbatim. Print the registry; report
     strandedness separately.
   - `AbsenceReason.NoPeerAccepts` is decided from `unnegotiableOnThisDeployment`, a hardcoded
     set measured against one server on one day. It is a fact about a deployment reported as
     though it were measured about whichever server is in use. Either derive it from evidence
     about the server actually in use, or rename it to something honest about what is known.
2. **Choose the declared set deliberately, against the size budget.** For each codec: does
   the reference server have it, does the app need it, and what does it cost in SDP bytes?
   Record the answer in `docs/architecture.md` §4.11 with the module that exercises it, as
   N-8 requires.
3. **Make an unmatchable preference list safe** — that is item 1's fix, and it belongs to this
   item too.

**Acceptance criteria.**

1. The audit's log line prints the registry unfiltered, and a JVM test proves it.
2. A stranded codec is reported with a reason the app can justify from evidence it has.
3. For every codec in the final declared set, an APK-to-APK call negotiates it and audio is
   heard — one call per codec, recorded. This is N-9's on-device round trip.
4. The declared set's total SDP cost is stated in bytes and is under the §2.3 budget.

**Non-goal.** Enabling OpenH264 or Lyra. OpenH264 carries Cisco's licensing terms and is a
product decision; Lyra is ADR-008 and its gate has not passed.

### 3.4 Item 4 — "production-ready, maximum stability, fix all bugs"

**This item cannot be accepted as written, and pretending otherwise is the failure mode.**
"Fix all bugs" has no completion test, so no honest report can ever claim it. §9 of the
master prompt makes the same point about absolutes: *"Absolutes like 'zero crashes' cannot be
tested and therefore cannot be delivered."*

**Replace it with the budgets that already exist and are unmeasured.** `docs/dod-sweep.md`
lists them; not one carries a number from hardware. Deliver these instead:

1. **Crash-free rate over a stated soak**, with a denominator. One hour registered idle plus
   twenty call cycles is a defensible start.
2. **Mouth-to-ear latency**, both ends local, method stated.
3. **CPU during a steady 1:1 call** on `arm64-v8a`, on hardware.
4. **Native memory across 30 minutes of call** — deltas at 0, 15 and 30 minutes. Unbounded
   growth here is the defect the budget exists to catch.
5. **50 create/destroy cycles** returning to within a stated delta of baseline.
6. **Battery over one hour idle-registered**, against the registration-only path.

**Acceptance criterion.** Each of the six carries a measured number and a stated method in
`docs/dod-sweep.md`, replacing the current "NOT VERIFIED". A budget that is missed is a
finding and is reported as one; a budget with no number is not delivered.

**And one specific reliability defect is already known.** Three of the four streams crossing
the `SipEngine` seam publish with `tryEmit` and discard the result, so they drop **silently**
when the buffer is full — including `endedCalls`, where a drop is a call that happened and is
missing from the history (`docs/reconciliation.md` A-5, `docs/data-structures.md` §1.2).
`emitOrReport` logs the refusal; it does not prevent the loss. Decide the policy per stream
from that table and implement it.

### 3.5 Item 5 — the dialer forgets the selected extension

**Reported:** selecting an extension from the dropdown, leaving the dialer and returning
resets it.

**Known, and understood.** The override lived in a private `MutableStateFlow(Entry())` in
`DialerViewModel`. Compose Navigation scopes a ViewModel to its destination, so leaving
discards it and `uiState` falls back to the default account.

**The trap.** The same override is documented as **per call**, and `place()` clears it after a
successful call so that a one-off call from the work account does not silently become every
later call's account. Those are two different lifetimes:

- **survives leaving the screen** — what this item asks for;
- **survives placing a call** — what the design deliberately refuses.

A fix that persists the value somewhere `place()` does not clear turns a per-call override
into a sticky setting, which is a worse bug than the one being fixed.

**Acceptance criteria.**

1. Select a non-default account, navigate away, return: the selection is still shown.
2. Select a non-default account, place a call **successfully**, return: the selection is back
   to the default. Both by test.
3. Compiles under **`-PwarningsAsErrors=true`**. CI uses that flag; a local build without it
   passes while CI fails, and that exact mistake has already cost a red build here — an
   `Unnecessary safe call on a non-null receiver` in this very file.

### 3.6 Item 6 — memory leaks and resource lifecycle

**Reported:** find and fix all leaks and lifecycle problems.

**LeakCanary has now been read, and it reports one leak.** Verbatim from the device:

```
1 APPLICATION LEAKS ... 5372 bytes retained by leaking objects
Displaying only 1 leak trace out of 2 with the same signature
┬─── GC Root: Global variable in native code
├─ android.telecom.ConnectionService$1 instance
│    Anonymous subclass of com.android.internal.telecom.IConnectionService$Stub
│    ↓ ConnectionService$1.this$0
╰→ com.whatsappv2.telecom.SipConnectionService instance
     Leaking: YES (received Service#onDestroy() and not held by ActivityThread)
     Retaining 2.7 kB in 38 objects
0 LIBRARY LEAKS
```

**VERIFY before writing a fix, because the retaining reference is the framework's own.** The
path is `IConnectionService$Stub` → `this$0`, which is `android.telecom.ConnectionService`'s
own binder holding its outer service. Nothing in this repository appears on that path. Two
possibilities, and they need separating with evidence rather than argument:

1. It is Android's retention of an unbound `ConnectionService`, in which case the honest
   report is a named leak with its retaining reference and a stated reason it is not fixable
   here — which acceptance criterion 1 explicitly permits.
2. Something in this app keeps Telecom bound longer than it needs to be, in which case the
   instance count grows with call count. **Measure it:** run twenty call cycles and record
   whether the count of retained `SipConnectionService` instances grows without bound or
   settles. That number is the deliverable.

**Two lifecycle hazards specific to this stack, neither to be regressed:**

- **pjsua2 objects are native handles with an owner.** A `Call` that outlives its native peer
  is a use-after-free that surfaces days later as a random crash.
- **SWIG directors (`Account`, `Call`, `LogWriter`) have native peers**, and a collected one
  is a use-after-free. They must stay referenced for the life of the endpoint — and
  `libDestroy` deletes the log writer, so there is exactly one writer per `start`.

**Acceptance criteria.**

1. LeakCanary is clean across a real session, or each remaining leak is named with its
   retaining reference and a stated reason it is not fixable here.
2. The instance count above is measured across twenty call cycles and reported as a number.
3. 50 create/destroy cycles return to within a stated delta of baseline.
4. The thread-confinement assertion (DoD 4) still fires on a foreign thread, by test.

### 3.7 Item 7 — placing a call on an unregistered account

**Reported:** if the account is not registered but the extension exists, the call should
reconnect the account automatically and then place the call.

**Known, and the gap is real.** `PlaceCallUseCase`
(`domain/src/main/kotlin/com/whatsappv2/domain/usecase/PlaceCallUseCase.kt:73-85`) resolves an
account and a target, then places the call. **It never consults registration state.** Its only
account-related failures are `NoAccountAvailable` and `UnknownAccount`, neither of which means
"not registered". The engine below it does check, and returns `SipError.NotRegistered` — so
today the user gets a refusal with no attempt to fix the cause.

**DECIDE before implementing. Three defensible answers:**

| Option | Behaviour | Cost |
|---|---|---|
| **Refuse** | a distinct error, and the UI says so | Honest and instant. The user must retry by hand |
| **Register, then dial** | await registration with a bounded timeout, then place | What was asked for. Adds latency on a cold account, and a bounded wait needs a number |
| **Dial anyway** | current behaviour below the use case | The call dies at Timer B, 32 seconds later, with no explanation |

**Option 3 is not acceptable** — it is the reported defect. Choose 1 or 2 and record it in
`docs/architecture.md` as a decision with the rejected alternative.

**If option 2:** the wait is bounded and the bound is stated. **SHOW YOUR WORKING** on the
number, against `RegistrationBackoff`'s parameters — base 2 s, ceiling 1800 s
(`domain/…/registration/RegistrationBackoff.kt:101-104`). A wait longer than a user will hold
a phone to their ear is a refusal with extra steps. Registration against the reference server
completes in **about 25 ms** once the request is on the wire (§2.1: `TX REGISTER` at
`11:43:11.725`, `RX 200` at `11:43:11.782`, including the 401 round trip), so the bound is
about tolerating a lost packet, not about tolerating a slow server.

**Acceptance criteria.**

1. With an account deliberately unregistered, placing a call produces the decided behaviour
   within the stated bound — not a 32-second silence.
2. The user-visible message names the actual problem. `CallMessages` has one sentence per
   `SipError` case with an exhaustive `when` and no `else`, so a new case forces a decision
   about what the user is told.
3. A JVM test for both paths — registered dials immediately, unregistered does the decided
   thing — with no device.

---

## 4. What is out of scope

Stated so the diff does not widen:

- **Lyra.** ADR-008, gate not passed. `PJMEDIA_HAS_LYRA_CODEC` stays `0`. Note that the
  reported item 1 was titled "Lyra" but describes an ordinary audio call — and the trace
  settles it: the account had `audio=[lyra]` configured, nothing in this build can negotiate
  Lyra, and that configuration is precisely what caused the disconnect. Item 1 is a codec
  defect, not a Lyra feature request.
- **OpenH264.** A licensing decision, not an engineering one.
- **The native build.** It works: three ABIs, in CI, from vendored source. Do not reopen it.
- **Installing a TCP listener or `mod_opus` on the server.** Both are worth doing and neither
  is a change in this repository. The TCP listener is a **blocker for video** (§3.2) and must
  be raised with whoever operates the server rather than quietly worked around.

---

## 5. Delivery

Do not attempt this in one pass. After each phase: push, let CI run, and report what works,
what does not, and what was assumed.

1. **Instrument, then reproduce.** The trace in §2 was captured this way and every diagnosis
   above rests on it. Change nothing during this phase.
2. **Item 1's codec fix.** It is the root cause of the answered-call disconnect *and* of the
   `m=audio 0` outgoing offers, so it comes first and may change what items 2 and 3 look like.
3. **Item 2's size budget**, with the arithmetic.
4. **Items 5 and 7** — both small, both testable on the JVM, neither needing a device.
5. **Item 3's audit corrections.**
6. **Item 6** with real LeakCanary output and an instance count.
7. **Item 4 as the six budgets**, measured on hardware.

---

## 6. Definition of done — each item binary

1. Every fix carries a **trace or a measurement**, not a description of the change.
2. An APK-to-APK **audio** call is answered and stays up 60 seconds, with audio heard both
   ways, by a person.
3. An APK-to-APK **video** call connects with picture both ways, by a person — or the report
   states the byte arithmetic showing why it cannot yet, and names what has to change.
4. Every codec in the final declared set has a recorded on-device round trip (N-9).
5. The codec audit prints the registry **unfiltered** and reports a reason it can justify.
6. An unmatchable codec preference cannot disable the endpoint, by test.
7. The dialer keeps a selection across navigation and drops it after a successful call —
   both by test.
8. Placing a call on an unregistered account does the decided thing within a stated bound,
   and the user is told what happened.
9. LeakCanary is clean, or each leak is named with its retaining reference and an instance
   count across twenty call cycles.
10. All six §9 budgets in `docs/dod-sweep.md` carry a measured number and a method.
11. `./gradlew build -PwarningsAsErrors=true` is green, and CI is green. **If CI is red, the
    report says so and shows the log** — §10, Honesty.
12. No document describes behaviour that no longer exists — including this one.
13. Every `DECIDE` above is answered in the document that owns it, or listed as an open
    question with an owner.

---

## 7. How to work

- **Measure before diagnosing.** The previous revision of this file carried two diagnoses
  reached by reading code, and the device disproved both (§2.6). A trace costs minutes; a
  wrong fix costs a day.
- **Probe the server directly.** Half the findings above came from sending SIP `OPTIONS` at
  the reference server from a laptop and bisecting the size at which it stops answering. That
  took four minutes and settled a question that source reading had got wrong twice.
- **Build with `-PwarningsAsErrors=true`.** CI does. A local green build without it means
  nothing.
- **Verify on hardware, not on the emulator.** Emulator media proves nothing about AEC,
  routing or battery.
- **Ground every claim.** Before naming a symbol, flag or codec id, grep it. This codebase
  has already shipped one silent failure from an invented assumption.
- **Report honestly.** If a phase's tests fail, show the output. If something was skipped,
  name it. "Implemented", "registered" and "verified on hardware" are three different claims,
  and only the last one ends an argument.
