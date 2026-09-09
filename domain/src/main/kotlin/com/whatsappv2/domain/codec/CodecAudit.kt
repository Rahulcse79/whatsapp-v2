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
 * Measured against the deployed FreeSWITCH on 2026-09-09 (`fs_cli -x "show codec"`): it
 * offers PCMU, PCMA, G.729, G.723.1, AMR, Speex, VP8 and VP9 — **no Opus, no G.722, no
 * H.264**. This build compiles and registers Opus and lists it first in
 * [com.whatsappv2.domain.model.CodecPreferences.DEFAULT], so every call negotiates
 * narrowband G.711 and nothing says so. That is [AbsenceReason.NoPeerAccepts], and it
 * applies to the app's headline audio codec today — not only to Lyra.
 */
data class CodecAudit(
    /** Audio codecs the library reported through `codecEnum2()`, in registry order. */
    val registeredAudio: List<RegisteredCodec>,

    /** Video codecs the library reported through `videoCodecEnum2()`. */
    val registeredVideo: List<RegisteredCodec>,

    /**
     * Every codec in the declared feature set that the registry does not contain, with the
     * reason it is absent.
     */
    val absent: Map<DeclaredCodec, AbsenceReason>,
) {
    init {
        val registeredIds = (registeredAudio + registeredVideo).map { it.name }.toSet()
        val absentNames = absent.keys.map { it.name }.toSet()
        // The invariant, asserted rather than assumed: a codec cannot be both registered and
        // absent. A codec in neither set is a bug in the audit, not in the build, and this is
        // where that shows up — at construction, not three screens later.
        require((registeredIds intersect absentNames).isEmpty()) {
            "a codec is both registered and absent: ${registeredIds intersect absentNames}"
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
     * The codecs that work and have nobody to talk to.
     *
     * Not a defect and not nothing: it is the difference between a preference that does
     * something and one that cannot, and it is the only reason a user-facing screen can
     * honestly explain.
     */
    val strandedByPeer: Set<DeclaredCodec>
        get() = absent.filterValues { it == AbsenceReason.NoPeerAccepts }.keys
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
 * property of `pjsip/config/config_site.h`; the domain enums are what the app can *offer*.
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
     * True today of `H264` (`PJMEDIA_HAS_OPENH264_CODEC 0`) and `LYRA`
     * (`PJMEDIA_HAS_LYRA_CODEC 0`, ADR-008). Reported at INFO. Owner: whoever decides the
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
     * Registered, selectable, and no peer has ever accepted it.
     *
     * **The honest state of Opus and G.722 against the deployed server today.** Not a defect
     * in this app at all — the fix is one missing module on the server — but it is the reason
     * a wideband preference does nothing, and without this case nothing can say so.
     * Owner: whoever operates the registrar.
     */
    data object NoPeerAccepts : AbsenceReason
}
