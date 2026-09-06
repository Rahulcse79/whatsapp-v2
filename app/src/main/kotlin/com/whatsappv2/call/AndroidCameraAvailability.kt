package com.whatsappv2.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.whatsappv2.domain.engine.CameraAvailability
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether this device can send video, answered from Android (Task 51, §5.2).
 *
 * ## Two questions, one answer, and that is deliberate
 *
 * A camera can be unusable because the user declined the permission or because the device
 * has none — the manifest marks `android.hardware.camera` `required="false"` precisely so
 * this app installs on the latter. [CameraAvailability] gives callers one boolean rather
 * than letting them tell the cases apart, because the only correct response to either is
 * the same: place the call without video (§5.2), and do not nag.
 *
 * ## Asked every time
 *
 * No caching. A permission granted in Settings while the app sat in the background is
 * granted, and a remembered "no" would keep video off until the process restarted. It
 * matters in the other direction too: Android 14 checks the `camera` foreground-service
 * type against the live permission, so `RegistrationService` asking a stale value here is
 * a crash rather than a downgrade.
 *
 * The hardware check is second because it is the more expensive of the two and the answer
 * cannot change: a device does not grow a camera. The permission check short-circuits it
 * on the common denied path.
 */
@Singleton
class AndroidCameraAvailability @Inject constructor(
    @ApplicationContext private val context: Context,
) : CameraAvailability {

    override fun isCameraUsable(): Boolean = hasPermission() && hasCamera()

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * `FEATURE_CAMERA_ANY`, not `FEATURE_CAMERA`.
     *
     * The narrower feature means a *rear* camera specifically, and a tablet with only a
     * front-facing one would report false — which for a video call is exactly backwards:
     * the front camera is the one a call wants.
     */
    private fun hasCamera(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
}
