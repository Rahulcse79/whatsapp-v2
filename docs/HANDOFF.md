# Handoff — paste this whole file as the opening message to the next agent

> **Repo:** `whatsapp-v2` · **Branch:** `docs/native-mandate-design` · **Written 2026-09-10.**
> `docs/master-engineering-prompt.md` governs *how* to work. `docs/calling-defects-prompt.md`
> is the corrected spec for the seven calling defects — **read it before anything else**, it
> carries the measured baseline. This file says what is done, what is half-done, what is
> wrong, and what to do next.
>
> **Read the "Known defects in the previous agent's own work" section first.** Two of them
> mean item 2 is *not* fixed, and one is uncommitted work that has never been run.

---

## 0. The one-paragraph state

Two root causes behind the reported calling failures were found by reading a device trace and
probing the server directly, and both are fixed in code with JVM tests: an endpoint-wide
codec wipe (item 1) and an oversized INVITE (item 2). **Item 2's fix is incomplete** — see
§3.1. Items 5 and 7 are done and tested. Item 3 is partly done. Item 4 was reframed and not
delivered. Item 6 was diagnosed and is not fixable here. **Lyra is 0% implemented — only a
dependency count exists.** Nothing has been verified on hardware, no APK has been built, and
CI has not yet gone green on these commits.

---

## 1. Verified facts you can build on — do not re-derive these

All measured on 2026-09-10 against the live setup. Trace read off the handset; server probed
from a laptop on the same network.

| Fact | Value |
|---|---|
| Server | `192.168.80.145`, presents as `iriscloud` (FreeSWITCH derivative, `mod_sofia`) |
| Handset | Zebra TC15, Android 13 (API 33), `arm64-v8a`, USB debugging, IP `192.168.137.140` |
| NAT | handset `192.168.137.140` arrives at the server as `192.168.100.150` |
| Extensions | `7000`–`7005`; `7000` is the handset |
| **UDP 5060** | **works** — SIP `OPTIONS` answered `200 OK` |
| **TCP 5060** | **`ECONNREFUSED`** |
| **TLS 5061** | **closed** |
| **Max SIP payload the path carries** | **1472 bytes.** 1475 gets no answer. 1472+8+20 = 1500 = Ethernet MTU; the path drops IP fragments silently |
| REGISTER round trip | **57 ms** including the 401 digest exchange |
| The app's audio INVITE | **1742 bytes** — 7 retransmits over 32 s, zero responses (Timer B) |
| The app's 781-byte INVITE | got `100 Trying` on the first attempt — this is the control |
| Codecs the library really registers | `opus/48000/2`, `G722/16000`, PCMU, PCMA, GSM, iLBC, AMR-WB, AMR, speex×3, L16×2 |
| Video registry | `VP8/102, H264/99, VP8/103, VP9/106` — **H264 comes from `MediaCodec`, not OpenH264** |
| LeakCanary | 1 leak, **2686 bytes per Telecom bind cycle**, retained by `android.telecom.ConnectionService$1.this$0` — the framework's own binder. Zero orphaned connections from app code |

**Two claims in the old spec were disproved and are already corrected in
`docs/calling-defects-prompt.md` §2.6.** Opus and G.722 *do* register (the audit's log line
was filtered); and "the request never reached anything" is specifically *because it exceeds
the path MTU and there is no TCP to fall back to*.

---

## 2. What is committed and green

Commits `038359e` and `2682eb6`, both pushed.

- **Item 1 — answered call disconnects. FIXED.** Root cause: `applyPriorities` set priority
  `0` on every registered codec no account preference named. An account saved with
  `audio=[lyra]` matched nothing, so **all** audio codecs went to 0, endpoint-wide and
  persistently. `pjmedia_endpt_create_audio_sdp` stops at the first disabled codec →
  zero-format m-lines → outgoing `m=audio 0 RTP/AVP 0`, and answering produced
  `PJMEDIA_SDPNEG_ENOMEDIA` and a `488` the app sent *itself*.
  Fix: `domain/…/codec/CodecPriorities.kt` — a preference set matching nothing changes no
  priority; one account cannot disable a codec another needs. 9 tests.
- **Item 5 — dialer selection. FIXED**, with the per-call/per-navigation trap covered: it
  survives navigation, is cleared by a *successful* call, and is kept after a refused one.
- **Item 7 — unregistered account. FIXED.** `PlaceCallUseCase` now registers then dials,
  bounded at 5 s (57 ms observed; the bound covers three Timer A retransmissions). New
  `PlaceCallError.NotRegistered`. 5 tests.
- **Item 3 — audit honesty. PARTLY DONE.** Registry now logged verbatim with priorities;
  `NoPeerAccepts` → `ExpectedUnsupportedByServer(source)` carrying its evidence; an ERROR
  fires when every audio codec sits at priority 0. **Not done:** per-codec on-device round
  trips (N-9), and deriving strandedness from real evidence.
- **CI red fixed** (`Unnecessary safe call` under `-Werror`), and `bin/` is now gitignored —
  stale IDE output made the architecture test scan its own violation fixtures and fail every
  rule, locally only.

**Verified green locally:** `:test:arch:test`, detekt, `:domain:test` (454), `:feature:dialer`
and `:feature:history` unit tests, all under `-PwarningsAsErrors=true`.

---

## 3. Known defects in the previous agent's own work — START HERE

### 3.1 Item 2 is NOT fixed. The trim alone is 54 bytes short.

The SRTP change narrows the offer to the two `AES_CM_128` suites, saving the two `AES_256`
lines. Measured from the real INVITE, those lines are **108 bytes each, not the 116 that was
estimated**:

```
1742 − 216 (two AES_256 lines)            = 1526   → STILL 54 BYTES OVER the 1472 limit
1742 − 216 − 198 (ICE ufrag/pwd/2 cands)  = 1328   → fits
```

**ICE is what closes the gap, and ICE was not changed.** `NatPolicy.DEFAULT` has
`iceEnabled = true` (`domain/…/model/NatPolicy.kt:29`), so on a default account the INVITE is
still fragmented and still dropped. On a flat LAN ICE buys nothing.

**DECIDE and implement:** either turn ICE off for this deployment (per-account setting already
exists and reaches the stack at `RealPjsipCoreGateway:924`), or find another 54+ bytes. Then
**re-measure on the handset** — do not trust the arithmetic alone.

### 3.2 ~~Uncommitted, never-run changes~~ — RESOLVED, committed and green

The constant corrections (`AES_256` 116→**108**, `AES_128` 76→**84**) and the rewritten
tests are committed. **9 tests ran and passed.** The working tree is clean; nothing is
outstanding here. The corrected arithmetic is what §3.1 above is based on.

### 3.3 `SdpBudget` is inert — it is documentation, not enforcement

Nothing in production calls it. It is referenced only from comments and its own tests. The
byte budget is therefore not enforced anywhere; a future change can re-inflate the offer and
nothing fails. Either wire it into a real check or say plainly that it is a documented
constant.

### 3.4 Nothing has been verified on hardware

The spec's own rule is *"a calling defect is not fixed until a call has been placed, answered
and heard."* No APK was built, no call placed. Every item above is "compiles and passes JVM
tests" and nothing more.

### 3.5 CI has not gone green on these commits

At handoff the last *completed* `CI` run was the pre-fix failure. `Native mandate` runs were
in progress. **Check `gh pr checks docs/native-mandate-design` before assuming anything.**

---

## 4. The local build is blocked, and it needs the user's password

`:data:sip` cannot compile on this Mac — SWIG's Java typemaps are missing, so
`:pjsip:api:generatePjsua2Bindings` fails:

```bash
sudo port selfupdate && sudo port install swig-java
```

**Ask the user to run it; you cannot.** Until then the gateway changes
(`RealPjsipCoreGateway`, `CodecAuditReporter`, `DeclaredFeatureSet`) are unverified by any
local compiler. The generated bindings from an earlier run survive at
`pjsip/api/build/generated/pjsua2/` (313 files), which is why the failure is only the
task's precondition check.

Once unblocked, the user's own verification command is:

```bash
./gradlew :app:assembleDebug -Ppjsip.abis=arm64-v8a -Ppjsip.swig=/opt/local/bin/swig -Ppjsip.swig.version=4.4.1
```

Gradle on this machine is slow (3–15 min). **Never run two Gradle invocations at once** — it
corrupts the build directory and every test class then fails to load with
`ClassNotFoundException`, which looks like a code failure and is not. If that happens:
`rm -rf <module>/build` and re-run singly.

---

## 5. Lyra — 0% implemented. Only the closure has been counted.

`PJMEDIA_HAS_LYRA_CODEC` is still **0**. `third_party/lyra` does not exist.
`AudioCodec.LYRA` is still out of `CodecPreferences.DEFAULT`. No build has been attempted.
**Do not describe any of this as working.** Full findings: `docs/lyra-criterion-1.md`.

What the count established, from `google/lyra` at tag `v1.3.2`:

- **Lyra's own code is small** — 20 non-test `.cc`, 34 `.h`. Its direct deps (absl
  `20211102.0`, glog, gulrak filesystem `v1.3.6`) all have CMake.
- **Rock 1 — TensorFlow Lite pinned at v2.11.0**, ~1.35 GB, reached through
  `lyra/tflite_model_wrapper.cc`, and the **XNNPACK delegate is included directly** so it is
  not optional. TFLite has an official CMake build with Android support; the risk is a 2022
  tree of `cpuinfo`/XNNPACK/ruy sources under a 2024 NDK.
- **Rock 2 — `com_google_audio_dsp`** has **no CMake build at all**, reached at 12 call sites
  for 6 targets (`signal_vector_util`, `number_util`, `resampler_q`, `mfcc`, `spectrogram`,
  `spectrogram:inverse_spectrogram`, `portable:read_wav_file`). All would be hand-written.
- **Cheap parts:** all four model files ship in the repo (**3.5 MB**), and
  `third_party/pjproject/aconfigure.ac:2582-2640` already implements `--with-lyra=DIR` — it
  link-tests `LyraDecoder::Create`, then sets `ac_lyra_model_path` and defines the flag.
  **pjproject needs no change.**

**Do this first and stop if it fails:** build **TFLite v2.11.0 for `arm64-v8a` with NDK r27c
via its own CMake, XNNPACK enabled**. Nothing else matters until that is known. If it cannot
be patched cheaply, that is ADR-008 **Exit B** — write it up, ship no code. A working clone is
already at
`/private/tmp/claude-501/-Users-rahulsingh-Desktop-whatsapp-v2/e0bd43b1-5ce8-4cf8-bae4-9ba7eaf5c9bd/scratchpad/lyra`
(may be gone; re-clone `--depth 1 --branch v1.3.2`).

Constraints: **do not edit `third_party/` directly — patches only** (rule 12). Vendor via
`tools/vendor/vendor.sh` + `pins.sh` + `record-hashes.sh`, no build-time downloads (N-2).
`config_site.h` is the one source of truth (N-8). Run `:test:arch:test`.

**And the fact that survives either exit:** the deployed server offers PCMU, PCMA, G.729,
G.723.1, AMR, Speex, VP8, VP9 — **no Lyra**. Even on Exit A the honest deliverable is
*"Lyra compiled, registered, model files verified, provably selectable, with no deployed peer
that accepts it."*

---

## 6. Not delivered, deliberately

- **Item 4 — "production-ready, fix all bugs."** Has no completion test, so no honest report
  can claim it. `docs/calling-defects-prompt.md` §3.4 replaces it with the six measurable
  budgets in `docs/dod-sweep.md`, **none of which has a number yet**.
- **Item 6 — memory leaks.** The one leak is the framework's own `ConnectionService` binder,
  2686 bytes per bind cycle, not reachable from app code. Not fixable here; the spec's
  acceptance criterion explicitly allows naming it instead. **Not measured across 20 call
  cycles** — that number is still owed.

---

## 7. Server-side work neither agent can do

- **No TCP listener on `192.168.80.145`.** A video INVITE is ~2700 bytes and cannot fit a
  1472-byte datagram after any trim, so **video calling cannot work until the server accepts
  TCP.** Raise it with whoever operates the server.
- **`mod_opus` is not installed.** Configured in `modules.conf.xml`, `.so` absent. Installing
  it halves the bandwidth of every call with **no client change** — the APK already registers
  and offers Opus.

---

## 8. Housekeeping found, not yet actioned

- `log.txt` at the repo root: 0 bytes, untracked, stale.
- `docs/phase-2-checkpoint.md` is referenced by nothing.
- `android-sip-app-prompt.md` at the root is referenced by nothing (its sibling
  `pjsip-native-build-prompt.md` *is* referenced by README and two docs).

---

## 9. How to work here

- **Measure before diagnosing.** Three diagnoses in this project were reached by reading code
  and the device disproved all three — including two by the agent writing this file.
- **Probe the server directly.** Bisecting the MTU with SIP `OPTIONS` took four minutes and
  settled a question source-reading had got wrong twice.
- Build with `-PwarningsAsErrors=true`; CI does, and a local green build without it means
  nothing.
- Verify on the handset, not an emulator.
- Grep every symbol, flag and codec id before naming it.
- **Report honestly.** "Implemented", "registered" and "verified on hardware" are three
  different claims, and only the last ends an argument.
