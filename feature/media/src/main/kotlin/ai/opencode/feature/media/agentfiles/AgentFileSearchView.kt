package ai.opencode.feature.media

import ai.opencode.core.R as CoreR
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeIconButtonStyle
import ai.opencode.core.ui.OpenCodeSearchField
import ai.opencode.core.ui.OpenCodeSpacing
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.SFSymbol
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction

@Composable
internal fun AgentFileSearchBar(state: AgentFileSearchFeature.State, send: (AgentFileSearchFeature.Action) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.fillMaxWidth().padding(horizontal = OpenCodeSpacing.s8).testTag("agent-file-search-bar")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OpenCodeSearchField(state.query, { send(AgentFileSearchFeature.Action.QueryChanged(it)) },
                stringResource(R.string.media_file_find), Modifier.weight(1f).focusRequester(focus).testTag("agent-file-search-query"),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }))
            OpenCodeIconButton(SFSymbol.Xmark, stringResource(CoreR.string.common_close), {
                focusManager.clearFocus()
                onClose()
            }, Modifier.testTag("agent-file-search-close"), style = OpenCodeIconButtonStyle.Standard)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val count = state.matches?.count ?: 0
            Text(when {
                state.searching -> stringResource(R.string.media_file_searching)
                state.query.isEmpty() -> ""
                count == 0 -> stringResource(R.string.media_file_no_matches)
                else -> stringResource(R.string.media_file_match_counter, state.selectedIndex + 1, count)
            }, Modifier.weight(1f).padding(horizontal = OpenCodeSpacing.s8).semantics { liveRegion = LiveRegionMode.Polite },
                style = OpenCodeTextStyles.caption1)
            OpenCodeIconButton(SFSymbol.ChevronUp, stringResource(R.string.media_file_previous_match), { send(AgentFileSearchFeature.Action.Previous) },
                Modifier.testTag("agent-file-search-previous"), style = OpenCodeIconButtonStyle.Standard, enabled = count > 0)
            OpenCodeIconButton(SFSymbol.ChevronDown, stringResource(R.string.media_file_next_match), { send(AgentFileSearchFeature.Action.Next) },
                Modifier.testTag("agent-file-search-next"), style = OpenCodeIconButtonStyle.Standard, enabled = count > 0)
        }
    }
}
