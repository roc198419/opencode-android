package ai.opencode.feature.settings

import ai.opencode.core.R as CoreR
import ai.opencode.core.experiments.ExperimentalFeature
import ai.opencode.core.experiments.key
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.PageTransitions
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.store.scope
import ai.opencode.core.ui.OpenCodeLargeTopBar
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.OpenCodeTopBar
import ai.opencode.core.ui.OpenCodeTopBarBackButton
import ai.opencode.core.ui.rememberOpenCodeLargeTopBarScrollBehavior
import ai.opencode.core.ui.rememberOverscrollIfContentScrolls
import ai.opencode.core.ui.AccessibilityFocusRequest
import ai.opencode.core.ui.accessibilityFocusRequest
import ai.opencode.core.ui.rememberAccessibilityFocusRequest
import ai.opencode.feature.settings.connection.ConnectionCard
import ai.opencode.feature.settings.connection.ServerPage
import ai.opencode.feature.settings.onboarding.OnboardingPlaygroundView
import ai.opencode.feature.settings.serverdetails.ServerDetailsPage
import ai.opencode.feature.settings.whatsnew.WhatsNewDetailPage
import ai.opencode.feature.settings.whatsnew.WhatsNewListPage
import ai.opencode.feature.settings.whatsnew.WhatsNewSmallerListPage
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.delay

/** Every Settings store page composables take (pages, not list rows, may hold a store). */
typealias SettingsStore = StoreOf<SettingsFeature.State, SettingsFeature.Action>

private sealed interface SettingsRoute {
    data object Root : SettingsRoute
    data class Page(val index: Int, val page: SettingsPage) : SettingsRoute
}

/**
 * Settings' own stack, the root page (connection card; Appearance, Sessions, Models, Voice,
 * Notifications; What’s New; Server Details; Experimental, Developer and the version line) and the
 * pages it pushes. Back on a pushed page goes back one level; at the root it closes Settings
 * ([SettingsFeature.SettingsDelegate.Close]).
 */
@Composable
fun SettingsView(store: SettingsStore, modifier: Modifier = Modifier) {
    LaunchedEffect(store) { store.send(SettingsFeature.Action.Task) }
    val path by store.observe { it.path }
    val root by store.observe { it.root }
    val backStack: List<SettingsRoute> = remember(path) {
        listOf<SettingsRoute>(SettingsRoute.Root) + path.mapIndexed { index, page -> SettingsRoute.Page(index, page) }
    }
    Box(modifier.fillMaxSize()) {
        // Pages move on Material's shared axis (and the back gesture previews the pop) instead of
        // Navigation 3's default 0.7 s cross-fade.
        val reduceMotion = rememberReduceMotion()
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val slidePx = with(LocalDensity.current) { PageTransitions.SlideDistance.roundToPx() }
        NavDisplay(
            backStack = backStack,
            onBack = { store.send(SettingsFeature.Action.BackTapped) },
            transitionSpec = { PageTransitions.forward(slidePx, rtl, reduceMotion) },
            popTransitionSpec = { PageTransitions.backward(slidePx, rtl, reduceMotion) },
            predictivePopTransitionSpec = { PageTransitions.predictiveBack(slidePx, rtl, reduceMotion) },
            entryProvider = { route ->
                when (route) {
                    SettingsRoute.Root -> NavEntry(route) {
                        // Settings asked for while What’s New is the page up switches Settings'
                        // root.
                        AnimatedContent(root, transitionSpec = { PageTransitions.fadeThrough(reduceMotion) }, label = "settings-root") { shown ->
                            when (shown) {
                                SettingsFeature.State.Root.settings -> SettingsRootPage(store)
                                SettingsFeature.State.Root.whatsNew -> WhatsNewListPage(store)
                            }
                        }
                    }
                    is SettingsRoute.Page -> NavEntry(route) { SettingsPageContent(store, route.page) }
                }
            },
        )
    }
    OnboardingPlaygroundPresentation(store)
}

@Composable
private fun SettingsPageContent(store: SettingsStore, page: SettingsPage) {
    when (page) {
        SettingsPage.Server -> ServerPage(store)
        SettingsPage.Appearance -> AppearanceSettingsView(store)
        SettingsPage.Themes -> ThemesSettingsView(store)
        SettingsPage.Language -> LanguageSettingsView(store)
        SettingsPage.Sessions -> SessionsSettingsView(store)
        SettingsPage.Models -> ModelVisibilitySettingsView(store)
        is SettingsPage.ModelProvider -> ModelVisibilityProviderView(store, page.providerID)
        SettingsPage.Voice -> VoiceSettingsView(store)
        is SettingsPage.VoiceProvider -> VoiceProviderDetailPage(store, page.opensAccount)
        SettingsPage.VoiceAccount -> VoiceProviderAccountPage(store)
        SettingsPage.VoiceDelegator -> VoiceDelegatorSettingsPage(store)
        SettingsPage.CallVoicePicker -> CallVoicePickerView(store)
        SettingsPage.CallHistory -> CallHistoryView(store)
        is SettingsPage.CallReplay -> CallReplayView(store, page.log)
        SettingsPage.Notifications -> NotificationSettingsView(store)
        SettingsPage.WhatsNew -> WhatsNewListPage(store)
        is SettingsPage.WhatsNewDetail -> WhatsNewDetailPage(store, page.entryID)
        SettingsPage.WhatsNewSmallerChanges -> WhatsNewSmallerListPage(store)
        SettingsPage.ServerDetails -> ServerDetailsPage(store)
        is SettingsPage.Experimental -> ExperimentalSettingsView(store, focus = page.focus?.let(ExperimentalFeature::fromRawValue))
        SettingsPage.Developer -> DeveloperSettingsView(store)
        SettingsPage.Playgrounds -> PlaygroundsSettingsView(store)
        SettingsPage.MotionPlayground -> MotionPlaygroundSettingsView(store)
    }
}

/** The Onboarding playground, full screen over Settings. */
@Composable
private fun OnboardingPlaygroundPresentation(store: SettingsStore) {
    val shown by store.observe { it.onboardingPlayground != null }
    if (!shown) return
    Dialog(
        onDismissRequest = { store.send(SettingsFeature.Action.OnboardingPlayground(PresentationAction.Dismiss)) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val playground = remember(store) {
            store.scope<SettingsFeature.State, SettingsFeature.Action, ai.opencode.feature.settings.onboarding.OnboardingPlaygroundFeature.State, ai.opencode.feature.settings.onboarding.OnboardingPlaygroundFeature.Action>(
                state = { it.onboardingPlayground ?: ai.opencode.feature.settings.onboarding.OnboardingPlaygroundFeature.State() },
                action = { SettingsFeature.Action.OnboardingPlayground(PresentationAction.Presented(it)) },
            )
        }
        OnboardingPlaygroundView(
            playground,
            onClose = { store.send(SettingsFeature.Action.OnboardingPlayground(PresentationAction.Dismiss)) },
        )
    }
}

/**
 * One Settings page's chrome: a Back control (“Back” closes Settings at the root, pops otherwise)
 * and a list of grouped rows.
 *
 * The bar is the desktop title bar ([OpenCodeLargeTopBar]): a start-aligned large title that
 * scrolls up into the bar as the list scrolls, and the page sets text in Material's own type scale
 * ([SettingsText.typography]). A page that draws its own title ([titleVisible] false, as What's
 * New's entry page does) keeps the small bar, its title fading in over 0.15 s once the page's title
 * scrolls under it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPageScaffold(
    title: String,
    store: SettingsStore,
    modifier: Modifier = Modifier,
    /** False hides the bar's title (fading on the swap role), as What's New's entry page does until its own title scrolls under the bar. */
    titleVisible: Boolean = true,
    /** Whether the page draws its own title, so the bar stays small (What's New's entry page). */
    drawsOwnTitle: Boolean = false,
    listState: LazyListState = rememberLazyListState(),
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val canvas = OpenCodeTheme.colors.groupedCanvas
    val scrollBehavior = if (drawsOwnTitle) {
        TopAppBarDefaults.pinnedScrollBehavior()
    } else {
        rememberOpenCodeLargeTopBarScrollBehavior(listState)
    }
    val back: @Composable () -> Unit = {
        OpenCodeTopBarBackButton(onClick = { store.send(SettingsFeature.Action.BackTapped) })
    }
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        motionScheme = MaterialTheme.motionScheme,
        shapes = MaterialTheme.shapes,
        typography = SettingsText.typography,
    ) {
        Scaffold(
            modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = canvas,
            topBar = {
                if (drawsOwnTitle) {
                    OpenCodeTopBar(
                        title = {
                            val reduceMotion = rememberReduceMotion()
                            val phase = if (titleVisible) MotionPhase.Enter else MotionPhase.Exit
                            val alpha by animateFloatAsState(if (titleVisible) 1f else 0f, MotionRole.Swap.animation(phase, reduceMotion), label = "settings-title")
                            Text(
                                title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .graphicsLayer { this.alpha = alpha }
                                    .then(if (titleVisible) Modifier.semantics { heading() } else Modifier.clearAndSetSemantics {}),
                            )
                        },
                        navigation = back,
                        actions = actions,
                        scrollBehavior = scrollBehavior,
                        containerColor = canvas,
                    )
                } else {
                    OpenCodeLargeTopBar(
                        title = title,
                        scrollBehavior = scrollBehavior,
                        navigation = back,
                        actions = actions,
                        containerColor = canvas,
                    )
                }
            },
            bottomBar = bottomBar,
        ) { padding ->
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
                modifier = Modifier.fillMaxSize(),
                overscrollEffect = rememberOverscrollIfContentScrolls(listState),
                content = content,
            )
        }
    }
}

/** Settings' root page. */
@Composable
private fun SettingsRootPage(store: SettingsStore) {
    val voiceModeEnabled by remember { shared(SharedKeys.voiceModeEnabled).flow }.collectAsState()
    val developerMode by remember { shared(SharedKeys.developerMode).flow }.collectAsState()
    val hideModels by remember { shared(ExperimentalFeature.hideModels.key).flow }.collectAsState()
    val notificationPermission by store.observe { it.notificationPermission }
    val returnFocus = rememberSettingsReturnFocus(store, page = null)
    val push = { page: SettingsPage ->
        returnFocus.opened(page)
        store.send(SettingsFeature.Action.PagePushed(page))
    }

    CompositionLocalProvider(LocalSettingsRowIcons provides true) {
        SettingsPageScaffold(title = stringResource(CoreR.string.common_settings), store = store) {
            item(key = "connection", contentType = "connection") {
                ConnectionCard(
                    store,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    onOpen = { returnFocus.opened(SettingsPage.Server) },
                    focusRequest = returnFocus.request(SettingsPage.Server),
                )
            }
            // Preferences: Appearance, Language, Sessions, Models, Voice and Notifications.
            settingsSection(key = "preferences") {
                SettingsRow(stringResource(R.string.settings_appearance_title), "paintpalette.fill", SettingsTint.Purple, modifier = returnFocus.on(SettingsPage.Appearance)) {
                    push(SettingsPage.Appearance)
                }
                SettingsDivider(inset = true)
                SettingsRow(stringResource(R.string.settings_language_title), "globe", SettingsTint.Blue, modifier = returnFocus.on(SettingsPage.Language)) {
                    push(SettingsPage.Language)
                }
                SettingsDivider(inset = true)
                SettingsRow(stringResource(R.string.settings_sessions_title), "bubble.left.fill", SettingsTint.Blue, modifier = returnFocus.on(SettingsPage.Sessions)) { push(SettingsPage.Sessions) }
                if (ExperimentalFeature.hideModels.isActive(hideModels)) {
                    SettingsDivider(inset = true)
                    SettingsRow(stringResource(R.string.settings_models_title), "cpu", SettingsTint.Teal, modifier = returnFocus.on(SettingsPage.Models)) { push(SettingsPage.Models) }
                }
                SettingsDivider(inset = true)
                SettingsRow(stringResource(R.string.settings_voice_title), "waveform", SettingsTint.Indigo, modifier = returnFocus.on(SettingsPage.Voice), badge = if (voiceModeEnabled) stringResource(R.string.settings_value_on) else null) {
                    push(SettingsPage.Voice)
                }
                if (notificationPermission != null) {
                    SettingsDivider(inset = true)
                    SettingsRow(stringResource(R.string.settings_notifications_title), "bell.badge.fill", SettingsTint.Red, modifier = returnFocus.on(SettingsPage.Notifications)) {
                        push(SettingsPage.Notifications)
                    }
                }
            }
            settingsSection(key = "whats-new") {
                SettingsRow(stringResource(R.string.settings_update_title), "arrow.down.circle", SettingsTint.Blue) {
                    store.send(SettingsFeature.Action.Delegate(SettingsFeature.SettingsDelegate.CheckAppUpdates))
                }
                SettingsDivider(inset = true)
                SettingsRow(stringResource(R.string.settings_whats_new_title), "sparkles", SettingsTint.Orange, modifier = returnFocus.on(SettingsPage.WhatsNew)) { push(SettingsPage.WhatsNew) }
            }
            settingsSection(
                key = "experimental",
                footer = {
                    SelectionContainer(Modifier.fillMaxWidth()) {
                        Text(
                            AppVersion.current.displayLabel,
                            style = OpenCodeTextStyles.footnote.copy(fontFeatureSettings = "tnum"),
                            color = OpenCodeTheme.colors.secondaryText,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
            ) {
                ExperimentalSettingsRow(returnFocus.on(SettingsPage.Experimental())) { push(SettingsPage.Experimental()) }
                SettingsDivider(inset = true)
                SettingsRow(stringResource(R.string.settings_developer_title), "hammer.fill", SettingsTint.Teal, modifier = returnFocus.on(SettingsPage.Developer), badge = if (developerMode) stringResource(R.string.settings_value_on) else null) {
                    push(SettingsPage.Developer)
                }
            }
        }
    }
}

/**
 * QA U-02: when NavigationStack pops a page, VoiceOver goes back to the row that pushed it;
 * TalkBack landed on Back instead. A page remembers which of its rows pushed the page above it
 * ([opened]), and once it's the top page again that row takes TalkBack's focus back ([on], `:core`'s
 * `accessibilityFocusRequest`, a no-op without touch exploration). The request is spent by that
 * move, so the list scrolling the row away and back doesn't move the focus again (QA W-05), and the
 * row is forgotten soon after, so a later return that no row started doesn't land on it.
 */
@Stable
internal class SettingsReturnFocus(
    private val opener: MutableState<String?>,
    private val returning: String?,
    private val focus: AccessibilityFocusRequest?,
) {
    fun opened(page: SettingsPage) {
        opener.value = page.toString()
    }

    fun request(page: SettingsPage): AccessibilityFocusRequest? = focus.takeIf { page.toString() == returning }

    fun on(page: SettingsPage): Modifier = Modifier.accessibilityFocusRequest(request(page))
}

/** [SettingsReturnFocus] for the page showing [page] (null: Settings' root). */
@Composable
internal fun rememberSettingsReturnFocus(store: SettingsStore, page: SettingsPage?): SettingsReturnFocus {
    val opener = rememberSaveable { mutableStateOf<String?>(null) }
    val isTop by store.observe { it.path.lastOrNull() == page }
    val returning = opener.value.takeIf { isTop }
    LaunchedEffect(returning) {
        if (returning == null) return@LaunchedEffect
        delay(SETTINGS_RETURN_FOCUS_HOLD_MS)
        opener.value = null
    }
    val focus = rememberAccessibilityFocusRequest(returning)
    return remember(opener, returning, focus) { SettingsReturnFocus(opener, returning, focus) }
}

/** How long a returning row keeps its focus request: well past the two frames it waits before moving TalkBack. */
private const val SETTINGS_RETURN_FOCUS_HOLD_MS = 1_000L

/** Shared body for pages whose list sits under [SettingsPageScaffold]. */
internal fun LazyListScope.footerOnly(key: Any, content: @Composable ColumnScope.() -> Unit) {
    item(key = key) {
        androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 32.dp, vertical = 8.dp), content = content)
    }
}
