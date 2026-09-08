package com.whatsappv2.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.whatsappv2.R
import com.whatsappv2.call.CallNotification
import com.whatsappv2.call.CallNotificationPolicy
import com.whatsappv2.call.CallNotifications
import com.whatsappv2.call.Ringer
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.domain.engine.CameraAvailability
import com.whatsappv2.domain.engine.SipCallController
import com.whatsappv2.domain.engine.SipRegistrar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * Whatever the service is actually doing at the time, and no more —
 * [ForegroundServiceTypes] decides, and it is a pure function so every combination can be
 * enumerated in a test rather than discovered on one handset. `specialUse` while only
 * holding a registration, `phoneCall` during a call, plus `microphone` and `camera` when
 * those permissions are held and the call is using them (Task 51).
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
 *
 * Catching is not sufficient on its own, though — see [onStartCommand] and
 * [stopSelfSafely]. Once `startForegroundService` has been called, `startForeground` must
 * happen whatever the service then decides, and before anything stops the service, or the
 * platform kills the process.
 */
@AndroidEntryPoint
class RegistrationService : Service() {

    @Inject
    lateinit var registrar: SipRegistrar

    @Inject
    lateinit var calls: SipCallController

    @Inject
    lateinit var logger: Logger

    /**
     * Whether a camera may be used, asked at the moment the service goes foreground.
     *
     * Android 14 checks the `camera` service type against the permission then and there,
     * so a cached answer from app start is the wrong one after somebody revokes it in
     * Settings — and being wrong here throws (Task 51).
     */
    @Inject
    lateinit var camera: CameraAvailability

    @Inject
    lateinit var callNotifications: CallNotifications

    @Inject
    lateinit var ringer: Ringer

    /**
     * The main thread, deliberately.
     *
     * `CoroutineScope(SupervisorJob())` supplies `Dispatchers.Default`, which put [render]
     * on a background thread racing [onStartCommand] on the main one — see [stopSelfSafely]
     * for what that race cost. Serialising them also makes [isForeground] a field one
     * thread owns rather than two threads write.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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

    /**
     * Goes foreground **immediately**, before any decision is made.
     *
     * ## The crash this exists to stop
     *
     * `startForegroundService` is a promise: the service must call `startForeground`
     * within a few seconds or the platform kills the process with
     * `ForegroundServiceDidNotStartInTimeException`. This method used to do nothing but
     * return, and the only caller of `startForeground` was [render] — driven by a flow
     * collector started in [onCreate].
     *
     * That collector is asynchronous, and worse, its first answer is often
     * [ServiceDecision.Stop]: [ServiceRunPolicy] returns `Stop` when nothing is registered
     * and no call is up, which is exactly the state the app is in while somebody is
     * **adding their first account**. `Stop` routes to `stopSelfSafely`, `startForeground`
     * is never called at all, and five seconds later Android kills the app. On a handset
     * that read as "the app crashes when I register an extension", with
     * `startForegroundCount:0` in the ActivityManager log.
     *
     * ## Why the state can be read here
     *
     * `registrationState` and `activeCalls` are `StateFlow`s, so the current answer is
     * available synchronously. There is no need to wait for an emission to know what to
     * show — waiting was the bug.
     *
     * A `Stop` decision still goes foreground first and stops immediately after. That
     * looks redundant and is not: the promise was made by the caller of
     * `startForegroundService`, and it has to be kept even when the answer is "there is
     * nothing to do". Stopping without keeping it is the crash.
     *
     * START_NOT_STICKY: if the process is killed, the app decides whether to register
     * again on next launch. Restarting a bare service with no state would put a
     * notification on screen with nothing behind it.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val presentation = currentPresentation()

        startOrUpdate(presentation.decision.foregroundReason(), presentation)

        if (presentation.decision is ServiceDecision.Stop) stopSelfSafely()
        return START_NOT_STICKY
    }

    /** What the service should be showing right now, read straight from the state holders. */
    private fun currentPresentation(): Presentation {
        val registrations = registrar.registrationState.value
        val active = calls.activeCalls.value
        return Presentation(
            decision = ServiceRunPolicy.decide(registrations, active.size),
            summary = RegistrationSummaryFactory.summarise(registrations, active.size),
            call = CallNotificationPolicy.decide(active),
        )
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
        if (isForeground) {
            notificationManager().notify(NOTIFICATION_ID, notificationFor(presentation))
            return
        }

        enterForeground(reason, presentation)
    }

    /**
     * Goes foreground, and does not give up on the first refusal.
     *
     * ## Why giving up was worse than trying again
     *
     * This used to log the refusal and call `stopSelf`, reasoning that a service which
     * cannot go foreground should not pretend otherwise. The platform disagrees. Once
     * `startForegroundService` has been called, stopping without `startForeground` is
     * reported by ActivityManager as *"Bringing down service while still waiting for start
     * foreground"* and the process is killed there and then. The honest response was the
     * crash it was written to avoid.
     *
     * A refusal is therefore answered with the one type that cannot be refused for want of
     * a runtime permission — `specialUse`, which [ServiceReason.REGISTRATION] maps to. The
     * per-type checks Android 14 added are what make `phoneCall`, `microphone` and `camera`
     * throw, and none of them applies to merely holding a registration.
     *
     * If even that is refused the service stays up rather than stopping. It is not a
     * foreground service and the platform may yet kill it, but [ServiceLauncher] starts it
     * again on the next state change, and any `onStartCommand` that succeeds keeps the
     * promise and saves the process. Stopping would remove that chance.
     */
    private fun enterForeground(reason: ServiceReason, presentation: Presentation) {
        if (isForeground || goForeground(reason, presentation)) return

        if (reason != ServiceReason.REGISTRATION) {
            goForeground(ServiceReason.REGISTRATION, presentation)
        }
    }

    /** One attempt. `true` when the service is a foreground one afterwards. */
    private fun goForeground(reason: ServiceReason, presentation: Presentation): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notificationFor(presentation),
            serviceTypesFor(reason),
        )
        isForeground = true
        true
    } catch (e: IllegalStateException) {
        // Android 12+ background-start restriction.
        logger.error(TAG, "Foreground start refused: ${e.javaClass.simpleName}")
        false
    } catch (e: SecurityException) {
        // Android 14+ missing the per-type permission.
        logger.error(TAG, "Foreground type not permitted: ${e.javaClass.simpleName}")
        false
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

    /**
     * Stops the service, keeping the `startForegroundService` promise on the way out.
     *
     * ## The crash that outlived the last fix
     *
     * [onStartCommand] was made to go foreground before deciding anything, which fixed the
     * service being started and never calling `startForeground` at all. It did not fix
     * this, because `onStartCommand` is not the only path to `stopSelf`.
     *
     * [render] is driven by a collector launched in [onCreate], and `onCreate` and
     * `onStartCommand` arrive as two separate messages. The collector's first answer while
     * somebody is adding their first account is [ServiceDecision.Stop] — nothing is
     * registered yet — so it can reach here and stop the service in the gap **before
     * `onStartCommand` has run at all**. ActivityManager then logs *"Bringing down service
     * while still waiting for start foreground"* and kills the process immediately, with
     * `startForegroundCount:0`. It is a race, which is why a handset survived eleven of
     * these starts in a row and died on the twelfth.
     *
     * Going foreground here costs a notification posted and removed in the same breath.
     * That is the price of a promise somebody else made, and it is cheaper than the crash.
     */
    private fun stopSelfSafely() {
        if (!isForeground) {
            val presentation = currentPresentation()
            enterForeground(presentation.decision.foregroundReason(), presentation)
        }

        // Still not foreground means the promise could not be kept at all, and stopping
        // now is precisely the crash. Staying up costs nothing — there is no notification
        // and no wake lock behind a service that never became one — and it leaves the next
        // onStartCommand able to keep the promise instead. See [enterForeground].
        if (!isForeground) return

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        isForeground = false
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

    /**
     * The foreground types to declare, decided by [ForegroundServiceTypes] (Task 51).
     *
     * Everything variable is read here, at the moment of the call, and passed in: the
     * platform checks each type against a live permission, so an answer cached anywhere
     * else is an answer that can be stale by the time it is used.
     */
    private fun serviceTypesFor(reason: ServiceReason): Int = ForegroundServiceTypes.of(
        reason = reason,
        microphoneGranted = ContextCompat.checkSelfPermission(this, RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED,
        cameraGranted = camera.isCameraUsable(),
        // Any call sending video is enough: the type describes what the service is doing,
        // and one video call among several is still a service using the camera.
        videoActive = calls.activeCalls.value.any { it.media.hasVideo },
    )

    companion object {
        private const val TAG = "RegistrationService"
        private const val RECORD_AUDIO = android.Manifest.permission.RECORD_AUDIO
        private const val CHANNEL_ID = "sip-registration"
        private const val NOTIFICATION_ID = 1

        /**
         * Starts the service. Safe to call when it is already running — and safe to call
         * from the background, which is the part that was not true.
         *
         * `startForegroundService` throws `ForegroundServiceStartNotAllowedException` in
         * the **caller's** process when Android 12+ will not permit a background start.
         * `ServiceLauncher` wrapped its own call; `SipMessagingService` did not, and that
         * is the push path, which is background by definition. Guarding here covers every
         * caller rather than the ones somebody remembered.
         *
         * A refusal is not an error to escalate: it means the app may not hold a
         * registration right now, which is the platform's decision to make. The next
         * foreground moment starts it.
         */
        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, RegistrationService::class.java))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RegistrationService::class.java))
        }
    }
}
