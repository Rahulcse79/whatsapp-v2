package com.whatsappv2.data.account.crypto.keystore

import com.whatsappv2.data.account.crypto.AesGcmCredentialCipher
import com.whatsappv2.data.account.crypto.CipherError
import com.whatsappv2.data.account.crypto.SecretKeyProvider

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.success
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the credential key in the Android Keystore.
 *
 * The key material never leaves the Keystore — on devices with a TEE or StrongBox it
 * never enters the app process at all. Extracting the stored password therefore requires
 * more than reading the app's files, which is the whole point of DoD 12.
 *
 * ## Why user authentication is not required
 *
 * `setUserAuthenticationRequired(true)` would bind decryption to a recent unlock, which
 * sounds strictly better and is wrong here: this app must decrypt credentials to
 * re-register in the background, and the moment that matters most is while the device is
 * locked and an incoming call is arriving. A key that cannot be used then would mean
 * missed calls.
 *
 * `setUnlockedDeviceRequired(true)` is omitted for the same reason.
 *
 * ## Why this lives in its own package
 *
 * The Android Keystore cannot run on the JVM, so nothing here is reachable by a unit
 * test. Keeping it beside the AES-GCM logic would drag that package's coverage down and
 * make its gate meaningless - the same measurement flaw `FakeSipEngine` had. This is
 * verified on-device from Task 33 instead.
 *
 * The trade-off is recorded in `docs/security.md` rather than buried here: on a rooted
 * or compromised device an attacker who can run code as this app can ask the Keystore to
 * decrypt. The Keystore raises the cost of offline extraction; it does not defend
 * against code already running as the app.
 */
@Singleton
class AndroidKeystoreSecretKeyProvider @Inject constructor() : SecretKeyProvider {

    override fun getOrCreateKey(): Outcome<SecretKey, CipherError> = try {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        success(existing ?: generateKey())
    } catch (e: GeneralSecurityException) {
        failure(CipherError.KeyUnavailable(e.javaClass.simpleName))
    } catch (e: java.io.IOException) {
        // KeyStore.load can fail on a corrupt keystore file.
        failure(CipherError.KeyUnavailable(e.javaClass.simpleName))
    }

    override fun deleteKey() {
        runCatching {
            KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    private fun generateKey(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AesGcmCredentialCipher.KEY_SIZE_BITS)
            // The caller supplies a fresh random IV per encryption. Letting the Keystore
            // pick would be safe too, but the IV must then be read back from the Cipher,
            // and a caller that forgets produces silently unreadable data.
            .setRandomizedEncryptionRequired(false)
            .build()

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            .apply { init(spec) }
            .generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "whatsappv2.credentials.v1"
    }
}
