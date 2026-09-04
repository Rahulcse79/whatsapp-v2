package com.whatsappv2.core.designsystem.preview

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.whatsappv2.core.designsystem.theme.WhatsAppV2Theme

/**
 * Renders a preview in light **and** dark in one annotation.
 *
 * Task 14 requires every component to be previewed in both, and an architecture rule
 * checks it. Two separate `@Preview` annotations per component would be copied
 * inconsistently within a week; one annotation cannot be half-applied.
 */
@Preview(name = "Light", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
annotation class ThemePreviews

/**
 * Also renders at the largest accessibility font scale.
 *
 * Used for components carrying text that must not clip: a call button whose label is
 * cut off at 200% font scale is unusable by exactly the people who set it.
 */
@Preview(name = "Light", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Large font", showBackground = true, fontScale = 2.0f)
annotation class ThemeAndFontPreviews

/** Wraps preview content in the app theme, so a preview cannot lie about styling. */
@Composable
fun PreviewSurface(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    WhatsAppV2Theme(darkTheme = darkTheme, content = content)
}
