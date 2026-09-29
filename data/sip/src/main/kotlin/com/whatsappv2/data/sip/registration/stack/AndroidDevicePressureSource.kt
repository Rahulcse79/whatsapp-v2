package com.whatsappv2.data.sip.registration.stack

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.whatsappv2.domain.video.DevicePressure
import com.whatsappv2.domain.video.DevicePressureSource
import com.whatsappv2.domain.video.ThermalPressure
import java.io.File

/**
 * Thermal status and process CPU load, read from the platform on every tick.
 *
 * ## Polled rather than subscribed
 *
 * `PowerManager.addThermalStatusListener` exists and is not used. The listener delivers on a
 * callback thread, so its value would have to be published across threads to be read from
 * PJSIP's executor, and the thing being published changes about once a minute while it is
 * read every two seconds — a subscription's whole advantage is spent on a value that is
 * cheap to ask for. `getCurrentThermalStatus` is a one-way binder call to a cached integer.
 *
 * ## Why CPU comes from /proc/self/stat
 *
 * There is no supported API for "how much CPU is this process using". `Debug.threadCpuTimeNanos`
 * covers the calling thread only, and the media pipeline's cost is spread across PJSIP's
 * executor, the camera's threads and Codec2's own. `/proc/self/stat` is per-process, is the
 * same file `dumpsys` reads, and needs no permission for one's own process.
 *
 * Phase 3 is the reason this is only ever a corroborating signal: the encode collapse there
 * happened with four and a half cores idle, because the contention was inside Codec2 and not
 * on the CPU. A policy that downgraded on load alone would have been reading the wrong
 * number confidently — which is why [com.whatsappv2.domain.video.AdaptiveVideoPolicy]
 * requires something else to agree before load counts at all.
 *
 * Not thread safe: it keeps the previous CPU reading to difference against, and is called
 * from the one tick that owns it.
 */
internal class AndroidDevicePressureSource(
    context: Context,
    private val statFile: File = File("/proc/self/stat"),
    private val cores: Int = Runtime.getRuntime().availableProcessors(),
    private val clockTicksPerSecond: Long = LINUX_CLOCK_TICKS_PER_SECOND,
) : DevicePressureSource {

    private val power: PowerManager? = context.getSystemService(PowerManager::class.java)

    private var previousTicks: Long = -1
    private var previousUptimeMillis: Long = 0

    override fun sample(): DevicePressure = DevicePressure(
        thermal = thermal(),
        cpuLoad = cpuLoad(),
    )

    /**
     * The platform's thermal verdict, or [ThermalPressure.NONE] where it has none.
     *
     * API 29. Below that the platform has no thermal status at all — not a cooler device, an
     * unmeasured one — and [ThermalPressure.NONE] is the honest answer: adaptation then runs
     * on the network and the encoder, which are the signals that matter most in any case.
     *
     * Anything above `THERMAL_STATUS_CRITICAL` maps to [ThermalPressure.CRITICAL] rather
     * than to a value this code has not heard of, so a platform that adds a level still
     * produces the most severe response this policy has.
     */
    private fun thermal(): ThermalPressure {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ThermalPressure.NONE
        val status = runCatching { power?.currentThermalStatus }.getOrNull() ?: return ThermalPressure.NONE
        return when (status) {
            PowerManager.THERMAL_STATUS_NONE -> ThermalPressure.NONE
            PowerManager.THERMAL_STATUS_LIGHT -> ThermalPressure.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalPressure.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalPressure.SEVERE
            else -> ThermalPressure.CRITICAL
        }
    }

    /**
     * Process CPU time since the last call, as a fraction of what every core could have
     * delivered in the same wall time.
     *
     * Divided by [cores], so the result is comparable across handsets and cannot exceed 1.0
     * — a four-party mesh measured at 280-340% of *one* core is about 0.4 of an eight-core
     * device, and a threshold written against the per-core figure would have fired on a
     * healthy call. The first call establishes the baseline and reports zero, because there
     * is no interval to divide by yet.
     */
    private fun cpuLoad(): Double {
        val ticks = processCpuTicks() ?: return 0.0
        val uptime = SystemClock.elapsedRealtime()

        val previous = previousTicks
        val previousUptime = previousUptimeMillis
        previousTicks = ticks
        previousUptimeMillis = uptime

        if (previous < 0) return 0.0
        val elapsedMillis = uptime - previousUptime
        if (elapsedMillis <= 0) return 0.0
        // A counter that went backwards means the file was misread, not that the process ran
        // negative CPU. Report nothing rather than a number that would read as an idle device.
        val usedTicks = ticks - previous
        if (usedTicks < 0) return 0.0

        val usedMillis = (usedTicks * 1_000.0) / clockTicksPerSecond
        return (usedMillis / (elapsedMillis.toDouble() * cores)).coerceIn(0.0, 1.0)
    }

    /**
     * `utime + stime` from `/proc/self/stat`, in clock ticks.
     *
     * Parsed from the *last* `)` rather than by splitting the whole line, because field 2 is
     * the executable name in parentheses and may itself contain spaces — the classic way to
     * misparse this file. After that closing paren the fields are space separated and
     * `utime` and `stime` are the 12th and 13th.
     */
    private fun processCpuTicks(): Long? = runCatching {
        val line = statFile.readText()
        val afterName = line.lastIndexOf(')')
        if (afterName < 0) return null
        val fields = line.substring(afterName + 1).trim().split(' ')
        // state is [0] here, so utime is [11] and stime [12] counting from the same origin.
        val utime = fields.getOrNull(UTIME_INDEX)?.toLongOrNull() ?: return null
        val stime = fields.getOrNull(UTIME_INDEX + 1)?.toLongOrNull() ?: return null
        utime + stime
    }.getOrNull()

    private companion object {
        /**
         * `sysconf(_SC_CLK_TCK)`, which is 100 on every Android ABI.
         *
         * Not readable from the JVM without NDK glue, and a wrong value here scales the load
         * rather than breaking it — so it is a named constant with its assumption stated,
         * and it is injectable for the test that pins the arithmetic.
         */
        const val LINUX_CLOCK_TICKS_PER_SECOND = 100L

        /** `utime`, counting from the field after the closing paren of the process name. */
        const val UTIME_INDEX = 11
    }
}
