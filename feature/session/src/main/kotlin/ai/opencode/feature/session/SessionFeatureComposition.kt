package ai.opencode.feature.session

import ai.opencode.core.diagnostics.LabMarks
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.core.store.combine
import ai.opencode.core.store.ifLet
import ai.opencode.core.store.onChange
import ai.opencode.core.store.scope
import ai.opencode.feature.composer.ComposerAttachmentsFeature
import ai.opencode.feature.composer.DictationFeature
import ai.opencode.feature.composer.DraftState
import ai.opencode.feature.composer.RequestDockFeature
import ai.opencode.feature.media.BrowserFeature
import ai.opencode.feature.media.VoiceViewingPage
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.CancelID
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.voice.VoiceFeature
import kotlin.time.Duration.Companion.milliseconds

// How the session's reducers compose: the core steps, the child features, and the `onChange` hooks
// around them. `CancelID` and `coreSteps` stay in `SessionFeature.kt`.

/** The ordered core steps; the first that takes the action wins. */
internal fun SessionFeature.reduceCore(state: State, action: Action): Reduced<State, Action> {
    for (step in SessionFeature.coreSteps) {
        step(this, state, action)?.let { return it }
    }
    return Reduced(state)
}

private object ShellOutputSlot
private object ProjectFilesSlot
private object FolderBrowserSlot
private object VoiceSlot

/** Double optional for the call's viewing page. */
private data class ViewingBox(val page: VoiceViewingPage?)

/**
 * The session's reducers composed once per feature; the class keeps the result as its lazy
 * `builtBody`.
 */
internal fun SessionFeature.composedBody(): Reducer<State, Action> {
    val coreReducers: Reducer<State, Action> = run {
        Reducer<State, Action> { state, action -> reduceCore(state, action) }
            .onChange({ state -> if (state.offersManualMoveToBackground) state.foregroundWork else null }) { _, _, state ->
                scheduleBackgroundHint(state)
            }
            // One place cancels superseded run-state work: Stop's retries and reads, the read
            // after a run's end, and Send Now's wake.
            .onChange({ it.runWorkGeneration }) { old, _, state ->
                Reduced(
                    state,
                    Effect.cancel(
                        listOf(CancelID.Interrupt(old), CancelID.InterruptSettle(old), CancelID.RunEndCheck(old), CancelID.StrandedSteer(old)),
                    ),
                )
            }
            // In the action that starts or ends a run, so the transcript's first frame after it
            // already has Starting Up or the finale.
            .onChange({ it.workingRunSignal }) { old, new, state ->
                val finale = state.workingRun.finale
                val updated = state.updateWorkingRun(old)
                val next = updated.workingRun.finale
                val event: String? = if (new.isRunning != old.isRunning) {
                    when {
                        new.isRunning -> "start"
                        next != null && next != finale -> if (next.kind == WorkingRun.Finale.Kind.pending) "pending" else "finale"
                        else -> "end"
                    }
                } else if (next != null && next != finale && finale?.kind == WorkingRun.Finale.Kind.pending) {
                    "finale"
                } else {
                    null
                }
                if (event != null) LabMarks.mark("working_run:$event", sessionID = new.sessionID)
                Reduced(updated)
            }
    }

    val streamChain: Reducer<State, Action> = run {
        coreReducers
            // A queued send's message started with its pick still waiting.
            .onChange({ it.queuedPickDue }) { _, _, state -> applyDueModelPick(state) }
            .onChange({ it.queuedAgentPickDue }) { _, _, state -> applyDueAgentPick(state) }
            .onChange({ it.timedReadCadence }) { old, new, state ->
                val polls = if (old.pendingWork != new.pendingWork) retunePendingWork(state) else Reduced(state)
                val watched = if (old.watchdog != new.watchdog && polls.state.watchdogSchedule != null) watchdog(polls.state) else Reduced(polls.state)
                Reduced(watched.state, Effect.merge(polls.effect, watched.effect))
            }
    }

    val coreChain: Reducer<State, Action> = run {
        var reducer: Reducer<State, Action> = streamChain
        reducer = ifLet(
            id = ShellOutputSlot,
            parent = reducer,
            state = { it.shellOutput },
            setState = { s, c -> s.copy(shellOutput = c) },
            action = { (it as? Action.ShellOutput)?.action },
            embed = Action::ShellOutput,
            child = ShellOutputFeature(),
        )
        reducer = ifLet(
            id = ProjectFilesSlot,
            parent = reducer.onChange({ it.streamIsHealthy }) { _, healthy, state ->
                if (healthy && state.projectFiles != null) Reduced(state, Effect.send(Action.ProjectFiles(PresentationAction.Presented(ProjectFilesFeature.Action.Refresh))))
                else Reduced(state)
            }.onChange({ it.server.persistenceID to it.effectiveDirectory }) { _, _, state ->
                Reduced(state.copy(projectFiles = null))
            },
            state = { it.projectFiles },
            setState = { s, c -> s.copy(projectFiles = c) },
            action = { (it as? Action.ProjectFiles)?.action },
            embed = Action::ProjectFiles,
            child = ProjectFilesFeature(),
            childID = { it.pageID },
        )
        // Plain optional: `sessionSheet` runs header `…` menu actions and confirmations while the
        // sheet is closed and keeps in-flight fork/export work alive across sheet dismissal.
        reducer = ifLet(
            parent = reducer,
            state = { it.sessionSheet },
            setState = { s, c -> s.copy(sessionSheet = c) },
            action = { (it as? Action.SessionSheet)?.action },
            embed = Action::SessionSheet,
            child = SessionSheetFeature(),
        )
        reducer = ifLet(
            id = FolderBrowserSlot,
            parent = reducer,
            state = { it.folderBrowser },
            setState = { s, c -> s.copy(folderBrowser = c) },
            action = { (it as? Action.FolderBrowser)?.action },
            embed = Action::FolderBrowser,
            child = FolderBrowserFeature(),
        )
        ifLet(
            id = VoiceSlot,
            parent = reducer,
            state = { it.voice },
            setState = { s, c -> s.copy(voice = c) },
            action = { (it as? Action.Voice)?.action },
            embed = Action::Voice,
            child = VoiceFeature(),
        )
    }

    val sessionReducers: Reducer<State, Action> = run {
        val core = coreChain
        combine(
            bindingReducer,
            scope(
                state = State::browser,
                setState = { s, c -> s.copy(browser = c) },
                action = { (it as? Action.Browser)?.action },
                embed = Action::Browser,
                child = BrowserFeature(),
            ),
            scope(
                state = State::dictation,
                setState = { s, c -> s.copy(dictation = c) },
                action = { (it as? Action.Dictation)?.action },
                embed = Action::Dictation,
                child = DictationFeature,
            ),
            scope(
                state = State::requestDock,
                setState = { s, c -> s.copy(requestDock = c) },
                action = { (it as? Action.RequestDock)?.action },
                embed = Action::RequestDock,
                child = RequestDockFeature<DraftState>(),
            ),
            scope(
                state = State::composerAttachments,
                setState = { s, c -> s.copy(composerAttachments = c) },
                action = { (it as? Action.ComposerAttachments)?.action },
                embed = Action::ComposerAttachments,
                child = ComposerAttachmentsFeature(),
            ),
            Reducer { state, action ->
                when {
                    action is Action.ShellOutput && state.shellOutput == null -> Reduced(state)
                    action is Action.SessionSheet && state.sessionSheet == null -> Reduced(state)
                    action is Action.FolderBrowser && state.folderBrowser == null -> Reduced(state)
                    action is Action.Voice && state.voice == null -> Reduced(state)
                    else -> core.reduce(state, action)
                }
            },
        )
    }

    val fullBody: Reducer<State, Action> = run {
        sessionReducers
            // A pending request takes the composer as its answer and gives it back as it was;
            // paging or switching requests moves the bar's words.
            .onChange({ it.answerContext }) { old, _, state -> reconcileAnswering(old, state) }
            .onChange({ it.composingRetiresStarterPrompts }) { _, _, state ->
                Reduced(if (state.composingRetiresStarterPrompts) state.copy(starterPrompts = emptyList()) else state)
            }
            .onChange({ it.persistedComposerWatch }) { old, _, state -> reconcilePersistedComposer(old, state) }
            .onChange({ it.persistedSessionWatch }) { old, _, state -> reconcilePersistedSession(old, state) }
            // Each admission and answer of a voice prompt re-reads the pinned session and retunes
            // the watchdog, as a typed send and its reply do.
            .onChange({ it.voice?.admittedPromptIDs ?: emptyList() }) { _, _, state ->
                val session = state.session
                if (session == null || state.voice?.target?.sessionID != session.id) return@onChange Reduced(state)
                val watched = watchdog(state)
                Reduced(watched.state, Effect.merge(Effect.send(Action.RefreshRequested(RefreshReason.RunState)), watched.effect))
            }
            // The call mirrors whether the family is blocked on a native approval the call cannot settle.
            .onChange({ state ->
                val voice = state.voice
                if (voice == null || voice.target.sessionID != state.session?.id) null else state.hasPendingRequests
            }) { _, _, state ->
                val needsInput = state.hasPendingRequests
                Reduced(state.copy(voice = state.voice?.copy(targetNeedsInput = needsInput)))
            }
            // A compaction in the call's session drops the delegation header from its context, so
            // the next delegation explains itself again (D4a).
            .onChange({ it.voiceCompaction }) { _, _, state ->
                val mark = state.voiceCompaction ?: return@onChange Reduced(state)
                Reduced(state, Effect.send(Action.voicePresented(VoiceFeature.Action.CompactionObserved(mark))))
            }
            // The call hears which page is open over the session. Debounced, so a burst of
            // navigation sends one update; scrolling sends none.
            .onChange({ state ->
                val voice = state.voice
                if (voice == null || voice.target.sessionID != state.session?.id) null else ViewingBox(state.browser.viewing)
            }) { _, _, state ->
                // Nothing to say when the call already knows (a call starting with no page).
                val voice = state.voice ?: return@onChange Reduced(state, Effect.cancel(CancelID.VoiceViewing))
                if (voice.viewing == state.browser.viewing) return@onChange Reduced(state)
                val page = state.browser.viewing
                val clock = clock
                Reduced(
                    state,
                    Effect.run<Action> { send ->
                        clock.sleep(600.milliseconds)
                        send(Action.voicePresented(VoiceFeature.Action.ViewingChanged(page)))
                    }.cancellable(CancelID.VoiceViewing, cancelInFlight = true),
                )
            }
            // A call on a draft creates its session with the draft's model; on a session, its next
            // delegation applies a pick still waiting.
            .onChange({ it.sendModel }) { _, _, state ->
                val synced = state.syncVoiceModel()
                Reduced(synced.copy(sessionSheet = synced.sessionSheet?.copy(pendingModel = synced.pendingModel)))
            }
            // When the attached session gets a generated or edited title, tell the voice model.
            .onChange({ VoiceSessionName(it.voice?.target?.sessionID, it.voice?.sessionTitle) }) { old, _, state ->
                val sessionID = state.voice?.target?.sessionID
                val title = state.voice?.sessionTitle
                if (sessionID == null || old.sessionID != sessionID || title == null || old.title == title) {
                    Reduced(state)
                } else {
                    Reduced(state, Effect.send(Action.voicePresented(VoiceFeature.Action.SessionRenamed(title))))
                }
            }
    }

    /** Content batches and family run changes skip the full body's hooks (the stream fast path). */
    val builtBody: Reducer<State, Action> = run {
        val full = fullBody
        val stream = streamChain
        Reducer { state, action ->
            when (action) {
                is Action.ContentBatch, is Action.FamilyRunStateChanged -> stream.reduce(state, action)
                else -> full.reduce(state, action)
            }
        }
    }
    return builtBody
}

/**
 * What the composer's chrome needs from the draft ([draftShape]). It changes as the first character
 * comes or the last one goes, not on every keystroke, so a view reading it doesn't recompose as the
 * person types.
 */
data class DraftShape(
    val isEmpty: Boolean = true,
    /** Something besides whitespace: there's text to send. */
    val hasText: Boolean = false,
) {
    constructor(draft: String) : this(isEmpty = draft.isEmpty(), hasText = draft.isNotBlank())
}

/** Whether [SessionFeature.State.draft] is empty or blank. */
val SessionFeature.State.draftShape: DraftShape get() = DraftShape(draft)
