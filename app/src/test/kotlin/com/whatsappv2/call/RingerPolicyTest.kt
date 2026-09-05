package com.whatsappv2.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether an incoming call makes a sound (Task 37, third done-when).
 *
 * The alternative to this test is a person, a handset and the ringer switch, three times.
 * That is exactly why the rule lives in a pure object.
 */
class RingerPolicyTest {

    @Test
    fun `silent mode rings nothing and buzzes nothing`() {
        val signal = RingerPolicy.signalFor(RingerMode.SILENT, InterruptionFilter.ALL)

        assertTrue(signal.isSilent)
    }

    @Test
    fun `vibrate mode buzzes and does not ring`() {
        // A ringtone in vibrate mode is the bug people notice in a meeting.
        val signal = RingerPolicy.signalFor(RingerMode.VIBRATE, InterruptionFilter.ALL)

        assertFalse(signal.playRingtone)
        assertTrue(signal.vibrate)
    }

    @Test
    fun `normal mode rings and does not also buzz`() {
        // "Vibrate when ringing" is a platform preference this app cannot read, so it does
        // not guess at it - doing both would be louder than the phone app under the same
        // settings.
        val signal = RingerPolicy.signalFor(RingerMode.NORMAL, InterruptionFilter.ALL)

        assertTrue(signal.playRingtone)
        assertFalse(signal.vibrate)
    }

    @Test
    fun `total silence and alarms-only are respected whatever the ringer says`() {
        // DoD-adjacent and the reason this is not "calls always ring": the user asked for
        // nothing, and a calling app is not an exception to that.
        for (mode in RingerMode.entries) {
            assertTrue(
                RingerPolicy.signalFor(mode, InterruptionFilter.NONE).isSilent,
                "$mode must stay silent under total silence",
            )
            assertTrue(
                RingerPolicy.signalFor(mode, InterruptionFilter.ALARMS).isSilent,
                "$mode must stay silent under alarms-only",
            )
        }
    }

    @Test
    fun `priority-only Do Not Disturb follows the ringer switch`() {
        // "Priority only" is the user allowing some things through, and the platform
        // decides which - for the notification, which posts either way. Overriding the
        // ringer here would make this app louder than the phone app under one setting.
        assertEquals(
            RingerPolicy.signalFor(RingerMode.NORMAL, InterruptionFilter.ALL),
            RingerPolicy.signalFor(RingerMode.NORMAL, InterruptionFilter.PRIORITY),
        )
        assertTrue(RingerPolicy.signalFor(RingerMode.SILENT, InterruptionFilter.PRIORITY).isSilent)
    }

    @Test
    fun `a filter this app does not recognise falls back to the ringer, not to silence`() {
        // A newer platform filter must not silence calls on an app that predates it.
        val signal = RingerPolicy.signalFor(RingerMode.NORMAL, InterruptionFilter.UNKNOWN)

        assertTrue(signal.playRingtone)
    }
}
