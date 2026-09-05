package com.whatsappv2.data.sip.di

import com.whatsappv2.core.common.dispatcher.DispatcherProvider
import com.whatsappv2.data.sip.registration.LinphoneCoreGateway
import com.whatsappv2.data.sip.registration.stack.RealLinphoneCoreGateway
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * The scope the SIP stack's own work runs on.
 *
 * Qualified rather than a bare `CoroutineScope`. An unqualified scope in the singleton
 * graph is a binding any module can accidentally match, and the resulting bug — two
 * components sharing one cancellation — is not one the compiler catches.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class SipStackScope

/** Binds the SDK seam. The implementation is the only class allowed to name liblinphone. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class SipStackModule {

    @Binds
    @Singleton
    abstract fun bindGateway(gateway: RealLinphoneCoreGateway): LinphoneCoreGateway
}

/**
 * Supplies the engine's scope.
 *
 * A separate `object` module rather than a companion inside the abstract one: Dagger
 * supports companion-object `@Provides`, but the two forms have different rules about
 * static-ness and it is not worth the ambiguity for one function.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object SipStackScopeModule {

    /**
     * Lives as long as the process, because the stack does.
     *
     * `SupervisorJob` so one failed child - a registration collect that throws - cannot
     * cancel the scope and silently take every other account's recovery down with it.
     * `io` because everything on this scope is a socket or a stack callback waiting on
     * one, never computation.
     */
    @Provides
    @Singleton
    @SipStackScope
    fun provideSipStackScope(dispatchers: DispatcherProvider): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatchers.io)
}
