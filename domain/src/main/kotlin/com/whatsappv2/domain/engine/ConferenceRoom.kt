package com.whatsappv2.domain.engine

import com.whatsappv2.domain.model.DialledTarget
import com.whatsappv2.domain.model.SipUri

/**
 * The address of the conference bridge a video merge moves everybody into (ADR-003).
 *
 * ## Why this is a type and not a string constant
 *
 * The room is a property of the **deployment**, not of the app: it is an extension in a
 * FreeSWITCH dialplan, and a different server numbers it differently. Carrying it as an
 * injected value means the number appears once, a test can supply its own, and the day it
 * becomes a per-account setting the change is to what provides this rather than to every
 * place that merges a call.
 *
 * ## The default is verified, not assumed
 *
 * `3000` is the extension actually installed on the reference server: it resolves through
 * `dialplan/default/02_cisco_features.xml` to `conference 3000@whatsapp-video`, and that
 * profile is the one with `video-mode=mux` and the portrait layout group — checked live
 * with `conference list`, whose flags include `video_muxing`. A room on the `default`
 * profile would be worse than no room at all: mod_conference's default is passthrough, so
 * everybody would join, everybody would be heard, and everybody would see the same single
 * face. See `docs/Freeswitch_configuration_docs/conference-video/`.
 *
 * A `data class` rather than a `value class` deliberately: Hilt's KSP processor mangles
 * the name of a `@Provides` method returning an inline class — `provideConferenceRoom` came
 * out as `provideConferenceRoom-19javJc` and failed the build — and a wrapper this
 * short-lived gains nothing from being unboxed.
 *
 * @property extension the dialled number, or a full SIP URI. Blank means no bridge is
 *   configured, and a video merge then declines rather than silently dropping video.
 */
data class ConferenceRoom(val extension: String) {

    /** Whether a bridge is configured at all. */
    val isConfigured: Boolean get() = extension.isNotBlank()

    /**
     * The room's address on [domain], or null when none is configured or it will not parse.
     *
     * Resolved exactly as a dialled extension is, so `3000` and `sip:3000@host` both work
     * and neither needs a second spelling rule.
     */
    fun uriOn(domain: String): SipUri? =
        if (isConfigured) DialledTarget.resolve(extension, domain) else null

    companion object {
        /** The reference deployment's video bridge. See the class KDoc for the evidence. */
        val DEFAULT = ConferenceRoom("3000")

        /** No bridge: a video merge is declined rather than quietly downgraded. */
        val NONE = ConferenceRoom("")
    }
}
