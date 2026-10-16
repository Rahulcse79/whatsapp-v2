package com.whatsappv2.domain.model

import com.whatsappv2.domain.video.VideoFrameRate

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
 * Light or dark, or whatever the phone is doing.
 *
 * Three values and not a boolean, because "follow the system" is the state most people
 * never leave and a boolean has no room for it — it would have to be read as "dark" or
 * "light" the moment it was persisted, and the app would stop tracking the phone's
 * schedule without anyone having asked it to.
 */
enum class ThemeMode {
    /** Dark when the phone is dark, light when it is light. Not the default — see [AppSettings]. */
    SYSTEM,

    /** Light, whatever the phone is doing. */
    LIGHT,

    /** Dark, whatever the phone is doing. */
    DARK,
    ;

    /**
     * Whether the app draws dark, given whether the phone currently is.
     *
     * The one place the three-way choice becomes a yes or no. Every theme call site
     * asks this rather than switching on the enum, so a fourth mode is one branch here.
     */
    fun resolvesToDark(systemIsDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemIsDark
        LIGHT -> false
        DARK -> true
    }
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

    /**
     * The SRTP policy new accounts start with. Existing accounts keep their own.
     *
     * [SrtpPolicy.DISABLED] since 2026-09-10: the previous default, `OPTIONAL`, failed
     * every outgoing call on every FreeSWITCH tested — see the enum for what it sends
     * and why the server refuses it. A default that cannot place a call is not a default.
     * [SrtpPolicy.MANDATORY] is the choice for a server that has SRTP; it sends
     * `RTP/SAVP` and fails closed.
     */
    val defaultSrtpPolicy: SrtpPolicy = SrtpPolicy.DISABLED,

    val preferredAudioRoute: PreferredAudioRoute = PreferredAudioRoute.AUTOMATIC,

    /**
     * Light, dark, or follow the phone.
     *
     * **Light** on a fresh install. It followed the phone, on the reasoning that this is
     * what the app did before there was a choice and so nobody's appearance changes
     * because of an update — a good argument about the *update* and the wrong default for
     * the app. This is a softphone whose screens are a green bar over pale surfaces, and
     * the light palette is the one they were drawn and checked in; a handset in dark mode
     * got the dark palette on first launch without anybody having asked for it.
     *
     * Only the default moves. `SYSTEM` is still in the enum, still offered in Settings and
     * still the right answer for anyone who wants it — and a choice already stored is
     * untouched, because this is `AppSettings.DEFAULT` and DataStore only falls back to it
     * for a key that was never written.
     */
    val themeMode: ThemeMode = ThemeMode.LIGHT,

    /**
     * Whether SIP signalling is written to the log.
     *
     * **Off on a fresh install, and unavailable in release builds** (§7, DoD 12). Even
     * when on, `Authorization` and `Proxy-Authenticate` headers are redacted before
     * anything is written - a trace containing a digest response is a credential in a log
     * file.
     */
    val sipTraceEnabled: Boolean = false,

    /**
     * Whether a SIP TLS server's certificate is checked before the connection is used.
     *
     * **Off on a fresh install, and that is a deliberate, informed choice rather than an
     * oversight.** With it off, TLS still negotiates and still encrypts — what is given up
     * is *authentication*: the app no longer confirms that the certificate is issued by
     * somebody it trusts, or that it was issued for the host being dialled. An attacker
     * able to sit on the path can therefore present any certificate at all and read or
     * alter the signalling. On an untrusted network that is the whole of the protection.
     *
     * The reason it defaults off is that the deployments this app is pointed at do not
     * have certificates that can pass a check, and a client that refuses to register is
     * useless to their operators:
     *
     *  - One registrar presents a **wildcard** certificate (`*.example.net`). PJSIP
     *    compares names for exact equality and refuses wildcards outright for SIP, citing
     *    RFC 5922 §7.2 — so a certificate most of the internet accepts can never match.
     *  - A lab server presents a **self-signed certificate with no SubjectAltName at all**
     *    (`CN=FreeSWITCH`) while being addressed by IP. Nothing in it names the host, so
     *    no amount of trust configuration makes the identity match.
     *
     * Both are fixed by issuing the server a certificate that names the host it is reached
     * by. Until then this switch is what lets an operator run TLS at all, and turning it
     * **on** is what makes that TLS worth something — which is why it is offered rather
     * than decided here.
     *
     * Enforcement still fails closed when it is on: if no CA bundle can be produced,
     * `PjsipTrustStore` returns nothing and the handshake fails rather than silently
     * skipping the check.
     */
    val verifyTlsCertificates: Boolean = false,

    /**
     * How long the call log is kept before old entries are removed.
     *
     * One setting for the whole log, and deliberately not one per kind: a call is a call,
     * and somebody who wants three weeks of history wants three weeks of it whether the
     * camera was on or not. The pruning is by time alone, so audio and video are covered
     * by the same rule rather than by two that could drift.
     */
    val callHistoryRetention: CallHistoryRetention = CallHistoryRetention.DEFAULT,
    /**
     * The frame rate outgoing video is asked to run at.
     *
     * 15 fps on a fresh install, which is where the quality ladders were pinned before this
     * was a choice — it divides the camera's 30 evenly and is what a four-party mesh on
     * these handsets sustains on three legs at once. A higher rate is genuinely smoother on
     * a link that can carry it; 20 and 25 do not divide 30 and arrive as judder even at zero
     * loss. See [VideoFrameRate], which carries that distinction so Settings can show it.
     *
     * It sets what the ladder's rungs mean, not what a call is guaranteed to get:
     * `AdaptiveVideoPolicy` still steps down under measured pressure.
     */
    val videoFrameRate: VideoFrameRate = VideoFrameRate.DEFAULT,

    /**
     * Whether a trained voice profile is used to keep other speakers off the call.
     *
     * **Off by default, until the gate stops cutting the person it is supposed to keep.**
     * This defaulted to on, on the reasoning that the gate needs an enrolled voice and so
     * costs an untrained user nothing. That part is still true and is not the problem. The
     * problem is what it does to a user who *has* trained, measured on two people and two
     * handsets on 2026-10-06, 60-second turns through the real capture chain:
     *
     * ```
     *   speaker vs their own profile   p50 +0.322 and +0.319   threshold 0.35
     *   speaker vs the other profile   p50 +0.185 and +0.213
     *   nobody speaking                p50 +0.005 and +0.091
     * ```
     *
     * Other voices and room noise are held — 0 of 452 impostor windows and 0 of 513 ambient
     * windows crossed the threshold. But the enrolled speaker's own median sits *below* it,
     * so the gate cuts them roughly half the time, in closures of up to 4.2 seconds while
     * they are still talking. No threshold fixes it: at no setting does the user get through
     * 95% of the time with under 10% leak, because the two distributions overlap that far.
     *
     * The cause is not the threshold but the chain. Scoring reads the capture bridge, which
     * is downstream of RNNoise (`EC_OPTIONS = ECHO_USE_SPEECH_ENHANCER`); `VoiceEnroller`
     * records through `AudioRecord`, which never sees it. So the profile describes audio
     * that no live window will ever look like. Until enrolment and scoring run through the
     * same processing, this must not be on for somebody who has not asked for it.
     *
     * Everything to switch it on is still here and still works, because that is how the
     * fix gets measured. See `VOICE-GATE-FINDINGS-2026-10-06.md`.
     *
     * Independent of the user's own mute. Both can silence the microphone and neither can
     * undo the other: see `RealPjsipCoreGateway.applyCaptureRouting`, which owns that one
     * connection on behalf of both.
     */
    val liveCallFilteringEnabled: Boolean = false,

    /**
     * Whether the name on screen follows a transfer that happened somewhere else.
     *
     * **Off on a fresh install**, because turning it on changes what this endpoint puts on
     * the wire and that is not a change to make on anybody's behalf.
     *
     * A transferred call keeps its dialog. 4023 calls 4022, 4022 transfers it to 4021, and
     * nothing in the SIP that reaches the remaining party says the person on the other end
     * changed — so the screen keeps naming whoever was there when the call started.
     * FreeSWITCH will say so, in an in-dialog `INFO`, but only to an endpoint that first
     * advertised `X-FS-Support: update_display,send_info` on its INVITE, its answer or its
     * REFER.
     *
     * So this is one switch over both halves:
     *
     *  - **Off** — nothing is advertised, no update is ever sent, and one that arrived
     *    anyway is ignored. Exactly the behaviour of every build before this existed.
     *  - **On** — the advertisement goes out, the update comes back, it is answered `200
     *    OK`, and the call's name and address both move to the new party.
     *
     * Server-specific by nature, which is the other reason it is a choice rather than a
     * default: the headers are FreeSWITCH's own, and a PBX that does not send them leaves
     * the switch doing nothing at all. See `ConnectedPartyUpdate` for the exchange.
     */
    val updateCallerIdOnTransfer: Boolean = false,
) {
    companion object {
        /** What a fresh install starts with. */
        val DEFAULT: AppSettings = AppSettings()
    }
}
