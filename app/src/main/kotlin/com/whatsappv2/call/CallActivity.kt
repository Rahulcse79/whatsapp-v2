package com.whatsappv2.call

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.core.designsystem.theme.WhatsAppV2Theme
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.feature.calls.CallRoute
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The call screen, including over the lock screen (Tasks 37 and 39).
 *
 * ## Why a second activity
 *
 * Because a full-screen intent needs one, and because a call must be answerable without
 * unlocking. `MainActivity` is the app; this is one call, launched by the platform, and
 * finishing when the call ends. Routing an incoming call through the main activity's back
 * stack would leave the app open on the dialler after every call.
 *
 * ## Showing over the keyguard
 *
 * `setShowWhenLocked` and `setTurnScreenOn` are the API 27+ way and the window flags are
 * the older one; both are applied because `minSdk` is 26. `requestDismissKeyguard` then
 * asks — asks, not forces — for a secure keyguard to be dismissed once the user acts, and
 * a device with a PIN will still demand it, which is correct: the call is answerable, the
 * rest of the phone is not.
 */
@AndroidEntryPoint
class CallActivity : ComponentActivity() {

    @Inject
    lateinit var logger: Logger

    override fun onCreate(savedInstanceState: Bundle?) {
        showOverLockScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val callId = intent?.getStringExtra(EXTRA_CALL_ID)?.let(::CallId)
        if (callId == null) {
            // Nothing to show. Finishing is the honest response: an empty call screen
            // that cannot be dismissed is worse than no screen at all.
            logger.error(TAG, "Call screen opened with no call id")
            finish()
            return
        }

        setContent {
            WhatsAppV2Theme {
                CallRoute(
                    callId = callId,
                    // The activity's whole lifetime is this call. When the FSM says the
                    // call is over, the screen goes with it rather than lingering on a
                    // terminated call the user has to dismiss.
                    onCallFinished = { finish() },
                )
            }
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
    }

    companion object {
        private const val TAG = "CallActivity"

        private const val EXTRA_CALL_ID = "com.whatsappv2.call.SCREEN_CALL_ID"

        /**
         * The intent that opens this call.
         *
         * `NEW_TASK` because the callers are a notification and a broadcast receiver,
         * neither of which has an activity task to start from. `CLEAR_TOP` so a second
         * open of the same call reuses the screen instead of stacking a second copy of it
         * behind the first.
         */
        fun intentFor(context: Context, callId: CallId): Intent =
            Intent(context, CallActivity::class.java).apply {
                putExtra(EXTRA_CALL_ID, callId.value)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
    }
}
