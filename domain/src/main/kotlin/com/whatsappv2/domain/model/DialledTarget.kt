package com.whatsappv2.domain.model

import com.whatsappv2.core.common.result.getOrNull

/**
 * Turns what somebody typed into an address (Tasks 35, 55).
 *
 * Extracted from `PlaceCallUseCase` when transfer needed the same thing. `1001` and
 * `sip:1001@example.com` are the same destination, and a transfer target typed into the
 * transfer dialog has to be completed the same way a dialled one is — otherwise
 * transferring to `1001` fails while calling `1001` works, which is the sort of
 * inconsistency nobody reports as a bug because they assume they typed it wrong.
 *
 * Pure, so the equivalence is asserted directly rather than through an engine.
 */
object DialledTarget {

    /**
     * The address [input] names, completed against [accountDomain], or null if unusable.
     *
     * A bare extension is completed against the account's own domain — this is the line
     * that makes dialling `1001` work at all, since a SIP URI has no meaning without one.
     * Anything that already looks like a URI is parsed as written, so a call to another
     * domain is not silently rewritten to this account's.
     *
     * ## [accountPort]
     *
     * The account's own port — `SipAccount.dialledPort` — and **only** for the branch that
     * supplies the account's domain. Set it to 5070 and calls go to 5070; leave it at 5060
     * and they go to 5060. There is no default-is-special rule to remember.
     *
     * It is the fix for a real asymmetry: registration put the port on its request URI
     * through `SipAccount.effectiveRegistrar`, and a call never did, so an account on a
     * server listening on 5070 registered on 5070 and then sent every INVITE to 5060.
     *
     * The other two branches are deliberately untouched. A destination the user spelled
     * out — `sip:1004@elsewhere` or `1004@elsewhere` — names its own host, and imposing
     * this account's port on somebody else's server is a guess, not a completion.
     */
    fun resolve(input: String, accountDomain: String, accountPort: Int? = null): SipUri? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        val qualified = when {
            trimmed.startsWith(SIP_SCHEME) || trimmed.startsWith(SIPS_SCHEME) -> trimmed
            // A user@host with no scheme — the host is explicit, only the scheme is not.
            trimmed.contains('@') -> "$SIP_SCHEME$trimmed"
            else -> "$SIP_SCHEME$trimmed@$accountDomain${accountPort?.let { ":$it" }.orEmpty()}"
        }
        return SipUri.parse(qualified).getOrNull()
    }

    private const val SIP_SCHEME = "sip:"
    private const val SIPS_SCHEME = "sips:"
}
