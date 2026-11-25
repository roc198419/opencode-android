package ai.opencode.feature.media

import ai.opencode.core.dependencies.withDependenciesSuspending
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.model.OpenCodeAutocompleteFile
import ai.opencode.server.model.AgentFileReference
import ai.opencode.server.model.OpenCodeTranscriptFile
import ai.opencode.server.testing.WireFixture
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.retry.ServerContact
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Probing, streaming ranges, errors, text documents, JSON formatting and folding, highlight
 * budgets, cache keys and the loader's credentials. Requests are answered by an OkHttp interceptor,
 * so nothing reaches the network.
 */
class AgentFileLoadingTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test fun fileSearchMatchesLiteralTextWithoutLosingUnicodeOffsets() = runTest {
        val text = "🦊Test test TEST [.*] 中文中文 aaaa"
        val words = AgentFileSearchMatches.find(text, "test")
        assertEquals(3, words.count)
        assertEquals(listOf("Test", "test", "TEST"), (0 until words.count).map { text.substring(words.range(it)) })
        assertEquals(2, words.range(0).first)
        assertEquals(1, AgentFileSearchMatches.find(text, "[.*]").count)
        assertEquals(2, AgentFileSearchMatches.find(text, "中文").count)
        assertEquals(2, AgentFileSearchMatches.find(text, "aa").count)
        assertEquals(0, AgentFileSearchMatches.find(text, "").count)
        assertEquals(0, AgentFileSearchMatches.find(text, "missing").count)
        assertEquals(1, AgentFileSearchMatches.find(" x ", " x ").count)
    }

    @Test fun fileSearchCoversTheWholeLazyDocumentAndPreservesSyntaxSpans() = runTest {
        val source = "before\n" + "a ".repeat(90_000) + "Needle\nlast NEEDLE"
        val document = AgentTextDocument.of(source, "large.txt", null)
        val matches = AgentFileSearchMatches.find(document.text, "needle")
        assertTrue(document.usesLazyTextView)
        assertEquals(2, matches.count)
        assertTrue(matches.range(0).first > AgentTextDocument.maxHighlightedCharacters)
        for (index in 0 until matches.count) {
            val line = document.lineIndex(matches.range(index).first)
            assertTrue(document.lineRange(line).contains(matches.range(index).first))
        }
        val base = androidx.compose.ui.text.AnnotatedString("let test = test", listOf(
            androidx.compose.ui.text.AnnotatedString.Range(androidx.compose.ui.text.SpanStyle(color = androidx.compose.ui.graphics.Color.Red), 0, 3)))
        val found = AgentFileSearchMatches.find(base.text, "test")
        val search = AgentFileSearchFeature.State(AgentTextDocument.plainText(base.text), "test", found, 1)
        val normal = androidx.compose.ui.text.SpanStyle(background = androidx.compose.ui.graphics.Color.Yellow)
        val active = androidx.compose.ui.text.SpanStyle(background = androidx.compose.ui.graphics.Color.Blue)
        val highlighted = highlightFileMatches(base, 0, search, normal, active)
        assertEquals(base.text, highlighted.text)
        assertEquals(listOf(base.spanStyles.single(), androidx.compose.ui.text.AnnotatedString.Range(normal, 4, 8),
            androidx.compose.ui.text.AnnotatedString.Range(active, 11, 15)), highlighted.spanStyles)
        val clipped = highlightFileMatches(androidx.compose.ui.text.AnnotatedString("est"), 12, search, normal, active)
        assertEquals(listOf(androidx.compose.ui.text.AnnotatedString.Range(active, 0, 3)), clipped.spanStyles)
    }

    @Test fun fileSearchSelectsTheFirstResultCyclesAndClearsStaleMatches() = runTest {
        val document = AgentTextDocument.plainText("test first\ntest second")
        val matches = AgentFileSearchMatches.find(document.text, "test")
        val store = ai.opencode.core.store.TestStore(AgentFileSearchFeature.State(document), AgentFileSearchFeature(), this)
        store.send(AgentFileSearchFeature.Action.QueryChanged("test")) { it.copy(query = "test", generation = 1, searching = true) }
        var found: AgentFileSearchMatches? = null
        store.receive({ action -> (action as? AgentFileSearchFeature.Action.Found)?.takeIf { it.generation == 1 }?.also { found = it.matches } != null }) {
            it.copy(matches = found, selectedIndex = 0, searching = false, selectionRevision = 1)
        }
        assertEquals(0 until 4, store.state.selectedRange)
        store.send(AgentFileSearchFeature.Action.Previous) { it.copy(selectedIndex = 1, selectionRevision = 2) }
        store.send(AgentFileSearchFeature.Action.Next) { it.copy(selectedIndex = 0, selectionRevision = 3) }
        store.send(AgentFileSearchFeature.Action.Next) { it.copy(selectedIndex = 1, selectionRevision = 4) }
        store.send(AgentFileSearchFeature.Action.QueryChanged("test"))
        store.send(AgentFileSearchFeature.Action.QueryChanged("")) { it.copy(query = "", matches = null, selectedIndex = -1, generation = 2) }
        store.send(AgentFileSearchFeature.Action.Found(1, matches))
        store.send(AgentFileSearchFeature.Action.Next)
        store.send(AgentFileSearchFeature.Action.Previous)
        store.send(AgentFileSearchFeature.Action.QueryChanged("none")) { it.copy(query = "none", generation = 3, searching = true) }
        store.receive({ action -> (action as? AgentFileSearchFeature.Action.Found)?.takeIf { it.generation == 3 }?.also { found = it.matches } != null }) {
            it.copy(matches = found, searching = false, selectionRevision = 5)
        }
        assertEquals(0, store.state.matches!!.count)
        store.send(AgentFileSearchFeature.Action.Next)
        store.finish()
    }

    @Test fun singleFileSearchMatchCanBeSelectedAgain() = runTest {
        val document = AgentTextDocument.plainText("single")
        val matches = AgentFileSearchMatches.find(document.text, "single")
        val store = ai.opencode.core.store.TestStore(AgentFileSearchFeature.State(document, "single", matches, 0), AgentFileSearchFeature(), this)
        store.send(AgentFileSearchFeature.Action.Next) { it.copy(selectionRevision = 1) }
        store.send(AgentFileSearchFeature.Action.Previous) { it.copy(selectionRevision = 2) }
        store.finish()
    }

    private fun response(status: Int, headers: Map<String, String>, body: Long = 0): Response {
        val builder = Response.Builder()
            .request(Request.Builder().url("https://box/api/fs/read/a.mp4").build())
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("")
        headers.forEach { (name, value) -> builder.header(name, value) }
        // A network body reports the Content-Length it was sent with.
        val contentLength = headers["Content-Length"]?.toLong() ?: body
        return builder.body(Buffer().asResponseBody(headers["Content-Type"]?.toMediaType(), contentLength)).build()
    }

    /** A client whose every call is answered by [answer] without a connection. */
    private fun client(answer: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain -> answer(chain.request()) }.build()

    private fun answered(request: Request, status: Int, headers: Map<String, String>, body: String = ""): Response {
        val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("")
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder.body(body.toResponseBody(headers["Content-Type"]?.toMediaType())).build()
    }

    @Test
    fun hashOriginalIsNeverAWorkspacePath() = runTest {
        val hash = "a".repeat(64)
        val tag = "\"$hash\""
        val movie = OpenCodeTranscriptFile(id = "m", name = "clip.mp4", mime = "video/mp4", mediaHash = hash)
        val reference = requireNotNull(movie.mediaViewerReference)
        val cache = temporaryDirectory("blob-original")
        try {
            WireFixture { request ->
                if (request.header("If-None-Match") == tag) WireFixture.Reply.status(304)
                else WireFixture.Reply.Json(200, "movie bytes", mapOf("ETag" to tag, "Content-Type" to "application/octet-stream"))
            }.use { wire ->
                val server = wire.server
                val blob = requireNotNull(AgentFileLoader.original(server, reference, "/work"))
                val workspace = requireNotNull(AgentFileLoader.original(server, AgentFileReference("clip.mp4"), "/work"))
                assertEquals("/work/clip.mp4", workspace.cacheKey)
                assertEquals("media:$hash", blob.cacheKey)
                assertNull(MediaReferenceResolver.originalRequest(server, "../fs/read"))
                assertNull(AgentFileLoader.original(server, reference.copy(media = reference.media!!.copy(hash = "invalid")), "/work"))
                // Current media.get ignores Range. Only a real 206 can select the player’s streaming source.
                val probe = AgentFileLoader.probe(blob.request, server)
                assertFalse(probe.supportsRanges)
                assertEquals(11L, probe.size)
                val file = AgentFileLoader.cacheURL(server, blob.cacheKey, reference.name, cache)
                repeat(2) {
                    AgentFileLoader.download(blob.request, file, server, maxBytes = 11) { _, _ -> }
                    assertEquals("movie bytes", file.readText())
                }
                assertEquals(listOf("bytes=0-1", null, null), wire.requests.map { it.header("Range") })
                assertEquals(listOf(null, null, tag), wire.requests.map { it.header("If-None-Match") })
                assertTrue(wire.requests.all { it.path == "/api/experimental/media/$hash" && it.header("Authorization") == server.basicAuthorization })
            }
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun oversizedChunkedOriginalDoesNotReplaceCachedFile() = runTest {
        val directory = temporaryDirectory("bounded-original").apply { mkdirs() }
        val file = File(directory, "original").apply { writeText("prior") }
        try {
            val request = Request.Builder().url("https://box/file").build()
            val error = runCatching {
                AgentFileLoader.download(request, file, maxBytes = 4, client = client {
                    answered(it, 200, emptyMap()).newBuilder().body(Buffer().writeUtf8("oversize").asResponseBody(null, -1)).build()
                }) { _, _ -> }
            }.exceptionOrNull()
            assertEquals(AgentFileLoader.overLimit, error)
            assertEquals("prior", file.readText())
            assertEquals(listOf(file), directory.listFiles()?.toList())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun probeReadsRangeSupportAndSize() {
        val ranged = AgentFileProbe.of(response(206, mapOf("Content-Range" to "bytes 0-1/48213", "Content-Type" to "video/mp4", "ETag" to "W/\"1\"")))
        assertEquals(AgentFileProbe(size = 48213, mime = "video/mp4", supportsRanges = true, entityTag = "W/\"1\""), ranged)
        val whole = AgentFileProbe.of(response(200, mapOf("Content-Length" to "900", "Content-Type" to "application/pdf")))
        assertTrue(whole.size == 900L && !whole.supportsRanges)
        assertEquals(0L, AgentFileProbe.of(response(416, mapOf("Content-Range" to "bytes */0"))).size)
        assertNull(AgentFileProbe.totalLength("bytes 0-1/*"))
    }

    @Test
    fun streamingAsksForExactlyTheBytesAVFoundationWants() {
        assertEquals("bytes=0-1", StreamingFileLoader.rangeHeader(offset = 0, length = 2, toEnd = false))
        assertEquals("bytes=1000-1499", StreamingFileLoader.rangeHeader(offset = 1000, length = 500, toEnd = false))
        assertEquals("bytes=1000-", StreamingFileLoader.rangeHeader(offset = 1000, length = 500, toEnd = true))
    }

    @Test
    fun errorsReadAsUserFacingCopy() {
        assertEquals(AgentFileError.NotFound, AgentFileError.from(404))
        assertNull(AgentFileError.from(206))
        assertEquals("File Not Found", AgentFileError.NotFound.title)
        assertEquals("Couldn’t Open File", AgentFileError.Server(500).title)
    }

    @Test
    fun textDocumentsSplitLinesAndClampTheFocus() {
        val document = AgentTextDocument.of("let a = 1\nlet b = 2\n", fileName = "a.swift", focusLine = 2)
        val lines = (0 until document.displayLineCount).map { index -> document.lineRange(index).let { document.text.substring(it.first, it.last + 1) } }
        assertEquals(listOf("let a = 1", "let b = 2"), lines)
        assertEquals(2, document.focusLine)
        assertNull(AgentTextDocument.of("x", fileName = "a.txt", focusLine = 9).focusLine)
    }

    @Test
    fun jsonFormattingAndLineFoldingKeepParagraphsBoundedAndUTF8Safe() {
        val raw = """{"items":[{"id":"a:1,b:2","empty":{},"list":[],"escaped":"quote \" { } [ ] , : end"}],"ok":true}"""
        val formatted = AgentTextDocument.formatJSON(raw)
        assertTrue(formatted.contains("\"id\": \"a:1,b:2\""))
        assertTrue(formatted.contains("\"empty\": {}"))
        assertTrue(formatted.contains("\"list\": []"))
        assertTrue(formatted.contains(""""escaped": "quote \" { } [ ] , : end""""))

        // Multi-byte UTF-8 scalars (4-byte emoji) never split mid-scalar when folded.
        val emojiLine = "🦊".repeat(1_500) // 6,000 UTF-8 bytes on one line
        val folded = AgentTextDocument.foldLongLines(emojiLine, maxLineBytes = 4_096)
        val scan = AgentTextDocument.scan(folded.encodeToByteArray())
        assertEquals(2, scan.lineCount)
        assertTrue(scan.longestLineBytes <= 4_096)
        assertEquals(emojiLine, folded.replace("\n", ""))

        // Escapes at `maxLineBytes - 1` or right after a multi-byte scalar at the boundary
        // still fold subsequent runs and finish within budget.
        val limit = 100
        val boundaryPrefix = "a".repeat(91) + """\"""" + "b".repeat(95) + "🦊" + """\""""
        val boundaryChunk = "c".repeat(99) + """\n"""
        val escapedJSON = """{"k":"""" + boundaryPrefix + boundaryChunk.repeat(5_000) + """"}"""
        val started = System.nanoTime()
        val formattedEscaped = AgentTextDocument.formatJSON(escapedJSON, maxLineBytes = limit)
        val elapsedMs = (System.nanoTime() - started) / 1e6
        val escapedScan = AgentTextDocument.scan(formattedEscaped.encodeToByteArray())
        assertTrue("Longest line was ${escapedScan.longestLineBytes} bytes", escapedScan.longestLineBytes <= limit + 2)
        assertTrue(escapedScan.lineCount > 5_000)
        if (System.getenv("CI") != "true") {
            assertTrue("Boundary-escape JSON format took $elapsedMs ms", elapsedMs < 50)
        }
    }

    @Test
    fun singleLine200KBJSONAndLongLinesStayWithinHighlightBudget() {
        val entries = (0 until 2_000).joinToString(",") { i ->
            """{"id":"item_$i","index":$i,"active":true,"score":$i.5,"message":"Payload entry number $i with realistic text content"}"""
        }
        val singleLine200KB = "[$entries]"
        assertTrue(singleLine200KB.encodeToByteArray().size > 200_000)

        var started = System.nanoTime()
        val tokens = SyntaxHighlighter.tokens(singleLine200KB, language = "json")
        var elapsedMs = (System.nanoTime() - started) / 1e6
        if (System.getenv("CI") != "true") {
            assertTrue("200 KB single-line JSON highlight took $elapsedMs ms", elapsedMs < 50)
        }
        assertTrue("Lines over 1,000 bytes render plain without token runs", tokens.isEmpty())

        started = System.nanoTime()
        val incremental = SyntaxHighlighter.highlightIncremental(singleLine200KB, language = "json", previous = null)
        elapsedMs = (System.nanoTime() - started) / 1e6
        if (System.getenv("CI") != "true") {
            assertTrue("200 KB single-line JSON incremental highlight took $elapsedMs ms", elapsedMs < 50)
        }
        assertTrue(incremental.tokens.isEmpty())

        // A long line between short lines renders plain while short neighbors still highlight
        // and incremental highlighting advances safePrefixUTF8Count past the long line.
        val longLine = "let blob = \"" + "abcdefghij".repeat(150) + "\""
        val mixed = "let before = 1\n$longLine\nlet after = 2\n"
        val firstSource = "let before = 1\n$longLine\n"
        val firstPass = SyntaxHighlighter.highlightIncremental(firstSource, language = "swift", previous = null)
        assertEquals(firstSource.encodeToByteArray().size, firstPass.safePrefixUTF8Count)
        val secondPass = SyntaxHighlighter.highlightIncremental(
            mixed,
            language = "swift",
            previous = SyntaxHighlighter.Previous(firstSource, firstPass.safePrefixUTF8Count, firstPass.safePrefixTokens),
        )
        val colored = secondPass.tokens.map { mixed.substring(it.start, it.end) }
        assertEquals("Short lines highlight while the >1,000-byte line between them stays plain", listOf("let", "1", "let", "2"), colored)

        // A streaming code block crossing the 128 KB budget still advances safePrefixUTF8Count
        // to the last complete line before the cap.
        val line = "let counter = 12345\n" // 20 UTF-8 bytes
        val beforeCap = line.repeat(6_500) // 130,000 bytes (< 131,072)
        val afterCap = beforeCap + line.repeat(100) // 132,000 bytes (> 131,072)
        val preCapResult = SyntaxHighlighter.highlightIncremental(beforeCap, language = "swift", previous = null)
        assertEquals(beforeCap.encodeToByteArray().size, preCapResult.safePrefixUTF8Count)
        val postCapResult = SyntaxHighlighter.highlightIncremental(
            afterCap,
            language = "swift",
            previous = SyntaxHighlighter.Previous(beforeCap, preCapResult.safePrefixUTF8Count, preCapResult.safePrefixTokens),
        )
        assertTrue(
            "Crossing 128 KB still advances safePrefixUTF8Count to the boundary line",
            postCapResult.safePrefixUTF8Count > preCapResult.safePrefixUTF8Count,
        )
        assertTrue(postCapResult.safePrefixUTF8Count <= SyntaxHighlighter.maxHighlightedUTF8Bytes)
    }

    /**
     * Its paint, wrap-toggle and memory-footprint probes drive `UIHostingController` and
     * `drawHierarchy`.
     */
    @Test
    fun largeFilesFormatAndHighlightOffMainActorAndRenderWithinBudget() = runTest {
        val directory = temporaryDirectory("large-file-test").apply { mkdirs() }
        try {
            suspend fun verifyCase(
                minBytes: Int,
                name: String,
                focusLine: Int? = null,
                buildBudgetMs: Double,
                makeText: () -> String,
                check: (AgentTextDocument) -> Unit = {},
            ) {
                val file = File(directory, name)
                val text = makeText()
                assertTrue(text.encodeToByteArray().size > minBytes)
                file.writeText(text)
                val started = System.nanoTime()
                val loaded = AgentFileViewerModel.loadTextContent(file, fileName = name, focusLine = focusLine, isMarkdown = false)
                val elapsedMs = (System.nanoTime() - started) / 1e6
                val document = (loaded as? AgentFileViewerModel.Content.Text)?.document
                assertNotNull("Expected a text document for $name", document)
                assertTrue(document!!.usesLazyTextView)
                if (System.getenv("CI") != "true") {
                    assertTrue("$name build took $elapsedMs ms", elapsedMs < buildBudgetMs)
                }
                check(document)
                file.delete()
            }

            verifyCase(
                minBytes = 5_000_000,
                name = "pretty-5mb.json",
                focusLine = 100_000,
                buildBudgetMs = 500.0,
                makeText = {
                    val prettyEntries = (0 until 4_000).joinToString(",\n") { i ->
                        """
                          {
                            "id": "item_$i",
                            "index": $i,
                            "active": ${if (i % 2 == 0) "true" else "false"},
                            "score": ${i * 17}.42,
                            "message": "Payload entry number $i with realistic text content for the viewer"
                          }
                        """.trimIndent().prependIndent("  ")
                    }
                    "[\n" + "$prettyEntries,\n".repeat(6) + prettyEntries + "\n]\n"
                },
                check = { doc ->
                    assertEquals(100_000, doc.focusLine)
                    assertNotNull(doc.focusRange)
                },
            )
            verifyCase(
                minBytes = 4_800_000,
                name = "minified-5mb.json",
                buildBudgetMs = 500.0,
                makeText = {
                    val minifiedEntries = (0 until 6_500).joinToString(",") { i ->
                        """{"id":"item_$i","index":$i,"active":true,"score":$i.5,"message":"Payload entry number $i with realistic text content"}"""
                    }
                    "[" + "$minifiedEntries,".repeat(5) + minifiedEntries + "]"
                },
                check = { doc -> assertTrue("Minified 5 MB JSON is formatted into indented lines", doc.lineCount > 200_000) },
            )
            verifyCase(
                minBytes = 5_000_000,
                name = "server-5mb.log",
                buildBudgetMs = 500.0,
                makeText = {
                    val logChunk = (0 until 50).joinToString("\n") { i ->
                        "2026-10-18T12:00:${"%02d".format(i)}Z INFO worker[$i] " + "frame=$i ".repeat(600)
                    }
                    "$logChunk\n".repeat(22)
                },
                check = { doc ->
                    assertTrue(AgentTextDocument.scan(doc.text.encodeToByteArray()).longestLineBytes <= AgentTextDocument.maxParagraphBytes)
                },
            )
            verifyCase(
                minBytes = 5_000_000,
                name = "changes-5mb.diff",
                buildBudgetMs = 500.0,
                makeText = {
                    val diffHunk = """
                        diff --git a/OpenCode/SessionView.swift b/OpenCode/SessionView.swift
                        index 1234567..89abcdef 100644
                        --- a/OpenCode/SessionView.swift
                        +++ b/OpenCode/SessionView.swift
                        @@ -10,6 +10,8 @@ struct SessionView: View {
                           let title: String
                        +  let subtitle: String = "Added line in diff hunk"
                        +  let count: Int = 42
                           var body: some View { Text(title) }
                        }
                    """.trimIndent()
                    "$diffHunk\n".repeat(16_000)
                },
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun cacheKeysSeparateServersAndPaths() {
        val cache = temporaryDirectory("agent-file-cache")
        val a = OpenCodeServer(url = "https://a:4096", username = "opencode", password = "")
        val b = OpenCodeServer(url = "https://b:4096", username = "opencode", password = "")
        val first = AgentFileLoader.cacheURL(a, absolutePath = "/tmp/x.mp4", name = "x.mp4", cacheDirectory = cache)
        assertEquals("x.mp4", first.name)
        assertNotEquals(AgentFileLoader.cacheURL(b, absolutePath = "/tmp/x.mp4", name = "x.mp4", cacheDirectory = cache), first)
        assertNotEquals(AgentFileLoader.cacheURL(a, absolutePath = "/var/x.mp4", name = "x.mp4", cacheDirectory = cache), first)
    }

    /** The loader's client sends once by construction ([AgentFileLoader.client]). */
    @Test
    fun probeAndDownloadAttachCustomServerHeadersAndRecordContact() = runTest {
        val server = OpenCodeServer(
            url = "http://100.101.102.103:4096",
            username = "opencode",
            password = "pw",
            headers = listOf(
                OpenCodeServer.Header(name = "CF-Access-Client-Id", value = "id-123"),
                OpenCodeServer.Header(name = "CF-Access-Client-Secret", value = "sec-456"),
            ),
        )
        val rawRequest = Request.Builder().url("http://100.101.102.103:4096/api/fs/read/demo.txt?location%5Bdirectory%5D=/tmp").build()
        val contact = ServerContact()
        val probed = Locked<Request?>(null)
        val probe = AgentFileLoader.probe(
            rawRequest,
            server = server,
            contact = contact,
            client = client { request ->
                probed.set(request)
                answered(request, 206, mapOf("Content-Range" to "bytes 0-1/12", "Content-Type" to "text/plain"))
            },
        )
        val sent = probed.current!!
        assertEquals("bytes=0-1", sent.header("Range"))
        assertEquals("id-123", sent.header("CF-Access-Client-Id"))
        assertEquals("sec-456", sent.header("CF-Access-Client-Secret"))
        assertEquals(server.basicAuthorization, sent.header("Authorization"))
        assertEquals(AgentFileProbe(size = 12, mime = "text/plain", supportsRanges = true), probe)
        assertTrue(contact.hasAnsweredHTTPOrigin(rawRequest.url))

        val directory = temporaryDirectory("agent-file-test")
        val destination = File(directory, "demo.txt")
        try {
            val downloadContact = ServerContact()
            val downloadedRequest = Locked<Request?>(null)
            val progress = Locked(listOf<Pair<Long, Long?>>())
            val downloaded = AgentFileLoader.download(
                rawRequest,
                destination = destination,
                server = server,
                contact = downloadContact,
                client = client { request ->
                    downloadedRequest.set(request)
                    answered(request, 200, emptyMap(), body = "hello world\n")
                },
                progress = { received, expected -> progress.update { it + (received to expected) } },
            )
            val request = downloadedRequest.current!!
            assertEquals("id-123", request.header("CF-Access-Client-Id"))
            assertEquals("sec-456", request.header("CF-Access-Client-Secret"))
            assertEquals(server.basicAuthorization, request.header("Authorization"))
            assertEquals(listOf(12L to 12L), progress.current)
            assertEquals(destination, downloaded)
            assertEquals("hello world\n", AgentFileLoader.readText(downloaded))
            assertTrue(downloadContact.hasAnsweredHTTPOrigin(rawRequest.url))
        } finally {
            directory.deleteRecursively()
        }
    }

    /** OkHttp's `HttpUrl` rejects zone IDs, so such a request can't be built on Android. */
    @Test
    fun foreignOriginRequestsExcludeServerAuthAndCustomHeaders() = runTest {
        val server = OpenCodeServer(
            url = "http://100.101.102.103:4096",
            username = "opencode",
            password = "pw",
            alternateURLs = listOf("https://box.tail1234.ts.net", "http://[fe80::1%25en0]:4096"),
            headers = listOf(
                OpenCodeServer.Header(name = "CF-Access-Client-Id", value = "id-123"),
                OpenCodeServer.Header(name = "CF-Access-Client-Secret", value = "sec-456"),
            ),
        )
        val alternateRequest = AgentFileLoader.authorizedRequest(
            Request.Builder().url("https://box.tail1234.ts.net:443/api/fs/read/demo.txt").build(),
            server,
        )
        assertEquals(server.basicAuthorization, alternateRequest.header("Authorization"))
        assertEquals("id-123", alternateRequest.header("CF-Access-Client-Id"))

        for (foreign in listOf(
            "https://cdn.example.com/demo.txt",
            "http://100.101.102.103:8080/api/fs/read/demo.txt",
            "https://100.101.102.103:4096/api/fs/read/demo.txt",
            "http://[fe80::1]:4096/api/fs/read/demo.txt",
        )) {
            val url = foreign.toHttpUrl()
            val contact = ServerContact()
            val sent = Locked<Request?>(null)
            AgentFileLoader.probe(
                Request.Builder().url(url).build(),
                server = server,
                contact = contact,
                client = client { request ->
                    sent.set(request)
                    answered(request, 206, mapOf("Content-Range" to "bytes 0-1/12", "Content-Type" to "text/plain"))
                },
            )
            val request = sent.current!!
            assertNull("leaked Authorization to $foreign", request.header("Authorization"))
            assertNull("leaked custom header to $foreign", request.header("CF-Access-Client-Id"))
            assertNull("leaked custom header to $foreign", request.header("CF-Access-Client-Secret"))
            assertFalse(contact.hasAnsweredHTTPOrigin(url))
        }
    }
}

/** Exercise the same Media3 DataSource that the remote gallery player reads after a seek. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentMovieRangeTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun rangePlaybackSeeksTheAuthenticatedOriginal() = runTest {
        val hash = "e".repeat(64)
        val file = OpenCodeTranscriptFile(id = "movie", name = "clip", mime = "video/mp4", mediaHash = hash)
        WireFixture { request ->
            val (range, body) = when (request.header("Range")) {
                "bytes=0-1" -> "0-1" to "ab"
                "bytes=4-6" -> "4-6" to "efg"
                else -> error("Unexpected range")
            }
            WireFixture.Reply.Json(206, body, mapOf("Content-Range" to "bytes $range/10", "Content-Type" to "application/octet-stream"))
        }.use { wire ->
            val server = wire.server
            val original = requireNotNull(AgentFileLoader.original(server, requireNotNull(file.mediaViewerReference), null))
            val probe = AgentFileLoader.probe(original.request, server)
            assertTrue(probe.supportsRanges)
            assertEquals(10L, probe.size)
            val loader = StreamingFileLoader(original.request, server, file.mime, file.name)
            val source = loader.dataSourceFactory.createDataSource()
            try {
                val item = loader.makeMediaItem().localConfiguration!!
                assertEquals("video/mp4", item.mimeType)
                val spec = DataSpec.Builder().setUri(item.uri).setPosition(4).setLength(3).build()
                assertEquals(3L, source.open(spec))
                val bytes = ByteArray(3)
                assertEquals(3, source.read(bytes, 0, bytes.size))
                assertEquals("efg", bytes.decodeToString())
            } finally {
                source.close()
                loader.invalidate()
            }
            assertEquals(2, wire.requests.size)
            assertTrue(wire.requests.all { it.path == "/api/experimental/media/$hash" && it.header("Authorization") == server.basicAuthorization })
        }
    }
}

/** Whether a file the agent names in its folder is there before its card shows. */
class AgentFileCheckTests {
    @get:Rule val isolation = TestIsolationRule()

    private val directory = "/work/app"
    private val server = OpenCodeServer(url = "https://box:4096", username = "opencode", password = "")

    private fun file(path: String) = requireNotNull(AgentFileReference.fromInlineCode(path))

    private fun found(vararg paths: String): (OpenCodeServer, String, String, Int) -> List<OpenCodeAutocompleteFile> =
        { _, location, _, _ -> paths.map { OpenCodeAutocompleteFile(it, "$location/$it", location) } }

    private suspend fun check(
        path: String,
        status: Int,
        finds: MutableList<String> = mutableListOf(),
        matches: List<String> = emptyList(),
    ): AgentFileChecks.Result = WireFixture { WireFixture.Reply.status(status) }.use { wire ->
        val find = found(*matches.toTypedArray())
        withDependenciesSuspending({
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(findFiles = { s, location, query, limit ->
                finds += query
                find(s, location, query, limit)
            })
        }) {
            AgentFileChecks.check(wire.server, directory, file(path))
        }
    }

    @Test
    fun bareNameInOneSubfolderResolvesToIt() = runTest {
        val finds = mutableListOf<String>()
        val result = check("spec.pdf", 404, finds, listOf("docs/spec.pdf", "docs/spec.pdf.bak", "spec.pdfx"))
        assertEquals(AgentFileChecks.Result.Found(file("docs/spec.pdf")), result)
        assertEquals(listOf("spec.pdf"), finds)
    }

    @Test
    fun bareNameInTwoFoldersOrNoneIsMissing() = runTest {
        assertEquals(AgentFileChecks.Result.Missing, check("notes.pdf", 404, matches = listOf("a/notes.pdf", "b/notes.pdf")))
        assertEquals(AgentFileChecks.Result.Missing, check("ghost.mp4", 404))
    }

    @Test
    fun missingPathIsMissingWithoutALookup() = runTest {
        val finds = mutableListOf<String>()
        assertEquals(AgentFileChecks.Result.Missing, check("recordings/take.mp4", 404, finds))
        assertTrue(finds.isEmpty())
    }

    @Test
    fun fileAtTheRootOrAnErrorKeepsTheCard() = runTest {
        assertEquals(AgentFileChecks.Result.Found(file("clip.mp4")), check("clip.mp4", 200))
        assertEquals(AgentFileChecks.Result.Found(file("clip.mp4")), check("clip.mp4", 500))
    }

    @Test
    fun onlyFilesInTheFolderOtherThanImagesAreChecked() {
        assertTrue(AgentFileChecks.checks(file("clip.mp4"), directory))
        assertTrue(AgentFileChecks.checks(file("/work/app/docs/spec.pdf"), directory))
        assertFalse(AgentFileChecks.checks(file("/elsewhere/clip.mp4"), directory))
        assertFalse(AgentFileChecks.checks(file("shot.png"), directory))
        assertFalse(AgentFileChecks.checks(file("Main.kt"), directory))
    }

    @Test
    fun checksRunFourAtATimeOncePerFile() {
        val started = mutableListOf<String>()
        val gates = mutableMapOf<String, CompletableDeferred<AgentFileChecks.Result>>()
        val checks = AgentFileChecks(checker = { _, _, reference ->
            started += reference.path
            gates.getOrPut(reference.path) { CompletableDeferred() }.await()
        }, dispatcher = Dispatchers.Unconfined)
        val files = listOf("a.mp4", "b.mp4", "c.mp4", "d.mp4", "e.mp4", "f.mp4").map(::file)
        val outside = file("/elsewhere/x.mp4")

        checks.watch(server, directory, files + outside)
        checks.watch(server, directory, files)
        assertEquals(listOf("a.mp4", "b.mp4", "c.mp4", "d.mp4"), started)
        assertEquals(AgentFileChecks.Status.Pending, checks.status(server, directory, files[0]))
        assertNull(checks.shown(server, directory, files[0]))
        assertEquals(AgentFileChecks.Status.Found(outside), checks.status(server, directory, outside))

        gates.getValue("a.mp4").complete(AgentFileChecks.Result.Found(file("clips/a.mp4")))
        gates.getValue("b.mp4").complete(AgentFileChecks.Result.Missing)
        assertEquals(listOf("a.mp4", "b.mp4", "c.mp4", "d.mp4", "e.mp4", "f.mp4"), started)
        assertEquals(AgentFileChecks.Status.Found(file("clips/a.mp4")), checks.status(server, directory, files[0]))
        assertEquals(file("clips/a.mp4"), checks.shown(server, directory, files[0]))
        assertEquals(AgentFileChecks.Status.Missing, checks.status(server, directory, files[1]))
        assertNull(checks.shown(server, directory, files[1]))

        checks.watch(server, directory, files.take(2))
        assertEquals(6, started.size)

        gates.getValue("c.mp4").complete(AgentFileChecks.Result.Missing)
        checks.invalidate(server, directory)
        checks.watch(server, directory, files.take(2))
        assertEquals(listOf("a.mp4", "b.mp4", "c.mp4", "d.mp4", "e.mp4", "f.mp4", "b.mp4"), started)
    }

    @Test
    fun aFolderChangedDuringACheckIsCheckedAgain() {
        val gates = mutableListOf<CompletableDeferred<AgentFileChecks.Result>>()
        val checks = AgentFileChecks(checker = { _, _, _ ->
            val gate = CompletableDeferred<AgentFileChecks.Result>()
            gates += gate
            gate.await()
        }, dispatcher = Dispatchers.Unconfined)
        val clip = file("clip.mp4")

        checks.watch(server, directory, listOf(clip))
        checks.invalidate(server, directory)
        gates[0].complete(AgentFileChecks.Result.Missing)
        assertEquals(2, gates.size)
        gates[1].complete(AgentFileChecks.Result.Found(clip))
        assertEquals(AgentFileChecks.Status.Found(clip), checks.status(server, directory, clip))
    }

    @Test
    fun aCheckNoRowWatchesAnyMoreIsSkipped() {
        val started = mutableListOf<String>()
        val checks = AgentFileChecks(checker = { _, _, reference ->
            started += reference.path
            CompletableDeferred<AgentFileChecks.Result>().await()
        }, dispatcher = Dispatchers.Unconfined)
        val release = checks.watch(server, directory, listOf("a.mp4", "b.mp4", "c.mp4", "d.mp4", "e.mp4").map(::file))
        release()
        checks.watch(server, directory, listOf(file("f.mp4")))
        assertEquals(listOf("a.mp4", "b.mp4", "c.mp4", "d.mp4"), started)
    }

    @Test
    fun aFileTheViewerCouldNotFindLosesItsCard() {
        val checks = AgentFileChecks(checker = { _, _, reference -> AgentFileChecks.Result.Found(reference) }, dispatcher = Dispatchers.Unconfined)
        val outside = file("/elsewhere/x.mp4")
        assertEquals(AgentFileChecks.Status.Found(outside), checks.status(server, directory, outside))
        checks.markMissing(server, "/elsewhere/x.mp4")
        assertEquals(AgentFileChecks.Status.Missing, checks.status(server, directory, outside))
    }
}
