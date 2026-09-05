package com.whatsappv2.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.whatsappv2.R
import com.whatsappv2.call.CallNotification
import com.whatsappv2.call.CallNotificationPolicy
import com.whatsappv2.call.CallNotifications
import com.whatsappv2.call.Ringer
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.domain.engine.SipCallController
import com.whatsappv2.domain.engine.SipRegistrar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps SIP registration alive while the app is backgrounded.
 *
 * ## Why it stops itself
 *
 * §6 requires it: a foreground service that outlives its purpose is a battery bug, and a
 * persistent notification with nothing behind it teaches people to dismiss this app's
 * notifications. [ServiceRunPolicy] owns that decision so the rule can be asserted
 * directly rather than inferred from `dumpsys` output, and this class only applies it.
 *
 * ## Service type
 *
 * Two types, matching what the service is actually doing at the time:
 *
 * - `specialUse` while only holding a registration. That is not a phone call, a data
 *   sync, or any other standard type, and declaring it as one would be a false claim
 *   about the app's behaviour.
 * - `phoneCall` while a call is in progress, which Android 14+ also gates on
 *   `MANAGE_OWN_CALLS`.
 *
 * ## It also renders the call
 *
 * One foreground notification, not two. When a call exists the notification **is** the
 * call — a `CallStyle` notification with answer and decline, or with hang up (Task 37) —
 * and it goes back to the registration summary when the call ends. Posting a second
 * notification beside this one would leave the user looking at "1 active call" and a
 * ringing card that disagree, and would leave the `phoneCall` foreground type attached to
 * the wrong one.
 *
 * ## Failing to start
 *
 * Android 12+ refuses to start a foreground service from the background in many
 * situations, and 14+ adds per-type permission checks. Both throw. They are caught and
 * logged rather than allowed to crash: the app can still function without the service,
 * and taking the process down loses any call already in progress.
 */
@AndroidEntryPoint
class RegistrationService : Service() {

    @Inject
    lateinit var registrar: SipRegistrar

    @Inject
    lateinit var calls: SipCallController

    @Inject
    lateinit var logger: Logger

    @Inject
    lateinit var callNotifications: CallNotifications

    @Inject
    lateinit var ringer: Ringer

    private val scope = CoroutineScope(SupervisorJob())
    private var isForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()

        callNotifications.createChannel()

        scope.launch {
            combine(registrar.registrationState, calls.activeCalls) { registrations, active ->
                Presentation(
                    decision = ServiceRunPolicy.decide(registrations, active.size),
                    summary = RegistrationSummaryFactory.summarise(registrations, active.size),
                    call = CallNotificationPolicy.decide(active),
                )
            }
                // Rebuilding an identical notification wakes the UI thread for nothing,
                // and registration state churns during a retry storm.
                .distinctUntilChanged()
                .collect(::render)
        }
    }

    /** Everything the service shows at one instant, derived once so nothing disagrees. */
    private data class Presentation(
        val decision: ServiceDecision,
        val summary: RegistrationSummary,
        val call: CallNotification,
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_NOT_STICKY: if the process is killed, the app decides whether to register
        // again on next launch. Restarting a bare service with no state would put a
        // notification on screen with nothing behind it.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // A ringtone that outlives the service is a ringtone with nothing to answer.
        ringer.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun render(presentation: Presentation) {
        // Ringing is driven from the same place as the notification, so a call can never
        // be ringing without a card to answer it on, or be answered and still ringing.
        when (presentation.call) {
            is CallNotification.Incoming -> ringer.start()
            else -> ringer.stop()
        }

        when (val decision = presentation.decision) {
            is ServiceDecision.Stop -> stopSelfSafely()
            is ServiceDecision.Run -> startOrUpdate(decision.reason, presentation)
        }
    }

    private fun startOrUpdate(reason: ServiceReason, presentation: Presentation) {
        val notification = notificationFor(presentation)

        if (isForeground) {
            notificationManager().notify(NOTIFICATION_ID, notification)
            return
        }

        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, reason.serviceType())
            isForeground = true
        } catch (e: IllegalStateException) {
            // Android 12+ background-start restriction.
            logger.error(TAG, "Foreground start refused: ${e.javaClass.simpleName}")
        } catch (e: SecurityException) {
            // Android 14+ missing the per-type permission.
            logger.error(TAG, "Foreground type not permitted: ${e.javaClass.simpleName}")
        }
    }

    /**
     * The call, if there is one, and the registration summary otherwise.
     *
     * The choice is [CallNotificationPolicy]'s, which is a pure function and is tested as
     * one; this only builds what it chose.
     */
    private fun notificationFor(presentation: Presentation): Notification =
        when (val call = presentation.call) {
            is CallNotification.Incoming -> callNotifications.buildIncoming(call.call)
            is CallNotification.Ongoing -> callNotifications.buildOngoing(call.call)
            is CallNotification.None -> buildNotification(presentation.summary)
        }

    private fun stopSelfSafely() {
        if (isForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    private fun buildNotification(summary: RegistrationSummary): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(summary.title)
            .setContentText(summary.text)
            .setSmallIcon(R.drawable.ic_notification_call)
            .setOngoing(true)
            // Silent by default: a registration notification that makes a sound every
            // time the network changes is one the user turns off, taking the call
            // notifications with it.
            .setSilent(!summary.needsAttention)
            .setPriority(
                if (summary.needsAttention) {
                    NotificationCompat.PRIORITY_DEFAULT
                } else {
                    NotificationCompat.PRIORITY_LOW
                },
            )
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "SIP registration",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows whether your accounts are registered and able to receive calls."
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ServiceReason.serviceType(): Int = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> 0
        this == ServiceReason.ACTIVE_CALL -> ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        // specialUse did not exist before 14; phoneCall is the closest honest type there.
        else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
    }

    companion object {
        private const val TAG = "RegistrationService"
        private const val CHANNEL_ID = "sip-registration"
        private const val NOTIFICATION_ID = 1

        /** Starts the service. Safe to call when it is already running. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, RegistrationService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RegistrationService::class.java))
        }
    }
}
