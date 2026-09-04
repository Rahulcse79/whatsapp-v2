package com.whatsappv2

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.whatsappv2.core.common.logging.Logger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The single Activity that hosts every screen.
 *
 * Empty for now: navigation and the Compose host arrive in Task 15, on top of the
 * design system from Task 14. Its only job here is to prove the Hilt graph reaches an
 * Activity.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var logger: Logger

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logger.debug(TAG, "MainActivity created")
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
