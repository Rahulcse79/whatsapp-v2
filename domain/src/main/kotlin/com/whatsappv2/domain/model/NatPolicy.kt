package com.whatsappv2.domain.model

/**
 * NAT traversal and keepalive settings for one account (§5.1).
 *
 * [keepaliveIntervalSeconds] is the client's half of the anti-stampede story in §2.1:
 * it is configurable per account rather than a hardcoded ping, so 5,000 clients are not
 * all forced onto the same interval.
 */
data class NatPolicy(
    val iceEnabled: Boolean,
    val stunEnabled: Boolean,
    val keepaliveIntervalSeconds: Int,
) {
    init {
        require(keepaliveIntervalSeconds in MIN_KEEPALIVE_SECONDS..MAX_KEEPALIVE_SECONDS) {
            "Keepalive must be between $MIN_KEEPALIVE_SECONDS and $MAX_KEEPALIVE_SECONDS seconds, " +
                "was $keepaliveIntervalSeconds"
        }
    }

    companion object {
        const val MIN_KEEPALIVE_SECONDS = 10
        const val MAX_KEEPALIVE_SECONDS = 600
        const val DEFAULT_KEEPALIVE_SECONDS = 30

        /** ICE and STUN on, with a NAT-binding-friendly interval. */
        val DEFAULT: NatPolicy = NatPolicy(
            iceEnabled = true,
            stunEnabled = true,
            keepaliveIntervalSeconds = DEFAULT_KEEPALIVE_SECONDS,
        )
    }
}
