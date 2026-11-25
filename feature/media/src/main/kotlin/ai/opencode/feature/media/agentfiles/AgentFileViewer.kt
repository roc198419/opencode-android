package ai.opencode.feature.media

import ai.opencode.core.R as CoreR
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.strings.localized
import ai.opencode.core.strings.localizedPlural
import ai.opencode.core.ui.AppearanceColorScheme
import ai.opencode.core.ui.OpenCodeContentUnavailable
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodePrimaryButton
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.appFormattingLocale
import ai.opencode.core.ui.rememberAppAppearance
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.AgentFileReference
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import java.util.Locale
import kotlinx.coroutines.delay

/** A file opened from a document, resolved against that document's folder. */
data class AgentFileDestination(val reference: AgentFileReference, val directory: String?)

/**
 * Renders a Markdown document the way a reply renders (`:feature:transcript` provides it; media
 * sits below the transcript). [onOpenFile] pushes a file the document links to; web links are
 * the renderer's own. Laid out in a vertically scrolling column, so it must not scroll itself.
 */
typealias AgentFileMarkdown = @Composable (source: String, server: OpenCodeServer, directory: String?, onOpenFile: (AgentFileReference) -> Unit) -> Unit

/**
 * The app's Markdown renderer for file pages. Null: Markdown files open as source.
 */
val LocalAgentFileMarkdown = staticCompositionLocalOf<AgentFileMarkdown?> { null }

/** Loading, playback and document content only. The fullscreen viewer owns navigation and actions. */
@Composable
internal fun AgentFilePage(
    model: AgentFileViewerModel,
    wrapsLines: Boolean,
    showsSource: Boolean,
    noAppOpens: Boolean,
    isClear: Boolean,
    pip: AgentFilePictureInPicture?,
    onOpen: (AgentFileDestination) -> Unit,
    onExternalOpen: (java.io.File) -> Unit,
    search: AgentFileSearchFeature.State? = null,
) {
    val reference = model.reference
    val markdown = LocalAgentFileMarkdown.current
    when (val phase = model.phase) {
        AgentFileViewerModel.Phase.Opening -> StatusView(reference, detail = stringResource(R.string.media_file_opening), progress = null)
        is AgentFileViewerModel.Phase.Downloading -> StatusView(
            reference, detail = progressText(phase.received, phase.expected),
            progress = phase.expected?.let { phase.received.toFloat() / maxOf(it, 1).toFloat() },
        )
        is AgentFileViewerModel.Phase.ConfirmLarge -> ConfirmLargeView(reference, phase.size, model::confirmDownload)
        is AgentFileViewerModel.Phase.Failed -> FailedView(phase.error, model::open)
        is AgentFileViewerModel.Phase.Ready -> when (val content = phase.content) {
            is AgentFileViewerModel.Content.Image -> Unit // Rendered by the shared zoomable page.
            is AgentFileViewerModel.Content.Player -> AgentFilePlayer(
                player = content.player,
                isAudio = content.isAudio,
                name = reference.name,
                isClear = isClear,
                pip = pip,
                modifier = Modifier.testTag(if (content.isStreaming) "agent-file-player-streaming" else "agent-file-player"),
            )
            is AgentFileViewerModel.Content.QuickLook -> {
                val pdf = content.pdf
                if (pdf != null) {
                    AgentPdfView(pdf, Modifier.testTag("agent-file-quick-look"))
                } else {
                    ExternalDocumentView(reference, noAppOpens, { onExternalOpen(content.file) }, Modifier.testTag("agent-file-quick-look"))
                }
            }
            is AgentFileViewerModel.Content.Text -> AgentCodeView(content.document, wrapsLines, search = search?.takeIf { it.document === content.document })
            is AgentFileViewerModel.Content.Markdown -> {
                if (showsSource || markdown == null || search != null) {
                    AgentCodeView(content.document, wrapsLines, search = search?.takeIf { it.document === content.document })
                } else {
                    val fileDirectory = model.fileDirectory
                    Box(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .testTag("agent-file-markdown"),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Box(
                            Modifier
                                .widthIn(max = TranscriptMetrics.readableColumnWidth)
                                .fillMaxWidth()
                                .padding(horizontal = TranscriptMetrics.contentGutter, vertical = TranscriptMetrics.turn)
                                .navigationBarsPadding(),
                        ) {
                            markdown(content.source, model.server, fileDirectory) { file -> onOpen(AgentFileDestination(file, fileDirectory)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileGlyph(reference: AgentFileReference) {
    OpenCodeIcon(reference.symbolName, contentDescription = null, size = 44.dp, tint = OpenCodeTheme.colors.secondaryText)
}

@Composable
private fun StatusView(reference: AgentFileReference, detail: String, progress: Float?) {
    Column(
        Modifier
            .padding(32.dp)
            .semantics(mergeDescendants = true) {
                if (progress != null) progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
            }
            .testTag("agent-file-status"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.turn),
    ) {
        FileGlyph(reference)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.compact)) {
            Text(reference.name, style = OpenCodeTextStyles.headline, color = OpenCodeTheme.colors.text, textAlign = TextAlign.Center)
            Text(
                detail,
                style = OpenCodeTextStyles.subheadline.copy(fontFeatureSettings = "tnum"),
                color = OpenCodeTheme.colors.secondaryText,
            )
        }
        val barModifier = Modifier.widthIn(max = 240.dp).fillMaxWidth()
        if (progress != null) {
            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = barModifier)
        } else {
            LinearProgressIndicator(modifier = barModifier)
        }
    }
}

@Composable
private fun ConfirmLargeView(reference: AgentFileReference, size: Long, onDownload: () -> Unit) {
    Column(
        Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.turn),
    ) {
        FileGlyph(reference)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.compact)) {
            Text(reference.name, style = OpenCodeTextStyles.headline, color = OpenCodeTheme.colors.text, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.media_file_confirm_large_message, fileSizeText(size)),
                style = OpenCodeTextStyles.subheadline,
                color = OpenCodeTheme.colors.secondaryText,
                textAlign = TextAlign.Center,
            )
        }
        OpenCodePrimaryButton(text = stringResource(R.string.media_file_download), onClick = onDownload)
    }
}

@Composable
private fun FailedView(error: AgentFileError, onRetry: () -> Unit) {
    OpenCodeContentUnavailable(
        symbol = if (error == AgentFileError.NotFound) SFSymbol.QuestionmarkFolder else SFSymbol.ExclamationmarkTriangle,
        title = error.title,
        description = error.detail,
        action = stringResource(CoreR.string.common_try_again),
        onAction = onRetry,
    )
}

/** A document Android can't preview here: another app opens it. */
@Composable
private fun ExternalDocumentView(reference: AgentFileReference, noAppOpens: Boolean, onOpen: () -> Unit, modifier: Modifier) {
    Column(
        modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.turn),
    ) {
        FileGlyph(reference)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.compact)) {
            Text(reference.name, style = OpenCodeTextStyles.headline, color = OpenCodeTheme.colors.text, textAlign = TextAlign.Center)
            Text(reference.typeLabel, style = OpenCodeTextStyles.subheadline, color = OpenCodeTheme.colors.secondaryText)
        }
        OpenCodePrimaryButton(text = stringResource(R.string.media_file_open_in_another_app), onClick = onOpen)
        if (noAppOpens) {
            // Android only: nothing installed opens this type (Share still saves it to Files).
            Text(
                stringResource(R.string.media_file_no_app_message),
                style = OpenCodeTextStyles.footnote,
                color = OpenCodeTheme.colors.secondaryText,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag("agent-file-no-app"),
            )
        }
    }
}

/** A PDF's pages, rendered as they scroll into view. */
@Composable
private fun AgentPdfView(document: AgentPdfDocument, modifier: Modifier) {
    BoxWithConstraints(modifier.fillMaxSize().background(OpenCodeTheme.colors.groupedCanvas)) {
        val widthPx = with(LocalDensity.current) { (maxWidth - TranscriptMetrics.standard * 2).roundToPx() }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = TranscriptMetrics.standard,
                top = TranscriptMetrics.standard,
                end = TranscriptMetrics.standard,
                bottom = TranscriptMetrics.standard + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.standard),
        ) {
            items(count = document.pageCount, key = { it }) { index ->
                val bitmap by produceState<android.graphics.Bitmap?>(null, document, index, widthPx) {
                    value = document.render(index, widthPx)
                }
                val pageDescription = stringResource(R.string.media_file_pdf_page_description, index + 1, document.pageCount)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(document.pageAspects[index].coerceAtLeast(0.05f))
                        .background(Color.White)
                        .semantics { contentDescription = pageDescription },
                ) {
                    bitmap?.let {
                        Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    }
                }
            }
        }
    }
}

/**
 * The player, with a glyph and name above the controls for audio. Video controls hide while playing
 * and come back with a tap.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun AgentFilePlayer(player: Player, isAudio: Boolean, name: String, isClear: Boolean, pip: AgentFilePictureInPicture?, modifier: Modifier) {
    var controlsShown by remember { mutableStateOf(true) }
    val playPause = rememberPlayPauseButtonState(player)
    // Letterboxing shows the dark room behind, so it lifts with a drag.
    Box(
        modifier
            .fillMaxSize()
            .background(if (isClear && !isAudio) Color.Transparent else Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = !isAudio) {
                controlsShown = !controlsShown
            },
    ) {
        if (isAudio) {
            AudioArtwork(name)
        } else {
            ContentFrame(
                player = player,
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coordinates ->
                        // Where the letterboxed video sits: the PiP window grows out of it.
                        val pipState = pip ?: return@onGloballyPositioned
                        val bounds = coordinates.boundsInWindow()
                        val video = player.videoSize
                        pipState.videoFrame = if (video.width > 0 && video.height > 0 && bounds.width > 0f && bounds.height > 0f) {
                            val aspect = video.width * video.pixelWidthHeightRatio / video.height
                            val width = minOf(bounds.width, bounds.height * aspect)
                            val height = width / aspect
                            Rect(bounds.center.x - width / 2, bounds.center.y - height / 2, bounds.center.x + width / 2, bounds.center.y + height / 2)
                        } else {
                            bounds
                        }
                    },
            )
        }
        LaunchedEffect(controlsShown, playPause.showPlay, isAudio) {
            if (!isAudio && controlsShown && !playPause.showPlay) {
                delay(3_000)
                controlsShown = false
            }
        }
        if (controlsShown || isAudio) {
            PlayerControls(player, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun PlayerControls(player: Player, modifier: Modifier) {
    val playPause = rememberPlayPauseButtonState(player)
    val progress = rememberProgressStateWithTickInterval(player, 250L)
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val duration = progress.durationMs.coerceAtLeast(0)
    val position = scrubbing?.toLong() ?: progress.currentPositionMs
    val playbackPositionDescription = stringResource(R.string.media_player_position_description)
    Column(
        modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.45f))
            .navigationBarsPadding()
            .padding(horizontal = TranscriptMetrics.contentGutter, vertical = TranscriptMetrics.clustered),
    ) {
        Slider(
            value = position.coerceIn(0, maxOf(duration, 1)).toFloat(),
            onValueChange = { scrubbing = it },
            onValueChangeFinished = {
                scrubbing?.let { player.seekTo(it.toLong()) }
                scrubbing = null
            },
            valueRange = 0f..maxOf(duration, 1).toFloat(),
            enabled = duration > 0,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White),
            modifier = Modifier.semantics { contentDescription = playbackPositionDescription },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(timeText(position), style = OpenCodeTextStyles.caption1.copy(fontFeatureSettings = "tnum"), color = Color.White)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = playPause::onClick, enabled = playPause.isEnabled) {
                OpenCodeIcon(
                    if (playPause.showPlay) "play.fill" else "pause.fill",
                    contentDescription = if (playPause.showPlay) stringResource(R.string.media_player_play) else stringResource(R.string.media_player_pause),
                    tint = Color.White,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (duration > 0) "-" + timeText(duration - position) else "--:--",
                style = OpenCodeTextStyles.caption1.copy(fontFeatureSettings = "tnum"),
                color = Color.White,
            )
        }
    }
}

@Composable
internal fun AudioArtwork(name: String) {
    val reducedMotion = rememberReduceMotion()
    val pulse = if (reducedMotion) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "waveform")
        val value by transition.animateFloat(0.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "waveform")
        value
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp)
            .clearAndSetSemantics {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.turn, Alignment.CenterVertically),
    ) {
        OpenCodeIcon("waveform", contentDescription = null, size = 72.dp, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.alpha(pulse))
        Text(name, style = OpenCodeTextStyles.headline, color = Color.White, maxLines = 2, textAlign = TextAlign.Center)
    }
}

/** "Downloading 1.2 MB of 3.1 MB"; before the first bytes, just the size. */
fun progressText(received: Long, expected: Long?): String {
    val total = expected?.takeIf { it > 0 }?.let(::fileSizeText)
    if (received <= 0) {
        if (total != null) return localized(R.string.media_file_downloading_size, total)
        return localized(R.string.media_file_downloading)
    }
    val done = fileSizeText(received)
    if (total != null) return localized(R.string.media_file_downloading_progress, done, total)
    return localized(R.string.media_file_downloading_size, done)
}

/** `ByteCountFormatter` `.file`: decimal units ("Zero KB", "12 bytes", "345 KB", "1.2 MB"). */
internal fun fileSizeText(bytes: Long): String {
    if (bytes == 0L) return localized(R.string.media_file_size_zero)
    if (bytes < 1000) return localizedPlural(R.plurals.media_file_size_bytes, bytes.toInt(), bytes.toInt())
    val units = listOf(
        R.string.media_file_size_kilobytes,
        R.string.media_file_size_megabytes,
        R.string.media_file_size_gigabytes,
        R.string.media_file_size_terabytes,
    )
    var value = bytes / 1000.0
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) {
        value /= 1000
        unit++
    }
    val text = if (unit == 0) {
        String.format(appFormattingLocale(), "%d", Math.round(value))
    } else {
        val rounded = Math.round(value * 10) / 10.0
        if (rounded == Math.floor(rounded)) {
            String.format(appFormattingLocale(), "%d", rounded.toLong())
        } else {
            String.format(appFormattingLocale(), "%.1f", rounded)
        }
    }
    return localized(units[unit], text)
}

private fun timeText(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0) / 1000)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs) else String.format(Locale.US, "%d:%02d", minutes, secs)
}
