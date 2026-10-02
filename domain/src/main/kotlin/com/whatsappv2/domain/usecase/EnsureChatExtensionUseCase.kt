package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.secret.Secret
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
 * ## The identity comes from the login response; the registration details do not
 *
 * **Who** this account is, is entirely the server's: [ChatExtension.number] is the PBX
 * extension and [ChatExtension.domain] the SIP domain, and neither is inferred from the
 * chat identity — the first version of this did infer them and produced accounts that
 * could not register, because the username is not the extension.
 *
 * **How** to reach the switch is this deployment's, and is fixed here: port [SIP_PORT] and
 * password [SIP_PASSWORD]. The login response's `serverPort` (5061) and `sipPassword` are
 * the platform's own values and do not register against the PBX; they are still modelled on
 * [ChatExtension], so the day a deployment needs them back this is a two-constant change in
 * one file.
 *
 * Everything else is what `SipAccountDraft` defaults — codecs, NAT policy and expiry, the
 * same values the account editor starts from.
 *
 * ## No extension means no account
 *
 * Not an account built from whatever else was to hand. A missing account is visible and
 * fixable; one that registers the wrong identity looks correct in the list and fails only
 * when somebody tries to call. The credential is no longer a second gate — it is
 * [SIP_PASSWORD], so an extension is the only thing that can be missing.
 *
 * ## One account per extension, and it is the default
 *
 * An account already registering this extension is left exactly as it is — somebody who has
 * tuned their codecs or switched to TLS has made a decision, and a sign-in is not a reason
 * to undo it. Matched on the **extension**, on either field that can carry it, rather than
 * on `username` and `domain`: an older build of this app wrote the *username* into the
 * username column, so a stricter match saw no account and signed the user in to a second one
 * beside the first.
 *
 * Either way the account ends up the **default**, displacing whatever was. The signed-in
 * identity is the one this person is placing calls as, and a device carrying accounts from a
 * previous build was dialling out from one of those instead.
 *
 * ## The previous user's extension is logged out
 *
 * One handset, one person. Signing in as `mcx8102` on a phone that had been `mcx8101` used
 * to leave `8101` **registered**, so the device answered for both: calls for the previous
 * user still rang it, and calling that user from this one looped straight back — dialled
 * `8101`, arrived here, `From: 8102`. Read off the M14 on 2 Oct 2026, which held
 * `Chat (mcx8101)` and `Chat (mcx8102)` both logged in at once.
 *
 * So any account this use case provisioned for a *different* identity is logged out. Logged
 * out, not deleted: the row survives with its encrypted password, so signing back in as that
 * person restores it without retyping anything. Accounts the user made by hand are never
 * touched — only ones carrying [CHAT_LABEL_PREFIX], which is a label only this class writes.
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
    private val logout: LogoutUseCase,
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

        // Either column, because an older build of this app put the USERNAME in the username
        // column — `mcx8101` where `8101` belongs — and matching only the strict pair meant
        // a second account beside the first on every sign-in.
        val existing = accounts.observeAccounts().first().firstOrNull {
            it.extension == extension.number || it.username == extension.number
        }
        if (existing != null) {
            logger.info(TAG, "An account for this extension already exists; leaving it alone")
            // Still made the default. The account may be right while the default is one a
            // previous build left behind, and that is the one calls go out from.
            makeDefault(existing.id)
            logOutOtherChatExtensions(keep = existing.id)
            return Outcome.Success(existing.id)
        }

        val draft = SipAccountDraft(
            id = AccountId(UUID.randomUUID().toString()),
            // The username labels it, because that is what the person typed to sign in;
            // the extension is what registers, because that is what the PBX answers to.
            label = "$CHAT_LABEL_PREFIX$username)",
            username = extension.number,
            extension = extension.number,
            // This deployment's, not the response's — see the class KDoc.
            password = Secret(SIP_PASSWORD),
            // The server's name for the extension, or the number when it has none — which
            // is the live case on these accounts (`extensionName` comes back null).
            displayName = extension.displayName,
            domain = extension.domain,
            registrar = extension.domain,
            port = SIP_PORT.toString(),
            transport = if (extension.secure) Transport.TLS else Transport.UDP,
            // The identity the user just signed in as is the one they are calling as, so it
            // displaces whatever was default rather than only filling an empty slot.
            isDefault = true,
        )

        return when (val saved = saveAccount(draft)) {
            is Outcome.Success -> {
                // Belt and braces: the draft asks to be the default and the repository
                // honours it, but the rule is stated where the repository documents it.
                makeDefault(draft.id)
                logOutOtherChatExtensions(keep = draft.id)
                logger.info(TAG, "Created a SIP extension for the chat identity on port $SIP_PORT")
                Outcome.Success(draft.id)
            }
            is Outcome.Failure -> {
                // Named, not silent, and not fatal: chat is signed in either way.
                logger.warn(TAG, "Could not create a SIP extension for chat: ${saved.error}")
                Outcome.Failure(Unit)
            }
        }
    }

    /**
     * Logs out every chat-provisioned account except [keep].
     *
     * Identified by the label this class writes, so an account the user configured by hand is
     * never touched — logging someone out of their own account because they signed in to chat
     * would be this use case overstepping badly.
     *
     * [LogoutUseCase] rather than a repository write, because it is the thing that knows a
     * logout must not cut off a live call and that the unregister has to be clean. It refuses
     * during a call, which is the right answer: the handset is mid-conversation on that
     * extension, and the next sign-in can tidy up.
     */
    private suspend fun logOutOtherChatExtensions(keep: AccountId) {
        val stale = accounts.observeAccounts().first().filter {
            it.id != keep && it.registrationWanted && it.label.startsWith(CHAT_LABEL_PREFIX)
        }

        stale.forEach { account ->
            when (val result = logout(account.id)) {
                is Outcome.Success ->
                    logger.info(TAG, "Logged out a chat extension belonging to a previous sign-in")
                is Outcome.Failure ->
                    logger.warn(TAG, "Could not log out a previous chat extension: ${result.error}")
            }
        }
    }

    /** Makes [id] the account outgoing calls use. A failure here is logged, never fatal. */
    private suspend fun makeDefault(id: AccountId) {
        when (val result = accounts.setDefault(id)) {
            is Outcome.Success -> Unit
            is Outcome.Failure ->
                logger.warn(TAG, "Could not make the chat extension the default account: ${result.error}")
        }
    }

    internal companion object {
        private const val TAG = "ChatExtension"

        /**
         * The port the PBX registers on, regardless of what the login response says.
         *
         * `serverPort` comes back as 5061 and is the platform's own port; registering there
         * does not work. SIP's default does. [ChatExtension.port] still records what the
         * server said, so switching back is a change to this line.
         */
        const val SIP_PORT = 5060

        /**
         * The SIP credential, regardless of what the login response says.
         *
         * `sipPassword` comes back populated and is a platform credential, not the one the
         * switch authenticates a REGISTER with. On this deployment the extensions take
         * `1234`. Not a secret worth protecting — it is the same five characters on every
         * extension — and it is encrypted at rest by the account repository regardless.
         */
        const val SIP_PASSWORD = "1234"

        /**
         * What marks an account as this class's work.
         *
         * The only way to tell a chat-provisioned extension from one the user typed in, and
         * what keeps [logOutOtherChatExtensions] off accounts that are none of its business.
         */
        const val CHAT_LABEL_PREFIX = "Chat ("
    }
}
