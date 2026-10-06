package com.whatsappv2.feature.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whatsappv2.domain.voice.EnrolmentProgress
import com.whatsappv2.domain.voice.VoiceProfile
import java.text.DateFormat
import java.util.Date

/**
 * Settings → Train My Voice (ADR-013).
 *
 * ## What the user is actually being offered
 *
 * Not "noise cancellation" — that is already on and needs no profile. This is the thing
 * a noise suppressor cannot do: keep *other people's voices* out. So the copy says that,
 * and it says the limit too. A feature that promises silence and delivers 93% of a
 * nearby conversation removed is a feature people stop trusting; one that says what it
 * does is one they can work with.
 *
 * ## Record, replace, delete, and what is stored
 *
 * One profile. Training again replaces it — stated on the button rather than buried,
 * because "Train again" that silently merged would be a different and worse feature (see
 * [VoiceProfile]). Delete is always offered beside it: a profile the user cannot remove
 * is a recording of their voice they cannot take back, even though it is not one.
 */
@Composable
internal fun ColumnScope.VoiceProfileSection(
    /** Whether the trained voice is used on calls. The switch is rendered below. */
    filteringEnabled: Boolean,
    onFilteringChange: (Boolean) -> Unit,
    viewModel: VoiceProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val microphone = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.train() }

    Text("Your voice", style = MaterialTheme.typography.titleMedium)
    Text(
        text = "Train the app on your voice and it will keep only you on the call — " +
            "other people talking nearby are muted while you are not speaking. " +
            "Everything stays on this phone; the recording is turned into a short " +
            "signature and then discarded.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    when (val progress = state.progress) {
        is EnrolmentProgress.Recording -> Recording(progress, viewModel.targetSeconds, viewModel::cancel)
        EnrolmentProgress.Building -> Text(
            "Building your voice signature…",
            style = MaterialTheme.typography.bodyMedium,
        )
        is EnrolmentProgress.Ready -> Acknowledgeable(
            "Done. Your voice profile is ready.",
            viewModel::acknowledge,
        )
        is EnrolmentProgress.Failed -> Acknowledgeable(progress.reason.message(), viewModel::acknowledge)
        null -> Idle(state.profile, viewModel.targetSeconds) {
            microphone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    if (!state.isEnrolling && state.profile != null) {
        TextButton(onClick = viewModel::delete) { Text("Delete my voice profile") }
    }

    // The switch for the promise the paragraph above makes, in the same card as the thing
    // it switches. It knows whether a profile exists so it can say "on, but nothing to
    // filter against yet" rather than claiming to be doing something it cannot.
    LiveCallFilteringToggle(
        enabled = filteringEnabled,
        hasProfile = state.profile != null,
        onChange = onFilteringChange,
    )
}

@Composable
private fun ColumnScope.Idle(profile: VoiceProfile?, targetSeconds: Int, onTrain: () -> Unit) {
    Text(
        text = profile?.let {
            val on = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it.createdAtEpochMillis))
            "Trained on $on from ${it.enrolledSeconds} seconds of speech."
        } ?: "No voice profile yet.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Button(onClick = onTrain) {
        Text(if (profile == null) "Train my voice" else "Train again (replaces the current one)")
    }
    Text(
        text = "You will be asked to talk for about $targetSeconds seconds. " +
            "Read anything — what you say does not matter, only how you sound.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ColumnScope.Recording(
    progress: EnrolmentProgress.Recording,
    targetSeconds: Int,
    onCancel: () -> Unit,
) {
    Text("Keep talking… ${progress.seconds} of $targetSeconds seconds", style = MaterialTheme.typography.bodyMedium)
    LinearProgressIndicator(
        progress = { progress.fraction },
        modifier = Modifier.fillMaxWidth(),
    )
    // Pauses are fine and the count shows it: only speech is counted, so a user who
    // stops to think sees the number stop rather than the bar filling with silence.
    Text(
        text = "Only your speech counts, so pauses are fine.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = onCancel) { Text("Stop") }
}

@Composable
private fun ColumnScope.Acknowledgeable(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onDismiss) { Text("OK") }
    }
}

/** Plain English, because every one of these is something the user can act on. */
private fun EnrolmentProgress.Failed.Reason.message(): String = when (this) {
    EnrolmentProgress.Failed.Reason.TOO_LITTLE_SPEECH ->
        "That was not quite enough speech. Try again and keep talking until the bar fills."
    EnrolmentProgress.Failed.Reason.NO_MICROPHONE ->
        "The microphone is busy. End any call and try again."
    EnrolmentProgress.Failed.Reason.NO_MODEL ->
        "This build has no voice model, so a profile cannot be made."
    EnrolmentProgress.Failed.Reason.FAILED ->
        "That did not work. Please try again."
}
