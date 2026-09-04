package com.whatsappv2.data.settings

/**
 * Robolectric runs against a pinned SDK rather than the module's targetSdk: the
 * android-all jar for the newest platform is not always published when that platform
 * ships, and a test suite should not break the day compileSdk moves.
 */
internal const val ROBOLECTRIC_SDK = 34
