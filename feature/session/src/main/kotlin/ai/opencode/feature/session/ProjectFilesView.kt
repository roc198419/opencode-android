package ai.opencode.feature.session

import ai.opencode.core.R as CoreR
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeContentUnavailable
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeIconButtonStyle
import ai.opencode.core.ui.OpenCodeSpacing
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeTextField
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.feature.media.MermaidTheme
import ai.opencode.feature.media.LocalMediaPresenter
import ai.opencode.server.model.AgentFileReference
import ai.opencode.server.model.ProjectFilePaths
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow

fun sessionProjectFilesStackPage(state: SessionFeature.State): SessionStackPage? = state.projectFiles?.let {
    SessionStackPage(it.pageID, ai.opencode.core.strings.localized(R.string.session_project_files_title))
}

@Composable
fun ProjectFilesPage(store: StoreOf<SessionFeature.State, SessionFeature.Action>, modifier: Modifier = Modifier) {
    val current by store.observe { it.projectFiles }
    var retained by remember { mutableStateOf(current) }
    SideEffect { if (current != null) retained = current }
    val state = current ?: retained ?: return
    fun send(action: ProjectFilesFeature.Action) = store.send(SessionFeature.Action.ProjectFiles(PresentationAction.Presented(action)))
    fun back() = if (state.query.isNotEmpty() || state.history.size > 1) send(ProjectFilesFeature.Action.Back) else send(ProjectFilesFeature.Action.Close)
    BackHandler(enabled = current != null && (state.query.isNotEmpty() || state.history.size > 1)) { back() }
    LaunchedEffect(state.pageID) { send(ProjectFilesFeature.Action.Appeared) }
    val context = LocalContext.current
    val presenter = LocalMediaPresenter.current
    val theme = if (OpenCodeTheme.colors.isDark) MermaidTheme.Dark else MermaidTheme.Light
    val focus = LocalFocusManager.current
    val scrollStates = rememberSaveableStateHolder()
    val folderDescription = stringResource(R.string.session_folder_title)
    val breadcrumbs = state.breadcrumbs
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().testTag("project-files")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = OpenCodeSpacing.s8), verticalAlignment = Alignment.CenterVertically) {
            OpenCodeIconButton(SFSymbol.ChevronLeft, stringResource(CoreR.string.common_back), { back() }, style = OpenCodeIconButtonStyle.Standard)
            Text(stringResource(R.string.session_project_files_title), Modifier.weight(1f).semantics { heading() }, style = OpenCodeTextStyles.headline, color = OpenCodeTheme.colors.text)
            OpenCodeIconButton(
                if (state.showHidden) SFSymbol.Eye else SFSymbol.EyeSlash,
                stringResource(R.string.session_project_files_hidden),
                { send(ProjectFilesFeature.Action.ToggleHidden) },
                Modifier.semantics { selected = state.showHidden }, style = OpenCodeIconButtonStyle.Standard,
            )
            OpenCodeIconButton(SFSymbol.ArrowClockwise, stringResource(R.string.session_project_files_refresh), { send(ProjectFilesFeature.Action.Refresh) }, style = OpenCodeIconButtonStyle.Standard)
        }
        key(state.path) {
            val breadcrumbScroll = rememberLazyListState(initialFirstVisibleItemIndex = breadcrumbs.lastIndex)
            LazyRow(Modifier.fillMaxWidth().testTag("project-files-breadcrumbs"), state = breadcrumbScroll) {
                items(breadcrumbs, key = { it }) { path ->
                    TextButton(onClick = { send(ProjectFilesFeature.Action.BreadcrumbTapped(path)) }, shape = OpenCodeShape.control) {
                        Text(if (path == state.root) path.trimEnd('/').substringAfterLast('/').ifEmpty { path } else path.substringAfterLast('/'), maxLines = 1)
                        if (path != breadcrumbs.last()) OpenCodeIcon(SFSymbol.ChevronRight, null)
                    }
                }
            }
        }
        OpenCodeTextField(
            value = state.query,
            onValueChange = { send(ProjectFilesFeature.Action.QueryChanged(it)) },
            placeholder = stringResource(R.string.session_project_files_search),
            label = stringResource(CoreR.string.common_search),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = OpenCodeSpacing.s16, vertical = OpenCodeSpacing.s8).testTag("project-files-search"),
            trailingIcon = if (state.query.isNotEmpty()) ({
                OpenCodeIconButton(SFSymbol.Xmark, stringResource(CoreR.string.common_cancel), { send(ProjectFilesFeature.Action.QueryChanged("")) }, style = OpenCodeIconButtonStyle.Standard)
            }) else null,
        )
        if (state.searching) Text(stringResource(R.string.session_project_files_search_limit), Modifier.padding(horizontal = OpenCodeSpacing.s16, vertical = OpenCodeSpacing.s4), style = OpenCodeTextStyles.caption1, color = OpenCodeTheme.colors.secondaryText)
        HorizontalDivider(color = OpenCodeTheme.colors.border)
        if (state.loading && state.entries.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
        when {
            state.error != null -> OpenCodeContentUnavailable(
                SFSymbol.QuestionmarkFolder, stringResource(R.string.session_folder_open_failed_title), Modifier.weight(1f).fillMaxWidth(),
                action = stringResource(CoreR.string.common_retry), onAction = { send(ProjectFilesFeature.Action.Refresh) },
            )
            state.loading && state.entries.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.entries.isEmpty() -> OpenCodeContentUnavailable(
                SFSymbol.Folder,
                if (state.searching) stringResource(R.string.session_folder_search_empty_title, state.query) else stringResource(R.string.session_media_empty_files_title),
                Modifier.weight(1f).fillMaxWidth(),
            )
            else -> scrollStates.SaveableStateProvider(if (state.searching) "search:${state.query}" else state.path) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = rememberLazyListState()) {
                    items(state.entries, key = { it.path }) { file ->
                        Row(
                            Modifier.fillMaxWidth().semantics { if (file.isDirectory) stateDescription = folderDescription }.clickable(role = Role.Button) {
                                if (current == null) return@clickable
                                focus.clearFocus()
                                if (file.isDirectory) send(ProjectFilesFeature.Action.DirectoryTapped(file.path))
                                else SessionTranscriptView.openFile(AgentFileReference(file.path), store, context, null, presenter, theme)
                            }.openCodeTapTarget().padding(horizontal = OpenCodeSpacing.s16, vertical = OpenCodeSpacing.s12),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(OpenCodeSpacing.s12),
                        ) {
                            OpenCodeIcon(if (file.isDirectory) "folder" else AgentFileReference(file.path).symbolName, null)
                            Column(Modifier.weight(1f)) {
                                Text(state.entryName(file), style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.text, maxLines = 1, overflow = if (file.isDirectory) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis)
                                if (state.searching) Text(ProjectFilePaths.relative(state.root, file.path).orEmpty(), style = OpenCodeTextStyles.caption1, color = OpenCodeTheme.colors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (file.isDirectory) OpenCodeIcon(SFSymbol.ChevronRight, null)
                        }
                    }
                }
            }
        }
    }
}
