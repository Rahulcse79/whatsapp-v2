package com.whatsappv2.domain.model

/** How DTMF digits are carried (§5.1, DoD 8). */
enum class DtmfMode {
    /**
     * RFC 4733 telephone-event, in the RTP stream. The default: it survives transcoding
     * and low-bitrate codecs, where in-band tones are mangled.
     */
    RFC_4733,

    /**
     * SIP INFO. A fallback for gateways that do not negotiate telephone-event; it puts
     * digits on the signalling path, which is slower and not universally supported.
     */
    SIP_INFO,
}

/** Where call audio should start, when the device offers a choice. */
enum class PreferredAudioRoute {
    /** Follow the system: headset if connected, otherwise earpiece. */
    AUTOMATIC,

    /** Always start on speakerphone. */
    SPEAKER,

    /** Always start on the earpiece, even with a headset connected. */
    EARPIECE,
}

/**
 * App-wide preferences (§5.1).
 *
 * **The default account is deliberately not here.** It lives in the accounts table, where
 * deleting an account and promoting its replacement happen in one transaction. Holding it
 * here as well would be a second source of truth that can point at an account that no
 * longer exists.
 */
data class AppSettings(
    /** DTMF transport, unless an account overrides it. */
    val dtmfMode: DtmfMode = DtmfMode.RFC_4733,

    /** The SRTP policy new accounts start with. Existing accounts keep their own. */
    val defaultSrtpPolicy: SrtpPolicy = SrtpPolicy.OPTIONAL,

    val preferredAudioRoute: PreferredAudioRoute = PreferredAudioRoute.AUTOMATIC,

    /**
     * Whether SIP signalling is written to the log.
     *
     * **Off on a fresh install, and unavailable in release builds** (§7, DoD 12). Even
     * when on, `Authorization` and `Proxy-Authenticate` headers are redacted before
     * anything is written - a trace containing a digest response is a credential in a log
     * file.
     */
    val sipTraceEnabled: Boolean = false,
) {
    companion object {
        /** What a fresh install starts with. */
        val DEFAULT: AppSettings = AppSettings()
    }
}
