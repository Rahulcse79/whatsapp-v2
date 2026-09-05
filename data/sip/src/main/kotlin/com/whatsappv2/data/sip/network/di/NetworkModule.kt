package com.whatsappv2.data.sip.network.di

import com.whatsappv2.data.sip.network.NetworkMonitor
import com.whatsappv2.data.sip.network.platform.ConnectivityNetworkMonitor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the platform's network monitor.
 *
 * Internal, and so are both types it names: nothing outside `:data:sip` should be able to
 * ask for a [NetworkMonitor]. Network-change recovery is the SIP stack's business, and a
 * ViewModel that wanted to know whether the device is online would be reaching past the
 * engine for something the engine already reports through `registrationState`.
 *
 * There is deliberately no binding here for the recovery coordinator itself. It is owned
 * by the engine rather than injected — it needs the engine as its registrar, which would
 * be a dependency cycle, and its lifetime is the stack's.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class NetworkModule {

    @Binds
    @Singleton
    abstract fun bindNetworkMonitor(monitor: ConnectivityNetworkMonitor): NetworkMonitor
}
