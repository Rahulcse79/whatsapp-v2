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
    fun `a fresh install is light`() {
        // It followed the phone, which meant a handset in dark mode got the dark palette
        // on first launch without anybody asking for it. The app's screens are drawn and
        // checked light, so that is what a fresh install opens in; SYSTEM is still offered
        // in Settings for anyone who wants it.
        assertEquals(ThemeMode.LIGHT, AppSettings.DEFAULT.themeMode)
        assertFalse(
            AppSettings.DEFAULT.themeMode.resolvesToDark(systemIsDark = true),
            "a fresh install must stay light on a phone that is in dark mode",
        )
    }
}
