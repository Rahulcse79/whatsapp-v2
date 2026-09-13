package com.whatsappv2.push

import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.SipAccount

/**
 * Which stored account a push's `account_id` names (ADR-004, Task 38).
 *
 * ## Why this is not a map lookup
 *
 * [AccountId] is a UUID minted on this device when the account was saved. The push
 * gateway never sees it: what the gateway has is what the REGISTER carried — the SIP user
 * (`1001`) and the realm it registered in. So `account_id` on the wire is the SIP identity,
 * and the first version of the wake path, which did `AccountId(payload.accountId)` and
 * looked that up, could never find anything: a live process got a push, started the
 * service, and sent no REGISTER.
 *
 * The internal id is still accepted first, so a gateway that is handed it (a future one,
 * or a test) is not wrong either.
 */
object PushAccountResolver {

    /**
     * Resolves [accountId] against [accounts], or returns null when nothing matches.
     *
     * Accepted spellings, in order: the internal id; the SIP user (`1001`); the SIP user
     * qualified by domain (`1001@192.168.2.196`), which is the form that tells two accounts
     * with the same user on different servers apart. A user with no qualifier matches the
     * first account carrying it, which is the right answer on a device with one account
     * per server and a spare REGISTER on the other one otherwise.
     */
    fun resolve(accountId: String, accounts: List<SipAccount>): AccountId? {
        accounts.firstOrNull { it.id.value == accountId }?.let { return it.id }

        val user = accountId.substringBefore('@')
        val domain = accountId.substringAfter('@', missingDelimiterValue = "")
        return accounts.firstOrNull { account ->
            account.username == user && (domain.isEmpty() || account.domain.equals(domain, ignoreCase = true))
        }?.id
    }
}
