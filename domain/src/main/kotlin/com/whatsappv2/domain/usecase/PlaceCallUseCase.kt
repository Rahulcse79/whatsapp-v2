package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.domain.engine.CameraAvailability
import com.whatsappv2.domain.engine.SipCallController
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.engine.SipRegistrar
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.DialledTarget
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.repository.SipAccountRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** Why a call could not be placed, at the granularity the dialer can act on. */
sealed interface PlaceCallError {

    /** No account was chosen and none is marked default, so there is nothing to call from. */
    data object NoAccountAvailable : PlaceCallError

    /** The per-call account override names an account that is not configured. */
    data class UnknownAccount(val id: AccountId) : PlaceCallError

    /** What the user typed is not a callable address. */
    data class InvalidTarget(val input: String) : PlaceCallError

    /**
     * The account is not registered and could not be registered in time (Task 76).
     *
     * Distinct from [SipError.NotRegistered] arriving through [Rejected]: that one means the
     * engine refused an INVITE, while this one means the recovery was attempted and did not
     * finish inside [PlaceCallUseCase.REGISTRATION_WAIT_MILLIS]. The user is told different
     * things — "not registered" against "still trying to reach the server" — so they are
     * different cases rather than one with a flag.
     */
    data class NotRegistered(val id: AccountId) : PlaceCallError

    /** The engine refused. Carries the cause so the dialer can name it. */
    data class Rejected(val cause: SipError) : PlaceCallError
}

/**
 * Places a call, having decided who places it and to where (Task 35).
 *
 * ## Why the resolution lives here
 *
 * Two things have to be worked out before an INVITE means anything, and neither of them
 * needs a SIP stack:
 *
 * **Which account.** The dialer offers a per-call override (Task 36); with none, the
 * default account is used. That is a rule, and a rule in a ViewModel is a rule that the
 * next screen to place a call will implement slightly differently.
 *
 * **What was dialled.** `1001` and `sip:1001@example.com` are the same call, which
 * [DialledTarget] reconciles against the chosen account's domain. It lives beside the
 * model rather than here because transfer needs the identical rule (Task 55), and a
 * second copy of it would be a transfer to `1001` that fails while a call to `1001`
 * works.
 *
 * Both are pure decisions with a repository read in front of them, which is exactly the
 * shape that survives being unit-tested with no engine at all.
 *
 * ## And whether video is possible (Task 74)
 *
 * A third decision, added when the dialler grew a video button. `MediaProfile` says
 * **downgrade, never refuse**: a video call placed where the camera cannot be used goes
 * out as audio rather than failing (Task 51's second done-when). [JoinConferenceUseCase]
 * and [CallWaitingUseCase] already applied that rule; this path did not, so until Task 74
 * the only way to ask for video here was one that skipped the check.
 */
class PlaceCallUseCase @Inject constructor(
    private val accounts: SipAccountRepository,
    private val calls: SipCallController,
    private val camera: CameraAvailability,
    /**
     * Consulted, and used, before the INVITE (Task 76).
     *
     * The role interface rather than the whole engine: this needs to read registration state
     * and re-register, and nothing else — giving it `SipEngine` would put `transfer` and
     * `shutdown` in scope of a class that places calls.
     */
    private val registrar: SipRegistrar,
) {

    /**
     * @param input a bare extension or a full SIP URI, as typed.
     * @param accountOverride the per-call account, or null to use the default.
     * @param media what to offer. A video profile is downgraded to audio when the camera
     *   cannot be used, so the call is placed either way.
     */
    suspend operator fun invoke(
        input: String,
        accountOverride: AccountId? = null,
        media: MediaProfile = MediaProfile.AUDIO,
    ): Outcome<CallId, PlaceCallError> {
        val account = when (accountOverride) {
            null -> accounts.observeDefaultAccount().first()
                ?: return failure(PlaceCallError.NoAccountAvailable)

            else -> accounts.findById(accountOverride)
                ?: return failure(PlaceCallError.UnknownAccount(accountOverride))
        }

        val target = DialledTarget.resolve(input, account.domain)
            ?: return failure(PlaceCallError.InvalidTarget(input))

        // Before the INVITE, not after it fails. See [ensureRegistered].
        if (!ensureRegistered(account)) {
            return failure(PlaceCallError.NotRegistered(account.id))
        }

        val offered = media.downgradedWhenCameraUnavailable(camera.isCameraUsable())

        return when (val result = calls.placeCall(account.id, target, offered)) {
            is Outcome.Success -> result
            is Outcome.Failure -> failure(PlaceCallError.Rejected(result.error))
        }
    }

    /**
     * Registers [account] if it is not registered, and waits — briefly — for the answer.
     *
     * ## The defect this closes
     *
     * This use case resolved an account and a target and then placed the call, and **never
     * consulted registration state**. Its only account-related failures were
     * `NoAccountAvailable` and `UnknownAccount`, neither of which means "not registered", so
     * a call on a lapsed account went straight to the engine, which refused it. The user saw
     * a call that dialled and died with nothing done about the cause — and if the engine's
     * own check were ever removed, an INVITE with no binding behind it takes 32 seconds to
     * fail at Timer B.
     *
     * ## Register, rather than refuse
     *
     * [SipRegistrar.register] is documented as replacing any existing registration for the
     * same id, so it is correct both for an account the engine has never seen and for one
     * whose binding lapsed — which is why it is used in place of `refreshRegistration`, whose
     * contract needs the engine to already hold the account.
     *
     * ## SHOW YOUR WORKING on the bound
     *
     * Against the reference server a REGISTER completes in **57 ms** including the 401 digest
     * round trip (`TX REGISTER` 11:43:11.725 → `RX 200` 11:43:11.782). So the wait is not
     * about a slow server; it is about a lost packet. SIP Timer A retransmits a REGISTER at
     * 500 ms, 1 s and 2 s, so **[REGISTRATION_WAIT_MILLIS] = 5000 covers three
     * retransmissions** and still answers the user well inside the time they will hold a
     * phone to their ear. Timer B's own 32 seconds is the thing being avoided, not matched:
     * a bound longer than a person will wait is a refusal with extra steps.
     *
     * @return true when the account is registered and the INVITE may go out.
     */
    private suspend fun ensureRegistered(account: SipAccount): Boolean {
        if (registrar.registrationState.value[account.id]?.isUsable == true) return true

        return withTimeoutOrNull(REGISTRATION_WAIT_MILLIS) {
            // The outcome is deliberately not the answer. `register` returns when the
            // registrar responded, and the state map is what the engine actually gates the
            // INVITE on — so a success here with a state that has not moved would place a
            // call the engine then refuses. The state is the thing to wait for.
            registrar.register(account)
            registrar.registrationState.first { it[account.id]?.isUsable == true }
            true
        } ?: false
    }

    companion object {
        /**
         * How long a cold account may take to register before the call is refused.
         *
         * Five seconds. See [ensureRegistered] for the arithmetic behind it.
         */
        const val REGISTRATION_WAIT_MILLIS = 5_000L
    }
}
