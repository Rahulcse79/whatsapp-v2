package com.whatsappv2.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThemeModeTest {

    @Test
    fun `system follows the phone both ways`() {
        assertTrue(ThemeMode.SYSTEM.resolvesToDark(systemIsDark = true))
        assertFalse(ThemeMode.SYSTEM.resolvesToDark(systemIsDark = false))
    }

    @Test
    fun `a forced mode ignores the phone`() {
        // The whole point of choosing: a phone on a dark schedule must not override a
        // person who asked for light, and the reverse.
        assertFalse(ThemeMode.LIGHT.resolvesToDark(systemIsDark = true))
        assertFalse(ThemeMode.LIGHT.resolvesToDark(systemIsDark = false))
        assertTrue(ThemeMode.DARK.resolvesToDark(systemIsDark = true))
        assertTrue(ThemeMode.DARK.resolvesToDark(systemIsDark = false))
    }

    @Test
    fun `a fresh install follows the phone`() {
        // What the app did before there was a choice, so an update changes nobody's screen.
        assertEquals(ThemeMode.SYSTEM, AppSettings.DEFAULT.themeMode)
    }
}
