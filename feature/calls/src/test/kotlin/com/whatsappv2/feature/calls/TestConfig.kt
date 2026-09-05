package com.whatsappv2.feature.calls

/**
 * Robolectric runs against a pinned SDK rather than the module's targetSdk: the
 * android-all jar for the newest platform is not always published when that platform
 * ships, and a test suite should not break the day compileSdk moves.
 */
internal const val CALLS_ROBOLECTRIC_SDK = 34

/**
 * The screen these tests lay out on.
 *
 * Robolectric's default is 320x470dp, which is smaller than any phone shipped this
 * decade and short enough that the bottom of a full-height screen is laid out with no
 * height at all — the call button reports itself undisplayed, and a tap on it does
 * nothing. Asserting a layout against that screen tests the emulator, not the app.
 */
internal const val CALLS_SCREEN = "w411dp-h891dp"
