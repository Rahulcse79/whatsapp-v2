# Handoff — video calling hardening (whatsapp-v2)

Continue this work. Everything below was measured on hardware, not assumed.

## Environment (re-verify, never assume)
- Repo `/Users/rahulsingh/Desktop/whatsapp-v2`, branch **`fix/video-call-hardening`** (off
  `test/4party-video-conference`). **All work is uncommitted** — see `git status`.
- FreeSWITCH runs locally. `fs_cli` is at `/usr/local/freeswitch/bin/fs_cli` (NOT on PATH).
  Config lives in `/usr/local/freeswitch/etc/freeswitch`. **The Wi-Fi IP changes between
  sessions — re-read it from `sofia status` every time** (was 192.168.2.194).
- Handsets: **1001 = SM-M146B, the only one on USB** (drive this one). 1004 = TC15,
  1005 = SM-E236B — a human must answer those.
- Test extensions: **9196** = audio+video echo (answers instantly, no human needed),
  9198 tone, 9199 unrouted. Conference room **3000** -> `conference 3000@whatsapp-video`.

## Build rules (each of these cost hours before)
- Build ONLY with `./build.sh --reuse-native --install`. Never `./gradlew assemble` (no NDK env).
- **Never `pkill`** broadly — it poisons `~/.gradle/caches/build-cache-1` and silently drops
  `Hilt_*` classes. Use TaskStop.
- `./build.sh` run under `nohup ... &` reports exit 0 even when Gradle FAILED. Always grep the
  log for `BUILD SUCCESSFUL`.
- **Prove the build on the device before believing any test**: compare `md5 -q` of
  `app/build/outputs/apk/debug/app-debug.apk` against `adb shell md5sum <pm path>`, and
  `dexdump -d` the APK for a symbol you changed (`dexdump` is in
  `~/Library/Android/sdk/build-tools/36.0.0`).
- Hold every call **>=60s** before calling it good (SIP timers fire ~32s in).
- Call chrome **auto-hides after 4s** (`CHROME_IDLE_MILLIS`). Re-dump the view hierarchy and
  tap by content-desc; a stale coordinate just silently reveals the chrome instead.

## How to measure aspect ratio (the key technique)
```
adb shell dumpsys SurfaceFlinger | grep -A2 'SurfaceView\[com.whatsappv2/com.whatsappv2.call.CallActivity\]@0(BLAST)'
```
`toDisplayTransform` scale x and y must be **equal**. Unequal x/y *is* the picture being
stretched, definitionally. Compare against the decoded size the app logs
(`adb logcat | grep "Video is"`). Do not eyeball screenshots — a dark room reads as a
letterbox bar and sent me down a blind alley.

## DONE and verified on hardware — session of 2026-09-21 (later)

4. **Registration died in deep sleep (item A).** Every registration timer ran on a clock
   that stops in suspend: the coordinator's retries were coroutine `delay`s
   (`CLOCK_MONOTONIC`) with a 30-minute ceiling, and PJSIP's own re-REGISTER runs on its
   `pj_gettickcount` heap, which is the same clock. Fix, in `data/sip/.../network/`:
   - `WakeTimer` seam; `AlarmWakeTimer` = `AlarmManager.setExactAndAllowWhileIdle`
     (`ELAPSED_REALTIME_WAKEUP`, falls back to inexact and says so), delivered to a
     manifest receiver `WakeTimerReceiver` that takes a 10 s partial wake lock.
   - A **keepalive** REGISTER at half the granted expiry (90 s for 180 s), armed by
     `RegistrationRecoveryCoordinator` while an account is `Registered`, re-armed from its
     own firing (an equal `Registered` never re-emits on a StateFlow).
   - Retries on the same timer; `RegistrationBackoff.DEFAULT_CEILING` 1800 s → **300 s**.
   - `DeviceWakeMonitor` / `BroadcastWakeMonitor`: SCREEN_ON, USER_PRESENT, DOZE_EXIT and
     app-foreground each re-register now (pending retry runs immediately; a healthy
     registration is refreshed, throttled to one per 30 s per account).
   - Manifest: `SCHEDULE_EXACT_ALARM` + `WAKE_LOCK` in `:data:sip`. The app is already on
     the doze whitelist on the M14 (`dumpsys deviceidle whitelist`), which is why the alarm
     shows `exactAllowReason=allow-listed window=0`.
   Evidence, M14, `dumpsys deviceidle force-idle` for 6 min: `Alarm keepalive/… fired`
   at 04:28:45, 04:30:15, 04:31:45, 04:33:15 (90 s to the second), and
   `sofia status profile internal reg` EXP for 1001 advanced on every one
   (04:32:44 → 04:34:14 → 04:35:44 → 04:37:14) — the lease never lapsed. Leaving idle:
   `Re-registering … (device woke (DOZE_EXIT))`. Registrar frozen with `kill -STOP`:
   408 after 32 s → `retry 1 in 1s` → `Alarm retry/… fired` → re-registered the moment
   FS was `kill -CONT`ed. 22 coordinator tests (9 new), `:data:sip` 300/300 green.
5. **Mid-call resolution change left the picture stretched (item B).** Root cause: the
   renderer window's `disp_size` (what `videoWindow.info.size` reads) is applied lazily on
   the renderer's next `put_frame` (`vid_port.c: handle_format_change`), and the app's
   `FMT_CHANGED` callback runs before that — twice, and reads the old size both times.
   Fix: `decodedVideoSize` now reads `getStreamInfo(idx).vidCodecParam.decFmt`, which the
   stream updates *before* it publishes the event, with the window as fallback, plus one
   re-read 250 ms later for the preview half. Evidence: 9196 video call logs
   `Video is 1088x612 in` 220 ms after the CIF placeholder; SurfaceFlinger
   `scale x=3.6075 y=3.6078`. (The old code logged `352x288` and stayed there on the
   1001→1004 call.)
6. **Video-call audio "not clear" — the route.** Every video call ran on
   `ActiveEarpieceRoute`, the app itself sending `USER_SWITCH_EARPIECE` at
   "Call audio started": `CallAudioCoordinator.begin` seeded `chosenRoute` from
   `CallControls.audioRoute`, whose *default* is EARPIECE, so `routeAfterDeviceChange`
   kept it as a "choice" on every call — the Settings preference was dead too. Fix:
   `AudioRoutePolicy.preferredRoute(hasVideo)` → SPEAKER for a video call (headset still
   wins, explicit EARPIECE preference still honoured); the default route is no longer read
   as a choice; an escalation to video mid-call re-derives the route. Evidence:
   `turning speaker phone true` 0.9 s into the 9196 video call; the in-call chrome reads
   "Turn off speakerphone"; an audio-only call still goes to the earpiece.
   **What is left of "not clear" is the codec**: a video call keeps FreeSWITCH in the media
   path and FS's `global_codec_prefs=PCMU,PCMA,VP8` makes it G.711 at 8 kHz, while an
   audio-only call bypasses FS and gets Opus/Lyra. The transport is clean — new
   end-of-call media statistics (below) show 0 % loss, 3 ms avg jitter, 12 ms RTT on the
   71 s test call. The fix for that is server-side: `mod_opus` on the local FS
   (`libopus` 1.6.1 is installed via MacPorts; the FS headers are in
   `/usr/local/freeswitch/include/freeswitch`; mod_opus.c for v1.10.11 is three files).
   Not done here — building and loading a codec into the deployed FS is the user's call.
7. **Media statistics at the end of every call.** `PjCall.snapshotMediaStatistics` every
   15 s while media is up and once more before a local hangup; logged as
   `Media statistics for <call> (last snapshot)` at DISCONNECTED. Not dumped *at*
   DISCONNECTED: pjsua deinits the media before that callback on both hangup paths
   (measured: "audio deactivated").
8. **Polish (item E).** Dial-pad keys have descriptions (`1`…`9`, `Star`, `0`, `Pound`)
   and `Role.Button`; bottom tabs describe as "Chats"/"Calls" (uiautomator now sees
   them); the dialler card reads "Extension 1001 on 192.168.2.194"; chrome auto-hide is
   6 s and honours the platform's accessibility "time to take action" (already never hid
   with touch exploration on).

## DONE and verified on hardware — earlier
1. **Remote video was stretched on every video call.** `Modifier.size()` honours the parent's
   constraints, so `VideoLayout.cover()`'s deliberately oversized box was clamped to screen
   width and the GL renderer (which never letterboxes) stretched the frame to fill it.
   Fix: `requiredSize` in `CallVideo.videoBounds`.
   Evidence — 1:1 `scale x=0.9926 y=3.6078` (3.6x vertical stretch) became
   `x=3.6075 y=3.6078` with `tx=-1422` (centred). Conference `x=1.5 y=1.725` became
   `x=1.5 y=1.5`, i.e. **1080x1920 centred** — which also closes the old
   "conference renders 545x1913 half-width" bug.
2. **No device knew it was in a conference.** Fix: `PjsipSipEngine.markConferenceIfRoom()`
   marks a call a conference when its remote address is the configured `ConferenceRoom`
   (already DI-provided, extension 3000). Must be called **after** `store()` — `store` does
   `current.copy(...)` and would overwrite the flag.
   Verified: log `is the conference room: marking it a conference`, UI shows "3000",
   duration, and a Participants row; scaling switches to Fit.
   Why not RFC 4579 `;isfocus` — all measured, all negative: FreeSWITCH's 200 OK Contact is
   `<sip:3000@...;transport=udp>` with no isfocus; `sip_contact_params=isfocus` is set but
   never reaches a response; `mod_sofia` only emits `;isfocus` on the *subscription* path;
   and no conference NOTIFY arrives unsolicited (only `Event: message-summary`).
3. **VP8/103 removal still holds.** App offer is `m=video ... RTP/AVP 102 99`; FS picks
   pt 102. Keep `ROSTER_SUBSCRIBE_ENABLED = false` — the in-dialog SUBSCRIBE SIGSEGVs in
   mod_evsub ~32s later.

## OPEN — do these next

### C. 1004 and 1005 still run the OLD APK
The 1004 leg negotiated `rtp_video_pt: 103` — MediaCodec VP8, whose decoder renders **black**
on these handsets. The fix is only installed on 1001. **Install the new APK on 1004 and 1005**
before judging any multi-device video result.

### D. Bridge participant list
FreeSWITCH serves RFC 4575 only on SUBSCRIBE, and pjsua2's in-dialog
`Call.sendRequest("SUBSCRIBE")` SIGSEGVs. A real live roster needs
`pjsip_evsub_create_uac` exposed through SWIG (full native rebuild, no `--reuse-native`).
`ConferenceInfoParser.kt` + its test already exist and are ready for the NOTIFY.
Until then the UI honestly says "This bridge does not publish a participant list" — the user
asked to **show extensions where possible**; the local-mix path (`localMixRoster`) genuinely
knows its members, and `CallExtras.kt` already prefers `uri.user` (the extension) over the
full SIP URI. Never show a count that can be wrong.

### E. Accessibility / UI polish — done (see 8 above); the remaining item
- A TalkBack pass on the in-call screen has not been made by ear; only the descriptions
  were verified through a uiautomator dump.

### F. Audio codec on FreeSWITCH-mediated calls (from 6 above)
- Build and load `mod_opus` on the local FS, set `global_codec_prefs=OPUS,PCMU,PCMA,VP8`
  (vars.xml) and check `conference.conf.xml`'s `whatsapp-video` profile `rate` before
  judging conference audio. Then re-measure with the media statistics log.

## Test matrix still UNRUN
3-device conference (Add -> Merge -> bridge, roster, max-members=4 and a clean 5th refusal,
and that a REFERred-in handset shows "conference"); video ON/OFF x10; camera flip x10;
hold/resume with video; self-preview drag/resize/rotate; background/foreground; rotation;
incoming call during a video call; Wi-Fi drop/restore; permission denial paths; 20x join/leave
stress with PSS/socket/thread baselines; LeakCanary at 0 leaks.
I got as far as: 1:1 video both ways, conference-of-one, no-answer 480 with clean camera
release, a >70s hold with no crash; and on 2026-09-21: 6 min forced deep idle with the
lease kept, registrar frozen/unfrozen recovery, 71 s video call to 9196 (speaker, correct
size, 0 % loss), and an audio-only call still on the earpiece.
**Everything else is untested — do not report it as working.** In particular the
video-escalation-mid-call route change (`CallAudioCoordinator.follow`) is covered by the
pure policy tests only; it needs a second human handset.

## Deliverable expected
Branch + diff, and a table: Issue | Root cause | Fix | Evidence | Verified. State plainly what
could not be tested and why.
