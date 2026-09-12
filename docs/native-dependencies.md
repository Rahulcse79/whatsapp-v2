# Native dependencies — provenance, licences, versions, patches

**Master prompt §2.6 (N-10).** One row per vendored tree: name, upstream URL, exact
version *and commit*, licence, why it is here, every local patch, and size. Plus the
**exempt toolchain** under its own heading (§2.1.1) — not vendored, not built here, and
pinned anyway, because an unpinned tool is a silently different `.so` and defeats N-11.

> **STATUS — `third_party/` exists.** Phase 2a landed on 2026-09-09. Every version, commit
> and licence below is verified against upstream, and every size is **measured** with `du`
> on the vendored tree, not estimated. The tree hashes in
> `pjsip/patches/vendored-tree.sha256` were verified to reproduce **byte-identically from a
> fresh `git worktree` checkout**, which is what makes architecture rule 12 meaningful in
> CI rather than only on the machine that vendored.

---

## 1. The four unconditional trees

| Name | Upstream | Version | Commit | Licence | Why it is here |
|---|---|---|---|---|---|
| **pjproject** | https://github.com/pjsip/pjproject | `2.17` | `5a457451fa2712ba18e12b01738e8ff3af2b26fd` | **GPL-2.0** *or* a Teluu commercial licence — see §4 | The SIP stack itself. Signalling, media, the pjsua2 API the whole of `:data:sip` is written against |
| **OpenSSL** | https://github.com/openssl/openssl | `openssl-3.5.0` | `636dfadc70ce26f2473870570bfd9ec352806b1d` | Apache-2.0 | **TLS transport.** Without it `configure-android` builds a stack with no TLS and does not complain — DoD 13 and `docs/security.md` §Transport both fail silently |
| **Opus** | https://downloads.xiph.org/releases/opus/ — **the release tarball, not the GitHub tag; see §1.3** | `1.5.2` | `ddbe48383984d56acd9e1ab6a090c54ca6b735a6` | BSD-3-Clause (GitHub reports `NOASSERTION`; the tree's `COPYING` is the 3-clause BSD) | The only wideband audio codec in the build. `CodecPreferences.DEFAULT` lists it first (`domain/…/model/Codecs.kt:78-82`) |
| **libvpx** | https://github.com/webmproject/libvpx | `v1.17.0` | `6df3ec34557879fff673706f4a1d9fbd0f3a6f0e` | BSD-3-Clause | **VP8** — the only video codec both ends can negotiate. The deployed FreeSWITCH offers VP8 and VP9 and no H.264 (`docs/reconciliation.md` A-1b) |

**These are the versions the green build already uses**, not new choices: they are the
`workflow_dispatch` defaults at `.github/workflows/build-pjsip.yml:33-47`, proven by run
`34317978694` (`docs/reconciliation.md` B-7). Vendoring changes *where the source comes
from*, not *which source*.

**A dependency with no answer in the "why" column is removed.** All four have one.

### 1.0 The Lyra closure — nineteen trees for one codec (ADR-008, Exit A, 2026-09-10)

Every pin is a commit hash. Where upstream pinned a tag, the hash the tag pointed at on
2026-09-10 is what is recorded; where upstream floated a branch (glog, psimd), this is the
first pin. "TFLite's pin" means `tensorflow/lite/tools/cmake/modules/*.cmake` at v2.11.0;
"XNNPACK's pin" means its `cmake/Download*.cmake` at the commit below.

| Name | Upstream | Version | Commit | Licence | Why it is here |
|---|---|---|---|---|---|
| **lyra** | https://github.com/google/lyra | `v1.3.2` | `47698dadf0010abff6a848e02642f55f806d4842` | Apache-2.0 | The codec: 17 sources, 34 headers, and the four weight files in `lyra/model_coeffs` (3.5 MB of data, the one place N-1 does not reach). Registers as `lyra/16000/1` |
| **tensorflow** | https://github.com/tensorflow/tensorflow | `v2.11.0` | `d5b57ca93e506df258271ea00fc29cf98383a374` | Apache-2.0 | TensorFlow Lite: the interpreter and built-in kernels `lyra/tflite_model_wrapper.cc` runs the three `.tflite` models on. **Kept, not pruned**: 472 of 28,000 files — see §1.2 |
| **xnnpack** | https://github.com/google/XNNPACK | — | `e8f74a9763aa36559980a0c2f37f587794995622` | BSD-3-Clause | The CPU delegate. Not optional: the wrapper includes `xnnpack_delegate.h` directly. 158 MB, of which `src/` is 97 MB of microkernels for every ISA and `test/` is 55 MB kept because one `ADD_LIBRARY` at CMakeLists.txt:7215 names it unconditionally |
| **abseil-cpp** | https://github.com/abseil/abseil-cpp | `lts_2022_06_23`+ | `273292d1cfc0a94a65082ee350509af1d113344d` | Apache-2.0 | `absl::Status`, `Span`, `StrFormat`, `random` — used by Lyra, audio_dsp and TFLite alike. TFLite's pin wins over Lyra's older `20211102.0`; one absl per archive |
| **eigen** | https://gitlab.com/libeigen/eigen | — | `3bb6a48d8c171cf20b5f8e48bfb4e424fbd4f79e` | **MPL-2.0** (built `EIGEN_MPL2_ONLY`, which excludes its few LGPL files) | Matrix maths under TFLite's kernels and audio_dsp's spectrogram. TFLite's `eigen.cmake` **writes into this tree at configure time**, which is why every closure tree is built from a staged copy |
| **cpuinfo** | https://github.com/pytorch/cpuinfo | — | `5e63739504f0f8e18e941bd63b2d6d42536c7d90` | BSD-2-Clause | CPU feature detection for XNNPACK and ruy; `deps/clog` inside it serves both (byte-identical to the cpuinfo commit XNNPACK would fetch for clog). `test/` pruned, guarded |
| **ruy** | https://github.com/google/ruy | — | `841ea4172ba904fe3536789497f9565f2ef64129` | Apache-2.0 | TFLite's matrix-multiply backend on ARM |
| **flatbuffers** | https://github.com/google/flatbuffers | `v2.0.6` | `615616cb5549a34bdf288c04bc1b94bd7a65c396` | Apache-2.0 | The `.tflite` file format. `flatc` is built for the host from this tree during the build |
| **gemmlowp** | https://github.com/google/gemmlowp | — | `fda83bdc38b118cc6b56753bd540caa49e570745` | Apache-2.0 | Quantised matrix multiply TFLite's kernels reference |
| **farmhash** | https://github.com/google/farmhash | — | `0d859a811870d10f53a594927d0d0b97573ad06d` | MIT | Hashing in TFLite's string kernels |
| **fft2d** | https://github.com/petewarden/OouraFFT | `v1.0` | `c6fd2dd6d21397baa6653139d31d84540d5449a2` | Ooura's own permissive terms (`readme2d.txt`: "You may use, copy, modify this code for any purpose and without fee") | The split-radix FFT both TFLite's RFFT kernel and audio_dsp's spectrogram call. Same bytes as the `mirror.tensorflow.org` tarball TFLite pins |
| **FP16** | https://github.com/Maratyszcza/FP16 | — | `0a92994d729ff76a58f692d3028ca1b64b145d91` | MIT | Half-precision conversion headers, XNNPACK |
| **FXdiv** | https://github.com/Maratyszcza/FXdiv | — | `b408327ac2a15ec3e43352421954f5b1967701d1` | MIT | Division-by-constant headers, XNNPACK |
| **psimd** | https://github.com/Maratyszcza/psimd | — | `072586a71b55b7f8c584153d223e95687148a900` | MIT | Portable SIMD headers FP16 requires unconditionally. **Upstream FP16 fetches `master`**; this is its last commit (2020-05-17) and the first time it has been pinned |
| **pthreadpool** | https://github.com/Maratyszcza/pthreadpool | — | `545ebe9f225aec6dca49109516fac02e973a3de2` | BSD-2-Clause | XNNPACK's thread pool |
| **neon2sse** | https://github.com/intel/ARM_NEON_2_x86_SSE | — | `a15b489e1222b2087007546b4912e21293ea86ff` | BSD-2-Clause | NEON intrinsics on x86_64, for the one ABI that is not ARM |
| **audio_dsp** | https://github.com/google/multichannel-audio-tools | — | `14a45c5a7c965e5ef01fe537bd816ce10a247813` | Apache-2.0 | Mel filterbank, spectrogram, resampler and WAV I/O. **No CMake upstream** — `pjsip/lyra/CMakeLists.txt` lists its 16 files, the closure of the six Bazel targets Lyra names |
| **glog** | https://github.com/google/glog | `v0.6.0` | `b33e3bad4c46c8a6345525fd822af355e5ef9446` | BSD-3-Clause | `LOG`/`CHECK` in Lyra and audio_dsp. **Upstream Lyra floats `branch = "master"`**; pinned here, built `WITH_GFLAGS=OFF` so gflags is not in the closure |
| **gulrak-filesystem** | https://github.com/gulrak/filesystem | `v1.3.6` | `7e37433f318488ae4bc80f80e12df12a01579874` | MIT | `ghc::filesystem`, header-only; Lyra's model-path handling |

**Two of Lyra's WORKSPACE dependencies are deliberately absent.** `com_google_protobuf`
(v3.15.4): `lyra_config.proto` has one `int32` field and the file it parses is two bytes;
patch `0001` (§2) replaces the generated class with a parser of that message. A host
`protoc` and an Android `libprotobuf-lite` would be a larger dependency than the codec.
`com_github_gflags_gflags`: reached only through glog, and glog is built without it.

**The closure's cost.** 420 MB on disk for `third_party/` against 148 MB before, of which
XNNPACK is 158 MB and TensorFlow 41 MB after its keep-list. `git` compresses it to roughly
a third. Nothing here is fetched at build time: `pjsip/lyra/CMakeLists.txt` points every
`FetchContent` the sub-builds would perform at a vendored tree and sets
`FETCHCONTENT_FULLY_DISCONNECTED`, so an override it forgot is a configure error, not a
download.

**What vendoring nineteen trees taught, in the order it bit.** (1) `vendor.sh` died on
macOS's bash 3.2 at the first empty prune array (`"${OPUS_PRUNE[@]}"` under `set -u`); it
had only ever run on Linux. (2) `PJPROJECT_PRUNE` still listed `tests`, restored in §1.2
but never removed from the script — the re-run deleted 431 files the committed tree had.
(3) The closure trees carry 30-odd `.gitignore` files of their own, and git honours the
deepest: eigen's `core` rule would have dropped `Eigen/src/Core/` on a case-insensitive
disk, 202 files in all. (4) flatbuffers' `.gitattributes` says `*.bat text eol=crlf` and
would override the root's `third_party/** -text` for its subtree, so a fresh checkout would
rewrite files and rule 12's hash would differ between machines. (5) Five symlinks that no
build reads but that `find -type f` cannot see. `vendor.sh` now strips nested `.gitignore`
and `.gitattributes` files and symlinks from the closure trees; the four original trees are
left as committed.

### 1.1 Sizes — measured, and the estimate they replace

**Method:** each tag downloaded as a source tarball, extracted, `du -sh` on the extracted
tree. Then committed to a throwaway git repository and `git gc --aggressive --prune=now`,
to measure what the *pack* actually costs — which is what a clone pays.

| Tree | Extracted | **Vendored (final)** |
|---|---|---|
| pjproject | 66 MB | **60 MB** |
| OpenSSL | 138 MB | **49 MB** |
| libvpx | 26 MB | **22 MB** |
| Opus | 16 MB | **16 MB** |
| **Total working tree** | **246 MB** | **148 MB** |

**Clone size before and after**, as master prompt §2.1 requires — `.git` measured directly:

```
before phase 2a               .git = 30 MB
after,  vendored + pruned     .git = 87 MB   ◄── actual
(unpruned would have been     .git ≈ 145 MB)
```

**The final figure is 148 MB rather than the 134 MB a first pass measured**, and every
megabyte of the difference is a directory that was pruned and then restored because the
tree's own build system needed it. §1.2 has the three failures and the corrected rule;
`tools/vendor/verify-prune.sh` is what turned it from a lesson into a check.

**The master prompt's cost table is high by roughly a factor of four, and the reason is
worth stating** because it changes the decision. §2.1 quotes GitHub API repository sizes —
pjproject 50.6 MB, OpenSSL 351 MB, libvpx 75.7 MB, Opus 25.7 MB, "**~500 MB** for the four
unconditional trees". Those are **git repository** sizes: they include upstream's entire
history. This project vendors a **tarball extract of one tag**, which carries no history at
all. The correct figure is 246 MB extracted, 115 MB packed — and 26 MB packed once pruned.

**Consequence: the 1 GB threshold in §2.1 is not approached by either option**, so the "ADR
before the commit lands" trigger it describes does not fire on size. ADR-007 is still owed —
for the sourcing change itself — but it is not a size exception.

### 1.2 The prune, and the rule that had to be corrected three times

**Decision: vendor pruned trees.** Recorded in ADR-007.

**The rule as first written was wrong**, and it is worth recording why, because it sounds
obviously right: *"remove each tree's own test suite, its documentation, and its
pre-generated build scratch — things neither compiled into the `.so` nor read by
`configure`."*

An autotools project **declares** its tests and docs, and declaring one is enough to
require it. Three failures, each discovered further into the build than the last:

| # | Tree | What broke | How far in |
|---|---|---|---|
| 1 | OpenSSL | `doc/`, `demos/` and `fuzz/` are in the **unconditional** `SUBDIRS` line (`build.info:4`), so `Configure` walks into each and reads a `build.info` that is not there | Configure |
| 2 | Opus | `configure.ac:1039` declares `doc/Makefile` and `Makefile.am:10` names `./doc`, so `autoreconf` stops with *"required file `doc/Makefile.in` not found"* — **a message about a file nobody deleted** | ~3 minutes into a cross-compile, after OpenSSL had built |
| 3 | pjproject | the root `Makefile` names `pjsip-apps/src/pjsua/android` | `make` |

**The corrected rule: prune only what the tree's own build files never mention.** Not
"tests and docs". `tools/vendor/verify-prune.sh` greps every build file in every vendored
tree for every pruned path and fails if one is referenced — so the question is answered in
seconds rather than three minutes into a cross-compile.

**What is actually pruned, after the corrections:**

| Tree | Removed | Saving | Why it is safe |
|---|---|---|---|
| OpenSSL | `test/` | **89 MB** | **The one deliberate exception, and it carries two thirds of the saving.** `build.info:5-7` guards it — `IF[{- !$disabled{tests} -}] SUBDIRS=test` — and every `./Configure` here passes `no-tests`. `verify-openssl-prune.sh` asserts **both**, because the prune is safe only while both hold. Proven empirically: OpenSSL cross-compiled green with `test/` absent |
| pjproject | `tests/` → **restored** | — | The root `Makefile` recurses into it |
| pjproject | `pjsip-apps/src/samples`, `src/pjsua/ios`, `src/swig/{java/android,csharp,python}`, `src/rust` | ~8 MB | Sample apps and other-language bindings. Unreferenced by the build, and each carried its own `.gitignore` — which cost 348 silently dropped files before they were removed. `pjsip-apps/src/swig/{Makefile,pjsua2.i,java/Makefile}` are **kept**: they are stage 1 |
| pjproject | `src/pjsua/android` → **restored** | — | The root `Makefile` names it. It carries a `gradle-wrapper.jar` this repository would rather not hold; a wrapper jar is a smaller problem than a build that does not run |
| libvpx | `build_debug/` | ~4 MB | Pre-generated scratch, referenced by nothing |
| libvpx | `test/`, `examples/` → **restored** | — | libvpx's `configure` knows about both, and `--disable-unit-tests` is not the same as the directory being absent |
| Opus | nothing | — | `configure.ac` declares `doc/Makefile`; Opus is 16 MB and there is nothing here worth breaking a cross-compile for |
| — | **Total** | **~98 MB of 246** | |

**What is deliberately NOT pruned, and why each would have been a mistake:**

| Kept | Size | Why |
|---|---|---|
| `pjproject/pjsip-apps/src/swig/` (less its sample apps) | 4 MB | **This is stage 1.** `pjsua2.i` and the Java `Makefile` are what SWIG reads (N-13) |
| `pjproject/third_party/webrtc/` | 1.7 MB | `PJMEDIA_HAS_WEBRTC_AEC=1` in the green build's compile line — the acoustic echo canceller, and the difference between a usable speakerphone and feedback |
| `pjproject/third_party/*` generally | 16 MB | Upstream's own vendoring, built by relative path. Load-bearing |
| `opus/dnn/` | 10.7 MB | `Makefile.am:14` puts it on the include path. Opus 1.5's LACE/NoLACE enhancement; removing it breaks the build, not just a feature |

**`opus/dnn/` is the near-miss worth recording.** At 10.7 MB it was the second-largest prune
candidate by eye, and pruning it would have broken the build. It was kept because `grep`
found it on the include path — the same question `verify-prune.sh` now asks automatically.

### 1.3 Opus comes from the release tarball, because the tag archive downloads at build time

**Found by building on a laptop that has no `wget`.** It would have passed CI indefinitely.

`third_party/opus/autogen.sh:12` calls `dnn/download_model.sh`, which is:

```sh
model=opus_data-$1.tar.gz
if [ ! -f $model ]; then
        wget https://media.xiph.org/opus/models/$model
fi
```

**A vendored tree that fetches from the network at build time defeats N-2 outright.** The
whole claim of §2.1.2 is that the source is *in this repository* and the build reads what is
checked out. And every Ubuntu runner has `wget`, so this would simply have downloaded, on
every build, for ever — the egress-blocked job of §2.1.2 would eventually have caught it,
after ten minutes, with a socket error rather than a name.

**Why the tag archive has it and the release tarball does not.** GitHub's
`archive/refs/tags/v1.5.2.tar.gz` is the git tree at that tag: no generated `configure`, so
the build must run `autogen.sh`, and `autogen.sh` fetches the DNN weights. The official
release tarball ships `configure` pre-generated and carries the weights as **ten
`dnn/*_data.c` files**. It contains no download script at all.

**And it is what the green workflow already used** —
`.github/workflows/build-pjsip.yml:293-294` fetches from `downloads.xiph.org`. Vendoring
from the GitHub tag was this project's own divergence from a build that worked, and the
lesson generalises: **vendor from whatever the proven build fetched, not from whatever
GitHub makes convenient.**

`tools/vendor/verify-no-fetch.sh` now greps every vendored build entry point for `wget`,
`curl`, `git clone` and `download_model`, so this class fails in seconds rather than in the
egress-blocked job. That check is the cheap first line; the egress-blocked job remains the
authoritative one, because a fetch buried in a Makefile rule is beyond grep.

### 1.4 Five checks, because every one of them caught something

**A pruned tree that does not build is worse than an unpruned one**, and every failure in
this class is silent: the tree looks complete on the machine that vendored it, and CI fails
on a fresh checkout with an error nowhere near the cause. So each is asserted rather than
trusted. Three of the four caught a real defect on the first vendoring attempt.

| Check | What it asserts | What it caught |
|---|---|---|
| `tools/vendor/verify-vendored.sh` | Every file on disk under `third_party/` is one git tracks | **348 files silently dropped.** `.gitignore`'s `build/` rule matched `third_party/pjproject/build/` — which is not build output, it is the make-based build system every target includes — and the vendored sample apps' own `.gitignore` files excluded the rest |
| `tools/vendor/verify-openssl-prune.sh` | `build.info` still guards `SUBDIRS=test`, `no-tests` is still passed, and every unconditional `SUBDIR` exists | **`doc/`, `demos/` and `fuzz/` had been pruned** out of the unconditional `SUBDIRS` line |
| `.gitattributes` `third_party/** -text` | Git performs no line-ending conversion in a vendored tree | **188 CRLF files would have been rewritten**, so the committed bytes would not be upstream's bytes and rule 12's hash would differ between the vendoring machine and a fresh checkout |
| `tools/vendor/verify-no-fetch.sh` | No vendored build entry point runs `wget`, `curl`, `git clone` or `download_model` | **Opus fetched its DNN weights from `media.xiph.org` on every build** (§1.3). Every runner has `wget`, so CI would never have noticed |
| `tools/vendor/verify-prune.sh` | No pruned directory is one its own tree's build files name | Five separate restorations — §1.2 |
| Architecture **rule 12** | Each tree matches its recorded content hash | The standing check. Verified to reproduce byte-identically from a fresh `git worktree` checkout |

**The native stage now has a proven run — on one ABI, on one platform.** 2026-09-10, macOS
12.7.6, NDK r27c (`27.2.12479018`), `arm64-v8a`, driven by `pjsip/build-native.sh` exactly as
`pjsip/CMakeLists.txt` invokes it. It produced `libpjsua2.so` (19.5 MB) and
`libc++_shared.so` (1.8 MB), and the artefact was verified rather than assumed:

| Checked | Result |
|---|---|
| Architecture | `ELF64`, `AArch64` |
| 16 KB page alignment (Play) | every `LOAD` segment `0x4000` — **and the check ran**, rather than skipping for want of `llvm-readelf` |
| JNI entry point | `Java_org_pjsip_pjsua2_pjsua2JNI_swig_1module_1init` present |
| Declared codecs linked in | `opus_encoder_create`, `pjmedia_codec_opus_init`, `vpx_codec_encode`, `SSL_CTX_new` |

**And CI has now built all three ABIs on Linux, through Gradle.** Run `34423538239`,
2026-09-10, NDK r27c (pin asserted against `Pkg.Revision`), from vendored source:

```
pjsua2: configuring / building   arm64-v8a
pjsua2: configuring / building   armeabi-v7a
pjsua2: configuring / building   x86_64
native libraries: 3 ABIs × 2 libraries, all present
```

That last line is `assertNativeLibraries` — **N-6 satisfied at packaging time**, not inferred
from a green tick. No 16 KB alignment failure was reported for any ABI. `Build`, `Static
analysis` and `Architecture rules` all passed in the same run.

So N-2, N-4, N-5 and N-6 are demonstrated: the vendored source compiles, through one
`./gradlew`, with no manual step, for every supported ABI, on the platform CI runs.

**What is still NOT proven**, and the distinction is the one master prompt §10 insists on:

- **Nothing has run on a handset.** Not one call has been placed with these binaries. N-9's
  on-device codec round trip and every §9 budget remain unmeasured.
- **The egress-blocked offline job of §2.1.2 has not gone green.** Until it does, "the
  vendored tree needs no network" is an argument, not a result — and Opus proved that
  argument wrong once already (§1.3).
- **Reproducibility (N-11): measured. The answer is no, and here is exactly what varies.**
  See §1.5. `pjsip/build-native.sh`
carries the TLS/Opus/VPX assertions and the 16 KB alignment assertion, ported flag-for-flag
from the green workflow, and `.github/workflows/native-mandate.yml` runs the whole native
stage with **egress blocked** (§2.1.2). Until that job is green, the honest claim is
*"vendored, verified byte-identical, and built by a script ported from a proven build but
not yet proven itself"*.

---

## 2. Local patches

`pjsip/patches/` holds one patch and `vendored-tree.sha256`, rule 12's manifest.

| # | Subject | Tree | Reason | Upstream |
|---|---|---|---|---|
| `0001` | `lyra-config-without-protobuf` | lyra | **Adds** `lyra/lyra_config.pb.h`, the file `protoc` would generate for `lyra_config.proto`, as a hand-written proto2 parser of that one-field message (`optional int32 identifier = 1`). The only file ever parsed with it is two bytes. Removes protobuf — a host `protoc` build plus `libprotobuf-lite` — from the closure. Skips unknown fields and groups, so a `.binarypb` that grows still parses. No upstream file is modified | Not sent: upstream builds with Bazel and has no reason to want this |

**The rule (N-7).** Every local change is a **numbered patch file**, applied by the build in
order, never an edit to the vendored tree. Each carries: what it changes, why, and whether
it has been sent upstream.

```
pjsip/patches/
  0001-<short-description>.patch
  0002-<short-description>.patch
  README.md          — the index: number, subject, reason, upstream status
```

**Why files and not edits.** An unrecorded edit is invisible at the next version bump:
somebody bumps pjproject, re-applies the series, and the undocumented fix is silently gone —
a fixed bug returns with no commit that removed it. Architecture **rule 12**
(`docs/module-structure.md` §3.2) is the machine check: the tree on disk must equal
upstream-at-the-recorded-commit plus the patches, in order.

**The escalation threshold, decided in advance** (master prompt §2.9): start with patch
files; **fork `pjsip/pjproject` into an organisation remote and vendor from the fork when
the series exceeds what you would rebase by hand.** A patch series stops scaling the moment
you need a fix that is on upstream `master` but in no release — you cannot `git log` a
tarball. The offline test still passes from a fork; only the provenance improves.

---

## 3. The exempt toolchain — not vendored, not built here

**Master prompt §2.1.1.** N-1 governs **dependencies**, not **tools**. The test that
separates them: **does the artifact ship inside the APK?** A `.so` does; a compiler does
not. Everything that ships is built here from vendored source. Everything that *does the
building* is exempt — and pinned, because an unpinned tool is a silently different `.so`.

| Tool | Pinned version | Where CI gets it | Pinned today? |
|---|---|---|---|
| **Android NDK** | **r27c** | `nttld/setup-ndk@v1` (`.github/workflows/build-pjsip.yml:207`) | **Yes.** r27+ is mandatory for 16 KB page sizes — Android 15 and Play submissions (ADR-006) |
| **SWIG** | — | `apt-get install swig` on the runner | **NO — and this is a defect.** `pjsip/api/build.gradle.kts:25-27` states it outright: "whatever `apt-get install swig` gives the runner". Between 4.2.0 and 4.4.1 the typed vector classes rename `add`/`reserve` to `doAdd`/`doReserve`, and 45 generated files differ. **A runner image bump silently changes the JNI surface.** Fix in phase 3a |
| **JDK** | 17 (AAR job), 21 (APK job) | `actions/setup-java` (`:592`, `:747`) | **Yes**, but **two different versions in one pipeline** — worth a look, not necessarily a defect |
| **autotools** | runner default | Ubuntu image | **No.** Drives `configure-android` |
| **CMake** | runner default | Ubuntu image | **No.** Becomes load-bearing at N-4 |
| **Ninja / Make** | runner default | Ubuntu image | **No** |
| **Python** | runner default | Ubuntu image | **No.** OpenSSL's `Configure` is Perl; Python is used by libvpx tooling |
| **Bazel** | 5.3.2 *if* Lyra ships | Lyra's `.bazelversion` | Gate-dependent — §5 |
| **Gradle wrapper + every Maven dependency** | `gradle/libs.versions.toml` | Maven Central / Google | **Yes.** That supply chain is already governed there, and `settings.gradle.kts:19-38` narrows it to two repositories |

**The honest summary of this table: one tool is pinned, one is a known defect, and five run
at whatever the runner image happens to carry.** N-11 (reproducibility) cannot be measured
over an unpinned toolchain, so **pinning these is a prerequisite for budget 13 of §9, not a
tidy-up after it.**

**SWIG is the urgent one** and it is not a theoretical risk: the `.so` exports
`Java_org_pjsip_pjsua2_pjsua2JNI_*` symbols named after the Java SWIG generated beside it.
Today a comment asks people to be careful. Generating both halves in one build from one
source tree makes the drift structurally impossible — which is the whole argument for N-13
in one sentence — **but only if the SWIG that generates them is the same one every time.**

---

## 4. Licences

| Dependency | SPDX | Full text at | Obligation |
|---|---|---|---|
| pjproject | `GPL-2.0` or commercial | `third_party/pjproject/COPYING` | **See below. This is the release blocker** |
| OpenSSL | `Apache-2.0` | `third_party/openssl/LICENSE.txt` | Attribution; no copyleft |
| Opus | `BSD-3-Clause` | `third_party/opus/COPYING` | Attribution |
| libvpx | `BSD-3-Clause` | `third_party/libvpx/LICENSE` | Attribution |

**Licence text is copied into the tree, not linked.** A link is a promise about a server
somebody else operates.

### 4.1 ADR-002 got sharper, not softer

The licence position is **GPLv2 working assumption, commercial licence UNRESOLVED**
(`docs/architecture.md:33-36`). Vendoring does not soften that — it sharpens it.

Statically linking GPLv2 code into a closed-source APK you distribute is precisely the case
the GPL addresses. **And the source is now in this repository**, which makes the obligation
visible to anyone who clones it rather than buried in a build step.

If "full control" is meant to include the right to ship this closed-source, that is a
**Teluu commercial licence — a purchase, not a task**, and no amount of build ownership
substitutes for it.

**DECIDE before any store submission.** Carried in every phase report until decided.

---

## 5. Lyra — the §2.4 gate

**Status: closed at Exit A, 2026-09-10.** Criterion 1 passed on the first attempt:
TensorFlow Lite v2.11.0 with the XNNPACK delegate configured and built for `arm64-v8a`
under NDK r27c with zero errors (1,070 compile steps), and the whole closure — glog,
audio_dsp, Lyra — linked into one `liblyra.a` against which pjproject's own
`--with-lyra` link test passes. Run on a Zebra TC15 with the model files, the codec
encoded 50 frames of a 16 kHz tone at exactly 8 bytes/frame (3200 bps) and decoded 16,000
samples. The 2022-vintage `cpuinfo`/XNNPACK sources the gate feared compiled unchanged.

`third_party/lyra` and its eighteen dependencies exist (§1.0); `pjsip/lyra/CMakeLists.txt`
builds them; `pjsip/build-native.sh` runs it before pjproject and passes
`--with-lyra=$prefix`; `config_site.h` says `PJMEDIA_HAS_LYRA_CODEC 1`; the app ships the
weights as assets and copies them to `filesDir/lyra` on first run
(`LyraModels.kt`); the codec audit reports `lyra` registered and stranded by peer, or
`ModelFilesUnusable` with the reason.

**What is not yet verified, and by what:** `libpjsua2.so` with Lyra in it has not been
built — the Mac that did the above has SWIG without its Java typemaps, so stage 2 cannot
run there; CI can. The registration of `lyra/16000/1` on a handset, and a call carried by
it, are owed behind that build. §5.4 still stands: no deployed server offers Lyra.

### 5.0 What the closure costs at build time

TensorFlow Lite is ~1,100 compile steps: 25 minutes at `-j6` on a 4-core Mac, per ABI.
`build-native.sh` stamps the Lyra stage by the hash of all nineteen trees plus the CMake
file, so a local re-run skips it. **CI has no persistent stage and pays it on every run,
three ABIs each.** The fix is an `actions/cache` keyed on that same hash around
`$STAGE/<abi>/lyra-build` and `$PREFIX/<abi>/lib/liblyra.a`; not done in this pass, and the
first CI run will show the cost before the cache is justified.

The sections below are the investigation as it stood before the build; they are kept
because they say what was expected and are the record of what did and did not bite.

### 5.1 Verified upstream state — 2026-09-09, from the GitHub API

| Fact | Value | Source |
|---|---|---|
| `.bazelversion` | **5.3.2** | `google/lyra/.bazelversion`, read directly |
| `com_google_glog` | **`branch = "master"` — unpinned, floats** | `WORKSPACE:94-96` |
| `com_github_gflags_gflags` | **`branch = "android_linking_fix"` — unpinned, and an individual's fork** | `WORKSPACE:100-102` |
| `org_tensorflow` | `commit = "d5b57ca93e506df258271ea00fc29cf98383a374"` (= v2.11.0) | `WORKSPACE:169-172` |
| `com_google_protobuf` | `tag = "v3.15.4"` | `WORKSPACE:27-29` |
| `com_google_absl` | `tag = "20211102.0"` | `WORKSPACE:37-39` |
| `com_google_audio_dsp` | `commit = "14a45c5a…"`, with the comment *"There are no tags for this repo, we are synced to bleeding edge"* | `WORKSPACE:69-73` |
| `fft2d` | a URL, one academic host, no mirror | `WORKSPACE:84-87` |
| Repository size | 19 MB | GitHub API |

**Two dependencies float, and N-11 cannot be measured until they are pinned.** That is not a
reproducibility *finding* — it is an unpinned *input*, and master prompt §2.4.3 is explicit
that pinning glog and the gflags fork to commit hashes, and mirroring `fft2d` into this
repository, is **the first act of the spike, before any build attempt**.

### 5.2 Criterion 1, and why it is checked first

> `liblyra` and its closure build for `arm64-v8a` with **NDK r27+**, and the resulting
> `.so` files are 16 KB aligned.

**The conflict, stated precisely.** Lyra's README pins NDK **r21.4.7075529**. This project
mandates **r27c** for 16 KB page alignment (ADR-006, and Play's requirement for Android
15+). Lyra at r21 and pjproject at r27 is a C++17 libc++ ABI mismatch across the `-llyra`
link, and an r21-built `.so` is not 16 KB aligned. **Either Lyra's build is ported to a
modern NDK, or the two halves cannot ship in one APK.**

**The specific unknown to test:** the NDK ceiling of Bazel 5.3.2's
`android_ndk_repository`. **ASSUMPTION: it is well below r27.** This has not been tested and
it is exactly what criterion 1 exists to settle. It is a CI question, not a laptop one.

### 5.3 The model files, if the gate ever passes

Four files, **~3.6 MB total**.

**ASSUMPTION — these four sizes are the one set of numbers in this document I did not
verify myself.** They are taken from `docs/master-engineering-prompt.md` §9.8, which
attributes them to upstream `main` on 2026-09-09. GitHub's tree and contents APIs both
returned 404 for `google/lyra` when I tried to confirm them, so they are carried forward
labelled rather than restated as fact. **Confirm by `du` on the vendored tree if the gate
ever passes** — the total matters only for §9 budget 8, and the *presence and integrity* of
the files matters far more than their size.

| File | Size |
|---|---|
| `soundstream_encoder.tflite` | 1.78 MB |
| `lyragan.tflite` | 1.48 MB |
| `quantizer.tflite` | 329 KB |
| `lyra_config.binarypb` | 2 bytes |

**Said out loud, per master prompt §2.4.3 item 4: these are prebuilt binaries this
repository cannot compile.** They are trained weights — data, not code. N-3 deliberately
requires vendoring them, and N-1's absolutism must not be read as covering them. **They are
the one exception, and it is written down rather than assumed.**

At 3.6 MB they are a **correctness** problem, not a download-size one: without them the
codec **registers and then fails when a stream opens**, which is worse than not having it,
because it advertises a capability it cannot deliver. Ship a content-hash manifest and
verify at extraction; a truncated asset must be a loud startup failure, not a dead call.

### 5.4 Even on Exit A, there is no peer

The deployed FreeSWITCH offers **PCMU, PCMA, G.729, G.723.1, AMR, Speex, VP8, VP9** and
nothing else (`fs_cli -x "show codec"`, 2026-09-09 — `docs/reconciliation.md` A-1b).

So on Exit A the honest deliverable is *"Lyra compiled from vendored source, registered,
model files verified, and provably selectable, with no deployed peer that accepts it"* —
which is exactly what N-9 asks for, and it is a real result. **What it is not is "Lyra
calling works." Do not report the former as the latter.**

**And note the same sentence is true of Opus today**, which already ships. The codec audit
(`docs/data-structures.md` §4) is what lets the app say so.

---

## 6. The N-10 CI check

**PROPOSED.** A check that this file lists **exactly** the directories under `third_party/`
— no more, no fewer.

**Two failure directions, and both matter.** A directory with no row is an undocumented
dependency. A row with no directory is a document describing something that no longer
exists, which is DoD 14.

**The check must not expect the §3 toolchain rows to be directories.** They are listed
under their own heading precisely because they are *not* in `third_party/`, and a naive
implementation that scans every table in this file will fail on them. That is the one
subtlety in an otherwise trivial check, and it is written here so it is not rediscovered.

### 1.5 N-11 — measured, and the answer is *not bit-reproducible*

N-11 asks for two builds of the same commit to be hash-compared, and permits the answer to
be no *"provided the document states precisely why they cannot and what varies"*. This is
that statement.

**The measurement.** CI runs `34428646793` and `34429022474`. The two commits differ only
in workflow YAML — `git diff` over `third_party/`, `pjsip/build-native.sh`,
`pjsip/CMakeLists.txt` and `pjsip/config/` is **empty**, so the native inputs and the
toolchain are identical. Same runner image, same NDK r27c, same SWIG 4.2.0.

| Library | ABI | Size, both runs | Digest |
|---|---|---|---|
| `libc++_shared.so` | arm64-v8a | 1,794,776 | `f9992c4b…` **identical** |
| `libc++_shared.so` | armeabi-v7a | 1,301,936 | `4df8b2e8…` **identical** |
| `libc++_shared.so` | x86_64 | 1,617,608 | `cd110349…` **identical** |
| `libpjsua2.so` | arm64-v8a | 19,553,544 | `6a530a30…` vs `757f0698…` — **differs** |
| `libpjsua2.so` | armeabi-v7a | 15,224,252 | `cee0a77c…` vs `c965eaff…` — **differs** |
| `libpjsua2.so` | x86_64 | 19,501,168 | `28a70a12…` vs `29bb5725…` — **differs** |

**Two results, and the contrast is the informative part.**

`libc++_shared.so` is **bit-identical** across runs, on every ABI. It is *copied* from the
NDK rather than built, so it reproduces exactly — which also confirms the comparison method
works and is not simply reporting noise.

`libpjsua2.so` **differs in content while matching in size to the byte**, on every ABI.
Identical length with different bytes is the signature of something *fixed-width* being
embedded — a build id, a timestamp, or a path of constant length — rather than a structural
difference in what was compiled. A different code path or a different dependency would
change the size.

**What has NOT been established is which of those it is.** The candidates are pjproject's
`-Wl,--build-id=sha1`, a `__DATE__`/`__TIME__` expansion somewhere in pjproject or OpenSSL,
and the absolute build path — and naming one without evidence would be exactly the kind of
laundered guess §3 of the master prompt forbids. `build-native.sh` now prints the build id
and any embedded date/time per ABI, so the next green run answers it from data.

**Cross-platform, for completeness and clearly labelled as a different question.** The same
commit built on macOS 12.7.6 produced `arm64-v8a/libpjsua2.so` at **19,559,040** bytes
against CI's **19,553,544** — a 5,496-byte difference, so macOS and Linux builds differ
*structurally*, not just in an embedded field. N-11 does not ask for cross-platform
reproducibility and this build does not have it.
