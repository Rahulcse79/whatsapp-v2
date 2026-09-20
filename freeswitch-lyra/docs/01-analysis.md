# 1. Analysis — what the request assumed, what is actually there, and what was built

Everything below was verified on 2026-09-20 against the running system, not assumed.

## 1.1 The environment, as found

| Fact | Evidence |
|---|---|
| FreeSWITCH **1.10.11-release** (git `f24064f7c9`, 2023-12-22), x86_64, macOS 12.7.6 | `fs_cli -x version`; source tree at `~/Documents/GitHub/freeswitch` is the same commit |
| Prefix `/usr/local/freeswitch`, config in `etc/freeswitch`, modules in `lib/freeswitch/mod/*.so`, headers + `lib/pkgconfig/freeswitch.pc` installed | `ls`, `cat freeswitch.pc` |
| Built with Apple clang (`gcc`/`g++` aliases), `-g -O2`, MacPorts deps in `/opt/local`; **libc++** already linked into `libfreeswitch.dylib` | `config.log`, `otool -L` |
| Codecs registered: G.711 u/a, L16, G.723.1, G.729, AMR, Speex, B64, VP8/VP9, PROXY. **No Lyra, no Opus, no G.722** | `core.db` `interfaces` table (`show codec` prints `0 total` on this instance — the core DB view is stale after a swap-out; the sqlite table and `module_exists` are authoritative) |
| Recording infrastructure present: `mod_sndfile` (WAV), `mod_dptools` (`record_session`), `mod_commands` (`uuid_record`), `mod_native_file` | `module_exists`, module dir |
| **The live 1004→1005 Lyra call: both legs `signal_bridge`, state `CS_HIBERNATE`, `read_codec`/`write_codec` empty, `variable_bypass_media: true`, `audio_media_flow: disabled`** | `show channels as json`, `uuid_dump` |
| The negotiated SDP (both sides): `m=audio 4000 RTP/AVP 96 120`, `a=rtpmap:96 lyra/16000`, `a=fmtp:96 bitrate=3200`, `a=rtpmap:120 telephone-event/16000` | `uuid_dump … switch_r_sdp / switch_m_sdp` |
| Lyra **v1.3.2** vendored at `third_party/lyra` with its whole build closure (TFLite 2.11, XNNPACK, absl, glog, audio_dsp, …) and a working CMake build for it (`pjsip/lyra/CMakeLists.txt`, Android) | `lyra_config.cc:28-34`, the closure trees, `pjsip/build-native.sh` |
| Lyra frame: **20 ms** (`kFrameRate = 50`), mono, internal 16 kHz; quantized bits {64,120,184} → packet sizes **8 / 15 / 23 bytes** → bitrates **3200 / 6000 / 9200** | `lyra_config.cc:42-47`, `lyra_config.h GetPacketSize/GetBitrate` |
| RTP payload = the raw Lyra frames back-to-back, `bitrate/(50*8)` bytes each, one per 20 ms packet at the default ptime; `bitrate=` in the fmtp is the size the *sender of that SDP* wants to receive | `pjmedia/src/pjmedia-codec/lyra.cpp:530-565 (parse), 430-450 (fmtp)` |
| The Android app uses pjmedia's Lyra at the library default bitrate **3200** and only 16 kHz; DTX ("vad") default on in pjmedia | `RealPjsipCoreGateway.kt:2552-2565`, `pjmedia-codec/config.h:584,615` |

## 1.2 What was wrong or unsuitable in the request

1. **"Lyra-to-Lyra calls already working through FreeSWITCH using RTP passthrough."** They are not. They work because the dialplan sets `bypass_media=true` for every audio-only handset call (`dialplan/default/01_coralx_push_wake.xml`, amended 2026-09-16). FreeSWITCH relays SIP only; RTP flows handset-to-handset. **Nothing on the server ever sees a Lyra packet.** No module, however good, can record a stream that never arrives. Server-side recording therefore requires taking FreeSWITCH back into the media path for the calls to be recorded — and that is a dialplan change that has to be part of the design, not an afterthought.

2. **"Implement a proper codec integration rather than dumping RTP" — with a full decode → PCM → *re-encode* → RTP pipeline for the outgoing media.** The re-encode leg is wrong for this problem. FreeSWITCH already bridges two legs that negotiated the *same* codec implementation without transcoding: the read path decodes a frame *only* for media bugs and then forwards the original encoded frame (`switch_core_io.c:758 if (do_bugs) goto done;`); the write path does the same (`switch_core_media.c:16158`). So with a Lyra codec module loaded, a Lyra↔Lyra bridge stays a frame relay; the decoder runs only while a recording bug is attached, and the encoder never runs on the recording path. Encoding is still implemented (tones, IVR prompts, voicemail and the conference mixer need it toward a Lyra leg), but it is not what makes recording work and must not be put on the forwarding path.

3. **A custom recording engine (queue, worker thread, hand-written WAV writer, disk-full handling …).** FreeSWITCH already has the production recording engine: `switch_ivr_record_session_event` (`switch_ivr_async.c:2927`) — a media bug that mixes both directions (or keeps them as stereo), buffers 64 KB before each disk write, writes through `mod_sndfile` (correct WAV header, finalised on close), creates the directory tree, resamples, survives hangup (the bug is torn down with the session and the file closed), fires `RECORD_START`/`RECORD_STOP`, sets `record_seconds`/`record_ms`/`record_completion_cause`, and is driven by `uuid_record` for pause/mask/stop. It is what `mod_conference`, `record_session` and every commercial FreeSWITCH deployment use. Re-implementing it would add more code than the whole codec module and lose all of that. The right shape is **codec module + core media bug**; a private queue/worker is only justified if measurement shows the Lyra decode is too slow for the media thread — see `05-performance.md` for the numbers that decide this.

4. **"Verify Lyra codec registered" / "codec module in C++ against the installed FreeSWITCH."** Fine in itself, but the module must be built against the *installed* 1.10.11 headers (`pkg-config freeswitch`), with the same compiler family and the C++ runtime FreeSWITCH already links (libc++), and must not export the static TFLite/absl/glog symbols into the process (modules load `RTLD_LOCAL` unless they ask for `SMODF_GLOBAL_SYMBOLS`; this one does not).

5. **The example config is inconsistent.** `lyra_recording_enabled`/`lyra_recording_path` in one place, `enabled`/`recording_path` in another; `create_call_directory` has no defined meaning; the example path `20260919/192530_1000_1001_<uuid>.wav` implies a date directory that the `filename_pattern` cannot express. Fixed: one file `lyra_recording.conf.xml` with `directory_pattern` (default `{date}`) and `filename_pattern` (default `{time}_{caller}_{callee}_{uuid}`), every token defined in `03-lld.md`.

6. **Guarding against process aborts is a requirement the prompt did not state.** Lyra is built on glog and uses `CHECK_EQ` (`lyra_decoder.cc:46,58,313`, `buffered_resampler.cc:50-138`); a failed CHECK aborts the process — i.e. FreeSWITCH. Every input that can reach those checks (sample rate, sample count per call) is validated before the library is touched, and the module refuses to load if a decoder cannot be created with the configured models.

7. **`bypass_media` ⇒ Lyra was a workaround, and it has a second cost** the recording change removes: a bypassed leg cannot be moved into the conference mixer (`m=audio 0`, documented in `docs/Freeswitch_configuration_docs/conference-video/README.md`). Once FreeSWITCH has a Lyra codec, the video-only exception in the dialplan is no longer needed either — audio-only *unrecorded* calls keep bypassing (zero change), everything else goes through a server that now understands the codec.

## 1.3 Constraints carried over from the prompt and honoured

* Every operational value configurable; sensible defaults only where the codec itself fixes them (Lyra is mono, 20 ms, 16 kHz native — those are facts, not settings).
* `enabled=false` ⇒ byte-identical behaviour to today: bypass stays on, no bug, no decoder, no file.
* No hardcoded paths, extensions, UUIDs, bitrates; caller/callee/UUID treated as untrusted, filenames sanitised, root directory authoritative.
* Recording failure never touches the call (`RECORD_HANGUP_ON_ERROR` is never set; every failure path logs and returns).
* Modern C++17 (the closure is C++17), RAII, `unique_ptr`, `atomic`, `mutex`; no globals besides the module singletons FreeSWITCH's loader requires (one `struct` per module, mutex-guarded config snapshot).
* Build: CMake against `pkg-config freeswitch`, `-Wall -Wextra -Wpedantic`, hardening flags where the toolchain supports them; the Lyra closure built by the *same* CMake file the Android app uses so the two ends cannot drift.
* Unit tests without network or FreeSWITCH; integration tests that drive real SIP calls with real Lyra RTP through the local FreeSWITCH; measurements instead of claims.

## 1.4 The decision

```
                    recording disabled (default today)              recording enabled
handset A ─SIP─▶ FreeSWITCH ◀─SIP─ handset B            handset A ─SIP─▶ FreeSWITCH ◀─SIP─ handset B
handset A ◀════════ RTP (Lyra) ═══════▶ handset B          handset A ◀═RTP═▶ FreeSWITCH ◀═RTP═▶ handset B
              (bypass_media=true, unchanged)                        │ same codec both legs:
                                                                    │ encoded frames relayed, no transcode
                                                                    ▼ media bug (record_session)
                                                              mod_lyra decode → PCM → mod_sndfile → WAV
```

* **`mod_lyra`** — a codec module: registers `lyra/16000` (and other rates on request) with the Lyra v1.3.2 library, thin adapter, lazy encoder/decoder creation, PLC on loss, defensive against the library's CHECKs.
* **`mod_lyra_record`** — the recording policy: reads `lyra_recording.conf.xml`, exposes the `lyra_record` dialplan application and the `lyra_recording` API (status/metrics/reload), computes a safe path, starts the core recorder with the configured channel count / sample rate / format, counts outcomes, and does nothing at all when disabled.
* **Dialplan** — `01_coralx_push_wake.xml` and `coralx.xml` ask the module whether recording is enabled and only then keep FreeSWITCH in the media path and arm `execute_on_answer=lyra_record`.
