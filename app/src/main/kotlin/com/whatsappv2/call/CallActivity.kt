package com.whatsappv2.call

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
 * the older one; both are applied because `minSdk` is 26. Between them the call is
 * answerable without unlocking, and the rest of the phone stays locked — which is the
 * whole of what this screen needs.
 *
 * **`requestDismissKeyguard` is deliberately not called**, though it used to be. It leaks
 * this activity, every time. `KeyguardManager.requestDismissKeyguard` hands system_server
 * an anonymous `IKeyguardDismissCallback.Stub` whose generated `val$activity` field holds
 * the activity — it captures it whether or not a callback is passed, because the stub's
 * own methods reach for `activity.isDestroyed()` and `activity.mHandler`. That binder
 * object is rooted in native code and nothing releases it, so the activity survives its
 * own `onDestroy`. LeakCanary caught it on a handset: two `CallActivity` instances
 * retained, 213 kB each, one per call that had rung.
 *
 * Nothing is lost by dropping it. It only ever *asked* for a secure keyguard to be
 * dismissed, and a device with a PIN refused anyway; showing over the lock screen — the
 * part that matters — is `setShowWhenLocked`'s job and it still does it.
 */
@AndroidEntryPoint
class CallActivity : ComponentActivity() {

    @Inject
    lateinit var logger: Logger

    @Inject
    lateinit var ongoingCall: OngoingCall

    override fun onCreate(savedInstanceState: Bundle?) {
        showOverLockScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // The intent is a request, not the answer (Task 45). After the process was killed
        // the system hands the original intent back, and the call it names may be long
        // over; asking Telecom and the engine is what makes this a screen for a call that
        // is actually up rather than one that was.
        //
        // Deliberately not savedInstanceState: that is the app's memory of what was true,
        // and what was true is exactly what a restart cannot rely on.
        val requested = intent?.getStringExtra(EXTRA_CALL_ID)?.let(::CallId)
        val callId = ongoingCall.current(requested)
        if (callId == null) {
            // Nothing to show. Finishing is the honest response: an empty call screen
            // that cannot be dismissed is worse than no screen at all.
            logger.info(TAG, "Call screen opened with no call in progress")
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
