package com.whatsappv2.call

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.whatsappv2.MainActivity
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.repository.AppSettingsRepository
import com.whatsappv2.feature.calls.CallRoute
import com.whatsappv2.ui.navigation.AppDestination
import com.whatsappv2.ui.theme.AppThemed
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

    /** For the theme, so the call screen matches the app behind it (item 5.2). */
    @Inject
    lateinit var settings: AppSettingsRepository

    /**
     * The call on screen, as state because it can change without this activity restarting.
     *
     * `launchMode="singleTask"` (the manifest) means a second call does not get a second
     * activity: the platform hands the new id to [onNewIntent] and `onCreate` never runs
     * again. Reading the intent once into a local therefore pinned the screen to the first
     * call for the life of the task. Place a first call, then dial a second from the
     * dialler, and the screen stayed on the first — now held, showing "Resume call" —
     * under a banner naming the *new* call as the held one, which was the opposite of the
     * truth (TC15 24143524701316, 2026-09-12 12:01).
     *
     * [CallRoute] was already built for this: it re-points on every change of its `callId`
     * argument. Nothing below it needed fixing — only the argument had to be allowed to
     * move.
     */
    @VisibleForTesting
    internal var watchedCall by mutableStateOf<CallId?>(null)
        private set

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
        val callId = ongoingCall.current(intent?.callId())
        if (callId == null) {
            // Nothing to show. Finishing is the honest response: an empty call screen
            // that cannot be dismissed is worse than no screen at all.
            logger.info(TAG, "Call screen opened with no call in progress")
            finish()
            return
        }
        watchedCall = callId

        setContent {
            AppThemed(settings) {
                watchedCall?.let { current ->
                    CallRoute(
                        callId = current,
                        // The activity's whole lifetime is this call. When the FSM says the
                        // call is over, the screen goes with it rather than lingering on a
                        // terminated call the user has to dismiss.
                        onCallFinished = { finish() },
                        // Not finish(): the call carries on while the user dials the second
                        // leg, and this screen is what they come back to when it connects.
                        onAddCall = { startActivity(MainActivity.intentFor(this, AppDestination.DIALER)) },
                    )
                }
            }
        }
    }

    /**
     * Re-points the screen at the call a fresh intent names.
     *
     * `OngoingCall.next` rather than `current` — it carries the reason, which is that a
     * just-built intent is better evidence than the engine's list, whose newest member has
     * not landed yet.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val requested = ongoingCall.next(intent.callId()) ?: return
        if (requested == watchedCall) return
        logger.info(TAG, "Call screen re-pointed at $requested")
        watchedCall = requested
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

    /** The call this intent names, if any. */
    private fun Intent.callId(): CallId? = getStringExtra(EXTRA_CALL_ID)?.let(::CallId)

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
