package ai.opencode.server.live

import ai.opencode.server.model.ProjectFilePaths

import ai.opencode.core.strings.localized
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.R
import ai.opencode.server.internal.list
import ai.opencode.server.internal.obj
import ai.opencode.server.internal.req
import ai.opencode.server.internal.percentEncodePath
import ai.opencode.server.model.OpenCodeAccount
import ai.opencode.server.model.OpenCodeAgentMention
import ai.opencode.server.model.OpenCodeAutocompleteAgent
import ai.opencode.server.model.OpenCodeAutocompleteCommand
import ai.opencode.server.model.OpenCodeAutocompleteFile
import ai.opencode.server.model.OpenCodeAutocompleteMentions
import ai.opencode.server.model.OpenCodeAutocompleteSkill
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeMCPServer
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodeOAuthAttempt
import ai.opencode.server.model.OpenCodeOAuthStatus
import ai.opencode.server.model.OpenCodeServerCapabilities
import ai.opencode.server.model.OpenCodeSkillMention
import ai.opencode.server.retry.RequestIdempotency
import ai.opencode.server.retry.RetryPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import java.net.URI
import java.text.Collator
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

// Composer autocomplete.

internal suspend fun requestSkills(server: OpenCodeServer, directory: String?): List<OpenCodeAutocompleteSkill> =
    request<DataPayload<List<OpenCodeAutocompleteSkill>>>("/api/skill", query = locationQuery(directory), timeout = 20.seconds, server = server).data

internal suspend fun requestAgents(server: OpenCodeServer, directory: String?): List<OpenCodeAutocompleteAgent> =
    request<DataPayload<List<OpenCodeAutocompleteAgent>>>("/api/agent", query = locationQuery(directory), timeout = 20.seconds, server = server)
        .data.filter { !it.hidden && it.mode != "primary" }

internal suspend fun requestPrimaryAgents(server: OpenCodeServer, directory: String?): List<OpenCodeAutocompleteAgent> =
    OpenCodeAutocompleteAgent.choosable(
        request<DataPayload<List<OpenCodeAutocompleteAgent>>>("/api/agent", query = locationQuery(directory), timeout = 20.seconds, server = server).data,
    )

@Serializable private data class AgentBody(val agent: String)
@Serializable private data class ModelBody(val model: OpenCodeModelRef)

internal suspend fun requestSwitchAgent(server: OpenCodeServer, sessionID: String, agent: String) {
    perform(
        "/api/session/$sessionID/agent", method = "POST", body = encodedBody(AgentBody(agent)),
        idempotency = RequestIdempotency.Idempotent, server = server,
    )
}

internal suspend fun requestCommands(server: OpenCodeServer, directory: String?): List<OpenCodeAutocompleteCommand> =
    request<DataPayload<List<OpenCodeAutocompleteCommand>>>("/api/command", query = locationQuery(directory), timeout = 20.seconds, server = server).data

internal suspend fun requestFindFiles(server: OpenCodeServer, directory: String, query: String, limit: Int): List<OpenCodeAutocompleteFile> {
    val items = locationQuery(directory) + listOf("query" to query, "type" to "file", "limit" to limit.toString())
    val response = perform("/api/fs/find", query = items, timeout = 15.seconds, server = server)
    val entries = decoded(response.data) { it.obj().req("data") { d -> d.list(::decodeFindEntry) } }
    return entries.map { path ->
        val absolute = ProjectFilePaths.resolve(directory, path) ?: path
        val relative = ProjectFilePaths.relative(directory, absolute) ?: path.removePrefix("/")
        OpenCodeAutocompleteFile(relativePath = relative, absolutePath = absolute, directory = directory)
    }
}

@Serializable
private data class CommandBody(
    val name: String,
    val text: String,
    val files: List<PromptBody.File>? = null,
    val agents: List<OpenCodeAgentMention>? = null,
    val skills: List<OpenCodeSkillMention>? = null,
    val delivery: String? = null,
)

internal suspend fun requestSendCommand(
    server: OpenCodeServer,
    sessionID: String,
    name: String,
    text: String,
    mentions: OpenCodeAutocompleteMentions,
    delivery: OpenCodeDelivery,
) {
    val mentionFiles = mentions.files.map { PromptBody.File(it.uri, it.name, it.description, it.mention) }
    val body = encodedBody(
        CommandBody(
            name = name,
            text = text,
            files = mentionFiles.ifEmpty { null },
            agents = mentions.agents.ifEmpty { null },
            skills = mentions.skills.ifEmpty { null },
            delivery = delivery.rawValue,
        ),
        "command",
    )
    perform(
        "/api/session/$sessionID/command", method = "POST", body = body, timeout = 30.seconds,
        idempotency = RequestIdempotency.Unsafe, retry = RetryPolicy.send, server = server,
    )
}

internal suspend fun requestModelCatalog(server: OpenCodeServer, directory: String?): OpenCodeModelCatalog {
    // Catalogs are directory-sensitive and large: share decoded results between features.
    val key = "${server.persistenceID}|${directory ?: ""}"
    OpenCodeReadCache.shared.modelCatalog(key)?.let { return it }
    // V2 ignores a bare `directory`; one catalog per opened session keeps the location set bounded.
    val query = locationQuery(directory)
    val catalog = coroutineScope {
        // Older V2 servers do not expose the default-model route; keep the catalog usable there.
        val defaultPayload = async {
            try {
                request<DataPayload<ModelInfoPayload?>>("/api/model/default", query = query, timeout = LOCATION_BOOT_TIMEOUT, server = server)
            } catch (_: OpenCodeClientError) { null }
        }
        val payload = request<DataPayload<List<ModelInfoPayload>>>("/api/model", query = query, timeout = LOCATION_BOOT_TIMEOUT, server = server)
        val models = payload.data.filter { it.enabled && it.status == "active" }.map { it.model }
        OpenCodeModelCatalog(models, defaultPayload.await()?.data?.model)
    }
    OpenCodeReadCache.shared.storeModelCatalog(catalog, key)
    return catalog
}

internal suspend fun requestSwitchModel(server: OpenCodeServer, sessionID: String, model: OpenCodeModelRef) {
    perform(
        "/api/session/$sessionID/model", method = "POST", body = encodedBody(ModelBody(model)),
        idempotency = RequestIdempotency.Idempotent, server = server,
    )
}

// Capabilities.

internal fun pathComponent(value: String): String = percentEncodePath(value, keepSlash = false)

@Serializable private data class MethodBody(val methodID: String)
@Serializable private data class CodeBody(val code: String)

/** Starts the integration's first OAuth method (`GET /api/integration/{id}`, then `POST …/connect/oauth`). */
internal suspend fun requestStartIntegrationOAuth(server: OpenCodeServer, integrationID: String): OpenCodeOAuthAttempt {
    val id = pathComponent(integrationID)
    val info = request<DataPayload<IntegrationMethodsPayload>>("/api/integration/$id", timeout = 20.seconds, server = server)
    val methodID = info.data.methods.firstOrNull { it.type == "oauth" }?.id
        ?: throw OpenCodeClientError(localized(R.string.server_error_integration_no_sign_in))
    val payload = request<DataPayload<OAuthAttemptPayload>>(
        "/api/integration/$id/connect/oauth",
        method = "POST",
        body = encodedBody(MethodBody(methodID)),
        timeout = 30.seconds,
        idempotency = RequestIdempotency.Unsafe,
        server = server,
    )
    val url = runCatching { URI(payload.data.url) }.getOrNull()
    if (url == null || (url.scheme != "https" && url.scheme != "http")) throw OpenCodeClientError(localized(R.string.server_error_sign_in_url_invalid))
    return OpenCodeOAuthAttempt(
        attemptID = payload.data.attemptID,
        url = url,
        instructions = payload.data.instructions,
        mode = OpenCodeOAuthAttempt.Mode.entries.firstOrNull { it.rawValue == payload.data.mode } ?: OpenCodeOAuthAttempt.Mode.Auto,
    )
}

internal suspend fun requestIntegrationOAuthStatus(server: OpenCodeServer, integrationID: String, attemptID: String): OpenCodeOAuthStatus {
    val payload = request<DataPayload<OAuthStatusPayload>>(
        "/api/integration/${pathComponent(integrationID)}/connect/oauth/${pathComponent(attemptID)}",
        timeout = 15.seconds,
        server = server,
    )
    return when (payload.data.status) {
        "complete" -> OpenCodeOAuthStatus.Complete
        "expired" -> OpenCodeOAuthStatus.Expired
        "failed" -> OpenCodeOAuthStatus.Failed(payload.data.message ?: "")
        else -> OpenCodeOAuthStatus.Pending
    }
}

internal suspend fun requestCompleteIntegrationOAuth(server: OpenCodeServer, integrationID: String, attemptID: String, code: String) {
    perform(
        "/api/integration/${pathComponent(integrationID)}/connect/oauth/${pathComponent(attemptID)}/complete",
        method = "POST",
        body = encodedBody(CodeBody(code)),
        timeout = 30.seconds,
        idempotency = RequestIdempotency.Unsafe,
        server = server,
    )
}

internal suspend fun requestCancelIntegrationOAuth(server: OpenCodeServer, integrationID: String, attemptID: String) {
    perform(
        "/api/integration/${pathComponent(integrationID)}/connect/oauth/${pathComponent(attemptID)}",
        method = "DELETE",
        timeout = 15.seconds,
        server = server,
    )
}

internal fun MCPServerPayload.server(): OpenCodeMCPServer {
    val error = status.error ?: ""
    val mapped = when (status.status) {
        "connected" -> OpenCodeMCPServer.Status.Connected
        "pending" -> OpenCodeMCPServer.Status.Pending
        "disabled" -> OpenCodeMCPServer.Status.Disabled
        "failed" -> OpenCodeMCPServer.Status.Failed(error)
        "needs_auth" -> OpenCodeMCPServer.Status.NeedsAuth(error)
        else -> OpenCodeMCPServer.Status.Unknown(status.status)
    }
    return OpenCodeMCPServer(name, mapped, integrationID)
}

internal fun IntegrationPayload.accounts(): List<OpenCodeAccount> = connections.mapNotNull { connection ->
    when (connection.type) {
        "credential" -> {
            val method = connection.method?.let(OpenCodeAccount.Method::of) ?: return@mapNotNull null
            OpenCodeAccount(id, name, connection.label ?: "", method)
        }
        "env" -> OpenCodeAccount(id, name, connection.name ?: "", OpenCodeAccount.Method.Environment)
        else -> null
    }
}

/** Finder-like name order (`localizedStandardCompare`). */
internal fun standardCollator(): Collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.SECONDARY }

/** No `location`: the server answers for its own working directory, so this never fans out. */
internal suspend fun requestServerCapabilities(server: OpenCodeServer): OpenCodeServerCapabilities {
    try {
        return coroutineScope {
            // Failures are values until awaited in order.
            val mcp = async { capturedRequest<LocatedPayload<List<MCPServerPayload>>>("/api/mcp", timeout = 20.seconds, server = server) }
            val skills = async { capturedRequest<LocatedPayload<List<OpenCodeAutocompleteSkill>>>("/api/skill", timeout = 20.seconds, server = server) }
            val integrations = async { capturedRequest<LocatedPayload<List<IntegrationPayload>>>("/api/integration", timeout = 20.seconds, server = server) }
            val mcpPayload = mcp.await().getOrThrow()
            val skillPayload = skills.await().getOrThrow()
            val integrationPayload = integrations.await().getOrThrow()
            OpenCodeServerCapabilities(
                location = mcpPayload.location?.directory,
                mcpServers = mcpPayload.data.map { it.server() },
                skills = standardCollator().let { collator ->
                    skillPayload.data.sortedWith { a, b -> naturalCompare(a.name, b.name, collator) }
                },
                accounts = integrationPayload.data.flatMap { it.accounts() },
            )
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: OpenCodeClientError) {
        throw error
    } catch (error: Exception) {
        throw OpenCodeClientError(error.message ?: localized(R.string.server_error_load_server_details))
    }
}

/** `POST /api/experimental/mcp/{server}/connect` or `/disconnect`. */
internal suspend fun requestSetMCPConnected(server: OpenCodeServer, name: String, connected: Boolean) {
    perform(
        "/api/experimental/mcp/${pathComponent(name)}/${if (connected) "connect" else "disconnect"}",
        method = "POST",
        timeout = 30.seconds,
        idempotency = mcpConnectionIdempotency(connected),
        server = server,
    )
}

/**
 * Connect stops then starts the MCP server, so a repeat restarts it again: retried only when
 * provably unsent. Disconnect sets it Off the same either way.
 */
internal fun mcpConnectionIdempotency(connected: Boolean): RequestIdempotency =
    if (connected) RequestIdempotency.Unsafe else RequestIdempotency.Idempotent
