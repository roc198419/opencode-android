package ai.opencode.feature.drawer.switcher

import ai.opencode.core.R as CoreR
import ai.opencode.core.motion.AmbientTimeline
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.drawer.HomeConnectionFeature
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.R
import ai.opencode.feature.drawer.ServerMenuStatus
import ai.opencode.feature.drawer.ServerReachability
import ai.opencode.feature.drawer.style.DrawerTunables
import ai.opencode.feature.settings.R as SettingsR
import ai.opencode.feature.settings.connection.RenameServerDialog
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.friendlyName
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The drawer's title with Server Flow on. The server picked shows at once, with an underline
 * shimmering under its name until it answers. The menu checks the server shown and gives every
 * server it knows about a status glyph and word ([ServerMenuStatus]).
 *
 * @param servers in the menu's order ([drawerTitleServers]).
 */
@Composable
fun ServerFlowTitle(
    current: OpenCodeServer,
    servers: List<OpenCodeServer>,
    status: HomeConnectionFeature.State.ConnectionStatus,
    onSwitch: (OpenCodeServer) -> Unit,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
    onManage: () -> Unit,
    onAdd: () -> Unit,
    onPair: () -> Unit,
    onRename: (String) -> Unit,
    modifier: Modifier = Modifier,
    credentialsRejected: Boolean = false,
    switchingTo: OpenCodeServer? = null,
    reachability: Map<String, ServerReachability> = emptyMap(),
) {
    val shown = switchingTo ?: current
    var renaming by rememberSaveable(current.persistenceID) { mutableStateOf(false) }
    // A switch speaks for itself: the server it left has nothing to offer.
    val presentation = DrawerTitle.Presentation(
        serverCount = servers.size,
        status = if (switchingTo == null) status else HomeConnectionFeature.State.ConnectionStatus.connected,
        credentialsRejected = credentialsRejected,
    )
    val accessibilityValue = if (switchingTo == null) presentation.accessibilityValue else stringResource(R.string.drawer_server_connecting_state)
    val selectedText = stringResource(R.string.drawer_row_selected)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val available = maxWidth
        var expanded by rememberSaveable { mutableStateOf(false) }
        Box {
            Label(
                shown, presentation, isConnecting = switchingTo != null, chevron = true, available = available,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.DropdownList,
                        onClickLabel = presentation.accessibilityHint,
                    ) { expanded = true }
                    .clearAndSetSemantics {
                        contentDescription = shown.friendlyName
                        stateDescription = accessibilityValue
                        heading()
                        testTag = DrawerTitle.TAG
                    },
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (presentation.listsServers) {
                    for (server in servers) {
                        val isShown = server.persistenceID == shown.persistenceID
                        val serverStatus = ServerMenuStatus.of(server, current, status, credentialsRejected, switchingTo, reachability)
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(server.friendlyName, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                                    if (serverStatus != null) {
                                        Text(serverStatus.title, style = OpenCodeTextStyles.footnote, color = OpenCodeTheme.colors.secondaryText)
                                    }
                                }
                            },
                            leadingIcon = {
                                // A menu toggle draws the checkmark.
                                if (isShown) OpenCodeIcon(SFSymbol.Checkmark, contentDescription = null) else Spacer(Modifier.size(24.dp))
                            },
                            trailingIcon = serverStatus?.let { { OpenCodeIcon(it.systemImage, contentDescription = null) } },
                            onClick = {
                                expanded = false
                                if (!isShown) onSwitch(server)
                            },
                            // Picking a server switches to it, so it reads as chosen, not as a toggle.
                            modifier = Modifier.semantics { if (isShown) stateDescription = selectedText },
                        )
                    }
                    HorizontalDivider()
                }
                if (presentation.offersTryAgain) {
                    DropdownMenuItem(
                        text = { Text(stringResource(CoreR.string.common_try_again)) },
                        leadingIcon = { OpenCodeIcon(SFSymbol.ArrowClockwise, contentDescription = null) },
                        onClick = {
                            expanded = false
                            onRetry()
                        },
                    )
                }
                if (presentation.offersEdit) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.drawer_server_edit)) },
                        leadingIcon = { OpenCodeIcon(SFSymbol.Key, contentDescription = null) },
                        onClick = {
                            expanded = false
                            onEdit()
                        },
                    )
                }
                if (presentation.offersTryAgain || presentation.offersEdit) HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(SettingsR.string.settings_servers_add_server)) },
                    leadingIcon = { OpenCodeIcon(SFSymbol.Plus, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onAdd()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(SettingsR.string.settings_servers_scan_pairing_code)) },
                    leadingIcon = { OpenCodeIcon(SFSymbol.QrcodeViewfinder, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onPair()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(SettingsR.string.settings_server_rename_server_ellipsis)) },
                    leadingIcon = { OpenCodeIcon(SFSymbol.Pencil, contentDescription = null) },
                    enabled = switchingTo == null,
                    onClick = {
                        expanded = false
                        renaming = true
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.drawer_server_manage)) },
                    leadingIcon = { OpenCodeIcon(SFSymbol.Gearshape, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onManage()
                    },
                )
            }
        }

    }
    if (renaming) {
        RenameServerDialog(
            server = current,
            onDismissRequest = { renaming = false },
            onRename = onRename,
        )
    }
}

/** The Server Flow title wired to Home. */
@Composable
fun ServerFlowTitle(
    store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by store.observe { it.server }
    val status by store.observe { it.connection.displayedStatus }
    val switchingTo by store.observe { it.switchingTo }
    val credentialsRejected by store.observe { it.credentialsRejected }
    val reachability by store.observe { it.serverReachability }
    val saved by remember { shared(ServerSharedKeys.savedServers).flow }.collectAsState()
    ServerFlowTitle(
        current = current,
        servers = drawerTitleServers(current, saved),
        status = status,
        onSwitch = { store.send(HomeFeature.Action.Delegate(HomeFeature.DelegateAction.ServerSwitchTapped(it))) },
        onRetry = { store.send(HomeFeature.Action.RetryConnectionTapped) },
        onEdit = { store.send(HomeFeature.Action.Delegate(HomeFeature.DelegateAction.EditServerRequested(current))) },
        onManage = onManage,
        onAdd = { store.send(HomeFeature.Action.Delegate(HomeFeature.DelegateAction.AddServerRequested)) },
        onPair = { store.send(HomeFeature.Action.Delegate(HomeFeature.DelegateAction.PairServerRequested)) },
        onRename = { store.send(HomeFeature.Action.Delegate(HomeFeature.DelegateAction.ServerRenamed(current, it))) },
        modifier = modifier,
        credentialsRejected = credentialsRejected,
        switchingTo = switchingTo,
        reachability = reachability,
    )
}

private val chevronSize = 18.dp
private val chevronGap = 6.dp

/** The chevron and its gap. */
private val chevronWidth = chevronSize + chevronGap

@Composable
private fun Label(
    shown: OpenCodeServer,
    presentation: DrawerTitle.Presentation,
    isConnecting: Boolean,
    chevron: Boolean,
    available: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = OpenCodeTheme.colors
    val limit = maxOf(available - if (chevron) chevronWidth else 0.dp, 40.dp)
    Row(
        modifier.heightIn(min = 48.dp).widthIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(chevronGap),
    ) {
        Box {
            Name(shown.friendlyName, presentation, limit = limit)
            if (isConnecting) {
                Box(Modifier.matchParentSize(), contentAlignment = Alignment.BottomStart) {
                    ServerFlowUnderline(reducedMotion = rememberReduceMotion(), modifier = Modifier.offset(y = 2.dp))
                }
            }
        }
        if (chevron) {
            val chevronColor by animateColorAsState(
                if (presentation.isDimmed && !colors.highContrast) colors.tertiaryText else colors.secondaryText,
                MotionRole.Swap.animation(reduceMotion = rememberReduceMotion()),
                label = "server-flow-chevron",
            )
            OpenCodeIcon(SFSymbol.ChevronDown, contentDescription = null, size = chevronSize, tint = chevronColor)
        }
    }
}

@Composable
private fun Name(text: String, presentation: DrawerTitle.Presentation, limit: Dp) {
    val colors = OpenCodeTheme.colors
    val color by animateColorAsState(
        if (presentation.isDimmed) colors.secondaryText else colors.text,
        MotionRole.Swap.animation(reduceMotion = rememberReduceMotion()),
        label = "server-flow-name",
    )
    Text(
        text,
        modifier = Modifier.widthIn(max = limit),
        style = DrawerTunables.titleFont.font.copy(fontWeight = FontWeight.Bold),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.MiddleEllipsis,
    )
}

/**
 * The line under the name while a server connects. A sweep runs along it once per
 * `AmbientClock.sweepPeriod`. Reduce Motion holds the sweep. Hidden from TalkBack.
 */
@Composable
fun ServerFlowUnderline(reducedMotion: Boolean, modifier: Modifier = Modifier) {
    val colors = OpenCodeTheme.colors
    val track = colors.tertiaryText.copy(alpha = colors.tertiaryText.alpha * 0.5f)
    val ink = colors.secondaryText
    AmbientTimeline(isActive = true, reduceMotion = reducedMotion) { sample ->
        val phase = FastOutSlowInEasing.transform(sample.sweep.toFloat())
        Canvas(
            modifier
                .fillMaxWidth()
                .height(2.dp)
                .clipToBounds()
                .clearAndSetSemantics {},
        ) {
            val radius = CornerRadius(size.height / 2, size.height / 2)
            drawRoundRect(track, cornerRadius = radius)
            if (!reducedMotion && !sample.isSettled) {
                val segment = size.width * 0.32f
                drawRoundRect(ink, topLeft = Offset(segment * (-1 + 4.2f * phase), 0f), size = Size(segment, size.height), cornerRadius = radius)
            }
        }
    }
}
