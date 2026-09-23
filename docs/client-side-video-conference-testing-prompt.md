# Prompt: test the client-side video conference end to end, and fix what it finds

> Paste whole. `docs/video-call-verification-prompt.md` governs what counts as proof;
> `docs/client-side-video-conference-prompt.md` is the feature's own spec.
> Written 2026-09-22, after the architecture landed and Gate 0 passed.

## The rule

**Find it, reproduce it, root-cause it, fix it, re-verify it.** A defect reported without a
fix is half a job; a fix without a re-run is a guess. Nothing is "working" that has not been
watched working, and **nothing falls back to room 3000** — if client-side mixing breaks,
fix the client.

## Already proven (do not re-derive)

- H.264 negotiates end to end: `m=video … RTP/AVP 99`, `a=rtpmap:99 H264/90000`, both `c=`
  lines the handsets' own addresses.
- Media bypasses FreeSWITCH: `bypass_media=true`, `coralx_video_call=yes`, and **zero**
  media/RTP channel variables on the leg (an FS-mediated call has ~10).
- Audio calls still keep FreeSWITCH in the media path, so Lyra recording is unaffected.
- 1:1 CPU: **133 %** of one core with hardware H.264, against **253 %** with software VP8
  through the server. Measured across all threads — `/proc/<pid>/schedstat` alone reports
  the main thread only and will tell you 4 %.
- 21 JVM tests green on `VideoMix` / `VideoConferenceBridge`; audio conference untouched.

## Open defect, and it blocks everything below

**The remote picture freezes after the first frame.** Self-view 0.28 mean pixel change over
10 s (live), remote 0.00 (a still). Decide between the two causes before fixing anything:

- **the decoder stalls** — MediaCodec's H.264 decoder producing one frame and stopping,
  the same family as VP8/103's black picture, which is why it is priority 0; or
- **the far end stops sending.**

The video **RX packet count** separates them. It is in `Media statistics for <call>`, logged
every 15 s and at hangup — clear logcat first, the buffer rotates fast.

## The kit

| | |
|---|---|
| Handsets | 1005 Samsung E23 `.194`, 1001 M14 `.197`, 1004 TC15, 1000 TC15 |
| Server | FreeSWITCH `192.168.2.196` — **re-read it, the Wi-Fi moves mid-session** |
| Driving both ends | `adb tcpip 5555` once over USB per handset, then `adb connect <ip>:5555`. Do this for every handset you need: multi-party testing is impossible one cable at a time |
| Motion, not eyeballs | two screenshots N seconds apart, mean pixel change per region. 0.00 is a still image; a live decode always has noise |
| CPU | sum `/proc/<pid>/task/*/schedstat`, never the pid's own |

## The matrix — every row fixed, not just recorded

**1:1 (must pass before anything else)**
1. H.264 renders and *keeps* rendering, 60 s, both ends. Remote motion > 0.
2. Media statistics: video RX and TX both counting, loss < 2 %.
3. Hold → resume: the picture comes back, both ends.
4. Camera flip ×5, video off/on ×5: no freeze, no crash.

**3-party (host + 2)**
5. Merge two video calls. Each spoke sees the host + the other spoke. Nobody sees
   themselves in the grid. Screenshot from all three.
6. `show channels`: every leg `bypass_media=true`, no leg to 3000, zero FS media variables.
7. Host CPU and PSS, against the 1:1 baseline.
8. One spoke leaves: the other two are undisturbed, the grid reflows.
9. That spoke rejoins: it is back in everyone's picture — this is where a stale link hides.

**4-party (host + 3)**
10. As above with three spokes. The busiest sink carries 3 sources, inside `vid_conf`'s 4.
11. A 5th is refused cleanly, and the four already in are undisturbed.
12. Held ≥ 60 s: no re-INVITE storm, loss < 2 % on every leg, no crash.

**Teardown and lifecycle**
13. Host hangs up: every spoke ends cleanly, no native crash, camera released.
14. Background → foreground during a conference: the picture returns.
15. 10 × merge/unmerge: PSS, threads and fds within 10 % of baseline; 0 LeakCanary leaks.

## Report

| Issue | Root cause (`path:line`) | Fix | Evidence before → after | Verified |

Plus CPU, memory, packet loss, codec and media path for 1:1, 3-party and 4-party — and
anything that could not be tested, with the reason.
