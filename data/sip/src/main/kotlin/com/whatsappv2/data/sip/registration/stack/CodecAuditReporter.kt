package com.whatsappv2.data.sip.registration.stack

import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.data.sip.codec.DeclaredFeatureSet
import com.whatsappv2.domain.codec.AbsenceReason
import com.whatsappv2.domain.codec.CodecAudit
import com.whatsappv2.domain.codec.CodecAuditor
import org.pjsip.pjsua2.Endpoint

/**
 * Reads the codec registry off a running endpoint and reports what it found (N-9, §2.5).
 *
 * ## Why this is not a method on the gateway
 *
 * It reads one thing from `Endpoint` and hands the rest to a pure function in `:domain`, so
 * it has no business holding gateway state — and `RealPjsipCoreGateway` is already at
 * detekt's `LargeClass` threshold, which is the tool saying the same thing less politely.
 *
 * ## Why it reads the SAME registry the priority pass reads
 *
 * `applyPriorities` iterates the codecs PJSIP registered and asks which preference matches,
 * which is why an absent H264 is skipped rather than raised. Reading `codecEnum2()` here,
 * in the same pass, is what stops the audit and the priority application from disagreeing
 * about what the library contains — that skip becomes reportable evidence instead of a
 * silent no-op (§2.5 step 4).
 *
 * O(n) over ≤ ~30 codecs, once per endpoint start. Never in the call path.
 */
/**
 * @param lyraModelProblem why Lyra's model files are unusable, or null; see
 *   `RealPjsipCoreGateway.tuneLyra`. Reported against the codec as
 *   [com.whatsappv2.domain.codec.AbsenceReason.ModelFilesUnusable].
 */
internal fun Endpoint.auditCodecs(logger: Logger, lyraModelProblem: String? = null): CodecAudit {
    val auditor = CodecAuditor(
        declared = DeclaredFeatureSet.declared,
        knownUnnegotiable = DeclaredFeatureSet.unnegotiableOnThisDeployment,
        knownUnnegotiableSource = DeclaredFeatureSet.UNNEGOTIABLE_SOURCE,
    )

    val result = auditor.audit(
        registeredAudio = codecEnum2().map { it.codecId to it.priority.toInt() },
        registeredVideo = videoCodecEnum2().map { it.codecId to it.priority.toInt() },
        compiledIn = DeclaredFeatureSet.compiledIn,
        modelFilesUnusable = lyraModelProblem?.let { mapOf("lyra" to it) }.orEmpty(),
    )

    // INFO once per start: the codec list the running library ACTUALLY registered, verbatim
    // and now genuinely so. This line used to be built from lists the auditor had already
    // stripped stranded codecs out of, so it under-reported the registry while claiming to
    // be it — and on 2026-09-10 that reading was taken as proof the build contains no Opus
    // and no G.722, while an INVITE off the same handset carried both.
    //
    // The priority is printed with each id because it is the other half of the answer: a
    // codec at priority 0 is registered and will never be offered, and without the number
    // those two states look identical in a log.
    logger.info(
        TAG,
        "Codec audit: registered audio=${result.registeredAudio.map { "${it.codecId}@${it.priority}" }} " +
            "video=${result.registeredVideo.map { "${it.codecId}@${it.priority}" }}",
    )

    // The one thing the registry list cannot say on its own, and the state that broke calling
    // on 2026-09-10: everything registered, nothing offerable. `create_audio_sdp` stops at
    // the first disabled codec, so an endpoint in this state builds media lines with no
    // formats in them — outgoing offers go out as `m=audio 0 RTP/AVP 0` and answering an
    // inbound call produces PJMEDIA_SDPNEG_ENOMEDIA and a 488 this app sends itself.
    if (result.registeredAudio.isNotEmpty() && result.registeredAudio.none { it.priority > 0 }) {
        logger.error(
            TAG,
            "Every registered audio codec is at priority 0. No call can negotiate audio in " +
                "this state; check the account's codec preferences.",
        )
    }

    result.absent.forEach { (codec, reason) ->
        val line = "Codec ${codec.name} (${codec.kind}) is not usable: $reason"
        // A codec that was declared, compiled in, and did not register is a BUILD DEFECT,
        // and N-9 requires it be reported as one — at ERROR, once, with the codec id.
        // Everything else is a decision, or a fact about the deployment; reporting those at
        // ERROR is how people learn to ignore the log.
        when (reason) {
            AbsenceReason.RegistrationFailed ->
                logger.error(TAG, "$line — the build declared it and the library did not register it")
            // Registered without its weights: every offer advertises a codec that fails
            // when the stream opens. As loud as a missing registration, for that reason.
            is AbsenceReason.ModelFilesUnusable ->
                logger.error(TAG, "$line — the codec is in every offer and cannot open a stream")
            else -> logger.info(TAG, line)
        }
    }

    return result
}

private const val TAG = "PjsipCodecAudit"
