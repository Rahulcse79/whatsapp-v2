package com.whatsappv2.voice

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.whatsappv2.di.ROBOLECTRIC_SDK
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The voice profile's one promise, asserted against the merged manifest (ADR-013).
 *
 * ## What this is guarding
 *
 * ONNX Runtime's AAR ships a `ContentProvider` that builds an HTTP client and registers a
 * network callback the moment the process starts — it is visible in logcat as
 * `ai.onnxruntime.telemetry.HttpClient.<init>` from `TelemetryInitializer`, and it runs
 * whether or not a model is ever loaded. The app manifest removes it with
 * `tools:node="remove"`.
 *
 * That removal is one line in a file nobody reads, defeated silently by an ORT upgrade
 * that renames the provider, or by somebody tidying away an attribute whose purpose is
 * not obvious. This is what makes that a failing test rather than a quiet regression in
 * the one property the whole feature is sold on: that nothing about the user's voice
 * leaves the handset.
 *
 * Robolectric resolves providers from the *merged* manifest, which is the artefact that
 * actually ships — so this checks the thing, not a copy of the thing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class VoicePrivacyTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun `onnx runtime's telemetry provider is not in the merged manifest`() {
        val authority = "${context.packageName}.onnxruntime_telemetry_initializer"

        val resolved = context.packageManager.resolveContentProvider(authority, 0)

        assertNull(
            resolved,
            "ONNX Runtime's telemetry provider is registered again. It opens a network " +
                "channel at process start, in the library that computes the voice " +
                "embedding. Remove it in app/src/main/AndroidManifest.xml.",
        )
    }

    @Test
    fun `no provider from the onnxruntime package is registered at all`() {
        // Broader than the authority above, so a renamed provider in a future ORT is
        // caught too rather than slipping past a string that no longer matches.
        val providers = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS)
            .providers
            .orEmpty()

        val onnx = providers.filter { it.name.orEmpty().startsWith("ai.onnxruntime") }

        assertTrue(
            onnx.isEmpty(),
            "ONNX Runtime registered ${onnx.map { it.name }}. Nothing from that library " +
                "should run before a model is asked for.",
        )
    }
}
