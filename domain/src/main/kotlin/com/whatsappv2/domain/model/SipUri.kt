package com.whatsappv2.domain.model

import com.whatsappv2.core.common.logging.redact
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.flatMap
import com.whatsappv2.core.common.result.map
import com.whatsappv2.core.common.result.success

/**
 * A validated SIP URI — the RFC 3261 subset this client actually uses:
 * `scheme:user@host:port;param=value`.
 *
 * The constructor is private and [parse] is the only way in, so an invalid instance
 * cannot exist anywhere in the codebase. Validation therefore happens once, at the
 * edge, instead of being re-checked defensively at every call site.
 *
 * Not modelled, because nothing here needs it: headers (`?h=v`), passwords in the
 * user part, and `tel:` URIs. Adding them is a change to [parse] alone.
 */
class SipUri private constructor(
    val scheme: SipScheme,
    val user: String?,
    val host: SipHost,
    val port: Int?,
    val parameters: Map<String, String>,
) {

    /** The `;transport=` parameter, when present and recognised. */
    val transport: Transport? get() = parameters[PARAM_TRANSPORT]?.let(Transport::fromToken)

    /**
     * The port to actually connect to: the explicit port, else the transport's default,
     * else the scheme's default. Callers must not re-derive this.
     */
    val effectivePort: Int get() = port ?: transport?.defaultPort ?: scheme.defaultPort

    /** The URI as it goes on the wire. */
    fun render(): String = buildString {
        append(scheme.token).append(':')
        user?.let { append(it).append('@') }
        append(host.rendered)
        port?.let { append(':').append(it) }
        parameters.forEach { (name, value) ->
            append(';').append(name)
            if (value.isNotEmpty()) append('=').append(value)
        }
    }

    /**
     * Redacted by design: the user part is a phone number or extension, which must not
     * reach a release log (§7, DoD 12). Use [render] for the real value — an explicit
     * call is much harder to do by accident than a string template.
     */
    override fun toString(): String =
        "SipUri(${scheme.token}:${redact(user)}@${host.rendered}:$effectivePort)"

    override fun equals(other: Any?): Boolean = this === other ||
        (
            other is SipUri &&
                scheme == other.scheme &&
                user == other.user &&
                host == other.host &&
                port == other.port &&
                parameters == other.parameters
            )

    override fun hashCode(): Int {
        var result = scheme.hashCode()
        result = HASH_FACTOR * result + (user?.hashCode() ?: 0)
        result = HASH_FACTOR * result + host.hashCode()
        result = HASH_FACTOR * result + (port ?: 0)
        result = HASH_FACTOR * result + parameters.hashCode()
        return result
    }

    companion object {
        private const val HASH_FACTOR = 31
        private const val PARAM_TRANSPORT = "transport"

        private const val MIN_PORT = 1
        private const val MAX_PORT = 65_535

        /** RFC 3261 user characters, minus `;` which would collide with parameters. */
        private const val USER_EXTRA_CHARS = "-_.!~*'()&=+$,?/%"

        /**
         * Parses [input] into a [SipUri].
         *
         * A bare `alice@example.com` is rejected: guessing a scheme is how a `sips`
         * account silently becomes an unencrypted `sip` one. Callers that want a
         * default should add it explicitly before calling.
         */
        fun parse(input: String): Outcome<SipUri, SipUriError> {
            val trimmed = input.trim()
            return if (trimmed.isEmpty()) failure(SipUriError.Empty) else parseScheme(trimmed)
        }

        private fun parseScheme(trimmed: String): Outcome<SipUri, SipUriError> {
            val separator = trimmed.indexOf(':')
            if (separator <= 0) return failure(SipUriError.MissingScheme)

            val token = trimmed.substring(0, separator)
            val scheme = SipScheme.fromToken(token)
                ?: return failure(SipUriError.UnsupportedScheme(token))

            return parseAfterScheme(scheme, trimmed.substring(separator + 1))
        }

        private fun parseAfterScheme(scheme: SipScheme, remainder: String): Outcome<SipUri, SipUriError> {
            val paramSeparator = remainder.indexOf(';')
            val authority = if (paramSeparator < 0) remainder else remainder.substring(0, paramSeparator)
            // null means "no ';' at all", which is different from a ';' followed by
            // nothing - the latter is malformed and must not be silently accepted.
            val paramPart = if (paramSeparator < 0) null else remainder.substring(paramSeparator + 1)

            // Split on the LAST '@': RFC 3261 allows '@' inside the user part.
            val atIndex = authority.lastIndexOf('@')
            val user = if (atIndex < 0) null else authority.substring(0, atIndex)
            val hostPort = if (atIndex < 0) authority else authority.substring(atIndex + 1)

            if (user != null && !isValidUser(user)) return failure(SipUriError.InvalidUser(user))

            return parseHostPort(hostPort).flatMap { (host, port) ->
                parseParameters(paramPart).map { parameters ->
                    SipUri(scheme, user, host, port, parameters)
                }
            }
        }

        private fun parseHostPort(hostPort: String): Outcome<Pair<SipHost, Int?>, SipUriError> {
            if (hostPort.isEmpty()) return failure(SipUriError.MissingHost)

            return if (hostPort.startsWith('[')) {
                parseBracketedIpV6(hostPort)
            } else {
                parseNameOrIpV4(hostPort)
            }
        }

        private fun parseBracketedIpV6(hostPort: String): Outcome<Pair<SipHost, Int?>, SipUriError> {
            val close = hostPort.indexOf(']')
            if (close < 0) return failure(SipUriError.InvalidHost(hostPort))

            val address = hostPort.substring(1, close)
            if (!isValidIpV6(address)) return failure(SipUriError.InvalidHost(address))

            val rest = hostPort.substring(close + 1)
            return portFrom(rest).map { SipHost.IpV6(address) as SipHost to it }
        }

        private fun parseNameOrIpV4(hostPort: String): Outcome<Pair<SipHost, Int?>, SipUriError> {
            val colon = hostPort.lastIndexOf(':')
            val hostText = if (colon < 0) hostPort else hostPort.substring(0, colon)
            val portText = if (colon < 0) "" else hostPort.substring(colon)

            if (hostText.isEmpty()) return failure(SipUriError.MissingHost)

            val host: SipHost = when {
                isValidIpV4(hostText) -> SipHost.IpV4(hostText)
                isValidHostname(hostText) -> SipHost.Hostname(hostText)
                else -> return failure(SipUriError.InvalidHost(hostText))
            }
            return portFrom(portText).map { host to it }
        }

        /** [rest] is either empty or `:port`. Anything else is malformed. */
        private fun portFrom(rest: String): Outcome<Int?, SipUriError> {
            if (rest.isEmpty()) return success(null)
            if (!rest.startsWith(':')) return failure(SipUriError.InvalidPort(rest))

            val text = rest.substring(1)
            val port = text.toIntOrNull()
            return if (port == null || port !in MIN_PORT..MAX_PORT) {
                failure(SipUriError.InvalidPort(text))
            } else {
                success(port)
            }
        }

        private fun parseParameters(paramPart: String?): Outcome<Map<String, String>, SipUriError> {
            if (paramPart == null) return success(emptyMap())

            val parameters = LinkedHashMap<String, String>()
            for (token in paramPart.split(';')) {
                if (token.isEmpty()) return failure(SipUriError.InvalidParameter(token))

                val eq = token.indexOf('=')
                val name = if (eq < 0) token else token.substring(0, eq)
                val value = if (eq < 0) "" else token.substring(eq + 1)
                if (name.isEmpty()) return failure(SipUriError.InvalidParameter(token))

                parameters[name.lowercase()] = value
            }
            return success(parameters)
        }

        private fun isValidUser(user: String): Boolean =
            user.isNotEmpty() && user.all { it.isLetterOrDigit() || it in USER_EXTRA_CHARS }

        private fun isValidHostname(host: String): Boolean = HostSyntax.isValidHostname(host)

        private fun isValidIpV4(host: String): Boolean = HostSyntax.isValidIpV4(host)

        private fun isValidIpV6(address: String): Boolean = HostSyntax.isValidIpV6(address)
    }
}
