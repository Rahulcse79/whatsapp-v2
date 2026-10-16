/*
 * The declared feature set for the iOS build (the Apple half of N-8).
 *
 * `pjsip/config/pj/config_site.h` is the same decision for Android. The two files are
 * deliberately separate rather than one file with `#if defined(PJ_ANDROID)` arms, for a
 * reason that is specific to how pjproject consumes them: on Android this header is read
 * TWICE — once by SWIG to generate the Java API and once by the cross-compile — and the
 * whole argument for keeping it in one place is that those two readings cannot disagree.
 * iOS has no SWIG stage; Swift calls the C API directly through `PjsipBridge.mm`. Merging
 * the files would therefore make the Android invariant harder to see while buying iOS
 * nothing, and it would put Android's MediaCodec decisions in front of a reader looking
 * for Apple's.
 *
 * What the two files MUST agree on is the wire: the codecs, the transports and the call
 * ceiling. Those are stated here with the Android value beside them, and the differences
 * are the ones the platform forces.
 *
 * ## This file is NOT copied into third_party/
 *
 * The vendored tree is read-only — architecture rule 12 hashes it and an edit without a
 * patch in `pjsip/patches/` fails the build. `build-pjsip-ios.sh` copies pjproject into a
 * work directory and drops this header into the copy, exactly as `pjsip/build-native.sh`
 * does for Android.
 */

#ifndef PJ_CONFIG_SITE_H_IOS
#define PJ_CONFIG_SITE_H_IOS

/* ---------------------------------------------------------------- the wire
 *
 * Everything in this block has a counterpart in the Android config_site.h and the two
 * values are the same. A difference here is a call that negotiates differently depending
 * on which handset placed it, which is the one class of bug a shared backend cannot help
 * with.
 */

/* Simultaneous calls. 8, matching Android: ADR-009 mixes N calls locally with
 * pjmedia_conf, so an 8-party conference is 7 concurrent calls on this device.
 *
 * NOT sufficient on its own — this is only the compile-time default for
 * `pjsua_config.max_calls`. `PjsipBridge.mm` sets `ua_cfg.max_calls` explicitly at
 * startup from the same constant, for the same reason the Android gateway does: raising
 * this alone left pjsua answering the fifth INVITE `486 Busy Here`. */
#define PJSUA_MAX_CALLS                     8

/* Video calling at all. OFF by default upstream, and this app is a video softphone. */
#define PJMEDIA_HAS_VIDEO                   1

/* VP8 — the ONLY video codec both ends can negotiate. The deployed FreeSWITCH offers VP8
 * and VP9 from CORE_VPX and no H.264 at all, so without libvpx there is no video call.
 * Backed by third_party/libvpx, cross-compiled by build-pjsip-ios.sh. */
#define PJMEDIA_HAS_VPX_CODEC               1

/* Opus. Backed by third_party/opus.
 *
 * As on Android, and for the same reason: the deployed server has `mod_opus` configured
 * and its .so not installed, so every call negotiates G.711 today. The codec audit
 * reports that as `NoPeerAccepts` rather than leaving it silent. Compiling it here keeps
 * the two clients offering the same list. */
#define PJMEDIA_HAS_OPUS_CODEC              1

/* TLS. Without OpenSSL, aconfigure builds a stack with TLS silently disabled and the
 * first anybody knows is a TLS account failing on a handset. build-pjsip-ios.sh fails
 * the build rather than letting that through. */
#define PJSIP_HAS_TLS_TRANSPORT             1

/* ---------------------------------------------------------------- Apple-only
 *
 * Below here the two platforms genuinely differ.
 */

/* H.264 through VideoToolbox, not OpenH264.
 *
 * Android gets H.264 from MediaCodec and sets `PJMEDIA_HAS_OPENH264_CODEC 0` with a
 * DECIDE note attached. The same decision resolves differently here because Apple ships
 * the encoder: `aconfigure` detects VideoToolbox for an `*-apple-darwin_ios*` host and
 * defines `PJMEDIA_HAS_VID_TOOLBOX_CODEC=1` itself, so H.264 costs this build nothing and
 * needs no fifth native dependency. It is NOT defined here — letting configure own it is
 * what keeps a simulator build (where the framework exists but the hardware encoder does
 * not) from asserting a codec it cannot run. */

/* CoreAudio, and nothing else. PortAudio is the upstream default on Darwin and is wrong
 * on a phone: it has no route control, no interruption handling and no voice-processing
 * unit. Taken from upstream's own PJ_CONFIG_IPHONE profile. */
#define PJMEDIA_AUDIO_DEV_HAS_PORTAUDIO     0
#define PJMEDIA_AUDIO_DEV_HAS_WMME          0
#define PJMEDIA_AUDIO_DEV_HAS_COREAUDIO     1

/* Speex's echo canceller is off because CoreAudio's VoiceProcessingIO unit already does
 * it in hardware on every supported device, and running both produces audible pumping.
 * This is upstream's own recommendation in the PJ_CONFIG_IPHONE profile. */
#define PJMEDIA_HAS_SPEEX_AEC               0

/* L16 is uncompressed 16-bit PCM — 128 kbit/s for narrowband, 256 for wideband. No
 * deployment this app talks to offers it, and leaving it compiled puts two entries at the
 * top of every SDP offer that exist only to be ignored. */
#define PJMEDIA_HAS_L16_CODEC               0

/* Lyra is NOT compiled for iOS, and this is a known gap rather than a decision.
 *
 * ADR-008 closed at Exit A for Android: TensorFlow Lite, XNNPACK, audio_dsp, glog and
 * Lyra v1.3.2 all build for arm64-v8a under NDK r27c from nineteen vendored trees. That
 * closure has not been cross-compiled for iOS. Until it is, an iPhone and an Android
 * handset on the same call negotiate G.711 or Opus rather than Lyra — which works, and
 * costs the bandwidth Lyra exists to save.
 *
 * Stated as 0 rather than left undefined so that `pjsua_codec_enum` and the codec audit
 * report it as NotCompiled, which is the honest state, instead of omitting the row. */
#define PJMEDIA_HAS_LYRA_CODEC              0

/* iLBC from CoreAudio rather than the bundled implementation: it is the same codec and
 * Apple's is hardware-accelerated. Upstream PJ_CONFIG_IPHONE. */
#define PJMEDIA_HAS_ILBC_CODEC              1
#define PJMEDIA_ILBC_CODEC_USE_COREAUDIO    1

/* Hardware floating point. Every arm64 device has it; upstream's profile sets this for
 * the armv6/armv7 era and it remains correct. */
#define PJ_HAS_FLOATING_POINT               1

/* ---------------------------------------------------------------- CallKit
 *
 * The audio device must NOT be opened when a call connects.
 *
 * iOS hands the audio session to the app in `provider(_:didActivate:)`, which happens
 * AFTER CallKit has decided the call may have audio — after the user has accepted, and
 * after any cellular call has been put on hold. A stack that opens the device itself on
 * SDP negotiation races that callback, and the symptom is a call that connects with no
 * audio in either direction until it is held and resumed.
 *
 * So pjsua never opens the device on its own: `PjsipBridge.mm` calls
 * `pjsua_set_no_snd_dev()` at startup and `pjsua_set_snd_dev()` from the CallKit
 * activation callback. This flag is what makes that safe — with it, pjsua keeps the
 * conference bridge running on a null device instead of tearing media down when no
 * sound device is present.
 */
#define PJSUA_DONT_RELEASE_SND_DEV          1

/* Codec quality 4, not 5: it is what makes pjsua instantiate the resampler with the small
 * filter, which is the difference between a resampler that fits a phone's CPU budget and
 * one that does not. Upstream PJ_CONFIG_IPHONE. */
#define PJSUA_DEFAULT_CODEC_QUALITY         4

/* ---------------------------------------------------------------- sizing
 *
 * Upstream's iPhone profile sets these to 31/31 and PJSUA_MAX_ACC 4, sized for a
 * single-account softphone. This app is multi-account and mixes an 8-party conference
 * locally, so the dialog and transaction tables are sized from PJSUA_MAX_CALLS instead of
 * from upstream's assumption.
 *
 * A transaction table that is too small does not degrade: `pjsip_tsx_layer_create` returns
 * PJ_ETOOMANY and the INVITE is never sent. 8 calls can have an INVITE, a BYE, a REFER and
 * a re-INVITE in flight each, plus one REGISTER per account, so 127 is four times the
 * worst case and costs a few kilobytes of hash table.
 */
#define PJSIP_MAX_TSX_COUNT                 127
#define PJSIP_MAX_DIALOG_COUNT              127
#define PJSUA_MAX_ACC                       8

/* One conference port per call, plus the two the sound device occupies, plus headroom for
 * the tone generators ringback uses. Upstream's formula with this app's call ceiling. */
#define PJSUA_MAX_PLAYERS                   4
#define PJSUA_MAX_RECORDERS                 4
#define PJSUA_MAX_CONF_PORTS                (PJSUA_MAX_CALLS + 2 * PJSUA_MAX_PLAYERS + 4)

#endif /* PJ_CONFIG_SITE_H_IOS */
