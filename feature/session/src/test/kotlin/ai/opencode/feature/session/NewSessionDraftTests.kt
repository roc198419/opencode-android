package ai.opencode.feature.session

import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.Store
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.core.ui.OpenCodeAppTheme
import ai.opencode.server.model.OpenCodeProjectFile
import ai.opencode.server.model.OpenCodeProjectFiles
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

import androidx.compose.ui.semantics.getOrNull

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.store.PresentationAction
import ai.opencode.feature.composer.PromptOutbox
import ai.opencode.feature.composer.UndeliveredPrompts
import ai.opencode.feature.session.FolderBrowserFeature.Listing
import ai.opencode.feature.session.FolderBrowserFeature.Route
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.append
import ai.opencode.feature.session.SessionTestSupport.increment
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeDirectoryListing
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * A new session's project: the recent projects above the composer, the Project sheet, and folders
 * browsed on the server.
 */
class NewSessionDraftTests {
    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
    private val projects = (1..7).map { OpenCodeProject(id = "p$it", name = "project-$it", directory = "/srv/code/project-$it") }
    private val start = Route(path = null)

    private fun Harness.draft(project: OpenCodeProject? = null, text: String = ""): SessionFeature.State = state {
        SessionFeature.State.draft(server = server, draftProject = project ?: projects[0], projects = projects).withDraft(text)
    }.also { it.agentSelection.isEnabled.set(false) }

    /** Failed at the end of the test. */
    private val issues = LockIsolated(listOf<String>())

    private fun draftTest(block: suspend CoroutineScope.(Harness) -> Unit) = sessionTest { h ->
        block(h)
        assertEquals(emptyList<String>(), issues.value)
    }

    /** Nothing in these tests may create a session or send a prompt. */
    private fun DependencyValues.Builder.noSends() {
        this[OpenCodeClient] = this[OpenCodeClient].copy(
            createSession = { _, _, _ ->
                issues.append("Choosing a project must not create a session")
                throw OpenCodeClientError(message = "Unexpected")
            },
            sendPrompt = { _, _, _, _, _, _, _ -> issues.append("Choosing a project must not send") },
        )
    }

    private fun listing(directory: String, folders: List<String>) = OpenCodeDirectoryListing(directory = directory, folders = folders)

    private fun folderAction(action: FolderBrowserFeature.Action): Action = Action.FolderBrowser(PresentationAction.Presented(action))

    private fun SessionFeature.State.updateFolderBrowser(transform: (FolderBrowserFeature.State) -> FolderBrowserFeature.State) =
        copy(folderBrowser = folderBrowser?.let(transform))

    private val listed: (Action) -> Boolean = {
        it is Action.FolderBrowser && (it.action as? PresentationAction.Presented)?.action is FolderBrowserFeature.Action.Listed
    }

    // region Recent projects

    @Test fun visibleProjectSlotsSurviveRecencyRefreshesAndRepeatedPicks() = draftTest { h ->
        var order = DraftProjectOrder()
        val identity = h.draft().taskIdentity
        order = order.update(projects.take(5), chosen = projects[0], identity = identity)
        val original = order.projects.map { it.directory }
        order = order.update(projects.take(5).reversed(), chosen = projects[3], identity = identity)
        assertEquals(original, order.projects.map { it.directory })

        // A folder outside the five replaces only the last slot, rather than
        // pushing four visible rows down. Selecting a retained row keeps it there.
        order = order.update(listOf(projects[6]) + projects.take(4), chosen = projects[6], identity = identity)
        assertEquals(listOf("p1", "p2", "p3", "p4", "p7"), order.projects.map { it.id })
        order = order.update(projects.take(5), chosen = projects[1], identity = identity)
        assertEquals(listOf("p1", "p2", "p3", "p4", "p7"), order.projects.map { it.id })
        order = order.update(projects.take(5), chosen = projects[4], identity = identity)
        assertEquals(listOf("p1", "p2", "p3", "p4", "p5"), order.projects.map { it.id })
    }

    @Test fun visibleProjectSlotsUseDirectoryIdentityAndFillAnInitiallyEmptyDraft() = draftTest { h ->
        var order = DraftProjectOrder()
        val identity = h.draft().taskIdentity
        order = order.update(emptyList(), chosen = null, identity = identity)
        order = order.update(emptyList(), chosen = projects[0], identity = identity)
        order = order.update(projects.take(5), chosen = projects[0], identity = identity)
        val renamed = OpenCodeProject(id = "new-id", name = "Renamed", directory = projects[2].directory)
        order = order.update(listOf(renamed) + projects, chosen = renamed, identity = identity)
        assertEquals(5, order.projects.size)
        assertEquals(renamed, order.projects[2])
    }

    /**
     * The Project sheet lists a folder once even when the server has it
     * under two projects.
     */
    @Test fun projectSheetListsEachFolderOnce() = draftTest { h ->
        val root = OpenCodeProject(id = "global", name = "/", directory = "/")
        val rootAgain = OpenCodeProject(id = "root-git", name = "/", directory = "/")
        val copy = OpenCodeProject(id = "p1-again", name = "project-1", directory = projects[0].directory)
        var state = h.draft().copy(projects = listOf(root, projects[0], rootAgain, copy, projects[1]))
        assertEquals(listOf("global", "p1", "p2"), state.filteredProjects.map { it.id })
        state = state.copy(projectFilter = "project")
        assertEquals(listOf("p1", "p2"), state.filteredProjects.map { it.id })
    }

    @Test fun aServerChangeDropsEveryPreviousProjectSlot() = draftTest { h ->
        val first = h.draft()
        val otherServer = OpenCodeServer(url = "http://localhost:10", username = "", password = "")
        var next = h.state { SessionFeature.State.draft(server = otherServer, draftProject = null, projects = emptyList(), visit = first.visit) }
        assertEquals(first.taskIdentity.visit, next.taskIdentity.visit)
        assertNotEquals(first.taskIdentity, next.taskIdentity)
        var order = DraftProjectOrder()
        order = order.update(first.recentProjects, chosen = first.draftProject, identity = first.taskIdentity)

        // An empty snapshot must clear old folders immediately, even before loading.
        order = order.update(next.recentProjects, chosen = next.draftProject, identity = next.taskIdentity)
        assertTrue(order.projects.isEmpty())
        val folder = OpenCodeProject.folder("/other-server/work", emptyList())
        next = next.copy(projects = listOf(folder), draftProject = folder)
        order = order.update(next.recentProjects, chosen = next.draftProject, identity = next.taskIdentity)
        assertEquals(listOf(folder), order.projects)

        // Switching directly between populated drafts resets all five slots too.
        order = order.update(first.recentProjects, chosen = first.draftProject, identity = first.taskIdentity)
        assertEquals(first.recentProjects, order.projects)
        order = order.update(next.recentProjects, chosen = next.draftProject, identity = next.taskIdentity)
        assertEquals(listOf(folder), order.projects)
    }

    @Test fun theListShowsTheFiveMostRecentProjects() = draftTest { h ->
        assertEquals(listOf("p1", "p2", "p3", "p4", "p5"), h.draft().recentProjects.map { it.id })
        assertEquals("A recent choice keeps its place", listOf("p1", "p2", "p3", "p4", "p5"), h.draft(projects[3]).recentProjects.map { it.id })
    }

    @Test fun aChosenProjectOutsideTheFiveLeadsTheList() = draftTest { h ->
        assertEquals(listOf("p7", "p1", "p2", "p3", "p4"), h.draft(projects[6]).recentProjects.map { it.id })
        val folder = OpenCodeProject.folder("/srv/scratch", projects)
        assertEquals(listOf("scratch", "project-1", "project-2", "project-3", "project-4"), h.draft(folder).recentProjects.map { it.name })
    }

    @Test fun aFolderAlreadyListedByDirectoryIsntListedTwice() = draftTest { h ->
        val twin = projects.toMutableList().apply { add(2, OpenCodeProject(id = "other-id", name = "project-2 copy", directory = projects[1].directory)) }
        var state = h.draft().copy(projects = twin)
        assertEquals("One row per directory", listOf("p1", "p2", "p3", "p4", "p5"), state.recentProjects.map { it.id })
        state = state.copy(draftProject = OpenCodeProject(id = "folder:/srv/code/project-2", name = "project-2", directory = projects[1].directory))
        assertEquals(
            "A folder that is a listed project's directory keeps its place",
            listOf("p1", "p2", "p3", "p4", "p5"),
            state.recentProjects.map { it.id },
        )
    }

    @Test fun onceTheFirstSendLeavesTheProjectStays() = draftTest { h ->
        val state = h.draft(projects[0]).copy(
            outboxDraftID = "msg_first",
            items = listOf(OpenCodeTranscriptItem.UserText(id = "msg_first", text = "Fix the build")),
            activePicker = SessionFeature.State.ActivePicker.project,
            folderBrowser = FolderBrowserFeature.State(server = server),
        )
        assertFalse(state.canChooseProject)
        val store = h.store(state) { noSends() }
        // A pick that lands mid-flight closes the sheet and changes nothing.
        store.send(Action.ProjectPicked(projects[3])) { it.copy(activePicker = null) }
        assertEquals(projects[0], store.state.draftProject)
        store.send(Action.ProjectButtonTapped)
        assertNull(store.state.activePicker)
        store.finish()
    }

    @Test fun theSessionArrivingClosesTheProjectSheet() = draftTest { h ->
        val state = h.draft(projects[0]).openProjectPicker()
        val initialIdentity = state.taskIdentity
        val created = OpenCodeSession(id = "ses_new", serverTitle = "New session", projectID = "p1", directory = projects[0].directory, updatedAt = SessionTestSupport.distantPast)
        val store = h.store(state) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, _ -> created },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false
        store.send(Action.SessionCreated(created))
        assertEquals(
            "A draft becoming its created session keeps its taskIdentity so .task does not re-run",
            initialIdentity,
            store.state.taskIdentity,
        )
        assertNull(store.state.activePicker)
        store.send(Action.ProjectPickerDismissed)
        assertNull(store.state.folderBrowser)
        assertFalse(store.state.canChooseProject)
        h.skipInFlightEffects()
    }

    @Test fun pickingARecentProjectChangesOnlyTheDraftsProject() = draftTest { h ->
        val store = h.store(h.draft(text = "Keep this")) { noSends() }
        store.send(Action.DraftProjectTapped(projects[2])) { it.copy(draftProject = projects[2]) }
        store.receive(Action.AgentsRequested)
        store.receive({ it is Action.AgentsResponse }, "agentsResponse")
        assertEquals("Keep this", store.state.draft)
        assertNull(store.state.session)
        assertTrue(store.state.items.isEmpty())
        store.finish()
    }

    // endregion

    // region The Project sheet

    @Test fun cancellingTheProjectSheetKeepsTheDraft() = draftTest { h ->
        val image = OpenCodeOutgoingAttachment(data = byteArrayOf(1), mime = "image/png")
        val state = h.draft(projects[1], text = "Half a thought").withPendingAttachments(listOf(image))
        val store = h.store(state) { noSends() }
        store.send(Action.ProjectButtonTapped) {
            it.copy(activePicker = SessionFeature.State.ActivePicker.project, folderBrowser = FolderBrowserFeature.State(server = server))
        }
        // A folder pushed, then the sheet swiped away.
        store.send(folderAction(FolderBrowserFeature.Action.RoutesChanged(listOf(start)))) {
            it.updateFolderBrowser { browser -> browser.copy(routes = listOf(start)) }
        }
        // The stack stays as it was while the sheet slides away, then goes.
        store.send(Action.Binding(BindingAction.ActivePicker(null))) { it.copy(activePicker = null) }
        store.send(Action.ProjectPickerDismissed) { it.copy(folderBrowser = null) }
        assertEquals("Half a thought", store.state.draft)
        assertEquals(listOf(image), store.state.pendingAttachments)
        assertEquals(projects[1], store.state.draftProject)
        store.finish()
    }

    @Test fun reopeningTheSheetStartsAtTheTop() = draftTest { h ->
        val state = h.draft().copy(
            projectFilter = "har",
            activePicker = SessionFeature.State.ActivePicker.project,
            folderBrowser = FolderBrowserFeature.State(server = server, routes = listOf(start, Route(path = "/srv"))),
        )
        val store = h.store(state)
        store.send(Action.Binding(BindingAction.ActivePicker(null))) { it.copy(activePicker = null) }
        store.send(Action.ProjectPickerDismissed) { it.copy(folderBrowser = null) }
        store.send(Action.ProjectButtonTapped) {
            it.copy(
                activePicker = SessionFeature.State.ActivePicker.project,
                projectFilter = "",
                folderBrowser = FolderBrowserFeature.State(server = server),
            )
        }
        store.finish()
    }

    // endregion

    // region Folders on the server

    @Test fun browsingReadsOnlyTheFoldersOpened() = draftTest { h ->
        val reads = LockIsolated(listOf<String?>())
        val state = h.draft().openProjectPicker()
        val store = h.store(state) {
            noSends()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                listDirectory = { _, path ->
                    reads.append(path)
                    if (path == null) {
                        OpenCodeDirectoryListing(directory = "/srv", folders = listOf("/srv/code", "/srv/notes"))
                    } else {
                        OpenCodeDirectoryListing(directory = path, folders = listOf("/srv/code/lantern"))
                    }
                },
            )
        }
        // Opening the sheet reads nothing.
        assertTrue(reads.value.isEmpty())
        store.send(folderAction(FolderBrowserFeature.Action.FolderAppeared(start))) {
            it.updateFolderBrowser { b -> b.copy(listings = b.listings + (start to Listing.Loading)) }
        }
        store.receive(listed, "listed") {
            it.updateFolderBrowser { b -> b.copy(listings = b.listings + (start to Listing.Loaded(listing("/srv", listOf("/srv/code", "/srv/notes"))))) }
        }
        val code = Route(path = "/srv/code")
        store.send(folderAction(FolderBrowserFeature.Action.FolderAppeared(code))) {
            it.updateFolderBrowser { b -> b.copy(listings = b.listings + (code to Listing.Loading)) }
        }
        store.receive(listed, "listed") {
            it.updateFolderBrowser { b -> b.copy(listings = b.listings + (code to Listing.Loaded(listing("/srv/code", listOf("/srv/code/lantern"))))) }
        }
        // Coming back up shows what it showed.
        store.send(folderAction(FolderBrowserFeature.Action.FolderAppeared(start)))
        assertEquals(listOf(null, "/srv/code"), reads.value)
        store.finish()
    }

    @Test fun aFolderThatFailsToListOffersTryAgain() = draftTest { h ->
        val attempts = LockIsolated(0)
        val state = h.draft().openProjectPicker()
        val store = h.store(state) {
            noSends()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                listDirectory = { _, _ ->
                    if (attempts.increment() == 1) throw OpenCodeClientError(message = "The server couldn’t list this folder", statusCode = 500)
                    OpenCodeDirectoryListing(directory = "/srv", folders = emptyList())
                },
            )
        }
        store.send(folderAction(FolderBrowserFeature.Action.FolderAppeared(start))) {
            it.updateFolderBrowser { b -> b.copy(listings = b.listings + (start to Listing.Loading)) }
        }
        store.receive(listed, "listed") {
            it.updateFolderBrowser { b -> b.copy(listings = b.listings + (start to Listing.Failed("The server couldn’t list this folder"))) }
        }
        // An unlisted folder can't be chosen, and appearing again doesn't retry on its own.
        store.send(folderAction(FolderBrowserFeature.Action.SelectTapped(start)))
        store.send(folderAction(FolderBrowserFeature.Action.FolderAppeared(start)))
        assertEquals(1, attempts.value)
        store.send(folderAction(FolderBrowserFeature.Action.RetryTapped(start))) {
            it.updateFolderBrowser { b -> b.copy(listings = b.listings + (start to Listing.Loading)) }
        }
        store.receive(listed, "listed") {
            it.updateFolderBrowser { b -> b.copy(listings = b.listings + (start to Listing.Loaded(listing("/srv", emptyList())))) }
        }
        assertEquals("A failure leaves the draft's project alone", projects[0], store.state.draftProject)
        store.finish()
    }

    @Test fun choosingAFolderMakesItTheDraftsProjectWithoutCreatingASession() = draftTest { h ->
        val lantern = Route(path = "/srv/code/lantern")
        val state = h.draft(text = "Fix the build").openProjectPicker().updateFolderBrowser {
            it.copy(routes = listOf(start, lantern), listings = it.listings + (lantern to Listing.Loaded(listing("/srv/code/lantern", emptyList()))))
        }
        val store = h.store(state) { noSends() }
        val folder = OpenCodeProject(id = "folder:/srv/code/lantern", name = "lantern", directory = "/srv/code/lantern")
        store.send(folderAction(FolderBrowserFeature.Action.SelectTapped(lantern)))
        store.receive(
            {
                it is Action.FolderBrowser &&
                    (it.action as? PresentationAction.Presented)?.action is FolderBrowserFeature.Action.Delegate
            },
            "folderBrowser.delegate.selected",
        )
        store.receive(Action.ProjectPicked::class) {
            it.copy(draftProject = folder, activePicker = null)
        }
        store.receive(Action.AgentsRequested)
        store.receive({ it is Action.AgentsResponse }, "agentsResponse")
        store.send(Action.ProjectPickerDismissed) { it.copy(folderBrowser = null) }
        assertNull(store.state.session)
        assertEquals("Fix the build", store.state.draft)
        assertEquals(folder, store.state.recentProjects.first())
        store.finish()
    }

    @Test fun theTitleMenuClimbsToAnyFolderAbove() = draftTest { h ->
        val code = Route(path = "/srv/code")
        val state = FolderBrowserFeature.State(server = server).copy(
            routes = listOf(start, code, Route(path = "/srv/code/lantern")),
            listings = mapOf(start to Listing.Loaded(listing("/srv", listOf("/srv/code")))),
        )
        val store = h.store(state, { FolderBrowserFeature() }) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                listDirectory = { _, path -> OpenCodeDirectoryListing(directory = path ?: "/", folders = listOf("/srv")) },
            )
        }
        // A folder already on the stack pops back to it.
        store.send(FolderBrowserFeature.Action.AncestorTapped("/srv/code")) { it.copy(routes = listOf(start, code)) }
        store.send(FolderBrowserFeature.Action.AncestorTapped("/srv")) { it.copy(routes = listOf(start)) }
        // One above where browsing began starts again from there, and loads it.
        val root = Route(path = "/")
        store.send(FolderBrowserFeature.Action.AncestorTapped("/")) {
            it.copy(routes = listOf(root), listings = it.listings + (root to Listing.Loading))
        }
        store.receive(FolderBrowserFeature.Action.Listed::class) {
            it.copy(listings = it.listings + (root to Listing.Loaded(listing("/", listOf("/srv")))))
        }
        assertEquals(listOf("/", "/Users", "/Users/sam"), FolderBrowserFeature.ancestors("/Users/sam/code"))
        assertTrue(FolderBrowserFeature.ancestors("/").isEmpty())
        store.finish()
    }

    // endregion

    // region First send

    private fun DependencyValues.Builder.sendWorld(createSession: suspend (directory: String, id: String?) -> OpenCodeSession, getSession: suspend (String) -> OpenCodeSession) {
        this[UuidClient] = UuidClient.incrementing()
        this[Clock] = ImmediateClock()
        this[UndeliveredPrompts] = UndeliveredPrompts()
        this[PromptOutbox] = PromptOutbox()
        this[OpenCodeClient] = this[OpenCodeClient].copy(
            createSession = { _, directory, id -> createSession(directory, id) },
            getSession = { _, id -> getSession(id) },
            sendPrompt = { _, _, _, _, _, _, _ -> },
            message = { _, _, _ -> throw OpenCodeClientError(message = "Missing", statusCode = 404) },
            pendingInputs = { _, _ -> emptyList() },
            familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
            waitForIdle = { _, _ -> awaitCancellation() },
        )
    }

    @Test fun theFirstSendCreatesTheSessionInTheChosenFolder() = draftTest { h ->
        val created = LockIsolated(listOf<String>())
        val state = h.draft(text = "Find the flaky test").copy(draftProject = OpenCodeProject.folder("/srv/code/lantern", projects))
        val store = h.store(state) {
            sendWorld(
                createSession = { directory, id ->
                    created.append(directory)
                    OpenCodeSession(id = id ?: "ses_new", serverTitle = "New session", projectID = "global", directory = directory, updatedAt = SessionTestSupport.distantPast)
                },
                getSession = { id ->
                    OpenCodeSession(id = id, serverTitle = "New session", projectID = "global", directory = "/srv/code/lantern", updatedAt = SessionTestSupport.distantPast)
                },
            )
        }
        store.exhaustive = false
        store.send(Action.SendTapped)
        assertFalse("The send has left: the list and chip go, and picks wait", store.state.canChooseProject)
        store.receive(Action.SessionCreated::class, timeout = 2.seconds)
        assertEquals("One session, in the folder", listOf("/srv/code/lantern"), created.value)
        assertEquals("/srv/code/lantern", store.state.session?.directory)
        h.skipInFlightEffects()
    }

    /**
     * A send made while a call's session is still being created (the call
     * ended quickly) creates that same session, never a second one.
     */
    @Test fun aSendWhileTheCallsSessionIsBeingMadeCreatesThatOne() = draftTest { h ->
        val ids = LockIsolated(listOf<String?>())
        val state = h.draft(text = "Find the flaky test").copy(callSessionID = "ses_fromthecall")
        val store = h.store(state) {
            sendWorld(
                createSession = { directory, id ->
                    ids.append(id)
                    OpenCodeSession(id = id ?: "ses_new", serverTitle = "New session", projectID = "p1", directory = directory, updatedAt = SessionTestSupport.distantPast)
                },
                getSession = { id ->
                    OpenCodeSession(id = id, serverTitle = "New session", projectID = "p1", directory = "/srv/code/project-1", updatedAt = SessionTestSupport.distantPast)
                },
            )
        }
        store.exhaustive = false
        store.send(Action.SendTapped)
        store.receive(Action.SessionCreated::class, timeout = 2.seconds)
        assertEquals(listOf<String?>("ses_fromthecall"), ids.value)
        assertEquals("ses_fromthecall", store.state.session?.id)
        h.skipInFlightEffects()
    }

    /**
     * Deleting a draft's only send, which failed, makes it unsent again: its
     * project can change.
     */
    @Test fun deletingTheOnlyFailedSendFreesTheProject() = draftTest { h ->
        val store = h.store(h.draft(text = "Find the flaky test")) {
            this[UuidClient] = UuidClient.incrementing()
            this[Clock] = ImmediateClock()
            this[UndeliveredPrompts] = UndeliveredPrompts()
            this[PromptOutbox] = PromptOutbox()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                createSession = { _, _, _ -> throw OpenCodeClientError(message = "No such folder", statusCode = 400) },
            )
        }
        store.exhaustive = false
        store.send(Action.SendTapped)
        store.receive(Action.PromptFailed::class, timeout = 2.seconds)
        assertFalse(store.state.canChooseProject)
        val id = store.state.failedSends.firstOrNull()?.id
        store.send(Action.FailedSendDeleteTapped(id ?: ""))
        assertTrue(store.state.canChooseProject)
        store.send(Action.ProjectPicked(projects[2]))
        assertEquals(projects[2], store.state.draftProject)
    }

    @Test fun aDraftWithoutAProjectOpensTheSheetInsteadOfSending() = draftTest { h ->
        val state = h.state { SessionFeature.State.draft(server = server, draftProject = null, projects = emptyList()).withDraft("Keep this") }
        val store = h.store(state) { noSends() }
        store.send(Action.SendTapped) {
            it.copy(activePicker = SessionFeature.State.ActivePicker.project, folderBrowser = FolderBrowserFeature.State(server = server))
        }
        assertEquals("Keep this", store.state.draft)
        assertTrue(store.state.recentProjects.isEmpty())
        store.finish()
    }

    // endregion
}


class ProjectFilesBrowsingTests {
    @get:Rule val isolation = TestIsolationRule()
    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
    private fun initial() = ProjectFilesFeature.State(server, "/project", -1)

    @Test fun browsingLoadsOnDemandAndBackUsesTheCachedDirectory() = sessionTest { h ->
        val root = OpenCodeProjectFiles("/project", listOf(OpenCodeProjectFile("/project/src", true)))
        val child = OpenCodeProjectFiles("/project/src", emptyList())
        val reads = LockIsolated(listOf<String>())
        val store = h.store(initial(), { ProjectFilesFeature() }) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(listProjectFiles = { _, _, path ->
                reads.append(path)
                if (path == root.directory) root else child
            })
        }
        store.send(ProjectFilesFeature.Action.Appeared) { it.copy(loading = true, generation = 1) }
        store.receive(ProjectFilesFeature.Action.Loaded(1, "/project", Result.success(root))) {
            it.copy(loading = false, listings = mapOf("/project" to root))
        }
        store.send(ProjectFilesFeature.Action.DirectoryTapped("/project/src")) { it.copy(history = listOf("/project", "/project/src"), loading = true, generation = 2, autoDescendRemaining = ProjectFilesFeature.MAX_COMPACT_DEPTH) }
        store.receive(ProjectFilesFeature.Action.Loaded(2, "/project/src", Result.success(child))) {
            it.copy(loading = false, listings = it.listings + ("/project/src" to child), autoDescendRemaining = 0)
        }
        store.send(ProjectFilesFeature.Action.Back) { it.copy(history = listOf("/project"), generation = 3) }
        store.send(ProjectFilesFeature.Action.DirectoryTapped("/elsewhere"))
        assertEquals(listOf("/project", "/project/src"), reads.value)
        store.finish()
    }

    @Test fun aFailedDirectoryCanBeRetried() = sessionTest { h ->
        var attempts = 0
        val listing = OpenCodeProjectFiles("/project", emptyList())
        val error = OpenCodeClientError("Unavailable")
        val store = h.store(initial(), { ProjectFilesFeature() }) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(listProjectFiles = { _, _, _ ->
                if (++attempts == 1) throw error
                listing
            })
        }
        store.send(ProjectFilesFeature.Action.Appeared) { it.copy(loading = true, generation = 1) }
        store.receive(ProjectFilesFeature.Action.Loaded(1, "/project", Result.failure(error))) { it.copy(loading = false, error = "Unavailable") }
        store.send(ProjectFilesFeature.Action.Refresh) { it.copy(loading = true, error = null, generation = 2) }
        store.receive(ProjectFilesFeature.Action.Loaded(2, "/project", Result.success(listing))) { it.copy(loading = false, listings = mapOf("/project" to listing)) }
        store.finish()
    }

    @Test fun searchingUsesTheProjectRootAndRejectsPathsOutsideIt() = sessionTest { h ->
        val found = listOf(OpenCodeProjectFile("/project/src/file.kt", false))
        val store = h.store(initial(), { ProjectFilesFeature() }) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(findFiles = { _, root, query, limit ->
                assertEquals("/project", root)
                assertEquals("file", query)
                assertEquals(200, limit)
                listOf(
                    ai.opencode.server.model.OpenCodeAutocompleteFile("src/file.kt", "/project/src/file.kt", root),
                    ai.opencode.server.model.OpenCodeAutocompleteFile("../secret", "/secret", root),
                )
            })
        }
        store.send(ProjectFilesFeature.Action.QueryChanged("file")) { it.copy(query = "file", generation = 1, loading = true) }
        store.receive(ProjectFilesFeature.Action.Searched(1, Result.success(found))) { it.copy(loading = false, searchResults = found) }
        store.finish()
    }

    @Test fun staleSearchAndDirectoryResponsesDoNotReplaceCurrentResults() {
        val reducer = ProjectFilesFeature()
        val state = initial().copy(query = "new", generation = 4, loading = true)
        assertEquals(state, reducer.reduce(state, ProjectFilesFeature.Action.Searched(3, Result.success(emptyList()))).state)
        assertEquals(state, reducer.reduce(state, ProjectFilesFeature.Action.Loaded(4, "/project", Result.success(OpenCodeProjectFiles("/project", emptyList())))).state)
        val normal = initial().copy(generation = 6)
        assertEquals(normal, reducer.reduce(normal, ProjectFilesFeature.Action.Searched(6, Result.success(emptyList()))).state)
    }

    @Test fun searchFiltersHiddenAncestorsUntilTheyAreEnabled() {
        val hidden = OpenCodeProjectFile("/project/.config/file", false)
        val visible = OpenCodeProjectFile("/project/src/file", false)
        val state = initial().copy(query = "file", searchResults = listOf(hidden, visible))
        assertEquals(listOf(visible), state.entries)
        assertEquals(listOf(hidden, visible), ProjectFilesFeature().reduce(state, ProjectFilesFeature.Action.ToggleHidden).state.entries)
    }

    @Test fun closingTheBrowserCancelsItsInFlightDirectoryRead() = sessionTest { h ->
        val state = h.state { SessionFeature.State.session(server, OpenCodeSession(id = "ses_files", serverTitle = "Files", projectID = "p", directory = "/project", updatedAt = java.time.Instant.EPOCH)) }
            .copy(projectFiles = initial())
        val store = h.store(state) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(listProjectFiles = { _, _, _ -> awaitCancellation() })
        }
        store.send(Action.ProjectFiles(PresentationAction.Presented(ProjectFilesFeature.Action.Appeared))) {
            it.copy(projectFiles = it.projectFiles!!.copy(loading = true, generation = 1))
        }
        store.send(Action.ProjectFiles(PresentationAction.Dismiss)) { it.copy(projectFiles = null) }
        store.finish()
    }
    private fun chain(): List<OpenCodeProjectFiles> {
        val folders = listOf("src", "main", "java", "com", "test", "company")
            .runningFold("/project") { path, part -> "$path/$part" }
        return folders.mapIndexed { index, path ->
            OpenCodeProjectFiles(path, if (index < folders.lastIndex) listOf(OpenCodeProjectFile(folders[index + 1], true))
                else listOf(OpenCodeProjectFile("$path/Company.java", false)))
        }
    }

    @Test fun oneTapTraversesASingleDirectoryChainAndCachedRowsStayCompact() = sessionTest { h ->
        val chain = chain()
        val cache = chain.associateBy { it.directory }
        val reads = LockIsolated(listOf<String>())
        val store = h.store(initial().copy(listings = mapOf("/project" to chain.first())), { ProjectFilesFeature() }) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(listProjectFiles = { _, _, path -> reads.append(path); cache.getValue(path) })
        }
        store.send(ProjectFilesFeature.Action.DirectoryTapped("/project/src")) {
            it.copy(history = listOf("/project", "/project/src"), loading = true, generation = 1, autoDescendRemaining = ProjectFilesFeature.MAX_COMPACT_DEPTH)
        }
        for ((index, listing) in chain.drop(1).withIndex()) {
            store.receive(ProjectFilesFeature.Action.Loaded(index + 1, listing.directory, Result.success(listing))) {
                val last = index == chain.size - 2
                it.copy(
                    history = listOf("/project", if (last) listing.directory else chain[index + 2].directory),
                    listings = it.listings + (listing.directory to listing),
                    generation = if (last) it.generation else it.generation + 1,
                    loading = !last,
                    autoDescendRemaining = if (last) 0 else ProjectFilesFeature.MAX_COMPACT_DEPTH - index - 1,
                )
            }
        }
        assertEquals(chain.map { it.directory }, store.state.breadcrumbs)
        assertEquals(listOf("/project", chain.last().directory), store.state.history)
        store.send(ProjectFilesFeature.Action.Back) { it.copy(history = listOf("/project"), generation = it.generation + 1) }
        assertEquals("src/main/java/com/test/company", store.state.entryName(chain.first().entries.single()))
        store.send(ProjectFilesFeature.Action.DirectoryTapped("/project/src")) {
            it.copy(history = listOf("/project", chain.last().directory), generation = it.generation + 1)
        }
        assertEquals(chain.drop(1).map { it.directory }, reads.value)
        store.finish()
    }

    @Test fun breadcrumbsCanOpenAnIntermediateDirectoryWithoutDescendingAgain() = sessionTest { h ->
        val chain = chain()
        val leaf = chain.last().directory
        val middle = "/project/src/main/java"
        val store = h.store(initial().copy(history = listOf("/project", leaf), listings = chain.associateBy { it.directory }), { ProjectFilesFeature() })
        store.send(ProjectFilesFeature.Action.BreadcrumbTapped(middle)) {
            it.copy(history = listOf("/project", leaf, middle), generation = 1)
        }
        assertEquals(middle, store.state.path)
        assertEquals(listOf("/project", "/project/src", "/project/src/main", middle), store.state.breadcrumbs)
        store.send(ProjectFilesFeature.Action.Back) { it.copy(history = listOf("/project", leaf), generation = 2) }
        store.send(ProjectFilesFeature.Action.BreadcrumbTapped("/project")) { it.copy(history = listOf("/project", leaf, "/project"), generation = 3) }
        store.send(ProjectFilesFeature.Action.Back) { it.copy(history = listOf("/project", leaf), generation = 4) }
        store.send(ProjectFilesFeature.Action.BreadcrumbTapped("/elsewhere"))
        store.send(ProjectFilesFeature.Action.BreadcrumbTapped("/project/other"))
        store.finish()
    }

    @Test fun filesHiddenFilesBranchesAndEmptyFoldersStopCompaction() = sessionTest { h ->
        val folder = "/project/src"
        val child = OpenCodeProjectFile("$folder/main", true)
        for (entries in listOf(
            emptyList(), listOf(OpenCodeProjectFile("$folder/File.java", false)),
            listOf(child, OpenCodeProjectFile("$folder/File.java", false)),
            listOf(child, OpenCodeProjectFile("$folder/.keep", false)),
            listOf(child, OpenCodeProjectFile("$folder/test", true)),
            listOf(OpenCodeProjectFile("$folder/.hidden", true)),
        )) {
            val store = h.store(initial().copy(listings = mapOf(folder to OpenCodeProjectFiles(folder, entries))), { ProjectFilesFeature() })
            store.send(ProjectFilesFeature.Action.DirectoryTapped(folder)) { it.copy(history = listOf("/project", folder), generation = 1) }
            assertEquals("src", store.state.entryName(OpenCodeProjectFile(folder, true)))
            store.finish()
        }
    }

    @Test fun hiddenDirectoryChainsCompactOnlyWhileHiddenFilesAreShown() = sessionTest { h ->
        val folder = "/project/src"
        val hidden = "$folder/.hidden"
        val state = initial().copy(showHidden = true, listings = mapOf(
            folder to OpenCodeProjectFiles(folder, listOf(OpenCodeProjectFile(hidden, true))),
            hidden to OpenCodeProjectFiles(hidden, emptyList()),
        ))
        assertEquals("src/.hidden", state.entryName(OpenCodeProjectFile(folder, true)))
        val store = h.store(state, { ProjectFilesFeature() })
        store.send(ProjectFilesFeature.Action.DirectoryTapped(folder)) { it.copy(history = listOf("/project", hidden), generation = 1) }
        store.finish()
    }

    @Test fun automaticDescentStopsAtItsDepthBudget() = sessionTest { h ->
        val limit = ProjectFilesFeature.MAX_COMPACT_DEPTH
        val paths = (1..limit).map { count -> "/project/" + List(count) { "next" }.joinToString("/") }
        val reads = LockIsolated(listOf<String>())
        val store = h.store(initial(), { ProjectFilesFeature() }) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(listProjectFiles = { _, _, path ->
                reads.append(path)
                OpenCodeProjectFiles(path, listOf(OpenCodeProjectFile("$path/next", true)))
            })
        }
        store.send(ProjectFilesFeature.Action.DirectoryTapped(paths.first())) {
            it.copy(history = listOf("/project", paths.first()), loading = true, generation = 1, autoDescendRemaining = limit)
        }
        for ((index, path) in paths.withIndex()) {
            val listing = OpenCodeProjectFiles(path, listOf(OpenCodeProjectFile("$path/next", true)))
            store.receive(ProjectFilesFeature.Action.Loaded(index + 1, path, Result.success(listing))) {
                val last = index == paths.lastIndex
                it.copy(history = listOf("/project", if (last) path else paths[index + 1]),
                    listings = it.listings + (path to listing), loading = !last,
                    generation = if (last) it.generation else it.generation + 1,
                    autoDescendRemaining = if (last) 0 else limit - index - 1)
            }
        }
        assertEquals(paths, reads.value)
        assertEquals(List(limit) { "next" }.joinToString("/"), store.state.entryName(OpenCodeProjectFile(paths.first(), true)))
        store.finish()
    }

    @Test fun returningDuringDescentCancelsTheNextReadAndIgnoresLateResults() = sessionTest { h ->
        val chain = chain()
        val root = chain[0]
        val first = chain[1]
        val next = chain[2].directory
        val cancelled = LockIsolated(false)
        val store = h.store(initial().copy(listings = mapOf(root.directory to root)), { ProjectFilesFeature() }) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(listProjectFiles = { _, _, path ->
                if (path == first.directory) first else try { awaitCancellation() } finally { cancelled.setValue(true) }
            })
        }
        store.send(ProjectFilesFeature.Action.DirectoryTapped(first.directory)) {
            it.copy(history = listOf(root.directory, first.directory), loading = true, generation = 1, autoDescendRemaining = ProjectFilesFeature.MAX_COMPACT_DEPTH)
        }
        store.receive(ProjectFilesFeature.Action.Loaded(1, first.directory, Result.success(first))) {
            it.copy(history = listOf(root.directory, next), listings = it.listings + (first.directory to first), generation = 2, autoDescendRemaining = ProjectFilesFeature.MAX_COMPACT_DEPTH - 1)
        }
        store.send(ProjectFilesFeature.Action.Back) { it.copy(history = listOf(root.directory), generation = 3, loading = false, autoDescendRemaining = 0) }
        store.send(ProjectFilesFeature.Action.Loaded(2, next, Result.success(chain[2])))
        assertTrue(cancelled.value)
        store.finish()
    }

    @Test fun hidingFilesDuringDescentDoesNotContinueInsideAHiddenAncestor() = sessionTest { h ->
        val folder = "/project/.cache"
        val listing = OpenCodeProjectFiles(folder, listOf(OpenCodeProjectFile("$folder/src", true)))
        val store = h.store(initial().copy(history = listOf("/project", folder), showHidden = true, loading = true,
            generation = 1, autoDescendRemaining = ProjectFilesFeature.MAX_COMPACT_DEPTH), { ProjectFilesFeature() })
        store.send(ProjectFilesFeature.Action.ToggleHidden) { it.copy(showHidden = false) }
        store.send(ProjectFilesFeature.Action.Loaded(1, folder, Result.success(listing))) {
            it.copy(loading = false, listings = mapOf(folder to listing), autoDescendRemaining = 0)
        }
        store.finish()
    }

    @Test fun aFailedChildStopsDescentAndRefreshRetriesThatLocation() = sessionTest { h ->
        val chain = chain()
        val first = chain[1]
        val child = chain[2]
        val failure = OpenCodeClientError("Unavailable")
        var attempts = 0
        val store = h.store(initial().copy(listings = mapOf(first.directory to first)), { ProjectFilesFeature() }) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(listProjectFiles = { _, _, path ->
                assertEquals(child.directory, path)
                if (++attempts == 1) throw failure else child
            })
        }
        store.send(ProjectFilesFeature.Action.DirectoryTapped(first.directory)) {
            it.copy(history = listOf("/project", child.directory), loading = true, generation = 1, autoDescendRemaining = ProjectFilesFeature.MAX_COMPACT_DEPTH - 1)
        }
        store.receive(ProjectFilesFeature.Action.Loaded(1, child.directory, Result.failure(failure))) {
            it.copy(loading = false, error = "Unavailable", autoDescendRemaining = 0)
        }
        store.send(ProjectFilesFeature.Action.Refresh) { it.copy(listings = emptyMap(), loading = true, error = null, generation = 2) }
        store.receive(ProjectFilesFeature.Action.Loaded(2, child.directory, Result.success(child))) {
            it.copy(listings = mapOf(child.directory to child), loading = false)
        }
        store.finish()
    }

    @Test fun selectingTheCurrentBreadcrumbPinsTheDirectoryBeingLoaded() = sessionTest { h ->
        val chain = chain()
        val folder = chain[1].directory
        val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
        val store = h.store(initial(), { ProjectFilesFeature() }) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(listProjectFiles = { _, _, _ -> ready.await(); chain[1] })
        }
        store.send(ProjectFilesFeature.Action.DirectoryTapped(folder)) {
            it.copy(history = listOf("/project", folder), loading = true, generation = 1, autoDescendRemaining = ProjectFilesFeature.MAX_COMPACT_DEPTH)
        }
        store.send(ProjectFilesFeature.Action.BreadcrumbTapped(folder)) { it.copy(autoDescendRemaining = 0) }
        ready.complete(Unit)
        store.receive(ProjectFilesFeature.Action.Loaded(1, folder, Result.success(chain[1]))) {
            it.copy(loading = false, listings = mapOf(folder to chain[1]))
        }
        store.finish()
    }

    @Test fun malformedChildrenCannotEscapeTheRootOrCreateACycle() = sessionTest { h ->
        val folder = "/project/src"
        for (path in listOf("/outside", folder, "$folder/../src", "$folder/deep/child")) {
            val state = initial().copy(listings = mapOf(folder to OpenCodeProjectFiles(folder, listOf(OpenCodeProjectFile(path, true)))))
            assertEquals("src", state.entryName(OpenCodeProjectFile(folder, true)))
            val store = h.store(state, { ProjectFilesFeature() })
            store.send(ProjectFilesFeature.Action.DirectoryTapped(folder)) { it.copy(history = listOf("/project", folder), generation = 1) }
            store.finish()
        }
        val state = ProjectFilesFeature.State(server, "C:/Project", -1, history = listOf("C:/Project", "c:/project/src/main"))
        assertEquals(listOf("C:/Project", "c:/project/src", "c:/project/src/main"), state.breadcrumbs)
    }

}


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProjectFilesViewTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test fun aLoadedProjectShowsItsFilesAndFolders() {
        val root = "/workspace/opencode"
        val server = SessionTestSupport.testServer
        val listing = OpenCodeProjectFiles(root, listOf(
            OpenCodeProjectFile("$root/docs", true),
            OpenCodeProjectFile("$root/src", true),
            OpenCodeProjectFile("$root/README.md", false),
            OpenCodeProjectFile("$root/build.gradle.kts", false),
            OpenCodeProjectFile("$root/.gitignore", false),
        ))
        val state = SessionFeature.State.session(server, SessionTestSupport.testSession(directory = root)).copy(
            projectFiles = ProjectFilesFeature.State(server, root, -1, listings = mapOf(root to listing)),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val store = Store(state, Reducer<SessionFeature.State, SessionFeature.Action> { current, _ ->
            Reduced(current)
        }, scope = scope)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val view = ComposeView(activity)
        activity.setContentView(view)
        try {
            view.setContent { OpenCodeAppTheme { ProjectFilesPage(store) } }
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
            view.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, 1080, 2400)
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
            val semantics = requireNotNull(findRoot(view)).semanticsOwner.unmergedRootSemanticsNode
            val labels = text(semantics)
            assertTrue(labels.contains("Project Files"))
            assertTrue(labels.contains("src"))
            assertTrue(labels.contains("README.md"))
            assertFalse(labels.contains(".gitignore"))
            System.getProperty("opencode.projectFilesScreenshot")?.let { path ->
                val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                val file = java.io.File(path)
                file.parentFile?.mkdirs()
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        } finally {
            view.disposeComposition()
            scope.coroutineContext[Job]?.cancel()
            controller.pause().stop().destroy()
        }
    }

    @Test fun compactRowsAndRealBreadcrumbsKeepTheirNavigationAndTouchTargets() {
        val frameDelay = org.robolectric.shadows.ShadowChoreographer.getFrameDelay()
        val paused = org.robolectric.shadows.ShadowChoreographer.isPaused()
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16))
        val root = "/workspace/sample"
        val paths = listOf("src", "main", "java", "com", "test", "company").runningFold(root) { path, part -> "$path/$part" }
        val listings = paths.mapIndexed { index, path ->
            path to OpenCodeProjectFiles(path, if (index < paths.lastIndex) listOf(OpenCodeProjectFile(paths[index + 1], true))
                else listOf(OpenCodeProjectFile("$path/Company.java", false)))
        }.toMap()
        val compact = "src/main/java/com/test/company"
        fun pump() = repeat(8) {
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
        }
        fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        try {
            for (fontScale in listOf(1f, 2f)) for (deep in listOf(false, true)) {
                val actions = mutableListOf<ProjectFilesFeature.Action>()
                val browser = ProjectFilesFeature.State(SessionTestSupport.testServer, root, -1,
                    history = if (deep) listOf(root, paths.last()) else listOf(root), listings = listings)
                val state = SessionFeature.State.session(SessionTestSupport.testServer, SessionTestSupport.testSession(directory = root)).copy(projectFiles = browser)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                val reducer: Reducer<SessionFeature.State, Action> = ai.opencode.core.store.scope(
                    state = { it.projectFiles!! }, setState = { current, files -> current.copy(projectFiles = files) },
                    action = { (it as? Action.ProjectFiles)?.action?.let { presented -> (presented as? PresentationAction.Presented)?.action } },
                    embed = { Action.ProjectFiles(PresentationAction.Presented(it)) }, child = ProjectFilesFeature(),
                )
                val store = Store(state, Reducer<SessionFeature.State, Action> { current, action ->
                    (action as? Action.ProjectFiles)?.action?.let { (it as? PresentationAction.Presented)?.action }?.let(actions::add)
                    reducer.reduce(current, action)
                }, scope = scope)
                val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                val view = ComposeView(controller.get())
                controller.get().setContentView(view)
                try {
                    view.setContent {
                        OpenCodeAppTheme {
                            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(3f, fontScale)) {
                                ProjectFilesPage(store)
                            }
                        }
                    }
                    pump()
                    view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, 1080, 2400)
                    pump()
                    val nodes = descendants(requireNotNull(findRoot(view)).semanticsOwner.unmergedRootSemanticsNode)
                    val label = if (deep) "test" else compact
                    val row = nodes.firstOrNull { it.config.getOrNull(SemanticsActions.OnClick) != null && label in text(it) }
                        ?: error("Missing $label (deep=$deep, font=$fontScale): ${nodes.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { item -> item.text } }}")
                    assertTrue("File navigation keeps a 48 dp touch target", row.touchBoundsInRoot.height >= 144f && row.touchBoundsInRoot.width >= 144f)
                    if (deep) assertTrue(nodes.any { "company" in text(it) })
                    System.getProperty("opencode.compactFilesScreenshots")?.let { directory ->
                        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                        view.draw(Canvas(bitmap))
                        val file = java.io.File(directory, "project-files-${if (deep) "breadcrumbs" else "compact"}-$fontScale.png")
                        file.parentFile?.mkdirs()
                        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                    actions.clear()
                    assertTrue(requireNotNull(row.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
                    pump()
                    view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, 1080, 2400)
                    pump()
                    assertEquals(listOf(if (deep) ProjectFilesFeature.Action.BreadcrumbTapped("$root/src/main/java/com/test")
                        else ProjectFilesFeature.Action.DirectoryTapped("$root/src")), actions)
                    assertEquals(if (deep) "$root/src/main/java/com/test" else paths.last(), store.state.projectFiles!!.path)
                } finally {
                    view.disposeComposition()
                    scope.coroutineContext[Job]?.cancel()
                    controller.pause().stop().destroy()
                    pump()
                }
            }
        } finally {
            org.robolectric.shadows.ShadowChoreographer.setFrameDelay(frameDelay)
            org.robolectric.shadows.ShadowChoreographer.setPaused(paused)
        }
    }

    private fun findRoot(view: View): RootForTest? {
        if (view is RootForTest) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findRoot(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun text(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::text)
}
