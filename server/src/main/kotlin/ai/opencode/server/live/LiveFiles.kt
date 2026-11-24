package ai.opencode.server.live

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.strings.localized
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.R
import ai.opencode.server.ServerLinkClient
import ai.opencode.server.OpenCodeTransport
import ai.opencode.core.files.AttachmentFileType
import ai.opencode.server.model.OpenCodeProjectFile
import ai.opencode.server.model.OpenCodeProjectFiles
import ai.opencode.server.model.ProjectFilePaths
import ai.opencode.server.model.FileReadTarget
import ai.opencode.server.model.OpenCodeDirectoryListing
import ai.opencode.server.model.OpenCodeUploadEvent
import ai.opencode.server.retry.NetworkRetry
import ai.opencode.server.retry.RequestIdempotency
import ai.opencode.server.retry.RetryPolicy
import ai.opencode.server.retry.ServerContact
import ai.opencode.server.retry.TransportFailure
import ai.opencode.server.internal.UrlParts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.IOException
import java.text.Collator
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

private const val MAX_IMAGE_BYTES = 25 * 1024 * 1024

/**
 * Decoded Markdown image bytes by cache key, bounded by bytes, least recently used out first.
 * Android decodes bitmaps in the UI layer.
 */
class MarkdownImageCache(private val capacityBytes: Long = 32L * 1024 * 1024) {
    private val entries = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var size = 0L

    fun image(key: String): ByteArray? = synchronized(entries) { entries[key] }

    fun insert(image: ByteArray, key: String) = synchronized(entries) {
        entries.put(key, image)?.let { size -= it.size }
        size += image.size
        val iterator = entries.entries.iterator()
        while (size > capacityBytes && iterator.hasNext()) {
            val eldest = iterator.next()
            if (eldest.key == key) continue
            size -= eldest.value.size
            iterator.remove()
        }
    }

    companion object Key : DependencyKey<MarkdownImageCache> {
        override val liveValue: MarkdownImageCache by lazy { MarkdownImageCache() }
        override val testValue: MarkdownImageCache get() = MarkdownImageCache()
    }
}

internal fun markdownImageCacheKey(server: OpenCodeServer, directory: String?, source: String) =
    "markdown:${server.persistenceID}|${directory ?: ""}|$source"

/** Decodes a `data:image/` URL, or fetches (and caches) a Markdown image from [server]. */
internal suspend fun requestMarkdownImage(
    server: OpenCodeServer,
    directory: String?,
    source: String,
    fallback: FileReadTarget?,
    cache: MarkdownImageCache = dependency(MarkdownImageCache),
): ByteArray? {
    val cacheKey = markdownImageCacheKey(server, directory, source)
    cache.image(cacheKey)?.let { return it }
    if (source.startsWith("data:image/")) {
        val comma = source.indexOf(',')
        if (comma < 0) return null
        val encoded = source.substring(comma + 1)
        if (!source.substring(0, comma).contains(";base64") || encoded.length > (MAX_IMAGE_BYTES + 2) / 3 * 4) return null
        val data = decodeBase64(encoded) ?: return null
        if (data.size > MAX_IMAGE_BYTES || data.isEmpty()) return null
        cache.insert(data, cacheKey)
        return data
    }
    val target = dependency(ServerLinkClient).resolve(server)
    val outside = fallback?.let { target.fileReadRequest(it.location, it.relativePath) }
    val request = target.imageRequest(source, directory) ?: outside ?: return null
    val requestURL = request.url
    val scheme = UrlParts.parse(source)?.scheme?.lowercase()
    val isExternalURL = scheme == "http" || scheme == "https"
    val isServerRead = !isExternalURL && requestURL.encodedPath.startsWith("/api/fs/read/") && target.matchesOrigin(requestURL)
    val contact = server.persistenceID
    val transport = dependency(OpenCodeTransport)
    val data = try {
        NetworkRetry.run(
            idempotency = RequestIdempotency.Idempotent,
            label = if (isServerRead) "GET /api/fs/read" else "GET image",
            reachable = if (isServerRead) ({ ServerContact.shared.next(contact) }) else null,
        ) {
            val builder = request.newBuilder()
            if (isServerRead) {
                val correlation = NetworkRetry.correlation.current() ?: NetworkRetry.Correlation()
                correlation.identify(builder, UUID.randomUUID().toString().uppercase())
                applyCapabilities(builder)
            }
            val response = try {
                transport.measuredRequest.withTimeout(60.seconds).newCall(builder.build()).awaitResponse()
            } catch (error: IOException) {
                val failure = if (isServerRead) TransportFailure.of(error, requestURL, ServerContact.shared) else TransportFailure.of(error)
                throw transportError(error, failure)
            }
            if (response.code !in 200..299) throw statusError(response.code, response.headers["Retry-After"])
            if (isServerRead) ServerContact.shared.heard(contact, requestURL)
            response.body
        }
    } catch (_: OpenCodeClientError) {
        return null
    }
    if (data.size > MAX_IMAGE_BYTES || data.isEmpty()) return null
    cache.insert(data, cacheKey)
    return data
}

/** One directory per upload keeps the original name without collisions: `${tmp}/uploads/${uuid}/${name}`. */
internal fun uploadPath(tmp: String, id: UUID = UUID.randomUUID(), name: String): String =
    "${tmp.removeSuffix("/")}/uploads/${id.toString().lowercase()}/${AttachmentFileType.sanitizedName(name)}"

/**
 * Streams a local file into the server's temporary directory (`POST /api/experimental/fs/write`),
 * then finishes with its absolute path. Cancelling the collector cancels the transfer.
 */
internal fun uploadFile(server: OpenCodeServer, directory: String?, file: File, name: String): Flow<OpenCodeUploadEvent> = channelFlow {
    val path = performUpload(server, directory, file, name) { sent -> trySend(OpenCodeUploadEvent.Progress(sent)) }
    send(OpenCodeUploadEvent.Finished(path))
}.buffer(8, BufferOverflow.DROP_OLDEST)

private suspend fun performUpload(server: OpenCodeServer, directory: String?, file: File, name: String, progress: (Long) -> Unit): String {
    val info = try {
        request<InfoPayload>("/api/info", retry = NetworkRetry.currentPolicy(RetryPolicy.send), server = server)
    } catch (error: OpenCodeClientError) {
        if (error.statusCode == 404) throw OpenCodeClientError.uploadsUnsupported
        throw error
    }
    // `paths.tmp` arrived just before the write route; without it, uploads have nowhere to go.
    val tmp = info.paths?.tmp?.ifEmpty { null } ?: throw OpenCodeClientError.uploadsUnsupported
    val query = locationQuery(directory) + listOf("path" to uploadPath(tmp, name = name))
    if (server.endpoint("/api/experimental/fs/write", query) == null) throw OpenCodeClientError(localized(R.string.server_error_invalid_url))
    // The same path on every attempt: a retry overwrites, never duplicates. Something the
    // person is sending: it rides out a long drop like a send. Like any request, it goes again
    // as soon as the server answers anything, at whichever address answers then.
    val contact = server.persistenceID
    val link = dependency(ServerLinkClient)
    return NetworkRetry.run(
        NetworkRetry.currentPolicy(RetryPolicy.send),
        idempotency = RequestIdempotency.Idempotent,
        label = "POST /api/experimental/fs/write",
        reachable = { ServerContact.shared.next(contact) },
    ) {
        val target = link.route(server)
        val url = target.endpoint("/api/experimental/fs/write", query) ?: throw OpenCodeClientError(localized(R.string.server_error_invalid_url))
        val sentAt = Clock.liveValue.now()
        try {
            writeUpload(url, file, target, progress)
        } catch (error: OpenCodeClientError) {
            val transport = error.transport
            if (transport == TransportFailure.NotSent) {
                link.invalidate(server, target.url)
            } else if (transport != null) {
                link.requestFailed(server, target.url, sentAt)
            }
            throw error
        }
    }
}

/** The file as an octet stream, reporting bytes sent about once per percent. */
private class ProgressFileBody(private val file: File, private val report: (Long) -> Unit) : RequestBody() {
    override fun contentType(): MediaType = "application/octet-stream".toMediaType()
    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        // Bound reducer traffic to roughly one event per percent.
        val step = maxOf(total / 100, 64L * 1024)
        var sent = 0L
        var lastReported = -1L
        file.source().use { source ->
            val buffer = okio.Buffer()
            while (true) {
                val read = source.read(buffer, 64L * 1024)
                if (read == -1L) break
                sink.write(buffer, read)
                sent += read
                if (sent == total || sent - lastReported >= step) {
                    lastReported = sent
                    report(sent)
                }
            }
        }
    }
}

private suspend fun writeUpload(url: HttpUrl, file: File, server: OpenCodeServer, progress: (Long) -> Unit): String {
    val builder = Request.Builder().url(url)
        .post(ProgressFileBody(file, progress))
        .header("Accept", "application/json")
    server.authorize(builder)
    val correlation = NetworkRetry.correlation.current() ?: NetworkRetry.Correlation()
    val requestID = UUID.randomUUID().toString().uppercase()
    correlation.identify(builder, requestID)
    applyCapabilities(builder)
    // An idle interval: a stalled body trips it, a slow but moving one doesn't.
    val client = dependency(OpenCodeTransport).request.withTimeout(120.seconds)
    val response = try {
        client.newCall(builder.build()).awaitResponse()
    } catch (error: IOException) {
        val transport = TransportFailure.of(error)
        if (currentCoroutineContext().isActive) {
            AppLog.network.notice { "Upload: ${logDescription(error)} → ${transport?.rawValue ?: "not retryable"}" }
        }
        // Cancellation carries no transport, so it isn't retried.
        throw transportError(error, transport)
    }
    val status = response.code
    // A missing route is a bare 404; a missing location carries a tagged error body.
    if (status == 404 && !response.body.decodeToString().contains("Location")) throw OpenCodeClientError.uploadsUnsupported
    if (status !in 200..299) throw statusError(status, response.headers["Retry-After"])
    ServerContact.shared.heard(server.persistenceID)
    return decodedAs<DataPayload<WrittenFilePayload>>(response.body).data.path
}

internal suspend fun requestListDirectory(server: OpenCodeServer, path: String?): OpenCodeDirectoryListing {
    val payload = try {
        // No location: the server's default one, already running, resolves absolute paths anywhere on its disk.
        request<DirectoryListingPayload>("/api/fs/list", query = path?.let { listOf("path" to it) } ?: emptyList(), server = server)
    } catch (error: OpenCodeClientError) {
        // A missing folder, or a file, answers 500. Only the words change.
        if (error.statusCode != null && error.statusCode != 401) throw error.copy(message = localized(R.string.server_error_list_folder))
        throw error
    }
    return directoryListing(payload.location.directory, path, payload.data.map { it.path to it.type })
}

internal suspend fun requestProjectFiles(server: OpenCodeServer, root: String, path: String): OpenCodeProjectFiles {
    require(ProjectFilePaths.relative(root, path) != null)
    val payload = request<DirectoryListingPayload>("/api/fs/list", query = listOf("path" to path), server = server)
    return withContext(Dispatchers.Default) { projectFiles(root, path, payload.location.directory, payload.data.map { it.path to it.type }) }
}

internal fun projectFiles(root: String, path: String, location: String, entries: List<Pair<String, String>>): OpenCodeProjectFiles {
    val directory = ProjectFilePaths.normalize(path) ?: error("Invalid project path")
    val collator = standardCollator()
    val files = entries.mapNotNull { (entry, type) ->
        if (type != "file" && type != "directory") return@mapNotNull null
        val absolute = ProjectFilePaths.resolve(location, entry) ?: return@mapNotNull null
        if (ProjectFilePaths.relative(root, absolute).isNullOrEmpty()) return@mapNotNull null
        val parent = ProjectFilePaths.parent(absolute) ?: return@mapNotNull null
        if (ProjectFilePaths.relative(directory, parent) != "") return@mapNotNull null
        OpenCodeProjectFile(absolute, type == "directory")
    }.distinctBy { it.path }.sortedWith { a, b ->
        when {
            a.isDirectory != b.isDirectory -> if (a.isDirectory) -1 else 1
            a.name.startsWith('.') != b.name.startsWith('.') -> if (a.name.startsWith('.')) 1 else -1
            else -> naturalCompare(a.name, b.name, collator)
        }
    }
    return OpenCodeProjectFiles(directory, files)
}

/** Entry paths are relative to the location. Folders sort by name, hidden ones after the rest. */
internal fun directoryListing(location: String, path: String?, entries: List<Pair<String, String>>): OpenCodeDirectoryListing {
    fun resolve(relative: String): String? = ProjectFilePaths.resolve(location, relative)
    val directory = resolve(path ?: location) ?: throw OpenCodeClientError(localized(R.string.server_error_list_folder))
    val folders = entries.filter { it.second == "directory" }.mapNotNull { resolve(it.first) }.filter { it != directory }
    fun name(path: String) = path.trimEnd('/').substringAfterLast('/')
    val collator = standardCollator()
    val sorted = folders.toSet().sortedWith { x, y ->
        val a = name(x)
        val b = name(y)
        if (a.startsWith(".") != b.startsWith(".")) (if (!a.startsWith(".")) -1 else 1) else naturalCompare(a, b, collator)
    }
    return OpenCodeDirectoryListing(directory, sorted)
}

/** `localizedStandardCompare`: case-insensitive, digit runs compared by value. */
internal fun naturalCompare(a: String, b: String, collator: Collator = standardCollator()): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            val startA = i
            val startB = j
            while (i < a.length && a[i].isDigit()) i++
            while (j < b.length && b[j].isDigit()) j++
            val numberA = a.substring(startA, i).trimStart('0')
            val numberB = b.substring(startB, j).trimStart('0')
            if (numberA.length != numberB.length) return numberA.length - numberB.length
            val compared = numberA.compareTo(numberB)
            if (compared != 0) return compared
        } else {
            val compared = collator.compare(ca.toString(), cb.toString())
            if (compared != 0) return compared
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}
