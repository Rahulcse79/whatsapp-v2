package com.whatsappv2.onboarding

import androidx.test.core.app.ApplicationProvider
import com.whatsappv2.di.ROBOLECTRIC_SDK
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What survives a restart, and what a new version of the terms does to it.
 *
 * Robolectric because the thing under test is `SharedPreferences` persistence — a fake
 * would assert that the fake works.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class FirstRunStoreTest {

    private lateinit var store: FirstRunStore

    @Before
    fun setUp() {
        store = FirstRunStore(ApplicationProvider.getApplicationContext())
        store.reset()
    }

    @Test
    fun `a fresh install owes both the terms and the tour`() {
        assertFalse(store.hasAcceptedTerms())
        assertFalse(store.hasSeenTour())
    }

    @Test
    fun `acceptance survives, so the terms are not shown on every launch`() {
        store.acceptTerms()

        // A second instance is what a cold start actually produces.
        assertTrue(FirstRunStore(ApplicationProvider.getApplicationContext()).hasAcceptedTerms())
    }

    @Test
    fun `the tour is remembered separately from the terms`() {
        // Kept apart so that amending the terms does not re-run the tour, and adding a
        // tour page does not re-present a legal document.
        store.markTourSeen()

        assertTrue(store.hasSeenTour())
        assertFalse(store.hasAcceptedTerms())
    }

    @Test
    fun `an acceptance of older terms does not stand`() {
        // Stored as the version accepted, not as a boolean: a flag cannot express "you
        // agreed to a different document". Asserted on the rule rather than through the
        // store, because at TERMS_VERSION 1 every acceptance is current and the case that
        // matters cannot be reached — which is exactly how a regression would hide.
        assertFalse(termsAreCurrent(accepted = 1, shipped = 2), "terms 1 accepted, terms 2 shipped")
        assertFalse(termsAreCurrent(accepted = 0, shipped = 1), "nothing accepted")
        assertTrue(termsAreCurrent(accepted = 2, shipped = 2))
        // Ahead of this build — a downgrade. Not re-asking is right: they agreed to more.
        assertTrue(termsAreCurrent(accepted = 3, shipped = 2))
    }

    @Test
    fun `the version actually written is the one this build ships`() {
        store.acceptTerms()

        assertTrue(store.hasAcceptedTerms())
    }

    @Test
    fun `reset puts the app back to its first run`() {
        store.acceptTerms()
        store.markTourSeen()

        store.reset()

        assertFalse(store.hasAcceptedTerms())
        assertFalse(store.hasSeenTour())
    }
}
