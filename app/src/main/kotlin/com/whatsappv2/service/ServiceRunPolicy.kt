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
 * The service type to go foreground with, for **any** decision — including [ServiceDecision.Stop].
 *
 * A `Stop` still needs one, and that is the whole point. `startForegroundService` promises
 * that `startForeground` will be called; the promise is made by the caller and survives
 * the service deciding there is nothing to do. Going straight to `stopSelf` without it is
 * what killed the app with `ForegroundServiceDidNotStartInTimeException` while somebody
 * was adding their first account — the decision then is `Stop`, because nothing is
 * registered yet.
 *
 * `REGISTRATION` is the honest default: `specialUse` describes a service that is starting
 * up to hold a registration, and it needs no runtime permission, so it cannot itself throw.
 */
fun ServiceDecision.foregroundReason(): ServiceReason =
    (this as? ServiceDecision.Run)?.reason ?: ServiceReason.REGISTRATION

/**
 * Decides whether the registration service should run.
 *
 * Pure, so the rule can be asserted directly rather than inferred from `dumpsys` output.
 * The service itself only applies the answer.
 *
 * ## Running is about intent, not about the current answer from the registrar
 *
 * This used to run only while a registration was *usable* (or in flight), and stop the
 * moment it was not. That rule had a hole a handset fell through every day: Wi-Fi drops,
 * every `Registered` account is mapped to `Failed(NETWORK_UNAVAILABLE)`, the rule says
 * `Stop`, the foreground service exits — and the process is now an ordinary background
 * process that the platform kills within minutes. When Wi-Fi came back nothing was alive
 * to re-register, and the extension showed as unregistered until somebody opened the app.
 *
 * The purpose of the service is to keep a logged-in account **reachable**, and that
 * purpose does not end when the network does. So the service runs while any account
 * *wants* to be registered — logged in and never logged out — whether the registrar
 * currently says Registered, Registering, or Failed for a reason that will clear on its
 * own. It stops when every account is logged out (or none exists) and no call is up,
 * which is the only state in which there is genuinely nothing to hold open. §6's "no
 * service that outlives its purpose" still holds; the purpose was described too narrowly.
 *
 * [justifiesWakeLock] is deliberately narrower. Waiting for a network needs no CPU — the
 * recovery coordinator waits on the platform's connectivity callback, not a timer — so
 * holding a wake lock through an outage would be the battery bug §6 warns about.
 */
object ServiceRunPolicy {

    /**
     * @param registrations current state per account, as the registrar reports it.
     * @param activeCalls how many calls are in progress.
     * @param wantsRegistration whether any account is logged in — the user's intent,
     *   independent of what the registrar has reported so far. True while the process is
     *   starting and the stack has not yet been told about the accounts, and true through
     *   a network outage.
     */
    fun decide(
        registrations: Map<*, RegistrationState>,
        activeCalls: Int,
        wantsRegistration: Boolean = false,
    ): ServiceDecision = when {
        // A call outranks everything: it must keep running even if the registration
        // behind it has since failed, or the call would be killed mid-sentence.
        activeCalls > 0 -> ServiceDecision.Run(ServiceReason.ACTIVE_CALL)

        // "Registering" counts as a reason to run. Stopping between the request and the
        // response would kill the very attempt the service exists to make.
        registrations.values.any { it.isUsable || it is RegistrationState.Registering } ->
            ServiceDecision.Run(ServiceReason.REGISTRATION)

        // Recovering: a failure that clears on its own (no network, a timeout, a 503) is a
        // registration that is coming back, and the process has to be alive when it does.
        registrations.values.any { it.isRecovering } -> ServiceDecision.Run(ServiceReason.REGISTRATION)

        // Logged in, but the registrar has said nothing yet — the first seconds of a
        // process the platform started after a reboot, a sticky restart or a task swipe.
        wantsRegistration -> ServiceDecision.Run(ServiceReason.REGISTRATION)

        else -> ServiceDecision.Stop
    }

    /**
     * True when the service currently justifies holding a wake lock.
     *
     * Narrower than [decide], and on purpose — see the class comment. §6: no wake lock may
     * be held while unregistered, and waiting for a network is unregistered.
     */
    fun justifiesWakeLock(
        registrations: Map<*, RegistrationState>,
        activeCalls: Int,
    ): Boolean = activeCalls > 0 ||
        registrations.values.any { it.isUsable || it is RegistrationState.Registering }

    /** A failure the app will get past without the user: the network, the server, the link. */
    private val RegistrationState.isRecovering: Boolean
        get() = this is RegistrationState.Failed && !reason.requiresUserAction
}
