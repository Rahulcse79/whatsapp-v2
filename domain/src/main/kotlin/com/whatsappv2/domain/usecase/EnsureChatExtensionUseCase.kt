package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.chat.CoralServerUrl
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.Transport
import com.whatsappv2.domain.repository.SipAccountRepository
import com.whatsappv2.domain.validation.SipAccountDraft
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject

/**
 * Gives a chat sign-in a SIP extension to call from.
 *
 * ## Why signing in to chat should create a SIP account at all
 *
 * Because the chat thread offers a call button, and a button that cannot work is worse
 * than no button. On this deployment the two identities are the same person: the Coral
 * platform authenticates `mcx8101` and the PBX registers an extension for them. Asking
 * somebody who has just typed their credentials to go to Settings and type the same
 * credentials again, into a form with nine fields, to make the call button work, is a
 * step with no decision in it — so it is done for them.
 *
 * ## What is taken and what is defaulted
 *
 * | | Value | Why |
 * |---|---|---|
 * | username / extension | the chat username | They are one identity on this deployment. |
 * | password | the chat password | Same credentials; it never leaves [SipAccountRepository]'s encrypted column. |
 * | domain / registrar | the chat server's host | The same host serves `/services/`, `/chat/` and SIP. |
 * | transport / port | UDP / 1234 | The deployment's own, which is not SIP's default 5060. |
 * | everything else | `SipAccountDraft`'s defaults | Codecs, NAT, expiry — what the editor starts from. |
 *
 * ## It never overwrites an account the user made
 *
 * If an account already registers this username on this host, nothing happens. Somebody
 * who has tuned their codecs or switched to TLS has made a decision, and a sign-in is not
 * a reason to undo it. Only the absence of one is treated as "there is nothing to call from".
 *
 * ## Failure is not a failed sign-in
 *
 * The caller is told, and ignores it. Chat works without a SIP account; only the call
 * button does not, and reporting "signed in" as a failure because the PBX half could not
 * be set up would be the tail wagging the dog.
 */
class EnsureChatExtensionUseCase @Inject constructor(
    private val accounts: SipAccountRepository,
    private val saveAccount: SaveAccountUseCase,
    private val logger: Logger,
) {

    suspend operator fun invoke(
        url: CoralServerUrl,
        username: String,
        password: Secret,
    ): Outcome<AccountId, Unit> {
        val host = url.host
        val present = accounts.observeAccounts().first()
        present.firstOrNull { it.username == username && it.domain == host }?.let {
            logger.info(TAG, "An extension for this chat identity already exists; leaving it alone")
            return Outcome.Success(it.id)
        }

        val draft = SipAccountDraft(
            id = AccountId(UUID.randomUUID().toString()),
            label = "Chat ($username)",
            username = username,
            // The same string. On this deployment the username IS the extension, which is
            // what makes a call to a directory row work without a translation table.
            extension = username,
            password = password,
            displayName = username,
            domain = host,
            registrar = host,
            port = CHAT_SIP_PORT.toString(),
            transport = Transport.UDP,
            // The first account on a fresh install has to be the default, or a call
            // placed from the thread fails for want of one having just created it.
            // Only when there is nothing else to displace.
            isDefault = present.isEmpty(),
        )

        return when (val saved = saveAccount(draft)) {
            is Outcome.Success -> {
                logger.info(TAG, "Created a SIP extension for the chat identity")
                Outcome.Success(draft.id)
            }
            is Outcome.Failure -> {
                // Named, not silent, and not fatal: chat is signed in either way.
                logger.warn(TAG, "Could not create a SIP extension for chat: ${saved.error}")
                Outcome.Failure(Unit)
            }
        }
    }

    private companion object {
        const val TAG = "ChatExtension"

        /** This deployment's SIP port. Not 5060, which is why it is written down. */
        const val CHAT_SIP_PORT = 1234
    }
}
