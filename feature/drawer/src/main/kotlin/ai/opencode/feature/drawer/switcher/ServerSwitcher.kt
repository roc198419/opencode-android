package ai.opencode.feature.drawer.switcher

import ai.opencode.core.strings.localized
import ai.opencode.feature.drawer.HomeConnectionFeature
import ai.opencode.feature.drawer.R
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.friendlyName
import java.text.Collator

// The rules the drawer's title (`switcher/ServerFlowTitle.kt`) reads everything it shows from,
// [DrawerTitle.Presentation] and the texts below. `OpenCodeServer.friendlyName` was moved down to
// `:server` (`OpenCodeServerFriendlyName.kt`): the title shows a server only by it, never by an
// address.

/**
 * Saved servers, most recent first, with the one in use even if it was never saved, and its live
 * copy (a rename shows at once).
 */
fun switchableServers(current: OpenCodeServer, saved: List<OpenCodeServer>): List<OpenCodeServer> {
    if (saved.any { it.persistenceID == current.persistenceID }) {
        return saved.map { if (it.persistenceID == current.persistenceID) current else it }
    }
    return listOf(current) + saved
}

/**
 * The menu's servers: [switchableServers] in a steady order, so the menu never moves as servers are
 * used.
 */
fun drawerTitleServers(current: OpenCodeServer, saved: List<OpenCodeServer>): List<OpenCodeServer> =
    switchableServers(current, saved).sortedWith { a, b -> localizedStandardCompare(a.friendlyName, b.friendlyName) }

/** Finder-like ordering: runs of digits compare by value, text by the locale's collator. */
internal fun localizedStandardCompare(a: String, b: String): Int {
    val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
    val left = chunks(a)
    val right = chunks(b)
    for (i in 0 until minOf(left.size, right.size)) {
        val x = left[i]
        val y = right[i]
        val result = if (x[0].isDigit() && y[0].isDigit()) {
            val byValue = x.trimStart('0').length.compareTo(y.trimStart('0').length)
            if (byValue != 0) byValue else x.trimStart('0').compareTo(y.trimStart('0'))
        } else {
            collator.compare(x, y)
        }
        if (result != 0) return result
    }
    return left.size.compareTo(right.size)
}

private fun chunks(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val out = ArrayList<String>()
    var start = 0
    for (i in 1..text.length) {
        if (i == text.length || text[i].isDigit() != text[start].isDigit()) {
            out += text.substring(start, i)
            start = i
        }
    }
    return out
}

/**
 * The drawer's title (`ServerFlowTitle`): always the server in use by its friendly name, never an
 * address. No status shows while all is well; a confirmed outage dims it.
 */
object DrawerTitle {
    const val TAG = "drawer-title"

    /** What the title shows for a server count and connection status. */
    data class Presentation(
        /** A confirmed outage of the server in use; not a reconnect or a switch. */
        val isDimmed: Boolean,
        val hasMenu: Boolean,
        val listsServers: Boolean,
        val offersTryAgain: Boolean,
        /** Rejected credentials: editing the server replaces Try Again. */
        val offersEdit: Boolean,
        /** The connection's status, never an address. */
        val issue: String?,
        val accessibilityValue: String,
        val accessibilityHint: String?,
    ) {
        companion object {
            operator fun invoke(
                serverCount: Int,
                status: HomeConnectionFeature.State.ConnectionStatus,
                credentialsRejected: Boolean = false,
                switchingTo: String? = null,
            ): Presentation {
                val rejected = status == HomeConnectionFeature.State.ConnectionStatus.disconnected && credentialsRejected
                val issue = when (status) {
                    HomeConnectionFeature.State.ConnectionStatus.connected -> null
                    HomeConnectionFeature.State.ConnectionStatus.reconnecting -> localized(R.string.drawer_server_reconnecting_state)
                    HomeConnectionFeature.State.ConnectionStatus.disconnected -> if (rejected) localized(R.string.drawer_server_sign_in_failed) else localized(R.string.drawer_server_offline)
                }
                val listsServers = serverCount > 1
                val offersTryAgain = status == HomeConnectionFeature.State.ConnectionStatus.disconnected && !rejected
                val offersEdit = rejected
                return Presentation(
                    isDimmed = status == HomeConnectionFeature.State.ConnectionStatus.disconnected && switchingTo == null,
                    hasMenu = true,
                    listsServers = listsServers,
                    offersTryAgain = offersTryAgain,
                    offersEdit = offersEdit,
                    issue = issue,
                    accessibilityValue = switchingTo?.let { localized(R.string.drawer_server_connecting_to_state, it) } ?: issue ?: "",
                    accessibilityHint = localized(R.string.drawer_server_show_servers_hint),
                )
            }

            /** From the view's inputs. */
            fun of(
                servers: List<OpenCodeServer>,
                status: HomeConnectionFeature.State.ConnectionStatus,
                switchingTo: OpenCodeServer?,
                credentialsRejected: Boolean = false,
            ): Presentation = Presentation(servers.size, status, credentialsRejected, switchingTo?.friendlyName)
        }
    }
}
