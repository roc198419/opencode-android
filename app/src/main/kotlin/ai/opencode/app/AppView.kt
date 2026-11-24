package ai.opencode.app

import ai.opencode.feature.settings.AppUpdateView
import ai.opencode.feature.system.updates.AppUpdateFeature

import ai.opencode.core.R as CoreR
import ai.opencode.core.ui.OpenCodeErrorText
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeSheetHandle
import ai.opencode.core.ui.OpenCodeSurfaces
import androidx.compose.ui.unit.sp
import ai.opencode.core.ui.OpenCodeRadius
import androidx.compose.foundation.BorderStroke
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.diagnostics.HidesFromScreenshots
import ai.opencode.core.experiments.key
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.store.rememberScope
import ai.opencode.core.strings.localized
import ai.opencode.core.ui.AccessibilityFocusTarget
import ai.opencode.core.ui.OpenCodeAppTheme
import ai.opencode.core.ui.OpenCodeFonts
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeIconButtonStyle
import ai.opencode.core.ui.OpenCodeMinTapTarget
import ai.opencode.core.ui.OpenCodePrimaryButton
import ai.opencode.core.ui.OpenCodeSecondaryButton
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.ThisDevice
import ai.opencode.core.ui.accessibilityFocusRequest
import ai.opencode.core.ui.accessibilityFocusTarget
import ai.opencode.core.ui.rememberAccessibilityFocusRequest
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.HomeView
import ai.opencode.feature.drawer.notices.AccessibilityAnnouncer
import ai.opencode.feature.media.MediaView
import ai.opencode.feature.settings.onboarding.WelcomeFeature
import ai.opencode.feature.settings.onboarding.WelcomeView
import ai.opencode.feature.settings.servers.PairingScannerView
import ai.opencode.feature.system.clienttools.ClientToolsFeature
import ai.opencode.feature.transcript.TranscriptMediaProviders
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.clienttools.ClientTool
import ai.opencode.server.friendlyName
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import ai.opencode.core.ui.OpenCodeAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import ai.opencode.core.ui.OpenCodeTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** The phone's client tools: links and audio always, the screen while its experiment is on. */
private fun clientTools(shareScreen: Boolean): Set<ClientTool> {
    val tools = mutableSetOf(ClientTool.voiceOpenURL, ClientTool.voicePlayAudio, ClientTool.voiceAudioControl)
    if (ExperimentalFeature.shareScreenWithAgents.isActive(shareScreen)) tools.add(ClientTool.voiceRequestScreen)
    return tools
}

/**
 * Home when a server is connected, else the connection screen; over either the New Server / Edit
 * Server sheet, the pairing scanner, the pairing confirmation, the error alerts and the server
 * switch toast.
 */
@Composable
fun AppView(store: StoreOf<AppFeature.State, AppFeature.Action>) {
    LaunchedEffect(store) {
        store.send(AppFeature.Action.Task)
        // The system action inbox is started by `AppWideWork`, in the window that owns it.
        store.send(AppFeature.Action.PendingSharesRequested)
    }
    val hasHome by store.observe { it.home != null }
    val isEditingServer by store.observe { it.isEditingServer }
    val isScanning by store.observe { it.isScanningPairingCode }
    val hasSavedServers by store.observe { it.savedServers.value.isNotEmpty() }
    val welcomePreview by remember { shared(ExperimentalFeature.welcomePreview.key).flow }.collectAsState()
    val showsWelcome = !hasSavedServers && ExperimentalFeature.welcomePreview.isActive(welcomePreview)
    val shareScreenWithAgents by remember { shared(ExperimentalFeature.shareScreenWithAgents.key).flow }.collectAsState()
    val clientTools = clientTools(shareScreenWithAgents)
    LaunchedEffect(store, clientTools) {
        store.send(AppFeature.Action.ClientTools(ClientToolsFeature.Action.ToolsChanged(clientTools)))
    }
    OpenCodeAppTheme {
        // Media's file viewer renders Markdown files with the transcript's renderer.
        TranscriptMediaProviders {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (hasHome) {
                val home = store.rememberScope<AppFeature.State, AppFeature.Action, HomeFeature.State, HomeFeature.Action>(
                    state = { it.home ?: HomeFeature.State.placeholder(OpenCodeServer(url = "", username = "", password = "")) },
                    action = AppFeature.Action::Home,
                )
                // The app's one media root: the fullscreen viewer covers the whole Home.
                MediaView { HomeView(home, welcomeMark = painterResource(R.drawable.opencode_app_mark)) }
            } else if (showsWelcome) {
                val welcome = store.rememberScope<AppFeature.State, AppFeature.Action, WelcomeFeature.State, WelcomeFeature.Action>(
                    state = { it.welcome },
                    action = AppFeature.Action::Welcome,
                )
                val progress by store.observe { it.connectionProgressLabel }
                val problem by store.observe { it.errorMessage }
                WelcomeView(welcome, progress, problem, mark = painterResource(R.drawable.opencode_app_mark))
            } else {
                ConnectionView(store)
            }
            // Over Home too: the drawer adds and edits servers without leaving it.
            if (isEditingServer) ServerFormSheet(store)
            if (isScanning) PairingScannerPresenter(store)
            AppAlerts(store)
            AppUpdateView(store.rememberScope<AppFeature.State, AppFeature.Action, AppUpdateFeature.State, AppUpdateFeature.Action>(
                state = { it.updates }, action = AppFeature.Action::Updates,
            ))
        }
        }
    }
}

@Composable
private fun AppAlerts(store: StoreOf<AppFeature.State, AppFeature.Action>) {
    val shareError by store.observe { it.shareError }
    val notificationError by store.observe { it.notificationError }
    val systemActionError by store.observe { it.systemActionError }
    val pairingError by store.observe { it.pairingError }
    val pendingPairing by store.observe { it.pendingPairing }
    shareError?.let { ErrorAlert(stringResource(R.string.app_alert_share_failed_title), it) { store.send(AppFeature.Action.ShareErrorDismissed) } }
    notificationError?.let { ErrorAlert(stringResource(R.string.app_alert_open_session_failed_title), it) { store.send(AppFeature.Action.NotificationErrorDismissed) } }
    systemActionError?.let { ErrorAlert(stringResource(R.string.app_alert_open_session_failed_title), it) { store.send(AppFeature.Action.SystemActionErrorDismissed) } }
    pairingError?.let { ErrorAlert(stringResource(R.string.app_alert_pairing_failed_title), it) { store.send(AppFeature.Action.PairingErrorDismissed) } }
    pendingPairing?.let { pending ->
        OpenCodeAlertDialog(
            onDismissRequest = { store.send(AppFeature.Action.PairingCancelled(pending.id)) },
            title = { Text(pending.title) },
            text = { Text(pending.message) },
            confirmButton = { TextButton(onClick = { store.send(AppFeature.Action.PairingConfirmed(pending.id)) }) { Text(stringResource(R.string.app_pairing_confirm)) } },
            dismissButton = { TextButton(onClick = { store.send(AppFeature.Action.PairingCancelled(pending.id)) }) { Text(stringResource(CoreR.string.common_cancel)) } },
        )
    }
}

@Composable
private fun ErrorAlert(title: String, message: String, onDismiss: () -> Unit) {
    OpenCodeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.common_ok)) } },
    )
}

/** The largest font scales (1.8× and 2×). */
@Composable
private fun isAccessibilitySize(): Boolean = LocalDensity.current.fontScale > 1.5f

/** The saved servers, pairing and the manual form. */
@Composable
private fun ConnectionView(store: StoreOf<AppFeature.State, AppFeature.Action>) {
    val savedServers by store.observe { it.savedServers.value }
    val errorMessage by store.observe { it.errorMessage }
    val errorAttempt by store.observe { it.errorContext?.attempt }
    val returnServer by store.observe { it.returnServer }
    val progress by store.observe { it.connectionProgressLabel }
    val accessibilitySize = isAccessibilitySize()
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (savedServers.isNotEmpty()) {
            // The "Servers" bar: shown once there are servers, with Back to the one just left.
            Box(Modifier.fillMaxWidth().heightIn(min = 56.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.app_servers_title), style = OpenCodeTextStyles.headline, modifier = Modifier.semantics { heading() })
                if (returnServer != null && progress == null) {
                    OpenCodeIconButton(
                        symbol = SFSymbol.ChevronBackward,
                        contentDescription = stringResource(R.string.app_servers_back_description, returnServer?.displayName ?: stringResource(R.string.app_server_fallback_name)),
                        onClick = { store.send(AppFeature.Action.ReturnToServerTapped) },
                        style = OpenCodeIconButtonStyle.Standard,
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp),
                    )
                }
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = COLUMN_WIDTH).fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.padding(top = if (savedServers.isEmpty()) 24.dp else 0.dp),
                ) {
                    Image(
                        painter = painterResource(R.drawable.opencode_wordmark),
                        contentDescription = "OpenCode",
                        modifier = Modifier.size(width = 200.dp, height = 36.dp),
                        alignment = Alignment.CenterStart,
                    )
                    Text(
                        if (savedServers.isEmpty()) stringResource(R.string.app_connection_intro_new, ThisDevice.casualName)
                        else stringResource(R.string.app_connection_intro_returning),
                        style = OpenCodeTextStyles.body,
                        color = OpenCodeTheme.colors.secondaryText,
                    )
                }
                // New nodes for each attempt, so a repeated failure is announced again.
                errorMessage?.let { androidx.compose.runtime.key(errorAttempt) { ConnectionErrorCard(store, it, announces = true) } }
                if (savedServers.isEmpty()) SetupSteps(store) else SavedServerList(store, savedServers)
                if (accessibilitySize) PairingActionsButtons(store)
            }
        }
        if (!accessibilitySize) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.widthIn(max = COLUMN_WIDTH).padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 16.dp)) {
                    PairingActionsButtons(store)
                }
            }
        }
    }
}

private val COLUMN_WIDTH = 520.dp

/** Peer pairing actions share one shape; prominence is carried by the fill alone. */
@Composable
private fun PairingActionsButtons(store: StoreOf<AppFeature.State, AppFeature.Action>) {
    val progress by store.observe { it.connectionProgressLabel }
    val colors = OpenCodeTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PairingActionButton(
            onClick = { store.send(AppFeature.Action.AddServerTapped) },
            container = OpenCodeSurfaces.neutralFill,
            content = colors.text,
            border = OpenCodeSurfaces.border,
        ) { Text(stringResource(R.string.app_connection_enter_manually), style = PairingActionLabel, textAlign = TextAlign.Center) }
        PairingActionButton(
            onClick = { store.send(AppFeature.Action.ScanPairingCodeTapped) },
            enabled = progress == null,
            container = colors.text,
            content = colors.canvas,
        ) {
            if (progress != null) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.canvas)
                Spacer(Modifier.width(10.dp))
                Text(progress!!, style = PairingActionLabel)
            } else {
                OpenCodeIcon(SFSymbol.QrcodeViewfinder, contentDescription = null, size = 18.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.app_connection_scan_pairing_code), style = PairingActionLabel, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun PairingActionButton(
    onClick: () -> Unit,
    container: Color,
    content: Color,
    enabled: Boolean = true,
    border: BorderStroke? = null,
    label: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        // The desktop's button: small radius, not a 16 dp card.
        shape = RoundedCornerShape(OpenCodeRadius.fill),
        border = border,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            // The progress state keeps its fill: the label says what's happening.
            disabledContainerColor = container,
            disabledContentColor = content,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        content = label,
    )
}

/**
 * The pairing buttons' label. Semibold, a step above the desktop's medium button text: on these
 * full-width buttons, the screen's two main actions, medium read as softer than the copy above.
 */
private val PairingActionLabel get() = OpenCodeTextStyles.callout.copy(fontWeight = FontWeight.SemiBold)

/** What a new user needs on their computer before the scan can work. */
@Composable
private fun SetupSteps(store: StoreOf<AppFeature.State, AppFeature.Action>) {
    val linkColor = OpenCodeTheme.colors.secondaryText
    val otherWaysToInstall = stringResource(R.string.app_setup_other_ways_to_install)
    Column {
        Text(
            stringResource(R.string.app_setup_instructions, ThisDevice.casualName),
            style = OpenCodeTextStyles.body,
            modifier = Modifier.padding(vertical = 16.dp),
        )
        HorizontalDivider()
        SetupSection(stringResource(R.string.app_setup_install_title)) {
            CopyCommand("curl -fsSL https://opencode.ai/v2/install | bash") { store.send(AppFeature.Action.CopyTextTapped(it)) }
            Text(
                buildAnnotatedString {
                    withLink(LinkAnnotation.Url("https://opencode.ai/download", TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline)))) {
                        append(otherWaysToInstall)
                    }
                },
                style = OpenCodeTextStyles.footnote,
                color = linkColor,
                modifier = Modifier.heightIn(min = 48.dp).padding(vertical = 14.dp),
            )
        }
        HorizontalDivider()
        SetupSection(stringResource(R.string.app_setup_terminal_title)) {
            CopyCommand("opencode pair --remote") { store.send(AppFeature.Action.CopyTextTapped(it)) }
            Text(stringResource(R.string.app_setup_terminal_detail), style = OpenCodeTextStyles.footnote, color = OpenCodeTheme.colors.secondaryText)
            PasteRow(store)
        }
    }
}

@Composable
private fun SetupSection(title: String, detail: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = OpenCodeTextStyles.headline, modifier = Modifier.semantics { heading() })
        detail()
    }
}

/** Paste for a copied pairing link; a failed paste says why in place of the prompt. */
@Composable
private fun PasteRow(store: StoreOf<AppFeature.State, AppFeature.Action>) {
    val pasteError by store.observe { it.pasteError }
    val progress by store.observe { it.connectionProgressLabel }
    val prompt: @Composable (Modifier) -> Unit = { modifier ->
        Text(
            pasteError ?: stringResource(R.string.app_setup_paste_prompt),
            style = OpenCodeTextStyles.footnote,
            color = if (pasteError != null) OpenCodeTheme.colors.warning else OpenCodeTheme.colors.secondaryText,
            // Paste's failure replaces the prompt and is read out; the prompt itself isn't.
            modifier = modifier.semantics { if (pasteError != null) liveRegion = LiveRegionMode.Polite },
        )
    }
    val button: @Composable () -> Unit = {
        OpenCodeSecondaryButton(onClick = { store.send(AppFeature.Action.PastePairingCodeTapped) }, enabled = progress == null) {
            OpenCodeIcon(SFSymbol.DocOnClipboard, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.app_setup_paste), maxLines = 1)
        }
    }
    if (isAccessibilitySize()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            prompt(Modifier)
            button()
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            prompt(Modifier.weight(1f, fill = false))
            button()
        }
    }
}

/** A terminal command shown in the setup steps; tapping copies it. */
@Composable
private fun CopyCommand(command: String, onCopy: (String) -> Unit) {
    var copied by remember { mutableStateOf(false) }
    val reduceMotion = rememberReduceMotion()
    val copyDescription = stringResource(R.string.app_setup_copy_command_description, command)
    val copiedState = stringResource(CoreR.string.common_copied)
    LaunchedEffect(copied) {
        if (!copied) return@LaunchedEffect
        // The Copied checkmark's dwell.
        delay(1_500)
        copied = false
    }
    Surface(
        onClick = {
            onCopy(command)
            copied = true
        },
        // The desktop's shell block: a clearly filled box, hairline border.
        shape = RoundedCornerShape(OpenCodeRadius.fill),
        color = OpenCodeSurfaces.commandFill,
        // Explicit: the fill matches `surfaceVariant`, whose content colour is the secondary text.
        contentColor = OpenCodeTheme.colors.text,
        border = OpenCodeSurfaces.border,
        // The label stands in for the visible command (cleared below), read once as a button.
        modifier = Modifier.heightIn(min = 48.dp).semantics {
            contentDescription = copyDescription
            stateDescription = if (copied) copiedState else ""
            role = Role.Button
        },
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                command,
                modifier = Modifier.weight(1f, fill = false).clearAndSetSemantics {},
                // The desktop's mono; no contextual alternates, so a command to retype is drawn as typed.
                style = OpenCodeTextStyles.footnote.copy(
                    fontFamily = OpenCodeFonts.monospace,
                    lineHeight = 20.sp,
                    fontFeatureSettings = "calt 0",
                ),
            )
            // The copied checkmark swaps in and back on the swap role (a cross-fade under reduce motion).
            Crossfade(copied, animationSpec = MotionRole.Swap.animation(reduceMotion = reduceMotion), label = "copy-command") { isCopied ->
                OpenCodeIcon(
                    if (isCopied) SFSymbol.Checkmark else SFSymbol.SquareOnSquare,
                    contentDescription = null,
                    tint = OpenCodeTheme.colors.secondaryText,
                    size = 16.dp,
                )
            }
        }
    }
}

@Composable
private fun SavedServerList(store: StoreOf<AppFeature.State, AppFeature.Action>, servers: List<OpenCodeServer>) {
    val connectingID by store.observe { it.connectingServerID }
    val progress by store.observe { it.connectionProgressLabel }
    val lastUsedID by store.observe { it.savedServer.value?.persistenceID }
    var pendingRemoval by remember { mutableStateOf<OpenCodeServer?>(null) }
    val accessibilitySize = isAccessibilitySize()
    val connectingDescription = stringResource(R.string.app_servers_connecting_description)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        servers.forEach { server ->
            androidx.compose.runtime.key(server.persistenceID) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = OpenCodeTheme.colors.text,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.weight(1f)
                                .clickable { store.send(AppFeature.Action.ServerTapped(server)) }
                                .heightIn(min = 72.dp)
                                .padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OpenCodeIcon(
                                if (server.isSecure) SFSymbol.LockFill else SFSymbol.ServerRack,
                                contentDescription = null,
                                tint = OpenCodeTheme.colors.secondaryText,
                                size = 20.dp,
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    server.displayName,
                                    style = OpenCodeTextStyles.headline,
                                    maxLines = if (accessibilitySize) 2 else 1,
                                    overflow = TextOverflow.MiddleEllipsis,
                                )
                                val detail = if (connectingID == server.id && progress != null) progress
                                else serverRowDetail(server, lastUsedID)
                                detail?.let {
                                    Text(
                                        it,
                                        style = OpenCodeTextStyles.footnote,
                                        color = OpenCodeTheme.colors.secondaryText,
                                        maxLines = if (accessibilitySize) 2 else 1,
                                        overflow = TextOverflow.MiddleEllipsis,
                                    )
                                }
                            }
                        }
                        if (connectingID == server.id) {
                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(
                                    Modifier.size(20.dp).semantics { contentDescription = progress ?: connectingDescription },
                                    strokeWidth = 2.dp,
                                )
                            }
                        } else {
                            OpenCodeIconButton(
                                symbol = SFSymbol.Pencil,
                                contentDescription = stringResource(R.string.app_servers_edit_description, server.displayName),
                                onClick = { store.send(AppFeature.Action.EditServerTapped(server)) },
                                style = OpenCodeIconButtonStyle.Standard,
                            )
                            OpenCodeIconButton(
                                symbol = SFSymbol.Trash,
                                contentDescription = stringResource(R.string.app_servers_remove_description, server.displayName),
                                onClick = { pendingRemoval = server },
                                style = OpenCodeIconButtonStyle.Standard,
                            )
                        }
                    }
                }
            }
        }
    }
    pendingRemoval?.let { server ->
        OpenCodeAlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(stringResource(R.string.app_servers_remove_confirm_title, server.displayName)) },
            text = { Text(stringResource(R.string.app_servers_remove_confirm_message, ThisDevice.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRemoval = null
                        store.send(AppFeature.Action.ServerDeleted(server))
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = OpenCodeTheme.colors.destructive),
                ) { Text(stringResource(R.string.app_servers_remove_confirm)) }
            },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text(stringResource(CoreR.string.common_cancel)) } },
        )
    }
}

/** The address when the name doesn't say it, a username other than the default, and whether it's the last used. */
private fun serverRowDetail(server: OpenCodeServer, lastUsedID: String?): String? {
    val parts = mutableListOf<String>()
    if (server.displayName != server.host) parts += server.host
    if (server.username != "opencode") parts += server.username
    if (lastUsedID == server.persistenceID) parts += localized(R.string.app_servers_last_used)
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}

/** Local messages format commands as `code`; render that instead of backticks. */
private fun formatted(message: String): AnnotatedString = buildAnnotatedString {
    val parts = message.split('`')
    // An unmatched backtick stays literal.
    if (parts.size % 2 == 0) {
        append(message)
        return@buildAnnotatedString
    }
    parts.forEachIndexed { index, part ->
        if (index % 2 == 1) withStyle(SpanStyle(fontFamily = OpenCodeFonts.monospace)) { append(part) } else append(part)
    }
}

private fun errorTitle(phase: AppFeature.FailureContext.Phase?, context: AppFeature.FailureContext?): String = when (phase) {
    AppFeature.FailureContext.Phase.Pair -> localized(R.string.app_error_title_pair)
    AppFeature.FailureContext.Phase.LoadSessions -> localized(R.string.app_error_title_load_sessions)
    else -> localized(R.string.app_error_title_connect, context?.friendlyName ?: localized(R.string.app_server_fallback_name))
}

/**
 * [announces]: TalkBack reads the title and message as they appear (live regions), where the card
 * comes into view; the New Server sheet announces its own (see `ServerFormSheet`).
 */
@Composable
private fun ConnectionErrorCard(store: StoreOf<AppFeature.State, AppFeature.Action>, message: String, announces: Boolean) {
    val phase by store.observe { it.errorPhase }
    val context by store.observe { it.errorContext }
    val needsLocalNetwork by store.observe { it.errorNeedsLocalNetwork }
    var didCopy by remember(message) { mutableStateOf(false) }
    val title = errorTitle(phase, context)
    val secondary = OpenCodeTheme.colors.secondaryText
    val readsOut = Modifier.semantics { if (announces) liveRegion = LiveRegionMode.Polite }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = OpenCodeTheme.colors.text,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics(mergeDescendants = true) { heading() }.then(readsOut),
            ) {
                OpenCodeIcon(SFSymbol.ExclamationmarkTriangleFill, contentDescription = null, tint = OpenCodeTheme.colors.warning, size = 20.dp)
                Text(title, style = OpenCodeTextStyles.headline, color = OpenCodeTheme.colors.warning)
            }
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(formatted(message), style = OpenCodeTextStyles.subheadline, modifier = readsOut)
                    context?.takeIf { it.details.isNotEmpty() }?.let { Text(it.detailText, style = OpenCodeTextStyles.subheadline) }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        context?.let {
                            Text(stringResource(R.string.app_error_phase, it.phase.title), style = OpenCodeTextStyles.footnote, color = secondary)
                            Text(stringResource(R.string.app_error_server, it.serverLabel), style = OpenCodeTextStyles.footnote, color = secondary)
                            Text(stringResource(R.string.app_error_attempt, it.attempt), style = OpenCodeTextStyles.footnote, color = secondary)
                        }
                        Text("OpenCode ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = OpenCodeTextStyles.footnote, color = secondary)
                    }
                }
            }
            if (needsLocalNetwork) {
                OpenCodePrimaryButton(onClick = { store.send(AppFeature.Action.LocalNetworkSettingsTapped) }) { Text(stringResource(CoreR.string.common_open_settings)) }
            }
            OpenCodeSecondaryButton(onClick = {
                store.state.errorReport(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toString(), java.time.Instant.now())?.let {
                    store.send(AppFeature.Action.CopyTextTapped(it))
                    didCopy = true
                }
            }) {
                OpenCodeIcon(if (didCopy) SFSymbol.Checkmark else SFSymbol.SquareOnSquare, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (didCopy) stringResource(CoreR.string.common_copied) else stringResource(R.string.app_error_copy_report))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerFormSheet(store: StoreOf<AppFeature.State, AppFeature.Action>) {
    val state by store.observe { it }
    var revealsPassword by rememberSaveable { mutableStateOf(false) }
    val passwordFocus = remember { FocusRequester() }
    val eyeFocus = remember { FocusRequester() }
    var isPasswordFocused by remember { mutableStateOf(false) }
    var refocusesPassword by remember { mutableStateOf(false) }
    var passwordValue by remember { mutableStateOf(TextFieldValue(state.password, TextRange(state.password.length))) }
    val scroll = rememberScrollState()
    val isConnectDisabled = state.connectionProgressLabel != null || state.serverURL.isBlank() || state.headerProblem != null
    val attempt = state.errorContext?.attempt
    val reduceMotion = rememberReduceMotion()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // TalkBack starts on the title, not the scrim or the drag handle: one move once the sheet has
    // opened, after TalkBack's own first focus in the new window, which would otherwise land after
    // it.
    var hasOpened by remember { mutableStateOf(false) }
    LaunchedEffect(sheetState.currentValue) { if (sheetState.currentValue == SheetValue.Expanded) hasOpened = true }
    val titleFocus = rememberAccessibilityFocusRequest(Unit.takeIf { hasOpened })
    ModalBottomSheet(
        onDismissRequest = { store.send(AppFeature.Action.CancelEditingTapped) },
        sheetState = sheetState,
        dragHandle = { OpenCodeSheetHandle() },
        shape = OpenCodeShape.sheetTop,
    ) {
        // Its password and headers, out of problem reports and voice screenshots.
        HidesFromScreenshots()
        // Here only the keyboard goes (the sheet window's controller): a cleared focus would fall
        // to the drag handle, and TalkBack's focus with it.
        val keyboard = LocalSoftwareKeyboardController.current
        val dismissKeyboard = { keyboard?.hide() }
        // TalkBack takes the scroll up to the error for the user's and leaves Connect for whatever
        // is then in view (QA OB-B-01); VoiceOver stays on Connect. So while TalkBack is on
        // Connect the form doesn't scroll, the error (out of view) is announced, and once the
        // keyboard has gone and the card has pushed the form down, TalkBack is put back on
        // Connect if that moved it off. The announcement comes last, or a focus move cuts it off.
        val connectFocus = remember { AccessibilityFocusTarget() }
        val connectInView = remember { BringIntoViewRequester() }
        var staysOnConnect by remember { mutableStateOf(false) }
        val announcer = remember { dependency(AccessibilityAnnouncer) }
        val attemptAtOpen = remember { attempt }
        val density = LocalDensity.current
        val imeBottom by rememberUpdatedState(WindowInsets.ime.getBottom(density))
        LaunchedEffect(attempt) {
            if (attempt == null || attempt == attemptAtOpen) return@LaunchedEffect
            dismissKeyboard()
            if (staysOnConnect) {
                staysOnConnect = false
                snapshotFlow { imeBottom }.first { it == 0 }
                // TalkBack's own move after the form shifts lands within this.
                delay(TALKBACK_SETTLE_MS)
                if (!connectFocus.isFocused) {
                    connectInView.bringIntoView()
                    delay(TALKBACK_SETTLE_MS)
                    connectFocus.focus()
                }
            } else {
                // Back to the top on the layout role, so a failed attempt's error is seen.
                scroll.animateScrollTo(0, MotionRole.Layout.animation(reduceMotion = reduceMotion))
            }
            val failed = store.state
            failed.errorMessage?.let { announcer.announce(localized(R.string.app_error_announcement, errorTitle(failed.errorPhase, failed.errorContext), it.replace("`", "")), false) }
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
            Text(
                if (state.editingSavedServer == null) stringResource(R.string.app_server_form_new_title) else stringResource(R.string.app_server_form_edit_title),
                style = OpenCodeTextStyles.headline,
                color = OpenCodeTheme.colors.text,
                modifier = Modifier.accessibilityFocusRequest(titleFocus).semantics { heading() },
            )
            TextButton(
                onClick = { store.send(AppFeature.Action.CancelEditingTapped) },
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
            ) { Text(stringResource(CoreR.string.common_cancel)) }
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(scroll).imePadding().navigationBarsPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.errorMessage?.let { ConnectionErrorCard(store, it, announces = false) }
            OpenCodeTextField(
                value = state.serverName,
                onValueChange = { store.send(AppFeature.Action.ServerNameChanged(it)) },
                label = stringResource(R.string.app_server_form_name),
                // Blank keeps the name the address gives, shown as the prompt.
                placeholder = state.credentialNamePrompt,
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OpenCodeTextField(
                value = state.serverURL,
                onValueChange = { store.send(AppFeature.Action.ServerURLChanged(it)) },
                label = stringResource(R.string.app_server_form_address),
                placeholder = "http://192.168.1.20:4096",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OpenCodeTextField(
                value = state.username,
                onValueChange = { store.send(AppFeature.Action.UsernameChanged(it)) },
                label = stringResource(R.string.app_server_form_username),
                placeholder = "opencode",
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            // A new field each time the password is shown or hidden: Compose keeps a field's first
            // password flag (foundation 1.12's `CoreTextFieldSemanticsModifierNode` never stores a
            // new one), so TalkBack would read a shown password as hidden. Input focus carries over
            // to the new field; the eye sits over it, outside the swap, so TalkBack's focus stays
            // on it.
            Box {
                androidx.compose.runtime.key(revealsPassword) {
                    OpenCodeTextField(
                        // Its own value, so the new field keeps the caret where it was.
                        value = if (passwordValue.text == state.password) passwordValue else TextFieldValue(state.password, TextRange(state.password.length)),
                        onValueChange = {
                            passwordValue = it
                            if (it.text != state.password) store.send(AppFeature.Action.PasswordChanged(it.text))
                        },
                        label = stringResource(R.string.app_server_form_password),
                        placeholder = stringResource(R.string.app_server_form_password),
                        singleLine = true,
                        visualTransformation = if (revealsPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (revealsPassword) KeyboardType.Text else KeyboardType.Password,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Go,
                        ),
                        keyboardActions = KeyboardActions(onGo = {
                            if (!isConnectDisabled) {
                                dismissKeyboard()
                                store.send(AppFeature.Action.ConnectButtonTapped)
                            }
                        }),
                        trailingIcon = { Spacer(Modifier.size(OpenCodeMinTapTarget)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(passwordFocus)
                            .onFocusChanged { isPasswordFocused = it.isFocused },
                    )
                }
                // The label sits above the field: the eye centres on the field's 48 dp row.
                Box(Modifier.matchParentSize(), contentAlignment = Alignment.BottomEnd) {
                    OpenCodeIconButton(
                        symbol = if (revealsPassword) SFSymbol.EyeSlash else SFSymbol.Eye,
                        contentDescription = if (revealsPassword) stringResource(R.string.app_server_form_hide_password_description) else stringResource(R.string.app_server_form_show_password_description),
                        onClick = {
                            refocusesPassword = isPasswordFocused
                            // Through the eye, where TalkBack's focus already is: the old field's focus
                            // would otherwise fall to the drag handle, and TalkBack's with it.
                            if (isPasswordFocused) eyeFocus.requestFocus()
                            revealsPassword = !revealsPassword
                        },
                        style = OpenCodeIconButtonStyle.Standard,
                        modifier = Modifier.focusRequester(eyeFocus),
                    )
                }
            }
            LaunchedEffect(revealsPassword) {
                if (!refocusesPassword) return@LaunchedEffect
                refocusesPassword = false
                passwordFocus.requestFocus()
            }
            HeaderFields(store, state.headers, state.headerProblem)
            ConnectButton(
                label = state.connectionProgressLabel ?: if (state.editingSavedServer == null) stringResource(R.string.app_server_form_connect) else stringResource(R.string.app_server_form_save_and_connect),
                inProgress = state.connectionProgressLabel != null,
                enabled = !isConnectDisabled,
                modifier = Modifier.bringIntoViewRequester(connectInView).accessibilityFocusTarget(connectFocus),
            ) {
                staysOnConnect = connectFocus.isFocused
                dismissKeyboard()
                store.send(AppFeature.Action.ConnectButtonTapped)
            }
            Text(
                formatted(editHint(state.editingSavedServer)),
                style = OpenCodeTextStyles.footnote,
                color = OpenCodeTheme.colors.secondaryText,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** How long TalkBack takes to move its focus after a scroll or a layout change it reacts to. */
private const val TALKBACK_SETTLE_MS = 300L

private fun editHint(editing: OpenCodeServer?): String {
    if (editing == null) {
        return localized(R.string.app_server_form_hint_new)
    }
    return if (editing.usesPairingToken) {
        localized(R.string.app_server_form_hint_edit_paired)
    } else {
        localized(R.string.app_server_form_hint_edit)
    }
}

/** Label-coloured when ready, a quiet fill while disabled, an arrow at the end. */
@Composable
private fun ConnectButton(label: String, inProgress: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = OpenCodeTheme.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.text,
            contentColor = colors.canvas,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            disabledContentColor = colors.secondaryText,
        ),
        // The desktop's contrast button: small radius, medium label.
        shape = RoundedCornerShape(OpenCodeRadius.fill),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        if (inProgress) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.secondaryText)
            Spacer(Modifier.width(10.dp))
        }
        Text(label, style = PairingActionLabel)
        Spacer(Modifier.weight(1f))
        OpenCodeIcon(SFSymbol.ArrowRight, contentDescription = null)
    }
}

/** Custom headers: rows of name and value, sent with every request. */
@Composable
private fun HeaderFields(
    store: StoreOf<AppFeature.State, AppFeature.Action>,
    headers: List<OpenCodeServer.Header>,
    headerProblem: String?,
) {
    val accessibilitySize = isAccessibilitySize()
    fun update(index: Int, transform: (OpenCodeServer.Header) -> OpenCodeServer.Header) {
        // A row edit that can't land on a row just removed.
        val current = store.state.headers
        if (index !in current.indices) return
        store.send(AppFeature.Action.HeadersChanged(current.toMutableList().also { it[index] = transform(it[index]) }))
    }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(stringResource(R.string.app_server_form_headers_title), style = OpenCodeTextStyles.caption1.copy(fontWeight = FontWeight.SemiBold), color = OpenCodeTheme.colors.secondaryText)
        headers.forEachIndexed { index, header ->
            val nameField: @Composable (Modifier) -> Unit = { modifier ->
                val nameDescription = stringResource(R.string.app_server_form_header_name_description, index + 1)
                OpenCodeTextField(
                    value = header.name,
                    onValueChange = { value -> update(index) { it.copy(name = value) } },
                    placeholder = stringResource(R.string.app_server_form_header_name),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    modifier = modifier.semantics { contentDescription = nameDescription },
                )
            }
            val valueField: @Composable (Modifier) -> Unit = { modifier ->
                val valueDescription = stringResource(R.string.app_server_form_header_value_description, index + 1)
                OpenCodeTextField(
                    value = header.value,
                    onValueChange = { value -> update(index) { it.copy(value = value) } },
                    placeholder = stringResource(R.string.app_server_form_header_value),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    modifier = modifier.semantics { contentDescription = valueDescription },
                )
            }
            val removeButton: @Composable () -> Unit = {
                androidx.compose.material3.IconButton(
                    onClick = {
                        val current = store.state.headers
                        if (index in current.indices) store.send(AppFeature.Action.HeadersChanged(current.filterIndexed { i, _ -> i != index }))
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    OpenCodeIcon(SFSymbol.MinusCircleFill, contentDescription = stringResource(R.string.app_server_form_header_remove_description, index + 1), tint = OpenCodeTheme.colors.destructive)
                }
            }
            if (accessibilitySize) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        nameField(Modifier.weight(1f))
                        removeButton()
                    }
                    valueField(Modifier.fillMaxWidth())
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    nameField(Modifier.weight(1f))
                    valueField(Modifier.weight(1f))
                    removeButton()
                }
            }
        }
        TextButton(
            onClick = { store.send(AppFeature.Action.HeadersChanged(store.state.headers + OpenCodeServer.Header("", ""))) },
            colors = ButtonDefaults.textButtonColors(contentColor = OpenCodeTheme.colors.secondaryText),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            OpenCodeIcon(SFSymbol.Plus, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.app_server_form_header_add), style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium))
        }
        if (headerProblem != null) {
            OpenCodeErrorText(headerProblem)
        } else {
            Text(stringResource(R.string.app_server_form_headers_detail), style = OpenCodeTextStyles.footnote, color = OpenCodeTheme.colors.secondaryText)
        }
    }
}

/**
 * `.sheet(isPresented: $store.isScanningPairingCode) { PairingScannerView(…) }`: settings'
 * CameraX + ZXing scanner, full screen, with its own no-camera / access-off / paste fallbacks.
 * Back dismisses it like Cancel.
 */
@Composable
private fun PairingScannerPresenter(store: StoreOf<AppFeature.State, AppFeature.Action>) {
    Dialog(
        onDismissRequest = { store.send(AppFeature.Action.ScannerDismissed) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        HidesFromScreenshots()
        PairingScannerView(
            onScanned = { store.send(AppFeature.Action.PairingCodeScanned(it)) },
            onCancel = { store.send(AppFeature.Action.ScannerDismissed) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
