package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.domain.engine.CameraAvailability
import com.whatsappv2.domain.engine.SipCallController
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.DialledTarget
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.repository.SipAccountRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Why a call could not be placed, at the granularity the dialer can act on. */
sealed interface PlaceCallError {

    /** No account was chosen and none is marked default, so there is nothing to call from. */
    data object NoAccountAvailable : PlaceCallError

    /** The per-call account override names an account that is not configured. */
    data class UnknownAccount(val id: AccountId) : PlaceCallError

    /** What the user typed is not a callable address. */
    data class InvalidTarget(val input: String) : PlaceCallError

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

        val offered = media.downgradedWhenCameraUnavailable(camera.isCameraUsable())

        return when (val result = calls.placeCall(account.id, target, offered)) {
            is Outcome.Success -> result
            is Outcome.Failure -> failure(PlaceCallError.Rejected(result.error))
        }
    }
}
