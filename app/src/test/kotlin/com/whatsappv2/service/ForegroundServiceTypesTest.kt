package com.whatsappv2.service

import android.content.pm.ServiceInfo
import android.os.Build
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task 51's third done-when: the service starts on Android 14+ without a
 * `ForegroundServiceTypeException`.
 *
 * That exception is thrown when a declared type's permission is not held **at the moment
 * `startForeground` runs**, so the property being asserted is a relationship between what
 * is declared and what is granted. It is a pure function of five values, which is the only
 * reason every combination can be checked at all — on a device this would need one handset
 * per Android version and a way to revoke permissions mid-test.
 */
class ForegroundServiceTypesTest {

    private val q = Build.VERSION_CODES.Q
    private val u = Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    @Test
    fun `before Q there are no service types to declare`() {
        val types = ForegroundServiceTypes.of(
            reason = ServiceReason.ACTIVE_CALL,
            sdkInt = Build.VERSION_CODES.P,
            microphoneGranted = true,
            cameraGranted = true,
            videoActive = true,
        )

        assertEquals(ForegroundServiceTypes.NONE, types)
    }

    @Test
    fun `holding a registration on 14+ is specialUse, not a phone call`() {
        val types = ForegroundServiceTypes.of(ServiceReason.REGISTRATION, sdkInt = u)

        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, types)
    }

    @Test
    fun `holding a registration below 14 falls back to phoneCall`() {
        val types = ForegroundServiceTypes.of(ServiceReason.REGISTRATION, sdkInt = q)

        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL, types)
    }

    @Test
    fun `an audio call with the microphone granted declares phoneCall and microphone`() {
        val types = ForegroundServiceTypes.of(
            reason = ServiceReason.ACTIVE_CALL,
            sdkInt = u,
            microphoneGranted = true,
        )

        assertTrue(types has ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        assertTrue(types has ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        assertFalse(types has ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
    }

    @Test
    fun `a video call with both permissions declares camera too`() {
        val types = ForegroundServiceTypes.of(
            reason = ServiceReason.ACTIVE_CALL,
            sdkInt = u,
            microphoneGranted = true,
            cameraGranted = true,
            videoActive = true,
        )

        assertTrue(types has ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        assertTrue(types has ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    @Test
    fun `a video call without the camera permission does not declare camera`() {
        // The whole point: declaring a type whose permission is missing throws, and the
        // person it would throw for is the one who declined the camera on purpose.
        val types = ForegroundServiceTypes.of(
            reason = ServiceReason.ACTIVE_CALL,
            sdkInt = u,
            microphoneGranted = true,
            cameraGranted = false,
            videoActive = true,
        )

        assertFalse(types has ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        assertTrue(types has ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
    }

    @Test
    fun `an audio call does not declare camera even with the permission held`() {
        val types = ForegroundServiceTypes.of(
            reason = ServiceReason.ACTIVE_CALL,
            sdkInt = u,
            microphoneGranted = true,
            cameraGranted = true,
            videoActive = false,
        )

        assertFalse(types has ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
    }

    @Test
    fun `a call whose microphone permission was revoked still runs as a call`() {
        val types = ForegroundServiceTypes.of(
            reason = ServiceReason.ACTIVE_CALL,
            sdkInt = u,
            microphoneGranted = false,
        )

        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL, types)
    }

    @Test
    fun `a registration never declares a type a permission can refuse`() {
        // What the fallback in RegistrationService.enterForeground rests on. When
        // startForeground is refused for a call's types, the service retries as a
        // REGISTRATION instead of stopping - because stopping before startForeground has
        // succeeded is what kills the process with
        // ForegroundServiceDidNotStartInTimeException, which is the crash a handset found
        // on 2026-09-08.
        //
        // That retry is only worth making while REGISTRATION declares nothing the platform
        // checks a runtime permission for. `microphone` and `camera` are exactly those;
        // `specialUse` and `phoneCall` rest on install-time permissions the manifest holds,
        // so they cannot be revoked out from under the service.
        val flags = listOf(false, true)
        val combinations = flags.flatMap { mic -> flags.flatMap { cam -> flags.map { vid -> Triple(mic, cam, vid) } } }

        for (sdkInt in listOf(q, u)) {
            for ((microphone, camera, video) in combinations) {
                val types = ForegroundServiceTypes.of(
                    reason = ServiceReason.REGISTRATION,
                    sdkInt = sdkInt,
                    microphoneGranted = microphone,
                    cameraGranted = camera,
                    videoActive = video,
                )

                assertFalse(types has ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                assertFalse(types has ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            }
        }
    }

    private infix fun Int.has(type: Int): Boolean = this and type == type
}
