package com.whatsappv2.domain.codec

import com.whatsappv2.domain.model.AudioCodec
import com.whatsappv2.domain.model.VideoCodec

/**
 * What the running native library actually registered, and why each declared codec did not.
 *
 * ## Why this exists
 *
 * **Compiled, registered and negotiated are three different claims** (master prompt §10),
 * and a build log can only speak to the first. N-9 is not satisfied by a build log: a codec
 * can be compiled in and fail to register, and one can register and never be accepted by any
 * peer. Both are invisible today, and both change what the user should be told.
 *
 * The audit runs **once per endpoint start** and produces this value. It is a value rather
 * than a log line so the UI can say *"Opus: built, no peer accepts it"* instead of showing a
 * preference that silently does nothing.
 *
 * ## The state this was written for is not hypothetical
 *
 * Recorded against the deployed server on 2026-09-09 (`fs_cli -x "show codec"`): it offers
 * PCMU, PCMA, G.729, G.723.1, AMR, Speex, VP8 and VP9 — no Opus, no G.722, no H.264. This
 * build compiles and registers Opus and lists it first in
 * [com.whatsappv2.domain.model.CodecPreferences.DEFAULT], so a call there negotiates
 * narrowband G.711 and nothing said so. That is
 * [AbsenceReason.ExpectedUnsupportedByServer], and it applies to the app's headline audio
 * codec — not only to Lyra.
 *
 * ## What this type must never do again
 *
 * Report a codec as absent **and hide it from the registry**. Doing both is what turned one
 * stale server record into a confident, wrong claim that this build does not contain Opus at
 * all (see [registeredAudio]).
 */
data class CodecAudit(
    /**
     * Audio codecs the library reported through `codecEnum2()`, in registry order, **verbatim**.
     *
     * Nothing is removed from this list. It used to have stranded codecs filtered out of it
     * before anything could read it, and the log line built from it therefore claimed to
     * print the registry while printing a subset. That cost a whole diagnosis: on 2026-09-10
     * the filtered line was read as evidence that this build does not register Opus or
     * G.722, and a real INVITE off the same handset carries `a=rtpmap:96 opus/48000/2` and
     * `a=rtpmap:9 G722/8000`. Both register. The audit was wrong, not the build.
     *
     * Strandedness is reported through [absent] — a codec can be in both, and that
     * combination is the whole point of [AbsenceReason.ExpectedUnsupportedByServer].
     */
    val registeredAudio: List<RegisteredCodec>,

    /** Video codecs the library reported through `videoCodecEnum2()`, verbatim. See above. */
    val registeredVideo: List<RegisteredCodec>,

    /**
     * Every codec in the declared feature set that the registry does not contain, with the
     * reason it is absent.
     */
    val absent: Map<DeclaredCodec, AbsenceReason>,
) {
    init {
        val registeredIds = (registeredAudio + registeredVideo).map { it.name }.toSet()
        // A codec may be registered AND reported with a reason — that is exactly what
        // ExpectedUnsupportedByServer means, and the old invariant forbade it. Enforcing
        // "registered or absent, never both" is what forced the registry lists to be
        // filtered before anyone could see them, and the filtering is what made the log
        // line lie. What must still hold is narrower: a codec whose absence is a *build*
        // fact cannot also be in the registry, because those two cannot both be true.
        val contradictions = absent
            .filterValues { it == AbsenceReason.NotCompiled || it == AbsenceReason.RegistrationFailed }
            .keys.map { it.name }.toSet() intersect registeredIds
        require(contradictions.isEmpty()) {
            "a codec is registered and reported as not built: $contradictions"
        }
    }

    /** True when every declared codec registered. The only state that needs no explaining. */
    val isComplete: Boolean get() = absent.isEmpty()

    /**
     * The codecs whose absence is a **build defect** rather than a decision.
     *
     * [AbsenceReason.RegistrationFailed] means the flag said 1 and the library did not
     * register it — the one case a build log cannot show, and the one N-9 requires be
     * reported at ERROR with the codec id.
     */
    val defects: Map<DeclaredCodec, AbsenceReason>
        get() = absent.filterValues { it == AbsenceReason.RegistrationFailed }

    /**
     * The codecs that work here and are not expected to negotiate against this server.
     *
     * Not a defect and not nothing: it is the difference between a preference that does
     * something and one that probably cannot, and it is what a user-facing screen can say
     * without overstating what has been measured.
     */
    val strandedByPeer: Set<DeclaredCodec>
        get() = absent.filterValues { it is AbsenceReason.ExpectedUnsupportedByServer }.keys
}

/** One codec the library reported, as `codecEnum2()` describes it. */
data class RegisteredCodec(
    /**
     * The codec-name segment, lowercased — `opus` from `opus/48000/2`.
     *
     * Normalised once, here, so the audit and [com.whatsappv2.domain.model.AudioCodec]'s
     * `payloadName` are compared on the same shape. PJSIP spells codec ids with a clock rate
     * and channel count; the domain stores the name alone.
     */
    val name: String,

    /** The full id PJSIP reported, kept verbatim for the log. `opus/48000/2`. */
    val codecId: String,

    /** `255` highest, `0` disabled. */
    val priority: Int,
) {
    companion object {
        /**
         * Parses one `codecEnum2()` entry.
         *
         * **Lowercasing is load-bearing and has already cost one silent failure.** The stack
         * matches preferences to codec ids by prefix, so an uppercase `LYRA` matches
         * `lyra/16000/1` **never** — and does so with no error anywhere. `Codecs.kt:38` spells
         * it lowercase for this reason and a test pins it; doing the same normalisation here
         * means the audit cannot disagree with the matcher about what "the same codec" is.
         */
        fun parse(codecId: String, priority: Int): RegisteredCodec = RegisteredCodec(
            name = codecId.substringBefore('/').lowercase(),
            codecId = codecId,
            priority = priority,
        )
    }
}

/**
 * A codec this build was **configured** to contain — the declared feature set (N-8).
 *
 * Deliberately not the same type as [AudioCodec]/[VideoCodec]. The declared set is a
 * property of `pjsip/config/pj/config_site.h`; the domain enums are what the app can *offer*.
 * They overlap and they are not the same list, and collapsing them would hide exactly the
 * mismatch this audit exists to report — H264 is in `CodecPreferences.DEFAULT` and not in
 * the declared set.
 */
data class DeclaredCodec(
    /** Lowercased codec name, matching [RegisteredCodec.name]. */
    val name: String,

    /** Audio or video — they come from different registries. */
    val kind: Kind,
) {
    enum class Kind { AUDIO, VIDEO }

    companion object {
        fun audio(codec: AudioCodec) = DeclaredCodec(codec.payloadName.lowercase(), Kind.AUDIO)
        fun video(codec: VideoCodec) = DeclaredCodec(codec.payloadName.lowercase(), Kind.VIDEO)
    }
}

/**
 * Why a declared codec is not in the registry.
 *
 * Four cases, and they are four because they have four different user-visible consequences
 * and four different owners. Collapsing them into "unavailable" is what makes the current
 * behaviour impossible to explain.
 */
sealed interface AbsenceReason {

    /**
     * The build was configured without it. A **decision**, not a defect.
     *
     * True today of `H264` (`PJMEDIA_HAS_OPENH264_CODEC 0`); no longer of `LYRA`, which
     * ADR-008 compiled in at Exit A on 2026-09-10. Reported at INFO. Owner: whoever decides the
     * feature set.
     */
    data object NotCompiled : AbsenceReason

    /**
     * The flag said 1 and the library did not register it. A **build defect**.
     *
     * The one case no build log can show, and the reason the audit exists rather than a
     * `grep` of `config.log`. Reported at ERROR, once, with the codec id (N-9).
     * Owner: whoever owns the native build.
     */
    data object RegistrationFailed : AbsenceReason

    /**
     * Registered, and its model files are missing or fail their manifest.
     *
     * Lyra only, and only on Exit A of the §2.4 gate. Worse than not having the codec,
     * because it advertises a capability it cannot deliver: the codec registers and then
     * fails when a stream opens. Owner: whoever ships the assets.
     */
    data class ModelFilesUnusable(val detail: String) : AbsenceReason

    /**
     * Registered and selectable here, and **recorded** as unsupported by the server this
     * deployment talks to.
     *
     * ## Why it is named for its evidence rather than for its effect
     *
     * It used to be `NoPeerAccepts`, which states a fact about every peer. Nothing measures
     * that. What actually exists is a hand-maintained list —
     * `DeclaredFeatureSet.unnegotiableOnThisDeployment` — compiled from one `fs_cli -x "show
     * codec"` against one server on one day. Reporting a recorded expectation as though it
     * were an observation is how the audit came to state, confidently and wrongly, that
     * codecs this build offers on the wire were unusable.
     *
     * The rename is the fix that costs nothing and buys the only thing that matters: whoever
     * reads it can tell what is known from what is assumed. Deriving it from real evidence —
     * a negotiated codec observed per server — is the better answer and needs a per-server
     * record this app does not keep yet (`docs/system-design.md` §5.1).
     *
     * [source] says where the expectation came from, so the claim travels with its warrant.
     * Owner: whoever operates the registrar.
     */
    data class ExpectedUnsupportedByServer(val source: String) : AbsenceReason
}
