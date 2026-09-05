package com.whatsappv2.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.di.ApplicationScope
import com.whatsappv2.domain.engine.SipCallController
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.MediaProfile
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The notification's buttons (Task 37).
 *
 * A receiver rather than an activity, because answering from the lock screen must not
 * require unlocking the device — an activity would prompt for the keyguard first, and a
 * call that needs a PIN before it can be declined is a call that gets missed.
 *
 * ## Why the work outlives the broadcast
 *
 * `onReceive` runs on the main thread and must return quickly, while answering a call
 * suspends. `goAsync` keeps the process alive until the engine has been told; without it
 * the platform is free to kill the process mid-answer, and the caller hears the ringing
 * stop for no reason.
 */
@AndroidEntryPoint
class CallActionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var calls: SipCallController

    @Inject
    lateinit var logger: Logger

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        // No super.onReceive here, and injection still happens. Hilt's Gradle plugin
        // rewrites this class to extend its generated base and inserts the call to that
        // base's onReceive — which is what injects the fields above — at the top of this
        // method (`AndroidEntryPointClassVisitor.OnReceiveAdapter`). Written by hand it
        // does not even compile: the superclass Kotlin sees is `BroadcastReceiver`, whose
        // `onReceive` is abstract, and the rewrite happens after Kotlin has finished.

        val callId = intent.getStringExtra(EXTRA_CALL_ID)?.let(::CallId) ?: run {
            logger.error(TAG, "Call action with no call id: ${intent.action}")
            return
        }

        val pending = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    // Audio, because a notification button has no way to say "with
                    // video": a video answer is an explicit choice on the call screen.
                    ACTION_ANSWER -> {
                        calls.answer(callId, MediaProfile.AUDIO)
                        // The call is answered either way; this only brings the screen up.
                        context.startActivity(CallActivity.intentFor(context, callId))
                    }

                    // Declined, not busy: the user was there and said no, and the caller
                    // hears the difference (603 rather than 486).
                    ACTION_REJECT -> calls.reject(callId, HangupReason.LOCAL_REJECTED)

                    ACTION_HANGUP -> calls.hangup(callId, HangupReason.LOCAL_HANGUP)

                    else -> logger.warn(TAG, "Unknown call action: ${intent.action}")
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "CallActionReceiver"

        const val EXTRA_CALL_ID = "com.whatsappv2.call.CALL_ID"
        const val ACTION_ANSWER = "com.whatsappv2.call.ANSWER"
        const val ACTION_REJECT = "com.whatsappv2.call.REJECT"
        const val ACTION_HANGUP = "com.whatsappv2.call.HANGUP"
    }
}
