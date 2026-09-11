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

    /** Orange, for "trying": distinct from the amber warning and from the green it is on its way to. */
    val Orange40 = Color(0xFFB35A00)
    val Orange80 = Color(0xFFFFB77C)

    /** The grey a thing that is switched off is drawn in. */
    val Neutral50 = Color(0xFF747874)
    val Neutral60 = Color(0xFF8E928E)

    /** A blue for the third accent — video, mostly. Material's default here is a mauve. */
    val Blue40 = Color(0xFF3B6470)
    val Blue80 = Color(0xFFA3CDDB)
    val BlueContainer = Color(0xFFBEE9F7)
    val BlueOnContainer = Color(0xFF001F27)
    val BlueContainerDark = Color(0xFF1E4B56)

    /**
     * The neutral ramp, faintly green. Every surface tier is named here because a tier
     * left undefined falls back to Material's baseline, which is mauve — the settings
     * cards and every dropdown were drawing lilac on a green app until these were filled
     * in. Numbers are Material tones: 99 is nearly white, 10 nearly black.
     */
    val Neutral4 = Color(0xFF0C0F0D)
    val Neutral6 = Color(0xFF111412)
    val Neutral10 = Color(0xFF191C1A)
    val Neutral12 = Color(0xFF1D201E)
    val Neutral17 = Color(0xFF272B28)
    val Neutral20 = Color(0xFF2E312F)
    val Neutral22 = Color(0xFF323634)
    val Neutral24 = Color(0xFF373B38)
    val Neutral87 = Color(0xFFDBDDD9)
    val Neutral90 = Color(0xFFE1E3DF)
    val Neutral92 = Color(0xFFE7E9E4)
    val Neutral94 = Color(0xFFECEEE9)
    val Neutral95 = Color(0xFFEFF1EC)
    val Neutral96 = Color(0xFFF2F4EF)
    val Neutral98 = Color(0xFFF8FAF5)
    val Neutral99 = Color(0xFFFBFDF8)
    val White = Color(0xFFFFFFFF)

    /** Outlines: the neutral-variant ramp, one step greener than the neutrals. */
    val NeutralVariant30 = Color(0xFF404943)
    val NeutralVariant50 = Color(0xFF717972)
    val NeutralVariant60 = Color(0xFF8B938B)
    val NeutralVariant80 = Color(0xFFC1C9C0)
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
    tertiary = Palette.Blue40,
    onTertiary = Palette.White,
    tertiaryContainer = Palette.BlueContainer,
    onTertiaryContainer = Palette.BlueOnContainer,
    error = Palette.Red40,
    onError = Palette.White,
    errorContainer = Palette.RedContainer,
    onErrorContainer = Palette.RedOnContainer,
    background = Palette.Neutral99,
    onBackground = Palette.Neutral10,
    surface = Palette.Neutral99,
    onSurface = Palette.Neutral10,
    surfaceVariant = Palette.Neutral95,
    onSurfaceVariant = Palette.NeutralVariant30,
    surfaceDim = Palette.Neutral87,
    surfaceBright = Palette.Neutral99,
    surfaceContainerLowest = Palette.White,
    surfaceContainerLow = Palette.Neutral96,
    surfaceContainer = Palette.Neutral94,
    surfaceContainerHigh = Palette.Neutral92,
    surfaceContainerHighest = Palette.Neutral90,
    outline = Palette.NeutralVariant50,
    outlineVariant = Palette.NeutralVariant80,
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
    tertiary = Palette.Blue80,
    onTertiary = Palette.BlueOnContainer,
    tertiaryContainer = Palette.BlueContainerDark,
    onTertiaryContainer = Palette.BlueContainer,
    error = Palette.Red80,
    onError = Palette.RedOnContainer,
    errorContainer = Palette.RedContainerDark,
    onErrorContainer = Palette.RedContainer,
    background = Palette.Neutral10,
    onBackground = Palette.Neutral90,
    surface = Palette.Neutral10,
    onSurface = Palette.Neutral90,
    surfaceVariant = Palette.Neutral20,
    onSurfaceVariant = Palette.NeutralVariant80,
    surfaceDim = Palette.Neutral6,
    surfaceBright = Palette.Neutral24,
    surfaceContainerLowest = Palette.Neutral4,
    surfaceContainerLow = Palette.Neutral12,
    surfaceContainer = Palette.Neutral17,
    surfaceContainerHigh = Palette.Neutral22,
    surfaceContainerHighest = Palette.Neutral24,
    outline = Palette.NeutralVariant60,
    outlineVariant = Palette.NeutralVariant30,
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

/**
 * The four colours a status indicator can be (item 5.5).
 *
 * Green is registered, orange is trying, red is failed, grey is off. Fixed like
 * [CallColors] rather than taken from the scheme, because a traffic light whose red came
 * out of a wallpaper palette would not be a traffic light — and because "registered" must
 * be the same green as "answer" so the app has one meaning for it. Colour is never the
 * only channel: every indicator pairs its dot with the state in words.
 */
data class StatusColors(
    val online: Color,
    val connecting: Color,
    val failed: Color,
    val offline: Color,
)

internal val LightStatusColors = StatusColors(
    online = Palette.Green40,
    connecting = Palette.Orange40,
    failed = Palette.Red40,
    offline = Palette.Neutral50,
)

internal val DarkStatusColors = StatusColors(
    online = Palette.Green80,
    connecting = Palette.Orange80,
    failed = Palette.Red80,
    offline = Palette.Neutral60,
)
