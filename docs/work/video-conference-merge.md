# Merging a video call now builds a real video conference

**Status:** implemented 2026-09-16; JVM suite and detekt green. The merge audio defect
found during the four-handset run is fixed and verified (§4b); the self-view is now
movable, resizable and minimisable, verified on a TC15 in a live room (§4c).

Supersedes the open design question in
[`video-conference-merge-drop.md`](video-conference-merge-drop.md) §5, which offered three
options and said the choice was the stakeholder's. **Option B was chosen** on 2026-09-16:
merging video calls routes them into the bridge.

---

## 1. What was asked for

> When I merge a video call into a conference, all participants' video streams must be
> visible on every user's screen. The local user's camera should be displayed clearly in the
> top-right corner as a small preview. Maximum conference size is 4 participants.

## 2. Why the previous behaviour could not satisfy it, at any implementation quality

Merge was `mixCalls` — ADR-009's device-hosted star — and as of 2026-09-15 it explicitly
dropped video on the way in (`dropVideoForMix`). That was the right call for what it was,
and it can never meet the requirement above. The reason is topology, not effort:

- In a star, the host holds N-1 legs and **every other participant holds exactly one** — to
  the host.
- A participant can only receive the picture carried on a leg they hold.
- So for 1002 to see 1004, this handset would have to decode both cameras and **re-encode a
  composite for each peer**. That is an MCU, running on a phone.
- ADR-009 measured it: one video stream is ~135 % of a core on a TC15, and a four-way
  device-mixed video conference is ~540 % against a 400 % budget.

No amount of client work changes this. It is the same conclusion ADR-003 reached, which is
why ADR-009 superseded it **for audio only** and left video with the bridge.

## 3. What was built

### 3.1 The policy is a pure function

`MergeTopology.of(calls, bridgeConfigured)` decides, and it is the whole of the rule:

| Calls being merged | Topology | Why |
|---|---|---|
| All audio | `LocalMix` (ADR-009) | Needs no server. Routing it through one would hand back the dependency ADR-009 removed. |
| **Any** leg with video | `Bridge` (ADR-003) | One video leg is enough: mixing some here and bridging the rest is two conferences that cannot hear each other. |
| Video, no room configured | `Unavailable` | Declined, and says so. A silent downgrade takes two people's cameras away and tells them nothing. |
| Video, more than 4 including this handset | `Unavailable` | `SipConferenceController.MAX_VIDEO_CONFERENCE`. |

Enumerated in `MergeTopologyTest`.

### 3.2 The bridge merge itself

`SipConferenceController.mergeIntoConference(callIds, room)`, and the order is the point:

1. **Tear the local mix down first.** A device that is mixing *and* transferring is briefly
   both host and member, holding native ports open for legs on their way out.
2. **Resume every held leg.** A transferee that follows a REFER from a leg it believes is on
   hold arrives at the bridge with its media stopped. Asserted in
   `PjsipSipEngineConferenceTest`: nothing is REFERred until the resume lands.
3. **REFER each leg to the room**, then dial the room ourselves with video. A leg that
   refuses the REFER is logged and skipped rather than failing the whole merge.
4. **Nothing is hung up.** The leg *is* the dialog the REFER travels in.

FreeSWITCH is a B2BUA for these calls, so it executes the REFER itself and moves the peer
into `conference 3000@whatsapp-video`. **Peers therefore do not need this build to be
pulled into the conference** — which is what made a four-way test possible with one handset
on USB.

### 3.3 Two latent defects found on the way, and fixed

Both were in the existing transfer path and would have fired on an ordinary blind transfer
between two handsets, not only on a merge.

**PJSUA2 was reusing one `Call` object for two native calls.** pjsua accepts an inbound
REFER by itself (`pjsua_call.c:6240` sets `PJSIP_SC_ACCEPTED`) and places the new INVITE
(`:6384`). PJSUA2 then seeds the new call's `user_data` with the *parent's* pointer and only
promotes it to a separate instance when the parent has a `child` (`call.cpp:569-584`). With
no child it assigns the new call's id to the existing object instead. PJSIP says so out
loud: *"Warning: application reuses Call instance in call transfer"* (`endpoint.cpp:1817`).
Fixed by overriding `onCallTransferRequest` and minting a child `PjCall`.

**The engine dropped every event for a REFER-created call.** Only `INCOMING_RECEIVED` could
name a call the engine had never seen, and this one is outgoing — so each event was logged
as *"Ignoring … for unknown call"* and a transferred handset went on showing the leg it had
already left. Fixed with `StackCallState.OUTGOING_TRANSFERRED` and `onTransferredCall`,
which publishes it into `Outgoing.Calling` with the media the stack carried over.

### 3.4 The self-view

`showsLocalPreview` required `showsRemoteVideo`. That is wrong in exactly the case that
matters most here: between joining the room and the bridge's first composed frame there is
no remote picture, so a user whose camera was plainly on saw nothing of themselves. The
self-view now follows **this** device's camera, and `showsAnyVideo` gates the video layer so
the preview's surface exists before the first remote frame.

Position and size were a fixed corner at the time. They are not any more — see §4c: the
self-view is a floating window the user drags, resizes and minimises, defaulting to the
bottom-end corner at a quarter of the shorter edge. `setZOrderMediaOverlay(true)` is
unchanged and still what makes it draw over the remote surface rather than behind it.

## 4. The server half, verified rather than assumed

Checked live on 2026-09-16, not read from notes:

- `conference 3000@whatsapp-video` resolves through
  `dialplan/default/02_cisco_features.xml`.
- A test room's flags contain **`video_muxing`**, `minimize_video_encoding` and
  `video_required_for_canvas`, and **not** `video_floor_only`. Passthrough would send the
  floor holder's camera to everybody — the defect fixed on 2026-09-15.
- `mod_av` does **not** load, so H.264 cannot be composed. The app offers **VP8** first
  (`Codecs.kt:85`), which the core carries as `switch_vpx.c`. A member that can only do
  H.264 would be heard and not seen.
- Extension-to-extension **audio** calls run `bypass_media=true`; calls to `3000` do not,
  because the conference needs FreeSWITCH's own media. Video calls stopped bypassing on
  2026-09-16 — §4b is why, and it is the whole of the audio fix.

## 4b. The audio defect the four-handset test found (fixed 2026-09-16)

Video was right and **no member could hear any other**. It was a server problem with a
client-shaped symptom, and the evidence is unambiguous. From the 15:43 test, two members
REFERred in by a merge:

```
Set Codec sofia/internal/1003@... L16/0 20 ms 441 samples 352800 bits 1 channels
AUDIO RTP [sofia/internal/1003@...] 172.19.171.214 port 17726 -> 127.0.0.1 port 9999 codec: 97
```

against the one member that dialled `3000` itself:

```
Set Codec sofia/internal/1005@... PCMU/8000 20 ms 160 samples 64000 bits 1 channels
AUDIO RTP [sofia/internal/1005@...] 172.19.171.214 port 16422 -> 172.19.171.14 port 4044 codec: 0
```

`L16/0` has a clock rate of zero and `127.0.0.1:9999` is FreeSWITCH's black hole. The
transferred members were on the canvas with no audio path at all; the one member that did
have audio had nobody to hear.

**Cause.** `bypass_media=true` applied to every handset-to-handset call, which is what lets
two handsets agree on Lyra (ADR-008) — and leaves FreeSWITCH holding a leg whose negotiated
audio codec is one it does not have (`show codec`: no Lyra, no Opus, no G722). Merging
REFERs that leg into `3000`; FreeSWITCH transfers the existing channel, has to take media
back for the mixer, finds only Lyra, and offers `m=audio 0 RTP/AVP 19` with video alone.

**Nothing in the `3000` extension can repair it** — that re-INVITE is built inside
`switch_ivr_session_transfer`, before the destination extension runs. `media_reset` and
`absolute_codec_string=PCMU,PCMA,VP8` were both tried there and measured as no change; the
extension now carries a comment saying so.

**Fix**, in `dialplan/default/01_coralx_push_wake.xml`: bypass only when the offer has no
video.

```xml
<condition field="${switch_r_sdp}" expression="m=video" break="never">
  <anti-action application="set" data="bypass_media=true"/>
</condition>
```

A video call is exactly the call that gets merged — `MergeTopology` sends any leg with video
to the bridge and keeps audio-only conferences on the handset — so staying in the media path
for video costs nothing that was ever available.

| Call | Bypassed | Audio codec | Merged into 3000 |
|---|---|---|---|
| audio only | yes | lyra/16000 | local mix (ADR-009), no bridge involved |
| carries video | **no** | PCMU/8000 | works: member joins `hear\|speak\|video` |

Reproduced and verified with scripted SIP UAs on two spare directory users, before and
after: a callee answering Lyra-only gets `m=audio 0`; the same callee after the fix
negotiates PCMU and the conference member has a real RTP peer. Audio-only calls still
bypass and still negotiate Lyra.

**Verified end to end through the app on 2026-09-17.** A TC15 placed two video calls and
pressed Merge. `conference 3000 list` then showed three members, every one of them
`hear|speak|video`:

```
3;sofia/internal/1011@…;Outbound Call;1011;hear|speak|video
2;sofia/internal/1005@…;1005;1005;hear|speak|video|floor|vid-floor
1;sofia/internal/1010@…;Outbound Call;1010;hear|speak|video
```

and all three mixed at `Raw Codec Activation Success L16@8000hz`, on `PCMU/8000` legs
pointed at real RTP peers — against the `L16@22050hz` / `L16/0 → 127.0.0.1:9999` the
transferred members used to get. Written up in
[`../Freeswitch_configuration_docs/conference-video/README.md`](../Freeswitch_configuration_docs/conference-video/README.md).

A second, independent audio defect was fixed on the client at the same time:
`ConferenceBridge.remix()` returned early on an empty membership, so
`setConferenceMembers(emptySet())` — how a local mix is torn down, and what a bridge merge
runs before REFERring the legs away — dropped the membership and left every `pjmedia_conf`
link open. Participants went on hearing each other after the conference had ended.

## 4c. The self-view is now movable, resizable and minimisable

`SelfPreview` was a fixed box in one corner. It is now a floating window the user owns:

- **Drag** anywhere; it snaps to the nearest corner on release. Only the corner is stored
  (`SelfPreviewPlacement`), because a pixel offset restored into a rotated display is off
  the edge of the screen.
- **Minimise / restore** from a glyph in the box's outward corner, a tap on the minimised
  preview, or a double tap at any size. The chosen size is kept across minimising.
- **Resize** from a grip on the inward corner, between 0.60 and 0.90 of the shorter edge,
  with 0.75 the default. The camera's proportion is held at every size, so no gesture can
  squash a face — PJSIP's renderer stretches to whatever bounds it is given, and the box is
  the only control there is.

  Measured on a TC15 at 720x1600 with a 16:9 camera: minimised 79x44, **smallest 431x242**,
  **default 539x303**, **largest 647x364** — 11 %, 60 %, 75 % and 90 % of the screen's
  width, every one of them at ratio 1.78.

  **0.90 is a physical ceiling, not a policy.** The preview parks in a corner with
  `Spacing.large` on each side, so on a 720px handset the box can be at most 656px — 0.911
  of the short edge. A request for "twice the maximum" is not a number this file can relax;
  it is a rectangle 1296px wide on a 720px screen. A self-view larger than the ceiling is a
  different feature: swapping the self-view and the remote picture so the camera is the
  full-screen layer and the conference becomes the floating one, which is a change to
  `CallVideo`'s two surfaces rather than to a constant.

  Two consequences of sizes this large, both real:

  - the height ceiling (`PREVIEW_MAX_HEIGHT_FRACTION`, 0.40) is now the binding constraint
    for a portrait or 3:4 frame rather than a rare guard, so the width fraction alone no
    longer predicts the box on every screen;
  - at 75 % of the width the preview covers the middle of the display, so "tap the picture
    to hide the controls" has to be aimed away from it. `SelfPreviewTest` taps near the top
    of the picture for exactly this reason.

  The control glyphs are sized from the **box** rather than from a constant
  (`CONTROL_SHARE`, held between the two design-system sizes), so the box can move a long
  way in either direction without them going out of proportion.

Three things about it are worth knowing because each was a real defect on the way:

1. **Size changes are discrete.** The child is a `SurfaceView`; every bounds change fires
   `surfaceChanged`, which republishes the surface through `setVideoWindows` →
   `applyPreview` on the single PJSIP thread. So a resize drags an *outline* and commits
   once on release. The camera is never reopened and no stream is renegotiated.
2. **A tap detector placed after a drag detector eats the drag**, because pointer events
   reach the innermost `pointerInput` first and `detectTapGestures` consumes the down.
3. **`CallScreen`'s chrome-toggle overlay is a full-screen `clickable` above the video
   layer**, so it won every pointer aimed at the preview. Fixed with explicit `zIndex`:
   video 1, chrome 2 — buttons still outrank the preview, and the preview now outranks the
   toggle.

### Two more defects the handset found that no test had

Both were invisible to the JVM suite because Robolectric's `AndroidView` does not take a
pointer the way a real `SurfaceView` does, and both made the self-view look simply broken.

**The camera was eating every gesture.** The gestures lived on the preview's box, *behind*
the `AndroidView`, and Compose's interop hands the pointer to the wrapped view first. While
the box was a fixed 3:4 with a landscape frame letterboxed inside it this was hidden: the
black bars were ordinary Compose surface, so a drag that happened to start in one worked.
Matching the box to the camera removed the bars — and with them the only draggable part of
the preview. Fixed with a gesture surface laid *over* the camera and under the controls
(`TAG_PREVIEW_SURFACE`).

**Raising the video layer broke the chrome toggle.** The self-view has to be above the
full-screen tap target that hides the call controls, and the remote picture has to be below
it — but both live in the video layer, so no `zIndex` outside it can separate them. Lifting
the whole layer made the remote `SurfaceView` swallow every tap and the controls could not
be brought back. The tap target now lives *inside* the video layer, between the remote
picture and the self-view (`CallVideo`'s `onPictureTap`).

Verified on a TC15 in a live call, on the build that was actually installed — checked by
grepping the APK's dex, after an earlier run was tested against a stale APK and the
conclusions drawn from it were wrong:

- dragged from the middle of the camera picture to another corner, both glyphs repositioning
  with it; tapping the picture still hides and shows the controls
- resized to the ceiling: 178x100 → 286x161, holding the camera's 16:9
- minimised to 98x56 with the picture still live, then restored to exactly 286x161
- `conference 3000 list` showed the member `hear|speak|video` throughout, and the only
  re-INVITEs on the wire were the ~60 s session refreshes — no UI operation renegotiated
  anything

## 5. Known limits

- **Four is a product ceiling, not a measured one.** The `wa-portrait` layout group runs to
  nine tiles and the handset pays for one stream either way. Four is where a face on a 9:20
  screen is still a face.
- **No roster.** The bridge publishes none, so `ConferenceSession.rosterAvailable` stays
  false and the participant count badge is absent rather than wrong.
- **The host is not special any more.** Once merged, everybody is an ordinary member; the
  conference outlives any one handset, which the local mix could not do.
