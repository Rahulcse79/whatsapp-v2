package com.whatsappv2.data.sip.di

import com.whatsappv2.data.sip.UnavailableSipEngine
import com.whatsappv2.domain.engine.SipCallController
import com.whatsappv2.domain.engine.SipConferenceController
import com.whatsappv2.domain.engine.SipEngine
import com.whatsappv2.domain.engine.SipMediaController
import com.whatsappv2.domain.engine.SipRegistrar
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the SIP engine and each of its roles.
 *
 * The role interfaces are bound separately so a ViewModel can depend on just the part it
 * needs - the account list wants [SipRegistrar], not the power to place calls - while all
 * of them resolve to the same singleton.
 *
 * Currently bound to [UnavailableSipEngine]. Task 27 replaces that binding with the
 * liblinphone implementation; nothing above this module changes.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SipEngineModule {

    @Binds
    @Singleton
    abstract fun bindSipEngine(engine: UnavailableSipEngine): SipEngine

    @Binds
    abstract fun bindRegistrar(engine: SipEngine): SipRegistrar

    @Binds
    abstract fun bindCallController(engine: SipEngine): SipCallController

    @Binds
    abstract fun bindMediaController(engine: SipEngine): SipMediaController

    @Binds
    abstract fun bindConferenceController(engine: SipEngine): SipConferenceController
}
