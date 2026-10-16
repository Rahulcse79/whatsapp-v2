package com.whatsappv2.shared.ios

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.AudioCodec
import com.whatsappv2.domain.model.CodecPreferences
import com.whatsappv2.domain.model.DialledTarget
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.model.Transport
import com.whatsappv2.domain.model.VideoCodec
import com.whatsappv2.domain.registration.RegistrationBackoff
import com.whatsappv2.domain.validation.AccountField
import com.whatsappv2.domain.validation.AccountValidator
import com.whatsappv2.domain.validation.AccountViolation
import com.whatsappv2.domain.validation.SipAccountDraft
import com.whatsappv2.domain.video.VideoFrameRate
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The shared logic, in the shapes Swift can actually hold.
 *
 * ## Why this file exists rather than Swift calling the domain types directly
 *
 * Kotlin/Native's Objective-C export is lossy in three places this codebase lands on
 * immediately, and each of them turns a clean Kotlin API into something a Swift caller
 * has to work around at every call site:
 *
 *  1. **`kotlin.time.Duration` is a value class over a packed `Long`.** It exports as a
 *     raw `Int64` whose encoding — the low bit selects milliseconds or nanoseconds — is
 *     an implementation detail. Swift receiving that number and dividing by 1_000_000 is
 *     wrong for every duration on one side of the representation switch, silently. So
 *     durations cross this boundary as `Double` seconds.
 *
 *  2. **`Outcome<T, E>` is a generic sealed interface.** Generics on an exported
 *     interface erase to `id`, so Swift gets `Any` and casts twice — once for the branch
 *     and once for the payload — with no compiler help when it casts to the wrong thing.
 *     So results cross as small concrete classes carrying a `Bool` and two lists.
 *
 *  3. **`@JvmInline value class` erases to its underlying type.** `AccountId("x")` and a
 *     bare `"x"` are the same thing to Swift, which is exactly the confusion the value
 *     class exists to prevent on the Kotlin side.
 *
 * ## Why commonMain and not iosMain
 *
 * Nothing in this file is Apple-specific — there is not a `platform.*` import in it. In
 * `iosMain` it would compile only on a machine with Xcode, which is the one place this
 * project cannot currently build; in `commonMain` it is type-checked by the JVM target
 * on every ordinary `./gradlew :shared:jvmTest`, and its tests run on both. A facade
 * whose whole job is to be correct at a boundary should be the best-tested file in the
 * module, not the least.
 *
 * ## What it is not
 *
 * Not a place for logic. Every function here delegates. A rule that appears in this file
 * rather than being called from it is a rule Android does not have, which is the one
 * thing this module exists to prevent.
 */
object SharedFacade {

    // ------------------------------------------------------------------ dialling

    /**
     * Completes a dialled string into a full SIP URI, or null when it cannot be one.
     *
     * The same rule the Android dialler uses, carried port and all: an account
     * registered on 5070 must send its INVITEs to 5070, and the defect that made this
     * worth stating was a `DialledTarget` that completed from the domain alone.
     */
    fun resolveDialledTarget(
        input: String,
        accountDomain: String,
        accountPort: Int,
    ): String? = DialledTarget
        .resolve(input, accountDomain, accountPort.takeIf { it > 0 })
        ?.render()

    /** True when [input] parses as a SIP URI. For live validation in the dial field. */
    fun isValidSipUri(input: String): Boolean = SipUri.parse(input) is Outcome.Success

    /**
     * The short label for a URI — the user part, falling back to the host.
     *
     * A call list wants `7001`, not `sip:7001@192.168.80.145`: on one deployment every
     * row shares the host, so the two thirds of the string nobody reads are the two
     * thirds that push the name off the screen.
     */
    fun labelForUri(uri: String): String = when (val parsed = SipUri.parse(uri)) {
        is Outcome.Success -> parsed.value.label()
        is Outcome.Failure -> uri
    }

    // ------------------------------------------------------------------ registration

    /**
     * Seconds to wait before retry number [attempt], zero-based.
     *
     * [retryAfterSeconds] is the server's `Retry-After` when it sent one; 0 means none.
     * [seed] makes the jitter reproducible in a test and must stay 0 in the app, where a
     * fresh random is what stops every handset in a building retrying in the same second
     * after an outage.
     */
    fun registrationRetryDelaySeconds(
        attempt: Int,
        retryAfterSeconds: Int = 0,
        seed: Int = 0,
    ): Double {
        val retryAfter: Duration? = retryAfterSeconds.takeIf { it > 0 }?.seconds
        val random = if (seed == 0) Random.Default else Random(seed)
        return RegistrationBackoff()
            .delayFor(attempt, retryAfter, random)
            .inWholeMilliseconds / MILLIS_PER_SECOND
    }

    /** The upper bound of the retry window for [attempt], in seconds. */
    fun registrationRetryWindowSeconds(attempt: Int): Double =
        RegistrationBackoff().windowFor(attempt).inWholeMilliseconds / MILLIS_PER_SECOND

    // ------------------------------------------------------------------ validation

    /**
     * Validates an account the user is editing.
     *
     * Takes the fields as strings, exactly as the form holds them, because that is what
     * [SipAccountDraft] is for: the parsing and the range checks *are* the validation,
     * and a facade that accepted an already-parsed port would have done the interesting
     * half of the job in Swift.
     */
    @Suppress("LongParameterList")
    fun validateAccount(
        accountId: String,
        label: String,
        username: String,
        authUsername: String,
        password: String,
        displayName: String,
        domain: String,
        registrar: String,
        outboundProxy: String,
        port: String,
        transportToken: String,
        registrationExpirySeconds: String,
        stunServer: String,
    ): ValidationReport {
        val draft = SipAccountDraft(
            id = AccountId(accountId.ifBlank { "draft" }),
            label = label,
            username = username,
            authUsername = authUsername,
            password = Secret(password),
            displayName = displayName,
            domain = domain,
            registrar = registrar,
            outboundProxy = outboundProxy,
            port = port,
            transport = Transport.fromToken(transportToken) ?: Transport.UDP,
            registrationExpirySeconds = registrationExpirySeconds,
            stunServer = stunServer,
        )
        return when (val outcome = AccountValidator.validate(draft)) {
            is Outcome.Success -> ValidationReport(
                isValid = true,
                violations = emptyList(),
                // Warnings are AccountViolations too — "TLS on port 5060" is legal and
                // almost certainly a mistake — so they get the same wording as errors
                // and differ only in which list they are in.
                warnings = outcome.value.warnings.map { it.toReport().message },
            )
            is Outcome.Failure -> ValidationReport(
                isValid = false,
                violations = outcome.error.map { it.toReport() },
                warnings = emptyList(),
            )
        }
    }

    // ------------------------------------------------------------------ codecs

    /** The default audio codec order, highest preference first, as payload names. */
    fun defaultAudioCodecNames(): List<String> =
        CodecPreferences.DEFAULT.audio.map(AudioCodec::payloadName)

    /** The default video codec order, highest preference first, as payload names. */
    fun defaultVideoCodecNames(): List<String> =
        CodecPreferences.DEFAULT.video.map(VideoCodec::payloadName)

    /**
     * Every audio codec the domain knows about, whether or not this build compiled it.
     *
     * The difference between this list and what `PjsipBridge.enumerateAudioCodecs`
     * reports is the honest answer to "which preferences are being silently dropped" —
     * the same question `CodecAudit` answers on Android.
     */
    fun allAudioCodecNames(): List<String> = AudioCodec.entries.map(AudioCodec::payloadName)

    /** Every video codec the domain knows about. */
    fun allVideoCodecNames(): List<String> = VideoCodec.entries.map(VideoCodec::payloadName)

    // ------------------------------------------------------------------ video quality

    /**
     * The frame rates the quality setting may take, lowest first.
     *
     * The adaptive ladder itself (`AdaptiveVideoPolicy`) is NOT exposed here. It is a
     * stateful object that consumes a smoothed `VideoConditions` every sample and
     * returns a rung; flattening that into primitives would either lose the smoothing or
     * move it to Swift, and moving it to Swift is how the two platforms stop agreeing.
     * Wiring it is the next piece of work, and until then iOS encodes at the fixed rate
     * the user chose — which is what Android did before the ladder landed.
     */
    fun videoFrameRateOptions(): List<Int> = VideoFrameRate.entries.map { it.fps }

    private const val MILLIS_PER_SECOND = 1000.0
}

/** One field-level problem with an account form, flattened for Swift. */
data class ViolationReport(
    /** The [AccountField] name, e.g. `USERNAME`. The form keys on it to mark the field. */
    val field: String,
    /** What is wrong, already in words a user reads. */
    val message: String,
)

/** The result of validating an account form. */
data class ValidationReport(
    val isValid: Boolean,
    val violations: List<ViolationReport>,
    /** Legal, but probably not meant — TLS on port 5060, say. */
    val warnings: List<String>,
)

private fun AccountViolation.toReport(): ViolationReport = ViolationReport(
    field = field.name,
    message = when (this) {
        is AccountViolation.Required -> "${field.label} is required"
        is AccountViolation.NotANumber -> "${field.label} must be a number"
        is AccountViolation.OutOfRange -> "${field.label} is out of range"
        is AccountViolation.Malformed -> "${field.label} is not valid"
        is AccountViolation.Conflict -> detail
    },
)

/** A readable name for a field, for a message a person reads. */
private val AccountField.label: String
    get() = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
