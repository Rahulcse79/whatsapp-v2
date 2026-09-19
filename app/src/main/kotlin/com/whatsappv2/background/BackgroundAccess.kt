package com.whatsappv2.background

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the platform will let the registration service keep running, and the one way
 * to ask it to.
 *
 * ## Why this is asked for at all
 *
 * The service holds a SIP registration so calls can arrive while the app is not on
 * screen. Battery optimisation is the platform's licence to end exactly that kind of
 * process, and on the handsets this app ships to — a Zebra, a Samsung — it is used
 * freely: a swiped or idle app is ended within minutes, and nothing re-registers when the
 * network comes back. The exemption is what stops that, and on Android 12+ it is also
 * what permits the service to be restarted from an alarm after the task is swiped away.
 *
 * ## Why it is a request and not a demand
 *
 * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` shows the system's own dialog, and the
 * user may say no. That answer is respected: the prompt is shown once, and the setting
 * stays reachable from Settings for whoever changes their mind. An app that asks on every
 * launch is an app people uninstall.
 *
 * Not a runtime permission, so it does not belong in `AppPermission`: there is no
 * `requestPermissions` call, no rationale flow, and no "denied permanently" state — only
 * a system switch that can be read and a screen that sets it.
 */
@Singleton
class BackgroundAccess @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** True when the app is on the battery-optimisation allowlist. */
    fun isExempt(): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun hasBeenAsked(): Boolean = preferences.getBoolean(ASKED_KEY, false)

    fun markAsked() = preferences.edit { putBoolean(ASKED_KEY, true) }

    /** The system's "Let app always run in background?" dialog for this package. */
    fun requestIntent(): Intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:${context.packageName}"))

    /** The full battery-optimisation list, for a device whose dialog is missing or refused. */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    private companion object {
        const val FILE_NAME = "background-access"
        const val ASKED_KEY = "asked"
    }
}

/**
 * When to show the prompt. Pure, so the rule is a test and not a screenshot.
 *
 * Only once somebody has logged in: a fresh install with no account has nothing to keep
 * running and no reason to explain itself. Only once ever: see [BackgroundAccess].
 */
object BackgroundAccessPolicy {
    fun shouldAsk(wantsRegistration: Boolean, exempt: Boolean, askedBefore: Boolean): Boolean =
        wantsRegistration && !exempt && !askedBefore
}
