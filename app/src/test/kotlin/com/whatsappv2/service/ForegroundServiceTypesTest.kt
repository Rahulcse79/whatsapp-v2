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

    private infix fun Int.has(type: Int): Boolean = this and type == type
}
