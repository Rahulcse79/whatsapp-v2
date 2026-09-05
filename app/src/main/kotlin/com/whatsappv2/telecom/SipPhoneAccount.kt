package com.whatsappv2.telecom

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import com.whatsappv2.core.common.logging.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers this app with Telecom as a self-managed calling app (Task 34, §3).
 *
 * ## Self-managed, not managed
 *
 * A *managed* connection service hands its calls to the system dialer, which is for
 * replacing the phone app. A *self-managed* one keeps its own UI and its own call list and
 * asks Telecom only for what Telecom is actually good at: knowing about the cellular call
 * the user is already on, owning audio focus and routing, and arbitrating between apps.
 *
 * §3 names hand-rolled call notifications as a **rejected design**. This is the reason it
 * is rejected: without Telecom, an incoming SIP call has no idea a mobile call is in
 * progress, and it interrupts it.
 */
@Singleton
class SipPhoneAccount @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger,
) {

    /** The handle Telecom knows this app by. Stable, so re-registering replaces in place. */
    val handle: PhoneAccountHandle
        get() = PhoneAccountHandle(
            ComponentName(context, SipConnectionService::class.java),
            ACCOUNT_ID,
        )

    /**
     * Registers the account, or logs why it could not be.
     *
     * `CAPABILITY_SELF_MANAGED` is deprecated and is still the only way to register a
     * self-managed account through `android.telecom`. Its successor is
     * `androidx.core:core-telecom`'s `CallsManager`, which is a different integration
     * rather than a renamed constant — adopting it is its own task, and §3 asks for a
     * self-managed `ConnectionService`, which is this. Hence the suppression, and hence it
     * sits on this function alone rather than on the file.
     *
     * Failure is not fatal and must not be. `MANAGE_OWN_CALLS` can be absent on a build
     * that has not asked for it yet, and some devices ship without a Telecom
     * implementation at all; neither is a reason to refuse to start. What follows is that
     * calls cannot be placed, which the engine already reports honestly.
     */
    @Suppress("DEPRECATION")
    fun register() {
        val telecom = context.getSystemService(TelecomManager::class.java)
        if (telecom == null) {
            logger.warn(TAG, "No TelecomManager on this device; SIP calling is unavailable")
            return
        }

        val account = PhoneAccount.builder(handle, ACCOUNT_LABEL)
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .addSupportedUriScheme(PhoneAccount.SCHEME_SIP)
            // Telecom rejects a self-managed account that cannot say where its calls go.
            .setAddress(Uri.fromParts(PhoneAccount.SCHEME_SIP, ACCOUNT_ID, null))
            .build()

        // Both are real and neither should take the process down: SecurityException when
        // MANAGE_OWN_CALLS has not been granted, IllegalArgumentException when the OEM's
        // Telecom refuses the account outright.
        runCatching { telecom.registerPhoneAccount(account) }
            .onSuccess { logger.info(TAG, "Registered self-managed phone account") }
            .onFailure { logger.error(TAG, "Telecom refused the phone account: ${it.javaClass.simpleName}") }
    }

    /**
     * Whether Telecom will allow an outgoing call right now.
     *
     * The honest way to ask the third done-when's question. Telecom knows about the
     * cellular call this app cannot see, so it is asked rather than guessed at; the rule
     * applied to the answer is [TelecomPolicy.mayPlaceCall].
     */
    fun outgoingCallPermitted(): Boolean {
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return false
        return runCatching { telecom.isOutgoingCallPermitted(handle) }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "SipPhoneAccount"
        const val ACCOUNT_ID = "whatsappv2-sip"
        const val ACCOUNT_LABEL = "whatsapp-v2"
    }
}
