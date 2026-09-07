package com.whatsappv2.permission

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews
import com.whatsappv2.core.designsystem.theme.AppTheme

/**
 * The first-run permission screen (Task 72).
 *
 * ## This reverses an earlier decision, deliberately
 *
 * Permissions were asked for **in context** — at the moment the capability was needed —
 * and the reason was written down: a wall of dialogs on first run is how people learn to
 * press Deny, and Android gives one prompt per permission, so a refusal here is permanent
 * and costs the feature. That reasoning has not become wrong. It was overruled by a
 * product decision to ask up front, and this screen is written to keep as much of the old
 * protection as it can:
 *
 *  - every permission is introduced by its own [AppPermission.rationale] **before** the
 *    system dialog, so nobody is asked to decide blind;
 *  - the whole screen is skippable, and skipping is a plain button rather than a trap;
 *  - refusing everything still reaches the app. Only [AppPermission.RECORD_AUDIO] blocks a
 *    feature, and it blocks calling with an explanation rather than dead-ending;
 *  - the in-context path still exists, so a permission declined here is asked for again by
 *    the feature that needs it, once, at the moment it would work.
 *
 * ## What it does not ask for
 *
 * An "Accounts" permission. `GET_ACCOUNTS` reads the *device's* account list — Google,
 * Exchange — which this app never touches: its accounts are SIP accounts it stores itself
 * in `:data:account`. Requesting it would be asking for somebody's personal data with no
 * feature behind it, which §7 forbids. If a device-account integration is ever wanted, it
 * is a feature first and a permission second.
 */
@Composable
fun PermissionOnboarding(
    coordinator: PermissionCoordinator,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Everything that exists on this device and is not already held. A permission the
    // platform grants implicitly must not be shown as something to decide.
    val requests = remember(coordinator) { onboardingPermissions(coordinator) }

    var index by remember { mutableIntStateOf(0) }
    val current = requests.getOrNull(index)

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // Recorded whatever the answer was. Without this, PermissionCoordinator cannot
        // later tell "never asked" from "permanently denied", and the app would keep
        // offering a system dialog Android will never show again.
        current?.let(coordinator::markRequested)
        index += 1
    }

    if (current == null) {
        // Nothing left to ask, on this run or at all. Finishing from a composition effect
        // rather than during composition: onFinished writes to a tracker.
        LaunchedEffect(Unit) { onFinished() }
        return
    }

    OnboardingStep(
        permission = current,
        stepNumber = index + 1,
        stepCount = requests.size,
        onAllow = { launcher.launch(current.manifestPermission) },
        onSkipOne = { index += 1 },
        onSkipAll = onFinished,
        modifier = modifier,
    )
}

/** One permission, explained before it is asked for. */
@Composable
private fun OnboardingStep(
    permission: AppPermission,
    stepNumber: Int,
    stepCount: Int,
    onAllow: () -> Unit,
    onSkipOne: () -> Unit,
    onSkipAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(AppTheme.spacing.huge),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.large),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Step $stepNumber of $stepCount",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().testTag(TAG_PROGRESS),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.medium),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = permission.iconForSheet(),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(permission.title, style = MaterialTheme.typography.headlineSmall)
            }

            Text(permission.rationale, style = MaterialTheme.typography.bodyMedium)

            Text(
                // Said before the choice, not after it. Somebody deciding whether to
                // decline is entitled to know what declining costs.
                text = "If you say no: ${permission.deniedExplanation}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(onClick = onAllow, modifier = Modifier.fillMaxWidth().testTag(TAG_ALLOW)) {
                Text("Continue")
            }

            TextButton(onClick = onSkipOne, modifier = Modifier.testTag(TAG_SKIP)) {
                Text("Not now")
            }

            TextButton(onClick = onSkipAll, modifier = Modifier.testTag(TAG_SKIP_ALL)) {
                Text("Skip all and go to the app")
            }
        }
    }
}

/**
 * The permissions worth asking for on this device, in the order they are asked.
 *
 * Microphone first because it is the only one that blocks a feature; the rest are
 * ordered by how visibly the app degrades without them. Anything already granted, not
 * present on this Android version, or granted at install time is left out — asking for
 * one of those would be a dialog that proves nothing and teaches the user that this
 * screen wastes their time.
 */
internal fun onboardingPermissions(coordinator: PermissionCoordinator): List<AppPermission> =
    listOf(
        AppPermission.RECORD_AUDIO,
        AppPermission.POST_NOTIFICATIONS,
        AppPermission.CAMERA,
        AppPermission.READ_CONTACTS,
        AppPermission.BLUETOOTH_CONNECT,
    ).filter { permission ->
        permission.isRuntimePermission &&
            permission.appliesToThisDevice &&
            !coordinator.status(permission, activity = null).isGranted
    }

internal const val TAG_PROGRESS = "onboarding-progress"
internal const val TAG_ALLOW = "onboarding-allow"
internal const val TAG_SKIP = "onboarding-skip"
internal const val TAG_SKIP_ALL = "onboarding-skip-all"

@ThemePreviews
@Composable
private fun PermissionOnboardingPreview() = PreviewSurface {
    // Rendered from the model rather than a live coordinator, so the preview needs no
    // Android permission state.
    Column(
        modifier = Modifier.padding(AppTheme.spacing.huge),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.large),
    ) {
        Text(AppPermission.RECORD_AUDIO.title, style = MaterialTheme.typography.headlineSmall)
        Text(AppPermission.RECORD_AUDIO.rationale, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = "If you say no: ${AppPermission.RECORD_AUDIO.deniedExplanation}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
