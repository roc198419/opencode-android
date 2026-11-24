package ai.opencode.feature.settings

import ai.opencode.core.R as CoreR
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeAlertDialog
import ai.opencode.core.ui.OpenCodePrimaryButton
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.system.updates.AppUpdateError
import ai.opencode.feature.system.updates.AppUpdateFeature
import ai.opencode.feature.system.updates.AppUpdateFeature.Action
import ai.opencode.feature.system.updates.AppUpdateFeature.Phase
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun AppUpdateView(store: StoreOf<AppUpdateFeature.State, Action>) {
    val state by store.observe { it }
    if (!state.presented) return
    OpenCodeAlertDialog(
        onDismissRequest = { store.send(Action.Dismiss) },
        title = { Text(stringResource(R.string.settings_update_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.currentVersion.isNotEmpty()) Text(
                    stringResource(R.string.settings_update_current_version, state.currentVersion),
                    style = OpenCodeTextStyles.footnote, color = OpenCodeTheme.colors.secondaryText,
                )
                val message = when (state.phase) {
                    Phase.Idle, Phase.Checking -> stringResource(R.string.settings_update_checking)
                    Phase.Current -> stringResource(R.string.settings_update_current)
                    Phase.Available -> stringResource(R.string.settings_update_available, state.release?.versionName.orEmpty(), (state.release?.apkSize ?: 0) / 1_000_000)
                    Phase.Downloading -> stringResource(R.string.settings_update_downloading)
                    Phase.Ready -> stringResource(R.string.settings_update_ready)
                    Phase.Permission -> stringResource(R.string.settings_update_permission)
                    Phase.Installing -> stringResource(R.string.settings_update_installing)
                    Phase.Unsupported -> stringResource(R.string.settings_update_unsupported)
                    Phase.Failed -> stringResource(when (state.error) {
                        AppUpdateError.Verification -> R.string.settings_update_verification_failed
                        AppUpdateError.Download -> R.string.settings_update_download_failed
                        AppUpdateError.Install -> R.string.settings_update_install_failed
                        else -> R.string.settings_update_check_failed
                    })
                }
                Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                when (state.phase) {
                    Phase.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    Phase.Downloading -> {
                        val progress = state.bytes.toFloat() / (state.release?.apkSize ?: 1).coerceAtLeast(1)
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.settings_update_progress, (progress * 100).toInt()), style = OpenCodeTextStyles.footnote)
                    }
                    else -> Unit
                }
                if (state.phase != Phase.Unsupported) {
                    SettingsToggleRow(
                        stringResource(R.string.settings_update_automatic), state.automaticallyChecks,
                        onCheckedChange = { store.send(Action.AutomaticallyChecksChanged(it)) },
                    )
                    Text(stringResource(R.string.settings_update_automatic_detail), style = OpenCodeTextStyles.footnote, color = OpenCodeTheme.colors.secondaryText)
                }
            }
        },
        confirmButton = {
            when (state.phase) {
                Phase.Available -> OpenCodePrimaryButton(onClick = { store.send(Action.Download) }) { Text(stringResource(R.string.settings_update_download)) }
                Phase.Ready, Phase.Permission -> OpenCodePrimaryButton(onClick = { store.send(Action.Install) }) { Text(stringResource(R.string.settings_update_install)) }
                Phase.Failed -> OpenCodePrimaryButton(onClick = {
                    store.send(if (state.release == null) Action.Check(manual = true) else if (state.downloaded) Action.Install else Action.Download)
                }) { Text(stringResource(CoreR.string.common_retry)) }
                Phase.Downloading -> TextButton(onClick = { store.send(Action.CancelDownload) }) { Text(stringResource(CoreR.string.common_cancel)) }
                Phase.Current -> TextButton(onClick = { store.send(Action.Check(manual = true)) }) { Text(stringResource(R.string.settings_update_check)) }
                Phase.Unsupported -> TextButton(onClick = { store.send(Action.Dismiss) }) { Text(stringResource(CoreR.string.common_ok)) }
                else -> Unit
            }
        },
        dismissButton = {
            if (state.phase != Phase.Unsupported) TextButton(onClick = { store.send(Action.Dismiss) }) { Text(stringResource(CoreR.string.common_close)) }
        },
    )
}
