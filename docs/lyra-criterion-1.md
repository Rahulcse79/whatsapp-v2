# ADR-008 criterion 1 — the dependency map, before any build is attempted

> **Status: investigation, not a verdict.** Criterion 1 asks whether `liblyra` and its
> closure build for `arm64-v8a` with NDK r27c, producing 16 KB aligned output. This document
> records what the closure actually *is*, measured from `google/lyra` at tag `v1.3.2` on
> 2026-09-10. No build has been run. Exit A and Exit B are both still open.

## Why this document exists before the build

ADR-008 opened the gate with an estimate and one specific suspicion: that Bazel 5.3.2's
`android_ndk_repository` refuses NDK r27c. That suspicion is probably right, and it is also
not the expensive part. The expensive part is what has to be rebuilt if Bazel is abandoned
for CMake — and nobody had counted it. This is that count.

## What Lyra itself is: small

| | |
|---|---|
| Non-test `.cc` under `lyra/` | **20** |
| Non-test `.h` under `lyra/` | **34** |
| Model files, prebuilt, shipped in the repository | **4** (3.5 MB) |

`lyra_encoder`'s own direct external dependencies are modest and all have first-class CMake
builds: `com_google_absl` (tag `20211102.0`), `com_google_glog`, `gulrak_filesystem`
(`v1.3.6`). **If that were the whole closure, criterion 1 would be a day's work.**

## What the closure actually is: two hard rocks

### Rock 1 — TensorFlow Lite, pinned at v2.11.0

`lyra/tflite_model_wrapper.cc` includes:

```
tensorflow/lite/interpreter.h
tensorflow/lite/interpreter_builder.h
tensorflow/lite/kernels/register.h
tensorflow/lite/model_builder.h
tensorflow/lite/signature_runner.h
tensorflow/lite/delegates/xnnpack/xnnpack_delegate.h
```

`WORKSPACE` pins `org_tensorflow` at commit `d5b57ca93e506df258271ea00fc29cf98383a374`
(v2.11.0) — a tree of roughly **1.35 GB**.

**The decisive unknown for the whole gate is here, and it is not the one ADR-008 named.**
TFLite does ship an official CMake build with Android cross-compilation support, so the port
is possible in principle. The risk is the *age gap*: TF 2.11 is November 2022 and NDK r27c is
2024. TFLite's CMake build vendors its own `cpuinfo`, `XNNPACK`, `ruy`, `pthreadpool`,
`flatbuffers`, `farmhash`, `fft2d`, `eigen` and `gemmlowp` at 2022 revisions, and those are
exactly the kind of low-level, `__builtin`- and `asm`-heavy sources that break on a newer
toolchain. The XNNPACK delegate is not optional here — Lyra's wrapper includes it directly.

**This must be attempted before anything else is written**, because it is both the largest
cost and the most likely failure.

### Rock 2 — `com_google_audio_dsp`, a Bazel-only repository with no CMake at all

Pinned at commit `14a45c5a7c965e5ef01fe537bd816ce10a247813`. Lyra reaches into it at
**twelve** call sites for six distinct targets:

```
audio/dsp:signal_vector_util
audio/dsp:number_util
audio/dsp:resampler_q
audio/dsp/mfcc
audio/dsp/spectrogram
audio/dsp/spectrogram:inverse_spectrogram
audio/dsp/portable:read_wav_file
```

Google's `audio_dsp` has **no CMake build of any kind**. Every one of these targets and its
transitive closure — which reaches Eigen and `fft2d` — has to be hand-expressed as CMake.
That is new, unreviewed build code this repository would then own, which is a real cost under
the vendoring mandate and not a mechanical translation.

## What is genuinely easy

- **The model files.** All four ship inside the Lyra repository: `lyra_config.binarypb`,
  `lyragan.tflite` (1.4 MB), `quantizer.tflite` (329 KB), `soundstream_encoder.tflite`
  (1.7 MB) — **3.5 MB total**. They are trained weights, so N-1 does not reach them and N-3
  requires vendoring them as data. Step 4's asset shipping is unblocked and cheap.
- **The pjproject side.** `third_party/pjproject/aconfigure.ac:2582-2640` already implements
  `--with-lyra=DIR` and expects a plain prefix: `lib/liblyra.a`, `lyra_encoder.h`,
  `lyra_decoder.h`, and the include dirs `include/com_google_absl`,
  `include/com_google_glog/src`, `include/gulrak_filesystem`. It link-tests
  `LyraDecoder::Create` and, on success, sets `ac_lyra_model_path=$LYRA_PREFIX/model_coeffs`
  and defines `PJMEDIA_HAS_LYRA_CODEC 1`. **Nothing in pjproject needs changing.**
- **The runtime seam.** `pjmedia_codec_lyra_config.model_path` (`pjmedia-codec/lyra.h:69`)
  takes the folder, so step 4 is `Endpoint.setCodecLyraConfig` inside
  `RealPjsipCoreGateway` and an asset copy to `filesDir`.

## The two unpinned dependencies, still unpinned

`WORKSPACE` still floats two of them, which is criterion 3's blocker and unchanged since
ADR-008 recorded it:

```
com_google_glog          branch = "master"
com_github_gflags_gflags branch = "android_linking_fix"   (an individual's fork)
```

Under CMake these stop being Bazel's problem and become ours — both get pinned to a commit
in `tools/vendor/pins.sh` like the other four trees, which is strictly better than today.

## The recommended order, and where to stop

1. **Build TFLite v2.11.0 for `arm64-v8a` with NDK r27c via its own CMake, XNNPACK enabled.**
   Nothing else matters until this is known. If it fails on 2022-vintage `cpuinfo`/XNNPACK
   sources under r27c and cannot be patched cheaply, that is **Exit B** and the write-up is
   the deliverable.
2. Only then, hand-write CMake for `audio_dsp`'s six targets plus `fft2d`.
3. Only then, CMake for Lyra's own 20 sources against absl/glog/filesystem.
4. Only then vendor, wire `--with-lyra`, flip the flag, ship the models.

**The blocker that survives either exit is unchanged and worth restating.** The deployed
server offers PCMU, PCMA, G.729, G.723.1, AMR, Speex, VP8 and VP9 — no Lyra. Even on Exit A
the honest deliverable is *"Lyra compiled, registered, model files verified, provably
selectable, with no deployed peer that accepts it."* And the reference server has no TCP
listener, so it cannot currently carry a video INVITE either (`docs/architecture.md` §4.11.1)
— there is server-side work queued behind both features.
