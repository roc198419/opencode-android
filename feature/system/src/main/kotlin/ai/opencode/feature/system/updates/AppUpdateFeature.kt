package ai.opencode.feature.system.updates

import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import kotlinx.coroutines.CancellationException

object AppUpdateSharedKeys {
    val automaticallyChecks = SharedKey.appStorage("app-update-checks", true)
    val lastCheckedAt = SharedKey.appStorage("app-update-last-check", 0L)
    val dismissedVersion = SharedKey.appStorage("app-update-dismissed-version", 0L)
}

class AppUpdateFeature : Reducer<AppUpdateFeature.State, AppUpdateFeature.Action> {
    enum class Phase { Idle, Checking, Current, Available, Downloading, Ready, Permission, Installing, Unsupported, Failed }

    data class State(
        val presented: Boolean = false,
        val automaticallyChecks: Boolean = true,
        val currentVersion: String = "",
        val phase: Phase = Phase.Idle,
        val release: AppUpdateRelease? = null,
        val downloaded: Boolean = false,
        val bytes: Long = 0,
        val error: AppUpdateError? = null,
        val generation: Int = 0,
    )

    sealed interface Action {
        data class Check(val manual: Boolean) : Action
        data class Checked(val generation: Int, val release: AppUpdateRelease?, val error: AppUpdateError? = null) : Action
        data class AutomaticallyChecksChanged(val enabled: Boolean) : Action
        data object Dismiss : Action
        data object Download : Action
        data object CancelDownload : Action
        data class Progress(val generation: Int, val bytes: Long) : Action
        data class Downloaded(val generation: Int, val error: AppUpdateError? = null) : Action
        data object Install : Action
        data class Installed(val result: AppUpdateClient.InstallResult?, val error: AppUpdateError? = null) : Action
        data object Resumed : Action
    }

    private enum class CancelID { Check, Download, Install }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = when (action) {
        is Action.Check -> {
            val client = dependency(AppUpdateClient)
            val installed = client.installed()
            if (!installed.supported) {
                if (action.manual) Reduced(state.copy(presented = true, phase = Phase.Unsupported)) else Reduced(state)
            } else if (state.phase in setOf(Phase.Checking, Phase.Downloading, Phase.Permission, Phase.Installing)) {
                Reduced(if (action.manual) state.copy(presented = true) else state)
            } else {
                val automatically = shared(AppUpdateSharedKeys.automaticallyChecks).value
                val now = dependency(DateClient).now().epochSecond
                val previous = shared(AppUpdateSharedKeys.lastCheckedAt).value
                if (!action.manual && (!automatically || now >= previous && previous > 0 && now - previous < 12 * 60 * 60)) {
                    Reduced(state)
                } else {
                    shared(AppUpdateSharedKeys.lastCheckedAt).set(now)
                    val next = state.copy(
                        presented = action.manual, automaticallyChecks = automatically, currentVersion = installed.versionName,
                        phase = Phase.Checking, error = null, generation = state.generation + 1,
                        release = null, downloaded = false, bytes = 0,
                    )
                    Reduced(next, Effect.run { send ->
                        try { send(Action.Checked(next.generation, client.check())) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { send(Action.Checked(next.generation, null, updateError(error, AppUpdateError.Check))) }
                    }.cancellable(CancelID.Check, cancelInFlight = true))
                }
            }
        }
        is Action.Checked -> {
            if (action.generation != state.generation || state.phase != Phase.Checking) Reduced(state)
            else {
                val show = state.presented || action.release?.let { it.versionCode != shared(AppUpdateSharedKeys.dismissedVersion).value } == true
                Reduced(state.copy(
                    presented = show, release = action.release, error = action.error,
                    phase = when { action.error != null -> Phase.Failed; action.release != null -> Phase.Available; else -> Phase.Current },
                ))
            }
        }
        is Action.AutomaticallyChecksChanged -> {
            shared(AppUpdateSharedKeys.automaticallyChecks).set(action.enabled)
            Reduced(state.copy(automaticallyChecks = action.enabled))
        }
        Action.Dismiss -> {
            state.release?.let { shared(AppUpdateSharedKeys.dismissedVersion).set(it.versionCode) }
            if (state.phase == Phase.Checking) Reduced(
                state.copy(presented = false, phase = Phase.Idle, generation = state.generation + 1), Effect.cancel(CancelID.Check),
            ) else Reduced(state.copy(presented = false))
        }
        Action.Download -> {
            val release = state.release
            if (release == null || state.phase == Phase.Downloading) Reduced(state)
            else {
                val client = dependency(AppUpdateClient)
                val generation = state.generation + 1
                Reduced(state.copy(phase = Phase.Downloading, error = null, bytes = 0, generation = generation), Effect.run { send ->
                    try {
                        client.download(release) { send(Action.Progress(generation, it)) }
                        send(Action.Downloaded(generation))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { send(Action.Downloaded(generation, updateError(error, AppUpdateError.Download))) }
                }.cancellable(CancelID.Download, cancelInFlight = true))
            }
        }
        Action.CancelDownload -> if (state.phase != Phase.Downloading) Reduced(state) else Reduced(
            state.copy(phase = Phase.Available, bytes = 0, generation = state.generation + 1), Effect.cancel(CancelID.Download),
        )
        is Action.Progress -> if (action.generation != state.generation || state.phase != Phase.Downloading) Reduced(state)
            else Reduced(state.copy(bytes = action.bytes.coerceIn(0, state.release?.apkSize ?: 0)))
        is Action.Downloaded -> if (action.generation != state.generation || state.phase != Phase.Downloading) Reduced(state)
            else Reduced(state.copy(phase = if (action.error == null) Phase.Ready else Phase.Failed, downloaded = action.error == null, error = action.error))
        Action.Install -> {
            val release = state.release
            if (release == null || !state.downloaded || state.phase == Phase.Installing) Reduced(state)
            else {
                val client = dependency(AppUpdateClient)
                Reduced(state.copy(phase = Phase.Installing, error = null), Effect.run { send ->
                    try { send(Action.Installed(client.install(release))) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { send(Action.Installed(null, updateError(error, AppUpdateError.Install))) }
                }.cancellable(CancelID.Install, cancelInFlight = true))
            }
        }
        is Action.Installed -> Reduced(state.copy(
            phase = when { action.error != null -> Phase.Failed; action.result == AppUpdateClient.InstallResult.PermissionRequired -> Phase.Permission; else -> Phase.Installing },
            error = action.error,
            downloaded = state.downloaded && action.error != AppUpdateError.Verification,
        ))
        Action.Resumed -> when (state.phase) {
            Phase.Permission -> if (dependency(AppUpdateClient).canInstall()) Reduced(state, Effect.send(Action.Install)) else Reduced(state.copy(phase = Phase.Ready))
            Phase.Installing -> Reduced(state.copy(phase = Phase.Ready))
            else -> Reduced(state)
        }
    }

    private fun updateError(error: Exception, fallback: AppUpdateError) = (error as? AppUpdateException)?.reason ?: fallback
}
