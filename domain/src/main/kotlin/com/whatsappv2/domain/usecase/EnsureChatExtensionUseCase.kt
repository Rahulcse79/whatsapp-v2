package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.domain.chat.ChatExtension
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
 * ## Every value comes from the login response
 *
 * Nothing here is inferred from the chat identity, because the first version of this did
 * exactly that and produced accounts that could not register — the username is not the
 * extension, the web password is not the SIP password, and the port is not 5060. All four
 * arrive in the login payload; [ChatExtension] holds them and names what each replaced.
 *
 * The only thing still defaulted is what `SipAccountDraft` defaults: codecs, NAT policy
 * and expiry, which are the same values the account editor starts from.
 *
 * ## It does nothing rather than guess
 *
 * No extension, or no SIP password, means no account — not an account built from whatever
 * else was to hand. A missing account is visible and fixable; one that registers the wrong
 * identity looks correct in the list and fails only when somebody tries to call.
 *
 * ## It never overwrites an account the user made
 *
 * If an account already registers this extension on this domain, nothing happens. Somebody
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
        username: String,
        extension: ChatExtension?,
    ): Outcome<AccountId, Unit> {
        // No extension, no account. The login response is the only thing that knows this
        // person's PBX number, and a registration built from the username instead would
        // register something the switch has never heard of — see ChatExtension.
        if (extension == null) {
            logger.info(TAG, "The login response carried no extension; no SIP account was created")
            return Outcome.Failure(Unit)
        }
        // And no credential, no account. The SIP password is its own secret in the login
        // response; the one typed at sign-in is the web password and does not register.
        // It is absent on a session read back from storage, which is why this only ever
        // runs on the way in.
        val sipPassword = extension.sipPassword ?: run {
            logger.info(TAG, "The login response carried no SIP password; no account was created")
            return Outcome.Failure(Unit)
        }

        val present = accounts.observeAccounts().first()
        present.firstOrNull { it.username == extension.number && it.domain == extension.domain }?.let {
            logger.info(TAG, "An extension for this chat identity already exists; leaving it alone")
            return Outcome.Success(it.id)
        }

        val draft = SipAccountDraft(
            id = AccountId(UUID.randomUUID().toString()),
            // The username labels it, because that is what the person typed to sign in;
            // the extension is what registers, because that is what the PBX answers to.
            label = "Chat ($username)",
            username = extension.number,
            extension = extension.number,
            password = sipPassword,
            // The server's name for the extension, or the number when it has none — which
            // is the live case on these accounts (`extensionName` comes back null).
            displayName = extension.displayName,
            domain = extension.domain,
            registrar = extension.domain,
            port = extension.port.toString(),
            transport = if (extension.secure) Transport.TLS else Transport.UDP,
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
    }
}
