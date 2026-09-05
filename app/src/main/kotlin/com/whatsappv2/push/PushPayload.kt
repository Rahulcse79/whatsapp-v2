package com.whatsappv2.push

/**
 * What the push gateway sends, per ADR-004's normative contract (Task 38).
 *
 * ```
 * call_id     string    SIP Call-ID of the pending INVITE, for correlation
 * account_id  string    which registered identity the call is for
 * sent_at     epoch ms  for staleness detection
 * type        enum      incoming_call (extensible: missed_call, message_waiting)
 * ```
 *
 * **It carries no credentials and no call content**, and this type cannot represent any:
 * there is nowhere to put a password, and the caller's identity arrives in the INVITE over
 * the secured signalling channel rather than here (§7, DoD 12). That is not an accident of
 * the fields chosen — it is the contract, and a field added here later would be a
 * disclosure decision, not a schema change.
 */
data class PushPayload(
    val callId: String,
    val accountId: String,
    val sentAtEpochMillis: Long,
    val type: PushType,
) {
    /**
     * Redacted. The `Call-ID` correlates a call, and the account id names an identity;
     * neither belongs in a log that may be attached to a bug report (§7).
     */
    override fun toString(): String = "PushPayload(type=$type, sentAt=$sentAtEpochMillis)"

    companion object {
        const val KEY_CALL_ID = "call_id"
        const val KEY_ACCOUNT_ID = "account_id"
        const val KEY_SENT_AT = "sent_at"
        const val KEY_TYPE = "type"

        /**
         * Parses a data message, or returns null when it is not one of ours.
         *
         * Strict on purpose. A payload missing a field is not a payload with a sensible
         * default — waking the device and re-registering for a message this app cannot
         * interpret is exactly the battery cost push exists to avoid. The unparseable case
         * is logged by the caller and dropped.
         */
        fun from(data: Map<String, String>): PushPayload? {
            val type = PushType.fromToken(data[KEY_TYPE])
            val callId = data[KEY_CALL_ID]?.takeIf { it.isNotBlank() }
            val accountId = data[KEY_ACCOUNT_ID]?.takeIf { it.isNotBlank() }
            val sentAt = data[KEY_SENT_AT]?.toLongOrNull()

            // All four or none: a partial payload is not a payload with defaults, and
            // waking the device for one is the battery cost push exists to avoid.
            if (type == null || callId == null || accountId == null || sentAt == null) return null

            return PushPayload(callId, accountId, sentAt, type)
        }
    }
}

/**
 * What the push is about.
 *
 * An enum with an explicit unknown rather than a string: the contract says the field is
 * extensible, and a future `message_waiting` reaching a client that predates it must be
 * ignored quietly rather than treated as a call.
 */
enum class PushType(val token: String) {
    INCOMING_CALL("incoming_call"),
    MISSED_CALL("missed_call"),
    MESSAGE_WAITING("message_waiting"),
    ;

    companion object {
        fun fromToken(token: String?): PushType? = entries.firstOrNull { it.token == token }
    }
}
