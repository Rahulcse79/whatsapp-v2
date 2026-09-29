package com.whatsappv2.data.sip.registration.stack

import android.content.Context
import com.whatsappv2.core.common.logging.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The certificate authorities PJSIP verifies a SIP TLS server against (P-8, §7).
 *
 * ## Why this class exists at all
 *
 * PJSIP does not verify anything until it is told what to trust. `TlsConfig.verifyServer`
 * on its own is not enough: OpenSSL is compiled here without a default CA store, so
 * verification with no CA list configured rejects **every** certificate rather than
 * accepting them. Turning verification on therefore means producing a CA bundle first,
 * and that is this class's whole job.
 *
 * ## Where the certificates come from
 *
 * **The device's own trust store**, read through `AndroidCAStore` rather than by reading
 * `/system/etc/security/cacerts`. The filesystem layout moved into an APEX module in
 * recent Android versions and the `KeyStore` API did not, so the API is the one that keeps
 * working. This is what makes a free, publicly-signed certificate — Let's Encrypt — verify
 * with no configuration at all, which is the intended production setup.
 *
 * **Anything in the `sip-ca` asset directory**, appended after it. That directory is
 * declared in the `debug` source set only, so a lab certificate physically cannot be
 * packaged into a release build — the file is absent rather than ignored, which is a
 * guarantee a runtime flag cannot make. Drop a PEM in
 * `data/sip/src/debug/assets/sip-ca/` to have debug builds trust a self-signed
 * FreeSWITCH; release builds trust the device store and nothing else.
 *
 * ## Why this is NOT in the cache directory any more
 *
 * It was, and that is what broke TLS on a handset for fourteen hours without a single
 * clue pointing here.
 *
 * `cacheDir` is storage the platform is explicitly allowed to reclaim whenever it likes,
 * with the app running and with no notification of any kind. The bundle was written once,
 * during `libInit`, and its **path** was then baked into the TLS listener's `TlsConfig`.
 * OpenSSL does not hold the file open: `init_ossl_ctx` calls
 * `SSL_CTX_load_verify_locations` **per connection** (`ssl_sock_ossl.c:1297`), so the path
 * is re-read from disk every time a TLS socket is created. Delete the file and every
 * subsequent handshake fails before the ClientHello — while the listener, the account and
 * the transport all still look perfectly healthy.
 *
 * Observed exactly that way on 2026-09-29. The app started at 11:54 the previous day and
 * wrote the bundle; the platform cleared the cache directory at 01:42; every TLS REGISTER
 * from then on died with
 *
 *     Error loading CA list file '…/cache/pjsip-ca-bundle.pem':
 *       error:80000002:system library::No such file or directory
 *     TLS connect() error: [code=472402]
 *     SIP registration failed, status=503
 *
 * and the user was shown "registrar unreachable", which blames the network for a missing
 * local file. UDP and TCP on the same account and the same server were unaffected, which
 * is exactly the shape that sends people looking at the server's TLS configuration.
 *
 * So the bundle lives in [filesDir][Context.getFilesDir] now — storage the platform does
 * not reclaim behind the app's back — and [ensureBundle] re-creates it if it has gone
 * missing anyway. Because the path never changes and OpenSSL re-reads it per connection,
 * restoring the file is enough on its own: no transport has to be torn down and no
 * account has to be re-added for the next handshake to succeed.
 *
 * ## Fail closed
 *
 * If no bundle can be produced, [refreshBundle] and [ensureBundle] return null and the
 * caller leaves `verifyServer` **on**, so TLS fails. That is deliberate. A fallback to
 * "verify nothing" would turn a broken trust store into a silent downgrade, and the
 * failure it hides is exactly the one certificate verification exists to catch.
 */
@Singleton
internal open class PjsipTrustStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger,
) {

    /**
     * Where the bundle lives, and the one path PJSIP is ever given.
     *
     * Stable for the life of the install, which is what lets [ensureBundle] repair a
     * deleted bundle without touching the TLS transport that already refers to it.
     */
    private val bundleFile: File
        get() = File(File(context.filesDir, BUNDLE_DIRECTORY), BUNDLE_NAME)

    /**
     * Rebuilds the bundle from the device trust store, whatever is already on disk.
     *
     * Called when the SIP stack starts. The device's set of trusted roots changes when the
     * OS updates or the user installs or removes a CA, and a bundle written months ago
     * would not reflect that — so a fresh one is produced once per stack lifetime, which
     * is the cheapest schedule that still tracks the platform.
     */
    fun refreshBundle(): File? = writeBundle()

    /**
     * The bundle, built only if the current one is missing or unusable.
     *
     * The repair path. Called before a TLS account registers, so that a bundle the
     * platform removed — or one that never got written because the trust store was
     * unreadable at startup — is back in place before the handshake needs it, rather than
     * at the next app restart.
     *
     * A file that exists and is non-empty is taken as good: re-reading and re-encoding
     * every root in the device store costs hundreds of milliseconds, and paying that on
     * every registration to detect a case that essentially does not happen would be a
     * worse trade than [refreshBundle]'s once-per-start rebuild.
     */
    fun ensureBundle(): File? {
        val existing = bundleFile
        if (existing.isFile && existing.length() > 0L) return existing

        logger.warn(
            TAG,
            "The CA bundle is missing at ${existing.absolutePath}; rebuilding it before TLS is used",
        )
        return writeBundle()
    }

    private fun writeBundle(): File? {
        discardCacheCopy()

        val pem = collectCertificates() ?: return null

        return runCatching {
            bundleFile.apply {
                parentFile?.mkdirs()
                writeText(pem)
            }
        }.onFailure {
            logger.error(TAG, "Could not write the CA bundle", it)
        }.onSuccess {
            logger.info(TAG, "CA bundle written to ${it.absolutePath}")
        }.getOrNull()
    }

    /**
     * Every CA this app trusts, as concatenated PEM, or null when there are none.
     *
     * `open` for one reason: `AndroidCAStore` is a device provider that no JVM test
     * runtime supplies, so a unit test of the surrounding file handling — where the
     * bundle lives, and when a deleted one is rebuilt, which is the part that broke —
     * would otherwise be testing a collector that always returns nothing. Overriding
     * this is how those tests get a certificate to write. Nothing in production does.
     */
    internal open fun collectCertificates(): String? {
        val certificates = StringBuilder()
        val fromDevice = appendDeviceCertificates(certificates)
        val fromAssets = appendBundledCertificates(certificates)

        if (fromDevice + fromAssets == 0) {
            logger.error(TAG, "No CA certificates found; SIP TLS will fail rather than skip verification")
            return null
        }

        logger.info(TAG, "CA bundle: $fromDevice from the device, $fromAssets bundled")
        return certificates.toString()
    }

    /**
     * Removes the bundle the pre-2026-09-29 builds left in the cache directory.
     *
     * Housekeeping rather than correctness — nothing reads that path any more. It is a few
     * hundred kilobytes of a directory whose whole purpose is to be reclaimable, and
     * leaving a stale trust anchor lying around under a name this class still recognises
     * is the kind of thing that confuses the next investigation.
     */
    private fun discardCacheCopy() {
        val legacy = File(context.cacheDir, BUNDLE_NAME)
        if (!legacy.exists()) return
        if (legacy.delete()) {
            logger.info(TAG, "Removed the old cache-directory CA bundle")
        }
    }

    private fun appendDeviceCertificates(into: StringBuilder): Int = runCatching {
        val store = KeyStore.getInstance(ANDROID_CA_STORE).apply { load(null) }
        var written = 0
        for (alias in store.aliases()) {
            val certificate = store.getCertificate(alias) as? X509Certificate ?: continue
            appendPem(into, certificate)
            written++
        }
        written
    }.getOrElse {
        logger.error(TAG, "Could not read the device trust store", it)
        0
    }

    private fun appendBundledCertificates(into: StringBuilder): Int {
        // Absent in release, because the source set that declares it is `debug`. An empty
        // list here is the normal, expected case rather than a problem to report.
        val names = runCatching { context.assets.list(ASSET_DIRECTORY) }.getOrNull().orEmpty()
        return names.count { name ->
            runCatching {
                context.assets.open("$ASSET_DIRECTORY/$name").use { stream ->
                    into.append(stream.readBytes().decodeToString().trimEnd()).append('\n')
                }
                logger.warn(TAG, "Trusting bundled CA '$name' — debug builds only")
                true
            }.getOrElse {
                logger.error(TAG, "Bundled CA '$name' could not be read", it)
                false
            }
        }
    }

    private fun appendPem(into: StringBuilder, certificate: X509Certificate) {
        val encoder = Base64.getMimeEncoder(PEM_LINE_LENGTH, LINE_SEPARATOR)
        into.append(PEM_BEGIN).append('\n')
            .append(encoder.encodeToString(certificate.encoded))
            .append('\n').append(PEM_END).append('\n')
    }

    private companion object {
        const val TAG = "PjsipTrustStore"

        /** The device's trust store, as an alias the `KeyStore` API resolves. */
        const val ANDROID_CA_STORE = "AndroidCAStore"

        /** Declared in the `debug` source set only. See the class documentation. */
        const val ASSET_DIRECTORY = "sip-ca"

        /** Under `filesDir`, which the platform does not reclaim. See the class KDoc. */
        const val BUNDLE_DIRECTORY = "sip-ca"

        const val BUNDLE_NAME = "pjsip-ca-bundle.pem"

        const val PEM_BEGIN = "-----BEGIN CERTIFICATE-----"
        const val PEM_END = "-----END CERTIFICATE-----"

        /** PEM wraps base64 at 64 characters. */
        const val PEM_LINE_LENGTH = 64
        val LINE_SEPARATOR = byteArrayOf('\n'.code.toByte())
    }
}
