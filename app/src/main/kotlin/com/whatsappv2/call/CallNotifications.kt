package com.whatsappv2.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.whatsappv2.R
import com.whatsappv2.domain.engine.CallSnapshot
import com.whatsappv2.domain.model.CallId
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The call notifications, as `CallStyle` (Task 37, DoD 7).
 *
 * ## Why `CallStyle` and not a custom layout
 *
 * A custom layout is the **rejected design** (§3). `CallStyle` is what makes the platform
 * treat this as a call: it ranks above other notifications, it is the shape Android Auto
 * and Wear know how to render, and it is what a lock screen shows with real answer and
 * decline buttons. A hand-drawn `RemoteViews` gets none of that and looks wrong on every
 * device it was not tested on.
 *
 * ## The channel is silent, deliberately
 *
 * [Ringer] plays the ringtone, because Telecom does not ring for self-managed calls and
 * the app therefore owns the sound. If the channel also carried a ringtone the user would
 * hear two, and the channel's would keep playing after the call was answered.
 */
@Singleton
class CallNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Creates the call channel.
     *
     * `IMPORTANCE_HIGH` so the notification can present as a heads-up and carry a
     * full-screen intent, and `CATEGORY_CALL` on each notification so the platform ranks
     * it as one. Neither overrides Do Not Disturb — that stays the user's decision, which
     * is why [RingerPolicy] exists rather than a `setBypassDnd(true)`.
     */
    fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Calls",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Incoming and ongoing calls."
            // Silent: the app rings for itself, and two ringtones is worse than none.
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        notificationManager().createNotificationChannel(channel)
    }

    /**
     * A ringing call, answerable from the lock screen.
     *
     * The full-screen intent is what turns a heads-up into the full-screen answer UI on a
     * locked device. It is requested rather than assumed: from Android 14 the permission
     * is granted only to calling and alarm apps, and a device that withholds it still
     * shows the heads-up notification, which stays answerable.
     */
    fun buildIncoming(call: CallSnapshot): Notification =
        base(call)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenIntent(call.callId), true)
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    callerOf(call),
                    action(CallActionReceiver.ACTION_REJECT, call.callId),
                    action(CallActionReceiver.ACTION_ANSWER, call.callId),
                ),
            )
            .build()

    /** A call in progress, with the one action that matters: end it. */
    fun buildOngoing(call: CallSnapshot): Notification =
        base(call)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setStyle(
                NotificationCompat.CallStyle.forOngoingCall(
                    callerOf(call),
                    action(CallActionReceiver.ACTION_HANGUP, call.callId),
                ),
            )
            // Shown when the user taps the notification body rather than a button.
            .setContentIntent(fullScreenIntent(call.callId))
            .build()

    private fun base(call: CallSnapshot) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_call)
            .setOngoing(true)
            // The app rings; the notification does not.
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentTitle(displayNameOf(call))

    /**
     * Who is calling, as the platform's own person type.
     *
     * The display name is what the peer asserted and is not trusted for anything but
     * display — callers set their own. The address is shown when there is no name, because
     * "Unknown" tells the user less than the number does.
     */
    private fun callerOf(call: CallSnapshot): Person =
        Person.Builder()
            .setName(displayNameOf(call))
            .setImportant(true)
            .build()

    private fun displayNameOf(call: CallSnapshot): String =
        call.remoteDisplayName?.takeIf { it.isNotBlank() }
            ?: call.remote.user
            ?: call.remote.host.rendered

    private fun action(action: String, callId: CallId): PendingIntent {
        val intent = Intent(context, CallActionReceiver::class.java).apply {
            this.action = action
            putExtra(CallActionReceiver.EXTRA_CALL_ID, callId.value)
        }
        return PendingIntent.getBroadcast(
            context,
            // Distinct per action and per call, so answering one call cannot reuse the
            // intent that was built to decline another.
            (action + callId.value).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun fullScreenIntent(callId: CallId): PendingIntent =
        PendingIntent.getActivity(
            context,
            callId.value.hashCode(),
            CallActivity.intentFor(context, callId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun notificationManager() =
        context.getSystemService(NotificationManager::class.java)

    companion object {
        /**
         * The call channel.
         *
         * Separate from the registration channel so the two can be silenced
         * independently: someone who turns off the persistent "registered" notification
         * must not thereby turn off their incoming calls.
         */
        const val CHANNEL_ID = "sip-calls"
    }
}
