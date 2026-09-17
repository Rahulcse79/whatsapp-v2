# Work order — a merged video conference drops the third leg

**Status:** root cause **confirmed on the wire** 2026-09-15 16:33; fix applied, awaiting a
three-handset retest.
**Reported:** 2026-09-15, three TC15 handsets (1002, 1003, 1004) on the Mac's FreeSWITCH.

---

## 1. The observed defect

1. 1004 places a video call to 1002, and a second video call to 1003.
2. 1004 presses **Merge**.
3. On **1003** a dialog appears: *"Turn on video? Extension 1003 would like to add video
   to this call. Your camera will only start if you accept."* — over a call screen reading
   **"On hold by the other party"**.
4. Tapping **Turn on video** disconnects the call.

Two things are wrong, and they are separate faults. The dialog should never have appeared;
and answering it should never drop a call.

## 2. Verified baseline — what the code actually does

Read from source, not assumed. Line numbers are current at the time of writing.

| Fact | Where |
|---|---|
| Merge resumes every held member before mixing | `PjsipSipEngine.mixCalls` → `resumeHeldForMix(...) { setHold(it, held = false) }` (`PjsipSipEngine.kt:1245`) |
| Resume sends a re-INVITE built from the call's **current** setting | `RealPjsipCoreGateway.resumeCall` → `call.reinvite(call.info.resumeParams())` (`RealPjsipCoreGateway.kt:1180`) |
| `resumeParams()` reuses `CallInfo.setting` verbatim and only adds `PJSUA_CALL_UNHOLD` | `RealPjsipCoreGateway.kt:2335-2339` |
| So on a **video** call the resume offer still carries `m=video` | follows from the two rows above — `setting.videoCount` is 1 for a video call |
| A re-INVITE offering video is treated as an **escalation** whenever the call has no *active* video right now | `PjCall.onCallRxReinvite`: `if (info.remVideoCount > 0 && !info.hasActiveVideo())` (`RealPjsipCoreGateway.kt:1732-1738`) |
| A held call's video stream is **not** active | PJSIP reports hold as `PJSUA_CALL_MEDIA_LOCAL_HOLD` / `REMOTE_HOLD`, not `ACTIVE`; `isLive` treats those separately at `RealPjsipCoreGateway.kt:2013-2015` |
| An escalation is **deferred** — the far end gets no answer until the user taps | `prm.isAsync = true` (`RealPjsipCoreGateway.kt:1735`) |
| Accepting answers the deferred transaction with a freshly built setting | `respondToVideoUpdate` → `call.answer(callParams(accept)…)` (`RealPjsipCoreGateway.kt:1279-1286`) |
| Local mixing is **audio only, by design** | `SipConferenceController.mixCalls` KDoc: *"it is **audio only** — ADR-009's gate measured video at ~135 % of a core per stream, which does not fit, so a video conference remains a call to the bridge (ADR-003)"* (`SipEngine.kt`) |

## 3. Root cause — confirmed

Captured with `sofia global siptrace on` plus `adb logcat` on 1003. The decisive sequence,
from FreeSWITCH's own trace:

```
16:33:35.744  send INVITE cseq=120181312  -> 1002      (re-INVITE, with SDP)
16:33:35.843  recv 200 OK cseq=120181312  <- 1002      answered
16:33:37.154  send INVITE cseq=120181313  -> 1002      (re-INVITE, with SDP)
16:33:38.156  send INVITE cseq=120181313  -> 1002      RETRANSMIT — no reply
   … 13 seconds of silence …
16:33:51.25   send BYE    -> 1002
              Reason: SIP;cause=488;text="Incomplete offer/answer"
16:33:51.26   Hangup sofia/internal/1002 [CS_HIBERNATE] [MANDATORY_IE_MISSING]
16:33:51.27   Hangup sofia/internal/1004 [CS_HIBERNATE] [MANDATORY_IE_MISSING]
```

**1002 never answered the second re-INVITE**, because its app deferred it as a video
escalation and put a dialog on screen. `prm.isAsync = true` means "I will answer this
later", and the app answered only when a human tapped — but FreeSWITCH abandoned the
transaction after 13.5 s and tore the leg down, which took the bridged 1004 leg with it
and collapsed the conference.

So the hypothesis in §2 was right about the misclassification and wrong about which leg
died: the prompt appears on one handset while the call that drops is *another* handset's,
which is why this was hard to attribute from the screen alone.

Candidate (b) is excluded — the offers and answers that *were* exchanged carried SDP
normally. Candidate (c) is excluded — the `BYE` originates at FreeSWITCH with an explicit
SIP reason, not at 1004.

## 4. The fix

1. **Never defer a re-INVITE.** `prm.isAsync` is no longer set. The stack answers
   immediately, keeping the call exactly as it is — video declined for a call that had
   none, accepted for one that did. No SIP transaction ever waits for a person.
2. **Accepting is a new offer, not a late reply.** `respondToVideoUpdate(accept = true)`
   now sends *our* re-INVITE via the same path as the Video button. Declining sends
   nothing, because the call is already what the user chose to keep.
3. **Stop misreading a resume as an escalation.** `VideoOfferClassifier.isEscalation`
   takes a third input — whether the call has *ever* carried video — so hold/resume,
   bridge re-syncs and session refreshes no longer prompt. Pure and unit-tested.

## 5. Original hypothesis (superseded by §3)

**The dialog is a resume re-INVITE misread as a video escalation.**

`onCallRxReinvite` asks "does the offer have video, and is video not running?" Both are true
for a call that *already had video* and was merely **held** — hold is exactly what makes
video stop being active. The guard was written to exclude session-timer refreshes and the
far end's hold, and it does; it does not exclude the **resume**.

So pressing Merge on 1004 sends 1003 an unhold offer carrying `m=video`, and 1003 shows a
prompt for video it already had, on a call it can see is on hold.

**The drop is the second fault**, and the hypothesis to test is that the deferred re-INVITE
is answered too late or answered wrongly. Candidates, to be separated by trace:

- **(a) Transaction timeout.** `isAsync` leaves 1004's re-INVITE unanswered while the dialog
  sits on screen. A re-INVITE that gets no final response dies at Timer B (~32 s), and the
  answer then lands on a dead transaction.
- **(b) A mismatched answer.** `callParams(accept)` builds a *new* `CallSetting` rather than
  answering from the offer, so the answer may not correspond to what 1004 offered.
- **(c) The mix itself.** 1004 is bridging in the local audio conference when the video
  answer arrives; ADR-009 never budgeted video there.

These are distinguishable: (a) shows a 408/timeout or a `BYE` from 1004 in the SIP trace
about 32 s after Merge; (b) shows a 4xx/488 or an immediate `BYE` right after the answer;
(c) shows the `BYE` originating on 1004 with no SIP error before it.

### Design question this still forces

ADR-009 says a device-mixed conference is **audio only** and that video conferencing is a
call to the bridge (ADR-003) — which is the `3000` room, now composing a real grid
(`docs/Freeswitch_configuration_docs/conference-video/`). Merging two *video* calls is
therefore not a supported video conference today, and the honest options are:

- **A — Merge drops video, and says so.** On mix, renegotiate each leg to audio and tell the
  user the conference is audio only. Matches ADR-009; no camera on any leg.
- **B — Merge routes to the bridge.** Merging video calls transfers both legs into room
  `3000`, which already composes everybody. This is the only path that yields an actual
  video conference.
- **C — Lift ADR-009's audio-only limit.** Requires re-measuring the CPU gate; out of scope
  for a defect fix.

**A and B are both defensible; the choice is the user's.** The fixes in §5 are needed under
any of them.

## 6. Acceptance criteria

Numbered, binary, each independently checkable.

1. Resuming a held **video** call — by Merge or by the Resume button — raises **no**
   "Turn on video?" prompt on the far end.
2. A genuine escalation (a call established as audio, far end adds video) **still** raises
   the prompt. This must not regress.
3. Accepting a genuine escalation never drops the call: the call remains established and
   media flows both ways.
4. Declining a genuine escalation keeps the call up as audio (existing Task 54 behaviour).
5. A deferred re-INVITE that the user does not answer within the SIP transaction's life is
   answered by the stack rather than left to time out, and the call survives.
6. Merging three legs (1004 + 1002 + 1003) leaves all three established, with no `BYE`
   from any party, for at least 120 s — past the session-timer refresh at 60 s.
7. Whatever §4 decides, the on-screen state after Merge matches what was negotiated: if
   video was dropped, no leg shows a running camera and the screen says the conference is
   audio only.
8. Unit tests cover 1, 2 and 5 at the mapper/gateway seam; they fail against the current
   code.

## 7. Test plan

**Instrumented repro** (the reporter answers the calls; 1003 is on USB):

1. `fs_cli -x "sofia global siptrace on"` — handset-to-handset calls run `bypass_media=true`,
   so FreeSWITCH relays the SDP untouched and the trace shows every offer and answer.
2. `adb logcat -c` on 1003, then capture continuously.
3. 1004 → video call 1002; answer. 1004 → video call 1003; answer. Merge on 1004.
4. Tap **Turn on video** on 1003.
5. Collect: the full SIP dialog for the 1003 leg, and 1003's `PjsipGateway` /
   `PjsipSipEngine` lines around the answer.

**Evidence to extract:** who sent the `BYE` and with what `Reason`; the elapsed time between
1004's re-INVITE and 1003's 200 OK; the `m=` lines of the offer and the answer.

**Regression suite:** `./gradlew test detekt :test:arch:test` green, plus the new cases in AC 8.

## 8. Out of scope

- Lifting ADR-009's audio-only ceiling on local mixing (option C).
- The office server `192.168.80.145`, which refuses TCP 5060 — tracked separately in
  `docs/Freeswitch_configuration_docs/conference-video/office-server-192.168.80.145.md`.
- Marking a dialler-dialled room as a conference in history — no `;isfocus` is advertised;
  tracked separately.
