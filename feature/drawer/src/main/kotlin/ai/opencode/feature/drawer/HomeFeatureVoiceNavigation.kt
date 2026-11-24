package ai.opencode.feature.drawer

import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.reconcileInvariants

// Pending-subagent navigation, call retargeting and the covers a voice open
// (`android_voice_open_session`) clears on every mounted session.

fun HomeFeature.State.pushingPendingSubagent(reason: String? = null): HomeFeature.State {
    val child = pendingSubagentPush ?: return this
    if (child.parentID != sessionDetail?.session?.id) return this
    return copy(pendingSubagentPush = null).pushingSubagent(child, reason = reason)
}

/** The call follows the screen on top, as it follows a drawer switch. */
fun HomeFeature.State.retargetingCallToTop(reason: String): HomeFeature.State {
    val voice = sessionDetail?.voice ?: return this
    if (voice.phase.isTerminal) return this
    val topScreen = topDetail ?: return this
    val top = topScreen.session ?: return this
    if (voice.target.sessionID == top.id) return this
    return updatingDetail { detail ->
        val moved = detail.voice?.retarget(top, reason = reason)?.copy(viewing = topScreen.browser.viewing)
        detail.copy(voice = moved).reconcileInvariants(targetScreen = topScreen)
    }
}

/** A sheet presented by any mounted ancestor can cover the destination. */
internal fun HomeFeature.State.clearingSessionCovers(): HomeFeature.State =
    updatingDetail { it.clearingVoiceNavigationCovers() }
        .updatingSubagents { it.clearingVoiceNavigationCovers() }
        .syncingTranscriptCover()

private fun SessionFeature.State.clearingVoiceNavigationCovers(): SessionFeature.State =
    copy(browser = browser.close(), activePicker = null, folderBrowser = null, shellOutput = null, projectFiles = null)
