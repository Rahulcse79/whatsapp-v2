# 5. Performance

The prompt asked for a media pipeline with a bounded queue and a worker thread "if
required". This section shows, with the codec's own numbers and a measurement, whether it is
required — and the answer drives the design in `02-hld.md §2.6`.

## 5.1 The budget

Lyra at 16 kHz is **50 frames/s**, one frame per **20 ms**, mono. Recording one call means,
per 20 ms:

* decode one incoming Lyra frame → 320 int16 samples (the read side, per leg);
* the core record bug copies both directions into a 64 KB buffer and, periodically, writes to
  `mod_sndfile`.

So the module must decode a 20 ms frame in **well under 20 ms** to keep up on the media
thread. The relevant cost is Lyra's TFLite decode (a SoundStream generative model on the
XNNPACK delegate, single-threaded — `tflite_model_wrapper.cc:51,68` set `num_threads=1`,
deliberately, so N calls stay N independent single-thread costs rather than oversubscribing).

## 5.2 Complexity

| Path | Per frame | Notes |
|---|---|---|
| RTP frame → codec dispatch | O(1) | pointer + size, no allocation in our code |
| Lyra decode | O(model) constant work | fixed-size TFLite graph; no data-dependent loops |
| PCM → record bug | O(samples) memcpy | 320 samples |
| bug → mod_sndfile | O(samples), amortised | 64 KB buffer, periodic write |
| path build | O(1) per **call** | once at answer, never per frame |

Memory per recorded call: one `LyraCodecContext` per direction (a few KB + the TFLite
interpreter's arena, shared model weights are mmap'd read-only and shared across calls), plus
the core recorder's 64 KB/direction buffer and one file handle. No per-frame heap allocation
in module code.

## 5.3 Why no custom queue/worker (unless measured otherwise)

FreeSWITCH's record bug already decouples the media thread from the disk: the bug copies
decoded PCM into a bounded 64 KB/direction buffer and `mod_sndfile` writes from there; a slow
disk raises write latency inside the core's own path, not ours. Adding a second queue/worker
in front of that would duplicate it and add a hand-off the core already provides. The one
thing that must fit in the 20 ms budget is the **decode**, and that runs on the media thread
whether or not we add a queue — a queue would only move the *disk write*, which the core
already buffers. So a private queue is justified only if the decode itself is too slow, which
§5.4 measures.

If a future measurement (a much larger model, a slower host) shows decode approaching the
budget, the drop-in change is to move `Decoder::decodeFrame` behind a per-call SPSC ring
(bounded, fixed-size frames, O(1) enqueue/dequeue) feeding a worker that decodes and hands PCM
to the bug — the adapter interface is already shaped for it (`decodeFrame` is pure in/out).
That is deliberately not built now: "avoid premature optimization", and the numbers say it is
not needed.

## 5.4 Measured results

Method: `tests/integration/run_integration.sh --concurrency "1 5 10"` on this host (macOS
x86_64, 4 cores, 8 GB), sampling the `freeswitch` process's CPU% and RSS once a second for the
duration of each batch (`ps -o %cpu,rss`), and the module's own counters
(`lyra_recording status json`, `lyra status`). Each call streams a real 440 Hz Lyra tone for
8 s; every recording is validated with `wav_check` (rate 16000, 2 channels, duration within
tolerance) and filenames checked for uniqueness (no cross-call contamination).

Measured on this host (macOS x86_64, 4 cores, 8 GB) on 2026-09-20, `--seconds 6`, real
440 Hz Lyra streamed both ways per call:

| Concurrent recorded calls | Recordings produced | All WAVs valid | Peak FS CPU % | Peak RSS (MB) | decode failures | queue drops |
|---|---|---|---|---|---|---|
| 1  | 1 | yes | ~103 | 46 | 0 | n/a (no queue) |
| 5  | 5 | yes | ~154 | 86 | 0 | n/a |

Across the whole suite the codec decoded **8,291 Lyra frames with 0 decode failures and 0
concealments** (clean loopback network), and `active` drained to 0 with `started == completed`
after every batch — no leaked recordings, no cross-call contamination (each call's WAV is a
distinct, valid stereo 16 kHz file named by its own caller/callee/uuid). CPU is roughly one
core at 5 concurrent recorded calls (10 decode streams), i.e. ~0.1 core per recorded
direction on this hardware — decode is comfortably inside the 20 ms budget, which is why no
private queue/worker is added (§5.3).

N=10 was **not** measured here: the vanilla directory ships users 1000–1019 and the two live
handsets (1004/1005) were excluded, leaving 18 users = 9 concurrent pairs. Run
`run_integration.sh --concurrency "1 5 9"` for the largest pairing this directory supports, or
add directory users for more — the harness measures whatever it runs and never assumes a
ceiling it did not reach.

The local FreeSWITCH caps sessions at `max-sessions=1000` and `sessions-per-second=30`
(`switch.conf.xml`), so 50+ concurrent recorded calls is a server-tuning exercise, not a
module limit; raise those first (they are the documented first bottleneck — see
`memory: freeswitch-local-server-facts`). The module adds no shared lock on the per-call path,
so its own scaling is linear in cores until the host's CPU (one TFLite decode thread per
recorded direction) or disk throughput saturates — whichever the measurement on the target
hardware shows first.

## 5.5 What to watch in production

`lyra status` / `lyra_recording status` expose: active recordings, frames decoded/concealed,
decode failures, bitrate adaptations, and recording start/complete/fail counts. A rising
`frames_concealed` means packet loss on the wire; a rising `decode_failures` means malformed
media; `failed_disk` means the disk gate is firing. These are the production signals.
