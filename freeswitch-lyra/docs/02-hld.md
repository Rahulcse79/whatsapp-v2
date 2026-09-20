# 2. High-Level Design

## 2.1 The one diagram that matters

```
   Handset A (PJSIP+Lyra)                 FreeSWITCH 1.10.11                 Handset B (PJSIP+Lyra)
   ─────────────────────                 ──────────────────                 ─────────────────────
        │  INVITE lyra/16000                    │                                   │
        ├──────────────────────────────────────▶  negotiate: both legs on Lyra     │
        │                                       ├──────────── INVITE lyra/16000 ────▶│
        │                                       │◀──────────── 200 lyra/16000 ───────┤
        │◀──────────────── 200 lyra/16000 ──────┤                                   │
        │                                       │                                   │
        │═══ RTP Lyra 20ms frames ═════════════▶│ read: decode ONLY for the bug     │
        │                                       │   ┌──────────────────────────┐    │
        │                                       │   │ mod_lyra  Decoder→PCM     │    │
        │                                       │   └────────────┬─────────────┘    │
        │                                       │   record media bug (core)         │
        │                                       │   ├─ read stream  (A→B) ──┐        │
        │                                       │   ├─ write stream (B→A) ──┤        │
        │                                       │   ▼                       ▼        │
        │                                       │  mod_sndfile  ── stereo WAV ── disk│
        │                                       │                                   │
        │                                       │  forward original Lyra frame ─────▶│  (no re-encode: same impl)
        │◀════ RTP Lyra 20ms frames ════════════│◀══════════════════════════════════│
```

When recording is **disabled** the whole middle disappears: the dialplan sets
`bypass_media=true`, FreeSWITCH relays SIP only, and RTP flows handset-to-handset exactly
as it does in production today.

## 2.2 Components and responsibilities

| Component | Responsibility | Owns |
|---|---|---|
| **`mod_lyra`** (codec module) | Register `lyra/16000` with FreeSWITCH; decode Lyra→PCM and encode PCM→Lyra on demand; conceal lost frames; report status/metrics; hot-reload path/dtx. | One `switch_codec_interface`; a per-call `LyraCodecContext` holding a lazily-built `Encoder`/`Decoder`; a module-wide config snapshot + metrics. |
| **`lyra_adapter`** (inside mod_lyra) | The only code that touches the Lyra library; translate between int16 PCM / byte packets and `LyraEncoder`/`LyraDecoder`; guard the library's aborting `CHECK`s behind validation and `try/catch`. | `Encoder`, `Decoder`, `probe`, `validateModelPath`. |
| **`mod_lyra_record`** (policy module) | Decide whether a call is recorded; compute a safe path; check disk; start/stop the **core** recorder; count outcomes; hot-reload; do nothing when disabled. | A module-wide config snapshot + metrics; the `lyra_record`/`lyra_record_stop` apps and `lyra_recording` API. |
| **`record_path`** (inside mod_lyra_record) | Turn untrusted caller/callee/UUID + time into a path proven to be inside the configured root. | `sanitizeAtom`, `expandFilename/Directory`, `buildRecordingPath`. |
| **FreeSWITCH core** (unmodified) | `switch_ivr_record_session_event` — the media bug that mixes/splits directions, buffers, writes the WAV via `mod_sndfile`, finalises on close, and is torn down with the session. | The recording engine itself. |
| **Dialplan** (`01_coralx_push_wake.xml`, `coralx.xml`) | When recording is enabled, keep FreeSWITCH in the media path, force Lyra on both legs, and arm `lyra_record` on answer; otherwise bypass as today. | The per-call recording decision. |

## 2.3 Why the codec module is enough (and re-encode is not on the record path)

FreeSWITCH's read path (`switch_core_io.c:407-758`) decodes an incoming frame **only** when
a media bug, resampler, or transcode needs PCM. With a Lyra bug attached it decodes the
frame for the bug and then, because both legs share the codec implementation, forwards the
**original encoded frame** to the far leg (`if (do_bugs) goto done;`, line 758). The write
path mirrors this (`switch_core_media.c:16158`). So:

* **No transcode on the media path.** The relayed audio is the sender's own Lyra bytes; the
  decoder's PCM feeds the recorder and nothing else. This is what keeps recording from
  degrading the call.
* **The encoder runs only toward a Lyra leg that needs synthesised audio** — a tone, an IVR
  prompt, voicemail, or the conference mixer. It is implemented and tested, but it is not
  what makes recording work.

## 2.4 Threading, ownership, lifecycle

* **Per-codec instance** — one `LyraCodecContext` per direction per call. FreeSWITCH
  serialises `encode`/`decode`/`destroy` on that instance behind `codec->mutex`
  (`switch_core_codec.c`), so the context needs no lock of its own. It is a C++ object
  `new`ed in `init` and `delete`d in `destroy`; the `Encoder`/`Decoder` are `unique_ptr`
  members built lazily on first use and freed with the context (RAII). A collected native
  peer is impossible because ownership is explicit and single.
* **Module-wide state** — the config snapshot is a `shared_ptr<const Config>` swapped whole
  under a `std::mutex` on reload; in-flight calls keep the snapshot they started with.
  Metrics are `std::atomic` counters (relaxed; they are statistics, not synchronisation).
* **Recording bug** — created and owned by the core on the call's session; runs on the
  session's media thread(s); torn down automatically when the session is destroyed, which
  flushes and closes the WAV (`switch_ivr_async.c` record teardown). `mod_lyra_record` holds
  only the path string on the channel so it can stop the right bug.
* **Concurrency** — each call has its own session, codec contexts, bug, file handle and path.
  There is no shared per-call state, so N calls are N independent pipelines; the only shared
  objects are the immutable config snapshot and the atomic counters. No cross-call
  contamination is possible by construction (proved in the concurrency integration test).

## 2.5 Backpressure and shutdown

* **Backpressure** — the core recorder buffers up to `SWITCH_DEFAULT_FILE_BUFFER_LEN`
  (64 KB) per direction and writes on the media thread; `mod_sndfile` writes are short and
  local. At 16 kHz mono that is ~2 s of headroom. A slow disk raises write latency, which
  the core recorder handles (it is the same path every FreeSWITCH deployment records on); a
  full disk is caught up front by the `min-free-mb` gate and, if it fills mid-call, by the
  core's write-error handling — which stops the recording, never the call (we never set
  `RECORD_HANGUP_ON_ERROR`). See `07-failure-and-recovery.md`.
* **Shutdown** — on `mod_lyra_record` unload the config snapshot is released; active
  recordings belong to their sessions and are closed when those sessions end. On `mod_lyra`
  unload the codec interface is withdrawn (FreeSWITCH refuses to unload a codec that is in
  use). No thread is owned by either module, so there is nothing to join.

## 2.6 What is deliberately NOT built

* No custom queue/worker/ring-buffer for audio — the core recorder already is one, tuned and
  proven. A private queue is added only if `05-performance.md` shows the decode is too slow
  for the media thread; the measurement there says it is not.
* No hand-written WAV encoder — `mod_sndfile` writes and finalises the WAV, including the
  header fix-up on close.
* No RTP handling — `mod_sofia`/the core RTP stack receive and send; the module never sees a
  socket.
