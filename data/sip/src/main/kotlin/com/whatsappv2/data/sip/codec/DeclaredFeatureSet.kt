package com.whatsappv2.data.sip.codec

import com.whatsappv2.domain.codec.DeclaredCodec

/**
 * The declared feature set (N-8), in the form the codec audit compares against.
 *
 * ## Why it is here and not in the gateway
 *
 * Two reasons, and the second is the load-bearing one.
 *
 * It is not a property of the gateway: the feature set is a decision about the **build**,
 * made in `pjsip/config/pj/config_site.h`, and the gateway merely reports what the resulting
 * library did with it.
 *
 * And it has to be reachable **from a JVM unit test**. Touching any `org.pjsip.pjsua2` type
 * runs `pjsua2JNI`'s static initialiser, which is `System.loadLibrary("pjsua2")` — fine on a
 * handset, an `UnsatisfiedLinkError` on the JVM, and it has taken this project's CI down
 * once already. So a constant that lives on `RealPjsipCoreGateway`'s companion cannot be
 * read by a test without loading the whole native stack. Here, it can.
 *
 * `DeclaredFeatureSetTest` is what stops this becoming a second source of truth: it parses
 * `pjsip/config/pj/config_site.h` and fails if the two disagree. Without that test this file is
 * exactly the drift N-13 removes elsewhere, restated in Kotlin.
 */
object DeclaredFeatureSet {

    /**
     * Codecs this build is configured to contain, by name.
     *
     * Names are lowercase because that is how [DeclaredCodec] and
     * [com.whatsappv2.domain.codec.RegisteredCodec] both normalise: PJSIP spells codec ids
     * with a clock rate (`opus/48000/2`, `PCMU/8000/1`) and the case differs per codec, so
     * one normalisation in one place is what stops `LYRA` from matching `lyra/16000/1`
     * never — silently, which is the real bug this project already shipped once.
     */
    val declared: Set<DeclaredCodec> = setOf(
        DeclaredCodec("opus", DeclaredCodec.Kind.AUDIO),
        DeclaredCodec("g722", DeclaredCodec.Kind.AUDIO),
        DeclaredCodec("pcmu", DeclaredCodec.Kind.AUDIO),
        DeclaredCodec("pcma", DeclaredCodec.Kind.AUDIO),
        DeclaredCodec("lyra", DeclaredCodec.Kind.AUDIO),
        DeclaredCodec("vp8", DeclaredCodec.Kind.VIDEO),
    )

    /**
     * Codecs whose `config_site.h` flag is `1`, plus the ones pjmedia builds unconditionally.
     *
     * This is what separates *not compiled* — a **decision** — from *registration failed* —
     * a **build defect**. Without it every absence collapses into one indistinguishable
     * case, which is the state the project was in before the audit existed.
     *
     * `h264` is deliberately absent: `PJMEDIA_HAS_OPENH264_CODEC 0`. `lyra` is present since
     * ADR-008 closed at Exit A (2026-09-10): `PJMEDIA_HAS_LYRA_CODEC 1`, the closure is
     * vendored and built by `pjsip/lyra/CMakeLists.txt`, and it registers as
     * `lyra/16000/1`. G.711 and G.722 carry no flag — pjmedia always builds them — which is
     * why they are here without a corresponding `#define`.
     */
    val compiledIn: Set<String> = setOf("opus", "g722", "pcmu", "pcma", "lyra", "vp8")

    /**
     * Flags that must read `1` in `config_site.h` for [compiledIn] to be true.
     *
     * Only the codecs that HAVE a flag. The unconditional ones are absent by construction,
     * and `DeclaredFeatureSetTest` knows the difference rather than guessing it.
     */
    val requiredFlags: Map<String, String> = mapOf(
        "opus" to "PJMEDIA_HAS_OPUS_CODEC",
        "vp8" to "PJMEDIA_HAS_VPX_CODEC",
        "lyra" to "PJMEDIA_HAS_LYRA_CODEC",
    )

    /** Flags that must read `0`, so a codec silently arriving is caught too. */
    val forbiddenFlags: Map<String, String> = mapOf(
        "h264" to "PJMEDIA_HAS_OPENH264_CODEC",
    )

    /**
     * Codecs this build registers that the deployed server does not offer.
     *
     * **Measured, 2026-09-09.** `fs_cli -x "show codec"` reports PCMU, PCMA, G.729, G.723.1,
     * AMR, Speex, VP8 and VP9 — **no Opus and no G.722**. So every call negotiates narrowband
     * G.711 while the app lists a wideband codec first, and until the audit existed nothing
     * said so (`docs/reconciliation.md` A-1b).
     *
     * `lyra` joined the set on 2026-09-10, the day it was compiled in: no FreeSWITCH offers
     * it and none can — it is not an IETF codec and interoperates only with another
     * endpoint running the same PJSIP integration, which means another install of this
     * app, with the server passing media through untouched.
     *
     * A fact about a **deployment**, not about this build: installing `mod_opus` server-side
     * shrinks this set with no change here. It is a constant only because there is no
     * server-driven codec configuration yet (`docs/system-design.md` §5.1) — when there is,
     * this is the thing it feeds.
     */
    val unnegotiableOnThisDeployment: Set<String> = setOf("opus", "g722", "lyra")

    /**
     * Where [unnegotiableOnThisDeployment] came from, so the claim travels with its warrant.
     *
     * It is a **record**, not a measurement made by this app, and saying so is the whole
     * point: `AbsenceReason.ExpectedUnsupportedByServer` used to be called `NoPeerAccepts`,
     * which asserts something about every peer that nothing here has ever checked.
     */
    const val UNNEGOTIABLE_SOURCE: String =
        "recorded from `fs_cli -x \"show codec\"` on the reference server, 2026-09-09"
}
