package com.whatsappv2

import android.content.Context
import android.content.Intent
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
import com.whatsappv2.ui.navigation.AppDestination
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

    /**
     * A screen another activity asked this one to open, until it has been opened.
     *
     * The call screen's "Add call" is the only sender today. It runs in its own task, so
     * it cannot navigate this graph — it can only start this activity with a request, and
     * `singleTop` (the manifest) means that request arrives at [onNewIntent] rather than
     * at a second `MainActivity`. State, because it can land while the app is already up
     * and already on another screen.
     */
    private var openDestination by mutableStateOf<AppDestination?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        logger.debug(TAG, "MainActivity created")
        openDestination = intent?.requestedDestination()

        setContent {
            AppThemed(settings, statusBarOverHeader = true) {
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
                        AppRoot(
                            videoGate = rememberCameraGate(),
                            openDestination = openDestination,
                            onDestinationOpened = { openDestination = null },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.requestedDestination()?.let { openDestination = it }
    }

    private fun Intent.requestedDestination(): AppDestination? =
        getStringExtra(EXTRA_OPEN_ROUTE)?.let(AppDestination::fromRoute)

    companion object {
        private const val TAG = "MainActivity"

        private const val EXTRA_OPEN_ROUTE = "com.whatsappv2.OPEN_ROUTE"

        /**
         * An intent that brings the app forward on [destination].
         *
         * `SINGLE_TOP` alongside `NEW_TASK` so the flag is right whether or not the
         * manifest's launch mode is: the two together are what turn "start the app" into
         * "bring the app back and tell it where to go", which is what a user pressing Add
         * call during a live call is asking for.
         */
        fun intentFor(context: Context, destination: AppDestination): Intent =
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_OPEN_ROUTE, destination.route)
            }
    }
}
