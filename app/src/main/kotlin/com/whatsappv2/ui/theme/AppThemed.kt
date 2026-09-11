package com.whatsappv2.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whatsappv2.core.designsystem.theme.WhatsAppV2Theme
import com.whatsappv2.domain.model.ThemeMode
import com.whatsappv2.domain.repository.AppSettingsRepository
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The app theme, as the user chose it in Settings (item 5.2).
 *
 * ## One place, for two activities
 *
 * `MainActivity` and `CallActivity` both draw under this, so a chip pressed in Settings
 * recolours the settings screen *and* the call screen behind it in the same frame, and
 * neither activity carries its own reading of the preference that could drift from the
 * other's.
 *
 * ## The first frame follows the phone
 *
 * The preference is a DataStore read, and the first composition cannot wait for it. Until
 * it lands the theme does what the app did before there was a choice — follow the phone —
 * so the worst case is one or two frames in the phone's mode for someone who chose the
 * other. In practice the store is already warm: the registration service has read it
 * before any screen opens. Blocking the main thread on the read would be the wrong trade
 * for a flash this short.
 *
 * ## The system bars are told too
 *
 * `enableEdgeToEdge` picks status- and navigation-bar icon colours from the *phone's*
 * night mode, not ours. Someone who forces Light on a dark phone would otherwise have
 * light icons on a light bar — a clock nobody can read. The insets controller is told
 * the theme every time it resolves, so the icons follow the app rather than the phone.
 */
@Composable
fun AppThemed(
    settings: AppSettingsRepository,
    content: @Composable () -> Unit,
) {
    val mode by remember(settings) {
        settings.observeSettings().map { it.themeMode }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)

    val darkTheme = mode.resolvesToDark(systemIsDark = isSystemInDarkTheme())

    val view = LocalView.current
    SideEffect {
        // A preview and a test have no window; only a real activity's bars are adjusted.
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }

    WhatsAppV2Theme(darkTheme = darkTheme, content = content)
}
