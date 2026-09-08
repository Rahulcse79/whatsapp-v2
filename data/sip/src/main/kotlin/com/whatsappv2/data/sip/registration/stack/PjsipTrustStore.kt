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
 * ## Fail closed
 *
 * If no bundle can be produced, [caBundle] returns null and the caller leaves
 * `verifyServer` **on**, so TLS fails. That is deliberate. A fallback to "verify nothing"
 * would turn a broken trust store into a silent downgrade, and the failure it hides is
 * exactly the one certificate verification exists to catch.
 */
@Singleton
internal class PjsipTrustStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger,
) {

    /**
     * The CA bundle as a PEM file PJSIP can be pointed at, or null if none could be built.
     *
     * Written to the cache directory: it is derived data, it costs a few hundred
     * kilobytes, and it must be regenerated when the device's trust store changes — which
     * a cache the system may clear models better than a file that lives forever.
     */
    fun caBundle(): File? {
        val certificates = StringBuilder()
        val fromDevice = appendDeviceCertificates(certificates)
        val fromAssets = appendBundledCertificates(certificates)

        if (fromDevice + fromAssets == 0) {
            logger.error(TAG, "No CA certificates found; SIP TLS will fail rather than skip verification")
            return null
        }

        return runCatching {
            File(context.cacheDir, BUNDLE_NAME).apply { writeText(certificates.toString()) }
        }.onFailure {
            logger.error(TAG, "Could not write the CA bundle", it)
        }.onSuccess {
            logger.info(TAG, "CA bundle: $fromDevice from the device, $fromAssets bundled")
        }.getOrNull()
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

        const val BUNDLE_NAME = "pjsip-ca-bundle.pem"

        const val PEM_BEGIN = "-----BEGIN CERTIFICATE-----"
        const val PEM_END = "-----END CERTIFICATE-----"

        /** PEM wraps base64 at 64 characters. */
        const val PEM_LINE_LENGTH = 64
        val LINE_SEPARATOR = byteArrayOf('\n'.code.toByte())
    }
}
