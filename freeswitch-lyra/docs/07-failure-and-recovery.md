# 7. Failure and recovery behaviour

The governing rule: **a recording problem never affects the call.** The module never sets
`RECORD_HANGUP_ON_ERROR`, every failure path logs and returns, and FreeSWITCH itself is never
put at risk by bad input or a bad disk.

## 7.1 Failure matrix

| Situation | What happens | Call | Recording | Where |
|---|---|---|---|---|
| `enabled=false` | `lyra_record` returns immediately; dialplan bypasses | unchanged | none | `mod_lyra_record.cpp startRecording`, metric `skipped_disabled` |
| Call not on Lyra (fell back to PCMU) | skipped, logged | unaffected | none | `readCodecIsLyra`, metric `skipped_not_lyra` |
| Bad `lyra_recording.conf` at load | module loads **disabled**; fix + `lyra_recording reload` | unaffected | none | `mod_lyra_record_load` |
| Bad `lyra_recording.conf` at reload | reload rejected, previous config kept | unaffected | continues on old config | `lyra_recording reload`, metric `reload_failures` |
| Bad `lyra.conf` at load | **mod_lyra refuses to load** (a declared-but-broken codec is worse than none) | Lyra calls fail to negotiate cleanly rather than half-work | n/a | `mod_lyra_load` |
| Model files missing/corrupt | `probe` fails → module refuses to load; if they vanish later, encoder/decoder create fails, backs off `kCreateRetryFrames`, logs | call continues (frames dropped/concealed) | that call not recorded | `probe`, `ensureEncoder/Decoder` |
| Path escapes root / bad pattern | recording not started, logged | unaffected | none | `buildRecordingPath`, metric `failed_path` |
| Disk below `min-free-mb` | recording not started, logged | unaffected | none | `freeMegabytes`, metric `failed_disk` |
| Disk fills mid-recording | core write fails; bug stops; file finalised at its current length (partial but playable) | unaffected | partial recording kept | core `switch_ivr_async.c` record error path |
| WAV open fails (permissions) | `record_session_event` returns error, logged | unaffected | none | metric `failed_start` |
| Lyra decode rejects a frame | frame concealed (generative model), counted | unaffected | continuous audio, one concealed frame | `lyra_decode` → `Decoder::decodeFrame`, metric `decode_failures`/`frames_concealed` |
| Packet lost (RTP `SFF_PLC`) | one packet's worth concealed | unaffected | continuous | `lyra_decode` loss branch |
| Peer changes bitrate mid-call | frame size followed, logged once | unaffected | continuous | `lyra_decode` adaptation, metric `bitrate_adaptations` |
| Abrupt hangup / crash of one leg | session destroyed → bug torn down → WAV flushed and closed | ends | recording finalised | core session teardown |
| Lyra library internal error | caught by adapter `try/catch`; returns failure, never propagates | unaffected | frame dropped/concealed | `lyra_adapter.cpp` |
| Lyra `CHECK`/abort risk | prevented: every sample-rate/size input validated before the library is called; models proven at load | — | — | `probe`, `validateModelPath`, size guards |

## 7.2 Why FreeSWITCH cannot be crashed by this

* **The library's aborting `CHECK`s** (`lyra_decoder.cc:46,58,313`, `buffered_resampler.cc`)
  fire on wrong sample counts and rates. The adapter only ever calls the library with the
  exact `samplesPerFrame()` it computed, at a rate `probe()` accepted at load, so those
  branches are unreachable. Anything else returns an error before the library is touched.
* **Every library call is inside `try/catch`** in the adapter — Lyra is not written to be
  exception-safe across the boundary, so an escaping exception is turned into a clean
  failure code.
* **Bad configuration fails closed**: mod_lyra refuses to load rather than register a codec
  it cannot run; mod_lyra_record loads disabled rather than record to nowhere.

## 7.3 Recovery

* Fix a config file and `... reload` — no restart, in-flight calls unaffected.
* If the model directory was the problem, restore it and `reload mod_lyra` (when idle) or
  restart; `lyra version`/`lyra status` confirm the codec is back.
* Partial recordings from a disk-full event are valid WAVs up to the point of failure and can
  be played and concatenated; the `record_completion_cause` channel variable records why they
  stopped.
