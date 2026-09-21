# Conference history, the bridge roster, and the self-view (2026-09-21)

**Status:** implemented; JVM suite and detekt green on this branch. Live multi-handset
verification was **not** possible in this session — see §6 — so every claim below is
"the code does this and a test pins it", not "measured on hardware".

Five things were asked for, in one message. Each is one section.

## 1. A bridged conference reaches history as one entry, named by its people

A video conference is built in the FreeSWITCH bridge (ADR-003): the device that presses
Merge REFERs every leg to room `3000` and dials the room itself. Until now those were three
unrelated rows in the log — two video calls that "ended in a transfer" and a call to
"3000" wearing the group glyph — because only a **local** mix (ADR-009) stamped its legs
with a shared `conferenceKey`.

- `PjsipSipEngine.mergeIntoConference` now stamps every leg whose REFER the stack accepted,
  and the room leg, with **one key** — the room leg's when it already has one, else the
  local mix's the legs are leaving, else a new one (`mergeIntoRoom`, file level).
- The transferee's half: a call the stack places by following a REFER *to the room* is tied
  to the leg the REFER arrived on (`linkTransferredRoomLeg`), when that leg is unambiguous.
  Its history reads "a conference with 1001" rather than a transferred call beside `3000`.
- `HistoryRow.Call` gained `members` (legs that are not the room), `hasVideo` (any leg) and
  `isConference`. `groupConferences` takes an `isRoom` predicate, supplied by
  `HistoryViewModel` from the injected `ConferenceRoom` (`ConferenceRoom.matches`). The
  row is titled by its people — "1004, 1005" — and the room leg stays in `legs`, so the
  conference's duration runs to the room leg's ending and Delete removes it too.

## 2. The swipe on a conference row means what it says

Right swipe → voice conference, mixed on this device as members answer (unchanged path).
Left swipe → **video conference in the bridge** (new). It used to place a voice conference
"whatever was asked", from the days when a conference could only be mixed here.

- `ConferenceJoinCoordinator.callBack(members, video = true)` dials each member with video,
  one at a time (Telecom refuses a second INVITE while one rings), and `bridgeOnConnect`
  has each REFERred into the room as it is established: the first two together, when the
  room is dialled; every later one alone, to join the leg this device already holds.
  `awaitQuietForBridge` is what keeps the dialling behind each merge — the room INVITE is
  an outgoing call too.
- `ConferenceJoinPolicy.planBridge` is the pure rule; `MergeCallsUseCase.bridge` resolves
  the room the same way `invoke` does.
- **`mergeIntoConference` with a room leg among the ids keeps it**, resumes it and REFERs
  only the others. Before this, Add → Merge from a bridged conference REFERred the room leg
  to its own room and dialled the room a second time. That fix is what the third member
  of a video call-back relies on.
- No camera → the call-back goes out as voice and the snackbar says so (Task 75's rule).
- The detail dialog offers both: "Voice call" and "Video call".

## 3. Two marks on a conference row

The group glyph takes the circle; the media — camera or handset — is a small badge on its
lower corner (`MediaBadge`, `conferenceMediaTag`). "Video conference" / "Voice conference"
to a screen reader. The subtitle counts *people*, not legs, and drops the count for a
conference of which this device knows one other member.

## 4. The participants list on a bridged video conference

The bridge publishes no roster (RFC 4575 needs an evsub SWIG has not got — `HANDOFF.md`
§D). The screen used to say only that, over a conference the user had just assembled, and
the two people they merged vanished from it a second after Merge as their legs ended.

- `ConferenceSession.invited`: the members this device REFERred into the room, or the one
  that REFERred it there. Known from this device's own actions, kept apart from
  `participants` (what the bridge says) so neither is mistaken for the other.
  `participantCount` stays null — no count the bridge did not give.
- `ConferenceUiState.fromMerge`; the header names them — "You, 1004 and 1005" — the list
  drops down, and the list says where the names came from. Stable, because it comes from
  the merge rather than from the legs.
- Over video, the title, duration and roster sit at the **top** of the chrome rather than
  centred over the faces (`InCallChrome`).

## 5. A tap outside a maximised self-view minimises it

`SelfPreviewState` is hoisted to `CallVideo` (`rememberSelfPreviewState`) so the
picture-tap target can see it: while the preview is maximised, a tap on the picture
minimises it and does nothing else; minimised, the tap toggles the chrome as before.
`SelfPreviewTest` pins both halves inside the real `CallScreen`.

## 6. What is and is not verified

JVM: `:domain`, `:data:sip` (303), `:feature:calls` (135), `:feature:history` (69),
`:feature:dialer` tests and detekt on every module. A pre-existing detekt failure from PR #30
(`:data:calllog` indentation, `:data:sip` import order, `;` wrapping, `ReturnCount`,
`TooManyFunctions` on an 11-function interface, `LargeClass` on `PjsipSipEngine`) is fixed
on this branch — the engine's new logic and some existing snapshot builders moved to file
level, and `TooManyFunctions.thresholdInInterfaces` is 20 like its siblings.

**Not verified on hardware.** The local FreeSWITCH was bound to `[::1]:5060` with its event
socket not answering `auth`, and the attached TC15 carries accounts for two other servers,
so no handset could register during this session. Everything in §1, §2 and §4 that needs a
real REFER, a real bridge or a second handset — the merged legs ending on their transfers,
the room leg's `invited`, the sequential video call-back — is asserted against
`FakeSipEngine` and the engine's fake gateway only. Run the 3-device matrix in
`HANDOFF.md` before calling any of it done; the swipe paths need a registered handset and
two answering peers.
