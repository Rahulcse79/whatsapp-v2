# Prompt: the call screen shows the wrong call, and the banner lies about a conference

Branch off `main` (clean, PR #17 merged `docs/native-mandate-design` into it). Work on
`fix/second-leg-call-screen`. **Do not push** — Rahul pushes by hand.

---

## 1. What was observed, on hardware

TC15 `24143524701316`, ext 1002 on the local FreeSWITCH, 2026-09-12 12:01.

Call 9196 (echo). Press Home, reopen the app — it lands on the Dialler — dial 9198 and
press Call. Two calls are now up. The screen then reads:

| element | shows | truth |
|---|---|---|
| banner | `9198 is on hold — tap to swap` | 9198 is the **active** call |
| identity | `9196` | the screen is pointed at the **first** call |
| hold button | `Resume call` | correct for 9196, which the stack held |

Press **Merge**. pjmedia builds the bridge correctly —

```
conference.c  Added port 1 (sip:9196@192.168.2.196), port count=3
conference.c  Port 0 (Android JNI) transmitting to port 1 (sip:9196…)
conference.c  Port 1 (sip:9196…) transmitting to port 2 (sip:9198…)
conference.c  Port 2 (sip:9198…) transmitting to port 1 (sip:9196…)
PjsipGateway  Mixing 2 call(s) on this device
```

— the Merge button correctly becomes `2 calls merged / Merged`, and the banner **still**
says `9198 is on hold — tap to swap` about a member that is passing audio both ways. The
banner's parent `View [32,212][688,308]` is `clickable="true"` in the accessibility dump,
so the user can tap it and tear down the conference they just built.

## 2. Two independent root causes — fix both

**D1 — `CallActivity` ignores `onNewIntent`.** It is `android:launchMode="singleTask"`
(`AndroidManifest.xml:147`). `AppNavHost.kt:67` starts it with the **new** call's id every
time a route places a call. The instance already exists, so the system delivers to
`onNewIntent`, which `CallActivity` does not override — `onCreate` never re-runs, the
`callId` captured at `CallActivity.kt:74-75` stays the first call, and `CallRoute` keeps
watching it. This is why the screen is on the wrong leg.

`CallViewModel` already re-points for the two paths that were fixed before —
`respondToSecondCall` (`:469`) and `swapTo` (`:510`) — and an outgoing second leg goes
through neither.

**D2 — `otherCalls` is not filtered to held calls.** `CallViewModel.kt:251` is
`state.calls.filterNot { it.callId == callId }` — *every* other call — while its own KDoc
at `CallUiState.kt:246-252` says "Every other call this app is **holding** … Held calls".
`CallScreen.kt:342` then renders `HeldCallBanner` for `otherCalls.firstOrNull()`
unconditionally. Conference members are `CONNECTED`, so D1 alone does not fix the merged
case: fix D2 or the banner lies about every conference regardless of which leg is shown.

`CallPhase` already distinguishes `ON_HOLD`, `HELD_BY_REMOTE`, `HELD_BY_BOTH`, and
`CallUiState.Active.mixedCallCount` / `EngineState.mixed` already say who is mixed. Use
what exists; do not invent a second state model.

## 3. Also in scope

**D3 — there is no "Add call" control.** In-call controls are Mute, Speaker, Hold, Keypad,
Video, Flip, Transfer, Record, End, plus Merge once two calls exist. The only route to a
second outgoing leg is Home → reopen → Dialler, which no user will find, so a
user-initiated conference is effectively unreachable (ADR-009 assumes it is reachable).
Add it to `CallSecondaryControls`, opening the dialler for a second leg. Gate it the way
`MergeButton` is gated — present only when it means something.

**D4 — dangling KDoc.** `swapTo`'s doc block at `CallViewModel.kt:472-478` is followed
immediately by `merge()`'s doc block; `swapTo` itself is at `:505`. The comment documents
the wrong function.

**Out of scope, say so rather than doing it:** `EncryptedRecordingStore.init {
sweepAbandoned() }` (`:75`) still does `listFiles()` + `delete()` on the injecting thread,
which is main. Recording playback still does not exist.

## 4. Standards

- detekt caps that bite here: `LargeClass` on `CallViewModel`, `LongMethod` 60,
  `ReturnCount` 3, `TooManyFunctions` 20, import ordering, no unused imports. New pure
  logic goes to **file level** or a new file.
- Arch rules: no `Color(0x…)` / `N.dp` outside `:core:designsystem` (rule 8), every
  design-system component previewed light **and** dark (rule 7), no raw `Thread(` (rule 5).
- Gate: `./gradlew testDebugUnitTest test detekt -x :pjsip:api:generatePjsua2Bindings -x
  :pjsip:buildPjsua2Native` green.
- **Every regression test must be verified by reverting the fix and watching it fail.**
  A test that passes against the bug is not a test.
- Commit small, evidence in the message, in the voice of the existing commits.

## 5. Then retest on the handset

`./build.sh --install` (`--reuse-native` is fine — no `config_site.h` change here).

1. Call 9196. Home → reopen → Dialler → 9198 → Call.
   **Expect:** the screen is on **9198**, and the banner names **9196**.
2. Press Merge. **Expect:** `Mixing 2 call(s)`, both bridge links in the log, button reads
   `Merged`, and **no held banner at all**.
3. Hang up one leg. **Expect:** the screen follows the survivor, no stale banner.
4. Use the new Add call control for leg 2 and repeat 1–2.
5. After each: `adb logcat -d | grep -E "Fatal signal|FATAL EXCEPTION|ANR in|Skipped [0-9]{3,} frames"`,
   `pidof com.whatsappv2` unchanged, `dumpsys telecom | grep -c "Call id"` back to 0,
   LeakCanary **0 APPLICATION LEAKS**.

Report which of "compiles / registered / connected on the wire / heard" each claim is.
"Heard" cannot be automated — list it as owed for a human.
