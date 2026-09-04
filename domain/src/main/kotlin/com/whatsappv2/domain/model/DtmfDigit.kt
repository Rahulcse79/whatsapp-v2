package com.whatsappv2.domain.model

/**
 * A DTMF digit (§5.2, DoD 8).
 *
 * All sixteen tones, including A-D: they are rarely dialled by hand but are required
 * by some PBX and carrier signalling, and an enum that omits them silently loses them.
 */
enum class DtmfDigit(val symbol: Char) {
    ZERO('0'),
    ONE('1'),
    TWO('2'),
    THREE('3'),
    FOUR('4'),
    FIVE('5'),
    SIX('6'),
    SEVEN('7'),
    EIGHT('8'),
    NINE('9'),
    STAR('*'),
    HASH('#'),
    A('A'),
    B('B'),
    C('C'),
    D('D'),
    ;

    override fun toString(): String = symbol.toString()

    companion object {
        private val BY_SYMBOL: Map<Char, DtmfDigit> = entries.associateBy { it.symbol }

        /** Parses one digit, accepting lower-case a-d. Returns `null` if unrecognised. */
        fun fromChar(symbol: Char): DtmfDigit? = BY_SYMBOL[symbol.uppercaseChar()]

        /**
         * Parses a whole dial string, e.g. `"*21#"`. Returns `null` if **any**
         * character is not a DTMF digit — a partially-sent sequence is worse than a
         * rejected one, because the far end acts on what it did receive.
         */
        fun parseSequence(input: String): List<DtmfDigit>? {
            if (input.isEmpty()) return null
            val digits = input.map { fromChar(it) ?: return null }
            return digits
        }
    }
}
