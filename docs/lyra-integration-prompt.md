# Prompt: make Lyra work end to end — pjproject → `liblyra` → a call you can hear

> **Paste this whole file as the opening message.** It is scoped to one thing: turning
> `PJMEDIA_HAS_LYRA_CODEC 0` into a call carried by Lyra, in *this* repository, on *this*
> deployment. `docs/master-engineering-prompt.md` still governs **how** to work.
> `docs/lyra-criterion-1.md` is the dependency map and `docs/architecture.md` ADR-008 is the
> decision this either closes or ends. Read both before planning.
>
> **Everything below was verified against the vendored tree on 2026-09-10.** Where a line
> number is cited, it was read, not remembered.

---

## 1. Mandate

Make Lyra a codec this app can actually negotiate and carry, or produce the evidence that it
cannot and close ADR-008 at Exit B. Both are acceptable outcomes. **A report that says
"integrated" without a call is not.**

---

## 2. Read this before planning: six things a plan gets wrong

### 2.1 pjproject's half is already done. You are not writing codec code.

This is the single biggest framing error available. PJSIP 2.17 ships a complete Lyra codec
and this repository already vendors it:

| What | Where |
|---|---|
| Codec implementation | `third_party/pjproject/pjmedia/src/pjmedia-codec/lyra.cpp` |
| Public API | `third_party/pjproject/pjmedia/include/pjmedia-codec/lyra.h` |
| Registration, automatic | `pjmedia/src/pjmedia-codec/audio_codecs.c:145-150` — `pjmedia_codec_lyra_init(endpt)` inside `#if PJMEDIA_HAS_LYRA_CODEC`, called by `pjmedia_codec_register_audio_codecs` |
| Build integration | `third_party/pjproject/aconfigure.ac:2583-2650` — `--with-lyra=DIR` |
| CMake finder (unused here) | `third_party/pjproject/cmake/FindLyra.cmake` |

`pjsua2` needs **no change at all**. Once the flag is on and `-llyra` links, the codec
registers itself and appears in `codecEnum2()` like every other one.

**So the work is not "integrate Lyra with PJSIP". It is "produce a `liblyra` that
`configure-android` can link, and give the codec its model files at runtime."**

### 2.2 `config_site.h` beats `configure`, and this is the trap that wastes a day

`aconfigure.ac:2642` does `AC_DEFINE(PJMEDIA_HAS_LYRA_CODEC,1)` when the link test passes.
That define **loses**:

- `pjmedia/include/pjmedia-codec/config.h:574-576` guards the flag with `#ifndef`.
- `pjlib/include/pj/config.h:317` includes `<pj/config_site.h>`.
- `pjsip/config/pj/config_site.h:77` says `#define PJMEDIA_HAS_LYRA_CODEC 0`, unguarded.

config_site is seen first, the `#ifndef` then keeps it, and **a perfectly successful
`--with-lyra` build registers no codec at all.** The symptom is the worst kind: configure
says "Using lyra prefix", the link test passes, the build is green, and `codecEnum2()` has
no `lyra` row.

That line is N-8's single source of truth and changing it is part of the work, not a
workaround. Change it **and** pass `--with-lyra`; either alone is a silent no-op.

### 2.3 The model path baked at build time is a path on **your Mac**

`aconfigure.ac:2641` sets `ac_lyra_model_path="$LYRA_PREFIX/model_coeffs"`, and
`pjmedia-codec/config.h:595` defaults `PJMEDIA_CODEC_LYRA_DEFAULT_MODEL_PATH` to the
*relative* string `"model_coeffs"`. Neither exists on an Android device.

Lyra needs four files in one directory (`lyra.h:61-67`):

```
lyra_config.binarypb   lyragan.tflite   quantizer.tflite   soundstream_encoder.tflite
```

They are ~3.5 MB, they ship in `google/lyra` itself, and they are **trained weights: data,
not code** — the one place N-1's "no prebuilt binaries" does not reach, said out loud in
ADR-008 rather than assumed.

So they must be packaged as app assets, copied to a real filesystem directory on first run,
and the absolute path handed to the codec **at runtime**, before any call:

```c
pjmedia_codec_lyra_config cfg;
pjmedia_codec_lyra_get_config(&cfg);   /* lyra.h:98  */
cfg.model_path = pj_str("/data/user/0/com.whatsappv2/files/lyra");
cfg.bit_rate   = 3200;                 /* 3200 | 6000 | 9200 */
pjmedia_codec_lyra_set_config(&cfg);   /* lyra.h:108 */
```

`lyra.cpp:200-203` copies that path into a fixed buffer at init, so **set it after
`libInit()` and before the first call**. An invalid directory fails codec *creation*, not
init — which surfaces as a call that fails to negotiate rather than a startup error.

Whether pjsua2's Java bindings expose `pjmedia_codec_lyra_set_config` at all is the first
thing to check: if SWIG does not wrap it, this needs a small JNI shim or a pjproject patch
(**patch, never an edit — rule 12**), and that changes the shape of the work.

### 2.4 It registers as `lyra/16000/1`, and only that

`pjmedia-codec/config.h:604-635`: `PJMEDIA_CODEC_LYRA_HAS_8KHZ 0`, `HAS_16KHZ 1`,
`HAS_32KHZ 0`, `HAS_48KHZ 0`. `lyra.cpp:167-181` enables exactly those rows.

Two consequences. The id is lowercase `lyra/16000/1` — `AudioCodec.LYRA.payloadName` is
already `"lyra"` for exactly this reason, and `CodecPriorities` matches by lowercase prefix
because an uppercase `LYRA` matched `lyra/16000/1` **never, and silently**. That shipped
once. And the configure link test at `aconfigure.ac:2638` constructs a decoder at **8000 Hz**
even though 8 kHz is disabled — a passing link test says nothing about which rates register.

### 2.5 There is no peer, and this does not change

The deployed FreeSWITCH at `192.168.80.145` offers **PCMU, PCMA, G.729, G.723.1, AMR, Speex,
VP8, VP9**. No Lyra, and no `mod_lyra` exists to install.

So the only call Lyra can carry on this deployment is **app → app with the server passing
media through untranscoded**. That is a server configuration (bypass/proxy media on both
legs, with the dynamic payload type preserved), it is not a change in this repository, and
**it must be arranged before the final done-when can be met.** Plan for it in phase 0, not
after everything else works.

If that cannot be arranged, the honest deliverable is the one ADR-008 already names:
*"Lyra compiled, registered, model files verified, provably selectable, with no deployed peer
that accepts it."* Say that rather than stretching a definition.

### 2.6 The SDP has 144 bytes of headroom, and an offer is not free

`SdpBudget`: the path carries **1472 bytes**, the current audio INVITE is **1328**. Adding
Lyra adds an `a=rtpmap:` line and an `a=fmtp:` line (`lyra.cpp:323` writes a bitrate fmtp).
Measure the offer again after enabling it — `docs/architecture.md` §4.11.1 has the method,
and the previous agent's arithmetic was wrong twice before it was measured.

---

## 3. Verified baseline — what exists today

- `PJMEDIA_HAS_LYRA_CODEC 0` (`pjsip/config/pj/config_site.h:77`), stated deliberately so the
  audit's `NotCompiled` reason has a decision behind it.
- `third_party/lyra` **does not exist**. Vendored trees are `libvpx`, `openssl`, `opus`,
  `pjproject`.
- `AudioCodec.LYRA` exists in `:domain` with `payloadName == "lyra"`, deliberately **out** of
  `CodecPreferences.DEFAULT`.
- `DeclaredFeatureSet.kt:72` maps `"lyra" to "PJMEDIA_HAS_LYRA_CODEC"`, so the audit already
  reports it as not compiled — that is the audit working, not the audit being skipped.
- The native build is **autotools driven by CMake** (`pjsip/CMakeLists.txt`, option (b) of
  master prompt §2.3). Each vendored tree is copied into the build directory and configured
  per ABI; nothing is written into `third_party/`.
- The configure line to extend is `pjsip/build-native.sh:201-202`:
  ```
  ./configure-android --use-ndk-cflags \
    --with-ssl="$prefix" --with-opus="$prefix" --with-vpx="$prefix"
  ```
- Immediately below it, `build-native.sh:204-219` **asserts what configure actually did**,
  because `configure-android` does not fail when it cannot find a dependency — it quietly
  builds a stack without it. Lyra needs the same assertion. This convention has already
  caught real defects; do not skip it.
- What `--with-lyra=$DIR` demands of that directory (`aconfigure.ac:2608-2621`):
  ```
  $DIR/lyra_decoder.h                      (via -I$DIR)
  $DIR/include/com_google_absl
  $DIR/include/gulrak_filesystem
  $DIR/include/com_google_glog/src
  $DIR/lib/liblyra.a                       (via -L$DIR/lib -llyra)
  $DIR/model_coeffs/                       (four files, build-host copy)
  ```
  built as C++17 with the three `GLOG_*` visibility defines it sets.

---

## 4. Scope

**In.** Vendoring Lyra and its closure; a CMake build for everything Bazel currently builds;
`liblyra` and the include layout above; the configure flag and its assertion; the
`config_site.h` flip; model files on the device and the runtime config that points at them;
the domain-side codec wiring; the measurement of the resulting SDP; and one call.

**Out.** Bazel anywhere in this build (N-2 forbids build-time downloads and Bazel 5.3.2 is
end-of-life). Any edit inside `third_party/` (rule 12 — patches only, via
`tools/vendor/apply-patches.sh`). Server-side FreeSWITCH work — raise it, do not attempt it.
Shipping Lyra in `CodecPreferences.DEFAULT` before a call has been heard.

---

## 5. Phases. There is a gate after each, and phase 0 can end the project.

### Phase 0 — criterion 1, and the peer question. **Stop here if either fails.**

1. **Build TensorFlow Lite v2.11.0 for `arm64-v8a` with NDK r27c via its own CMake, XNNPACK
   enabled.** ~1.35 GB, reached through `lyra/tflite_model_wrapper.cc`, and the XNNPACK
   delegate is included directly so it is not optional. TFLite has an official CMake build
   with Android support; the risk is a 2022 `cpuinfo`/XNNPACK/ruy tree under a 2024 NDK.
   Nothing else matters until this is known.
2. **Ask whoever operates `192.168.80.145` whether media pass-through between two extensions
   can be arranged.** It costs one message and it decides whether the last done-when is
   reachable at all.

**If (1) cannot be patched cheaply, that is ADR-008 Exit B.** Write it up, ship no code,
leave `AudioCodec.LYRA` exactly where it is. That is a successful outcome of this prompt.

### Phase 1 — vendor Lyra and pin the closure

`google/lyra` at `v1.3.2` via `tools/vendor/vendor.sh` + `pins.sh` + `record-hashes.sh`. Add
the row to `docs/native-dependencies.md` — `tools/vendor/verify-pins.sh` asserts that the
document and `pins.sh` agree and that every `third_party/` directory has a row, so a missing
row fails the build.

**Two of Lyra's own dependencies float** and pinning them is the first act: `com_google_glog`
is `branch = "master"` and `com_github_gflags_gflags` is `branch = "android_linking_fix"` on
an individual's fork. N-11 is not measurable until both are commits.

Lyra's own code is small — 20 non-test `.cc`, 34 `.h`. absl `20211102.0`, glog and gulrak
filesystem `v1.3.6` all have CMake already.

### Phase 2 — `com_google_audio_dsp` has no CMake at all

12 call sites across 6 targets: `signal_vector_util`, `number_util`, `resampler_q`, `mfcc`,
`spectrogram`, `spectrogram:inverse_spectrogram`, `portable:read_wav_file`. All hand-written.
This is the second rock and it is unavoidable.

### Phase 3 — `liblyra` in the layout configure expects

Static, C++17, `arm64-v8a`, NDK r27c, into `$prefix/lib/liblyra.a` with the headers laid out
as §3 lists. **16 KB page alignment is not optional** — Android 15 refuses to load a
non-aligned `.so`, and Lyra's own README pins NDK r21, which produces exactly that.

### Phase 4 — the configure flag, and the assertion beside it

Add `--with-lyra="$prefix"` to `pjsip/build-native.sh:201`. Then add the assertion, in the
style of the three already there: grep `config.log` for the usability result and fail the
build if Lyra is not enabled. A green build that quietly dropped Lyra is the failure mode
this whole block exists to prevent.

### Phase 5 — flip the flag and prove registration

`pjsip/config/pj/config_site.h:77` → `1`. Then prove it from the device, not from the build
log: `PjsipCodecAudit` already logs the registry verbatim with priorities. **A `lyra/16000/1`
row at a non-zero priority is the proof.** No row means §2.2 caught you.

### Phase 6 — model files on the device

Package the four files as assets, copy to `filesDir` on first run, verify all four exist and
are non-empty, and call `pjmedia_codec_lyra_set_config` with the absolute directory before
the first call. Check first whether pjsua2's SWIG bindings expose that function at all
(§2.3); if not, the shim or the patch is part of this phase.

### Phase 7 — domain wiring

`AudioCodec.LYRA` into `CodecPreferences.DEFAULT` **only once phase 8 has passed**, and mind
`CodecPriorities`: a preference set matching nothing must change no priority — that bug wiped
every audio codec endpoint-wide on 2026-09-10 and the regression test for it is in
`:domain`. Run `:test:arch:test`.

### Phase 8 — a call

Two devices, both this build, Lyra offered by both, server passing media through. Place it,
answer it, **hear it**. Capture the INVITE and re-measure against `SdpBudget`. Record the
observed codec from the audit log, not from the offer.

---

## 6. Definition of done — each item binary

1. `./gradlew :pjsip:buildPjsua2Native` produces `libpjsua2.so` with Lyra compiled in, from
   vendored source, with no build-time download (N-2) and no edit inside `third_party/`.
2. `tools/vendor/verify-pins.sh` and `verify-vendored.sh` pass with `third_party/lyra`
   present, pinned, documented and hashed.
3. `build-native.sh` fails the build if configure did not enable Lyra.
4. The codec audit on a handset logs `lyra/16000/1` at a non-zero priority.
5. The four model files are present on the device and the codec's `model_path` points at
   them; a deliberately wrong path produces a clear, logged failure rather than a silent one.
6. The audio INVITE with Lyra offered is measured on the wire and its size recorded against
   the 1472-byte limit.
7. A call between two devices is placed, answered and heard, with the audit confirming Lyra
   was the codec — **or** the report states plainly that no deployed peer accepts Lyra and
   names what was proven instead.
8. `:domain:test`, `:data:sip:test`, `:test:arch:test` and `detekt` green under
   `-PwarningsAsErrors=true`. `./build.sh` still produces an installable APK.

---

## 7. How to work

- **Measure before diagnosing.** Three diagnoses in this project were reached by reading code
  and the device disproved all three.
- **Grep every symbol, flag and codec id before naming it.** Every citation in this file was
  read at the line given; keep that standard.
- `:data:sip` compiles locally despite the SWIG task's error — see `docs/HANDOFF.md` §4.
- **Report honestly.** "Compiled", "registered" and "heard on a call" are three different
  claims and only the third one ends an argument.
