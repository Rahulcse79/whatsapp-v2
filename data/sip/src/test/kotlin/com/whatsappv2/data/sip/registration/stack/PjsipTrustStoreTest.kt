package com.whatsappv2.data.sip.registration.stack

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.whatsappv2.core.common.logging.NoOpLogger
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pinned rather than taken from compileSdk: the android-all jar for the newest platform is
 * not always published when that platform ships, and this suite should not break the day
 * compileSdk moves. Matches the constant `:data:settings` uses for the same reason.
 */
private const val ROBOLECTRIC_SDK = 34

/**
 * Where the TLS trust anchors live, and what happens when they go missing.
 *
 * ## The outage these lock down
 *
 * The bundle used to be written into `cacheDir`, which the platform may empty at any
 * moment while the app runs. Its path is fixed into the TLS listener at stack start, and
 * OpenSSL re-reads that path on **every** connection (`init_ossl_ctx`,
 * `ssl_sock_ossl.c:1297`) — so once the platform cleared the cache, every TLS handshake
 * died with `error:80000002:system library::No such file or directory` before the
 * ClientHello, and the app reported the registrar as unreachable. UDP and TCP on the same
 * account kept working, which is what made it look like a server problem.
 *
 * Two properties close it, and both are asserted here: the bundle is not in reclaimable
 * storage, and a bundle that has gone missing is rebuilt at the same path before TLS
 * needs it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class PjsipTrustStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * A trust store whose certificates are a fixed string.
     *
     * `AndroidCAStore` is a device provider no JVM test runtime supplies, so the real
     * collector would always come back empty here and every write would be skipped. What
     * is under test is the file handling around it, not the encoding of a certificate.
     */
    private class FakeTrustStore(
        context: Context,
        private val pem: String?,
    ) : PjsipTrustStore(context, NoOpLogger) {
        var collections = 0
            private set

        override fun collectCertificates(): String? {
            collections++
            return pem
        }
    }

    private fun trustStore(pem: String? = PEM) = FakeTrustStore(context, pem)

    private fun bundle(): File = File(File(context.filesDir, "sip-ca"), "pjsip-ca-bundle.pem")

    @Test
    fun `the bundle is written outside the cache directory`() {
        val written = assertNotNull(trustStore().refreshBundle())

        assertFalse(
            written.absolutePath.startsWith(context.cacheDir.absolutePath),
            "the CA bundle must not live in storage the platform may reclaim: $written",
        )
        assertTrue(written.absolutePath.startsWith(context.filesDir.absolutePath))
        assertEquals(PEM, written.readText())
    }

    @Test
    fun `ensureBundle rebuilds a bundle the platform deleted`() {
        val store = trustStore()
        val first = assertNotNull(store.refreshBundle())
        assertTrue(first.delete())

        val repaired = assertNotNull(store.ensureBundle())

        assertTrue(repaired.isFile)
        assertEquals(PEM, repaired.readText())
        // Same path, which is the whole reason no transport has to be torn down.
        assertEquals(first.absolutePath, repaired.absolutePath)
    }

    @Test
    fun `ensureBundle rebuilds a bundle that was truncated to nothing`() {
        val store = trustStore()
        assertNotNull(store.refreshBundle()).writeText("")

        assertEquals(PEM, assertNotNull(store.ensureBundle()).readText())
    }

    @Test
    fun `ensureBundle reuses a healthy bundle instead of re-reading the trust store`() {
        val store = trustStore()
        store.refreshBundle()
        val afterFirstWrite = store.collections

        store.ensureBundle()

        assertEquals(afterFirstWrite, store.collections, "a healthy bundle must not be rebuilt")
    }

    @Test
    fun `refreshBundle rebuilds even when a healthy bundle is already there`() {
        val store = trustStore()
        store.refreshBundle()
        val afterFirstWrite = store.collections

        store.refreshBundle()

        assertEquals(afterFirstWrite + 1, store.collections)
    }

    @Test
    fun `it fails closed when no certificate can be found`() {
        assertNull(trustStore(pem = null).refreshBundle())
        assertFalse(bundle().exists())
    }

    @Test
    fun `the bundle an older build left in the cache directory is removed`() {
        val legacy = File(context.cacheDir, "pjsip-ca-bundle.pem").apply {
            parentFile?.mkdirs()
            writeText(PEM)
        }

        trustStore().refreshBundle()

        assertFalse(legacy.exists(), "the stale cache-directory bundle should be cleaned up")
    }

    private companion object {
        val PEM = """
            -----BEGIN CERTIFICATE-----
            MIIBkTCB+wIJAJ8s3x1example0000000000000000000000000000000000000
            -----END CERTIFICATE-----
        """.trimIndent()
    }
}
