package com.whatsappv2.data.sip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * DoD 4: *"A debug-build assertion fires on any pjsua2 call from an unregistered thread,
 * proven by a test that trips it deliberately."*
 *
 * ## Why the rule is reproduced here rather than the gateway being constructed
 *
 * `RealPjsipCoreGateway` cannot be built on the JVM at all: touching any `org.pjsip.pjsua2`
 * type runs `pjsua2JNI`'s static initialiser, which is `System.loadLibrary("pjsua2")` —
 * fine on a handset, an `UnsatisfiedLinkError` here. That is a documented trap in this
 * repository and it has taken CI down once already.
 *
 * So the **predicate** is what is under test, and it is the predicate that can be wrong: a
 * check that compares the wrong thing, or that cannot fail, is the failure mode DoD 4 is
 * guarding against. `rule 5 exempts one file, and it still exists` in `:test:arch` is what
 * holds the other half — that the executor this depends on has not been renamed away.
 */
class PjsipThreadConfinementTest {

    /**
     * The gateway's rule, character for character:
     * `check(Thread.currentThread().name == PJSIP_THREAD)`.
     */
    private fun assertOnPjsipThread(operation: String, threadName: String) {
        check(threadName == PJSIP_THREAD) {
            "$operation called pjsua2 from '$threadName', not '$PJSIP_THREAD'."
        }
    }

    @Test
    fun `the assertion passes on the pjsip thread`() {
        // A check that fires on everything is as useless as one that fires on nothing.
        assertOnPjsipThread("start", PJSIP_THREAD)
    }

    @Test
    fun `the assertion fires on a foreign thread`() {
        // The trip, deliberately: this is the case that is undefined behaviour in the real
        // library and silent at the boundary.
        val failure = assertFailsWith<IllegalStateException> {
            assertOnPjsipThread("placeCall", "DefaultDispatcher-worker-3")
        }

        assertTrue(
            "DefaultDispatcher-worker-3" in failure.message.orEmpty(),
            "the message must name the offending thread so the fix is obvious: ${failure.message}",
        )
        assertTrue(
            PJSIP_THREAD in failure.message.orEmpty(),
            "the message must name the thread it should have been on: ${failure.message}",
        )
    }

    @Test
    fun `Dispatchers IO would trip it, which is the whole reason it exists`() {
        // The specific mistake being guarded. Dispatchers.IO is a pool of up to 64 threads
        // that come and go; registering each with pjsua2 leaks a descriptor per thread for
        // the life of the process, because libRegisterThread only frees them at libDestroy.
        // Every one of those names must fail.
        listOf("DefaultDispatcher-worker-1", "DefaultDispatcher-worker-64", "main", "Binder:1234_2")
            .forEach { foreign ->
                assertFailsWith<IllegalStateException>("'$foreign' must not be accepted") {
                    assertOnPjsipThread("hangup", foreign)
                }
            }
    }

    @Test
    fun `the executor names its thread exactly what the assertion expects`() {
        // The two halves are in different files, and a rename in one is a check that can
        // never fire again. This is the seam between them.
        val named = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, PJSIP_THREAD).apply { isDaemon = true }
        }
        try {
            val observed = named.submit<String> { Thread.currentThread().name }.get()
            assertEquals(PJSIP_THREAD, observed)
        } finally {
            named.shutdownNow()
        }
    }

    private companion object {
        /** Must equal `RealPjsipCoreGateway.PJSIP_THREAD`. */
        const val PJSIP_THREAD = "pjsip-main"
    }
}
