package com.whatsappv2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.core.designsystem.theme.WhatsAppV2Theme
import com.whatsappv2.permission.LocalPermissionCoordinator
import com.whatsappv2.permission.PermissionCoordinator
import com.whatsappv2.ui.AppRoot
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The single Activity that hosts every screen.
 *
 * [enableEdgeToEdge] is called before `setContent` so the app draws behind the system
 * bars from the first frame. Insets are then consumed by `Scaffold`, never by hardcoded
 * padding: a fixed status-bar height is wrong on every device with a cutout, and wrong
 * again the moment a keyboard appears.
 *
 * Navigation and the real screens arrive in Task 15; this hosts the placeholder that
 * proves the theme, insets and design system work on a device.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var logger: Logger

    @Inject
    lateinit var permissionCoordinator: PermissionCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        logger.debug(TAG, "MainActivity created")

        setContent {
            WhatsAppV2Theme {
                // Provided here rather than passed down: a permission request happens
                // deep inside a screen, and threading the coordinator through every
                // composable in between would couple them all to it.
                CompositionLocalProvider(
                    LocalPermissionCoordinator provides permissionCoordinator,
                ) {
                    AppRoot()
                }
            }
        }
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
