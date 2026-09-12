package com.whatsappv2.feature.settings

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The version line at the foot of Settings.
 *
 * The formatting is tested rather than the lookup: `PackageManager` is the platform's and
 * the only interesting cases are the ones it can hand back — a null name while the app is
 * being replaced, or a blank one from a malformed manifest.
 */
class AppVersionTest {

    @Test
    fun `both numbers are shown`() {
        // The name is what a person says out loud; the code is what Android compares. A
        // bug report carrying only the name cannot tell two builds of 1.0.3 apart, and
        // this project has already shipped two APKs whose versionCode did not move.
        assertEquals("Version 1.0.3 (3)", appVersionLabel(name = "1.0.3", code = 3))
    }

    @Test
    fun `a missing name says so rather than showing an empty line`() {
        assertEquals(UNKNOWN_VERSION, appVersionLabel(name = null, code = 3))
        assertEquals(UNKNOWN_VERSION, appVersionLabel(name = "  ", code = 3))
    }
}
