package com.whatsappv2.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.di.ApplicationScope
import com.whatsappv2.domain.repository.SipAccountRepository
import com.whatsappv2.service.RegistrationService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Brings the registrations back after a reboot, and after the app is updated.
 *
 * ## What it does and what it does not have to
 *
 * Receiving the broadcast at all is most of the work: the platform has started the
 * process to deliver it, and `SipApplication.onCreate` has already begun restoring every
 * logged-in account. What is missing is something to keep that process alive long enough
 * for the REGISTERs to go out and then to stay reachable — the foreground service. So
 * this starts it, with the restore hint, when there is at least one account to restore.
 *
 * `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` are two of the few moments Android 12+
 * lets a background app start a foreground service at all, and the window is short,
 * which is why the account check is a single read and not a subscription.
 *
 * Nothing here restores an account the user logged out of: [BootRestorePolicy] reads the
 * same `registrationWanted` the app's own restore honours.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var repository: SipAccountRepository

    @Inject
    lateinit var logger: Logger

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return

        // The receiver's ten seconds are enough for one database read; the coroutine is
        // held open for exactly that read and not a moment longer.
        val pending = goAsync()
        scope.launch {
            try {
                val accounts = repository.observeAccounts().first()
                if (BootRestorePolicy.shouldStart(accounts.map { it.registrationWanted })) {
                    logger.info(TAG, "${intent.action}: starting the registration service")
                    RegistrationService.start(context, expectRestore = true)
                } else {
                    logger.info(TAG, "${intent.action}: no logged-in account, nothing to restore")
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            // Some OEMs (HTC, and a few rugged builds) fire this instead of BOOT_COMPLETED
            // on a fast boot; it is declared in the manifest beside the other two.
            "android.intent.action.QUICKBOOT_POWERON",
        )
    }
}

/**
 * Whether a boot should start the service: only when somebody is logged in.
 *
 * Pure and separate so the rule is a test rather than a reboot. A start with nothing
 * behind it costs a notification that appears and disappears on every boot of a phone
 * that has never had an account — the exact thing §6 says teaches people to dismiss this
 * app's notifications.
 */
object BootRestorePolicy {
    fun shouldStart(registrationWanted: List<Boolean>): Boolean = registrationWanted.any { it }
}
