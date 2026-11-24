package ai.opencode.feature.settings.connection

import ai.opencode.core.ui.OpenCodeTextField
import ai.opencode.core.sharing.shared
import ai.opencode.core.ui.AlertTextFields
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.R as CoreR
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.settings.ConnectionStatus
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.SettingsButtonRow
import ai.opencode.feature.settings.SettingsDivider
import ai.opencode.feature.settings.SettingsFeature.SettingsDelegate
import ai.opencode.feature.settings.SettingsRowFrame
import ai.opencode.feature.settings.SettingsSection
import ai.opencode.feature.settings.SettingsText
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.friendlyName
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import ai.opencode.core.ui.OpenCodeAlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Saved servers, most recent first, with the one in use even if it was never saved, and its live
 * copy (a rename shows at once).
 */
internal fun switchableServers(current: OpenCodeServer, saved: List<OpenCodeServer>): List<OpenCodeServer> {
    if (saved.any { it.persistenceID == current.persistenceID }) {
        return saved.map { if (it.persistenceID == current.persistenceID) current else it }
    }
    return listOf(current) + saved
}

/**
 * Settings' saved servers, the one in use checked, a tap switches, and each has Rename, Edit
 * (address, password, headers) and Remove; Add Server pairs or takes an address. It lives on the
 * server page, which the connection card and the drawer's Manage Servers… open. The scanner and the
 * forms present over Settings (Home's), so Cancel returns here.
 *
 * @param status Home's connection: the server in use couldn't connect (a rejected password, an
 *   address that answers nothing), so its edit is offered outright.
 * @param offersEditCurrent false when the page above already offers its own edit (a refused password).
 * @param send Home's server actions.
 * @param onSwitch switching returns to the connection card, which shows it.
 */
@Composable
fun SettingsServersSection(
    current: OpenCodeServer,
    status: ConnectionStatus,
    connectionErrorMessage: String?,
    send: (SettingsDelegate) -> Unit,
    onSwitch: (OpenCodeServer) -> Unit,
    modifier: Modifier = Modifier,
    offersEditCurrent: Boolean = true,
) {
    val savedServers by remember { shared(ServerSharedKeys.savedServers).flow }.collectAsState()
    var renaming by remember { mutableStateOf<OpenCodeServer?>(null) }
    var removing by remember { mutableStateOf<OpenCodeServer?>(null) }
    // A steady order, so rows never move as servers are used.
    val servers = remember(current, savedServers) {
        switchableServers(current, savedServers).sortedWith { a, b -> localizedStandardCompare(a.friendlyName, b.friendlyName) }
    }

    SettingsSection(modifier, header = stringResource(R.string.settings_servers_header)) {
        servers.forEachIndexed { index, server ->
            if (index > 0) SettingsDivider()
            SettingsServerRow(
                server = server,
                isCurrent = server.persistenceID == current.persistenceID,
                onSwitch = onSwitch,
                onRename = { renaming = server },
                onEdit = { send(SettingsDelegate.EditServer(server)) },
                onRemove = { removing = server },
            )
        }
        if (offersEditCurrent && (connectionErrorMessage != null || status == ConnectionStatus.disconnected)) {
            SettingsDivider()
            SettingsButtonRow(
                stringResource(R.string.settings_servers_edit_server, current.friendlyName),
                onClick = { send(SettingsDelegate.EditServer(current)) },
                symbol = SFSymbol.ExclamationmarkTriangle,
            )
        }
        SettingsDivider()
        AddServerRow(send)
    }

    renaming?.let { server ->
        RenameServerDialog(
            server = server,
            onDismissRequest = { renaming = null },
            onRename = { send(SettingsDelegate.RenameServer(server, it)) },
        )
    }

    removing?.let { server ->
        OpenCodeAlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.settings_servers_remove_title, server.friendlyName)) },
            text = { Text(stringResource(R.string.settings_servers_remove_message, ai.opencode.core.ui.ThisDevice.name)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    send(SettingsDelegate.RemoveServer(server))
                }) { Text(stringResource(R.string.settings_servers_remove_server), color = OpenCodeTheme.colors.destructive) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(CoreR.string.common_cancel)) } },
        )
    }
}

@Composable
fun RenameServerDialog(server: OpenCodeServer, onDismissRequest: () -> Unit, onRename: (String) -> Unit) {
    var nameDraft by rememberSaveable(server.persistenceID) { mutableStateOf(server.name.orEmpty()) }
    OpenCodeAlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.settings_server_rename_title)) },
        text = {
            Column {
                Text(stringResource(R.string.settings_servers_rename_message, ai.opencode.core.ui.ThisDevice.name))
                OpenCodeTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    placeholder = server.friendlyName,
                    trailingIcon = AlertTextFields.clearButton(nameDraft) { nameDraft = "" },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onRename(nameDraft)
                onDismissRequest()
            }) { Text(stringResource(CoreR.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismissRequest) { Text(stringResource(CoreR.string.common_cancel)) } },
    )
}

/** One saved server: name and address, the current one checked; its options in a menu (and on long press). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SettingsServerRow(
    server: OpenCodeServer,
    isCurrent: Boolean,
    onSwitch: (OpenCodeServer) -> Unit,
    onRename: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuShown by remember { mutableStateOf(false) }
    val name = server.friendlyName
    val currentServerState = stringResource(R.string.settings_servers_current_server_state, server.host)
    val switchLabel = stringResource(R.string.settings_servers_switch_label)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .combinedClickable(
                    role = Role.Button,
                    onLongClick = { menuShown = true },
                    onClick = { if (!isCurrent) onSwitch(server) },
                )
                .clearAndSetSemantics {
                    contentDescription = name
                    stateDescription = if (isCurrent) currentServerState else server.host
                    if (!isCurrent) onClick(label = switchLabel) { onSwitch(server); true }
                }
                .heightIn(min = 52.dp)
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = SettingsText.title,
                    color = OpenCodeTheme.colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    server.host,
                    style = SettingsText.summary,
                    color = OpenCodeTheme.colors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
            if (isCurrent) {
                OpenCodeIcon(
                    SFSymbol.Checkmark,
                    contentDescription = null,
                    size = 20.dp,
                    tint = OpenCodeTheme.colors.selection,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
        Box(Modifier.padding(end = 4.dp)) {
            IconButton(onClick = { menuShown = true }, modifier = Modifier.size(48.dp)) {
                OpenCodeIcon(SFSymbol.EllipsisCircle, contentDescription = stringResource(R.string.settings_servers_options_description, name))
            }
            DropdownMenu(expanded = menuShown, onDismissRequest = { menuShown = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.settings_servers_rename_menu)) },
                    leadingIcon = { OpenCodeIcon(SFSymbol.Pencil, contentDescription = null) },
                    onClick = {
                        menuShown = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.settings_servers_edit_menu)) },
                    leadingIcon = { OpenCodeIcon(SFSymbol.SliderHorizontal3, contentDescription = null) },
                    onClick = {
                        menuShown = false
                        onEdit()
                    },
                )
                // The server in use can't be removed; switch away first.
                if (!isCurrent) {
                    val red = OpenCodeTheme.colors.destructive
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_servers_remove_menu), color = red) },
                        leadingIcon = { OpenCodeIcon(SFSymbol.Trash, contentDescription = null, tint = red) },
                        onClick = {
                            menuShown = false
                            onRemove()
                        },
                    )
                }
            }
        }
    }
}

/** Add Server: a menu of Scan Pairing Code and Enter Address. */
@Composable
private fun AddServerRow(send: (SettingsDelegate) -> Unit) {
    var menuShown by remember { mutableStateOf(false) }
    Box {
        SettingsRowFrame(onClick = { menuShown = true }) {
            Text(stringResource(R.string.settings_servers_add_server), style = SettingsText.title, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        }
        DropdownMenu(expanded = menuShown, onDismissRequest = { menuShown = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_servers_scan_pairing_code)) },
                leadingIcon = { OpenCodeIcon(SFSymbol.QrcodeViewfinder, contentDescription = null) },
                onClick = {
                    menuShown = false
                    send(SettingsDelegate.PairServer)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_servers_enter_address)) },
                leadingIcon = { OpenCodeIcon(SFSymbol.Keyboard, contentDescription = null) },
                onClick = {
                    menuShown = false
                    send(SettingsDelegate.AddServer)
                },
            )
        }
    }
}

/**
 * Foundation's `localizedStandardCompare`: case- and diacritic-insensitive, numbers compared by value
 * (“Mac 2” before “Mac 10”). (Was in the removed `servers/ServerTitleFeature.kt`; the drawer's
 * server menu keeps its own copy.)
 */
private fun localizedStandardCompare(a: String, b: String): Int {
    val collator = java.text.Collator.getInstance().apply { strength = java.text.Collator.PRIMARY }
    val left = chunks(a)
    val right = chunks(b)
    for (i in 0 until minOf(left.size, right.size)) {
        val x = left[i]
        val y = right[i]
        val result = if (x.first().isDigit() && y.first().isDigit()) {
            x.trimStart('0').length.compareTo(y.trimStart('0').length).takeIf { it != 0 } ?: x.trimStart('0').compareTo(y.trimStart('0'))
        } else {
            collator.compare(x, y)
        }
        if (result != 0) return result
    }
    return left.size.compareTo(right.size)
}

private fun chunks(text: String): List<String> {
    val out = ArrayList<String>()
    val current = StringBuilder()
    for (c in text) {
        if (current.isNotEmpty() && current.last().isDigit() != c.isDigit()) {
            out += current.toString()
            current.clear()
        }
        current.append(c)
    }
    if (current.isNotEmpty()) out += current.toString()
    return out
}
