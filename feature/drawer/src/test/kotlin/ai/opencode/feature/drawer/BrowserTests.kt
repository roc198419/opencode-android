package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.composer.DictationClient
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeatureTestSupport.Harness
import ai.opencode.feature.drawer.HomeFeatureTestSupport.LockIsolated
import ai.opencode.feature.drawer.HomeFeatureTestSupport.append
import ai.opencode.feature.drawer.HomeFeatureTestSupport.client
import ai.opencode.feature.drawer.HomeFeatureTestSupport.date
import ai.opencode.feature.drawer.HomeFeatureTestSupport.detail
import ai.opencode.feature.drawer.HomeFeatureTestSupport.homeTest
import ai.opencode.feature.drawer.HomeFeatureTestSupport.receiveBounded
import ai.opencode.feature.drawer.HomeFeatureTestSupport.snapshot
import ai.opencode.feature.drawer.catchup.CatchUpFeature
import ai.opencode.feature.media.BrowserFeature
import ai.opencode.feature.media.OpenURLClient
import ai.opencode.feature.media.PageReaderClient
import ai.opencode.feature.media.UniversalLinkClient
import ai.opencode.feature.session.SessionFeature
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeSessionFamily
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class OpenLinksInAppsTests {
    @get:Rule val isolation = TestIsolationRule()

    private val link = "https://github.com/anomalyco/opencode/pull/1"

    private class HomeLinkLab(h: Harness, catchUp: Boolean = false) {
        companion object {
            val first = OpenCodeSession(id = "ses_first", serverTitle = "First", projectID = "p", directory = "/work", updatedAt = date(2_000))
            val second = OpenCodeSession(id = "ses_second", serverTitle = "Second", projectID = "p", directory = "/work", updatedAt = date(1_000))
            val child = OpenCodeSession(
                id = "ses_child", serverTitle = "Explore", projectID = "p", directory = "/work", updatedAt = date(2_500), parentID = "ses_first",
            )
            val grandchild = OpenCodeSession(
                id = "ses_grandchild", serverTitle = "Check", projectID = "p", directory = "/work", updatedAt = date(2_600), parentID = "ses_child",
            )
        }

        val gate = LinkProbeGate()
        val opened = LockIsolated<List<String>>(emptyList())
        val transcripts = Channel<String>(Channel.UNLIMITED)
        val store: TestStore<HomeFeature.State, Action>

        init {
            val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
            val sessions = listOf(first, second, child, grandchild)
            var state = h.state {
                val home = HomeFeature.State(server, snapshot(sessions)).copy(transcriptCache = sessions.associate { it.id to emptyList() })
                home.openingDetail(first, cachedItems = emptyList()).updatingDetail { it.copy(familySessions = listOf(first, child)) }
            }
            if (catchUp) state = state.withCatchUp(CatchUpFeature.Stack(queue = listOf(first.id)))
            val gate = gate
            val opened = opened
            val transcripts = transcripts
            store = h.store(state) {
                this[Clock] = ImmediateClock()
                this[DateClient] = DateClient.constant(date(3_000))
                this[UuidClient] = UuidClient.incrementing()
                this[ExperimentalFeatures] = ExperimentalFeatures.enabling(setOf(ExperimentalFeature.inAppBrowser))
                this[UniversalLinkClient] = UniversalLinkClient { gate.open(it) }
                this[OpenURLClient] = OpenURLClient(open = { url ->
                    opened.append(url)
                    true
                })
                this[OpenCodeClient] = client().copy(
                    homeAttention = { _, _, _ -> emptySet() },
                    getSession = { _, id -> sessions.firstOrNull { it.id == id } ?: first },
                    transcript = { _, _, _ -> emptyList() },
                    sessionFamily = { _, root -> OpenCodeSessionFamily(sessions = listOf(root), activeSessionIDs = emptySet()) },
                    runningShells = { _, _ -> emptyList() },
                    modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
                    familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
                    pendingInputs = { _, _ -> emptyList() },
                )
                this[DictationClient] = DictationClient(
                    start = { transcripts },
                    stop = { transcripts.close() },
                )
            }
            store.exhaustive = false
        }

        val parent: SessionFeature.State? get() = store.state.sessionDetail
        val top: SessionFeature.State? get() = store.state.topDetail

        /** Nothing opened anywhere: no page on any screen, and not the system browser. */
        fun expectNothingOpened() {
            assertEquals(false, store.state.sessionDetail?.browser?.isOpen)
            assertTrue(store.state.subagentStack.all { !it.browser.isOpen })
            assertTrue("Nor in the system browser", opened.value.isEmpty())
        }

        /** A tap on the screen on top whose answer is that no app claims it opens the in-app browser there. */
        suspend fun tapAgainAndOpen(link: String, request: Int) {
            store.send(topAction(SessionFeature.Action.LinkTapped(link)))
            gate.expect(LinkProbeGate.Event.Asked(link, request))
            gate.answer(link, request, openedInApp = false)
            store.receiveBounded({ it.detailAction is SessionFeature.Action.UniversalLinkResponse }, "sessionDetail.universalLinkResponse")
            store.receiveBounded(
                { (it.detailAction as? SessionFeature.Action.Browser)?.action is BrowserFeature.Action.Open },
                "sessionDetail.browser.open",
            )
            assertEquals("A fresh tap opens as usual", link, store.state.sessionDetail?.browser?.url)
        }

        fun topAction(action: SessionFeature.Action): Action {
            val id = store.state.subagentStack.lastOrNull()?.navigationID
            return if (id != null) Action.SubagentStack(id, action) else detail(action)
        }
    }

    private fun visibleScreenChanged(action: Action) = action.detailAction == SessionFeature.Action.VisibleScreenChanged

    /** Leaving the session before the system answers: the tap belonged to the session left, so nothing opens. */
    @Test
    fun aSwitchBeforeTheAnswerCancelsTheTap() = homeTest { h ->
        val lab = HomeLinkLab(h)
        val store = lab.store
        store.send(detail(SessionFeature.Action.LinkTapped(link)))
        lab.gate.expect(LinkProbeGate.Event.Asked(link, 1))
        store.send(Action.SessionTapped(HomeLinkLab.second.id))
        assertEquals(HomeLinkLab.second, lab.parent?.session)
        // The session left ends its effects: the check is cancelled, so no answer can arrive.
        lab.gate.expect(LinkProbeGate.Event.Cancelled(link, 1))
        store.skipReceivedActions()
        assertNull(lab.parent?.linkProbe)
        lab.expectNothingOpened()
        h.skipInFlightEffects()
    }

    /** A recording keeps the screen's identity, and its effects, across the switch: the answer arrives, on a fresh state that drops it. */
    @Test
    fun aSwitchKeepingTheScreenDropsTheAnswer() = homeTest { h ->
        val lab = HomeLinkLab(h)
        val store = lab.store
        store.send(detail(SessionFeature.Action.micPressBegan))
        store.receiveBounded({ it.detailAction == SessionFeature.Action.dictationReady }, "sessionDetail.dictationReady")
        val navigationID = lab.parent?.navigationID
        store.send(detail(SessionFeature.Action.LinkTapped(link)))
        lab.gate.expect(LinkProbeGate.Event.Asked(link, 1))
        val probeID = lab.parent?.linkProbe?.id ?: return@homeTest fail("The tap is waiting on the system")
        store.send(Action.SessionTapped(HomeLinkLab.second.id))
        assertEquals(HomeLinkLab.second, lab.parent?.session)
        assertEquals("The effect lives on", navigationID, lab.parent?.navigationID)

        lab.gate.answer(link, 1, openedInApp = false)
        store.receiveBounded(
            { it == detail(SessionFeature.Action.UniversalLinkResponse(probeID, openedInApp = false)) },
            "sessionDetail.universalLinkResponse",
        )
        // The recording isn't under test: stop it, then let every effect finish.
        store.send(detail(SessionFeature.Action.dictationStopRequested))
        store.skipReceivedActions()
        lab.expectNothingOpened()
        h.skipInFlightEffects()
    }

    /** Pushing a subagent covers the session: its waiting tap is cancelled, so nothing opens anywhere. */
    @Test
    fun pushingASubagentCancelsTheParentsTap() = homeTest { h ->
        val lab = HomeLinkLab(h)
        val store = lab.store
        store.send(detail(SessionFeature.Action.LinkTapped(link)))
        lab.gate.expect(LinkProbeGate.Event.Asked(link, 1))
        store.send(Action.SessionTapped(HomeLinkLab.child.id))
        assertEquals(HomeLinkLab.child, lab.top?.session)
        assertEquals(true, lab.parent?.isCovered)
        store.receiveBounded(::visibleScreenChanged, "sessionDetail.visibleScreenChanged")
        lab.gate.expect(LinkProbeGate.Event.Cancelled(link, 1))
        assertNull(lab.parent?.linkProbe)

        // Back on the parent, the old tap stays gone, and a fresh one works.
        store.send(Action.SubagentsPopped(0))
        lab.tapAgainAndOpen(link, request = 2)
        store.skipReceivedActions()
        assertTrue(lab.opened.value.isEmpty())
        h.skipInFlightEffects()
    }

    /** A subagent pushed over a subagent: the tap on the middle one is cancelled. */
    @Test
    fun aNestedPushCancelsTheTapBeneath() = homeTest { h ->
        val lab = HomeLinkLab(h)
        val store = lab.store
        store.send(Action.SessionTapped(HomeLinkLab.child.id))
        val childID = store.state.subagentStack.lastOrNull()?.navigationID ?: return@homeTest fail("The subagent is pushed")
        store.send(Action.SubagentStack(childID, SessionFeature.Action.LinkTapped(link)))
        lab.gate.expect(LinkProbeGate.Event.Asked(link, 1))
        store.send(Action.SessionTapped(HomeLinkLab.grandchild.id))
        assertEquals(2, store.state.subagentStack.size)
        assertEquals(HomeLinkLab.grandchild, lab.top?.session)
        store.receiveBounded(
            { it is Action.SubagentStack && it.id == childID && it.action == SessionFeature.Action.VisibleScreenChanged },
            "subagentStack[$childID].visibleScreenChanged",
        )
        lab.gate.expect(LinkProbeGate.Event.Cancelled(link, 1))
        assertNull(store.state.subagentStack.firstOrNull { it.navigationID == childID }?.linkProbe)
        store.skipReceivedActions()
        lab.expectNothingOpened()
        h.skipInFlightEffects()
    }

    /** A tap on a subagent, then back to its parent before the answer: the subagent's screen leaves and takes its check with it. */
    @Test
    fun popBeforeTheAnswerCancelsTheTap() = homeTest { h ->
        val lab = HomeLinkLab(h)
        val store = lab.store
        store.send(Action.SessionTapped(HomeLinkLab.child.id))
        val childID = store.state.subagentStack.lastOrNull()?.navigationID ?: return@homeTest fail("The subagent is pushed")
        store.send(Action.SubagentStack(childID, SessionFeature.Action.LinkTapped(link)))
        lab.gate.expect(LinkProbeGate.Event.Asked(link, 1))
        store.send(Action.SubagentsPopped(0))
        lab.gate.expect(LinkProbeGate.Event.Cancelled(link, 1))
        lab.expectNothingOpened()
        lab.tapAgainAndOpen(link, request = 2)
        store.skipReceivedActions()
        assertTrue(lab.opened.value.isEmpty())
        h.skipInFlightEffects()
    }

    /**
     * Catch Up shows a subagent in place (a family switch, no push). Parent → subagent → parent
     * before the answer: the tap is cancelled on the way out, so the parent's session coming back
     * can't revive it.
     */
    @Test
    fun aFamilyRoundTripInCatchUpCancelsTheTap() = homeTest { h ->
        val lab = HomeLinkLab(h, catchUp = true)
        val store = lab.store
        store.send(detail(SessionFeature.Action.LinkTapped(link)))
        lab.gate.expect(LinkProbeGate.Event.Asked(link, 1))
        store.send(detail(SessionFeature.Action.Delegate(SessionFeature.Delegate.OpenSubagent(HomeLinkLab.child))))
        store.receiveBounded({ it.detailAction is SessionFeature.Action.FamilyPicked }, "sessionDetail.familyPicked")
        assertTrue("Swapped in place, not pushed", store.state.subagentStack.isEmpty())
        assertEquals(HomeLinkLab.child.id, lab.parent?.session?.id)
        store.receiveBounded(::visibleScreenChanged, "sessionDetail.visibleScreenChanged")
        lab.gate.expect(LinkProbeGate.Event.Cancelled(link, 1))

        store.send(detail(SessionFeature.Action.FamilyPicked(HomeLinkLab.first.id)))
        assertEquals(HomeLinkLab.first.id, lab.parent?.session?.id)
        assertNull(lab.parent?.linkProbe)
        lab.expectNothingOpened()
        lab.tapAgainAndOpen(link, request = 2)
        store.skipReceivedActions()
        assertTrue(lab.opened.value.isEmpty())
        h.skipInFlightEffects()
    }

    /** Dismissing Catch Up's last card leaves its screen in place, but it is no longer the card on screen: its waiting tap is cancelled. */
    @Test
    fun dismissingTheLastCatchUpCardCancelsItsTap() = homeTest { h ->
        val lab = HomeLinkLab(h, catchUp = true)
        val store = lab.store
        store.send(detail(SessionFeature.Action.LinkTapped(link)))
        lab.gate.expect(LinkProbeGate.Event.Asked(link, 1))
        store.send(Action.CatchUp(CatchUpFeature.Action.CardDismissed(HomeLinkLab.first.id, CatchUpFeature.Disposition.keepUnread)))
        assertEquals(true, store.state.catchUp?.queue?.isEmpty())
        store.receiveBounded(::visibleScreenChanged, "sessionDetail.visibleScreenChanged")
        lab.gate.expect(LinkProbeGate.Event.Cancelled(link, 1))
        store.skipReceivedActions()
        assertNull(lab.parent?.linkProbe)
        lab.expectNothingOpened()
        h.skipInFlightEffects()
    }
}

class BrowserLifetimeTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "https://switch.example", username = "", password = "")
    private val link = "https://example.com/article"

    private fun session(id: String, parent: String? = null) =
        OpenCodeSession(id = id, serverTitle = id, projectID = "project", directory = "/project", updatedAt = date(10), parentID = parent)

    private fun Harness.store(sessions: List<OpenCodeSession>, discarded: LockIsolated<List<UUID>>): TestStore<HomeFeature.State, Action> {
        val state = state {
            HomeFeature.State(server, snapshot(sessions)).openingDetail(session("root"), cachedItems = emptyList())
        }
        return store(state) {
            this[UuidClient] = UuidClient.incrementing()
            this[PageReaderClient] = PageReaderClient(
                read = PageReaderClient.testValue.read,
                goBack = {},
                goForward = {},
                scrollToTop = {},
                openExternal = PageReaderClient.testValue.openExternal,
                discard = { id -> discarded.append(id) },
            )
            this[OpenCodeClient] = client().copy(
                getSession = { _, id -> session(id) },
                transcript = { _, _, _ -> awaitCancellation() },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
            )
        }.also { it.exhaustive = false }
    }

    @Test
    fun switchingSessionsLetsGoOfThePageAndItsStore() = homeTest { h ->
        val discarded = LockIsolated<List<UUID>>(emptyList())
        val store = h.store(listOf(session("root"), session("other")), discarded)
        store.send(detail(SessionFeature.Action.Browser(BrowserFeature.Action.Open(link))))
        val id = store.state.sessionDetail?.browser?.pageID ?: return@homeTest fail("The page opened")

        store.send(Action.SessionTapped("other"))
        repeat(100) { if (discarded.value.isEmpty()) store.skipReceivedActions() }
        assertEquals("The session that left took its page with it", listOf(id), discarded.value)
        assertEquals(false, store.state.sessionDetail?.browser?.isOpen)
        h.skipInFlightEffects()
    }

    @Test
    fun projectFilesCoverTheTranscriptAndReopeningTheSessionDismissesThem() = homeTest { h ->
        val store = h.store(listOf(session("root")), LockIsolated(emptyList()))
        store.send(detail(SessionFeature.Action.ProjectFilesTapped))
        assertTrue(store.state.sessionDetail?.projectFiles != null)
        assertTrue(store.state.sessionDetail?.isTranscriptCovered == true)
        store.send(Action.SessionTapped("root"))
        store.skipReceivedActions()
        assertNull(store.state.sessionDetail?.projectFiles)
        assertFalse(store.state.sessionDetail?.isTranscriptCovered == true)
        h.skipInFlightEffects()
    }

    @Test
    fun theSessionsOwnRowGoesBackToItFromUnderItsPage() = homeTest { h ->
        val discarded = LockIsolated<List<UUID>>(emptyList())
        val store = h.store(listOf(session("root")), discarded)
        store.send(detail(SessionFeature.Action.Browser(BrowserFeature.Action.Open(link))))
        val id = store.state.sessionDetail?.browser?.pageID ?: return@homeTest fail("The page opened")
        store.send(Action.SessionTapped("root"))
        assertEquals(false, store.state.sessionDetail?.browser?.isOpen)
        repeat(100) { if (discarded.value.isEmpty()) store.skipReceivedActions() }
        assertEquals(listOf(id), discarded.value)
        h.skipInFlightEffects()
    }
}

/**
 * The system's universal-link check, held until the test answers it. Records each check asked and
 * cancelled, numbered per link.
 */
internal class LinkProbeGate {
    sealed interface Event {
        data class Asked(val url: String, val number: Int) : Event
        data class Cancelled(val url: String, val number: Int) : Event
    }

    private val counts = HashMap<String, Int>()
    private val answers = HashMap<Pair<String, Int>, CompletableDeferred<Boolean>>()
    private val happened = HashSet<Event>()
    private val waiters = HashMap<Event, MutableList<CompletableDeferred<Unit>>>()

    suspend fun open(url: String): Boolean {
        val (number, answer) = synchronized(this) {
            val number = (counts[url] ?: 0) + 1
            counts[url] = number
            val answer = CompletableDeferred<Boolean>()
            answers[url to number] = answer
            note(Event.Asked(url, number))
            number to answer
        }
        try {
            return answer.await()
        } catch (cancelled: CancellationException) {
            synchronized(this) {
                answers.remove(url to number)
                note(Event.Cancelled(url, number))
            }
            throw cancelled
        }
    }

    fun answer(url: String, number: Int, openedInApp: Boolean) {
        val answer = synchronized(this) { answers.remove(url to number) } ?: throw AssertionError("Check $number of $url isn't waiting")
        answer.complete(openedInApp)
    }

    suspend fun expect(event: Event) {
        val waiter = synchronized(this) {
            if (event in happened) null else CompletableDeferred<Unit>().also { waiters.getOrPut(event) { mutableListOf() } += it }
        } ?: return
        try {
            withTimeout(5.seconds) { waiter.await() }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            fail("Timed out waiting for $event")
        }
    }

    private fun note(event: Event) {
        happened += event
        waiters.remove(event)?.forEach { it.complete(Unit) }
    }
}
