package com.whatsappv2.data.sip.registration.stack

import android.content.Context
import android.content.pm.ApplicationInfo
import android.hardware.camera2.CameraManager
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.map
import com.whatsappv2.core.common.result.success
import com.whatsappv2.data.sip.call.SipCallGateway
import com.whatsappv2.data.sip.call.SipRecordingGateway
import com.whatsappv2.data.sip.call.SipVideoGateway
import com.whatsappv2.data.sip.call.StackCallEvent
import com.whatsappv2.data.sip.call.StackCallState
import com.whatsappv2.data.sip.call.StackConferenceEvent
import com.whatsappv2.data.sip.call.StackTransferEvent
import com.whatsappv2.data.sip.call.TransferEventMapper
import com.whatsappv2.data.sip.registration.NameAddr
import com.whatsappv2.data.sip.registration.SipCoreGateway
import com.whatsappv2.data.sip.registration.StackAccount
import com.whatsappv2.data.sip.registration.StackPushParameters
import com.whatsappv2.data.sip.registration.StackRegistrationEvent
import com.whatsappv2.data.sip.registration.StackRegistrationState
import com.whatsappv2.domain.codec.CodecAudit
import com.whatsappv2.domain.codec.CodecPriorities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.pjsip.PjCameraInfo2
import org.pjsip.pjsua2.Account
import org.pjsip.pjsua2.AccountConfig
import org.pjsip.pjsua2.AudioMedia
import org.pjsip.pjsua2.AudioMediaRecorder
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
import org.pjsip.pjsua2.OnCallTsxStateParam
import org.pjsip.pjsua2.OnIncomingCallParam
import org.pjsip.pjsua2.OnIpChangeProgressParam
import org.pjsip.pjsua2.OnRegStateParam
import org.pjsip.pjsua2.TlsConfig
import org.pjsip.pjsua2.TransportConfig
import org.pjsip.pjsua2.VidDevManager
import org.pjsip.pjsua2.VideoWindowHandle
import org.pjsip.pjsua2.pj_ssl_sock_proto
import org.pjsip.pjsua2.pjmedia_dir
import org.pjsip.pjsua2.pjmedia_orient
import org.pjsip.pjsua2.pjmedia_tp_proto
import org.pjsip.pjsua2.pjmedia_type
import org.pjsip.pjsua2.pjmedia_vid_dev_std_index
import org.pjsip.pjsua2.pjsip_inv_state
import org.pjsip.pjsua2.pjsip_role_e
import org.pjsip.pjsua2.pjsip_ssl_method
import org.pjsip.pjsua2.pjsip_status_code
import org.pjsip.pjsua2.pjsip_transport_type_e
import org.pjsip.pjsua2.pjsua_call_flag
import org.pjsip.pjsua2.pjsua_call_media_status
import org.pjsip.pjsua2.pjsua_call_vid_strm_op
import org.pjsip.pjsua2.pjsua_ip_change_op
import org.pjsip.pjsua2.pjsua_vid_req_keyframe_method
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
    private val pjsip = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, PJSIP_THREAD).apply { isDaemon = true }
    }

    /**
     * Enforces the thread-confinement invariant instead of documenting it (DoD 4).
     *
     * ## The invariant
     *
     * pjsua2 requires every thread that calls into it to be **registered with the library
     * first**, and `Endpoint::libRegisterThread` allocates a thread descriptor that is
     * *"only freed when the library is destroyed"*. The adapter's answer is this
     * single-threaded executor: `libCreate` registers its own caller, every call is posted
     * through the same executor, so exactly one thread ever calls in and no descriptor
     * leaks (`docs/pjsip-migration.md:36-60`).
     *
     * ## Why an assertion and not a comment
     *
     * That design is correct and was, until now, held in place by everyone remembering it.
     * Calling in from an unregistered thread is **undefined behaviour**: it does not throw,
     * it does not log, and it surfaces later as a `SIGSEGV` in a stack trace with no Kotlin
     * frames — the top source of native crashes in pjsua2 apps, and silent until it is
     * fatal. Owning the build makes this more important rather than less: a stack you
     * patched is a stack whose crashes are yours to explain.
     *
     * ## Debug only, deliberately
     *
     * A release build does nothing here. The check must never be the thing that ends a
     * shipped call — the underlying bug is a crash either way, and turning a maybe-crash
     * into a definite one in front of a user buys nothing.
     *
     * O(1): one reference comparison per call into the library.
     *
     * `FLAG_DEBUGGABLE` rather than `BuildConfig.DEBUG`: this module does not generate a
     * `BuildConfig` (AGP 8 made that opt-in), and turning the feature on for one boolean
     * would add a generated class to every variant of a library that has managed without
     * one. The manifest flag is the same fact, already present.
     *
     * @throws IllegalStateException in a debug build, naming both threads.
     */
    private fun assertOnPjsipThread(operation: String) {
        if (!isDebuggable) return
        val current = Thread.currentThread()
        check(current.name == PJSIP_THREAD) {
            "$operation called pjsua2 from '${current.name}', not '$PJSIP_THREAD'.\n" +
                "  Every thread that calls into pjsua2 must be registered with the library " +
                "first, and libRegisterThread leaks a descriptor for the life of the " +
                "process. Post through the pjsip executor instead.\n" +
                "  Calling in unregistered is undefined behaviour: it does not throw at the " +
                "boundary, it becomes a SIGSEGV later with no Kotlin frames."
        }
    }

    /**
     * Whether this is a debuggable build, read once.
     *
     * The flag the platform sets from the manifest, which is exactly "is this a debug
     * build" without needing a generated `BuildConfig` in this module.
     */
    private val isDebuggable: Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * The codec audit (N-9, §2.5), recomputed once per successful [start].
     *
     * `null` until the stack has started. A `StateFlow` rather than a one-shot because the
     * endpoint can be stopped and started again — a network change, a settings change — and
     * the answer is a property of the running library, not of the process.
     */
    private val audit = MutableStateFlow<CodecAudit?>(null)

    /**
     * Why Lyra's model files cannot be used, or null. Set once by [start] and read by
     * every audit after it, so a codec that registered without its weights is reported
     * as such on every account rather than only at the moment the copy failed.
     */
    @Volatile
    private var lyraModelProblem: String? = null
    override val codecAudit: StateFlow<CodecAudit?> = audit.asStateFlow()

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

    /**
     * Whether the trace reaches the log, from the switch in Settings.
     *
     * Read on PJSIP's own threads and written from the engine's, hence `@Volatile`.
     * Default off, matching `AppSettings.DEFAULT.sipTraceEnabled`, so a build does not
     * start writing signalling to logcat before anyone has asked it to.
     */
    @Volatile
    private var traceEnabled: Boolean = false

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
     * True from [setNetworkReachable]'s `handleIpChange` until PJSIP reports the change
     * completed. Same thread rule as [linkDownSeen].
     *
     * `handleIpChange` restarts the listeners *asynchronously* — the UDP socket comes
     * back "in 10 ms" — and then re-registers every account itself. The recovery
     * coordinator asks for a refresh of its own in the same instant, and that REGISTER
     * left before the socket existed: `503 Transport not available`, the engine told the
     * UI the registration had failed, and the gateway logged a stack trace, for a
     * registration PJSIP then completed on its own 14 ms later (TC15, every Wi-Fi blip on
     * 2026-09-11: 13:33 and 13:53). While this is set, [refreshAccount] is a no-op.
     */
    private var ipChangeInProgress = false

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
        val created = object : Endpoint() {
            // PJSIP's IP-change handling is asynchronous; this is the one signal that it
            // has finished, and [refreshAccount] is gated on it. Every op is reported;
            // only the last one clears the gate. Delivered on the PJSIP thread.
            override fun onIpChangeProgress(prm: OnIpChangeProgressParam) {
                if (prm.op == pjsua_ip_change_op.PJSUA_IP_CHANGE_OP_COMPLETED) {
                    ipChangeInProgress = false
                    logger.info(TAG, "IP change handled by PJSIP (status ${prm.status})")
                }
            }
        }
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
            "start: libInit ok (trace writer ${if (logWriter != null) "installed" else "missing"}, " +
                "trace ${if (traceEnabled) "on" else "off"})",
        )

        // After libInit and before libStart, and both halves of that matter. libInit
        // is what registers the codecs, so there is nothing to configure before it;
        // a stream created after libStart has already taken its parameters, so
        // configuring later changes nothing until the next call.
        created.tuneOpus(logger)
        created.tuneVideoCodecs(logger)
        lyraModelProblem = created.tuneLyra(context, logger)

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
            writer = PjsipLogWriter(logger) { traceEnabled }
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
                account.finishRemoval("stop")
            }
            accounts.clear()
            // Deleted while the library is still up: `Call::~Call` touches the call slot
            // unconditionally, and after libDestroy there is no slot to touch. See
            // [PjAccount.release] for why a finalizer must never be the one to do this.
            calls.values.forEach { call -> runCatching { call.delete() } }
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
            ipChangeInProgress = true
            // A gate that never opens would swallow every later refresh. PJSIP reports
            // COMPLETED even on failure, but only after its registrations time out; past
            // this the refreshes are wanted again whatever PJSIP is still doing.
            pjsip.schedule(
                {
                    if (ipChangeInProgress) {
                        ipChangeInProgress = false
                        logger.warn(TAG, "IP change not reported complete in time; refreshes resume")
                    }
                },
                IP_CHANGE_GATE_MILLIS,
                TimeUnit.MILLISECONDS,
            )

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
            val config = account.toAccountConfig(
                transportParam = transportUriParameter(account.transport),
                pushParameters = pushParameters,
            )

            // Replaced in place rather than added again. Two PJSIP accounts for one
            // identity fight over the same registrar binding, and the loser's calls go to
            // a device that has stopped listening.
            val existing = accounts[account.key]
            if (existing != null) {
                // `pjsua_acc_modify` re-registers in place when the identity, the proxy or
                // the credentials moved, and it does that by calling
                // `pjsua_acc_set_registration` - which returns PJSIP_EBUSY rather than
                // queueing when a REGISTER transaction is already in flight, because
                // `pjsip_regc_register` refuses a second one. pjsua2 raises that status as
                // a thrown Error.
                //
                // Swallowed by `onPjsip`, as it was, an edit went nowhere: the engine had
                // already published `Registering`, no stack event ever followed, and the
                // account sat on that spinner until the user logged out and registered
                // again. Logging out worked because it destroys the account and the next
                // register builds a fresh one - so that is what this does, instead of
                // leaving the user to find the workaround.
                runCatching { existing.modify(config) }.onFailure { failure ->
                    logger.warn(
                        TAG,
                        "modify failed for ${account.key}; rebuilding it: ${failure.message}",
                    )
                    rebuildAccount(account.key, config)
                }
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

    /**
     * Destroys an account and stands a fresh one up in its place.
     *
     * The automated form of the workaround a handset found: an edit that PJSIP refused to
     * apply in place only took effect after a logout and a new registration, because that
     * path destroys the account rather than modifying it. Failing to rebuild is reported
     * as a registration failure, since the alternative - a silent log line - is the
     * indefinite spinner this exists to remove.
     */
    private fun rebuildAccount(accountKey: String, config: AccountConfig) {
        accounts.remove(accountKey)?.let { stale ->
            // Best effort, and unchecked on purpose: the reason we are here is usually
            // that the registrar is not answering, so neither of these can be relied on.
            runCatching { stale.setRegistration(false) }
            stale.finishRemoval("rebuilt")
        }

        runCatching {
            val rebuilt = PjAccount(accountKey)
            rebuilt.create(config, accounts.isEmpty())
            accounts[accountKey] = rebuilt
        }.onFailure { failure ->
            logger.error(TAG, "Could not rebuild $accountKey", failure)
            events.tryEmit(
                StackRegistrationEvent(
                    accountKey = accountKey,
                    state = StackRegistrationState.FAILED,
                    statusCode = null,
                    message = failure.message ?: "the account could not be rebuilt",
                ),
            )
        }
    }

    override fun removeAccount(accountKey: String) {
        onPjsip("removeAccount") {
            val account = accounts.remove(accountKey) ?: return@onPjsip
            accountConfigs -= accountKey
            // Unregister before shutdown so the registrar hears `Expires: 0` rather than
            // simply losing the binding when it lapses — and shut down only once the
            // registrar has answered, because a deleted account has no callback left to
            // report the answer through.
            //
            // This used to shut the account down on the very next line. Measured on a
            // TC15, 2026-09-10: `Expires: 0` sent at :02.066, "Deleting account 0" at
            // :02.067, the registrar's 200 at :02.112 — forty-five milliseconds too late
            // for anyone to hear it. The engine, promised a CLEARED event by this
            // interface, waited its full five seconds on every logout and every policy
            // edit and then logged "not acknowledged; dropping it locally". The wire was
            // always fine; the report was thrown away.
            account.leaving = true
            val sent = runCatching { account.setRegistration(false) }.isSuccess
            if (!sent) {
                // Nothing registered, so nothing to wait for. PJSIP says so as an error.
                account.finishRemoval("not registered")
                return@onPjsip
            }
            // A registrar that never answers must not keep the credentials in memory
            // for the life of the process (Task 29). Longer than the engine's own wait,
            // so that the ordinary case is the answer and not this.
            pjsip.schedule(
                { account.finishRemoval("no answer in ${UNREGISTER_GRACE_MILLIS} ms") },
                UNREGISTER_GRACE_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    /**
     * Frees a director's native peer now, on this thread, rather than whenever the
     * garbage collector gets round to it.
     *
     * ## The crash this closes
     *
     * SIGABRT on a Zebra TC15, 2026-09-10 22:14:57, tid `FinalizerDaemon`:
     * `SwigDirector_Account::~SwigDirector_Account → Account::~Account → shutdown() →
     * pjsua_acc_del2 → pj_log → pj_thread_this` — *"Calling pjlib from unknown/external
     * thread"*. A `PjAccount` had been dropped from [accounts] on logout, the user logged
     * in again, and the collector then finalised the old Java object on its own thread.
     *
     * Two things are wrong with letting a finalizer do it, and the assertion only catches
     * the first. The finalizer thread is not registered with pjlib, so any pjlib call
     * from it is undefined and, in a debug build, an abort. Worse, `Account::~Account`
     * calls `shutdown()`, which asks `isValid()`, which checks **the slot number** —
     * `pjsua_var.acc[id].valid` (`pjsua_acc.c:115-119`) — and pjsua reuses freed slots.
     * The new account had slot 0, the old object still said 0, and the destructor was
     * deleting the *live* account. `Call::~Call` has the same shape (`pjsua2/call.cpp:
     * 525-544`): it clears the slot's user data and hangs up whatever is active in it.
     *
     * `delete()` runs the destructor here, while the slot is still the one this object
     * owned and already invalid, so the destructor's own `shutdown()` is a no-op; SWIG
     * then zeroes the pointer so the eventual finalizer finds nothing to do. The pjsua2
     * Android sample does exactly this for its calls and accounts.
     */
    private fun PjAccount.release() {
        runCatching { delete() }.onFailure { logger.warn(TAG, "delete of $accountKey failed: ${it.message}") }
    }

    override fun setTraceEnabled(enabled: Boolean) {
        // Not posted to the PJSIP thread: it is one volatile write, the writer reads it on
        // whichever thread PJSIP logs from, and going through the executor would make a
        // debugging switch wait behind whatever the stack is busy with.
        traceEnabled = enabled
        logger.info(TAG, "SIP trace ${if (enabled) "enabled" else "disabled"}")
    }

    override fun refreshAccount(accountKey: String) {
        onPjsip("refreshAccount") {
            if (ipChangeInProgress) {
                logger.info(TAG, "Refresh of $accountKey folded into the IP change PJSIP is handling")
                return@onPjsip
            }
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
                accounts[key]?.modify(
                    stored.toAccountConfig(
                        transportParam = transportUriParameter(stored.transport),
                        pushParameters = pushParameters,
                    ),
                )
            }
        }
    }

    /**
     * The `;transport=` URI parameter for an account's transport, or `""` for UDP.
     *
     * UDP is SIP's default transport (RFC 3261 §18.1.1), so naming it adds nothing and
     * costs the ability to upgrade an oversized request — a URI that says `transport=udp`
     * is a URI PJSIP will keep on UDP even when the message no longer fits a datagram.
     *
     * TCP and TLS must be named, because nothing else would select them: neither the
     * registrar URI nor the account id carries the information otherwise, and the
     * alternative — pinning `sipConfig.transportId` — silently sends nothing.
     */
    private fun transportUriParameter(transport: String): String =
        when (transport.uppercase()) {
            TRANSPORT_TCP -> ";transport=tcp"
            TRANSPORT_TLS -> ";transport=tls"
            else -> ""
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
        // Every OTHER configured account's preferences. PJSIP's priorities are endpoint-wide,
        // so without this the last account to register decides what the rest may negotiate.
        val otherAudio = otherAccountPreferences(account.key) { it.audioCodecs }
        val otherVideo = otherAccountPreferences(account.key) { it.videoCodecs }

        applyPriorities(
            kind = "Audio",
            accountKey = account.key,
            available = codecEnum2().map { it.codecId },
            preferred = account.audioCodecs,
            alsoRequired = otherAudio,
        ) { id, priority -> codecSetPriority(id, priority) }

        applyPriorities(
            kind = "Video",
            accountKey = account.key,
            available = videoCodecEnum2().map { it.codecId }.softwareVp8First(),
            preferred = account.videoCodecs,
            alsoRequired = otherVideo,
        ) { id, priority -> videoCodecSetPriority(id, priority) }

        logger.info(
            TAG,
            "Codecs for ${account.key}: audio=${account.audioCodecs} video=${account.videoCodecs}",
        )

        audit.value = auditCodecs(logger, lyraModelProblem)
    }

    /** What every account except [exceptKey] needs kept enabled. See [applyCodecs]. */
    private fun otherAccountPreferences(
        exceptKey: String,
        select: (StackAccount) -> List<String>,
    ): Set<String> = accountConfigs
        .filterKeys { it != exceptKey }
        .values
        .flatMapTo(mutableSetOf(), select)

    /**
     * Writes one kind of codec priority, having asked [CodecPriorities] what it should be.
     *
     * The ranking itself is a pure function in `:domain` and is tested there. What is left
     * here is the two things only the adapter can do: read the registry, and write the
     * numbers back one `runCatching` at a time so one codec refusing a priority does not
     * cost the others theirs.
     *
     * ## The refusal that matters
     *
     * [CodecPriorities.Assignment.wouldDisableEverything] is the guard against the defect of
     * 2026-09-10: an account saved with `audio=[lyra]` — a codec this build does not contain
     * — matched nothing, so the previous version of this function set **every** registered
     * audio codec to priority 0, endpoint-wide. `pjmedia_endpt_create_audio_sdp` then built
     * an m-line with no formats in it, and pjsua deactivated the line. Outgoing offers went
     * out as `m=audio 0 RTP/AVP 0`; inbound calls rang and then died on
     * `PJMEDIA_SDPNEG_ENOMEDIA` the moment the user answered, with this app sending itself a
     * `488 Unable to create media session`.
     *
     * The assignment comes back empty in that case, so applying it is already a no-op. This
     * says so at ERROR rather than relying on that: a preference list nothing can honour is
     * a configuration the user has to fix, and until it is fixed the endpoint keeps the
     * priorities pjmedia registered rather than losing its voice.
     */
    private inline fun applyPriorities(
        kind: String,
        accountKey: String,
        available: List<String>,
        preferred: List<String>,
        alsoRequired: Set<String>,
        set: (String, Short) -> Unit,
    ) {
        val assignment = CodecPriorities.assign(available, preferred, alsoRequired)

        if (assignment.wouldDisableEverything) {
            logger.error(
                TAG,
                "$kind codecs for $accountKey match nothing this build registered " +
                    "($preferred); keeping the library's own priorities, because disabling " +
                    "them all makes every call fail to negotiate media",
            )
            return
        }

        assignment.priorities.forEach { (codecId, priority) ->
            runCatching { set(codecId, priority) }
        }

        // A preference the build cannot honour is not an error on its own - the call still
        // connects on whatever else was offered - but it is never what the author meant, and
        // it used to be invisible. `CodecPreferences.DEFAULT` names H264 while the native
        // build sets PJMEDIA_HAS_OPENH264_CODEC to 0, so H264 was silently never negotiated
        // and an H264-only peer got no video at all.
        if (assignment.unmatchedPreferences.isNotEmpty()) {
            logger.warn(
                TAG,
                "$kind codecs preferred but not in this build: ${assignment.unmatchedPreferences}",
            )
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

            // This is the producer RESUMING had been missing since the state was written.
            // Without it the FSM never leaves Held(LOCAL): media comes back as
            // PJSUA_CALL_MEDIA_ACTIVE, the mapper is asked what STREAMS_RUNNING means
            // from Held(LOCAL), and `resumeEventFor` has no arm for it because the arm
            // it has is `Resuming` — the state only this line can produce. The re-INVITE
            // went out, the far end answered, the audio came back, and the app stayed
            // held for the rest of the call. Hold worked; resume was unreachable.
            //
            // Published *before* the send, on this thread. The answer is published from
            // PJSIP's worker thread, and publishing RESUMING after `reinvite` returned
            // would race it: a 200 OK processed in the gap would put STREAMS_RUNNING
            // ahead of RESUMING in the flow, and the FSM would read that as nothing and
            // then as a resume that never lands. Before the send there is no gap. What
            // it costs is a Resuming that lasts the length of a synchronous call that
            // either sends or throws — and a throw is answered below.
            //
            // A hold has no equivalent because it needs none: PJSIP reports
            // PJSUA_CALL_MEDIA_LOCAL_HOLD on its own and the mapper turns that into
            // LocalHold.
            call.pendingResume.begin()
            call.publish(StackCallState.RESUMING)

            runCatching {
                call.reinvite(call.info.resumeParams())
            }.onFailure {
                // Never left this device — a re-INVITE already in flight, most likely.
                // The call is exactly where it was, and the FSM has to be told so, or
                // the Resuming just published is the stuck state under a new name.
                call.pendingResume.cancel()
                call.publish(StackCallState.RESUME_FAILED)
            }.getOrThrow()
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
            val call = calls[callKey] ?: return@onPjsip

            // Recorded first, and whether or not there is a stream to act on. A mute
            // pressed before the media is up used to return here having done nothing,
            // while the engine had already moved its own state to muted - so the screen
            // said muted and the microphone was live. Now the intent is kept and
            // [PjCall.onCallMediaState] applies it to whatever stream arrives.
            call.microphoneMuted = muted

            val media = call.audioMedia ?: return@onPjsip
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
            val next = cameraAfter(running.vidDevManager(), call.captureDevice)
            if (next == null) {
                logger.info(TAG, "Only one camera on this device; nothing to switch to")
                return@onPjsip
            }
            // The preview is bound to the old device's window; PJSIP replaces that window
            // on the change, so the preview is stopped first and re-drawn on the new one.
            call.stopPreview()
            call.captureDevice = next
            call.vidSetStream(
                pjsua_call_vid_strm_op.PJSUA_CALL_VID_STRM_CHANGE_CAP_DEV,
                CallVidSetStreamParam().apply { capDev = next },
            )
            call.applyPreview()
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
            cameraWanted = capturing
            val op = if (capturing) {
                pjsua_call_vid_strm_op.PJSUA_CALL_VID_STRM_START_TRANSMIT
            } else {
                pjsua_call_vid_strm_op.PJSUA_CALL_VID_STRM_STOP_TRANSMIT
            }
            calls.values.forEach { call ->
                // The preview holds its own reference on the capture window (see
                // `PjCall.applyPreview`), so it goes first or the camera stays open.
                if (!capturing) call.stopPreview()
                runCatching { call.vidSetStream(op, CallVidSetStreamParam()) }
                if (capturing) call.applyPreview()
            }
        }
    }

    /**
     * What [setCameraCapturing] last asked for, so a video stream that appears *after* the
     * asking can be given the same answer.
     *
     * `START_TRANSMIT` is per stream, and `CameraPolicy` asks for the camera before the
     * INVITE is sent so the offer can carry video — at which point there is no stream, the
     * request is `PJ_ENOTFOUND`, and the `runCatching` above swallows it, correctly. With
     * `autoTransmitOutgoing` off (`AccountConfigFactory`, §5.2) nothing in PJSIP starts
     * capture when the stream is finally negotiated either, so until this existed a video
     * call placed as one connected with a renderer, an encoder, and no camera behind it:
     * `pjsua_vid.c` logged "Setting up TX.." and then nothing (TC15, 2026-09-11). The same
     * gap reopens on every re-INVITE, because `pjsua_vid_stop_stream` drops the capture
     * window and the rebuilt stream starts without one. `PjCall.onCallMediaState` reads
     * this when a video stream comes up and re-issues the start.
     *
     * Only the start is re-issued. A stream PJSIP builds with auto-transmit off is not
     * transmitting until told to, so a `false` here needs nothing done to it — and
     * `STOP_TRANSMIT` on a stream that never captured walks `dec_vid_win` with an invalid
     * window id when another call holds the preview, which is a crash rather than a no-op.
     */
    @Volatile
    private var cameraWanted = false

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
            calls.values.forEach {
                it.applyVideoWindows()
                it.applyPreview()
            }
        }
    }

    /**
     * Rotates what the cameras capture so a portrait call sends a portrait picture.
     *
     * Applied to every capture device rather than the one in use, and with `keep`, so
     * the setting survives a camera switch and reaches captures that have not started
     * yet — PJSIP holds it per device and applies it when the device opens. The mapping
     * from display rotation to `pjmedia_orient` is pjsua2's own Android sample's
     * (`CallActivity.updateCaptureOrientation`): the camera sensor sits landscape, so a
     * portrait screen (rotation 0) needs the frame turned 270°, and `android_dev.c:1023`
     * mirrors that for a back-facing camera on its own.
     */
    override fun setCaptureRotation(degrees: Int) {
        val orient = captureOrientFor(degrees) ?: return
        onPjsip("setCaptureRotation") {
            val manager = endpoint?.vidDevManager() ?: return@onPjsip
            manager.cameras().forEach { id ->
                runCatching { manager.setCaptureOrient(id, orient, true) }
                    .onFailure { logger.warn(TAG, "Camera $id did not take rotation $degrees: ${it.message}") }
            }
        }
    }

    // ------------------------------------------------------------------ recording

    /**
     * Records this call's media to [filePath] (Task 58).
     *
     * PJSIP has no per-call record flag. A recorder is a media port, so both legs are
     * transmitted into it: the far end's stream and this device's capture. Nothing here
     * decides whether recording is allowed — `RecordingPolicy` has already answered that.
     *
     * The answer is *waited for*. Posting the work and returning was the reason a refusal
     * never reached the screen: the recorder marked the call as recording the instant the
     * job was queued, so a call with no audio stream yet showed "Recording this call" over
     * a file nothing ever opened.
     */
    override suspend fun startRecording(callKey: String, filePath: String): Outcome<Unit, String> {
        val answer = CompletableDeferred<Outcome<Unit, String>>()
        onPjsip("startRecording") {
            val running = endpoint
            val media = calls[callKey]?.audioMedia
            answer.complete(
                when {
                    running == null -> failure("the stack is not running")
                    media == null -> failure("the call has no audio stream")
                    else -> openRecorder(media, running.audDevManager().captureDevMedia, filePath, logger)
                        .map { recorders[callKey] = it }
                },
            )
        }
        // Bounded, like every other wait in this app (§1.4). One thread serves pjsua2, a
        // media operation can be ahead of this one, and a wait with no end would hang the
        // caller's coroutine for the life of the process rather than report anything.
        return withTimeoutOrNull(RECORDING_START_TIMEOUT_MILLIS) { answer.await() }
            ?: failure("the stack did not answer in $RECORDING_START_TIMEOUT_MILLIS ms")
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

        /**
         * True once [removeAccount] has sent the un-REGISTER: the next registration
         * answer is the one that finishes the removal.
         */
        @Volatile
        var leaving: Boolean = false

        /** PJSIP thread only: [finishRemoval] runs once, whichever caller gets there first. */
        private var finished = false

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
            // Posted, not done here: this callback runs in the account's own native
            // frame, and deleting the object under it is the same crash as deleting a
            // call inside its callback. Whatever the answer was — a 2xx or a failure —
            // the registrar has spoken, and the account is done.
            if (leaving) onPjsip("finishRemoval") { finishRemoval("unregistration answered $code") }
        }

        /**
         * Shuts the account down and frees its native peer. Idempotent, so the answer
         * and the fallback timer can both call it.
         */
        fun finishRemoval(why: String) {
            if (finished) return
            finished = true
            logger.debug(TAG, "Releasing account $accountKey: $why")
            runCatching { shutdown() }
                .onFailure { logger.warn(TAG, "shutdown of $accountKey failed: ${it.message}") }
            release()
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

        /**
         * Whether the user has muted this call, independently of any one audio stream.
         *
         * The stream is not the place to keep it. [audioMedia] does not exist while the
         * call is still ringing, and it is replaced outright on every re-INVITE - a hold,
         * a resume, a codec renegotiation - so a mute applied only to the stream that
         * happened to exist at the time is a mute that the next negotiation quietly
         * undoes. That is what made the mute button look intermittent: it worked, and
         * then hold and resume put a live microphone back on the call.
         */
        @Volatile
        var microphoneMuted: Boolean = false

        /** The capture device this call is using, for [switchCamera] to cycle from. */
        @Volatile
        var captureDevice: Int = CAPTURE_DEVICE_DEFAULT

        /** True while a re-INVITE is held awaiting the user's answer (Task 54). */
        @Volatile
        var reinvitePending: Boolean = false

        /**
         * Our resume re-INVITE, between going out and being answered.
         *
         * Read by [onCallTsxState], which is otherwise told about every transaction on
         * the call and has no way to know which one anybody is waiting for. The rule
         * for what settles it is [PendingResume]'s, where it has a test.
         */
        val pendingResume = PendingResume()

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
            // CONNECTING is the moment between the 200 and the ACK, and which side sent
            // the 200 decides whether the media is negotiated by then (`sip_inv.c`):
            //
            //  - As the *caller* (UAC) it is not — the state is set first and the answer's
            //    SDP is processed after (`inv_on_state_early`, the RX_MSG branch). Reported
            //    as CONNECTED, the engine took it as the answer, read `videoActive` as
            //    false, and released the camera 12 ms after the far end accepted a video
            //    call (TC15, 2026-09-11, `set video stream, op=6`). The media-state
            //    callback and CONFIRMED both follow the update and are the ones to report.
            //  - As the *callee* (UAS) it is — `pjsip_inv_answer` negotiates while building
            //    the 200, before this state, so the media-state callback has already fired
            //    with the call still INCOMING and reported nothing that connects. Skipping
            //    CONNECTING here too left every answered incoming call stuck on the ringing
            //    screen: the second Answer got "already answered" (PJ_EINVALIDOP) and
            //    Decline the same (second TC15, 12:44 the same day). For the callee this
            //    *is* the answer.
            if (info.state == pjsip_inv_state.PJSIP_INV_STATE_CONNECTING &&
                info.role == pjsip_role_e.PJSIP_ROLE_UAC
            ) {
                return
            }
            publish(callStateOf(info), info)

            if (info.state == pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED) {
                pendingResume.cancel()
                stopPreview()
                // Only now. The native peer is finished with this director, and holding it
                // any longer is the leak; releasing it any earlier is a crash.
                calls -= callKey
                recorders -= callKey
                audioMedia = null
                // And freed on the PJSIP thread, after this callback has returned — not
                // from inside it, where the native frame is still this object's, and not
                // by the garbage collector, whose thread pjlib has never seen and whose
                // timing lets pjsua reuse this call's slot first. See [PjAccount.release].
                onPjsip("releaseCall") { runCatching { delete() } }
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
                            // Only when the user has not muted. This used to start the
                            // capture leg unconditionally, so every re-INVITE reconnected
                            // a microphone the user had switched off. Playback is
                            // unconditional: muting is about what leaves this device.
                            if (!microphoneMuted) {
                                running.audDevManager().captureDevMedia.startTransmit(stream)
                            }
                            stream.startTransmit(running.audDevManager().playbackDevMedia)
                        }.onFailure { logger.error(TAG, "Could not connect audio: ${it.message}") }
                    }

                    media.type == pjmedia_type.PJMEDIA_TYPE_VIDEO &&
                        media.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE -> {
                        // Only once capture is set up: before `START_TRANSMIT` PJSIP
                        // reports INVALID (-3) here, and a preview asked for on -3 fails.
                        if (media.videoCapDev != pjmedia_vid_dev_std_index.PJMEDIA_VID_INVALID_DEV) {
                            captureDevice = media.videoCapDev
                        }
                        applyVideoWindows()
                        if (media.dir and pjmedia_dir.PJMEDIA_DIR_ENCODING != 0) startTransmitIfWanted()
                    }
                }
            }
            val state = callStateOf(info)
            // Media running again is our resume landing; anything else, LOCAL_HOLD
            // restated mid-flight included, leaves it outstanding.
            pendingResume.onMediaState(state)
            publish(state, info)
        }

        /**
         * Notices the resume re-INVITE the far end refused (§2.1).
         *
         * The only signal there is: PJSIP reports a refused re-INVITE through no other
         * callback, for the reasons [PendingResume] gives. A call that moved to
         * `Resuming` on the strength of the request going out would otherwise stay
         * there for the rest of its life.
         *
         * The event body is only a transaction for a transaction-state event; pjsua2
         * leaves it default-constructed for the others, which is an empty method and
         * a status of 0, and [PendingResume.refusedBy] does not match either.
         */
        override fun onCallTsxState(prm: OnCallTsxStateParam) {
            if (!pendingResume.isOutstanding) return
            val tsx = runCatching { prm.e.body.tsxState.tsx }.getOrNull() ?: return
            val refused = pendingResume.refusedBy(
                isClient = tsx.role == pjsip_role_e.PJSIP_ROLE_UAC,
                method = tsx.method,
                statusCode = tsx.statusCode,
            )
            if (!refused) return

            logger.warn(TAG, "Resume refused on $callKey with ${tsx.statusCode}; the call is still held")
            publish(StackCallState.RESUME_FAILED)
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
                    state = TransferEventMapper.stateOf(prm.statusCode, prm.finalNotify),
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

        /**
         * Gives a video stream that has just come up the camera decision already made
         * (see [cameraWanted]). Posted rather than done inline: this runs inside PJSIP's
         * media-state callback, and `pjsua_call_set_vid_strm` re-acquires the call it is
         * being told about, which is safe on the same thread but clearer after the
         * callback has returned.
         */
        private fun startTransmitIfWanted() {
            if (!cameraWanted) return
            onPjsip("startTransmit") {
                if (!cameraWanted || !calls.containsKey(callKey)) return@onPjsip
                vidSetStream(
                    pjsua_call_vid_strm_op.PJSUA_CALL_VID_STRM_START_TRANSMIT,
                    CallVidSetStreamParam(),
                )
                applyPreview()
            }
        }

        private val localPreview = LocalPreview(logger)

        /**
         * Draws this device's own picture into the preview surface, or takes it down when
         * the surface is gone — see [LocalPreview] for why a sendrecv stream needs this
         * at all. Only while capture is running: started earlier, the preview would open
         * the camera itself, which is the decision `CameraPolicy` owns, and a surface
         * that arrives first is picked up when [startTransmitIfWanted] runs.
         */
        fun applyPreview() {
            val surface = previewSurface
            if (surface == null) {
                localPreview.stop()
                return
            }
            if (!isTransmittingVideo()) return
            localPreview.draw(captureDevice, surface)
        }

        fun stopPreview() = localPreview.stop()

        /**
         * Whether this call's video stream is encoding, i.e. capture has been set up.
         *
         * The stream is found from the call's own media list first, and the question is
         * only put to PJSIP when there is one. `vidStreamIsRunning(-1, …)` asks PJSIP to
         * find it, and on a call whose video has just been removed it finds nothing and
         * then *asserts* on the -1 it resolved to — `pjsua_vid.c:2873`, SIGABRT on the
         * PJSIP thread, the whole process gone the moment "Turn off my video" was pressed
         * (TC15, 2026-09-11 13:59). A library assertion is not an exception `runCatching`
         * can see.
         */
        private fun isTransmittingVideo(): Boolean {
            val info = infoOrNull() ?: return false
            val index = info.media.firstOrNull { media ->
                media.type == pjmedia_type.PJMEDIA_TYPE_VIDEO &&
                    media.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE &&
                    media.dir and pjmedia_dir.PJMEDIA_DIR_ENCODING != 0
            }?.index ?: return false
            return runCatching { vidStreamIsRunning(index.toInt(), pjmedia_dir.PJMEDIA_DIR_ENCODING) }
                .getOrDefault(false)
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
                    mediaEncrypted = encryptedAudio(info),
                ),
            )
        }

        /**
         * Whether this call's audio is actually running over SRTP.
         *
         * This used to be the literal `false`, with nothing anywhere that could make it
         * true - so `PjsipSipEngine.enforceMediaEncryption` saw "not encrypted" on every
         * call. On an account set to `SrtpPolicy.MANDATORY` that check terminates the
         * call, which means mandatory encryption did not fail closed on a cleartext peer:
         * it failed closed on *everything*, encrypted calls included. A control that
         * rejects the good case as well as the bad one is not a control, it is an outage.
         *
         * `StreamInfo.proto` is the negotiated transport profile and carries
         * `PJMEDIA_TP_PROFILE_SRTP` as a bit, which covers SAVP, SAVPF and DTLS-SRTP
         * alike - hence a mask rather than an equality against one constant.
         *
         * Every audio stream must be encrypted, not just one: a call with one secured
         * stream and one in the clear is not a secured call. `getStreamInfo` throws for a
         * media index the library has already released, and an exception escaping here
         * would read as "encrypted", so it is caught and answered `false` - the safe
         * direction for a check whose failure hangs the call up.
         */
        private fun encryptedAudio(info: CallInfo?): Boolean {
            // Live streams only. On DISCONNECTED the media is already gone and pjsua2
            // logs `pjsua_call_get_stream_info … PJ_EINVAL` at ERROR for every hangup —
            // an error about a stream nobody needs an answer for. There is nothing to
            // encrypt in a stream that is not running, so the answer is the same.
            val audio = info?.media
                ?.withIndex()
                ?.filter { (_, m) -> m.type == pjmedia_type.PJMEDIA_TYPE_AUDIO && m.isLive }
                .orEmpty()
            if (audio.isEmpty()) return false

            return audio.all { (index, _) ->
                runCatching {
                    (getStreamInfo(index.toLong()).proto and SRTP_PROFILE) != 0
                }.getOrDefault(false)
            }
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

    private fun callParams(videoEnabled: Boolean) = CallOpParam(true).apply {
        opt = CallSetting().apply {
            audioCount = 1
            // There is no `isVideoEnabled` in PJSIP. The stream count *is* the profile.
            videoCount = if (videoEnabled) 1L else 0L
            // How a lost packet gets repaired. `CallSetting()` zeroes this (only
            // `CallSetting(true)` takes pjsua's defaults, and those also offer a text
            // stream), and zero meant no `a=rtcp-fb:* nack pli` in the SDP: the decoder
            // could not ask for a keyframe and libvpx sends one on its own every 60 s, so
            // one lost packet on Wi-Fi left the far end a smear of macroblocks for up to
            // a minute (TC15, 0.4 % loss, 2026-09-11). PLI is the RTCP request, SIP INFO
            // the fallback for a peer without RTCP-FB; both are what pjsua defaults to.
            reqKeyframeMethod = (
                pjsua_vid_req_keyframe_method.PJSUA_VID_REQ_KEYFRAME_RTCP_PLI or
                    pjsua_vid_req_keyframe_method.PJSUA_VID_REQ_KEYFRAME_SIP_INFO
                ).toLong()
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
            // DoD 4. Every pjsua2 call in this class arrives through here, so this one line
            // is the whole enforcement point for the thread-confinement invariant. It is a
            // tautology today - `pjsip` is single-threaded, so the check cannot fail - and
            // that is exactly the point: the day somebody adds a second executor, or posts
            // one operation to Dispatchers.IO to "just get it working", this fails in the
            // debug build instead of becoming a SIGSEGV with no Kotlin frames weeks later.
            assertOnPjsipThread(what)

            // The throwable, not just its message. A message alone cannot say which frame
            // in a JNI call threw, and for an UnsatisfiedLinkError the frame is the answer.
            runCatching(block).onFailure { logger.error(TAG, "$what failed: ${it.message}", it) }
        }
    }

    /**
     * Runs [block] on the caller's thread, having first asserted it is the PJSIP one.
     *
     * For the paths that are ALREADY on the executor — a SWIG director callback, which
     * pjsua2 raises on its own registered thread — where re-posting through [onPjsip] would
     * deadlock or reorder. Visible to tests so DoD 4's "proven by a test that trips it
     * deliberately" has something to trip.
     */
    internal fun <T> requirePjsipThread(what: String, block: () -> T): T {
        assertOnPjsipThread(what)
        return block()
    }

    internal companion object {
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

        /** `pjmedia_tp_proto`'s SRTP bit. `StreamInfo.proto` is an Int, so this is too. */
        const val SRTP_PROFILE = pjmedia_tp_proto.PJMEDIA_TP_PROFILE_SRTP

        const val PJSIP_THREAD = "pjsip-main"

        /**
         * How long [startRecording] waits for the PJSIP thread before giving up.
         *
         * Matches the unregister acknowledgement in `PjsipSipEngine`: long enough that
         * a busy media thread is not mistaken for a refusal, short enough that the tap
         * that asked for the recording still gets an answer.
         */
        const val RECORDING_START_TIMEOUT_MILLIS = 5_000L
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
        /**
         * The jitter buffer's ceiling, in milliseconds.
         *
         * This was 500, under a comment observing that half a second of delay is already
         * a conversation people talk over - which is the right observation and the wrong
         * number. PJSIP's buffer is adaptive and grows toward this bound whenever it sees
         * jitter, so the ceiling is the worst delay a user can be made to hear, and on the
         * LAN these calls actually run on (25 ms round trip) half a second of it is all
         * cost and no benefit. The handset symptom was exactly that: audible lag on a call
         * between two phones on the same Wi-Fi.
         *
         * 200 ms is the usual VoIP ceiling: comfortably above the worst jitter a local
         * network produces, and below the ~250 ms where people start talking over each
         * other. A congested link will drop into concealment sooner than it used to, which
         * is the trade - and it is the right way round, because a brief artefact is
         * recoverable and a permanently late conversation is not.
         */
        const val JITTER_BUFFER_MAX_MS = 200

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

        /** `PJSUA_DTMF_METHOD_SIP_INFO`; RFC 2833 is method 0 and is `dialDtmf`'s default. */
        const val DTMF_METHOD_SIP_INFO = 1

        /** Whatever `AccountConfig.videoConfig.defaultCaptureDevice` resolved to. */
        const val CAPTURE_DEVICE_DEFAULT = -1

        /**
         * How long a removed account may wait for its un-REGISTER to be answered before
         * it is shut down regardless. Longer than `PjsipSipEngine`'s five-second wait,
         * so the ordinary path is the answer and this is only ever a registrar that has
         * gone away.
         */
        const val UNREGISTER_GRACE_MILLIS = 8_000L

        /** How long a refresh defers to PJSIP's own IP-change re-registration at most. */
        const val IP_CHANGE_GATE_MILLIS = 15_000L
    }
}

private const val SIP_OK = 200
private const val SIP_PROGRESS = 183
private const val SIP_ERROR_FLOOR = 300

// ---------------------------------------------------------------------- mapping
//
// Pure functions of pjsua2 values, kept outside the class: they read no gateway state,
// and detekt's LargeClass limit is the budget the class spends on the things that do.

/**
 * PJSIP's invite state as the app's.
 *
 * `CONNECTING` has no arm of its own and falls into `else`; `PjCall.onCallState` publishes
 * it only for the callee — see the comment there for what each side broke.
 */
private fun callStateOf(info: CallInfo): StackCallState = when (info.state) {
    pjsip_inv_state.PJSIP_INV_STATE_CALLING -> StackCallState.OUTGOING_INIT
    pjsip_inv_state.PJSIP_INV_STATE_INCOMING -> StackCallState.INCOMING_RECEIVED
    // EARLY is a 1xx in flight. For the callee that is the 180 *we* sent, and the call
    // is still an incoming one — the media-state callback fires in this state while the
    // 200 is being built, and reporting it as "ringing at the far end" was a wrong event
    // for an inbound call. For the caller, 180 is ringing and 183 with SDP is early
    // media, and the difference is audible.
    pjsip_inv_state.PJSIP_INV_STATE_EARLY -> when {
        info.role == pjsip_role_e.PJSIP_ROLE_UAS -> StackCallState.INCOMING_RECEIVED
        info.lastStatusCode == SIP_PROGRESS -> StackCallState.OUTGOING_EARLY_MEDIA
        else -> StackCallState.OUTGOING_RINGING
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

/**
 * The re-INVITE that lifts our hold, and only that.
 *
 * `PJSUA_CALL_UNHOLD` goes on `opt.flag`. It used to go on `CallOpParam.options`,
 * which `Call::reinvite` never reads — pjsua2 passes only `prm.opt` to
 * `pjsua_call_reinvite2` (`third_party/pjproject/pjsip/src/pjsua2/call.cpp:810-816`);
 * `options` is read by `setHold` and `xferReplaces` alone. With the flag missing,
 * `pjsua_call_reinvite2` took the `local_hold` branch and built the SDP *of a hold*
 * (`pjsua_call.c:3531-3532`), so the resume went out as `a=sendonly` — a second hold.
 *
 * The setting is the call's own, not `CallSetting(true)`. The default setting is
 * `aud_cnt=1, vid_cnt=1, txt_cnt=1` (`pjsua_call.c:656-670`), and `apply_call_setting`
 * replaces the call's setting and re-initialises its media to match
 * (`pjsua_call.c:699-745`) — which put an `m=video` and an `m=text` line into the
 * resume of an audio call. Measured on the handset on 2026-09-10: the hold re-INVITE
 * was answered 200 in 280 ms; the resume was 1852 bytes, escalated to TCP per
 * RFC 3261 §18.1.1, was refused there, fell back to an 1846-byte UDP datagram, and
 * got no response of any kind through seven retransmissions. Both halves of the
 * handoff's diagnosis — "the re-INVITE is sent and the media does resume on the
 * wire" — were wrong; nothing resumed, because nothing arrived.
 *
 * `CallInfo.setting` is `call->opt` verbatim (`pjsua_call.c:2556`), so the counts
 * this call was placed or answered with are exactly what is re-offered.
 */
private fun CallInfo.resumeParams(): CallOpParam {
    val resume = setting
    resume.flag = pjsua_call_flag.PJSUA_CALL_UNHOLD.toLong()
    return CallOpParam().apply { opt = resume }
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
private fun Endpoint.tuneOpus(logger: Logger) {
    runCatching {
        val opus = codecOpusConfig
        opus.sample_rate = RealPjsipCoreGateway.CORE_CLOCK_RATE
        opus.channel_cnt = RealPjsipCoreGateway.MONO
        opus.bit_rate = RealPjsipCoreGateway.OPUS_BITRATE
        opus.complexity = RealPjsipCoreGateway.OPUS_COMPLEXITY
        opus.packet_loss = RealPjsipCoreGateway.OPUS_EXPECTED_LOSS_PCT
        opus.cbr = false
        codecOpusConfig = opus
    }.onFailure {
        // Not fatal: a build without Opus still registers PCMU and G722, and a call
        // on those is worth more than no call. Loud, because it means the native
        // library was built without PJMEDIA_HAS_OPUS_CODEC and §5.2 is not being met.
        logger.error(RealPjsipCoreGateway.TAG, "Opus not configured - is it compiled in? ${it.message}")
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
private fun Endpoint.tuneVideoCodecs(logger: Logger) {
    videoCodecEnum2().forEach { info ->
        runCatching {
            val param = getVideoCodecParam(info.codecId)
            param.encFmt.apply {
                width = RealPjsipCoreGateway.VIDEO_WIDTH
                height = RealPjsipCoreGateway.VIDEO_HEIGHT
                fpsNum = RealPjsipCoreGateway.VIDEO_FPS
                fpsDenum = 1
                avgBps = RealPjsipCoreGateway.VIDEO_AVG_BPS
                maxBps = VIDEO_MAX_BPS
            }
            setVideoCodecParam(info.codecId, param)
        }.onFailure {
            logger.warn(RealPjsipCoreGateway.TAG, "Video codec ${info.codecId} kept its defaults: ${it.message}")
        }
    }
}

/**
 * Points the Lyra codec at its model files (ADR-008, Exit A).
 *
 * After `libInit`, which is when the codec registers and writes its *default* path —
 * the relative string `"model_coeffs"`, which exists nowhere on a device — and before
 * `libStart`. `lyra.cpp:199-203` writes that default at the end of init, so a path set
 * earlier is overwritten; a path set later is not read until the next stream.
 *
 * Returns what is wrong, or null. A problem is not fatal to the stack — every other
 * codec still works — but it is fatal to *this* codec in a way the registry cannot
 * show: `lyra/16000/1` registers from the library alone and then fails when a stream
 * opens. The audit carries the problem against the codec for exactly that reason.
 */
private fun Endpoint.tuneLyra(context: Context, logger: Logger): String? {
    val dir = File(context.filesDir, LyraModels.ASSET_DIR)
    val installed = LyraModels.install(
        open = { name -> runCatching { context.assets.open("${LyraModels.ASSET_DIR}/$name") }.getOrNull() },
        dir = dir,
    )
    if (installed != null) {
        logger.error(LYRA_TAG, "Lyra model files unusable: $installed")
        return installed
    }
    return runCatching {
        val lyra = codecLyraConfig
        lyra.modelPath = dir.absolutePath
        codecLyraConfig = lyra
    }.exceptionOrNull()?.let { failure ->
        // The library was built without PJMEDIA_HAS_LYRA_CODEC, or the setter refused
        // the path. Either way the codec cannot be used and config_site.h says it can.
        logger.error(LYRA_TAG, "Lyra not configured — is it compiled in? ${failure.message}")
        "pjsua2 refused the Lyra configuration: ${failure.message}"
    }
}

private const val LYRA_TAG = "PjsipGateway"

/**
 * The camera to switch to after [current], or null when there is nothing to switch to.
 *
 * Cameras only. PJSIP's device list also holds every renderer and its colour-bar
 * generator, and cycling through the whole list pointed the encoder at a device that
 * cannot capture. "Default" (-1) is an alias, not a position in the list, so it is
 * resolved to the device it stands for before the next one is looked up.
 */
private fun cameraAfter(manager: VidDevManager, current: Int): Int? {
    val cameras = manager.cameras()
    if (cameras.size < 2) return null
    val resolved = if (current == pjmedia_vid_dev_std_index.PJMEDIA_VID_DEFAULT_CAPTURE_DEV) {
        runCatching { manager.getDevInfo(current).id }.getOrDefault(current)
    } else {
        current
    }
    return cameras[(cameras.indexOf(resolved).coerceAtLeast(0) + 1) % cameras.size]
}

/**
 * The video registry with libvpx's VP8 ahead of Android MediaCodec's.
 *
 * Both register as `VP8`, and `CodecPriorities` offers same-name codecs in the order it is
 * given — so this is the one place that decides which VP8 the offer leads with. Software:
 * on the TC15 the MediaCodec decoder never produced a picture (`and_vid_mediacodec.cpp:
 * Decoder failed to get input Buffer`, every frame `PJ_ETOOSMALL`), while libvpx decoded
 * the same echoed stream at 1088×612 without a dropped frame (2026-09-11). MediaCodec's
 * VP8 stays registered, one step below, so a far end that only speaks to it still gets an
 * answer. Stable, so nothing else in the registry moves.
 */
private fun List<String>.softwareVp8First(): List<String> =
    sortedBy { if (it.equals(MEDIACODEC_VP8_ID, ignoreCase = true)) 1 else 0 }

/** `VP8/<PJMEDIA_RTP_PT_VP8_RSV1>`: the id `and_vid_mediacodec.cpp:76` registers its VP8 under. */
private const val MEDIACODEC_VP8_ID = "VP8/103"

/**
 * The indices of the real cameras: capture devices of the platform's camera driver.
 *
 * PJSIP's device list also holds every renderer and a colour-bar test-pattern generator,
 * and the generator reports itself as a capture device — so the first version of this
 * filtered on direction alone and the second "Flip" of a call pointed the encoder at
 * `Colorbar generator [Colorbar]` (TC15, 2026-09-11). The driver name is what tells the
 * camera apart: `android_dev.c` registers its devices under `Android`.
 */
private fun VidDevManager.cameras(): List<Int> = (0 until devCount.toInt()).filter { id ->
    runCatching {
        val info = getDevInfo(id)
        info.dir and pjmedia_dir.PJMEDIA_DIR_CAPTURE != 0 && info.driver.equals(CAMERA_DRIVER, ignoreCase = true)
    }.getOrDefault(false)
}

/** What `android_dev.c` calls its factory: `pj_ansi_strxcpy(info->driver, "Android", ...)`. */
private const val CAMERA_DRIVER = "Android"

/**
 * pjsua2's Android sample mapping from `Display.getRotation()` degrees to the capture
 * orientation, or null for a value that is not a rotation.
 */
private fun captureOrientFor(degrees: Int): Int? = when (degrees) {
    0 -> pjmedia_orient.PJMEDIA_ORIENT_ROTATE_270DEG
    QUARTER_TURN_DEGREES -> pjmedia_orient.PJMEDIA_ORIENT_NATURAL
    HALF_TURN_DEGREES -> pjmedia_orient.PJMEDIA_ORIENT_ROTATE_90DEG
    THREE_QUARTER_TURN_DEGREES -> pjmedia_orient.PJMEDIA_ORIENT_ROTATE_180DEG
    else -> null
}

private const val QUARTER_TURN_DEGREES = 90
private const val HALF_TURN_DEGREES = 180
private const val THREE_QUARTER_TURN_DEGREES = 270

/**
 * Opens a pjsua2 recorder on [filePath] and transmits both legs into it (Task 58).
 *
 * At file level rather than in `RealPjsipCoreGateway` because the class is already at
 * detekt's `LargeClass` bound, and because this needs nothing of the gateway but the two
 * media ports it is handed. Runs on the PJSIP thread — its caller is inside `onPjsip`.
 *
 * Total by construction: every path returns a value, including the throwing ones, because
 * the caller is completing a deferred that somebody is waiting on.
 */
private fun openRecorder(
    media: AudioMedia,
    captureDevMedia: AudioMedia,
    filePath: String,
    logger: Logger,
): Outcome<AudioMediaRecorder, String> {
    val recorder = AudioMediaRecorder()
    return runCatching {
        recorder.createRecorder(filePath)
        media.startTransmit(recorder)
        captureDevMedia.startTransmit(recorder)
    }.fold(
        onSuccess = { success(recorder) },
        onFailure = { thrown ->
            // Half-started is worse than not started: a port created and never handed back
            // is a conference slot `stopRecording` can no longer find, so it is freed here.
            runCatching { media.stopTransmit(recorder) }
            runCatching { recorder.delete() }
            logger.error(RealPjsipCoreGateway.TAG, "startRecording failed: ${thrown.message}", thrown)
            failure(thrown.message ?: thrown.javaClass.simpleName)
        },
    )
}
