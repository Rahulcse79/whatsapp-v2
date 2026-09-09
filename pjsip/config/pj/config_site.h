/*
 * The declared feature set (N-8). THIS FILE IS THE DECISION.
 *
 * pjproject's compile-time feature set is decided here and nowhere else. Both stages of
 * the build read this one file:
 *
 *   stage 1 — SWIG parses it, so the generated Java API describes exactly the library
 *             stage 2 produces;
 *   stage 2 — the cross-compile compiles against it.
 *
 * Generating the two halves from one file is the whole argument for N-13 in one sentence:
 * the `.so` exports `Java_org_pjsip_pjsua2_pjsua2JNI_*` symbols named after the Java SWIG
 * generated beside it, and a second copy of the feature set is how those two drift apart.
 * Before this file existed the same flags were written twice in one workflow, 290 lines
 * apart (.github/workflows/build-pjsip.yml:97-102 and :390-396).
 *
 * ## Why it lives under `config/pj/`
 *
 * pjproject includes it as `#include <pj/config_site.h>`, so the directory placed on the
 * include path has to CONTAIN a `pj/` — it cannot be it. With the header one level up,
 * SWIG stops at `pjsua2.i:210: Error: Unable to find 'pj/config_site.h'`, which names the
 * file it wanted and not the search path that was wrong.
 *
 * ## This file is NOT copied into third_party/
 *
 * The vendored tree is read-only: architecture rule 12 hashes it, and an edit with no
 * patch in pjsip/patches/ fails the build (N-7). Upstream ships `config_site_sample.h`
 * and `config_site_test.h` but no `config_site.h`, so the build puts THIS directory first
 * on the include path and `#include <pj/config_site.h>` resolves here. Nothing writes into
 * the vendored tree.
 *
 * ## Every flag carries the app capability that needs it
 *
 * A flag with no answer in that column is a flag nobody decided — see
 * docs/architecture.md §4.11, which is this table with the module that exercises each.
 */

/* Video calling at all. OFF by default upstream, and this app is a video softphone.
 * Exercised by :feature:calls through SipVideoGateway. */
#define PJMEDIA_HAS_VIDEO 1

/* VP8 — the ONLY video codec both ends can negotiate. The deployed FreeSWITCH offers
 * VP8 and VP9 from CORE_VPX and no H.264 at all (docs/reconciliation.md A-1b), so
 * without libvpx there is no video call. Backed by third_party/libvpx. */
#define PJMEDIA_HAS_VPX_CODEC 1

/* Opus — the only wideband audio codec in this build. Backed by third_party/opus.
 *
 * NOTE, and it is not a defect in this file: the deployed server does not offer Opus.
 * `mod_opus` is configured in its modules.conf.xml and its .so is not installed, so every
 * call negotiates G.711 today. The codec audit (docs/data-structures.md §4) reports that
 * as `NoPeerAccepts` rather than leaving it silent. Installing mod_opus server-side halves
 * the bandwidth of every call with no change here. */
#define PJMEDIA_HAS_OPUS_CODEC 1

/* TLS transport. Without OpenSSL, configure-android builds a stack with TLS silently
 * disabled and the first anybody knows is a TLS account failing on a handset.
 * DoD 13 and docs/security.md §Transport. Backed by third_party/openssl. */
#define PJSIP_HAS_TLS_TRANSPORT 1

/* H.264 is NOT compiled, and this is a decision with an open question attached.
 *
 * `CodecPreferences.DEFAULT` names H264 (domain/…/model/Codecs.kt:78-82) and this build
 * does not contain it, so the preference is silently dropped: applyPriorities iterates the
 * codecs PJSIP registered, and an absent H264 is skipped rather than raised
 * (docs/reconciliation.md A-1). Enabling it means cross-compiling OpenH264 — a fifth
 * native dependency — and accepting Cisco's licensing terms, which is a product decision.
 *
 * Until it is decided, the codec audit reports H264 as NotCompiled, which is the honest
 * state. DECIDE: OpenH264 in, or H264 out of DEFAULT. */
#define PJMEDIA_HAS_OPENH264_CODEC 0

/* Lyra is NOT compiled. ADR-008 — the §2.4 gate is open, criterion 1 only.
 *
 * Deliberately stated rather than left to the upstream default of 0, so that the audit's
 * `NotCompiled` reason has a decision behind it and this line is what changes on Exit A. */
#define PJMEDIA_HAS_LYRA_CODEC 0

/* Everything else pjproject decides for Android, including the acoustic echo canceller
 * (PJMEDIA_HAS_WEBRTC_AEC), the MediaCodec hardware path, the camera capture backend and
 * libyuv. `configure-android --use-ndk-cflags` sets those from the platform rather than
 * from here; docs/architecture.md §4.11 records the values the green build actually
 * produced, read off the compiler invocation rather than assumed. */
#include <pj/config_site_sample.h>
