# Prompt: a video conference mixed on the handset, with no bridge

> Paste whole as the opening message. `docs/master-engineering-prompt.md` governs *how*;
> `docs/video-call-verification-prompt.md` governs what counts as proof on hardware.
> Written 2026-09-22. Every source reference below was read, not recalled.

## The goal

A video conference where **this handset composes the picture and FreeSWITCH is not in the
media path** — the shape ADR-009 already uses for conference *audio*, extended to video.
Extension **3000 and the `whatsapp-video` bridge profile stop being used for video.**

## What already exists (verified — do not re-derive)

- `pjmedia` has a video mixer: `third_party/pjproject/pjmedia/src/pjmedia/vid_conf.c`. It
  tiles **1, 2, 3 or 4** sources into one canvas and picks the arrangement itself —
  2×2 landscape, stacked portrait (`vid_conf.c:1483-1558`). You compute no layout.
- **The ceiling is 4 and it is silent.** `pjmedia_rect_size tr_size[4]` and
  `for (i = 0; i < cp->transmitter_cnt && i < 4; ++i)`: a 5th source gets no render state
  and is simply not drawn. No error, no log.
- The Java API is **already in the APK** — no SWIG rebuild, no `--reuse-native` exception:
  `Call.getEncodingVideoMedia(int)` / `getDecodingVideoMedia(int)` (`Call.java:175,190`)
  and `VideoMedia.startTransmit(sink, VideoMediaTransmitParam)` (`VideoMedia.java:93`).
- **Nothing in the app uses `VideoMedia` today** — `grep VideoMedia data/sip/src/main` is
  empty. The video bridge is used 1:1 only, decoder → renderer.
- `startTransmit` here is **asynchronous**, unlike the audio one: completion arrives on
  `Endpoint.onVideoMediaOpCompleted()`. A roster must not read as connected on return.

## GATE 0 — answer this before writing any feature code

**Does hardware H.264 both render and cost little on these handsets?** Everything below
depends on it: 3 encodes + 3 decodes of software VP8 will not fit — one 1:1 software-VP8
call already costs ~230 % CPU (measured; the 3-way figure is an extrapolation, not a
measurement). Removing FreeSWITCH from the media path is what makes H264/99 (MediaCodec)
usable at all, since `mod_av` does not load on the bridge and
`PJMEDIA_HAS_OPENH264_CODEC 0`.

The risk is concrete: MediaCodec's **VP8 decoder renders black** on these phones, which is
why VP8/103 is pinned to `CodecPriorities.DISABLED`. Its H.264 decoder is **untested**.

Experiment, ~20 minutes, no feature code:

1. Force H264/99 on a **1:1** call between two handsets, `bypass_media` so FS is out of the
   media path.
2. Hold 60 s. Does a picture actually appear at both ends? (Screenshot + `Video is WxH`.)
3. `cat /proc/<pid>/schedstat` before and after — CPU per second of call.

**If the picture is black, stop and report it: client-side video conferencing is off the
table on this hardware and 3000 stays.** If it renders at roughly a third of a core,
continue.

## The wiring

Two ports per leg: a **decoder** (what they send) and an **encoder** (what we send them).
Each sink takes every source **except its own decoder**, so nobody is sent their own face —
better than the bridge, which sends one shared canvas with everyone in it.

```
camera ──────────┐
decoder(A) ──┐   ├──▶ encoder(A)   A sees: camera, B, C
decoder(B) ──┼───┼──▶ encoder(B)   B sees: camera, A, C
decoder(C) ──┘   ├──▶ encoder(C)   C sees: camera, A, B
                 └──▶ renderer     we see: A, B, C
```

Mirror the audio mix rather than inventing a second arrangement: `deviceControlTargets` /
`acrossTheMix` in `PjsipSipEngine.kt` already decide membership, and the engine — not the
screen — owns it. On the PJSIP thread, like every other stack operation.

## DECIDE before coding

1. **Who mixes.** Host-and-spokes, as ADR-009's audio mix already is — the host composes
   and every other member sees one stream. Say so in the model; do not let two handsets
   both believe they are mixing.
2. **What the 5th member gets.** The mixer drops them silently. The app must not: refuse
   the 5th with a message, or keep them audio-only. Never show a roster of 5 over a
   picture of 4.
3. **What a member sees on the way in.** Between joining and the first composed frame
   there is no remote picture; `CallDisplay.showsLocalPreview` already covers that case.
4. **Teardown.** A leg that ends must have its ports disconnected from every sink before
   the call object is released — a connection into a freed port is a native crash, the
   same rule `conference.remove(callKey)` follows for audio.

## Scope

**In:** video mixing across the legs this device already holds; the 4-member ceiling stated
in the UI; the existing Merge button driving it; teardown.

**Out (say so, do not silently skip):** more than 4; picking the layout; an SFU;
re-encoding at different sizes per member; changing the audio mix, which already works.

## Acceptance — each one on hardware, per `video-call-verification-prompt.md`

1. Gate 0 answered with the two numbers and a screenshot.
2. Three handsets in one conference: each sees the other two, nobody sees themselves in
   the grid (their own camera is the self-view, as now). Screenshot from each.
3. Held ≥ 60 s: no re-INVITE storm, no crash, `Media statistics` loss < 2 % on every leg.
4. `fs_cli -x "show channels"` shows the legs **bypassing media** — FreeSWITCH carries
   signalling only. This is the claim the whole change exists to make.
5. Host CPU measured and reported (`schedstat`), against the same 3-way through 3000.
6. A member leaves: the grid reflows, the others are undisturbed, no crash.
7. A 5th caller gets whatever (2) decided, and the four already in are undisturbed.
8. JVM tests for the membership arithmetic — which legs transmit to which sink — with no
   stack involved.

## Deliverable

Branch, one commit per step, Gate 0's numbers first. A table: Issue | Root cause | Fix |
Evidence | Verified. Plus, stated plainly: what this costs in CPU and uplink against the
bridge, and — if Gate 0 fails — why it cannot be done on this hardware.
