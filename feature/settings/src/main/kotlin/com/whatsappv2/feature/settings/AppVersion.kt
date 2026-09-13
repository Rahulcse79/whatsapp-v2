package com.whatsappv2.feature.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign

/**
 * The version of the app that is actually installed, at the foot of Settings.
 *
 * ## Read from the package manager, not from a `BuildConfig`
 *
 * `BuildConfig.VERSION_NAME` belongs to whichever module reads it, and this is not `:app`
 * — a constant compiled into `:feature:settings` would say what *this module* was built
 * as, which is not a thing anybody wants to know. The package manager answers for the APK
 * on the phone, which is the question being asked: "which build am I looking at?" It is
 * also the only answer that stays right when somebody sideloads a build over another.
 *
 * ## Both numbers, because they answer different questions
 *
 * The name is what a person says out loud; the code is what Android compares when it
 * decides whether an install is an upgrade. A bug report that carries only the name cannot
 * tell two builds of `1.0.3` apart, and this project has already shipped two APKs whose
 * versionCode did not move — the package manager saw no upgrade between them
 * (`app/build.gradle.kts`).
 */
@Composable
internal fun AppVersionFooter(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Remembered on the context: the installed package cannot change under a running
    // process without it being restarted, so asking once per composition is once too many.
    val label = remember(context) { context.appVersionLabel() }

    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .testTag(TAG_APP_VERSION),
    )
}

/**
 * This app's version, as one line, or a stated absence.
 *
 * A `PackageManager` can throw `NameNotFoundException` for the caller's own package — it
 * happens while the app is being replaced — and a settings screen that crashes on the way
 * to showing a version number is a worse outcome than one that says it does not know.
 *
 * The exception is genuinely swallowed rather than logged. There is nothing in it that the
 * fallback line does not already say, this composable has no `Logger` and `android.util.Log`
 * is forbidden here (§7, DoD 12), and injecting a logging dependency into a screen so that
 * an unreachable branch can narrate itself is a worse trade than this suppression.
 */
@Suppress("SwallowedException")
private fun Context.appVersionLabel(): String = try {
    val info = packageManager.getPackageInfo(packageName, 0)
    appVersionLabel(info.versionName, info.longVersionCodeCompat())
} catch (e: PackageManager.NameNotFoundException) {
    UNKNOWN_VERSION
}

/** `longVersionCode` is API 28; `minSdk` here is 26, so the older field is still needed. */
private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        longVersionCode
    } else {
        @Suppress("DEPRECATION")
        versionCode.toLong()
    }

/**
 * Formats the two numbers, or says so when there is no name to format.
 *
 * Split from the lookup so it can be tested without a `PackageManager`: the awkward cases
 * are a null name and a blank one, and both come back from the platform rather than from
 * anything this app controls.
 */
internal fun appVersionLabel(name: String?, code: Long): String =
    if (name.isNullOrBlank()) UNKNOWN_VERSION else "Version $name ($code)"

internal const val UNKNOWN_VERSION = "Version unavailable"

/** The footer, so a test reads the version rather than any line that starts with "Version". */
internal const val TAG_APP_VERSION = "settings-app-version"
