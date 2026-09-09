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
import com.whatsappv2.data.sip.registration.NameAddr
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
import org.pjsip.pjsua2.IpChangeParam
import org.pjsip.pjsua2.OnCallMediaStateParam
import org.pjsip.pjsua2.OnCallRxReinviteParam
import org.pjsip.pjsua2.OnCallStateParam
import org.pjsip.pjsua2.OnCallTransferStatusParam
import org.pjsip.pjsua2.OnIncomingCallParam
import org.pjsip.pjsua2.OnRegStateParam
import org.pjsip.pjsua2.TlsConfig
import org.pjsip.pjsua2.TransportConfig
import org.pjsip.pjsua2.VideoWindowHandle
import org.pjsip.pjsua2.pj_ssl_sock_proto
import org.pjsip.pjsua2.pjmedia_dir
import org.pjsip.pjsua2.pjmedia_srtp_use
import org.pjsip.pjsua2.pjmedia_type
import org.pjsip.pjsua2.pjmedia_vid_stream_rc_method
import org.pjsip.pjsua2.pjsip_inv_state
import org.pjsip.pjsua2.pjsip_ssl_method
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
 * So every call into PJSIP is posted to [pjsip], a single-threaded executor. Its one
 * thread is registered by `libCreate()` itself — pjsua2's `Endpoint::libCreate` ends with
 * `mainThread = pj_thread_this()` — and because `start` is posted through the same
 * executor as everything else, that thread *is* the only one that ever calls in. No
 * explicit `libRegisterThread` is needed or wanted; see [startEndpoint] for what happened
 * when one was made anyway. A single thread also makes ordering free: `start` then
 * `addAccount` cannot race, because the executor runs them in submission order.
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
    private val trustStore: PjsipTrustStore,
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

    /**
     * PJSIP's log sink, held for the life of the gateway — but not built until the stack
     * is.
     *
     * A SWIG director like [PjAccount] and [PjCall], and held for the same reason: PJSIP
     * keeps a native pointer to it and calls it from its own threads. Collected while that
     * pointer is live, the next log line is a use-after-free — and one raised from a
     * thread that has nothing to do with whatever caused it.
     *
     * **Built in [endpointConfig], never here.** `PjsipLogWriter` extends pjsua2's
     * `LogWriter`, and loading that class runs `pjsua2JNI`'s static initialiser, which is
     * `System.loadLibrary("pjsua2")`. As an eager field that ran in this gateway's
     * *constructor*, so merely resolving the Hilt graph tried to load a native library —
     * fine on a handset, an `UnsatisfiedLinkError` on the JVM, and it took thirteen unit
     * tests in `:app` down with it the moment the trace was added.
     *
     * **One per `start`, because `libDestroy` deletes it.** `Endpoint::libDestroy` ends
     * with `delete this->writer` (pjsua2 `endpoint.cpp`), so the writer handed to
     * `libInit` does not survive the matching [stop]. A single cached instance reused
     * across a stop/start cycle would hand `libInit` a pointer C++ had already freed.
     */
    @Volatile
    private var logWriter: PjsipLogWriter? = null

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

    /**
     * Whether a `setNetworkReachable(false)` has arrived since the last IP change was
     * handled. Read and written only on the [pjsip] executor thread, so it needs no
     * synchronisation of its own.
     */
    private var linkDownSeen = false

    /**
     * Why [start] failed, when it did, so a later operation can say so instead of
     * failing silently.
     *
     * Written by [start] and read by [reportStackUnavailable], both of which run on the
     * [pjsip] executor thread, so it needs no synchronisation. Null means either the
     * stack is up or nothing has tried yet - [endpoint] distinguishes those.
     */
    private var startFailure: Throwable? = null

    // ------------------------------------------------------------------ lifecycle

    override fun start() {
        onPjsip("start") {
            if (endpoint != null) return@onPjsip

            // Staged deliberately. Every line below can fail for a different reason, and
            // until this was traced from a handset the only evidence of any of them was
            // one line saying "start failed" with a message and no stack trace. The stage
            // that logged last is the stage that broke.
            logger.info(TAG, "start: begin")
            startFailure = null

            runCatching { startEndpoint() }
                .onSuccess { logger.info(TAG, "start: SIP core running") }
                .onFailure { cause ->
                    // Recorded, not just logged. Without this the next addAccount knows
                    // only that `endpoint` is null and cannot say why - which is exactly
                    // how a dead stack became an indefinite "Registering" spinner.
                    startFailure = cause
                    logger.error(TAG, "start: FAILED - the SIP stack is not running", cause)
                }
        }
    }

    /**
     * Brings the endpoint up, or throws saying which stage did not survive.
     *
     * Separated from [start] so the failure has somewhere to be caught once, rather than
     * a `runCatching` around each stage that would turn a hard failure into five soft
     * ones and carry on with a half-built stack.
     */
    private fun startEndpoint() {
        // Without this there are no capture devices at all, and a video call
        // negotiates a stream it can never fill.
        PjCameraInfo2.SetCameraManager(context.getSystemService(CameraManager::class.java))

        // This is where a build with no `libpjsua2.so` dies, and it dies here rather
        // than at `libCreate` because touching `Endpoint` runs the static initialiser
        // of `pjsua2JNI` - which calls `swig_module_init()` with nothing behind it.
        // Note that only the FIRST attempt reports UnsatisfiedLinkError; a class whose
        // initialiser threw is permanently unusable, so every retry after it reports
        // NoClassDefFoundError instead, for the same underlying reason.
        val created = Endpoint()
        logger.info(TAG, "start: Endpoint constructed (JNI bindings loaded)")

        created.libCreate()
        logger.info(TAG, "start: libCreate ok")

        // No libRegisterThread here, and that is deliberate rather than an omission.
        //
        // `libCreate()` registers the thread that called it — pjsua2's own
        // `Endpoint::libCreate` ends with `mainThread = pj_thread_this()` and
        // `threadDescMap[pj_thread_this()] = NULL`. Every operation in this class,
        // `start` included, is posted through `onPjsip` to one single-threaded executor,
        // so the thread that gets registered there is the only thread that ever touches
        // PJSIP. There is nothing left to register.
        //
        // Calling it anyway was a native SIGSEGV, not a no-op: it landed between
        // `libCreate` and `libInit`, and `pj_thread_register` takes a mutex that
        // `libInit` is what brings up — the crash was `pj_mutex_lock` two frames under
        // `Endpoint::libRegisterThread`. It would also have leaked, since the
        // `pj_thread_desc` it mallocs is only freed when the library is destroyed.
        created.libInit(endpointConfig())
        // The trace state is stated rather than assumed. It was silently off for two
        // builds - the stack came up, registered and rang with not one PJSIP line - and a
        // startup that says which it is costs one line and settles that question in
        // logcat instead of in a source read.
        logger.info(
            TAG,
            "start: libInit ok (SIP trace ${if (logWriter != null) "on" else "off"})",
        )

        // After libInit and before libStart, and both halves of that matter. libInit
        // is what registers the codecs, so there is nothing to configure before it;
        // a stream created after libStart has already taken its parameters, so
        // configuring later changes nothing until the next call.
        created.tuneOpus()
        created.tuneVideoCodecs()

        // All three, once, at startup. PJSIP binds an account to a transport by id,
        // so the transport an account needs has to exist before the account does —
        // and creating them lazily would mean the first TLS account paid for a
        // listener the UDP ones had already been running without.
        transports[TRANSPORT_UDP] =
            created.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, TransportConfig())
        transports[TRANSPORT_TCP] =
            created.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TCP, TransportConfig())
        transports[TRANSPORT_TLS] =
            created.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TLS, tlsTransportConfig())

        logger.info(TAG, "start: transports created (UDP, TCP, TLS)")

        created.libStart()
        logger.info(TAG, "start: libStart ok")
        endpoint = created
    }

    /**
     * The endpoint configuration (§5.2).
     *
     * This used to be `EpConfig()`, which is every default PJSIP ships, and the defaults
     * are chosen for a desktop softphone on an unknown machine rather than for this app.
     * Four of them cost audio quality directly:
     *
     *  - **`clockRate` was 16000.** That is the rate the conference bridge mixes at, so
     *    Opus - negotiated at 48 kHz, and the only wideband codec in the default
     *    preference list - was being resampled down to 16 kHz and back up on the way out.
     *    Paying for a full-band codec and then throwing two thirds of the band away.
     *  - **`quality` was 8.** The resampler quality, 1..10. At 48 kHz there is more to
     *    lose in a bad resample, so this goes to the top.
     *  - **VAD was on.** Silence suppression saves bandwidth by not sending during
     *    silence, and pays for it by clipping the first syllable after every pause. On a
     *    wideband codec that is the most audible artefact left.
     *  - **`ecTailLen` was implicit.** Stated now, because an echo canceller whose tail
     *    is shorter than the device's acoustic path cancels nothing.
     *
     * The echo canceller **algorithm** is deliberately left at pjmedia's default rather
     * than forced to `PJMEDIA_ECHO_WEBRTC_AEC3`. AEC3 has to be compiled into the native
     * library to exist, the native build has never completed a run, and an `ecOptions`
     * naming an algorithm that is not there is worse than the default - it is no echo
     * cancellation at all. That is a change to make once P-1 is green and the build's
     * feature flags can be read rather than assumed.
     *
     * These are principled starting points, not measured ones. P-7 is where they get
     * checked against a real handset and a real link; nothing here has been heard yet.
     */
    private fun endpointConfig(): EpConfig = EpConfig().apply {
        uaConfig.userAgent = USER_AGENT
        uaConfig.maxCalls = MAX_CALLS

        // Without this, everything PJSIP has to say goes to a sink Android drops, and an
        // entire failed call leaves zero app-side log lines - which is exactly what it
        // did. `msgLogging` is the one that carries the SIP messages themselves; the
        // level alone would give the library's chatter and not the INVITEs.
        //
        // `consoleLevel` is NOT "the level of PJSIP's own console sink". It is the gate on
        // the application callback as well. pjsua's `log_writer` reads:
        //
        //     if (level <= (int)pjsua_var.log_cfg.console_level) {
        //         if (pjsua_var.log_cfg.cb) (*pjsua_var.log_cfg.cb)(level, buffer, len);
        //         else pj_log_write(level, buffer, len);
        //     }
        //
        // - and `cb` is what pjsua2 points at this writer. Set to 0, as it was, no level
        // can ever satisfy `level <= 0`, so the callback is never reached and the trace is
        // dead by construction. The stack came up, registered and rang with not one line
        // to show for it. It tracks `level` instead: one number decides the verbosity, and
        // there is no second one left behind to silence the first.
        //
        // Ownership: `libDestroy` does `delete this->writer`, so C++ frees it. SWIG's
        // generated `LogWriter()` sets `swigCMemOwn = true` and its finalizer calls
        // `delete_LogWriter` - which after `libDestroy` would be a second free of the same
        // pointer. `swigReleaseOwnership()` hands ownership to C++, which is what makes the
        // single delete in `libDestroy` the only one, and has the director hold a strong
        // reference back so the object stays alive while PJSIP can still call it.
        logConfig.apply {
            writer = PjsipLogWriter(logger)
                .also { it.swigReleaseOwnership() }
                .also { logWriter = it }
            msgLogging = SIP_MESSAGE_LOGGING
            level = TRACE_LEVEL
            consoleLevel = TRACE_LEVEL
        }

        medConfig.apply {
            clockRate = CORE_CLOCK_RATE
            channelCount = MONO
            quality = RESAMPLE_QUALITY
            ecTailLen = EC_TAIL_MS
            // `noVad` reads backwards: true disables voice activity detection.
            noVad = true
            // Adaptive, but bounded. An unbounded jitter buffer trades a defect the user
            // hears for one they hear later, and half a second of delay is already a
            // conversation people talk over.
            jbMax = JITTER_BUFFER_MAX_MS
        }
    }

    /**
     * The TLS transport's configuration (P-8, §7).
     *
     * The stack ADR-006 removed verified the server certificate and its common name on
     * every connection. The replacement shipped with a bare `TransportConfig()`, and
     * pjsua2 defaults `verifyServer` to **off** — so for the length of the migration this
     * app negotiated TLS and then accepted whatever certificate arrived, which is
     * encryption without authentication and stops no attacker who can reach the path.
     *
     * Two halves, and neither works alone:
     *
     *  - **`verifyServer`** turns the check on. Set unconditionally, including when
     *    [PjsipTrustStore] could not produce a bundle — TLS then fails, loudly, rather
     *    than falling back to trusting anything. See that class for why failing closed is
     *    the whole point.
     *  - **`caListFile`** is what it checks against. OpenSSL is built here with no default
     *    CA store, so without this every certificate is rejected rather than accepted.
     *
     * `method` picks the handshake and `proto` masks what that handshake may settle on.
     * Both are needed: SSLv23 means "negotiate the highest available", and without the
     * mask that includes TLS 1.0 and 1.1, which is how a client written in 2026 still ends
     * up on a deprecated cipher suite because the far end offered one.
     */
    private fun tlsTransportConfig(): TransportConfig = TransportConfig().apply {
        tlsConfig = TlsConfig().apply {
            verifyServer = true
            trustStore.caBundle()?.let { bundle -> caListFile = bundle.absolutePath }

            method = pjsip_ssl_method.PJSIP_SSLV23_METHOD
            proto = (
                pj_ssl_sock_proto.PJ_SSL_SOCK_PROTO_TLS1_2 or
                    pj_ssl_sock_proto.PJ_SSL_SOCK_PROTO_TLS1_3
                ).toLong()
        }
    }

    /**
     * Opus, configured rather than left at whatever the codec defaults to (§5.2).
     *
     * Opus is the only wideband codec in the default preference list, so its settings are
     * most of what "audio quality" means here.
     *
     *  - `sample_rate` matches the bridge, so nothing resamples on the way in or out.
     *  - `bit_rate` is well above the 16-24 kbps that narrowband deployments settle for;
     *    at 48 kHz mono this is transparent for speech.
     *  - `complexity` is the encoder's own quality/CPU dial, 0..10.
     *  - `packet_loss` is not a measurement, it is a *hint*: it tells the encoder how much
     *    FEC to carry. Zero means no redundancy, and the first lost packet is a hole.
     *  - CBR off, because VBR spends the bits where the speech is.
     */
    private fun Endpoint.tuneOpus() {
        runCatching {
            val opus = codecOpusConfig
            opus.sample_rate = CORE_CLOCK_RATE
            opus.channel_cnt = MONO
            opus.bit_rate = OPUS_BITRATE
            opus.complexity = OPUS_COMPLEXITY
            opus.packet_loss = OPUS_EXPECTED_LOSS_PCT
            opus.cbr = false
            codecOpusConfig = opus
        }.onFailure {
            // Not fatal: a build without Opus still registers PCMU and G722, and a call
            // on those is worth more than no call. Loud, because it means the native
            // library was built without PJMEDIA_HAS_OPUS_CODEC and §5.2 is not being met.
            logger.error(TAG, "Opus not configured - is it compiled in? ${it.message}")
        }
    }

    /**
     * Video encoder parameters, per codec (§5.2).
     *
     * PJSIP's defaults here are conservative enough to look like a fault: a small frame at
     * a low bitrate, which on a modern handset reads as a broken camera rather than a
     * bandwidth choice. Every registered codec gets the same ceiling, because the codec
     * that ends up negotiated is the far end's decision, not ours.
     *
     * A ceiling, not a target - `rateControlBandwidth` on the account is what actually
     * holds the stream to it, and PJSIP drops below it on its own when the link cannot
     * carry it. Applied per codec inside `runCatching` so one codec rejecting a format
     * does not cost the others theirs.
     */
    private fun Endpoint.tuneVideoCodecs() {
        videoCodecEnum2().forEach { info ->
            runCatching {
                val param = getVideoCodecParam(info.codecId)
                param.encFmt.apply {
                    width = VIDEO_WIDTH
                    height = VIDEO_HEIGHT
                    fpsNum = VIDEO_FPS
                    fpsDenum = 1
                    avgBps = VIDEO_AVG_BPS
                    maxBps = VIDEO_MAX_BPS
                }
                setVideoCodecParam(info.codecId, param)
            }.onFailure {
                logger.warn(TAG, "Video codec ${info.codecId} kept its defaults: ${it.message}")
            }
        }
    }

    override fun stop() {
        onPjsip("stop") {
            val running = endpoint ?: return@onPjsip

            // Accounts first: each one unregisters and drops the credential PJSIP holds
            // for it, which is what Task 29 requires of a logout. libDestroy would take
            // them with it, but not cleanly and not with an `Expires: 0` on the wire.
            // Recorders first, and while the endpoint is still alive: each one holds an
            // open file, and `clear()` only drops the Kotlin reference - the file stays
            // open until a finalizer happens to run, which for a recording the user asked
            // to keep is a truncated file. After libDestroy the native port is already
            // gone and deleting it would be worse than not.
            recorders.values.forEach { runCatching { it.delete() } }
            recorders.clear()

            accounts.values.forEach { account ->
                runCatching { account.setRegistration(false) }
                runCatching { account.shutdown() }
            }
            accounts.clear()
            calls.clear()
            transports.clear()

            running.libDestroy()
            // libDestroy tears the library down; it does not free the Java object's own
            // native peer. This pairing - destroy, then delete, then drop - is the one the
            // pjsua2 Android sample uses, and without the middle step every start/stop
            // cycle leaves an Endpoint behind for a finalizer to find later.
            runCatching { running.delete() }
            endpoint = null
            // libDestroy already deleted it; this only drops the Kotlin reference so the
            // next start builds a fresh one rather than reusing a freed pointer.
            logWriter = null
            logger.info(TAG, "SIP core stopped")
        }
    }

    // ------------------------------------------------------------------ network

    /**
     * The link underneath the transports changed (Task 30, DoD 6).
     *
     * [com.whatsappv2.data.sip.network.TransportRebinder] signals a change as `false` then
     * `true`, and the two halves are not symmetric.
     *
     * **`false` does not touch the stack.** There is no address to bind to while the link
     * is down, so tearing transports down here would buy nothing and would race the
     * platform's own teardown. It is recorded, and that is all.
     *
     * **`true` is where the work is**, and PJSIP has one call for exactly this:
     * `handleIpChange` shuts the transports down, stands the listeners back up on the
     * address the device now holds, and re-registers every account - in that order, which
     * is the order that matters. A REGISTER sent before the rebind leaves from an
     * interface that no longer exists and never reaches the wire, which is the defect the
     * recovery coordinator was written to avoid.
     *
     * Guarded on [linkDownSeen] so a `true` with no preceding `false` is a no-op: at
     * startup, and on a spurious callback, the transports are already correct and
     * restarting them would drop calls that are working.
     */
    override fun setNetworkReachable(reachable: Boolean) {
        onPjsip("setNetworkReachable") {
            val running = endpoint ?: return@onPjsip

            if (!reachable) {
                linkDownSeen = true
                logger.info(TAG, "Link down; transports left alone until one returns")
                return@onPjsip
            }

            if (!linkDownSeen) {
                logger.debug(TAG, "Link reported up without a down; nothing to rebind")
                return@onPjsip
            }
            linkDownSeen = false

            running.handleIpChange(
                IpChangeParam().apply {
                    // Both, explicitly. Shutting the transport down without restarting the
                    // listener leaves the stack with nothing to send from; restarting the
                    // listener without the shutdown leaves the old socket bound to an
                    // address the device has given up.
                    shutdownTransport = true
                    restartListener = true
                },
            )
            logger.info(TAG, "Link up; transports rebound and accounts re-registered")
        }
    }

    // ------------------------------------------------------------------ accounts

    override fun addAccount(account: StackAccount) {
        onPjsip("addAccount") {
            val running = endpoint ?: run {
                reportStackUnavailable(account.key, "addAccount")
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

    /**
     * Tells whoever asked that the stack is not running, and why.
     *
     * This existed as `logger.error("addAccount before start")` and nothing else, which
     * is the defect a handset found: `PjsipSipEngine.register` sets
     * `RegistrationState.Registering` before calling through, the call returned without
     * doing anything, and no event ever arrived to move the state off it. A dead stack
     * presented as an indefinite spinner - indistinguishable, to the user, from a slow
     * network or a wrong password.
     *
     * A FAILED event with no status code maps to `SipError.TransportFailure`, which is
     * the honest reading: the request never reached a server, so this is not a rejection
     * to check a password against. [startFailure] supplies the actual cause, because
     * "the stack is not running" is not an answer anybody can act on.
     */
    private fun reportStackUnavailable(accountKey: String, operation: String) {
        val cause = startFailure
        val reason = cause
            ?.let { "${it.javaClass.simpleName}: ${it.message ?: "no message"}" }
            ?: "the SIP stack was never started"

        logger.error(TAG, "$operation refused - $reason", cause)
        events.tryEmit(
            StackRegistrationEvent(
                accountKey = accountKey,
                state = StackRegistrationState.FAILED,
                statusCode = null,
                message = reason,
            ),
        )
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

        // The ceiling the encoder parameters are allowed to reach, and the thing that
        // actually holds them there. Without rate control PJSIP encodes at the format's
        // bitrate whatever the link is doing, and a 2.5 Mbit stream on a cell connection
        // does not degrade - it stalls, because the packets it needs are the ones being
        // dropped.
        videoConfig.rateControlMethod =
            pjmedia_vid_stream_rc_method.PJMEDIA_VID_STREAM_RC_SIMPLE_BLOCKING
        videoConfig.rateControlBandwidth = VIDEO_MAX_BPS

        // A few keyframes up front. The first frame a decoder can actually show is a
        // keyframe, and one every two seconds means up to two seconds of grey.
        videoConfig.startKeyframeCount = VIDEO_START_KEYFRAMES
        videoConfig.startKeyframeInterval = VIDEO_START_KEYFRAME_INTERVAL_MS
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
            kind = "Audio",
            available = codecEnum2().map { it.codecId },
            preferred = account.audioCodecs,
        ) { id, priority -> codecSetPriority(id, priority) }

        applyPriorities(
            kind = "Video",
            available = videoCodecEnum2().map { it.codecId },
            preferred = account.videoCodecs,
        ) { id, priority -> videoCodecSetPriority(id, priority) }

        logger.info(
            TAG,
            "Codecs for ${account.key}: audio=${account.audioCodecs} video=${account.videoCodecs}",
        )
    }

    private inline fun applyPriorities(
        kind: String,
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

        // A preference the build cannot honour is not an error - the call still connects
        // on whatever else was offered - but it is never what the author meant, and until
        // now it was invisible. `CodecPreferences.DEFAULT` names H264 while the native
        // build sets PJMEDIA_HAS_OPENH264_CODEC to 0, so H264 is silently never
        // negotiated and an H264-only peer gets no video at all. Said out loud, once per
        // account, rather than discovered on a call that half worked.
        val missing = preferred.filter { name ->
            available.none { it.startsWith(name, ignoreCase = true) }
        }
        if (missing.isNotEmpty()) {
            logger.warn(TAG, "$kind codecs preferred but not in this build: $missing")
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
     * PJSIP has no core-wide capture switch; capture belongs to a call's video stream. So
     * this is `START_TRANSMIT`/`STOP_TRANSMIT` applied to every call that has one, which
     * produces the same observable behaviour — the camera is released when `CameraPolicy`
     * says nobody should hold it.
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
            val remote = NameAddr.of(info?.remoteUri)
            callEventFlow.tryEmit(
                StackCallEvent(
                    callKey = callKey,
                    accountKey = accountKey,
                    remoteUri = remote.uri,
                    remoteDisplayName = remote.displayName,
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
            // The throwable, not just its message. A message alone cannot say which frame
            // in a JNI call threw, and for an UnsatisfiedLinkError the frame is the answer.
            runCatching(block).onFailure { logger.error(TAG, "$what failed: ${it.message}", it) }
        }
    }

    private companion object {
        const val TAG = "PjsipGateway"

        /**
         * PJSIP's trace level, and what it costs.
         *
         * 4 is where the SIP messages are. The release logger compiles `debug` to an
         * empty body, so the lines are dropped there rather than shipped - but PJSIP
         * still formats them before handing them over. That is a real if small cost, and
         * the honest trade for a client whose signalling is otherwise invisible.
         */
        const val TRACE_LEVEL = 4L

        /** 1 enables the SIP message trace. The level alone does not. */
        const val SIP_MESSAGE_LOGGING = 1L
        const val PJSIP_THREAD = "pjsip-main"
        const val EVENT_BUFFER = 64

        const val TRANSPORT_UDP = "UDP"
        const val TRANSPORT_TCP = "TCP"
        const val TRANSPORT_TLS = "TLS"

        /**
         * Media tuning (§5.2). Starting points chosen from PJSIP's own guidance, not
         * measurements - P-7 is where they meet a handset.
         */
        const val USER_AGENT = "whatsapp-v2 (PJSIP)"

        /** Simultaneous calls the stack will hold: one active, one held, room to transfer. */
        const val MAX_CALLS = 4L

        const val MONO = 1L

        /**
         * The conference bridge's mixing rate, and Opus's native one. PJSIP defaults to
         * 16000; at 48000 nothing resamples a wideband call. It costs CPU in the bridge,
         * and this is the one line to change if a low-end device cannot carry it.
         */
        const val CORE_CLOCK_RATE = 48_000L

        /** Resampler quality, 1..10. PJSIP defaults to 8. */
        const val RESAMPLE_QUALITY = 10L

        /** Echo tail, ms. Shorter than the device's acoustic path cancels nothing. */
        const val EC_TAIL_MS = 200L

        /** Jitter buffer ceiling, ms. Beyond this, delay is the worse defect. */
        const val JITTER_BUFFER_MAX_MS = 500

        /** Transparent for speech at 48 kHz mono; well above narrowband practice. */
        const val OPUS_BITRATE = 32_000L

        /** Encoder quality/CPU dial, 0..10. */
        const val OPUS_COMPLEXITY = 8L

        /** An FEC hint, not a measurement: how much redundancy to carry. */
        const val OPUS_EXPECTED_LOSS_PCT = 5L

        const val VIDEO_WIDTH = 1280L
        const val VIDEO_HEIGHT = 720L
        const val VIDEO_FPS = 30
        const val VIDEO_AVG_BPS = 1_500_000L
        const val VIDEO_MAX_BPS = 2_500_000L
        const val VIDEO_START_KEYFRAMES = 3L
        const val VIDEO_START_KEYFRAME_INTERVAL_MS = 1_000L

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
