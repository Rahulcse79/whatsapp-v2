package com.whatsappv2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.domain.repository.AppSettingsRepository
import com.whatsappv2.permission.LocalPermissionCoordinator
import com.whatsappv2.permission.PermissionCoordinator
import com.whatsappv2.permission.PermissionOnboarding
import com.whatsappv2.permission.rememberCameraGate
import com.whatsappv2.ui.AppRoot
import com.whatsappv2.ui.theme.AppThemed
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
 *
 * ## The first-run gate (Task 72)
 *
 * The permission screen is decided here rather than inside [AppRoot], because this is the
 * one place with the injected [PermissionCoordinator] — which keeps `AppRoot` renderable
 * from a test and a preview with no permission state at all. The flag is read once into
 * `rememberSaveable`, so finishing the screen moves on without a second read and a
 * rotation mid-flow does not restart it.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var logger: Logger

    @Inject
    lateinit var permissionCoordinator: PermissionCoordinator

    /** Read for the theme only. Every other setting is a screen's business, not the host's. */
    @Inject
    lateinit var settings: AppSettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        logger.debug(TAG, "MainActivity created")

        setContent {
            AppThemed(settings) {
                // Provided here rather than passed down: a permission request happens
                // deep inside a screen, and threading the coordinator through every
                // composable in between would couple them all to it.
                CompositionLocalProvider(
                    LocalPermissionCoordinator provides permissionCoordinator,
                ) {
                    var onboarding by rememberSaveable {
                        mutableStateOf(permissionCoordinator.needsOnboarding())
                    }

                    if (onboarding) {
                        PermissionOnboarding(
                            coordinator = permissionCoordinator,
                            onFinished = {
                                permissionCoordinator.markOnboardingComplete()
                                onboarding = false
                            },
                        )
                    } else {
                        // The camera is asked for when a video call is pressed, not only on
                        // the first-run screen somebody may have skipped (Task 74).
                        AppRoot(videoGate = rememberCameraGate())
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
