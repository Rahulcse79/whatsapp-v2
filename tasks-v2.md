# Task Breakdown v2 — UX round

The second round of work, after the 68 tasks in [`tasks.md`](tasks.md) closed. Those came
from [`android-sip-app-prompt.md`](android-sip-app-prompt.md); **these came from the
product owner as a list of ten changes**, so each task below records its origin as
`Owner n` and cites a prompt section (`§`) only where one genuinely constrains the work.

`tasks.md` is not superseded. It stays the record of how the app got built, and the rules
it set — layering, the SIP abstraction, honest state — still bind everything here.

## How to use this file

- Work **top to bottom**. The order is a topological sort, not the owner's order; the
  mapping back to their list is in [Owner's list → tasks](#owners-list--tasks).
- Each task is sized to be finishable in one focused sitting and to leave the repo
  **green** when it is done.
- **`Done when`** boxes are binary. If you cannot tick one, the task is not finished —
  do not move on and do not tick it optimistically.
- Builds run in **CI, not on a laptop** (see README). Push and read the workflow result.
- Four tasks carry a **DECIDE**. Those are real gaps in the request, not rhetorical ones.
  Stop and ask; do not invent a permission, a screen, or a server API. (§13)

**Task format**

```
### Task N — Title
Depends on · Source · Modules
Build:      what to produce
Done when:  binary checks
```

## Phase map

| Phase | Tasks | Outcome |
|---|---|---|
| 10 — Navigation shell | 69–70 | Two tabs become one screen with a dialler on it |
| 11 — Correctness | 71–73 | History actually refreshes; permissions are asked for; a save says so |
| 12 — Call surface | 74–76 | Video from the dialler, icons worth pressing, an honest mute |
| 13 — Sweep & new ground | 77–78 | Latency measured across the app; group calling, dummy first |

## Open DECIDEs

These block the tasks that name them. Each is a question the owner's list did not answer,
and each has a wrong answer that ships as a defect.

| # | Question | Task |
|---|---|---|
| D1 | "Remove the Settings page" — remove the **tab**, or delete the four controls on it? | 69 |
| D2 | Which "Accounts" permission? `GET_ACCOUNTS` reads *device* accounts this app never touches. | 72 |
| D3 | First-run permission wall reverses a recorded decision. Confirm the reversal. | 72 |
| D4 | A group has no server to be created on. Confirm the page is local-only. | 78 |

---

# Phase 10 — Navigation shell

### Task 69 — Settings and Accounts move behind a top-right Settings icon
**Depends on:** nothing · **Source:** Owner 4 · **Modules:** `:app`, `:feature:accounts`, `:feature:settings`

**DECIDE (D1) before writing code.** "Remove the Settings page" reads two ways. The
reading taken here is *remove the Settings **destination***: the four controls on
`SettingsScreen` — DTMF mode, default SRTP policy, preferred audio route, SIP trace — are
working functionality wired to `:data:settings`, and deleting them would remove features
nobody asked to lose. If the owner meant delete them, this task changes shape entirely.
Confirm before starting.

Build:
- Drop `ACCOUNTS` and `SETTINGS` from `AppDestination`
  ([`AppDestination.kt:26`](app/src/main/kotlin/com/whatsappv2/ui/navigation/AppDestination.kt:26)).
  They stay routes in the graph; they stop being tabs.
- A `Settings` action in the top-right of the main screen's `TopAppBar`, opening a screen
  that holds **both** — app settings and the SIP account list. `AccountsScreen` already
  has a `TopAppBar` with no `actions` slot
  ([`AccountsScreen.kt:98`](feature/accounts/src/main/kotlin/com/whatsappv2/feature/accounts/list/AccountsScreen.kt:98));
  the main screen needs one adding.
- Keep the existing routes reachable: `AccountEditorRoute`, `AccountDetailRoute` and the
  `account-detail/{accountId}` deep path in
  [`AppNavHost.kt:54–98`](app/src/main/kotlin/com/whatsappv2/ui/navigation/AppNavHost.kt:54)
  must all still resolve. A tab going away is not a route going away.
- `AppDestination.START` currently returns `DIALER`
  ([`AppDestination.kt:32`](app/src/main/kotlin/com/whatsappv2/ui/navigation/AppDestination.kt:32)).
  Task 70 moves it; leave it valid here so the repo stays green between the two.

Done when:
- [x] The bottom bar shows no Accounts tab and no Settings tab
- [x] A Settings icon in the top-right opens a screen offering both accounts and app settings
- [x] Every route in `AppNavHost` still resolves, including `account-detail/{accountId}`
- [x] No control from `SettingsScreen` was lost: DTMF, SRTP, audio route and SIP trace are
      all still reachable and still write through `SettingsViewModel`
- [x] `:feature:accounts` and `:feature:settings` still do not depend on each other —
      `:app` composes them, per the layering rule (§4.1)
- [x] `MainActivityTest` and any `AppRoot` test that asserted four tabs is updated, not deleted

---

### Task 70 — Remove the Dialer tab; a floating dialler button on Calls
**Depends on:** 69 · **Source:** Owner 1 · **Modules:** `:app`, `:feature:history`, `:feature:dialer`

After Task 69 the bar holds Dialer and Calls. This task removes Dialer, which leaves one
item — so the `NavigationBar` in
[`AppRoot.kt:45`](app/src/main/kotlin/com/whatsappv2/ui/AppRoot.kt:45) goes with it. A
one-tab bottom bar is chrome that navigates nowhere.

Build:
- Drop `DIALER` from `AppDestination`; set `START` to `HISTORY`.
- A `FloatingActionButton` at the bottom-right of the Calls screen that opens the dialler.
- **The FAB's click leaves the module.** `:feature:history` may not call into
  `:feature:dialer` — [`AppNavHost.kt:23–25`](app/src/main/kotlin/com/whatsappv2/ui/navigation/AppNavHost.kt:23)
  states why, and `:test:arch` enforces it. So `HistoryRoute` takes a new
  `onOpenDialer: () -> Unit` alongside its existing `onCallPlaced`, and `:app` decides
  what that opens.
- The dialler becomes a modal surface over Calls (bottom sheet or its own route). Either
  is fine; what is not fine is losing `DialerScreen`'s existing state — the input, the
  selected account, recents and contacts must survive a dismiss and reopen the way the
  tab's `saveState`/`restoreState` used to give them for free.
- The FAB must not sit on top of the last row. Give the list bottom content padding equal
  to the FAB plus its margin.

Done when:
- [x] No Dialer tab, and no bottom bar at all
- [x] The app opens on Calls
- [x] A FAB at the bottom-right of Calls opens the dialler, and dismissing it returns to Calls
- [x] Placing a call from the dialler still opens `CallActivity` through `onCallPlaced` —
      one call screen, reached one way (Task 39's rule)
- [ ] Typed input and the chosen account survive dismiss → reopen
- [x] The last history row is fully readable with the FAB on screen
- [x] `:test:arch` still passes: no `:feature:history` → `:feature:dialer` dependency

---

# Phase 11 — Correctness

### Task 71 — The call history does not auto-refresh. Make it.
**Depends on:** 70 · **Source:** Owner 3 · **Prompt refs:** §5.2 · **Modules:** `:feature:history`

**This is a real defect, and two comments in the repo currently deny it.**
[`HistoryViewModel.kt:47`](feature/history/src/main/kotlin/com/whatsappv2/feature/history/HistoryViewModel.kt:47)
says "The store's change signal invalidates the paging source", and
[`CallLogPagingSource.kt:25`](feature/history/src/main/kotlin/com/whatsappv2/feature/history/CallLogPagingSource.kt:25)
says "`CallLogRepository.changes` invalidates the source anyway". Neither is true:
`changes()` is implemented in
[`CallLogRepositoryImpl.kt:44`](data/calllog/src/main/kotlin/com/whatsappv2/data/calllog/CallLogRepositoryImpl.kt:44)
and in the fake, and **no production code collects it**. `PagingSource.invalidate()` is
called nowhere in the repository. The list only changes when something else rebuilds it.

Build:
- A failing test first, asserting a new entry appears while the screen is open. It must
  fail against today's code — a test that passes before the fix proves nothing.
- Collect `repository.changes()` in `HistoryViewModel` and invalidate the live
  `CallLogPagingSource`. Hold the current source so there is something to invalidate;
  `pagerFor` builds a new one per filter
  ([`HistoryViewModel.kt:135`](feature/history/src/main/kotlin/com/whatsappv2/feature/history/HistoryViewModel.kt:135)).
- **`changes()` is count-based** — `dao.observeCount().map { }` — so it emits when a row is
  added or removed and **not** when one is updated in place. A call whose duration is
  written when it ends does not change the count. Either widen the signal at the DAO or
  state in the KDoc that in-place updates are not covered; do not leave a comment claiming
  coverage the code does not have.
- Refresh on resume: returning from a call must show that call, not the list as it was.
  Drive it from lifecycle, not a timer.
- Correct the two KDoc comments above to describe what the code does after this task.
- Scroll position survives a refresh. `getRefreshKey`
  ([`CallLogPagingSource.kt:57`](feature/history/src/main/kotlin/com/whatsappv2/feature/history/CallLogPagingSource.kt:57))
  already anchors on the visible row; prove it still holds with an invalidation mid-list.

Done when:
- [x] A test asserts a new call appears in an open list without user action, and that test
      fails on the parent commit
- [x] Ending a call and returning to Calls shows it, with its duration
- [x] Deleting a row, and clearing the log, both still update the list
- [ ] A refresh triggered while scrolled into last week does not jump to the top
- [x] The in-place-update limitation is either fixed or written down; no comment in
      `:feature:history` claims behaviour the code does not have
- [x] No polling loop: nothing in the screen wakes on a timer to check for new rows

---

### Task 72 — Ask for the app's permissions when it is installed
**Depends on:** 69 · **Source:** Owner 2 · **Prompt refs:** §6 · **Modules:** `:app`

**DECIDE (D2) — which "Accounts" permission?** The owner's list names Camera, Contacts,
**Accounts**, Microphone and Notifications. Four of those are modelled in
[`AppPermission`](app/src/main/kotlin/com/whatsappv2/permission/AppPermission.kt:48).
"Accounts" is not, and the plausible candidate is wrong: `GET_ACCOUNTS` reads the
*device's* account list (Google, Exchange), which this app never touches — its accounts
are SIP accounts it stores itself in `:data:account`. Requesting it would ask for personal
data with no feature behind it, which §7 forbids. Ask what was meant before adding
anything.

**DECIDE (D3) — this reverses a recorded decision.**
[`PermissionRequest.kt:16`](app/src/main/kotlin/com/whatsappv2/permission/PermissionRequest.kt:16)
records the current design and its reason: *in-context by design, because a wall of
dialogs on first run is how people learn to press Deny.* Android gives one prompt per
permission; a refusal on a launch screen is permanent and costs the feature. Confirm the
owner wants the wall, then change the comment — code and its recorded rationale must not
contradict each other.

Build:
- A first-run screen that requests, in order, with each rationale shown **before** its
  system dialog: microphone, notifications, camera, contacts, Bluetooth.
- `ActivityResultContracts.RequestPermission` handles one permission
  ([`PermissionRequest.kt:39`](app/src/main/kotlin/com/whatsappv2/permission/PermissionRequest.kt:39)).
  A sequence needs `RequestMultiplePermissions`, or a driver that runs the existing
  single-permission request once per entry. Either way `coordinator.markRequested` must
  fire for **every** permission asked, or `PermissionCoordinator.status` will later
  mistake a permanent denial for a first request
  ([`PermissionCoordinator.kt:38`](app/src/main/kotlin/com/whatsappv2/permission/PermissionCoordinator.kt:38)).
- Respect what is already modelled: skip anything whose `appliesToThisDevice` is false, and
  skip `MANAGE_OWN_CALLS`, which is install-time and would return granted while proving
  nothing (`isRuntimePermission = false`).
- A refusal must not dead-end. Only `RECORD_AUDIO` is `BLOCKS_FEATURE`; the other four
  `DEGRADE_GRACEFULLY` and the app must reach Calls with all of them denied.
- Shown once. Record completion so a relaunch goes straight to the app, and keep the
  in-context path working for anyone who declined and later presses the feature.

Done when:
- [ ] D2 and D3 are answered in writing before any code lands
- [x] A fresh install asks for microphone, notifications, camera and contacts, each with
      its `AppPermission.rationale` shown first
- [x] Every permission requested is passed to `coordinator.markRequested`
- [ ] Declining all of them still reaches the Calls screen; only calling is blocked, with
      `RECORD_AUDIO.deniedExplanation` shown rather than a silent failure
- [x] A device below API 31 is not asked for `BLUETOOTH_CONNECT`, and below 33 not asked
      for `POST_NOTIFICATIONS`
- [x] Relaunching does not ask again
- [x] The comment in `PermissionRequest.kt` describes the design that now exists
- [x] No permission is added to `AndroidManifest.xml` without a feature that reads it

---

### Task 73 — Saving an account says so, and says whether it registered
**Depends on:** 69 · **Source:** Owner 5 · **Prompt refs:** §5.1 · **Modules:** `:feature:accounts`, `:app`

**The cause is known.** `AccountEditorRoute` calls `onSaved()` the moment the `Saved`
event arrives ([`AccountsRoutes.kt:66`](feature/accounts/src/main/kotlin/com/whatsappv2/feature/accounts/AccountsRoutes.kt:66)),
and `:app` wires that to `popBackStack()`
([`AppNavHost.kt:66`](app/src/main/kotlin/com/whatsappv2/ui/navigation/AppNavHost.kt:66)).
The editor is gone before any message could be drawn on it. Nothing is broken in the save
path — `AccountEditorViewModel.save()` works and `AccountEditorScreen` already shows
"Saving…" ([`AccountEditorScreen.kt:112`](feature/accounts/src/main/kotlin/com/whatsappv2/feature/accounts/editor/AccountEditorScreen.kt:112)).
The confirmation simply has nowhere to appear.

Build:
- Show the confirmation on the **account list**, after the pop — the surface that outlives
  the editor. Hand the event across as a navigation result rather than making the list
  re-query and guess.
- Say more than "Saved". `AccountEditorEvent.Saved` already carries `label`,
  `unregisteredFirst` and `registration: RegistrationAttempt`
  ([`AccountEditorViewModel.kt:61`](feature/accounts/src/main/kotlin/com/whatsappv2/feature/accounts/editor/AccountEditorViewModel.kt:61)),
  and the KDoc says why: an edit releases the old binding and takes out a new one, and if
  that second half failed **the user is now unreachable**. §5.1 calls a silent partial
  re-registration a bug. So:
  - registered → "*label* saved and registered"
  - failed → "*label* saved, but registration failed" with the reason, and it must not
    vanish on a timer the way a routine success may
- `SaveFailed` and `CredentialsMustBeReEntered` keep the editor open and report there.
  Only a success navigates.

Done when:
- [x] Adding an account shows a confirmation naming the account, after returning to the list
- [x] Editing an existing account shows the same confirmation
- [x] A save whose re-registration failed says so, distinguishably from a clean save, and
      does not disappear before it can be read
- [x] `SaveFailed` still keeps the user on the editor with their input intact
- [x] A test covers all three outcomes — saved+registered, saved+registration failed, failed
- [ ] `:feature:accounts` coverage still clears its CI gate (`.../accounts/editor` 45%,
      `.../accounts/list` 40%)

---

# Phase 12 — Call surface

### Task 74 — A video-call button in the dialler
**Depends on:** 70, 72 · **Source:** Owner 6 · **Prompt refs:** §5.2 · **Modules:** `:feature:dialer`

The plumbing exists. `PlaceCallUseCase` already takes `media: MediaProfile = AUDIO`, so a
video call is the same call with `MediaProfile.AUDIO_VIDEO`. What is missing is a way to
ask for one: the dialler has a single call button
([`DialerScreen.kt:157`](feature/dialer/src/main/kotlin/com/whatsappv2/feature/dialer/DialerScreen.kt:157))
and `DialerActions.onCall` takes no argument
([`DialerActions.kt:25`](feature/dialer/src/main/kotlin/com/whatsappv2/feature/dialer/DialerActions.kt:25)).

Build:
- A second action beside the audio one, `Icons.Filled.Videocam`, same enablement rule
  (`state.canPlaceCall`).
- Carry the profile through: `DialerActions.onVideoCall`, `DialerViewModel.onVideoCall`,
  and a `media` parameter on `place(target)`
  ([`DialerViewModel.kt:187`](feature/dialer/src/main/kotlin/com/whatsappv2/feature/dialer/DialerViewModel.kt:187)).
- **Camera permission is asked for here, in context**, even after Task 72 — someone who
  declined on first run presses this button and must get an explanation, not a failure.
- **A declined camera downgrades; it does not refuse.** The manifest already commits to
  this: `MediaProfile.downgradedWhenCameraUnavailable`, and `android.hardware.camera` is
  `required="false"` so the app installs on devices with no camera at all. A video press
  on such a device places an audio call and says why.
- The call screen already handles the rest — `CallDisplay.videoActive`, escalation, camera
  switching (Tasks 52–54). This task adds no video logic.

Done when:
- [x] A video button sits beside the call button, disabled when the call button is
- [x] Pressing it places a call negotiated with `MediaProfile.AUDIO_VIDEO`
- [x] With camera permission denied, the call is placed as audio and the user is told —
      it is not refused and does not crash
- [x] On a device reporting no camera, the same downgrade happens
- [x] Both buttons have distinct `contentDescription`s and their own test tags
- [x] A ViewModel test asserts the profile reaching `PlaceCallUseCase` for each button

---

### Task 75 — History call icons worth looking at and pressing
**Depends on:** 71, 74 · **Source:** Owner 7 · **Modules:** `:feature:history`, `:core:designsystem`

Today the direction is a bare `Icon` with no colour distinction
([`HistoryScreen.kt:229`](feature/history/src/main/kotlin/com/whatsappv2/feature/history/HistoryScreen.kt:229))
and the row's only action is a call-back `IconButton`
([`HistoryScreen.kt:190`](feature/history/src/main/kotlin/com/whatsappv2/feature/history/HistoryScreen.kt:190)).
Missed, incoming and outgoing look the same weight at a glance.

Build:
- Colour and shape by direction, from the theme — missed in the error colour, incoming and
  outgoing distinguishable without relying on colour alone (colour is not the only channel;
  the glyph must differ too).
- Press feedback: ripple, and a visible pressed state on the row action.
- A video call-back action beside the audio one, using Task 74's path with the entry's own
  account — a call back goes out the way the call came in
  ([`HistoryViewModel.kt:112`](feature/history/src/main/kotlin/com/whatsappv2/feature/history/HistoryViewModel.kt:112)).
- Touch targets at least 48dp, whatever the icon's drawn size.
- Reuse `CallActionButton` from `:core:designsystem` if it fits; add a shared component
  there rather than growing a second call-button style inside the feature.

Do not regress:
- `directionDescription()` ([`HistoryScreen.kt:235`](feature/history/src/main/kotlin/com/whatsappv2/feature/history/HistoryScreen.kt:235))
  is what a screen reader announces. Keep it, and give the new video action its own.
- The existing test tags — `entryTag`, `callBackTag` — are referenced by UI tests. Keep
  them and add tags for anything new.
- Direction icons are auto-mirrored; RTL must stay correct.

Done when:
- [x] Missed, incoming and outgoing are each distinguishable by glyph **and** by colour
- [x] Every row action shows a pressed state and is at least 48dp
- [x] A row offers an audio and a video call-back, both using the entry's own account
- [x] Every icon has a `contentDescription`; no existing one was dropped
- [ ] Existing history UI tests pass with their tags unchanged
- [ ] The list renders correctly in RTL and in dark theme

---

### Task 76 — The mute button waits for the engine. Show that, don't fake it.
**Depends on:** nothing · **Source:** Owner 8 · **Prompt refs:** §5.2, §13 · **Modules:** `:feature:calls`, `:data:sip`

**Diagnose before changing anything.** The delay is a design, not an accident. Pressing
mute calls `CallViewModel.setMuted`
([`CallViewModel.kt:303`](feature/calls/src/main/kotlin/com/whatsappv2/feature/calls/CallViewModel.kt:303)),
which launches through `act()`
([`CallViewModel.kt:412`](feature/calls/src/main/kotlin/com/whatsappv2/feature/calls/CallViewModel.kt:412))
and reports only failures. The button's appearance comes from `controls.isMuted`
([`CallScreen.kt:389`](feature/calls/src/main/kotlin/com/whatsappv2/feature/calls/CallScreen.kt:389)),
which the state machine sets on `CallEvent.SetMuted`
([`CallStateMachine.kt:183`](domain/src/main/kotlin/com/whatsappv2/domain/call/CallStateMachine.kt:183))
— i.e. **after** `LinphoneSipEngine.setMuted` has driven both the stack and Telecom
([`LinphoneSipEngine.kt:854`](data/sip/src/main/kotlin/com/whatsappv2/data/sip/LinphoneSipEngine.kt:854)).
So the button is inert for a round trip.

Measure that round trip on a device before choosing a fix. If it is milliseconds, the bug
is elsewhere — a dropped state emission, or a recomposition that is not observing.

**The fix must not lie about a live microphone.** Flipping the icon optimistically means a
failed mute shows "Muted" over an open mic. That is the same class of defect the hold path
already refuses: *a call shown as held whose re-INVITE the far end refused is a screen
lying about where the audio is going*
([`LinphoneSipEngine.kt:875`](data/sip/src/main/kotlin/com/whatsappv2/data/sip/LinphoneSipEngine.kt:875)).

Build:
- An in-flight state on the control: the press registers **immediately** and visibly, the
  confirmed state arrives from the engine, and a failure reverts with the reason. Press →
  visible acknowledgement must be within one frame; press → settled state is whatever the
  engine takes, and is allowed to take it.
- Guard the double press. A second tap while in flight must not queue an opposite request.
- Leave `setMuted`'s already-muted short-circuit alone
  ([`LinphoneSipEngine.kt:858`](data/sip/src/main/kotlin/com/whatsappv2/data/sip/LinphoneSipEngine.kt:858)):
  it is what stops a headset's mute button echoing back through Telecom.
- Apply the same treatment to hold, speaker and video, which go through the same `act()`.

Done when:
- [ ] The measured press → screen-change latency is written down, before and after
- [x] The control acknowledges a press within one frame
- [x] "Muted" is shown only after the engine confirms; a failed mute reverts and says why
- [x] Double-tapping mid-flight cannot leave the icon and the microphone disagreeing
- [ ] A headset mute arriving through Telecom still updates the on-screen state
- [x] Hold, speaker and video behave the same way
- [ ] A ViewModel test covers press → pending → confirmed and press → pending → failed

---

# Phase 13 — Sweep and new ground

### Task 77 — A latency and freshness sweep across the app
**Depends on:** 71, 76 · **Source:** Owner 9 · **Prompt refs:** §6, §8 · **Modules:** all

"Fix delays and auto-refresh issues throughout the app" is not checkable as written, so
the deliverable is the **inventory first**, then numbered fixes against it. An unmeasured
performance task becomes an unbounded one.

Build:
- `docs/latency-sweep.md`: every screen, and for each — is its state **pushed** (a flow
  from the store) or **pulled** (something asks)? Where a timer exists, what wakes it and
  when does it stop?
- Two patterns already in the tree set the bar, and both should be cited:
  - **Right:** `AccountDetailRoute`'s countdown ticks once a second **only while a retry
    is pending**, and not at all otherwise
    ([`AccountsRoutes.kt:104`](feature/accounts/src/main/kotlin/com/whatsappv2/feature/accounts/AccountsRoutes.kt:104)).
  - **Wrong:** the history list's change signal, which Task 71 fixes. Check whether any
    other declared-but-uncollected flow exists — `changes()` was one.
- A numbered defect list. Each entry: screen, symptom, measured number, cause, fix.
- Budgets, stated as numbers rather than adjectives: tap → visible feedback, screen open →
  first content, and store write → screen updated.
- Fix what the list finds. Anything deferred is written down as a follow-up with its
  reason, not dropped.

Done when:
- [x] `docs/latency-sweep.md` covers every screen and classifies each as pushed or pulled
- [x] Every timer in the app is named, with what starts and stops it
- [x] No flow is declared on a repository interface and collected by nothing
- [ ] Three budgets are stated as numbers, and each screen is measured against them
- [x] Every defect found is fixed or carried as a written follow-up with a reason
- [x] No fix introduces a poll where a flow would do

---

### Task 78 — Group call: a dummy page that builds a group and dials it
**Depends on:** 70, 74 · **Source:** Owner 10 · **Prompt refs:** §2.2 · **Modules:** new `:feature:group`, `:app`

**DECIDE (D4) — "create a group" has no server behind it.** §2.2 says group calling is not
a SIP client feature on its own, and ADR-003 chose a **dial-in MCU**: the bridge owns the
room, and the app joins it by calling a conference URI — which is exactly what
`JoinConferenceUseCase` does. There is no API in this codebase, and none specified, for
creating a room or inviting members to one. So "create a group" here can only mean a
**local** selection of people plus a conference address to dial. That is why the owner
called it a dummy page, and the task is written that way. If server-side group creation is
wanted, that is a server contract to specify first and a different task.

Build:
- `:feature:group`, added to
  [`settings.gradle.kts:63`](settings.gradle.kts:63) beside the other features; copy
  [`feature/settings/build.gradle.kts`](feature/settings/build.gradle.kts) as the template.
- A page that lets the user name a group, add members from contacts (`SipContact`, the
  same source the dialler uses) and enter or pick the conference address to dial.
- Audio and video start buttons, going through `JoinConferenceUseCase`, whose `withVideo`
  flag already downgrades when the camera is unavailable — the same rule as Task 74.
- Joining opens `CallActivity`, exactly as the dialler and history do. The conference UI
  on the other side already exists: `ConferenceRoster`, `ConferenceVideo` and
  `CallDisplay.isConference` (Tasks 60, 61). **This task builds no call UI.**
- Reached from the Calls screen, alongside Task 70's dialler FAB.
- Say what it is. The page states plainly that the group is local to this device and that
  the call is a dial-in to a bridge — a screen implying a server-side group that does not
  exist is worse than no screen.

Done when:
- [ ] D4 is answered before any code lands
- [x] `:feature:group` is in `settings.gradle.kts` and builds
- [x] A group can be named, given members from contacts, and given a conference address
- [x] Audio start and video start both join through `JoinConferenceUseCase`
- [x] Joining opens the existing call screen, and the roster renders — no new call UI
- [x] With the camera denied, a video start joins as audio and says so
- [x] The page states that the group is local and the call is a dial-in
- [x] `:test:arch` passes: `:feature:group` depends on `:domain` and `:core:*` only

---

## Owner's list → tasks

| Owner's item | Task |
|---|---|
| 1 — Dialer page → floating button on Calls | 70 |
| 2 — Ask for permissions on install | 72 |
| 3 — Calls: auto-refresh the history | 71 |
| 4 — Settings page → top-right icon with Accounts | 69 |
| 5 — Success message when an account is saved | 73 |
| 6 — Video call icon in the dialler | 74 |
| 7 — Better history call icons | 75 |
| 8 — Mute button delay | 76 |
| 9 — Delays and auto-refresh across the app | 77 |
| 10 — Group call dummy page | 78 |

---

## Status — implemented, and what is still unticked

All ten tasks are implemented. The unticked boxes below are unticked for two reasons, and
neither of them is "not done yet".

**Needs a device or an emulator.** This project has never had one in CI, which is the same
reason six Definition-of-Done items in [`docs/dod-sweep.md`](docs/dod-sweep.md) are unticked.
The mechanism is in the code and gated by the JVM suite; the *observation* is not:

- the FAB clearance and the RTL/dark rendering of the history rows;
- a headset mute arriving through Telecom;
- ending a call and returning to Calls;
- the press → screen-change latency, before and after Task 76. **No number in
  [`docs/latency-sweep.md`](docs/latency-sweep.md) was measured on a running app**, and the
  document says so at the top rather than reporting its targets as results.

**Genuinely not done, and worth naming:**

1. **Task 70 — the dialler does not keep its input across a dismiss.** It was a tab with
   `saveState`/`restoreState`, which gave this for free; it is now a route, and popping it
   destroys the `DialerViewModel` with the typed number in it. The fix is to hoist that
   state above the route. It is a real regression from the tab layout and it is not fixed.
2. **Task 71 — no test covers a refresh mid-list preserving scroll position.**
   `getRefreshKey` is unchanged and already anchors on the visible row, so the behaviour is
   inherited rather than newly written, but it is asserted nowhere.
3. **Task 76 — the press → pending → confirmed path has no test.** The two that exist are
   the ones that are deterministic under `StandardTestDispatcher`: a second press is
   dropped rather than queued, and a refused mute is neither shown as muted nor left stuck
   busy. `StateFlow` conflation can legitimately swallow the intermediate busy state, so a
   test asserting it would be one that fails at random.
4. **Task 72 — "declining everything still reaches Calls" is only half true.** Skipping
   the whole flow does reach Calls. What is *not* implemented is `RECORD_AUDIO`'s refusal
   blocking calling with `deniedExplanation` shown — the engine fails the call instead,
   which is a worse message. That predates this round and is now written down.

**The four DECIDEs were not answered in writing.** The instruction was to build all ten, so
each was resolved under the reading recorded in its task and in the code's own comments —
D1 removed the Settings *destination* and kept every control on it; D2 did **not** add
`GET_ACCOUNTS`; D3 built the first-run wall and rewrote the contradicting comment; D4 built
the group page as local-only with the page saying so. Any of the four can be reversed
cheaply; none of them was assumed away silently.

## Checkpoints

Stop, push, and read CI at Tasks **70, 73, 76, 78**. Each report states what works, what
was assumed, and what is deferred (§9, §13) — and, for this round, whether the four
DECIDEs were answered or worked around.
