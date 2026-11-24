package ai.opencode.feature.session

import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.Reduced
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.server.model.ProjectFilePaths

internal fun SessionFeature.reduceProjectFiles(state: State, action: Action): Reduced<State, Action>? = when (action) {
    Action.ProjectFilesTapped -> {
        val root = state.effectiveDirectory?.let(ProjectFilePaths::normalize)
        if (root == null || state.projectFiles != null) Reduced(state)
        else Reduced(state.copy(projectFiles = ProjectFilesFeature.State(
            server = state.server, root = root,
            pageID = (uuid.next().hashCode() and 0x3FFFFFFF) or 0xC0000000.toInt(),
        )))
    }
    is Action.ProjectFiles -> when ((action.action as? PresentationAction.Presented)?.action) {
        ProjectFilesFeature.Action.Close -> Reduced(state.copy(projectFiles = null))
        else -> Reduced(state)
    }
    else -> null
}
