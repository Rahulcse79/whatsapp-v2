package com.whatsappv2.audio

import com.whatsappv2.domain.call.AudioRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where call audio goes, and what a focus change means (Task 40, DoD 8).
 *
 * Every one of these is a question a device could only answer by having someone plug a
 * headset in mid-call. Keeping the rule in a pure object is what makes them assertable.
 */
class AudioRoutePolicyTest {

    @Test
    fun `bluetooth beats a wired headset beats the earpiece`() {
        assertEquals(
            AudioRoute.BLUETOOTH,
            AudioRoutePolicy.preferredRoute(AudioDevices(hasBluetooth = true, hasWiredHeadset = true)),
        )
        assertEquals(
            AudioRoute.WIRED_HEADSET,
            AudioRoutePolicy.preferredRoute(AudioDevices(hasWiredHeadset = true)),
        )
        assertEquals(AudioRoute.EARPIECE, AudioRoutePolicy.preferredRoute(AudioDevices()))
    }

    @Test
    fun `the speaker is never chosen automatically`() {
        // Choosing it for someone puts their call on the desk in front of a room. It is
        // only ever the fallback for a device with no earpiece at all - a tablet.
        assertEquals(
            AudioRoute.SPEAKER,
            AudioRoutePolicy.preferredRoute(AudioDevices(hasEarpiece = false)),
        )
    }

    @Test
    fun `plugging in a wired headset mid-call switches to it`() {
        // Task 40's second done-when. Plugging in is a physical instruction, and it beats
        // a button pressed a minute ago.
        val route = AudioRoutePolicy.routeAfterDeviceChange(
            devices = AudioDevices(hasWiredHeadset = true),
            chosen = AudioRoute.SPEAKER,
            arrived = AudioRoute.WIRED_HEADSET,
        )

        assertEquals(AudioRoute.WIRED_HEADSET, route)
    }

    @Test
    fun `connecting a bluetooth headset mid-call switches to it`() {
        // Task 40's third done-when, and the same rule.
        val route = AudioRoutePolicy.routeAfterDeviceChange(
            devices = AudioDevices(hasBluetooth = true),
            chosen = AudioRoute.EARPIECE,
            arrived = AudioRoute.BLUETOOTH,
        )

        assertEquals(AudioRoute.BLUETOOTH, route)
    }

    @Test
    fun `a choice that is still possible survives an unrelated device change`() {
        // Speaker stays speaker: nothing arrived, and the user asked for it.
        val route = AudioRoutePolicy.routeAfterDeviceChange(
            devices = AudioDevices(hasWiredHeadset = true),
            chosen = AudioRoute.SPEAKER,
            arrived = null,
        )

        assertEquals(AudioRoute.SPEAKER, route)
    }

    @Test
    fun `unplugging the chosen headset falls back rather than leaving audio nowhere`() {
        val route = AudioRoutePolicy.routeAfterDeviceChange(
            devices = AudioDevices(),
            chosen = AudioRoute.WIRED_HEADSET,
            arrived = null,
        )

        assertEquals(AudioRoute.EARPIECE, route)
    }

    @Test
    fun `every device set offers the speaker and lists exactly what is attached`() {
        val devices = AudioDevices(hasBluetooth = true, hasWiredHeadset = false)

        assertTrue(AudioRoute.SPEAKER in devices.available)
        assertTrue(AudioRoute.BLUETOOTH in devices.available)
        assertTrue(AudioRoute.WIRED_HEADSET !in devices.available)
    }

    @Test
    fun `losing focus mutes, whether the loss is permanent or transient`() {
        // Task 40's fourth done-when. An incoming cellular call takes focus transiently,
        // and a SIP call that keeps its microphone open through it is a microphone
        // recording a conversation the user thinks is private.
        assertEquals(FocusAction.MUTE, AudioRoutePolicy.actionFor(AudioRoutePolicy.FOCUS_LOSS))
        assertEquals(
            FocusAction.MUTE,
            AudioRoutePolicy.actionFor(AudioRoutePolicy.FOCUS_LOSS_TRANSIENT),
        )
    }

    @Test
    fun `regaining focus resumes, and ducking is ignored`() {
        assertEquals(FocusAction.RESUME, AudioRoutePolicy.actionFor(AudioRoutePolicy.FOCUS_GAIN))
        assertEquals(
            FocusAction.IGNORE,
            AudioRoutePolicy.actionFor(AudioRoutePolicy.FOCUS_LOSS_TRANSIENT_CAN_DUCK),
        )
    }
}
