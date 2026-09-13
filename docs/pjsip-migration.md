# PJSIP migration — the plan, and the API it is written against

**ADR:** ADR-006 (accepted 2026-09-08) · **Supersedes:** ADR-001 ·
**Outcome:** the previous SIP stack is removed entirely; the stack becomes PJSIP 2.17.

This is the working plan for that migration. It exists because the interesting part is not
the Kotlin — the seam for this was built in ADR-001 and only two files touch the SDK — it
is the three places where pjsua2 is **not** a drop-in for what it replaced, and the one
constraint that changes the threading model.

Every API fact below was read out of the pjproject 2.17 headers
(`pjsip/include/pjsua2/*.hpp`) and the official Android Kotlin sample
(`pjsip-apps/src/swig/java/android/app-kotlin`). None of it is recalled or inferred. Where
something is **not** verified it says so.

---

## 1. What actually changes

Two files import the SDK. That is the whole surface:

| File | Lines | Fate |
|---|---|---|
| the previous stack's core gateway | 1,066 | Replaced by `RealPjsipCoreGateway.kt` |
| `data/sip/…/stack/SipStackInfo.kt` | 52 | Rewritten — one call for a version string |
| The other ~3,900 lines of `:data:sip` | — | **Unchanged.** Already SDK-free |
| 3,663 lines of `:data:sip` tests | — | **Unchanged.** They drive `FakeSipCoreGateway` |

The four gateway interfaces — `SipCoreGateway`, `SipCallGateway`, `SipVideoGateway`,
`SipRecordingGateway` — do not change shape. That is the point of them.

---

## 2. The three things that are not a translation

### 2.1 Thread confinement — this is the one that bites

The previous stack did not care which thread called it. **pjsua2 does.** Every thread that calls
into the library must first be registered, and `endpoint.hpp` is explicit about the cost:

> Note that each time this function is called, it will allocate some memory to store the
> thread description, **which will only be freed when the library is destroyed.**

The current gateway is called from `Dispatchers.IO` — a pool that grows to 64 threads and
recycles them. Registering on each call would leak a descriptor per thread per call, for
the life of the process. Registering once per thread still leaks one per pool thread, and
still races, because `libIsThreadRegistered()` is per-thread state on a pool that hands
work to whichever thread is free.

**Decision: the adapter owns a single-threaded dispatcher and confines every PJSIP call to
it.** `Endpoint`, `Account` and `Call` objects are only ever touched from it. The gateway's
public methods stay `suspend` and post to that executor internally, so nothing above the
seam changes.

**And it needs no explicit registration, which cost a native crash to learn.**
`Endpoint::libCreate` ends with `mainThread = pj_thread_this()` and
`threadDescMap[pj_thread_this()] = NULL` — it registers its own caller. Since `start` is
posted through the same executor as every other operation, that caller is the one thread
that ever calls in.

Calling `libRegisterThread` anyway was not a harmless duplicate. Placed between
`libCreate` and `libInit` it was a SIGSEGV: `pj_thread_register` takes a mutex that
`libInit` is what brings up, and the crash landed in `pj_mutex_lock` two frames under
`Endpoint::libRegisterThread`. It would have leaked as well — the `pj_thread_desc` it
mallocs is freed only at `libDestroy`.

This is not a preference. Calling pjsua2 from an unregistered thread is undefined
behaviour, and the failure is a native crash rather than an exception.

### 2.2 Callbacks arrive by subclassing, not by listener

The previous stack took a listener object. pjsua2 requires subclasses:

```kotlin
class PjAccount : Account() {
    override fun onRegState(prm: OnRegStateParam) { … }
    override fun onIncomingCall(prm: OnIncomingCallParam) { … }
}

class PjCall(acc: Account, callId: Int) : Call(acc, callId) {
    override fun onCallState(prm: OnCallStateParam) { … }
    override fun onCallMediaState(prm: OnCallMediaStateParam) { … }
    override fun onCallMediaEvent(prm: OnCallMediaEventParam) { … }
}
```

**These are SWIG director objects with a native peer.** The sample's own comment is
`/* Maintain reference to avoid auto garbage collecting */` — if the Kotlin object is
collected while the native side still holds it, the next callback crashes the process. The
adapter must hold a strong reference to every live `PjCall` and to the `Account`, and
release it only after `onCallState` reports `PJSIP_INV_STATE_DISCONNECTED`.

The existing `callsByKey` map already did exactly this, so the shape
carries over — but before it was a convenience and here it is a correctness
requirement.

### 2.3 Video renders into a Surface, not a TextureView

The previous stack took a `TextureView` directly. pjsua2 does not:

```kotlin
val wh = VideoWindowHandle()
wh.handle.setWindow(surfaceHolder.surface)   // android.view.Surface
videoWindow.setWindow(wh)
```

The `VideoWindow` comes off the call's own media info — `CallMediaInfo.videoWindow` for
the remote stream, `VideoPreview(devId).videoWindow` for the local preview — and is only
valid once `onCallMediaEvent` reports `PJMEDIA_EVENT_FMT_CHANGED` with a
`videoIncomingWindowId != pjsua2.INVALID_ID`.

**So `CallVideo.kt` changes from `TextureView` to `SurfaceView`**, and
`StackVideoSurfaceController` changes from "hand over two views once" to "hand over a
`Surface` whenever the holder or the window changes". That is UI work in `:feature:calls`,
outside the 1,118 lines counted above, and it is the part of this migration most likely to
need a device to get right.

`PjCameraInfo2.SetCameraManager(cameraManager)` must also be called once at startup or
there are no capture devices at all.

---

## 3. The API map

Verified against the 2.17 headers. This table exists so nobody re-derives it.

### Lifecycle — `Endpoint`

| Need | Call |
|---|---|
| Create / init / start | `libCreate()`, `libInit(EpConfig)`, `libStart()` |
| Shut down | `libDestroy(flags = 0)` |
| Current state | `libGetState(): pjsua_state` |
| Register a thread | `libRegisterThread(name)` · check with `libIsThreadRegistered()` |
| Transports | `transportCreate(pjsip_transport_type_e, TransportConfig): TransportId` |
| Codecs | `codecEnum2()`, `codecSetPriority(id, prio)` · `videoCodecEnum2()`, `videoCodecSetPriority(id, prio)` |
| Devices | `audDevManager()`, `vidDevManager()` |

Priority is `0..255`; `255` is highest, `0` disables. This replaces the old
`setAudioPayloadTypes(Array<PayloadType>)` — same intent, per-codec instead of per-array,
so `StackAccount.audioCodecs` / `videoCodecs` survive unchanged and only the loop behind
them is rewritten.

Codec ids are strings with a clock rate: `"opus/48000"`, `"PCMU/8000"`, `"AMR-WB"`. They
are **matched by prefix**, so `codecSetPriority("opus", 255)` is legal. The exact ids for
this build must be read back with `codecEnum2()` at runtime rather than assumed.

### Registration — `Account`

| Need | Call / field |
|---|---|
| Create | `Account.create(AccountConfig, makeDefault)` |
| Re-register | `setRegistration(renew = true)` |
| Unregister | `setRegistration(renew = false)`, then `shutdown()` |
| Change config | `modify(AccountConfig)` |
| State | `getInfo(): AccountInfo` · callback `onRegState(OnRegStateParam)` |

`AccountConfig` fields this app needs, all confirmed present:

- `idUri` — `"Display <sip:user@domain>"`
- `regConfig.registrarUri`, `regConfig.timeoutSec`, `regConfig.registerOnAdd`
- `regConfig.contactUriParams` — **this is where RFC 8599 `pn-provider`, `pn-param`,
  `pn-prid` go** (ADR-004, Task 38). Not `contactParams`: that one lands after the `>` as
  a header parameter, and FreeSWITCH stores only the URI
- `sipConfig.authCreds.add(AuthCredInfo("Digest", "*", user, 0, password))`
- `sipConfig.proxies.add(uri)` — the outbound proxy
- `sipConfig.transportId` — binds the account to a transport, which is how per-account
  UDP/TCP/TLS is selected
- `natConfig.iceEnabled`, `natConfig.sipStunUse`, `natConfig.turnEnabled`,
  `natConfig.udpKaIntervalSec` — the existing `NatPolicy` maps onto these
- `mediaConfig.srtpUse` (`pjmedia_srtp_use`), `mediaConfig.srtpSecureSignaling` — this is
  `SrtpPolicy`. **`MANDATORY` is `PJMEDIA_SRTP_MANDATORY`**, and unlike before it is
  genuinely per-account rather than core-wide, which removes a documented limitation in
  `docs/security.md`
- `videoConfig.autoShowIncoming`, `autoTransmitOutgoing`, `defaultCaptureDevice` — the
  Task 54 escalation prompt needs `autoTransmitOutgoing = false`

### Calls — `Call`

| Need | Call |
|---|---|
| Place | `makeCall(dstUri, CallOpParam)` |
| Answer / reject | `answer(CallOpParam)` with `statusCode = PJSIP_SC_OK` / `PJSIP_SC_BUSY_HERE` / `PJSIP_SC_DECLINE` |
| Hang up | `hangup(CallOpParam)` |
| Hold / resume | `setHold(CallOpParam)` / `reinvite(CallOpParam)` |
| Re-negotiate | `update(CallOpParam)` |
| DTMF | `dialDtmf(digits)` (RFC 2833) · `sendDtmf(CallSendDtmfParam)` (selects INFO) |
| Blind transfer | `xfer(dest, CallOpParam)` |
| Attended transfer | `xferReplaces(destCall, CallOpParam)` |
| State | `getInfo(): CallInfo` · `isActive()` |
| Audio media | `getAudioMedia(medIdx): AudioMedia` |
| Video | `vidSetStream(op, CallVidSetStreamParam)` |

`CallOpParam.opt` is a `CallSetting` with `audioCount` and `videoCount`. **That is how
`MediaProfile` is expressed:** audio-only is `audioCount = 1, videoCount = 0`; a video call
is `videoCount = 1`. There is no `isVideoEnabled` boolean.

Video stream ops (`pjsua_call_vid_strm_op`), which replace `params.isVideoEnabled`:

- `PJSUA_CALL_VID_STRM_ADD` — escalate to video
- `PJSUA_CALL_VID_STRM_REMOVE` — de-escalate
- `PJSUA_CALL_VID_STRM_CHANGE_DIR` — video mute, without dropping the stream
- `PJSUA_CALL_VID_STRM_CHANGE_CAP_DEV` — **camera switch**, no re-INVITE
- `PJSUA_CALL_VID_STRM_START_TRANSMIT` / `STOP_TRANSMIT`
- `PJSUA_CALL_VID_STRM_SEND_KEYFRAME`

**Audio does not connect itself.** The previous stack wired the capture and playback devices
automatically; pjsua2 requires it explicitly in `onCallMediaState`, per active audio
stream:

```kotlin
val am = getAudioMedia(i)
ep.audDevManager().captureDevMedia.startTransmit(am)
am.startTransmit(ep.audDevManager().playbackDevMedia)
```

Forgetting this is a connected call with silence in both directions, and it is the single
most likely reason a first PJSIP build "registers but has no audio". **Mute is
`stopTransmit` on the capture leg only** — not a library-wide flag.

### Recording

`AudioMediaRecorder.createRecorder(fileName, …)`, then `startTransmit` both call legs into
it. The existing `RecordingPolicy` and `EncryptedRecordingStore` are unaffected — only the
mechanism changes, and pjsua2 has no per-call `params.recordFile` equivalent.

### Not yet verified

- **Lyra.** Present in 2.17 as experimental, needs a Bazel build of `google/lyra` plus four
  TFLite model files in the APK, and is disabled by default. `config_site.h` would need
  `PJMEDIA_HAS_LYRA_CODEC`. Out of scope here; it is its own piece of work, and FreeSWITCH
  cannot negotiate it either (see the audit).
- **Conference.** `JoinConferenceUseCase` currently has no caller (see the group-call
  removal). If it is kept, dial-in conferencing is an ordinary call in pjsua2 too and the
  roster has no pjsua2 source at all — that needs deciding before it is ported.

---

## 4. Tasks, in dependency order

### P-1 · Produce a PJSIP artifact  ✅ **done 2026-09-09**

Three defects were fixed on 2026-09-08 (the host `ar` leaking into the Opus cross-compile;
a configure that accepted "no TLS, no Opus" silently; a 16 KB check that could not fail).
**The workflow then went green.**

**Evidence:** GitHub Actions run **`34317978694`**, 2026-09-09T06:11:28Z, head SHA
`051fe490` — an ancestor of `main`. Every job green:

| Job | Duration |
|---|---|
| Generate the pjsua2 Java API | 15s |
| pjproject 2.17 · arm64-v8a | 2m51s |
| pjproject 2.17 · x86_64 | 3m23s |
| pjproject 2.17 · armeabi-v7a | 2m46s |
| Assemble pjsua2.aar | 1m20s |
| Build the app APK | 2m21s |
| **Total** | **7m18s** |

Artifacts: `pjsua2-aar-2.17` (19 MB), `app-debug-apk-pjsip-2.17` (38 MB), and a per-ABI
`pjsip-2.17-<abi>` (9–10 MB each). The arm64 log shows `checking VPX usability... yes`, the
SSL/Opus configure-summary group, and the 16 KB LOAD-segment assertion running to
completion. The NDK resolved to **r27c**.

**Done when — met:** all three ABIs, `libpjsua2.so` with every LOAD segment aligned
≥ 0x4000, the generated `org.pjsip.pjsua2` sources, TLS and Opus both enabled.

> **The measurement that matters beyond this task.** 2m46s–3m23s per ABI is roughly a
> twentieth of the "about an hour per ABI" this project had been assuming, and it is the
> number that removes the case for a native-build cache. See `docs/system-design.md` §2.5.

### P-2 · Assemble a consumable AAR  ⚠️ **superseded by ADR-007**

> **Superseded 2026-09-09.** ADR-007 replaces the AAR entirely: the source is vendored into
> `third_party/` and `:pjsip` becomes the native build rather than a wrapper around a
> binary. **The instruction below to take `pjsua2-aar-<version>` from a workflow artifact
> and place it at `pjsip/libs/pjsua2.aar` is exactly the manual native step N-5 forbids**,
> and DoD 14 fails on any document that still describes it.
>
> **`:pjsip` now compiles from `third_party/`.** A verified `libpjsua2.so` was produced for
> `arm64-v8a` on 2026-09-10 — 16 KB aligned, `pjsua2JNI` present, Opus/VP8/OpenSSL linked —
> and stage 1 regenerates all 318 bindings byte-identically to the ones this task's AAR used
> to carry. This task is therefore **obsolete, not merely superseded**; it stays only until
> CI is green on all three ABIs, at which point it should be deleted outright rather than
> annotated again.



The matrix stages `jni/<abi>/libpjsua2.so` and a Java source root; the `aar` job compiles
the bindings against `android.jar` and packages all three ABIs into one
`pjsua2-<version>.aar`. It fails if any ABI is missing, because an AAR with two of three
is not a smaller AAR — it is an `UnsatisfiedLinkError` on the device that has the third.

**Gradle wiring, decided so it is not re-litigated:** a `:pjsip` module holding the AAR,
exposed through its `default` configuration:

```kotlin
// pjsip/build.gradle.kts
configurations.maybeCreate("default")
artifacts.add("default", file("pjsua2.aar"))
```

`settings.gradle.kts` sets `RepositoriesMode.FAIL_ON_PROJECT_REPOS`, so a module-level
`flatDir` is not available and would be the wrong shape anyway. A project dependency needs
no repository at all.

**This is deliberately not wired yet.** Adding a module that points at an AAR which does
not exist breaks the build for everyone, today, in exchange for nothing.

**Done when:** the AAR exists, `:pjsip` is added, and `:data:sip` resolves
`org.pjsip.pjsua2.Endpoint`.

### P-3 · Check section 3 against the generated Java  ✅ **done 2026-09-08**

Section 3 was derived from the C++ headers. SWIG's Java output is a faithful mapping but
not a literal one — enums become classes of static ints, `std::vector` becomes a typed
vector class, and overloads collapse.

**This got cheap.** SWIG generates the bindings from the headers alone: no NDK, no OpenSSL,
no Opus, no cross-compile. The `bindings` job does exactly that in about a minute and
publishes `pjsua2-java-api-<version>`, and it has no `needs:`, so it lands while the native
matrix is still running. It also prints the public signatures of the fourteen classes the
adapter touches straight into the log, so section 3 can be diffed without downloading
anything.

(This cannot be run on a Mac with MacPorts SWIG: the language libraries ship as separate
ports and `swig-java` supplies the `java.swg`, `arrays_java.i` and `enumtypeunsafe.swg`
that the base port does not. Ubuntu's `swig` package includes them, which is why this is a
CI job.)

**Done.** Run locally rather than in CI: SWIG 4.4.1 was already installed, and its Java
library files (`java.swg`, `arrays_java.i`, `enumtypeunsafe.swg`) were fetched as data
rather than installed as a port. 313 Java files generated and read. The `bindings` job
stays, because CI is where this must be reproducible.

**What the generated Java corrected in section 3** — every one of these would have been a
compile error written from the C++ headers alone:

| Header says | SWIG actually generates |
|---|---|
| `codecSetPriority(string, pj_uint8_t)` | `codecSetPriority(String, short)` — **short**, not int |
| typed enums | classes of `public final static int`; every enum parameter is `Int` |
| `unsigned` fields | `long` — `audioCount`, `videoCount`, `getIndex`, `libDestroy(long)` |
| — | `CallSetting.customCallId` exists; `CallInfo.getRemVideoCount()` is how a video offer is detected |
| `VideoWindowHandle.handle` | `WindowHandle.setWindow(java.lang.Object)` — a `Surface` passes |
| `std::vector` | `CallMediaInfoVector` etc., `AbstractList`-shaped: `get`/`add`/`size` |
| — | 2.17 adds `onCallRxReinvite` and `onCallTxOffer`; `onCallRedirected` returns `int` |

### P-4 · Write `RealPjsipCoreGateway`  ✅ **done 2026-09-08**

Implements the four existing gateway interfaces. Single-threaded dispatcher per §2.1;
strong references to director objects per §2.2; explicit audio port connection per §3.

**Written**, ~800 lines, against the generated Java. Single-threaded dispatcher per §2.1;
directors strongly held per §2.2; explicit audio port connection per §3.

**Not yet compiled** — that needs the AAR (P-1, P-2). The contract was not changed, so the
3,663 lines of existing `:data:sip` tests should pass untouched; a failure there means the
gateway contract was altered rather than implemented.

### P-5 · Move the video surface to `SurfaceView`  ✅ **done 2026-09-08**

`CallVideo.kt`, `StackVideoSurfaceController`, and `PjCameraInfo2.SetCameraManager` at
startup.

**Written.** `CallVideo.kt` uses two `SurfaceView`s driven by `SurfaceHolder.Callback`,
because a surface is valid between `surfaceCreated` and `surfaceDestroyed` rather than for
the composable's lifetime. The preview takes `setZOrderMediaOverlay(true)` — two
overlapping `SurfaceView`s compose by z-order, and without it the preview draws *behind*
the full-screen remote video and is invisible.

`PjCameraInfo2.SetCameraManager` is called in `RealPjsipCoreGateway.start()`; without it
there are no capture devices at all.

**Still needs a handset:** that both render, and that rotation releases the surface without
a native crash.

### P-6 · Cut over and delete the old stack  ✅ **done 2026-09-08**

All of it: the PJSIP gateway bound in `SipStackModule`, the old gateway
deleted, its catalog entry gone, its vendor repository gone from
`settings.gradle.kts`, and seven files renamed off the old stack's name.

**One deviation from the plan as written.** Rule 2 was *not* narrowed to `org.pjsip`
alone. It now bans `org.linphone` **everywhere** and confines `org.pjsip` to `:data:sip` —
two clauses rather than one. Narrowing as originally specified would have removed the only
thing that catches an old-stack import being added back, which is the opposite of what
this task is for.

**This was done ahead of a green P-1**, on an explicit instruction, so for a day the tree
did not compile at all: `:data:sip:compileDebugKotlin` failed with 259 unresolved
`org.pjsip` references, and because `./gradlew build` stops there, nothing behind it —
detekt, lint, the architecture rules, the whole rest of the gate — ran on any commit.

**That is fixed, and not by waiting for the binary.** The Java half of the AAR is SWIG
output generated from the pjsua2 headers alone, with no NDK, no OpenSSL and no
cross-compile, so it is checked in as `:pjsip:api` (313 generated files plus the five
hand-written `org.pjsip` camera and audio shims pjproject ships beside its C) and
`:data:sip` falls back to it whenever `pjsip/libs/pjsua2.aar` is absent. The two are
mutually exclusive — the AAR always wins — because both on one classpath is a
duplicate-class failure at dex time.

So the adapter is now compiled and checked against the real PJSIP API on every commit.
What is still gated on P-1 is *running*: there is no `libpjsua2.so` without the AAR, and
an APK built without it raises `UnsatisfiedLinkError` on the first call. `:pjsip` says
exactly that at configuration time.

**Verified:** zero old-SDK imports in production code, zero references
in build files.

### P-7 · Re-verify on hardware  🔴 **outstanding — nothing below is verified**

Registration over UDP/TCP/TLS, audio both directions, video both directions, DTMF to an
IVR, hold/resume, blind and attended transfer, SRTP mandatory refusing a cleartext peer.

**Done when:** each is recorded with its result. None of these are covered by the JVM suite
— they all cross the seam being replaced, and the tests deliberately do not.

### P-8 · Restore TLS certificate verification  🟡 **implemented; unverified on hardware**

The stack that was removed verified the server certificate and its CN on every TLS
connection, unconditionally, in the old gateway. The replacement
does not: `RealPjsipCoreGateway` creates its TLS transport with a default
`TransportConfig()` (`data/sip/.../RealPjsipCoreGateway.kt:201`), and pjsua2's
`TlsConfig.verifyServer` defaults to off. Nothing in the JVM suite covers it, because the
seam is exactly where the tests stop.

This was found while correcting the two comments that still described the old stack —
`app/src/main/AndroidManifest.xml` and `app/src/main/res/xml/network_security_config.xml`
both told the reader that certificate validation was set in a class ADR-006 deleted. The
comments are now accurate; the behaviour they describe is not yet restored.

**Now implemented.** `RealPjsipCoreGateway.tlsTransportConfig()` sets `verifyServer`, and
`PjsipTrustStore` supplies what it verifies against. Both halves were required:
`verifyServer` alone verifies nothing, because OpenSSL is built here with no default CA
store, and a `TlsConfig` with no `caListFile` rejects every certificate rather than
accepting them.

**Where trust comes from.** The device's own store, read through `AndroidCAStore` rather
than `/system/etc/security/cacerts` — the filesystem layout moved into an APEX module and
the `KeyStore` API did not. A free, publicly-signed certificate (Let's Encrypt) therefore
verifies with no configuration at all, which is the intended production setup. A
self-signed lab server is handled by dropping a PEM into
`data/sip/src/debug/assets/sip-ca/`; that directory is declared in the **debug source set
only**, so a lab certificate cannot be packaged into a release build — the file is absent
rather than ignored, which a runtime flag could not guarantee.

**Fail closed.** If no bundle can be assembled, `verifyServer` stays on and TLS fails. A
fallback to "verify nothing" would turn a broken trust store into a silent downgrade, and
that is precisely the failure verification exists to catch.

**Still outstanding:** none of this has met a real server. A TLS account has never
registered, so what is written down is a configuration, not a result.

**Done when:** a valid certificate registers, one the device does not trust is refused, on
hardware; and docs/security.md states which of the two halves owns which check.

### P-9 · Codecs the binary does not have  🔴 **outstanding — decisions, not defects**

`CodecPreferences.DEFAULT` names H264. The native build sets
`PJMEDIA_HAS_OPENH264_CODEC 0`. The two have disagreed since ADR-006, and the disagreement
is silent: `applyPriorities` only ever touches codecs PJSIP actually registered, so an
absent H264 is skipped rather than failing. Nothing breaks; an H264-only peer simply gets
no video, which on most IMS endpoints and every iOS device is most of the peers there are.

**Made visible, not fixed.** `applyPriorities` now logs any preferred codec the build
cannot honour, once per account. That turns a capability gap into something a log answers
instead of a call that half worked.

**Not fixed here, deliberately.** Enabling it means cross-compiling OpenH264 alongside
OpenSSL and Opus, and adding a fourth native dependency to a build that has never
completed a single run multiplies the unknowns in P-1 rather than resolving any. It also
carries Cisco's OpenH264 licensing terms, which is a product decision and not an
engineering one.

**Done when:** either OpenH264 is in the build and an H264 call connects, or H264 leaves
`CodecPreferences.DEFAULT` and the docs say why.

**Lyra is the same shape, handled the other way round.** `AudioCodec.LYRA` was added on
2026-09-08 so the account editor lists it — the editor offers `AudioCodec.entries`, so a
codec absent from the enum cannot be chosen at all. It is deliberately **not** in
`CodecPreferences.DEFAULT`, which is the difference from H264: selectable, never offered
until the binary can honour it, and a test asserts exactly that so the distinction is not
lost in a later edit.

Worth having. Lyra carries intelligible wideband speech at **3.2, 6 or 9.2 kbps** — an
order of magnitude under Opus — which on a congested mobile uplink is the difference
between a call and no call.

Two things are still missing, both native:

  - `PJMEDIA_HAS_LYRA_CODEC` defaults to `0` (`pjmedia/include/pjmedia-codec/config.h`).
    Enabling it means cross-compiling Google's Lyra, which brings Bazel and TensorFlow
    Lite into a build that has not yet completed a single run — the same reason OpenH264
    is not being added today.
  - The four model files (`lyra_config.binarypb`, `lyragan.tflite`, `quantizer.tflite`,
    `soundstream_encoder.tflite`) have to ship on the device, with
    `CodecLyraConfig.modelPath` pointed at them. Without them the codec registers and then
    fails when a stream opens, which is worse than not having it.

The payload name is lowercase `lyra`, matching `pjmedia/src/pjmedia-codec/lyra.cpp`. The
stack matches preferences to codec ids by prefix, so `LYRA` would match `lyra/16000/1`
never — silently. A test pins the spelling.

**And a third party has to agree.** §3 already records that the deployed FreeSWITCH cannot
negotiate Lyra. A codec is a contract between two endpoints: even with the native build and
the model files done, a call to this registrar would still fall back. Lyra is therefore
only worth finishing alongside a server that offers it, or for direct calls that never
touch the MCU — which is a product decision, not a build one.

**Done when:** the native build registers `lyra/16000/1`, the model files ship, the server
offers it, and a call negotiates it — or Lyra leaves the enum and the docs say why.

---

## 5. What this migration does not fix

The defects reported from the handset on 2026-09-07 and 2026-09-08 — the retained
`ConnectionService`, mute, speaker, the proximity blank, the codec preferences that never
reached the stack — were all **above** this seam, in `:app` and `:domain`. Every one of
them would reproduce identically on PJSIP.

They are fixed on `fix/media-controls-and-codecs`. Land and verify those first, or this
migration will be debugged with them still in play.
