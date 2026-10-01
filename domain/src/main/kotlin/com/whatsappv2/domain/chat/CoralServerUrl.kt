package com.whatsappv2.domain.chat

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.success

/**
 * Why a typed server URL was refused.
 *
 * Typed rather than a message, for the same reason [com.whatsappv2.domain.repository.AccountRepositoryError]
 * is: the sign-in screen puts the error on the URL field and the wording belongs to the UI,
 * not to the parser. Two of these carry a [suggestion] because the correction is
 * unambiguous and refusing without offering it wastes the user's time.
 */
sealed interface ChatUrlViolation {

    /** The correction to offer, when there is exactly one sensible one. */
    val suggestion: String? get() = null

    /** Nothing typed. */
    data object Blank : ChatUrlViolation

    /**
     * No scheme — `192.168.250.201` rather than `https://192.168.250.201`.
     *
     * **No longer raised either.** Typing a bare host is the most common thing a person
     * does and refusing it taught them nothing; [CoralServerUrl.parse] now assumes
     * `https://`, which is the safe half of the guess and the one a user is least likely
     * to be harmed by. [suggestion] carries what was assumed, so a caller can show it.
     */
    data class NotAbsolute(override val suggestion: String) : ChatUrlViolation

    /**
     * `http://`, on a build that refuses cleartext.
     *
     * **No longer raised by [CoralServerUrl.parse].** `http://` is a legitimate origin —
     * `http://192.168.250.201` is a real deployment, and it answers the same bytes as its
     * `https://` form — so the parser accepts it and the scheme the user typed is the
     * scheme that gets used.
     *
     * The case is kept because the *runtime* constraint has not gone away: this app sets
     * `usesCleartextTraffic=false` with no debug override, so an `http://` origin fails
     * with a network error that says nothing about why. Whoever permits cleartext is also
     * who should stop raising this; whoever does not needs it to explain the failure.
     */
    data class Cleartext(override val suggestion: String) : ChatUrlViolation

    /** Neither http nor https — `ftp://`, `ws://`, a Windows drive letter. */
    data class UnsupportedScheme(val scheme: String) : ChatUrlViolation

    /** A scheme, but no host after it. */
    data object Malformed : ChatUrlViolation
}

/**
 * Where this deployment lives: one **origin**, from which every URL is derived.
 *
 * ## Why the origin and not the individual URLs
 *
 * Because there are three of them and they sit on two different backends — the Coral UC
 * platform under `/services/` and chat-node under `/chat/` — and a person asked to type
 * three URLs will eventually type two that disagree. Deriving them means that cannot
 * happen, and it means the user types a host rather than a path they have to get exactly
 * right.
 *
 * ## The scheme is the user's, and everything follows it
 *
 * Both `http://` and `https://` are accepted, and whichever is typed is the one every
 * derived URL carries — `http://` gives `ws://`, `https://` gives `wss://`. That is not a
 * relaxation for its own sake: `http://192.168.250.201` is a real deployment of this
 * platform, and a parser that refused it would refuse the server the app is pointed at.
 *
 * A **bare host** is accepted too and assumed to be `https://`. `192.168.250.201` is the
 * likeliest thing a person types; refusing it taught them nothing, and https is the safe
 * half of the guess.
 *
 * What the parser cannot decide is whether the *platform* will carry a cleartext request:
 * this app sets `usesCleartextTraffic=false`. [isSecure] is how a caller asks, so the
 * decision is made where it can be acted on rather than hidden in a violation here.
 */
@JvmInline
value class CoralServerUrl private constructor(val origin: String) {

    /**
     * Retrofit base for the Coral UC platform. The trailing slash is required — Retrofit
     * refuses a base URL without one.
     */
    val servicesBaseUrl: String get() = "$origin/services/"

    /** Retrofit base for chat-node, as `ChatConfig.restBaseUrl`. */
    val chatRestBaseUrl: String get() = "$origin/chat/"

    /**
     * chat-node's socket, as `ChatConfig.wsUrl`.
     *
     * Derived from [origin]'s own scheme, so it cannot disagree with it: `https` gives
     * `wss`, `http` gives `ws`. Keeping a second stored string in step by hand is what
     * this class exists to avoid.
     */
    val chatWsUrl: String get() =
        if (isSecure) origin.replaceFirst(HTTPS, WSS) + WS_PATH else origin.replaceFirst(HTTP, WS) + WS_PATH

    /**
     * The host, with no scheme and no port — `gujlogin.coraltele.com`.
     *
     * What SIP wants. A SIP domain is a bare host: a registrar configured as
     * `https://host` produces a URI of `sip:user@https://host`, which fails to parse at
     * the point of registration rather than here.
     */
    val host: String
        get() = origin.substringAfter(SCHEME_SEPARATOR).substringBefore(':')

    /**
     * True when the origin is `https://`.
     *
     * Asked by the layer that builds the HTTP client, because an `http://` origin only
     * works on a build whose network security policy permits cleartext — and that is a
     * platform question this value class has no way to answer.
     */
    val isSecure: Boolean get() = origin.startsWith(HTTPS)

    override fun toString(): String = origin

    companion object {

        /** The deployment this app ships pointed at. Editable on the sign-in screen. */
        val DEFAULT: CoralServerUrl = CoralServerUrl("https://gujlogin.coraltele.com")

        private const val HTTPS_SCHEME = "https"
        private const val HTTP_SCHEME = "http"
        private const val HTTPS = "https://"
        private const val HTTP = "http://"
        private const val WSS = "wss://"
        private const val WS = "ws://"
        private const val WS_PATH = "/chat/ws"
        private const val SCHEME_SEPARATOR = "://"

        /** `host`, `host:8443`, `sub.host` — deliberately not a full RFC 3986 host grammar. */
        private val AUTHORITY = Regex("""^[A-Za-z0-9.\-]+(?::\d{1,5})?$""")

        /**
         * Parses what the user typed into an origin, discarding anything after it.
         *
         * **A pasted path is dropped, not refused.** Somebody copying
         * `https://host/services/app/v2/auth/login` out of an API document has given us
         * the right host; treating that as an error would be pedantry, and appending
         * `/services/` to it would produce a URL that fails much later and much less
         * legibly.
         *
         * **A bare host is assumed to be `https://`.** Three things are accepted —
         * `host`, `http://host`, `https://host` — and only the last two say anything
         * about the scheme, so the first has to be guessed. https is the half of that
         * guess whose failure is loud.
         */
        fun parse(raw: String): Outcome<CoralServerUrl, ChatUrlViolation> {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return failure(ChatUrlViolation.Blank)

            val separator = trimmed.indexOf(SCHEME_SEPARATOR)
            val scheme = if (separator < 0) HTTPS_SCHEME else trimmed.substring(0, separator).lowercase()
            val rest = if (separator < 0) trimmed else trimmed.substring(separator + SCHEME_SEPARATOR.length)
            val authority = rest.substringBefore('/').substringBefore('?').lowercase()

            return when {
                authority.isEmpty() || !AUTHORITY.matches(authority) -> failure(ChatUrlViolation.Malformed)
                scheme == HTTPS_SCHEME -> success(CoralServerUrl(HTTPS + authority))
                scheme == HTTP_SCHEME -> success(CoralServerUrl(HTTP + authority))
                else -> failure(ChatUrlViolation.UnsupportedScheme(scheme))
            }
        }
    }
}
