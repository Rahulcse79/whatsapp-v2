package com.whatsappv2.feature.accounts.status

import androidx.compose.ui.graphics.Color
import com.whatsappv2.core.designsystem.theme.DarkBarColorsForTest
import com.whatsappv2.core.designsystem.theme.LightBarColorsForTest
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The registration chip's colours, as numbers rather than as opinions.
 *
 * ## Why this test exists at all
 *
 * "Make the offline dot a darker red" is a request that cannot be satisfied naively: the
 * top bar is dark green, and against `#006D3B` a dark red has no contrast left. `#BA1A1A`
 * measures **1.00:1** there — the same luminance as the bar, which is to say invisible.
 * The chip therefore brings its own container in the alert states, and these are the
 * numbers that say the arrangement works. Somebody later "simplifying" the container away
 * and keeping the dark red would ship an indicator nobody can see, and this fails when
 * they do.
 *
 * Ratios are WCAG 2.x relative luminance, the same formula the accessibility tooling uses.
 */
class IndicatorContrastTest {

    @Test
    fun `the alert container separates from the bar it sits on`() {
        // Otherwise the chip has no ground and the dark reds on it have nothing to be dark
        // against. 3:1 is the bar for a non-text element that carries meaning.
        listOf(LightBarColorsForTest, DarkBarColorsForTest).forEach { bar ->
            val ratio = contrast(bar.statusAlert.container, bar.topStart)
            assertTrue(ratio >= NON_TEXT_MINIMUM, "container on the bar is only ${ratio.round()}:1")
        }
    }

    @Test
    fun `the offline words are readable on that container`() {
        // The words are the accessible channel — colour never carries a state alone — so
        // they are held to the text threshold, not the graphical one.
        listOf(LightBarColorsForTest, DarkBarColorsForTest).forEach { bar ->
            val ratio = contrast(bar.statusAlert.onContainer, bar.statusAlert.container)
            assertTrue(ratio >= TEXT_MINIMUM, "offline text is only ${ratio.round()}:1")
        }
    }

    @Test
    fun `the offline dot is readable on that container`() {
        listOf(LightBarColorsForTest, DarkBarColorsForTest).forEach { bar ->
            val ratio = contrast(bar.statusAlert.dot, bar.statusAlert.container)
            assertTrue(ratio >= NON_TEXT_MINIMUM, "offline dot is only ${ratio.round()}:1")
        }
    }

    @Test
    fun `the offline dot really is darker than the one it replaced`() {
        // The literal request, as a comparison rather than as a hex value: `status.failed`
        // is still the pale salmon this used to draw straight on the bar, so the two are
        // the before and the after and neither is written down twice.
        val old = luminance(LightBarColorsForTest.status.failed)
        val now = luminance(LightBarColorsForTest.statusAlert.dot)

        assertTrue(now < old, "the new dot is not darker: ${now.round()} vs ${old.round()}")
    }

    @Test
    fun `registered stays quiet — it has no container and a soft dot`() {
        // "Not too prominent" is a real requirement and this is what it means here: the
        // ordinary state is a bare chip, legible but unshouted, and only the state worth
        // acting on is drawn loudly.
        val ratio = contrast(LightBarColorsForTest.status.online, LightBarColorsForTest.topStart)
        assertTrue(ratio >= NON_TEXT_MINIMUM, "the online dot is not visible: ${ratio.round()}:1")
        assertTrue(ratio <= SUBTLE_MAXIMUM, "the online dot shouts at ${ratio.round()}:1")
    }

    private companion object {
        const val TEXT_MINIMUM = 4.5
        const val NON_TEXT_MINIMUM = 3.0

        /** Above this, a dot stops reading as a quiet marker and starts reading as an alarm. */
        const val SUBTLE_MAXIMUM = 7.0

        fun Double.round() = (this * 100).toInt() / 100.0

        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }

        fun luminance(color: Color): Double =
            0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

        fun contrast(a: Color, b: Color): Double {
            val (hi, lo) = listOf(luminance(a), luminance(b)).sorted().reversed()
            return (hi + 0.05) / (lo + 0.05)
        }
    }
}
