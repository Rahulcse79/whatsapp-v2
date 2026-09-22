# Prompt: video calling — test, verify and fix on real devices

> **How to use this file.** Paste it whole as the opening message to a coding agent working
> in `whatsapp-v2`. It governs everything a video call touches: 1:1 video, the conference
> bridge, and every piece of app UI a video call passes through. `docs/master-engineering-prompt.md`
> still governs *how* the work is done — its layer rules, review rubric and honesty rules
> apply unchanged. `docs/HANDOFF.md` is the last session's evidence and is read before
> anything is reproduced. This file adds *what to test*, *what counts as proof* and *what
> to deliver*.
>
> **DECIDE** = must be answered before code is written. **VERIFY** = must be checked against
> source or a device before it is relied on. **Written 2026-09-22.** Every value below
> marked *measured* was read off a device or the server that day; every unverified line
> says so.

---

## 1. The rule this file exists to enforce

**A video defect is not fixed until it has been reproduced on a handset, root-caused with
evidence, fixed, rebuilt, installed, and seen not to happen again on the same handset.**

Four claims, in ascending order, and only the last one closes an item:

| Claim | Proof it needs |
|---|---|
| *compiled* | `BUILD SUCCESSFUL` in the build log (not the exit code — see §3) |
| *installed* | the APK's `md5` matches `adb shell md5sum` of the installed `base.apk`, **and** the dex contains a symbol only the fix introduced |
| *negotiated* | the SIP trace shows the `m=video` line answered with the expected payload type |
| *verified on hardware* | the observable outcome named in §6, held for **at least 60 s** (SIP timers fire ~32 s in), with the evidence artefact named in §5 |

"It compiles", "the code looks right", "the log line is gone" and "the unit test passes"
are not among them. Report nothing as working that you have not watched work. If an item
cannot be tested, say so and say why — an untested item is not a failed one, and it is not
a passed one either.

**Reproduce before fixing.** A fix for a defect that was never seen is a guess, and a guess
that compiles is the most expensive kind: two earlier sessions drew conclusions from an APK
that was not the build under test (`docs/HANDOFF.md`, "Prove the build on the device").

---

## 2. The reference setup — part of the specification

Re-read every value in this table at the start of the session. **Nothing here is stable
across sessions**; the Wi-Fi address has changed four times in ten days.

| | *measured 2026-09-22* |
|---|---|
| Repo | `/Users/rahulsingh/Desktop/whatsapp-v2`, work on a branch off `main` |
| Server | FreeSWITCH on this Mac, `sofia status` → profile `internal` at **`192.168.137.247:5060`**. `fs_cli` is `/usr/local/freeswitch/bin/fs_cli` (not on `PATH`). Config `/usr/local/freeswitch/etc/freeswitch`, log `/usr/local/freeswitch/var/log/freeswitch/freeswitch.log` |
| Handset on USB | **Zebra TC15, serial `24110524701351`, Android 13, extension 1004**, `192.168.137.52`. This is the only device that can be driven by `adb`. `adb` is `~/Library/Android/sdk/platform-tools/adb` |
| Other handsets | 1000 (`.43`), 1001 (`.168`), 1005 (`.196`) — registered, not on USB. **A human must answer them.** Every multi-device step names which handset the human is holding |
| Echo | **9196** answers instantly with audio *and video* echoed back (`answer` + `echo`); the one video peer that needs nobody |
| Others | 9197 bridged echo (a transferable leg), 9198 tone (480+620 Hz), 9199 unrouted (a clean failure) |
| Conference | **3000** → `whatsapp-video` profile: `video-mode=mux`, layout `group:wa-portrait`, canvas **720x1280**, 15 fps, 1 Mb, **`max-members=4`**, `rfc-4579` flag. A conference of one is a legal test |
| Codecs | App offers `m=video … RTP/AVP 102 99` (VP8/102 libvpx, H264/99 MediaCodec); VP8/103 (MediaCodec) is priority 0 because its decoder renders black. Server `global_codec_prefs=PCMU,PCMA,VP8` — **video-call audio is G.711/8 kHz by deployment**, not a defect |
| Audio | Every call starts on the **earpiece** (a headset wins; Settings → Audio route → Speaker forces the loudspeaker). The "video calls start on the speaker" rule was reversed on 2026-09-22 |

**A third-party client is the control** where one exists. When a video call fails, ask
whether Zoiper/Linphone fails the same way against the same extension on the same
network. If both fail, the defect is in the server or the network and no fix belongs in
this repository.

---

## 3. Build, install, prove — the rules that cost hours before

1. Build only with **`./build.sh --reuse-native --install`**. Never `./gradlew assemble`
   (no NDK environment). A full native build is only needed when `pjsip/` or
   `third_party/` change.
2. **Never `pkill` Gradle** — it poisons `~/.gradle/caches/build-cache-1` and drops
   `Hilt_*` classes silently. Use `TaskStop`.
3. `build.sh` under `nohup … &` exits 0 when Gradle failed. **Grep the log for
   `BUILD SUCCESSFUL`.**
4. Prove the install: `md5 -q app/build/outputs/apk/debug/app-debug.apk` against
   `adb shell md5sum $(pm path com.whatsappv2)`; then
   `dexdump -d` (`~/Library/Android/sdk/build-tools/36.0.0/dexdump`) or `grep -a` the dex
   for a symbol only the fix introduced.
5. **A reinstall kills the process and the registration.** Relaunch
   (`adb shell monkey -p com.whatsappv2 -c android.intent.category.LAUNCHER 1`), wait ~5 s,
   and confirm in `sofia status profile internal reg` that 1004's **contact port changed**
   before originating anything. A call into a stale registration goes nowhere and reads as
   a code defect.
6. Turn on **Settings → SIP trace** before anything: `PjsipTrace` then carries every SIP
   message and the per-call RTP dump.
7. JVM tests for the modules touched (`:feature:calls`, `:data:sip`, `:app`) must be green
   before the branch is handed over; a fix that lands with a red test is not landed.

---

## 4. The verified baseline — read before proposing anything

Everything here was observed on hardware in the sessions of 2026-09-14 … 09-22 and is
recorded with its evidence in `docs/HANDOFF.md`. Do not re-fix it; do re-verify it.

| Fact | Where the proof lives |
|---|---|
| Remote video is no longer stretched: `requiredSize` in `CallVideo.videoBounds`; SurfaceFlinger scale x = y on 1:1 (`3.6075/3.6078`) and conference (`1.5/1.5`) | HANDOFF "earlier" #1 |
| A mid-call resolution change re-sizes the view: `decodedVideoSize` reads `vidCodecParam.decFmt`, re-read 250 ms later for the preview half | HANDOFF #5 |
| A call to 3000 is marked a conference (`markConferenceIfRoom`, after `store()`); UI shows the room, a duration and a Participants row; scaling is Fit | HANDOFF "earlier" #2 |
| VP8/103 is priority 0; FS picks 102 | HANDOFF "earlier" #3 |
| A video call starts on the earpiece, and the Speaker button and Settings preference both still reach the loudspeaker | 2026-09-22, Galaxy E23 |
| Returning to a video call from the launcher does not kill the process (`SelfPreview`'s shared `TextureView` is re-parented, not re-added) | 2026-09-22 |
| A held video call is dimmed, labelled, keeps its controls and drops the self-view whose camera is released | 2026-09-22 |
| The self-view parks above the in-call controls while they are on screen | 2026-09-22 |
| A failed call says why before the screen closes | 2026-09-22 |
| Self-view: 9:16 card, opens minimised, drag-to-corner, resize grip, tap-outside minimises a maximised preview, `TextureView` so the crop is real | `SelfPreview.kt`, `VideoLayout.kt`, `SelfPreviewTest` |
| In-call chrome auto-hides after **6 s** over video (`CHROME_IDLE_MILLIS`), never with touch exploration on, never while a prompt is up | `CallScreen.kt` |
| The bridge publishes no roster to this client (RFC 4575 needs an in-dialog SUBSCRIBE that SIGSEGVs in `mod_evsub`); the roster card says so honestly | HANDOFF item D |
| Registration survives deep sleep (AlarmManager keepalive at half-expiry) | HANDOFF #4 |

**Known and accepted, not defects:** narrowband audio on FS-mediated (video) calls; no
live participant list from the bridge; H264/99 untested and unusable for the conference
(`mod_av` does not load).

---

## 5. Evidence techniques — the only ones that count

| Question | Measure it with |
|---|---|
| Is the picture the right shape? | `adb shell dumpsys SurfaceFlinger \| grep -A2 'SurfaceView\[com.whatsappv2/com.whatsappv2.call.CallActivity\]'` — `toDisplayTransform` scale **x must equal y**. Compare with `logcat \| grep "Video is"` (`WxH in, WxH out`). Never eyeball a screenshot for aspect; a dark room reads as a letterbox bar |
| Is video flowing? | `logcat \| grep "Media statistics for"` at hangup (never dump at DISCONNECTED — media is gone by then); `PjsipTrace` RTP dump (`pkt loss=`) |
| Is the camera held or released? | `adb shell dumpsys media.camera \| grep -A3 "Device .* is open"` after the call ends; `logcat \| grep -E "vidSetStream\|STOP_TRANSMIT\|android_dev"` |
| Which codec/pt was negotiated? | `PjsipTrace` — the answer's `m=video` line; `fs_cli -x "show channels"` `read_codec/write_codec` |
| What is on screen? | `adb exec-out uiautomator dump /dev/tty` for the focused window (tap by `content-desc`, never by a stale coordinate — the chrome hides in 6 s); `adb exec-out screencap -p` for anything that is not the focused window (a heads-up notification) |
| Did it leak? | LeakCanary's `leaks.db` (`is_library_leak=0` count) after the run; the `ConnectionService` binder-stub leak is a known library leak and is fine |
| Did it crash or ANR? | `adb logcat -b crash -d` **before** `adb logcat -c`; `grep -E "Fatal signal\|FATAL EXCEPTION\|ANR in com.whatsappv2\|Skipped [0-9]+ frames"` |
| Registration state | `fs_cli -x "sofia status profile internal reg"` — compare the **contact port** or `EXP`, not the presence of a row |
| Drive the far end | `fs_cli -x "bgapi originate {origination_uuid=$U,ignore_early_media=true}user/1004 &echo"` rings the TC15; `uuid_kill $U` is the far-end BYE/CANCEL |
| Memory / threads during stress | `adb shell dumpsys meminfo com.whatsappv2 \| grep "TOTAL PSS"`, `ls /proc/<pid>/task \| wc -l`, `ls /proc/<pid>/fd \| wc -l` — baseline before, sample after every 5 cycles |

---

## 6. The test matrix — every row is a claim, and each one names its proof

**Legend.** *Solo* = TC15 against 9196/3000, no human. *Human* = needs a person on 1000/1001/1005; name which, and if nobody is there, mark the row **UNRUN**, not passed.

### 6.1 Placing and answering

| ID | Steps | Pass when | Evidence |
|---|---|---|---|
| V1 | Dialler → 9196 → *Place video call* | Connected ≤ 5 s; `Video is WxH in, WxH out` both non-zero; self-view visible; speaker on (`Turn off speakerphone` in the chrome) | logcat, uiautomator dump |
| V2 | V1 held **≥ 60 s** | No re-INVITE storm, no disconnect, `Media statistics` 0 % loss on both streams | `PjsipTrace`, statistics line |
| V3 | Inbound video call to 1004 (`originate … user/1004` with video, or a human from 1001 with *Place video call*) | Ringing screen shows **Decline** and **Video** only; *Answer with video* connects with both surfaces | uiautomator dump, `Video is` |
| V4 | Inbound **audio** call to 1004 | Ringing screen shows Decline and **Answer**, no video button; connects on the earpiece | uiautomator dump |
| V5 | V3 but the far end cancels while ringing (`uuid_kill`) | Screen closes, camera released, notification gone | `dumpsys media.camera`, `dumpsys notification` |
| V6 | 9199 with video | Clean failure message, no camera left open, screen closes | logcat, `dumpsys media.camera` |

### 6.2 The in-call screen over video

| ID | Steps | Pass when | Evidence |
|---|---|---|---|
| U1 | Chrome auto-hide | Controls gone ~6 s after the last touch; one tap on the picture brings them back; never hidden while a prompt is up | timed uiautomator dumps |
| U2 | Self-view opens minimised, restore glyph grows it to twice the width; tap outside a maximised preview minimises it and does **not** toggle the chrome | dumps of `call-video-preview` bounds before/after |
| U3 | Drag the self-view to each of the four corners | It parks in the nearest corner every time; the bottom corners clear the End button | dump bounds vs `Sizing.videoPreviewControlsInset` |
| U4 | Resize grip | The outline follows the finger; the box commits once on release; floor = half the ceiling; 9:16 held at every size | dump bounds; ratio arithmetic shown |
| U5 | Rotate the handset mid-call (`settings put system user_rotation 1`) | Both pictures re-oriented, SurfaceFlinger x = y after the rotation, self-view still 9:16 and still in a corner, the call untouched | SurfaceFlinger, dump |
| U6 | Keypad over video | Opens, sends DTMF (`PjsipTrace` RFC 4733 / INFO), chrome does not auto-hide while it is open | trace, dump |
| U7 | Background (Home) and return during a video call | Surfaces released on leave (`Could not attach` must not appear), re-attached on return, picture back within 2 s; the ongoing-call notification present throughout | logcat, `dumpsys notification` |
| U8 | Back press on the in-call screen | The call continues; the notification's *Hang up* ends it | dump, notification |

### 6.3 Media controls

| ID | Steps | Pass when | Evidence |
|---|---|---|---|
| M1 | Video off → on, **× 10**, on a 9196 call | Every toggle is one re-INVITE; the far end's picture returns each time; no crash; camera released while off | `PjsipTrace` count, `dumpsys media.camera` |
| M2 | Flip camera **× 10** | Preview switches each time; no re-INVITE; `Video is … out` size follows the sensor | logcat |
| M3 | Hold then resume a video call (≥ 20 s held) | Both pictures stop on hold (`a=sendonly`), both return on resume; self-view returns; no stretched frame after resume (x = y) | trace, SurfaceFlinger |
| M4 | Mute/unmute over video | Only the microphone changes (`Port 0 … stop transmitting`), video keeps flowing | `conference.c` lines, statistics |
| M5 | Speaker ↔ earpiece during video | Route changes; the picture is unaffected | `logcat \| grep "turning speaker phone"` |
| M6 | *Human, 1001*: audio call, then the far end adds video | The **Video request** prompt appears and pins the chrome; Accept sends our re-INVITE and both pictures come up; Decline keeps the audio call alive | trace, dump |
| M7 | Video call, far end drops video (their toggle) | Remote view goes, self-view stays, the call continues, no prompt | dump, trace |

### 6.4 Conference (bridge 3000)

| ID | Steps | Pass when | Evidence |
|---|---|---|---|
| C1 | *Solo*: video call to 3000 | `marking it a conference` logged; title "3000"; duration ticking; Participants card says the bridge publishes no list; scaling Fit; the composed 720x1280 canvas centred, x = y | logcat, dump, SurfaceFlinger |
| C2 | C1 held ≥ 60 s | No re-INVITE storm, no disconnect | trace |
| C3 | *Human*: 9196 call → *Add* → 3000 (or 1001) → *Merge* | The screen re-points at the room leg; the old legs end; the REFERred handset shows "conference" | logcat, dump on both |
| C4 | *Human*: 3 devices in 3000 with video | Each sees a grid of the others; badge absent (no roster); self-view over the grid does not cover a face at its default size | screencap from each |
| C5 | *Human*: a 5th caller into a 4-member room | Refused by the bridge (`max-members=4`); the 4 in the room are undisturbed | FS log, trace |
| C6 | Leave the room from the app | The leg ends cleanly; the conference history entry is one row, not one per leg | call log DB |

### 6.5 Robustness and resources

| ID | Steps | Pass when | Evidence |
|---|---|---|---|
| R1 | Incoming call during a video call (*Human* or `originate … user/1004`) | Second-call prompt pins the chrome; Accept holds the video call and swaps; Reject keeps it | dump, trace |
| R2 | Wi-Fi drop for 20 s, then restore, mid-video-call | Re-registration; the call survives or ends cleanly with a message — never a frozen screen | logcat, `sofia status` |
| R3 | Camera permission revoked (`pm revoke com.whatsappv2 android.permission.CAMERA`) then *Place video call* | An explanatory message; the call is placed audio-only or not at all — never a crash | logcat |
| R4 | **20 ×** join/leave 9196 with video | PSS, thread count and fd count after cycle 20 within 10 % of the baseline after cycle 1; 0 leaks in LeakCanary | the three counters, `leaks.db` |
| R5 | Call log after each of the above | One entry per call, marked video where video was negotiated, the right direction and duration | call log DB |

---

## 7. Method — per finding, in this order

1. **Reproduce** on the TC15 with the SIP trace on. Record the exact steps and the
   artefact from §5 that shows the defect. If it does not reproduce twice, it is not a
   finding yet.
2. **Root-cause** from source (`path:line`) and from the trace. Name the mechanism, not the
   symptom. If the cause is in PJSIP or FreeSWITCH, say so and say which file.
3. **Fix** in the smallest layer that owns the decision (`docs/master-engineering-prompt.md`
   layer rules). Add or extend a JVM test that fails before the fix and passes after.
4. **Rebuild, install, prove** per §3.
5. **Re-verify** the same steps as (1), held ≥ 60 s, with the same artefact now showing the
   pass condition.
6. **Record** it as a row in the deliverable table.

**DECIDE before touching code:** which tier of §1 the finding is at, and whether the fix
changes the SIP wire (a re-INVITE, an SDP line) — if it does, the FreeSWITCH log for the
Call-ID is part of the evidence, both before and after.

---

## 8. Constraints and honesty

- One device on USB. Everything marked *Human* needs Rahul on a second handset; ask for
  it with the exact steps, and until it happens the row is **UNRUN**.
- The far end of 9196 is an echo: it proves the transport and the renderer, not a human
  peer's behaviour (a peer's re-INVITE, a peer's hold, a peer's rotation). Say which.
- Never install a `.so` compiled from downloaded C, change FreeSWITCH config, or forget a
  Wi-Fi network on the handset without asking first. Config changes to FreeSWITCH are
  reversible edits under `/usr/local/freeswitch/etc/freeswitch`, and a `.bak.<date>` copy
  is made first.
- `detekt` caps `RealPjsipCoreGateway` and `PjsipSipEngine` at `LargeClass`: new pure
  logic goes to file level or a new file, not into either class.
- Rahul may be driving the handset while a test runs; read the log before blaming the code
  for a Back press or a speaker tap the script did not send.

---

## 9. Deliverable

1. A branch off `main`, one commit per fix, each message naming the defect and the evidence.
2. `docs/HANDOFF.md` brought up to date: what was verified, what was fixed, what is open.
3. A table in the final report:

   | Issue | Root cause (`path:line`) | Fix | Evidence (before → after) | Verified (device, date, duration) |
   |---|---|---|---|---|

4. The §6 matrix with every row marked **PASS**, **FAIL → fixed (row above)**, **FAIL →
   open**, or **UNRUN (reason)**. Rows are never left blank.
5. A plain list of what could not be tested and why.
