package com.whatsappv2.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** Call colours, which Material's scheme has no slot for. See [CallColors]. */
val LocalCallColors = staticCompositionLocalOf { LightCallColors }

/**
 * The app theme.
 *
 * @param darkTheme follows the system by default, so the app honours the user's choice.
 * @param dynamicColor uses the wallpaper palette on Android 12+. Off by default: this is
 *   a calling app, and a wallpaper-derived scheme can produce an "end call" button that
 *   is not recognisably red. When it is on, [CallColors] is still taken from the fixed
 *   palette for exactly that reason.
 */
@Composable
fun WhatsAppV2Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkScheme
        else -> LightScheme
    }

    CompositionLocalProvider(
        LocalCallColors provides if (darkTheme) DarkCallColors else LightCallColors,
        LocalSpacing provides Spacing(),
        LocalRadius provides Radius(),
        LocalSizing provides Sizing(),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            content = content,
        )
    }
}

/**
 * Design tokens, read as `AppTheme.spacing.large` rather than through the raw
 * composition locals — one import at a call site instead of four.
 */
object AppTheme {

    val spacing: Spacing
        @Composable @ReadOnlyComposable get() = LocalSpacing.current

    val radius: Radius
        @Composable @ReadOnlyComposable get() = LocalRadius.current

    val sizing: Sizing
        @Composable @ReadOnlyComposable get() = LocalSizing.current

    val callColors: CallColors
        @Composable @ReadOnlyComposable get() = LocalCallColors.current
}
