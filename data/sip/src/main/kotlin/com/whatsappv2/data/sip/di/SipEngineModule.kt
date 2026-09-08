package com.whatsappv2.data.sip.di

import com.whatsappv2.data.sip.PjsipSipEngine
import com.whatsappv2.domain.engine.SipCallController
import com.whatsappv2.domain.engine.SipConferenceController
import com.whatsappv2.domain.engine.SipEngine
import com.whatsappv2.domain.engine.SipMediaController
import com.whatsappv2.domain.engine.SipRegistrar
import com.whatsappv2.domain.registration.RegistrationRetrySchedule
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
 * Bound to [PjsipSipEngine], the real stack. It was written and unit-tested in Task 27
 * but this binding was never moved off `UnavailableSipEngine`, so the running app had no
 * SIP stack at all and every account read Offline whatever the user configured.
 *
 * As of Tasks 51-60 the engine answers for the whole of [SipEngine] — registration,
 * calling, hold, mute, routing, DTMF, video, transfer and dial-in conferencing — so
 * nothing is delegated to `UnavailableSipEngine` any more. That class remains as the
 * honest answer for a graph with no stack at all, not as a partial stand-in for this one.
 *
 * The module is `internal` because [PjsipSipEngine] is: a binding may not be more
 * visible than the type it names.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class SipEngineModule {

    @Binds
    @Singleton
    abstract fun bindSipEngine(engine: PjsipSipEngine): SipEngine

    @Binds
    abstract fun bindRegistrar(engine: SipEngine): SipRegistrar

    @Binds
    abstract fun bindCallController(engine: SipEngine): SipCallController

    @Binds
    abstract fun bindMediaController(engine: SipEngine): SipMediaController

    @Binds
    abstract fun bindConferenceController(engine: SipEngine): SipConferenceController

    /**
     * Bound from the concrete engine, not from [SipEngine], because the schedule is not
     * part of that contract — the engine reports what happened, the recovery coordinator
     * decides what happens next. Same singleton either way.
     */
    @Binds
    abstract fun bindRetrySchedule(engine: PjsipSipEngine): RegistrationRetrySchedule
}
