package com.whatsappv2.permission

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records which permissions have been asked for.
 *
 * Persisted, because the record is what separates "never asked" from "permanently
 * denied" — an in-memory flag would make every cold start look like a fresh install and
 * the app would never offer the settings route.
 *
 * Plain preferences rather than the encrypted store: this holds no secret, only whether
 * a dialog has been shown. Encrypting it would imply a sensitivity it does not have.
 */
@Singleton
class SharedPreferencesPermissionTracker @Inject constructor(
    context: Context,
) : PermissionRequestTracker {

    private val preferences: SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun hasBeenRequested(permission: AppPermission): Boolean =
        preferences.getBoolean(permission.name, false)

    override fun markRequested(permission: AppPermission) {
        preferences.edit { putBoolean(permission.name, true) }
    }

    override fun hasCompletedOnboarding(): Boolean =
        preferences.getBoolean(ONBOARDING_KEY, false)

    override fun markOnboardingComplete() {
        preferences.edit { putBoolean(ONBOARDING_KEY, true) }
    }

    private companion object {
        const val FILE_NAME = "permission-requests"

        /**
         * Prefixed so it cannot collide with an [AppPermission] name (Task 72).
         *
         * The per-permission records are keyed by enum name in this same file, and a
         * future permission called ONBOARDING_COMPLETE would otherwise read this flag.
         */
        const val ONBOARDING_KEY = "onboarding.complete"
    }
}
