package com.whatsappv2.domain.video

/**
 * Where [DevicePressure] comes from, behind an interface so the policy never sees Android.
 *
 * Two implementations and no more: the real one reads `PowerManager.getCurrentThermalStatus`
 * and this process's own CPU time, and the test one returns whatever the test said. The
 * interface exists for that second one — thermal throttling cannot be provoked in a unit
 * test, and a policy that could only be tested by warming a handset would not be tested.
 *
 * [sample] is called on the media controller's tick, which is PJSIP's executor thread, so
 * an implementation must not block: read a counter, return. Anything that needs a
 * subscription should keep its own last value and hand that over.
 */
interface DevicePressureSource {

    /** The pressure right now. Never throws — an unreadable source returns [DevicePressure]. */
    fun sample(): DevicePressure

    companion object {
        /**
         * A source that reports nothing, for a build or a test with no device behind it.
         *
         * Reporting *nothing* rather than refusing to answer is deliberate: adaptive
         * quality that cannot read the thermometer should still adapt on the network and
         * the encoder, which are the signals that matter most anyway.
         */
        val NONE: DevicePressureSource = object : DevicePressureSource {
            override fun sample(): DevicePressure = DevicePressure()
        }
    }
}
