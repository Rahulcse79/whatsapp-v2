package com.whatsappv2.permission

import android.Manifest
import android.os.Build

/**
 * What the app does when a permission is refused.
 *
 * Every permission has one, deliberately. "Request the permission and hope" is how apps
 * end up dead-ended on a screen with no explanation, and §6 requires honest state rather
 * than a silent blank.
 */
enum class DenialBehaviour {
    /**
     * The feature cannot work at all. The app stays usable, but the affected action is
     * blocked with an explanation rather than failing silently.
     */
    BLOCKS_FEATURE,

    /** The feature continues with reduced capability. */
    DEGRADES_GRACEFULLY,
}

/**
 * Every runtime permission the app can ask for, with the reason it is needed and what
 * happens if the user says no.
 *
 * Rationale strings live here rather than in the UI so that the reason a permission is
 * requested and the consequence of refusing it are stated in one place. When they are
 * split, the dialog and the fallback drift apart, and the user is told one thing and
 * shown another.
 */
enum class AppPermission(
    val manifestPermission: String,
    val title: String,
    val rationale: String,
    val denialBehaviour: DenialBehaviour,
    /** What the app does after a refusal, shown to the user rather than hidden. */
    val deniedExplanation: String,
    /** Android version this permission first requires. Below it, treat as granted. */
    val minimumSdk: Int = Build.VERSION_CODES.BASE,
    /**
     * False for install-time permissions. Requesting one at run time returns granted
     * immediately, which looks like a working flow while proving nothing.
     */
    val isRuntimePermission: Boolean = true,
) {
    RECORD_AUDIO(
        manifestPermission = Manifest.permission.RECORD_AUDIO,
        title = "Microphone access",
        rationale = "Calls need the microphone so the person you are speaking to can hear you. " +
            "Audio is never recorded unless you start a recording yourself.",
        denialBehaviour = DenialBehaviour.BLOCKS_FEATURE,
        deniedExplanation = "Without microphone access you can receive calls but nobody will hear " +
            "you, so calling is disabled.",
    ),

    CAMERA(
        manifestPermission = Manifest.permission.CAMERA,
        title = "Camera access",
        rationale = "Video calls need the camera. Audio calls do not use it.",
        denialBehaviour = DenialBehaviour.DEGRADES_GRACEFULLY,
        deniedExplanation = "Calls will connect as audio only. You can still see incoming video.",
    ),

    POST_NOTIFICATIONS(
        manifestPermission = Manifest.permission.POST_NOTIFICATIONS,
        title = "Notifications",
        rationale = "Incoming calls are shown as a notification so you can answer without opening " +
            "the app.",
        denialBehaviour = DenialBehaviour.DEGRADES_GRACEFULLY,
        deniedExplanation = "Calls will still ring on the lock screen, but you will not see missed " +
            "call or registration notifications.",
        minimumSdk = Build.VERSION_CODES.TIRAMISU,
    ),

    BLUETOOTH_CONNECT(
        manifestPermission = Manifest.permission.BLUETOOTH_CONNECT,
        title = "Bluetooth devices",
        rationale = "Needed to route call audio to a paired headset or car kit.",
        denialBehaviour = DenialBehaviour.DEGRADES_GRACEFULLY,
        deniedExplanation = "Call audio will use the earpiece or speaker. Bluetooth headsets will " +
            "not be offered.",
        minimumSdk = Build.VERSION_CODES.S,
    ),

    READ_CONTACTS(
        manifestPermission = Manifest.permission.READ_CONTACTS,
        title = "Contacts",
        rationale = "Lets the app show a caller's name instead of just their number, and lets you " +
            "call people from your contact list. Contacts never leave the device.",
        denialBehaviour = DenialBehaviour.DEGRADES_GRACEFULLY,
        deniedExplanation = "Calls will show the SIP address rather than a name. Everything else " +
            "works normally.",
    ),

    /**
     * Install-time, not runtime: `MANAGE_OWN_CALLS` is declared `normal` protection level
     * and is granted when the app is installed. Modelled here so the set is complete and
     * so nobody later adds a runtime request for it — that would appear to succeed while
     * verifying nothing.
     */
    MANAGE_OWN_CALLS(
        manifestPermission = Manifest.permission.MANAGE_OWN_CALLS,
        title = "Manage calls",
        rationale = "Lets calls appear in the system call UI alongside mobile calls.",
        denialBehaviour = DenialBehaviour.BLOCKS_FEATURE,
        deniedExplanation = "Granted at install time; it cannot be refused separately.",
        isRuntimePermission = false,
    ),
    ;

    /** False on versions where the permission does not exist and is implicitly held. */
    val appliesToThisDevice: Boolean get() = Build.VERSION.SDK_INT >= minimumSdk

    /** True when refusing this leaves the app usable. */
    val isOptional: Boolean get() = denialBehaviour == DenialBehaviour.DEGRADES_GRACEFULLY
}
