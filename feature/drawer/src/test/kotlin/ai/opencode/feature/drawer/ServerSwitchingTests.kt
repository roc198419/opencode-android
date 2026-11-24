package ai.opencode.feature.drawer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import ai.opencode.core.ui.OpenCodeAppTheme
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeConnectionFeature.State.ConnectionStatus
import ai.opencode.feature.drawer.switcher.DrawerTitle
import ai.opencode.feature.drawer.switcher.ServerFlowTitle
import ai.opencode.feature.session.ServerFlowTunables
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.time.Duration.Companion.seconds

class ServerSwitchingTests {
    @get:Rule val isolation = TestIsolationRule()

    private val snapshot = OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet())

    private fun offlineClient() = OpenCodeClient.testValue.copy(
        homeSnapshot = { throw OpenCodeClientError(message = "Offline") },
    )

    @Test
    fun aReconnectedStreamKeepsANameGivenSinceItStarted() = runTest {
        val laptop = OpenCodeServer(url = "http://primary.test", username = "opencode", password = "a")
        val home = HomeFeature.State(laptop, snapshot).let {
            it.copy(connection = it.connection.copy(server = it.server.copy(name = "Work Mac")))
        }
        val store = TestStore(home, HomeFeature(), backgroundScope) {
            this[Clock] = TestClock()
            this[OpenCodeClient] = offlineClient()
        }
        store.exhaustive = false
        store.send(HomeFeature.Action.ServerConnected(laptop))
        assertEquals("Work Mac", store.state.server.name)
    }

    @Test
    fun theDrawerTitleDimsOnlyOnAConfirmedOutageAndItsMenuOffersTryAgain() {
        val one = DrawerTitle.Presentation(serverCount = 1, status = ConnectionStatus.connected)
        assertTrue(
            "One server still offers connection management",
            !one.isDimmed && one.hasMenu && one.accessibilityValue.isEmpty(),
        )
        assertEquals("Show your servers", one.accessibilityHint)
        val several = DrawerTitle.Presentation(serverCount = 3, status = ConnectionStatus.connected)
        assertTrue(!several.isDimmed && several.hasMenu && several.listsServers && !several.offersTryAgain)
        assertEquals("Show your servers", several.accessibilityHint)

        for (count in listOf(1, 3)) {
            val reconnecting = DrawerTitle.Presentation(serverCount = count, status = ConnectionStatus.reconnecting)
            assertTrue("A reconnect earns nothing", !reconnecting.isDimmed && !reconnecting.offersTryAgain)
            assertTrue(reconnecting.hasMenu && reconnecting.accessibilityValue == "Reconnecting")

            val offline = DrawerTitle.Presentation(serverCount = count, status = ConnectionStatus.disconnected)
            assertTrue("An outage dims it; Try Again is in its menu", offline.isDimmed && offline.hasMenu && offline.offersTryAgain)
            assertEquals(count > 1, offline.listsServers)
            assertEquals("VoiceOver doesn't rely on the dimming", "Offline", offline.accessibilityValue)
            assertEquals("Show your servers", offline.accessibilityHint)
        }

        val switching = DrawerTitle.Presentation(serverCount = 3, status = ConnectionStatus.disconnected, switchingTo = "Studio")
        assertTrue(
            "Connecting to another server isn't this one's outage",
            !switching.isDimmed && switching.accessibilityValue == "Connecting to Studio",
        )
    }

    @Test
    fun rejectedCredentialsOfferEditingInsteadOfTryAgain() {
        for (count in listOf(1, 3)) {
            val rejected = DrawerTitle.Presentation(serverCount = count, status = ConnectionStatus.disconnected, credentialsRejected = true)
            assertTrue(
                "Trying again can't fix a 401",
                rejected.isDimmed && rejected.hasMenu && rejected.offersEdit && !rejected.offersTryAgain,
            )
            assertEquals("Sign-in failed", rejected.accessibilityValue)
            assertEquals("Show your servers", rejected.accessibilityHint)
        }
        val offline = DrawerTitle.Presentation(serverCount = 1, status = ConnectionStatus.disconnected)
        assertTrue(offline.offersTryAgain && !offline.offersEdit)
    }

    @Test
    fun aDroppedStreamLeavesTheTitleAloneUntilItFailsAgain() = runTest {
        val laptop = OpenCodeServer(url = "http://primary.test", username = "opencode", password = "a")
        val home = HomeFeature.State(laptop, snapshot).let {
            it.copy(connection = it.connection.copy(hasConnected = true))
        }
        val clock = TestClock()
        val store = TestStore(home, HomeFeature(), backgroundScope) {
            this[Clock] = clock
            this[OpenCodeClient] = offlineClient()
        }
        store.exhaustive = false
        fun title() = DrawerTitle.Presentation(serverCount = 1, status = store.state.connection.displayedStatus)

        store.send(HomeFeature.Action.ConnectionLost)
        assertEquals(ConnectionStatus.reconnecting, store.state.connectionStatus)
        assertTrue("A blip keeps connection management available", !title().isDimmed && title().hasMenu && title().issue == null)
        store.send(HomeFeature.Action.ConnectionLost)
        assertEquals("A second failure inside the grace isn't an outage yet", ConnectionStatus.reconnecting, store.state.connectionStatus)
        clock.advance(ServerFlowTunables.grace.seconds)
        store.receive({ it is HomeFeature.Action.ReconnectingGraceElapsed }, "reconnectingGraceElapsed")
        assertTrue(title().issue == "Reconnecting" && !title().isDimmed)
        clock.advance(ServerFlowTunables.offlineGrace.seconds)
        store.receive({ it is HomeFeature.Action.OfflineGraceElapsed }, "offlineGraceElapsed")
        assertEquals(ConnectionStatus.disconnected, store.state.connectionStatus)
        assertTrue("Still failing after the grace is an outage", title().isDimmed && title().offersTryAgain)
        store.send(HomeFeature.Action.RetryConnectionTapped)
        assertEquals(ConnectionStatus.reconnecting, store.state.connectionStatus)
        assertEquals("Try Again shows its reconnect at once", "Reconnecting", title().issue)
        assertFalse("Try Again brightens it while it reconnects", title().isDimmed)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ServerManagementViewTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test fun oneConnectedComputerOffersAddingPairingRenamingAndManagement() {
        val frameDelay = ShadowChoreographer.getFrameDelay()
        val paused = ShadowChoreographer.isPaused()
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16))
        val server = mutableStateOf(OpenCodeServer(url = "https://computer.invalid", username = "opencode", password = "", name = "Work Laptop"))
        var added = 0
        var paired = 0
        var managed = 0
        var renamed = 0
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val view = ComposeView(controller.get())
        controller.get().setContentView(view)
        try {
            view.setContent {
                OpenCodeAppTheme {
                    Box(Modifier.fillMaxSize().background(Color.White).padding(20.dp)) {
                        ServerFlowTitle(
                            current = server.value,
                            servers = listOf(server.value),
                            status = ConnectionStatus.connected,
                            onSwitch = { error("Unexpected server switch") },
                            onRetry = {},
                            onEdit = {},
                            onManage = { managed++ },
                            onAdd = { added++ },
                            onPair = { paired++ },
                            onRename = { name ->
                                renamed++
                                server.value = server.value.copy(name = name)
                            },
                        )
                    }
                }
            }
            pump()
            view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 1080, 2400)
            pump()
            openMenu()
            assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Rename Server…" } == true })
            click("Add Server")
            assertEquals(1, added)
            openMenu()
            click("Scan Pairing Code")
            assertEquals(1, paired)
            openMenu()
            click("Manage Servers…")
            assertEquals(1, managed)
            openMenu()
            click("Rename Server…")
            screenshot("rename")
            editName("Personal Computer")
            click("Cancel")
            assertEquals(0, renamed)
            assertEquals("Work Laptop", server.value.name)
            openMenu()
            click("Rename Server…")
            assertEquals("Work Laptop", nodes().first { it.config.getOrNull(SemanticsActions.SetText) != null }.config[SemanticsProperties.EditableText].text)
            editName("Personal Computer")
            click("Save")
            assertEquals(1, renamed)
            assertEquals("Personal Computer", server.value.name)
            assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Personal Computer") == true })
        } finally {
            view.disposeComposition()
            controller.pause().stop().destroy()
            ShadowChoreographer.setFrameDelay(frameDelay)
            ShadowChoreographer.setPaused(paused)
        }
    }

    private fun pump() = repeat(8) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50)) }

    private fun root(view: View): RootForTest? = when (view) {
        is RootForTest -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { root(view.getChildAt(it)) }
        else -> null
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
    private fun nodes() = WindowInspector.getGlobalWindowViews().flatMap { root(it)?.semanticsOwner?.unmergedRootSemanticsNode?.let(::descendants).orEmpty() }

    private fun openMenu() {
        val title = nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == DrawerTitle.TAG }
        assertTrue(requireNotNull(title.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
        pump()
    }

    private fun click(text: String) {
        val node = nodes().first { it.config.getOrNull(SemanticsActions.OnClick) != null && (it.config.getOrNull(SemanticsProperties.Text)?.any { item -> item.text == text } == true || descendants(it).any { child -> child.config.getOrNull(SemanticsProperties.Text)?.any { item -> item.text == text } == true }) }
        assertTrue(requireNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
        pump()
    }

    private fun editName(text: String) {
        val field = nodes().first { it.config.getOrNull(SemanticsActions.SetText) != null }
        assertTrue(requireNotNull(field.config.getOrNull(SemanticsActions.SetText)?.action).invoke(AnnotatedString(text)))
        pump()
    }

    private fun screenshot(name: String) {
        System.getProperty("opencode.serverManagementScreenshots")?.let { directory ->
            val label = "Rename Server"
            val window = WindowInspector.getGlobalWindowViews().last { candidate ->
                root(candidate)?.semanticsOwner?.unmergedRootSemanticsNode?.let(::descendants)?.any { node ->
                    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
                } == true
            }
            val bitmap = Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888)
            window.draw(Canvas(bitmap))
            val file = java.io.File(directory, "server-$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
