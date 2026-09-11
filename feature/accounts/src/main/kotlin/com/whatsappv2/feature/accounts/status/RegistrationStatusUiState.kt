package com.whatsappv2.feature.accounts.status

import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.feature.accounts.list.AccountStatus

/**
 * One account as the top-bar indicator needs it (item 5.5).
 *
 * The extension rather than the label or the identity: the bar has room for one short
 * thing, and "1001" is what a person on a phone system knows themselves as. The label and
 * the full identity are one tap away in the menu this expands into.
 */
data class AccountIndicatorRow(
    val id: AccountId,
    val label: String,
    /** What to show in the bar: the dialable extension, or the username when there is none. */
    val extension: String,
    val status: AccountStatus,
    val isDefault: Boolean,
)

/**
 * What the indicator shows.
 *
 * [Content.default] is the row the bar leads with. There is always exactly one default
 * while there are accounts — the repository promotes one on every delete — so a
 * `Content` with no default would be a store invariant broken, and the state says so by
 * carrying the row rather than an id to look up.
 */
sealed interface RegistrationStatusUiState {

    /** Reading the store; nothing to show yet, and nothing to say either. */
    data object Loading : RegistrationStatusUiState

    /** No account configured. The bar offers the way to add one. */
    data object NoAccounts : RegistrationStatusUiState

    data class Content(
        val accounts: List<AccountIndicatorRow>,
        val default: AccountIndicatorRow,
    ) : RegistrationStatusUiState
}
