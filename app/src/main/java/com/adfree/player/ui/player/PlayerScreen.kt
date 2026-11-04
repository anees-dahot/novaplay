package com.adfree.player.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.adfree.player.Accent
import com.adfree.player.NavyHigh
import com.adfree.player.NavySurface
import com.adfree.player.data.formatTime
import com.adfree.player.ui.Motion
import com.adfree.player.ui.Type
import com.adfree.player.ui.glass
import com.adfree.player.ui.pressable
import kotlinx.coroutines.delay
import kotlin.math.abs

private enum class Sheet { SPEED, AUDIO, SUBTITLES, ASPECT, CHAPTERS, MORE }
private enum class GestureMode { NONE, SEEK, BRIGHTNESS, VOLUME, PINCH }

private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f)
private val SUB_SCALES = listOf(75, 100, 125, 150, 200)
private val Secondary = Color(0xFFA9B4C9)
/** Tabular digits so time labels don't jitter while counting. */
private val Tabular = Type.footnote.copy(fontFeatureSettings = "tnum")

@Composable
fun PlayerScreen(s: PlayerState, c: PlayerController) {
    var sheet by remember { mutableStateOf<Sheet?>(null) }

    // Auto-hide controls after 3.5s of no interaction while playing.
    LaunchedEffect(s.controlsVisible, s.playing, s.interaction, sheet, s.seeking) {
        if (s.controlsVisible && s.playing && sheet == null && !s.seeking) {
            delay(3500)
            s.controlsVisible = false
        }
    }
    // Feedback bubbles disappear shortly after the last update.
    LaunchedEffect(s.gestureTick) {
        delay(900)
        s.gestureText = null
        s.hud = null
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { c.createVideoView(it) }, modifier = Modifier.fillMaxSize())

        // Night mode: dim + warm tint over the video.
        if (s.nightMode) Box(Modifier.fillMaxSize().background(Color(0x66301600)))

        if (!s.started) {
            CircularProgressIndicator(
                Modifier.align(Alignment.Center).size(40.dp),
                color = Color.White, strokeWidth = 3.dp,
            )
        }

        if (s.inPip) return@Box

        Box(Modifier.fillMaxSize().composedGestures(s, c))

        // Brightness / volume HUD (iOS-style level pill).
        AnimatedVisibility(
            visible = s.hud != null,
            enter = fadeIn(Motion.snappy()) + scaleIn(Motion.snappy(), initialScale = 0.9f),
            exit = fadeOut(Motion.smooth()) + scaleOut(Motion.smooth(), targetScale = 0.9f),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp),
        ) {
            val hud = s.hud ?: Hud(false, 0f)
            Row(
                Modifier.glass().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (hud.volume) Icons.AutoMirrored.Filled.VolumeUp else Icons.Default.LightMode,
                    null, tint = Color.White, modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(width = 140.dp, height = 6.dp).clip(CircleShape).background(Color(0x40FFFFFF))) {
                    Box(Modifier.fillMaxWidth(hud.level.coerceIn(0f, 1f)).height(6.dp).background(Color.White))
                }
            }
        }

        // Text feedback (seek amount, 2x, toggles).
        val bubble = if (s.holdingFast) "2×  ▶▶" else s.gestureText
        AnimatedVisibility(
            visible = bubble != null,
            enter = fadeIn(Motion.snappy()) + scaleIn(Motion.snappy(), initialScale = 0.9f),
            exit = fadeOut(Motion.smooth()),
            modifier = Modifier.align(if (s.holdingFast) Alignment.TopCenter else Alignment.Center)
                .padding(top = if (s.holdingFast) 28.dp else 0.dp),
        ) {
            Text(
                bubble.orEmpty(),
                color = Color.White,
                style = Type.headline,
                modifier = Modifier.glass().padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }

        if (s.locked) {
            AnimatedVisibility(
                visible = s.controlsVisible,
                enter = fadeIn(Motion.smooth()),
                exit = fadeOut(Motion.smooth()),
                modifier = Modifier.align(Alignment.CenterStart)
                    .windowInsetsPadding(WindowInsets.displayCutout).padding(24.dp),
            ) {
                GlassCircle(Icons.Default.Lock, "Unlock", 52) { s.locked = false; s.bump() }
            }
        } else {
            AnimatedVisibility(
                visible = s.controlsVisible,
                enter = fadeIn(Motion.smooth()) + scaleIn(Motion.smooth(), initialScale = 0.97f),
                exit = fadeOut(Motion.smooth()) + scaleOut(Motion.smooth(), targetScale = 0.97f),
                modifier = Modifier.fillMaxSize(),
            ) {
                Controls(s, c, onSheet = { sheet = it; s.bump() })
            }
        }
    }

    sheet?.let { current ->
        PlayerSheet(onDismiss = { sheet = null }) {
            when (current) {
                Sheet.SPEED -> ChoiceList(
                    "Playback Speed", SPEEDS.map { it to "${formatRate(it)}×" }, s.rate,
                ) { c.setRate(it); sheet = null }
                Sheet.AUDIO -> ChoiceList(
                    "Audio", s.audioTracks.map { it.id to it.name }, s.audioTrack,
                ) { c.selectAudio(it); sheet = null }
                Sheet.ASPECT -> ChoiceList(
                    "Aspect Ratio", VideoScale.entries.map { it to it.label }, s.scale,
                ) { c.setScale(it); sheet = null }
                Sheet.CHAPTERS -> ChoiceList(
                    "Chapters",
                    s.chapters.map { it.timeMs to "${formatTime(it.timeMs)}   ${it.name}" },
                    s.chapters.lastOrNull { it.timeMs <= s.time }?.timeMs ?: -1L,
                ) { c.seekTo(it); sheet = null }
                Sheet.SUBTITLES -> SubtitleSheet(s, c, close = { sheet = null })
                Sheet.MORE -> MoreSheet(s, c, close = { sheet = null }, openChapters = { sheet = Sheet.CHAPTERS })
            }
        }
    }
}

private fun formatRate(r: Float) = if (r % 1f == 0f) r.toInt().toString() else r.toString()

/** Tap = show/hide, double-tap sides = ±10s, hold = 2x, swipe left/right half = brightness/volume,
 *  horizontal swipe = seek, pinch = crop/fit. */

@Composable
private fun Modifier.composedGestures(s: PlayerState, c: PlayerController): Modifier {
    val haptics = LocalHapticFeedback.current
    return this
        .pointerInput(s.locked) {
            detectTapGestures(
                onPress = {
                    tryAwaitRelease()
                    if (s.holdingFast) c.stopHoldFast()
                },
                onLongPress = {
                    if (!s.locked) {
                        c.startHoldFast()
                        if (s.holdingFast) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                },
                onTap = {
                    s.controlsVisible = !s.controlsVisible
                    s.bump()
                },
                onDoubleTap = { pos ->
                    if (s.locked) return@detectTapGestures
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (pos.x < size.width / 2) {
                        c.seekBy(-10_000); s.flash("«  10s")
                    } else {
                        c.seekBy(10_000); s.flash("10s  »")
                    }
                },
            )
        }
        .pointerInput(s.locked) {
            if (s.locked) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val slop = viewConfiguration.touchSlop
                val leftSide = down.position.x < size.width / 2
                val startTime = s.time
                var mode = GestureMode.NONE
                var dx = 0f
                var dy = 0f
                var seekTarget = startTime
                var startDist = 0f
                var endDist = 0f

                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break

                    if (pressed.size >= 2) {
                        val dist = (pressed[0].position - pressed[1].position).getDistance()
                        if (mode != GestureMode.PINCH) {
                            s.seeking = false
                            mode = GestureMode.PINCH
                            startDist = dist
                        }
                        endDist = dist
                        event.changes.forEach { it.consume() }
                        continue
                    }
                    if (mode == GestureMode.PINCH) continue

                    val change = pressed[0]
                    val delta = change.position - change.previousPosition
                    dx += delta.x
                    dy += delta.y
                    if (mode == GestureMode.NONE && (abs(dx) > slop || abs(dy) > slop)) {
                        mode = when {
                            abs(dx) > abs(dy) -> GestureMode.SEEK.also { s.seeking = true }
                            leftSide -> GestureMode.BRIGHTNESS
                            else -> GestureMode.VOLUME
                        }
                    }
                    when (mode) {
                        GestureMode.SEEK -> {
                            // Full screen width = 90 seconds.
                            val offset = (dx / size.width * 90_000).toLong()
                            seekTarget = (startTime + offset).coerceIn(0, s.length.coerceAtLeast(0))
                            s.time = seekTarget
                            val sign = if (offset >= 0) "+" else "−"
                            s.flash("${formatTime(seekTarget)}   $sign${abs(offset) / 1000}s")
                        }
                        GestureMode.BRIGHTNESS -> {
                            s.hud = Hud(false, c.adjustBrightness(-delta.y / size.height) / 100f)
                            s.gestureTick++
                        }
                        GestureMode.VOLUME -> {
                            s.hud = Hud(true, c.adjustVolume(-delta.y / size.height) / 100f)
                            s.gestureTick++
                        }
                        else -> Unit
                    }
                    if (mode != GestureMode.NONE) change.consume()
                }

                when (mode) {
                    GestureMode.SEEK -> {
                        c.seekTo(seekTarget)
                        s.seeking = false
                    }
                    GestureMode.PINCH -> {
                        val target = when {
                            endDist > startDist * 1.15f -> VideoScale.CROP
                            endDist < startDist * 0.87f -> VideoScale.FIT
                            else -> null
                        }
                        if (target != null && target != s.scale) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            c.setScale(target)
                        }
                    }
                    else -> Unit
                }
                c.endGesture()
            }
        }
}

@Composable
private fun Controls(s: PlayerState, c: PlayerController, onSheet: (Sheet) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x33000000)) // light scrim so glass reads over bright video
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // ── Top: title capsule (left) + tools capsule (right)
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f, fill = false).glass().pressable(onClick = c::back)
                    .padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBackIos, "Back", tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(c.videoTitle, color = Color.White, style = Type.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(12.dp))
            Row(Modifier.glass().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${formatRate(s.rate)}×",
                    color = Color.White, style = Type.headline.copy(fontFeatureSettings = "tnum"),
                    modifier = Modifier.pressable { onSheet(Sheet.SPEED) }.padding(horizontal = 10.dp, vertical = 10.dp),
                )
                ToolIcon(Icons.Default.Audiotrack, "Audio") { onSheet(Sheet.AUDIO) }
                ToolIcon(Icons.Default.Subtitles, "Subtitles") { onSheet(Sheet.SUBTITLES) }
                ToolIcon(Icons.Default.AspectRatio, "Aspect ratio") { onSheet(Sheet.ASPECT) }
                ToolIcon(Icons.Default.PictureInPictureAlt, "Picture in picture") { c.enterPip() }
                ToolIcon(Icons.Default.MoreHoriz, "More") { onSheet(Sheet.MORE) }
            }
        }

        // ── Center transport
        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassCircle(Icons.Default.SkipPrevious, "Previous", 48) { c.playPrev(); s.bump() }
            GlassCircle(Icons.Default.Replay10, "Back 10 seconds", 56) { c.seekBy(-10_000); s.bump() }
            Box(
                Modifier.size(76.dp).glass(CircleShape).pressable(pressedScale = 0.9f, onClick = c::togglePlay),
                contentAlignment = Alignment.Center,
            ) {
                // Icon morphs (scale + fade) rather than snapping.
                AnimatedContent(
                    targetState = s.playing,
                    transitionSpec = {
                        (scaleIn(Motion.snappy(), initialScale = 0.6f) + fadeIn(Motion.snappy())) togetherWith
                            (scaleOut(Motion.snappy(), targetScale = 0.6f) + fadeOut(Motion.snappy()))
                    },
                    label = "playPause",
                ) { playing ->
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (playing) "Pause" else "Play",
                        tint = Color.White, modifier = Modifier.size(42.dp),
                    )
                }
            }
            GlassCircle(Icons.Default.Forward10, "Forward 10 seconds", 56) { c.seekBy(10_000); s.bump() }
            val canNext = s.hasNext || s.loopMode == LoopMode.ALL
            GlassCircle(Icons.Default.SkipNext, "Next", 48, enabled = canNext) { c.playNext(); s.bump() }
        }

        // ── Bottom: scrubber card
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .glass(RoundedCornerShape(22.dp))
                .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 6.dp),
        ) {
            Scrubber(s, c)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(s.time), color = Color.White, style = Tabular)
                Spacer(Modifier.width(10.dp))
                ToolIcon(Icons.Default.LockOpen, "Lock screen") {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    s.locked = true; s.bump()
                }
                if (s.abStart >= 0) {
                    Text(
                        if (s.abEnd >= 0) "A–B  ${formatTime(s.abStart)} – ${formatTime(s.abEnd)}" else "A  ${formatTime(s.abStart)}",
                        color = Accent, style = Type.caption,
                        modifier = Modifier.pressable { c.cycleAbRepeat() }.padding(8.dp),
                    )
                }
                if (s.chapters.isNotEmpty()) {
                    Text(
                        "Chapters",
                        color = Color.White, style = Type.caption,
                        modifier = Modifier.pressable { onSheet(Sheet.CHAPTERS) }.padding(8.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                ToolIcon(Icons.Default.ScreenRotation, "Rotate") { c.toggleRotation(); s.bump() }
                Spacer(Modifier.width(10.dp))
                Text("−" + formatTime((s.length - s.time).coerceAtLeast(0)), color = Secondary, style = Tabular)
            }
        }
    }
}

/**
 * iOS-style scrubber: thin track that thickens while you drag, follows the finger 1:1,
 * shows a frame preview above the finger, and marks chapters.
 */
@Composable
private fun Scrubber(s: PlayerState, c: PlayerController) {
    val max = s.length.toFloat().coerceAtLeast(1f)
    val thickness by animateDpAsState(if (s.seeking) 12.dp else 5.dp, Motion.snappy(), label = "track")
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(s.seeking, s.time / 2000) {
        if (!s.seeking) { preview = null; return@LaunchedEffect }
        delay(60)
        preview = c.previewFrame(s.time)?.asImageBitmap()
    }

    BoxWithConstraints(Modifier.fillMaxWidth().height(28.dp)) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        fun timeAt(x: Float) = (x / widthPx * max).toLong().coerceIn(0, s.length.coerceAtLeast(0))

        // Frame preview floats above the finger.
        if (s.seeking) {
            val previewW = 168.dp
            val fraction = (s.time / max).coerceIn(0f, 1f)
            val x = maxWidth * fraction - previewW / 2
            Column(
                Modifier.offset(x = x.coerceIn(0.dp, (maxWidth - previewW).coerceAtLeast(0.dp)), y = (-120).dp)
                    .width(previewW),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(previewW, 94.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black)
                        .glass(RoundedCornerShape(12.dp)),
                ) {
                    preview?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
                Spacer(Modifier.height(4.dp))
                Text(formatTime(s.time), color = Color.White, style = Tabular, modifier = Modifier.glass().padding(horizontal = 8.dp, vertical = 2.dp))
            }
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(s.length) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        s.seeking = true
                        s.time = timeAt(down.position.x)
                        s.bump()
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull() ?: break
                            if (!ch.pressed) break
                            s.time = timeAt(ch.position.x)
                            ch.consume()
                        }
                        c.seekTo(s.time)
                        s.seeking = false
                    }
                },
        ) {
            val h = thickness.toPx()
            val top = (size.height - h) / 2
            val r = CornerRadius(h / 2, h / 2)
            drawRoundRect(Color(0x40FFFFFF), Offset(0f, top), Size(size.width, h), r)
            drawRoundRect(Color.White, Offset(0f, top), Size(size.width * (s.time / max).coerceIn(0f, 1f), h), r)
            // Chapter gaps, like iOS chapter segments.
            if (s.chapters.size > 1 && s.length > 0) {
                s.chapters.drop(1).forEach { ch ->
                    val cx = size.width * (ch.timeMs / max)
                    drawRect(Color(0xCC0B1220), Offset(cx - 1.5f, top), Size(3f, h))
                }
            }
        }
    }
}

@Composable
private fun RowScope.ToolIcon(icon: ImageVector, desc: String, onClick: () -> Unit) {
    Box(Modifier.size(42.dp).pressable(pressedScale = 0.85f, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun GlassCircle(icon: ImageVector, desc: String, size: Int, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp).glass(CircleShape)
            .then(if (enabled) Modifier.pressable(pressedScale = 0.88f, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = if (enabled) Color.White else Color(0x55FFFFFF), modifier = Modifier.size((size * 0.55f).dp))
    }
}

// ───────── Sheets (iOS-style bottom sheets instead of dialogs) ─────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 560.dp,
        containerColor = NavySurface,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        scrimColor = Color(0x66000000),
    ) {
        Column(
            Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) { content() }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, style = Type.title, color = Color.White, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(NavyHigh)) { content() }
}

@Composable
private fun GroupDivider() = HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = Color(0x22FFFFFF))

@Composable
private fun <T> ChoiceList(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    SheetTitle(title)
    if (options.isEmpty()) {
        Text("None available", style = Type.body, color = Secondary, modifier = Modifier.padding(4.dp))
        return
    }
    Group {
        options.forEachIndexed { i, (value, label) ->
            Row(
                Modifier.fillMaxWidth().pressable(pressedScale = 0.98f) { onSelect(value) }
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = Type.body, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (value == selected) Icon(Icons.Default.Check, null, tint = Accent, modifier = Modifier.size(20.dp))
            }
            if (i < options.lastIndex) GroupDivider()
        }
    }
}

@Composable
private fun ActionRow(text: String, color: Color = Accent, onClick: () -> Unit) {
    Text(
        text, style = Type.body, color = color,
        modifier = Modifier.fillMaxWidth().pressable(pressedScale = 0.98f, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
    )
}

@Composable
private fun ToggleRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = Type.body, color = Color.White, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Color(0xFF30D158), checkedThumbColor = Color.White,
                uncheckedTrackColor = Color(0x33FFFFFF), uncheckedThumbColor = Color.White,
                uncheckedBorderColor = Color.Transparent, checkedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** iOS segmented control with a sliding selection pill. */
@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x22FFFFFF)).padding(2.dp),
    ) {
        val segW = maxWidth / options.size
        val idx = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
        val x by animateDpAsState(segW * idx, Motion.smooth(), label = "seg")
        Box(Modifier.offset(x = x).width(segW).fillMaxSize().clip(RoundedCornerShape(8.dp)).background(Color(0xFF3A4C6E)))
        Row(Modifier.fillMaxSize()) {
            options.forEach { (value, label) ->
                Box(
                    Modifier.weight(1f).fillMaxSize().pressable(pressedScale = 0.95f) { onSelect(value) },
                    contentAlignment = Alignment.Center,
                ) { Text(label, style = Type.footnote, color = Color.White) }
            }
        }
    }
}

@Composable
private fun SubtitleSheet(s: PlayerState, c: PlayerController, close: () -> Unit) {
    SheetTitle("Subtitles")
    if (s.spuTracks.isNotEmpty()) {
        Group {
            s.spuTracks.forEachIndexed { i, t ->
                Row(
                    Modifier.fillMaxWidth().pressable(pressedScale = 0.98f) { c.selectSpu(t.id) }
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(t.name, style = Type.body, color = Color.White, modifier = Modifier.weight(1f))
                    if (t.id == s.spuTrack) Icon(Icons.Default.Check, null, tint = Accent, modifier = Modifier.size(20.dp))
                }
                if (i < s.spuTracks.lastIndex) GroupDivider()
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    Group { ActionRow("Load Subtitle File…") { c.pickSubtitleFile(); close() } }

    Spacer(Modifier.height(20.dp))
    Text("DELAY", style = Type.caption, color = Secondary, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
    Group {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("−", style = Type.title, color = Accent,
                modifier = Modifier.pressable { c.shiftSpuDelay(-250) }.padding(horizontal = 16.dp, vertical = 6.dp))
            Text("${s.spuDelayMs} ms", style = Type.body.copy(fontFeatureSettings = "tnum"), color = Color.White,
                modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("+", style = Type.title, color = Accent,
                modifier = Modifier.pressable { c.shiftSpuDelay(250) }.padding(horizontal = 16.dp, vertical = 6.dp))
        }
    }

    Spacer(Modifier.height(20.dp))
    Text("SIZE", style = Type.caption, color = Secondary, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
    Segmented(SUB_SCALES.map { it to "$it%" }, s.subScale) { c.setSubScale(it) }
}

@Composable
private fun MoreSheet(s: PlayerState, c: PlayerController, close: () -> Unit, openChapters: () -> Unit) {
    SheetTitle("Options")
    Group {
        ActionRow("Take Screenshot") { close(); c.takeScreenshot() }
        GroupDivider()
        ActionRow(
            when {
                s.abStart < 0 -> "A–B Repeat: Set Point A"
                s.abEnd < 0 -> "A–B Repeat: Set Point B"
                else -> "Turn Off A–B Repeat"
            }
        ) { c.cycleAbRepeat(); if (s.abEnd >= 0 || s.abStart < 0) close() }
        if (s.chapters.isNotEmpty()) {
            GroupDivider()
            ActionRow("Chapters") { openChapters() }
        }
    }

    Spacer(Modifier.height(20.dp))
    Text("REPEAT", style = Type.caption, color = Secondary, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
    Segmented(listOf(LoopMode.OFF to "Off", LoopMode.ONE to "This Video", LoopMode.ALL to "All"), s.loopMode) { c.setLoopMode(it) }

    Spacer(Modifier.height(20.dp))
    Group {
        ToggleRow("Auto-Play Next", s.autoNext) { c.setAutoNext(it) }
        GroupDivider()
        ToggleRow("Background Play", s.backgroundPlay) { c.setBackgroundPlay(it) }
        GroupDivider()
        ToggleRow("Night Mode", s.nightMode) { c.setNightMode(it) }
        GroupDivider()
        ToggleRow("Hardware Decoding", s.hwDecoder) { close(); c.setHwDecoder(it) }
    }
    Text(
        "Turn off Hardware Decoding if a video stutters or shows a black screen.",
        style = Type.footnote, color = Secondary, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
    )
}
