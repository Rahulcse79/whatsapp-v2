package com.whatsappv2.data.account.di

import com.whatsappv2.data.account.crypto.AesGcmCredentialCipher
import com.whatsappv2.data.account.crypto.keystore.AndroidKeystoreSecretKeyProvider
import com.whatsappv2.data.account.crypto.CredentialCipher
import com.whatsappv2.data.account.crypto.SecretKeyProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the credential cipher.
 *
 * `@Binds` rather than `@Provides`: there is exactly one implementation of each, and
 * binding an interface to it is free at run time, whereas a provider method would add a
 * factory for no benefit.
 *
 * Both are singletons. The Keystore lookup is not free, and a second cipher instance
 * would hold a second reference to the same key for no reason.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CryptoModule {

    @Binds
    @Singleton
    abstract fun bindSecretKeyProvider(
        provider: AndroidKeystoreSecretKeyProvider,
    ): SecretKeyProvider

    @Binds
    @Singleton
    abstract fun bindCredentialCipher(cipher: AesGcmCredentialCipher): CredentialCipher
}
