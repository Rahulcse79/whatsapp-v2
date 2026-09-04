package com.whatsappv2.domain.repository

import com.whatsappv2.domain.model.AppSettings
import com.whatsappv2.domain.model.DtmfMode
import com.whatsappv2.domain.model.PreferredAudioRoute
import com.whatsappv2.domain.model.SrtpPolicy
import kotlinx.coroutines.flow.Flow

/**
 * App-wide preferences.
 *
 * Reads are a [Flow] so a change applies everywhere at once - the in-call screen must not
 * keep using the old audio route because it read the value on entry.
 *
 * Individual setters rather than one `save(AppSettings)`: a whole-object write would make
 * two screens changing different settings at the same time overwrite each other, and
 * every caller would have to read-modify-write correctly to avoid it.
 */
interface AppSettingsRepository {

    fun observeSettings(): Flow<AppSettings>

    /** The current values, for a caller that needs them once rather than continuously. */
    suspend fun currentSettings(): AppSettings

    suspend fun setDtmfMode(mode: DtmfMode)

    suspend fun setDefaultSrtpPolicy(policy: SrtpPolicy)

    suspend fun setPreferredAudioRoute(route: PreferredAudioRoute)

    /**
     * Turns SIP tracing on or off.
     *
     * The caller is responsible for not offering this in a release build; the repository
     * stores what it is told. Enforcing availability here would hide the decision in the
     * storage layer, where nobody reviewing the UI would find it.
     */
    suspend fun setSipTraceEnabled(enabled: Boolean)
}
