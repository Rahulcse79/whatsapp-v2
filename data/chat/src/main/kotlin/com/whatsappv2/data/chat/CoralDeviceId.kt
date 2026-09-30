package com.whatsappv2.data.chat

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The `deviceId` the Coral platform is told about at sign-in.
 *
 * ## One per install, not per sign-in
 *
 * The server feeds it to `imsiService.updateDeviceUser`, which records which device a user
 * is on. A fresh value per sign-in would make one handset look like a new device every
 * time somebody signed in, and whatever the platform does with that record — push routing,
 * session limits, an audit trail — would be wrong in a way nothing here could see. So it
 * is generated once and kept.
 *
 * ## 32 upper hex, and **not** the chat SDK's install id
 *
 * The platform's sample is `BF6625949EAA4D5F94CAA18641BE8E74` — 128 bits, no dashes,
 * uppercase. The chat SDK keeps its own id in its own format
 * (`UUID.randomUUID().toString()` — lowercase, dashed) in its own `SharedPreferences` file,
 * for its own purpose. Two values, two owners; sharing one would couple an identity the
 * Coral platform assigns meaning to with one chat-node assigns different meaning to.
 *
 * ## Why not an Android identifier
 *
 * `ANDROID_ID` and friends are either per-app-signing-key anyway or need a permission this
 * app has no other reason to hold. A random value in this app's own preferences is the
 * same thing without the permission, and it disappears when the app's data does — which is
 * the correct lifetime for "this install".
 */
@Singleton
internal class CoralDeviceId @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val preferences by lazy {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun get(): String {
        preferences.getString(KEY, null)?.takeIf { it.length == LENGTH }?.let { return it }

        val generated = ByteArray(BYTES)
            .also(SecureRandom()::nextBytes)
            .joinToString("") { "%02X".format(it) }

        preferences.edit().putString(KEY, generated).apply()
        return generated
    }

    private companion object {
        const val FILE_NAME = "coral-device"
        const val KEY = "device_id"

        /** 128 bits, as 32 uppercase hex characters — the shape the platform's sample uses. */
        const val BYTES = 16
        const val LENGTH = BYTES * 2
    }
}
