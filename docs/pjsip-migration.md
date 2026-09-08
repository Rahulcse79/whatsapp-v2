# PJSIP migration — the plan, and the API it is written against

**ADR:** ADR-006 (accepted 2026-09-08) · **Supersedes:** ADR-001 ·
**Outcome:** liblinphone 5.5.18 is removed entirely; the stack becomes PJSIP 2.17.

This is the working plan for that migration. It exists because the interesting part is not
the Kotlin — the seam for this was built in ADR-001 and only two files touch the SDK — it
is the three places where pjsua2 is **not** a drop-in for liblinphone, and the one
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
| `data/sip/…/registration/stack/RealLinphoneCoreGateway.kt` | 1,066 | Replaced by `RealPjsipCoreGateway.kt` |
| `data/sip/…/stack/SipStackInfo.kt` | 52 | Rewritten — one call for a version string |
| The other ~3,900 lines of `:data:sip` | — | **Unchanged.** Already SDK-free |
| 3,663 lines of `:data:sip` tests | — | **Unchanged.** They drive `FakeSipCoreGateway` |

The four gateway interfaces — `SipCoreGateway`, `SipCallGateway`, `SipVideoGateway`,
`SipRecordingGateway` — do not change shape. That is the point of them.

---

## 2. The three things that are not a translation

### 2.1 Thread confinement — this is the one that bites

liblinphone does not care which thread calls it. **pjsua2 does.** Every thread that calls
into the library must first be registered, and `endpoint.hpp` is explicit about the cost:

> Note that each time this function is called, it will allocate some memory to store the
> thread description, **which will only be freed when the library is destroyed.**

The current gateway is called from `Dispatchers.IO` — a pool that grows to 64 threads and
recycles them. Registering on each call would leak a descriptor per thread per call, for
the life of the process. Registering once per thread still leaks one per pool thread, and
still races, because `libIsThreadRegistered()` is per-thread state on a pool that hands
work to whichever thread is free.

**Decision: the adapter owns a single-threaded dispatcher and confines every PJSIP call to
it.** One thread, registered once at `libCreate` time. `Endpoint`, `Account` and `Call`
objects are only ever touched from it. The gateway's public methods stay `suspend` and
`withContext(pjsipDispatcher)` internally, so nothing above the seam changes.

This is not a preference. Calling pjsua2 from an unregistered thread is undefined
behaviour, and the failure is a native crash rather than an exception.

### 2.2 Callbacks arrive by subclassing, not by listener

liblinphone takes a `CoreListenerStub`. pjsua2 requires subclasses:

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

The existing `callsByKey` map already does exactly this for liblinphone, so the shape
carries over — but for liblinphone it was a convenience and here it is a correctness
requirement.

### 2.3 Video renders into a Surface, not a TextureView

liblinphone takes a `TextureView` through `core.nativeVideoWindowId`. pjsua2 does not:

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

Priority is `0..255`; `255` is highest, `0` disables. This replaces liblinphone's
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
- `regConfig.contactParams` / `contactUriParams` — **this is where RFC 8599 `pn-provider`,
  `pn-param`, `pn-prid` go** (ADR-004, Task 38)
- `sipConfig.authCreds.add(AuthCredInfo("Digest", "*", user, 0, password))`
- `sipConfig.proxies.add(uri)` — the outbound proxy
- `sipConfig.transportId` — binds the account to a transport, which is how per-account
  UDP/TCP/TLS is selected
- `natConfig.iceEnabled`, `natConfig.sipStunUse`, `natConfig.turnEnabled`,
  `natConfig.udpKaIntervalSec` — the existing `NatPolicy` maps onto these
- `mediaConfig.srtpUse` (`pjmedia_srtp_use`), `mediaConfig.srtpSecureSignaling` — this is
  `SrtpPolicy`. **`MANDATORY` is `PJMEDIA_SRTP_MANDATORY`**, and unlike liblinphone it is
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

**Audio does not connect itself.** liblinphone wires the capture and playback devices
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

### P-1 · Produce a PJSIP artifact  🔴 blocks P-2 and P-4

`.github/workflows/build-pjsip.yml` has never completed a run. Three defects in it were
fixed on 2026-09-08 (the host `ar` leaking into the Opus cross-compile; a configure that
accepted "no TLS, no Opus" silently; a 16 KB check that could not fail) but fixing what can
be read is not a green run.

**Done when:** the workflow produces, for all three ABIs, a `libpjsua2.so` with every LOAD
segment aligned ≥ 0x4000, alongside the generated `org.pjsip.pjsua2` sources; and
`config.log` shows TLS and Opus both enabled.

### P-2 · Assemble a consumable AAR

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

### P-3 · Check section 3 against the generated Java  🟢 no longer gated on P-1

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

**Done when:** every discrepancy between the generated Java and section 3 is corrected
**here**, before any adapter code is written.

This is the step that keeps the adapter from being fiction. It is now the cheapest task in
the plan and it is not optional.

### P-4 · Write `RealPjsipCoreGateway`

Implements the four existing gateway interfaces. Single-threaded dispatcher per §2.1;
strong references to director objects per §2.2; explicit audio port connection per §3.

**Done when:** `:data:sip` compiles with both stacks present and the existing 3,663 lines
of `:data:sip` tests still pass — they do not touch either SDK, so a failure there means
the gateway contract was changed rather than implemented.

### P-5 · Move the video surface to `SurfaceView`

`CallVideo.kt`, `StackVideoSurfaceController`, and `PjCameraInfo2.SetCameraManager` at
startup.

**Done when:** local preview and remote video both render on a handset, and the surface is
released on rotation without a native crash.

### P-6 · Cut over and delete liblinphone

Only now, and in this order: bind the PJSIP gateway in `SipStackModule`; narrow
`ArchitectureRules` from `org.linphone || org.pjsip` to `org.pjsip` alone; delete
`RealLinphoneCoreGateway`; drop `linphone-sdk` from the catalog and the Belledonne
repository from `settings.gradle.kts`.

Doing this last is what keeps the app buildable and shippable throughout. **Deleting
liblinphone before P-4 passes leaves no working stack at all.**

**Done when:** no `org.linphone` import remains outside the arch-test violation fixtures,
the Belledonne repository is gone, and the APK ships no `liblinphone.so`.

### P-7 · Re-verify on hardware

Registration over UDP/TCP/TLS, audio both directions, video both directions, DTMF to an
IVR, hold/resume, blind and attended transfer, SRTP mandatory refusing a cleartext peer.

**Done when:** each is recorded with its result. None of these are covered by the JVM suite
— they all cross the seam being replaced, and the tests deliberately do not.

---

## 5. What this migration does not fix

The defects reported from the handset on 2026-09-07 and 2026-09-08 — the retained
`ConnectionService`, mute, speaker, the proximity blank, the codec preferences that never
reached the stack — were all **above** this seam, in `:app` and `:domain`. Every one of
them would reproduce identically on PJSIP.

They are fixed on `fix/media-controls-and-codecs`. Land and verify those first, or this
migration will be debugged with them still in play.
