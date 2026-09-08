package com.whatsappv2.data.sip.registration.stack

import android.content.Context
import android.hardware.camera2.CameraManager
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.data.sip.call.SipCallGateway
import com.whatsappv2.data.sip.call.SipRecordingGateway
import com.whatsappv2.data.sip.call.SipVideoGateway
import com.whatsappv2.data.sip.call.StackCallEvent
import com.whatsappv2.data.sip.call.StackCallState
import com.whatsappv2.data.sip.call.StackConferenceEvent
import com.whatsappv2.data.sip.call.StackTransferEvent
import com.whatsappv2.data.sip.registration.SipCoreGateway
import com.whatsappv2.data.sip.registration.StackAccount
import com.whatsappv2.data.sip.registration.StackMediaEncryption
import com.whatsappv2.data.sip.registration.StackPushParameters
import com.whatsappv2.data.sip.registration.StackRegistrationEvent
import com.whatsappv2.data.sip.registration.StackRegistrationState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.pjsip.PjCameraInfo2
import org.pjsip.pjsua2.Account
import org.pjsip.pjsua2.AccountConfig
import org.pjsip.pjsua2.AudioMedia
import org.pjsip.pjsua2.AudioMediaRecorder
import org.pjsip.pjsua2.AuthCredInfo
import org.pjsip.pjsua2.Call
import org.pjsip.pjsua2.CallInfo
import org.pjsip.pjsua2.CallOpParam
import org.pjsip.pjsua2.CallSendDtmfParam
import org.pjsip.pjsua2.CallSetting
import org.pjsip.pjsua2.CallVidSetStreamParam
import org.pjsip.pjsua2.Endpoint
import org.pjsip.pjsua2.EpConfig
import org.pjsip.pjsua2.OnCallMediaStateParam
import org.pjsip.pjsua2.OnCallRxReinviteParam
import org.pjsip.pjsua2.OnCallStateParam
import org.pjsip.pjsua2.OnCallTransferStatusParam
import org.pjsip.pjsua2.OnIncomingCallParam
import org.pjsip.pjsua2.OnRegStateParam
import org.pjsip.pjsua2.TransportConfig
import org.pjsip.pjsua2.VideoWindowHandle
import org.pjsip.pjsua2.pjmedia_dir
import org.pjsip.pjsua2.pjmedia_srtp_use
import org.pjsip.pjsua2.pjmedia_type
import org.pjsip.pjsua2.pjsip_inv_state
import org.pjsip.pjsua2.pjsip_status_code
import org.pjsip.pjsua2.pjsip_transport_type_e
import org.pjsip.pjsua2.pjsua_call_flag
import org.pjsip.pjsua2.pjsua_call_media_status
import org.pjsip.pjsua2.pjsua_call_vid_strm_op
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real SIP stack, behind the gateway seam (ADR-006).
 *
 * **This is the only class in the project that touches PJSIP.** Everything above it works
 * in this module's own types, which is what keeps DoD 3 ("no SDK import outside
 * `:data:sip`") true and what lets the engine be tested on the JVM at all. The stack
 * behind it changed without the four interfaces below changing at all, which was the
 * entire point of building the seam in ADR-001.
 *
 * ## Threading — one thread, and it is not negotiable
 *
 * pjsua2 requires every thread that calls into it to be registered first, and
 * `Endpoint::libRegisterThread` allocates a thread descriptor **that is only freed when
 * the library is destroyed**. The engine calls this gateway from `Dispatchers.IO`, which
 * is a pool of up to 64 threads that come and go; registering from there would leak a
 * descriptor per thread for the life of the process, and calling without registering is
 * undefined behaviour that surfaces as a native crash rather than an exception.
 *
 * So every call into PJSIP is posted to [pjsip], a single-threaded executor whose one
 * thread is registered once at [start]. A single thread also makes ordering free: `start`
 * then `addAccount` cannot race, because the executor runs them in the order they were
 * submitted.
 *
 * Callbacks arrive on **pjsua2's own worker threads**, which the library registers itself.
 * Those may call back into the library — [PjCall.onCallMediaState] does — but they must
 * never block, so they publish to buffered flows rather than doing work inline.
 *
 * ## Director objects must outlive their native peers
 *
 * [PjAccount] and [PjCall] are SWIG directors: each has a native counterpart holding a
 * reference back. If Kotlin collects one while PJSIP still has the pointer, the next
 * callback dereferences freed memory. The official sample's own comment on this is
 * "Maintain reference to avoid auto garbage collecting". [accounts] and [calls] are what
 * hold them; a call is only dropped once `onCallState` reports `DISCONNECTED`.
 */
@Singleton
internal class RealPjsipCoreGateway @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger,
) : SipCoreGateway, SipCallGateway, SipVideoGateway, SipRecordingGateway {

    private val events = MutableSharedFlow<StackRegistrationEvent>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val registrationEvents: Flow<StackRegistrationEvent> = events.asSharedFlow()

    private val callEventFlow = MutableSharedFlow<StackCallEvent>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val callEvents: Flow<StackCallEvent> = callEventFlow.asSharedFlow()

    private val transferEventFlow = MutableSharedFlow<StackTransferEvent>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val transferEvents: Flow<StackTransferEvent> = transferEventFlow.asSharedFlow()

    /**
     * Never emitted, and that is the honest state of it.
     *
     * The dial-in MCU (ADR-003) publishes no roster this client can read, and PJSIP has no
     * conference roster API of its own — a bridge leg is an ordinary call. The flow
     * exists because the contract has always carried it; there is nothing behind it.
     * Kept so the contract is unchanged, empty so nothing above shows a roster that
     * does not exist.
     */
    private val conferenceEventFlow = MutableSharedFlow<StackConferenceEvent>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val conferenceEvents: Flow<StackConferenceEvent> = conferenceEventFlow.asSharedFlow()

    /**
     * The one thread PJSIP is ever called from. See the class documentation.
     *
     * A daemon thread, so it cannot hold the process up if [stop] is never reached.
     */
    private val pjsip = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, PJSIP_THREAD).apply { isDaemon = true }
    }

    private var endpoint: Endpoint? = null

    /** Transport ids by the token the domain uses — `UDP`, `TCP`, `TLS`. */
    private val transports = ConcurrentHashMap<String, Int>()

    /** Our account key to the director holding its native peer. */
    private val accounts = ConcurrentHashMap<String, PjAccount>()

    /** Our call key to the director holding its native peer. */
    private val calls = ConcurrentHashMap<String, PjCall>()

    /** The last account config used, so a push-parameter change can re-apply it. */
    private val accountConfigs = ConcurrentHashMap<String, StackAccount>()

    /** Push parameters, applied to every account's `Contact` (RFC 8599, ADR-004). */
    @Volatile
    private var pushParameters: StackPushParameters? = null

    /** Surfaces the call screen handed over, applied when a video stream appears. */
    @Volatile
    private var remoteSurface: Any? = null

    @Volatile
    private var previewSurface: Any? = null

    /** Recorders by call key, so [stopRecording] can find and release the right one. */
    private val recorders = ConcurrentHashMap<String, AudioMediaRecorder>()

    // ------------------------------------------------------------------ lifecycle

    override fun start() {
        onPjsip("start") {
            if (endpoint != null) return@onPjsip

            // Without this there are no capture devices at all, and a video call
            // negotiates a stream it can never fill.
            PjCameraInfo2.SetCameraManager(context.getSystemService(CameraManager::class.java))

            val created = Endpoint()
            created.libCreate()
            // The one registration this process makes. Every later call into PJSIP is
            // posted to this same thread, so no second one is ever needed.
            created.libRegisterThread(PJSIP_THREAD)
            created.libInit(EpConfig())

            // All three, once, at startup. PJSIP binds an account to a transport by id,
            // so the transport an account needs has to exist before the account does —
            // and creating them lazily would mean the first TLS account paid for a
            // listener the UDP ones had already been running without.
            transports[TRANSPORT_UDP] =
                created.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, TransportConfig())
            transports[TRANSPORT_TCP] =
                created.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TCP, TransportConfig())
            transports[TRANSPORT_TLS] =
                created.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TLS, TransportConfig())

            created.libStart()
            endpoint = created
            logger.info(TAG, "SIP core started (PJSIP)")
        }
    }

    override fun stop() {
        onPjsip("stop") {
            val running = endpoint ?: return@onPjsip

            // Accounts first: each one unregisters and drops the credential PJSIP holds
            // for it, which is what Task 29 requires of a logout. libDestroy would take
            // them with it, but not cleanly and not with an `Expires: 0` on the wire.
            accounts.values.forEach { account ->
                runCatching { account.setRegistration(false) }
                runCatching { account.shutdown() }
            }
            accounts.clear()
            calls.clear()
            recorders.clear()
            transports.clear()

            running.libDestroy()
            endpoint = null
            logger.info(TAG, "SIP core stopped")
        }
    }

    // ------------------------------------------------------------------ accounts

    override fun addAccount(account: StackAccount) {
        onPjsip("addAccount") {
            val running = endpoint ?: run {
                logger.error(TAG, "addAccount before start")
                return@onPjsip
            }

            accountConfigs[account.key] = account
            val config = account.toAccountConfig()

            // Replaced in place rather than added again. Two PJSIP accounts for one
            // identity fight over the same registrar binding, and the loser's calls go to
            // a device that has stopped listening.
            val existing = accounts[account.key]
            if (existing != null) {
                existing.modify(config)
            } else {
                val created = PjAccount(account.key)
                created.create(config, accounts.isEmpty())
                accounts[account.key] = created
            }

            // Core-wide, and applied on every account add because the last account
            // configured is the one whose codec list wins. Recorded in
            // docs/security.md rather than hidden behind an API that looks per-account.
            running.applyCodecs(account)
        }
    }

    override fun removeAccount(accountKey: String) {
        onPjsip("removeAccount") {
            val account = accounts.remove(accountKey) ?: return@onPjsip
            accountConfigs -= accountKey
            // Unregister before shutdown so the registrar hears `Expires: 0` rather than
            // simply losing the binding when it lapses.
            runCatching { account.setRegistration(false) }
            account.shutdown()
        }
    }

    override fun refreshAccount(accountKey: String) {
        onPjsip("refreshAccount") {
            accounts[accountKey]?.setRegistration(true)
        }
    }

    /**
     * Publishes RFC 8599 parameters on every account's `Contact` (ADR-004, Task 38).
     *
     * PJSIP carries them as `regConfig.contactParams`, a raw parameter string appended to
     * the `Contact` header — so this assembles the `;pn-provider=…;pn-param=…;pn-prid=…`
     * string itself. Every account is re-registered, because a
     * `Contact` the server has not seen is a wake-up path it cannot use.
     */
    override fun setPushParameters(parameters: StackPushParameters?) {
        onPjsip("setPushParameters") {
            pushParameters = parameters
            accountConfigs.forEach { (key, stored) ->
                accounts[key]?.modify(stored.toAccountConfig())
            }
        }
    }

    private fun StackAccount.toAccountConfig(): AccountConfig = AccountConfig().apply {
        idUri = "sip:$username@$domain"

        regConfig.registrarUri = registrarUri
        regConfig.timeoutSec = expirySeconds.toLong()
        regConfig.registerOnAdd = registerEnabled
        pushParameters?.let { push ->
            regConfig.contactParams =
                ";pn-provider=${push.provider};pn-param=${push.param};pn-prid=${push.prid}"
        }

        sipConfig.authCreds.add(
            // Realm `*` because the registrar names its own realm in the challenge, and
            // pinning ours would fail every deployment that does not happen to match.
            // `0` is PJSIP's data type for a plaintext password rather than a digest.
            AuthCredInfo("Digest", "*", authUsername, 0, password),
        )
        proxyUri?.let { sipConfig.proxies.add(it) }
        transports[transport.uppercase()]?.let { sipConfig.transportId = it }

        natConfig.iceEnabled = true

        // Per account, and genuinely so. A core-wide setting would let the last
        // account added decide encryption for every other one, which is the
        // limitation docs/security.md used to record.
        mediaConfig.srtpUse = when (mediaEncryption) {
            StackMediaEncryption.NONE -> pjmedia_srtp_use.PJMEDIA_SRTP_DISABLED
            StackMediaEncryption.OPTIONAL -> pjmedia_srtp_use.PJMEDIA_SRTP_OPTIONAL
            StackMediaEncryption.MANDATORY -> pjmedia_srtp_use.PJMEDIA_SRTP_MANDATORY
        }
        mediaConfig.srtpSecureSignaling = if (mediaEncryption == StackMediaEncryption.MANDATORY) 1 else 0

        // Nothing automatic. `autoTransmitOutgoing` left on would add a camera stream to
        // every call somebody places, and the Task 54 escalation prompt exists precisely
        // because the far end asking for video is a question, not an instruction.
        videoConfig.autoShowIncoming = false
        videoConfig.autoTransmitOutgoing = false
    }

    /**
     * The account's codec preferences, as PJSIP priorities (§5.1, §5.2).
     *
     * PJSIP has no payload-type array to reorder. It has a priority per codec — `255`
     * highest, `0` disabled — so the list order becomes a descending priority and
     * everything the account did not name is switched off. An ordered list in, a set of
     * priorities out.
     *
     * Matching is by **prefix on the codec id**, because PJSIP spells them with a clock
     * rate: `opus/48000`, `PCMU/8000`. The domain stores `opus` and `PCMU`, so
     * `codecId.startsWith(name)` is the comparison, case-insensitively.
     */
    private fun Endpoint.applyCodecs(account: StackAccount) {
        applyPriorities(
            available = codecEnum2().map { it.codecId },
            preferred = account.audioCodecs,
        ) { id, priority -> codecSetPriority(id, priority) }

        applyPriorities(
            available = videoCodecEnum2().map { it.codecId },
            preferred = account.videoCodecs,
        ) { id, priority -> videoCodecSetPriority(id, priority) }

        logger.info(
            TAG,
            "Codecs for ${account.key}: audio=${account.audioCodecs} video=${account.videoCodecs}",
        )
    }

    private inline fun applyPriorities(
        available: List<String>,
        preferred: List<String>,
        set: (String, Short) -> Unit,
    ) {
        available.forEach { codecId ->
            val rank = preferred.indexOfFirst { codecId.startsWith(it, ignoreCase = true) }
            // Descending from the top so the first preference outranks the second, and
            // everything unnamed is disabled rather than left at whatever PJSIP chose.
            val priority = if (rank < 0) CODEC_DISABLED else (CODEC_TOP - rank).toShort()
            runCatching { set(codecId, priority) }
        }
    }

    // ------------------------------------------------------------------ calls

    override fun placeCall(
        callKey: String,
        accountKey: String,
        destination: String,
        videoEnabled: Boolean,
    ) {
        onPjsip("placeCall") {
            val account = accounts[accountKey] ?: run {
                logger.error(TAG, "placeCall for an account the stack does not have")
                return@onPjsip
            }
            val call = PjCall(callKey, account)
            calls[callKey] = call
            call.makeCall(destination, callParams(videoEnabled))
        }
    }

    override fun answerCall(callKey: String, videoEnabled: Boolean) {
        onPjsip("answerCall") {
            val call = calls[callKey] ?: return@onPjsip
            call.answer(callParams(videoEnabled).apply { statusCode = pjsip_status_code.PJSIP_SC_OK })
        }
    }

    override fun rejectCall(callKey: String, busy: Boolean) {
        onPjsip("rejectCall") {
            val call = calls[callKey] ?: return@onPjsip
            // 486 and 603 mean different things to the caller, and the distinction is the
            // user's: busy is the phone answering, decline is the person.
            call.answer(
                CallOpParam(true).apply {
                    statusCode = if (busy) {
                        pjsip_status_code.PJSIP_SC_BUSY_HERE
                    } else {
                        pjsip_status_code.PJSIP_SC_DECLINE
                    }
                },
            )
        }
    }

    override fun terminateCall(callKey: String) {
        onPjsip("terminateCall") {
            // Idempotent per the gateway contract: a call PJSIP has already released is
            // one the caller wanted gone.
            calls[callKey]?.let { runCatching { it.hangup(CallOpParam(true)) } }
        }
    }

    override fun pauseCall(callKey: String) {
        onPjsip("pauseCall") {
            calls[callKey]?.setHold(CallOpParam(true))
        }
    }

    override fun resumeCall(callKey: String) {
        onPjsip("resumeCall") {
            val call = calls[callKey] ?: return@onPjsip
            // PJSUA_CALL_UNHOLD is what makes this a resume rather than an ordinary
            // re-INVITE; without the flag the offer keeps the `sendonly` direction and
            // the far end stays held.
            call.reinvite(
                CallOpParam(true).apply { options = pjsua_call_flag.PJSUA_CALL_UNHOLD.toLong() },
            )
        }
    }

    override fun sendDtmf(callKey: String, digit: Char, useInfo: Boolean) {
        onPjsip("sendDtmf") {
            val call = calls[callKey] ?: return@onPjsip
            if (useInfo) {
                call.sendDtmf(
                    CallSendDtmfParam().apply {
                        digits = digit.toString()
                        method = DTMF_METHOD_SIP_INFO
                    },
                )
            } else {
                // RFC 4733, on the RTP stream, which is what `dialDtmf` sends.
                call.dialDtmf(digit.toString())
            }
            // The digit is never logged: a DTMF sequence is a PIN or a card number as
            // often as it is a menu choice (§7, DoD 12).
            logger.debug(TAG, "DTMF sent on $callKey (info=$useInfo)")
        }
    }

    /**
     * Mutes the microphone for one call (Tasks 40, 42).
     *
     * PJSIP has no mute flag. Audio flows because the capture device was explicitly
     * connected to the call's stream in [PjCall.onCallMediaState], so muting is
     * disconnecting exactly that leg and leaving the playback leg alone — the user still
     * hears the far end, and the far end hears nothing. Local only, with no signalling,
     * which is what distinguishes it from hold.
     */
    override fun setMicrophoneMuted(callKey: String, muted: Boolean) {
        onPjsip("setMicrophoneMuted") {
            val running = endpoint ?: return@onPjsip
            val media = calls[callKey]?.audioMedia ?: return@onPjsip
            val capture = running.audDevManager().captureDevMedia
            if (muted) capture.stopTransmit(media) else capture.startTransmit(media)
        }
    }

    override fun transferCall(callKey: String, destination: String) {
        onPjsip("transferCall") {
            calls[callKey]?.xfer(destination, CallOpParam(true))
        }
    }

    override fun transferCallToCall(callKey: String, consultationCallKey: String) {
        onPjsip("transferCallToCall") {
            val call = calls[callKey] ?: return@onPjsip
            val consultation = calls[consultationCallKey] ?: run {
                logger.warn(TAG, "Attended transfer with no consultation call")
                return@onPjsip
            }
            // `xferReplaces` rather than an address: the `Replaces` header names a dialog,
            // and an address cannot name one. That is the whole difference between an
            // attended transfer and a blind one to the same extension.
            call.xferReplaces(consultation, CallOpParam(true))
        }
    }

    // ------------------------------------------------------------------ video

    override fun setVideoEnabled(callKey: String, enabled: Boolean) {
        onPjsip("setVideoEnabled") {
            val call = calls[callKey] ?: return@onPjsip
            call.vidSetStream(
                if (enabled) {
                    pjsua_call_vid_strm_op.PJSUA_CALL_VID_STRM_ADD
                } else {
                    pjsua_call_vid_strm_op.PJSUA_CALL_VID_STRM_REMOVE
                },
                CallVidSetStreamParam(),
            )
        }
    }

    /**
     * Answers a re-INVITE that offered video (Task 54).
     *
     * The offer was held rather than answered: [PjCall.onCallRxReinvite] sets `isAsync`,
     * which tells PJSIP not to reply until this app does. Declining answers **without**
     * the video stream rather than refusing the re-INVITE — the audio call survives, which
     * is Task 54's second done-when, and a 488 would end the call outright on several
     * peers.
     */
    override fun respondToVideoUpdate(callKey: String, accept: Boolean) {
        onPjsip("respondToVideoUpdate") {
            val call = calls[callKey] ?: return@onPjsip
            if (!call.reinvitePending) return@onPjsip
            call.reinvitePending = false
            call.answer(callParams(accept).apply { statusCode = pjsip_status_code.PJSIP_SC_OK })
        }
    }

    /**
     * Points the encoder at the next camera (Task 53).
     *
     * `CHANGE_CAP_DEV` re-negotiates nothing: the stream keeps running and only its source
     * moves, so the far end sees the picture change rather than a gap. Cycling the device
     * list rather than looking for one called "front" — device names are vendor strings,
     * and on a phone with one camera cycling correctly does nothing.
     */
    override fun switchCamera(callKey: String) {
        onPjsip("switchCamera") {
            val running = endpoint ?: return@onPjsip
            val call = calls[callKey] ?: return@onPjsip

            val devices = (0 until running.vidDevManager().devCount.toInt()).toList()
            if (devices.size < 2) {
                logger.info(TAG, "Only one camera on this device; nothing to switch to")
                return@onPjsip
            }

            val current = call.captureDevice
            val next = devices[(devices.indexOf(current).coerceAtLeast(0) + 1) % devices.size]
            call.captureDevice = next
            call.vidSetStream(
                pjsua_call_vid_strm_op.PJSUA_CALL_VID_STRM_CHANGE_CAP_DEV,
                CallVidSetStreamParam().apply { capDev = next },
            )
        }
    }

    /**
     * Starts or stops sending captured video (Task 51).
     *
     * PJSIP has no core-wide capture switch; capture belongs to a call's video stream. So this is `START_TRANSMIT`/`STOP_TRANSMIT` applied to every
     * call that has one, which produces the same observable behaviour — the camera is
     * released when `CameraPolicy` says nobody should hold it.
     */
    override fun setCameraCapturing(capturing: Boolean) {
        onPjsip("setCameraCapturing") {
            val op = if (capturing) {
                pjsua_call_vid_strm_op.PJSUA_CALL_VID_STRM_START_TRANSMIT
            } else {
                pjsua_call_vid_strm_op.PJSUA_CALL_VID_STRM_STOP_TRANSMIT
            }
            calls.values.forEach { call ->
                runCatching { call.vidSetStream(op, CallVidSetStreamParam()) }
            }
        }
    }

    /**
     * Takes the surfaces the call screen draws into, or releases them with nulls (Task 52).
     *
     * PJSIP renders into an `android.view.Surface` handed to a `VideoWindow`, not into a
     * `TextureView` handed to the core — so these are stored and applied whenever a video
     * window appears, which is on the media-state callback rather than here. A surface
     * arriving before there is a stream to draw is the ordinary case, not an error.
     *
     * Nulls are the important half: a surface PJSIP keeps writing into after the screen
     * has gone is a crash on some devices and a leak of every frame on the rest.
     */
    override fun setVideoWindows(remoteView: Any?, localPreview: Any?) {
        remoteSurface = remoteView
        previewSurface = localPreview
        onPjsip("setVideoWindows") {
            calls.values.forEach { it.applyVideoWindows() }
        }
    }

    // ------------------------------------------------------------------ recording

    /**
     * Records this call's media to [filePath] (Task 58).
     *
     * PJSIP has no per-call record flag. A recorder is a media port, so both legs are
     * transmitted into it: the far end's stream and this device's capture. Nothing here
     * decides whether recording is allowed — `RecordingPolicy` has already answered that.
     */
    override fun startRecording(callKey: String, filePath: String) {
        onPjsip("startRecording") {
            val running = endpoint ?: return@onPjsip
            val media = calls[callKey]?.audioMedia ?: run {
                logger.warn(TAG, "Recording asked for a call with no audio stream")
                return@onPjsip
            }

            val recorder = AudioMediaRecorder()
            recorder.createRecorder(filePath)
            media.startTransmit(recorder)
            running.audDevManager().captureDevMedia.startTransmit(recorder)
            recorders[callKey] = recorder
        }
    }

    override fun stopRecording(callKey: String) {
        onPjsip("stopRecording") {
            val running = endpoint ?: return@onPjsip
            val recorder = recorders.remove(callKey) ?: return@onPjsip
            calls[callKey]?.audioMedia?.let { runCatching { it.stopTransmit(recorder) } }
            runCatching { running.audDevManager().captureDevMedia.stopTransmit(recorder) }
            // The port has to go, or the file stays open and the next recording appends
            // to a stream nobody is reading.
            recorder.delete()
        }
    }

    // ------------------------------------------------------------------ directors

    /** One configured identity, and the callbacks PJSIP raises for it. */
    private inner class PjAccount(val accountKey: String) : Account() {

        override fun onRegState(prm: OnRegStateParam) {
            val code = prm.code
            events.tryEmit(
                StackRegistrationEvent(
                    accountKey = accountKey,
                    state = registrationStateOf(code, prm.expiration),
                    // Null when the request never got an answer at all, which the mapper
                    // reads as a transport problem rather than a rejection.
                    statusCode = code.takeIf { it > 0 },
                    message = prm.reason,
                ),
            )
        }

        override fun onIncomingCall(prm: OnIncomingCallParam) {
            // The one call this gateway has never seen before, so the one place a key is
            // minted rather than looked up.
            val callKey = UUID.randomUUID().toString()
            val call = PjCall(callKey, this, prm.callId)
            calls[callKey] = call
            call.publish(StackCallState.INCOMING_RECEIVED)
        }
    }

    /** One call, and the callbacks PJSIP raises for it. */
    private inner class PjCall : Call {

        val callKey: String
        private val accountKey: String

        /** The negotiated audio stream, held so mute and recording can reach it. */
        @Volatile
        var audioMedia: AudioMedia? = null
            private set

        /** The capture device this call is using, for [switchCamera] to cycle from. */
        @Volatile
        var captureDevice: Int = CAPTURE_DEVICE_DEFAULT

        /** True while a re-INVITE is held awaiting the user's answer (Task 54). */
        @Volatile
        var reinvitePending: Boolean = false

        constructor(callKey: String, account: PjAccount) : super(account) {
            this.callKey = callKey
            this.accountKey = account.accountKey
        }

        constructor(callKey: String, account: PjAccount, callId: Int) : super(account, callId) {
            this.callKey = callKey
            this.accountKey = account.accountKey
        }

        override fun onCallState(prm: OnCallStateParam) {
            val info = infoOrNull() ?: return
            publish(callStateOf(info), info)

            if (info.state == pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED) {
                // Only now. The native peer is finished with this director, and holding it
                // any longer is the leak; releasing it any earlier is a crash.
                calls -= callKey
                recorders -= callKey
                audioMedia = null
            }
        }

        /**
         * Connects the media, which PJSIP does not do by itself.
         *
         * PJSIP does not wire the capture and playback devices to a running call. Without
         * these two `startTransmit` calls the call connects, the SDP is correct, RTP
         * flows — and both parties hear silence. It is
         * the single most likely cause of a working registration with a dead call.
         */
        override fun onCallMediaState(prm: OnCallMediaStateParam) {
            val running = endpoint ?: return
            val info = infoOrNull() ?: return

            info.media.forEachIndexed { index, media ->
                when {
                    media.type == pjmedia_type.PJMEDIA_TYPE_AUDIO && media.isLive -> {
                        runCatching {
                            val stream = getAudioMedia(index)
                            audioMedia = stream
                            running.audDevManager().captureDevMedia.startTransmit(stream)
                            stream.startTransmit(running.audDevManager().playbackDevMedia)
                        }.onFailure { logger.error(TAG, "Could not connect audio: ${it.message}") }
                    }

                    media.type == pjmedia_type.PJMEDIA_TYPE_VIDEO &&
                        media.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE -> {
                        captureDevice = media.videoCapDev
                        applyVideoWindows()
                    }
                }
            }
            publish(callStateOf(info), info)
        }

        /**
         * Holds the far end's re-INVITE until the user answers it (Task 54).
         *
         * `isAsync` is what defers the reply. Without it PJSIP answers immediately and the
         * first anybody knows about an escalation is their own camera light; with it the
         * call sits in this state until [respondToVideoUpdate] answers, which is what makes
         * the prompt §5.2 requires possible at all.
         */
        override fun onCallRxReinvite(prm: OnCallRxReinviteParam) {
            val info = infoOrNull() ?: return
            if (info.remVideoCount > 0) {
                prm.isAsync = true
                reinvitePending = true
                publish(StackCallState.UPDATED_BY_REMOTE, info)
            }
        }

        override fun onCallTransferStatus(prm: OnCallTransferStatusParam) {
            transferEventFlow.tryEmit(
                StackTransferEvent(
                    callKey = callKey,
                    state = if (prm.statusCode == SIP_OK) StackCallState.CONNECTED else StackCallState.ERROR,
                    statusCode = prm.statusCode,
                ),
            )
        }

        /** Attaches the stored surfaces to whatever video windows this call now has. */
        fun applyVideoWindows() {
            val info = infoOrNull() ?: return
            info.media.forEach { media ->
                if (media.type != pjmedia_type.PJMEDIA_TYPE_VIDEO) return@forEach
                if (media.status != pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE) return@forEach

                // Incoming video draws into the remote surface; an outgoing-only stream is
                // this device's own picture and draws into the preview.
                val surface = if (media.dir and pjmedia_dir.PJMEDIA_DIR_DECODING != 0) {
                    remoteSurface
                } else {
                    previewSurface
                }

                runCatching {
                    media.videoWindow.setWindow(
                        VideoWindowHandle().apply { handle.setWindow(surface) },
                    )
                }.onFailure { logger.warn(TAG, "Could not attach a video surface: ${it.message}") }
            }
        }

        fun publish(state: StackCallState, info: CallInfo? = infoOrNull()) {
            callEventFlow.tryEmit(
                StackCallEvent(
                    callKey = callKey,
                    accountKey = accountKey,
                    remoteUri = info?.remoteUri.orEmpty(),
                    remoteDisplayName = null,
                    state = state,
                    statusCode = info?.lastStatusCode?.takeIf { it > 0 },
                    message = info?.lastReason,
                    // What the peer offered, read from their side of the negotiation
                    // rather than ours: ours says what we would accept, not what was asked.
                    videoOffered = (info?.remVideoCount ?: 0) > 0,
                    videoActive = info?.media?.any {
                        it.type == pjmedia_type.PJMEDIA_TYPE_VIDEO &&
                            it.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE
                    } == true,
                    mediaEncrypted = false,
                ),
            )
        }

        /**
         * The call's info, or null once PJSIP has released it.
         *
         * `getInfo` throws on a call the library has finished with, and that happens on
         * the ordinary teardown path rather than exceptionally — a callback can arrive
         * fractionally after the native object goes.
         */
        private fun infoOrNull(): CallInfo? = runCatching { info }.getOrNull()
    }

    // ------------------------------------------------------------------ mapping

    /**
     * PJSIP's invite state as the app's, or null for one that maps to nothing.
     *
     * `CONNECTING` is deliberately absent: it is the moment between the 200 and the ACK,
     * and the app has nothing different to do during it.
     */
    private fun callStateOf(info: CallInfo): StackCallState = when (info.state) {
        pjsip_inv_state.PJSIP_INV_STATE_CALLING -> StackCallState.OUTGOING_INIT
        pjsip_inv_state.PJSIP_INV_STATE_INCOMING -> StackCallState.INCOMING_RECEIVED
        // 180 is ringing; 183 with SDP is early media, and the difference is audible.
        pjsip_inv_state.PJSIP_INV_STATE_EARLY ->
            if (info.lastStatusCode == SIP_PROGRESS) {
                StackCallState.OUTGOING_EARLY_MEDIA
            } else {
                StackCallState.OUTGOING_RINGING
            }

        pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED -> confirmedStateOf(info)
        pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED ->
            if (info.lastStatusCode >= SIP_ERROR_FLOOR) StackCallState.ERROR else StackCallState.ENDED

        else -> StackCallState.CONNECTED
    }

    /**
     * A confirmed call is running, held, or held by the far end, and only its media says
     * which. PJSIP reports hold per stream rather than per call, so the audio stream is
     * what is asked.
     */
    private fun confirmedStateOf(info: CallInfo): StackCallState {
        val audio = info.media.firstOrNull { it.type == pjmedia_type.PJMEDIA_TYPE_AUDIO }
        return when (audio?.status) {
            pjsua_call_media_status.PJSUA_CALL_MEDIA_LOCAL_HOLD -> StackCallState.PAUSED
            pjsua_call_media_status.PJSUA_CALL_MEDIA_REMOTE_HOLD -> StackCallState.PAUSED_BY_REMOTE
            pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE -> StackCallState.STREAMS_RUNNING
            else -> StackCallState.CONNECTED
        }
    }

    /**
     * A registration status code as the app's state.
     *
     * `expiration == 0` on a 2xx is an unregister the server accepted, not a registration —
     * the same response code means opposite things depending on what was asked.
     */
    private fun registrationStateOf(code: Int, expiration: Long): StackRegistrationState = when {
        code in SIP_OK until SIP_ERROR_FLOOR && expiration == 0L -> StackRegistrationState.CLEARED
        code in SIP_OK until SIP_ERROR_FLOOR -> StackRegistrationState.OK
        code >= SIP_ERROR_FLOOR -> StackRegistrationState.FAILED
        else -> StackRegistrationState.PROGRESS
    }

    private fun callParams(videoEnabled: Boolean) = CallOpParam(true).apply {
        opt = CallSetting().apply {
            audioCount = 1
            // There is no `isVideoEnabled` in PJSIP. The stream count *is* the profile.
            videoCount = if (videoEnabled) 1L else 0L
        }
    }

    /** True for an audio stream that is carrying, or held by the far end and still there. */
    private val org.pjsip.pjsua2.CallMediaInfo.isLive: Boolean
        get() = status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE ||
            status == pjsua_call_media_status.PJSUA_CALL_MEDIA_REMOTE_HOLD

    /**
     * Runs [block] on the one registered PJSIP thread.
     *
     * Failures are logged rather than thrown: these are posted from a coroutine that has
     * already returned, so there is nobody left to catch them, and a PJSIP `Error` on one
     * operation must not take the executor down with it.
     */
    private fun onPjsip(what: String, block: () -> Unit) {
        pjsip.execute {
            runCatching(block).onFailure { logger.error(TAG, "$what failed: ${it.message}") }
        }
    }

    private companion object {
        const val TAG = "PjsipGateway"
        const val PJSIP_THREAD = "pjsip-main"
        const val EVENT_BUFFER = 64

        const val TRANSPORT_UDP = "UDP"
        const val TRANSPORT_TCP = "TCP"
        const val TRANSPORT_TLS = "TLS"

        /** PJSIP priorities run 0 (disabled) to 255 (first choice). */
        const val CODEC_TOP = 255
        const val CODEC_DISABLED: Short = 0

        /** `PJSUA_DTMF_METHOD_SIP_INFO`; RFC 2833 is method 0 and is `dialDtmf`'s default. */
        const val DTMF_METHOD_SIP_INFO = 1

        /** Whatever `AccountConfig.videoConfig.defaultCaptureDevice` resolved to. */
        const val CAPTURE_DEVICE_DEFAULT = -1

        const val SIP_OK = 200
        const val SIP_PROGRESS = 183
        const val SIP_ERROR_FLOOR = 300
    }
}
