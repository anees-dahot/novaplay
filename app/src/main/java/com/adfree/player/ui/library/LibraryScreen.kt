package com.adfree.player.ui.library

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.adfree.player.Accent
import com.adfree.player.Navy
import com.adfree.player.NavyHigh
import com.adfree.player.NavySurface
import com.adfree.player.data.ResumeStore
import com.adfree.player.data.Video
import com.adfree.player.data.VideoRepository
import com.adfree.player.data.formatSize
import com.adfree.player.data.formatTime
import com.adfree.player.ui.Motion
import com.adfree.player.ui.Type
import com.adfree.player.ui.pressable
import java.text.DateFormat
import java.util.Date

enum class SortBy(val label: String) { DATE("Newest"), NAME("Name"), SIZE("Size") }

private val Secondary = Color(0xFF8E9BB5)
private val Separator = Color(0x1FFFFFFF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpen: (videos: List<Video>, index: Int) -> Unit) {
    val context = LocalContext.current
    val permission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    fun hasPermission() =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(hasPermission()) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    var videos by remember { mutableStateOf<List<Video>?>(null) }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(SortBy.DATE) }
    var sortMenu by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }
    val resume = remember { ResumeStore(context) }

    // Re-scan every time the screen comes back (new downloads, updated resume bars).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = hasPermission()
        refreshTick++
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(permission) }
    LaunchedEffect(granted, refreshTick) {
        if (granted) videos = VideoRepository.load(context)
    }
    BackHandler(enabled = folder != null) { folder = null }

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = Navy,
        topBar = {
            LargeTopAppBar(
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = Navy,
                    scrolledContainerColor = NavySurface,
                ),
                title = {
                    Text(
                        folder ?: "Library",
                        style = if (scroll.state.collapsedFraction > 0.5f) Type.headline else Type.largeTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (folder != null) {
                        Row(
                            Modifier.padding(start = 8.dp).pressable { folder = null },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBackIos, "Back", tint = Accent, modifier = Modifier.size(18.dp))
                            Text("Library", color = Accent, style = Type.body)
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, "Sort", tint = Accent)
                        }
                        DropdownMenu(
                            expanded = sortMenu,
                            onDismissRequest = { sortMenu = false },
                            containerColor = NavyHigh,
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            SortBy.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label, style = Type.body) },
                                    trailingIcon = {
                                        if (option == sort) Icon(Icons.Default.Check, null, tint = Accent)
                                    },
                                    onClick = { sort = option; sortMenu = false },
                                )
                            }
                        }
                    }
                },
            )
        }
    ) { padding ->
        val list = videos
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                !granted -> EmptyState(
                    title = "See your videos",
                    message = "NovaPlay needs access to your videos to show them here. Nothing leaves your phone.",
                    action = "Allow Access",
                    onAction = { launcher.launch(permission) },
                )

                list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Accent)

                list.isEmpty() -> EmptyState(
                    title = "No Videos",
                    message = "Videos you download or record will show up here.",
                )

                // Folder pushes in from the right and goes back out the same way (spatial consistency).
                else -> AnimatedContent(
                    targetState = folder,
                    transitionSpec = {
                        if (targetState != null) {
                            slideInHorizontally(Motion.smooth()) { it } togetherWith
                                (slideOutHorizontally(Motion.smooth()) { -it / 4 } + fadeOut(Motion.smooth()))
                        } else {
                            (slideInHorizontally(Motion.smooth()) { -it / 4 } + fadeIn(Motion.smooth())) togetherWith
                                slideOutHorizontally(Motion.smooth()) { it }
                        }
                    },
                    label = "folder",
                ) { current ->
                    if (current == null) {
                        val folders = list.groupBy { it.folder }.toList().let { groups ->
                            when (sort) {
                                SortBy.NAME -> groups.sortedBy { it.first.lowercase() }
                                SortBy.DATE -> groups.sortedByDescending { g -> g.second.maxOf { it.dateAdded } }
                                SortBy.SIZE -> groups.sortedByDescending { g -> g.second.sumOf { it.size } }
                            }
                        }
                        GroupedList(folders.map { it.first }) { i ->
                            val (name, vids) = folders[i]
                            FolderRow(name, vids.size, showDivider = i < folders.lastIndex) { folder = name }
                        }
                    } else {
                        val inFolder = list.filter { it.folder == current }.let { v ->
                            when (sort) {
                                SortBy.NAME -> v.sortedBy { it.name.lowercase() }
                                SortBy.DATE -> v.sortedByDescending { it.dateAdded }
                                SortBy.SIZE -> v.sortedByDescending { it.size }
                            }
                        }
                        GroupedList(inFolder.map { it.id }) { i ->
                            val video = inFolder[i]
                            val progress = remember(video.id, refreshTick) {
                                val pos = resume.get(video.uri.toString())
                                if (pos > 0 && video.durationMs > 0) pos.toFloat() / video.durationMs else 0f
                            }
                            VideoRow(video, progress, showDivider = i < inFolder.lastIndex) { onOpen(inFolder, i) }
                        }
                    }
                }
            }
        }
    }
}

/** iOS "inset grouped" list: one rounded card, rows separated by inset hairlines. */
@Composable
private fun GroupedList(keys: List<Any>, row: @Composable (Int) -> Unit) {
    val count = keys.size
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
    ) {
        items(count, key = { keys[it] }) { i ->
            val shape = when {
                count == 1 -> RoundedCornerShape(14.dp)
                i == 0 -> RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
                i == count - 1 -> RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
                else -> RoundedCornerShape(0.dp)
            }
            Box(Modifier.animateItem().clip(shape).background(NavySurface)) { row(i) }
        }
    }
}

@Composable
private fun FolderRow(name: String, count: Int, showDivider: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().pressable(pressedScale = 0.98f, onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF5AA2FF), Color(0xFF2F6FEB)))),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Folder, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$count video${if (count == 1) "" else "s"}", style = Type.footnote, color = Secondary)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color(0x55FFFFFF))
        }
        if (showDivider) HorizontalDivider(Modifier.padding(start = 64.dp), thickness = 0.5.dp, color = Separator)
    }
}

@Composable
private fun VideoRow(video: Video, progress: Float, showDivider: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().pressable(pressedScale = 0.98f, onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(width = 120.dp, height = 68.dp).clip(RoundedCornerShape(10.dp))) {
                VideoThumb(video)
                Text(
                    formatTime(video.durationMs),
                    color = Color.White,
                    style = Type.caption,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(5.dp)
                        .background(Color(0x99000000), RoundedCornerShape(5.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
                if (progress > 0f) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x66000000))) {
                        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(Accent))
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(video.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = Type.subhead)
                Text(
                    "${formatSize(video.size)} · ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(video.dateAdded * 1000))}",
                    style = Type.footnote,
                    color = Secondary,
                )
            }
        }
        if (showDivider) HorizontalDivider(Modifier.padding(start = 144.dp), thickness = 0.5.dp, color = Separator)
    }
}

@Composable
private fun VideoThumb(video: Video) {
    val context = LocalContext.current
    val bitmap by produceState(VideoRepository.cachedThumb(video.id), video.id) {
        if (value == null) value = VideoRepository.thumbnail(context, video)
    }
    // Thumbnails fade in instead of popping.
    val alpha by animateFloatAsState(if (bitmap != null) 1f else 0f, Motion.smooth(), label = "thumb")
    Box(Modifier.fillMaxSize().background(NavyHigh)) {
        bitmap?.let {
            Image(
                it.asImageBitmap(), null,
                contentScale = ContentScale.Crop,
                alpha = alpha,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun EmptyState(title: String, message: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.VideoLibrary, null, tint = Secondary, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = Type.title, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(message, style = Type.subhead, color = Secondary, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(24.dp))
            Text(
                action,
                style = Type.headline,
                color = Color.White,
                modifier = Modifier
                    .pressable(onClick = onAction)
                    .background(Accent, RoundedCornerShape(50))
                    .padding(horizontal = 28.dp, vertical = 12.dp),
            )
        }
    }
}
