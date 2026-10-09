package com.whatsappv2.domain.repository

import com.whatsappv2.domain.model.AppSettings
import com.whatsappv2.domain.model.CallHistoryRetention
import com.whatsappv2.domain.model.DtmfMode
import com.whatsappv2.domain.model.PreferredAudioRoute
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.ThemeMode
import com.whatsappv2.domain.video.VideoFrameRate
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
     * Light, dark or follow the phone.
     *
     * Observed by every activity's theme, so a change here recolours the screen that
     * changed it and the call screen behind it in the same frame — no restart, no
     * "takes effect next time".
     */
    suspend fun setThemeMode(mode: ThemeMode)

    /**
     * The frame rate outgoing video is asked to run at.
     *
     * Takes effect on calls already running: the coordinator rebuilds its ladder at the new
     * rate and writes the rung it was already on, so nobody has to hang up to change it.
     */
    suspend fun setVideoFrameRate(rate: VideoFrameRate)

    /**
     * Turns SIP tracing on or off.
     *
     * The caller is responsible for not offering this in a release build; the repository
     * stores what it is told. Enforcing availability here would hide the decision in the
     * storage layer, where nobody reviewing the UI would find it.
     */
    suspend fun setSipTraceEnabled(enabled: Boolean)

    /**
     * Turns live call filtering — the trained-voice gate — on or off.
     *
     * Takes effect on calls already running: the gate is started and stopped from the
     * setting, so nobody has to hang up to change it.
     */
    suspend fun setLiveCallFilteringEnabled(enabled: Boolean)

    /**
     * Turns connected-party updates on transfer on or off.
     *
     * Takes effect on the next call rather than on calls already up: what it governs rides
     * on an INVITE, an answer or a REFER, and those are sent before a call is connected.
     * See [com.whatsappv2.domain.model.AppSettings.updateCallerIdOnTransfer].
     */
    suspend fun setUpdateCallerIdOnTransfer(enabled: Boolean)

    /**
     * Turns SIP TLS server-certificate verification on or off.
     *
     * Off by default; see [com.whatsappv2.domain.model.AppSettings.verifyTlsCertificates]
     * for what that costs and why the deployments this app targets need the choice.
     */
    suspend fun setVerifyTlsCertificates(verify: Boolean)

    /** How long the call log is kept. Takes effect on the next prune, not retroactively undone. */
    suspend fun setCallHistoryRetention(retention: CallHistoryRetention)
}
