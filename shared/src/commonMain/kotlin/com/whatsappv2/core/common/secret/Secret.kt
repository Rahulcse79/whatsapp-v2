package com.whatsappv2.core.common.secret

/**
 * A value that must never appear in a log, a crash report, or a `toString()`.
 *
 * The point is structural: reading the value requires calling [reveal], which is
 * explicit and greppable, while every accidental path — string templates, data-class
 * `toString()`, exception messages — yields a mask instead. Relying on developers to
 * remember not to log a `String` password does not survive contact with a codebase
 * this size (§7, DoD 12).
 *
 * This is not encryption. At-rest protection is the Keystore cipher in Task 16; this
 * only stops the value leaking through ordinary Kotlin conveniences.
 */
@JvmInline
value class Secret(private val raw: String) {

    /** True when the secret carries no characters. */
    val isEmpty: Boolean get() = raw.isEmpty()

    /** Character count, safe to log — it distinguishes "unset" from "wrong". */
    val length: Int get() = raw.length

    /**
     * The real value. Call this only where the secret is genuinely needed — building a
     * SIP `Authorization` header, or handing it to the cipher — and never store the
     * result in a field.
     */
    fun reveal(): String = raw

    override fun toString(): String = MASK

    companion object {
        private const val MASK = "Secret(***)"

        /** An absent secret. Distinct from a blank one the user actually typed. */
        val EMPTY: Secret = Secret("")
    }
}
