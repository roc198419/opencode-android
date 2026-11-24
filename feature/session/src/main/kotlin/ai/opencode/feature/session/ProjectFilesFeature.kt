package ai.opencode.feature.session

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.feature.session.sheet.clientResult
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeProjectFile
import ai.opencode.server.model.OpenCodeProjectFiles
import ai.opencode.server.model.ProjectFilePaths
import kotlin.time.Duration.Companion.milliseconds

class ProjectFilesFeature : Reducer<ProjectFilesFeature.State, ProjectFilesFeature.Action> {
    data class State(
        val server: OpenCodeServer,
        val root: String,
        val pageID: Int,
        val paths: List<String> = listOf(root),
        val listings: Map<String, OpenCodeProjectFiles> = emptyMap(),
        val query: String = "",
        val showHidden: Boolean = false,
        val searchResults: List<OpenCodeProjectFile> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val generation: Int = 0,
    ) {
        val path: String get() = paths.last()
        val searching: Boolean get() = query.isNotBlank()
        val entries: List<OpenCodeProjectFile> get() =
            (if (searching) searchResults else listings[path]?.entries.orEmpty()).filter {
                showHidden || ProjectFilePaths.relative(root, it.path).orEmpty().split('/').none { part -> part.startsWith('.') }
            }
    }

    sealed interface Action {
        data object Appeared : Action
        data object Refresh : Action
        data object Back : Action
        data object Close : Action
        data object ToggleHidden : Action
        data class DirectoryTapped(val path: String) : Action
        data class QueryChanged(val query: String) : Action
        data class Loaded(val generation: Int, val path: String, val result: Result<OpenCodeProjectFiles>) : Action
        data class Searched(val generation: Int, val result: Result<List<OpenCodeProjectFile>>) : Action
    }

    private enum class CancelID { Load }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = when (action) {
        Action.Close -> Reduced(state)
        Action.Appeared -> if (state.loading || state.listings.containsKey(state.path)) Reduced(state) else load(state)
        Action.Refresh -> load(state.copy(listings = emptyMap()))
        Action.ToggleHidden -> Reduced(state.copy(showHidden = !state.showHidden))
        Action.Back -> when {
            state.query.isNotEmpty() -> navigate(state.copy(query = "", searchResults = emptyList()))
            state.paths.size > 1 -> navigate(state.copy(paths = state.paths.dropLast(1)))
            else -> Reduced(state)
        }
        is Action.DirectoryTapped -> {
            val path = ProjectFilePaths.normalize(action.path)
            if (path == null || ProjectFilePaths.relative(state.root, path) == null) Reduced(state)
            else {
                val index = state.paths.indexOf(path)
                val paths = if (index >= 0) state.paths.take(index + 1) else state.paths + path
                navigate(state.copy(paths = paths, query = "", searchResults = emptyList()))
            }
        }
        is Action.QueryChanged -> {
            val next = state.copy(query = action.query, searchResults = emptyList())
            if (next.searching) load(next) else navigate(next)
        }
        is Action.Loaded -> {
            if (state.generation != action.generation || state.path != action.path || state.searching) Reduced(state)
            else action.result.fold(
                onSuccess = { Reduced(state.copy(loading = false, error = null, listings = state.listings + (action.path to it))) },
                onFailure = { Reduced(state.copy(loading = false, error = it.message)) },
            )
        }
        is Action.Searched -> {
            if (state.generation != action.generation || !state.searching) Reduced(state)
            else action.result.fold(
                onSuccess = { Reduced(state.copy(loading = false, error = null, searchResults = it)) },
                onFailure = { Reduced(state.copy(loading = false, error = it.message)) },
            )
        }
    }

    private fun navigate(state: State): Reduced<State, Action> =
        if (state.listings.containsKey(state.path)) Reduced(
            state.copy(generation = state.generation + 1, loading = false, error = null), Effect.cancel(CancelID.Load),
        ) else load(state)

    private fun load(state: State): Reduced<State, Action> {
        val next = state.copy(generation = state.generation + 1, loading = true, error = null)
        val client = dependency(OpenCodeClient)
        val clock = dependency(Clock)
        return Reduced(next, Effect.run<Action> { send ->
            if (next.searching) {
                clock.sleep(250.milliseconds)
                val result = clientResult {
                    client.findFiles(next.server, next.root, next.query.trim(), 200).mapNotNull { file ->
                        val path = ProjectFilePaths.resolve(next.root, file.absolutePath) ?: return@mapNotNull null
                        if (ProjectFilePaths.relative(next.root, path).isNullOrEmpty()) null else OpenCodeProjectFile(path, false)
                    }.distinctBy { it.path }
                }
                send(Action.Searched(next.generation, result))
            } else {
                send(Action.Loaded(next.generation, next.path, clientResult { client.listProjectFiles(next.server, next.root, next.path) }))
            }
        }.cancellable(CancelID.Load, cancelInFlight = true))
    }
}
