package com.whatsappv2.service

import com.whatsappv2.domain.model.RegistrationState

/**
 * Why the foreground service is running, which decides its service type.
 *
 * Android 14 requires the type passed to `startForeground` to match what the service is
 * actually doing, and the two cases here are genuinely different: holding a registration
 * is not a phone call, and declaring it as one would be a false claim about the app's
 * behaviour.
 */
enum class ServiceReason {
    /**
     * A call is in progress. `phoneCall`, which also requires `MANAGE_OWN_CALLS`.
     */
    ACTIVE_CALL,

    /**
     * Registered and waiting for calls. `specialUse`: the service is keeping a SIP
     * registration alive so incoming calls can arrive, which is not a phone call, a data
     * sync, or any other standard type.
     */
    REGISTRATION,
}

/** Whether the service should be running, and if so why. */
sealed interface ServiceDecision {

    data class Run(val reason: ServiceReason) : ServiceDecision

    /**
     * Nothing to hold open.
     *
     * The hard stop rule from §6: a foreground service that outlives its purpose is a
     * battery bug, and a persistent notification with nothing behind it teaches people to
     * dismiss notifications from this app.
     */
    data object Stop : ServiceDecision
}

/**
 * Decides whether the registration service should run.
 *
 * Pure, so the rule can be asserted directly rather than inferred from `dumpsys` output.
 * The service itself only applies the answer.
 */
object ServiceRunPolicy {

    /**
     * @param registrations current state per account.
     * @param activeCalls how many calls are in progress.
     */
    fun decide(
        registrations: Map<*, RegistrationState>,
        activeCalls: Int,
    ): ServiceDecision = when {
        // A call outranks everything: it must keep running even if the registration
        // behind it has since failed, or the call would be killed mid-sentence.
        activeCalls > 0 -> ServiceDecision.Run(ServiceReason.ACTIVE_CALL)

        // "Registering" counts as a reason to run. Stopping between the request and the
        // response would kill the very attempt the service exists to make.
        registrations.values.any { it.isUsable || it is RegistrationState.Registering } ->
            ServiceDecision.Run(ServiceReason.REGISTRATION)

        else -> ServiceDecision.Stop
    }

    /**
     * True when the service currently justifies holding a wake lock.
     *
     * Identical to "should it run at all", stated separately because §6 calls it out: no
     * wake lock may be held while unregistered, and a rule with its own name is harder to
     * quietly drift away from.
     */
    fun justifiesWakeLock(
        registrations: Map<*, RegistrationState>,
        activeCalls: Int,
    ): Boolean = decide(registrations, activeCalls) is ServiceDecision.Run
}
