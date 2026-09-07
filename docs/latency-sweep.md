# Latency and freshness sweep (Task 77)

Every screen, classified: is its state **pushed** to it, or does something **pull** for it?
Where a timer exists, what starts it and what stops it?

"Fix delays and auto-refresh issues throughout the app" is not checkable as written, so
this inventory is the deliverable and the fixes are numbered against it.

## Honest limit, stated first

**The budgets in this document are targets, not measurements.** This project has never had
an Android device or an emulator in CI ([`dod-sweep.md`](dod-sweep.md) says the same thing
about eight other items), so no press-to-frame number here was taken from a running app.
What *is* verified is structural and is gated by the JVM suite: which flows are collected,
which timers run and when they stop, and that no screen polls.

Reporting a target as a result is the failure mode §13 warns about. The three budgets
below are what the app is built to; measuring them needs one device in CI, and that single
change would settle this document and eight DoD items with it.

## The two patterns

**Right — `AccountDetailRoute`.** The registration status is pushed from
`registrationState`. The retry countdown genuinely cannot be: "in 42s" has to become
"in 41s" while nothing else has changed. So a one-second ticker drives *only* the clock
reading, and only while an attempt is actually pending
([`AccountsRoutes.kt`](../feature/accounts/src/main/kotlin/com/whatsappv2/feature/accounts/AccountsRoutes.kt)).
With nothing scheduled the ticker does not run. That is the difference between a countdown
and a wakeup every second for ever, and it is the shape every other timer here is measured
against.

**Wrong — the call history, until Task 71.** `CallLogRepository.changes()` was implemented
in `:data:calllog`, documented in two places as the thing that kept the list live, and
collected by **nothing**. `PagingSource.invalidate()` was called nowhere in the repository.
The list only changed when something else happened to rebuild it.

## Screen inventory

| Screen | State | Source | Timer |
|---|---|---|---|
| Calls (history) | **Pushed** | `CallLogRepository.changes()` → `CallLogPagingSource.invalidate()`, plus a lifecycle `ON_RESUME` reload | none |
| Dialler | **Pushed** | `combine(observeAccounts, registrationState, entry, recent, contacts)` | none |
| Group call | **Pushed** | local state; contact search is `mapLatest` over the query | none |
| Call screen | **Pushed** | `activeCalls`, `conferences`, recorder, `videoRequests`, `transferEvents` | 1s, while subscribed — see below |
| Accounts list | **Pushed** | `observeAccounts()` + `registrationState` | none |
| Account detail | **Pushed** | `registrationState` + retry schedule | 1s, **only while a retry is pending** |
| Account editor | **Pushed** | local draft state | none |
| Settings | **Pushed** | DataStore flow | none |
| First-run permissions | **Pushed** | local step index | none |

Nothing in the app polls. Every screen's state arrives on a flow from the store or the
engine, which is what makes the two timers below the whole of the app's wakeup surface.

## Every timer in the app

1. **`CallViewModel.ticker()`** — one second, while the call screen is subscribed.
   Justified: a call duration changes with nothing else changing, so there is nothing to be
   pushed. Bounded by `SharingStarted.WhileSubscribed(5s)`, so it stops five seconds after
   the screen goes away and does not survive the call.
2. **`AccountDetailRoute` countdown** — one second, and only while
   `nextRetryAtEpochMillis != null`. Stops the moment the retry lands or the screen leaves.

There is no third. A grep for `delay(` in a loop across `:app` and `:feature:*` returns
these two.

## Defects found, and what happened to them

| # | Screen | Symptom | Cause | Fix |
|---|---|---|---|---|
| 1 | Calls | A call that ended while the screen was open never appeared | `CallLogRepository.changes()` collected by nothing; `invalidate()` called nowhere | Task 71 — `HistoryViewModel.watchStoreChanges()`, with a regression test that fails on the parent commit |
| 2 | Calls | Returning from a call showed a stale list | The store's signal was emitted while the ViewModel's collector was stopped | Task 71 — `LifecycleEventEffect(ON_RESUME) { refresh() }` |
| 3 | Call screen | Mute, hold, speaker and video looked dead for a round trip | State moves only on the engine's answer, and nothing showed that a press had been received | Task 76 — an in-flight set; the press is acknowledged, the **outcome** still waits |
| 4 | Call screen | A second press mid-flight queued the opposite request | No re-entry guard on `act()` | Task 76 — `beginAction` claims the action atomically |
| 5 | Accounts | Saving an account looked like it did nothing | The editor popped before any message could be drawn | Task 73 — the confirmation is delivered to the list, which outlives the editor |
| 6 | Accounts | Delete, log in and log out were **silent** | `AccountsViewModel.events` was collected by nothing | Task 73 — collected in `AccountsRoute` |

Defects 1 and 6 are the same class of bug: a flow declared on an interface, implemented in
the data layer, and read by nobody. Both were invisible because the code *looked* wired.

## The check that would have caught both

Every `Flow` declared on a `:domain` repository or engine interface, against its production
call sites:

| Flow | Collected by |
|---|---|
| `activeCalls` | call screen, Telecom, notifications, services |
| `registrationState` | accounts list, detail, dialler, services |
| `changes()` | `HistoryViewModel` — **as of Task 71** |
| `conferences` | call screen |
| `incomingCalls` | Telecom registry |
| `endedCalls` | `CallLogRecorder` (`:domain`) |
| `transferEvents` | call screen |
| `videoRequests` | call screen |
| `observeAccounts` | accounts list, dialler |
| `observeDefaultAccount` | `PlaceCallUseCase`, `JoinConferenceUseCase` |
| `observeSettings` | settings |

And every ViewModel's `events` channel now has exactly one collector: `CallViewModel`,
`DialerViewModel`, `HistoryViewModel`, `GroupCallViewModel`, `AccountsViewModel`,
`AccountEditorViewModel`, `AccountDetailViewModel`.

Nothing is left declared-and-uncollected. Making this a CI gate is the obvious follow-up
and is **not** done — it needs a rule that can tell a genuine "collected in `:domain`" from
a genuine orphan, which the two false alarms above show is not a one-line grep.

## Budgets

Targets the app is built to. Unmeasured, as stated at the top.

| Budget | Target | How it is met structurally |
|---|---|---|
| Tap → visible feedback | ≤ 1 frame | Ripple on every control; in-flight spinner on call controls (Task 76) |
| Screen open → first content | ≤ 1 frame for cached state | Every screen renders from a `StateFlow` with an initial value; no screen blocks on a read |
| Store write → screen updated | ≤ 1 flow dispatch | Room table invalidation → repository flow → collector. No polling anywhere |

## Follow-ups, not done

Written down rather than silently dropped (§13):

1. **Measure the three budgets on a device.** Needs one device in CI. This is the change
   that would turn this document from targets into results.
2. **Gate the declared-and-uncollected check in CI.** Needs to handle collectors that live
   in `:domain`; a naive grep reports `endedCalls` and `observeDefaultAccount` as orphans
   when both are collected.
3. **`CallViewModel.ticker()` runs at one second regardless of phase.** It only needs to
   run while a duration is on screen — that is, from `Connected`. Ringing does not display
   a duration, so the tick is currently doing nothing for that stretch.
