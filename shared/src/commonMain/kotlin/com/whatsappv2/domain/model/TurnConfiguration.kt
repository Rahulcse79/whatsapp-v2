package com.whatsappv2.domain.model

import com.whatsappv2.core.common.secret.Secret

/**
 * TURN relay credentials (§5.1).
 *
 * The password is a [Secret], so it is encrypted at rest alongside the SIP password
 * (Task 16) and cannot leak through `toString()`.
 */
data class TurnConfiguration(
    val server: HostPort,
    val username: String,
    val password: Secret,
) {
    init {
        require(username.isNotBlank()) { "A TURN server requires a username" }
    }
}
