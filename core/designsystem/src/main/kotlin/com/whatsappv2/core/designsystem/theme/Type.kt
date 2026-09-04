package com.whatsappv2.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Typography.
 *
 * The default Material scale with two deliberate changes:
 *  - `displayLarge` is the in-call duration timer, so it uses tabular-friendly weight
 *    and does not shift as the digits change;
 *  - `labelLarge` is heavier, because call action buttons are read at a glance while
 *    the phone is moving.
 *
 * The system font family is used rather than a bundled one: it respects the user's
 * font-size and font-weight accessibility settings, which a calling app has no business
 * overriding.
 */
internal val AppTypography = Typography().let { default ->
    default.copy(
        displayLarge = default.displayLarge.copy(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Light,
            letterSpacing = 0.sp,
        ),
        labelLarge = default.labelLarge.copy(fontWeight = FontWeight.Medium),
    )
}

/** The style for the in-call duration timer. Named so its intent survives a redesign. */
val Typography.callTimer: TextStyle get() = displayLarge
