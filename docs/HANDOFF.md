# Handoff — paste this whole file as the opening message to the next agent

> **Repo:** `whatsapp-v2` · **Branch:** `docs/native-mandate-design` · **Written 2026-09-10.**
> `docs/master-engineering-prompt.md` governs *how* to work. `docs/calling-defects-prompt.md`
> is the corrected spec for the seven calling defects — **read it before anything else**, it
> carries the measured baseline. This file says what is done, what is half-done, what is
> wrong, and what to do next.
>
> **Read §0e, then §0d, then §0c, then §0b, then §0a — newest first.** §0e is the full
> end-to-end sweep; §0c replaces the deleted `docs/HANDOFF-NEXT.txt`. The evening pass of 2026-09-10
> built the APK, put it on a Zebra TC15, and placed, answered and held calls against two
> FreeSWITCH servers. §3.4's "nothing has been run on a handset" is no longer true. Where
> §0a and a later section disagree, §0a wins.

---

## 0a. Evening pass, 2026-09-10 — measured on hardware, and what it found

Device: Zebra TC15, serial `24110524701351`, `adb` at `~/Downloads/platform-tools/adb`.
Servers: `192.168.80.145` (FreeSWITCH behind an SBC, "iriscloud", extension 7003) and this
Mac's own FreeSWITCH 1.10.11 at `192.168.2.196` (extension 1001, Zoiper at 1000). Every
claim below has a log line behind it; the commit messages carry the lines.

### What was fixed, and how far each is verified

| Commit | Defect | Verified |
|---|---|---|
| `88b622c` | Every far-end hangup recorded as `SERVER_ERROR` — a BYE's 200 fell through to the error taxonomy | JVM test; not seen on hardware (no far-end hangup occurred in this pass) |
| `b5a9979` | **Resume never resumed.** The prompt's diagnosis was wrong: the re-INVITE carried `a=sendonly` again plus `m=video`/`m=text`, was 1852 bytes, escalated to TCP, was refused, fell back to a fragmented UDP datagram and got no answer at all. Root cause: `Call::reinvite` reads `opt.flag`, not `CallOpParam.options`, and `CallOpParam(true)` replaced the call's media counts with `1/1/1` | **On hardware, twice each way, on both servers** — 1250 B / 1187 B re-INVITEs, `sendonly`/`sendrecv` alternating, each answered 200 in ≤ 380 ms, Telecom in step |
| `b5a9979` | `RESUMING` had no producer; a refused resume was silent | JVM tests (`PendingResumeTest`, engine, mapper, FSM); the refusal path not seen on hardware |
| `d3310dd` | **Speaker pressed while ringing did nothing** — button disabled before media, FSM has no controls to hold it, coordinator re-asserted the earpiece at the 183. `PreferredAudioRoute` wired to nothing | **On hardware**: a press between 180 and the answer produced `USER_SWITCH_SPEAKER → ActiveSpeakerRoute → SPEAKER_ON`; the Settings route is JVM-tested only |
| `a28a554` | **Native crash**: `SIGABRT` in `FinalizerDaemon`, `Account::~Account → pjsua_acc_del2` on a reused slot after logout/login | Trace captured at 22:14:57; **on hardware** after the fix: two logout → forced GC → login cycles on one pid, `Deleting account 0` each time, slot 0 reused, no crash |
| `5572bcb` | An SRTP/codec/NAT edit never reached the running stack — `affectsRegistration` skipped it | JVM test that fails on the parent; **on hardware**: save → `Expires: 0` → `Deleting account 0` → `Adding account` → REGISTER → 200, row updated |
| `3079bf6` | Every logout and policy edit waited 5 s: the account was shut down 45 ms before the registrar's 200 to the un-REGISTER, so the CLEARED event never came | **On hardware**: 200 at :01.966, "Releasing account: unregistration answered 200" at :01.972 — 160 ms, no warning |

### The one that needs a decision — 2(e), outgoing calls fail 100%

**Both FreeSWITCH servers refuse the app's default offer.** `SrtpPolicy.OPTIONAL` makes
PJSIP put `a=crypto` lines on an `RTP/AVP` m-line; FreeSWITCH 1.10.11's own log at the moment
Zoiper answered:

```
[ERR] switch_core_media.c:5389 a=crypto in RTP/AVP, refer to rfc3711
[NOTICE] Hangup sofia/internal/1001@192.168.2.196 [CS_EXECUTE] [INCOMPATIBLE_DESTINATION]
```

→ `488 Not Acceptable Here`, `Reason: Q.850;cause=88`. With the account set to `DISABLED`
the same INVITE (1148 B) connects on the first try. So **every new account, on its default
policy, cannot place a call to a FreeSWITCH**. The options, none taken yet:

1. Default `SrtpPolicy` to `DISABLED` (app default and `SipAccountDraft`). Honest for these
   deployments — neither server offers SRTP — and no bytes.
2. Keep `OPTIONAL` but set `AccountMediaConfig.srtpOptionalDupOffer = true`: PJSIP then
   offers an `RTP/SAVP` m-line *and* an `RTP/AVP` one, the RFC 3711/4568 form FreeSWITCH
   accepts. Costs ~330 bytes — the audio INVITE on the `80.145` UDP path is 1148 B against
   1472, so it fits there only without ICE and only just; measure before shipping.
3. `MANDATORY` → `RTP/SAVP` only. Works only where the server has SRTP enabled; neither
   tested one does.

Account 7003 on `80.145` and account 1001 on `2.196` are both **left at `DISABLED`**,
deliberately, so calls work.

### Also measured — and, in the small hours of 2026-09-11, acted on

- ~~The app does not re-register on process start.~~ **Fixed, `12072b21`, verified**: login
  intent persisted per account (Room v3, `registration_wanted`), restored at start by
  `RestoreRegistrationsUseCase`; after `am force-stop` + launch the REGISTER went out 1.3 s
  after "Application started" with nothing pressed. A logged-out account stays out.
- ~~`RemoteAnswered rejected from Connected` on every answered call.~~ **Fixed, `9eec970d`**:
  PJSIP raises CONNECTING then CONFIRMED and both mapped to CONNECTED; a repeat on an
  established call is now no transition.
- ~~The SRTP default.~~ **Decided and fixed, `0a9a1f52` + `12072b21`**: new accounts and
  the app default are `DISABLED`; rows saved on `OPTIONAL` are rewritten by the v3
  migration with `MIGRATION_1_2`'s reasoning; `MANDATORY` untouched; the Settings copy
  says why. The `NDLB-allow-crypto-in-avp` server setting and `srtpOptionalDupOffer` are
  recorded on the enum as the two ways `OPTIONAL` could work.
- ~~LeakCanary: `SipConnectionService` via `ConnectionService$1.this$0`.~~ Framework-held
  binder, 2.7 kB; ignored by exact field pattern in a debug-only provider, `ddbf6825`.
- The account editor now says the codec order is the tap order and shows the list as it
  will be offered (`ddbf6825`).
- CI caches the Lyra prefix by the same content hash the build stamps it with
  (`91c24102`) — untested until the branch is pushed.
- A hardware Back press closes `CallActivity` mid-call; the ongoing-call notification reopens
  it. Not a defect, but it cost one test run.
- `192.168.2.196` **accepts TCP 5060** (the escalated 1581-byte INVITE went over TCP and was
  answered), so §8's video wall does not exist on this server — the direct path in the
  prompt's §6.3 and a video call through this FreeSWITCH are both open to try.

### Lyra — ADR-008 closed at Exit A, late on 2026-09-10

Commits `8c7268c` (vendor), `6de7ab3` (build), `c9cd32f` (runtime), `fac52e3` (ADR).
`docs/native-dependencies.md` §1.0 and §5 are the record. In one paragraph:

TensorFlow Lite v2.11.0 + XNNPACK built for arm64-v8a under NDK r27c with zero errors
(the risk the gate named did not exist); the whole closure — 19 vendored trees, one
patch replacing protobuf for a two-byte file — builds offline through
`pjsip/lyra/CMakeLists.txt` into one `liblyra.a`; pjproject's own `--with-lyra` link test
passes against the prefix; and a test binary on the TC15 encoded and decoded a 16 kHz
tone at exactly 3200 bps with the real model files. `config_site.h` says
`PJMEDIA_HAS_LYRA_CODEC 1`; the app ships the weights as assets and configures the codec at
start; the audit reports `lyra` registered-and-stranded, or `ModelFilesUnusable`.

**Verified on the TC15 after midnight (commit `f358634d`):** `./build.sh --install`
end to end — SWIG's Java typemaps supplied user-locally from the swig 4.4.1 source
(`SWIG_LIB=~/.local/share/swig/4.4.1`; MacPorts ships without them and
`sudo port install swig-java` is the proper fix) — then on the handset: codec audit
**`lyra/16000/1@254`**, model files in `files/lyra/`, and an INVITE to the local
FreeSWITCH with `a=rtpmap:96 lyra/16000` first (1246 bytes) answered on PCMU and
connected. Two build defects found on the way and fixed in that commit: pjproject was
being compiled for android-23 while everything else is 26, and the wrapper shipped 80 MB
of DWARF (APK 146 MB → 70 MB).

**What is still owed:**

1. **A call carried by Lyra.** No server offers it; it is app-to-app only. Two installs
   of this app (the TC15 and the Pixel 10a — the Pixel was not attached tonight) on the
   local FreeSWITCH with media bypassed (`bypass_media=true` in the dialplan for those
   extensions), Lyra first in both codec lists, and the SDP answer showing `lyra/16000`.
   Then the audit log and the wire.
2. armeabi-v7a and x86_64 have not been built at all — only arm64. The closure's CMake is
   ABI-agnostic on paper; CI is where that claim is tested, at ~25 min per ABI until a
   cache lands.
3. Account 1001 is left at `DISABLED` encryption with Lyra first in its codec list.


1. Decide the SRTP default (above), then re-measure the `80.145` INVITE size against 1472.
2. Prompt phase 1 is done on `2.196` — placed, answered, **heard**: not verified; the
   handset was driven by adb and nobody listened. Do that with a person at each end.
3. ~~Then phases 3–5: transfer, conference, and video through `2.196` over TCP.~~ Done in
   the morning pass — see §0b.

---

## 0b. Morning pass, 2026-09-11 — video, transfer, conference, all on hardware

Setup changed: the Mac and the TC15 are on the office Wi-Fi (`192.168.0.101` / `.108`),
the local FreeSWITCH binds `192.168.0.101` (its `lan-ip.sh` follows en0), and the handset
carries **two** accounts — `7000@192.168.80.145` (office server) and `localfs` =
`1001@192.168.0.101`, the default. Every test below ran on `localfs` against
`dialplan/default/03_whatsapp_v2_test_apps.xml` (outside the repo): 9196 echo, 9197
bridge-to-loopback, 9198 tone, 9199 unrouted, 3000 `mod_conference`. Rahul's 7001
(Zoiper) and 7002 (this APK on a second phone) were not registered on `80.145` during the
pass — every call to them got `480`. So **APK-to-APK is still owed**, Lyra included.

### Video — six defects, one commit (`2763f778`)

A video call to the echo placed, negotiated VP8 and then sent nothing, drew nothing, and
lay on its side. Each of these was measured before the next was looked for:

| Defect | Cause | Fix |
|---|---|---|
| "Camera released" 12 ms after the 200 OK, `set video stream, op=6` | `callStateOf` mapped PJSIP's CONNECTING to CONNECTED before the media update; the engine took it as the answer with `videoActive=false` and stored an established snapshot with video off | CONNECTING is not published; negotiated-video adoption is folded into the state before the store. New engine test records every snapshot and fails on the previous code |
| `Setting up TX..` then nothing — no capture device ever opened | `autoTransmitOutgoing` is off by design and the one START_TRANSMIT went out before the INVITE, when there was no stream | The gateway remembers `cameraWanted` and re-issues START_TRANSMIT when a video stream comes up — also on every re-INVITE, where pjsua drops the capture window |
| The preview view was a hole in the remote picture | A sendrecv stream has one window and it shows the far end; the local picture is the capture device's *preview* window, hidden, renderer never started | `LocalPreview` starts `pjsua_vid_preview` on it with the screen's surface; stopped before STOP_TRANSMIT, camera switch, and release (it holds a window reference) |
| Half the calls: black far end, `and_vid_mediacodec: Decoder failed to get input Buffer` | libvpx and MediaCodec both register `VP8`; both got priority 254 and pjmedia's unstable selection sort swapped them on every account save | `CodecPriorities` gives every enabled codec a distinct number (TOP is 254 — pjmedia demotes 255), gateway puts `VP8/102` (libvpx) ahead of `VP8/103` (MediaCodec). Audit now reads `VP8/102@254, VP8/103@253, H264/99@252` on every save |
| One lost packet = macroblock smear for up to 60 s | `CallSetting()` zeroes `reqKeyframeMethod`; no `a=rtcp-fb` in the SDP; libvpx keyframe interval is 60 s | PLI + SIP INFO requested on every call; FreeSWITCH answers `a=rtcp-fb:102 nack pli` |
| Both pictures rotated 90° | The camera captures landscape; nothing told the stack the screen was portrait | `VideoSurfaceController.setDisplayRotation` from `CallVideo` → `setCaptureOrient` for every camera, pjsua2's own sample mapping. `android_dev.c` logs "orientation set to 4" |

Also: "Flip" cycled through PJSIP's colour-bar generator (it reports itself as a capture
device) — cameras are now the `Android` driver's devices only.

**Verified** (screenshots in the session, log lines in the commit): camera acquired once
and never flapped; remote and preview both drawn and upright; flip → back camera → front;
video off (`REMOVE` + STOP_TRANSMIT, camera released) → on (`ADD` + START_TRANSMIT);
hold → resume re-issues START_TRANSMIT and restarts the preview. RX 1088×612 VP8 at
~1 Mbit with 0.4 % Wi-Fi loss and the picture clean at 32 s.

### Transfer and conference (`1dba318f`)

- **Blind**: 9197 → 9198. REFER → 202 → NOTIFY (`terminated;noresource`, sipfrag 200) →
  the app ends the leg; FreeSWITCH's loopback leg moves to 9198. First run exposed that
  the gateway read pjsua's `100 Accepted` as a *failure* (anything but 200 was ERROR), so
  the call went back to Connected before the transferee was tried. Mapping is now
  `TransferEventMapper.stateOf`, tested with pjsua's real sequence.
- **Failure path**: 9197 → 9199 (unrouted). **Cannot be produced on FreeSWITCH** — it
  answers every blind REFER with 200 and runs the target through the dialplan itself;
  the loopback leg just dies. Needs a phone as the far end that refuses (486). JVM-covered.
- **Attended**: 9197 on hold, consult 9198, "Complete transfer" → REFER with `Replaces`
  → 202 → 200 → both legs released, loopback leg at 9198. Exposed a second defect: every
  attended transfer asked for the same hold twice (the transfer, then Telecom's
  `holdActiveCallForNewCall` 200 ms later) and pjsua refused the second with
  `PJ_EINVALIDOP`, logged as `pauseCall failed`. The engine now holds once per re-INVITE.
- **Conference**: dialled 3000 as an ordinary call; `conference list` shows the member
  `hear|speak|talking|floor`; DTMF `0` over RFC 4733 muted it (`hear|floor`). Note:
  `JoinConferenceUseCase` has **no UI entry point** — nothing in `feature/` calls it — and
  the real gateway never emits a roster (no conference-info subscription; the flow exists
  for the contract). A "conference" is exactly a call to the bridge, which is ADR-003's
  design; the roster is future work, and so is a button.

### APK ↔ APK, early afternoon — two TC15s on the local FreeSWITCH

Second handset: TC15 `24143524701316` at `192.168.0.117`, accounts `7004@192.168.80.145`
and `1002@192.168.0.101`. Only one of the two is ever on USB (one cable), so each test
drives one phone by adb and Rahul answers the other.

- **Audio 1002 → 1001**: connected, PCMU through FreeSWITCH, 3.4K packets each way, 0 %
  loss. Two defects found on the *callee* and fixed in `7ca79296`: the morning's
  CONNECTING skip had broken every answered incoming call (the callee negotiates before
  CONNECTING, the caller after — the skip is now UAC-only), and an inbound call with the
  app already on screen showed nothing (`IncomingCallPresenter` starts `CallActivity`
  when one of our activities is resumed).
- **Incoming video** (FreeSWITCH `originate user/1002 &echo`, default codec prefs so the
  INVITE carries `m=video`): "Answer with video" → both pictures, upright, 1280×720 VP8
  at 1 Mbit, 0 % loss.
- **Lyra, 1001 → 1002 — a call carried by Lyra.** Both `localfs` accounts set to
  `lyra, PCMU, opus` in the editor (audit `lyra/16000/1@254`); FreeSWITCH given
  `dialplan/default/00_whatsapp_v2_bypass.xml` (outside the repo): `bypass_media=true`
  for `1001 ↔ 1002`, so the SDP crosses untouched. Offer `m=audio … 96 0 97 9 8`,
  `a=rtpmap:96 lyra/16000`; answer from `192.168.0.117` `m=audio 4002 RTP/AVP 96 120` —
  **Lyra, media direct phone-to-phone**, FreeSWITCH legs in `CS_HIBERNATE`.
  `lyra.cpp: Opening codec, model_path=…/files/lyra, enc_bit_rate=3200` → `audio
  updated, stream #0: lyra (sendrecv)`. 66 s; RX and TX `pt=96` 3.3K packets, 26.8 KB,
  **3.1 kbit/s**, 1 packet lost. ADR-008's last owed item is closed on the wire; whether
  it is *intelligible* is Rahul's call — nobody but him has heard it.

### What is still owed after this pass

> Superseded by **§0c "Still owed"**, which is the current list. Kept as the record of
> where this pass left things.

1. **On `80.145`** (7000 ↔ 7004, Zoiper 7001): the same calls through the office server,
   and the transfer failure path with a phone that refuses. The office extensions
   answered `480` all morning.
2. A preview that follows a *rotation* mid-call is untested (the activity recreates and
   re-reports; `LaunchedEffect(configuration)` covers a manifest that does not).
3. `sudo port install swig-java` so `./build.sh` runs without `SWIG_LIB`.

---

## 0e. 2026-09-11, evening — the full end-to-end sweep, and the two defects it found

**The newest section. Read it before §0d.** Every feature driven by `adb` against the local
FreeSWITCH on one TC15 (`24110524701351`, `localfs` = 1001, office = **7001** — not 7000, the
account was renamed), with a check after *every* step for: process death, `Fatal signal` /
`FATAL EXCEPTION` / `Assert failed`, `ANR in com.whatsappv2`, `Skipped N frames` (main-thread
stall), LeakCanary application leaks, and app-level `E` log lines.

### What was exercised, and passed

| Area | Evidence |
|---|---|
| Navigation, settings toggles, account editor ×5, screen in/out ×5 | `Activities: 1`, `Views: 13`, PSS flat |
| Outgoing audio to 9196 | connected; mute, speaker, hold, resume all return to rest |
| DTMF | FreeSWITCH logged `RTP RECV DTMF 1/2/3` — on the wire, not just tapped |
| Video mid-call | on → flip → flip back → off → on, no crash (the sweep's defect 1 stays fixed) |
| **Rotation mid-call** (was untested, §0b item 2) | landscape, portrait, then 4 rapid flips: call survived at 3:26, `android_dev.c: orientation set to 4`, still `ACTIVE` on the server |
| Incoming: app in front / backgrounded / **screen off** | heads-up with DECLINE/ANSWER when backgrounded; screen-off woke to the full-screen answer UI |
| 5 × incoming call cycles | no crash; LeakCanary: "All retained objects have been garbage collected" |
| Call waiting | "Tone is calling / You are on a call with Echo"; hold-and-answer re-points the screen; swap both ways; **ending the active leg follows the held one** (§0c defect 4 both halves) |
| Blind transfer | `REFER … Refer-To: sip:9198@…` → `202` → `NOTIFY terminated;reason=noresource` sipfrag `200 OK`; leg parked at 9198 |
| Attended transfer | `REFER sip:9197@… Refer-To: <sip:9198@…?Require=replaces&Replaces=…>` → `202`; both legs released, the two parties left bridged |
| Conference 3000 | member `hear|speak|talking|floor`; second member added; DTMF `0` → `hear|floor` |
| Recording | start, indicator, stop, sealed `.rec`, no plaintext left (see §0d) |
| Network loss and recovery | see below |
| Logout / login | server showed **0** registrations for 1001 after logout; Registered again after |
| Process restart | force-stop → relaunch → both accounts re-register |

**Final tally after the fixes:** 0 crashes, 0 ANRs, 0 main-thread stalls, 0 application
leaks, `Activities: 1`. (Bounded by the logcat ring buffer, which rotated during the run.)

### Defect 1 — the known-leak matcher had stopped matching (`c5186f8e`)

LeakCanary reported **1 APPLICATION LEAKS** after calls:
`android.telecom.ConnectionService$5.this$0` → `SipConnectionService`, GC root *global
variable in native code*, 2.7 kB. That retention was already understood and already written
down in `LeakCanaryConfigProvider` — but the matcher named `ConnectionService$1`, and
anonymous-class numbering belongs to the platform build. LeakCanary's own history showed the
whole defect in two rows: `ConnectionService$1` last leaked 21 hours ago, `ConnectionService$5`
last leaked 5 minutes ago, NEW.

Worse than having no matcher: a leak report that is always wrong is one nobody reads, and the
next real leak arrives in the same sentence. The pattern now covers the anonymous range, and
uses `LibraryLeakReferenceMatcher` so the retention is still *printed* — under LIBRARY LEAKS,
with its explanation — instead of being deleted from the output. Later in the same sweep the
**`$1` variant appeared too**, on the same handset, and was classified correctly: proof that
enumerating beats pinning.

### Defect 2 — transfer and recording acted on the wrong call (`9768b282`)

Two calls, end the active one, the screen correctly follows the held one — then Transfer says
*"That call has already ended. The call is still connected."* about a call showing 2:43, one
channel on FreeSWITCH, no BYE. The message contradicts itself because each half is about a
different call.

`CallRoute` closed over the route's `callId` for transfer-blind, start-consultation,
confirm-recording and stop-recording, reasoning that "the screen is looking at exactly one
call". True at any instant — but `followRemainingCall()`, `swapTo()` and
`respondToSecondCall()` all re-point `watched`, which is what the screen renders from. The fix
removes the parameter rather than passing a better value: both controllers take
`currentCall: () -> CallId?` and read it when they act, so there is no way to hand them the
wrong call. Recording was the worse half — a stale `stop()` succeeds quietly on an unknown
call, leaving the real recording running and capturing a leg nobody consented to.

### Three things that looked like defects and were not — recorded so nobody re-chases them

1. **27 preview starts against 7 stops, 31 "Creating video window" against 0 destroyed.**
   Not a leak: pjsua answers `Window already exists for cap_dev=-1, returning wid=1` every
   time and `Video ports connection 3->4 already exists`. 31 log lines, one window.
   `LocalPreview`'s idempotent re-attach works as documented.
2. **"Incoming call while backgrounded shows no UI."** A measurement artefact —
   `uiautomator dump` captures only the focused window, and a heads-up is not focused. A
   screenshot showed the heads-up with DECLINE / ANSWER exactly as expected.
3. **"1001 stuck on Reconnecting… while FreeSWITCH says Registered."** The phone had rejoined
   a *different* network after `svc wifi disable/enable` — `192.168.137.140` on a Windows
   hotspot, 100% packet loss to the Mac — so the REGISTER retransmissions correctly timed out
   and "Reconnecting…" was honest. The server row was a pre-blip entry still inside its hour
   TTL, not a fresh registration. Put back on `TEMP_WIFI_5G` by toggling Wi-Fi once (no
   settings change), the app recovered unaided: `17:01:15 registration success, 200 OK`, both
   accounts Registered. **Toggling Wi-Fi on this handset can move it to another SSID — check
   the phone's IP before blaming the code.**

### Still not covered, and why

- **The transfer-REFUSED path.** FreeSWITCH answers every blind REFER with 200, so it cannot
  produce it. Needs a far end that declines — a person on the other phone or Zoiper.
- **Whether anything is audible.** Every audio claim here is "connected on the wire" with
  packet counts, never "heard". Only Rahul can close that.
- **APK ↔ APK.** One USB cable; Phone B was off the cable for this sweep.
- **The office server** (7001 is registered and 7002 calls connected earlier today) — calls
  through it still need a far end.

---

## 0d. 2026-09-11, late afternoon — the ANR: sealing a recording on the main thread

**The newest section. Read it before §0c.**

Rahul reported the app crashing. It was not a crash — the process never died — it was an
**ANR**, and the log named it exactly:

```
09-11 15:32:19.547  6130  6130 I Choreographer: Skipped 310 frames!  The application may be
                                                doing too much work on its main thread.
09-11 15:32:27.623  1713  7011 E ActivityManager: ANR in com.whatsappv2 (…/.call.CallActivity)
   Reason: Input dispatching timed out (… is not responding. Waited 5005ms for MotionEvent)
/data/anr/anr_2026-09-11-15-32-19-998
```

`6130 6130` is pid = tid, so that is the **main** thread, and 310 skipped frames is ~5.2 s.
The timestamp is the moment "Stop recording" was tapped.

### The cause

One chain, with no dispatcher switch anywhere in it:

1. `CallRecordingController.stop()` launches on `viewModelScope` — **`Dispatchers.Main`**.
2. `PjsipCallRecorder.stop()` is `suspend`, but never left the caller's thread.
3. `RecordingStore.seal()` is **blocking**: it AES-GCM-encrypts the entire recording with an
   **Android Keystore** key — so a Keymaster round trip per block, not in-process AES —
   rewrites the file and deletes the plaintext.

5.3 MB through the TEE on the main thread ≈ 5 s of dead UI, and Android's input dispatcher
gives up at 5 s. `grep -rn "withContext\|Dispatchers" data/sip/…/recording/` returned
**nothing** before this fix.

### Why it appeared today and not weeks ago

It has always been written this way; it only became *reachable* on 2026-09-11. Before the
sweep's defect 2 (§0c), every recording was named `<id>.tmp`, `pjsua_recorder_create`
refused it, and the file was **0 bytes** — so `seal` returned at its `length() == 0L` guard
in microseconds. Fixing the filename turned an instant no-op into megabytes of Keystore
work on the main thread. A latent main-thread violation that was being hidden by a
different defect.

### The fix

`PjsipCallRecorder` takes a `DispatcherProvider` and every `RecordingStore` call —
`allocate`, `discard`, `seal`, `list`, `delete`, `purgeOlderThan` — goes through
`withContext(dispatchers.io)`. The fix is in the **implementation**, not the caller:
`CallRecorder` is a `suspend` interface precisely so an implementation may take time
without owning the caller's thread, and `CallRecordingController` was right to launch on
`viewModelScope`. A `suspend` function that blocks its caller is not a seam, it is a trap.

This follows the pattern already in `ContactsContractRepository` (`:data:contacts`), which
is the same problem solved the same way.

**The regression test trips.** `the store never runs on the thread that asked` records the
caller's thread name, gives the recorder a real `Dispatchers.IO`, and asserts the store saw
a different thread. Verified both ways: with the `withContext` removed it **FAILS**, with it
in place it passes.

### Verified on hardware

TC15 `24143524701316`, pid 10304, a FreeSWITCH-originated call to the echo, **60 s of
recording** — deliberately longer than the 55 s that ANR'd — then "Stop recording" followed
immediately by five taps hammered at the UI to force any block into an ANR:

```
sealed f45e4657-…__1789122035714__1789122109587.rec   7,092,552 bytes  (73.9 s)
                                      ^ a third larger than the file that ANR'd
Choreographer "Skipped … frames"   -> none
"ANR in com.whatsappv2"            -> none
new file in /data/anr/             -> none (newest is still anr_2026-09-11-15-32-19-998,
                                     which is from before the fix)
Fatal signal / FATAL EXCEPTION / Assert failed -> none;  pid unchanged
```

The five taps all landed — the call was on hold when they finished — so the main thread was
servicing input *through* the seal.

### One thing deliberately left

`EncryptedRecordingStore.init { sweepAbandoned() }` still runs on whichever thread first
injects the store, which is the main thread when `CallViewModel` is created. It is a
`listFiles()` plus an unlink per stale file — O(1) each, sub-millisecond — so it is not this
bug and was not swept into this fix. Worth moving if that directory ever grows.

---

## 0c. 2026-09-11, afternoon — the automated sweep, and two fixes about telling the truth

**Read this first; it is the newest.** It also **replaces `docs/HANDOFF-NEXT.txt`**, which
has been deleted — everything that file carried is here.

### The automated feature sweep (`9a066a10`) — six defects, all fixed

Every calling feature was driven by `adb` against the local FreeSWITCH (echo 9196, loopback
9197, tone 9198, conference 3000, FreeSWITCH-originated inbound calls), with a crash check
after every step. It found six things, and each was measured before the next was looked for:

| # | What broke | Cause | Fix |
|---|---|---|---|
| 1 | **Process crash** on "Turn off my video" | `vidStreamIsRunning(-1, …)` on a call whose video stream had just been removed resolves the index to -1 and pjsua ASSERTs (`pjsua_vid.c:2873`, SIGABRT on `pjsip-main`) | The gateway finds the encoding video stream in the call's own media list and asks only when there is one. `config_site.h` also sets `PJ_DEBUG 0` — pjproject's own release setting — so a library assertion logs `Assert failed` and returns `PJ_EINVAL` instead of ending a live call |
| 2 | **Recording wrote nothing** | The plaintext was named `<id>.tmp`, and `pjsua_recorder_create` picks its writer from the last four characters (`.wav`/`.mp3`, else `PJ_ENOTSUP`). The UI said "Recording this call" over a recorder that was never created | `<id>.unsealed.wav` (`RecordingFileNames`, JVM-tested). A 15 s call sealed a 1.47 MB `.rec` and deleted the plaintext |
| 3 | **Incoming call with the screen off did not wake the phone**, and showed no heads-up in the background | The ringing card was an *update* of the service's one notification (SystemUI fires a full-screen intent only on an ADD), and `setSilent(true)` puts it in a suppressive group that Android 13 explicitly blocks FSI for (b/231322873) | The ringing card is its own notification id, alert-once, and silence comes from the channel |
| 4 | **Call waiting showed the wrong screen** | Hold-and-answer left the held first call on screen under a banner saying the *second* call was on hold; ending the active call of a pair finished the screen and stranded the held one | The ViewModel re-points to the answered call and follows the remaining one (two tests) |
| 5 | **Every Wi-Fi blip logged a registration failure** and a stack trace | The recovery coordinator's refresh raced PJSIP's own IP-change re-registration (UDP socket back "in 10 ms") | `refreshAccount` defers to the IP change until PJSIP reports it complete (15 s safety). Verified: Wi-Fi off/on mid-call — call kept, one clean re-registration, no error |
| 6 | `pjsua_call_get_stream_info` logged an ERROR at every hangup | `encryptedAudio` asked for streams already torn down | Live streams only |

### Two fixes about the app telling the truth about itself (`ad583632`)

- **A recording the stack refuses is no longer shown as recording.**
  `SipRecordingGateway.startRecording` was fire-and-forget, so `PjsipCallRecorder` marked
  the call as recording the instant the job was *queued* — and the indicator is a function
  of that set. A stack that refused still put "Recording this call" on screen over a file
  nothing was writing. It is now `suspend … : Outcome<Unit, String>`: the PJSIP thread
  completes a deferred, the caller waits with a 5 s bound (§1.4), and a refusal becomes
  `RecordingError.EngineRefused`, which `CallRecordingController` already knew how to say
  out loud. A refused start also hands its slot back (`RecordingStore.discard`) — pjsua
  writes a WAV header before it can fail on the transmit, and a header *sealed* is a
  recording of nothing that still has to be listed and deleted like a real one. Three JVM
  tests cover the refusal path; it is **not** hardware-observed, because a live call cannot
  be asked to make the stack refuse.
- **`CallAudioCoordinator: Audio focus was not granted` is gone.** The connection is
  self-managed (`SipPhoneAccount` registers `CAPABILITY_SELF_MANAGED`, `SipConnection` sets
  `PROPERTY_SELF_MANAGED`), so Telecom has already taken focus for the call and refuses a
  second exclusive request over its own. Expected, not a fault — and warning about it put
  noise exactly where a real audio fault would have to show itself. Info now, saying which.
  The request stays, because it *is* granted when Telecom is not holding the call, and that
  is the case the focus listener exists for.

**Verified on hardware** — TC15 `24143524701316` (`1002@192.168.0.101`), a
FreeSWITCH-originated inbound call to the echo, this APK, 2026-09-11 15:30–15:32:

```
I CallAudioCoordinator: Audio focus stays with Telecom, which holds this self-managed call
   (info, immediately after MediaFocusControl's requestAudioFocus for uid/pid 10252/6130)
grep -c "Audio focus was not granted"  ->  0
I CallRecorder: Recording started on 8874d60d-…       <- through the awaited path
files/recordings/721eb922-…__1789120878949__1789120934362.rec   5,326,152 bytes  (55 s)
no .unsealed.wav left behind; no Fatal signal / FATAL EXCEPTION / Assert failed; same pid
```

JVM after both: **1189 tests, 0 failures, detekt clean.**

### Two decisions written down rather than left to be rediscovered

- **ADR-003 — there is no "Join conference" button, and none is wanted** (decided with the
  stakeholder, 2026-09-11). Under that ADR a conference *is* an ordinary call, so joining is
  dialling the bridge's extension in the dialler; a button would be a second name for the
  same act. `JoinConferenceUseCase` keeps no caller and stays, because it is the one path
  that marks a leg as a conference for a roster to attach to, and it is where an SFU swap
  would be wired in. `conferenceEvents` never emits — the client renders nothing rather
  than a fabricated roster, which is ADR-003's own second verify-bullet working.
- **ADR-008 now carries Lyra's per-call cost: ~120 % of one CPU core** on the TC15 for
  encode and decode together, observed during the 22-minute call of 2026-09-11 and not
  re-measured since. Against this app's pinned Opus at 32 kbit/s
  (`RealPjsipCoreGateway.OPUS_BITRATE`), Lyra's 3.1 kbit/s is ~10× less bandwidth for
  roughly an order of magnitude more CPU. The ADR's stale "what is still owed" paragraph is
  also corrected: the Lyra-linked `.so`, the audit line and a Lyra-carried call all exist.

### The test setup — do not re-derive it

- **Phone A**: TC15 serial `24110524701351`, `192.168.0.108`, accounts
  `7000@192.168.80.145` (office) **and** `localfs` = `1001@192.168.0.101` (lyra, PCMU, opus).
- **Phone B**: TC15 serial `24143524701316`, `192.168.0.117`, `1002@192.168.0.101`
  (lyra, PCMU, opus; Rahul deleted 7004 on it).
- **One USB cable.** `adb devices` first — only the plugged phone is driveable, Rahul
  answers the other. `adb` is at `/Users/rahulsingh/Downloads/platform-tools/adb`.
- **Driving the UI**: `uiautomator dump`, then tap the centre of the node's `bounds`.
  After `input text` press `KEYCODE_BACK` once to drop the keyboard, **and the first tap
  after that is often swallowed**. `Back` on the call screen *closes* it — reopen with
  `cmd statusbar expand-notifications` → "Ongoing call". Close the keypad with its
  "Hide the keypad" button, never Back. A reinstall kills the process and its registration
  with it: relaunch (`monkey -p com.whatsappv2 -c android.intent.category.LAUNCHER 1`) and
  confirm in `sofia status profile internal reg` before expecting a call to arrive.
- **Local FreeSWITCH on the Mac**: `/usr/local/freeswitch/bin/freeswitch -nc -nonat`; binds
  `en0` (192.168.0.101 on office Wi-Fi). `fs_cli` in the same directory; log at
  `/usr/local/freeswitch/var/log/freeswitch/freeswitch.log`; user password `1234`.
  Dialplan (outside the repo): `default/03_whatsapp_v2_test_apps.xml` → **9196** echo
  (audio+video), **9197** bridge to `loopback/9196` (transferable), **9198** tone,
  **9199** unrouted, **3000** `mod_conference`; `default/00_whatsapp_v2_bypass.xml` →
  `bypass_media` for 1001↔1002, which Lyra needs (FreeSWITCH does not know Lyra and 488s a
  Lyra-only offer).
- **An inbound call without the other phone**:
  `fs_cli -x "bgapi originate {origination_caller_id_number=9196,origination_caller_id_name=Echo,absolute_codec_string='PCMU'}user/1002 &echo"`
  — drop `absolute_codec_string` to get an `m=video` offer. A second conference member:
  `originate loopback/9196/default &conference(3000)`.
- **FreeSWITCH answers every blind REFER with 200**, so the transfer-refused path cannot be
  produced on it. It needs a phone as the far end that declines.
- **Build**: `SWIG_LIB=$HOME/.local/share/swig/4.4.1 ./build.sh --install` (full native
  ~5 min; `--reuse-native` for Kotlin-only changes, ~15 s). MacPorts `swig` lacks the Java
  typemaps — `sudo port install swig-java` is the proper fix.
- **Tests**: `./gradlew testDebugUnitTest test detekt -x :pjsip:api:generatePjsua2Bindings
  -x :pjsip:buildPjsua2Native`.
- **After any device test**: `adb logcat -d | grep -E "Fatal signal|FATAL EXCEPTION|Assert
  failed"`, and compare `pidof com.whatsappv2` before and after.
- **detekt caps `PjsipSipEngine` and `RealPjsipCoreGateway` with `LargeClass`** — both are
  at the bound, so new pure logic goes to file level or a new file. `ReturnCount` is 3.

### Still owed

1. **The office server `192.168.80.145`** (7000 ↔ 7004, Zoiper 7001): audio heard, video
   both ways, and the **transfer-REFUSED** path — a phone that declines the REFER target →
   486 → `TransferFailed` → the call returns to Connected. This is the one path the local
   FreeSWITCH cannot produce. Those extensions answered `480` all of 2026-09-11; **Rahul
   registers them**, so this is blocked on him and on nothing else.
2. A preview that follows a **rotation mid-call** is untested
   (`LaunchedEffect(configuration)` covers a manifest that does not).
3. `sudo port install swig-java`, so `./build.sh` runs without `SWIG_LIB`.
4. Lyra and Opus have never been **measured side by side** on one handset. The CPU number
   in ADR-008 is one observation of Lyra alone.

---

## 0. The one-paragraph state

Two root causes behind the reported calling failures were found by reading a device trace and
probing the server directly, and both are now fixed in code with JVM tests: an endpoint-wide
codec wipe (item 1) and an oversized INVITE (item 2). **Item 2 is complete as of this pass** —
ICE is off by default and a migration turns it off on accounts already saved, which is the 54
bytes the SRTP trim alone did not close (§3.1). Items 5 and 7 are done and tested. Item 3 is
partly done. Item 4 was reframed and not delivered. Item 6 was diagnosed and is not fixable
here. **Lyra is 0% implemented — only a dependency count exists.** §6, the call-history naming
defect, is **done in code with tests**. Two things that would have kept CI red were found and
fixed (§3.5). **Nothing in any of this has been verified on hardware and no APK has been
built** — that is the single largest thing still owed.

---

## 1. Verified facts you can build on — do not re-derive these

All measured on 2026-09-10 against the live setup. Trace read off the handset; server probed
from a laptop on the same network.

| Fact | Value |
|---|---|
| Server | `192.168.80.145`, presents as `iriscloud` (FreeSWITCH derivative, `mod_sofia`) |
| Handset | Zebra TC15, Android 13 (API 33), `arm64-v8a`, USB debugging, IP `192.168.137.140` |
| NAT | handset `192.168.137.140` arrives at the server as `192.168.100.150` |
| Extensions | `7000`–`7005`; `7000` is the handset |
| **UDP 5060** | **works** — SIP `OPTIONS` answered `200 OK` |
| **TCP 5060** | **`ECONNREFUSED`** |
| **TLS 5061** | **closed** |
| **Max SIP payload the path carries** | **1472 bytes.** 1475 gets no answer. 1472+8+20 = 1500 = Ethernet MTU; the path drops IP fragments silently |
| REGISTER round trip | **57 ms** including the 401 digest exchange |
| The app's audio INVITE | **1742 bytes** — 7 retransmits over 32 s, zero responses (Timer B) |
| The app's 781-byte INVITE | got `100 Trying` on the first attempt — this is the control |
| Codecs the library really registers | `opus/48000/2`, `G722/16000`, PCMU, PCMA, GSM, iLBC, AMR-WB, AMR, speex×3, L16×2 |
| Video registry | `VP8/102, H264/99, VP8/103, VP9/106` — **H264 comes from `MediaCodec`, not OpenH264** |
| LeakCanary | 1 leak, **2686 bytes per Telecom bind cycle**, retained by `android.telecom.ConnectionService$1.this$0` — the framework's own binder. Zero orphaned connections from app code |

**Two claims in the old spec were disproved and are already corrected in
`docs/calling-defects-prompt.md` §2.6.** Opus and G.722 *do* register (the audit's log line
was filtered); and "the request never reached anything" is specifically *because it exceeds
the path MTU and there is no TCP to fall back to*.

---

## 2. What is committed and green

Commits `038359e`, `2682eb6`, `c9b1bcf`, then `7936b92`, `d6d4390` and `ca229d1` from the
afternoon pass. All pushed.

- **Item 1 — answered call disconnects. FIXED.** Root cause: `applyPriorities` set priority
  `0` on every registered codec no account preference named. An account saved with
  `audio=[lyra]` matched nothing, so **all** audio codecs went to 0, endpoint-wide and
  persistently. `pjmedia_endpt_create_audio_sdp` stops at the first disabled codec →
  zero-format m-lines → outgoing `m=audio 0 RTP/AVP 0`, and answering produced
  `PJMEDIA_SDPNEG_ENOMEDIA` and a `488` the app sent *itself*.
  Fix: `domain/…/codec/CodecPriorities.kt` — a preference set matching nothing changes no
  priority; one account cannot disable a codec another needs. 9 tests.
- **Item 5 — dialer selection. FIXED**, with the per-call/per-navigation trap covered: it
  survives navigation, is cleared by a *successful* call, and is kept after a refused one.
- **Item 7 — unregistered account. FIXED.** `PlaceCallUseCase` now registers then dials,
  bounded at 5 s (57 ms observed; the bound covers three Timer A retransmissions). New
  `PlaceCallError.NotRegistered`. 5 tests.
- **Item 3 — audit honesty. PARTLY DONE.** Registry now logged verbatim with priorities;
  `NoPeerAccepts` → `ExpectedUnsupportedByServer(source)` carrying its evidence; an ERROR
  fires when every audio codec sits at priority 0. **Not done:** per-codec on-device round
  trips (N-9), and deriving strandedness from real evidence.
- **CI red fixed** (`Unnecessary safe call` under `-Werror`), and `bin/` is now gitignored —
  stale IDE output made the architecture test scan its own violation fixtures and fail every
  rule, locally only.

**Added in the pass of 2026-09-10 (afternoon):**

- **Item 2 finished** — ICE off by default, plus the account-store migration that turns it off
  on rows already saved. §3.1.
- **The call-history naming defect** — resolved at page load, with the extension rather than a
  URI as the last resort. §6.
- **`:data:sip:detekt` and `:test:arch:test` un-broken** — both were red at `48cf6d6`. §3.5.
- **`AccountConfigFactory.kt`** — the account-config translation, out of a gateway that was
  over `LargeClass`. Compiled and tested locally using the §4 workaround, which is how the
  one bug in it was caught before it was pushed.

**Verified green locally, 2026-09-10 15:56, all under `-PwarningsAsErrors=true`:**

| Module | Tests |
|---|---|
| `:domain` | 466 |
| `:data:sip` | 199 (via the §4 workaround) |
| `:feature:accounts` | 77 |
| `:feature:calls` | 62 |
| `:data:account` | 59 |
| `:core:common` | 51 |
| `:test:arch` | 35 |
| `:feature:dialer` | 34 |
| `:feature:history` | 23 |
| `:feature:settings` | 11 |
| `:data:contacts` | 10 |
| `:data:calllog` | 8 |

**0 failures, 0 errors.** `./gradlew detekt` is green across every module. `:app:test` is the
one thing not run: it needs the native libraries, which need the toolchain §4 describes.

---

## 3. Defects found in the previous agent's own work — all resolved except §3.4

### 3.1 ~~Item 2 is NOT fixed~~ — RESOLVED. ICE is off, and the migration turns it off too.

The SRTP trim saves 216 bytes, not the 232 that was estimated, so it left the INVITE at
**1526 — 54 bytes over** the 1472 the path carries. ICE is the 198 bytes that close the gap,
and ICE had not been changed.

Three changes, all with tests:

- `NatPolicy.DEFAULT.iceEnabled` is now **false** (`domain/…/model/NatPolicy.kt`), and
  `SipAccountDraft` takes its opening value from it rather than repeating a literal.
- **Account store version 1 → 2** turns `ice_enabled` off on every row already saved
  (`data/account/…/db/SipAccountDatabase.kt`). Without it the handset that reported the
  defect keeps its old row and the fix does nothing there. `SipAccountMigrationTest` builds a
  real version 1 database from the committed `1.json` — table, indices and identity hash —
  and asserts the migrated result. `2.json` is committed.
- The gateway's own comments carried the **estimated** 116/76 byte figures; they now carry
  the measured 108/84, and the account-config assembly moved out to `AccountConfigFactory.kt`
  (see §3.5).

Arithmetic, all measured: `1742 − 216 (two AES_256 lines) − 198 (ICE) = 1328`, which is 144
bytes under the hard limit and still 56 short of RFC 3261 §18.1.1's 200-byte headroom. Both
numbers are in `SdpBudgetTest` and in `docs/architecture.md` §4.11.1.

**A fact worth carrying forward:** `SipAccount.stunServer` is collected, validated and
persisted, and **nothing below `:domain` reads it** — no STUN server ever reaches `UaConfig`.
So ICE could only ever gather host candidates, which is why turning it off costs nothing.
Wiring STUN up is a separate change with its own measurement, and nobody has done it.

**Still owed: re-measure on the handset.** The arithmetic says 1328. Only a call says so.

### 3.2 ~~Uncommitted, never-run changes~~ — RESOLVED, committed and green

The constant corrections (`AES_256` 116→**108**, `AES_128` 76→**84**) and the rewritten
tests are committed. **9 tests ran and passed.** The working tree is clean; nothing is
outstanding here. The corrected arithmetic is what §3.1 above is based on.

### 3.3 ~~`SdpBudget` is inert~~ — RESOLVED by saying so plainly

It is a documented constant, not a guard, and its KDoc now says that in those words: nothing
in Kotlin ever sees the datagram PJSIP builds, so no code here can refuse an oversized one.
What it also now names is the three settings that *do* hold the offer down, each pinned by a
test — ICE off by default (`NatPolicyTest`), ICE off on rows already saved
(`SipAccountMigrationTest`), and two crypto suites rather than four
(`AccountConfigFactory.OFFERED_CRYPTO_SUITES`).

**And the limit of that, stated in the same place:** a change that adds bytes some *other*
way — a codec, an `fmtp` line, a second `m=` line — fails no test. Only a call on hardware
catches it.

### 3.4 ~~Nothing has been verified on hardware~~ — RESOLVED in the evening pass, see §0a

The spec's own rule is *"a calling defect is not fixed until a call has been placed, answered
and heard."* As of the evening of 2026-09-10 an APK has been built, installed, registered,
and calls have been placed and answered on two servers; the audio INVITE measured 1148–1318
bytes on the wire against 1472. "Heard" is still owed a person at each end.

### 3.5 Two things were keeping CI red, and they were not the code under review

Both were red **at `48cf6d6`**, before this pass touched anything, and both are fixed. The
previous handoff's "verified green locally: `:test:arch:test`, detekt" was wrong on both
counts — the detekt run it describes cannot have included `:data:sip`.

- **`:data:sip:detekt` failed with four findings.** `RealPjsipCoreGateway` went over
  `LargeClass` when the SRTP and NAT work added two hundred lines to a class that was
  already at the line, and `toAccountConfig` went over both `CyclomaticComplexMethod` and
  `NestedBlockDepth`. Fixed by moving the account-config translation into
  `AccountConfigFactory.kt` — no gateway state in it, the same reason `auditCodecs` lives in
  its own file — and splitting it into a NAT, an encryption and a video part. The fourth was
  a blank line before a brace in `DeclaredFeatureSet.kt`. **`./gradlew detekt` is green.**
- **`:test:arch:test` failed every rule at once, locally.** Eclipse and the IDE's Kotlin
  plugin copy sources into `<module>/bin/main`, including `test/arch`'s own `fixtures/` —
  the files that violate every rule *on purpose*. Gitignoring `bin/` fixed the commit and not
  the scan, which walks the filesystem. `"bin"` is now in `ArchitectureRules.EXCLUDED`.

**Still: check `gh pr checks docs/native-mandate-design` before assuming anything.** CI
compiles `:data:sip`, which nothing on this Mac can, so its verdict on that module is the
only one there is.

---

## 4. The local build is *mostly* not blocked — the workaround is one flag

The claim in the previous handoff was that `:data:sip` "cannot compile on this Mac". It can.
`:pjsip:api:generatePjsua2Bindings` fails its own precondition check — SWIG's Java typemaps
are missing — but **the bindings it would generate are already on disk** at
`pjsip/api/build/generated/pjsua2/` (313 files), so excluding the task compiles the module:

```bash
./gradlew :data:sip:compileDebugKotlin :data:sip:test -x :pjsip:api:generatePjsua2Bindings -PwarningsAsErrors=true
```

**Use it. It earns its keep immediately**: on the first run it caught a real bug in this
pass's own refactor — `pushParameters` is gateway state, not a `StackAccount` field, and the
extracted `toAccountConfig` had captured it by accident. Nothing but a compiler finds that.

What the exclusion does *not* do is produce an APK: `:app:assembleDebug` needs the native
libraries, which need the real toolchain. That still wants:

```bash
sudo port selfupdate && sudo port install swig-java
```

**Ask the user to run it; you cannot.** Once unblocked, the user's own verification command is:

```bash
./gradlew :app:assembleDebug -Ppjsip.abis=arm64-v8a -Ppjsip.swig=/opt/local/bin/swig -Ppjsip.swig.version=4.4.1
```

Gradle on this machine is slow (3–15 min). **Never run two Gradle invocations at once** — it
corrupts the build directory and every test class then fails to load with
`ClassNotFoundException`, which looks like a code failure and is not. If that happens:
`rm -rf <module>/build` and re-run singly.

---

## 5. Lyra — 0% implemented. Only the closure has been counted.

`PJMEDIA_HAS_LYRA_CODEC` is still **0**. `third_party/lyra` does not exist.
`AudioCodec.LYRA` is still out of `CodecPreferences.DEFAULT`. No build has been attempted.
**Do not describe any of this as working.** Full findings: `docs/lyra-criterion-1.md`.

What the count established, from `google/lyra` at tag `v1.3.2`:

- **Lyra's own code is small** — 20 non-test `.cc`, 34 `.h`. Its direct deps (absl
  `20211102.0`, glog, gulrak filesystem `v1.3.6`) all have CMake.
- **Rock 1 — TensorFlow Lite pinned at v2.11.0**, ~1.35 GB, reached through
  `lyra/tflite_model_wrapper.cc`, and the **XNNPACK delegate is included directly** so it is
  not optional. TFLite has an official CMake build with Android support; the risk is a 2022
  tree of `cpuinfo`/XNNPACK/ruy sources under a 2024 NDK.
- **Rock 2 — `com_google_audio_dsp`** has **no CMake build at all**, reached at 12 call sites
  for 6 targets (`signal_vector_util`, `number_util`, `resampler_q`, `mfcc`, `spectrogram`,
  `spectrogram:inverse_spectrogram`, `portable:read_wav_file`). All would be hand-written.
- **Cheap parts:** all four model files ship in the repo (**3.5 MB**), and
  `third_party/pjproject/aconfigure.ac:2582-2640` already implements `--with-lyra=DIR` — it
  link-tests `LyraDecoder::Create`, then sets `ac_lyra_model_path` and defines the flag.
  **pjproject needs no change.**

**Do this first and stop if it fails:** build **TFLite v2.11.0 for `arm64-v8a` with NDK r27c
via its own CMake, XNNPACK enabled**. Nothing else matters until that is known. If it cannot
be patched cheaply, that is ADR-008 **Exit B** — write it up, ship no code. A working clone is
already at
`/private/tmp/claude-501/-Users-rahulsingh-Desktop-whatsapp-v2/e0bd43b1-5ce8-4cf8-bae4-9ba7eaf5c9bd/scratchpad/lyra`
(may be gone; re-clone `--depth 1 --branch v1.3.2`).

Constraints: **do not edit `third_party/` directly — patches only** (rule 12). Vendor via
`tools/vendor/vendor.sh` + `pins.sh` + `record-hashes.sh`, no build-time downloads (N-2).
`config_site.h` is the one source of truth (N-8). Run `:test:arch:test`.

**And the fact that survives either exit:** the deployed server offers PCMU, PCMA, G.729,
G.723.1, AMR, Speex, VP8, VP9 — **no Lyra**. Even on Exit A the honest deliverable is
*"Lyra compiled, registered, model files verified, provably selectable, with no deployed peer
that accepts it."*

---

## 6. Call history showed a URI where it should show a name — DONE in code, unverified on device

**Requested by the user, 2026-09-10. Implemented in this pass.** The decision and its
rejected alternatives are recorded in `docs/architecture.md` §4.11.3, as every other DECIDE
in this project is.

**What was decided: resolve the name as the page loads, and keep the snapshot as the
fallback.** Both causes needed separate answers and both got one.

1. **`contactName` is still a snapshot, and still written once** — that is deliberate and it
   survives a contact being deleted. What it could not do was learn, so `CallLogTitles`
   (`domain/…/usecase/CallLogTitles.kt`) now asks the address book for the *current* name.
   Precedence: **current name → stored snapshot → the peer's `remoteDisplayName` → the
   address.** The first two are the user's own word for the person and both outrank the
   third, which is criterion 3 and was the ordering that already existed.
2. **The last resort is `SipUri.label()`, not `render()`** — `7001`, not
   `sip:7001@192.168.80.145`. A URI with no user part falls back to the host.

**Where it runs.** `CallLogPagingSource.load`, once per row loaded, on Paging's fetch
dispatcher — so it covers `ALL` and `MISSED` alike, and no contacts read happens per scrolled
row (criterion 5). `HistoryRow.Call` carries the resolved title, so the row composable and the
detail sheet read a value rather than resolving one.

**One thing that had to change underneath.** `ContactsContractRepository` remembers absences
as well as matches, so an address looked up before its contact existed stayed "nobody" for
the life of the process — which would have left the reported case half-fixed on the very
screen that reported it. It now registers a `ContentObserver` on `ContactsContract` (lazily,
on the first lookup that has permission — Hilt builds it long before the user answers that
prompt) and empties the cache on any change. `LookupCache` is synchronised for that reason.

**Tests.** `CallLogTitlesTest` (8, including the missed-call case and the deleted-contact
case), `CallLogPagingSourceTest` (4 new, both filters plus the per-row cost), two in
`SipUriTest`, three in `ContactsContractRepositoryTest`. `:feature:history` is green.

**Not done: seen working on the handset.** Same as everything else in this file.

## 7. Not delivered, deliberately

- **Item 4 — "production-ready, fix all bugs."** Has no completion test, so no honest report
  can claim it. `docs/calling-defects-prompt.md` §3.4 replaces it with the six measurable
  budgets in `docs/dod-sweep.md`, **none of which has a number yet**.
- **Item 6 — memory leaks.** The one leak is the framework's own `ConnectionService` binder,
  2686 bytes per bind cycle, not reachable from app code. Not fixable here; the spec's
  acceptance criterion explicitly allows naming it instead. **Not measured across 20 call
  cycles** — that number is still owed.

---

## 8. Server-side work neither agent can do

- **No TCP listener on `192.168.80.145`.** A video INVITE is ~2700 bytes and cannot fit a
  1472-byte datagram after any trim, so **video calling cannot work until the server accepts
  TCP.** Raise it with whoever operates the server.
- **`mod_opus` is not installed.** Configured in `modules.conf.xml`, `.so` absent. Installing
  it halves the bandwidth of every call with **no client change** — the APK already registers
  and offers Opus.

---

## 9. Housekeeping — actioned, with one left

- ~~`log.txt` at the repo root~~ — deleted. 0 bytes, untracked, stale.
- ~~The two prompt files at the repo root~~ — both are under `docs/` now (the move was
  sitting uncommitted in the working tree). **The links to them were broken by that move and
  are fixed**: `README.md` and `docs/architecture.md` both pointed at the old root paths.
- `docs/phase-2-checkpoint.md` is still referenced by nothing. Left alone: deleting a
  document is the user's call, not an agent's.

---

## 10. How to work here

- **Measure before diagnosing.** Three diagnoses in this project were reached by reading code
  and the device disproved all three — including two by the agent writing this file.
- **Probe the server directly.** Bisecting the MTU with SIP `OPTIONS` took four minutes and
  settled a question source-reading had got wrong twice.
- Build with `-PwarningsAsErrors=true`; CI does, and a local green build without it means
  nothing.
- Verify on the handset, not an emulator.
- Grep every symbol, flag and codec id before naming it.
- **Report honestly.** "Implemented", "registered" and "verified on hardware" are three
  different claims, and only the last ends an argument.
