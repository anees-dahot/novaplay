package com.adfree.player.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.adfree.player.data.formatTime
import kotlinx.coroutines.delay
import kotlin.math.abs

private enum class PlayerDialog { SPEED, AUDIO, SUBTITLES, ASPECT, CHAPTERS }
private enum class GestureMode { NONE, SEEK, BRIGHTNESS, VOLUME, PINCH }

private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f)
private val SUB_SCALES = listOf(75, 100, 125, 150, 200)

@Composable
fun PlayerScreen(s: PlayerState, c: PlayerController) {
    var dialog by remember { mutableStateOf<PlayerDialog?>(null) }

    // Auto-hide controls after 3.5s of no interaction while playing.
    LaunchedEffect(s.controlsVisible, s.playing, s.interaction, dialog) {
        if (s.controlsVisible && s.playing && dialog == null) {
            delay(3500)
            s.controlsVisible = false
        }
    }
    // Gesture feedback bubble disappears shortly after the last update.
    LaunchedEffect(s.gestureTick) {
        delay(800)
        s.gestureText = null
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { c.createVideoView(it) }, modifier = Modifier.fillMaxSize())

        if (!s.started) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(48.dp), color = Color.White)
        }

        // Night mode: dim + warm tint over the video.
        if (s.nightMode) Box(Modifier.fillMaxSize().background(Color(0x66301600)))

        if (s.inPip) return@Box

        Box(Modifier.fillMaxSize().playerGestures(s, c))

        (if (s.holdingFast) "2x ▶▶" else s.gestureText)?.let {
            Text(
                it,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0x99000000), RoundedCornerShape(10.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }

        if (s.locked) {
            if (s.controlsVisible) {
                IconButton(
                    onClick = { s.locked = false; s.bump() },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .windowInsetsPadding(WindowInsets.displayCutout)
                        .padding(24.dp)
                        .background(Color(0x99000000), CircleShape),
                ) { Icon(Icons.Default.Lock, "Unlock", tint = Color.White) }
            }
        } else {
            AnimatedVisibility(
                visible = s.controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                Controls(s, c, onDialog = { dialog = it })
            }
        }
    }

    when (dialog) {
        PlayerDialog.SPEED -> ChoiceDialog(
            title = "Playback speed",
            options = SPEEDS.map { it to "${formatRate(it)}x" },
            selected = s.rate,
            onSelect = { c.setRate(it); dialog = null },
            onDismiss = { dialog = null },
        )
        PlayerDialog.AUDIO -> ChoiceDialog(
            title = "Audio track",
            options = s.audioTracks.map { it.id to it.name },
            selected = s.audioTrack,
            onSelect = { c.selectAudio(it); dialog = null },
            onDismiss = { dialog = null },
        )
        PlayerDialog.SUBTITLES -> SubtitleDialog(s, c, onDismiss = { dialog = null })
        PlayerDialog.ASPECT -> ChoiceDialog(
            title = "Aspect ratio",
            options = VideoScale.entries.map { it to it.label },
            selected = s.scale,
            onSelect = { c.setScale(it); dialog = null },
            onDismiss = { dialog = null },
        )
        PlayerDialog.CHAPTERS -> ChoiceDialog(
            title = "Chapters",
            options = s.chapters.map { it.timeMs to "${formatTime(it.timeMs)}  ${it.name}" },
            selected = s.chapters.lastOrNull { it.timeMs <= s.time }?.timeMs ?: -1L,
            onSelect = { c.seekTo(it); dialog = null },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

private fun formatRate(r: Float) = if (r % 1f == 0f) r.toInt().toString() else r.toString()

/** Tap = show/hide, double-tap sides = ±10s, swipe left/right half = brightness/volume,
 *  horizontal swipe = seek, pinch = crop/fit. */
private fun Modifier.playerGestures(s: PlayerState, c: PlayerController): Modifier = this
    .pointerInput(s.locked) {
        detectTapGestures(
            onPress = {
                tryAwaitRelease()
                if (s.holdingFast) c.stopHoldFast()
            },
            onLongPress = {
                if (!s.locked) {
                    c.startHoldFast()
                    if (s.holdingFast) s.flash("2x ▶▶")
                }
            },
            onTap = {
                s.controlsVisible = !s.controlsVisible
                s.bump()
            },
            onDoubleTap = { pos ->
                if (s.locked) return@detectTapGestures
                if (pos.x < size.width / 2) {
                    c.seekBy(-10_000); s.flash("« 10s")
                } else {
                    c.seekBy(10_000); s.flash("10s »")
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
                        val sign = if (offset >= 0) "+" else "-"
                        s.flash("${formatTime(seekTarget)}  [$sign${abs(offset) / 1000}s]")
                    }
                    GestureMode.BRIGHTNESS ->
                        s.flash("☀ ${c.adjustBrightness(-delta.y / size.height)}%")
                    GestureMode.VOLUME ->
                        s.flash("🔊 ${c.adjustVolume(-delta.y / size.height)}%")
                    else -> Unit
                }
                if (mode != GestureMode.NONE) change.consume()
            }

            when (mode) {
                GestureMode.SEEK -> {
                    c.seekTo(seekTarget)
                    s.seeking = false
                }
                GestureMode.PINCH -> when {
                    endDist > startDist * 1.15f -> c.setScale(VideoScale.CROP)
                    endDist < startDist * 0.87f -> c.setScale(VideoScale.FIT)
                }
                else -> Unit
            }
            c.endGesture()
        }
    }

@Composable
private fun Controls(s: PlayerState, c: PlayerController, onDialog: (PlayerDialog) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        // Top bar
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = c::back) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
            Text(
                c.videoTitle,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onDialog(PlayerDialog.SPEED) }) {
                Text("${formatRate(s.rate)}x", color = Color.White)
            }
            IconButton(onClick = { onDialog(PlayerDialog.AUDIO) }) {
                Icon(Icons.Default.Audiotrack, "Audio track", tint = Color.White)
            }
            IconButton(onClick = { onDialog(PlayerDialog.SUBTITLES) }) {
                Icon(Icons.Default.Subtitles, "Subtitles", tint = Color.White)
            }
            IconButton(onClick = { onDialog(PlayerDialog.ASPECT) }) {
                Icon(Icons.Default.AspectRatio, "Aspect ratio", tint = Color.White)
            }
            IconButton(onClick = c::enterPip) {
                Icon(Icons.Default.PictureInPictureAlt, "Picture in picture", tint = Color.White)
            }
            MoreMenu(s, c, onDialog)
        }

        // Center: rewind / play-pause / forward
        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(onClick = { c.playPrev(); s.bump() }, size = 44) {
                Icon(Icons.Default.SkipPrevious, "Previous video", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            RoundButton(onClick = { c.seekBy(-10_000); s.bump() }, size = 52) {
                Icon(Icons.Default.Replay10, "Back 10 seconds", tint = Color.White, modifier = Modifier.size(32.dp))
            }
            RoundButton(onClick = c::togglePlay, size = 72) {
                Icon(
                    if (s.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (s.playing) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(48.dp),
                )
            }
            RoundButton(onClick = { c.seekBy(10_000); s.bump() }, size = 52) {
                Icon(Icons.Default.Forward10, "Forward 10 seconds", tint = Color.White, modifier = Modifier.size(32.dp))
            }
            RoundButton(
                onClick = { if (s.hasNext || s.loopMode == LoopMode.ALL) { c.playNext(); s.bump() } },
                size = 44,
            ) {
                Icon(
                    Icons.Default.SkipNext, "Next video",
                    tint = if (s.hasNext || s.loopMode == LoopMode.ALL) Color.White else Color.Gray,
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        // Bottom: seek bar + lock / rotate
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(s.time), color = Color.White, fontSize = 13.sp)
                SeekBar(s, c, Modifier.weight(1f).padding(horizontal = 12.dp))
                Text(formatTime(s.length), color = Color.White, fontSize = 13.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { s.locked = true; s.bump() }) {
                    Icon(Icons.Default.LockOpen, "Lock screen", tint = Color.White)
                }
                if (s.abStart >= 0) {
                    Text(
                        if (s.abEnd >= 0) "A–B ${formatTime(s.abStart)}–${formatTime(s.abEnd)}" else "A ${formatTime(s.abStart)}",
                        color = MaterialTheme.colorScheme.primary, fontSize = 12.sp,
                    )
                }
                if (s.chapters.isNotEmpty()) {
                    TextButton(onClick = { onDialog(PlayerDialog.CHAPTERS) }) {
                        Text("Chapters (${s.chapters.size})", color = Color.White, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { c.toggleRotation(); s.bump() }) {
                    Icon(Icons.Default.ScreenRotation, "Rotate", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun SeekBar(s: PlayerState, c: PlayerController, modifier: Modifier) {
    val max = s.length.toFloat().coerceAtLeast(1f)
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    // Load a preview frame shortly after the drag position settles.
    LaunchedEffect(s.seeking, s.time / 2000) {
        if (!s.seeking) { preview = null; return@LaunchedEffect }
        delay(80)
        preview = c.previewFrame(s.time)?.asImageBitmap()
    }
    BoxWithConstraints(modifier) {
        val trackPad = 10.dp // slider thumb radius: track starts/ends this far in
        val fraction = (s.time / max).coerceIn(0f, 1f)
        if (s.seeking) {
            val previewW = 160.dp
            val x = (maxWidth - trackPad * 2) * fraction + trackPad - previewW / 2
            Column(
                Modifier
                    .offset(x = x.coerceIn(0.dp, (maxWidth - previewW).coerceAtLeast(0.dp)), y = (-104).dp)
                    .width(previewW),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(previewW, 90.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black),
                ) {
                    preview?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
                Text(formatTime(s.time), color = Color.White, fontSize = 12.sp)
            }
        }
        Slider(
                    value = s.time.toFloat().coerceIn(0f, s.length.toFloat().coerceAtLeast(1f)),
                    onValueChange = {
                        s.seeking = true
                        s.time = it.toLong()
                        s.bump()
                    },
                    onValueChangeFinished = {
                        c.seekTo(s.time)
                        s.seeking = false
                    },
                    valueRange = 0f..s.length.toFloat().coerceAtLeast(1f),
                    colors = SliderDefaults.colors(inactiveTrackColor = Color(0x55FFFFFF)),
                    modifier = Modifier.fillMaxWidth(),
                )
        // Chapter markers: tap a chapter in the Chapters list to jump.
        if (s.chapters.size > 1 && s.length > 0) {
            Canvas(Modifier.matchParentSize().padding(horizontal = trackPad)) {
                s.chapters.drop(1).forEach { ch ->
                    val cx = size.width * (ch.timeMs / max)
                    drawRect(Color.White, topLeft = Offset(cx - 1.5f, size.height / 2 - 6f), size = Size(3f, 12f))
                }
            }
        }
    }
}

@Composable
private fun MoreMenu(s: PlayerState, c: PlayerController, onDialog: (PlayerDialog) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true; s.bump() }) {
            Icon(Icons.Default.MoreVert, "More", tint = Color.White)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuItem("Screenshot") { open = false; c.takeScreenshot() }
            MenuItem(
                when {
                    s.abStart < 0 -> "A–B repeat: set A"
                    s.abEnd < 0 -> "A–B repeat: set B"
                    else -> "A–B repeat: off"
                }
            ) { open = false; c.cycleAbRepeat() }
            MenuItem(s.loopMode.label + "  ↻") {
                c.setLoopMode(LoopMode.entries[(s.loopMode.ordinal + 1) % LoopMode.entries.size])
            }
            MenuToggle("Auto-play next", s.autoNext) { c.setAutoNext(it) }
            MenuToggle("Background play", s.backgroundPlay) { c.setBackgroundPlay(it) }
            MenuToggle("Night mode", s.nightMode) { c.setNightMode(it) }
            MenuToggle("Hardware decoder", s.hwDecoder) { open = false; c.setHwDecoder(it) }
            if (s.chapters.isNotEmpty()) MenuItem("Chapters") { open = false; onDialog(PlayerDialog.CHAPTERS) }
        }
    }
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, onClick = onClick)
}

@Composable
private fun MenuToggle(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        trailingIcon = { Switch(checked = checked, onCheckedChange = null) },
        onClick = { onChange(!checked) },
    )
}

@Composable
private fun RoundButton(onClick: () -> Unit, size: Int, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(size.dp)
            .background(Color(0x66000000), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (options.isEmpty()) Text("None available")
                options.forEach { (value, label) ->
                    OptionRow(label, value == selected) { onSelect(value) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SubtitleDialog(s: PlayerState, c: PlayerController, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Subtitles") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (s.spuTracks.isEmpty()) Text("No subtitle tracks in this video")
                s.spuTracks.forEach { t ->
                    OptionRow(t.name, t.id == s.spuTrack) { c.selectSpu(t.id) }
                }
                Spacer(Modifier.width(1.dp))
                OutlinedButton(
                    onClick = { c.pickSubtitleFile(); onDismiss() },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                ) { Text("Load subtitle file (.srt, .ass…)") }

                HorizontalDivider()
                Text("Delay", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { c.shiftSpuDelay(-250) }) { Text("−250ms") }
                    Text("${s.spuDelayMs} ms", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { c.shiftSpuDelay(250) }) { Text("+250ms") }
                }

                Text("Size", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SUB_SCALES.forEach { p ->
                        FilterChip(
                            selected = s.subScale == p,
                            onClick = { c.setSubScale(p) },
                            label = { Text("$p%") },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
