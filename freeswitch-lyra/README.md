# freeswitch-lyra — server-side recording of Lyra-to-Lyra calls

Two FreeSWITCH modules that let the **server** record Lyra ↔ Lyra calls to WAV, controlled
entirely by configuration, with zero change to call behaviour when it is switched off.

* **`mod_lyra`** — a FreeSWITCH codec module wrapping Google **Lyra v1.3.2** (the same codec
  the handsets use, from `third_party/lyra`). It gives FreeSWITCH the ability to decode Lyra
  to PCM (and encode it back), which is the prerequisite for recording a codec the server
  otherwise does not understand.
* **`mod_lyra_record`** — the recording policy. It reads `lyra_recording.conf.xml`, decides
  whether a call should be recorded, builds a safe path, and drives FreeSWITCH's own
  battle-tested recording engine. It does nothing when disabled.

> **Read [`docs/01-analysis.md`](docs/01-analysis.md) first.** The original request assumed
> Lyra-to-Lyra calls reach FreeSWITCH ("RTP passthrough") — they do not; today they
> `bypass_media`, so the server never sees the audio. That analysis explains what was
> corrected and why the design is a codec module + the core media bug rather than a
> hand-rolled decode/re-encode/record pipeline.

## How it behaves

| `enabled` | Behaviour |
|---|---|
| `false` (default) | Identical to today: audio calls `bypass_media`, RTP flows handset-to-handset, no decoder, no file, no overhead. |
| `true` | FreeSWITCH stays in the media path for Lyra calls, decodes each frame to PCM for the recorder, writes a stereo WAV under the configured root, and relays the original Lyra frames to the far end unchanged. |

## Quick start

```bash
cd freeswitch-lyra
CMAKE=/opt/local/bin/cmake ./scripts/build-lyra.sh          # build the Lyra archive (once)
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release && cmake --build build -j
ctest --test-dir build --output-on-failure                 # unit tests
sudo ./scripts/install.sh --reload                         # install + load into FreeSWITCH
# edit conf/autoload_configs/lyra_recording.conf.xml: enabled=true, set recording-path
# apply the dialplan change in docs/06 §6.5, then:
fs_cli -x "reloadxml" && fs_cli -x "lyra_recording reload"
./tests/integration/run_integration.sh                      # prove it end-to-end
```

## Layout

```
docs/     01 analysis · 02 HLD · 03 LLD · 04 config · 05 performance
          06 build & deploy · 07 failure/recovery · 08 security · 09 verification
src/      mod_lyra (codec + Lyra adapter) · mod_lyra_record (policy + safe paths) · common
conf/     autoload_configs (lyra.conf, lyra_recording.conf) · dialplan (merged push-wake)
scripts/  build-lyra.sh · install.sh
tests/    unit (no FS, no network) · integration (real SIP/RTP Lyra calls through FS)
cmake/    FindFreeSWITCH · FindLyraArchive
```

## Design guarantees

* **Nothing hardcoded** — paths, extensions, UUIDs, bitrate, sample rate, format, filename
  pattern are all configuration; the only fixed values are the ones the Lyra codec itself
  fixes (mono, 20 ms, 16 kHz native).
* **Recording failure never touches the call** — disk full, bad path, decoder failure: the
  call continues, the failure is logged and counted (`docs/07`).
* **Untrusted input is contained** — caller/callee/UUID are sanitised and the path is proven
  inside the configured root, with unit tests for the traversal attacks (`docs/08`).
* **No FreeSWITCH crash from bad input** — the Lyra library's aborting `CHECK`s are guarded by
  validation and the codec is proven at load (`docs/07 §7.2`).
* **Built against the installed FreeSWITCH** (1.10.11, `pkg-config`), same C++ runtime,
  private symbols; the Lyra closure is the same CMake build the Android app uses, so the two
  ends cannot drift (`docs/06`).

Verified environment: FreeSWITCH 1.10.11 at `/usr/local/freeswitch`, Lyra v1.3.2 from
`third_party/lyra`, macOS x86_64. See `docs/01-analysis.md` for the full ground-truth table.
