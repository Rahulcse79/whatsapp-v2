package com.whatsappv2.data.sip.di

import com.whatsappv2.data.sip.LinphoneSipEngine
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
 * Bound to [LinphoneSipEngine], the real stack. It was written and unit-tested in Task 27
 * but this binding was never moved off `UnavailableSipEngine`, so the running app had no
 * SIP stack at all and every account read Offline whatever the user configured.
 *
 * The engine now covers registration and calling — place, answer, reject, hang up, hold,
 * resume, mute, route and DTMF (Tasks 35, 37, 40-43). Transfer and conferencing are still
 * delegated to `UnavailableSipEngine` and still answer `EngineUnavailable`, which is what
 * Tasks 55 and 60 replace. Delegating rather than restubbing means there is one set of "not
 * built yet" answers instead of two that can drift.
 *
 * The module is `internal` because [LinphoneSipEngine] is: a binding may not be more
 * visible than the type it names.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class SipEngineModule {

    @Binds
    @Singleton
    abstract fun bindSipEngine(engine: LinphoneSipEngine): SipEngine

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
    abstract fun bindRetrySchedule(engine: LinphoneSipEngine): RegistrationRetrySchedule
}
