package com.whatsappv2.service

import com.whatsappv2.di.ApplicationScope
import com.whatsappv2.domain.repository.SipAccountRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether any account is logged in — the user's standing instruction to stay reachable.
 *
 * [ServiceRunPolicy] needs this beside the registrar's state because the two answer
 * different questions. The registrar says what the network currently allows; this says
 * what the user asked for, and it is the user's answer that decides whether the
 * foreground service has a purpose. Read from the account store rather than from the
 * stack so it is true from the first moment of a process the platform started — after a
 * reboot, a sticky restart or a task swipe — before the stack has been told about a single
 * account.
 *
 * A `StateFlow` with an eager start, so [RegistrationService] can read the current value
 * synchronously inside `onStartCommand`, where waiting for an emission was the crash the
 * service's own comments describe. `false` until the store answers; the service bridges
 * that gap with its restore grace rather than trusting the placeholder.
 */
@Singleton
class RegistrationDemand @Inject constructor(
    repository: SipAccountRepository,
    @ApplicationScope scope: CoroutineScope,
) {
    val wanted: StateFlow<Boolean> = repository.observeAccounts()
        .map { accounts -> accounts.any { it.registrationWanted } }
        .stateIn(scope, SharingStarted.Eagerly, initialValue = false)
}
