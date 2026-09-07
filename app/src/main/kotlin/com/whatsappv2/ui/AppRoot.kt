package com.whatsappv2.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews
import com.whatsappv2.ui.navigation.AppNavHost

/**
 * The app shell: a single navigation host.
 *
 * ## There is no bottom bar any more (Tasks 69, 70)
 *
 * There was one, over this same host. Settings and Accounts moved behind the Calls
 * screen's top-right icon and the Dialer became a floating button on it, which left the
 * bar with one destination — and a bar that cannot navigate anywhere is decoration that
 * costs a permanent strip of a phone screen.
 *
 * With the bar gone, the shell has nothing left to draw. Each destination owns its own
 * `Scaffold` and therefore its own insets, which is why this no longer wraps one: a
 * `Scaffold` here would consume the insets and leave every screen inside it padding
 * against bars that had already been accounted for.
 *
 * The back stack still survives rotation, because `rememberNavController` saves it.
 */
@Composable
fun AppRoot(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    AppNavHost(
        navController = navController,
        modifier = modifier.fillMaxSize(),
    )
}

@ThemePreviews
@Composable
private fun AppRootPreview() = PreviewSurface { AppRoot() }
