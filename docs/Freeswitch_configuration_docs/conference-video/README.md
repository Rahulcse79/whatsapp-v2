# Conference video — why room 3000 now shows everybody

Canonical copies of the three FreeSWITCH edits made on **2026-09-15**. Each file is the
exact block that was inserted, so a rebuilt server can be brought back to this state by
pasting them in and running `reloadxml`. The changed files on the Mac keep `.bak` copies
next to them (`…bak.20260915-132219`), which is the rollback.

## The defect

A video conference on extension `3000` showed **one** face to everybody. Three handsets
joined, all three saw the same person, each with its own self-view in the corner.

`3000` routed to `conference 3000@default`, and the `default` profile in
`conference.conf.xml` sets no `video-mode`. mod_conference's default is **passthrough**:
it forwards the current floor holder's video to every member and nobody else's. So the
picture was correct and complete — it was a picture of one person, because that is all
the bridge was sending. Nothing in the app could have changed it.

Confirmed on the running server: conferences on `3000@default` logged no canvas and no
layout lines at all, which is what passthrough looks like.

## The three edits

| File on the server | Copy here | What went in |
|---|---|---|
| `autoload_configs/conference.conf.xml` | [`conference.conf.whatsapp-video.profile.xml`](conference.conf.whatsapp-video.profile.xml) | a new `whatsapp-video` profile: `video-mode=mux`, portrait canvas, one shared encode |
| `autoload_configs/conference_layouts.conf.xml` | [`conference_layouts.wa-portrait.xml`](conference_layouts.wa-portrait.xml) | layouts `wa-1` … `wa-9` and the group `wa-portrait` |
| `dialplan/default/02_cisco_features.xml` | [`02_cisco_features.conference-3000.xml`](02_cisco_features.conference-3000.xml) | `conference $1@default` → `conference $1@whatsapp-video` |

### Why a new profile and not `video-mcu-stereo`

The stock video profiles are 48 kHz **stereo** with a 1920×1080 landscape canvas at 30 fps.
The handsets negotiate PCMU or PCMA (see [06](../06-modules-and-codecs.md)) and are held
upright, so all of that is work spent on something nobody receives. `whatsapp-video` is
8 kHz mono, 720×1280 portrait, 15 fps.

### Why a new layout group and not `group:grid`

The stock `grid` group is built for a landscape canvas. Its two-person layout, `2x1`, puts
the images side by side at `y=90` — a band across the middle of the canvas with the top
and bottom quarters empty. On a 9:20 handset that is two thin strips with black above and
below them. `wa-portrait` stacks instead: two people are one above the other, each filling
half the screen; three are two across the top and one spanning the bottom; four are a 2×2.

Every image carries `zoom="true"`, which crops a frame to fill its tile instead of
letterboxing it inside one. Without it a 16:9 camera frame in a portrait tile is mostly
black border, which defeats the point of composing a portrait canvas at all.

### Codec constraint worth knowing

`mod_av` does not load on this build, so H.264 cannot be decoded and therefore cannot be
composed. VP8 can: it is in the core as `switch_vpx.c` against libvpx 1.8.1, and both the
`internal` profile and the app offer it. **A member that can only do H.264 will be heard
and will not be seen** — audio-only members are excluded from the canvas deliberately
(`video-required-for-canvas`), so they leave no black square in the grid.

## Checking it

```bash
/usr/local/freeswitch/bin/fs_cli -x "reloadxml"
/usr/local/freeswitch/bin/fs_cli -x "bgapi originate loopback/3000/default &park()"
/usr/local/freeswitch/bin/fs_cli -x "conference list"
```

The flag list must contain **`video_muxing`**. `video_floor_only` must not appear — that
is the flag which means "send the floor holder and nobody else". Tear the test room down
with `conference 3000 hup all`.

## The second defect: every merged member was deaf (fixed 2026-09-16)

Video was fixed on 2026-09-15 and audio was still broken, in a way that looked like the
app's fault and was not. In a merged conference **nobody could hear anybody**.

### What the log said

Two members of the 15:43 test, both REFERred into the room by a merge:

```
Set Codec sofia/internal/1003@... L16/0 20 ms 441 samples 352800 bits 1 channels
AUDIO RTP [sofia/internal/1003@...] 172.19.171.214 port 17726 -> 127.0.0.1 port 9999 codec: 97
```

and the one member that dialled `3000` itself:

```
Set Codec sofia/internal/1005@... PCMU/8000 20 ms 160 samples 64000 bits 1 channels
AUDIO RTP [sofia/internal/1005@...] 172.19.171.214 port 16422 -> 172.19.171.14 port 4044 codec: 0
```

`L16/0` is not a codec — clock rate zero — and `127.0.0.1:9999` is FreeSWITCH's black
hole. The transferred members were in the conference, on the canvas, and had no audio
path at all. The one member with real audio had nobody to hear.

### Why

`bypass_media=true` in [`01_coralx_push_wake.xml`](01_coralx_push_wake.audio-only-bypass.xml)
applied to **every** handset-to-handset call. That is what lets two handsets agree on
Lyra (ADR-008) — and it leaves FreeSWITCH holding a leg whose negotiated audio codec is
one it does not have. `show codec` on this build lists no Lyra, no Opus and no G722.

Merging REFERs that leg into `3000`. FreeSWITCH transfers the existing channel and has to
take media back for the mixer, and the only audio codec on the dialog is Lyra — so it
offers `m=audio 0 RTP/AVP 19`, the stream killed, and video alone. Reproduced with
scripted UAs: a callee that answers `lyra/16000` gets `m=audio 0`; the same callee
answering PCMU gets a working stream.

**Nothing in the `3000` extension can repair this.** That re-INVITE is built during
`switch_ivr_session_transfer`, before the destination extension runs. `media_reset` and
`absolute_codec_string=PCMU,PCMA,VP8` were both tried there and both measured as no
change; the extension carries a note saying so.

### The fix

Media bypass is now conditional on the offer carrying no video:

```xml
<condition field="${switch_r_sdp}" expression="m=video" break="never">
  <anti-action application="set" data="bypass_media=true"/>
</condition>
```

A video call is exactly the call that gets merged — `MergeTopology` sends any leg with
video to the bridge and keeps audio-only conferences on the handset — so staying in the
media path for video costs nothing that was ever available. The audio becomes PCMU, which
is what the room mixes at anyway, and VP8 passes through either way.

An audio call is untouched: still bypassed, still Lyra, still conferenced on the device
with no server in the media path.

| Call | Bypassed | Audio codec | Merge into 3000 |
|---|---|---|---|
| audio only | yes | lyra/16000 | local mix, ADR-009 — no bridge involved |
| carries video | **no** | PCMU/8000 | works: member joins `hear|speak|video` |

One consequence worth knowing: a **Lyra-only** account now gets 488 on a video call
rather than connecting, because FreeSWITCH negotiates and has no Lyra. Keep PCMU in the
account's codec list, which `CodecPreferences.DEFAULT` does.

## The other server

The handsets also carry accounts on the office server `192.168.80.145`, and the same
passthrough default very probably applies there. It cannot be fixed from here, and it is
not the first thing wrong with video on that box: **it refuses TCP 5060, and a video INVITE
is too large for the 1500-byte path**, so no video call reaches it at all. See
[office-server-192.168.80.145.md](office-server-192.168.80.145.md) for the measurements and
the handover.

## The client half

The server composing a grid is only half of it. PJSIP's Android renderer draws every frame
onto a full-screen quad and stretches it to whatever bounds the view has
(`opengl_dev.c:271-300`), with no aspect-ratio correction anywhere in the path — so a
composed portrait canvas on a 9:20 screen came out stretched. The app now reads the decoded
frame size out of PJSIP's own video window and sizes the surface to it: see
`VideoSurfaceController.videoSizes`, `VideoLayout.fit` / `VideoLayout.cover`, and
`CallVideo`.
