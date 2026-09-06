package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.engine.CameraAvailability
import com.whatsappv2.domain.engine.NoCameraAvailable
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.CodecPreferences
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.NatPolicy
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.Transport
import com.whatsappv2.domain.testing.FakeSipAccountRepository
import com.whatsappv2.domain.testing.FakeSipEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Dialling into a conference (Task 60, ADR-003, DoD 11). */
class JoinConferenceUseCaseTest {

    private val account = SipAccount(
        id = AccountId("acct-1"),
        label = "Work",
        username = "alice",
        extension = null,
        authUsername = null,
        password = Secret("hunter22"),
        displayName = null,
        domain = "sip.example.com",
        registrar = null,
        outboundProxy = null,
        port = null,
        transport = Transport.UDP,
        registrationExpirySeconds = 3_600,
        stunServer = null,
        turn = null,
        natPolicy = NatPolicy.DEFAULT,
        srtpPolicy = SrtpPolicy.OPTIONAL,
        codecs = CodecPreferences.DEFAULT,
        isDefault = true,
    )

    private object CameraPresent : CameraAvailability {
        override fun isCameraUsable(): Boolean = true
    }

    private fun useCase(engine: FakeSipEngine, camera: CameraAvailability = NoCameraAvailable) =
        JoinConferenceUseCase(FakeSipAccountRepository().given(account), engine, camera)

    @Test
    fun `a bare conference number is completed against the account domain`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)

        val result = useCase(engine)("3000")

        assertIs<Outcome.Success<CallId>>(result)
        assertEquals("sip:3000@sip.example.com", engine.conferences.value.single().conferenceUri.render())
    }

    @Test
    fun `the leg is marked as a conference so the roster can attach to it`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)

        useCase(engine)("3000")

        assertTrue(engine.activeCalls.value.single().isConference)
    }

    @Test
    fun `a fresh session has no roster rather than an empty one`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)

        useCase(engine)("3000")

        val session = engine.conferences.value.single()
        assertFalse(session.rosterAvailable)
        // Task 60's third done-when: "cannot see" is not "nobody is here".
        assertNull(session.participantCount)
    }

    @Test
    fun `joining with video downgrades when there is no camera`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)

        useCase(engine)("3000", withVideo = true)

        assertEquals(MediaProfile.AUDIO, engine.activeCalls.value.single().media)
    }

    @Test
    fun `joining with video keeps it when a camera is available`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)

        useCase(engine, CameraPresent)("3000", withVideo = true)

        assertEquals(MediaProfile.AUDIO_VIDEO, engine.activeCalls.value.single().media)
    }

    @Test
    fun `an unusable conference address is refused`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)

        val result = useCase(engine)("  ")

        assertIs<PlaceCallError.InvalidTarget>(assertIs<Outcome.Failure<PlaceCallError>>(result).error)
    }

    @Test
    fun `an account override that is not configured is refused`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)

        val result = useCase(engine)("3000", accountOverride = AccountId("nope"))

        assertIs<PlaceCallError.UnknownAccount>(assertIs<Outcome.Failure<PlaceCallError>>(result).error)
    }

    @Test
    fun `an engine refusal is carried through with its cause`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        engine.alwaysFail(FakeSipEngine.Operation.PLACE_CALL, SipError.NotRegistered)

        val result = useCase(engine)("3000")

        val error = assertIs<PlaceCallError.Rejected>(assertIs<Outcome.Failure<PlaceCallError>>(result).error)
        assertEquals(SipError.NotRegistered, error.cause)
    }

    @Test
    fun `with no accounts configured there is nothing to join from`() = runTest {
        val engine = FakeSipEngine()
        val useCase = JoinConferenceUseCase(FakeSipAccountRepository(), engine, NoCameraAvailable)

        val result = useCase("3000")

        assertIs<PlaceCallError.NoAccountAvailable>(assertIs<Outcome.Failure<PlaceCallError>>(result).error)
    }
}
