package com.whatsappv2.feature.settings

import com.whatsappv2.domain.voice.EnrolmentProgress
import com.whatsappv2.domain.voice.VoiceEnrolment
import com.whatsappv2.domain.voice.VoiceProfile
import com.whatsappv2.domain.voice.VoiceProfileRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The button that ends a recording must keep it, not throw it away.
 *
 * This is a regression test for a defect measured on a handset on 2026-10-06: the card asks
 * the user to talk for about ninety seconds and counts "X of 90" while they do, but the
 * recording loop only ended on its own at `EnrolmentRules.MAXIMUM_SECONDS` (120) or a
 * five-minute wall clock, and the single button on screen was wired to `cancel()`. A user
 * who spoke their 91 seconds and pressed it got no profile, no error, and no sign anything
 * had gone wrong — the old profile simply stayed, byte for byte.
 *
 * So the cases worth holding are about *which* of the two things the button does, and the
 * fake enrolment here is shaped like the real one for exactly that reason: its flow keeps
 * running until something tells it to stop, so a test that cancels instead of finishing
 * never reaches [EnrolmentProgress.Ready] — which is precisely how the bug behaved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceEnrolmentFinishTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `finishing a recording stores the profile it had captured`() = runTest(dispatcher) {
        val enrolment = FakeEnrolment(PROFILE)
        val profiles = FakeProfiles()
        val viewModel = VoiceProfileViewModel(enrolment, profiles)

        viewModel.train()
        advanceUntilIdle()
        assertNull(profiles.stored, "nothing is stored while the recording is still running")

        viewModel.finish()
        advanceUntilIdle()

        assertTrue(enrolment.finishRequested, "the view model asks enrolment to finish")
        assertEquals(PROFILE, profiles.stored, "the captured recording becomes the profile")
    }

    @Test
    fun `finishing does not cancel the job, so Ready can still arrive`() = runTest(dispatcher) {
        val enrolment = FakeEnrolment(PROFILE)
        val profiles = FakeProfiles()
        val viewModel = VoiceProfileViewModel(enrolment, profiles)

        viewModel.train()
        advanceUntilIdle()
        viewModel.finish()
        advanceUntilIdle()

        // The bug in one assertion: cancelling here killed the flow before Building and
        // Ready could be emitted, so the profile was never handed back to be stored.
        assertEquals(
            EnrolmentProgress.Ready(PROFILE),
            viewModel.uiState.value.progress,
            "the flow runs on past Building to Ready",
        )
    }

    @Test
    fun `cancelling still abandons the recording and stores nothing`() = runTest(dispatcher) {
        val enrolment = FakeEnrolment(PROFILE)
        val profiles = FakeProfiles()
        val viewModel = VoiceProfileViewModel(enrolment, profiles)

        viewModel.train()
        advanceUntilIdle()
        viewModel.cancel()
        advanceUntilIdle()

        assertTrue(!enrolment.finishRequested, "cancelling is not finishing")
        assertNull(profiles.stored, "an abandoned recording leaves the old profile alone")
        assertNull(viewModel.uiState.value.progress, "and the card goes back to resting")
    }

    private class FakeEnrolment(private val profile: VoiceProfile) : VoiceEnrolment {
        private val finished = CompletableDeferred<Unit>()
        var finishRequested = false
            private set

        /** Records "for ever", like the real one, until asked to stop. */
        override fun enrol(): Flow<EnrolmentProgress> = flow {
            emit(EnrolmentProgress.Recording(0, 0f))
            emit(EnrolmentProgress.Recording(SPEECH_SECONDS, 0.68f))
            finished.await()
            emit(EnrolmentProgress.Building)
            emit(EnrolmentProgress.Ready(profile))
        }

        override fun requestFinish() {
            finishRequested = true
            finished.complete(Unit)
        }
    }

    private class FakeProfiles : VoiceProfileRepository {
        var stored: VoiceProfile? = null
            private set

        override val profile = MutableStateFlow<VoiceProfile?>(null)
        override suspend fun current(): VoiceProfile? = profile.value
        override suspend fun replace(profile: VoiceProfile) {
            stored = profile
            this.profile.value = profile
        }

        override suspend fun delete() {
            stored = null
            profile.value = null
        }
    }

    private companion object {
        /** Past `EnrolmentRules.MINIMUM_SECONDS`, which is what makes finishing legitimate. */
        const val SPEECH_SECONDS = 61

        val PROFILE: VoiceProfile = checkNotNull(
            VoiceProfile.of(
                embedding = FloatArray(VoiceProfile.DIMENSIONS) { 1f },
                enrolledSeconds = SPEECH_SECONDS,
                createdAtEpochMillis = 1_791_000_000_000L,
            ),
        )
    }
}
