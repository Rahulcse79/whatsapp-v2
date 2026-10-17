package com.whatsappv2.domain.video

/**
 * How hot the device says it is.
 *
 * Mirrors Android's `PowerManager.THERMAL_STATUS_*` without importing it, so the policy
 * stays on `:domain`'s pure-JVM classpath and a test can be severe without a device. The
 * Android side maps the platform constant onto this and is the only thing that knows the
 * mapping — see `AndroidDevicePressureSource`.
 *
 * Ordered coolest first, and compared with `>=` throughout: a status the platform adds
 * above [CRITICAL] maps onto [CRITICAL] rather than onto a value the policy has never
 * heard of.
 */
enum class ThermalPressure {
    /** `THERMAL_STATUS_NONE`, or a platform too old to have been asked. */
    NONE,

    /** `THERMAL_STATUS_LIGHT`. Nothing has been throttled yet. */
    LIGHT,

    /** `THERMAL_STATUS_MODERATE`. Throttling has begun and is not yet user-visible. */
    MODERATE,

    /** `THERMAL_STATUS_SEVERE`. The platform is shedding clock. Video should give way. */
    SEVERE,

    /** `THERMAL_STATUS_CRITICAL` and above. Anything still running is being cut back. */
    CRITICAL,
}

/**
 * The wire, as RTCP describes it.
 *
 * Fractions rather than counts: the policy compares against thresholds that have to mean
 * the same thing at 15 fps and 30 fps, and a count does not. Converting from PJSIP's
 * cumulative counters is the collector's job.
 */
data class NetworkConditions(
    /** Outbound loss as reported by the far end's receiver reports, 0.0 to 1.0. */
    val lossFraction: Double = 0.0,
    /** Round trip, milliseconds, from RTCP. */
    val rttMillis: Double = 0.0,
    /** Inbound jitter, milliseconds, already a running mean in `pjsua2`. */
    val jitterMillis: Double = 0.0,
    /** Measured outbound video bitrate over the sample interval. */
    val txBitrateBps: Int = 0,
    /**
     * PLI/FIR/NACK arrivals per second from the far end.
     *
     * The far end asking for a keyframe means it lost its reference chain, which is the
     * receiver's account of loss that its receiver reports may not have caught up with
     * yet. Rate, not count, for the same reason as [lossFraction].
     */
    val feedbackPerSecond: Double = 0.0,
)

/**
 * What the encoder is actually managing, against what it was asked for.
 *
 * Ratios against the configured rate rather than absolute frame rates, because a tier
 * change moves the target and a threshold in absolute fps would silently mean something
 * different on every rung. 1.0 is "keeping up".
 *
 * These come from the native `vidcnt` counters, whose real meaning Phase 3 had to
 * establish: `encoded` counts `encode_begin` calls that returned success, *including the
 * ones that returned nothing to send*, so it read 30/s on a handset emitting 4.4 pictures
 * a second. [actualFpsRatio] is built from `encf` — calls that produced a payload — and
 * that distinction is the difference between this policy working and it never firing.
 */
data class EncoderConditions(
    /** Measured output frames per second over the configured frame rate. */
    val actualFpsRatio: Double = 1.0,
    /** Mean wall clock inside `encode_begin`, milliseconds. */
    val latencyMillis: Double = 0.0,
    /** Fraction of calls that found no input frame waiting — `ein` against `ebeg`. */
    val inputStarvationRatio: Double = 0.0,
    /**
     * Fraction of calls that found no free output buffer.
     *
     * Phase 3's deadlock lived here: stranded output indices exhaust the component's pool
     * and it then withholds *input*, which presented as input starvation. The fix
     * decoupled the two, so a non-zero value now means genuine pressure rather than the
     * bug — and if it climbs with the pipeline otherwise healthy, that is a correctness
     * regression to fix rather than a reason to drop quality.
     */
    val outputStarvationRatio: Double = 0.0,
)

/**
 * What the decoder is managing, per incoming leg, already reduced to the worst one.
 *
 * The worst leg rather than the mean: in a mesh, one peer's stream collapsing is exactly
 * the case a mean hides, and a tile that is frozen is not made acceptable by two that are
 * not.
 */
data class DecoderConditions(
    /** Decoded frames per second over the rate the incoming stream is negotiated at. */
    val actualFpsRatio: Double = 1.0,
    /** Pictures dropped before MediaCodec because they were incomplete, as a fraction. */
    val incompletePictureRatio: Double = 0.0,
    /** Fraction of pulls where the jitter buffer had nothing to give. */
    val starvationRatio: Double = 0.0,
)

/** Device-wide pressure that no single stream owns. */
data class DevicePressure(
    val thermal: ThermalPressure = ThermalPressure.NONE,
    /**
     * Process CPU load as a fraction of what this device can deliver, 0.0 to 1.0.
     *
     * A fraction of *total capacity*, not of one core: a four-party mesh was measured at
     * 280-340% of a core and that was never the wall, so a number that can exceed 1.0
     * would invite exactly the wrong conclusion. Phase 3 proved the encode collapse
     * happened with four and a half cores idle — which is why this is one input among
     * several and never on its own sufficient to drop a tier.
     */
    val cpuLoad: Double = 0.0,
)

/**
 * One smoothed view of everything the policy is allowed to look at.
 *
 * Assembled by [VideoConditionsSmoother] from raw samples. Every field is already an
 * exponentially weighted mean except [shape], [budget] and [displayCeiling], which are
 * facts about the call rather than measurements and change discretely.
 */
data class VideoConditions(
    val shape: CallShape,
    val budget: VideoBudget,
    val network: NetworkConditions = NetworkConditions(),
    val encoder: EncoderConditions = EncoderConditions(),
    val decoder: DecoderConditions = DecoderConditions(),
    val device: DevicePressure = DevicePressure(),
    val displayCeiling: DisplayCeiling = DisplayCeiling.UNCONSTRAINED,
)

/**
 * Exponentially weighted smoothing, so one bad sample is never a tier change.
 *
 * ## Why EWMA and not a rolling window
 *
 * A rolling window of N samples has to hold them, and every threshold then has a second
 * hidden parameter — how many of the N must breach. An exponentially weighted mean has one
 * parameter, needs no storage, and its response time is statable: at [ALPHA] = 0.4 a step
 * change is 87% expressed after four samples, which at the controller's two-second tick is
 * eight seconds, comfortably inside the upgrade window and just past the downgrade one.
 *
 * ## Why this is not the whole of the hysteresis
 *
 * Smoothing decides *what the conditions are*. It deliberately does not decide when to
 * act: that is [AdaptiveVideoPolicy]'s breach clock, and keeping the two apart is what
 * makes "conditions are bad" and "conditions have been bad long enough" separately
 * testable. Smoothing alone would still act on a single sample, just a scaled one.
 *
 * Not thread safe. It is fed from one place — the media controller's tick, on PJSIP's
 * executor — and a mutex here would only hide a caller that had stopped doing that.
 */
class VideoConditionsSmoother(private val alpha: Double = ALPHA) {

    init {
        require(alpha > 0.0 && alpha <= 1.0) { "alpha must be in (0, 1], was $alpha" }
    }

    private var smoothed: VideoConditions? = null

    /** How many samples have been folded in. The policy refuses to act below [MIN_SAMPLES]. */
    var samples: Int = 0
        private set

    /** The current smoothed view, or null before the first sample. */
    val current: VideoConditions? get() = smoothed

    /**
     * Folds [raw] in and returns the new smoothed view.
     *
     * The first sample is taken whole — there is nothing to weight it against, and seeding
     * from zero would spend the first several seconds of every call claiming the network
     * was perfect and the encoder idle.
     */
    fun accept(raw: VideoConditions): VideoConditions {
        val previous = smoothed
        samples++
        val next = if (previous == null) raw else blend(previous, raw)
        smoothed = next
        return next
    }

    /**
     * Discards the smoothed state, keeping nothing.
     *
     * Called when the media underneath changes shape — video off and on again, a hold, a
     * participant joining — because the mean of a stream that has stopped is not evidence
     * about the stream that replaced it. Phase 3's harness taught the same lesson about
     * measurements carried across a restart: they look valid and are not.
     */
    fun reset() {
        smoothed = null
        samples = 0
    }

    private fun blend(old: VideoConditions, new: VideoConditions): VideoConditions {
        fun mix(o: Double, n: Double) = o + alpha * (n - o)
        return new.copy(
            network = NetworkConditions(
                lossFraction = mix(old.network.lossFraction, new.network.lossFraction),
                rttMillis = mix(old.network.rttMillis, new.network.rttMillis),
                jitterMillis = mix(old.network.jitterMillis, new.network.jitterMillis),
                txBitrateBps = mix(
                    old.network.txBitrateBps.toDouble(),
                    new.network.txBitrateBps.toDouble(),
                ).toInt(),
                feedbackPerSecond = mix(old.network.feedbackPerSecond, new.network.feedbackPerSecond),
            ),
            encoder = EncoderConditions(
                actualFpsRatio = mix(old.encoder.actualFpsRatio, new.encoder.actualFpsRatio),
                latencyMillis = mix(old.encoder.latencyMillis, new.encoder.latencyMillis),
                inputStarvationRatio = mix(
                    old.encoder.inputStarvationRatio,
                    new.encoder.inputStarvationRatio,
                ),
                outputStarvationRatio = mix(
                    old.encoder.outputStarvationRatio,
                    new.encoder.outputStarvationRatio,
                ),
            ),
            decoder = DecoderConditions(
                actualFpsRatio = mix(old.decoder.actualFpsRatio, new.decoder.actualFpsRatio),
                incompletePictureRatio = mix(
                    old.decoder.incompletePictureRatio,
                    new.decoder.incompletePictureRatio,
                ),
                starvationRatio = mix(old.decoder.starvationRatio, new.decoder.starvationRatio),
            ),
            // Thermal is a platform verdict, not a measurement: smoothing it would delay
            // acting on SEVERE for no gain, and the platform has already debounced it.
            device = DevicePressure(
                thermal = new.device.thermal,
                cpuLoad = mix(old.device.cpuLoad, new.device.cpuLoad),
            ),
        )
    }

    companion object {
        /** See the class doc: 87% of a step change expressed in four samples. */
        const val ALPHA = 0.4

        /**
         * Samples needed before the policy will act at all.
         *
         * Two, so that the first reading of a fresh stream — which is taken against
         * counters that started at zero moments earlier and is therefore nearly always
         * pessimistic — can never on its own move a tier.
         */
        const val MIN_SAMPLES = 2
    }
}
