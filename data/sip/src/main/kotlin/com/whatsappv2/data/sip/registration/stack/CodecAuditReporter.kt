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
internal fun Endpoint.auditCodecs(logger: Logger): CodecAudit {
    val auditor = CodecAuditor(
        declared = DeclaredFeatureSet.declared,
        knownUnnegotiable = DeclaredFeatureSet.unnegotiableOnThisDeployment,
    )

    val result = auditor.audit(
        registeredAudio = codecEnum2().map { it.codecId to it.priority.toInt() },
        registeredVideo = videoCodecEnum2().map { it.codecId to it.priority.toInt() },
        compiledIn = DeclaredFeatureSet.compiledIn,
    )

    // INFO once per start: the codec list the running library ACTUALLY registered, verbatim
    // rather than summarised. A summary is what hid the Opus gap for months.
    logger.info(
        TAG,
        "Codec audit: registered audio=${result.registeredAudio.map { it.codecId }} " +
            "video=${result.registeredVideo.map { it.codecId }}",
    )

    result.absent.forEach { (codec, reason) ->
        val line = "Codec ${codec.name} (${codec.kind}) is not usable: $reason"
        // A codec that was declared, compiled in, and did not register is a BUILD DEFECT,
        // and N-9 requires it be reported as one — at ERROR, once, with the codec id.
        // Everything else is a decision, or a fact about the deployment; reporting those at
        // ERROR is how people learn to ignore the log.
        if (reason == AbsenceReason.RegistrationFailed) {
            logger.error(TAG, "$line — the build declared it and the library did not register it")
        } else {
            logger.info(TAG, line)
        }
    }

    return result
}

private const val TAG = "PjsipCodecAudit"
