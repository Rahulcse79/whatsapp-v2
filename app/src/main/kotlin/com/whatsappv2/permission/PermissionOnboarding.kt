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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.whatsappv2.background.BackgroundAccess
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
 * ## The last step is not a runtime permission
 *
 * Battery-optimisation exemption — "run in the background" — is asked for here too, after
 * the runtime permissions and last of all. It is not an [AppPermission] and cannot be:
 * there is no `requestPermissions` call behind it, no rationale flow, and no "denied
 * permanently" state, only a system switch the app may open and the user may set. So it
 * gets a step of the same shape rather than a place in the enum, which would put a member
 * in `AppPermission` that every `when` over it would have to pretend to handle.
 *
 * It is last deliberately. The runtime permissions block features and this one blocks
 * *reachability*, which is harder to feel on a phone with no account on it yet — by the
 * end of the flow the user has at least seen what the app is for. And it is still asked
 * again after the first login, by `BackgroundAccessPrompt`, for anyone who took the
 * "Skip all" door out of this screen before reaching it.
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
    /**
     * The battery-optimisation switch, for the final step. Null leaves that step out —
     * which is how a preview, a test, and a build with nothing to show for it get the
     * runtime permissions alone.
     */
    backgroundAccess: BackgroundAccess? = null,
) {
    val context = LocalContext.current

    // Everything that exists on this device and is not already held. A permission the
    // platform grants implicitly must not be shown as something to decide.
    val requests = remember(coordinator) { onboardingPermissions(coordinator) }

    // Decided once, on the way in, and not re-read. The step count is printed on every
    // screen ("Step 2 of 6"), and a count that shrank underneath the user when they came
    // back from the system dialog would be worse than a count that is briefly one too many.
    val backgroundStep = remember(backgroundAccess) {
        backgroundAccess != null && !backgroundAccess.isExempt()
    }
    val stepCount = requests.size + if (backgroundStep) 1 else 0

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

    if (current != null) {
        OnboardingStep(
            content = current.asStepContent(),
            stepNumber = index + 1,
            stepCount = stepCount,
            onAllow = { launcher.launch(current.manifestPermission) },
            onSkipOne = { index += 1 },
            onSkipAll = onFinished,
            modifier = modifier,
        )
        return
    }

    if (backgroundStep && index == requests.size && backgroundAccess != null) {
        // Answered either way, so `BackgroundAccessPrompt` does not raise the same
        // question again after the first login. Somebody who leaves by "Skip all" never
        // reaches this line, and that later prompt is deliberately still waiting for them.
        fun answered() {
            backgroundAccess.markAsked()
            index += 1
        }

        OnboardingStep(
            content = BACKGROUND_ACCESS_STEP,
            stepNumber = stepCount,
            stepCount = stepCount,
            onAllow = {
                answered()
                // The system's own dialog decides this, not the app. The full
                // battery-optimisation list is the fallback for a handset whose
                // per-package dialog is missing or whose OEM refuses the direct request.
                runCatching { context.startActivity(backgroundAccess.requestIntent()) }
                    .onFailure {
                        runCatching { context.startActivity(backgroundAccess.settingsIntent()) }
                    }
            },
            onSkipOne = ::answered,
            onSkipAll = onFinished,
            modifier = modifier,
        )
        return
    }

    // Nothing left to ask, on this run or at all. Finishing from a composition effect
    // rather than during composition: onFinished writes to a tracker.
    LaunchedEffect(Unit) { onFinished() }
}

/**
 * What one step says, gathered into one value.
 *
 * Four fields rather than an [AppPermission], because the last step is not one:
 * battery-optimisation exemption is a system switch, not a runtime permission, and it has
 * to read identically to the steps before it or it looks like a different kind of request
 * bolted on the end. Grouped rather than passed loose for the reason `SettingsActions` is:
 * they are four halves of one thing, and spread across a signature they pushed
 * [OnboardingStep] past the parameter limit that keeps it readable.
 */
private data class StepContent(
    val icon: ImageVector,
    val title: String,
    val rationale: String,
    /** What the user loses by saying no, said *before* they choose. */
    val deniedExplanation: String,
)

/** A runtime permission's own words, which already exist on the enum. */
private fun AppPermission.asStepContent() = StepContent(
    icon = iconForSheet(),
    title = title,
    rationale = rationale,
    deniedExplanation = deniedExplanation,
)

/** One thing to be asked for, explained before it is asked. */
@Composable
private fun OnboardingStep(
    content: StepContent,
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
                    imageVector = content.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(content.title, style = MaterialTheme.typography.headlineSmall)
            }

            Text(content.rationale, style = MaterialTheme.typography.bodyMedium)

            Text(
                // Said before the choice, not after it. Somebody deciding whether to
                // decline is entitled to know what declining costs.
                text = "If you say no: ${content.deniedExplanation}",
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

/**
 * The last step's words.
 *
 * A file-level value rather than four arguments at the call site, so the wording sits
 * beside the permission rationales it has to read like rather than inside a `when`.
 */
private val BACKGROUND_ACCESS_STEP = StepContent(
    icon = Icons.Filled.BatteryFull,
    title = "Run in the background",
    rationale = "This phone may stop the app when it is not on screen, and calls to your " +
        "extension would then be missed until you open it again. Allowing the app to run " +
        "in the background keeps it registered and able to ring.",
    deniedExplanation = "the phone may stop the app when it is not on screen, so calls " +
        "could be missed. You can allow it later from Settings.",
)

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
