# Prompt: Own the PJSIP native build — codecs, video, and conferencing

> **How to use this file.** Paste it whole as the opening message to a coding agent working
> in **this** repository (`whatsapp-v2`). It is a *successor* to
> `android-sip-app-prompt.md`, not a replacement: that file specifies the app, this one
> specifies the native stack underneath it. Sections marked **DECIDE** must be answered
> before code is written. Sections marked **VERIFY** must be checked against source before
> being relied on — several of the facts below have a date on them.

---

## 1. Role and mandate

You are a senior Android engineer with C/C++ cross-compilation experience. Take full
ownership of the PJSIP native stack this app links against: its build, its feature set, its
codecs, and the seam between it and Kotlin.

Optimise, in this order:

1. **A build that completes and is reproducible.** A stack that does not build is not a
   stack. Every capability below is downstream of this.
2. **Negotiated capability, not compiled capability.** A codec that compiles but that the
   far end will not accept has bought nothing. Every codec claim is a claim about *two*
   endpoints.
3. **Correctness under real network conditions** — NAT, loss, handover, Doze.
4. **Battery and CPU.** This app holds a registration for hours and encodes audio in real
   time; it must not be why a phone dies at 4pm.

Do not write prototype code, `TODO` stubs presented as finished, or a `CMakeLists.txt` that
"will be filled in later". If something is out of scope for a phase, say so in the phase
report.

---

## 2. Corrections to the framing — read before planning

These override any contrary instruction, including anything in the request that produced
this file. They exist because the naive version of each is false in this repository.

### 2.1 Autotools is the **tested** Android path — but upstream CMake now exists

**CORRECTED 2026-09-09.** This section previously said "there is no CMake build of
pjproject". That is false as of 2.17 and the correction changes what you should try first,
so it is recorded rather than quietly edited. `docs/master-engineering-prompt.md` §2.3 is
now the authority on this decision; what follows is the short version.

pjproject 2.17 ships a root `CMakeLists.txt` (`project(pjproject … LANGUAGES C CXX)`,
CMake `3.28…4.0`) and a `cmake/` directory of find modules — `FindOPUS.cmake`,
`FindVPX.cmake`, **`FindLyra.cmake`**, `FindOpenH264.cmake`, `FindSRTP.cmake` — driven by
options including `PJMEDIA_WITH_OPUS_CODEC`, `PJMEDIA_WITH_VPX_CODEC` and
`PJMEDIA_WITH_LYRA_CODEC`. It prints an **experimental** warning naming its tested
platforms as Linux x86_64 and macOS. **Android is not among them.**

So: upstream CMake plus the NDK toolchain file is a legitimate first attempt, and it is the
only shape where CMake is the real build rather than a wrapper. Timebox it. The documented
and Android-tested path, already implemented in `.github/workflows/build-pjsip.yml`, is
still:

```
./configure-android --use-ndk-cflags --with-ssl=… --with-opus=… --with-vpx=… --with-lyra=…
make dep && make
make -C pjsip-apps/src/swig   # SWIG generates the org.pjsip.pjsua2 Java bindings
```

What remains true: **do not hand-author a CMake build of pjproject's sources.** That is
several hundred source files re-expressed by hand, re-diverging from upstream at every
release, and upstream's own CMake build removes the last reason to attempt it. If you
believe you must, state the case in the phase report and stop for a decision — do not
start.

### 2.2 "Do not rely on prebuilt binaries" is **NOT yet satisfied** — corrected 2026-09-09

`.github/workflows/build-pjsip.yml` cross-compiles pjproject **2.17** from the upstream
tarball, together with OpenSSL, Opus and libvpx **v1.17.0**, on **NDK r27c**, for
`arm64-v8a`, `armeabi-v7a` and `x86_64`, then packages `pjsua2.aar`. ADR-006 records why
(option 1 of three: a third-party AAR pins somebody else's older pjproject, and vendored
`.so` files are not reproducible).

**This section previously concluded that the mandate was only to "finish and harden the
source build that exists". That is now wrong**, and `docs/master-engineering-prompt.md` §2
supersedes it. Three prebuilt paths remain, and all three are closed by N-1…N-14 there:

1. **The sources are fetched, not vendored** — `curl` of four tarballs at build time
   (`build-pjsip.yml:85`, `:271`, `:281`, `:293`, `:328`), with versions typed by a human
   into `workflow_dispatch` inputs.
2. **The output is a prebuilt AAR a human downloads and drops into `pjsip/libs/`** — which
   is both a binary nobody in a fresh clone can reproduce and the manual native step the
   new mandate forbids.
3. **318 Java files are committed under `:pjsip:api`** and taken automatically whenever the
   AAR is absent (`data/sip/build.gradle.kts:71`), producing a build that compiles and
   cannot run. This is the second door, and "we build from source in CI" was never true of
   it. See `docs/master-engineering-prompt.md` §2.8.

Read the workflow before proposing anything regardless: it already encodes several failures
you would otherwise rediscover — the host `ar` leaking into the Opus build, a `configure`
that accepts "no TLS, no Opus" silently, and a `config_site.h` that force-enabled VPX while
configure had disabled it and thereby corrupted `.depend`.

**VERIFY:** as of 2026-09-08 `docs/pjsip-migration.md` P-1 records that this workflow *had
never completed a run*. Check its current status before planning around it. If it still has
not gone green, that is Phase 1 and nothing else starts.

### 2.3 Lyra is real in PJSIP 2.17 — and it is a **two-sided** contract

This is the correction that matters most, because the codec is genuinely supported and the
request is still not achievable as stated.

What is true: pjmedia ships a Lyra codec, and the SWIG bindings in `:pjsip:api` expose it —
`CodecLyraConfig` with `bitRate` and `modelPath`, reachable via
`Endpoint.setCodecLyraConfig()`. Lyra carries intelligible wideband speech at **3.2, 6 or
9.2 kbps**, an order of magnitude under Opus, which on a congested mobile uplink is the
difference between a call and no call. It is worth having.

What is also true, and what "integrate Lyra from source" hides:

1. **`PJMEDIA_HAS_LYRA_CODEC` defaults to `0`** in `pjmedia/include/pjmedia-codec/config.h`.
   Enabling it means cross-compiling Google's Lyra, which pulls **Bazel and TensorFlow
   Lite** into a build that currently uses autotools and nothing else. That is a fourth
   native dependency alongside OpenSSL, Opus and libvpx, with a build system unlike the
   other three. **Verified 2026-09-09:** `--with-lyra=DIR` does exist
   (`aconfigure.ac:2583`), but `DIR` is a prefix **you** populate — configure links
   `-llyra` from `$DIR/lib`, wants absl/glog/gulrak headers under `$DIR/include`, derives
   the models as `$DIR/model_coeffs`, and **defaults Lyra off for any cross build that
   omits the flag** (`:2589-2591`). If its C++17 link check fails it sets
   `ac_no_lyra_codec=1` and carries on silently (`:2649`) — assert on it.
2. **Four model files must ship on the device** — `lyra_config.binarypb`, `lyragan.tflite`,
   `quantizer.tflite`, `soundstream_encoder.tflite` — with `CodecLyraConfig.modelPath`
   pointed at an extracted, readable path. Without them the codec *registers* and then fails
   when a stream opens, which is worse than not having it: it advertises capability it
   cannot deliver.
3. **The payload name is lowercase `lyra`** (`pjmedia/src/pjmedia-codec/lyra.cpp`). The
   stack matches preferences to codec ids by prefix, so an `AudioCodec.LYRA` spelled
   uppercase matches `lyra/16000/1` **never**, and does so silently. A test already pins
   this. Do not unpin it.
4. **The deployed FreeSWITCH cannot negotiate Lyra.** A codec is an agreement between two
   endpoints. Even with the native build done and the models shipped, a call through this
   registrar falls back to Opus or G.722. Lyra is finishable only alongside a server that
   offers it, or for direct calls that never touch the MCU.

So: **DECIDE** — is there a server-side Lyra plan? If not, the honest deliverable is "Lyra
compiled, registered, and provably selectable, with a documented note that no deployed peer
accepts it", not "Lyra calling works". Do not report the former as the latter.

`AudioCodec.LYRA` is deliberately in the enum but **not** in `CodecPreferences.DEFAULT` —
selectable, never offered until the binary can honour it. Keep that distinction.

### 2.4 The video codec situation is the reverse of what the request assumes

The request lists H.264 as a requirement and VP9 as "optional". In this build:

| Codec | Status | What it would take |
|---|---|---|
| **VP8** | **Built and working** — libvpx v1.17.0, `--enable-vp8`, `PJMEDIA_HAS_VPX_CODEC 1` | Nothing |
| **VP9** | **Built** — `--enable-vp9` is already passed | Confirm the far end offers it |
| **H.264** | **Absent** — `config_site.h` sets `PJMEDIA_HAS_OPENH264_CODEC 0` | Cross-compile OpenH264, plus a **licensing decision** (Cisco's terms) |

H.264 is the gap, and it is a product decision before it is an engineering one: absent
H.264, most IMS endpoints and *every* iOS device get no video. The deployed FreeSWITCH has
`mod_av` missing and `mod_h26x` unloaded, so its only working video codec is the VP8 that
`CORE_VPX` provides — which is exactly why libvpx is in the build.

`CodecPreferences.DEFAULT` currently names H264 while the binary lacks it. `applyPriorities`
only touches codecs PJSIP actually registered, so the mismatch is skipped rather than
raised; it now logs once per account. **DECIDE:** add OpenH264 (and accept its licence), or
remove H264 from the defaults and document why. Do not leave it as a silent no-op.

### 2.5 PJSIP's conference bridge is **local mixing**, not multi-party calling

`pjmedia`'s conference bridge mixes the ports on *this device*. It is how you route audio
between a call, a recorder and a tone generator — not how three people talk to each other.
Multi-party requires a conference focus on the server: ADR-003 already chose **FreeSWITCH
`mod_conference`, dial-in MCU**. The client's job is to dial a conference URI, render the
streams it is sent, and drive floor control. It does not mix.

"Video conferencing" through pjsua2 alone does not exist. Scope it as: dial-in conference,
participant list, per-participant UI, N remote video surfaces — against an MCU that must
already be configured.

### 2.6 Push is not a PJSIP feature

PJSIP has no push. ADR-004 chose **RFC 8599 client parameters plus an ESL-driven push
gateway**. The `.so` file contributes the `pn-*` contact parameters and nothing else; the
wake path is server-side infrastructure. Do not plan push as a native-build task.

### 2.7 "Zero crashes, zero ANRs, zero memory leaks" is not a specification

It cannot be tested and therefore cannot be delivered. Replace each with a measured budget —
see §7. A target you can fail is worth more than an absolute you can only claim.

### 2.8 "Full control over sample rate and packetization" overstates what codecs allow

PCMU and PCMA are 8 kHz, G.722 is 16 kHz, and no configuration changes that. What PJSIP
actually exposes, per codec, via `CodecParam`: clock rate *where the codec defines more than
one*, channel count, average/maximum bitrate (meaningful for Opus and Lyra; fixed for the
G.711 family), ptime, VAD and PLC toggles, and `fmtp` lines that reach the SDP. Specify
control in those terms. An invented knob is a shipped field outage.

### 2.9 The licence is unresolved, and "enterprise deployment" is what makes it urgent

ADR-002 records **GPLv2 as a working assumption, with the commercial licence UNRESOLVED**.
Building from source changes nothing about that: PJSIP is GPLv2 or a paid Teluu licence, and
statically linking it into a closed-source APK you distribute is the case the GPL is about.
This is a blocker for "suitable for real-world deployment", not a footnote. **DECIDE** before
any store submission; say so in every phase report until it is decided.

### 2.10 Builds run in CI. Verification runs on hardware

Do not install an NDK, Bazel, or a C toolchain on the developer's machine, and do not run
`./gradlew assembleRelease` there to "check". Native builds go through
`build-pjsip.yml`; app builds go through `ci.yml`. Batch your changes and push once rather
than dispatching a workflow per edit, and do not sit watching a run — start it and move on.

For inspecting the *generated API*, dump the artifact and read it (`javap`, `unzip -l`,
`nm -D` on the `.so`) rather than guessing or spending a CI run on a question a local
artifact can answer.

Functional verification is **on a real handset against the existing FreeSWITCH deployment**
(ADR-005 — no local Docker server). Emulator media proves nothing about AEC, routing, or
battery.

---

## 3. Verified baseline — what already exists

Read these before writing anything. Every claim in §2 is grounded here.

| Thing | Where | State |
|---|---|---|
| Native build | `.github/workflows/build-pjsip.yml` | pjproject 2.17 + OpenSSL + Opus + libvpx 1.17.0, NDK r27c, 3 ABIs |
| Compile-time feature set | `config_site.h`, written by that workflow | `PJMEDIA_HAS_VIDEO 1`, `VPX 1`, `OPUS 1`, `PJSIP_HAS_TLS_TRANSPORT 1`, `OPENH264 0` |
| Binary module | `:pjsip` (`pjsip/build.gradle.kts`) | Publishes `libs/pjsua2.aar` through its `default` configuration; the AAR is gitignored |
| Source fallback | `:pjsip:api` | SWIG-generated `org.pjsip.pjsua2` Java, checked in, so the tree compiles without the AAR — and an APK built on it throws `UnsatisfiedLinkError` on the first call. **Being removed** (N-13/N-14): generated per build from the vendored tree instead |
| The seam | `SipCoreGateway`, `SipCallGateway`, `SipVideoGateway`, `SipRecordingGateway` | ≈342-line contract; ≈985 lines of PJSIP-specific adapter behind it. Everything else in `:data:sip` is SDK-free |
| Adapter | `data/sip/.../PjsipSipEngine.kt` + `stack/`, `call/`, `registration/` | P-4 landed 2026-09-08 |
| Migration state | `docs/pjsip-migration.md` | P-1 (artifact) and P-7 (hardware re-verify) outstanding as of 2026-09-08 |
| Decisions | `docs/architecture.md` ADR-002 … ADR-006 | Licence, conference, push, test target, stack |
| SDK levels | `gradle/libs.versions.toml` | minSdk 26, compileSdk/targetSdk 37 |

Three constraints from the migration doc that will bite if ignored:

- **Thread confinement.** Every thread that touches pjsua2 must be registered with the
  library first, and native objects are not free-threaded. This is the single most common
  source of native crashes in pjsua2 apps.
- **Callbacks arrive by subclassing**, not by listener registration — `Account`, `Call` and
  `Endpoint` subclasses, whose lifetimes you own.
- **Video renders into a `Surface`**, not a `TextureView` (P-5).

---

## 4. Scope

**In:**

1. `build-pjsip.yml` completing reliably and reproducibly across all three ABIs.
2. Codec capability made explicit and testable: what is compiled, what is registered at
   runtime, what the far end accepts — three different lists, reported separately.
3. Lyra to whatever the §2.3 decision permits.
4. The H.264 decision from §2.4, executed either way.
5. Runtime codec control through the existing gateway seam — priority, bitrate where the
   codec has one, ptime, VAD/PLC, `fmtp`.
6. Audio quality tuning: AEC selection, jitter buffer, PLC, and the capture/playback path.
7. Performance measured against §7's budgets on hardware.

**Out:**

- **No** CMake port of pjproject (§2.1).
- **No** SIP server, MCU, SBC, or push-gateway implementation. The infrastructure is assumed
  to exist; its contract is stated, not built.
- **No** rewrite of `:domain`, `:feature:*`, or the SDK-free two-thirds of `:data:sip`.
  If a change needs to reach above the gateway seam, that is a signal to re-read the seam,
  not to widen the diff.
- **No** third-party repackaged PJSIP AAR, and no `.so` committed to git (ADR-006).
- **No** new native dependency added while `build-pjsip.yml` is red. One unknown at a time.
- **No** store submission before ADR-002 is resolved.

---

## 5. Codec requirements, stated honestly

For each codec deliver **three facts, separately**: *compiled in?* (grep the built config),
*registered at runtime?* (enumerate from `Endpoint`), *negotiated with the deployed server?*
(SDP evidence from a real call). A codec is delivered only when all three are yes.

| Codec | Compiled today | Work |
|---|---|---|
| PCMU / PCMA | Yes (always) | Priority and ptime control only |
| G.722 | Yes (always) | Confirm the server offers it |
| Opus | Yes (`--with-opus`) | Bitrate, VBR/CBR, ptime, DTX, and `CodecOpusConfig` wired to settings |
| **Lyra** | **No** — `PJMEDIA_HAS_LYRA_CODEC 0` | Bazel + TFLite cross-compile, ship 4 model files, set `modelPath`, keep the lowercase `lyra` id — and §2.3's server question |
| VP8 | Yes | Nothing |
| VP9 | Yes (`--enable-vp9`) | Confirm a peer offers it |
| **H.264** | **No** — `OPENH264 0` | Cross-compile OpenH264 + accept Cisco's terms, or drop from defaults |

Report the runtime codec list from a real device as a table in the phase report. Not "codecs
are working".

---

## 6. Audio quality

Use what pjmedia already has before writing anything new:

- **AEC** — pjmedia ships several implementations, selected by `EpConfig.medConfig.ecOptions`
  / `ecTailLen`. Android also exposes a hardware `AcousticEchoCanceler` through the platform
  audio device. **DECIDE** which, per device class; running both is a known way to make
  audio worse, not better. Measure with a real double-talk scenario on speakerphone.
- **Jitter buffer** — pjmedia's adaptive buffer is on by default; tune
  `jbMin`/`jbMax`/`jbInit` and report the chosen values with the loss/latency evidence that
  justified them, not as taste.
- **PLC and VAD** — per codec, via `CodecParam`.
- **Capture path** — the Android audio device implementation in
  `pjmedia/src/pjmedia-audiodev/android`. Confirm which one the build selects; that choice,
  not application code, sets the floor on round-trip latency.

Every tuning claim needs a before/after number from a device.

---

## 7. Performance — budgets, not absolutes

Replace §2.7's zeros with these. Each is a number a test can fail. **DECIDE** any target
below that the stakeholder wants moved; do not silently adopt a softer one.

1. Mouth-to-ear latency on Wi-Fi, both ends local: report the measured figure and the method.
2. CPU during a steady 1:1 Opus audio call, measured on the reference handset, reported per
   ABI. Lyra will be materially higher — report it separately, it is a neural codec.
3. No unbounded native heap growth over a 30-minute call: `nm`/`dumpsys meminfo` deltas at
   0 / 15 / 30 minutes, plus a LeakCanary-clean JVM side.
4. Native memory is released at teardown: a call created and destroyed 50 times returns to
   within a stated delta of baseline.
5. Battery over a one-hour idle registered session, measured, and compared against the
   registration-only path.
6. Zero pjsua2 calls from unregistered threads, enforced by an assertion in debug builds
   rather than by hope.
7. `ANR` and crash counts from a stated soak duration — a number with a denominator, not
   "zero".
8. APK size delta per ABI for each native dependency added. Lyra's model files are a
   download-size decision as much as a quality one.

---

## 8. Delivery plan — phases, with a report between each

Do not attempt this in one pass. After each phase: push, let CI run, and report what works,
what does not, and what you assumed.

1. **Green the native build.** `build-pjsip.yml` completes for all three ABIs and produces a
   `pjsua2.aar` whose `.so` files pass the 16 KB page-size check. Nothing else starts first.
2. **Prove the artifact.** Install on hardware; register; complete a 1:1 audio call. Dump the
   runtime codec list. Close out P-7.
3. **Codec truth table.** Compiled / registered / negotiated, for every codec in §5,
   reported as data. Resolve the H264-in-defaults mismatch either way.
4. **Runtime codec control.** Priority, bitrate, ptime, VAD/PLC, `fmtp` — through the
   existing gateway seam, with tests at the seam.
5. **Video.** VP8 end-to-end against the server; VP9 if a peer offers it; the OpenH264
   decision executed. Camera switch, orientation, hardware-accelerated paths confirmed.
6. **Audio quality.** §6, each change with a measured before/after.
7. **Lyra.** Only after 1–6 and only per §2.3's decision. Bazel/TFLite cross-compile, model
   files, `modelPath`, negotiation evidence — or a documented stop with the reason.
8. **Conference.** Dial-in against `mod_conference`, participant list, N video surfaces.
9. **Hardening.** §7's budgets measured, ADR-002 escalated, docs updated.

---

## 9. Definition of done — each item binary

1. `build-pjsip.yml` completes for `arm64-v8a`, `armeabi-v7a` and `x86_64` from a clean run.
2. The produced `.so` files satisfy the 16 KB page-size requirement, proven by the check,
   not asserted.
3. `./gradlew clean build` is green from a fresh clone *without* the AAR present (the
   `:pjsip:api` fallback path still compiles).
4. A registered account completes a 1:1 audio call on hardware against the deployed
   FreeSWITCH, with the negotiated codec named in the report.
5. The runtime codec table from §5 is filled in from a real device, all three columns.
6. `CodecPreferences.DEFAULT` names no codec the binary cannot register, enforced by a test.
7. A codec preference change made in the UI provably reaches the SDP of the next call.
8. TLS is enabled — `PJ_HAS_SSL_SOCK 1` in the built headers — and an SRTP-mandatory call
   against a cleartext-only peer **fails** rather than downgrading.
9. A 1:1 video call establishes bidirectional VP8; camera switch and video mute work.
10. A dial-in conference connects and lists participants.
11. Every §7 budget has a measured number recorded, on the reference handset, per ABI where
    the metric is ABI-dependent.
12. No pjsua2 call occurs from an unregistered thread, enforced in debug builds.
13. Lyra is either negotiating on a real call, or absent from `CodecPreferences.DEFAULT`
    with the reason documented — and `AudioCodec.LYRA`'s lowercase-id test still passes.
14. ADR-002 (licence) is either resolved or restated as an open blocker in the final report.
15. `docs/pjsip-migration.md` and `docs/architecture.md` reflect the delivered state; no ADR
    describes a stack that no longer exists.

---

## 10. How to work

- **Ask before assuming.** If a DECIDE is unanswered — Lyra's server side, H.264's licence,
  AEC selection, the distribution model — ask. An invented config key, payload name, or
  configure flag is a production outage, and this stack has already produced one silent
  version of exactly that (H264 in the defaults of a build without it).
- **Ground every claim.** Before naming a `configure` flag, a `config_site.h` macro, a
  codec id, a class or a file path, confirm it exists — in the pjproject tag being built, in
  the generated bindings, or in this repo. `javap` on the real artifact beats memory, and
  beats a CI run.
- **One unknown at a time.** Every native dependency added to a red build multiplies the
  failure modes instead of resolving one. This is why OpenH264 and Lyra are late phases.
- **Another session may share this working tree.** Re-check the branch and `git status`
  before committing; HEAD may have moved under you.
- **Report honestly.** If a phase's build fails, show the log. If a codec compiled but never
  negotiated, say that — "Lyra integrated" and "Lyra compiled but no peer accepts it" are
  different sentences and only one of them is true.
