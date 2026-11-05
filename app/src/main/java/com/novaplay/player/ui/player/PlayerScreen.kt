package com.novaplay.player.ui.player

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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SubtitlesOff
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
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
import com.novaplay.player.Accent
import com.novaplay.player.NavyHigh
import com.novaplay.player.NavySurface
import com.novaplay.player.data.formatTime
import com.novaplay.player.ui.Motion
import com.novaplay.player.ui.Type
import com.novaplay.player.ui.pressable
import kotlinx.coroutines.delay
import kotlin.math.abs

private enum class Sheet { SPEED, AUDIO, SUBTITLES, ASPECT, CHAPTERS, MORE, SLEEP, CAST, ONLINE_SUBS }
private enum class GestureMode { NONE, SEEK, BRIGHTNESS, VOLUME, PINCH }

private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f)
private val SUB_SCALES = listOf(75, 100, 125, 150, 200)
private val Secondary = Color(0xFF9A9AA0)
private val Dim = Color(0xB3FFFFFF)
private val Scrim = Color(0x80000000)
private val TrackColor = Color(0x4DFFFFFF)
private val TimeW = 56.dp
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
    // Feedback disappears shortly after the last update.
    LaunchedEffect(s.gestureTick) {
        delay(800)
        s.gestureText = null
        s.hud = null
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c.createVideoView(it) },
            update = { v ->
                // View transform (not a Compose layer) so the SurfaceView itself scales/moves.
                v.scaleX = s.zoom; v.scaleY = s.zoom
                v.translationX = s.panX; v.translationY = s.panY
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Night mode: dim + warm tint over the video.
        if (s.nightMode) Box(Modifier.fillMaxSize().background(Color(0x66301600)))

        if (!s.started) {
            CircularProgressIndicator(
                Modifier.align(Alignment.Center).size(36.dp),
                color = Color.White, strokeWidth = 2.5.dp,
            )
        }

        if (s.inPip) return@Box

        Box(Modifier.fillMaxSize().composedGestures(s, c))

        // Controls sit under the feedback so a seek bubble is never hidden by the bar.
        if (s.locked) {
            AnimatedVisibility(
                visible = s.controlsVisible,
                enter = fadeIn(Motion.smooth()),
                exit = fadeOut(Motion.smooth()),
                modifier = Modifier.align(Alignment.CenterStart)
                    .windowInsetsPadding(WindowInsets.displayCutout).padding(24.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(52.dp).background(Scrim, CircleShape)
                            .pressable(pressedScale = 0.88f) { s.locked = false; s.bump() },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Default.Lock, "Unlock", tint = Color.White, modifier = Modifier.size(24.dp)) }
                    Spacer(Modifier.height(6.dp))
                    Text("Tap to unlock", color = Dim, style = Type.caption)
                }
            }
        } else {
            AnimatedVisibility(
                visible = s.controlsVisible,
                enter = fadeIn(Motion.smooth()),
                exit = fadeOut(Motion.smooth()),
                modifier = Modifier.fillMaxSize(),
            ) {
                Controls(s, c, onSheet = { sheet = it; s.bump() })
            }
        }

        // Brightness (left) / volume (right) level bar.
        val hud = s.hud
        AnimatedVisibility(
            visible = hud != null,
            enter = fadeIn(Motion.snappy()),
            exit = fadeOut(Motion.smooth()),
            modifier = Modifier.align(if (hud?.volume == true) Alignment.CenterEnd else Alignment.CenterStart)
                .windowInsetsPadding(WindowInsets.displayCutout).padding(horizontal = 40.dp),
        ) {
            LevelBar(hud ?: Hud(false, 0f))
        }

        // Hold-to-2× pill.
        AnimatedVisibility(
            visible = s.holdingFast,
            enter = fadeIn(Motion.snappy()),
            exit = fadeOut(Motion.smooth()),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
        ) {
            Row(
                Modifier.background(Scrim, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("2×", color = Color.White, style = Type.subhead.copy(fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Default.FastForward, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }

        // Text feedback (seek amount, toggles). Double-tap seeks show on their own half.
        val text = s.gestureText
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val x = when (s.flashSide) { -1 -> -maxWidth / 4; 1 -> maxWidth / 4; else -> 0.dp }
            AnimatedVisibility(
                visible = text != null,
                enter = fadeIn(Motion.snappy()),
                exit = fadeOut(Motion.smooth()),
                modifier = Modifier.align(Alignment.Center).offset(x = x),
            ) {
                Text(
                    text.orEmpty(),
                    color = Color.White,
                    style = Type.headline.copy(fontFeatureSettings = "tnum"),
                    modifier = Modifier.background(Scrim, RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                )
            }
        }
    }

    sheet?.let { current ->
        PlayerSheet(onDismiss = { sheet = null }) {
            when (current) {
                Sheet.SPEED -> ChoiceList(
                    "Playback Speed", SPEEDS.map { it to "${formatRate(it)}×" }, s.rate,
                ) { c.setRate(it); sheet = null }
                Sheet.AUDIO -> AudioSheet(s, c)
                Sheet.ASPECT -> ChoiceList(
                    "Aspect Ratio", VideoScale.entries.map { it to it.label }, s.scale,
                ) { s.zoom = 1f; s.panX = 0f; s.panY = 0f; c.setScale(it); sheet = null }
                Sheet.SLEEP -> ChoiceList(
                    "Sleep Timer",
                    listOf(0 to "Off", 15 to "15 minutes", 30 to "30 minutes", 45 to "45 minutes",
                        60 to "1 hour", 90 to "1½ hours", -1 to "End of this video"),
                    when { s.sleepAtEnd -> -1; s.sleepAt > 0 -> -2; else -> 0 },
                ) { c.setSleep(it); sheet = null }
                Sheet.CAST -> CastSheet(s, c, close = { sheet = null })
                Sheet.CHAPTERS -> ChoiceList(
                    "Chapters",
                    s.chapters.map { it.timeMs to "${formatTime(it.timeMs)}   ${it.name}" },
                    s.chapters.lastOrNull { it.timeMs <= s.time }?.timeMs ?: -1L,
                ) { c.seekTo(it); sheet = null }
                Sheet.SUBTITLES -> SubtitleSheet(s, c, close = { sheet = null }, openOnline = { sheet = Sheet.ONLINE_SUBS })
                Sheet.ONLINE_SUBS -> OnlineSubsSheet(s, c, close = { sheet = null })
                Sheet.MORE -> MoreSheet(
                    s, c, close = { sheet = null },
                    openChapters = { sheet = Sheet.CHAPTERS },
                    openSleep = { sheet = Sheet.SLEEP },
                    openCast = { sheet = Sheet.CAST },
                )
            }
        }
    }
}

private fun formatRate(r: Float) = if (r % 1f == 0f) r.toInt().toString() else r.toString()

@Composable
private fun LevelBar(hud: Hud) {
    val level = hud.level.coerceIn(0f, 1f)
    Column(
        Modifier.background(Scrim, RoundedCornerShape(16.dp)).padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            when {
                !hud.volume -> Icons.Default.LightMode
                level == 0f -> Icons.AutoMirrored.Filled.VolumeOff
                else -> Icons.AutoMirrored.Filled.VolumeUp
            },
            null, tint = Color.White, modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.height(10.dp))
        Box(Modifier.size(width = 4.dp, height = 120.dp).clip(CircleShape).background(TrackColor)) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(level).background(Color.White))
        }
        Spacer(Modifier.height(8.dp))
        Text("${(level * 100).toInt()}", color = Color.White, style = Tabular)
    }
}

/** Tap = show/hide, double-tap sides = ±10s (keep tapping to stack), hold = 2x,
 *  swipe left/right half = brightness/volume, horizontal swipe = seek, pinch = zoom + pan. */
@Composable
private fun Modifier.composedGestures(s: PlayerState, c: PlayerController): Modifier {
    val haptics = LocalHapticFeedback.current
    // Double-tap seek streak: further single taps on the same side keep adding 10s.
    val streak = remember { SeekStreak() }
    return this
        .pointerInput(s.locked) {
            fun seekTap(left: Boolean) {
                val now = System.currentTimeMillis()
                if (now - streak.at > 900 || streak.left != left) streak.total = 0
                streak.left = left
                streak.at = now
                streak.total += 10
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                c.seekBy(if (left) -10_000 else 10_000)
                s.flash(if (left) "−${streak.total}s" else "+${streak.total}s", if (left) -1 else 1)
            }
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
                onTap = { pos ->
                    val left = pos.x < size.width / 2
                    if (!s.locked && System.currentTimeMillis() - streak.at < 900 && streak.left == left) {
                        seekTap(left)
                    } else {
                        s.controlsVisible = !s.controlsVisible
                        s.bump()
                    }
                },
                onDoubleTap = { pos ->
                    if (s.locked) return@detectTapGestures
                    // Middle third does nothing special; play/pause lives on the button.
                    val third = size.width / 3
                    when {
                        pos.x < third -> seekTap(true)
                        pos.x > third * 2 -> seekTap(false)
                        else -> { c.togglePlay(); s.flash(if (s.playing) "Paused" else "Playing") }
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
                // Ignore swipes that start in the system-gesture strips (status bar / home bar).
                val edge = 32.dp.toPx()
                val nearEdge = down.position.y < edge || down.position.y > size.height - edge
                val startTime = s.time
                var mode = GestureMode.NONE
                var dx = 0f
                var dy = 0f
                var seekTarget = startTime
                var startDist = 0f
                var startZoom = 1f
                var lastCentroid = Offset.Zero

                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break

                    if (pressed.size >= 2) {
                        val dist = (pressed[0].position - pressed[1].position).getDistance().coerceAtLeast(1f)
                        val centroid = (pressed[0].position + pressed[1].position) / 2f
                        if (mode != GestureMode.PINCH) {
                            if (mode == GestureMode.SEEK) s.time = startTime
                            s.seeking = false
                            s.gestureText = null
                            mode = GestureMode.PINCH
                            startDist = dist
                            startZoom = s.zoom
                            lastCentroid = centroid
                        }
                        s.zoom = (startZoom * dist / startDist).coerceIn(1f, 4f)
                        val pan = centroid - lastCentroid
                        lastCentroid = centroid
                        val maxX = (s.zoom - 1f) * size.width / 2f
                        val maxY = (s.zoom - 1f) * size.height / 2f
                        s.panX = (s.panX + pan.x).coerceIn(-maxX, maxX)
                        s.panY = (s.panY + pan.y).coerceIn(-maxY, maxY)
                        s.flash("${(s.zoom * 100).toInt()}%")
                        event.changes.forEach { it.consume() }
                        continue
                    }
                    if (mode == GestureMode.PINCH || nearEdge) continue

                    val change = pressed[0]
                    val delta = change.position - change.previousPosition
                    dx += delta.x
                    dy += delta.y
                    if (mode == GestureMode.NONE && (abs(dx) > slop || abs(dy) > slop)) {
                        if (s.holdingFast) c.stopHoldFast()
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
                        // Snap back to normal when nearly unzoomed.
                        if (s.zoom < 1.08f) {
                            if (startZoom > 1f) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            s.zoom = 1f; s.panX = 0f; s.panY = 0f
                        }
                    }
                    else -> Unit
                }
                c.endGesture()
            }
        }
}

private class SeekStreak {
    var at = 0L
    var left = false
    var total = 0
}

@Composable
private fun Controls(s: PlayerState, c: PlayerController, onSheet: (Sheet) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Box(Modifier.fillMaxSize()) {
        // Gradient scrims only where text/icons sit; the middle of the picture stays clean.
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(120.dp)
                .background(Brush.verticalGradient(listOf(Color(0xA6000000), Color.Transparent))),
        )
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(150.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xBF000000)))),
        )

        Box(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            // ── Top bar: back + title, track tools on the right.
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = c::back)
                Column(Modifier.weight(1f).padding(start = 4.dp, end = 8.dp)) {
                    Text(s.title, color = Color.White, style = Type.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (s.playlistSize > 1) {
                        Text("${s.playlistIndex + 1} of ${s.playlistSize}", color = Dim, style = Type.caption)
                    }
                }
                IconBtn(Icons.Default.Audiotrack, "Audio") { onSheet(Sheet.AUDIO) }
                IconBtn(
                    if (s.spuTrack >= 0) Icons.Default.Subtitles else Icons.Default.SubtitlesOff,
                    "Subtitles",
                ) { onSheet(Sheet.SUBTITLES) }
                IconBtn(if (s.castingTo != null) Icons.Default.CastConnected else Icons.Default.Cast, "Cast") { onSheet(Sheet.CAST) }
                IconBtn(Icons.Default.PictureInPictureAlt, "Picture in picture") { c.enterPip() }
                IconBtn(Icons.Default.MoreVert, "More") { onSheet(Sheet.MORE) }
            }

            // ── Center transport: plain icons, only play/pause gets a soft backing.
            Row(
                Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(36.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBtn(Icons.Default.Replay10, "Back 10 seconds", box = 56, iconSize = 34) { c.seekBy(-10_000); s.bump() }
                Box(
                    Modifier.size(72.dp).background(Scrim, CircleShape)
                        .pressable(pressedScale = 0.9f, onClick = c::togglePlay),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(
                        targetState = s.playing,
                        transitionSpec = {
                            (scaleIn(Motion.snappy(), initialScale = 0.7f) + fadeIn(Motion.snappy())) togetherWith
                                (scaleOut(Motion.snappy(), targetScale = 0.7f) + fadeOut(Motion.snappy()))
                        },
                        label = "playPause",
                    ) { playing ->
                        Icon(
                            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (playing) "Pause" else "Play",
                            tint = Color.White, modifier = Modifier.size(44.dp),
                        )
                    }
                }
                IconBtn(Icons.Default.Forward10, "Forward 10 seconds", box = 56, iconSize = 34) { c.seekBy(10_000); s.bump() }
            }

            // ── Bottom: time · scrubber · remaining, then tool row.
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTime(s.time), color = Color.White, style = Tabular, modifier = Modifier.width(TimeW))
                    Scrubber(s, c, Modifier.weight(1f).padding(horizontal = 10.dp))
                    Text(
                        "−" + formatTime((s.length - s.time).coerceAtLeast(0)),
                        color = Dim, style = Tabular, textAlign = TextAlign.End, modifier = Modifier.width(TimeW),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(Icons.Default.LockOpen, "Lock screen") {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        s.locked = true; s.bump()
                    }
                    if (s.playlistSize > 1) {
                        IconBtn(Icons.Default.SkipPrevious, "Previous") { c.playPrev(); s.bump() }
                        IconBtn(Icons.Default.SkipNext, "Next", enabled = s.hasNext || s.loopMode == LoopMode.ALL) {
                            c.playNext(); s.bump()
                        }
                    }
                    if (s.playlistSize > 1) {
                        IconBtn(Icons.Default.Shuffle, "Shuffle", tint = if (s.shuffle) Accent else Color.White) {
                            c.setShuffle(!s.shuffle); s.bump()
                        }
                    }
                    if (!s.playing && s.started) {
                        IconBtn(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous frame") { c.frameStep(false) }
                        IconBtn(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next frame") { c.frameStep(true) }
                    }
                    if (s.sleepAt > 0 || s.sleepAtEnd) {
                        IconBtn(Icons.Default.Bedtime, "Sleep timer", tint = Accent) { onSheet(Sheet.SLEEP) }
                    }
                    if (s.abStart >= 0) {
                        Text(
                            if (s.abEnd >= 0) "A–B" else "A",
                            color = Accent, style = Type.subhead.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.pressable { c.cycleAbRepeat() }.padding(horizontal = 10.dp, vertical = 10.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (s.chapters.isNotEmpty()) {
                        IconBtn(Icons.AutoMirrored.Filled.List, "Chapters") { onSheet(Sheet.CHAPTERS) }
                    }
                    Text(
                        "${formatRate(s.rate)}×",
                        color = if (s.rate != 1f) Accent else Color.White,
                        style = Type.subhead.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                        modifier = Modifier.pressable { onSheet(Sheet.SPEED) }.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                    IconBtn(Icons.Default.AspectRatio, "Aspect ratio") { onSheet(Sheet.ASPECT) }
                    IconBtn(Icons.Default.ScreenRotation, "Rotate") { c.toggleRotation(); s.bump() }
                }
            }
        }
    }
}

/**
 * Scrubber: thin track that thickens while dragging, round thumb, follows the finger 1:1,
 * frame preview above the finger, chapter ticks.
 */
@Composable
private fun Scrubber(s: PlayerState, c: PlayerController, modifier: Modifier = Modifier) {
    val max = s.length.toFloat().coerceAtLeast(1f)
    val thickness by animateDpAsState(if (s.seeking) 6.dp else 3.dp, Motion.snappy(), label = "track")
    val thumb by animateDpAsState(if (s.seeking) 18.dp else 12.dp, Motion.snappy(), label = "thumb")
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(s.seeking, s.time / 2000) {
        if (!s.seeking) { preview = null; return@LaunchedEffect }
        delay(60)
        preview = c.previewFrame(s.time)?.asImageBitmap()
    }

    BoxWithConstraints(modifier.height(32.dp)) {
        // Frame preview floats above the finger.
        if (s.seeking && s.length > 0) {
            val previewW = 160.dp
            val fraction = (s.time / max).coerceIn(0f, 1f)
            val x = maxWidth * fraction - previewW / 2
            Column(
                Modifier.offset(x = x.coerceIn(0.dp, (maxWidth - previewW).coerceAtLeast(0.dp)), y = (-118).dp)
                    .width(previewW),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(previewW, 90.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black)
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(8.dp)),
                ) {
                    preview?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    formatTime(s.time), color = Color.White, style = Tabular,
                    modifier = Modifier.background(Scrim, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }

        Canvas(
            Modifier
                .fillMaxSize()
                // Keyed on length only; width is read live so rotation never leaves a stale mapping.
                .pointerInput(s.length) {
                    fun timeAt(x: Float) =
                        (x / size.width.coerceAtLeast(1) * max).toLong().coerceIn(0, s.length.coerceAtLeast(0))
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
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
                        s.bump()
                    }
                },
        ) {
            val h = thickness.toPx()
            val cy = size.height / 2
            val top = cy - h / 2
            val r = CornerRadius(h / 2, h / 2)
            val played = size.width * (s.time / max).coerceIn(0f, 1f)
            drawRoundRect(TrackColor, Offset(0f, top), Size(size.width, h), r)
            drawRoundRect(Accent, Offset(0f, top), Size(played, h), r)
            if (s.chapters.size > 1 && s.length > 0) {
                s.chapters.drop(1).forEach { ch ->
                    val cx = size.width * (ch.timeMs / max)
                    drawRect(Color.Black, Offset(cx - 1f, top), Size(2f, h))
                }
            }
            drawCircle(Color.White, thumb.toPx() / 2, Offset(played.coerceIn(0f, size.width), cy))
        }
    }
}

@Composable
private fun IconBtn(
    icon: ImageVector,
    desc: String,
    box: Int = 44,
    iconSize: Int = 22,
    enabled: Boolean = true,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(box.dp)
            .then(if (enabled) Modifier.pressable(pressedScale = 0.85f, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = if (enabled) tint else Color(0x4DFFFFFF), modifier = Modifier.size(iconSize.dp))
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
        Box(Modifier.offset(x = x).width(segW).fillMaxSize().clip(RoundedCornerShape(8.dp)).background(Color(0xFF3A3A40)))
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
private fun SubtitleSheet(s: PlayerState, c: PlayerController, close: () -> Unit, openOnline: () -> Unit) {
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
    Group {
        if (com.novaplay.player.subs.OpenSubtitles.enabled) {
            ActionRow("Search Online…") { openOnline() }
            GroupDivider()
        }
        ActionRow("Load Subtitle File…") { c.pickSubtitleFile(); close() }
    }

    Spacer(Modifier.height(20.dp))
    Text("DELAY", style = Type.caption, color = Secondary, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
    Group {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("−", style = Type.title, color = Accent,
                modifier = Modifier.pressable { c.shiftSpuDelay(-250) }.padding(horizontal = 16.dp, vertical = 6.dp))
            Text("${s.spuDelayMs} ms", style = Type.body.copy(fontFeatureSettings = "tnum"), color = Color.White,
                modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Text("+", style = Type.title, color = Accent,
                modifier = Modifier.pressable { c.shiftSpuDelay(250) }.padding(horizontal = 16.dp, vertical = 6.dp))
        }
    }

    Spacer(Modifier.height(20.dp))
    Text("SIZE", style = Type.caption, color = Secondary, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
    Segmented(SUB_SCALES.map { it to "$it%" }, s.subScale) { c.setSubScale(it) }

    Spacer(Modifier.height(20.dp))
    Text("STYLE", style = Type.caption, color = Secondary, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        SUB_COLORS.forEachIndexed { i, (name, rgb) ->
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF000000 or rgb.toLong()))
                    .border(if (i == s.subColor) 2.5.dp else 0.5.dp, if (i == s.subColor) Accent else Color(0x33FFFFFF), CircleShape)
                    .pressable(pressedScale = 0.9f) { c.setSubStyle(i, s.subBackground, s.subBold) },
            ) { if (i == s.subColor) Icon(Icons.Default.Check, name, tint = Color.Black, modifier = Modifier.align(Alignment.Center).size(18.dp)) }
        }
    }
    Spacer(Modifier.height(12.dp))
    Group {
        ToggleRow("Background Box", s.subBackground) { c.setSubStyle(s.subColor, it, s.subBold) }
        GroupDivider()
        ToggleRow("Bold", s.subBold) { c.setSubStyle(s.subColor, s.subBackground, it) }
    }
    Text(
        "Subtitles you load are remembered for that video.",
        style = Type.footnote, color = Secondary, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
    )
}

@Composable
private fun AudioSheet(s: PlayerState, c: PlayerController) {
    SheetTitle("Audio")
    if (s.audioTracks.isNotEmpty()) {
        Group {
            s.audioTracks.forEachIndexed { i, t ->
                Row(
                    Modifier.fillMaxWidth().pressable(pressedScale = 0.98f) { c.selectAudio(t.id) }
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(t.name, style = Type.body, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (t.id == s.audioTrack) Icon(Icons.Default.Check, null, tint = Accent, modifier = Modifier.size(20.dp))
                }
                if (i < s.audioTracks.lastIndex) GroupDivider()
            }
        }
    }

    Spacer(Modifier.height(20.dp))
    Text("VOLUME BOOST", style = Type.caption, color = Secondary, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
    Segmented(listOf(100 to "Off", 125 to "125%", 150 to "150%", 200 to "200%"), s.volumeBoost) { c.setVolumeBoost(it) }

    Spacer(Modifier.height(20.dp))
    Text("BASS BOOST", style = Type.caption, color = Secondary, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
    Segmented(listOf(0 to "Off", 4 to "Low", 8 to "Medium", 12 to "High"), s.bassDb) { c.setBass(it) }

    Spacer(Modifier.height(20.dp))
    Text("DELAY", style = Type.caption, color = Secondary, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
    Stepper("${s.audioDelayMs} ms", onMinus = { c.shiftAudioDelay(-50) }, onPlus = { c.shiftAudioDelay(50) })

    Spacer(Modifier.height(20.dp))
    Text("EQUALIZER", style = Type.caption, color = Secondary, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
    Group {
        (listOf(-1 to "Off") + s.eqPresets.mapIndexed { i, n -> i to n }).forEachIndexed { i, (value, label) ->
            Row(
                Modifier.fillMaxWidth().pressable(pressedScale = 0.98f) { c.setEqPreset(value) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = Type.body, color = Color.White, modifier = Modifier.weight(1f))
                if (value == s.eqPreset) Icon(Icons.Default.Check, null, tint = Accent, modifier = Modifier.size(20.dp))
            }
            if (i < s.eqPresets.size) GroupDivider()
        }
    }
}

@Composable
private fun Stepper(label: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Group {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("−", style = Type.title, color = Accent,
                modifier = Modifier.pressable(onClick = onMinus).padding(horizontal = 16.dp, vertical = 6.dp))
            Text(label, style = Type.body.copy(fontFeatureSettings = "tnum"), color = Color.White,
                modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Text("+", style = Type.title, color = Accent,
                modifier = Modifier.pressable(onClick = onPlus).padding(horizontal = 16.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun CastSheet(s: PlayerState, c: PlayerController, close: () -> Unit) {
    androidx.compose.runtime.DisposableEffect(Unit) {
        c.startCastScan()
        onDispose { if (s.castingTo == null) c.stopCastScan() }
    }
    SheetTitle("Cast")
    Group {
        Row(
            Modifier.fillMaxWidth().pressable(pressedScale = 0.98f) { c.castTo(null); close() }
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("This phone", style = Type.body, color = Color.White, modifier = Modifier.weight(1f))
            if (s.castingTo == null) Icon(Icons.Default.Check, null, tint = Accent, modifier = Modifier.size(20.dp))
        }
        s.renderers.forEach { name ->
            GroupDivider()
            Row(
                Modifier.fillMaxWidth().pressable(pressedScale = 0.98f) { c.castTo(name); close() }
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Cast, null, tint = Secondary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(name, style = Type.body, color = Color.White, modifier = Modifier.weight(1f))
                if (s.castingTo == name) Icon(Icons.Default.Check, null, tint = Accent, modifier = Modifier.size(20.dp))
            }
        }
    }
    Row(Modifier.padding(start = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(14.dp), color = Secondary, strokeWidth = 1.5.dp)
        Spacer(Modifier.width(8.dp))
        Text("Looking for Chromecast devices on this Wi-Fi…", style = Type.footnote, color = Secondary)
    }
}

@Composable
private fun MoreSheet(
    s: PlayerState, c: PlayerController, close: () -> Unit,
    openChapters: () -> Unit, openSleep: () -> Unit, openCast: () -> Unit,
) {
    SheetTitle("Options")
    Group {
        ActionRow("Take Screenshot") { close(); c.takeScreenshot() }
        GroupDivider()
        ActionRow(
            when {
                s.sleepAtEnd -> "Sleep Timer: End of Video"
                s.sleepAt > 0 -> "Sleep Timer: ${((s.sleepAt - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1)} min left"
                else -> "Sleep Timer"
            }
        ) { openSleep() }
        GroupDivider()
        ActionRow(s.castingTo?.let { "Casting to $it" } ?: "Cast to TV") { openCast() }
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
        if (s.playlistSize > 1) {
            GroupDivider()
            ToggleRow("Shuffle", s.shuffle) { c.setShuffle(it) }
        }
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

@Composable
private fun OnlineSubsSheet(s: PlayerState, c: PlayerController, close: () -> Unit) {
    var query by remember { mutableStateOf(c.subtitleQuery()) }
    LaunchedEffect(Unit) { if (!s.onlineSearched) c.searchOnlineSubs(query) }
    val loadedAtOpen = remember { s.onlineLoaded }
    LaunchedEffect(s.onlineLoaded) { if (s.onlineLoaded != loadedAtOpen) close() }
    SheetTitle("Search Subtitles")
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Movie or show name", color = Secondary) },
            shape = RoundedCornerShape(10.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent, cursorColor = Accent,
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
            ),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { c.searchOnlineSubs(query) }),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text("Search", style = Type.headline, color = Accent,
            modifier = Modifier.pressable { c.searchOnlineSubs(query) }.padding(horizontal = 8.dp, vertical = 10.dp))
    }
    Spacer(Modifier.height(14.dp))
    when {
        s.onlineBusy -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), color = Secondary, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Working…", style = Type.body, color = Secondary)
        }
        s.onlineError != null -> Text(s.onlineError.orEmpty(), style = Type.body, color = Color(0xFFFF6961), modifier = Modifier.padding(16.dp))
        s.onlineSearched && s.onlineSubs.isEmpty() -> Text("No subtitles found. Try a shorter name.", style = Type.body, color = Secondary, modifier = Modifier.padding(16.dp))
    }
    if (s.onlineSubs.isNotEmpty()) {
        Group {
            s.onlineSubs.take(40).forEachIndexed { i, sub ->
                Row(
                    Modifier.fillMaxWidth().pressable(pressedScale = 0.98f) { c.downloadOnlineSub(sub) }
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(sub.name, style = Type.body, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            buildString {
                                append(sub.language.uppercase())
                                append(" · ${sub.downloads} downloads")
                                if (sub.exact) append(" · Matches this file")
                            },
                            style = Type.footnote, color = if (sub.exact) Color(0xFF30D158) else Secondary,
                        )
                    }
                }
                if (i < minOf(s.onlineSubs.size, 40) - 1) GroupDivider()
            }
        }
    }
    Text(
        "Subtitles by OpenSubtitles.com",
        style = Type.footnote, color = Secondary, modifier = Modifier.padding(start = 16.dp, top = 10.dp),
    )
}
