package com.whatsappv2.onboarding

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the user has already been through on their way into the app.
 *
 * Two facts, kept apart on purpose. Accepting terms is a decision the user made about a
 * document; finishing the tour is a thing they have seen. Collapsing them into one
 * "onboarded" flag would mean that adding a tour page, or amending the terms, could only
 * be handled by showing both again — and re-presenting a legal document because the
 * marketing copy changed is the wrong way round.
 *
 * Plain preferences rather than the encrypted store, for the same reason
 * `SharedPreferencesPermissionTracker` uses them: this holds no secret, only whether a
 * screen has been shown. Encrypting it would imply a sensitivity it does not have.
 *
 * Uninstalling clears it, which is the documented behaviour — the terms are shown again on
 * a reinstall, which is correct, because a fresh install is a fresh agreement.
 */
@Singleton
class FirstRunStore @Inject constructor(@ApplicationContext context: Context) {

    private val preferences: SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * True once the user has accepted the terms **at the version they were shown**.
     *
     * Versioned rather than boolean: terms that change are terms that have to be accepted
     * again, and a plain flag cannot express that. Storing the accepted version means
     * raising [TERMS_VERSION] is the whole of what a future amendment costs.
     */
    fun hasAcceptedTerms(): Boolean =
        termsAreCurrent(accepted = preferences.getInt(KEY_TERMS_VERSION, 0), shipped = TERMS_VERSION)

    fun acceptTerms() = preferences.edit { putInt(KEY_TERMS_VERSION, TERMS_VERSION) }

    fun hasSeenTour(): Boolean = preferences.getBoolean(KEY_TOUR, false)

    fun markTourSeen() = preferences.edit { putBoolean(KEY_TOUR, true) }

    /** Puts the app back to its first-run state. For a "show me that again" control, and for tests. */
    fun reset() = preferences.edit { clear() }

    companion object {
        /**
         * The version of the terms this build ships.
         *
         * Raise it when the text in [TermsText] changes in a way a user would want to be
         * told about. Everyone who accepted an older version is asked again; nobody who
         * accepted this one is.
         */
        const val TERMS_VERSION = 1

        private const val FILE_NAME = "first-run"
        private const val KEY_TERMS_VERSION = "terms.accepted.version"
        private const val KEY_TOUR = "tour.seen"
    }
}

/**
 * Whether an acceptance still stands against the terms this build ships.
 *
 * Its own function because it is the whole of the versioning rule, and a rule that lives
 * only inside a `SharedPreferences` read can only be tested at whatever version the app
 * happens to be on today — which is 1, where `>= shipped` and "any acceptance at all"
 * cannot be told apart. Here the interesting case can actually be written down.
 */
internal fun termsAreCurrent(accepted: Int, shipped: Int): Boolean = accepted >= shipped
