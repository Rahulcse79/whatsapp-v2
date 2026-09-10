# Prompt: complete the calling stack — every codec, video, conferencing, transfer, and two bugs already diagnosed

> **Paste this whole file as the opening message.** It replaces the Lyra-only version of this
> document: Lyra is one section of a larger goal, not the goal.
> `docs/master-engineering-prompt.md` governs **how** to work.
> `docs/calling-defects-prompt.md` carries the measured calling baseline.
> `docs/HANDOFF.md` says what the last pass did and what it left.
>
> **Everything cited below was read in the tree on 2026-09-10, at the line given.** Two of the
> defects in §2 were traced to root cause while writing this — start there, they are cheap and
> they are what makes the app unpleasant to use today.

---

## 1. The goal, and what it honestly means

Audio calling, video calling, conferencing and call transfer, working on hardware, with every
codec this deployment can actually use — Lyra included if it can be built.

**Three things make "100% working" a claim that needs qualifying, and a plan that ignores them
fails late:**

- **Video cannot work through the current server.** A video INVITE is ~2700 bytes; the path to
  `192.168.80.145` carries **1472** and drops IP fragments silently, and its **TCP 5060 refuses
  connections** so RFC 3261 §18.1.1's escalation has nowhere to go. This is not a codec problem
  and no trim closes 1200 bytes. It needs **a TCP listener on the server** — someone else's
  change — *or* the direct device-to-device path in §6.3, which has no such limit.
- **No deployed peer accepts Lyra.** The server offers PCMU, PCMA, G.729, G.723.1, AMR, Speex,
  VP8, VP9. Lyra is app↔app only, and only with media pass-through arranged on the server.
- **Nothing here is verified until a call is placed, answered and heard.** The last pass fixed
  the oversized INVITE in code and green tests; no call has been made since. Both a Zebra TC15
  and a Pixel 10a currently have the build installed.

---

## 2. Two bugs, already traced. Fix these first.

### 2.1 Hold cannot be resumed — **root cause proven, the fix is small**

**Symptom.** Tap Hold: the call holds. Tap it again: nothing. The call is stuck held.

**What actually happens.** The re-INVITE *is* sent and the media *does* resume on the wire.
The app's model never learns:

1. `PjsipSipEngine.setHold` (`data/sip/…/PjsipSipEngine.kt:961`) validates the transition and
   calls `callGateway.resumeCall`. **It discards the transition result** — nothing moves the
   FSM optimistically.
2. `RealPjsipCoreGateway.resumeCall` (`:1031-1040`) sends the re-INVITE with
   `PJSUA_CALL_UNHOLD`. Correct, and not the bug.
3. The far end answers, media returns to `PJSUA_CALL_MEDIA_ACTIVE`, and
   `toStackCallState` (`:1524-1526`) maps it to `STREAMS_RUNNING`.
4. `CallStateMapper` (`:87`) sends `STREAMS_RUNNING` to `resumeEventFor(state)`
   (`CallStateMapper.kt:151-155`):
   ```kotlin
   state is CallState.Resuming                              -> ResumeConfirmed
   state is CallState.Held && state.by != HoldParty.LOCAL   -> RemoteResume
   else                                                     -> null
   ```
   The state is `Held(LOCAL)`. It matches neither arm. **It returns `null`.**
5. No event, no transition. The call stays `Held(LOCAL)` for the rest of its life.

**And here is why it was never caught:** `StackCallState.RESUMING` exists
(`StackCallEvent.kt:57`) and is mapped (`CallStateMapper.kt:95` → `resumeStartedEventFor`,
which would move the call to `Resuming` and make step 4 work) — but **nothing in the gateway
ever emits it.** Grep it: three hits, all declarations and consumers, no producer. The state
machine was built for a transition the stack was never taught to report. This is the third
time this repository has shipped that exact shape — `CallLogRepository.changes()` was
implemented and collected by nothing until Task 71, and `NatPolicy` was persisted and read by
nothing until `ed189b7`.

**Fix it at the seam that is missing, not by special-casing the mapper.** The gateway knows it
has sent a resume re-INVITE; that is what `RESUMING` is for. Emitting it makes the existing
FSM path work as designed and leaves `resumeEventFor` honest. Whichever way you go:

- A resume the **far end** must lift (`Held(REMOTE)`, `Held(BOTH)`) must still be refused —
  that rule is deliberate and `PjsipSipEngine.kt:955-960` documents why.
- Add the regression test at the mapper level: `Held(LOCAL)` + `STREAMS_RUNNING` must produce
  a transition to `Connected`. It fails on the parent commit; if it does not, you have not
  reproduced the bug.
- Then hold and resume on the handset, twice each way, and read the state out of the log.

### 2.2 Speaker button — **one verified defect, and one thing you must measure before fixing**

**Symptom as reported.** Enabling the speaker does not switch audio to the loudspeaker; it
starts working only after visiting **Settings → Audio Route → Automatic**, which is already
the default value.

**The verified defect: that Settings control is wired to nothing.**
`PreferredAudioRoute` (`domain/…/model/AppSettings.kt:19,45`) is written by
`SettingsScreen.kt:160` through `AppSettingsRepository.setPreferredAudioRoute` and **read by no
production code at all.** The only collector of `observeSettings()` is
`PjsipSipEngine.kt:412`, which reads `sipTraceEnabled` and nothing else. So selecting
"Automatic" changes no behaviour anywhere — it is decoration, and the same defect class as the
two in §2.1. Either wire it to the route decision or delete the control; a setting that
appears to do something and does nothing is worse than no setting.

**Therefore the reported workaround cannot be what it looks like.** Re-selecting an inert
setting cannot fix routing. Something else about leaving the call screen and coming back is
doing it. **Measure that before changing any code** — this repository has had three diagnoses
reached by reading and disproved by the device:

```bash
adb -s <serial> logcat | grep -iE "CallAudio|AudioRoute|Telecom|SipConnection"
```

Press Speaker, capture. Then do the Settings round trip, capture again, and diff. The answer is
in which of these two paths actually reaches Telecom:

| Path | Where |
|---|---|
| The button | `CallViewModel` → `SipMediaController.setAudioRoute` → `PjsipSipEngine.kt:1035` → `platform.requestAudioRoute` → `TelecomCallRegistry.kt:151` → `SipConnection.kt:185` → Android `Connection.setAudioRoute(mask)` |
| The coordinator | `CallAudioCoordinator.applyRoute` (`:195`) computes `AudioRoutePolicy.routeAfterDeviceChange(currentDevices(), chosenRoute, arrived)` and pushes it through the **same** engine call |

**The two candidates, and they need different fixes.** Either (a) `Connection.setAudioRoute` is
being called and Telecom is ignoring it — a real Android behaviour on **self-managed**
connections, which this app uses (`Capabilities: SelfManaged` in the device log), where routing
can require the connection to be the active audio-focus call; or (b) the coordinator recomputes
a route from a stale `chosenRoute` and overrides the press. `begin()` (`:151-179`) already
carries a documented fix for one version of (b), so read that comment before assuming it is the
same one again.

Do not fix both speculatively. Find which, fix that, and say in the commit message what the log
showed.

---

## 3. The PJSIP build — what is on, what is off, what to turn on

`pjsip/config/pj/config_site.h` is the **single source of truth** (N-8). Its entire current
content, in flags:

| Flag | Value | Meaning |
|---|---|---|
| `PJMEDIA_HAS_VIDEO` | **1** | video compiled in |
| `PJMEDIA_HAS_VPX_CODEC` | **1** | VP8/VP9 |
| `PJMEDIA_HAS_OPUS_CODEC` | **1** | Opus |
| `PJSIP_HAS_TLS_TRANSPORT` | **1** | TLS |
| `PJMEDIA_HAS_OPENH264_CODEC` | **0** | H.264 comes from `MediaCodec` instead |
| `PJMEDIA_HAS_LYRA_CODEC` | **0** | §5 |

Everything else is pjproject's Android default. **"Turn on all the flags" is not a plan** —
each one that is off is off for a reason, and each one you turn on drags in a library that must
be vendored, pinned, hashed and documented (N-2, N-3, N-7, N-10). Turn on what the deployment
can use, with a reason per flag, and leave the rest.

Also note `PJSIP_MAX_PKT_LEN` is **4000** (`third_party/pjproject/pjsip/include/pjsip/sip_config.h:372`,
not overridden here), which is what lets a ~2700-byte video INVITE be *received* — relevant to
§6.3.

---

## 4. Codecs — what registers, what the server takes, what is missing

**Registered by the running library**, read off the handset on 2026-09-10:
`opus/48000/2`, `G722/16000`, PCMU, PCMA, GSM, iLBC, AMR-WB, AMR, speex ×3, L16 ×2.
Video: `VP8/102`, `H264/99`, `VP8/103`, `VP9/106`.

**Offered by the deployed FreeSWITCH:** PCMU, PCMA, **G.729**, **G.723.1**, AMR, Speex, VP8, VP9.

**What the app offers today** (`CodecPreferences.DEFAULT`): Opus, G.722, PCMU, PCMA / VP8, H.264.

Three gaps, in the order they are worth money:

1. **`mod_opus` is not installed on the server** — configured in `modules.conf.xml`, `.so`
   absent. Installing it **halves the bandwidth of every call with no client change**, because
   the APK already registers and offers Opus. Today every call falls to **PCMU at ~160 kbps**.
   This is the single highest-value item in this document and it is not a code change. Raise it.
2. **G.729 is offered by the server and absent from this build.** It would give a **48 kbps**
   call where PCMU gives 160. It needs `bcg729` vendored, and it carries its own patent history
   — `docs/architecture.md` records it as an open **DECIDE**, not an oversight. Decide it.
3. **G.723.1** — same shape, lower value.

**When you add a codec, check the offer size.** `SdpBudget`: the audio INVITE is now 1328 bytes
against a 1472-byte ceiling — **144 bytes of headroom**. Every codec adds an `a=rtpmap:` line
and possibly an `fmtp`. Measure on the wire, do not estimate: the previous pass's arithmetic was
wrong twice before it was measured.

---

## 5. Lyra

### 5.1 pjproject's half is already done. You are not writing codec code.

| What | Where |
|---|---|
| Codec implementation | `third_party/pjproject/pjmedia/src/pjmedia-codec/lyra.cpp` |
| Public API | `third_party/pjproject/pjmedia/include/pjmedia-codec/lyra.h` |
| Registration, automatic | `pjmedia/src/pjmedia-codec/audio_codecs.c:145-150` — `pjmedia_codec_lyra_init(endpt)` inside `#if PJMEDIA_HAS_LYRA_CODEC` |
| Build integration | `third_party/pjproject/aconfigure.ac:2583-2650` — `--with-lyra=DIR` |

`pjsua2` needs **no change**. The work is producing a `liblyra` that `configure-android` can
link, and giving the codec its model files at runtime.

### 5.2 `config_site.h` beats `configure` — the trap that wastes a day

`aconfigure.ac:2642` does `AC_DEFINE(PJMEDIA_HAS_LYRA_CODEC,1)` when the link test passes. That
define **loses**: `pjmedia-codec/config.h:574-576` guards the flag with `#ifndef`, and
`pj/config.h:317` includes `<pj/config_site.h>` first, where this repo says `0`. So a
successful `--with-lyra` build registers **no codec at all**, with configure cheerfully
reporting "Using lyra prefix". **Both changes are required; either alone is a silent no-op.**

### 5.3 The model path baked at build time is a path on your build host

`aconfigure.ac:2641` sets `ac_lyra_model_path="$LYRA_PREFIX/model_coeffs"`; the default is the
*relative* string `"model_coeffs"` (`pjmedia-codec/config.h:595`). Neither exists on a device.
Four files are needed in one directory (`lyra.h:61-67`): `lyra_config.binarypb`,
`lyragan.tflite`, `quantizer.tflite`, `soundstream_encoder.tflite` — ~3.5 MB, shipped in
`google/lyra`, and **trained weights: data, not code**, the one place N-1's "no prebuilt
binaries" does not reach (said out loud in ADR-008 rather than assumed).

Ship them as assets, copy to `filesDir` on first run, then:

```c
pjmedia_codec_lyra_config cfg;
pjmedia_codec_lyra_get_config(&cfg);   /* lyra.h:98  */
cfg.model_path = pj_str("/data/user/0/com.whatsappv2/files/lyra");
cfg.bit_rate   = 3200;                 /* 3200 | 6000 | 9200 */
pjmedia_codec_lyra_set_config(&cfg);   /* lyra.h:108 */
```

`lyra.cpp:200-203` copies the path into a fixed buffer at init, so **set it after `libInit()`
and before the first call**. An invalid directory fails codec *creation*, not init — a call
that will not negotiate rather than a startup error. **First check whether SWIG wraps
`pjmedia_codec_lyra_set_config` at all**; if not, this needs a JNI shim or a pjproject patch
(patch, never an edit — rule 12), and that changes the shape of the work.

### 5.4 It registers as `lyra/16000/1`, and only that

`pjmedia-codec/config.h:604-635`: `HAS_8KHZ 0`, `HAS_16KHZ 1`, `HAS_32KHZ 0`, `HAS_48KHZ 0`;
`lyra.cpp:167-181` enables exactly those. The id is lowercase — `AudioCodec.LYRA.payloadName`
is `"lyra"` for precisely this reason, because an uppercase `LYRA` matched `lyra/16000/1`
**never, and silently**, and that shipped. Note also that the configure link test
(`aconfigure.ac:2638`) builds a decoder at **8000 Hz**, a rate that is disabled by default: a
passing link test says nothing about which rates register.

### 5.5 The dependency closure — this is the whole cost

`docs/lyra-criterion-1.md` has the full map. In short: Lyra itself is small (20 `.cc`, 34
`.h`), absl `20211102.0` / glog / gulrak filesystem `v1.3.6` all have CMake — and then two
rocks. **TensorFlow Lite pinned at v2.11.0**, ~1.35 GB, reached through
`lyra/tflite_model_wrapper.cc` with the XNNPACK delegate included directly so it is not
optional; and **`com_google_audio_dsp`**, which has **no CMake build at all** — 12 call sites
across 6 targets, all hand-written. Two of Lyra's own dependencies also **float**
(`com_google_glog` on `branch = "master"`, `gflags` on a fork branch); pinning them is the
first act, because N-11 is not measurable until they are commits.

No Bazel in this build: N-2 forbids build-time downloads and Bazel 5.3.2 is end-of-life.

---

## 6. Video, conferencing, transfer

### 6.1 Conferencing

ADR-003 decided **FreeSWITCH `mod_conference`, dial-in MCU** — the app does not mix. The client
side exists: `SipConferenceController`, `JoinConferenceUseCase`, and PJSIP's own conference
bridge is *local* mixing and is not what makes a multi-party call. What is owed is a
verification on hardware against a real conference extension, and confirmation that
`mod_conference` is loaded on `192.168.80.145`.

### 6.2 Transfer

`TransferCallUseCase` implements blind and attended transfer with tests, and
`TransferEventMapper` maps the REFER/NOTIFY side. Owed: a hardware run of each, including the
failure path where the transfer target rejects.

### 6.3 Video, and the direct device-to-device path

Through the server, video is blocked by §1's MTU wall until someone opens TCP 5060.

**Device to device it is not blocked at all**, and this is worth understanding before writing
anything off. Both APKs create their own UDP, TCP and TLS listeners at startup — the device log
says so: `start: transports created (UDP, TCP, TLS)`. Over TCP there is no datagram limit, and
PJSIP escalates any request over 1300 bytes to TCP by itself; peer-to-peer the escalation
succeeds because the other end has a listener. Both ends being this app, the negotiated codecs
would be **Opus and VP8** rather than PCMU.

Two app-level gaps stand in the way, and neither is about transports:

1. **`RealPjsipCoreGateway.kt:394-396`** creates UDP and TCP with a bare `TransportConfig()`,
   so they bind **ephemeral** ports — the callee has no predictable address to dial.
2. **`PlaceCallUseCase.kt:112`** gates every call on `ensureRegistered(account)`. With no
   registrar the call fails with `NotRegistered` and no INVITE is ever sent.

Everything else already works: `DialledTarget.resolve` (`DialledTarget.kt:31`) passes a full URI
through untouched, `SipUri` preserves `;transport=tcp`, and `ed189b7` made PJSIP select the
transport from that URI parameter. **If you implement this, make it an explicit per-account
opt-in** — binding 5060 and answering INVITEs from anyone on the LAN is a real change in
posture, not a convenience.

---

## 7. Order of work, with a gate after each

| # | Phase | Gate |
|---|---|---|
| 0 | **§2.1 hold, §2.2 speaker** | Both demonstrated fixed on a handset, with the log that shows it |
| 1 | **Place, answer and hear one audio call** | The measurement §3.4 of the handoff still owes. Everything after this assumes it |
| 2 | **Raise with the server operator:** `mod_opus`, a TCP 5060 listener, `mod_conference` | Their answers decide phases 4 and 6 |
| 3 | **Conferencing and transfer on hardware** | Each verified, including one failure path |
| 4 | **Codec gaps** — G.729 decision, Opus once the server has it | Offer re-measured against `SdpBudget` |
| 5 | **Video** — server TCP, or the §6.3 direct path | A video call seen and heard |
| 6 | **Lyra** — §5, and `docs/lyra-criterion-1.md` phase 0 first | TFLite v2.11.0 for `arm64-v8a` under NDK r27c. **If it fails that is ADR-008 Exit B: write it up, ship no code.** That is a successful outcome, not a failure to report |

Phases 0-3 need no server change and no new dependency. **Do not start phase 6 before phase 1.**

---

## 8. Definition of done — each item binary

1. Hold, then resume, works on a handset, twice, and a mapper-level regression test fails on the
   parent commit.
2. The speaker button routes audio to the loudspeaker immediately, and the Settings → Audio
   Route control either does something or no longer exists.
3. An audio call is placed, answered and **heard** between two devices.
4. A transfer (blind and attended) and a conference join are each performed on hardware.
5. Every codec added is registered on the device (`PjsipCodecAudit` logs the registry verbatim)
   **and** the resulting INVITE size is measured on the wire against 1472.
6. Video: either a call carried through the server after it accepts TCP, or a device-to-device
   call over TCP — seen and heard, with the codec confirmed from the audit log.
7. Lyra: `lyra/16000/1` registered at non-zero priority with its model files on the device and a
   call carried by it — **or** ADR-008 closed at Exit B with the evidence.
8. `:domain:test`, `:data:sip:test`, `:test:arch:test` and `detekt` green under
   `-PwarningsAsErrors=true`; `./build.sh` still produces an installable APK; vendoring checks
   (`verify-pins.sh`, `verify-vendored.sh`) pass for anything new in `third_party/`.

---

## 9. How to work

- **Measure before diagnosing.** Three diagnoses in this project were reached by reading code
  and the device disproved all three. §2.2 is explicitly one of these.
- **Grep every symbol, flag and codec id before naming it.** Every citation here was read at
  the line given; hold that standard.
- `:data:sip` compiles locally: `-x :pjsip:api:generatePjsua2Bindings` — see `docs/HANDOFF.md`
  §4. `./build.sh --reuse-native` produces an APK without the SWIG toolchain.
- Build with `-PwarningsAsErrors=true`; CI does, and a local green build without it means
  nothing.
- **Watch for the "wired to nothing" shape.** This repository has shipped it four times now —
  `CallLogRepository.changes()`, `NatPolicy`, `sipTraceEnabled`, and `PreferredAudioRoute` in
  §2.2. When you add a setting, add the thing that reads it in the same commit, and a test that
  fails when nothing does.
- **Report honestly.** "Compiled", "registered" and "heard on a call" are three different
  claims, and only the third one ends an argument.
