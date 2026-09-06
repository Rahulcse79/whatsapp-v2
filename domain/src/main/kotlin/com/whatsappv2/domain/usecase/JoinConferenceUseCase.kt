package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.mapError
import com.whatsappv2.domain.engine.CameraAvailability
import com.whatsappv2.domain.engine.SipConferenceController
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.DialledTarget
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.repository.SipAccountRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Joins a dial-in conference (Task 60, §2.2, ADR-003, DoD 11).
 *
 * ## A conference is a call, and that is the design
 *
 * ADR-003 chose a dial-in MCU: the bridge mixes, and joining is dialling its URI. So this
 * is [PlaceCallUseCase] with one difference, and it is worth naming rather than reusing
 * the call path — the engine has to know the leg is a conference so it can attach a
 * roster to it, and a call placed through the ordinary path has no roster and no way to
 * grow one.
 *
 * Everything downstream is the same call machinery: hold, mute, DTMF, hangup and the call
 * log all work on the leg without knowing it is a bridge. That is the payoff of the MCU
 * choice, and the reason §2.2 asks for the *model* to be SFU-ready while the transport
 * stays this simple.
 */
class JoinConferenceUseCase @Inject constructor(
    private val accounts: SipAccountRepository,
    private val conferences: SipConferenceController,
    private val camera: CameraAvailability,
) {

    /**
     * @param input the conference address — `3000` or `sip:3000@example.com`, resolved
     *   the same way a dialled extension is.
     * @param withVideo join with video (Task 61). Downgraded to audio when the camera
     *   cannot be used, so a declined permission joins the conference rather than
     *   blocking it (Task 51).
     */
    suspend operator fun invoke(
        input: String,
        accountOverride: AccountId? = null,
        withVideo: Boolean = false,
    ): Outcome<CallId, PlaceCallError> {
        val account = when (accountOverride) {
            null -> accounts.observeDefaultAccount().first()
                ?: return failure(PlaceCallError.NoAccountAvailable)

            else -> accounts.findById(accountOverride)
                ?: return failure(PlaceCallError.UnknownAccount(accountOverride))
        }

        val uri = DialledTarget.resolve(input, account.domain)
            ?: return failure(PlaceCallError.InvalidTarget(input))

        val media = (MediaProfile.of(audio = true, video = withVideo) ?: MediaProfile.AUDIO)
            .downgradedWhenCameraUnavailable(camera.isCameraUsable())

        return conferences.joinConference(account.id, uri, media)
            .mapError { PlaceCallError.Rejected(it) }
    }
}
