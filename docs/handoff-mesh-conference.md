# Prompt: make 4-party mesh conferencing stable

Paste this whole file as your first message. Branch `fix/video-call-verification`.
Last commits: `edeb9f0a`, `0c173c51`. 1683 JVM tests green.

## The task

**Fix 4-party video conferencing and make it stable and accurate. Break nothing else.**

3-party mesh is proven working on hardware. 4-party is not. Everything below was
measured, not guessed — do not re-derive it.

## What the architecture is now (do not change it)

A conference is a **full mesh**, client-side PJSIP/pjmedia. **Never dial extension 3000.**

- Every participant holds a leg to every other. Every device draws the same grid from
  its own calls. The "host" is only whoever pressed Merge (the *focus*).
- **Nobody relays.** `ConferenceMix.wanted(relay=false)` and
  `VideoMix.wanted(compose=false)` return no links in a mesh: each device wires its mic
  and camera to each leg, and each leg to its own speaker and its own tile. pjsua's own
  per-call `START_TRANSMIT` and decoder→window links do all the work. This is what makes
  duplicate audio/video impossible — **do not reintroduce relaying**.
- **The roster is the whole protocol.** The focus announces RFC 4575 with
  `<coralx-topology>mesh</coralx-topology>`; every device reconciles its legs against it
  (`ConferenceMesh.plan` in `:domain`). Join, leave, removal and recovery are one rule.
- **Glare:** the lower `user@host` dials. Both ends compute the same, so a pair opens
  one dialog.
- A member whose leg **to the focus** ends leaves and drops its other legs. That is how
  End and `removeFromConference` reach everybody.

Key files: `domain/engine/ConferenceMesh.kt`, `PjsipSipEngine` (search `mesh`),
`ConferenceMix`, `VideoMix`, `ConferenceBridge`, `VideoConferenceBridge`,
`ConferenceInfoWriter/Parser`, `feature/calls/ConferenceRoster.kt`.

## Proven working — do not break

1. **3-party mesh**, 1001/1005/1000: 6 FreeSWITCH channels, 0 to 3000, "Conference
   call" + badge + Participants list + a 2-tile grid with correct extensions **on every
   device**.
2. **Per-member End** on the focus only (red button per row, none against "You"), and it
   propagates — the removed member drops its legs to the others.
3. **Hold/Unhold**, conference and 1:1: fans across the mix, camera released and
   re-acquired, picture returns.
4. A refused hold is now settled (`PendingHold`), and `Resuming` + remote hold is
   `Held(BOTH)`.

## The 4-party defect — where to start

Measured 2026-09-24 17:52 on 1000 (focus) + 1001 + 1003 + 1005, all video:

- Every tile black on every handset, **every self-view black** on 1000 and 1005.
- CPU: 1000 = **101 %**, 1005 = **105 %**, 1001 = **335 %** of one core (sum
  `/proc/<pid>/task/*/schedstat`, never the pid's own). ~100 % means the video pipeline
  is *not running* on that device; 335 % is three encodes + three decodes, near
  ADR-009's 400 % budget.
- The focus logged `Mesh conference: 0 member(s) drawn from their own streams` then
  `Camera released`.

**Cause found and fixed in `edeb9f0a`** — `videoMixable` enforces canvas rules (min two
sources, four-source `vid_conf` ceiling) that a mesh has no business obeying; with the
second camera still negotiating it returned empty, `dropVideoForMix` re-INVITEd video off
every leg, and `CameraPolicy` released the camera. **This fix is committed but NOT yet
tested on hardware.** Start by re-running the 4-party case against it.

If 4-party is still unstable after that, the next suspects, in order:

1. **CPU.** A 4-party mesh costs every handset 3 encodes + 3 decodes — what the star
   cost its host alone. 1001 measured 335 %. If this is the wall, the honest fix is a
   stated participant ceiling for *video* mesh (audio can stay larger), enforced where it
   can be reported, not a silent drop.
2. **Encoder starvation on the SM-E236B** (`c2.android.avc.encoder` stops yielding input
   buffers). Pre-existing and documented. **Do not re-add a mid-call encoder swap** — it
   was tried and stranded the device.
3. **The M14 (Android 15) rendering black remote tiles while RX counters count.** Seen
   intermittently at 3-party too, recovered by itself, never root-caused. Suspect
   MediaCodec/VP8 on Android 15. Accounts persist `codecs`, so old accounts still offer
   **VP8 first** — check what actually negotiated before blaming the mesh.

## Traps that cost hours — read these

- **FreeSWITCH does not forward an in-dialog MESSAGE.** `mod_sofia` terminates the roster
  and re-originates it out of dialog (fresh `Call-ID`, no `To` tag,
  `X-FS-Sending-Message`), so pjsua delivers it to **`Account.onInstantMessage`**, never
  `Call.onInstantMessage`. Both are wired; the account one matches the call by `From`.
- **FreeSWITCH is a B2BUA and drops custom INVITE headers**, so `X-Coralx-Conference`
  does not survive. Auto-answer therefore also accepts a caller the roster says this
  device is *awaiting*. Nothing may depend on that header alone.
- **Open the mesh before publishing `mixed`.** Publishing `mixed` is what triggers the
  roster; opening the mesh after sent the first roster — the only one a 3-party
  conference sends — with no mesh marker.
- **Install on every handset.** A stale build on one device looks exactly like a mesh
  bug: 1003 sat on the 16:49 APK, never saw a roster, and answered its mesh leg as an
  ordinary second call (holding the first). Check `dumpsys package com.whatsappv2 |
  grep lastUpdateTime` on all four before believing any result.
- The in-call chrome auto-hides: **reveal and tap in one `adb shell` command** or the tap
  misses. Verify typed digits with a screenshot — the dialler silently accumulates them.
- TC15 at `192.168.137.142` accepts no `adb input` (app works, taps do nothing).
- Never `pkill` Gradle.

## The kit

| | |
|---|---|
| Devices | 1001 M14 `RZCW501E52K` (A15) · 1005 SM-E236B `.196` · 1000 TC15 `.43` · 1003 TC15 `.142` (no input) |
| Server | FreeSWITCH `192.168.137.123`, `/usr/local/freeswitch/bin/fs_cli` — **re-read the IP, it moves** |
| Build | `./build.sh --reuse-native`, then `adb -s <dev> install -r app/build/outputs/apk/debug/app-debug.apk` |
| Tests | `./gradlew -Ppjsip.native=false :domain:test :data:sip:testDebugUnitTest :feature:calls:testDebugUnitTest :test:arch:test -x :pjsip:api:generatePjsua2Bindings` |
| Proof | `fs_cli -x 'show channels'` — a 4-party mesh is **12 channels**, 0 to 3000. Screenshot every handset. Motion, not eyeballs. |

## The rule

Find it, reproduce it, root-cause it, fix it, re-verify it on the handsets. A defect
reported without a fix is half a job; a fix without a device re-run is a guess. Report
honestly what you did not manage to verify.
