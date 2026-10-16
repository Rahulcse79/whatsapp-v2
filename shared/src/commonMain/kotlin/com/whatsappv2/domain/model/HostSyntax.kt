package com.whatsappv2.domain.model

/**
 * Host syntax rules, shared by [SipUri] and [HostPort].
 *
 * Extracted so a STUN server, a TURN server, an outbound proxy and a SIP URI all
 * agree on what a valid host is. Two implementations would drift, and the one used
 * less often would be the wrong one.
 */
internal object HostSyntax {

    private const val MAX_LABEL_LENGTH = 63
    private const val MAX_HOSTNAME_LENGTH = 253
    private const val IPV4_OCTETS = 4
    private const val MAX_OCTET = 255
    private const val MAX_OCTET_DIGITS = 3
    private const val MAX_IPV6_GROUPS = 8
    private const val MAX_IPV6_GROUP_DIGITS = 4

    private val LABEL = Regex("[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?")
    private val IPV6_COMPRESSION = Regex("::")

    /** Classifies [host], or returns `null` when it is not a valid host of any kind. */
    fun classify(host: String): SipHost? = when {
        isValidIpV4(host) -> SipHost.IpV4(host)
        isValidHostname(host) -> SipHost.Hostname(host)
        else -> null
    }

    fun isValidHostname(host: String): Boolean {
        if (host.isEmpty() || host.length > MAX_HOSTNAME_LENGTH) return false

        // A single trailing dot denotes the DNS root and is legal.
        val name = host.removeSuffix(".")
        if (name.isEmpty()) return false

        val labels = name.split('.')
        val labelsAreValid = labels.all { label ->
            label.length in 1..MAX_LABEL_LENGTH && LABEL.matches(label)
        }

        // RFC 1123: the top label must not be all digits. Without this a typo'd address
        // such as 1.2.3.256 fails IPv4 validation and then silently succeeds as a DNS
        // name, failing much later at resolution time.
        return labelsAreValid && !labels.last().all(Char::isDigit)
    }

    fun isValidIpV4(host: String): Boolean {
        val octets = host.split('.')
        if (octets.size != IPV4_OCTETS) return false

        return octets.all { octet ->
            // "01" is rejected: leading zeros invite octal misreadings.
            octet.isNotEmpty() &&
                octet.length <= MAX_OCTET_DIGITS &&
                octet.all(Char::isDigit) &&
                (octet.length == 1 || !octet.startsWith('0')) &&
                octet.toInt() <= MAX_OCTET
        }
    }

    fun isValidIpV6(address: String): Boolean {
        if (address.isEmpty()) return false

        val compressions = IPV6_COMPRESSION.findAll(address).count()
        val groups = address.split(":").filter(String::isNotEmpty)

        // "::" stands in for one or more zero groups, so only an uncompressed address
        // must carry the full eight.
        val structureIsValid = when {
            compressions > 1 -> false
            compressions == 0 -> groups.size == MAX_IPV6_GROUPS
            else -> groups.size <= MAX_IPV6_GROUPS
        }
        return structureIsValid && groups.all(::isIpV6Group)
    }

    private fun isIpV6Group(group: String): Boolean =
        group.length <= MAX_IPV6_GROUP_DIGITS &&
            group.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
}
