package ai.opencode.feature.media

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal object AgentFileSearchContext : DependencyKey<CoroutineContext> {
    override val liveValue = Dispatchers.Default
    override val testValue = EmptyCoroutineContext
}

class AgentFileSearchMatches private constructor(private val starts: IntArray, val length: Int) {
    val count: Int get() = starts.size
    fun range(index: Int): IntRange = starts[index] until starts[index] + length

    fun firstEndingAfter(offset: Int): Int {
        var low = 0
        var high = count
        while (low < high) {
            val mid = (low + high) ushr 1
            if (starts[mid] + length <= offset) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        suspend fun find(text: String, query: String): AgentFileSearchMatches {
            if (query.isEmpty()) return AgentFileSearchMatches(IntArray(0), 0)
            val context = currentCoroutineContext()
            var starts = IntArray(64)
            var count = 0
            var offset = 0
            var checkpoint = 0
            while (offset <= text.length - query.length) {
                if (offset >= checkpoint) {
                    context.ensureActive()
                    checkpoint = offset + 1024
                }
                if (text.regionMatches(offset, query, 0, query.length, ignoreCase = true)) {
                    if (count == starts.size) starts = starts.copyOf(starts.size * 2)
                    starts[count++] = offset
                    offset += query.length
                } else offset++
            }
            return AgentFileSearchMatches(starts.copyOf(count), query.length)
        }
    }
}

class AgentFileSearchFeature : Reducer<AgentFileSearchFeature.State, AgentFileSearchFeature.Action> {
    data class State(
        val document: AgentTextDocument,
        val query: String = "",
        val matches: AgentFileSearchMatches? = null,
        val selectedIndex: Int = -1,
        val generation: Int = 0,
        val selectionRevision: Int = 0,
        val searching: Boolean = false,
    ) {
        val selectedRange: IntRange? get() = matches?.takeIf { selectedIndex in 0 until it.count }?.range(selectedIndex)
    }

    sealed interface Action {
        data class QueryChanged(val query: String) : Action
        data class Found(val generation: Int, val matches: AgentFileSearchMatches) : Action
        data object Previous : Action
        data object Next : Action
    }

    private object SearchID

    override fun reduce(state: State, action: Action): Reduced<State, Action> = when (action) {
        is Action.QueryChanged -> if (action.query == state.query) Reduced(state) else {
            val next = state.copy(query = action.query, matches = null, selectedIndex = -1,
                generation = state.generation + 1, searching = action.query.isNotEmpty())
            val context = dependency(AgentFileSearchContext)
            if (next.query.isEmpty()) Reduced(next, Effect.cancel(SearchID)) else Reduced(next, Effect.run<Action> { send ->
                val matches = withContext(context) { AgentFileSearchMatches.find(next.document.text, next.query) }
                send(Action.Found(next.generation, matches))
            }.cancellable(SearchID, cancelInFlight = true))
        }
        is Action.Found -> if (action.generation != state.generation || !state.searching) Reduced(state) else Reduced(
            state.copy(matches = action.matches, selectedIndex = if (action.matches.count > 0) 0 else -1,
                searching = false, selectionRevision = state.selectionRevision + 1),
        )
        Action.Next, Action.Previous -> {
            val count = state.matches?.count ?: 0
            if (count == 0) Reduced(state) else Reduced(state.copy(
                selectedIndex = Math.floorMod(state.selectedIndex + if (action == Action.Next) 1 else -1, count),
                selectionRevision = state.selectionRevision + 1,
            ))
        }
    }
}
