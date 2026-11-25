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
        val history: List<String> = listOf(root),
        val listings: Map<String, OpenCodeProjectFiles> = emptyMap(),
        val query: String = "",
        val showHidden: Boolean = false,
        val searchResults: List<OpenCodeProjectFile> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val generation: Int = 0,
        val autoDescendRemaining: Int = 0,
    ) {
        val path: String get() = history.last()
        val breadcrumbs: List<String> get() {
            val parents = mutableListOf<String>()
            var current: String? = path
            while (current != null && !ProjectFilePaths.relative(root, current).isNullOrEmpty()) {
                parents += current
                current = ProjectFilePaths.parent(current)
            }
            return listOf(root) + parents.asReversed()
        }
        val searching: Boolean get() = query.isNotBlank()
        val entries: List<OpenCodeProjectFile> get() =
            (if (searching) searchResults else listings[path]?.entries.orEmpty()).filter { visible(it.path) }

        private fun visible(path: String): Boolean = showHidden ||
            ProjectFilePaths.relative(root, path).orEmpty().split('/').none { it.startsWith('.') }

        fun entryName(file: OpenCodeProjectFile): String {
            if (!file.isDirectory || searching) return file.name
            val (end, _) = compactedPath(file.path, MAX_COMPACT_DEPTH)
            val suffix = ProjectFilePaths.relative(file.path, end).orEmpty()
            return if (suffix.isEmpty()) file.name else "${file.name}/$suffix"
        }

        internal fun singleChild(path: String): OpenCodeProjectFile? = listings[path]?.entries?.singleOrNull()?.takeIf {
            it.isDirectory && visible(it.path) &&
                !ProjectFilePaths.relative(root, it.path).isNullOrEmpty() &&
                ProjectFilePaths.parent(it.path)?.let { parent -> ProjectFilePaths.relative(path, parent) == "" } == true
        }

        internal fun compactedPath(start: String, remaining: Int): Pair<String, Int> {
            var current = start
            var left = remaining
            while (left > 1) {
                current = singleChild(current)?.path ?: break
                left--
            }
            return current to left
        }
    }

    sealed interface Action {
        data object Appeared : Action
        data object Refresh : Action
        data object Back : Action
        data object Close : Action
        data object ToggleHidden : Action
        data class DirectoryTapped(val path: String) : Action
        data class BreadcrumbTapped(val path: String) : Action
        data class QueryChanged(val query: String) : Action
        data class Loaded(val generation: Int, val path: String, val result: Result<OpenCodeProjectFiles>) : Action
        data class Searched(val generation: Int, val result: Result<List<OpenCodeProjectFile>>) : Action
    }

    private enum class CancelID { Load }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = when (action) {
        Action.Close -> Reduced(state)
        Action.Appeared -> if (state.loading || state.listings.containsKey(state.path)) Reduced(state) else load(state)
        Action.Refresh -> load(state.copy(listings = emptyMap(), autoDescendRemaining = 0))
        Action.ToggleHidden -> Reduced(state.copy(showHidden = !state.showHidden))
        Action.Back -> when {
            state.query.isNotEmpty() -> navigate(state.copy(query = "", searchResults = emptyList(), autoDescendRemaining = 0))
            state.history.size > 1 -> navigate(state.copy(history = state.history.dropLast(1), autoDescendRemaining = 0))
            else -> Reduced(state)
        }
        is Action.DirectoryTapped -> openDirectory(state, action.path, compact = true)
        is Action.BreadcrumbTapped -> if (ProjectFilePaths.relative(action.path, state.path) == null) Reduced(state)
            else openDirectory(state, action.path, compact = false)
        is Action.QueryChanged -> {
            val next = state.copy(query = action.query, searchResults = emptyList(), autoDescendRemaining = 0)
            if (next.searching) load(next) else navigate(next)
        }
        is Action.Loaded -> {
            if (state.generation != action.generation || state.path != action.path || state.searching) Reduced(state)
            else action.result.fold(
                onSuccess = { listing ->
                    val loaded = state.copy(loading = false, error = null, listings = state.listings + (action.path to listing))
                    val child = if (loaded.autoDescendRemaining > 1) loaded.singleChild(loaded.path) else null
                    if (child == null) Reduced(loaded.copy(autoDescendRemaining = 0)) else navigate(loaded.copy(
                        history = loaded.history.dropLast(1) + child.path,
                        autoDescendRemaining = loaded.autoDescendRemaining - 1,
                    ))
                },
                onFailure = { Reduced(state.copy(loading = false, error = it.message, autoDescendRemaining = 0)) },
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

    private fun openDirectory(state: State, value: String, compact: Boolean): Reduced<State, Action> {
        val path = ProjectFilePaths.normalize(value) ?: return Reduced(state)
        if (ProjectFilePaths.relative(state.root, path) == null) return Reduced(state)
        val same = ProjectFilePaths.relative(state.path, path) == ""
        if (same && state.query.isEmpty()) return Reduced(if (compact) state else state.copy(autoDescendRemaining = 0))
        return navigate(state.copy(
            history = if (same) state.history else state.history + path,
            query = "", searchResults = emptyList(),
            autoDescendRemaining = if (compact) MAX_COMPACT_DEPTH else 0,
        ))
    }

    private fun navigate(state: State): Reduced<State, Action> {
        val (path, remaining) = state.compactedPath(state.path, state.autoDescendRemaining)
        val next = state.copy(history = state.history.dropLast(1) + path, autoDescendRemaining = remaining)
        return if (next.listings.containsKey(path)) Reduced(
            next.copy(generation = next.generation + 1, loading = false, error = null, autoDescendRemaining = 0), Effect.cancel(CancelID.Load),
        ) else load(next)
    }

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

    companion object {
        const val MAX_COMPACT_DEPTH = 16
    }
}
