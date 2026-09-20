# 3. Low-Level Design

Types, ownership, and the exact FreeSWITCH/Lyra calls each one makes. Verified against the
installed FreeSWITCH 1.10.11 headers and Lyra v1.3.2 sources.

## 3.1 File / folder structure

```
freeswitch-lyra/
├── CMakeLists.txt                 build both modules + unit tests (pkg-config freeswitch)
├── cmake/
│   ├── FindFreeSWITCH.cmake       locate the installed FreeSWITCH (headers, mod dir)
│   └── FindLyraArchive.cmake      locate build/lyra-prefix/lib/liblyra.a
├── conf/
│   ├── autoload_configs/
│   │   ├── lyra.conf.xml                  mod_lyra settings
│   │   ├── lyra_recording.conf.xml        mod_lyra_record settings
│   │   └── modules.conf.xml.snippet       the two <load> lines
│   └── dialplan/
│       ├── default/01_coralx_push_wake.xml   push-wake + recording hook (merged)
│       └── coralx.xml                         resume/timeout + recording hook (merged)
├── src/
│   ├── common/params.h            FreeSWITCH-free config parsing helpers (unit-tested)
│   ├── mod_lyra/
│   │   ├── lyra_adapter.{h,cpp}   the ONLY code that includes the Lyra library
│   │   ├── lyra_codec_config.{h,cpp}  parse+validate lyra.conf (pure)
│   │   └── mod_lyra.cpp           the codec module (FreeSWITCH glue)
│   └── mod_lyra_record/
│       ├── record_config.{h,cpp}  parse+validate lyra_recording.conf (pure)
│       ├── record_path.{h,cpp}    safe path/filename builder (pure, security-critical)
│       └── mod_lyra_record.cpp    the recording-policy module (FreeSWITCH glue)
├── scripts/
│   ├── build-lyra.sh              build the Lyra closure for the host → build/lyra-prefix
│   └── install.sh                 install .so + models + configs into a FS prefix
├── tests/
│   ├── unit/                      no FreeSWITCH, no network (ctest)
│   └── integration/              real SIP/RTP calls through the local FreeSWITCH
└── docs/                          this design set
```

## 3.2 mod_lyra — types

```
LyraCodecContext                       // one per direction per call; codec->private_info
  shared_ptr<const CodecConfig> cfg    // the snapshot this call started with
  int sampleRate, framesPerPacket
  size_t samplesPerFrame, bytesPerPcmFrame
  int encodeBitrate, decodeBitrate     // decodeBitrate follows the peer's frame size
  unique_ptr<Encoder> encoder          // lazy; built on first encode
  unique_ptr<Decoder> decoder          // lazy; built on first decode
  uint32_t encoderRetryIn, decoderRetryIn   // backoff after a create failure
```

Lyra adapter (isolates the library):

```
Encoder::create(rate, bitrate, dtx, modelPath) -> unique_ptr<Encoder>   // nullptr on failure, noexcept
Encoder::encodeFrame(pcm, samples, out, cap)   -> optional<size_t>       // 0 = DTX silence
Decoder::create(rate, modelPath)               -> unique_ptr<Decoder>
Decoder::decodeFrame(packet, bytes, pcm, samples) -> bool                // conceals on reject
Decoder::conceal(pcm, samples)                 -> bool                    // PLC
probe(modelPath, rate) / validateModelPath(modelPath) -> optional<string> // error or nullopt
```

Codec callbacks and the FreeSWITCH functions they rely on:

| Callback | Does | Key FS/Lyra calls |
|---|---|---|
| `lyra_init` | build context, read `fmtp_in` bitrate, set `fmtp_out`, flag `HAS_PLC` | `switch_core_sprintf`, `SWITCH_CODEC_FLAG_ENCODE/DECODE` |
| `lyra_encode` | PCM→N Lyra frames; DTX→`SFF_CNG` | `Encoder::encodeFrame` |
| `lyra_decode` | N Lyra frames→PCM; loss/`SFF_PLC`→conceal; follow peer bitrate change | `Decoder::decodeFrame/conceal` |
| `lyra_destroy` | `delete` context | — |
| `mod_lyra_load` | validate+probe config, `SWITCH_ADD_CODEC`, one `add_implementation` per rate, `SWITCH_ADD_API` | `switch_core_codec_add_implementation`, `switch_loadable_module_create_module_interface` |

## 3.3 mod_lyra_record — types and flow

```
RecordConfig  { bool enabled; string recordingRoot, format; Track track;
                int sampleRate, maxSeconds; string directoryPattern, filenamePattern;
                long long minFreeMb; }

startRecording(session, overridePath?):
  cfg = snapshot
  if !cfg.enabled            -> metric skipped_disabled; return           // zero overhead
  if readCodec != "lyra"     -> metric skipped_not_lyra; return           // scope guard
  if bug already on channel  -> return (already recording)
  path = buildRecordingPath(root, dirPat, filePat, ext, inputs)          // sanitised, confined
  if !path                   -> metric failed_path; return
  if freeMegabytes(path) < minFreeMb -> metric failed_disk; return
  set RECORD_STEREO / record_sample_rate on channel
  switch_ivr_record_session_event(session, path, maxSeconds, NULL, NULL) // the core engine
  store path under channel-private "lyra_record"; metrics started, active++
```

`readCodecIsLyra` uses `switch_core_session_get_read_codec()->implementation->iananame`,
falling back to the `rtp_use_codec_name` channel variable. The whole feature is defined as
Lyra-to-Lyra, so a call that negotiated PCMU is skipped by design (counted, logged).

## 3.4 Configuration contract

`lyra.conf.xml` → `CodecConfig`:

| param | type / range | default | invalid ⇒ |
|---|---|---|---|
| `model-path` | absolute dir with the 4 model files | `<data_dir>/lyra/model_coeffs` | module refuses to load |
| `default-bitrate` | 3200 \| 6000 \| 9200 | 3200 | reject config |
| `dtx` | bool | false | reject config |
| `sample-rates` | subset of 8000/16000/32000/48000 | 16000 | reject config |
| `ptime` | multiple of 20, ≤120 | 20 | reject config |

`lyra_recording.conf.xml` → `RecordConfig`:

| param | type / range | default | invalid ⇒ |
|---|---|---|---|
| `enabled` | bool | false | reject config |
| `recording-path` (a.k.a. `lyra-recording-path`) | absolute dir | — | required when enabled |
| `format` | bare extension | wav | reject config |
| `track` | stereo \| mixed | stereo | reject config |
| `sample-rate` | 0 (follow call) / 8000/16000/32000/48000 | 16000 | reject config |
| `directory-pattern` | tokens, may nest with `/` | `{date}` | segments sanitised |
| `filename-pattern` | tokens | `{time}_{caller}_{callee}_{uuid}` | empty rejected |
| `min-free-mb` | ≥0 | 100 | reject config |
| `max-seconds` | 0..86400 | 0 (unlimited) | reject config |

Filename/directory tokens: `{date}` (YYYYMMDD), `{time}` (HHMMSS), `{epoch}`, `{caller}`,
`{callee}`, `{uuid}`, `{domain}`. Every token value is sanitised to `[A-Za-z0-9._-]` with
`.` runs collapsed; the whole path is normalised and asserted inside the root
(`03-lld.md` §3.5, `08-security.md`).

## 3.5 The path builder (security-critical), step by step

```
buildRecordingPath(root, dirPat, filePat, ext, inputs):
  require root is absolute
  dir  = for each '/'-separated segment of expand(dirPat):  sanitizeAtom(segment) drop-if-empty
  file = sanitizeAtom(expand(filePat) as ONE atom)          // no '/' can survive here
  full = normalize(root + "/" + dir + "/" + file + "." + sanitizeAtom(ext))
  require isInside(normalize(root), full)                   // second, independent defence
  return full
```

`sanitizeAtom` is O(n) in the value length (capped at 64), `lexicalNormalize` is O(segments).
No allocation on the media hot path: path building happens once per call at answer, not per
frame.

## 3.6 Complexity of the data structures on the hot path

| Structure | Operation | Time | Space |
|---|---|---|---|
| `LyraCodecContext` | per-frame encode/decode | O(1) dispatch + O(model) inside Lyra | fixed; buffers presized to `kMaxFrameBytes`/`bytesPerPcmFrame` |
| config `shared_ptr` snapshot | read at call start | O(1) | shared, immutable |
| atomic counters | per frame/outcome | O(1) lock-free | O(1) |
| core record bug ring | per frame | O(1) enqueue | bounded 64 KB/direction |

There is no per-frame allocation in our code except the Lyra library's own per-frame vector
(bounded ≤23 bytes), which is the library's API surface, not ours.
