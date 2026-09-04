package com.whatsappv2.domain.call

/**
 * Where call audio is playing (§5.2, DoD 8).
 *
 * A route, not a "speakerphone on" boolean: with a wired headset and Bluetooth both
 * connected there are four destinations, and two booleans cannot express which one is
 * active without inventing a precedence rule that will disagree with Telecom's.
 */
enum class AudioRoute {
    EARPIECE,
    SPEAKER,
    WIRED_HEADSET,
    BLUETOOTH,
}
