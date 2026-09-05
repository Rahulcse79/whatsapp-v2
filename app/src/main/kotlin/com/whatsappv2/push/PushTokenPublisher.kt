package com.whatsappv2.push

import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.di.ApplicationScope
import com.whatsappv2.domain.engine.PushToken
import com.whatsappv2.domain.engine.SipRegistrar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts the FCM token on the `Contact` header of every REGISTER (ADR-004, Task 38).
 *
 * ## Why it is published unconditionally
 *
 * RFC 8599 parameters cost nothing when the server ignores them, and they are the only way
 * the token reaches the registrar without a side channel of its own. ADR-004 builds the
 * client half regardless of what the deployed FreeSWITCH turns out to store, precisely so
 * this side is already correct when the gateway lands.
 *
 * ## Clearing
 *
 * Not from here. `SipRegistrar.setPushToken(null)` is the engine's own API for it and the
 * engine implements it; the caller that should use it is a logout that knows no account is
 * left to be woken for, which is a decision for the deployment that actually has a push
 * gateway (ADR-004 puts the gateway outside this repository). A `clear()` on this class
 * with nothing calling it would look like that decision had been made.
 *
 * ## Firebase may not be configured, and that is not a crash
 *
 * This repository carries no `google-services.json` — it is deployment configuration, and
 * a checked-in one would tie every build to one Firebase project. Without it
 * `FirebaseMessaging.getInstance()` throws, so it is caught: an app with no push
 * configured still registers, still takes calls on an open socket, and simply misses the
 * ones that arrive in Doze. That is a real limitation, and it is logged as one rather than
 * hidden behind a crash on launch.
 */
@Singleton
class PushTokenPublisher @Inject constructor(
    private val registrar: SipRegistrar,
    @ApplicationScope private val scope: CoroutineScope,
    private val logger: Logger,
) {

    /**
     * Fetches the current token and publishes it. Safe to call more than once.
     *
     * `getToken` and `getInstance` are deprecated in firebase-messaging 25.1.2, which
     * replaces the fetch with `register()` plus an `onRegistered` callback on the service.
     * That is a different shape for the wake path — the token stops being something this
     * class can ask for and starts arriving on a callback — so it is a migration with a
     * decision in it (ADR-004) rather than a rename, and it is deliberately not made in
     * passing here. Suppressed rather than baselined, so the obligation stays at the call
     * site where the next reader meets it. CI compiles with `-Werror`.
     */
    @Suppress("DEPRECATION")
    fun publishCurrentToken() {
        val messaging = runCatching { FirebaseMessaging.getInstance() }
            .onFailure { logger.warn(TAG, "Push is not configured on this build: ${it.javaClass.simpleName}") }
            .getOrNull() ?: return

        messaging.token
            .addOnSuccessListener { token -> publish(token) }
            .addOnFailureListener { logger.error(TAG, "Could not read the push token: ${it.javaClass.simpleName}") }
    }

    /**
     * Publishes a rotated token.
     *
     * FCM rotates on its own schedule — a restore to a new device, an app data clear — and
     * a registrar holding the old `pn-prid` will wake a device that no longer exists.
     * Task 38's third done-when is exactly this path.
     */
    fun publish(token: String) {
        if (token.isBlank()) {
            logger.warn(TAG, "Ignoring a blank push token")
            return
        }

        scope.launch {
            // The token itself is never logged: it identifies a device (§7, DoD 12).
            registrar.setPushToken(
                PushToken(provider = PROVIDER, param = senderId(token), prid = token),
            )
        }
    }

    /**
     * `pn-param` — the FCM sender the gateway pushes through.
     *
     * Firebase does not expose the sender id through `FirebaseMessaging`, and the value
     * lives in the `google-services.json` this repository deliberately does not carry. The
     * project id from the initialised app is the closest thing available at runtime, and
     * the gateway matches on `pn-prid` anyway — the token is the thing that identifies the
     * device. Named here rather than left blank so the gap is visible.
     */
    private fun senderId(token: String): String =
        runCatching { FirebaseApp.getInstance().options.gcmSenderId }
            .getOrNull()
            ?: token.substringBefore(':', missingDelimiterValue = PROVIDER)

    private companion object {
        const val TAG = "PushTokenPublisher"

        /** `pn-provider`, per ADR-004. */
        const val PROVIDER = "fcm"
    }
}
