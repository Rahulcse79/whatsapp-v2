package com.whatsappv2.domain.model

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.success

/**
 * A validated `host[:port]`, used for STUN and TURN servers and for a registrar
 * override — endpoints that are not SIP URIs but still must be a real host.
 *
 * As with [SipUri], the constructor is private so an invalid instance cannot exist.
 */
class HostPort private constructor(
    val host: SipHost,
    val port: Int?,
) {
    /** The value as it should be written back out, brackets included for IPv6. */
    fun render(): String = host.rendered + (port?.let { ":$it" } ?: "")

    override fun toString(): String = render()

    override fun equals(other: Any?): Boolean =
        this === other || (other is HostPort && host == other.host && port == other.port)

    override fun hashCode(): Int = HASH_FACTOR * host.hashCode() + (port ?: 0)

    companion object {
        private const val HASH_FACTOR = 31
        private const val MIN_PORT = 1
        private const val MAX_PORT = 65_535

        /**
         * Parses `host`, `host:port`, `[v6]` or `[v6]:port`. A scheme prefix such as
         * `stun:` is accepted and dropped, because that is how users paste these
         * values, and silently mis-parsing `stun:` as `host=stun` would be worse.
         */
        fun parse(input: String): Outcome<HostPort, HostPortError> {
            val trimmed = input.trim().removePrefix("stun:").removePrefix("turn:").removePrefix("turns:")
            if (trimmed.isEmpty()) return failure(HostPortError.Empty)

            return if (trimmed.startsWith('[')) parseBracketed(trimmed) else parsePlain(trimmed)
        }

        private fun parseBracketed(input: String): Outcome<HostPort, HostPortError> {
            val close = input.indexOf(']')
            if (close < 0) return failure(HostPortError.InvalidHost(input))

            val address = input.substring(1, close)
            if (!HostSyntax.isValidIpV6(address)) return failure(HostPortError.InvalidHost(address))

            return portFrom(input.substring(close + 1)).map { HostPort(SipHost.IpV6(address), it) }
        }

        private fun parsePlain(input: String): Outcome<HostPort, HostPortError> {
            val colon = input.lastIndexOf(':')
            val hostText = if (colon < 0) input else input.substring(0, colon)
            val portText = if (colon < 0) "" else input.substring(colon)

            val host = HostSyntax.classify(hostText)
                ?: return failure(HostPortError.InvalidHost(hostText))

            return portFrom(portText).map { HostPort(host, it) }
        }

        private fun portFrom(rest: String): Outcome<Int?, HostPortError> {
            if (rest.isEmpty()) return success(null)
            if (!rest.startsWith(':')) return failure(HostPortError.InvalidPort(rest))

            val text = rest.substring(1)
            val port = text.toIntOrNull()
            return if (port == null || port !in MIN_PORT..MAX_PORT) {
                failure(HostPortError.InvalidPort(text))
            } else {
                success(port)
            }
        }

        private inline fun <A, B> Outcome<A, HostPortError>.map(
            transform: (A) -> B,
        ): Outcome<B, HostPortError> = when (this) {
            is Outcome.Success -> Outcome.Success(transform(value))
            is Outcome.Failure -> this
        }
    }
}

/** Why a `host[:port]` string is unusable. */
sealed interface HostPortError {
    data object Empty : HostPortError
    data class InvalidHost(val host: String) : HostPortError
    data class InvalidPort(val port: String) : HostPortError
}
