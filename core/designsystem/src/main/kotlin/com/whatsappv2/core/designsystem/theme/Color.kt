package com.whatsappv2.core.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The colour palette.
 *
 * This is the only file in the project permitted to contain a colour literal — an
 * architecture rule enforces it. Everywhere else reads `MaterialTheme.colorScheme` or
 * [CallColors], so a rebrand is a change here rather than a search across the codebase.
 *
 * Green primary because this is a calling app and green means "answer" almost
 * universally; red is reserved for ending a call and must not be spent on anything else.
 */
internal object Palette {
    val Green40 = Color(0xFF006D3B)
    val Green80 = Color(0xFF6DDD9A)
    val GreenContainer = Color(0xFF97F7B7)
    val GreenOnContainer = Color(0xFF00210F)
    val GreenContainerDark = Color(0xFF00522B)

    val Teal40 = Color(0xFF4E6355)
    val Teal80 = Color(0xFFB4CCBB)
    val TealContainer = Color(0xFFD0E8D7)
    val TealOnContainer = Color(0xFF0B1F14)
    val TealContainerDark = Color(0xFF364B3E)

    val Red40 = Color(0xFFBA1A1A)
    val Red80 = Color(0xFFFFB4AB)
    val RedContainer = Color(0xFFFFDAD6)
    val RedOnContainer = Color(0xFF410002)
    val RedContainerDark = Color(0xFF93000A)

    val Amber40 = Color(0xFF7A5900)
    val Amber80 = Color(0xFFF2C144)

    val Neutral10 = Color(0xFF191C1A)
    val Neutral20 = Color(0xFF2E312F)
    val Neutral90 = Color(0xFFE1E3DF)
    val Neutral95 = Color(0xFFEFF1EC)
    val Neutral99 = Color(0xFFFBFDF8)
    val White = Color(0xFFFFFFFF)
}

internal val LightScheme = lightColorScheme(
    primary = Palette.Green40,
    onPrimary = Palette.White,
    primaryContainer = Palette.GreenContainer,
    onPrimaryContainer = Palette.GreenOnContainer,
    secondary = Palette.Teal40,
    onSecondary = Palette.White,
    secondaryContainer = Palette.TealContainer,
    onSecondaryContainer = Palette.TealOnContainer,
    error = Palette.Red40,
    onError = Palette.White,
    errorContainer = Palette.RedContainer,
    onErrorContainer = Palette.RedOnContainer,
    background = Palette.Neutral99,
    onBackground = Palette.Neutral10,
    surface = Palette.Neutral99,
    onSurface = Palette.Neutral10,
    surfaceVariant = Palette.Neutral95,
    onSurfaceVariant = Palette.Neutral20,
)

internal val DarkScheme = darkColorScheme(
    primary = Palette.Green80,
    onPrimary = Palette.GreenOnContainer,
    primaryContainer = Palette.GreenContainerDark,
    onPrimaryContainer = Palette.GreenContainer,
    secondary = Palette.Teal80,
    onSecondary = Palette.TealOnContainer,
    secondaryContainer = Palette.TealContainerDark,
    onSecondaryContainer = Palette.TealContainer,
    error = Palette.Red80,
    onError = Palette.RedOnContainer,
    errorContainer = Palette.RedContainerDark,
    onErrorContainer = Palette.RedContainer,
    background = Palette.Neutral10,
    onBackground = Palette.Neutral90,
    surface = Palette.Neutral10,
    onSurface = Palette.Neutral90,
    surfaceVariant = Palette.Neutral20,
    onSurfaceVariant = Palette.Neutral90,
)

/**
 * Call-specific colours that Material's scheme has no slot for.
 *
 * Answer and hang-up must keep their meaning in both light and dark and must never be
 * re-tinted by dynamic colour: a wallpaper-derived "end call" button that comes out
 * green would be a genuinely dangerous piece of UI.
 */
data class CallColors(
    val answer: Color,
    val onAnswer: Color,
    val hangUp: Color,
    val onHangUp: Color,
    val activeControl: Color,
    val onActiveControl: Color,
    val warning: Color,
)

internal val LightCallColors = CallColors(
    answer = Palette.Green40,
    onAnswer = Palette.White,
    hangUp = Palette.Red40,
    onHangUp = Palette.White,
    activeControl = Palette.GreenContainer,
    onActiveControl = Palette.GreenOnContainer,
    warning = Palette.Amber40,
)

internal val DarkCallColors = CallColors(
    answer = Palette.Green80,
    onAnswer = Palette.GreenOnContainer,
    hangUp = Palette.Red80,
    onHangUp = Palette.RedOnContainer,
    activeControl = Palette.GreenContainerDark,
    onActiveControl = Palette.GreenContainer,
    warning = Palette.Amber80,
)
